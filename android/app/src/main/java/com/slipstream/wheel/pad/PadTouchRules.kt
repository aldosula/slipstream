package com.slipstream.wheel.pad

/**
 * Touch decisions of the play surface that do not need a View (ARCHITECTURE.md 7.1), pure so
 * they can be unit tested. Distances are in pixels, times in milliseconds.
 */
object PadTouchRules {
    /** Touchpad tap: shorter than 200 ms and moving less than 4 % of the pad. */
    const val TOUCH_TAP_MS = 200L
    const val TOUCH_TAP_TRAVEL = 0.04f

    /** Shortest time a touchpad click is held, so the hub sees at least one packet of it. */
    const val TOUCH_CLICK_MIN_MS = 40L

    /** MotionEvent.FLAG_CANCELED (API 33): the pointer was cancelled, for example by palm rejection. */
    const val FLAG_CANCELED = 0x20

    /**
     * Whether a finger landing [distance] from the pause ring's centre starts a pause hold.
     * Inside [coreRadius] (the drawn ring and a little around it) it always does. In the rest
     * of the [hitRadius] circle it does only when the finger is not on a control's drawn
     * bounds ([overControl]), so a control the ring's touch area overlaps (the PlayStation
     * touchpad) keeps its touches there, and cannot open the menu by accident.
     */
    fun pauseRingTakes(distance: Float, hitRadius: Float, coreRadius: Float, overControl: Boolean): Boolean {
        if (distance <= coreRadius) return true
        return distance <= hitRadius && !overControl
    }

    /**
     * Whether a touchpad finger that lifted after [durationMs], having moved at most
     * [movedFraction] of the pad, was a tap (a touchpad click). A cancelled pointer never is.
     */
    fun touchpadTapIsClick(durationMs: Long, movedFraction: Float, canceled: Boolean): Boolean =
        !canceled && durationMs <= TOUCH_TAP_MS && movedFraction < TOUCH_TAP_TRAVEL

    /** How long the click of a tap lasting [durationMs] is held: the tap's own duration, at least 40 ms. */
    fun clickHoldMs(durationMs: Long): Long = if (durationMs > TOUCH_CLICK_MIN_MS) durationMs else TOUCH_CLICK_MIN_MS

    /** True for an ACTION_UP or ACTION_POINTER_UP whose [flags] say the pointer was cancelled. */
    fun isCanceled(flags: Int): Boolean = flags and FLAG_CANCELED != 0
}
