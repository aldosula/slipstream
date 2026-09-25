package com.slipstream.wheel.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.slipstream.wheel.link.StatusSink
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToLong

/** Allocation-free number formatting into a reused StringBuilder. */
object Fmt {
    private val POW10 = longArrayOf(1, 10, 100, 1000, 10000)

    fun fixed(sb: StringBuilder, value: Double, decimals: Int): StringBuilder {
        if (value.isNaN() || value.isInfinite()) return sb.append("--")
        val p = POW10[decimals]
        var scaled = (abs(value) * p).roundToLong()
        if (value < 0 && scaled != 0L) sb.append('-')
        val whole = scaled / p
        sb.append(whole)
        if (decimals > 0) {
            sb.append('.')
            scaled %= p
            var div = p / 10
            while (div > 0) {
                sb.append('0' + ((scaled / div) % 10).toInt())
                div /= 10
            }
        }
        return sb
    }
}

/** A drawn radio indicator: ring, and a filled dot when selected. */
class RadioDrawable(
    private val accent: Int,
    private val neutral: Int,
    private val strokePx: Float,
    private val selected: Boolean,
) : Drawable() {
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = strokePx }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = accent }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val r = min(b.width(), b.height()) / 2f
        ring.color = if (selected) accent else neutral
        canvas.drawCircle(b.exactCenterX(), b.exactCenterY(), r - strokePx / 2f, ring)
        if (selected) canvas.drawCircle(b.exactCenterX(), b.exactCenterY(), r * 0.45f, dot)
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * Phone vibration: the short tick on each gear shift (and on each press in controller mode,
 * at the control's strength), and rumble driven by the game through
 * STATUS. Rumble is re-issued as a 120 ms one-shot on every STATUS (20 Hz), so it stops by
 * itself when the hub goes quiet. Vibrator calls are binder calls, so they run on their
 * own thread: never on a receive thread, and never on the main thread, which delivers the
 * touch events of the pedals.
 */
class Haptics(context: Context) {
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Vibrator::class.java)
    }?.takeIf { it.hasVibrator() }

    private val amplitudeControl = vibrator?.hasAmplitudeControl() == true

    private val tickEffect: VibrationEffect? = when {
        vibrator == null -> null
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
        else -> VibrationEffect.createOneShot(12, if (amplitudeControl) 180 else VibrationEffect.DEFAULT_AMPLITUDE)
    }

    private val thread = HandlerThread("slip-haptics").also { it.start() }
    private val handler = Handler(thread.looper)

    /** Per control tick strengths for controller mode, built once so a press allocates nothing. */
    private val levelEffects: Array<VibrationEffect?> = Array(LEVELS) { i ->
        when {
            vibrator == null -> null
            amplitudeControl -> VibrationEffect.createOneShot(LEVEL_MS, (255 * (i + 1) / LEVELS).coerceIn(1, 255))
            else -> tickEffect
        }
    }
    private val levelTasks: Array<Runnable> = Array(LEVELS) { i ->
        Runnable {
            val v = vibrator
            val e = levelEffects[i]
            if (v != null && e != null) v.vibrate(e)
        }
    }

    @Volatile private var rumbleLevel = 0
    private var lastAmp = 0
    private var lastIssueMs = 0L
    private val rumbleTask = Runnable { applyRumble() }
    private val tickTask = Runnable {
        val v = vibrator
        val e = tickEffect
        if (v != null && e != null) v.vibrate(e)
    }

    /** A light tick. Returns at once: the vibrator is driven from the haptics thread. */
    fun tick() {
        if (vibrator == null || tickEffect == null) return
        handler.post(tickTask)
    }

    /**
     * A tick of [strength] 0..1 (0 is off), for a press in controller mode. Returns at once:
     * the vibrator is driven from the haptics thread.
     */
    fun tick(strength: Float) {
        if (vibrator == null || strength <= 0f) return
        val level = (strength * LEVELS + 0.5f).toInt().coerceIn(1, LEVELS) - 1
        handler.post(levelTasks[level])
    }

    /** STATUS sink for rumble; runs on receive threads and only hands the level over. */
    val rumbleSink = StatusSink { _, status, _ ->
        val level = maxOf(status.rumbleStrong, status.rumbleWeak * 3 / 5)
        rumbleLevel = level
        handler.post(rumbleTask)
    }

    private fun applyRumble() {
        val v = vibrator ?: return
        val level = rumbleLevel
        if (level < RUMBLE_THRESHOLD) {
            if (lastAmp != 0) {
                v.cancel()
                lastAmp = 0
            }
            return
        }
        val amp = if (amplitudeControl) (level / 257).coerceIn(1, 255) else VibrationEffect.DEFAULT_AMPLITUDE
        val now = SystemClock.uptimeMillis()
        // STATUS arrives on every path; skip duplicates within one STATUS period.
        if (amp == lastAmp && now - lastIssueMs < REISSUE_MS) return
        v.vibrate(VibrationEffect.createOneShot(ONE_SHOT_MS, amp))
        lastAmp = amp
        lastIssueMs = now
    }

    fun stopRumble() {
        handler.removeCallbacks(rumbleTask)
        rumbleLevel = 0
        handler.post {
            if (lastAmp != 0) vibrator?.cancel()
            lastAmp = 0
        }
    }

    fun release() {
        stopRumble()
        thread.quitSafely()
    }

    private companion object {
        const val LEVELS = 4
        const val LEVEL_MS = 14L
        const val RUMBLE_THRESHOLD = 1500
        const val ONE_SHOT_MS = 120L
        const val REISSUE_MS = 40L
    }
}
