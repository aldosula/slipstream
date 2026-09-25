package com.slipstream.wheel.ui

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import com.slipstream.wheel.R
import com.slipstream.wheel.pad.PadStyle
import com.slipstream.wheel.pad.layout.PadLayout

/**
 * The pause menu over the play surface (ARCHITECTURE.md 7.1): Resume, Edit layout, Switch
 * profile, Exit. An overlay in the same window, so the screen stays immersive. While it
 * shows, the activity sends PAUSED and the hub holds every control at rest.
 */
@SuppressLint("ViewConstructor")
class PauseMenu(context: Context, private val listener: Listener) : FrameLayout(context) {

    interface Listener {
        fun onMenuResume()
        fun onMenuEditLayout()
        fun onMenuSelectProfile(id: String)
        fun onMenuExit()
    }

    private val panel = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.bg_panel)
        val pad = Ui.dp(context, 20f)
        setPadding(pad, pad, pad, pad)
    }

    val isShowing: Boolean get() = visibility == View.VISIBLE

    init {
        setBackgroundColor(Ui.color(context, R.color.scrim))
        visibility = View.GONE
        isClickable = true // touches stop here instead of reaching the controls
        isFocusable = true
        val scroll = ScrollView(context).apply {
            isFillViewport = false
            addView(panel)
        }
        addView(scroll, LayoutParams(Ui.dp(context, 400f), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    }

    fun show(current: PadLayout) {
        renderMain(current)
        visibility = View.VISIBLE
    }

    fun hide() {
        visibility = View.GONE
    }

    private fun renderMain(current: PadLayout) {
        panel.removeAllViews()
        val ctx = context
        panel.addView(Ui.text(ctx, ctx.getString(R.string.paused), 22f, R.color.text, bold = true))
        panel.addView(Ui.text(ctx, current.name + "  |  " + styleName(current.style), 14f, R.color.text_muted).apply {
            layoutParams = Ui.vertical(ctx, top = 2f)
        })
        panel.addView(Ui.text(ctx, ctx.getString(R.string.pause_hint), 13.5f, R.color.text_muted).apply {
            layoutParams = Ui.vertical(ctx, top = 8f)
        })
        panel.addView(Ui.primaryButton(ctx, ctx.getString(R.string.resume)).apply {
            setOnClickListener { listener.onMenuResume() }
        }, Ui.vertical(ctx, top = 16f))
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Ui.secondaryButton(ctx, ctx.getString(R.string.edit_layout)).apply {
            setOnClickListener { listener.onMenuEditLayout() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Ui.secondaryButton(ctx, ctx.getString(R.string.switch_profile)).apply {
            setOnClickListener { requestProfiles() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = Ui.dp(ctx, 10f)
        })
        panel.addView(row, Ui.vertical(ctx, top = 10f))
        panel.addView(Ui.secondaryButton(ctx, ctx.getString(R.string.exit)).apply {
            setOnClickListener { listener.onMenuExit() }
        }, Ui.vertical(ctx, top = 10f))
    }

    private var profilesProvider: (() -> Pair<List<PadLayout>, String>)? = null

    /** Where "Switch profile" gets the list and the current id from. */
    fun setProfiles(provider: () -> Pair<List<PadLayout>, String>) {
        profilesProvider = provider
    }

    private fun requestProfiles() {
        val (profiles, currentId) = profilesProvider?.invoke() ?: return
        panel.removeAllViews()
        val ctx = context
        panel.addView(Ui.text(ctx, ctx.getString(R.string.switch_profile), 22f, R.color.text, bold = true))
        val list = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = Ui.vertical(ctx, top = 12f)
        }
        profiles.forEachIndexed { i, p ->
            if (i > 0) list.addView(Ui.divider(ctx))
            val row = Ui.row(ctx, p.name, styleName(p.style), start = Ui.radio(ctx, p.id == currentId))
            row.setOnClickListener { listener.onMenuSelectProfile(p.id) }
            list.addView(row)
        }
        panel.addView(list)
        panel.addView(Ui.secondaryButton(ctx, ctx.getString(R.string.back)).apply {
            setOnClickListener { profiles.firstOrNull { it.id == currentId }?.let { renderMain(it) } }
        }, Ui.vertical(ctx, top = 12f))
    }

    private fun styleName(style: PadStyle): String =
        context.getString(if (style == PadStyle.PLAYSTATION) R.string.style_ps else R.string.style_xbox)
}
