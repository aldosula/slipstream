package com.slipstream.wheel

import com.slipstream.wheel.input.SteeringMath
import com.slipstream.wheel.pad.ButtonHolds
import com.slipstream.wheel.pad.GyroAim
import com.slipstream.wheel.pad.MotionMath
import com.slipstream.wheel.pad.PadButton
import com.slipstream.wheel.pad.PadState
import com.slipstream.wheel.pad.StickMath
import com.slipstream.wheel.pad.TapCounters
import com.slipstream.wheel.pad.TriggerMath
import com.slipstream.wheel.protocol.PadFrame
import com.slipstream.wheel.protocol.Slp
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Integer comparison with a tolerance, for rounded fixed-point values. */
fun assertNear(expected: Int, actual: Int, tolerance: Int) =
    assertTrue("expected $expected +-$tolerance, got $actual", abs(expected - actual) <= tolerance)

/** Tap counters and the held bit (PROTOCOL.md 12.3), and the lock-free pad state. */
class TapCountersTest {
    @Test
    fun pressIncrementsOnceAndSetsHeldInTheSameWord() {
        var w = 0L
        w = TapCounters.press(w, 3)
        assertTrue(TapCounters.held(w, 3))
        assertEquals(1, TapCounters.tap(w, 3))
        assertEquals("holding an already held button is not a new press", w, TapCounters.press(w, 3))
        w = TapCounters.release(w, 3)
        assertFalse(TapCounters.held(w, 3))
        assertEquals("release never touches the counter", 1, TapCounters.tap(w, 3))
        assertEquals("releasing twice changes nothing", w, TapCounters.release(w, 3))
    }

    @Test
    fun counterWrapsAtSixteenWithoutTouchingNeighbours() {
        var w = TapCounters.press(0L, 0)
        w = TapCounters.release(w, 0)
        for (n in 2..16) {
            w = TapCounters.press(w, 4)
            w = TapCounters.release(w, 4)
        }
        assertEquals(15, TapCounters.tap(w, 4))
        w = TapCounters.press(w, 4)
        assertEquals("15 wraps to 0", 0, TapCounters.tap(w, 4))
        assertTrue(TapCounters.held(w, 4))
        assertEquals(1, TapCounters.tap(w, 0))
        for (i in intArrayOf(1, 2, 3, 5, 6, 7, 8)) {
            assertEquals(0, TapCounters.tap(w, i))
            assertFalse(TapCounters.held(w, i))
        }
    }

    @Test
    fun heldBitsAndTapsUnpackInCanonicalOrder() {
        var lo = 0L
        var hi = 0L
        lo = TapCounters.press(lo, 0) // Cross
        lo = TapCounters.press(lo, 8) // Create
        hi = TapCounters.press(hi, 0) // bit 9, Options
        hi = TapCounters.press(hi, 8) // bit 17, Share
        hi = TapCounters.release(hi, 8)
        assertEquals((1 shl 0) or (1 shl 8) or (1 shl 9), TapCounters.heldBits(lo, hi))
        val taps = ByteArray(18)
        TapCounters.unpackTaps(lo, hi, taps)
        val want = ByteArray(18).also { it[0] = 1; it[8] = 1; it[9] = 1; it[17] = 1 }
        assertArrayEquals(want, taps)
        val released = TapCounters.releaseAll(lo)
        assertEquals(0, TapCounters.heldBits(released, 0L))
        assertEquals(1, TapCounters.tap(released, 0))
        assertEquals(1, TapCounters.tap(released, 8))
    }

    @Test
    fun theSnapshotThatFirstShowsTheHeldBitCarriesTheIncrement() {
        val s = PadState()
        val f = PadFrame()
        s.snapshot(f)
        assertEquals(0, f.buttons)
        assertEquals(0, f.taps[PadButton.CIRCLE].toInt())
        s.setButton(PadButton.CIRCLE, true)
        s.snapshot(f)
        assertEquals(1 shl PadButton.CIRCLE, f.buttons)
        assertEquals(1, f.taps[PadButton.CIRCLE].toInt())
        s.setButton(PadButton.CIRCLE, true) // a second hold of the same button
        s.snapshot(f)
        assertEquals(1, f.taps[PadButton.CIRCLE].toInt())
        s.setButton(PadButton.CIRCLE, false)
        s.snapshot(f)
        assertEquals(0, f.buttons)
        assertEquals(1, f.taps[PadButton.CIRCLE].toInt())
        // A tap too quick for any snapshot still leaves its count behind: the hub replays it.
        s.setButton(PadButton.SHARE, true)
        s.setButton(PadButton.SHARE, false)
        s.snapshot(f)
        assertEquals(0, f.buttons)
        assertEquals(1, f.taps[PadButton.SHARE].toInt())
        assertEquals(1, s.tapCounter(PadButton.SHARE))
    }

    @Test
    fun everyPressAndReleaseRaisesTheSignalButNothingElseDoes() {
        val s = PadState()
        s.signal.consume()
        s.setButton(PadButton.UP, true)
        assertTrue(s.signal.consume())
        s.setButton(PadButton.UP, true)
        assertFalse("no change, no packet", s.signal.consume())
        s.setButton(PadButton.UP, false)
        assertTrue(s.signal.consume())
        s.setButton(99, true)
        assertFalse("reserved bits are ignored", s.signal.consume())
        s.setGyro(1, 2, 3)
        s.setAccel(4, 5, 6)
        assertFalse("raw motion rides on the idle repeat", s.signal.consume())
        s.setStick(0, 100, -100)
        assertTrue(s.signal.consume())
        s.setStick(0, 100, -100)
        assertFalse(s.signal.consume())
        s.setTrigger(1, 500)
        assertTrue(s.signal.consume())
    }

    @Test
    fun releaseAllKeepsCountersAndTrackingIds() {
        val s = PadState()
        s.setStick(0, 1000, 2000)
        s.setStick(1, -3000, 4000)
        s.setTrigger(0, 40000)
        s.setTrigger(1, 50000)
        s.setButton(PadButton.CROSS, true)
        s.setButton(PadButton.RIGHT, true)
        s.setTouch(0, true, 9, 100, 200)
        s.releaseAll()
        val f = PadFrame()
        s.snapshot(f)
        assertEquals(0, f.lx); assertEquals(0, f.ly); assertEquals(0, f.rx); assertEquals(0, f.ry)
        assertEquals(0, f.l2); assertEquals(0, f.r2)
        assertEquals(0, f.buttons)
        assertEquals(1, f.taps[PadButton.CROSS].toInt())
        assertEquals(1, f.taps[PadButton.RIGHT].toInt())
        assertEquals("finger inactive, id kept", 9, f.touch0Id)
        assertEquals(100, f.touch0X)
    }

    @Test
    fun snapshotCarriesEveryFieldAndAimIsAddedToTheRightStick() {
        val s = PadState()
        s.setFlag(Slp.FLAG_PAUSED, false)
        s.setFlag(Slp.FLAG_STYLE_PS, true)
        s.setStick(0, -32768, 32767)
        s.setStick(1, 30000, -100)
        s.setGyroAim(StickMath.pack(5000, -200))
        s.setTrigger(0, 65535)
        s.setTrigger(1, 1234)
        s.setTouch(0, true, 5, 65535, 0)
        s.setTouch(1, true, 127, 32768, 40000)
        s.setGyro(-32767, 1600, 32767)
        s.setAccel(0, -4096, 4096)
        val f = PadFrame()
        s.snapshot(f)
        assertEquals("never -32768", -32767, f.lx)
        assertEquals(32767, f.ly)
        assertEquals("touch plus aim, clamped", 32767, f.rx)
        assertEquals(-300, f.ry)
        assertEquals(65535, f.l2)
        assertEquals(1234, f.r2)
        assertEquals(0x80 or 5, f.touch0Id)
        assertEquals(0x80 or 127, f.touch1Id)
        assertEquals(32768, f.touch1X)
        assertEquals(40000, f.touch1Y)
        assertEquals(Slp.FLAG_STYLE_PS, f.flags)
        assertEquals("motion fields are zero without MOTION", 0, f.gyroX)
        assertEquals(0, f.accelY)
        s.setFlag(Slp.FLAG_MOTION, true)
        s.snapshot(f)
        assertEquals(Slp.FLAG_STYLE_PS or Slp.FLAG_MOTION, f.flags)
        assertEquals(-32767, f.gyroX); assertEquals(1600, f.gyroY); assertEquals(32767, f.gyroZ)
        assertEquals(0, f.accelX); assertEquals(-4096, f.accelY); assertEquals(4096, f.accelZ)
    }

    @Test
    fun startsPaused() {
        val f = PadFrame()
        PadState().snapshot(f)
        assertEquals(Slp.FLAG_PAUSED, f.flags)
    }

    @Test
    fun buttonHoldsCountSourcesSoOnlyTheFirstIsAPress() {
        val s = PadState()
        val h = ButtonHolds(s)
        assertTrue(h.press(PadButton.CROSS)) // finger on Cross
        assertFalse(h.press(PadButton.CROSS)) // volume key mapped to Cross
        assertEquals(1, s.tapCounter(PadButton.CROSS))
        h.release(PadButton.CROSS)
        assertTrue("still held by the other source", s.heldButtons and 1 != 0)
        h.release(PadButton.CROSS)
        assertEquals(0, s.heldButtons)
        h.release(PadButton.CROSS) // extra release is harmless
        assertTrue(h.press(PadButton.CROSS))
        assertEquals(2, s.tapCounter(PadButton.CROSS))
        assertFalse(h.press(-1))
        assertFalse(h.press(18))
    }

    @Test
    fun concurrentWritersOnBothWordsDoNotLoseUpdates() {
        val s = PadState()
        val bits = intArrayOf(0, 5, 8, 9, 12, 17)
        val threads = bits.map { bit ->
            Thread {
                repeat(1600) {
                    s.setButton(bit, true)
                    s.setButton(bit, false)
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        for (bit in bits) assertEquals("1600 presses mod 16 on bit $bit", 0, s.tapCounter(bit))
        for (bit in bits) {
            s.setButton(bit, true)
            assertEquals(1, s.tapCounter(bit))
        }
        assertEquals(bits.fold(0) { m, b -> m or (1 shl b) }, s.heldButtons)
    }
}

class StickMathTest {
    private val r = 100f

    @Test
    fun centreAndDeadzoneAreZero() {
        assertEquals(0, StickMath.output(0f, 0f, r, 0.08f, 1f))
        assertEquals(0, StickMath.output(7.9f, 0f, r, 0.08f, 1f))
        assertEquals(0, StickMath.output(-5f, 5f, r, 0.08f, 1f))
        assertTrue(StickMath.output(8.5f, 0f, r, 0.08f, 1f) != 0)
    }

    @Test
    fun fullDeflectionAndYIsUp() {
        val right = StickMath.output(r, 0f, r, 0.08f, 1f)
        assertEquals(32767, StickMath.x(right))
        assertEquals(0, StickMath.y(right))
        val up = StickMath.output(0f, -r, r, 0.08f, 1f) // screen y grows down
        assertEquals(0, StickMath.x(up))
        assertEquals(32767, StickMath.y(up))
        val down = StickMath.output(0f, r, r, 0.08f, 1f)
        assertEquals(-32767, StickMath.y(down))
        val left = StickMath.output(-3 * r, 0f, r, 0.08f, 1f)
        assertEquals("clamped to the circle, never -32768", -32767, StickMath.x(left))
    }

    @Test
    fun outsideTheCircleKeepsDirectionAtFullMagnitude() {
        val p = StickMath.output(300f, -300f, r, 0.08f, 1f)
        assertEquals(1f, StickMath.magnitude(p), 0.001f)
        assertEquals(StickMath.x(p), StickMath.y(p))
        assertNear(23170, StickMath.x(p), 2)
    }

    @Test
    fun deadzoneIsRescaledAndCurveShapesTheMagnitude() {
        val dz = 0.2f
        val half = StickMath.output(r * (dz + (1f - dz) / 2f), 0f, r, dz, 1f)
        assertNear(16384, StickMath.x(half), 1)
        val curved = StickMath.output(r * 0.5f, 0f, r, 0f, 2f)
        assertNear(8192, StickMath.x(curved), 1)
        val symmetric = StickMath.output(-r * 0.37f, r * 0.21f, r, 0.08f, 1.5f)
        val mirrored = StickMath.output(r * 0.37f, -r * 0.21f, r, 0.08f, 1.5f)
        assertEquals(-StickMath.x(mirrored), StickMath.x(symmetric))
        assertEquals(-StickMath.y(mirrored), StickMath.y(symmetric))
    }

    @Test
    fun packingRoundTripsNegativeValues() {
        for (x in intArrayOf(-32767, -1, 0, 1, 32767)) {
            for (y in intArrayOf(-32767, -1, 0, 1, 32767)) {
                val p = StickMath.pack(x, y)
                assertEquals(x, StickMath.x(p))
                assertEquals(y, StickMath.y(p))
            }
        }
        assertEquals(-32767, StickMath.toI16(-2f))
        assertEquals(16384, StickMath.toI16(0.5f))
        assertEquals(-16384, StickMath.toI16(-0.5f))
    }

    @Test
    fun floatingOriginStaysInsideTheControlAndKnobStaysOnTheCircle() {
        assertEquals(50f, StickMath.origin(50f, 0f, 116f), 0f)
        assertEquals(116f, StickMath.origin(200f, 0f, 116f), 0f)
        assertEquals(0f, StickMath.origin(-5f, 0f, 116f), 0f)
        val k = FloatArray(2)
        StickMath.knob(300f, 400f, 50f, k)
        assertEquals(30f, k[0], 0.001f)
        assertEquals(40f, k[1], 0.001f)
        StickMath.knob(3f, 4f, 50f, k)
        assertEquals(3f, k[0], 0f)
    }
}

class TriggerMathTest {
    @Test
    fun slideMeasuresTravelFromTheLandingPoint() {
        val travel = TriggerMath.travelPx(200f, 0.5f)
        assertEquals(100f, travel, 0f)
        var a = 40f
        assertEquals(0f, TriggerMath.value(a, 40f, travel), 0f)
        a = TriggerMath.anchor(a, 90f, travel)
        assertEquals(0.5f, TriggerMath.value(a, 90f, travel), 0.0001f)
        // Back past the landing point: re-anchored, trigger released.
        a = TriggerMath.anchor(a, 20f, travel)
        assertEquals(20f, a, 0f)
        assertEquals(0f, TriggerMath.value(a, 20f, travel), 0f)
        // Overshoot drags the anchor, so the first pixel back releases.
        a = TriggerMath.anchor(a, 200f, travel)
        assertEquals(1f, TriggerMath.value(a, 200f, travel), 0f)
        a = TriggerMath.anchor(a, 199f, travel)
        assertTrue(TriggerMath.value(a, 199f, travel) < 1f)
    }

    @Test
    fun outputScaleAndCurve() {
        assertEquals(0, TriggerMath.output(0f, 1f))
        assertEquals(TriggerMath.FULL, TriggerMath.output(1f, 1f))
        assertEquals(32768, TriggerMath.output(0.5f, 1f))
        assertEquals(16384, TriggerMath.output(0.5f, 2f))
        assertEquals(1f, TriggerMath.travelPx(0f, 0.5f), 0f)
    }
}

class GyroAimTest {
    @Test
    fun modes() {
        assertFalse(GyroAim.active(GyroAim.Mode.OFF, true))
        assertTrue(GyroAim.active(GyroAim.Mode.ALWAYS, false))
        assertTrue(GyroAim.active(GyroAim.Mode.WHILE_TOUCHING, true))
        assertFalse(GyroAim.active(GyroAim.Mode.WHILE_TOUCHING, false))
    }

    @Test
    fun yawLeftAimsLeftAndPitchUpAimsUp() {
        // +rate about +y (up) swings the back of the phone to the player's left.
        val left = GyroAim.deflection(0f, 61f, 1f, false)
        assertNear(-16384, StickMath.x(left), 1)
        assertEquals(0, StickMath.y(left))
        // +rate about +x tips the top toward the player: the back aims up.
        val up = GyroAim.deflection(61f, 0f, 1f, false)
        assertNear(16384, StickMath.y(up), 1)
        assertEquals(0, StickMath.x(up))
        val inverted = GyroAim.deflection(61f, 0f, 1f, true)
        assertNear(-16384, StickMath.y(inverted), 1)
    }

    @Test
    fun noiseGateSensitivityAndSaturation() {
        assertEquals(0, GyroAim.deflection(0.9f, -0.9f, 4f, false))
        assertEquals(0f, GyroAim.gate(1f), 0f)
        assertEquals(0.5f, GyroAim.gate(1.5f), 0.0001f)
        assertEquals(-0.5f, GyroAim.gate(-1.5f), 0.0001f)
        val slow = StickMath.x(GyroAim.deflection(0f, -31f, 1f, false))
        val fast = StickMath.x(GyroAim.deflection(0f, -31f, 2f, false))
        assertNear(2 * slow, fast, 1)
        assertEquals(32767, StickMath.x(GyroAim.deflection(0f, -5000f, 1f, false)))
        assertEquals(-32767, StickMath.y(GyroAim.deflection(-5000f, 0f, 1f, false)))
        // Sensitivity is clamped to its range.
        assertEquals(GyroAim.deflection(0f, 20f, GyroAim.SENSITIVITY_MAX, false), GyroAim.deflection(0f, 20f, 100f, false))
    }
}

/**
 * Motion frame (PROTOCOL.md 12.1): +x to the player's right, +y up, +z toward the player.
 * ROTATION_90: device +x is up, device -y is right. ROTATION_270: device -x is up, +y right.
 */
class MotionMathTest {
    private val out = FloatArray(3)

    private fun ctrl(rotation: Int, x: Float, y: Float, z: Float): FloatArray {
        MotionMath.toController(rotation, x, y, z, out)
        return out.copyOf()
    }

    @Test
    fun gravityAtRestReadsPlusOneGUpInBothLandscapes() {
        val g = MotionMath.STANDARD_GRAVITY
        // Upright landscape, screen facing the player: world up is device +x (90) or -x (270).
        for ((rot, dev) in listOf(SteeringMath.ROTATION_90 to floatArrayOf(g, 0f, 0f), SteeringMath.ROTATION_270 to floatArrayOf(-g, 0f, 0f))) {
            val c = ctrl(rot, dev[0], dev[1], dev[2])
            assertEquals("rotation $rot x", 0, MotionMath.accelUnits(c[0]))
            assertEquals("rotation $rot y", 4096, MotionMath.accelUnits(c[1]))
            assertEquals("rotation $rot z", 0, MotionMath.accelUnits(c[2]))
        }
        // Lying flat on its back, screen up: +z in both.
        for (rot in intArrayOf(SteeringMath.ROTATION_90, SteeringMath.ROTATION_270)) {
            assertEquals(4096, MotionMath.accelUnits(ctrl(rot, 0f, 0f, g)[2]))
        }
    }

    @Test
    fun tiltedBackThirtyDegrees() {
        val g = MotionMath.STANDARD_GRAVITY
        val c30 = cos(PI / 6).toFloat()
        val s30 = sin(PI / 6).toFloat()
        // Screen tipped back: world up has +y and +z parts in the controller frame, in both landscapes.
        val a = ctrl(SteeringMath.ROTATION_90, g * c30, 0f, g * s30)
        val b = ctrl(SteeringMath.ROTATION_270, -g * c30, 0f, g * s30)
        for (c in listOf(a, b)) {
            assertEquals(0, MotionMath.accelUnits(c[0]))
            assertNear((4096 * c30).toInt(), MotionMath.accelUnits(c[1]), 1)
            assertNear(2048, MotionMath.accelUnits(c[2]), 1)
        }
    }

    @Test
    fun playerRightAndUpAxesMapToXAndY() {
        val w = 2f // rad/s
        // Rotation about the player's right axis (pitch). Device axis: -y in 90, +y in 270.
        assertArrayEquals(floatArrayOf(w, 0f, 0f), ctrl(SteeringMath.ROTATION_90, 0f, -w, 0f), 0f)
        assertArrayEquals(floatArrayOf(w, 0f, 0f), ctrl(SteeringMath.ROTATION_270, 0f, w, 0f), 0f)
        // Rotation about world up (yaw). Device axis: +x in 90, -x in 270.
        assertArrayEquals(floatArrayOf(0f, w, 0f), ctrl(SteeringMath.ROTATION_90, w, 0f, 0f), 0f)
        assertArrayEquals(floatArrayOf(0f, w, 0f), ctrl(SteeringMath.ROTATION_270, -w, 0f, 0f), 0f)
        // Roll about the screen normal is the same axis in every landscape.
        assertArrayEquals(floatArrayOf(0f, 0f, w), ctrl(SteeringMath.ROTATION_270, 0f, 0f, w), 0f)
    }

    @Test
    fun bothLandscapesAreProperRotations() {
        for (rot in intArrayOf(SteeringMath.ROTATION_0, SteeringMath.ROTATION_90, SteeringMath.ROTATION_180, SteeringMath.ROTATION_270)) {
            val ex = ctrl(rot, 1f, 0f, 0f)
            val ey = ctrl(rot, 0f, 1f, 0f)
            val ez = ctrl(rot, 0f, 0f, 1f)
            // Right-handed: (ex x ey) == ez, so rotation signs survive the remap.
            val cx = ex[1] * ey[2] - ex[2] * ey[1]
            val cy = ex[2] * ey[0] - ex[0] * ey[2]
            val cz = ex[0] * ey[1] - ex[1] * ey[0]
            assertArrayEquals("rotation $rot", ez, floatArrayOf(cx, cy, cz), 0f)
        }
    }

    @Test
    fun wireUnitsAndSaturation() {
        assertEquals(2880, MotionMath.gyroUnits(PI.toFloat())) // 180 dps
        assertEquals(-16, MotionMath.gyroUnits((-PI / 180.0).toFloat()))
        assertEquals(32767, MotionMath.gyroUnits(100f))
        assertEquals(-32767, MotionMath.gyroUnits(-100f))
        assertEquals(4096, MotionMath.accelUnits(MotionMath.STANDARD_GRAVITY))
        assertEquals(-32767, MotionMath.accelUnits(-10 * MotionMath.STANDARD_GRAVITY))
        assertEquals(0, MotionMath.gyroUnits(Float.NaN))
        assertEquals(180f, MotionMath.radToDps(PI.toFloat()), 0.001f)
        assertTrue(abs(MotionMath.radToDps(1f) - 57.29578f) < 0.001f)
    }
}
