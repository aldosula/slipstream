package com.slipstream.wheel

import com.slipstream.wheel.input.ControllerState
import com.slipstream.wheel.input.PulseCounters
import com.slipstream.wheel.link.AdbTcpTransport
import com.slipstream.wheel.link.BeaconGate
import com.slipstream.wheel.link.LinkConfig
import com.slipstream.wheel.link.LinkEngine
import com.slipstream.wheel.link.LinkStats
import com.slipstream.wheel.link.RttMath
import com.slipstream.wheel.protocol.Beacon
import com.slipstream.wheel.protocol.FrameAssembler
import com.slipstream.wheel.protocol.FrameSink
import com.slipstream.wheel.protocol.Framing
import com.slipstream.wheel.protocol.InputFrame
import com.slipstream.wheel.protocol.InputPacketWriter
import com.slipstream.wheel.protocol.Le
import com.slipstream.wheel.protocol.PairingCode
import com.slipstream.wheel.protocol.PairingKey
import com.slipstream.wheel.protocol.Seq
import com.slipstream.wheel.protocol.Slp
import com.slipstream.wheel.protocol.StatusDecoder
import com.slipstream.wheel.protocol.StatusPacket
import org.json.JSONObject
import org.junit.Assert.assertEquals
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
import kotlin.math.roundToInt
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
                val summary = checkStatuses(plan, recs, epoch, tUs, sent, sentUpTo, lastSendNs)
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

    /** (b): every STATUS decoded and tag-checked by the app's decoder, then checked field by field. */
    private fun checkStatuses(
        plan: Plan,
        recs: List<StatusRecord>,
        epoch: Int,
        tUs: IntArray,
        sent: BooleanArray,
        sentUpTo: IntArray,
        lastSendNs: Long,
    ): JSONObject {
        val n = plan.packets
        val out = JSONObject()
        val slots = buildList {
            if (plan.useUdp) add(SLOT_UDP)
            if (plan.useTcp) add(SLOT_TCP)
        }
        var maxHoldActive = 0
        for (slot in slots) {
            val mine = recs.filter { it.slot == slot }.sortedBy { it.rxNs }
            val label = if (slot == SLOT_UDP) "udp" else "tcp"
            assertTrue("STATUS received on $label", mine.isNotEmpty())
            var prevSeq = 0
            for (r in mine) {
                assertEquals("STATUS epoch is ours", epoch, r.epoch)
                val l = r.lastSeq
                assertTrue("last_seq $l is a seq that was sent", l in 1..n && sent[l])
                assertEquals("echo_t_us is the t_us of packet last_seq $l", tUs[l], r.echoTUs)
                assertEquals("accepted at last_seq $l", sentUpTo[l], r.accepted)
                assertEquals("missing at last_seq $l", l - sentUpTo[l], r.missing)
                assertEquals("output byte of the headless hub (none, no error)", 0, r.output)
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
            out.put(
                label,
                JSONObject()
                    .put("count", mine.size)
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

    /** Frames INPUT with the app's [Framing]; can split one frame over two writes or join two in one. */
    private class TcpFrameWriter(socket: Socket) {
        private val out = socket.getOutputStream()
        private val buf = ByteArray(Framing.HEADER_LEN + Slp.INPUT_LEN)
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
    private inner class HubProcess(private val run: String, beaconPort: Int? = null) : AutoCloseable {
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
