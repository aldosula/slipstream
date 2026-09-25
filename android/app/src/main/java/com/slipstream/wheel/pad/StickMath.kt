package com.slipstream.wheel.pad

import com.slipstream.wheel.input.PedalMath
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Touch stick geometry as pure functions (ARCHITECTURE.md 7.1). Screen y grows downward;
 * the wire's +y is up. A stick value is two i16 packed into one Int (x | y << 16) so the
 * touch path returns it without allocating.
 */
object StickMath {
    const val DEFAULT_DEADZONE = 0.08f
    const val DEFAULT_CURVE = 1.0f
    const val MAX_DEADZONE = 0.5f

    fun pack(x: Int, y: Int): Int = (x and 0xFFFF) or (y shl 16)
    fun x(packed: Int): Int = (packed shl 16) shr 16
    fun y(packed: Int): Int = packed shr 16

    /** -1..1 to the wire i16, rounded half away from zero so both directions match. Never -32768. */
    fun toI16(v: Float): Int {
        val s = v.coerceIn(-1f, 1f) * 32767f
        val r = if (s >= 0f) floor(s + 0.5f) else -floor(-s + 0.5f)
        return r.toInt().coerceIn(-32767, 32767)
    }

    /**
     * Finger offset from the stick origin ([dx], [dy] in pixels, y down) to the wire value.
     * The offset is limited to the stick's circle of [radius]; a radial [deadzone] (a share
     * of the radius) is cut out and the rest rescaled so full deflection still reaches 1;
     * then the response [curve] is applied to the magnitude. The direction is kept.
     */
    fun output(dx: Float, dy: Float, radius: Float, deadzone: Float, curve: Float): Int {
        if (radius <= 0f) return 0
        val nx = dx / radius
        val ny = -dy / radius
        val mag = sqrt(nx * nx + ny * ny)
        val dz = deadzone.coerceIn(0f, MAX_DEADZONE)
        if (mag <= dz || mag == 0f) return 0
        val lin = ((mag.coerceAtMost(1f) - dz) / (1f - dz)).coerceIn(0f, 1f)
        val shaped = PedalMath.curve(lin, curve.coerceIn(0.2f, 5f))
        val k = shaped / mag
        return pack(toI16(nx * k), toI16(ny * k))
    }

    /** Floating origin: where the thumb lands, kept inside the control's area. */
    fun origin(landing: Float, min: Float, max: Float): Float =
        if (max < min) (min + max) / 2f else landing.coerceIn(min, max)

    /** The knob's drawn offset: the finger offset limited to the circle. Writes (x, y) into out. */
    fun knob(dx: Float, dy: Float, radius: Float, out: FloatArray) {
        val mag = sqrt(dx * dx + dy * dy)
        if (mag <= radius || mag == 0f) {
            out[0] = dx
            out[1] = dy
        } else {
            out[0] = dx * radius / mag
            out[1] = dy * radius / mag
        }
    }

    /** Magnitude of a packed value, 0..1, for tests and gauges. */
    fun magnitude(packed: Int): Float {
        val fx = x(packed) / 32767f
        val fy = y(packed) / 32767f
        return sqrt(fx * fx + fy * fy)
    }
}
