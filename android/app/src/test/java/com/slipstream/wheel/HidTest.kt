package com.slipstream.wheel

import com.slipstream.wheel.hid.HidReport
import com.slipstream.wheel.hid.PulseRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HidReportTest {
    private val d = HidReport.DESCRIPTOR.map { it.toInt() and 0xFF }

    /** Walks the short items of the descriptor and sums the Input report bits. */
    private fun inputBits(): Int {
        var i = 0
        var size = 0
        var count = 0
        var bits = 0
        while (i < d.size) {
            val prefix = d[i]
            val len = when (prefix and 0x03) { 3 -> 4; else -> prefix and 0x03 }
            var data = 0
            for (k in 0 until len) data = data or (d[i + 1 + k] shl (8 * k))
            when (prefix and 0xFC) {
                0x74 -> size = data // Report Size
                0x94 -> count = data // Report Count
                0x80 -> bits += size * count // Input
            }
            i += 1 + len
        }
        return bits
    }

    @Test
    fun descriptorShape() {
        assertEquals(listOf(0x05, 0x01, 0x09, 0x05, 0xA1, 0x01, 0x85, 0x01), d.subList(0, 8))
        assertEquals(0xC0, d.last())
        val s = d.joinToString(",")
        assertTrue("4 byte Logical Maximum 65535", s.contains(listOf(0x27, 0xFF, 0xFF, 0x00, 0x00).joinToString(",")))
        assertTrue("five axes X Y Z Rx Ry", s.contains(listOf(0x09, 0x30, 0x09, 0x31, 0x09, 0x32, 0x09, 0x33, 0x09, 0x34).joinToString(",")))
        assertTrue("buttons 1..32", s.contains(listOf(0x19, 0x01, 0x29, 0x20).joinToString(",")))
        assertEquals("5 x 16 bit axes plus 32 buttons", 5 * 16 + 32, inputBits())
        assertEquals(inputBits() / 8, HidReport.PAYLOAD_LEN)
    }

    @Test
    fun reportLayout() {
        val out = ByteArray(HidReport.PAYLOAD_LEN)
        HidReport.write(out, steer = -32767, throttle = 65535, brake = 0x1234, clutch = 1, handbrake = 0, buttons = 0x80000101.toInt())
        assertEquals(
            listOf(0x00, 0x00, 0xFF, 0xFF, 0x34, 0x12, 0x01, 0x00, 0x00, 0x00, 0x01, 0x01, 0x00, 0x80),
            out.map { it.toInt() and 0xFF },
        )
        HidReport.write(out, steer = 32767, throttle = 0, brake = 0, clutch = 0, handbrake = 0, buttons = 0)
        assertEquals(0xFE, out[0].toInt() and 0xFF)
        assertEquals(0xFF, out[1].toInt() and 0xFF)
    }

    @Test
    fun buttonNumberingMatchesVjoyTable() {
        // Pulse channel j is button j + 1 (bit j); held bit i is button i + 9 (bit i + 8).
        assertEquals(1 shl 0, HidReport.buttons(pulseBits = 1, held = 0))
        assertEquals(1 shl 7, HidReport.buttons(pulseBits = 1 shl 7, held = 0))
        assertEquals(1 shl 8, HidReport.buttons(pulseBits = 0, held = 1))
        assertEquals(1 shl 31, HidReport.buttons(pulseBits = 0, held = 1 shl 23))
        assertEquals("reserved held bits dropped", 0, HidReport.buttons(pulseBits = 0, held = 1 shl 24))
    }
}

class PulseRendererTest {
    private fun counters(ch0: Int, ch1: Int = 0) = ByteArray(8).also {
        it[0] = ch0.toByte()
        it[1] = ch1.toByte()
    }

    @Test
    fun firstReadingIsBaseline() {
        val r = PulseRenderer(60, 40)
        assertEquals(0, r.update(counters(5, 200), 0))
        assertEquals(Long.MAX_VALUE, r.nextChangeMs())
    }

    @Test
    fun pressThenGapThenNextPress() {
        val r = PulseRenderer(60, 40)
        r.update(counters(5), 0)
        assertEquals("two presses queued, first is down", 1, r.update(counters(7), 10))
        assertEquals(70, r.nextChangeMs())
        assertEquals(1, r.update(counters(7), 69))
        assertEquals("released after 60 ms", 0, r.update(counters(7), 70))
        assertEquals(0, r.update(counters(7), 109))
        assertEquals("second press after the 40 ms gap", 1, r.update(counters(7), 110))
        assertEquals(0, r.update(counters(7), 170))
        assertEquals(0, r.update(counters(7), 210))
        assertEquals(Long.MAX_VALUE, r.nextChangeMs())
    }

    @Test
    fun wrapCountsAndResetIsIgnored() {
        val r = PulseRenderer(60, 40)
        r.update(counters(255), 0)
        r.update(counters(1), 1) // 255 to 1: two presses
        var presses = 0
        var last = 0
        for (t in 1..400) {
            val b = r.update(counters(1), t.toLong()) and 1
            if (b == 1 && last == 0) presses++
            last = b
        }
        assertEquals(2, presses)

        val q = PulseRenderer(60, 40)
        q.update(counters(0), 0)
        assertEquals("a jump of 128 or more is a reset, not presses", 0, q.update(counters(200), 1))
    }

    @Test
    fun channelsAreIndependent() {
        val r = PulseRenderer(60, 40)
        r.update(counters(0, 0), 0)
        assertEquals(0b11, r.update(counters(1, 1), 5))
        assertEquals(0, r.update(counters(1, 1), 65))
    }
}
