package com.slipstream.wheel.link

import com.slipstream.wheel.protocol.Slp
import com.slipstream.wheel.protocol.StatusDecoder
import com.slipstream.wheel.protocol.StatusPacket
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.PortUnreachableException
import java.net.StandardSocketOptions
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.util.concurrent.locks.LockSupport

/**
 * Wi-Fi path: one UDP socket connected to the hub, TOS 0xB8 (DSCP EF, the WMM voice queue).
 *
 * The socket is a DatagramChannel (its socket() is the connected DatagramSocket) in
 * non-blocking mode, so [send] can never stall the sender thread when the radio is backed
 * up: the datagram is dropped and the next one, 2 ms later, carries the full state anyway.
 * The send buffer is kept small for the same reason: stale packets must not queue.
 * A receive thread waits on a Selector and hands each authenticated STATUS to [sink].
 *
 * The socket is rebuilt, on the receive thread, whenever it can no longer reach the hub:
 * - [retarget] gave a new hub address (a beacon from the paired hub at another IP),
 * - [requestReopen] was called (the network the process is bound to changed),
 * - sends kept failing (Wi-Fi dropped, or DHCP gave the phone a new address, which leaves a
 *   connected UDP socket holding a source address that no longer exists),
 * - nothing authenticated came back for [staleNs] (a route that silently went elsewhere).
 * A hub that is simply not running costs one socket rebuild every [staleNs], nothing more.
 */
class UdpTransport(
    override val slot: Int,
    host: InetAddress?,
    port: Int,
    key: ByteArray,
    private val stats: LinkStats,
    private val sink: StatusSink,
    private val staleNs: Long = STALE_NS,
) : Transport {
    override val label: String = "Wi-Fi"

    /** Where INPUT goes. Null until the hub address is known. */
    @Volatile var target: InetSocketAddress? = host?.let { InetSocketAddress(it, port) }
        private set

    @Volatile private var channel: DatagramChannel? = null
    @Volatile private var selector: Selector? = null
    @Volatile private var running = false
    @Volatile private var reopen = false
    @Volatile private var generation = 0
    @Volatile private var thread: Thread? = null

    /** Receive thread only: when the last authenticated datagram arrived. */
    private var lastRxNs = 0L

    /** Sender thread only. */
    private var consecutiveErrors = 0

    /** Times the socket was built, for diagnostics and tests. */
    @Volatile var opens = 0
        private set

    /** Sized for the largest packet: INPUT (52) in wheel mode, PAD (76) in controller mode. */
    private val tx: ByteBuffer = ByteBuffer.allocateDirect(Slp.MAX_PACKET_LEN)
    private val rxArray = ByteArray(256)
    private val rx: ByteBuffer = ByteBuffer.wrap(rxArray)
    private val decoder = StatusDecoder(key)
    private val status = StatusPacket()

    override fun start() {
        if (running) return
        running = true
        val gen = ++generation
        stats.transports[slot].enabled = true
        thread = Thread({ receiveLoop(gen) }, "slip-udp-rx").also {
            it.isDaemon = true
            it.start()
        }
    }

    override fun stop() {
        running = false
        generation++
        selector?.wakeup()
        closeQuietly(channel)
        thread?.let {
            LockSupport.unpark(it)
            it.join(500)
        }
        thread = null
        stats.transports[slot].open = false
        stats.transports[slot].enabled = false
    }

    /** Points the path at another hub address; the socket is rebuilt at once. */
    fun retarget(host: InetAddress, port: Int) {
        val next = InetSocketAddress(host, port)
        if (next == target) return
        target = next
        requestReopen()
    }

    /** Rebuilds the socket soon. Safe from any thread; never blocks. */
    fun requestReopen() {
        reopen = true
        selector?.wakeup()
        thread?.let { LockSupport.unpark(it) }
    }

    override fun canSend(): Boolean = channel != null

    override fun send(packet: ByteArray, len: Int) {
        val ch = channel ?: return
        val t = stats.transports[slot]
        tx.clear()
        tx.put(packet, 0, len)
        tx.flip()
        try {
            if (ch.write(tx) == 0) {
                t.sendDrops++
            } else {
                t.sent++
                consecutiveErrors = 0
            }
        } catch (e: PortUnreachableException) {
            // ICMP from a hub host whose port is closed (hub restarting). The socket is fine.
            t.errors++
        } catch (e: IOException) {
            // No route, no network, or a source address that is gone: rebuild after a burst.
            t.errors++
            if (++consecutiveErrors >= ERRORS_BEFORE_REOPEN) {
                consecutiveErrors = 0
                requestReopen()
            }
        }
    }

    private fun alive(gen: Int): Boolean = running && generation == gen

    private fun receiveLoop(gen: Int) {
        ThreadBoost.setCurrent(-8)
        val t = stats.transports[slot]
        while (alive(gen)) {
            val dest = target
            if (dest == null) {
                // No hub address yet: wait for retarget().
                LockSupport.parkNanos(NO_TARGET_WAIT_NS)
                continue
            }
            reopen = false
            var failed = false
            var ch: DatagramChannel? = null
            var sel: Selector? = null
            try {
                ch = DatagramChannel.open()
                setTos(ch)
                try {
                    ch.socket().sendBufferSize = SEND_BUFFER_BYTES
                } catch (e: IOException) {
                    // Best effort: the default buffer only means more queueing under a stall.
                }
                ch.connect(dest)
                ch.configureBlocking(false)
                sel = Selector.open()
                ch.register(sel, SelectionKey.OP_READ)
                selector = sel
                lastRxNs = System.nanoTime()
                opens++
                channel = ch
                t.open = true
                while (alive(gen) && ch.isOpen && !reopen && dest == target) {
                    if (sel.select(SELECT_TIMEOUT_MS) > 0) {
                        sel.selectedKeys().clear()
                        drain(ch, t)
                    }
                    if (System.nanoTime() - lastRxNs > staleNs) break // silent path: rebuild
                }
            } catch (e: IOException) {
                t.errors++
                failed = true
            } catch (e: RuntimeException) {
                // Never let the path die silently: count it and rebuild.
                t.errors++
                failed = true
            } finally {
                channel = null
                selector = null
                t.open = false
                closeQuietly(ch)
                try {
                    sel?.close()
                } catch (e: IOException) {
                    // ignore
                }
            }
            if (alive(gen)) LockSupport.parkNanos(if (failed) REOPEN_DELAY_NS else QUICK_REOPEN_NS)
        }
    }

    private fun setTos(ch: DatagramChannel) {
        try {
            ch.setOption(StandardSocketOptions.IP_TOS, Slp.TOS_EF)
        } catch (e: Exception) {
            try {
                ch.socket().trafficClass = Slp.TOS_EF
            } catch (e2: Exception) {
                // DSCP is a hint to the radio. Without it the path still works.
            }
        }
    }

    private fun drain(ch: DatagramChannel, t: LinkStats.Transport) {
        while (true) {
            rx.clear()
            val n = try {
                ch.read(rx)
            } catch (e: PortUnreachableException) {
                return
            }
            if (n <= 0) return
            if (!decoder.decode(rxArray, 0, n, status)) continue
            val now = System.nanoTime()
            lastRxNs = now
            try {
                sink.onStatus(slot, status, now)
            } catch (e: RuntimeException) {
                t.errors++
            }
        }
    }

    private fun closeQuietly(ch: DatagramChannel?) {
        try {
            ch?.close()
        } catch (e: IOException) {
            // ignore
        }
    }

    companion object {
        /** About 16 datagrams of kernel accounting: enough for bursts, too small to queue lag. */
        private const val SEND_BUFFER_BYTES = 8 * 1024
        private const val SELECT_TIMEOUT_MS = 250L
        private const val REOPEN_DELAY_NS = 500_000_000L
        private const val QUICK_REOPEN_NS = 20_000_000L
        private const val NO_TARGET_WAIT_NS = 250_000_000L

        /** 100 ms of failed sends at 500 Hz. */
        const val ERRORS_BEFORE_REOPEN = 50

        /** No authenticated STATUS for this long: the socket is rebuilt. STATUS runs at 20 Hz. */
        const val STALE_NS = 3_000_000_000L
    }
}
