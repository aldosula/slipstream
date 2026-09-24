package com.slipstream.wheel.link

import com.slipstream.wheel.input.ControllerState
import com.slipstream.wheel.protocol.InputFrame
import com.slipstream.wheel.protocol.InputPacketWriter
import com.slipstream.wheel.protocol.Slp
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.util.concurrent.locks.LockSupport

/** What one drive session connects to. */
class LinkConfig(
    val key: ByteArray,
    /** Hub address for the Wi-Fi path; null means not known yet (see [LinkEngine.setWifiTarget]). */
    val host: InetAddress?,
    val udpPort: Int = Slp.PORT_UDP,
    val tcpPort: Int = Slp.PORT_TCP,
    val useWifi: Boolean,
    val useUsb: Boolean,
    val rateHz: Int = Slp.RATE_DEFAULT_HZ,
    /** Where the USB path connects: 127.0.0.1 on the phone, tunnelled by adb reverse. */
    val usbAddress: InetAddress = AdbTcpTransport.LOOPBACK_V4,
)

/**
 * The sender. One thread at the highest priority the app can take waits on the state's
 * change signal with a timeout of one idle period (2 ms at 500 Hz). On wake it enforces the
 * 1 ms minimum spacing, snapshots the state, builds exactly one 52 byte packet into a reused
 * buffer and writes the same bytes to every connected transport. Nothing on this path
 * allocates.
 *
 * The Wi-Fi path exists whenever Wi-Fi is enabled, even before the hub address is known, so
 * a beacon seen mid-drive (or a hub that moved to another IP) can be picked up with
 * [setWifiTarget] without leaving the drive screen.
 */
class LinkEngine internal constructor(
    private val state: ControllerState,
    private val config: LinkConfig,
    private val extraSink: StatusSink?,
    /** Test hook: extra paths appended after the real ones. */
    extraTransports: List<Transport>,
) {
    constructor(state: ControllerState, config: LinkConfig, extraSink: StatusSink? = null) :
        this(state, config, extraSink, emptyList())

    val stats = LinkStats(TRANSPORT_SLOTS)

    private val sink = StatusSink { slot, status, rxNs ->
        if (stats.onStatus(slot, status, rxNs)) extraSink?.onStatus(slot, status, rxNs)
    }

    private val udp: UdpTransport? =
        if (config.useWifi) UdpTransport(SLOT_WIFI, config.host, config.udpPort, config.key, stats, sink) else null

    private val usb: AdbTcpTransport? =
        if (config.useUsb) AdbTcpTransport(SLOT_USB, config.tcpPort, config.key, stats, sink, config.usbAddress) else null

    private val transports: Array<Transport> = buildList {
        udp?.let { add(it) }
        usb?.let { add(it) }
        addAll(extraTransports)
    }.toTypedArray()

    private val writer = InputPacketWriter(config.key)
    private val frame = InputFrame()
    private val idleNs: Long = 1_000_000_000L / normalizeRate(config.rateHz)

    @Volatile private var running = false
    @Volatile private var generation = 0
    private var thread: Thread? = null
    private var seq = 0

    /** Random, non-zero, new on every start. */
    @Volatile var epoch = 0
        private set

    /** Niceness the sender obtained, for diagnostics. */
    @Volatile var senderNice = 0
        private set

    @Volatile var packetsBuilt = 0L
        private set

    /** Unexpected exceptions caught on the sender thread (it keeps running). */
    @Volatile var senderFaults = 0L
        private set

    /** System.nanoTime() of the last start. */
    @Volatile var startedNs = 0L
        private set

    val isRunning: Boolean get() = running
    val hasWifi: Boolean get() = udp != null
    val hasUsb: Boolean get() = usb != null

    /** Current Wi-Fi destination, or null while the hub address is unknown. */
    val wifiTarget: InetSocketAddress? get() = udp?.target

    fun start() {
        if (running) return
        // A sender from the previous run that outlived its join must be gone before this one
        // starts: two senders would share the packet buffer and the seq counter.
        thread?.let { if (it.isAlive) it.join(1000) }
        epoch = newEpoch()
        seq = 0
        packetsBuilt = 0
        startedNs = System.nanoTime()
        stats.reset(epoch)
        for (t in transports) t.start()
        val gen = ++generation
        running = true
        thread = Thread({ run(gen) }, "slip-send").also {
            it.priority = Thread.MAX_PRIORITY
            it.start()
        }
    }

    /** Stops sending. The last few packets carry PAUSED so the hub goes neutral at once. */
    fun stop() {
        if (!running) return
        running = false
        generation++
        val t = thread
        if (t != null) {
            LockSupport.unpark(t)
            t.join(500)
            if (!t.isAlive) thread = null
        }
        for (tr in transports) tr.stop()
    }

    /** The network the process routes over changed: rebuild the Wi-Fi socket on the new one. */
    fun onNetworkChanged() {
        udp?.requestReopen()
    }

    /** Sends Wi-Fi INPUT to [host]:[port] from now on (a beacon of the paired hub). */
    fun setWifiTarget(host: InetAddress, port: Int) {
        udp?.retarget(host, port)
    }

    /** How long the Wi-Fi path has gone without a STATUS for this epoch, or since start. */
    fun wifiSilentNs(nowNs: Long): Long {
        val last = stats.transports[SLOT_WIFI].lastStatusNs
        return nowNs - maxOf(last, startedNs)
    }

    private fun alive(gen: Int): Boolean = running && generation == gen

    private fun run(gen: Int) {
        senderNice = ThreadBoost.raiseCurrent()
        val me = Thread.currentThread()
        val signal = state.signal
        signal.attach(me)
        try {
            var last = System.nanoTime() - idleNs
            while (alive(gen)) {
                val now = System.nanoTime()
                val since = now - last
                if (!signal.isPending() && since < idleNs) {
                    LockSupport.parkNanos(this, idleNs - since)
                    continue
                }
                if (since < Slp.MIN_SPACING_NS) {
                    LockSupport.parkNanos(this, Slp.MIN_SPACING_NS - since)
                    continue
                }
                signal.consume()
                last = System.nanoTime()
                sendOne(last, 0)
            }
            if (generation == gen + 1) {
                // Stopped (not superseded): tell the hub to go neutral now.
                repeat(FINAL_PAUSED_PACKETS) {
                    sendOne(System.nanoTime(), Slp.FLAG_PAUSED)
                    LockSupport.parkNanos(Slp.MIN_SPACING_NS)
                }
            }
        } finally {
            signal.detach(me)
        }
    }

    private fun sendOne(nowNs: Long, extraFlags: Int) {
        try {
            var paths = 0
            for (t in transports) if (t.canSend()) paths++
            // No path up yet: build nothing, so the first packet on the wire is seq 1.
            if (paths == 0) return
            state.snapshot(frame)
            seq++ // u32, wraps
            frame.epoch = epoch
            frame.seq = seq
            frame.tUs = (nowNs / 1000L).toInt()
            var flags = frame.flags or extraFlags
            if (paths > 1) flags = flags or Slp.FLAG_MULTIPATH
            frame.flags = flags
            frame.rtt100us = stats.rtt100us
            val packet = writer.write(frame)
            packetsBuilt++
            for (t in transports) {
                if (!t.canSend()) continue
                try {
                    t.send(packet, Slp.INPUT_LEN)
                } catch (e: RuntimeException) {
                    // One misbehaving path must not starve the others.
                    senderFaults++
                }
            }
        } catch (e: RuntimeException) {
            // Never let the sender thread die: the next wake builds a fresh packet.
            senderFaults++
        }
    }

    companion object {
        const val SLOT_WIFI = 0
        const val SLOT_USB = 1
        const val TRANSPORT_SLOTS = 2
        private const val FINAL_PAUSED_PACKETS = 3

        private val random = SecureRandom()

        fun normalizeRate(hz: Int): Int = if (hz in Slp.RATES_HZ) hz else Slp.RATE_DEFAULT_HZ

        fun newEpoch(): Int {
            while (true) {
                val e = random.nextInt()
                if (e != 0) return e
            }
        }
    }
}
