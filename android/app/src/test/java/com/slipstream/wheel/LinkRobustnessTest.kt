package com.slipstream.wheel

import com.slipstream.wheel.input.ChangeSignal
import com.slipstream.wheel.input.ControllerState
import com.slipstream.wheel.link.AdbTcpTransport
import com.slipstream.wheel.link.BeaconGate
import com.slipstream.wheel.link.LinkConfig
import com.slipstream.wheel.link.LinkEngine
import com.slipstream.wheel.link.LinkStats
import com.slipstream.wheel.link.StatusSink
import com.slipstream.wheel.link.Transport
import com.slipstream.wheel.link.UdpTransport
import com.slipstream.wheel.protocol.Framing
import com.slipstream.wheel.protocol.InputFrame
import com.slipstream.wheel.protocol.InputPacketReader
import com.slipstream.wheel.protocol.Le
import com.slipstream.wheel.protocol.PacketAuth
import com.slipstream.wheel.protocol.PairingCode
import com.slipstream.wheel.protocol.Slp
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Defects found in review of the phone's link layer, each pinned by a test. */
class LinkRobustnessTest {
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

    private fun statusFor(f: InputFrame, accepted: Int): ByteArray {
        val b = ByteArray(Slp.STATUS_LEN)
        Slp.writeHeader(b, 0, Slp.TYPE_STATUS)
        Le.putU32(b, 4, f.epoch)
        Le.putU32(b, 8, f.seq)
        Le.putU32(b, 12, f.tUs)
        Le.putU32(b, 20, accepted)
        b[32] = Slp.OUTPUT_VJOY.toByte()
        PacketAuth(key).sign(b, Slp.STATUS_BODY_LEN)
        return b
    }

    private class Seen(val frame: InputFrame, val from: InetSocketAddress)

    /** Fake UDP hub. [answer] false: it records INPUT but never sends STATUS. */
    private inner class UdpHub(port: Int = 0, private val answer: Boolean = true) : AutoCloseable {
        val socket = DatagramSocket(null).apply {
            reuseAddress = true
            soTimeout = 50
            bind(InetSocketAddress(loopback, port))
        }
        val seen = CopyOnWriteArrayList<Seen>()
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
                if (!reader.read(buf, 0, pkt.length, f)) continue
                seen.add(Seen(f, pkt.socketAddress as InetSocketAddress))
                if (answer) {
                    val st = statusFor(f, seen.size)
                    try {
                        socket.send(DatagramPacket(st, st.size, pkt.socketAddress))
                    } catch (e: Exception) {
                        break
                    }
                }
            }
        }.apply { start() }
        val port: Int get() = socket.localPort

        override fun close() {
            running.set(false)
            socket.close()
            thread.join(1000)
        }
    }

    // ------------------------------------------------------------ USB address

    @Test
    fun usbPathConnectsTo127001NotTheAndroidDefaultLoopback() {
        // InetAddress.getLoopbackAddress() is ::1 on Android; adb reverse listens on 127.0.0.1.
        val cfg = LinkConfig(key = key, host = null, useWifi = false, useUsb = true)
        assertTrue(cfg.usbAddress is Inet4Address)
        assertArrayEquals(byteArrayOf(127, 0, 0, 1), cfg.usbAddress.address)
        assertArrayEquals(byteArrayOf(127, 0, 0, 1), AdbTcpTransport.LOOPBACK_V4.address)
    }

    // ------------------------------------------------------------ change signal

    @Test
    fun anOldConsumerDetachingDoesNotUnhookItsReplacement() {
        val signal = ChangeSignal()
        val old = Thread {}
        val replacement = Thread {}
        signal.attach(old)
        signal.attach(replacement) // a new sender started before the old one finished
        signal.detach(old) // the old sender's finally block
        assertSame(replacement, signal.consumerThread)
        signal.detach(replacement)
        assertNull(signal.consumerThread)
    }

    // ------------------------------------------------------------ Wi-Fi target

    @Test
    fun wifiPathStartsWithoutAHostAndJoinsWhenOneIsGiven() {
        UdpHub().use { hub ->
            val state = ControllerState()
            val engine = LinkEngine(state, LinkConfig(key = key, host = null, useWifi = true, useUsb = false))
            engine.start()
            try {
                assertTrue(engine.hasWifi)
                assertNull(engine.wifiTarget)
                Thread.sleep(100)
                assertEquals("nothing is sent without a hub address", 0, hub.seen.size)
                engine.setWifiTarget(loopback, hub.port)
                assertTrue("packets flow once the beacon gave an address", waitFor(3000) { hub.seen.size >= 20 })
                assertEquals("first packet on the wire is still seq 1", 1, hub.seen.first().frame.seq)
                assertTrue("STATUS comes back", waitFor(2000) { engine.stats.rtt100us > 0 })
            } finally {
                engine.stop()
            }
        }
    }

    @Test
    fun retargetMovesTheWifiPathToTheNewHubAddress() {
        UdpHub().use { a ->
            UdpHub().use { b ->
                val state = ControllerState()
                val engine = LinkEngine(state, LinkConfig(key = key, host = loopback, udpPort = a.port, useWifi = true, useUsb = false))
                engine.start()
                try {
                    assertTrue(waitFor(3000) { a.seen.size >= 10 })
                    engine.setWifiTarget(loopback, b.port)
                    assertTrue("the new address receives INPUT", waitFor(3000) { b.seen.size >= 10 })
                    val onA = a.seen.size
                    Thread.sleep(100)
                    assertTrue("the old address is left", a.seen.size - onA <= 2)
                    val epochs = (a.seen + b.seen).map { it.frame.epoch }.toSet()
                    assertEquals("same session, same epoch on both", 1, epochs.size)
                } finally {
                    engine.stop()
                }
            }
        }
    }

    // ------------------------------------------------------------ Wi-Fi socket rebuild

    @Test
    fun aSilentWifiPathRebuildsItsSocket() {
        UdpHub(answer = false).use { hub ->
            val stats = LinkStats(2)
            stats.reset(1)
            val udp = UdpTransport(0, loopback, hub.port, key, stats, StatusSink { _, _, _ -> }, staleNs = 300_000_000L)
            udp.start()
            try {
                val packet = packetBytes()
                assertTrue(waitFor(2000) { udp.canSend() })
                udp.send(packet, packet.size)
                assertTrue(waitFor(1000) { hub.seen.isNotEmpty() })
                val firstSource = hub.seen.first().from
                // No STATUS for longer than staleNs: the socket (and its source port) is rebuilt.
                assertTrue("socket rebuilt, opens=${udp.opens}", waitFor(3000) { udp.opens >= 2 })
                assertTrue(waitFor(2000) { udp.canSend() })
                val before = hub.seen.size
                udp.send(packet, packet.size)
                assertTrue(waitFor(1000) { hub.seen.size > before })
                assertNotEquals("new socket, new source port", firstSource.port, hub.seen.last().from.port)
            } finally {
                udp.stop()
            }
        }
    }

    @Test
    fun aHealthyWifiPathIsNotRebuilt() {
        UdpHub().use { hub ->
            val state = ControllerState()
            val engine = LinkEngine(state, LinkConfig(key = key, host = loopback, udpPort = hub.port, useWifi = true, useUsb = false))
            engine.start()
            try {
                assertTrue(waitFor(3000) { hub.seen.size >= 50 })
                Thread.sleep(800)
                val ports = hub.seen.map { it.from.port }.toSet()
                assertEquals("one socket for the whole drive", 1, ports.size)
            } finally {
                engine.stop()
            }
        }
    }

    @Test
    fun hubRestartedOnTheSamePortIsPickedUpAgain() {
        val state = ControllerState()
        val first = UdpHub()
        val port = first.port
        val engine = LinkEngine(state, LinkConfig(key = key, host = loopback, udpPort = port, useWifi = true, useUsb = false))
        engine.start()
        try {
            assertTrue(waitFor(3000) { first.seen.size >= 20 })
            first.close() // hub exits: the phone gets ICMP port unreachable meanwhile
            Thread.sleep(200)
            UdpHub(port = port).use { second ->
                assertTrue("INPUT reaches the restarted hub", waitFor(4000) { second.seen.size >= 20 })
                assertEquals("same epoch: the phone did not restart", engine.epoch, second.seen.last().frame.epoch)
                assertTrue("STATUS from the new hub is measured", waitFor(2000) {
                    engine.stats.transports[LinkEngine.SLOT_WIFI].lastStatusNs > 0
                })
            }
        } finally {
            engine.stop()
        }
    }

    // ------------------------------------------------------------ USB reconnect

    @Test
    fun usbReconnectsAfterTheHubDropsTheConnection() {
        ServerSocket(0, 2, loopback).use { server ->
            server.soTimeout = 5000
            val frames = CopyOnWriteArrayList<InputFrame>()
            val connections = AtomicInteger()
            val hub = Thread {
                val reader = InputPacketReader(key)
                repeat(2) { round ->
                    try {
                        server.accept().use { c ->
                            connections.incrementAndGet()
                            c.tcpNoDelay = true
                            val input = DataInputStream(c.getInputStream())
                            val out = c.getOutputStream()
                            val hdr = ByteArray(2)
                            val framed = ByteArray(Framing.HEADER_LEN + Slp.STATUS_LEN)
                            var n = 0
                            // First connection: take 30 frames then drop it (hub restart).
                            while (round == 1 || n < 30) {
                                input.readFully(hdr)
                                val body = ByteArray(Le.u16(hdr, 0))
                                input.readFully(body)
                                val f = InputFrame()
                                if (!reader.read(body, 0, body.size, f)) continue
                                frames.add(f)
                                n++
                                if (n % 10 == 1) {
                                    val st = statusFor(f, n)
                                    Framing.write(st, st.size, framed, 0)
                                    out.write(framed)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // closed
                    }
                }
            }.apply { start() }
            val state = ControllerState()
            val engine = LinkEngine(
                state,
                LinkConfig(key = key, host = null, tcpPort = server.localPort, useWifi = false, useUsb = true),
            )
            engine.start()
            try {
                assertTrue(waitFor(3000) { frames.size >= 30 })
                assertTrue("reconnected, connections=${connections.get()}", waitFor(4000) { connections.get() == 2 && frames.size >= 60 })
                val epochs = frames.map { it.epoch }.toSet()
                assertEquals(1, epochs.size)
                for (i in 1 until frames.size) assertTrue("seq keeps rising across the reconnect", frames[i].seq - frames[i - 1].seq > 0)
            } finally {
                engine.stop()
                server.close()
                hub.join(2000)
            }
        }
    }

    // ------------------------------------------------------------ sender resilience

    private class ThrowingTransport : Transport {
        override val slot: Int = LinkEngine.SLOT_USB
        override val label: String = "broken"
        val calls = AtomicInteger()
        override fun start() = Unit
        override fun stop() = Unit
        override fun canSend(): Boolean = true
        override fun send(packet: ByteArray, len: Int) {
            calls.incrementAndGet()
            throw IllegalStateException("simulated driver bug")
        }
    }

    @Test
    fun aThrowingPathDoesNotKillTheSenderOrStarveTheOtherPath() {
        UdpHub().use { hub ->
            val state = ControllerState()
            val broken = ThrowingTransport()
            val engine = LinkEngine(
                state,
                LinkConfig(key = key, host = loopback, udpPort = hub.port, useWifi = true, useUsb = false),
                null,
                listOf(broken),
            )
            engine.start()
            try {
                assertTrue("the healthy path keeps flowing", waitFor(3000) { hub.seen.size >= 200 })
                assertTrue(broken.calls.get() > 100)
                assertTrue(engine.senderFaults > 100)
                assertTrue(engine.isRunning)
            } finally {
                engine.stop()
            }
        }
    }

    @Test
    fun quickStopStartKeepsOneSenderAndAFreshSequence() {
        UdpHub().use { hub ->
            val state = ControllerState()
            val engine = LinkEngine(state, LinkConfig(key = key, host = loopback, udpPort = hub.port, useWifi = true, useUsb = false))
            engine.start()
            assertTrue(waitFor(3000) { hub.seen.size >= 20 })
            val firstEpoch = engine.epoch
            engine.stop()
            engine.start()
            try {
                val secondEpoch = engine.epoch
                assertNotEquals(firstEpoch, secondEpoch)
                assertTrue(waitFor(3000) { hub.seen.count { it.frame.epoch == secondEpoch } >= 100 })
                val second = hub.seen.map { it.frame }.filter { it.epoch == secondEpoch }
                assertEquals(1, second.first().seq)
                for (i in 1 until second.size) assertEquals("one sender: seq +1", second[i - 1].seq + 1, second[i].seq)
                val firstRun = hub.seen.map { it.frame }.filter { it.epoch == firstEpoch }
                assertTrue("the first run ended with PAUSED", firstRun.last().flags and Slp.FLAG_PAUSED != 0)
                assertTrue("the sender is hooked to the change signal", state.signal.consumerThread != null)
            } finally {
                engine.stop()
            }
        }
    }

    private fun packetBytes(): ByteArray {
        val f = InputFrame().apply {
            epoch = 5
            seq = 1
        }
        return com.slipstream.wheel.protocol.InputPacketWriter(key).write(f).copyOf()
    }
}

class BeaconGateTest {
    private val a = 0x0A000004 // 10.0.0.4
    private val b = 0x0A000005

    @Test
    fun sameContentFromOneSourceAtMostOncePerRefresh() {
        val g = BeaconGate(maxSources = 4, refreshMs = 900, expireMs = 5000)
        assertTrue(g.admit(a, 1L, 0))
        assertFalse("a flood of copies is dropped", g.admit(a, 1L, 1))
        assertFalse(g.admit(a, 1L, 899))
        assertTrue("refreshed once per second", g.admit(a, 1L, 900))
    }

    @Test
    fun aChangeIsPublishedAtOnce() {
        val g = BeaconGate()
        assertTrue(g.admit(a, 1L, 0))
        assertTrue("new name or fingerprint", g.admit(a, 2L, 10))
        assertTrue("another hub", g.admit(b, 2L, 11))
    }

    @Test
    fun sourcesAreCappedUntilOneExpires() {
        val g = BeaconGate(maxSources = 2, refreshMs = 900, expireMs = 5000)
        assertTrue(g.admit(1, 1L, 0))
        assertTrue(g.admit(2, 1L, 0))
        assertFalse("third source beyond the cap", g.admit(3, 1L, 10))
        assertTrue("a known source still refreshes", g.admit(1, 1L, 1000))
        assertTrue("after source 2 went quiet, room for a new one", g.admit(3, 1L, 5001))
    }

    @Test
    fun hashSeesEveryByte() {
        val x = byteArrayOf(1, 2, 3, 4)
        val y = byteArrayOf(1, 2, 3, 5)
        assertNotEquals(BeaconGate.hash(x, 0, 4), BeaconGate.hash(y, 0, 4))
        assertEquals(BeaconGate.hash(x, 0, 3), BeaconGate.hash(y, 0, 3))
    }
}
