package com.slipstream.wheel.pad

import com.slipstream.wheel.input.PedalMath

/**
 * Trigger bars (ARCHITECTURE.md 7.1): slide along the bar like the wheel's swipe pedals, or
 * tap mode (full on touch). Positions are measured along the bar's axis in the direction
 * that presses the trigger (toward the screen centre), in pixels.
 */
object TriggerMath {
    enum class Mode { SLIDE, TAP }

    /** Share of the bar's length that gives a full press. */
    const val DEFAULT_TRAVEL = 0.6f
    const val FULL = 65535

    fun travelPx(barLengthPx: Float, fraction: Float): Float = (barLengthPx * fraction).coerceAtLeast(1f)

    /**
     * Anchor after the finger moved to [pos]. Moving back past the landing point re-anchors
     * there (value 0); moving past full travel drags the anchor along, so the trigger starts
     * to release on the first pixel back.
     */
    fun anchor(anchor: Float, pos: Float, travel: Float): Float = when {
        pos < anchor -> pos
        pos - anchor > travel -> pos - travel
        else -> anchor
    }

    fun value(anchor: Float, pos: Float, travel: Float): Float = ((pos - anchor) / travel).coerceIn(0f, 1f)

    /** 0..1 to 0..65535 through the response curve. */
    fun output(v: Float, curve: Float): Int = PedalMath.output(v, curve)
}
