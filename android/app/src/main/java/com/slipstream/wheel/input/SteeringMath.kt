package com.slipstream.wheel.input

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Steering geometry (ARCHITECTURE.md section 3), pure so it can be tested with synthetic
 * gravity vectors.
 *
 * Input is world-up expressed in device coordinates, g = (R[6], R[7], R[8]) from the
 * rotation matrix, or the gravity / accelerometer reading (same direction, any magnitude).
 * Device axes: x to the right and y to the top of the phone in its natural portrait
 * orientation, z out of the screen toward the driver.
 *
 * Output angle is in radians, positive for a clockwise turn as seen by the driver
 * (steer right).
 */
object SteeringMath {
    /** Display rotations, same values as android.view.Surface.ROTATION_*. */
    const val ROTATION_0 = 0
    const val ROTATION_90 = 1
    const val ROTATION_180 = 2
    const val ROTATION_270 = 3

    /** Below this in-screen gravity component (of a unit vector) the phone is nearly flat. */
    const val FLAT_THRESHOLD = 0.25

    /**
     * Steering angle for the given display rotation, or NaN when the phone is nearly flat
     * (the caller holds the last angle).
     *
     * ROTATION_90: angle = atan2(g.y, g.x). ROTATION_270: angle = atan2(-g.y, -g.x).
     * ROTATION_0 and ROTATION_180 (tablets whose natural orientation is landscape) follow
     * the same rule after mapping device axes to display axes.
     */
    fun angle(gx: Double, gy: Double, gz: Double, rotation: Int): Double {
        val norm = sqrt(gx * gx + gy * gy + gz * gz)
        if (norm < 1e-6) return Double.NaN
        val x = gx / norm
        val y = gy / norm
        if (sqrt(x * x + y * y) < FLAT_THRESHOLD) return Double.NaN
        return when (rotation) {
            ROTATION_90 -> atan2(y, x)
            ROTATION_270 -> atan2(-y, -x)
            ROTATION_180 -> atan2(x, -y)
            else -> atan2(-x, y)
        }
    }

    /** Wraps an angle into (-pi, pi]. */
    fun wrap(a: Double): Double {
        var v = a
        while (v > PI) v -= 2 * PI
        while (v <= -PI) v += 2 * PI
        return v
    }

    /**
     * Relative angle to a normalized steer value in -1..1: deadzone (the remaining range is
     * rescaled so full lock still reaches 1), clamp(angle / lock), response curve.
     */
    fun shape(relative: Double, deadzone: Double, lock: Double, curve: Double): Double {
        val mag = abs(relative)
        if (mag <= deadzone || lock <= deadzone) return 0.0
        val lin = ((mag - deadzone) / (lock - deadzone)).coerceIn(0.0, 1.0)
        val shaped = if (curve == 1.0) lin else lin.pow(curve)
        return if (relative < 0) -shaped else shaped
    }

    /** -1..1 to the wire i16, rounded half away from zero so left and right match. Never -32768. */
    fun toI16(v: Double): Int {
        val s = v.coerceIn(-1.0, 1.0) * 32767.0
        val r = if (s >= 0) floor(s + 0.5) else -floor(-s + 0.5)
        return r.toInt().coerceIn(-32767, 32767)
    }

    fun degToRad(d: Double): Double = d * PI / 180.0
}

/**
 * Stateful steering pipeline: flat hold, calibrated center, deadzone, lock, curve, One Euro
 * filter, i16. Owned by the sensor thread; nothing here allocates per sample.
 */
class SteeringProcessor(config: Config) {
    data class Config(
        val lockDeg: Double = 90.0,
        val deadzoneDeg: Double = 0.5,
        val curve: Double = 1.0,
        /** 0 is off, 1..10 is light to heavy. */
        val smoothing: Int = 2,
        /** Stronger filtering for the raw accelerometer fallback. */
        val strongFilter: Boolean = false,
        val centerRad: Double = 0.0,
    )

    private val lock = SteeringMath.degToRad(config.lockDeg.coerceIn(10.0, 540.0))
    private val deadzone = SteeringMath.degToRad(config.deadzoneDeg.coerceIn(0.0, 30.0))
    private val curve = config.curve.coerceIn(0.2, 5.0)
    private val filter: OneEuroFilter? = filterFor(config.smoothing, config.strongFilter)

    /** Calibrated center, radians. */
    var center = config.centerRad
        private set

    /** Last raw angle (before the center), radians. Held while the phone is flat. */
    var lastRaw = 0.0
        private set

    var hasSample = false
        private set

    /** Last normalized output, -1..1, for gauges. */
    var lastNormalized = 0.0
        private set

    /** Processes one gravity sample. Returns the steer value for the wire. */
    fun process(gx: Double, gy: Double, gz: Double, rotation: Int, tNs: Long): Int {
        val a = SteeringMath.angle(gx, gy, gz, rotation)
        if (!a.isNaN()) {
            lastRaw = a
            hasSample = true
        }
        val rel = SteeringMath.wrap(lastRaw - center)
        val shaped = SteeringMath.shape(rel, deadzone, lock, curve)
        val out = filter?.filter(shaped, tNs) ?: shaped
        lastNormalized = out
        return SteeringMath.toI16(out)
    }

    /** Makes the current physical angle the new center. */
    fun recenter() {
        center = lastRaw
        filter?.reset()
    }

    companion object {
        fun filterFor(smoothing: Int, strong: Boolean): OneEuroFilter? {
            val level = smoothing.coerceIn(0, 10)
            if (level == 0 && !strong) return null
            val effective = if (level == 0) 1 else level
            var minCutoff = 12.0 / effective
            var beta = 4.0
            if (strong) {
                minCutoff /= 3.0
                beta /= 2.0
            }
            return OneEuroFilter(minCutoff, beta, 1.0)
        }
    }
}
