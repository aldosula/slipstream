package com.slipstream.wheel

import com.slipstream.wheel.input.ControllerState
import com.slipstream.wheel.input.PulseCounters
import com.slipstream.wheel.link.AdbTcpTransport
import com.slipstream.wheel.link.BeaconGate
import com.slipstream.wheel.link.LinkConfig
import com.slipstream.wheel.link.LinkEngine
import com.slipstream.wheel.link.LinkStats
import com.slipstream.wheel.link.PacketSource
import com.slipstream.wheel.link.RttMath
import com.slipstream.wheel.link.StatusSink
import com.slipstream.wheel.pad.PadButton
import com.slipstream.wheel.pad.PadState
import com.slipstream.wheel.protocol.Beacon
import com.slipstream.wheel.protocol.FrameAssembler
import com.slipstream.wheel.protocol.FrameSink
import com.slipstream.wheel.protocol.Framing
import com.slipstream.wheel.protocol.InputFrame
import com.slipstream.wheel.protocol.InputPacketWriter
import com.slipstream.wheel.protocol.Le
import com.slipstream.wheel.protocol.PadFrame
import com.slipstream.wheel.protocol.PadPacketReader
import com.slipstream.wheel.protocol.PadPacketWriter
import com.slipstream.wheel.protocol.PairingCode
import com.slipstream.wheel.protocol.PairingKey
import com.slipstream.wheel.protocol.Seq
import com.slipstream.wheel.protocol.Slp
import com.slipstream.wheel.protocol.StatusDecoder
import com.slipstream.wheel.protocol.StatusPacket
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
import java.io.File
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.PortUnreachableException
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin

/**
 * Cross-implementation wire test: this module's own protocol and link code against the real
 * C# hub (windows/tools/Slipstream.Cli, `slipstream hub`, headless), over real loopback sockets.
 *
 * Skipped unless SLIPSTREAM_INTEROP_PORT is set. `tools/interop.sh` builds the hub, picks free
 * ports and runs this class. Environment:
 * - SLIPSTREAM_INTEROP_PORT      UDP port the hub listens on (required to run)
 * - SLIPSTREAM_INTEROP_TCP_PORT  TCP (USB link) port, 127.0.0.1 (default: UDP port + 2)
 * - SLIPSTREAM_INTEROP_BEACON_PORT  UDP port for the beacon run f (skipped without it)
 * - SLIPSTREAM_INTEROP_HUB       path to slipstream.dll (run with dotnet) or the slipstream apphost
 * - SLIPSTREAM_INTEROP_DOTNET    dotnet executable (default "dotnet")
 * - SLIPSTREAM_INTEROP_OUT       folder for hub stats, hub logs and per-run results
 *
 * Every run starts its own hub with `--exit-idle-ms`, so the hub writes its stats JSON (PROTOCOL.md
 * section 9 counters as the hub saw them) and exits once this phone goes quiet. The phone side:
 * packets are built by the real [InputPacketWriter], framed by the real [Framing], STATUS is
 * checked by the real [StatusDecoder] and [FrameAssembler], RTT by [RttMath], loss by [LinkStats].
 * Run e drives the real [LinkEngine] with both of its transports.
 *
 * The pad_* runs do the same for controller mode (PROTOCOL.md section 12): PAD packets built from
 * the real [PadState] by the real [PadPacketWriter], over UDP, framed TCP and multipath, then the
 * real [LinkEngine] switching between wheel and pad within one epoch, and PAUSED and failsafe.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class InteropTest {

    // ---------------------------------------------------------------- environment ---

    private object Env {
        val udpPort: Int? = System.getenv("SLIPSTREAM_INTEROP_PORT")?.trim()?.toIntOrNull()
        val tcpPort: Int = System.getenv("SLIPSTREAM_INTEROP_TCP_PORT")?.trim()?.toIntOrNull() ?: ((udpPort ?: Slp.PORT_UDP) + 2)
        val beaconPort: Int? = System.getenv("SLIPSTREAM_INTEROP_BEACON_PORT")?.trim()?.toIntOrNull()
        val hub: String? = System.getenv("SLIPSTREAM_INTEROP_HUB")
        val dotnet: String = System.getenv("SLIPSTREAM_INTEROP_DOTNET") ?: "dotnet"
        val out: File = File(System.getenv("SLIPSTREAM_INTEROP_OUT") ?: "build/interop").absoluteFile
    }

    @Before
    fun requireInterop() {
        Assume.assumeTrue("cross-implementation test: set SLIPSTREAM_INTEROP_PORT (tools/interop.sh does)", Env.udpPort != null)
        assertNotNull("SLIPSTREAM_INTEROP_HUB must point at the hub CLI (slipstream.dll)", Env.hub)
        assertTrue("SLIPSTREAM_INTEROP_HUB does not exist: ${Env.hub}", File(Env.hub!!).exists())
        Env.out.mkdirs()
    }

    private val key = PairingCode.deriveKey(CODE)
    private val udpPort: Int get() = Env.udpPort!!
    private val tcpPort: Int get() = Env.tcpPort

    // ------------------------------------------------------------------ the runs ---

    /** (a) and (b): 2000 INPUT over UDP at 500 Hz, 20 % of them never sent, 15 shift-ups. */
    @Test
    fun a_udp2000PacketsAt500HzWith20PercentNotSent() {
        runScripted(
            Plan(
                name = "udp",
                packets = 2000,
                pulseSeqs = intArrayOf(100, 210, 320, 430, 540, 650, 760, 870, 980, 981, 1090, 1200, 1310, 1420, 1530),
                // Some increments ride on packets that are never sent: the next packet carries the
                // counter. 980 and 981 are one double press, so the hub sees +2 in a single packet.
                forcedDrops = intArrayOf(320, 760, 980, 1420),
                finalFrom = 1800,
                resetJumpSeq = 1111,
                replaySeqs = intArrayOf(400, 500, 600, 700, 800, 900, 1000, 1100, 1200, 1300),
                badTagSeqs = intArrayOf(300, 600, 900, 1200, 1500),
                malformedSeqs = intArrayOf(450, 1050, 1650),
                useUdp = true,
                useTcp = false,
                seed = 0x51495053L,
            ),
        )
    }

    /** (c): the same over framed TCP (the USB link), with frames split and coalesced on the wire. */
    @Test
    fun c_framedTcp1000PacketsWith20PercentNotSent() {
        runScripted(
            Plan(
                name = "tcp",
                packets = 1000,
                pulseSeqs = intArrayOf(30, 80, 130, 180, 230, 280, 330, 380, 430, 431, 480, 530, 580, 630, 680),
                forcedDrops = intArrayOf(130, 430, 580),
                finalFrom = 850,
                resetJumpSeq = 555,
                replaySeqs = intArrayOf(300, 400, 500, 600, 700),
                badTagSeqs = intArrayOf(250, 750, 800),
                splitSeqs = intArrayOf(101, 203, 307, 409, 511, 613, 719, 821, 923),
                coalesceSeqs = intArrayOf(150, 350, 550, 650, 950),
                useUdp = false,
                useTcp = true,
                seed = 0x54435031L,
            ),
        )
    }

    /** (d): multipath, every packet on UDP and on TCP. The TCP copy trails by 10 packets (20 ms). */
    @Test
    fun d_multipathEveryPacketOnUdpAndTcp() {
        runScripted(
            Plan(
                name = "multipath",
                packets = 1000,
                notSentPercent = 0,
                pulseSeqs = intArrayOf(30, 80, 130, 180, 230, 280, 330, 380, 430, 431, 480, 530, 580, 630, 680),
                forcedDrops = IntArray(0),
                finalFrom = 850,
                resetJumpSeq = 555,
                useUdp = true,
                useTcp = true,
                tcpLag = 10,
                seed = 0x4d505448L,
            ),
        )
    }

    /** (e): the app's real LinkEngine (UdpTransport + AdbTcpTransport, multipath) against the hub. */
    @Test
    fun e_realLinkEngineMultipath() {
        HubProcess("engine").use { hub ->
            hub.awaitReady()
            val state = ControllerState()
            state.setFlag(Slp.FLAG_PAUSED, false)
            state.setSteer(FINAL_STEER)
            state.setThrottle(FINAL_THROTTLE)
            state.setBrake(FINAL_BRAKE)
            state.setClutch(FINAL_CLUTCH)
            state.setHandbrake(FINAL_HANDBRAKE)
            for (bit in 0..23) if (FINAL_BUTTONS and (1 shl bit) != 0) state.setButton(bit, true)
            val engine = LinkEngine(
                state,
                LinkConfig(
                    key = key, host = AdbTcpTransport.LOOPBACK_V4, udpPort = udpPort, tcpPort = tcpPort,
                    useWifi = true, useUsb = true, rateHz = 500,
                ),
            )
            val snap = LinkStats.Snapshot(LinkEngine.TRANSPORT_SLOTS)
            var rtt100us = 0
            engine.start()
            try {
                assertTrue(
                    "both paths turn LIVE on authenticated hub STATUS",
                    waitFor(6000) {
                        engine.stats.snapshot(snap, System.nanoTime())
                        snap.state[LinkEngine.SLOT_WIFI] == LinkStats.TransportState.LIVE &&
                            snap.state[LinkEngine.SLOT_USB] == LinkStats.TransportState.LIVE
                    },
                )
                repeat(15) {
                    state.pulse(PulseCounters.SHIFT_UP)
                    Thread.sleep(120)
                }
                Thread.sleep(400)
                engine.stats.snapshot(snap, System.nanoTime())
                rtt100us = engine.stats.rtt100us
                assertEquals(LinkStats.TransportState.LIVE, snap.state[LinkEngine.SLOT_WIFI])
                assertEquals(LinkStats.TransportState.LIVE, snap.state[LinkEngine.SLOT_USB])
                assertTrue("smoothed RTT is known and fed back into INPUT, got $rtt100us", rtt100us > 0)
                assertEquals("no loss on loopback", 0.0, snap.lossTotal, 0.0)
            } finally {
                engine.stop() // sends the final PAUSED packets
            }
            val built = engine.packetsBuilt
            val s = hub.awaitStats()
            val udp = s.getJSONObject("transports").getJSONObject("udp")
            val tcp = s.getJSONObject("transports").getJSONObject("tcp")

            assertEquals("idle", s.getString("exit_reason"))
            assertEquals(Le.unsigned(engine.epoch), s.getLong("epoch"))
            assertEquals("every packet the engine built was applied once", built, s.getLong("accepted"))
            assertEquals(0L, s.getLong("missing"))
            assertEquals(built, s.getLong("last_seq"))
            assertEquals(built, udp.getLong("first_arrivals") + tcp.getLong("first_arrivals"))
            assertTrue("INPUT arrived on both paths", udp.getLong("packets") > 0 && tcp.getLong("packets") > 0)
            assertEquals(
                "every copy that lost the race is a duplicate",
                udp.getLong("packets") + tcp.getLong("packets") - built,
                udp.getLong("duplicates") + tcp.getLong("duplicates"),
            )
            assertEquals(0L, udp.getLong("bad_tags") + tcp.getLong("bad_tags") + udp.getLong("malformed") + tcp.getLong("malformed"))
            assertPresses(s, 15)

            val last = s.getJSONObject("last_input")
            assertEquals("the final packets carry PAUSED and MULTIPATH", Slp.FLAG_PAUSED or Slp.FLAG_MULTIPATH, last.getInt("flags"))
            assertEquals(FINAL_STEER, last.getInt("steer"))
            assertEquals(FINAL_THROTTLE, last.getInt("throttle"))
            assertEquals(FINAL_BRAKE, last.getInt("brake"))
            assertEquals(FINAL_CLUTCH, last.getInt("clutch"))
            assertEquals(FINAL_HANDBRAKE, last.getInt("handbrake"))
            assertEquals(Le.unsigned(FINAL_BUTTONS), last.getLong("buttons"))
            assertEquals(15, last.getJSONArray("pulses").getInt(0))
            assertTrue("the hub saw the phone's RTT", last.getInt("rtt_100us") > 0)
            // PAUSED: pedals and buttons released, steering centred (rule 6).
            assertEquals("paused", s.getString("state"))
            assertNeutral(s.getJSONObject("last_frame"), centred = true)
            assertNeutral(s.getJSONObject("applied_frame"), centred = true)

            val result = JSONObject()
                .put("run", "engine")
                .put("packets_built", built)
                .put("hub_accepted", s.getLong("accepted"))
                .put("hub_missing", s.getLong("missing"))
                .put("udp_packets", udp.getLong("packets")).put("udp_first", udp.getLong("first_arrivals")).put("udp_duplicates", udp.getLong("duplicates"))
                .put("tcp_packets", tcp.getLong("packets")).put("tcp_first", tcp.getLong("first_arrivals")).put("tcp_duplicates", tcp.getLong("duplicates"))
                .put("pulse_presses", s.getJSONArray("pulse_presses"))
                .put("phone_rtt_ms_smoothed", rtt100us / 10.0)
                .put("phone_rtt_wifi_us", snap.rttUs[LinkEngine.SLOT_WIFI])
                .put("phone_rtt_usb_us", snap.rttUs[LinkEngine.SLOT_USB])
            writeResult("engine", result)
            println("interop engine: $result")
        }
    }

    /**
     * (f): discovery as the connect screen does it. The hub broadcasts BEACON (section 7) on the test
     * port; the app's [Beacon] decoder, [BeaconGate] and fingerprint match pick the paired hub; then
     * the real [LinkEngine] connects over Wi-Fi UDP to the beacon's source IP (not loopback).
     */
    @Test
    fun f_beaconDiscoveryThenWifiLinkToTheBeaconSource() {
        val beaconPort = Env.beaconPort
        Assume.assumeTrue("set SLIPSTREAM_INTEROP_BEACON_PORT for the beacon run", beaconPort != null)
        val pairing = PairingKey.fromInput(CODE)!!
        DatagramSocket(null).use { listen ->
            listen.reuseAddress = true
            listen.broadcast = true
            listen.soTimeout = 200
            listen.bind(InetSocketAddress(beaconPort!!))
            HubProcess("beacon", beaconPort = beaconPort).use { hub ->
                hub.awaitReady()
                val gate = BeaconGate()
                val arrivals = mutableListOf<Long>()
                val admitted = mutableListOf<Pair<Inet4Address, Beacon>>()
                val buf = ByteArray(256)
                val pkt = DatagramPacket(buf, buf.size)
                val end = System.nanoTime() + 3_600_000_000L
                while (System.nanoTime() < end && admitted.size < 3) {
                    pkt.setLength(buf.size)
                    try {
                        listen.receive(pkt)
                    } catch (e: SocketTimeoutException) {
                        continue
                    }
                    val src = pkt.address as? Inet4Address ?: continue
                    val b = Beacon.decode(buf, 0, pkt.length) ?: continue
                    if (b.name != "INTEROP-beacon") continue // another hub on this LAN
                    arrivals += System.nanoTime()
                    if (!gate.admit(src.hashCode(), BeaconGate.hash(buf, 0, pkt.length), System.nanoTime() / 1_000_000L)) continue
                    admitted += src to b
                }
                assertTrue("at least 2 beacons from the hub in 3.6 s, got ${admitted.size}", admitted.size >= 2)
                for ((_, b) in admitted) {
                    assertTrue("beacon fingerprint matches the paired key", b.matches(pairing.fingerprint))
                    assertEquals("beacon udp_port", udpPort, b.udpPort)
                    assertEquals("beacon tcp_port", tcpPort, b.tcpPort)
                }
                val firstSeen = arrivals.first()
                val admittedGapsMs = arrivals.zipWithNext { a, c -> (c - a) / 1_000_000L }.filter { it > 500 }
                assertTrue("beacons are 1 s apart: $admittedGapsMs", admittedGapsMs.all { it in 800L..1300L })
                val (source, beacon) = admitted.first()

                // Connect as the app does after discovery: Wi-Fi only, to the beacon's source address.
                val state = ControllerState()
                state.setFlag(Slp.FLAG_PAUSED, false)
                state.setSteer(FINAL_STEER)
                state.setThrottle(FINAL_THROTTLE)
                val engine = LinkEngine(
                    state,
                    LinkConfig(key = key, host = source, udpPort = beacon.udpPort, useWifi = true, useUsb = false, rateHz = 500),
                )
                val snap = LinkStats.Snapshot(LinkEngine.TRANSPORT_SLOTS)
                engine.start()
                try {
                    assertTrue(
                        "the Wi-Fi path to ${source.hostAddress} turns LIVE on hub STATUS",
                        waitFor(4000) {
                            engine.stats.snapshot(snap, System.nanoTime())
                            snap.state[LinkEngine.SLOT_WIFI] == LinkStats.TransportState.LIVE
                        },
                    )
                    state.pulse(PulseCounters.SHIFT_UP)
                    Thread.sleep(400)
                } finally {
                    engine.stop()
                }
                val built = engine.packetsBuilt
                val s = hub.awaitStats()
                val udp = s.getJSONObject("transports").getJSONObject("udp")
                assertEquals(built, s.getLong("accepted"))
                assertEquals(0L, s.getLong("missing"))
                assertEquals(built, udp.getLong("packets"))
                assertEquals(0L, s.getJSONObject("transports").getJSONObject("tcp").getLong("packets"))
                assertPresses(s, 1)
                assertEquals(FINAL_STEER, s.getJSONObject("last_input").getInt("steer"))
                assertEquals(Slp.FLAG_PAUSED, s.getJSONObject("last_input").getInt("flags"))

                val result = JSONObject()
                    .put("run", "beacon")
                    .put("beacons_admitted", admitted.size)
                    .put("beacon_arrivals", arrivals.size)
                    .put("beacon_source", source.hostAddress)
                    .put("first_beacon_after_ready_ms", (firstSeen - hub.readyNs) / 1_000_000L)
                    .put("fingerprint", PairingKey.hex(beacon.fingerprint))
                    .put("packets_built", built)
                    .put("hub_accepted", s.getLong("accepted"))
                    .put("hub_missing", s.getLong("missing"))
                    .put("phone_rtt_wifi_us", snap.rttUs[LinkEngine.SLOT_WIFI])
                writeResult("beacon", result)
                println("interop beacon: $result")
            }
        }
    }

    // ------------------------------------------------------------- scripted runs ---

    private class Plan(
        val name: String,
        val packets: Int,
        val notSentPercent: Int = 20,
        /** Seqs at which the shift-up counter goes up by one (15 of them). */
        val pulseSeqs: IntArray,
        /** Seqs that are never sent, before the random ones are drawn. */
        val forcedDrops: IntArray,
        /** From this seq on the state is the final known state. */
        val finalFrom: Int,
        /** Channel 2 jumps by 140 here: a reset, no presses (rule 4, >= 128). */
        val resetJumpSeq: Int,
        /** After sending this seq, resend the packet five seqs older (a late duplicate). */
        val replaySeqs: IntArray = IntArray(0),
        /** Before this seq, send a copy with one flipped body bit (the tag must fail). */
        val badTagSeqs: IntArray = IntArray(0),
        /** UDP: send one datagram with a wrong length or version here. */
        val malformedSeqs: IntArray = IntArray(0),
        /** TCP: write this frame in two pieces, 1 ms apart. */
        val splitSeqs: IntArray = IntArray(0),
        /** TCP: hold this frame and write it together with the next one. */
        val coalesceSeqs: IntArray = IntArray(0),
        val useUdp: Boolean,
        val useTcp: Boolean,
        /** Multipath: the TCP copy of seq k is written together with the UDP copy of seq k + tcpLag. */
        val tcpLag: Int = 0,
        val seed: Long,
    ) {
        val multipath: Boolean get() = useUdp && useTcp
    }

    private class StatusRecord(
        val slot: Int,
        val rxNs: Long,
        val epoch: Int,
        val lastSeq: Int,
        val echoTUs: Int,
        val holdUs: Int,
        val accepted: Int,
        val missing: Int,
        val output: Int,
        val hubFlags: Int,
        val rumbleStrong: Int,
        val rumbleWeak: Int,
        val rttUs: Int,
    )

    private fun runScripted(plan: Plan) {
        val n = plan.packets
        assertEquals("15 shift-ups per run", 15, plan.pulseSeqs.size)
        assertTrue(plan.pulseSeqs.all { it in 2 until plan.finalFrom - 100 })
        val drop = dropSet(plan)
        assertTrue("seq 1 and the last seq are always sent", !drop[1] && !drop[n])
        val pressesAt = IntArray(n + 1)
        for (s in 1..n) pressesAt[s] = plan.pulseSeqs.count { it <= s }

        val writer = InputPacketWriter(key)
        val frame = InputFrame()
        val epoch = LinkEngine.newEpoch()
        val stats = LinkStats(LinkEngine.TRANSPORT_SLOTS).also { it.reset(epoch) }
        val tUs = IntArray(n + 1)
        val sent = BooleanArray(n + 1)
        val bytes = arrayOfNulls<ByteArray>(n + 1)
        val finalFrame = InputFrame()
        var replays = 0
        var badTags = 0
        var malformed = 0

        HubProcess(plan.name).use { hub ->
            hub.awaitReady()
            val statuses = ConcurrentLinkedQueue<StatusRecord>()
            val udp = if (plan.useUdp) openUdp() else null
            val tcp = if (plan.useTcp) openTcp() else null
            val udpRx = udp?.let { UdpStatusReceiver(it, key, stats, statuses) }
            val tcpRx = tcp?.let { TcpStatusReceiver(it, key, stats, statuses) }
            val tcpOut = tcp?.let { TcpFrameWriter(it) }
            val lag = ArrayDeque<ByteArray>()
            val splitAt = intArrayOf(1, 2, 30) // inside the length header, right after it, mid body
            var splitIndex = 0
            try {
                val t0 = System.nanoTime() + 5_000_000L
                for (seq in 1..n) {
                    sleepUntil(t0 + (seq - 1) * PERIOD_NS)
                    fill(plan, seq, pressesAt[seq], frame)
                    frame.epoch = epoch
                    frame.seq = seq
                    frame.tUs = (System.nanoTime() / 1000L).toInt()
                    frame.rtt100us = stats.rtt100us
                    val packet = writer.write(frame).copyOf()
                    tUs[seq] = frame.tUs
                    if (seq == n) finalFrame.copyFrom(frame)

                    if (seq in plan.badTagSeqs) {
                        val bad = packet.copyOf()
                        bad[16] = (bad[16].toInt() xor 1).toByte() // the tamper vector's change
                        if (udp != null) udpSend(udp, bad) else tcpOut!!.send(bad)
                        badTags++
                    }
                    if (!drop[seq]) {
                        sent[seq] = true
                        bytes[seq] = packet
                        if (udp != null) udpSend(udp, packet)
                        if (tcpOut != null) {
                            lag.addLast(packet)
                            while (lag.size > plan.tcpLag) {
                                val p = lag.removeFirst()
                                val split = if (seq in plan.splitSeqs) splitAt[splitIndex++ % splitAt.size] else 0
                                tcpOut.send(p, split = split, coalesce = seq in plan.coalesceSeqs)
                            }
                        }
                    }
                    if (seq in plan.replaySeqs) {
                        var old = seq - 5
                        while (!sent[old]) old--
                        if (udp != null) udpSend(udp, bytes[old]!!) else tcpOut!!.send(bytes[old]!!)
                        replays++
                    }
                    if (udp != null && seq in plan.malformedSeqs) {
                        val bad = when (malformed % 3) {
                            0 -> packet.copyOf(Slp.INPUT_LEN - 1) // 51 bytes
                            1 -> packet.copyOf(Slp.INPUT_LEN + 1) // 53 bytes
                            else -> packet.copyOf().also { it[2] = 2 } // version 2
                        }
                        udpSend(udp, bad)
                        malformed++
                    }
                }
                // Multipath: the trailing TCP copies go out at the same pace.
                while (lag.isNotEmpty()) {
                    LockSupport.parkNanos(PERIOD_NS)
                    tcpOut!!.send(lag.removeFirst())
                }
                tcpOut?.flushHeld()
                val lastSendNs = System.nanoTime()

                // Every path that delivered INPUT hears the final counters from the hub.
                if (udpRx != null) {
                    assertTrue("STATUS for seq $n arrives on UDP", waitFor(2000) { statuses.any { it.slot == SLOT_UDP && it.lastSeq == n } })
                }
                if (tcpRx != null) {
                    assertTrue("STATUS for seq $n arrives on TCP", waitFor(2000) { statuses.any { it.slot == SLOT_TCP && it.lastSeq == n } })
                }
                var closedByHub = false
                if (tcpOut != null && !plan.multipath) {
                    // Section 8: a frame whose length is not 52 closes the connection.
                    tcpOut.sendRaw(ByteArray(Framing.HEADER_LEN + Slp.INPUT_LEN + 1).also { Le.putU16(it, 0, Slp.INPUT_LEN + 1) })
                    closedByHub = waitFor(2000) { tcpRx!!.closed }
                    assertTrue("the hub closes a connection that sends a 53 byte frame", closedByHub)
                }

                val s = hub.awaitStats()
                udpRx?.stop()
                tcpRx?.stop()
                assertEquals("no STATUS failed its header or tag check", 0L, (udpRx?.rejected ?: 0L) + (tcpRx?.rejected ?: 0L))
                assertEquals("no bad STATUS frame length", false, tcpRx?.badFrame ?: false)

                val recs = statuses.toList()
                val sentUpTo = IntArray(n + 1)
                for (q in 1..n) sentUpTo[q] = sentUpTo[q - 1] + if (sent[q]) 1 else 0
                val summary = checkStatuses(plan.packets, plan.useUdp, plan.useTcp, recs, epoch, tUs, sent, sentUpTo, lastSendNs)
                val sentCount = sentUpTo[n]

                // LinkStats, fed the real STATUS stream, reports the loss the hub measured.
                val snap = LinkStats.Snapshot(LinkEngine.TRANSPORT_SLOTS)
                stats.snapshot(snap, System.nanoTime())
                assertEquals(sentCount.toLong(), snap.accepted)
                assertEquals((n - sentCount).toLong(), snap.missing)
                assertEquals((n - sentCount).toDouble() / n, snap.lossTotal, 1e-12)

                checkHubStats(plan, s, epoch, sentCount, replays, badTags, malformed, finalFrame)

                val tr = s.getJSONObject("transports")
                val result = JSONObject()
                    .put("run", plan.name)
                    .put("built", n)
                    .put("sent", sentCount)
                    .put("not_sent", n - sentCount)
                    .put("replays", replays)
                    .put("bad_tags_sent", badTags)
                    .put("malformed_sent", malformed)
                    .put("hub_accepted", s.getLong("accepted"))
                    .put("hub_missing", s.getLong("missing"))
                    .put("hub_loss_percent", s.getDouble("loss_percent"))
                    .put("hub_status_sent", s.getLong("status_sent"))
                    .put("hub_transports", tr)
                    .put("pulse_presses", s.getJSONArray("pulse_presses"))
                    .put("tcp_split_writes", tcpOut?.splits ?: 0)
                    .put("tcp_coalesced_writes", tcpOut?.coalesced ?: 0)
                    .put("tcp_closed_on_bad_length", closedByHub)
                    .put("status", summary)
                writeResult(plan.name, result)
                println("interop ${plan.name}: $result")
            } finally {
                udpRx?.stop()
                tcpRx?.stop()
                try {
                    udp?.close()
                } catch (e: Exception) {
                    // closing
                }
                try {
                    tcp?.close()
                } catch (e: Exception) {
                    // closing
                }
            }
        }
    }

    /** Exactly notSentPercent of the packets, forced ones first, then a seeded random pick. */
    private fun dropSet(plan: Plan): BooleanArray {
        val drop = BooleanArray(plan.packets + 1)
        val target = plan.packets * plan.notSentPercent / 100
        for (s in plan.forcedDrops) drop[s] = true
        var count = plan.forcedDrops.size
        val candidates = (2 until plan.packets).filter { !drop[it] }.shuffled(java.util.Random(plan.seed))
        for (s in candidates) {
            if (count >= target) break
            drop[s] = true
            count++
        }
        assertEquals(target, drop.count { it })
        return drop
    }

    /** The controller state of packet [seq]: moving everywhere, then the final known state. */
    private fun fill(plan: Plan, seq: Int, presses: Int, f: InputFrame) {
        if (seq >= plan.finalFrom) {
            f.steer = FINAL_STEER
            f.throttle = FINAL_THROTTLE
            f.brake = FINAL_BRAKE
            f.clutch = FINAL_CLUTCH
            f.handbrake = FINAL_HANDBRAKE
            f.buttons = FINAL_BUTTONS
        } else {
            f.steer = (sin(seq * 0.0125) * 32767.0).roundToInt().coerceIn(-32767, 32767)
            f.throttle = (seq * 331) and 0xFFFF
            f.brake = (seq * 997 + 12345) and 0xFFFF
            f.clutch = if (seq % 400 < 200) 0 else 65535
            f.handbrake = if ((seq / 250) % 2 == 0) 0 else 65535
            f.buttons = ((seq.toLong() * 2654435761L) and 0xFF_FFFFL).toInt()
        }
        f.aux = 0
        f.pulses.fill(0)
        f.pulses[0] = (PULSE0_START + presses).toByte() // wraps past 255
        f.pulses[1] = 77
        f.pulses[2] = (if (seq < plan.resetJumpSeq) 10 else 150).toByte()
        f.pulses[7] = 200.toByte()
        f.flags = if (plan.multipath) Slp.FLAG_MULTIPATH else 0
    }

    /**
     * (b): every STATUS decoded and tag-checked by the app's decoder, then checked field by field.
     * [padOutput] is -1 for the wheel (output byte 0, the headless hub's wheel output is "none"), or
     * the pad kind a controller-mode run expects once the pad is plugged in. With [exactCounts] false
     * (multipath with packets lost on one path only) [sentUpTo] is a lower bound for `accepted`.
     */
    private fun checkStatuses(
        n: Int,
        useUdp: Boolean,
        useTcp: Boolean,
        recs: List<StatusRecord>,
        epoch: Int,
        tUs: IntArray,
        sent: BooleanArray,
        sentUpTo: IntArray,
        lastSendNs: Long,
        padOutput: Int = -1,
        exactCounts: Boolean = true,
    ): JSONObject {
        val out = JSONObject()
        val slots = buildList {
            if (useUdp) add(SLOT_UDP)
            if (useTcp) add(SLOT_TCP)
        }
        var maxHoldActive = 0
        for (slot in slots) {
            val mine = recs.filter { it.slot == slot }.sortedBy { it.rxNs }
            val label = if (slot == SLOT_UDP) "udp" else "tcp"
            assertTrue("STATUS received on $label", mine.isNotEmpty())
            var prevSeq = 0
            var padSeen = false
            for (r in mine) {
                assertEquals("STATUS epoch is ours", epoch, r.epoch)
                val l = r.lastSeq
                assertTrue("last_seq $l is a seq that was sent", l in 1..n && sent[l])
                assertEquals("echo_t_us is the t_us of packet last_seq $l", tUs[l], r.echoTUs)
                if (exactCounts) {
                    assertEquals("accepted at last_seq $l", sentUpTo[l], r.accepted)
                    assertEquals("missing at last_seq $l", l - sentUpTo[l], r.missing)
                } else {
                    assertEquals("accepted + missing at last_seq $l", l, r.accepted + r.missing)
                    assertTrue("accepted ${r.accepted} at last_seq $l within ${sentUpTo[l]}..$l", r.accepted in sentUpTo[l]..l)
                }
                if (padOutput < 0) {
                    assertEquals("output byte of the headless hub (none, no error)", 0, r.output)
                } else {
                    // 0 only until the pad is plugged in on the first PAD packet; then it stays.
                    if (padSeen) assertEquals("pad output byte once plugged in", padOutput, r.output)
                    assertTrue("pad output byte ${r.output} is 0 or $padOutput", r.output == 0 || r.output == padOutput)
                    if (r.output == padOutput) padSeen = true
                }
                assertEquals(0, r.hubFlags)
                assertEquals(0, r.rumbleStrong or r.rumbleWeak)
                assertTrue("RTT is a plausible round trip, got ${r.rttUs} us", r.rttUs in 0..RTT_MAX_US)
                assertTrue("last_seq never goes back on one path", prevSeq == 0 || !Seq.newer(prevSeq, l))
                prevSeq = l
                if (r.rxNs < lastSendNs) maxHoldActive = maxOf(maxHoldActive, r.holdUs)
                // After the last packet a path hears STATUS for 1 s after its own last delivery (section 6).
                // In multipath the trailing copies extend that by the lag, so hold can pass 1 s a little.
                assertTrue("hold_us below 1.5 s, got ${r.holdUs}", r.holdUs in 0 until 1_500_000)
            }
            val rtts = mine.map { it.rttUs }.sorted()
            val gaps = mine.zipWithNext { a, b -> (b.rxNs - a.rxNs) / 1000 }.sorted()
            val medianGapUs = gaps[gaps.size / 2]
            assertTrue("STATUS runs at 20 Hz: median interval $medianGapUs us", medianGapUs in 40_000..60_000)
            val p50 = rtts[rtts.size / 2]
            assertTrue("median RTT on loopback under 5 ms, got $p50 us", p50 < 5_000)
            if (padOutput >= 0) assertEquals("the last STATUS on $label names the pad", padOutput, mine.last().output)
            out.put(
                label,
                JSONObject()
                    .put("count", mine.size)
                    .put("final_output", mine.last().output)
                    .put("median_interval_ms", medianGapUs / 1000.0)
                    .put("rtt_min_us", rtts.first())
                    .put("rtt_p50_us", p50)
                    .put("rtt_avg_us", rtts.average().roundToInt())
                    .put("rtt_p99_us", rtts[(rtts.size * 99) / 100])
                    .put("rtt_max_us", rtts.last())
                    .put("final_last_seq", mine.last().lastSeq)
                    .put("final_accepted", mine.last().accepted)
                    .put("final_missing", mine.last().missing),
            )
        }
        assertTrue("while INPUT flows at 500 Hz, hold_us stays under 100 ms, got $maxHoldActive", maxHoldActive < 100_000)
        out.put("max_hold_us_while_sending", maxHoldActive)
        return out
    }

    /** The hub's own view, from its stats JSON. */
    private fun checkHubStats(
        plan: Plan,
        s: JSONObject,
        epoch: Int,
        sentCount: Int,
        replays: Int,
        badTags: Int,
        malformed: Int,
        final: InputFrame,
    ) {
        val n = plan.packets
        assertEquals("idle", s.getString("exit_reason"))
        assertEquals("200 ms after the last packet the hub is in failsafe", "failsafe", s.getString("state"))
        assertEquals(Le.unsigned(epoch), s.getLong("epoch"))
        assertEquals(1L, s.getLong("epoch_changes"))
        assertEquals(n.toLong(), s.getLong("last_seq"))
        assertEquals("accepted = packets sent", sentCount.toLong(), s.getLong("accepted"))
        assertEquals("missing = packets never sent", (n - sentCount).toLong(), s.getLong("missing"))
        assertEquals(sentCount.toLong(), s.getLong("total_accepted"))
        assertEquals((n - sentCount).toLong(), s.getLong("total_missing"))
        assertPresses(s, 15)

        val udp = s.getJSONObject("transports").getJSONObject("udp")
        val tcp = s.getJSONObject("transports").getJSONObject("tcp")
        if (plan.multipath) {
            assertEquals(n.toLong(), udp.getLong("packets"))
            assertEquals(n.toLong(), tcp.getLong("packets"))
            assertEquals("each seq applied once", n.toLong(), udp.getLong("first_arrivals") + tcp.getLong("first_arrivals"))
            assertEquals("each seq has one duplicate", n.toLong(), udp.getLong("duplicates") + tcp.getLong("duplicates"))
            assertTrue(
                "duplicates are counted on the slower path (TCP, 20 ms behind): ${tcp.getLong("duplicates")} of $n",
                tcp.getLong("duplicates") >= n * 99L / 100,
            )
            assertEquals(0L, udp.getLong("bad_tags") + tcp.getLong("bad_tags") + udp.getLong("malformed") + tcp.getLong("malformed"))
        } else {
            val used = if (plan.useUdp) udp else tcp
            val idle = if (plan.useUdp) tcp else udp
            assertEquals(0L, idle.getLong("packets"))
            assertEquals((sentCount + replays).toLong(), used.getLong("packets"))
            assertEquals(sentCount.toLong(), used.getLong("first_arrivals"))
            assertEquals("the late replays are the duplicates", replays.toLong(), used.getLong("duplicates"))
            assertEquals("tampered packets fail the tag", badTags.toLong(), used.getLong("bad_tags"))
            val expectedMalformed = if (plan.useUdp) malformed.toLong() else 1L // TCP: the 53 byte frame
            assertEquals(expectedMalformed, used.getLong("malformed"))
            assertEquals(0L, used.getLong("foreign_epoch"))
        }

        // The last INPUT exactly as the hub decoded it from the wire.
        val last = s.getJSONObject("last_input")
        assertEquals(Le.unsigned(final.epoch), last.getLong("epoch"))
        assertEquals(n.toLong(), last.getLong("seq"))
        assertEquals(Le.unsigned(final.tUs), last.getLong("t_us"))
        assertEquals(final.steer, last.getInt("steer"))
        assertEquals(final.throttle, last.getInt("throttle"))
        assertEquals(final.brake, last.getInt("brake"))
        assertEquals(final.clutch, last.getInt("clutch"))
        assertEquals(final.handbrake, last.getInt("handbrake"))
        assertEquals(0, last.getInt("aux"))
        assertEquals(Le.unsigned(final.buttons), last.getLong("buttons"))
        val pulses = last.getJSONArray("pulses")
        for (ch in 0 until Slp.PULSE_CHANNELS) assertEquals("pulse counter $ch", final.pulses[ch].toInt() and 0xFF, pulses.getInt(ch))
        assertEquals((PULSE0_START + 15) and 0xFF, pulses.getInt(0))
        assertEquals(final.flags, last.getInt("flags"))
        assertEquals(final.rtt100us, last.getInt("rtt_100us"))

        // What the virtual device got from the final state (section 10), and what it shows after the failsafe.
        val applied = s.getJSONObject("applied_frame")
        assertEquals(0, applied.getInt("pulse_mask"))
        assertEquals(Le.unsigned(FINAL_BUTTONS), applied.getLong("held"))
        assertMapped(applied, FINAL_STEER, FINAL_THROTTLE, FINAL_BRAKE, FINAL_CLUTCH, FINAL_HANDBRAKE, FINAL_BUTTONS)
        assertMapped(s.getJSONObject("last_frame"), FINAL_STEER, 0, 0, 0, 0, 0) // rule 6: steering holds
    }

    private fun assertPresses(s: JSONObject, channel0: Int) {
        val presses = s.getJSONArray("pulse_presses")
        val pending = s.getJSONArray("pulse_pending")
        assertEquals("exactly $channel0 shift-up presses, no double presses: $presses", channel0, presses.getInt(0))
        for (ch in 1 until Slp.PULSE_CHANNELS) assertEquals("no presses on channel $ch: $presses", 0, presses.getInt(ch))
        for (ch in 0 until Slp.PULSE_CHANNELS) assertEquals("nothing left queued: $pending", 0, pending.getInt(ch))
    }

    /** Section 10 formulas, computed here independently of the hub's code. */
    private fun assertMapped(frame: JSONObject, steer: Int, throttle: Int, brake: Int, clutch: Int, handbrake: Int, held: Int) {
        val v = frame.getJSONObject("vjoy")
        assertEquals("vJoy X", vjoySteer(steer), v.getLong("x"))
        assertEquals("vJoy Y", vjoyPedal(throttle), v.getLong("y"))
        assertEquals("vJoy Z", vjoyPedal(brake), v.getLong("z"))
        assertEquals("vJoy Rx", vjoyPedal(clutch), v.getLong("rx"))
        assertEquals("vJoy Ry", vjoyPedal(handbrake), v.getLong("ry"))
        // Held bit i is button i + 9 (bit i + 8); no pulse is down at the end.
        assertEquals("vJoy buttons", Le.unsigned((held and Slp.BUTTONS_USED_MASK) shl 8), v.getLong("buttons"))
        val x = frame.getJSONObject("x360")
        assertEquals("X360 left thumb X", steer, x.getInt("left_thumb_x"))
        assertEquals("X360 right trigger", throttle ushr 8, x.getInt("right_trigger"))
        assertEquals("X360 left trigger", brake ushr 8, x.getInt("left_trigger"))
        assertEquals("X360 right thumb Y", (clutch ushr 1).toShort().toInt(), x.getInt("right_thumb_y"))
        var buttons = 0
        val dpad = intArrayOf(0x0001, 0x0002, 0x0004, 0x0008) // held bits 0..3: up, down, left, right
        for (i in 0..3) if (held and (1 shl i) != 0) buttons = buttons or dpad[i]
        if (handbrake >= 32768) buttons = buttons or 0x1000 // A
        assertEquals("X360 buttons", buttons, x.getInt("buttons"))
    }

    private fun assertNeutral(frame: JSONObject, centred: Boolean) {
        val v = frame.getJSONObject("vjoy")
        if (centred) assertEquals(vjoySteer(0), v.getLong("x"))
        for (axis in listOf("y", "z", "rx", "ry")) assertEquals("vJoy $axis at rest", 1L, v.getLong(axis))
        assertEquals(0L, v.getLong("buttons"))
        assertEquals(0, frame.getJSONObject("x360").getInt("buttons"))
    }

    // ======================================================== controller mode (PAD) ===
    //
    // PROTOCOL.md section 12. The scripted runs build every PAD packet from the app's real PadState
    // (held bits and 4-bit tap counters come from TapCounters, exactly as the play screen makes them)
    // with the real PadPacketWriter. Runs pad_d and pad_e1 drive the real LinkEngine with a PadState.
    // Expected DualShock 4 and Xbox 360 values are computed here from the 12.5 formulas and the two
    // controllers' own report layouts, independently of the hub's code.

    /**
     * PAD (a): 2000 PAD packets over UDP at 500 Hz, PlayStation layout, 400 (20 %) never sent, exactly
     * 12 short Cross taps. On purpose: a lost press, a lost release, a whole tap lost, a double tap lost,
     * a press made while the previous one still shows. Also late replays of press packets, tampered
     * copies that would add a tap, malformed datagrams, and a tap counter jump of 8 (no press).
     */
    @Test
    fun pad_a_udpPlayStation2000PacketsWith20PercentNotSent() {
        val plan = PadPlan(
            name = "pad_udp",
            packets = 2000,
            stylePs = true,
            taps = LOSSY_TAPS,
            expectedReplays = LOSSY_REPLAYS_EXPECTED,
            preTaps = mapOf(PadButton.CROSS to 7, PadButton.CIRCLE to 9, PadButton.TRIANGLE to 14),
            jumpSeq = 1300,
            jumpButton = PadButton.SQUARE,
            forcedDrops = LOSSY_TAP_DROPS,
            forcedKeeps = LOSSY_TAP_KEEPS + intArrayOf(1300),
            finalFrom = 1700,
            finalState = { psFinal(it) },
            replays = LATE_COPIES,
            badTagSeqs = intArrayOf(100, 580, 947, 1190, 1600),
            malformedSeqs = intArrayOf(150, 650, 1150, 1450, 1650),
            useUdp = true,
            useTcp = false,
            seed = 0x50414431L,
            padOutput = OUTPUT_DS4,
            padKind = "ds4",
        )
        runPadScripted(plan) { s, final ->
            assertTaps(s, mapOf(PadButton.CROSS to 12))
            assertEquals("the phone clamps a stick to -32767 (12.3)", -32767, s.getJSONObject("last_pad").getInt("lx"))
            assertPadMapped(s.getJSONObject("applied_pad_frame"), final, final.buttons)
            assertPadNeutral(s.getJSONObject("last_pad_frame"))
        }
    }

    /**
     * PAD (b): the same traffic over framed TCP (the USB link), Xbox layout: frames split and coalesced
     * on the wire, legal-length frames with bad content, and finally a 77 byte frame (section 8).
     */
    @Test
    fun pad_b_framedTcpXbox2000PacketsWith20PercentNotSent() {
        val plan = PadPlan(
            name = "pad_tcp",
            packets = 2000,
            stylePs = false,
            taps = LOSSY_TAPS,
            expectedReplays = LOSSY_REPLAYS_EXPECTED,
            preTaps = mapOf(PadButton.CROSS to 3, PadButton.TRIANGLE to 15, PadButton.L1 to 1),
            jumpSeq = 1300,
            jumpButton = PadButton.SHARE,
            forcedDrops = LOSSY_TAP_DROPS,
            forcedKeeps = LOSSY_TAP_KEEPS + intArrayOf(1300),
            finalFrom = 1700,
            finalState = { xboxFinal(it) },
            replays = LATE_COPIES,
            badTagSeqs = intArrayOf(100, 580, 947, 1190, 1600),
            malformedSeqs = intArrayOf(660, 1460),
            splitSeqs = intArrayOf(101, 203, 307, 409, 511, 613, 719, 821, 923, 1025, 1427, 1831),
            coalesceSeqs = intArrayOf(150, 350, 550, 950, 1250, 1750),
            useUdp = false,
            useTcp = true,
            seed = 0x50414432L,
            padOutput = OUTPUT_X360,
            padKind = "x360",
        )
        runPadScripted(plan) { s, final ->
            assertTaps(s, mapOf(PadButton.CROSS to 12))
            assertPadMapped(s.getJSONObject("applied_pad_frame"), final, final.buttons)
            assertPadNeutral(s.getJSONObject("last_pad_frame"))
        }
    }

    /**
     * PAD (c): multipath, every PAD packet on UDP and on TCP, the TCP copy 10 packets (20 ms) behind.
     * 13 packets (a press, a release, a whole tap and a whole double tap) are never sent on UDP, so
     * their only copy arrives late on TCP, after newer ones: the counters in the next UDP packet carry
     * those taps. Taps 10, 11 and 12 come while the replay of the double tap still runs (180 ms), so
     * the hub appends them to it (12.4 rule 3): 6 replayed taps in all. Every tap shows exactly once;
     * the late copies are duplicates on the slower path.
     */
    @Test
    fun pad_c_multipathEveryPadPacketOnUdpAndTcp() {
        val plan = PadPlan(
            name = "pad_multipath",
            packets = 1000,
            stylePs = true,
            notSentPercent = 0,
            taps = listOf(
                Tap(40, 10), Tap(100, 8), Tap(160, 12), Tap(220, 1), Tap(280, 3), Tap(340, 10),
                Tap(400, 25), Tap(460, 2), Tap(464, 2), Tap(500, 5), Tap(540, 6), Tap(580, 5),
            ),
            expectedReplays = 6,
            preTaps = mapOf(PadButton.CROSS to 11),
            finalFrom = 800,
            finalState = { psFinal(it) },
            useUdp = true,
            useTcp = true,
            tcpLag = 10,
            udpOnlyDrops = intArrayOf(100, 172, 280, 281, 282, 283, 460, 461, 462, 463, 464, 465, 466),
            seed = 0x50414433L,
            padOutput = OUTPUT_DS4,
            padKind = "ds4",
        )
        runPadScripted(plan) { s, final ->
            assertTaps(s, mapOf(PadButton.CROSS to 12))
            assertPadMapped(s.getJSONObject("applied_pad_frame"), final, final.buttons)
            assertPadNeutral(s.getJSONObject("last_pad_frame"))
        }
    }

    /**
     * PAD (d): the real LinkEngine in multipath switches wheel to pad to wheel within one epoch
     * (PROTOCOL.md 12.4 rule 2). Counters that moved on the phone while the other packet type was on
     * the wire are taken as the baseline at each switch: no spurious pulses or taps. The pad goes
     * neutral when the wheel comes back, and the wheel is still accepted afterwards.
     */
    @Test
    fun pad_d_modeSwitchWheelToPadToWheelInOneEpoch() {
        HubProcess("pad_switch").use { hub ->
            hub.awaitReady()
            val state = ControllerState()
            state.setFlag(Slp.FLAG_PAUSED, false)
            state.setSteer(FINAL_STEER)
            state.setThrottle(FINAL_THROTTLE)
            state.setBrake(FINAL_BRAKE)
            repeat(3) { state.pulse(PulseCounters.SHIFT_UP) } // the hub's baseline: never pressed
            val pad = PadState()
            pad.setFlag(Slp.FLAG_PAUSED, false)
            pad.setFlag(Slp.FLAG_STYLE_PS, true)
            repeat(5) { tapNow(pad, PadButton.CROSS) }
            pad.setStick(0, 20000, -20000)
            val log = StatusLog()
            val engine = LinkEngine(
                state,
                pad,
                LinkConfig(
                    key = key, host = AdbTcpTransport.LOOPBACK_V4, udpPort = udpPort, tcpPort = tcpPort,
                    useWifi = true, useUsb = true, rateHz = 500,
                ),
                log,
                PacketSource.INPUT,
            )
            val snap = LinkStats.Snapshot(LinkEngine.TRANSPORT_SLOTS)
            var switchToPadNs = 0L
            var switchToWheelNs = 0L
            engine.start()
            try {
                assertTrue(
                    "both paths turn LIVE on authenticated hub STATUS",
                    waitFor(6000) {
                        engine.stats.snapshot(snap, System.nanoTime())
                        snap.state[LinkEngine.SLOT_WIFI] == LinkStats.TransportState.LIVE &&
                            snap.state[LinkEngine.SLOT_USB] == LinkStats.TransportState.LIVE
                    },
                )
                repeat(2) {
                    state.pulse(PulseCounters.SHIFT_UP)
                    Thread.sleep(120)
                }
                Thread.sleep(100)
                // Two Cross taps on the phone while the link still sends INPUT: never seen by the hub.
                repeat(2) { tapNow(pad, PadButton.CROSS) }

                switchToPadNs = System.nanoTime()
                engine.setSource(PacketSource.PAD)
                assertTrue(
                    "the hub reports its DualShock 4 in STATUS once PAD arrives",
                    waitFor(3000) { log.after(switchToPadNs).any { it.output == OUTPUT_DS4 } },
                )
                assertEquals(PacketSource.PAD, engine.source)
                repeat(3) { tapTimed(pad, PadButton.CROSS) }
                // Shift-ups on the phone while PAD is on the wire: the hub must not replay them later.
                repeat(4) { state.pulse(PulseCounters.SHIFT_UP) }
                pad.setButton(PadButton.L1, true)
                pad.setStick(1, -32767, 32767)
                pad.setTrigger(1, 65535)
                Thread.sleep(100)

                // Back to the wheel while L1 is held and the sticks are deflected.
                switchToWheelNs = System.nanoTime()
                state.setSteer(-FINAL_STEER)
                engine.setSource(PacketSource.INPUT)
                assertTrue(
                    "STATUS names the wheel output again once INPUT is back",
                    waitFor(3000) { log.after(switchToWheelNs).any { it.output == 0 } },
                )
                assertEquals(PacketSource.INPUT, engine.source)
                assertTrue("the engine kept 300 ms between switches", switchToWheelNs - switchToPadNs >= LinkEngine.SOURCE_HOLD_NS)
                repeat(2) {
                    state.pulse(PulseCounters.SHIFT_UP)
                    Thread.sleep(120)
                }
                Thread.sleep(300)
            } finally {
                engine.stop() // final PAUSED packets, INPUT
            }
            val built = engine.packetsBuilt
            val s = hub.awaitStats()
            val udp = s.getJSONObject("transports").getJSONObject("udp")
            val tcp = s.getJSONObject("transports").getJSONObject("tcp")

            assertEquals("idle", s.getString("exit_reason"))
            assertEquals(Le.unsigned(engine.epoch), s.getLong("epoch"))
            assertEquals("one epoch for the whole run", 1L, s.getLong("epoch_changes"))
            assertEquals("every packet the engine built was applied once", built, s.getLong("accepted"))
            assertEquals(0L, s.getLong("missing"))
            assertEquals(built, s.getLong("last_seq"))
            assertEquals(built, udp.getLong("first_arrivals") + tcp.getLong("first_arrivals"))
            assertEquals(
                "every copy that lost the race is a duplicate",
                udp.getLong("packets") + tcp.getLong("packets") - built,
                udp.getLong("duplicates") + tcp.getLong("duplicates"),
            )
            assertEquals(0L, udp.getLong("bad_tags") + tcp.getLong("bad_tags") + udp.getLong("malformed") + tcp.getLong("malformed"))

            // 2 shift-ups before the pad and 2 after it; the 4 made while PAD was on the wire are a baseline.
            assertPresses(s, 4)
            // 3 Cross taps and one L1 press while PAD was on the wire; the 7 made before are a baseline.
            assertTaps(s, mapOf(PadButton.CROSS to 3, PadButton.L1 to 1))

            assertEquals("wheel", s.getString("mode"))
            assertEquals("paused", s.getString("state"))
            val last = s.getJSONObject("last_input")
            assertEquals("the wheel is accepted after the switch back", Slp.FLAG_PAUSED or Slp.FLAG_MULTIPATH, last.getInt("flags"))
            assertEquals(-FINAL_STEER, last.getInt("steer"))
            assertEquals(3 + 2 + 4 + 2, last.getJSONArray("pulses").getInt(0))
            val lp = s.getJSONObject("last_pad")
            assertEquals("the newest PAD carried every Cross tap the phone made", 5 + 2 + 3, lp.getJSONArray("taps").getInt(PadButton.CROSS))
            assertEquals(Le.unsigned(1 shl PadButton.L1), lp.getLong("buttons"))
            assertEquals(Slp.FLAG_MULTIPATH or Slp.FLAG_STYLE_PS, lp.getInt("flags"))
            assertTrue("the newest PAD is older than the newest INPUT", lp.getLong("seq") < last.getLong("seq"))
            // 12.4 rule 2: the pad went neutral when the wheel came back, and stays plugged in.
            assertPadNeutral(s.getJSONObject("last_pad_frame"))
            val applied = s.getJSONObject("applied_pad_frame")
            assertEquals(Le.unsigned(1 shl PadButton.L1), applied.getLong("buttons"))
            assertEquals(-32767, applied.getInt("rx"))
            assertEquals(ds4Axis(20000), applied.getJSONObject("ds4").getInt("left_x"))
            val po = s.getJSONObject("pad_output")
            assertEquals("ds4", po.getString("kind"))
            assertTrue(po.getBoolean("plugged"))
            assertNeutral(s.getJSONObject("last_frame"), centred = true)

            val outputs = log.outputSequence()
            assertEquals("STATUS output byte over the run: wheel, pad, wheel", listOf(0, OUTPUT_DS4, 0), outputs)

            val result = JSONObject()
                .put("run", "pad_switch")
                .put("packets_built", built)
                .put("hub_accepted", s.getLong("accepted"))
                .put("hub_missing", s.getLong("missing"))
                .put("epoch_changes", s.getLong("epoch_changes"))
                .put("udp_first", udp.getLong("first_arrivals")).put("udp_duplicates", udp.getLong("duplicates"))
                .put("tcp_first", tcp.getLong("first_arrivals")).put("tcp_duplicates", tcp.getLong("duplicates"))
                .put("pulse_presses", s.getJSONArray("pulse_presses"))
                .put("taps_emitted_by_name", s.getJSONObject("taps_emitted_by_name"))
                .put("status_outputs", JSONArray(outputs))
                .put("mode_at_end", s.getString("mode"))
                .put("pad_output", po)
            writeResult("pad_switch", result)
            println("interop pad_switch: $result")
        }
    }

    /**
     * PAD (e1): PAUSED with the real LinkEngine (Wi-Fi UDP). The phone pauses while sticks, a trigger,
     * a touch finger and L1 are held: the hub shows neutral (12.4 rule 4). While paused the phone repeats
     * at 50 Hz and the hub stays inside its 200 ms failsafe. After resume a Cross tap goes through; the
     * run ends with PAUSED packets that carry R1 held, which the hub must not show.
     */
    @Test
    fun pad_e1_pausedPadWithRealLinkEngine() {
        HubProcess("pad_paused").use { hub ->
            hub.awaitReady()
            val state = ControllerState()
            val pad = PadState()
            pad.setFlag(Slp.FLAG_STYLE_PS, true)
            pad.setFlag(Slp.FLAG_MOTION, true)
            pad.setGyro(160, -320, 48)
            pad.setAccel(0, 4096, 0)
            pad.setFlag(Slp.FLAG_PAUSED, false)
            val log = StatusLog()
            val engine = LinkEngine(
                state,
                pad,
                LinkConfig(key = key, host = AdbTcpTransport.LOOPBACK_V4, udpPort = udpPort, useWifi = true, useUsb = false, rateHz = 500),
                log,
                PacketSource.PAD,
            )
            val snap = LinkStats.Snapshot(LinkEngine.TRANSPORT_SLOTS)
            var builtWhilePaused = 0L
            var pauseStartNs = 0L
            var pauseEndNs = 0L
            engine.start()
            try {
                assertTrue(
                    "the Wi-Fi path turns LIVE and the hub reports its DualShock 4",
                    waitFor(6000) {
                        engine.stats.snapshot(snap, System.nanoTime())
                        snap.state[LinkEngine.SLOT_WIFI] == LinkStats.TransportState.LIVE && log.entries.any { it.output == OUTPUT_DS4 }
                    },
                )
                repeat(2) { tapTimed(pad, PadButton.CROSS) }
                pad.setButton(PadButton.L1, true)
                pad.setStick(0, 32767, 32767)
                pad.setTrigger(0, 40000)
                pad.setTouch(0, true, 3, 1000, 2000)
                Thread.sleep(50)

                val builtBefore = engine.packetsBuilt
                pauseStartNs = System.nanoTime()
                pad.setFlag(Slp.FLAG_PAUSED, true)
                Thread.sleep(PAUSE_MS)
                pauseEndNs = System.nanoTime()
                builtWhilePaused = engine.packetsBuilt - builtBefore
                // As the pause menu does: every finger is lifted, then the menu closes.
                pad.releaseAll()
                Thread.sleep(40)
                pad.setFlag(Slp.FLAG_PAUSED, false)
                tapTimed(pad, PadButton.CROSS)
                pad.setButton(PadButton.R1, true)
                pad.setStick(1, -32767, -32767)
                pad.setTrigger(1, 65535)
                pad.setTouch(1, true, 4, 65535, 65535)
                Thread.sleep(50)
                pad.setFlag(Slp.FLAG_PAUSED, true)
                Thread.sleep(100)
            } finally {
                engine.stop() // final PAUSED packets, PAD
            }
            val built = engine.packetsBuilt
            val s = hub.awaitStats()

            // The phone's paused repeat (50 Hz) against the hub's failsafe (200 ms).
            val pausedStatus = log.entries.filter { it.rxNs > pauseStartNs + 60_000_000L && it.rxNs < pauseEndNs }
            val maxPausedHold = pausedStatus.maxOfOrNull { it.holdUs } ?: Int.MAX_VALUE
            assertTrue("the phone slows to about 50 Hz while paused: $builtWhilePaused packets in $PAUSE_MS ms", builtWhilePaused in 15L..60L)
            assertTrue("STATUS kept coming while paused: ${pausedStatus.size}", pausedStatus.size >= 8)
            assertTrue("the hub heard the paused phone every <100 ms (failsafe 200 ms), max hold $maxPausedHold us", maxPausedHold < 100_000)

            assertEquals("idle", s.getString("exit_reason"))
            assertEquals(Le.unsigned(engine.epoch), s.getLong("epoch"))
            assertEquals(1L, s.getLong("epoch_changes"))
            assertEquals(built, s.getLong("accepted"))
            assertEquals(0L, s.getLong("missing"))
            assertEquals("controller", s.getString("mode"))
            assertEquals("ps", s.getString("style"))
            assertEquals("the phone paused, then went quiet", "paused", s.getString("state"))
            assertTaps(s, mapOf(PadButton.CROSS to 3, PadButton.L1 to 1, PadButton.R1 to 1))
            assertPresses(s, 0)
            val lp = s.getJSONObject("last_pad")
            assertEquals(Slp.FLAG_PAUSED or Slp.FLAG_MOTION or Slp.FLAG_STYLE_PS, lp.getInt("flags"))
            assertEquals("the PAUSED packets carried R1 held", Le.unsigned(1 shl PadButton.R1), lp.getLong("buttons"))
            assertEquals(-32767, lp.getInt("rx"))
            assertEquals(65535, lp.getInt("r2"))
            assertTrue(lp.getJSONArray("touch").getJSONObject(1).getBoolean("active"))
            assertEquals(160, lp.getJSONArray("gyro").getInt(0))
            // 12.4 rule 4: neutral while PAUSED, both now and at the moment the newest packet was applied.
            assertPadNeutral(s.getJSONObject("last_pad_frame"))
            assertPadNeutral(s.getJSONObject("applied_pad_frame"))
            assertEquals("ds4", s.getJSONObject("pad_output").getString("kind"))

            val result = JSONObject()
                .put("run", "pad_paused")
                .put("packets_built", built)
                .put("hub_accepted", s.getLong("accepted"))
                .put("hub_missing", s.getLong("missing"))
                .put("pause_ms", PAUSE_MS)
                .put("packets_built_while_paused", builtWhilePaused)
                .put("status_while_paused", pausedStatus.size)
                .put("max_hold_us_while_paused", maxPausedHold)
                .put("taps_emitted_by_name", s.getJSONObject("taps_emitted_by_name"))
                .put("state", s.getString("state"))
                .put("pad_output", s.getJSONObject("pad_output"))
            writeResult("pad_paused", result)
            println("interop pad_paused: $result")
        }
    }

    /**
     * PAD (e2): failsafe. A scripted PlayStation phone holds 12 buttons, both sticks, both triggers, two
     * touch fingers and motion, loses its last 10 packets (3 Cross taps inside them) and goes quiet. The
     * hub (forced to Xbox 360 output, 12.4 rule 5) replays the 3 taps, which run on past the failsafe
     * (12.4 rule 4: schedules finish), and the pad goes neutral with sticks centred.
     */
    @Test
    fun pad_e2_failsafeOnPadWithForcedXbox360Output() {
        val held = intArrayOf(
            PadButton.L1, PadButton.R1, PadButton.TRIANGLE, PadButton.CREATE, PadButton.OPTIONS, PadButton.HOME,
            PadButton.TOUCHPAD, PadButton.UP, PadButton.LEFT, PadButton.RIGHT, PadButton.MUTE, PadButton.SHARE,
        )
        val plan = PadPlan(
            name = "pad_failsafe",
            packets = 600,
            stylePs = true,
            notSentPercent = 10,
            taps = listOf(Tap(100, 10), Tap(590, 1), Tap(592, 1), Tap(594, 1)),
            expectedReplays = 3,
            forcedDrops = (590..599).toList().toIntArray(),
            finalFrom = 400,
            finalState = { p -> failsafeFinal(p, held) },
            useUdp = true,
            useTcp = false,
            seed = 0x50414434L,
            hubArgs = listOf("--pad-output", "x360"),
            padOutput = OUTPUT_X360,
            padKind = "x360",
            padSelected = "x360",
        )
        runPadScripted(plan) { s, final ->
            val expected = HashMap<Int, Int>()
            for (b in held) expected[b] = 1
            expected[PadButton.CROSS] = 4
            assertTaps(s, expected)
            // Applied with seq 600: the held buttons plus the first replayed Cross tap, already down.
            assertPadMapped(s.getJSONObject("applied_pad_frame"), final, final.buttons or (1 shl PadButton.CROSS))
            // After the failsafe and the replays: neutral, sticks centred (unlike the wheel's steering).
            assertPadNeutral(s.getJSONObject("last_pad_frame"))
        }
    }

    /** One tap: pressed in the packet [press], released [hold] packets later. */
    private class Tap(val press: Int, val hold: Int) {
        val release: Int get() = press + hold
    }

    private class PadPlan(
        val name: String,
        val packets: Int,
        val stylePs: Boolean,
        val notSentPercent: Int = 20,
        val taps: List<Tap>,
        val tapButton: Int = PadButton.CROSS,
        /** Taps the hub must replay (start itself) on [tapButton]; the others pass straight through. */
        val expectedReplays: Int,
        /** Taps made on the phone before the first packet: the hub takes them as its baseline. */
        val preTaps: Map<Int, Int> = emptyMap(),
        /** At this seq [jumpButton]'s counter goes up by 8 in one packet, released: no press (12.4 rule 3). */
        val jumpSeq: Int = 0,
        val jumpButton: Int = PadButton.SQUARE,
        /** Never sent. */
        val forcedDrops: IntArray = IntArray(0),
        /** Always sent (so a planned loss pattern is exactly what the hub sees). */
        val forcedKeeps: IntArray = IntArray(0),
        /** From this seq on the state is [finalState] (plus taps). */
        val finalFrom: Int,
        val finalState: (PadState) -> Unit,
        /** (after, old): after sending seq `after`, send packet `old` again (a late duplicate). */
        val replays: List<Pair<Int, Int>> = emptyList(),
        /** Before this seq, send a copy with the Cross counter's low bit flipped (the tag must fail). */
        val badTagSeqs: IntArray = IntArray(0),
        /** UDP: a malformed datagram here. TCP: a frame of a legal length with bad content. */
        val malformedSeqs: IntArray = IntArray(0),
        val splitSeqs: IntArray = IntArray(0),
        val coalesceSeqs: IntArray = IntArray(0),
        val useUdp: Boolean,
        val useTcp: Boolean,
        val tcpLag: Int = 0,
        /** Multipath: never sent on UDP; the only copy is the TCP one, [tcpLag] packets behind. */
        val udpOnlyDrops: IntArray = IntArray(0),
        val seed: Long,
        val hubArgs: List<String> = emptyList(),
        /** STATUS output byte once the pad is plugged in. */
        val padOutput: Int,
        /** pad_output.kind and .wanted in the hub stats. */
        val padKind: String,
        val padSelected: String = "auto",
    ) {
        val multipath: Boolean get() = useUdp && useTcp
    }

    /** Runs one scripted PAD plan against its own hub, checks what every run shares, then [checks]. */
    private fun runPadScripted(plan: PadPlan, checks: (JSONObject, PadFrame) -> Unit) {
        val n = plan.packets
        assertTrue(plan.taps.all { it.press >= 2 && it.release <= n })
        val drop = padDropSet(plan)
        val udpOnly = BooleanArray(n + 1).also { a -> for (q in plan.udpOnlyDrops) a[q] = true }
        val writer = PadPacketWriter(key)
        val pad = PadState()
        val frame = PadFrame()
        val epoch = LinkEngine.newEpoch()
        val stats = LinkStats(LinkEngine.TRANSPORT_SLOTS).also { it.reset(epoch) }
        val tUs = IntArray(n + 1)
        val sent = BooleanArray(n + 1)
        val bytes = arrayOfNulls<ByteArray>(n + 1)
        var replays = 0
        var badTags = 0
        var malformed = 0

        // The phone before its first packet: playing, its layout's flags, and some earlier taps.
        pad.setFlag(Slp.FLAG_PAUSED, false)
        pad.setFlag(Slp.FLAG_STYLE_PS, plan.stylePs)
        pad.setFlag(Slp.FLAG_MOTION, plan.stylePs)
        for ((b, count) in plan.preTaps) repeat(count) { tapNow(pad, b) }

        HubProcess(plan.name, extraArgs = plan.hubArgs).use { hub ->
            hub.awaitReady()
            val statuses = ConcurrentLinkedQueue<StatusRecord>()
            val udp = if (plan.useUdp) openUdp() else null
            val tcp = if (plan.useTcp) openTcp() else null
            val udpRx = udp?.let { UdpStatusReceiver(it, key, stats, statuses) }
            val tcpRx = tcp?.let { TcpStatusReceiver(it, key, stats, statuses) }
            val tcpOut = tcp?.let { TcpFrameWriter(it) }
            val lag = ArrayDeque<ByteArray>()
            val splitAt = intArrayOf(1, 2, 30)
            var splitIndex = 0
            fun sendPrimary(p: ByteArray) {
                if (udp != null) udpSend(udp, p) else tcpOut!!.send(p)
            }
            try {
                val t0 = System.nanoTime() + 5_000_000L
                for (seq in 1..n) {
                    sleepUntil(t0 + (seq - 1) * PERIOD_NS)
                    if (seq < plan.finalFrom) padMove(seq, pad, plan.stylePs) else if (seq == plan.finalFrom) plan.finalState(pad)
                    for (t in plan.taps) {
                        if (t.press == seq) pad.setButton(plan.tapButton, true)
                        if (t.release == seq) pad.setButton(plan.tapButton, false)
                    }
                    if (seq == plan.jumpSeq) repeat(8) { tapNow(pad, plan.jumpButton) }
                    pad.snapshot(frame)
                    frame.epoch = epoch
                    frame.seq = seq
                    frame.tUs = (System.nanoTime() / 1000L).toInt()
                    frame.rtt100us = stats.rtt100us
                    if (plan.multipath) frame.flags = frame.flags or Slp.FLAG_MULTIPATH
                    val packet = writer.write(frame).copyOf()
                    tUs[seq] = frame.tUs

                    if (seq in plan.badTagSeqs) {
                        val bad = packet.copyOf()
                        bad[32] = (bad[32].toInt() xor 1).toByte() // one more or one less Cross tap
                        sendPrimary(bad)
                        badTags++
                    }
                    if (!drop[seq]) {
                        sent[seq] = true
                        bytes[seq] = packet
                        if (udp != null && !udpOnly[seq]) udpSend(udp, packet)
                        if (tcpOut != null) {
                            lag.addLast(packet)
                            while (lag.size > plan.tcpLag) {
                                val p = lag.removeFirst()
                                val split = if (seq in plan.splitSeqs) splitAt[splitIndex++ % splitAt.size] else 0
                                tcpOut.send(p, split = split, coalesce = seq in plan.coalesceSeqs)
                            }
                        }
                    }
                    for ((after, old) in plan.replays) {
                        if (after != seq) continue
                        sendPrimary(bytes[old]!!)
                        replays++
                    }
                    if (seq in plan.malformedSeqs) {
                        val bad = if (udp != null) {
                            when (malformed % 5) {
                                0 -> packet.copyOf(Slp.PAD_LEN - 1) // 75 bytes
                                1 -> packet.copyOf(Slp.PAD_LEN + 1) // 77 bytes
                                2 -> packet.copyOf().also { it[2] = 2 } // version 2
                                3 -> packet.copyOf().also { it[3] = Slp.TYPE_INPUT } // INPUT type at PAD length
                                else -> packet.copyOf(Slp.INPUT_LEN) // PAD type at INPUT length
                            }
                        } else {
                            // Legal frame lengths, so the connection stays open (section 8), bad content.
                            if (malformed % 2 == 0) packet.copyOf().also { it[2] = 2 } else packet.copyOf(Slp.INPUT_LEN)
                        }
                        sendPrimary(bad)
                        malformed++
                    }
                }
                while (lag.isNotEmpty()) {
                    LockSupport.parkNanos(PERIOD_NS)
                    tcpOut!!.send(lag.removeFirst())
                }
                tcpOut?.flushHeld()
                val lastSendNs = System.nanoTime()

                if (udpRx != null) {
                    assertTrue("STATUS for seq $n arrives on UDP", waitFor(2000) { statuses.any { it.slot == SLOT_UDP && it.lastSeq == n } })
                }
                if (tcpRx != null) {
                    assertTrue("STATUS for seq $n arrives on TCP", waitFor(2000) { statuses.any { it.slot == SLOT_TCP && it.lastSeq == n } })
                }
                var closedByHub = false
                if (tcpOut != null && !plan.multipath) {
                    // Section 8: a frame whose length is neither 52 nor 76 closes the connection.
                    tcpOut.sendRaw(ByteArray(Framing.HEADER_LEN + Slp.PAD_LEN + 1).also { Le.putU16(it, 0, Slp.PAD_LEN + 1) })
                    closedByHub = waitFor(2000) { tcpRx!!.closed }
                    assertTrue("the hub closes a connection that sends a 77 byte frame", closedByHub)
                }

                val s = hub.awaitStats()
                udpRx?.stop()
                tcpRx?.stop()
                assertEquals("no STATUS failed its header or tag check", 0L, (udpRx?.rejected ?: 0L) + (tcpRx?.rejected ?: 0L))
                assertEquals("no bad STATUS frame length", false, tcpRx?.badFrame ?: false)

                // What reached the hub first: every sent packet on one path; in multipath the UDP copies.
                val delivered = BooleanArray(n + 1) { q -> q > 0 && sent[q] && !(plan.multipath && udpOnly[q]) }
                val deliveredUpTo = IntArray(n + 1)
                for (q in 1..n) deliveredUpTo[q] = deliveredUpTo[q - 1] + if (delivered[q]) 1 else 0
                val sentCount = sent.count { it }
                val summary = checkStatuses(
                    n, plan.useUdp, plan.useTcp, statuses.toList(), epoch, tUs, sent, deliveredUpTo, lastSendNs,
                    padOutput = plan.padOutput, exactCounts = !plan.multipath,
                )

                val final = PadFrame()
                assertTrue("the last packet decodes with the app's reader", PadPacketReader(key).read(bytes[n]!!, 0, Slp.PAD_LEN, final))
                checkPadHubStats(plan, s, epoch, sentCount, deliveredUpTo[n], replays, badTags, malformed, final)
                checks(s, final)
                assertEquals(
                    "taps the hub replayed on button ${plan.tapButton}: ${s.getJSONArray("taps_replayed")}",
                    plan.expectedReplays.toLong(),
                    s.getJSONArray("taps_replayed").getLong(plan.tapButton),
                )

                val tr = s.getJSONObject("transports")
                val result = JSONObject()
                    .put("run", plan.name)
                    .put("style", if (plan.stylePs) "ps" else "xbox")
                    .put("built", n)
                    .put("sent", sentCount)
                    .put("not_sent", n - sentCount)
                    .put("not_sent_on_udp_only", plan.udpOnlyDrops.size)
                    .put("replays", replays)
                    .put("bad_tags_sent", badTags)
                    .put("malformed_sent", malformed)
                    .put("hub_accepted", s.getLong("accepted"))
                    .put("hub_missing", s.getLong("missing"))
                    .put("hub_loss_percent", s.getDouble("loss_percent"))
                    .put("hub_transports", tr)
                    .put("state", s.getString("state"))
                    .put("taps_emitted", s.getJSONArray("taps_emitted"))
                    .put("taps_emitted_by_name", s.getJSONObject("taps_emitted_by_name"))
                    .put("taps_replayed", s.getJSONArray("taps_replayed"))
                    .put("pad_output", s.getJSONObject("pad_output"))
                    .put("tcp_split_writes", tcpOut?.splits ?: 0)
                    .put("tcp_coalesced_writes", tcpOut?.coalesced ?: 0)
                    .put("tcp_closed_on_bad_length", closedByHub)
                    .put("status", summary)
                writeResult(plan.name, result)
                println("interop ${plan.name}: $result")
            } finally {
                udpRx?.stop()
                tcpRx?.stop()
                try {
                    udp?.close()
                } catch (e: Exception) {
                    // closing
                }
                try {
                    tcp?.close()
                } catch (e: Exception) {
                    // closing
                }
            }
        }
    }

    /** Exactly max(notSentPercent, forced drops) packets never sent: forced ones, then a seeded pick. */
    private fun padDropSet(plan: PadPlan): BooleanArray {
        val n = plan.packets
        val drop = BooleanArray(n + 1)
        val keep = BooleanArray(n + 1)
        for (q in plan.forcedKeeps) keep[q] = true
        for ((_, old) in plan.replays) keep[old] = true
        keep[1] = true
        keep[n] = true
        for (q in plan.forcedDrops) {
            assertFalse("seq $q is both kept and dropped", keep[q])
            drop[q] = true
        }
        val target = maxOf(n * plan.notSentPercent / 100, plan.forcedDrops.size)
        var count = plan.forcedDrops.size
        val candidates = (2 until n).filter { !drop[it] && !keep[it] }.shuffled(java.util.Random(plan.seed))
        for (q in candidates) {
            if (count >= target) break
            drop[q] = true
            count++
        }
        assertEquals(target, drop.count { it })
        for (q in plan.udpOnlyDrops) assertFalse("udp-only drop $q is not dropped on every path", drop[q])
        return drop
    }

    /** The hub's own view of a scripted PAD run, from its stats JSON. */
    private fun checkPadHubStats(
        plan: PadPlan,
        s: JSONObject,
        epoch: Int,
        sentCount: Int,
        deliveredCount: Int,
        replays: Int,
        badTags: Int,
        malformed: Int,
        final: PadFrame,
    ) {
        val n = plan.packets
        assertEquals("idle", s.getString("exit_reason"))
        assertEquals("200 ms after the last packet the hub is in failsafe", "failsafe", s.getString("state"))
        assertEquals(Le.unsigned(epoch), s.getLong("epoch"))
        assertEquals(1L, s.getLong("epoch_changes"))
        assertEquals(n.toLong(), s.getLong("last_seq"))
        val accepted = s.getLong("accepted")
        val missing = s.getLong("missing")
        if (plan.multipath) {
            // A packet sent on TCP only arrives 20 ms late, after newer UDP ones: missing, then a duplicate.
            assertEquals(n.toLong(), accepted + missing)
            assertTrue("accepted $accepted in $deliveredCount..$n", accepted in deliveredCount.toLong()..n.toLong())
        } else {
            assertEquals("accepted = packets sent", sentCount.toLong(), accepted)
            assertEquals("missing = packets never sent", (n - sentCount).toLong(), missing)
        }
        assertEquals(accepted, s.getLong("total_accepted"))
        assertEquals(missing, s.getLong("total_missing"))
        assertPresses(s, 0)
        assertEquals("no INPUT was ever applied", 0L, s.getJSONObject("last_input").getLong("seq"))
        assertEquals("controller", s.getString("mode"))
        assertEquals(if (plan.stylePs) "ps" else "xbox", s.getString("style"))
        val po = s.getJSONObject("pad_output")
        assertEquals("pad output setting", plan.padSelected, po.getString("selected"))
        assertEquals("pad kind wanted", plan.padKind, po.getString("wanted"))
        assertEquals("pad kind plugged in", plan.padKind, po.getString("kind"))
        assertTrue("the pad is plugged in", po.getBoolean("plugged"))
        assertEquals("ready", po.getString("state"))

        val udp = s.getJSONObject("transports").getJSONObject("udp")
        val tcp = s.getJSONObject("transports").getJSONObject("tcp")
        if (plan.multipath) {
            assertEquals((sentCount - plan.udpOnlyDrops.size).toLong(), udp.getLong("packets"))
            assertEquals(sentCount.toLong(), tcp.getLong("packets"))
            assertEquals("each applied seq has one first arrival", accepted, udp.getLong("first_arrivals") + tcp.getLong("first_arrivals"))
            assertEquals(
                "every other copy is a duplicate",
                udp.getLong("packets") + tcp.getLong("packets") - accepted,
                udp.getLong("duplicates") + tcp.getLong("duplicates"),
            )
            assertTrue(
                "duplicates are counted on the slower path (TCP, 20 ms behind): ${tcp.getLong("duplicates")} of ${tcp.getLong("packets")}",
                tcp.getLong("duplicates") >= tcp.getLong("packets") * 99L / 100,
            )
            assertEquals(0L, udp.getLong("bad_tags") + tcp.getLong("bad_tags") + udp.getLong("malformed") + tcp.getLong("malformed"))
        } else {
            val used = if (plan.useUdp) udp else tcp
            val idle = if (plan.useUdp) tcp else udp
            assertEquals(0L, idle.getLong("packets"))
            assertEquals((sentCount + replays).toLong(), used.getLong("packets"))
            assertEquals(sentCount.toLong(), used.getLong("first_arrivals"))
            assertEquals("the late replays are the duplicates", replays.toLong(), used.getLong("duplicates"))
            assertEquals("tampered packets fail the tag", badTags.toLong(), used.getLong("bad_tags"))
            val expectedMalformed = malformed.toLong() + if (plan.useTcp) 1 else 0 // TCP: the 77 byte frame
            assertEquals(expectedMalformed, used.getLong("malformed"))
            assertEquals(0L, used.getLong("foreign_epoch"))
        }

        // The last PAD exactly as the hub decoded it from the wire, every field and all 18 counters.
        val lp = s.getJSONObject("last_pad")
        assertEquals(Le.unsigned(final.epoch), lp.getLong("epoch"))
        assertEquals(n.toLong(), lp.getLong("seq"))
        assertEquals(Le.unsigned(final.tUs), lp.getLong("t_us"))
        assertEquals(final.lx, lp.getInt("lx"))
        assertEquals(final.ly, lp.getInt("ly"))
        assertEquals(final.rx, lp.getInt("rx"))
        assertEquals(final.ry, lp.getInt("ry"))
        assertEquals(final.l2, lp.getInt("l2"))
        assertEquals(final.r2, lp.getInt("r2"))
        assertEquals(Le.unsigned(final.buttons), lp.getLong("buttons"))
        val taps = lp.getJSONArray("taps")
        for (b in 0 until Slp.PAD_BUTTONS) assertEquals("tap counter of button $b", final.taps[b].toInt(), taps.getInt(b))
        assertEquals(final.flags, lp.getInt("flags"))
        assertEquals(final.rtt100us, lp.getInt("rtt_100us"))
        val touch = lp.getJSONArray("touch")
        assertTouch(touch.getJSONObject(0), final.touch0X, final.touch0Y, final.touch0Id)
        assertTouch(touch.getJSONObject(1), final.touch1X, final.touch1Y, final.touch1Id)
        assertVector(lp.getJSONArray("gyro"), final.gyroX, final.gyroY, final.gyroZ)
        assertVector(lp.getJSONArray("accel"), final.accelX, final.accelY, final.accelZ)
    }

    private fun assertTouch(t: JSONObject, x: Int, y: Int, idByte: Int) {
        assertEquals("touch x", x, t.getInt("x"))
        assertEquals("touch y", y, t.getInt("y"))
        assertEquals("touch tracking id", idByte and 0x7F, t.getInt("id"))
        assertEquals("touch active", idByte and 0x80 != 0, t.getBoolean("active"))
    }

    private fun assertVector(a: JSONArray, x: Int, y: Int, z: Int) {
        assertEquals(3, a.length())
        assertEquals(x, a.getInt(0))
        assertEquals(y, a.getInt(1))
        assertEquals(z, a.getInt(2))
    }

    /** Presses the pad showed per canonical button (bit order of 12.2): exactly [expected], 0 elsewhere. */
    private fun assertTaps(s: JSONObject, expected: Map<Int, Int>) {
        val emitted = s.getJSONArray("taps_emitted")
        val pending = s.getJSONArray("taps_pending")
        assertEquals(Slp.PAD_BUTTONS, emitted.length())
        for (b in 0 until Slp.PAD_BUTTONS) {
            val want = expected[b] ?: 0
            assertEquals("presses on button $b (${PadButton.bothNames(b)}), no lost or doubled press: $emitted", want.toLong(), emitted.getLong(b))
            assertEquals("no tap left queued: $pending", 0, pending.getInt(b))
        }
    }

    /**
     * A pad frame from the hub's stats against PROTOCOL.md 12.5, computed from the phone's own frame
     * [f] and the button output [out] (held bits plus any replayed tap that is down).
     */
    private fun assertPadMapped(frame: JSONObject, f: PadFrame, out: Int) {
        val motion = f.flags and Slp.FLAG_MOTION != 0
        assertEquals("lx", f.lx, frame.getInt("lx"))
        assertEquals("ly", f.ly, frame.getInt("ly"))
        assertEquals("rx", f.rx, frame.getInt("rx"))
        assertEquals("ry", f.ry, frame.getInt("ry"))
        assertEquals("l2", f.l2, frame.getInt("l2"))
        assertEquals("r2", f.r2, frame.getInt("r2"))
        assertEquals("button output", Le.unsigned(out), frame.getLong("buttons"))
        val touch = frame.getJSONArray("touch")
        assertTouch(touch.getJSONObject(0), f.touch0X, f.touch0Y, f.touch0Id)
        assertTouch(touch.getJSONObject(1), f.touch1X, f.touch1Y, f.touch1Id)
        assertEquals("motion", motion, frame.getBoolean("motion"))
        if (motion) {
            assertVector(frame.getJSONArray("gyro"), f.gyroX, f.gyroY, f.gyroZ)
            assertVector(frame.getJSONArray("accel"), f.accelX, f.accelY, f.accelZ)
        } else {
            assertVector(frame.getJSONArray("gyro"), 0, 0, 0)
            assertVector(frame.getJSONArray("accel"), 0, 0, 0)
        }

        val x = frame.getJSONObject("x360")
        assertEquals("X360 left thumb X", f.lx, x.getInt("left_thumb_x"))
        assertEquals("X360 left thumb Y (+y up)", f.ly, x.getInt("left_thumb_y"))
        assertEquals("X360 right thumb X", f.rx, x.getInt("right_thumb_x"))
        assertEquals("X360 right thumb Y", f.ry, x.getInt("right_thumb_y"))
        assertEquals("X360 left trigger", f.l2 ushr 8, x.getInt("left_trigger"))
        assertEquals("X360 right trigger", f.r2 ushr 8, x.getInt("right_trigger"))
        assertEquals("X360 buttons", x360Buttons(out), x.getInt("buttons"))

        val d = frame.getJSONObject("ds4")
        assertEquals("DS4 left X", ds4Axis(f.lx), d.getInt("left_x"))
        assertEquals("DS4 left Y (0 at the top)", ds4AxisY(f.ly), d.getInt("left_y"))
        assertEquals("DS4 right X", ds4Axis(f.rx), d.getInt("right_x"))
        assertEquals("DS4 right Y", ds4AxisY(f.ry), d.getInt("right_y"))
        assertEquals("DS4 L2", f.l2 ushr 8, d.getInt("l2"))
        assertEquals("DS4 R2", f.r2 ushr 8, d.getInt("r2"))
        assertEquals("DS4 buttons word (hat, face, shoulders, digital L2 / R2)", ds4Buttons(out, f.l2, f.r2), d.getInt("buttons"))
        assertEquals("DS4 hat", ds4Hat(out), d.getInt("hat"))
        assertEquals("DS4 special (PS, touchpad click)", ds4Special(out), d.getInt("special"))
        val dt = d.getJSONArray("touch")
        assertDs4Touch(dt.getJSONObject(0), f.touch0X, f.touch0Y, f.touch0Id)
        assertDs4Touch(dt.getJSONObject(1), f.touch1X, f.touch1Y, f.touch1Id)
        // 12.5 leaves the DualShock 4 motion units to the hub; the direction of every axis must survive.
        val g = d.getJSONArray("gyro")
        val a = d.getJSONArray("accel")
        val src = if (motion) intArrayOf(f.gyroX, f.gyroY, f.gyroZ, f.accelX, f.accelY, f.accelZ) else IntArray(6)
        for (i in 0..2) {
            assertEquals("DS4 gyro $i keeps its sign", src[i].sign, g.getInt(i).sign)
            assertEquals("DS4 accel $i keeps its sign", src[3 + i].sign, a.getInt(i).sign)
        }
    }

    private fun assertDs4Touch(t: JSONObject, x: Int, y: Int, idByte: Int) {
        assertEquals("DS4 touch x (0..1919)", x * 1919 / 65535, t.getInt("x"))
        assertEquals("DS4 touch y (0..942)", y * 942 / 65535, t.getInt("y"))
        // The DualShock 4's finger byte is active low: bit 7 set while the finger is up.
        assertEquals("DS4 touch id byte", (idByte and 0x7F) or (if (idByte and 0x80 != 0) 0 else 0x80), t.getInt("id_byte"))
    }

    /** 12.4 rule 4 and rule 2: sticks centred, triggers 0, buttons up, fingers inactive, motion zero. */
    private fun assertPadNeutral(frame: JSONObject) {
        for (k in listOf("lx", "ly", "rx", "ry", "l2", "r2")) assertEquals("$k at rest", 0, frame.getInt(k))
        assertEquals("no button down", 0L, frame.getLong("buttons"))
        val touch = frame.getJSONArray("touch")
        for (i in 0..1) assertFalse("finger $i inactive", touch.getJSONObject(i).getBoolean("active"))
        assertFalse("motion off", frame.getBoolean("motion"))
        assertVector(frame.getJSONArray("gyro"), 0, 0, 0)
        assertVector(frame.getJSONArray("accel"), 0, 0, 0)
        val x = frame.getJSONObject("x360")
        for (k in listOf("left_thumb_x", "left_thumb_y", "right_thumb_x", "right_thumb_y", "left_trigger", "right_trigger", "buttons")) {
            assertEquals("X360 $k at rest", 0, x.getInt(k))
        }
        val d = frame.getJSONObject("ds4")
        for (k in listOf("left_x", "left_y", "right_x", "right_y")) assertEquals("DS4 $k centred", ds4Axis(0), d.getInt(k))
        assertEquals(0, d.getInt("l2"))
        assertEquals(0, d.getInt("r2"))
        assertEquals("DS4 buttons: hat released, nothing else", DS4_HAT_NONE, d.getInt("buttons"))
        assertEquals(DS4_HAT_NONE, d.getInt("hat"))
        assertEquals(0, d.getInt("special"))
        val dt = d.getJSONArray("touch")
        for (i in 0..1) assertTrue("DS4 finger $i up (bit 7)", dt.getJSONObject(i).getInt("id_byte") and 0x80 != 0)
        assertVector(d.getJSONArray("gyro"), 0, 0, 0)
        assertVector(d.getJSONArray("accel"), 0, 0, 0)
    }

    /** STATUS as the real LinkEngine hands it on (StatusSink), kept for checks after the run. */
    private class StatusLog : StatusSink {
        class Entry(val slot: Int, val rxNs: Long, val output: Int, val holdUs: Int, val lastSeq: Int)

        val entries = ConcurrentLinkedQueue<Entry>()

        override fun onStatus(slot: Int, status: StatusPacket, rxNs: Long) {
            entries.add(Entry(slot, rxNs, status.output, status.holdUs, status.lastSeq))
        }

        fun after(ns: Long): List<Entry> = entries.filter { it.rxNs > ns }

        /** The output byte over time, repeats collapsed. */
        fun outputSequence(): List<Int> {
            val out = ArrayList<Int>()
            for (e in entries.sortedBy { it.rxNs }) if (out.isEmpty() || out.last() != e.output) out += e.output
            return out
        }
    }

    // ------------------------------------------------------------------ transports ---

    private fun openUdp(): DatagramSocket = DatagramSocket().apply {
        try {
            trafficClass = Slp.TOS_EF
        } catch (e: Exception) {
            // DSCP is a hint; loopback ignores it anyway.
        }
        connect(InetSocketAddress(AdbTcpTransport.LOOPBACK_V4, udpPort))
        soTimeout = 50
    }

    private fun openTcp(): Socket = Socket().apply {
        tcpNoDelay = true
        connect(InetSocketAddress(AdbTcpTransport.LOOPBACK_V4, tcpPort), 3000)
    }

    private fun udpSend(socket: DatagramSocket, packet: ByteArray) {
        socket.send(DatagramPacket(packet, packet.size))
    }

    /** Frames INPUT or PAD with the app's [Framing]; can split one frame over two writes or join two in one. */
    private class TcpFrameWriter(socket: Socket) {
        private val out = socket.getOutputStream()
        private val buf = ByteArray(Framing.HEADER_LEN + Slp.MAX_PACKET_LEN)
        private var held: ByteArray? = null
        var splits = 0
        var coalesced = 0

        fun send(packet: ByteArray, split: Int = 0, coalesce: Boolean = false) {
            val len = Framing.write(packet, packet.size, buf, 0)
            val frame = buf.copyOf(len)
            val h = held
            if (h != null) {
                held = null
                out.write(h + frame)
                coalesced++
                return
            }
            if (coalesce) {
                held = frame
                return
            }
            if (split in 1 until len) {
                out.write(frame, 0, split)
                out.flush()
                LockSupport.parkNanos(1_000_000L)
                out.write(frame, split, len - split)
                splits++
                return
            }
            out.write(frame)
        }

        fun sendRaw(b: ByteArray) {
            flushHeld()
            out.write(b)
        }

        fun flushHeld() {
            held?.let { out.write(it) }
            held = null
        }
    }

    private class UdpStatusReceiver(
        private val socket: DatagramSocket,
        key: ByteArray,
        private val stats: LinkStats,
        private val into: ConcurrentLinkedQueue<StatusRecord>,
    ) {
        private val decoder = StatusDecoder(key)
        @Volatile private var running = true
        @Volatile var rejected = 0L
            private set
        private val thread = Thread({ loop() }, "interop-udp-rx").apply {
            isDaemon = true
            start()
        }

        private fun loop() {
            val buf = ByteArray(256)
            val pkt = DatagramPacket(buf, buf.size)
            val st = StatusPacket()
            while (running) {
                pkt.setLength(buf.size)
                try {
                    socket.receive(pkt)
                } catch (e: SocketTimeoutException) {
                    continue
                } catch (e: PortUnreachableException) {
                    continue
                } catch (e: IOException) {
                    if (!running || socket.isClosed) break
                    continue
                }
                val rxNs = System.nanoTime()
                if (!decoder.decode(buf, 0, pkt.length, st)) {
                    rejected = decoder.rejectedHeader + decoder.rejectedTag
                    continue
                }
                into.add(record(SLOT_UDP, st, rxNs))
                stats.onStatus(LinkEngine.SLOT_WIFI, st, rxNs)
            }
        }

        fun stop() {
            running = false
            thread.join(1000)
        }
    }

    private class TcpStatusReceiver(
        private val socket: Socket,
        key: ByteArray,
        private val stats: LinkStats,
        private val into: ConcurrentLinkedQueue<StatusRecord>,
    ) {
        private val decoder = StatusDecoder(key)
        private val assembler = FrameAssembler(Slp.STATUS_LEN)
        private val st = StatusPacket()
        @Volatile private var running = true
        @Volatile var rejected = 0L
            private set
        @Volatile var badFrame = false
            private set
        /** True once the hub closed the connection (EOF or reset). */
        @Volatile var closed = false
            private set
        private val sink = FrameSink { frame, len ->
            val rxNs = System.nanoTime()
            if (decoder.decode(frame, 0, len, st)) {
                into.add(record(SLOT_TCP, st, rxNs))
                stats.onStatus(LinkEngine.SLOT_USB, st, rxNs)
            } else {
                rejected = decoder.rejectedHeader + decoder.rejectedTag
            }
        }
        private val thread = Thread({ loop() }, "interop-tcp-rx").apply {
            isDaemon = true
            start()
        }

        private fun loop() {
            val input = socket.getInputStream()
            val buf = ByteArray(1024)
            try {
                while (running) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (!assembler.feed(buf, 0, n, sink)) {
                        badFrame = true
                        break
                    }
                }
            } catch (e: IOException) {
                // reset by the hub, or closed by stop()
            }
            closed = true
        }

        fun stop() {
            running = false
            try {
                socket.shutdownInput()
            } catch (e: IOException) {
                // already closed
            }
            thread.join(1000)
        }
    }

    // ------------------------------------------------------------------- the hub ---

    /** One `slipstream hub` process for one run, on the interop ports, stopping itself when idle. */
    private inner class HubProcess(
        private val run: String,
        beaconPort: Int? = null,
        extraArgs: List<String> = emptyList(),
    ) : AutoCloseable {
        private val statsFile = File(Env.out, "hub-$run.json").also { it.delete() }
        private val logFile = File(Env.out, "hub-$run.log")
        private val lines = CopyOnWriteArrayList<String>()
        private val ready = CountDownLatch(1)
        @Volatile private var bindError: String? = null
        private val process: Process

        /** System.nanoTime() when the hub reported both ports. */
        @Volatile var readyNs = 0L
            private set

        init {
            val hub = Env.hub!!
            val cmd = mutableListOf<String>()
            if (hub.endsWith(".dll")) cmd += Env.dotnet
            cmd += listOf(
                hub, "hub", "--code", CODE, "--name", "INTEROP-$run",
                "--port", udpPort.toString(), "--tcp-port", tcpPort.toString(),
                "--no-adb", "--no-qr",
                "--seconds", HUB_MAX_SECONDS.toString(), "--exit-idle-ms", EXIT_IDLE_MS.toString(),
                "--stats-json", statsFile.absolutePath,
            )
            cmd += if (beaconPort != null) listOf("--beacon-port", beaconPort.toString()) else listOf("--no-beacon")
            cmd += extraArgs
            process = ProcessBuilder(cmd).redirectErrorStream(true).start()
            process.outputStream.close()
            Thread({ pump() }, "interop-hub-$run").apply {
                isDaemon = true
                start()
            }
        }

        private fun pump() {
            var udpUp = false
            var tcpUp = false
            try {
                process.inputStream.bufferedReader().forEachLine { line ->
                    lines += line
                    if (Regex("^\\s*udp\\s+listening on $udpPort\\b").containsMatchIn(line)) udpUp = true
                    if (line.contains("listening on 127.0.0.1:$tcpPort")) tcpUp = true
                    if (line.contains("in use") || line.contains("Cannot listen")) {
                        bindError = line.trim()
                        ready.countDown()
                    }
                    if (udpUp && tcpUp && readyNs == 0L) {
                        readyNs = System.nanoTime()
                        ready.countDown()
                    }
                }
            } catch (e: IOException) {
                // process gone
            }
            ready.countDown()
        }

        fun log(): String = lines.joinToString("\n")

        fun awaitReady() {
            assertTrue("hub did not report its ports within 20 s:\n${log()}", ready.await(20, TimeUnit.SECONDS))
            assertNull("hub could not bind its ports:\n${log()}", bindError)
            assertTrue("hub exited during start:\n${log()}", process.isAlive)
        }

        /** Waits for the hub to exit on its own (idle), then reads its stats file. */
        fun awaitStats(): JSONObject {
            val exited = process.waitFor(EXIT_IDLE_MS + 8000L, TimeUnit.MILLISECONDS)
            assertTrue("hub did not exit after the phone went quiet:\n${log()}", exited)
            assertEquals("hub exit code:\n${log()}", 0, process.exitValue())
            assertTrue("hub wrote no stats file:\n${log()}", statsFile.exists())
            return JSONObject(statsFile.readText())
        }

        override fun close() {
            if (process.isAlive) {
                process.destroy()
                if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly()
            }
            logFile.writeText(log() + "\n")
        }
    }

    private fun writeResult(name: String, o: JSONObject) {
        File(Env.out, "result-$name.json").writeText(o.toString(2) + "\n")
    }

    companion object {
        private const val CODE = "SLIPSTREAMTEST22"
        private const val PERIOD_NS = 2_000_000L // 500 Hz
        private const val EXIT_IDLE_MS = 600 // longer than the 200 ms failsafe
        private const val HUB_MAX_SECONDS = 90
        private const val RTT_MAX_US = 50_000
        private const val SLOT_UDP = 0
        private const val SLOT_TCP = 1

        /** The shift-up counter starts here, so the 15 presses wrap it past 255 to 9. */
        private const val PULSE0_START = 250

        private const val FINAL_STEER = -12345
        private const val FINAL_THROTTLE = 54321
        private const val FINAL_BRAKE = 777
        private const val FINAL_CLUTCH = 32768
        private const val FINAL_HANDBRAKE = 65535
        private const val FINAL_BUTTONS = 0x00A5_0F01

        fun vjoySteer(steer: Int): Long = 1 + ((steer + 32767).toLong() * 32767 + 32767) / 65534
        fun vjoyPedal(p: Int): Long = 1 + (p.toLong() * 32767 + 32767) / 65535

        // ------------------------------------------------------- controller mode ---

        /** STATUS output byte of the pad kinds (the hub sends 3 for the DualShock 4, see TESTING.md). */
        private const val OUTPUT_X360 = Slp.OUTPUT_X360
        private const val OUTPUT_DS4 = 3
        private const val PAUSE_MS = 600L
        private const val DS4_HAT_NONE = 8

        /**
         * 12 Cross taps over 2000 packets, each press and each release in its own packet. With
         * [LOSSY_TAP_DROPS] and [LOSSY_TAP_KEEPS]: tap 2 loses its press and every packet that held it,
         * so the release packet's counter replays it; tap 3 loses its release; tap 4 is one packet
         * long; tap 5 is lost whole, release included; tap 6 loses its press but the next packet still
         * holds it (it shows once, no replay); taps 8 and 9 are a double tap lost whole (+2 in one
         * packet); tap 11 loses its release and the gap after it, so tap 12 is pressed while tap 11
         * still shows (a gap first) and released while that gap runs, so it is replayed after it.
         * That makes [LOSSY_REPLAYS_EXPECTED] replayed taps: 2, 5, 8, 9 and 12.
         */
        private val LOSSY_TAPS = listOf(
            Tap(100, 10), Tap(220, 8), Tap(340, 12), Tap(460, 1), Tap(580, 3), Tap(700, 10),
            Tap(820, 25), Tap(940, 2), Tap(944, 2), Tap(1060, 5), Tap(1180, 6), Tap(1190, 5),
        )
        private val LOSSY_TAP_DROPS = intArrayOf(
            220, 221, 222, 223, 224, 225, 226, 227, 352, 580, 581, 582, 583, 700,
            940, 941, 942, 943, 944, 945, 946, 1186, 1187, 1188, 1189,
        )
        private const val LOSSY_REPLAYS_EXPECTED = 5
        private val LOSSY_TAP_KEEPS = intArrayOf(100, 228, 340, 353, 460, 461, 579, 584, 701, 939, 947, 1180, 1190, 1195)

        /** Late copies of press packets (and two others): the hub must drop them, not press again. */
        private val LATE_COPIES = listOf(115 to 100, 470 to 460, 715 to 701, 1000 to 990, 1500 to 1490)

        /** DualShock 4 stick byte (12.5): ((v + 32767) * 255 + 32767) div 65534. */
        fun ds4Axis(v: Int): Int {
            val s = maxOf(v, -32767).toLong()
            return (((s + 32767) * 255 + 32767) / 65534).toInt()
        }

        /** DualShock 4 Y is 0 at the top: the formula on -v (12.5). */
        fun ds4AxisY(v: Int): Int = ds4Axis(-maxOf(v, -32767))

        private fun held(out: Int, bit: Int): Boolean = out and (1 shl bit) != 0

        /** The hat: 0 north, clockwise to 7 north-west, 8 released; opposite directions cancel (12.5). */
        fun ds4Hat(out: Int): Int {
            val north = held(out, PadButton.UP) && !held(out, PadButton.DOWN)
            val south = held(out, PadButton.DOWN) && !held(out, PadButton.UP)
            val east = held(out, PadButton.RIGHT) && !held(out, PadButton.LEFT)
            val west = held(out, PadButton.LEFT) && !held(out, PadButton.RIGHT)
            return when {
                north && east -> 1
                south && east -> 3
                south && west -> 5
                north && west -> 7
                north -> 0
                east -> 2
                south -> 4
                west -> 6
                else -> DS4_HAT_NONE
            }
        }

        /**
         * The DualShock 4 report's button word (bytes 5 and 6 of the USB report): hat in bits 0..3, then
         * Square, Cross, Circle, Triangle, L1, R1, L2, R2, Share, Options, L3, R3. Canonical bits 0..9 go
         * to Cross, Circle, Square, Triangle, L1, R1, L3, R3, Share (Create), Options; the digital L2 / R2
         * bits are set while the trigger's byte is 8 or more.
         */
        fun ds4Buttons(out: Int, l2: Int, r2: Int): Int {
            val table = intArrayOf(0x0020, 0x0040, 0x0010, 0x0080, 0x0100, 0x0200, 0x4000, 0x8000, 0x1000, 0x2000)
            var w = ds4Hat(out)
            for (bit in table.indices) if (held(out, bit)) w = w or table[bit]
            if ((l2 ushr 8) >= 8) w = w or 0x0400
            if ((r2 ushr 8) >= 8) w = w or 0x0800
            return w
        }

        /** Report byte 7: bit 0 PS (canonical 10), bit 1 touchpad click (canonical 11). Mute is not sent. */
        fun ds4Special(out: Int): Int =
            (if (held(out, PadButton.HOME)) 0x01 else 0) or (if (held(out, PadButton.TOUCHPAD)) 0x02 else 0)

        /**
         * XInput button word: canonical 0..10 to A, B, X, Y, LB, RB, LS, RS, Back, Start, Guide and 12..15 to
         * the D-pad (12.5). Bits 11, 16 and 17 have no Xbox 360 equivalent.
         */
        fun x360Buttons(out: Int): Int {
            val table = intArrayOf(
                0x1000, 0x2000, 0x4000, 0x8000, 0x0100, 0x0200, 0x0040, 0x0080, 0x0020, 0x0010, 0x0400, 0,
                0x0001, 0x0002, 0x0004, 0x0008,
            )
            var w = 0
            for (bit in table.indices) if (held(out, bit)) w = w or table[bit]
            return w
        }

        /** Press and release at once (between two packets): one tap counter step, never held on the wire. */
        private fun tapNow(pad: PadState, bit: Int) {
            pad.setButton(bit, true)
            pad.setButton(bit, false)
        }

        /** A real-time tap for the LinkEngine runs: 30 ms down, then 120 ms up. */
        private fun tapTimed(pad: PadState, bit: Int) {
            pad.setButton(bit, true)
            Thread.sleep(30)
            pad.setButton(bit, false)
            Thread.sleep(120)
        }

        /** The moving part of a scripted PAD run: sticks, triggers, fingers and motion change every packet. */
        private fun padMove(seq: Int, pad: PadState, stylePs: Boolean) {
            val a = seq * 0.0125
            pad.setStick(0, (sin(a) * 32767.0).roundToInt(), (cos(a) * 32767.0).roundToInt())
            pad.setStick(1, (sin(a * 1.7) * 30000.0).roundToInt(), (-cos(a * 0.6) * 32767.0).roundToInt())
            pad.setTrigger(0, (seq * 331) and 0xFFFF)
            pad.setTrigger(1, (seq * 997 + 12345) and 0xFFFF)
            if (!stylePs) return
            pad.setTouch(0, seq % 300 < 150, (seq / 300) and 0x7F, (seq * 211) and 0xFFFF, (seq * 97) and 0xFFFF)
            pad.setTouch(1, seq % 500 < 100, (40 + seq / 500) and 0x7F, (seq * 57) and 0xFFFF, (seq * 389) and 0xFFFF)
            pad.setGyro((seq * 37) % 4000 - 2000, (seq * 53) % 6000 - 3000, (sin(a) * 30000.0).roundToInt())
            pad.setAccel((seq * 7) % 8192 - 4096, 4096, -((seq * 11) % 4096))
        }

        /**
         * Final PlayStation state of runs pad_a and pad_c: a stick past full left (the phone clamps it to
         * -32767), L2 one step below the digital threshold and R2 on it, one finger down, one lifted.
         */
        private fun psFinal(pad: PadState) {
            pad.setStick(0, -40000, 12345)
            pad.setStick(1, 32767, -20000)
            pad.setTrigger(0, 2047)
            pad.setTrigger(1, 2048)
            pad.setTouch(0, true, 5, 65535, 0)
            pad.setTouch(1, false, 6, 1234, 54321)
            pad.setGyro(-100, 200, 32767)
            pad.setAccel(4096, -8192, 20000)
        }

        /** Final Xbox state of run pad_b: no touchpad, no motion. */
        private fun xboxFinal(pad: PadState) {
            pad.setStick(0, 12000, -32767)
            pad.setStick(1, -1, 32767)
            pad.setTrigger(0, 65535)
            pad.setTrigger(1, 255)
            pad.setTouch(0, false, 0, 0, 0)
            pad.setTouch(1, false, 0, 0, 0)
        }

        /** Final state of run pad_failsafe: [held] buttons down (D-pad up with left and right: the hat says north). */
        private fun failsafeFinal(pad: PadState, held: IntArray) {
            for (b in held) pad.setButton(b, true)
            pad.setStick(0, 32767, -32767)
            pad.setStick(1, 0, 1)
            pad.setTrigger(0, 65535)
            pad.setTrigger(1, 0)
            pad.setTouch(0, true, 127, 32768, 32768)
            pad.setTouch(1, true, 0, 0, 65535)
            pad.setGyro(16, -16, 0)
            pad.setAccel(0, 4096, -4096)
        }

        private fun record(slot: Int, st: StatusPacket, rxNs: Long): StatusRecord {
            val nowUs = (rxNs / 1000L).toInt()
            return StatusRecord(
                slot = slot,
                rxNs = rxNs,
                epoch = st.epoch,
                lastSeq = st.lastSeq,
                echoTUs = st.echoTUs,
                holdUs = st.holdUs,
                accepted = st.accepted,
                missing = st.missing,
                output = st.output,
                hubFlags = st.hubFlags,
                rumbleStrong = st.rumbleStrong,
                rumbleWeak = st.rumbleWeak,
                rttUs = RttMath.rttUs(nowUs, st.echoTUs, st.holdUs),
            )
        }

        private fun sleepUntil(deadlineNs: Long) {
            while (true) {
                val left = deadlineNs - System.nanoTime()
                if (left <= 0) return
                if (left > 300_000L) LockSupport.parkNanos(left - 200_000L) else Thread.onSpinWait()
            }
        }

        private fun waitFor(timeoutMs: Long, cond: () -> Boolean): Boolean {
            val end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            while (System.nanoTime() < end) {
                if (cond()) return true
                Thread.sleep(5)
            }
            return cond()
        }
    }
}
