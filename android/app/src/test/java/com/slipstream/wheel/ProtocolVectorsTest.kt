package com.slipstream.wheel

import com.slipstream.wheel.TestVectors.hex
import com.slipstream.wheel.TestVectors.unhex
import com.slipstream.wheel.hid.HidReport
import com.slipstream.wheel.input.PulseCounters
import com.slipstream.wheel.protocol.Beacon
import com.slipstream.wheel.protocol.FrameAssembler
import com.slipstream.wheel.protocol.Framing
import com.slipstream.wheel.protocol.InputFrame
import com.slipstream.wheel.protocol.InputPacketReader
import com.slipstream.wheel.protocol.InputPacketWriter
import com.slipstream.wheel.protocol.PacketAuth
import com.slipstream.wheel.protocol.PairingCode
import com.slipstream.wheel.protocol.PairingKey
import com.slipstream.wheel.protocol.Seq
import com.slipstream.wheel.protocol.Slp
import com.slipstream.wheel.protocol.StatusDecoder
import com.slipstream.wheel.protocol.StatusPacket
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every phone-relevant entry of docs/test-vectors.json, byte for byte. */
class ProtocolVectorsTest {
    private val v = TestVectors.json
    private val pairing = v.getJSONObject("pairing")
    private val code = pairing.getString("code")
    private val key = PairingCode.deriveKey(code)

    // ------------------------------------------------------------ pairing

    @Test
    fun pairingDerivation() {
        assertEquals(code, PairingCode.normalize(code))
        assertEquals(pairing.getString("raw_hex"), hex(PairingCode.decode(code)))
        assertEquals(pairing.getString("key_hex"), hex(key))
        assertEquals(pairing.getString("fingerprint_hex"), hex(PairingCode.fingerprint(key)))
        assertEquals(pairing.getString("display"), PairingCode.display(code))
    }

    @Test
    fun pairingKeyFromGroupedInput() {
        val pk = PairingKey.fromInput(pairing.getString("display"))
        assertNotNull(pk)
        assertEquals(code, pk!!.code)
        assertArrayEquals(key, pk.key)
        assertEquals(pairing.getString("fingerprint_hex"), hex(pk.fingerprint))
        assertTrue(pk.matches(unhex(pairing.getString("fingerprint_hex"))))
        assertFalse(pk.matches(ByteArray(8)))
        assertFalse(pk.matches(null))
    }

    @Test
    fun normalizeVectorsIncludingNulls() {
        val list = pairing.getJSONArray("normalize")
        assertTrue(list.length() >= 9)
        var nulls = 0
        for (i in 0 until list.length()) {
            val e = list.getJSONObject(i)
            val input = e.getString("in")
            if (e.isNull("out")) {
                nulls++
                assertNull("normalize(\"$input\") must be invalid", PairingCode.normalize(input))
                assertNull(PairingKey.fromInput(input))
            } else {
                assertEquals("normalize(\"$input\")", e.getString("out"), PairingCode.normalize(input))
            }
        }
        assertEquals(4, nulls)
    }

    // ------------------------------------------------------------ INPUT

    private fun frameFrom(f: JSONObject): InputFrame = InputFrame().apply {
        epoch = f.getLong("epoch").toInt()
        seq = f.getLong("seq").toInt()
        tUs = f.getLong("t_us").toInt()
        steer = f.getInt("steer")
        throttle = f.getInt("throttle")
        brake = f.getInt("brake")
        clutch = f.getInt("clutch")
        handbrake = f.getInt("handbrake")
        aux = f.getInt("aux")
        buttons = f.getLong("buttons").toInt()
        val p = f.getJSONArray("pulses")
        for (i in 0 until 8) pulses[i] = p.getInt(i).toByte()
        flags = f.getInt("flags")
        rtt100us = f.getInt("rtt_100us")
    }

    @Test
    fun inputEncodeByteExactAllVectors() {
        val list = v.getJSONArray("input")
        assertEquals(3, list.length())
        val writer = InputPacketWriter(key)
        for (i in 0 until list.length()) {
            val e = list.getJSONObject(i)
            val out = writer.write(frameFrom(e.getJSONObject("fields")))
            assertEquals(Slp.INPUT_LEN, out.size)
            assertEquals("input[$i]", e.getString("hex"), hex(out))
        }
    }

    @Test
    fun inputWriterReusesOneBuffer() {
        val writer = InputPacketWriter(key)
        val list = v.getJSONArray("input")
        val a = writer.write(frameFrom(list.getJSONObject(0).getJSONObject("fields")))
        val b = writer.write(frameFrom(list.getJSONObject(1).getJSONObject("fields")))
        assertSame(a, b)
        assertSame(writer.buffer, b)
    }

    @Test
    fun inputRoundTripThroughReader() {
        val reader = InputPacketReader(key)
        val list = v.getJSONArray("input")
        for (i in 0 until list.length()) {
            val e = list.getJSONObject(i)
            val expected = frameFrom(e.getJSONObject("fields"))
            val got = InputFrame()
            val bytes = unhex(e.getString("hex"))
            assertTrue(reader.read(bytes, 0, bytes.size, got))
            assertEquals(expected.epoch, got.epoch)
            assertEquals(expected.seq, got.seq)
            assertEquals(expected.tUs, got.tUs)
            assertEquals(expected.steer, got.steer)
            assertEquals(expected.throttle, got.throttle)
            assertEquals(expected.brake, got.brake)
            assertEquals(expected.clutch, got.clutch)
            assertEquals(expected.handbrake, got.handbrake)
            assertEquals(expected.buttons, got.buttons)
            assertArrayEquals(expected.pulses, got.pulses)
            assertEquals(expected.flags, got.flags)
            assertEquals(expected.rtt100us, got.rtt100us)
        }
    }

    @Test
    fun steerMinus32768IsNeverSent() {
        val writer = InputPacketWriter(key)
        val f = frameFrom(v.getJSONArray("input").getJSONObject(0).getJSONObject("fields")).apply { steer = -32768 }
        val out = writer.write(f)
        assertEquals(0x01, out[16].toInt() and 0xFF)
        assertEquals(0x80, out[17].toInt() and 0xFF)
    }

    // ------------------------------------------------------------ STATUS

    @Test
    fun statusDecodeBothVectors() {
        val list = v.getJSONArray("status")
        assertEquals(2, list.length())
        val dec = StatusDecoder(key)
        for (i in 0 until list.length()) {
            val e = list.getJSONObject(i)
            val f = e.getJSONObject("fields")
            val bytes = unhex(e.getString("hex"))
            val s = StatusPacket()
            assertTrue("status[$i]", dec.decode(bytes, 0, bytes.size, s))
            assertEquals(f.getLong("epoch"), s.epoch.toLong() and 0xFFFFFFFFL)
            assertEquals(f.getLong("last_seq"), s.lastSeq.toLong() and 0xFFFFFFFFL)
            assertEquals(f.getLong("echo_t_us"), s.echoTUs.toLong() and 0xFFFFFFFFL)
            assertEquals(f.getLong("hold_us"), s.holdUs.toLong() and 0xFFFFFFFFL)
            assertEquals(f.getLong("accepted"), s.accepted.toLong() and 0xFFFFFFFFL)
            assertEquals(f.getLong("missing"), s.missing.toLong() and 0xFFFFFFFFL)
            assertEquals(f.getInt("rumble_strong"), s.rumbleStrong)
            assertEquals(f.getInt("rumble_weak"), s.rumbleWeak)
            assertEquals(f.getInt("output"), s.output)
            assertEquals(f.getInt("hub_flags"), s.hubFlags)
        }
        val second = StatusPacket()
        val bytes = unhex(list.getJSONObject(1).getString("hex"))
        dec.decode(bytes, 0, bytes.size, second)
        assertEquals(Slp.OUTPUT_X360, second.outputKind)
        assertTrue(second.outputError)
    }

    @Test
    fun statusRejectsTamperWrongLengthHeaderAndKey() {
        val good = unhex(v.getJSONArray("status").getJSONObject(0).getString("hex"))
        val dec = StatusDecoder(key)
        val s = StatusPacket()
        assertTrue(dec.decode(good, 0, good.size, s))
        for (i in 0 until Slp.STATUS_LEN) {
            val bad = good.copyOf()
            bad[i] = (bad[i].toInt() xor 0x01).toByte()
            assertFalse("flipped byte $i", dec.decode(bad, 0, bad.size, s))
        }
        assertFalse(dec.decode(good, 0, good.size - 1, s))
        val longer = good.copyOf(good.size + 1)
        assertFalse(dec.decode(longer, 0, longer.size, s))
        val otherKey = PairingCode.deriveKey("ABCDEFGHIJKLMNOP")
        assertFalse(StatusDecoder(otherKey).decode(good, 0, good.size, s))
        // An INPUT packet is not a STATUS even with a valid tag.
        val input = unhex(v.getJSONArray("input").getJSONObject(0).getString("hex"))
        assertFalse(dec.decode(input, 0, input.size, s))
    }

    // ------------------------------------------------------------ BEACON

    @Test
    fun beaconDecode() {
        val b = v.getJSONObject("beacon")
        val f = b.getJSONObject("fields")
        val bytes = unhex(b.getString("hex"))
        val beacon = Beacon.decode(bytes, 0, bytes.size)
        assertNotNull(beacon)
        assertEquals(f.getInt("udp_port"), beacon!!.udpPort)
        assertEquals(f.getInt("tcp_port"), beacon.tcpPort)
        assertEquals(f.getString("name"), beacon.name)
        assertEquals(pairing.getString("fingerprint_hex"), hex(beacon.fingerprint))
        assertTrue(beacon.matches(PairingCode.fingerprint(key)))
        assertFalse(beacon.matches(PairingCode.fingerprint(PairingCode.deriveKey("ABCDEFGHIJKLMNOP"))))
    }

    @Test
    fun beaconRejectsWrongLengthAndHeader() {
        val bytes = unhex(v.getJSONObject("beacon").getString("hex"))
        assertNull(Beacon.decode(bytes, 0, bytes.size - 1))
        val longer = bytes.copyOf(bytes.size + 1)
        assertNull(Beacon.decode(longer, 0, longer.size))
        val wrongType = bytes.copyOf().also { it[3] = Slp.TYPE_STATUS }
        assertNull(Beacon.decode(wrongType, 0, wrongType.size))
        val wrongVersion = bytes.copyOf().also { it[2] = 2 }
        assertNull(Beacon.decode(wrongVersion, 0, wrongVersion.size))
        val tooLongName = ByteArray(17 + 33).also {
            System.arraycopy(bytes, 0, it, 0, 16)
            it[16] = 33
        }
        assertNull(Beacon.decode(tooLongName, 0, tooLongName.size))
    }

    // ------------------------------------------------------------ framing

    @Test
    fun frameVector() {
        val fr = v.getJSONObject("frame")
        val packet = unhex(fr.getString("packet_hex"))
        val out = ByteArray(Framing.HEADER_LEN + packet.size)
        val n = Framing.write(packet, packet.size, out, 0)
        assertEquals(out.size, n)
        assertEquals(fr.getString("hex"), hex(out))
    }

    @Test
    fun frameAssemblerAnySplit() {
        val fr = v.getJSONObject("frame")
        val framed = unhex(fr.getString("hex"))
        val packet = unhex(fr.getString("packet_hex"))
        val stream = framed + framed + framed
        for (chunk in listOf(1, 2, 3, 7, 53, 54, 55, stream.size)) {
            val asm = FrameAssembler(Slp.INPUT_LEN)
            var frames = 0
            var off = 0
            while (off < stream.size) {
                val len = minOf(chunk, stream.size - off)
                assertTrue(asm.feed(stream, off, len) { f, l ->
                    assertEquals(Slp.INPUT_LEN, l)
                    assertArrayEquals(packet, f.copyOf(l))
                    frames++
                })
                off += len
            }
            assertEquals("chunk $chunk", 3, frames)
        }
    }

    @Test
    fun frameAssemblerRejectsWrongLength() {
        val framed = unhex(v.getJSONObject("frame").getString("hex"))
        // The phone side expects 44 byte STATUS frames: a 52 byte frame is a protocol error.
        assertFalse(FrameAssembler(Slp.STATUS_LEN).feed(framed, 0, framed.size) { _, _ -> })
        val bogus = byteArrayOf(0xFF.toByte(), 0xFF.toByte())
        assertFalse(FrameAssembler(Slp.STATUS_LEN).feed(bogus, 0, 2) { _, _ -> })
    }

    @Test
    fun framedStatusRoundTrip() {
        val status = unhex(v.getJSONArray("status").getJSONObject(1).getString("hex"))
        val framed = ByteArray(Framing.HEADER_LEN + status.size)
        Framing.write(status, status.size, framed, 0)
        val dec = StatusDecoder(key)
        val s = StatusPacket()
        var got = 0
        assertTrue(FrameAssembler(Slp.STATUS_LEN).feed(framed, 0, framed.size) { f, l ->
            assertTrue(dec.decode(f, 0, l, s))
            got++
        })
        assertEquals(1, got)
        assertEquals(17, s.missing)
    }

    // ------------------------------------------------------------ tamper

    @Test
    fun tamperedInputFailsTag() {
        val tampered = unhex(v.getJSONObject("tamper").getString("hex"))
        val original = unhex(v.getJSONArray("input").getJSONObject(1).getString("hex"))
        val auth = PacketAuth(key)
        assertTrue(auth.verify(original, 0, Slp.INPUT_BODY_LEN))
        assertFalse(auth.verify(tampered, 0, Slp.INPUT_BODY_LEN))
        assertFalse(InputPacketReader(key).read(tampered, 0, tampered.size, InputFrame()))
        assertTrue(InputPacketReader(key).read(original, 0, original.size, InputFrame()))
    }

    // ------------------------------------------------------------ helpers

    @Test
    fun seqNewerVectors() {
        val list = v.getJSONArray("seq_newer")
        assertEquals(8, list.length())
        for (i in 0 until list.length()) {
            val e = list.getJSONObject(i)
            val a = e.getLong("a").toInt()
            val b = e.getLong("b").toInt()
            assertEquals("seq_newer(${e.getLong("a")}, ${e.getLong("b")})", e.getBoolean("newer"), Seq.newer(a, b))
        }
    }

    @Test
    fun pulseDeltaVectors() {
        val list = v.getJSONArray("pulse_delta")
        for (i in 0 until list.length()) {
            val e = list.getJSONObject(i)
            assertEquals(e.getInt("presses"), PulseCounters.delta(e.getInt("new"), e.getInt("old")))
        }
    }

    @Test
    fun mapHidSteerVectors() {
        val list = v.getJSONArray("map_hid_steer")
        assertEquals(3, list.length())
        for (i in 0 until list.length()) {
            val e = list.getJSONObject(i)
            assertEquals(e.getInt("out"), HidReport.steer(e.getInt("in")))
        }
    }
}
