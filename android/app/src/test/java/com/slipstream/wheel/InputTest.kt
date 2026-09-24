package com.slipstream.wheel

import com.slipstream.wheel.input.ControllerState
import com.slipstream.wheel.input.OneEuroFilter
import com.slipstream.wheel.input.PedalMath
import com.slipstream.wheel.input.PulseCounters
import com.slipstream.wheel.input.SteeringMath
import com.slipstream.wheel.input.SteeringProcessor
import com.slipstream.wheel.protocol.InputFrame
import com.slipstream.wheel.protocol.Slp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class PulseCountersTest {
    @Test
    fun incrementWrapsPerChannelWithoutCarry() {
        var p = 0L
        repeat(255) { p = PulseCounters.increment(p, 0) }
        assertEquals(255, PulseCounters.get(p, 0))
        assertEquals(0, PulseCounters.get(p, 1))
        p = PulseCounters.increment(p, 0)
        assertEquals(0, PulseCounters.get(p, 0))
        assertEquals("no carry into channel 1", 0, PulseCounters.get(p, 1))
    }

    @Test
    fun topChannelWrapsCleanly() {
        var p = 0L
        repeat(256 + 9) { p = PulseCounters.increment(p, 7) }
        assertEquals(9, PulseCounters.get(p, 7))
        for (ch in 0 until 7) assertEquals(0, PulseCounters.get(p, ch))
    }

    @Test
    fun unpackInChannelOrder() {
        var p = 0L
        for (ch in 0 until 8) repeat(ch + 1) { p = PulseCounters.increment(p, ch) }
        val out = ByteArray(8)
        PulseCounters.unpack(p, out)
        for (ch in 0 until 8) assertEquals(ch + 1, out[ch].toInt())
    }

    @Test
    fun deltaAcrossWrapCountsPresses() {
        // The hub sees new - old mod 256; a wrap from 255 to 1 is two presses.
        assertEquals(2, PulseCounters.delta(1, 255))
        assertEquals(0, PulseCounters.delta(128, 0))
    }
}

class ControllerStateTest {
    @Test
    fun snapshotCarriesEveryField() {
        val s = ControllerState()
        s.setSteer(-1234)
        s.setThrottle(40000)
        s.setBrake(1)
        s.setClutch(65535)
        s.setHandbrake(32768)
        s.setButton(0, true)
        s.setButton(23, true)
        s.pulse(PulseCounters.SHIFT_UP)
        s.pulse(PulseCounters.SHIFT_DOWN)
        s.pulse(PulseCounters.SHIFT_DOWN)
        s.setFlag(Slp.FLAG_PAUSED, false)
        val f = InputFrame()
        s.snapshot(f)
        assertEquals(-1234, f.steer)
        assertEquals(40000, f.throttle)
        assertEquals(1, f.brake)
        assertEquals(65535, f.clutch)
        assertEquals(32768, f.handbrake)
        assertEquals(0, f.aux)
        assertEquals((1 shl 0) or (1 shl 23), f.buttons)
        assertEquals(1, f.pulses[0].toInt())
        assertEquals(2, f.pulses[1].toInt())
        assertEquals(0, f.flags)
    }

    @Test
    fun clampsAndIgnoresReservedBits() {
        val s = ControllerState()
        s.setSteer(-40000)
        s.setThrottle(70000)
        s.setBrake(-5)
        s.setButton(24, true)
        s.setButton(31, true)
        val f = InputFrame()
        s.snapshot(f)
        assertEquals(-32767, f.steer)
        assertEquals(65535, f.throttle)
        assertEquals(0, f.brake)
        assertEquals(0, f.buttons)
    }

    @Test
    fun startsPausedAndMultipathNeverComesFromState() {
        val s = ControllerState()
        val f = InputFrame()
        s.snapshot(f)
        assertEquals(Slp.FLAG_PAUSED, f.flags)
        s.setFlag(Slp.FLAG_MULTIPATH, true)
        s.snapshot(f)
        assertEquals(0, f.flags and Slp.FLAG_MULTIPATH)
    }

    @Test
    fun pulsesWrapInState() {
        val s = ControllerState()
        repeat(300) { s.pulse(1) }
        assertEquals(300 % 256, s.pulseCounter(1))
        assertEquals(0, s.pulseCounter(0))
    }

    @Test
    fun signalRaisedOnlyOnChange() {
        val s = ControllerState()
        s.signal.consume()
        s.setThrottle(100)
        assertTrue(s.signal.consume())
        s.setThrottle(100)
        assertFalse("same value is not a change", s.signal.consume())
        s.setSteer(5)
        assertTrue(s.signal.consume())
        s.setSteer(5)
        assertFalse(s.signal.consume())
        s.pulse(0)
        assertTrue("every pulse is a change", s.signal.consume())
    }

    @Test
    fun concurrentWritersDoNotLoseUpdates() {
        val s = ControllerState()
        val threads = (0 until 4).map { t ->
            Thread {
                repeat(10_000) { i ->
                    when (t) {
                        0 -> s.setThrottle(i and 0xFFFF)
                        1 -> s.setBrake(i and 0xFFFF)
                        2 -> s.pulse(2)
                        else -> s.setButton(i % 24, i % 2 == 0)
                    }
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(9999, s.throttle)
        assertEquals(9999, s.brake)
        assertEquals(10_000 % 256, s.pulseCounter(2))
    }

    @Test
    fun releaseAllKeepsSteeringAndPulses() {
        val s = ControllerState()
        s.setSteer(700)
        s.setThrottle(5000)
        s.setButton(3, true)
        s.pulse(0)
        s.releaseAll()
        val f = InputFrame()
        s.snapshot(f)
        assertEquals(700, f.steer)
        assertEquals(0, f.throttle)
        assertEquals(0, f.buttons)
        assertEquals(1, f.pulses[0].toInt())
    }
}

class PedalMathTest {
    private val travel = PedalMath.travelPx(1000f, 0.35f)

    @Test
    fun travelIsShareOfScreenHeight() {
        assertEquals(350f, travel, 1e-3f)
    }

    @Test
    fun swipeMeasuresUpwardTravelFromLanding() {
        val anchor = 800f
        assertEquals(0f, PedalMath.swipeValue(anchor, 800f, travel), 1e-6f)
        assertEquals(0.5f, PedalMath.swipeValue(anchor, 800f - 175f, travel), 1e-6f)
        assertEquals(1f, PedalMath.swipeValue(anchor, 800f - 350f, travel), 1e-6f)
        assertEquals("clamped at full travel", 1f, PedalMath.swipeValue(anchor, 100f, travel), 1e-6f)
    }

    @Test
    fun movingBelowLandingReanchors() {
        var anchor = 800f
        anchor = PedalMath.swipeAnchor(anchor, 700f, travel)
        assertEquals("moving up keeps the anchor", 800f, anchor, 0f)
        anchor = PedalMath.swipeAnchor(anchor, 900f, travel)
        assertEquals("moving below re-anchors", 900f, anchor, 0f)
        assertEquals(0f, PedalMath.swipeValue(anchor, 900f, travel), 0f)
        assertEquals(1f, PedalMath.swipeValue(anchor, 900f - 350f, travel), 1e-6f)
        assertEquals(0.5f, PedalMath.swipeValue(anchor, 900f - 175f, travel), 1e-6f)
    }

    /** Replays a finger path through anchor and value, as DriveSurfaceView does. */
    private fun swipe(landY: Float, vararg ys: Float): FloatArray {
        var anchor = landY
        return FloatArray(ys.size) { i ->
            anchor = PedalMath.swipeAnchor(anchor, ys[i], travel)
            PedalMath.swipeValue(anchor, ys[i], travel)
        }
    }

    @Test
    fun overshootReleasesOnTheFirstPixelBack() {
        // Land at 800, full travel is at 450; the finger overshoots to 300, then comes back 1 px.
        val v = swipe(800f, 450f, 300f, 301f, 310f)
        assertEquals(1f, v[0], 1e-6f)
        assertEquals("still full while overshooting", 1f, v[1], 1e-6f)
        assertTrue("releases at once, no dead band, got ${v[2]}", v[2] < 1f)
        assertEquals((350f - 10f) / 350f, v[3], 1e-5f)
    }

    @Test
    fun withinTravelTheLandingPointStays() {
        val v = swipe(800f, 700f, 625f, 700f, 800f)
        assertEquals(100f / 350f, v[0], 1e-6f)
        assertEquals(0.5f, v[1], 1e-6f)
        assertEquals("back to the same height, same value", 100f / 350f, v[2], 1e-6f)
        assertEquals(0f, v[3], 0f)
    }

    @Test
    fun absoluteIsHeightInZone() {
        assertEquals(0f, PedalMath.absoluteValue(1000f, 0f, 1000f), 1e-6f)
        assertEquals(1f, PedalMath.absoluteValue(0f, 0f, 1000f), 1e-6f)
        assertEquals(0.25f, PedalMath.absoluteValue(750f, 0f, 1000f), 1e-6f)
        assertEquals(0f, PedalMath.absoluteValue(1200f, 0f, 1000f), 1e-6f)
        assertEquals(1f, PedalMath.absoluteValue(-50f, 0f, 1000f), 1e-6f)
    }

    @Test
    fun curvesAndScaling() {
        assertEquals(0.25f, PedalMath.curve(0.5f, 2f), 1e-6f)
        assertEquals(0.5f, PedalMath.curve(0.5f, 1f), 0f)
        assertEquals(0f, PedalMath.curve(0f, 2f), 0f)
        assertEquals(1f, PedalMath.curve(1f, 0.5f), 0f)
        assertEquals(0, PedalMath.toU16(0f))
        assertEquals(65535, PedalMath.toU16(1f))
        assertEquals(32768, PedalMath.toU16(0.5f))
        assertEquals(65535, PedalMath.toU16(1.5f))
        assertEquals(0, PedalMath.toU16(-0.2f))
        assertEquals(16384, PedalMath.output(0.5f, 2f))
    }
}

class OneEuroFilterTest {
    private fun step(filter: OneEuroFilter, ms: Int): DoubleArray {
        val out = DoubleArray(ms + 1)
        out[0] = filter.filter(0.0, 0L)
        for (t in 1..ms) out[t] = filter.filter(1.0, t * 1_000_000L)
        return out
    }

    @Test
    fun firstSamplePassesThrough() {
        assertEquals(0.42, OneEuroFilter(1.0, 0.0).filter(0.42, 123L), 0.0)
    }

    @Test
    fun stepResponseIsMonotonicWithoutOvershoot() {
        val y = step(OneEuroFilter(1.0, 0.0), 2000)
        for (t in 1 until y.size) {
            assertTrue("monotonic at $t ms", y[t] >= y[t - 1])
            assertTrue("no overshoot at $t ms", y[t] <= 1.0)
        }
        assertTrue(y.last() > 0.999)
    }

    @Test
    fun beta0IsFirstOrderLowPassAtMinCutoff() {
        // tau = 1 / (2 pi fc): with fc = 1 Hz the step reaches 1 - 1/e after about 159 ms.
        val y = step(OneEuroFilter(1.0, 0.0), 400)
        val tau = (1000.0 / (2 * PI)).toInt()
        assertEquals(0.632, y[tau], 0.02)
    }

    @Test
    fun speedRaisesCutoff() {
        val slow = step(OneEuroFilter(1.0, 0.0), 50)
        val fast = step(OneEuroFilter(1.0, 5.0), 50)
        assertTrue("beta makes a fast move follow sooner", fast[20] > slow[20] + 0.1)
        for (t in fast.indices) assertTrue(fast[t] <= 1.0)
    }

    @Test
    fun resetForgetsHistory() {
        val f = OneEuroFilter(1.0, 0.0)
        step(f, 10)
        f.reset()
        assertEquals(-0.7, f.filter(-0.7, 999_000_000L), 0.0)
    }
}

/**
 * Steering sign convention: a clockwise turn of the phone as seen by the driver is positive.
 *
 * Device axes: x right, y up in natural portrait, z out of the screen toward the driver.
 * Turning the phone clockwise by theta as the driver sees it is a rotation of the device by
 * -theta about its z axis, so a world-fixed vector (up) expressed in device coordinates
 * rotates by +theta. ROTATION_90 means the phone is turned 90 degrees counter-clockwise
 * from portrait, so device +x points up: neutral g = (1, 0, 0). ROTATION_270 is the other
 * landscape, device +x points down: neutral g = (-1, 0, 0).
 */
class SteeringMathTest {
    private fun rad(d: Double) = d * PI / 180.0
    private fun deg(r: Double) = r * 180.0 / PI

    /** World-up in device coordinates after a clockwise turn of [deg] from [neutralX]. */
    private fun gravity(neutralX: Double, deg: Double, tiltBack: Double = 0.0): DoubleArray {
        val t = rad(deg)
        val x = neutralX * cos(t)
        val y = neutralX * sin(t)
        val inPlane = cos(rad(tiltBack))
        return doubleArrayOf(x * inPlane, y * inPlane, sin(rad(tiltBack)))
    }

    @Test
    fun rotation90ClockwiseIsPositive() {
        for (d in listOf(-60.0, -30.0, -5.0, 0.0, 5.0, 30.0, 60.0, 120.0)) {
            val g = gravity(1.0, d)
            assertEquals("ROTATION_90 turn $d", d, deg(SteeringMath.angle(g[0], g[1], g[2], SteeringMath.ROTATION_90)), 1e-9)
        }
    }

    @Test
    fun rotation270ClockwiseIsPositive() {
        for (d in listOf(-60.0, -30.0, -5.0, 0.0, 5.0, 30.0, 60.0, 120.0)) {
            val g = gravity(-1.0, d)
            assertEquals("ROTATION_270 turn $d", d, deg(SteeringMath.angle(g[0], g[1], g[2], SteeringMath.ROTATION_270)), 1e-9)
        }
    }

    @Test
    fun spec90And270FormulasLiterally() {
        // angle = atan2(g.y, g.x) for ROTATION_90, atan2(-g.y, -g.x) for ROTATION_270.
        assertEquals(30.0, deg(SteeringMath.angle(cos(rad(30.0)), sin(rad(30.0)), 0.0, SteeringMath.ROTATION_90)), 1e-9)
        assertEquals(30.0, deg(SteeringMath.angle(-cos(rad(30.0)), -sin(rad(30.0)), 0.0, SteeringMath.ROTATION_270)), 1e-9)
        assertEquals(-30.0, deg(SteeringMath.angle(cos(rad(30.0)), -sin(rad(30.0)), 0.0, SteeringMath.ROTATION_90)), 1e-9)
    }

    @Test
    fun tiltingBackDoesNotSteer() {
        for (tilt in listOf(0.0, 30.0, 60.0)) {
            val g = gravity(1.0, 20.0, tilt)
            assertEquals("tilt $tilt", 20.0, deg(SteeringMath.angle(g[0], g[1], g[2], SteeringMath.ROTATION_90)), 1e-9)
        }
    }

    @Test
    fun magnitudeDoesNotMatter() {
        // Gravity and accelerometer sensors report m/s^2, not a unit vector.
        val g = gravity(-1.0, -15.0, 20.0).map { it * 9.81 }
        assertEquals(-15.0, deg(SteeringMath.angle(g[0], g[1], g[2], SteeringMath.ROTATION_270)), 1e-9)
    }

    @Test
    fun nearlyFlatIsNaNAndProcessorHolds() {
        val flat = gravity(1.0, 40.0, 80.0) // in-screen component cos(80) = 0.17 < 0.25
        assertTrue(SteeringMath.angle(flat[0], flat[1], flat[2], SteeringMath.ROTATION_90).isNaN())
        val p = SteeringProcessor(SteeringProcessor.Config(lockDeg = 90.0, deadzoneDeg = 0.0, smoothing = 0))
        val g = gravity(1.0, 45.0)
        val before = p.process(g[0], g[1], g[2], SteeringMath.ROTATION_90, 0)
        val held = p.process(flat[0], flat[1], flat[2], SteeringMath.ROTATION_90, 1_000_000)
        assertEquals(before, held)
    }

    private fun assertNear(msg: String, expected: Int, actual: Int) =
        assertTrue("$msg: expected $expected +-1, got $actual", kotlin.math.abs(expected - actual) <= 1)

    @Test
    fun lockDeadzoneCurveAndScale() {
        val p = SteeringProcessor(SteeringProcessor.Config(lockDeg = 90.0, deadzoneDeg = 0.0, curve = 1.0, smoothing = 0))
        fun at(d: Double): Int {
            val g = gravity(1.0, d)
            return p.process(g[0], g[1], g[2], SteeringMath.ROTATION_90, 0)
        }
        assertEquals(0, at(0.0))
        assertNear("45", 16384, at(45.0))
        assertNear("-45", -16384, at(-45.0))
        assertEquals("symmetric", -at(30.0), at(-30.0))
        assertEquals(32767, at(90.0))
        assertEquals("clamped", 32767, at(150.0))
        assertEquals("never -32768", -32767, at(-150.0))

        val dz = SteeringProcessor(SteeringProcessor.Config(lockDeg = 90.0, deadzoneDeg = 2.0, smoothing = 0))
        val g1 = gravity(1.0, 1.5)
        assertEquals(0, dz.process(g1[0], g1[1], g1[2], SteeringMath.ROTATION_90, 0))
        val g2 = gravity(1.0, 46.0)
        assertNear("range after the deadzone is rescaled", 16384, dz.process(g2[0], g2[1], g2[2], SteeringMath.ROTATION_90, 0))

        val curved = SteeringProcessor(SteeringProcessor.Config(lockDeg = 90.0, deadzoneDeg = 0.0, curve = 2.0, smoothing = 0))
        val g3 = gravity(1.0, 45.0)
        assertNear("curve 2", 8192, curved.process(g3[0], g3[1], g3[2], SteeringMath.ROTATION_90, 0))
    }

    @Test
    fun recenterMakesCurrentAngleStraightAhead() {
        val p = SteeringProcessor(SteeringProcessor.Config(lockDeg = 90.0, deadzoneDeg = 0.0, smoothing = 0))
        val g = gravity(-1.0, 10.0)
        p.process(g[0], g[1], g[2], SteeringMath.ROTATION_270, 0)
        p.recenter()
        assertEquals(0, p.process(g[0], g[1], g[2], SteeringMath.ROTATION_270, 1))
        val g2 = gravity(-1.0, 55.0)
        assertNear("after recenter", 16384, p.process(g2[0], g2[1], g2[2], SteeringMath.ROTATION_270, 2))
        assertEquals(rad(10.0), p.center, 1e-9)
    }

    @Test
    fun centerNearHalfTurnWraps() {
        val p = SteeringProcessor(SteeringProcessor.Config(lockDeg = 90.0, deadzoneDeg = 0.0, smoothing = 0, centerRad = rad(170.0)))
        val g = gravity(1.0, -170.0) // 20 degrees clockwise past the center, across the wrap
        assertNear("wrap", SteeringMath.toI16(20.0 / 90.0), p.process(g[0], g[1], g[2], SteeringMath.ROTATION_90, 0))
    }

    @Test
    fun smoothingFollowsAStepWithinAFewFrames() {
        val p = SteeringProcessor(SteeringProcessor.Config(lockDeg = 90.0, deadzoneDeg = 0.0, smoothing = 2))
        val g0 = gravity(1.0, 0.0)
        val g1 = gravity(1.0, 45.0)
        p.process(g0[0], g0[1], g0[2], SteeringMath.ROTATION_90, 0)
        var v = 0
        for (i in 1..100) v = p.process(g1[0], g1[1], g1[2], SteeringMath.ROTATION_90, i * 5_000_000L)
        assertTrue("reaches the target after 0.5 s at 200 Hz", v > 16000)
    }
}
