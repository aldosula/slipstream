package com.slipstream.wheel.pad.layout

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** An axis-aligned rectangle, mutable so hot paths can reuse it. Units are the caller's. */
class Box(var l: Float = 0f, var t: Float = 0f, var r: Float = 0f, var b: Float = 0f) {
    val width: Float get() = r - l
    val height: Float get() = b - t
    val cx: Float get() = (l + r) / 2f
    val cy: Float get() = (t + b) / 2f

    fun set(l: Float, t: Float, r: Float, b: Float): Box {
        this.l = l
        this.t = t
        this.r = r
        this.b = b
        return this
    }

    fun set(o: Box): Box = set(o.l, o.t, o.r, o.b)

    fun offset(dx: Float, dy: Float) {
        l += dx
        r += dx
        t += dy
        b += dy
    }

    /** Strict overlap: boxes that only touch do not intersect. */
    fun intersects(o: Box): Boolean = l < o.r && o.l < r && t < o.b && o.t < b

    fun contains(x: Float, y: Float): Boolean = x >= l && x < r && y >= t && y < b

    override fun toString(): String = "Box($l, $t, $r, $b)"
}

/**
 * Placement rules of ARCHITECTURE.md 7.1 and 7.2, pure so they can be unit tested:
 * - positions scale with the drawing area, sizes stay in dp;
 * - sizes shrink uniformly only if two controls that are apart on the layout's reference
 *   area would otherwise overlap;
 * - nothing inside the safe margin (12 dp from the long, curved edges) or over the display
 *   cutout;
 * - a 4 dp snap grid for the editor.
 */
object PadGeometry {
    const val SAFE_MARGIN_DP = 12f
    const val GRID_DP = 4f
    /**
     * Smallest size a stored layout may hold. Below [MIN_EDIT_DP] because a layout fitted to a
     * small screen ([fitToArea]) keeps the play screen's uniform shrink, down to [MIN_SCALE]
     * of a 25 dp pill. Touch bounds are grown to 44 dp whatever the drawn size.
     */
    const val MIN_SIZE_DP = 8f

    /** Smallest size the editor's handles and pinch make. */
    const val MIN_EDIT_DP = 24f
    const val MAX_SIZE_DP = 480f
    const val MIN_SCALE = 0.5f

    fun snap(v: Float, grid: Float = GRID_DP): Float = (v / grid).roundToInt() * grid

    /** Control rectangle in an area of [w] x [h] (any unit, with [unit] units per dp) at [scale]. */
    fun rect(c: PadControl, w: Float, h: Float, scale: Float, unit: Float, out: Box): Box {
        val hw = c.widthDp * scale * unit / 2f
        val hh = c.heightDp * scale * unit / 2f
        val x = c.cx * w
        val y = c.cy * h
        return out.set(x - hw, y - hh, x + hw, y + hh)
    }

    private fun overlapAt(a: PadControl, b: PadControl, w: Float, h: Float, scale: Float): Boolean {
        val dx = abs(a.cx - b.cx) * w
        val dy = abs(a.cy - b.cy) * h
        return dx < (a.widthDp + b.widthDp) * scale / 2f && dy < (a.heightDp + b.heightDp) * scale / 2f
    }

    /**
     * Uniform size factor for an area of [w] x [h] dp: 1, unless a pair of controls that is
     * apart on the layout's reference area would overlap here; then the largest factor that
     * keeps every such pair apart (never below [MIN_SCALE]). Overlaps the player made on
     * purpose (already overlapping on the reference area) do not shrink anything.
     */
    fun fitScale(layout: PadLayout, w: Float, h: Float): Float {
        var s = 1f
        val cs = layout.controls
        for (i in cs.indices) {
            for (j in i + 1 until cs.size) {
                val a = cs[i]
                val b = cs[j]
                if (overlapAt(a, b, layout.refWidthDp, layout.refHeightDp, 1f)) continue
                val sx = 2f * abs(a.cx - b.cx) * w / (a.widthDp + b.widthDp)
                val sy = 2f * abs(a.cy - b.cy) * h / (a.heightDp + b.heightDp)
                val limit = max(sx, sy)
                if (limit < s) s = limit
            }
        }
        return s.coerceIn(MIN_SCALE, 1f)
    }

    /**
     * [layout] as the play screen draws it on an area of [w] x [h] dp, with that area as its
     * new reference: the uniform shrink of [fitScale] is baked into the sizes (and the face
     * buttons), positions stay fractions. On the new reference [fitScale] is then 1, so an
     * editor that works at full size on this screen shows, and saves, exactly what plays here.
     */
    fun fitToArea(layout: PadLayout, w: Float, h: Float): PadLayout {
        if (w <= 0f || h <= 0f) return layout
        val s = fitScale(layout, w, h)
        // Float noise on a layout already fitted here is not a shrink: keep it exactly.
        val controls = if (s >= 1f - FIT_EPS) {
            layout.controls
        } else {
            layout.controls.map { c ->
                val options = if (c.kind == ControlKind.FACE) c.options.copy(faceButtonDp = c.options.faceButtonDp * s) else c.options
                c.copy(widthDp = c.widthDp * s, heightDp = c.heightDp * s, options = options)
            }
        }
        return layout.copy(controls = controls, refWidthDp = w, refHeightDp = h)
    }

    /** Where controls may be: [margin] off the long edges of a [w] x [h] area. */
    fun safeRegion(w: Float, h: Float, margin: Float, out: Box): Box =
        if (w >= h) out.set(0f, margin, w, h - margin) else out.set(margin, 0f, w - margin, h)

    /**
     * Moves [box] inside [safe] and clear of every cutout. Size is kept unless the box is
     * larger than the safe region; then it shrinks around its centre ([keepAspect] shrinks
     * both sides by the same factor). Returns false when no position clears every cutout.
     */
    fun clampBox(box: Box, safe: Box, cutouts: List<Box>, keepAspect: Boolean): Boolean {
        fit(box, safe, keepAspect)
        var clear = true
        for (k in cutouts.indices) {
            val cut = cutouts[k]
            if (!box.intersects(cut)) continue
            // The four ways out, smallest move that stays in the safe region wins.
            var bestDx = 0f
            var bestDy = 0f
            var best = Float.MAX_VALUE
            val moves = floatArrayOf(cut.l - box.r, 0f, cut.r - box.l, 0f, 0f, cut.t - box.b, 0f, cut.b - box.t)
            for (m in 0 until 4) {
                val dx = moves[2 * m]
                val dy = moves[2 * m + 1]
                val cost = abs(dx) + abs(dy)
                if (cost >= best) continue
                if (box.l + dx < safe.l - EPS || box.r + dx > safe.r + EPS) continue
                if (box.t + dy < safe.t - EPS || box.b + dy > safe.b + EPS) continue
                best = cost
                bestDx = dx
                bestDy = dy
            }
            if (best == Float.MAX_VALUE) {
                clear = false
                continue
            }
            box.offset(bestDx, bestDy)
        }
        return clear
    }

    private fun fit(box: Box, safe: Box, keepAspect: Boolean) {
        var w = box.width
        var h = box.height
        val fx = if (w > safe.width) safe.width / w else 1f
        val fy = if (h > safe.height) safe.height / h else 1f
        if (keepAspect) {
            val f = min(fx, fy)
            w *= f
            h *= f
        } else {
            w *= fx
            h *= fy
        }
        val cx = box.cx.coerceIn(safe.l + w / 2f, safe.r - w / 2f)
        val cy = box.cy.coerceIn(safe.t + h / 2f, safe.b - h / 2f)
        box.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
    }

    /**
     * [c] placed legally in an area of [w] x [h] dp with [cutouts] (dp, area coordinates):
     * the returned copy has its centre (and, only if it cannot fit, its size) adjusted.
     */
    fun clampControl(c: PadControl, w: Float, h: Float, cutouts: List<Box> = emptyList()): PadControl {
        val box = rect(c, w, h, 1f, 1f, Box())
        val before = Box().set(box)
        val safe = safeRegion(w, h, SAFE_MARGIN_DP, Box())
        clampBox(box, safe, cutouts, c.keepsAspect)
        if (same(before, box)) return c // already legal: keep the exact stored values
        val nw = box.width
        val nh = box.height
        val options = if (c.kind == ControlKind.FACE && nw < c.widthDp) {
            c.options.copy(faceButtonDp = c.options.faceButtonDp * nw / c.widthDp)
        } else {
            c.options
        }
        return c.copy(cx = box.cx / w, cy = box.cy / h, widthDp = nw, heightDp = nh, options = options)
    }

    /** Every control of [layout] clamped for an area of [w] x [h] dp. */
    fun clampLayout(layout: PadLayout, w: Float, h: Float, cutouts: List<Box> = emptyList()): PadLayout =
        layout.copy(controls = layout.controls.map { clampControl(it, w, h, cutouts) })

    /** Left-handed play: every control's position mirrored left to right, bindings unchanged. */
    fun mirror(layout: PadLayout): PadLayout =
        layout.copy(controls = layout.controls.map { it.copy(cx = 1f - it.cx) })

    /** Indices of controls that overlap at least one other control (the editor's warning outline). */
    fun overlapping(layout: PadLayout, w: Float, h: Float): BooleanArray {
        val cs = layout.controls
        val out = BooleanArray(cs.size)
        val a = Box()
        val b = Box()
        for (i in cs.indices) {
            rect(cs[i], w, h, 1f, 1f, a)
            for (j in i + 1 until cs.size) {
                rect(cs[j], w, h, 1f, 1f, b)
                if (a.intersects(b)) {
                    out[i] = true
                    out[j] = true
                }
            }
        }
        return out
    }

    private fun same(a: Box, b: Box): Boolean =
        abs(a.l - b.l) < EPS && abs(a.t - b.t) < EPS && abs(a.r - b.r) < EPS && abs(a.b - b.b) < EPS

    private const val EPS = 0.001f
    private const val FIT_EPS = 0.0001f
}
