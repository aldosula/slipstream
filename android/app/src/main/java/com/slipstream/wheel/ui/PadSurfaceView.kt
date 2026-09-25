package com.slipstream.wheel.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import com.slipstream.wheel.pad.ButtonHolds
import com.slipstream.wheel.pad.PadButton
import com.slipstream.wheel.pad.PadState
import com.slipstream.wheel.pad.PadTouchRules
import com.slipstream.wheel.pad.StickMath
import com.slipstream.wheel.pad.TriggerMath
import com.slipstream.wheel.pad.layout.Box
import com.slipstream.wheel.pad.layout.ControlKind
import com.slipstream.wheel.pad.layout.DefaultLayouts
import com.slipstream.wheel.pad.layout.PadLayout
import com.slipstream.wheel.pad.layout.StickClick
import com.slipstream.wheel.pad.layout.StickOrigin
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * The controller play surface (ARCHITECTURE.md 7.1). Draws only the playable controls, the
 * faint pause ring, and the status chip when the link degrades.
 *
 * Touch model: a finger belongs to the control it lands on until it lifts. Sticks and
 * triggers keep tracking it outside their bounds. Face buttons and the D-pad follow the
 * finger when slide-to-press is on (the layout's option). A stick press (L3 / R3) is a firm
 * press or a double tap, held while the thumb stays down. The touchpad reports two fingers
 * and turns a short, still tap into a click held for as long as the tap lasted.
 *
 * While input is disabled (the screen is paused or its menu is open, so the link sends
 * PAUSED) no finger drives anything, so a PAUSED packet never carries a new press. A
 * cancelled pointer (ACTION_CANCEL, or a lift flagged FLAG_CANCELED) releases what it held but
 * never counts as a tap.
 *
 * Every ACTION_DOWN and ACTION_POINTER_DOWN asks for unbuffered dispatch. Nothing in
 * onTouchEvent or onDraw allocates: slots, paths and paints are built when the layout or the
 * size changes.
 */
@SuppressLint("ViewConstructor")
class PadSurfaceView(
    context: Context,
    private val state: PadState,
    private val holds: ButtonHolds,
    private val haptics: Haptics,
) : View(context) {

    interface Listener {
        /** The pause ring was held for its full 1.5 s. */
        fun onPauseRequested()
    }

    var listener: Listener? = null

    private val renderer = PadRenderer(context)
    private val density = renderer.density
    private var layout: PadLayout? = null
    private var slots: Array<ControlSlot> = emptyArray()
    private var slideToPress = true
    private val area = RectF()
    private var insetL = 0
    private var insetT = 0
    private var insetR = 0
    private var insetB = 0
    private val cutouts = ArrayList<Box>(2)
    private val exclusion = ArrayList<Rect>(2)

    /** Off until the activity is resumed with its menu closed (see [setInputEnabled]). */
    private var inputEnabled = false

    // ------------------------------------------------------------ pointers
    private val pSlot = IntArray(MAX_POINTERS) { NONE }
    private val pBits = IntArray(MAX_POINTERS)
    private val pAnchor = FloatArray(MAX_POINTERS)
    private val pDownX = FloatArray(MAX_POINTERS)
    private val pDownY = FloatArray(MAX_POINTERS)
    private val pDownMs = LongArray(MAX_POINTERS)
    private val pMajor0 = FloatArray(MAX_POINTERS)
    private val pMoved = FloatArray(MAX_POINTERS)
    private val pFinger = IntArray(MAX_POINTERS) { -1 }
    private val pClick = BooleanArray(MAX_POINTERS)
    private val pClickByDoubleTap = BooleanArray(MAX_POINTERS)

    /** Per slot: the pointer that owns a stick or trigger, and the last short tap on a stick. */
    private var owner = IntArray(0)
    private var lastTapUpMs = LongArray(0)

    private val knob = FloatArray(2)

    // Touchpad fingers: owner pointer and tracking id per slot, 7-bit wrapping ids.
    private val fingerOwner = intArrayOf(-1, -1)
    private val trackId = IntArray(2)
    private var nextTrackId = 0
    private var touchClickHeld = false
    private val releaseTouchClick = Runnable {
        if (touchClickHeld) {
            touchClickHeld = false
            holds.release(PadButton.TOUCHPAD)
            invalidate()
        }
    }

    // ------------------------------------------------------------ pause ring
    private var pauseX = 0f
    private var pauseY = 0f
    private val pauseHitR = PadRenderer.PAUSE_HIT_DP / 2f * density
    private val pauseCoreR = PadRenderer.PAUSE_CORE_DP / 2f * density
    private var pausePointer = -1
    private var pauseDownNs = 0L
    private var pauseProgress = 0f
    private var ticking = false
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!ticking) return
            val p = pausePointer
            if (p < 0) {
                ticking = false
                return
            }
            pauseProgress = ((System.nanoTime() - pauseDownNs) / PAUSE_HOLD_NS.toFloat()).coerceIn(0f, 1f)
            invalidate()
            if (pauseProgress >= 1f) {
                // The finger stays down but no longer drives anything.
                pSlot[p] = IGNORED
                pausePointer = -1
                pauseProgress = 0f
                ticking = false
                listener?.onPauseRequested()
                return
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    // ------------------------------------------------------------ status chip
    private val chipText = StringBuilder(96)
    private var chipVisible = false
    private var chipBad = false

    init {
        isHapticFeedbackEnabled = false
        isSoundEffectsEnabled = false
    }

    /**
     * Whether fingers drive the controls. The activity turns it off while it is paused or its
     * menu is open (the link sends PAUSED then); turning it off lifts every finger.
     */
    fun setInputEnabled(enabled: Boolean) {
        if (enabled == inputEnabled) return
        inputEnabled = enabled
        if (!enabled) releaseAllPointers()
    }

    /** Shows [layout]. Every finger is released first. */
    fun setLayout(layout: PadLayout) {
        releaseAllPointers()
        this.layout = layout
        slideToPress = layout.slideToPress
        rebuild()
    }

    /** Visible system bar insets (zero while immersive) and display cutout rectangles, view pixels. */
    fun setInsets(l: Int, t: Int, r: Int, b: Int, cutoutRects: List<Rect>) {
        // Rebuilding lifts every finger, so only a real change of the drawing area does it.
        if (l == insetL && t == insetT && r == insetR && b == insetB && sameCutouts(cutouts, cutoutRects)) return
        insetL = l; insetT = t; insetR = r; insetB = b
        cutouts.clear()
        for (c in cutoutRects) cutouts.add(Box(c.left.toFloat(), c.top.toFloat(), c.right.toFloat(), c.bottom.toFloat()))
        rebuild()
    }

    /** Status chip: shown only while the link is degraded, and 3 s after (the activity decides). */
    fun setChip(visible: Boolean, bad: Boolean, message: CharSequence) {
        val changed = visible != chipVisible || bad != chipBad || !contentEquals(chipText, message)
        chipVisible = visible
        chipBad = bad
        chipText.setLength(0)
        chipText.append(message)
        if (changed) invalidate()
    }

    private fun contentEquals(a: CharSequence, b: CharSequence): Boolean {
        if (a.length != b.length) return false
        for (i in a.indices) if (a[i] != b[i]) return false
        return true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        rebuild()
    }

    private fun rebuild() {
        val l = layout ?: return
        if (width <= 0 || height <= 0) return
        releaseAllPointers()
        area.set(insetL.toFloat(), insetT.toFloat(), (width - insetR).toFloat(), (height - insetB).toFloat())
        slots = renderer.buildSlots(l, area, cutouts)
        owner = IntArray(slots.size) { -1 }
        lastTapUpMs = LongArray(slots.size)
        pauseX = area.left + DefaultLayouts.PAUSE_CX * area.width()
        pauseY = area.top + DefaultLayouts.PAUSE_CY * area.height()
        // Keep the system back gesture off the lower side edges, where thumbs sweep (API 29+).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val band = (200f * density).toInt().coerceAtMost(height)
            val edge = (32f * density).toInt()
            exclusion.clear()
            exclusion.add(Rect(0, height - band, edge, height))
            exclusion.add(Rect(width - edge, height - band, width, height))
            systemGestureExclusionRects = exclusion
        }
        invalidate()
    }

    /** Lifts every finger: sticks centred, triggers 0, buttons released, touchpad fingers off. */
    fun releaseAllPointers() {
        pSlot.fill(NONE)
        pBits.fill(0)
        pFinger.fill(-1)
        pClick.fill(false)
        pClickByDoubleTap.fill(false)
        owner.fill(-1)
        fingerOwner[0] = -1
        fingerOwner[1] = -1
        for (s in slots) s.resetLive()
        cancelPauseHold()
        removeCallbacks(releaseTouchClick)
        touchClickHeld = false
        holds.clear()
        state.rightStickTouched = false
        state.releaseAll()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        cancelPauseHold()
        removeCallbacks(releaseTouchClick)
        // The pending release will not run any more: let go of the click now.
        releaseTouchClick.run()
        super.onDetachedFromWindow()
    }

    // ------------------------------------------------------------ touch

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                requestUnbufferedDispatch(e)
                if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                    // A new gesture: no earlier finger is down any more. One still tracked here
                    // missed its lift, so it is cancelled rather than left holding a control.
                    for (p in 0 until MAX_POINTERS) if (pSlot[p] != NONE) up(p, pDownX[p], pDownY[p], e.eventTime, canceled = true)
                }
                val i = e.actionIndex
                down(e.getPointerId(i), e.getX(i), e.getY(i), e.getTouchMajor(i), e.eventTime)
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) move(e.getPointerId(i), e.getX(i), e.getY(i), e.getTouchMajor(i))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = e.actionIndex
                up(e.getPointerId(i), e.getX(i), e.getY(i), e.eventTime, PadTouchRules.isCanceled(e.flags))
            }
            MotionEvent.ACTION_CANCEL -> releaseAllPointers()
        }
        return true
    }

    private fun down(id: Int, x: Float, y: Float, major: Float, t: Long) {
        if (id < 0 || id >= MAX_POINTERS) return
        if (pSlot[id] != NONE) up(id, x, y, t, canceled = true)
        if (!inputEnabled) {
            pSlot[id] = IGNORED
            return
        }
        val ringDistance = hypot(x - pauseX, y - pauseY)
        if (pausePointer < 0 && ringDistance <= pauseHitR &&
            PadTouchRules.pauseRingTakes(ringDistance, pauseHitR, pauseCoreR, ringDistance > pauseCoreR && boxAt(x, y) >= 0)
        ) {
            pSlot[id] = PAUSE
            pausePointer = id
            pauseDownNs = System.nanoTime()
            pauseProgress = 0f
            if (!ticking) {
                ticking = true
                Choreographer.getInstance().postFrameCallback(frameCallback)
            }
            invalidate()
            return
        }
        val si = hitTest(x, y)
        if (si < 0) {
            pSlot[id] = IGNORED
            return
        }
        val s = slots[si]
        pSlot[id] = si
        pBits[id] = 0
        pDownX[id] = x
        pDownY[id] = y
        pDownMs[id] = t
        pMajor0[id] = major
        pMoved[id] = 0f
        pClick[id] = false
        pClickByDoubleTap[id] = false
        pFinger[id] = -1
        when (s.kind) {
            ControlKind.BUTTON -> pressBit(id, s, s.bit, tick = true)
            ControlKind.FACE -> {
                val b = faceBit(s, x, y)
                if (b >= 0) pressBit(id, s, b, tick = true)
            }
            ControlKind.DPAD -> setDpad(id, s, dpadBits(s, x, y))
            ControlKind.STICK -> {
                if (owner[si] >= 0) {
                    pSlot[id] = IGNORED
                    return
                }
                owner[si] = id
                stickDown(id, si, s, x, y, t)
            }
            ControlKind.TRIGGER -> {
                if (owner[si] >= 0) {
                    pSlot[id] = IGNORED
                    return
                }
                owner[si] = id
                triggerDown(id, s, x, y)
            }
            ControlKind.TOUCHPAD -> touchpadDown(id, s, x, y)
        }
        invalidate()
    }

    private fun move(id: Int, x: Float, y: Float, major: Float) {
        if (id < 0 || id >= MAX_POINTERS) return
        val si = pSlot[id]
        if (si == PAUSE) {
            if (hypot(x - pauseX, y - pauseY) > pauseHitR * PAUSE_SLOP) {
                pSlot[id] = IGNORED
                cancelPauseHold()
            }
            return
        }
        if (si < 0) return
        val s = slots[si]
        when (s.kind) {
            ControlKind.FACE -> if (slideToPress) {
                val b = faceBit(s, x, y)
                val cur = if (pBits[id] == 0) -1 else Integer.numberOfTrailingZeros(pBits[id])
                if (b != cur) {
                    if (cur >= 0) releaseBit(id, cur)
                    if (b >= 0) pressBit(id, s, b, tick = true)
                    invalidate()
                }
            }
            ControlKind.DPAD -> if (slideToPress) {
                val bits = dpadBits(s, x, y)
                if (bits != pBits[id]) {
                    setDpad(id, s, bits)
                    invalidate()
                }
            }
            ControlKind.STICK -> stickMove(id, s, x, y, major)
            ControlKind.TRIGGER -> triggerMove(id, s, x, y)
            ControlKind.TOUCHPAD -> touchpadMove(id, s, x, y)
            ControlKind.BUTTON -> Unit
        }
    }

    /** A finger lifts. A [canceled] one releases what it held but is never a tap. */
    private fun up(id: Int, x: Float, y: Float, t: Long, canceled: Boolean) {
        if (id < 0 || id >= MAX_POINTERS) return
        val si = pSlot[id]
        pSlot[id] = NONE
        if (si == PAUSE) {
            cancelPauseHold()
            return
        }
        if (si < 0) return
        val s = slots[si]
        when (s.kind) {
            ControlKind.BUTTON, ControlKind.FACE, ControlKind.DPAD -> releaseBits(id)
            ControlKind.STICK -> stickUp(id, si, s, t, canceled)
            ControlKind.TRIGGER -> {
                owner[si] = -1
                s.touched = false
                s.value = 0f
                state.setTrigger(s.control.binding, 0)
            }
            ControlKind.TOUCHPAD -> touchpadUp(id, s, x, y, t, canceled)
        }
        pBits[id] = 0
        invalidate()
    }

    /** Topmost control whose drawn bounds hold the point, or -1. */
    private fun boxAt(x: Float, y: Float): Int {
        var i = slots.size - 1
        while (i >= 0) {
            if (slots[i].box.contains(x, y)) return i
            i--
        }
        return -1
    }

    /** Topmost control under the finger: drawn bounds first, then the grown 44 dp touch bounds. */
    private fun hitTest(x: Float, y: Float): Int {
        val drawn = boxAt(x, y)
        if (drawn >= 0) return drawn
        var i = slots.size - 1
        while (i >= 0) {
            if (slots[i].hit.contains(x, y)) return i
            i--
        }
        return -1
    }

    private fun cancelPauseHold() {
        if (pausePointer >= 0 || pauseProgress > 0f) {
            pausePointer = -1
            pauseProgress = 0f
            invalidate()
        }
        if (ticking) {
            ticking = false
            Choreographer.getInstance().removeFrameCallback(frameCallback)
        }
    }

    // ------------------------------------------------------------ buttons, face, D-pad

    private fun pressBit(id: Int, s: ControlSlot, bit: Int, tick: Boolean) {
        if (bit < 0) return
        pBits[id] = pBits[id] or (1 shl bit)
        if (holds.press(bit) && tick) haptics.tick(s.control.haptic)
    }

    private fun releaseBit(id: Int, bit: Int) {
        pBits[id] = pBits[id] and (1 shl bit).inv()
        holds.release(bit)
    }

    private fun releaseBits(id: Int) {
        var bits = pBits[id]
        while (bits != 0) {
            val b = Integer.numberOfTrailingZeros(bits)
            bits = bits and (1 shl b).inv()
            holds.release(b)
        }
        pBits[id] = 0
    }

    /** The face button under the finger, or -1 between buttons. */
    private fun faceBit(s: ControlSlot, x: Float, y: Float): Int {
        val r = s.faceHitR
        for (k in 0 until 4) {
            val dx = x - s.faceX[k]
            val dy = y - s.faceY[k]
            if (dx * dx + dy * dy <= r * r) return DefaultLayouts.FACE_BITS[k]
        }
        return -1
    }

    /**
     * D-pad direction bits from the finger's offset to the centre: within 30 degrees of an
     * axis is that direction alone, between them both (a diagonal). The dead centre is none.
     */
    private fun dpadBits(s: ControlSlot, x: Float, y: Float): Int {
        val dx = x - s.box.centerX()
        val dy = y - s.box.centerY()
        val size = s.arm * 3f
        if (dx * dx + dy * dy < (size * DPAD_DEAD) * (size * DPAD_DEAD)) return 0
        val ax = abs(dx)
        val ay = abs(dy)
        val horizontalOnly = ay <= ax * TAN_30
        val verticalOnly = ax <= ay * TAN_30
        var bits = 0
        if (!verticalOnly) bits = bits or (1 shl if (dx > 0f) PadButton.RIGHT else PadButton.LEFT)
        if (!horizontalOnly) bits = bits or (1 shl if (dy < 0f) PadButton.UP else PadButton.DOWN)
        return bits
    }

    private fun setDpad(id: Int, s: ControlSlot, bits: Int) {
        val old = pBits[id]
        var released = old and bits.inv()
        while (released != 0) {
            val b = Integer.numberOfTrailingZeros(released)
            released = released and (1 shl b).inv()
            releaseBit(id, b)
        }
        var pressed = bits and old.inv()
        var ticked = false
        while (pressed != 0) {
            val b = Integer.numberOfTrailingZeros(pressed)
            pressed = pressed and (1 shl b).inv()
            pressBit(id, s, b, tick = !ticked)
            ticked = true
        }
    }

    // ------------------------------------------------------------ sticks

    private fun stickDown(id: Int, si: Int, s: ControlSlot, x: Float, y: Float, t: Long) {
        val o = s.control.options
        if (o.stickOrigin == StickOrigin.FLOATING) {
            s.originX = StickMath.origin(x, s.box.left, s.box.right)
            s.originY = StickMath.origin(y, s.box.top, s.box.bottom)
        } else {
            s.originX = s.box.centerX()
            s.originY = s.box.centerY()
        }
        s.touched = true
        val doubleTap = o.stickClick == StickClick.DOUBLE_TAP || o.stickClick == StickClick.BOTH
        if (doubleTap && lastTapUpMs[si] != 0L && t - lastTapUpMs[si] <= DOUBLE_TAP_MS) {
            engageClick(id, s)
            pClickByDoubleTap[id] = true
        }
        lastTapUpMs[si] = 0L
        updateStick(s, x, y)
        if (s.control.binding == 1) state.rightStickTouched = true
    }

    private fun stickMove(id: Int, s: ControlSlot, x: Float, y: Float, major: Float) {
        val moved = hypot(x - pDownX[id], y - pDownY[id])
        if (moved > pMoved[id]) pMoved[id] = moved
        val o = s.control.options
        val firm = o.stickClick == StickClick.FIRM_PRESS || o.stickClick == StickClick.BOTH
        if (firm && !pClick[id] && pMajor0[id] > 0f && major >= pMajor0[id] * FIRM_PRESS_GROWTH) engageClick(id, s)
        updateStick(s, x, y)
        invalidate()
    }

    private fun stickUp(id: Int, si: Int, s: ControlSlot, t: Long, canceled: Boolean) {
        owner[si] = -1
        s.touched = false
        s.knobX = 0f
        s.knobY = 0f
        state.setStick(s.control.binding, 0, 0)
        if (pClick[id]) {
            pClick[id] = false
            holds.release(s.bit)
        }
        if (s.control.binding == 1) state.rightStickTouched = false
        // A short, still touch arms the double tap; the touch that made a click does not.
        val shortTap = !canceled && t - pDownMs[id] <= TAP_MS && pMoved[id] <= s.radius * TAP_SLOP
        lastTapUpMs[si] = if (shortTap && !pClickByDoubleTap[id]) t else 0L
        pClickByDoubleTap[id] = false
    }

    private fun engageClick(id: Int, s: ControlSlot) {
        pClick[id] = true
        if (holds.press(s.bit)) haptics.tick(s.control.haptic)
    }

    private fun updateStick(s: ControlSlot, x: Float, y: Float) {
        val dx = x - s.originX
        val dy = y - s.originY
        StickMath.knob(dx, dy, s.radius, knob)
        s.knobX = knob[0]
        s.knobY = knob[1]
        val o = s.control.options
        val v = StickMath.output(dx, dy, s.radius, o.deadzone, o.curve)
        state.setStick(s.control.binding, StickMath.x(v), StickMath.y(v))
    }

    // ------------------------------------------------------------ triggers

    private fun axisPos(s: ControlSlot, x: Float, y: Float): Float = if (s.horizontal) s.sign * x else -y

    private fun triggerDown(id: Int, s: ControlSlot, x: Float, y: Float) {
        s.touched = true
        pAnchor[id] = axisPos(s, x, y)
        if (s.control.options.triggerMode == TriggerMath.Mode.TAP) {
            s.value = 1f
            state.setTrigger(s.control.binding, TriggerMath.FULL)
            haptics.tick(s.control.haptic)
        } else {
            s.value = 0f
            state.setTrigger(s.control.binding, 0)
        }
    }

    private fun triggerMove(id: Int, s: ControlSlot, x: Float, y: Float) {
        if (s.control.options.triggerMode == TriggerMath.Mode.TAP) return
        val pos = axisPos(s, x, y)
        val a = TriggerMath.anchor(pAnchor[id], pos, s.travel)
        pAnchor[id] = a
        val v = TriggerMath.value(a, pos, s.travel)
        if (v != s.value) {
            s.value = v
            state.setTrigger(s.control.binding, TriggerMath.output(v, s.control.options.curve))
            invalidate()
        }
    }

    // ------------------------------------------------------------ touchpad

    private fun touchpadDown(id: Int, s: ControlSlot, x: Float, y: Float) {
        val f = if (fingerOwner[0] < 0) 0 else if (fingerOwner[1] < 0) 1 else -1
        if (f < 0) {
            pSlot[id] = IGNORED // a third finger
            return
        }
        fingerOwner[f] = id
        pFinger[id] = f
        trackId[f] = nextTrackId
        nextTrackId = (nextTrackId + 1) and 0x7F
        touchpadUpdate(s, f, x, y, true)
    }

    private fun touchpadMove(id: Int, s: ControlSlot, x: Float, y: Float) {
        val f = pFinger[id]
        if (f < 0) return
        val moved = max(abs(x - pDownX[id]) / s.box.width(), abs(y - pDownY[id]) / s.box.height())
        if (moved > pMoved[id]) pMoved[id] = moved
        touchpadUpdate(s, f, x, y, true)
        invalidate()
    }

    private fun touchpadUp(id: Int, s: ControlSlot, x: Float, y: Float, t: Long, canceled: Boolean) {
        val f = pFinger[id]
        pFinger[id] = -1
        if (f < 0) return
        fingerOwner[f] = -1
        // A cancelled finger goes inactive where it was last seen, not at the cancel point.
        if (canceled) touchpadUpdate(s, f, s.fingerX[f], s.fingerY[f], false) else touchpadUpdate(s, f, x, y, false)
        val duration = t - pDownMs[id]
        if (PadTouchRules.touchpadTapIsClick(duration, pMoved[id], canceled)) {
            // A tap is a touchpad click, held for as long as the tap lasted. A second tap
            // while the first click is still held is its own click: release, then press
            // again, so the tap counter goes up and the hub replays it (PROTOCOL.md 12.4).
            if (touchClickHeld) holds.release(PadButton.TOUCHPAD)
            touchClickHeld = true
            if (holds.press(PadButton.TOUCHPAD)) haptics.tick(s.control.haptic)
            removeCallbacks(releaseTouchClick)
            postDelayed(releaseTouchClick, PadTouchRules.clickHoldMs(duration))
        }
    }

    private fun touchpadUpdate(s: ControlSlot, f: Int, x: Float, y: Float, active: Boolean) {
        val box = s.box
        val nx = ((x - box.left) / box.width()).coerceIn(0f, 1f)
        val ny = ((y - box.top) / box.height()).coerceIn(0f, 1f)
        s.fingerX[f] = box.left + nx * box.width()
        s.fingerY[f] = box.top + ny * box.height()
        s.fingerOn[f] = active
        state.setTouch(f, active, trackId[f], (nx * 65535f + 0.5f).toInt(), (ny * 65535f + 0.5f).toInt())
    }

    // ------------------------------------------------------------ drawing

    override fun onDraw(c: Canvas) {
        c.drawColor(renderer.cBg)
        val held = state.heldButtons
        val all = slots
        for (i in all.indices) renderer.draw(c, all[i], held)
        renderer.drawPauseRing(c, pauseX, pauseY, pauseProgress)
        if (chipVisible) {
            renderer.drawChip(c, area.centerX(), area.bottom - CHIP_BOTTOM_DP * density, chipText, chipBad)
        }
    }

    companion object {
        const val MAX_POINTERS = 32
        private const val NONE = -1
        private const val IGNORED = -2
        private const val PAUSE = -3

        const val PAUSE_HOLD_NS = 1_500_000_000L
        private const val PAUSE_SLOP = 1.6f

        const val DOUBLE_TAP_MS = 300L
        const val TAP_MS = 250L
        private const val TAP_SLOP = 0.3f

        /** Firm press: the touch's major axis grows by 35 % over its landing value. */
        const val FIRM_PRESS_GROWTH = 1.35f

        private const val DPAD_DEAD = 0.12f
        private const val TAN_30 = 0.57735f
        private const val CHIP_BOTTOM_DP = 14f
    }
}
