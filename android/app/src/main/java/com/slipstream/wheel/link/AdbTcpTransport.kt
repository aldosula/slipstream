package com.slipstream.wheel.link

import com.slipstream.wheel.protocol.FrameAssembler
import com.slipstream.wheel.protocol.FrameSink
import com.slipstream.wheel.protocol.Framing
import com.slipstream.wheel.protocol.Slp
import com.slipstream.wheel.protocol.StatusDecoder
import com.slipstream.wheel.protocol.StatusPacket
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

/**
 * USB path: TCP to 127.0.0.1:47802 on the phone, which `adb reverse tcp:47802 tcp:47802`
 * tunnels to the hub over the cable. Framed per PROTOCOL.md section 8, TCP_NODELAY.
 *
 * A connect is attempted every second while USB mode is on. Writes are non-blocking: if the
 * tunnel backs up, the unsent tail of the frame is finished first and newer packets are
 * dropped meanwhile (each packet is the full state, so nothing is lost but staleness). A
 * frame stuck for [STALL_NS] closes the connection so it can be rebuilt.
 */
class AdbTcpTransport(
    override val slot: Int,
    private val port: Int,
    key: ByteArray,
    private val stats: LinkStats,
    private val sink: StatusSink,
    private val address: InetAddress = LOOPBACK_V4,
) : Transport {
    override val label: String = "USB"

    private val channel = AtomicReference<SocketChannel?>(null)
    @Volatile private var selector: Selector? = null
    @Volatile private var running = false
    @Volatile private var generation = 0
    private var thread: Thread? = null

    /** Connections made, for diagnostics and tests. */
    @Volatile var connects = 0
        private set

    // Sender thread only.
    private val tx: ByteBuffer =
        ByteBuffer.allocateDirect(Framing.HEADER_LEN + Slp.INPUT_LEN).order(ByteOrder.LITTLE_ENDIAN)
    private var pendingOn: SocketChannel? = null
    private var pendingSinceNs = 0L

    // Receive thread only.
    private val rxArray = ByteArray(1024)
    private val rx: ByteBuffer = ByteBuffer.wrap(rxArray)
    private val assembler = FrameAssembler(Slp.STATUS_LEN)
    private val decoder = StatusDecoder(key)
    private val status = StatusPacket()
    private val frameSink = FrameSink { frame, len ->
        if (decoder.decode(frame, 0, len, status)) {
            try {
                sink.onStatus(slot, status, System.nanoTime())
            } catch (e: RuntimeException) {
                stats.transports[slot].errors++
            }
        }
    }

    override fun start() {
        if (running) return
        running = true
        val gen = ++generation
        stats.transports[slot].enabled = true
        thread = Thread({ connectLoop(gen) }, "slip-usb").also {
            it.isDaemon = true
            it.start()
        }
    }

    override fun stop() {
        running = false
        generation++
        selector?.wakeup()
        closeQuietly(channel.get())
        thread?.let {
            LockSupport.unpark(it)
            it.join(1500)
        }
        thread = null
        stats.transports[slot].open = false
        stats.transports[slot].enabled = false
    }

    override fun canSend(): Boolean = channel.get() != null

    override fun send(packet: ByteArray, len: Int) {
        val ch = channel.get() ?: return
        val t = stats.transports[slot]
        try {
            if (pendingOn != null) {
                if (pendingOn === ch) {
                    ch.write(tx)
                    if (tx.hasRemaining()) {
                        t.sendDrops++
                        if (System.nanoTime() - pendingSinceNs > STALL_NS) {
                            pendingOn = null
                            closeQuietly(ch)
                            channel.compareAndSet(ch, null)
                        }
                        return
                    }
                }
                pendingOn = null
            }
            tx.clear()
            tx.putShort(len.toShort())
            tx.put(packet, 0, len)
            tx.flip()
            ch.write(tx)
            if (tx.hasRemaining()) {
                pendingOn = ch
                pendingSinceNs = System.nanoTime()
            }
            t.sent++
        } catch (e: IOException) {
            t.errors++
            pendingOn = null
            closeQuietly(ch)
            // Stop writing to the dead socket now instead of throwing on every packet until the
            // receive thread notices; it clears the field anyway and reconnects.
            channel.compareAndSet(ch, null)
        }
    }

    private fun alive(gen: Int): Boolean = running && generation == gen

    private fun connectLoop(gen: Int) {
        ThreadBoost.setCurrent(-8)
        val t = stats.transports[slot]
        while (alive(gen)) {
            val attemptNs = System.nanoTime()
            var ch: SocketChannel? = null
            var sel: Selector? = null
            try {
                ch = SocketChannel.open()
                val s = ch.socket()
                s.tcpNoDelay = true
                s.sendBufferSize = SEND_BUFFER_BYTES
                s.connect(InetSocketAddress(address, port), CONNECT_TIMEOUT_MS)
                ch.configureBlocking(false)
                sel = Selector.open()
                ch.register(sel, SelectionKey.OP_READ)
                assembler.reset()
                selector = sel
                connects++
                channel.set(ch)
                t.open = true
                readLoop(ch, sel, gen)
            } catch (e: IOException) {
                // Nothing listening (no adb reverse, hub not running): retry in a second.
            } catch (e: RuntimeException) {
                // Never let the path die silently: count it and reconnect.
                t.errors++
            } finally {
                channel.set(null)
                selector = null
                t.open = false
                closeQuietly(ch)
                try {
                    sel?.close()
                } catch (e: IOException) {
                    // ignore
                }
            }
            val wait = RETRY_NS - (System.nanoTime() - attemptNs)
            if (alive(gen) && wait > 0) LockSupport.parkNanos(wait)
        }
    }

    private fun readLoop(ch: SocketChannel, sel: Selector, gen: Int) {
        while (alive(gen) && ch.isOpen) {
            if (sel.select(SELECT_TIMEOUT_MS) == 0) continue
            sel.selectedKeys().clear()
            while (true) {
                rx.clear()
                val n = ch.read(rx)
                if (n < 0) return // EOF: the hub or the adb tunnel closed
                if (n == 0) break
                if (!assembler.feed(rxArray, 0, n, frameSink)) return // bad frame length
            }
        }
    }

    private fun closeQuietly(ch: SocketChannel?) {
        try {
            ch?.close()
        } catch (e: IOException) {
            // ignore
        }
    }

    companion object {
        /**
         * 127.0.0.1, as PROTOCOL.md section 8 says. Not InetAddress.getLoopbackAddress(): on
         * Android that is ::1, and adbd before the Android 11 module update listens for
         * `adb reverse` on IPv4 loopback only, so ::1 is refused and USB never connects.
         */
        val LOOPBACK_V4: InetAddress = InetAddress.getByAddress("localhost", byteArrayOf(127, 0, 0, 1))

        private const val CONNECT_TIMEOUT_MS = 800
        private const val RETRY_NS = 1_000_000_000L
        private const val STALL_NS = 2_000_000_000L
        private const val SEND_BUFFER_BYTES = 4 * 1024
        private const val SELECT_TIMEOUT_MS = 500L
    }
}
