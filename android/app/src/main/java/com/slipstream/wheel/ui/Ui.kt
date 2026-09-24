package com.slipstream.wheel.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import com.slipstream.wheel.R

/**
 * Programmatic view builders so every screen shares one look: dark tinted neutrals, one
 * accent, flat rows inside a single panel level (never a card inside a card), 48 dp or larger
 * touch targets, no bounce or overshoot animation anywhere.
 */
object Ui {
    val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    val REGULAR: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)

    fun dp(ctx: Context, v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics).toInt()

    fun color(ctx: Context, @ColorRes id: Int): Int = ctx.getColor(id)

    fun text(ctx: Context, value: CharSequence, sizeSp: Float, @ColorRes colorRes: Int, bold: Boolean = false): TextView =
        TextView(ctx).apply {
            text = value
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setTextColor(color(ctx, colorRes))
            typeface = if (bold) MEDIUM else REGULAR
            setLineSpacing(0f, 1.15f)
        }

    fun title(ctx: Context, value: CharSequence) = text(ctx, value, 28f, R.color.text, bold = true)

    fun body(ctx: Context, value: CharSequence, muted: Boolean = false) =
        text(ctx, value, 15f, if (muted) R.color.text_muted else R.color.text)

    fun sectionLabel(ctx: Context, value: CharSequence): TextView =
        text(ctx, value.toString().uppercase(), 12f, R.color.text_muted, bold = true).apply {
            letterSpacing = 0.08f
            layoutParams = vertical(ctx, top = 28f, bottom = 10f)
        }

    /** The one surface level: rows go directly inside it. */
    fun panel(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.bg_panel)
        clipToOutline = true
        layoutParams = vertical(ctx)
    }

    fun divider(ctx: Context): View = View(ctx).apply {
        setBackgroundColor(color(ctx, R.color.stroke))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 1f)).apply {
            marginStart = dp(ctx, 16f)
        }
    }

    fun vertical(ctx: Context, top: Float = 0f, bottom: Float = 0f): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(ctx, top)
            bottomMargin = dp(ctx, bottom)
        }

    fun primaryButton(ctx: Context, label: CharSequence): Button = Button(ctx).apply {
        text = label
        isAllCaps = false
        stateListAnimator = null
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        typeface = MEDIUM
        setBackgroundResource(R.drawable.bg_button_primary)
        setTextColor(
            ColorStateList(
                arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                intArrayOf(color(ctx, R.color.text_faint), color(ctx, R.color.on_accent)),
            ),
        )
        minHeight = ctx.resources.getDimensionPixelSize(R.dimen.drive_button_height)
    }

    fun secondaryButton(ctx: Context, label: CharSequence): Button = Button(ctx).apply {
        text = label
        isAllCaps = false
        stateListAnimator = null
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        typeface = MEDIUM
        setTextColor(color(ctx, R.color.text))
        setBackgroundResource(R.drawable.bg_button_secondary)
        minHeight = dp(ctx, 48f)
        minimumHeight = dp(ctx, 48f)
        setPadding(dp(ctx, 16f), 0, dp(ctx, 16f), 0)
    }

    /** Small label. Accent text on the soft accent tint, or muted text on a neutral chip. */
    fun badge(ctx: Context, label: CharSequence, accent: Boolean): TextView =
        text(ctx, label.toString().uppercase(), 11f, if (accent) R.color.accent else R.color.text_muted, bold = true).apply {
            letterSpacing = 0.06f
            setBackgroundResource(if (accent) R.drawable.bg_badge else R.drawable.bg_badge_neutral)
            setPadding(dp(ctx, 8f), dp(ctx, 3f), dp(ctx, 8f), dp(ctx, 3f))
        }

    /** A tappable flat row with a title, an optional description and an optional end view. */
    fun row(ctx: Context, titleText: CharSequence, detail: CharSequence?, end: View? = null, start: View? = null): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = ctx.resources.getDimensionPixelSize(R.dimen.row_min_height)
            setPadding(dp(ctx, 16f), dp(ctx, 12f), dp(ctx, 16f), dp(ctx, 12f))
            foreground = ctx.getDrawable(R.drawable.bg_row)
            if (start != null) {
                addView(start, LinearLayout.LayoutParams(dp(ctx, 22f), dp(ctx, 22f)).apply { marginEnd = dp(ctx, 14f) })
            }
            val col = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(ctx, titleText, 16f, R.color.text, bold = true).apply {
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                })
                if (detail != null) {
                    addView(text(ctx, detail, 13.5f, R.color.text_muted).apply {
                        layoutParams = vertical(ctx, top = 2f)
                    })
                }
            }
            addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (end != null) {
                addView(end, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = dp(ctx, 12f)
                })
            }
        }

    /** Radio indicator for choice rows: accent ring and dot when selected. */
    fun radio(ctx: Context, selected: Boolean): View = View(ctx).apply {
        background = RadioDrawable(color(ctx, R.color.accent), color(ctx, R.color.stroke_strong), dp(ctx, 2f).toFloat(), selected)
    }

    /** Segmented choice. Selection is shown by the accent outline, not by colour alone. */
    fun segmented(ctx: Context, options: List<String>, selected: Int, onSelect: (Int) -> Unit): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            val views = ArrayList<TextView>()
            options.forEachIndexed { i, label ->
                val tv = text(ctx, label, 14f, if (i == selected) R.color.text else R.color.text_muted, bold = true).apply {
                    gravity = Gravity.CENTER
                    minHeight = dp(ctx, 48f)
                    setBackgroundResource(R.drawable.bg_segment)
                    isSelected = i == selected
                    setOnClickListener {
                        views.forEachIndexed { j, v ->
                            v.isSelected = j == i
                            v.setTextColor(color(ctx, if (j == i) R.color.text else R.color.text_muted))
                        }
                        onSelect(i)
                    }
                }
                views.add(tv)
                addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (i > 0) marginStart = dp(ctx, 8f)
                })
            }
        }

    fun tintSeekBar(ctx: Context, s: SeekBar) {
        s.progressTintList = ColorStateList.valueOf(color(ctx, R.color.accent))
        s.thumbTintList = ColorStateList.valueOf(color(ctx, R.color.accent))
        s.progressBackgroundTintList = ColorStateList.valueOf(color(ctx, R.color.stroke_strong))
    }

    fun tintSwitch(ctx: Context, s: Switch) {
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        s.thumbTintList = ColorStateList(states, intArrayOf(color(ctx, R.color.accent), color(ctx, R.color.text_muted)))
        s.trackTintList = ColorStateList(states, intArrayOf(color(ctx, R.color.accent_pressed), color(ctx, R.color.stroke_strong)))
    }

    fun drawable(ctx: Context, @DrawableRes id: Int) = ctx.getDrawable(id)
}
