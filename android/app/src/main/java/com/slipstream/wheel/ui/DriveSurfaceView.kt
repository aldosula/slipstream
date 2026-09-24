package com.slipstream.wheel.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.SystemClock
import android.util.TypedValue
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import com.slipstream.wheel.PedalMode
import com.slipstream.wheel.R
import com.slipstream.wheel.input.ControllerState
import com.slipstream.wheel.input.PedalMath
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The drive surface: two pedal zones (left brake, right gas, swappable), shift paddles,
 * handbrake, four held buttons, recenter, a steering arc gauge and the link status chip.
 *
 * Touch is tracked per pointer id; each pointer belongs to whatever it landed on until it
 * lifts. Every ACTION_DOWN and ACTION_POINTER_DOWN asks for unbuffered dispatch, so moves
 * arrive as they happen instead of once per frame. Nothing in onTouchEvent or onDraw
 * allocates: paints, rects and text buffers are made once.
 */
@SuppressLint("ViewConstructor")
class DriveSurfaceView(
    context: Context,
    private val state: ControllerState,
    private val cfg: Config,
) : View(context) {

    class Config(
        val pedalMode: PedalMode,
        val travelFraction: Float,
        val swapZones: Boolean,
        val throttleCurve: Float,
        val brakeCurve: Float,
        val lockDeg: Int,
        val labels: Array<String>,
    )

    interface Listener {
        fun onShift(up: Boolean)
        fun onRecenter()
    }

    var listener: Listener? = null

    // ------------------------------------------------------------ touch state
    private val pointerTarget = IntArray(MAX_POINTERS)
    private val pointerAnchor = FloatArray(MAX_POINTERS)
    private val pointerValue = FloatArray(MAX_POINTERS)
    private val pressCount = IntArray(T_COUNT)
    private var brakeShown = 0f
    private var gasShown = 0f
    private var flashUpUntil = 0L
    private var flashDownUntil = 0L

    // ------------------------------------------------------------ geometry
    private val density = resources.displayMetrics.density
    private val margin = 12f * density
    private val radius = 18f * density
    private val leftZone = RectF()
    private val rightZone = RectF()
    private val leftInner = RectF()
    private val rightInner = RectF()
    private val paddleDown = RectF()
    private val paddleUp = RectF()
    private val chip = RectF()
    private val gauge = RectF()
    private val recenter = RectF()
    private val held = Array(4) { RectF() }
    private val handbrake = RectF()
    private val scratch = RectF()
    private var zoneW = 0f
    private var travelPx = 1f
    private var safeL = 0
    private var safeT = 0
    private var safeR = 0
    private var safeB = 0
    private val exclusion = ArrayList<Rect>(2)

    // ------------------------------------------------------------ paints
    private val cBg = Ui.color(context, R.color.bg)
    private val cSurface = Ui.color(context, R.color.surface)
    private val cRaised = Ui.color(context, R.color.surface_raised)
    private val cStroke = Ui.color(context, R.color.stroke)
    private val cStrokeStrong = Ui.color(context, R.color.stroke_strong)
    private val cText = Ui.color(context, R.color.text)
    private val cMuted = Ui.color(context, R.color.text_muted)
    private val cFaint = Ui.color(context, R.color.text_faint)
    private val cAccent = Ui.color(context, R.color.accent)
    private val cAccentSoft = Ui.color(context, R.color.accent_soft)
    private val cOnAccent = Ui.color(context, R.color.on_accent)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = density }
    private val arcTrack = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 9f * density
        color = cStrokeStrong
    }
    private val arcFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 9f * density
        color = cAccent
    }
    private val bigNumber = textPaint(30f, cText, bold = true)
    private val label = textPaint(13f, cMuted, bold = true).apply { letterSpacing = 0.1f }
    private val buttonText = textPaint(15f, cText, bold = true)
    private val paddleText = textPaint(34f, cText, bold = true)
    private val paddleCaption = textPaint(11f, cMuted, bold = true).apply { letterSpacing = 0.1f }
    private val chipText = textPaint(13f, cText, bold = true)
    private val chipSub = textPaint(11.5f, cMuted, bold = false)
    private val chipTextSize = chipText.textSize
    private val chipSubSize = chipSub.textSize
    private val gaugeText = textPaint(22f, cText, bold = true)

    private fun textPaint(sp: Float, color: Int, bold: Boolean) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics)
        this.color = color
        typeface = if (bold) Ui.MEDIUM else Ui.REGULAR
        textAlign = Paint.Align.CENTER
        fontFeatureSettings = "tnum"
    }

    // ------------------------------------------------------------ text buffers
    private val numBuf = CharArray(8)
    private val statusLine1 = StringBuilder(64)
    private val statusLine2 = StringBuilder(64)
    private var statusLive = false
    private val leftIsBrake = !cfg.swapZones
    private val labelBrake = "BRAKE"
    private val labelGas = "GAS"
    private val labelDown = "DOWN"
    private val labelUp = "UP"
    private val labelCenter = "CENTER"
    private val labelHandbrake = "HANDBRAKE"
    private val symbolDown = "\u2212" // U+2212 minus sign
    private val symbolUp = "+"

    // ------------------------------------------------------------ gauge refresh
    private var ticking = false
    private var drawnSteer = Int.MIN_VALUE
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!ticking) return
            if (state.steerValue != drawnSteer) invalidate()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    /** Redraws the gauge on vsync only while the window is visible. */
    private fun setTicking(on: Boolean) {
        if (on == ticking) return
        ticking = on
        val ch = Choreographer.getInstance()
        if (on) ch.postFrameCallback(frameCallback) else ch.removeFrameCallback(frameCallback)
    }

    init {
        isHapticFeedbackEnabled = false
        isSoundEffectsEnabled = false
        pointerTarget.fill(T_NONE)
    }

    /** Status chip content. Called about ten times a second from the activity. */
    fun setStatus(line1: CharSequence, line2: CharSequence, live: Boolean) {
        statusLine1.setLength(0)
        statusLine1.append(line1)
        statusLine2.setLength(0)
        statusLine2.append(line2)
        statusLive = live
        fitText(chipText, chipTextSize, statusLine1)
        fitText(chipSub, chipSubSize, statusLine2)
        invalidate()
    }

    /** Shrinks a chip line until it fits beside the status dot (never below 70 %). */
    private fun fitText(p: Paint, baseSize: Float, text: CharSequence) {
        val avail = chip.width() - chip.height() - 12f * density
        if (avail <= 0f) return
        p.textSize = baseSize
        val w = p.measureText(text, 0, text.length)
        if (w > avail) p.textSize = (baseSize * avail / w).coerceAtLeast(baseSize * 0.7f)
    }

    /** Safe area from display cutouts and rounded corners; the zones still reach the edges. */
    fun setSafeInsets(l: Int, t: Int, r: Int, b: Int) {
        if (l == safeL && t == safeT && r == safeR && b == safeB) return
        safeL = l; safeT = t; safeR = r; safeB = b
        layoutRegions(width, height)
        invalidate()
    }

    /** Briefly lights a paddle, for shifts that came from the volume keys. */
    fun flashPaddle(up: Boolean) {
        val until = SystemClock.uptimeMillis() + FLASH_MS
        if (up) flashUpUntil = until else flashDownUntil = until
        invalidate()
        postInvalidateDelayed(FLASH_MS + 5)
    }

    /** Lifts every finger: pedals, handbrake and held buttons back to rest. */
    fun releaseAllPointers() {
        pointerTarget.fill(T_NONE)
        pointerValue.fill(0f)
        pressCount.fill(0)
        brakeShown = 0f
        gasShown = 0f
        state.releaseAll()
        invalidate()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        setTicking(visibility == VISIBLE)
    }

    override fun onDetachedFromWindow() {
        setTicking(false)
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        layoutRegions(w, h)
    }

    private fun layoutRegions(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val fw = w.toFloat()
        val fh = h.toFloat()
        val dp = density
        val colW = (fw * 0.30f).coerceIn(220f * dp, 380f * dp).coerceAtMost(fw * 0.44f)
        zoneW = (fw - colW) / 2f
        leftZone.set(0f, 0f, zoneW, fh)
        rightZone.set(fw - zoneW, 0f, fw, fh)
        val half = margin / 2f
        leftInner.set(leftZone.left + half + safeL * 0.5f, half, leftZone.right - half, fh - half)
        rightInner.set(rightZone.left + half, half, rightZone.right - half - safeR * 0.5f, fh - half)

        val paddleW = max(96f * dp, zoneW * 0.52f).coerceAtMost(zoneW - 2 * margin)
        val paddleH = max(72f * dp, fh * 0.22f)
        val top = safeT + margin
        paddleDown.set(safeL + margin, top, safeL + margin + paddleW, top + paddleH)
        paddleUp.set(fw - safeR - margin - paddleW, top, fw - safeR - margin, top + paddleH)

        val cx0 = zoneW + margin
        val cx1 = fw - zoneW - margin
        val gap = 8f * dp
        val chipH = 42f * dp
        val recenterH = 40f * dp
        val heldH = 52f * dp
        val hbH = 56f * dp
        chip.set(cx0, top, cx1, top + chipH)
        val bottom = fh - safeB - margin
        handbrake.set(cx0, bottom - hbH, cx1, bottom)
        val heldTop = handbrake.top - gap - heldH
        val cellW = (cx1 - cx0 - 3 * gap) / 4f
        for (i in 0 until 4) {
            val l = cx0 + i * (cellW + gap)
            held[i].set(l, heldTop, l + cellW, heldTop + heldH)
        }
        recenter.set(cx0 + (cx1 - cx0) * 0.2f, heldTop - gap - recenterH, cx1 - (cx1 - cx0) * 0.2f, heldTop - gap)
        val gaugeTop = chip.bottom + gap
        val gaugeBottom = recenter.top - gap
        val size = min(cx1 - cx0, (gaugeBottom - gaugeTop) * 1.25f).coerceAtLeast(40f * dp)
        val gcx = (cx0 + cx1) / 2f
        // The arc's open side faces down, so the square may overhang the bottom by a quarter.
        gauge.set(gcx - size / 2f, gaugeTop + arcTrack.strokeWidth, gcx + size / 2f, gaugeTop + arcTrack.strokeWidth + size)

        travelPx = PedalMath.travelPx(fh, cfg.travelFraction)

        // Keep the system back gesture off the lower edge of the pedal zones (API 29+).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val band = (200f * dp).toInt().coerceAtMost(h)
            exclusion.clear()
            exclusion.add(Rect(0, h - band, (24f * dp).toInt(), h))
            exclusion.add(Rect(w - (24f * dp).toInt(), h - band, w, h))
            systemGestureExclusionRects = exclusion
        }
    }

    // ------------------------------------------------------------ touch

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                requestUnbufferedDispatch(e)
                val i = e.actionIndex
                pointerDown(e.getPointerId(i), e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) pointerMove(e.getPointerId(i), e.getY(i))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = e.actionIndex
                pointerUp(e.getPointerId(i))
            }
            MotionEvent.ACTION_CANCEL -> releaseAllPointers()
        }
        return true
    }

    private fun pointerDown(id: Int, x: Float, y: Float) {
        if (id < 0 || id >= MAX_POINTERS) return
        if (pointerTarget[id] != T_NONE) pointerUp(id)
        val t = hitTest(x, y)
        if (t == T_NONE) return
        pointerTarget[id] = t
        pressCount[t]++
        when (t) {
            T_BRAKE, T_GAS -> {
                pointerAnchor[id] = y
                pointerValue[id] = if (cfg.pedalMode == PedalMode.ABSOLUTE) {
                    PedalMath.absoluteValue(y, 0f, height.toFloat())
                } else {
                    0f
                }
                updatePedal(t)
            }
            T_DOWN -> listener?.onShift(false)
            T_UP -> listener?.onShift(true)
            T_CENTER -> listener?.onRecenter()
            T_HANDBRAKE -> state.setHandbrake(65535)
            T_HELD0, T_HELD0 + 1, T_HELD0 + 2, T_HELD0 + 3 -> state.setButton(t - T_HELD0, true)
        }
        invalidate()
    }

    private fun pointerMove(id: Int, y: Float) {
        if (id < 0 || id >= MAX_POINTERS) return
        val t = pointerTarget[id]
        if (t != T_BRAKE && t != T_GAS) return
        val v = if (cfg.pedalMode == PedalMode.ABSOLUTE) {
            PedalMath.absoluteValue(y, 0f, height.toFloat())
        } else {
            val anchor = PedalMath.swipeAnchor(pointerAnchor[id], y, travelPx)
            pointerAnchor[id] = anchor
            PedalMath.swipeValue(anchor, y, travelPx)
        }
        if (v != pointerValue[id]) {
            pointerValue[id] = v
            updatePedal(t)
            invalidate()
        }
    }

    private fun pointerUp(id: Int) {
        if (id < 0 || id >= MAX_POINTERS) return
        val t = pointerTarget[id]
        if (t == T_NONE) return
        pointerTarget[id] = T_NONE
        pointerValue[id] = 0f
        pressCount[t] = (pressCount[t] - 1).coerceAtLeast(0)
        when (t) {
            T_BRAKE, T_GAS -> updatePedal(t)
            T_HANDBRAKE -> if (pressCount[t] == 0) state.setHandbrake(0)
            T_HELD0, T_HELD0 + 1, T_HELD0 + 2, T_HELD0 + 3 ->
                if (pressCount[t] == 0) state.setButton(t - T_HELD0, false)
        }
        invalidate()
    }

    /** The pedal is the deepest of the fingers on it. */
    private fun updatePedal(t: Int) {
        var raw = 0f
        for (i in 0 until MAX_POINTERS) if (pointerTarget[i] == t && pointerValue[i] > raw) raw = pointerValue[i]
        if (t == T_BRAKE) {
            val out = PedalMath.output(raw, cfg.brakeCurve)
            brakeShown = out / 65535f
            state.setBrake(out)
        } else {
            val out = PedalMath.output(raw, cfg.throttleCurve)
            gasShown = out / 65535f
            state.setThrottle(out)
        }
    }

    private fun hitTest(x: Float, y: Float): Int {
        if (paddleDown.contains(x, y)) return T_DOWN
        if (paddleUp.contains(x, y)) return T_UP
        if (x < zoneW) return if (leftIsBrake) T_BRAKE else T_GAS
        if (x >= width - zoneW) return if (leftIsBrake) T_GAS else T_BRAKE
        if (handbrake.contains(x, y)) return T_HANDBRAKE
        for (i in 0 until 4) if (held[i].contains(x, y)) return T_HELD0 + i
        if (recenter.contains(x, y)) return T_CENTER
        return T_NONE
    }

    // ------------------------------------------------------------ drawing

    override fun onDraw(c: Canvas) {
        c.drawColor(cBg)
        val now = SystemClock.uptimeMillis()
        val brakeTarget = T_BRAKE
        val gasTarget = T_GAS
        drawZone(
            c, leftInner,
            if (leftIsBrake) brakeShown else gasShown,
            if (leftIsBrake) labelBrake else labelGas,
            pressCount[if (leftIsBrake) brakeTarget else gasTarget] > 0,
            meterOnRight = true,
        )
        drawZone(
            c, rightInner,
            if (leftIsBrake) gasShown else brakeShown,
            if (leftIsBrake) labelGas else labelBrake,
            pressCount[if (leftIsBrake) gasTarget else brakeTarget] > 0,
            meterOnRight = false,
        )
        drawPaddle(c, paddleDown, symbolDown, labelDown, pressCount[T_DOWN] > 0 || now < flashDownUntil)
        drawPaddle(c, paddleUp, symbolUp, labelUp, pressCount[T_UP] > 0 || now < flashUpUntil)
        drawChip(c)
        drawGauge(c)
        drawButton(c, recenter, labelCenter, pressCount[T_CENTER] > 0, pill = true)
        val heldBits = state.heldButtons
        for (i in 0 until 4) drawButton(c, held[i], cfg.labels[i], (heldBits and (1 shl i)) != 0, pill = false)
        drawButton(c, handbrake, labelHandbrake, state.handbrake > 0, pill = false)
    }

    private fun drawZone(c: Canvas, r: RectF, value: Float, name: String, active: Boolean, meterOnRight: Boolean) {
        fill.color = cSurface
        c.drawRoundRect(r, radius, radius, fill)
        if (value > 0f) {
            // Level wash from the bottom, clipped to the rounded zone.
            c.save()
            c.clipRect(r.left, r.bottom - r.height() * value, r.right, r.bottom)
            fill.color = cAccentSoft
            c.drawRoundRect(r, radius, radius, fill)
            c.restore()
        }
        outline.color = if (active) cAccent else cStroke
        c.drawRoundRect(r, radius, radius, outline)

        // Vertical fill meter along the inner edge.
        val mw = 8f * density
        val mx = if (meterOnRight) r.right - margin - mw else r.left + margin
        val mTop = paddleDown.bottom + margin
        val mBottom = r.bottom - margin
        scratch.set(mx, mTop, mx + mw, mBottom)
        fill.color = cRaised
        c.drawRoundRect(scratch, mw / 2f, mw / 2f, fill)
        if (value > 0f) {
            scratch.top = mBottom - (mBottom - mTop) * value
            fill.color = cAccent
            c.drawRoundRect(scratch, mw / 2f, mw / 2f, fill)
        }

        val cx = r.centerX()
        val base = r.bottom - margin * 2.2f
        label.color = if (active) cText else cMuted
        c.drawText(name, cx, base, label)
        val n = percentChars(value)
        bigNumber.color = if (active) cText else cFaint
        c.drawText(numBuf, 0, n, cx, base - label.textSize - 10f * density, bigNumber)
    }

    private fun drawPaddle(c: Canvas, r: RectF, symbol: String, caption: String, pressed: Boolean) {
        fill.color = if (pressed) cAccent else cRaised
        c.drawRoundRect(r, radius, radius, fill)
        if (!pressed) {
            outline.color = cStrokeStrong
            c.drawRoundRect(r, radius, radius, outline)
        }
        paddleText.color = if (pressed) cOnAccent else cText
        paddleCaption.color = if (pressed) cOnAccent else cMuted
        val cy = r.centerY()
        c.drawText(symbol, r.centerX(), cy + paddleText.textSize * 0.2f, paddleText)
        c.drawText(caption, r.centerX(), r.bottom - 10f * density, paddleCaption)
    }

    private fun drawButton(c: Canvas, r: RectF, text: String, pressed: Boolean, pill: Boolean) {
        val rr = if (pill) r.height() / 2f else 14f * density
        fill.color = if (pressed) cAccent else cRaised
        c.drawRoundRect(r, rr, rr, fill)
        if (!pressed) {
            outline.color = cStrokeStrong
            c.drawRoundRect(r, rr, rr, outline)
        }
        buttonText.color = if (pressed) cOnAccent else cText
        c.drawText(text, r.centerX(), r.centerY() + buttonText.textSize * 0.35f, buttonText)
    }

    private fun drawChip(c: Canvas) {
        fill.color = cSurface
        val rr = chip.height() / 2f
        c.drawRoundRect(chip, rr, rr, fill)
        outline.color = cStroke
        c.drawRoundRect(chip, rr, rr, outline)
        val dotR = 4.5f * density
        val dotX = chip.left + rr
        if (statusLive) {
            fill.color = cAccent
            c.drawCircle(dotX, chip.centerY(), dotR, fill)
        } else {
            outline.color = cFaint
            c.drawCircle(dotX, chip.centerY(), dotR, outline)
        }
        val tx = (chip.left + dotX + dotR + chip.right) / 2f
        c.drawText(statusLine1, 0, statusLine1.length, tx, chip.centerY() - 2f * density, chipText)
        c.drawText(statusLine2, 0, statusLine2.length, tx, chip.centerY() + chipSub.textSize + 1f * density, chipSub)
    }

    private fun drawGauge(c: Canvas) {
        val steer = state.steerValue
        drawnSteer = steer
        val v = steer / 32767f
        c.drawArc(gauge, 150f, 240f, false, arcTrack)
        val sweep = v * 120f
        if (abs(sweep) > 0.5f) {
            if (sweep > 0) c.drawArc(gauge, 270f, sweep, false, arcFill) else c.drawArc(gauge, 270f + sweep, -sweep, false, arcFill)
        }
        val rad = Math.toRadians((270f + sweep).toDouble())
        val r = gauge.width() / 2f
        val kx = gauge.centerX() + (r * cos(rad)).toFloat()
        val ky = gauge.centerY() + (r * sin(rad)).toFloat()
        fill.color = cText
        c.drawCircle(kx, ky, 7f * density, fill)
        fill.color = cAccent
        c.drawCircle(kx, ky, 4f * density, fill)
        val deg = (v * cfg.lockDeg).roundToInt()
        val n = degreeChars(deg)
        c.drawText(numBuf, 0, n, gauge.centerX(), gauge.centerY() + gaugeText.textSize * 0.35f, gaugeText)
    }

    private fun percentChars(v: Float): Int {
        val p = (v * 100f).roundToInt().coerceIn(0, 100)
        var n = writeInt(p, 0)
        numBuf[n++] = '%'
        return n
    }

    private fun degreeChars(d: Int): Int {
        var n = 0
        if (d > 0) numBuf[n++] = '+'
        if (d < 0) numBuf[n++] = '-'
        n = writeInt(abs(d), n)
        numBuf[n++] = '°'
        return n
    }

    /** Writes a non-negative int into numBuf at [start]. Returns the new length. */
    private fun writeInt(value: Int, start: Int): Int {
        var v = value
        var digits = 1
        var t = v
        while (t >= 10) { t /= 10; digits++ }
        var i = start + digits - 1
        do {
            numBuf[i--] = '0' + (v % 10)
            v /= 10
        } while (v > 0)
        return start + digits
    }

    companion object {
        const val MAX_POINTERS = 32
        private const val FLASH_MS = 120L

        const val T_NONE = 0
        const val T_BRAKE = 1
        const val T_GAS = 2
        const val T_DOWN = 3
        const val T_UP = 4
        const val T_CENTER = 5
        const val T_HELD0 = 6
        const val T_HANDBRAKE = 10
        const val T_COUNT = 11
    }
}
