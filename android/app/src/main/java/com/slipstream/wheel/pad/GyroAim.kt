package com.slipstream.wheel.pad

/**
 * Gyro aim (ARCHITECTURE.md 7.1): the phone's yaw and pitch rates, in the controller frame of
 * PROTOCOL.md 12.1, become a right stick deflection that the snapshot adds to the touch stick.
 *
 * Frame: +x to the player's right, +y up, +z out of the screen toward the player, rates by
 * the right-hand rule. The phone aims through its back (-z). A positive rate about +y swings
 * the back toward the player's left, so it aims left (negative x). A positive rate about +x
 * tips the top edge toward the player and the back upward, so it aims up (positive y).
 */
object GyroAim {
    enum class Mode { OFF, ALWAYS, WHILE_TOUCHING }

    /** At sensitivity 1, this rate is a full stick deflection. */
    const val FULL_SCALE_DPS = 120f

    /** Rates below this are hand tremor and sensor noise: gated out, the rest passes. */
    const val NOISE_DPS = 1.0f

    const val SENSITIVITY_MIN = 0.25f
    const val SENSITIVITY_MAX = 4f
    const val SENSITIVITY_DEFAULT = 1f

    fun active(mode: Mode, touchingRightStick: Boolean): Boolean =
        mode == Mode.ALWAYS || (mode == Mode.WHILE_TOUCHING && touchingRightStick)

    /**
     * Rates about the controller +x (pitch) and +y (yaw) axes, degrees per second, to a
     * right stick deflection packed as StickMath.pack.
     */
    fun deflection(pitchDps: Float, yawDps: Float, sensitivity: Float, invertY: Boolean): Int {
        val k = sensitivity.coerceIn(SENSITIVITY_MIN, SENSITIVITY_MAX) / FULL_SCALE_DPS
        val x = -gate(yawDps) * k
        var y = gate(pitchDps) * k
        if (invertY) y = -y
        return StickMath.pack(StickMath.toI16(x), StickMath.toI16(y))
    }

    /** Soft noise gate: 0 inside +-NOISE_DPS, then continuous from 0. */
    fun gate(v: Float): Float = when {
        v > NOISE_DPS -> v - NOISE_DPS
        v < -NOISE_DPS -> v + NOISE_DPS
        else -> 0f
    }
}
