package com.slipstream.wheel

import com.slipstream.wheel.TestVectors.hex
import com.slipstream.wheel.TestVectors.unhex
import com.slipstream.wheel.pad.TapCounters
import com.slipstream.wheel.protocol.FrameAssembler
import com.slipstream.wheel.protocol.Framing
import com.slipstream.wheel.protocol.PacketAuth
import com.slipstream.wheel.protocol.PadFrame
import com.slipstream.wheel.protocol.PadPacketReader
import com.slipstream.wheel.protocol.PadPacketWriter
import com.slipstream.wheel.protocol.PairingCode
import com.slipstream.wheel.protocol.Slp
import com.slipstream.wheel.protocol.TapNibbles
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every phone-relevant PAD entry of docs/test-vectors.json (PROTOCOL.md section 12), byte for byte. */
class PadVectorsTest {
    private val v = TestVectors.json
    private val key = PairingCode.deriveKey(v.getJSONObject("pairing").getString("code"))

    private fun frameFrom(f: JSONObject): PadFrame = PadFrame().apply {
        epoch = f.getLong("epoch").toInt()
        seq = f.getLong("seq").toInt()
        tUs = f.getLong("t_us").toInt()
        lx = f.getInt("lx")
        ly = f.getInt("ly")
        rx = f.getInt("rx")
        ry = f.getInt("ry")
        l2 = f.getInt("l2")
        r2 = f.getInt("r2")
        buttons = f.getLong("buttons").toInt()
        val t = f.getJSONArray("taps")
        assertEquals(18, t.length())
        for (i in 0 until 18) taps[i] = t.getInt(i).toByte()
        flags = f.getInt("flags")
        rtt100us = f.getInt("rtt_100us")
        val touch = f.getJSONArray("touch")
        val t0 = touch.getJSONObject(0)
        val t1 = touch.getJSONObject(1)
        touch0X = t0.getInt("x")
        touch0Y = t0.getInt("y")
        touch0Id = (if (t0.getBoolean("active")) 0x80 else 0) or (t0.getInt("id") and 0x7F)
        touch1X = t1.getInt("x")
        touch1Y = t1.getInt("y")
        touch1Id = (if (t1.getBoolean("active")) 0x80 else 0) or (t1.getInt("id") and 0x7F)
        val g = f.getJSONArray("gyro")
        gyroX = g.getInt(0); gyroY = g.getInt(1); gyroZ = g.getInt(2)
        val a = f.getJSONArray("accel")
        accelX = a.getInt(0); accelY = a.getInt(1); accelZ = a.getInt(2)
    }

    @Test
    fun padEncodeByteExactAllVectors() {
        val list = v.getJSONArray("pad")
        assertEquals(3, list.length())
        val writer = PadPacketWriter(key)
        for (i in 0 until list.length()) {
            val e = list.getJSONObject(i)
            val out = writer.write(frameFrom(e.getJSONObject("fields")))
            assertEquals(Slp.PAD_LEN, out.size)
            assertEquals("pad[$i]", e.getString("hex"), hex(out))
        }
    }

    @Test
    fun padWriterReusesOneBuffer() {
        val writer = PadPacketWriter(key)
        val list = v.getJSONArray("pad")
        val a = writer.write(frameFrom(list.getJSONObject(0).getJSONObject("fields")))
        val b = writer.write(frameFrom(list.getJSONObject(2).getJSONObject("fields")))
        assertSame(a, b)
        assertSame(writer.buffer, b)
        assertEquals(list.getJSONObject(2).getString("hex"), hex(b))
    }

    @Test
    fun padRoundTripThroughTheHubSideReader() {
        val reader = PadPacketReader(key)
        val list = v.getJSONArray("pad")
        for (i in 0 until list.length()) {
            val e = list.getJSONObject(i)
            val want = frameFrom(e.getJSONObject("fields"))
            val bytes = unhex(e.getString("hex"))
            val got = PadFrame()
            assertTrue("pad[$i]", reader.read(bytes, 0, bytes.size, got))
            assertEquals(want.epoch, got.epoch)
            assertEquals(want.seq, got.seq)
            assertEquals(want.tUs, got.tUs)
            assertEquals(want.lx, got.lx)
            assertEquals(want.ly, got.ly)
            assertEquals(want.rx, got.rx)
            assertEquals(want.ry, got.ry)
            assertEquals(want.l2, got.l2)
            assertEquals(want.r2, got.r2)
            assertEquals(want.buttons, got.buttons)
            assertArrayEquals(want.taps, got.taps)
            assertEquals(want.flags, got.flags)
            assertEquals(want.rtt100us, got.rtt100us)
            assertEquals(want.touch0X, got.touch0X)
            assertEquals(want.touch0Y, got.touch0Y)
            assertEquals(want.touch0Id, got.touch0Id)
            assertEquals(want.touch1X, got.touch1X)
            assertEquals(want.touch1Y, got.touch1Y)
            assertEquals(want.touch1Id, got.touch1Id)
            assertEquals(want.gyroX, got.gyroX)
            assertEquals(want.gyroY, got.gyroY)
            assertEquals(want.gyroZ, got.gyroZ)
            assertEquals(want.accelX, got.accelX)
            assertEquals(want.accelY, got.accelY)
            assertEquals(want.accelZ, got.accelZ)
        }
    }

    @Test
    fun tapNibblePackingMatchesTheVectorBytes() {
        val list = v.getJSONArray("pad")
        for (i in 0 until list.length()) {
            val e = list.getJSONObject(i)
            val f = frameFrom(e.getJSONObject("fields"))
            val bytes = unhex(e.getString("hex"))
            val packed = ByteArray(Slp.PAD_TAP_BYTES)
            TapNibbles.pack(f.taps, packed, 0)
            assertArrayEquals("pad[$i] bytes 32..40", bytes.copyOfRange(32, 41), packed)
            val back = ByteArray(Slp.PAD_BUTTONS)
            TapNibbles.unpack(bytes, 32, back)
            assertArrayEquals(f.taps, back)
        }
        // Button 2k in the low nibble of byte k, 2k + 1 in the high nibble.
        val taps = ByteArray(18).also { it[0] = 0x3; it[1] = 0xA; it[16] = 0x5; it[17] = 0xF }
        val out = ByteArray(9)
        TapNibbles.pack(taps, out, 0)
        assertEquals(0xA3, out[0].toInt() and 0xFF)
        assertEquals(0xF5, out[8].toInt() and 0xFF)
    }

    @Test
    fun padFrameVector() {
        val fr = v.getJSONObject("pad_frame")
        val packet = unhex(fr.getString("packet_hex"))
        assertEquals(v.getJSONArray("pad").getJSONObject(1).getString("hex"), fr.getString("packet_hex"))
        val out = ByteArray(Framing.HEADER_LEN + packet.size)
        assertEquals(out.size, Framing.write(packet, packet.size, out, 0))
        assertEquals(fr.getString("hex"), hex(out))
        // A hub-side assembler for 76 byte frames takes it apart again at any split.
        val framed = unhex(fr.getString("hex"))
        val stream = framed + framed
        for (chunk in listOf(1, 2, 3, 77, 78, stream.size)) {
            val asm = FrameAssembler(Slp.PAD_LEN)
            var frames = 0
            var off = 0
            while (off < stream.size) {
                val n = minOf(chunk, stream.size - off)
                assertTrue(asm.feed(stream, off, n) { f, l ->
                    assertArrayEquals(packet, f.copyOf(l))
                    frames++
                })
                off += n
            }
            assertEquals(2, frames)
        }
    }

    @Test
    fun padTamperIsRejectedByTheHubSideReader() {
        val tampered = unhex(v.getJSONObject("pad_tamper").getString("hex"))
        val original = unhex(v.getJSONArray("pad").getJSONObject(1).getString("hex"))
        assertEquals(1, (tampered[53].toInt() xor original[53].toInt()) and 0xFF)
        val reader = PadPacketReader(key)
        assertTrue(reader.read(original, 0, original.size, PadFrame()))
        assertFalse(reader.read(tampered, 0, tampered.size, PadFrame()))
        assertFalse(PacketAuth(key).verify(tampered, 0, Slp.PAD_BODY_LEN))
    }

    @Test
    fun readerRejectsAnyFlippedByteWrongLengthTypeAndKey() {
        val good = unhex(v.getJSONArray("pad").getJSONObject(1).getString("hex"))
        val reader = PadPacketReader(key)
        val f = PadFrame()
        for (i in 0 until Slp.PAD_LEN) {
            val bad = good.copyOf()
            bad[i] = (bad[i].toInt() xor 0x01).toByte()
            assertFalse("flipped byte $i", reader.read(bad, 0, bad.size, f))
        }
        assertFalse(reader.read(good, 0, good.size - 1, f))
        val longer = good.copyOf(good.size + 1)
        assertFalse(reader.read(longer, 0, longer.size, f))
        assertFalse(PadPacketReader(PairingCode.deriveKey("ABCDEFGHIJKLMNOP")).read(good, 0, good.size, f))
        // An INPUT packet with a valid tag is not a PAD packet.
        val input = unhex(v.getJSONArray("input").getJSONObject(0).getString("hex"))
        assertFalse(reader.read(input, 0, input.size, f))
    }

    @Test
    fun tapDeltaVectors() {
        val list = v.getJSONArray("tap_delta")
        assertEquals(8, list.length())
        for (i in 0 until list.length()) {
            val e = list.getJSONObject(i)
            val n = e.getInt("new")
            val o = e.getInt("old")
            assertEquals("tap_delta($n, $o)", e.getInt("taps"), TapNibbles.delta(n, o))
            assertEquals(e.getInt("taps"), TapCounters.delta(n, o))
        }
    }

    @Test
    fun sticksNeverSendMinus32768AndReservedBitsStayZero() {
        val writer = PadPacketWriter(key)
        val f = frameFrom(v.getJSONArray("pad").getJSONObject(0).getJSONObject("fields")).apply {
            lx = -32768; ly = -40000; rx = 40000; ry = Int.MIN_VALUE
            buttons = -1
            flags = 0xFF
            l2 = 70000
            r2 = -5
        }
        val out = writer.write(f)
        for (off in intArrayOf(16, 18)) {
            assertEquals(0x01, out[off].toInt() and 0xFF)
            assertEquals(0x80, out[off + 1].toInt() and 0xFF)
        }
        assertEquals(0xFF, out[20].toInt() and 0xFF) // +32767 = ff 7f
        assertEquals(0x7F, out[21].toInt() and 0xFF)
        assertEquals(0x01, out[22].toInt() and 0xFF)
        assertEquals(0x80, out[23].toInt() and 0xFF)
        assertEquals("buttons 18..31 reserved", 0x03, out[30].toInt() and 0xFF)
        assertEquals(0, out[31].toInt())
        assertEquals("only PAUSED, MULTIPATH, MOTION, STYLE_PS", 0x1D, out[41].toInt() and 0xFF)
        assertEquals(0xFF, out[24].toInt() and 0xFF)
        assertEquals(0xFF, out[25].toInt() and 0xFF)
        assertEquals(0, out[26].toInt())
        assertEquals(0, out[27].toInt())
        assertEquals(0, out[66].toInt())
        assertEquals(0, out[67].toInt())
        assertTrue(PadPacketReader(key).read(out, 0, out.size, PadFrame()))
    }
}
