package com.slipstream.wheel.input

import kotlin.math.PI
import kotlin.math.abs

/**
 * One Euro filter (Casiez, Roussel, Vogel, CHI 2012): a first-order low pass whose cutoff
 * rises with the speed of the signal. Still hands get heavy smoothing, fast turns get almost
 * none, so jitter goes away without adding lag where it matters.
 *
 * @param minCutoff cutoff in Hz when the signal is still
 * @param beta how much the cutoff rises per unit of speed (units per second)
 * @param dCutoff cutoff in Hz of the speed estimate
 */
class OneEuroFilter(
    var minCutoff: Double,
    var beta: Double,
    var dCutoff: Double = 1.0,
) {
    private var initialized = false
    private var xPrev = 0.0
    private var dxPrev = 0.0
    private var tPrevNs = 0L

    fun reset() {
        initialized = false
    }

    /** Filters sample [x] taken at [tNs] (any monotonic clock, nanoseconds). */
    fun filter(x: Double, tNs: Long): Double {
        if (!initialized) {
            initialized = true
            xPrev = x
            dxPrev = 0.0
            tPrevNs = tNs
            return x
        }
        var dt = (tNs - tPrevNs) / 1e9
        if (dt <= 0.0) dt = 1e-4 // identical timestamps: treat as a very short step
        tPrevNs = tNs
        val dx = (x - xPrev) / dt
        dxPrev += alpha(dCutoff, dt) * (dx - dxPrev)
        val cutoff = minCutoff + beta * abs(dxPrev)
        xPrev += alpha(cutoff, dt) * (x - xPrev)
        return xPrev
    }

    private fun alpha(cutoff: Double, dt: Double): Double {
        val tau = 1.0 / (2.0 * PI * cutoff)
        return 1.0 / (1.0 + tau / dt)
    }
}
