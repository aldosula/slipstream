package com.slipstream.wheel

import com.slipstream.wheel.ui.Fmt
import org.junit.Assert.assertEquals
import org.junit.Test

class FmtTest {
    private fun f(v: Double, d: Int) = Fmt.fixed(StringBuilder(), v, d).toString()

    @Test
    fun fixedPoint() {
        assertEquals("2.3", f(2.345, 1))
        assertEquals("0.0", f(0.0, 1))
        assertEquals("12.05", f(12.049, 2))
        assertEquals("-1.5", f(-1.5, 1))
        assertEquals("0.0", f(-0.01, 1))
        assertEquals("100", f(99.6, 0))
    }

    @Test
    fun notANumberIsADashDashNotACrash() {
        assertEquals("--", f(Double.NaN, 1))
        assertEquals("--", f(Double.POSITIVE_INFINITY, 1))
    }
}
