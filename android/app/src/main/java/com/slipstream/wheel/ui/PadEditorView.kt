package com.slipstream.wheel.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import com.slipstream.wheel.R
import com.slipstream.wheel.pad.layout.Box
import com.slipstream.wheel.pad.layout.ControlKind
import com.slipstream.wheel.pad.layout.PadControl
import com.slipstream.wheel.pad.layout.PadGeometry
import com.slipstream.wheel.pad.layout.PadLayout
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * The layout editor surface (ARCHITECTURE.md 7.1). Same drawing area and renderer as the
 * play surface, so what is placed here is where it plays. Drag a control to move it; pinch
 * with a second finger, or drag one of the selected control's corner handles, to resize.
 * With the grid on, edges and sizes snap to 4 dp. Controls are kept out of the 12 dp safe
 * margin along the long edges and clear of the display cutout; overlapping controls are
 * allowed and drawn with a warning outline.
 *
 * A layout made on another screen size (its reference area is not this drawing area) is
 * shown fitted to this area (PadGeometry.fitToArea): the play screen's uniform shrink goes
 * into the sizes, so the editor shows what plays here and saving does not undo the shrink.
 * The fitted copy is always derived from the layout as given ([source]), never from an
 * earlier fitted copy, so a drawing area that is briefly smaller (system bars still showing
 * while the screen opens) cannot shrink the controls for good.
 */
@SuppressLint("ViewConstructor")
class PadEditorView(context: Context) : View(context) {

    interface Listener {
        fun onSelectionChanged(index: Int)
        fun onEdited()
    }

    var listener: Listener? = null

    private val renderer = PadRenderer(context)
    private val density = renderer.density

    /** The layout being edited, fitted to this drawing area (what is shown and saved). */
    var layout: PadLayout? = null
        private set

    /** The layout as given or as last edited, in its own reference area; [layout] derives from it. */
    private var source: PadLayout? = null

    var snap = true
        set(v) {
            field = v
            invalidate()
        }

    var selected = -1
        private set

    private var slots: Array<ControlSlot> = emptyArray()
    private var overlapping = BooleanArray(0)
    private val area = RectF()
    private var insetL = 0
    private var insetT = 0
    private var insetR = 0
    private var insetB = 0
    private val cutoutsPx = ArrayList<Box>(2)
    private val cutoutsDp = ArrayList<Box>(2)

    // Gesture state.
    private var mode = MODE_NONE
    private var primaryId = -1
    private var grabDx = 0f
    private var grabDy = 0f
    private var fixedX = 0f
    private var fixedY = 0f
    private var corner = -1
    private var span0 = 0f
    private var w0 = 0f
    private var h0 = 0f
    private var start: PadControl? = null

    private val cOutline = Ui.color(context, R.color.accent)
    private val selectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = cOutline
    }
    private val warnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = renderer.cWarn
        pathEffect = DashPathEffect(floatArrayOf(6f * density, 4f * density), 0f)
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = cOutline
    }
    private val gridPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = PadRenderer.withAlpha(Ui.color(context, R.color.stroke), 0x80)
    }
    private val bandPaint = Paint().apply {
        style = Paint.Style.FILL
        color = PadRenderer.withAlpha(Ui.color(context, R.color.stroke), 0x70)
    }
    private val cutoutPaint = Paint().apply {
        style = Paint.Style.FILL
        color = PadRenderer.withAlpha(renderer.cWarn, 0x55)
    }
    private val scratch = RectF()

    fun setLayout(l: PadLayout) {
        source = l
        layout = l
        if (selected >= l.controls.size) selected = -1
        rebuild()
    }

    fun select(index: Int) {
        val l = layout ?: return
        val next = if (index in l.controls.indices) index else -1
        if (next == selected) return
        selected = next
        invalidate()
        listener?.onSelectionChanged(next)
    }

    val selectedControl: PadControl? get() = layout?.controls?.getOrNull(selected)

    /** Replaces the selected control (an option changed in the panel). */
    fun updateSelected(transform: (PadControl) -> PadControl) {
        val c = selectedControl ?: return
        replace(selected, transform(c))
    }

    fun setInsets(l: Int, t: Int, r: Int, b: Int, cutoutRects: List<Rect>) {
        if (l == insetL && t == insetT && r == insetR && b == insetB && sameCutouts(cutoutsPx, cutoutRects)) return
        insetL = l; insetT = t; insetR = r; insetB = b
        cutoutsPx.clear()
        for (c in cutoutRects) cutoutsPx.add(Box(c.left.toFloat(), c.top.toFloat(), c.right.toFloat(), c.bottom.toFloat()))
        rebuild()
    }

    /** Drawing area in dp, the layout's reference size when it is saved. */
    val areaWidthDp: Float get() = area.width() / density
    val areaHeightDp: Float get() = area.height() / density

    /** The working layout made legal for this screen, with this screen as its reference. */
    fun legalLayout(): PadLayout? {
        val l = layout ?: return null
        if (area.width() <= 0f) return l
        return PadGeometry.clampLayout(l, areaWidthDp, areaHeightDp, cutoutsDp)
            .copy(refWidthDp = areaWidthDp, refHeightDp = areaHeightDp)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = rebuild()

    private fun rebuild() {
        val src = source ?: return
        if (width <= 0 || height <= 0) return
        area.set(insetL.toFloat(), insetT.toFloat(), (width - insetR).toFloat(), (height - insetB).toFloat())
        val otherScreen = abs(src.refWidthDp - areaWidthDp) > REF_EPS_DP || abs(src.refHeightDp - areaHeightDp) > REF_EPS_DP
        // Not an edit: the same controls, at the size the play screen gives them here.
        val l = if (area.width() > 0f && area.height() > 0f && otherScreen) PadGeometry.fitToArea(src, areaWidthDp, areaHeightDp) else src
        layout = l
        cutoutsDp.clear()
        for (c in cutoutsPx) {
            cutoutsDp.add(Box((c.l - area.left) / density, (c.t - area.top) / density, (c.r - area.left) / density, (c.b - area.top) / density))
        }
        // The editor works at full size: what the player places is what the reference holds.
        val atReference = l.copy(refWidthDp = areaWidthDp, refHeightDp = areaHeightDp)
        slots = renderer.buildSlots(atReference, area, cutoutsPx)
        overlapping = PadGeometry.overlapping(l, areaWidthDp, areaHeightDp)
        invalidate()
    }

    private fun replace(index: Int, c: PadControl) {
        val l = layout ?: return
        val legal = PadGeometry.clampControl(c, areaWidthDp, areaHeightDp, cutoutsDp)
        if (legal == l.controls[index]) return
        val list = l.controls.toMutableList()
        list[index] = legal
        // An edit is made on the fitted copy, which then becomes what is being edited.
        val next = l.copy(controls = list)
        source = next
        layout = next
        rebuild()
        listener?.onEdited()
    }

    // ------------------------------------------------------------ touch

    private fun dpX(x: Float): Float = (x - area.left) / density
    private fun dpY(y: Float): Float = (y - area.top) / density

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val l = layout ?: return true
        if (area.width() <= 0f) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                primaryId = e.getPointerId(0)
                down(l, e.x, e.y)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                val c = selectedControl
                if (c != null && e.pointerCount == 2 && mode != MODE_CORNER) {
                    mode = MODE_PINCH
                    span0 = max(span(e), 1f)
                    w0 = c.widthDp
                    h0 = c.heightDp
                    start = c
                }
            }
            MotionEvent.ACTION_MOVE -> move(e)
            MotionEvent.ACTION_POINTER_UP -> {
                // Lift the other finger to drag again. The dragging finger lifting ends the
                // drag too; otherwise the control would jump to the finger still down.
                if (mode == MODE_PINCH || e.getPointerId(e.actionIndex) == primaryId) {
                    mode = MODE_NONE
                    start = null
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                mode = MODE_NONE
                start = null
            }
        }
        return true
    }

    private fun span(e: MotionEvent): Float = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))

    private fun down(l: PadLayout, x: Float, y: Float) {
        val hit = handleAt(x, y)
        if (hit >= 0) {
            val s = slots[selected]
            mode = MODE_CORNER
            corner = hit
            // The opposite corner stays where it is.
            fixedX = dpX(if (hit == 0 || hit == 3) s.box.right else s.box.left)
            fixedY = dpY(if (hit == 0 || hit == 1) s.box.bottom else s.box.top)
            start = l.controls[selected]
            return
        }
        val i = controlAt(x, y)
        select(i)
        if (i < 0) {
            mode = MODE_NONE
            return
        }
        val c = l.controls[i]
        mode = MODE_DRAG
        grabDx = dpX(x) - c.cx * areaWidthDp
        grabDy = dpY(y) - c.cy * areaHeightDp
        start = c
    }

    private fun move(e: MotionEvent) {
        val c = selectedControl ?: return
        val base = start ?: return
        val w = areaWidthDp
        val h = areaHeightDp
        when (mode) {
            MODE_DRAG -> {
                val idx = e.findPointerIndex(primaryId).coerceAtLeast(0)
                var left = dpX(e.getX(idx)) - grabDx - c.widthDp / 2f
                var top = dpY(e.getY(idx)) - grabDy - c.heightDp / 2f
                if (snap) {
                    left = PadGeometry.snap(left)
                    top = PadGeometry.snap(top)
                }
                replace(selected, c.copy(cx = (left + c.widthDp / 2f) / w, cy = (top + c.heightDp / 2f) / h))
            }
            MODE_CORNER -> {
                val idx = e.findPointerIndex(primaryId).coerceAtLeast(0)
                val fx = dpX(e.getX(idx))
                val fy = dpY(e.getY(idx))
                var nw = abs(fx - fixedX)
                var nh = abs(fy - fixedY)
                if (base.keepsAspect) {
                    nw = max(nw, nh)
                    nh = nw
                }
                nw = size(nw)
                nh = if (base.keepsAspect) nw else size(nh)
                val cx = if (fx >= fixedX) fixedX + nw / 2f else fixedX - nw / 2f
                val cy = if (fy >= fixedY) fixedY + nh / 2f else fixedY - nh / 2f
                replace(selected, resized(base, nw, nh).copy(cx = cx / w, cy = cy / h))
            }
            MODE_PINCH -> {
                if (e.pointerCount < 2) return
                val f = span(e) / span0
                val nw = size(w0 * f)
                val nh = if (base.keepsAspect) nw else size(h0 * f)
                replace(selected, resized(base, nw, nh).copy(cx = c.cx, cy = c.cy))
            }
        }
    }

    private fun size(v: Float): Float {
        val s = if (snap) PadGeometry.snap(v) else v
        return s.coerceIn(PadGeometry.MIN_EDIT_DP, PadGeometry.MAX_SIZE_DP)
    }

    /** New size; the face cluster's buttons scale with it. */
    private fun resized(c: PadControl, w: Float, h: Float): PadControl {
        val options = if (c.kind == ControlKind.FACE && c.widthDp > 0f) {
            c.options.copy(faceButtonDp = (c.options.faceButtonDp * w / c.widthDp).coerceAtLeast(16f))
        } else {
            c.options
        }
        return c.copy(widthDp = w, heightDp = h, options = options)
    }

    private fun controlAt(x: Float, y: Float): Int {
        var i = slots.size - 1
        while (i >= 0) {
            if (slots[i].box.contains(x, y)) return i
            i--
        }
        i = slots.size - 1
        while (i >= 0) {
            if (slots[i].hit.contains(x, y)) return i
            i--
        }
        return -1
    }

    /** Corner handle of the selected control under the finger: 0 TL, 1 TR, 2 BR, 3 BL, or -1. */
    private fun handleAt(x: Float, y: Float): Int {
        val s = slots.getOrNull(selected) ?: return -1
        val r = HANDLE_HIT_DP / 2f * density
        for (k in 0 until 4) {
            val hx = if (k == 0 || k == 3) s.box.left else s.box.right
            val hy = if (k == 0 || k == 1) s.box.top else s.box.bottom
            if (abs(x - hx) <= r && abs(y - hy) <= r) return k
        }
        return -1
    }

    // ------------------------------------------------------------ drawing

    override fun onDraw(c: Canvas) {
        c.drawColor(renderer.cBg)
        if (snap) {
            val step = GRID_LINE_DP * density
            var x = area.left
            while (x <= area.right) {
                c.drawLine(x, area.top, x, area.bottom, gridPaint)
                x += step
            }
            var y = area.top
            while (y <= area.bottom) {
                c.drawLine(area.left, y, area.right, y, gridPaint)
                y += step
            }
        }
        // Safe margin along the long edges, and the cutout.
        val m = PadGeometry.SAFE_MARGIN_DP * density
        if (area.width() >= area.height()) {
            c.drawRect(area.left, area.top, area.right, area.top + m, bandPaint)
            c.drawRect(area.left, area.bottom - m, area.right, area.bottom, bandPaint)
        } else {
            c.drawRect(area.left, area.top, area.left + m, area.bottom, bandPaint)
            c.drawRect(area.right - m, area.top, area.right, area.bottom, bandPaint)
        }
        for (k in cutoutsPx.indices) {
            val b = cutoutsPx[k]
            c.drawRect(b.l, b.t, b.r, b.b, cutoutPaint)
        }
        for (i in slots.indices) {
            renderer.draw(c, slots[i], 0)
            if (i < overlapping.size && overlapping[i]) {
                scratch.set(slots[i].box)
                scratch.inset(-2f * density, -2f * density)
                c.drawRect(scratch, warnPaint)
            }
        }
        val s = slots.getOrNull(selected) ?: return
        scratch.set(s.box)
        scratch.inset(-3f * density, -3f * density)
        c.drawRect(scratch, selectPaint)
        val hs = HANDLE_DP / 2f * density
        for (k in 0 until 4) {
            val hx = if (k == 0 || k == 3) s.box.left else s.box.right
            val hy = if (k == 0 || k == 1) s.box.top else s.box.bottom
            c.drawRect(hx - hs, hy - hs, hx + hs, hy + hs, handlePaint)
        }
    }

    private companion object {
        const val MODE_NONE = 0
        const val MODE_DRAG = 1
        const val MODE_CORNER = 2
        const val MODE_PINCH = 3
        const val HANDLE_DP = 12f
        const val HANDLE_HIT_DP = 44f
        const val GRID_LINE_DP = 16f

        /** A reference this close to the drawing area is this screen. */
        const val REF_EPS_DP = 0.5f
    }
}
