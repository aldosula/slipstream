package com.slipstream.wheel

import com.slipstream.wheel.input.ControllerState
import com.slipstream.wheel.input.PulseCounters
import com.slipstream.wheel.link.LinkConfig
import com.slipstream.wheel.link.LinkEngine
import com.slipstream.wheel.link.LinkStats
import com.slipstream.wheel.protocol.Framing
import com.slipstream.wheel.protocol.InputFrame
import com.slipstream.wheel.protocol.InputPacketReader
import com.slipstream.wheel.protocol.Le
import com.slipstream.wheel.protocol.PacketAuth
import com.slipstream.wheel.protocol.PairingCode
import com.slipstream.wheel.protocol.Slp
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * End to end over real loopback sockets: the phone's LinkEngine against a minimal fake hub
 * that validates every INPUT and answers with STATUS, as PROTOCOL.md sections 5, 6 and 8 say.
 */
class LinkLoopbackTest {
    private val key = PairingCode.deriveKey("SLIPSTREAMTEST22")
    private val loopback: InetAddress = InetAddress.getLoopbackAddress()

    /** Builds a signed STATUS echoing [f]. */
    private fun statusFor(f: InputFrame, accepted: Int): ByteArray {
        val b = ByteArray(Slp.STATUS_LEN)
        Slp.writeHeader(b, 0, Slp.TYPE_STATUS)
        Le.putU32(b, 4, f.epoch)
        Le.putU32(b, 8, f.seq)
        Le.putU32(b, 12, f.tUs)
        Le.putU32(b, 16, 0)
        Le.putU32(b, 20, accepted)
        Le.putU32(b, 24, 0)
        b[32] = Slp.OUTPUT_VJOY.toByte()
        PacketAuth(key).sign(b, Slp.STATUS_BODY_LEN)
        return b
    }

    private class Received(val frame: InputFrame, val bytes: ByteArray)

    /** Fake UDP hub: validates, records, answers STATUS to the sender. */
    private inner class UdpHub : AutoCloseable {
        val socket = DatagramSocket(InetSocketAddress(loopback, 0)).apply { soTimeout = 100 }
        val received = CopyOnWriteArrayList<Received>()
        val rejected = java.util.concurrent.atomic.AtomicInteger()
        private val running = AtomicBoolean(true)
        private val thread = Thread {
            val reader = InputPacketReader(key)
            val buf = ByteArray(128)
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
                val f = InputFrame()
                if (!reader.read(buf, 0, pkt.length, f)) {
                    rejected.incrementAndGet()
                    continue
                }
                received.add(Received(f, buf.copyOf(pkt.length)))
                val st = statusFor(f, received.size)
                socket.send(DatagramPacket(st, st.size, pkt.socketAddress))
            }
        }.apply { start() }
        val port: Int get() = socket.localPort

        override fun close() {
            running.set(false)
            socket.close()
            thread.join(1000)
        }
    }

    /** Fake TCP hub: accepts one connection, framed both ways. */
    private inner class TcpHub : AutoCloseable {
        val server = ServerSocket(0, 1, loopback)
        val received = CopyOnWriteArrayList<Received>()
        private val running = AtomicBoolean(true)
        private var client: Socket? = null
        private val thread = Thread {
            try {
                val c = server.accept()
                client = c
                c.tcpNoDelay = true
                val input = DataInputStream(c.getInputStream())
                val out = c.getOutputStream()
                val reader = InputPacketReader(key)
                val hdr = ByteArray(2)
                val frameOut = ByteArray(Framing.HEADER_LEN + Slp.STATUS_LEN)
                while (running.get()) {
                    input.readFully(hdr)
                    val len = Le.u16(hdr, 0)
                    assertEquals("hub side frames are 52 bytes", Slp.INPUT_LEN, len)
                    val body = ByteArray(len)
                    input.readFully(body)
                    val f = InputFrame()
                    if (!reader.read(body, 0, len, f)) continue
                    received.add(Received(f, body))
                    if (received.size % 10 == 1) {
                        val st = statusFor(f, received.size)
                        Framing.write(st, st.size, frameOut, 0)
                        out.write(frameOut)
                    }
                }
            } catch (e: Exception) {
                // closed
            }
        }.apply { start() }
        val port: Int get() = server.localPort

        override fun close() {
            running.set(false)
            client?.close()
            server.close()
            thread.join(1000)
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

    @Test
    fun udpLinkSendsValidPacketsAndMeasuresRtt() {
        UdpHub().use { hub ->
            val state = ControllerState()
            state.setFlag(Slp.FLAG_PAUSED, false)
            state.setThrottle(1234)
            state.setSteer(-500)
            state.pulse(PulseCounters.SHIFT_UP)
            val engine = LinkEngine(
                state,
                LinkConfig(key = key, host = loopback, udpPort = hub.port, useWifi = true, useUsb = false, rateHz = 500),
            )
            engine.start()
            try {
                assertTrue("packets arrive", waitFor(3000) { hub.received.size >= 50 })
                val first = hub.received.first().frame
                assertEquals("first packet on the wire is seq 1", 1, first.seq)
                assertTrue("epoch is non-zero", first.epoch != 0)
                assertEquals(engine.epoch, first.epoch)
                assertEquals(1234, first.throttle)
                assertEquals(-500, first.steer)
                assertEquals(1, first.pulses[0].toInt())
                assertEquals("single path: no MULTIPATH flag", 0, first.flags and Slp.FLAG_MULTIPATH)
                assertEquals(0, hub.rejected.get())
                val frames = hub.received.map { it.frame }
                for (i in 1 until frames.size) {
                    assertEquals("seq +1 per packet", frames[i - 1].seq + 1, frames[i].seq)
                    assertEquals("one epoch per link start", first.epoch, frames[i].epoch)
                }
                assertTrue("RTT is measured from STATUS", waitFor(2000) { engine.stats.rtt100us > 0 })
                assertTrue("rtt_100us goes back into INPUT", waitFor(2000) { hub.received.last().frame.rtt100us > 0 })
                val snap = LinkStats.Snapshot(LinkEngine.TRANSPORT_SLOTS)
                engine.stats.snapshot(snap, System.nanoTime())
                assertEquals(LinkStats.TransportState.LIVE, snap.state[LinkEngine.SLOT_WIFI])
                assertEquals(LinkStats.TransportState.OFF, snap.state[LinkEngine.SLOT_USB])
                assertEquals(Slp.OUTPUT_VJOY, snap.output)
            } finally {
                engine.stop()
            }
            Thread.sleep(50)
            val last = hub.received.last().frame
            assertTrue("stopping sends PAUSED", last.flags and Slp.FLAG_PAUSED != 0)
        }
    }

    @Test
    fun idleRateAndMinimumSpacingUnderAStormOfChanges() {
        UdpHub().use { hub ->
            val state = ControllerState()
            val engine = LinkEngine(
                state,
                LinkConfig(key = key, host = loopback, udpPort = hub.port, useWifi = true, useUsb = false, rateHz = 500),
            )
            engine.start()
            try {
                assertTrue(waitFor(3000) { hub.received.size >= 5 })
                // Idle: about 500 per second.
                val n0 = hub.received.size
                Thread.sleep(400)
                val idle = hub.received.size - n0
                assertTrue("idle repeat near 500 Hz, got $idle in 400 ms", idle in 80..230)

                // Storm: a writer changes the state as fast as it can for 300 ms.
                val stop = AtomicBoolean(false)
                val writer = Thread {
                    var v = 0
                    while (!stop.get()) state.setSteer((v++ % 60000) - 30000)
                }
                val start = hub.received.size
                writer.start()
                Thread.sleep(300)
                stop.set(true)
                writer.join()
                Thread.sleep(20)
                val all = hub.received.toList()
                val storm = all.subList(start, all.size).map { it.frame }
                assertTrue("send on change is faster than idle, got ${storm.size}", storm.size > idle * 300 / 400)
                for (i in 1 until storm.size) {
                    val dt = (storm[i].tUs - storm[i - 1].tUs)
                    assertTrue("packets are at least 1 ms apart, got $dt us", dt >= 999)
                }
            } finally {
                engine.stop()
            }
        }
    }

    @Test
    fun usbLinkIsFramedBothWays() {
        TcpHub().use { hub ->
            val state = ControllerState()
            state.setFlag(Slp.FLAG_PAUSED, false)
            state.setBrake(4242)
            val engine = LinkEngine(
                state,
                LinkConfig(key = key, host = null, tcpPort = hub.port, useWifi = false, useUsb = true, usbAddress = loopback),
            )
            engine.start()
            try {
                assertTrue("framed packets arrive", waitFor(4000) { hub.received.size >= 20 })
                val first = hub.received.first().frame
                assertEquals(1, first.seq)
                assertEquals(4242, first.brake)
                assertTrue("STATUS over TCP reaches LinkStats", waitFor(2000) { engine.stats.rtt100us > 0 })
                val snap = LinkStats.Snapshot(LinkEngine.TRANSPORT_SLOTS)
                engine.stats.snapshot(snap, System.nanoTime())
                assertEquals(LinkStats.TransportState.LIVE, snap.state[LinkEngine.SLOT_USB])
            } finally {
                engine.stop()
            }
        }
    }

    @Test
    fun multipathSendsTheSameBytesOnBothPaths() {
        UdpHub().use { udp ->
            TcpHub().use { tcp ->
                val state = ControllerState()
                state.setFlag(Slp.FLAG_PAUSED, false)
                val engine = LinkEngine(
                    state,
                    LinkConfig(
                        key = key, host = loopback, udpPort = udp.port, tcpPort = tcp.port,
                        useWifi = true, useUsb = true, usbAddress = loopback,
                    ),
                )
                engine.start()
                try {
                    assertTrue(waitFor(4000) {
                        tcp.received.any { it.frame.flags and Slp.FLAG_MULTIPATH != 0 } && udp.received.size > 50
                    })
                    Thread.sleep(100)
                    val bySeqUdp = ConcurrentHashMap<Int, ByteArray>()
                    for (r in udp.received) if (r.frame.flags and Slp.FLAG_MULTIPATH != 0) bySeqUdp[r.frame.seq] = r.bytes
                    var matched = 0
                    for (r in tcp.received) {
                        val u = bySeqUdp[r.frame.seq] ?: continue
                        assertArrayEquals("same seq, same bytes on both paths", u, r.bytes)
                        matched++
                    }
                    assertTrue("packets seen on both paths, matched $matched", matched > 20)
                } finally {
                    engine.stop()
                }
            }
        }
    }
}
