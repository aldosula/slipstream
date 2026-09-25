package com.slipstream.wheel

import com.slipstream.wheel.input.ControllerState
import com.slipstream.wheel.link.LinkConfig
import com.slipstream.wheel.link.LinkEngine
import com.slipstream.wheel.link.LinkStats
import com.slipstream.wheel.link.PacketSource
import com.slipstream.wheel.pad.PadButton
import com.slipstream.wheel.pad.PadState
import com.slipstream.wheel.protocol.Framing
import com.slipstream.wheel.protocol.InputFrame
import com.slipstream.wheel.protocol.InputPacketReader
import com.slipstream.wheel.protocol.Le
import com.slipstream.wheel.protocol.PacketAuth
import com.slipstream.wheel.protocol.PadFrame
import com.slipstream.wheel.protocol.PadPacketReader
import com.slipstream.wheel.protocol.PairingCode
import com.slipstream.wheel.protocol.Seq
import com.slipstream.wheel.protocol.Slp
import com.slipstream.wheel.protocol.TapNibbles
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Random
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Controller mode end to end over real loopback sockets: the phone's LinkEngine with a
 * PadState against a fake hub that applies PROTOCOL.md sections 9 and 12.4 (tag, epoch,
 * sequence, mode switch, tap scheduler) and counts the presses its virtual buttons make.
 */
class PadLinkTest {
    private val key = PairingCode.deriveKey("SLIPSTREAMTEST22")
    private val loopback: InetAddress = InetAddress.getByName("127.0.0.1")

    private fun waitFor(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < end) {
            if (cond()) return true
            Thread.sleep(5)
        }
        return cond()
    }

    private fun statusFor(epoch: Int, seq: Int, tUs: Int, accepted: Int, missing: Int): ByteArray {
        val b = ByteArray(Slp.STATUS_LEN)
        Slp.writeHeader(b, 0, Slp.TYPE_STATUS)
        Le.putU32(b, 4, epoch)
        Le.putU32(b, 8, seq)
        Le.putU32(b, 12, tUs)
        Le.putU32(b, 20, accepted)
        Le.putU32(b, 24, missing)
        b[32] = Slp.OUTPUT_X360.toByte()
        PacketAuth(key).sign(b, Slp.STATUS_BODY_LEN)
        return b
    }

    /**
     * The hub's receive rules for one session. The tap scheduler is modelled by what it
     * emits, not by its timing: on each accepted PAD packet, per button, d = tap delta; if
     * d > 0 the schedule releases (if the button is down), replays `held ? d - 1 : d` taps,
     * then follows the held bit; if d = 0 the button follows the held bit. Every 0 to 1
     * transition of the virtual button is one press seen by the game.
     */
    private class HubModel {
        var epoch = 0
        var hasEpoch = false
        var lastSeq = 0
        var lastType = 0
        var accepted = 0
        var missing = 0
        var modeSwitches = 0
        val taps = IntArray(Slp.PAD_BUTTONS)
        val down = BooleanArray(Slp.PAD_BUTTONS)
        val presses = IntArray(Slp.PAD_BUTTONS)
        var bigDeltas = 0
        val last = PadFrame()

        /** Returns true when the packet was accepted. */
        fun accept(type: Int, f: PadFrame?, epochIn: Int, seqIn: Int): Boolean {
            if (!hasEpoch || epochIn != epoch) {
                // A single phone here: adopt at once (the 300 ms takeover guard needs a rival).
                hasEpoch = true
                epoch = epochIn
                lastSeq = seqIn
                accepted = 1
                missing = 0
                lastType = type
                if (f != null) baseline(f)
                return true
            }
            if (!Seq.newer(seqIn, lastSeq)) return false
            val d = seqIn - lastSeq
            if (d > 1) missing += d - 1
            lastSeq = seqIn
            accepted++
            if (type != lastType) {
                // Mode switch (12.4 rule 2): previous device neutral, counters are the new baseline.
                modeSwitches++
                lastType = type
                if (f != null) baseline(f)
                return true
            }
            if (f != null) apply(f)
            return true
        }

        private fun baseline(f: PadFrame) {
            for (b in 0 until Slp.PAD_BUTTONS) {
                taps[b] = f.taps[b].toInt()
                follow(b, f)
            }
            copy(f)
        }

        private fun apply(f: PadFrame) {
            val paused = f.flags and Slp.FLAG_PAUSED != 0
            for (b in 0 until Slp.PAD_BUTTONS) {
                val n = f.taps[b].toInt()
                val raw = (n - taps[b]) and 0xF
                val d = TapNibbles.delta(n, taps[b])
                if (raw >= 8) bigDeltas++
                taps[b] = n
                val held = !paused && (f.buttons ushr b) and 1 != 0
                if (d > 0) {
                    val replay = if ((f.buttons ushr b) and 1 != 0) d - 1 else d
                    down[b] = false // gap_first when it was down; a no-op otherwise
                    presses[b] += replay
                }
                if (held && !down[b]) presses[b]++
                down[b] = held
            }
            copy(f)
        }

        private fun follow(b: Int, f: PadFrame) {
            val held = f.flags and Slp.FLAG_PAUSED == 0 && (f.buttons ushr b) and 1 != 0
            if (held && !down[b]) presses[b]++
            down[b] = held
        }

        private fun copy(f: PadFrame) {
            last.lx = f.lx; last.ly = f.ly; last.rx = f.rx; last.ry = f.ry
            last.l2 = f.l2; last.r2 = f.r2; last.buttons = f.buttons; last.flags = f.flags
            last.touch0Id = f.touch0Id; last.touch0X = f.touch0X; last.touch0Y = f.touch0Y
        }
    }

    /** Fake UDP hub with seeded random loss on receive. */
    private inner class LossyHub(private val lossPercent: Int, seed: Long) : AutoCloseable {
        val socket = DatagramSocket(InetSocketAddress(loopback, 0)).apply { soTimeout = 50 }
        val model = HubModel()
        val types = CopyOnWriteArrayList<Int>()
        val seqs = CopyOnWriteArrayList<Int>()
        val times = CopyOnWriteArrayList<Int>()
        @Volatile var received = 0
        @Volatile var dropped = 0
        @Volatile var rejected = 0
        @Volatile var minus32768 = 0
        private val random = Random(seed)
        private val running = AtomicBoolean(true)
        private val lock = Any()
        private val thread = Thread {
            val padReader = PadPacketReader(key)
            val inputReader = InputPacketReader(key)
            val pf = PadFrame()
            val inf = InputFrame()
            val buf = ByteArray(256)
            val pkt = DatagramPacket(buf, buf.size)
            while (running.get()) {
                pkt.setLength(buf.size)
                try {
                    socket.receive(pkt)
                } catch (e: SocketTimeoutException) {
                    continue
                } catch (e: Exception) {
                    break
                }
                received++
                if (random.nextInt(100) < lossPercent) {
                    dropped++
                    continue
                }
                val type = buf[3].toInt()
                var tUs = 0
                var ok = true
                val st = synchronized(lock) {
                    var seq = 0
                    if (padReader.read(buf, 0, pkt.length, pf)) {
                        for (off in intArrayOf(16, 18, 20, 22)) if (Le.i16(buf, off) == -32768) minus32768++
                        seq = pf.seq
                        tUs = pf.tUs
                        model.accept(Slp.TYPE_PAD.toInt(), pf, pf.epoch, seq)
                    } else if (inputReader.read(buf, 0, pkt.length, inf)) {
                        seq = inf.seq
                        tUs = inf.tUs
                        model.accept(Slp.TYPE_INPUT.toInt(), null, inf.epoch, seq)
                    } else {
                        rejected++
                        ok = false
                    }
                    if (ok) {
                        types.add(type)
                        seqs.add(seq)
                        times.add(tUs)
                    }
                    statusFor(model.epoch, model.lastSeq, tUs, model.accepted, model.missing)
                }
                if (!ok) continue
                try {
                    socket.send(DatagramPacket(st, st.size, pkt.socketAddress))
                } catch (e: Exception) {
                    break
                }
            }
        }.apply { start() }
        val port: Int get() = socket.localPort

        fun <T> read(block: (HubModel) -> T): T = synchronized(lock) { block(model) }

        override fun close() {
            running.set(false)
            socket.close()
            thread.join(1000)
        }
    }

    private fun padEngine(pad: PadState, port: Int, source: PacketSource = PacketSource.PAD) = LinkEngine(
        ControllerState(),
        pad,
        LinkConfig(key = key, host = loopback, udpPort = port, useWifi = true, useUsb = false, rateHz = 500),
        null,
        source,
    )

    @Test
    fun tapsSurviveTwentyPercentLossWithoutLostOrDoubledPresses() {
        LossyHub(lossPercent = 20, seed = 0x5A1D_2026L).use { hub ->
            val pad = PadState()
            pad.setFlag(Slp.FLAG_PAUSED, false)
            pad.setFlag(Slp.FLAG_STYLE_PS, true)
            val engine = padEngine(pad, hub.port)
            engine.start()
            val made = IntArray(Slp.PAD_BUTTONS)
            try {
                assertTrue("packets arrive", waitFor(3000) { hub.received >= 20 })
                val rnd = Random(42)
                val buttons = intArrayOf(
                    PadButton.CROSS, PadButton.CIRCLE, PadButton.SQUARE, PadButton.TRIANGLE,
                    PadButton.R1, PadButton.L3, PadButton.UP, PadButton.RIGHT, PadButton.TOUCHPAD, PadButton.SHARE,
                )
                val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(2500)
                var step = 0
                while (System.nanoTime() < deadline || step < 150) {
                    val b = buttons[rnd.nextInt(buttons.size)]
                    when (step % 5) {
                        0, 1 -> { // an ordinary press
                            pad.setButton(b, true)
                            made[b]++
                            Thread.sleep(3L + rnd.nextInt(12))
                            pad.setButton(b, false)
                        }
                        2 -> { // a tap quicker than the 1 ms packet spacing: one snapshot sees nothing held
                            pad.setButton(b, true)
                            pad.setButton(b, false)
                            made[b]++
                        }
                        3 -> { // a double tap inside one packet interval
                            repeat(2) {
                                pad.setButton(b, true)
                                pad.setButton(b, false)
                                made[b]++
                            }
                        }
                        else -> { // a hold across many packets, released, pressed again at once
                            pad.setButton(b, true)
                            made[b]++
                            Thread.sleep(20)
                            pad.setButton(b, false)
                            pad.setButton(b, true)
                            made[b]++
                            Thread.sleep(2)
                            pad.setButton(b, false)
                        }
                    }
                    pad.setStick(0, (step * 997) % 65534 - 32767, -(step * 331) % 32767)
                    Thread.sleep(2L + rnd.nextInt(6))
                    step++
                }
                pad.setStick(0, 12345, -23456)
                pad.setTrigger(1, 40000)
                pad.setTouch(0, true, 3, 1000, 2000)
                Thread.sleep(150) // idle repeats carry the final state through the loss
                val snap = LinkStats.Snapshot(LinkEngine.TRANSPORT_SLOTS)
                engine.stats.snapshot(snap, System.nanoTime())
                assertEquals(LinkStats.TransportState.LIVE, snap.state[LinkEngine.SLOT_WIFI])
                assertTrue("loss seen by LinkStats", snap.lossTotal > 0.1 && snap.lossTotal < 0.3)
            } finally {
                engine.stop()
            }
            Thread.sleep(50)
            val lossShare = hub.dropped.toDouble() / hub.received
            assertTrue("about 20 % dropped, got $lossShare", lossShare in 0.15..0.25)
            assertEquals(0, hub.rejected)
            assertEquals("sticks never send -32768", 0, hub.minus32768)
            assertTrue("PAD only", hub.types.all { it == Slp.TYPE_PAD.toInt() })
            hub.read { m ->
                assertEquals("a 4-bit counter never jumped by 8 or more", 0, m.bigDeltas)
                assertEquals("no mode switch", 0, m.modeSwitches)
                for (b in 0 until Slp.PAD_BUTTONS) {
                    assertEquals("presses on ${PadButton.bothNames(b)}", made[b], m.presses[b])
                }
                assertTrue("final state PAUSED", m.last.flags and Slp.FLAG_PAUSED != 0)
                assertEquals(12345, m.last.lx)
                assertEquals(-23456, m.last.ly)
                assertEquals(40000, m.last.r2)
                assertEquals(0x80 or 3, m.last.touch0Id)
                assertEquals(0, m.last.buttons)
            }
            assertTrue("a real workout: ${made.sum()} presses", made.sum() >= 150)
        }
    }

    @Test
    fun sourceSwitchKeepsEpochAndSequenceAndNeverFlipsWithin300Ms() {
        LossyHub(lossPercent = 0, seed = 1).use { hub ->
            val pad = PadState()
            pad.setFlag(Slp.FLAG_PAUSED, false)
            val engine = padEngine(pad, hub.port, PacketSource.INPUT)
            engine.start()
            try {
                assertTrue(waitFor(3000) { hub.types.size >= 30 })
                assertEquals(PacketSource.INPUT, engine.source)
                engine.setSource(PacketSource.PAD)
                assertTrue(waitFor(1000) { hub.types.contains(Slp.TYPE_PAD.toInt()) })
                engine.setSource(PacketSource.INPUT) // asked back at once: must wait 300 ms
                Thread.sleep(50)
                assertEquals("still PAD 50 ms after the switch", PacketSource.PAD, engine.source)
                assertTrue(waitFor(2000) { engine.source == PacketSource.INPUT })
                Thread.sleep(30)
            } finally {
                engine.stop()
            }
            Thread.sleep(30)
            val types = hub.types.toList()
            val seqs = hub.seqs.toList()
            val times = hub.times.toList()
            for (i in 1 until seqs.size) assertEquals("one sequence across both types", seqs[i - 1] + 1, seqs[i])
            val firstPad = types.indexOf(Slp.TYPE_PAD.toInt())
            val backToInput = (firstPad until types.size).first { types[it] == Slp.TYPE_INPUT.toInt() }
            val heldUs = times[backToInput] - times[firstPad]
            // Measured on the packets' own t_us: the hold counts from the first PAD packet built, so
            // there is no tolerance (a 1 ms one once hid a hold timed from the switch decision instead).
            assertTrue("PAD lasted at least 300 ms, got $heldUs us", heldUs >= 300_000)
            for (i in firstPad until backToInput) assertEquals(Slp.TYPE_PAD.toInt(), types[i])
            hub.read { m ->
                assertEquals("INPUT to PAD and back", 2, m.modeSwitches)
                assertEquals(0, m.missing)
            }
        }
    }

    @Test
    fun padOverTheUsbPathIsFramedAs76Bytes() {
        ServerSocket(0, 1, loopback).use { server ->
            server.soTimeout = 5000
            val frames = CopyOnWriteArrayList<PadFrame>()
            val lengths = CopyOnWriteArrayList<Int>()
            val hub = Thread {
                try {
                    server.accept().use { c: Socket ->
                        c.tcpNoDelay = true
                        val input = DataInputStream(c.getInputStream())
                        val out = c.getOutputStream()
                        val reader = PadPacketReader(key)
                        val hdr = ByteArray(2)
                        val framed = ByteArray(Framing.HEADER_LEN + Slp.STATUS_LEN)
                        while (frames.size < 60) {
                            input.readFully(hdr)
                            val len = Le.u16(hdr, 0)
                            lengths.add(len)
                            val body = ByteArray(len)
                            input.readFully(body)
                            val f = PadFrame()
                            if (!reader.read(body, 0, len, f)) continue
                            frames.add(f)
                            if (frames.size % 10 == 1) {
                                val st = statusFor(f.epoch, f.seq, f.tUs, frames.size, 0)
                                Framing.write(st, st.size, framed, 0)
                                out.write(framed)
                            }
                        }
                    }
                } catch (e: Exception) {
                    // closed
                }
            }.apply { start() }
            val pad = PadState()
            pad.setFlag(Slp.FLAG_PAUSED, false)
            pad.setTrigger(0, 4242)
            val engine = LinkEngine(
                ControllerState(),
                pad,
                LinkConfig(key = key, host = null, tcpPort = server.localPort, useWifi = false, useUsb = true, usbAddress = loopback),
            )
            engine.start()
            try {
                assertTrue(waitFor(4000) { frames.size >= 60 })
                assertTrue(lengths.all { it == Slp.PAD_LEN })
                assertEquals(1, frames.first().seq)
                assertEquals(4242, frames.first().l2)
                assertTrue("STATUS over TCP reaches LinkStats", waitFor(2000) { engine.stats.rtt100us > 0 })
            } finally {
                engine.stop()
                hub.join(2000)
            }
        }
    }

    @Test
    fun wheelLinkWithoutAPadNeverSendsPad() {
        LossyHub(lossPercent = 0, seed = 2).use { hub ->
            val state = ControllerState()
            val engine = LinkEngine(state, LinkConfig(key = key, host = loopback, udpPort = hub.port, useWifi = true, useUsb = false))
            engine.start()
            try {
                assertTrue(waitFor(3000) { hub.types.size >= 20 })
                engine.setSource(PacketSource.PAD) // no PadState: ignored
                Thread.sleep(50)
                assertEquals(PacketSource.INPUT, engine.source)
            } finally {
                engine.stop()
            }
            assertTrue(hub.types.all { it == Slp.TYPE_INPUT.toInt() })
            assertArrayEquals(IntArray(0), hub.types.filter { it != Slp.TYPE_INPUT.toInt() }.toIntArray())
        }
    }
}
