package com.slipstream.wheel.link

import com.slipstream.wheel.input.ControllerState
import com.slipstream.wheel.pad.PadState
import com.slipstream.wheel.protocol.InputFrame
import com.slipstream.wheel.protocol.InputPacketWriter
import com.slipstream.wheel.protocol.PadFrame
import com.slipstream.wheel.protocol.PadPacketWriter
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

/** What the link puts on the wire: INPUT (wheel, 52 bytes) or PAD (controller, 76 bytes). */
enum class PacketSource { INPUT, PAD }

/**
 * The sender. One thread at the highest priority the app can take waits on the state's
 * change signal with a timeout of one idle period (2 ms at 500 Hz). On wake it enforces the
 * 1 ms minimum spacing, snapshots the state, builds exactly one packet into a reused buffer
 * and writes the same bytes to every connected transport. Nothing on this path allocates.
 *
 * The packet source switch picks what is built: INPUT from [ControllerState] (the wheel) or
 * PAD from [PadState] (controller mode, PROTOCOL.md section 12). Both share the epoch and
 * the sequence counter, so a switch does not reset either. A switch takes effect on the
 * next packet, but never less than [SOURCE_HOLD_NS] after the first packet of the previous
 * switch was built (not after the moment it was decided, which can be a spacing wait
 * earlier), so the packets of one type always span at least 300 ms on the wire.
 *
 * While a PAD link is PAUSED (the controller's pause menu, which can stay open for minutes)
 * the idle repeat slows to [PAUSED_IDLE_NS], well inside the hub's 200 ms failsafe, instead
 * of keeping the radio busy at 500 Hz. A change (the unpause itself) is still sent at once.
 * The wheel link (INPUT) keeps its rate in every state.
 *
 * The Wi-Fi path exists whenever Wi-Fi is enabled, even before the hub address is known, so
 * a beacon seen mid-drive (or a hub that moved to another IP) can be picked up with
 * [setWifiTarget] without leaving the drive screen.
 */
class LinkEngine internal constructor(
    private val state: ControllerState,
    private val pad: PadState?,
    private val config: LinkConfig,
    private val extraSink: StatusSink?,
    /** Test hook: extra paths appended after the real ones. */
    extraTransports: List<Transport>,
    initialSource: PacketSource,
) {
    constructor(state: ControllerState, config: LinkConfig, extraSink: StatusSink? = null) :
        this(state, null, config, extraSink, emptyList(), PacketSource.INPUT)

    internal constructor(state: ControllerState, config: LinkConfig, extraSink: StatusSink?, extraTransports: List<Transport>) :
        this(state, null, config, extraSink, extraTransports, PacketSource.INPUT)

    /** A link that can send PAD packets from [pad]; [source] is what it starts with. */
    constructor(
        state: ControllerState,
        pad: PadState,
        config: LinkConfig,
        extraSink: StatusSink? = null,
        source: PacketSource = PacketSource.PAD,
    ) : this(state, pad, config, extraSink, emptyList(), source)

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
    private val padWriter: PadPacketWriter? = if (pad != null) PadPacketWriter(config.key) else null
    private val padFrame: PadFrame? = if (pad != null) PadFrame() else null
    private val idleNs: Long = 1_000_000_000L / normalizeRate(config.rateHz)
    private val pausedIdleNs: Long = maxOf(idleNs, PAUSED_IDLE_NS)

    /** What the application asked for; the sender applies it (see [setSource]). */
    @Volatile private var requestedSource: PacketSource = if (pad == null) PacketSource.INPUT else initialSource

    // Sender thread only (set in start() before the thread exists).
    private var activeSource = requestedSource
    private var hasSwitched = false
    private var lastSwitchNs = 0L
    /** A switch was applied and its first packet is not built yet (it re-anchors [lastSwitchNs]). */
    private var switchUnsent = false

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

    /** The type of the packets being built now. */
    @Volatile var source: PacketSource = requestedSource
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
        activeSource = requestedSource
        source = activeSource
        hasSwitched = false
        switchUnsent = false
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

    /**
     * Switches between INPUT (wheel) and PAD (controller). Same epoch, same sequence counter.
     * Ignored for PAD when the link was built without a [PadState].
     */
    fun setSource(next: PacketSource) {
        if (next == PacketSource.PAD && pad == null) return
        requestedSource = next
        state.signal.raise()
        pad?.signal?.raise()
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
        val inputSignal = state.signal
        val padSignal = pad?.signal
        inputSignal.attach(me)
        padSignal?.attach(me)
        try {
            // The first packet goes out at once in every state, so the hub adopts the epoch.
            var last = System.nanoTime() - pausedIdleNs
            while (alive(gen)) {
                val now = System.nanoTime()
                applySource(now)
                val padNow = if (activeSource == PacketSource.PAD) pad else null
                val signal = padNow?.signal ?: inputSignal
                val idle = if (padNow != null && padNow.flagBits and Slp.FLAG_PAUSED != 0) pausedIdleNs else idleNs
                val since = now - last
                if (!signal.isPending() && since < idle) {
                    LockSupport.parkNanos(this, idle - since)
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
            inputSignal.detach(me)
            padSignal?.detach(me)
        }
    }

    /**
     * Sender thread: applies a requested source switch unless the first packet of the previous
     * switch was built under 300 ms ago.
     */
    private fun applySource(nowNs: Long) {
        val want = requestedSource
        if (want == activeSource) return
        if (hasSwitched && nowNs - lastSwitchNs < SOURCE_HOLD_NS) return
        activeSource = want
        source = want
        hasSwitched = true
        lastSwitchNs = nowNs
        switchUnsent = true
    }

    private fun sendOne(nowNs: Long, extraFlags: Int) {
        try {
            var paths = 0
            for (t in transports) if (t.canSend()) paths++
            // No path up yet: build nothing, so the first packet on the wire is seq 1.
            if (paths == 0) return
            seq++ // u32, wraps
            val multipath = if (paths > 1) Slp.FLAG_MULTIPATH else 0
            val packet: ByteArray
            val len: Int
            val pf = padFrame
            val pw = padWriter
            if (activeSource == PacketSource.PAD && pad != null && pf != null && pw != null) {
                pad.snapshot(pf)
                pf.epoch = epoch
                pf.seq = seq
                pf.tUs = (nowNs / 1000L).toInt()
                pf.flags = pf.flags or extraFlags or multipath
                pf.rtt100us = stats.rtt100us
                packet = pw.write(pf)
                len = Slp.PAD_LEN
            } else {
                state.snapshot(frame)
                frame.epoch = epoch
                frame.seq = seq
                frame.tUs = (nowNs / 1000L).toInt()
                frame.flags = frame.flags or extraFlags or multipath
                frame.rtt100us = stats.rtt100us
                packet = writer.write(frame)
                len = Slp.INPUT_LEN
            }
            packetsBuilt++
            if (switchUnsent) {
                // The hold before the next switch counts from the first packet of this type.
                lastSwitchNs = nowNs
                switchUnsent = false
            }
            for (t in transports) {
                if (!t.canSend()) continue
                try {
                    t.send(packet, len)
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

        /** A source switch never follows the previous one by less than this (PROTOCOL.md 12). */
        const val SOURCE_HOLD_NS = 300_000_000L

        /** Idle repeat of a PAUSED PAD link: 50 Hz, a quarter of the hub's 200 ms failsafe. */
        const val PAUSED_IDLE_NS = 20_000_000L

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
