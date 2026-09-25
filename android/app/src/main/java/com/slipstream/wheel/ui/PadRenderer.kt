package com.slipstream.wheel.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.CornerPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.TypedValue
import com.slipstream.wheel.R
import com.slipstream.wheel.pad.PadButton
import com.slipstream.wheel.pad.PadStyle
import com.slipstream.wheel.pad.TriggerMath
import com.slipstream.wheel.pad.layout.Box
import com.slipstream.wheel.pad.layout.ButtonShape
import com.slipstream.wheel.pad.layout.ControlKind
import com.slipstream.wheel.pad.layout.DefaultLayouts
import com.slipstream.wheel.pad.layout.PadControl
import com.slipstream.wheel.pad.layout.PadGeometry
import com.slipstream.wheel.pad.layout.PadLayout
import kotlin.math.max
import kotlin.math.min

/** True when [boxes] hold exactly [rects] (display cutouts), in order. */
internal fun sameCutouts(boxes: List<Box>, rects: List<android.graphics.Rect>): Boolean {
    if (boxes.size != rects.size) return false
    for (i in boxes.indices) {
        val b = boxes[i]
        val r = rects[i]
        if (b.l != r.left.toFloat() || b.t != r.top.toFloat() || b.r != r.right.toFloat() || b.b != r.bottom.toFloat()) return false
    }
    return true
}

/**
 * One control resolved to pixels for one screen. Built when the layout or the view size
 * changes, never on the touch or draw path. The play surface keeps the live drawing state
 * (knob, trigger fill, touchpad fingers) in it.
 */
class ControlSlot(val control: PadControl, val style: PadStyle) {
    val kind: ControlKind = control.kind
    val shape: ButtonShape = control.options.shape

    /** Drawn bounds, and the touch bounds (the drawn bounds grown to at least 44 dp). */
    val box = RectF()
    val hit = RectF()

    /** BUTTON: its canonical bit. STICK: L3 or R3. TOUCHPAD: the touchpad click. */
    var bit = -1
    var label = ""
    var icon = ICON_NONE
    var textSize = 0f
    var corner = 0f

    /** Idle opacity as a paint alpha; pressed controls draw fully opaque. */
    var alpha = 255

    // Stick
    var radius = 0f
    var originX = 0f
    var originY = 0f
    var knobX = 0f
    var knobY = 0f
    var touched = false

    // Trigger: the axis that presses it, pointing toward the screen centre.
    var horizontal = true
    var sign = 1f
    var travel = 1f
    var value = 0f

    // Face cluster: top, right, bottom, left.
    val faceX = FloatArray(4)
    val faceY = FloatArray(4)
    var faceR = 0f
    var faceHitR = 0f
    val triangle = Path()

    // D-pad
    var arm = 0f
    val cross = Path()
    val arrows = Array(4) { Path() } // up, down, left, right

    // Touchpad fingers, in pixels.
    val fingerX = FloatArray(2)
    val fingerY = FloatArray(2)
    val fingerOn = BooleanArray(2)

    fun resetLive() {
        originX = box.centerX()
        originY = box.centerY()
        knobX = 0f
        knobY = 0f
        touched = false
        value = 0f
        fingerOn[0] = false
        fingerOn[1] = false
    }

    companion object {
        const val ICON_NONE = 0
        const val ICON_VIEW = 1
        const val ICON_MENU = 2
        const val ICON_GUIDE = 3
        const val ICON_SHARE = 4
    }
}

/**
 * Draws pad controls for the play surface and the editor with one look: dark tinted
 * neutrals, the one accent for pressed state, the face glyph colours of ARCHITECTURE.md 7.2
 * on neutral buttons. Paints, paths and label strings are made once; [draw] allocates nothing.
 */
class PadRenderer(context: Context) {
    val density: Float = context.resources.displayMetrics.density
    private val sp = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 1f, context.resources.displayMetrics)

    val cBg = Ui.color(context, R.color.bg)
    private val cSurface = Ui.color(context, R.color.surface)
    private val cRaised = Ui.color(context, R.color.surface_raised)
    private val cStroke = Ui.color(context, R.color.stroke)
    private val cStrokeStrong = Ui.color(context, R.color.stroke_strong)
    private val cText = Ui.color(context, R.color.text)
    private val cMuted = Ui.color(context, R.color.text_muted)
    private val cFaint = Ui.color(context, R.color.text_faint)
    val cAccent = Ui.color(context, R.color.accent)
    private val cAccentSoft = Ui.color(context, R.color.accent_soft)
    private val cOnAccent = Ui.color(context, R.color.on_accent)
    val cWarn = Ui.color(context, R.color.warn)

    /** Glyph colours by face position (top, right, bottom, left). */
    private val psGlyph = intArrayOf(
        Ui.color(context, R.color.face_ps_triangle),
        Ui.color(context, R.color.face_ps_circle),
        Ui.color(context, R.color.face_ps_cross),
        Ui.color(context, R.color.face_ps_square),
    )
    private val xboxGlyph = intArrayOf(
        Ui.color(context, R.color.face_xbox_y),
        Ui.color(context, R.color.face_xbox_b),
        Ui.color(context, R.color.face_xbox_a),
        Ui.color(context, R.color.face_xbox_x),
    )
    private val xboxLetters = arrayOf("Y", "B", "A", "X")

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val crossFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        pathEffect = CornerPathEffect(7f * density)
    }
    private val crossLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
        pathEffect = CornerPathEffect(7f * density)
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Ui.MEDIUM
        letterSpacing = 0.06f
    }
    private val scratch = RectF()

    // ------------------------------------------------------------ layout

    /**
     * Places [layout] in [area] (pixels): centres scale with the area, sizes are dp shrunk
     * uniformly only when needed (PadGeometry.fitScale), then every control is kept out of
     * the safe margin and clear of the display [cutouts] (pixels, view coordinates).
     */
    fun buildSlots(layout: PadLayout, area: RectF, cutouts: List<Box>): Array<ControlSlot> {
        val w = area.width()
        val h = area.height()
        if (w <= 0f || h <= 0f) return emptyArray()
        val scale = PadGeometry.fitScale(layout, w / density, h / density)
        val safe = PadGeometry.safeRegion(w, h, PadGeometry.SAFE_MARGIN_DP * density, Box())
        val cuts = cutouts.map { Box(it.l - area.left, it.t - area.top, it.r - area.left, it.b - area.top) }
        val b = Box()
        return Array(layout.controls.size) { i ->
            val c = layout.controls[i]
            val s = ControlSlot(c, layout.style)
            PadGeometry.rect(c, w, h, scale, density, b)
            val nominalW = b.width
            PadGeometry.clampBox(b, safe, cuts, c.keepsAspect)
            s.box.set(b.l + area.left, b.t + area.top, b.r + area.left, b.b + area.top)
            val minHit = TOUCH_MIN_DP * density
            val growX = max(0f, (minHit - s.box.width()) / 2f)
            val growY = max(0f, (minHit - s.box.height()) / 2f)
            s.hit.set(s.box.left - growX, s.box.top - growY, s.box.right + growX, s.box.bottom + growY)
            s.alpha = (c.opacity.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
            prepare(s, scale * (if (nominalW > 0f) s.box.width() / nominalW else 1f), area)
            s.resetLive()
            s
        }
    }

    private fun prepare(s: ControlSlot, scale: Float, area: RectF) {
        val box = s.box
        val c = s.control
        val ps = s.style == PadStyle.PLAYSTATION
        when (s.kind) {
            ControlKind.BUTTON -> {
                s.bit = c.binding
                s.corner = when (s.shape) {
                    ButtonShape.PILL -> box.height() / 2f
                    ButtonShape.ROUND -> min(box.width(), box.height()) / 2f
                    ButtonShape.RECT -> min(12f * density, box.height() / 2f)
                }
                s.icon = when {
                    ps -> ControlSlot.ICON_NONE
                    c.binding == PadButton.CREATE -> ControlSlot.ICON_VIEW
                    c.binding == PadButton.OPTIONS -> ControlSlot.ICON_MENU
                    c.binding == PadButton.HOME -> ControlSlot.ICON_GUIDE
                    c.binding == PadButton.SHARE -> ControlSlot.ICON_SHARE
                    else -> ControlSlot.ICON_NONE
                }
                s.label = buttonLabel(c.binding, s.style)
                fitLabel(s, min(13f * sp, box.height() * 0.42f), box.width() * 0.82f)
            }
            ControlKind.TRIGGER -> {
                s.horizontal = box.width() >= box.height()
                s.sign = if (s.horizontal) (if (box.centerX() <= area.centerX()) 1f else -1f) else 1f
                val length = if (s.horizontal) box.width() else box.height()
                s.travel = TriggerMath.travelPx(length, TriggerMath.DEFAULT_TRAVEL)
                s.corner = min(14f * density, min(box.width(), box.height()) / 2f)
                s.label = if (ps) (if (c.binding == 0) "L2" else "R2") else (if (c.binding == 0) "LT" else "RT")
                fitLabel(s, min(14f * sp, min(box.width(), box.height()) * 0.4f), box.width() * 0.5f)
            }
            ControlKind.STICK -> {
                s.bit = c.stickClickBit
                s.radius = box.width() / 2f
            }
            ControlKind.DPAD -> {
                val size = min(box.width(), box.height())
                val half = size / 2f
                val a = size / 3f
                s.arm = a
                val cx = box.centerX()
                val cy = box.centerY()
                val h = a / 2f
                s.cross.reset()
                s.cross.moveTo(cx - h, cy - half)
                s.cross.lineTo(cx + h, cy - half)
                s.cross.lineTo(cx + h, cy - h)
                s.cross.lineTo(cx + half, cy - h)
                s.cross.lineTo(cx + half, cy + h)
                s.cross.lineTo(cx + h, cy + h)
                s.cross.lineTo(cx + h, cy + half)
                s.cross.lineTo(cx - h, cy + half)
                s.cross.lineTo(cx - h, cy + h)
                s.cross.lineTo(cx - half, cy + h)
                s.cross.lineTo(cx - half, cy - h)
                s.cross.lineTo(cx - h, cy - h)
                s.cross.close()
                val tip = half - a * 0.28f
                val base = half - a * 0.62f
                val wing = a * 0.2f
                arrow(s.arrows[0], cx, cy - tip, cx - wing, cy - base, cx + wing, cy - base)
                arrow(s.arrows[1], cx, cy + tip, cx - wing, cy + base, cx + wing, cy + base)
                arrow(s.arrows[2], cx - tip, cy, cx - base, cy - wing, cx - base, cy + wing)
                arrow(s.arrows[3], cx + tip, cy, cx + base, cy - wing, cx + base, cy + wing)
            }
            ControlKind.FACE -> {
                val size = min(box.width(), box.height())
                val d = min(c.options.faceButtonDp * scale * density, size * 0.48f)
                s.faceR = d / 2f
                val off = (size - d) / 2f
                val cx = box.centerX()
                val cy = box.centerY()
                s.faceX[0] = cx; s.faceY[0] = cy - off
                s.faceX[1] = cx + off; s.faceY[1] = cy
                s.faceX[2] = cx; s.faceY[2] = cy + off
                s.faceX[3] = cx - off; s.faceY[3] = cy
                // Hit circles a little larger than the buttons, never overlapping each other.
                s.faceHitR = max(s.faceR, min(s.faceR * 1.3f, off * 0.70f))
                val r = s.faceR * 0.46f
                val tx = s.faceX[0]
                val ty = s.faceY[0] + r * 0.12f
                s.triangle.reset()
                s.triangle.moveTo(tx, ty - r)
                s.triangle.lineTo(tx + r * 0.95f, ty + r * 0.62f)
                s.triangle.lineTo(tx - r * 0.95f, ty + r * 0.62f)
                s.triangle.close()
                s.textSize = s.faceR * 0.95f
            }
            ControlKind.TOUCHPAD -> {
                s.bit = PadButton.TOUCHPAD
                s.corner = min(16f * density, min(box.width(), box.height()) / 4f)
            }
        }
    }

    private fun arrow(p: Path, x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float) {
        p.reset()
        p.moveTo(x0, y0)
        p.lineTo(x1, y1)
        p.lineTo(x2, y2)
        p.close()
    }

    private fun fitLabel(s: ControlSlot, size: Float, maxWidth: Float) {
        text.textSize = size
        val w = text.measureText(s.label)
        s.textSize = if (w > maxWidth && w > 0f) max(size * maxWidth / w, 7f * sp) else size
    }

    private fun buttonLabel(bit: Int, style: PadStyle): String = when (bit) {
        PadButton.L1 -> if (style == PadStyle.PLAYSTATION) "L1" else "LB"
        PadButton.R1 -> if (style == PadStyle.PLAYSTATION) "R1" else "RB"
        PadButton.HOME -> if (style == PadStyle.PLAYSTATION) "PS" else "XBOX"
        PadButton.L3 -> "L3"
        PadButton.R3 -> "R3"
        else -> (PadButton.name(bit, style) ?: PadButton.bothNames(bit)).uppercase()
    }

    // ------------------------------------------------------------ drawing

    /** Draws [s]; [held] is the canonical held mask (pressed state for buttons, face, D-pad). */
    fun draw(c: Canvas, s: ControlSlot, held: Int) {
        when (s.kind) {
            ControlKind.BUTTON -> drawButton(c, s, isHeld(held, s.bit))
            ControlKind.TRIGGER -> drawTrigger(c, s)
            ControlKind.STICK -> drawStick(c, s, isHeld(held, s.bit))
            ControlKind.DPAD -> drawDpad(c, s, held)
            ControlKind.FACE -> drawFace(c, s, held)
            ControlKind.TOUCHPAD -> drawTouchpad(c, s, isHeld(held, s.bit))
        }
    }

    private fun isHeld(held: Int, bit: Int): Boolean = bit >= 0 && (held ushr bit) and 1 != 0

    private fun drawButton(c: Canvas, s: ControlSlot, pressed: Boolean) {
        val a = if (pressed) 255 else s.alpha
        val box = s.box
        fill.color = withAlpha(if (pressed) cAccent else cRaised, a)
        val round = s.shape == ButtonShape.ROUND
        val r = min(box.width(), box.height()) / 2f
        if (round) c.drawCircle(box.centerX(), box.centerY(), r, fill) else c.drawRoundRect(box, s.corner, s.corner, fill)
        if (!pressed) {
            line.strokeWidth = density
            line.color = withAlpha(cStrokeStrong, a)
            if (round) c.drawCircle(box.centerX(), box.centerY(), r - density / 2f, line) else c.drawRoundRect(box, s.corner, s.corner, line)
        }
        val ink = withAlpha(if (pressed) cOnAccent else cText, a)
        if (s.icon != ControlSlot.ICON_NONE) {
            drawIcon(c, s.icon, box.centerX(), box.centerY(), min(box.width(), box.height()), ink)
        } else {
            text.color = ink
            text.textSize = s.textSize
            c.drawText(s.label, box.centerX(), box.centerY() + s.textSize * 0.36f, text)
        }
    }

    private fun drawIcon(c: Canvas, icon: Int, cx: Float, cy: Float, size: Float, ink: Int) {
        val u = size * 0.13f
        line.color = ink
        line.strokeWidth = max(1.5f * density, size * 0.055f)
        when (icon) {
            ControlSlot.ICON_VIEW -> { // two overlapping windows
                scratch.set(cx - u * 1.6f, cy - u * 1.1f, cx + u * 0.6f, cy + u * 0.7f)
                c.drawRoundRect(scratch, u * 0.3f, u * 0.3f, line)
                scratch.set(cx - u * 0.6f, cy - u * 0.2f, cx + u * 1.6f, cy + u * 1.4f)
                fill.color = ink
                c.drawRoundRect(scratch, u * 0.3f, u * 0.3f, fill)
            }
            ControlSlot.ICON_MENU -> { // three lines
                for (k in -1..1) c.drawLine(cx - u * 1.4f, cy + k * u, cx + u * 1.4f, cy + k * u, line)
            }
            ControlSlot.ICON_GUIDE -> { // ring with a cross
                c.drawCircle(cx, cy, u * 2.1f, line)
                c.drawLine(cx - u, cy - u, cx + u, cy + u, line)
                c.drawLine(cx + u, cy - u, cx - u, cy + u, line)
            }
            ControlSlot.ICON_SHARE -> { // arrow up out of a tray
                val k = u * 0.8f
                c.drawLine(cx, cy - k * 1.6f, cx, cy + k * 0.6f, line)
                c.drawLine(cx - k * 0.9f, cy - k * 0.7f, cx, cy - k * 1.6f, line)
                c.drawLine(cx + k * 0.9f, cy - k * 0.7f, cx, cy - k * 1.6f, line)
                c.drawLine(cx - k * 1.6f, cy + k * 1.5f, cx + k * 1.6f, cy + k * 1.5f, line)
            }
        }
    }

    private fun drawTrigger(c: Canvas, s: ControlSlot) {
        val a = if (s.touched) 255 else s.alpha
        val box = s.box
        fill.color = withAlpha(cSurface, a)
        c.drawRoundRect(box, s.corner, s.corner, fill)
        val v = s.value
        if (v > 0f) {
            c.save()
            if (s.horizontal) {
                if (s.sign > 0f) c.clipRect(box.left, box.top, box.left + box.width() * v, box.bottom)
                else c.clipRect(box.right - box.width() * v, box.top, box.right, box.bottom)
            } else {
                c.clipRect(box.left, box.bottom - box.height() * v, box.right, box.bottom)
            }
            fill.color = cAccentSoft
            c.drawRoundRect(box, s.corner, s.corner, fill)
            c.restore()
        }
        line.strokeWidth = density
        line.color = withAlpha(if (s.touched) cAccent else cStroke, a)
        c.drawRoundRect(box, s.corner, s.corner, line)
        text.textSize = s.textSize
        text.color = withAlpha(if (s.touched) cText else cMuted, a)
        val tx = if (!s.horizontal) box.centerX() else if (s.sign > 0f) box.left + box.width() * 0.2f else box.right - box.width() * 0.2f
        c.drawText(s.label, tx, box.centerY() + s.textSize * 0.36f, text)
    }

    private fun drawStick(c: Canvas, s: ControlSlot, clicked: Boolean) {
        val a = if (s.touched) 255 else s.alpha
        val bx = if (s.touched) s.originX else s.box.centerX()
        val by = if (s.touched) s.originY else s.box.centerY()
        fill.color = withAlpha(cSurface, a)
        c.drawCircle(bx, by, s.radius, fill)
        line.strokeWidth = density
        line.color = withAlpha(cStroke, a)
        c.drawCircle(bx, by, s.radius - density / 2f, line)
        val kr = s.radius * 0.44f
        val kx = bx + s.knobX
        val ky = by + s.knobY
        fill.color = withAlpha(if (clicked) cAccent else cRaised, a)
        c.drawCircle(kx, ky, kr, fill)
        line.strokeWidth = 1.5f * density
        line.color = withAlpha(if (s.touched && !clicked) cAccent else cStrokeStrong, a)
        c.drawCircle(kx, ky, kr - line.strokeWidth / 2f, line)
    }

    private fun drawDpad(c: Canvas, s: ControlSlot, held: Int) {
        val any = held and PadButton.DPAD_MASK != 0
        val a = if (any) 255 else s.alpha
        crossFill.color = withAlpha(cSurface, a)
        c.drawPath(s.cross, crossFill)
        val cx = s.box.centerX()
        val cy = s.box.centerY()
        val h = s.arm / 2f
        val half = s.arm * 1.5f
        for (k in 0 until 4) {
            val bit = PadButton.UP + k
            val on = (held ushr bit) and 1 != 0
            if (on) {
                when (k) {
                    0 -> scratch.set(cx - h, cy - half, cx + h, cy - h)
                    1 -> scratch.set(cx - h, cy + h, cx + h, cy + half)
                    2 -> scratch.set(cx - half, cy - h, cx - h, cy + h)
                    else -> scratch.set(cx + h, cy - h, cx + half, cy + h)
                }
                fill.color = cAccentSoft
                c.drawRoundRect(scratch, 6f * density, 6f * density, fill)
            }
            fill.color = withAlpha(if (on) cAccent else cMuted, a)
            c.drawPath(s.arrows[k], fill)
        }
        crossLine.color = withAlpha(if (any) cAccent else cStrokeStrong, a)
        c.drawPath(s.cross, crossLine)
    }

    private fun drawFace(c: Canvas, s: ControlSlot, held: Int) {
        val ps = s.style == PadStyle.PLAYSTATION
        val colours = if (ps) psGlyph else xboxGlyph
        val r = s.faceR
        for (k in 0 until 4) {
            val on = (held ushr DefaultLayouts.FACE_BITS[k]) and 1 != 0
            val a = if (on) 255 else s.alpha
            val glyph = colours[k]
            val x = s.faceX[k]
            val y = s.faceY[k]
            fill.color = if (on) withAlpha(glyph, 0x4D) else withAlpha(cRaised, a)
            c.drawCircle(x, y, r, fill)
            line.strokeWidth = if (on) 2f * density else density
            line.color = withAlpha(if (on) glyph else cStrokeStrong, a)
            c.drawCircle(x, y, r - line.strokeWidth / 2f, line)
            val ink = withAlpha(glyph, a)
            if (ps) {
                line.color = ink
                line.strokeWidth = max(2f * density, r * 0.1f)
                val g = r * 0.4f
                when (k) {
                    0 -> c.drawPath(s.triangle, line)
                    1 -> c.drawCircle(x, y, g, line)
                    2 -> {
                        c.drawLine(x - g, y - g, x + g, y + g, line)
                        c.drawLine(x + g, y - g, x - g, y + g, line)
                    }
                    else -> {
                        val q = g * 0.85f
                        scratch.set(x - q, y - q, x + q, y + q)
                        c.drawRect(scratch, line)
                    }
                }
            } else {
                text.color = ink
                text.textSize = s.textSize
                c.drawText(xboxLetters[k], x, y + s.textSize * 0.36f, text)
            }
        }
    }

    private fun drawTouchpad(c: Canvas, s: ControlSlot, clicked: Boolean) {
        val fingers = s.fingerOn[0] || s.fingerOn[1]
        val a = if (fingers || clicked) 255 else s.alpha
        fill.color = withAlpha(cSurface, a)
        c.drawRoundRect(s.box, s.corner, s.corner, fill)
        if (clicked) {
            fill.color = cAccentSoft
            c.drawRoundRect(s.box, s.corner, s.corner, fill)
        }
        line.strokeWidth = density
        line.color = withAlpha(if (fingers) cAccent else cStroke, a)
        c.drawRoundRect(s.box, s.corner, s.corner, line)
        fill.color = cAccent
        for (k in 0 until 2) if (s.fingerOn[k]) c.drawCircle(s.fingerX[k], s.fingerY[k], 7f * density, fill)
    }

    /** The faint pause ring (16 dp) and, while held, its accent fill sweeping clockwise. */
    fun drawPauseRing(c: Canvas, cx: Float, cy: Float, progress: Float) {
        val r = PAUSE_RING_DP / 2f * density
        line.strokeWidth = 1.5f * density
        line.color = withAlpha(cFaint, 0x99)
        c.drawCircle(cx, cy, r, line)
        if (progress > 0f) {
            line.strokeWidth = 3f * density
            line.color = cAccent
            scratch.set(cx - r, cy - r, cx + r, cy + r)
            c.drawArc(scratch, -90f, 360f * progress.coerceAtMost(1f), false, line)
        }
    }

    /** Link status chip centred at [cx], bottom edge at [bottom]. [bad] shows the warning dot. */
    fun drawChip(c: Canvas, cx: Float, bottom: Float, message: CharSequence, bad: Boolean) {
        text.textSize = 13f * sp
        val tw = text.measureText(message, 0, message.length)
        val h = 34f * density
        val dot = 4.5f * density
        val pad = 14f * density
        val w = tw + pad * 2f + dot * 2f + 8f * density
        scratch.set(cx - w / 2f, bottom - h, cx + w / 2f, bottom)
        fill.color = cRaised
        c.drawRoundRect(scratch, h / 2f, h / 2f, fill)
        line.strokeWidth = density
        line.color = if (bad) cWarn else cStrokeStrong
        c.drawRoundRect(scratch, h / 2f, h / 2f, line)
        fill.color = if (bad) cWarn else cAccent
        c.drawCircle(scratch.left + pad + dot, scratch.centerY(), dot, fill)
        text.color = cText
        val tx = scratch.left + pad + dot * 2f + 8f * density + tw / 2f
        c.drawText(message, 0, message.length, tx, scratch.centerY() + text.textSize * 0.36f, text)
    }

    companion object {
        const val TOUCH_MIN_DP = 44f
        const val PAUSE_RING_DP = 16f
        const val PAUSE_HIT_DP = 44f

        /**
         * The part of the pause ring's 44 dp touch area that wins over a control drawn under it
         * (the PlayStation touchpad reaches up into the ring's area): the drawn ring plus 4 dp.
         */
        const val PAUSE_CORE_DP = 24f

        /** [color] with its alpha multiplied by [alpha] (0..255). */
        fun withAlpha(color: Int, alpha: Int): Int {
            val a = ((color ushr 24) * alpha + 127) / 255
            return (color and 0x00FFFFFF) or (a shl 24)
        }
    }
}
