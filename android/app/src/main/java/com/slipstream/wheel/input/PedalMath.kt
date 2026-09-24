package com.slipstream.wheel.input

import kotlin.math.pow

/**
 * Pedal geometry as pure functions. Screen y grows downward, so "up" means smaller y.
 * Every value is normalized to 0..1 before the curve and the u16 scaling.
 */
object PedalMath {
    const val DEFAULT_TRAVEL = 0.35f

    /** Travel in pixels for swipe mode: a fraction of the screen height. */
    fun travelPx(screenHeightPx: Float, travelFraction: Float): Float =
        (screenHeightPx * travelFraction).coerceAtLeast(1f)

    /**
     * Swipe mode anchor after the finger moves to [y]. Moving below the landing point
     * re-anchors it there, so the pedal is at zero and pressing starts again from that point.
     * Moving above full travel drags the anchor up with the finger, so the pedal stays at 1
     * and starts to release on the very first pixel back down. Without that, an overshoot
     * of N pixels is a dead band of N pixels on release: lift-off latency on the throttle.
     */
    fun swipeAnchor(anchorY: Float, y: Float, travelPx: Float): Float = when {
        y > anchorY -> y
        anchorY - y > travelPx -> y + travelPx
        else -> anchorY
    }

    /** Swipe mode value: how far the finger is above its anchor, over [travelPx]. */
    fun swipeValue(anchorY: Float, y: Float, travelPx: Float): Float =
        ((anchorY - y) / travelPx).coerceIn(0f, 1f)

    /** Absolute mode value: the finger's height in the zone, 0 at the bottom, 1 at the top. */
    fun absoluteValue(y: Float, zoneTop: Float, zoneBottom: Float): Float {
        val h = zoneBottom - zoneTop
        if (h <= 0f) return 0f
        return ((zoneBottom - y) / h).coerceIn(0f, 1f)
    }

    /** Response curve: v ^ exponent. 1 is linear, above 1 is finer near rest. */
    fun curve(v: Float, exponent: Float): Float = when {
        v <= 0f -> 0f
        v >= 1f -> 1f
        exponent == 1f -> v
        else -> v.pow(exponent)
    }

    /** 0..1 to the wire scale 0..65535, rounded. */
    fun toU16(v: Float): Int = (v * 65535f + 0.5f).toInt().coerceIn(0, 65535)

    /** Raw normalized position to the value put on the wire. */
    fun output(v: Float, exponent: Float): Int = toU16(curve(v, exponent))
}
