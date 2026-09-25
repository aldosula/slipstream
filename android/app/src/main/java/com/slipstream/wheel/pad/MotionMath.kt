package com.slipstream.wheel.pad

import com.slipstream.wheel.input.SteeringMath
import kotlin.math.PI
import kotlin.math.floor

/**
 * Motion geometry and units (PROTOCOL.md 12.1), pure so it can be tested with synthetic
 * vectors.
 *
 * Android reports sensors in device axes: x to the right and y to the top of the phone in
 * its natural (portrait) orientation, z out of the screen. The controller frame is the phone
 * held in landscape with the screen facing the player: +x to the player's right, +y up,
 * +z toward the player. ROTATION_90 is the phone turned 90 degrees counter-clockwise from
 * portrait, so device +x points up and device -y points right; ROTATION_270 is the other
 * landscape, device -x up and device +y right.
 *
 * Accelerometer values keep Android's sign: at rest the axis pointing up reads +1 g.
 */
object MotionMath {
    const val GYRO_UNITS_PER_DPS = 16f
    const val ACCEL_UNITS_PER_G = 4096f
    const val STANDARD_GRAVITY = 9.80665f
    private const val RAD_TO_DEG = (180.0 / PI).toFloat()

    /** Device axes to the controller frame for display [rotation] (Surface.ROTATION_*). */
    fun toController(rotation: Int, x: Float, y: Float, z: Float, out: FloatArray) {
        when (rotation) {
            SteeringMath.ROTATION_90 -> {
                out[0] = -y
                out[1] = x
            }
            SteeringMath.ROTATION_270 -> {
                out[0] = y
                out[1] = -x
            }
            SteeringMath.ROTATION_180 -> {
                out[0] = -x
                out[1] = -y
            }
            else -> {
                out[0] = x
                out[1] = y
            }
        }
        out[2] = z
    }

    fun radToDps(radPerSec: Float): Float = radPerSec * RAD_TO_DEG

    /** Rad/s to the wire's 1/16 dps, saturating at +-2048 dps. */
    fun gyroUnits(radPerSec: Float): Int = round16(radToDps(radPerSec) * GYRO_UNITS_PER_DPS)

    /** m/s^2 to the wire's 1/4096 g, saturating at +-8 g. */
    fun accelUnits(ms2: Float): Int = round16(ms2 / STANDARD_GRAVITY * ACCEL_UNITS_PER_G)

    private fun round16(v: Float): Int {
        if (v.isNaN()) return 0
        val r = if (v >= 0f) floor(v + 0.5f) else -floor(-v + 0.5f)
        return r.coerceIn(-32767f, 32767f).toInt()
    }
}
