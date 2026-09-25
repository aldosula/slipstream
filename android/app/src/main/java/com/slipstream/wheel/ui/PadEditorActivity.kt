package com.slipstream.wheel.ui

import android.app.AlertDialog
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.slipstream.wheel.DriveOrientation
import com.slipstream.wheel.R
import com.slipstream.wheel.Settings
import com.slipstream.wheel.pad.GyroAim
import com.slipstream.wheel.pad.PadButton
import com.slipstream.wheel.pad.PadStyle
import com.slipstream.wheel.pad.StickMath
import com.slipstream.wheel.pad.TriggerMath
import com.slipstream.wheel.pad.layout.ButtonShape
import com.slipstream.wheel.pad.layout.ControlKind
import com.slipstream.wheel.pad.layout.DefaultLayouts
import com.slipstream.wheel.pad.layout.LayoutStore
import com.slipstream.wheel.pad.layout.PadControl
import com.slipstream.wheel.pad.layout.PadGeometry
import com.slipstream.wheel.pad.layout.PadLayout
import com.slipstream.wheel.pad.layout.StickClick
import com.slipstream.wheel.pad.layout.StickOrigin
import kotlin.math.roundToInt

/**
 * The layout editor screen: the editor surface in the play screen's own orientation and
 * drawing area, a tool bar (grid, mirror, reset, layout options, save, close) and an options
 * panel for the selected control (opacity, haptic strength, and its kind's options).
 */
class PadEditorActivity : ComponentActivity(), PadEditorView.Listener {
    private lateinit var settings: Settings
    private lateinit var store: LayoutStore
    private lateinit var editor: PadEditorView
    private lateinit var bar: LinearLayout
    private lateinit var gridButton: Button
    private lateinit var panelScroll: ScrollView
    private lateinit var panel: LinearLayout
    private var dirty = false
    private var barAtTop = false
    private var showingLayoutOptions = false

    private val back = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() = close()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        store = LayoutStore.open(this)
        val id = intent.getStringExtra(EXTRA_PROFILE) ?: store.lastUsedId
        val layout = store.load(id) ?: store.current()

        requestedOrientation = when (settings.orientation) {
            DriveOrientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            DriveOrientation.REVERSE_LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)

        editor = PadEditorView(this)
        editor.listener = this
        editor.setLayout(layout)
        val root = FrameLayout(this)
        root.addView(editor, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        ViewCompat.setOnApplyWindowInsetsListener(editor) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            editor.setInsets(bars.left, bars.top, bars.right, bars.bottom, insets.displayCutout?.boundingRects ?: emptyList())
            insets
        }

        bar = buildBar()
        root.addView(bar, barParams())
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_panel)
            setPadding(0, Ui.dp(context, 8f), 0, Ui.dp(context, 8f))
        }
        panelScroll = ScrollView(this).apply {
            addView(panel)
            visibility = View.GONE
        }
        root.addView(panelScroll, panelParams(Gravity.END))
        setContentView(root)
        onBackPressedDispatcher.addCallback(this, back)
        Toast.makeText(this, R.string.editor_hint, Toast.LENGTH_SHORT).show()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    // ------------------------------------------------------------ tool bar

    private fun buildBar(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_panel)
            val p = Ui.dp(context, 6f)
            setPadding(p, p, p, p)
        }
        fun add(label: Int, onClick: () -> Unit): Button {
            val b = Ui.secondaryButton(this, getString(label)).apply {
                setPadding(Ui.dp(context, 12f), 0, Ui.dp(context, 12f), 0)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
                setOnClickListener { onClick() }
            }
            row.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                if (row.childCount > 0) marginStart = Ui.dp(this@PadEditorActivity, 6f)
            })
            return b
        }
        gridButton = add(R.string.editor_grid) {
            editor.snap = !editor.snap
            renderGrid()
        }
        renderGrid()
        add(R.string.editor_mirror) {
            val l = editor.layout ?: return@add
            editor.setLayout(PadGeometry.mirror(l))
            editor.legalLayout()?.let { editor.setLayout(it) }
            onEdited()
        }
        add(R.string.editor_reset) { confirmReset() }
        add(R.string.editor_layout) { showLayoutOptions() }
        add(R.string.editor_move_bar) {
            barAtTop = !barAtTop
            bar.layoutParams = barParams()
        }
        add(R.string.editor_close) { close() }
        val save = Ui.primaryButton(this, getString(R.string.editor_save)).apply {
            minHeight = Ui.dp(context, 48f)
            minimumHeight = Ui.dp(context, 48f)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(Ui.dp(context, 16f), 0, Ui.dp(context, 16f), 0)
            setOnClickListener { save() }
        }
        row.addView(save, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = Ui.dp(this@PadEditorActivity, 6f)
        })
        return row
    }

    /** The grid button shows its state with the accent outline, not colour alone. */
    private fun renderGrid() {
        gridButton.setBackgroundResource(if (editor.snap) R.drawable.bg_segment else R.drawable.bg_button_secondary)
        gridButton.isSelected = editor.snap
    }

    private fun barParams(): FrameLayout.LayoutParams =
        FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER_HORIZONTAL or (if (barAtTop) Gravity.TOP else Gravity.BOTTOM),
        ).apply {
            val m = Ui.dp(this@PadEditorActivity, 14f)
            topMargin = m
            bottomMargin = m
        }

    private fun panelParams(side: Int): FrameLayout.LayoutParams =
        FrameLayout.LayoutParams(Ui.dp(this, 300f), ViewGroup.LayoutParams.WRAP_CONTENT, side or Gravity.CENTER_VERTICAL).apply {
            val m = Ui.dp(this@PadEditorActivity, 16f)
            marginStart = m
            marginEnd = m
            topMargin = m
            bottomMargin = m
        }

    // ------------------------------------------------------------ editor callbacks

    override fun onSelectionChanged(index: Int) {
        showingLayoutOptions = false
        renderPanel()
    }

    override fun onEdited() {
        dirty = true
    }

    // ------------------------------------------------------------ options panel

    private fun showLayoutOptions() {
        editor.select(-1)
        showingLayoutOptions = true
        renderPanel()
    }

    private fun renderPanel() {
        panel.removeAllViews()
        val c = editor.selectedControl
        val l = editor.layout
        if (l == null || (c == null && !showingLayoutOptions)) {
            panelScroll.visibility = View.GONE
            return
        }
        if (c != null) {
            // Put the panel on the side away from the control, so it stays visible.
            val onRight = c.cx < 0.5f
            panelScroll.layoutParams = panelParams(if (onRight) Gravity.END else Gravity.START)
            buildControlPanel(l, c)
        } else {
            panelScroll.layoutParams = panelParams(Gravity.END)
            buildLayoutPanel(l)
        }
        panel.addView(Ui.secondaryButton(this, getString(R.string.editor_done)).apply {
            setOnClickListener {
                showingLayoutOptions = false
                editor.select(-1)
                renderPanel()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            val m = Ui.dp(this@PadEditorActivity, 16f)
            setMargins(m, Ui.dp(this@PadEditorActivity, 8f), m, Ui.dp(this@PadEditorActivity, 8f))
        })
        panelScroll.visibility = View.VISIBLE
    }

    private fun buildControlPanel(l: PadLayout, c: PadControl) {
        panel.addView(header(controlName(l.style, c)))
        panel.addView(slider(getString(R.string.opt_opacity), null, 15f, 100f, 5f, c.opacity * 100f, { "${it.roundToInt()} %" }) { v ->
            editor.updateSelected { it.copy(opacity = v / 100f) }
        })
        panel.addView(Ui.divider(this))
        panel.addView(slider(getString(R.string.opt_haptic), null, 0f, 100f, 25f, c.haptic * 100f, {
            if (it.roundToInt() == 0) "Off" else "${it.roundToInt()} %"
        }) { v -> editor.updateSelected { it.copy(haptic = v / 100f) } })
        when (c.kind) {
            ControlKind.BUTTON -> {
                panel.addView(Ui.divider(this))
                val shapes = listOf(ButtonShape.RECT, ButtonShape.PILL, ButtonShape.ROUND)
                panel.addView(choice(getString(R.string.opt_shape), null, listOf("Square", "Pill", "Round"), shapes.indexOf(c.options.shape)) { i ->
                    editor.updateSelected { it.copy(options = it.options.copy(shape = shapes[i])) }
                })
            }
            ControlKind.TRIGGER -> {
                panel.addView(Ui.divider(this))
                val modes = listOf(TriggerMath.Mode.SLIDE, TriggerMath.Mode.TAP)
                panel.addView(choice(getString(R.string.opt_trigger_mode), getString(R.string.opt_trigger_mode_hint), listOf("Slide", "Tap"), modes.indexOf(c.options.triggerMode)) { i ->
                    editor.updateSelected { it.copy(options = it.options.copy(triggerMode = modes[i])) }
                })
            }
            ControlKind.STICK -> {
                panel.addView(Ui.divider(this))
                val origins = listOf(StickOrigin.FIXED, StickOrigin.FLOATING)
                panel.addView(choice(getString(R.string.opt_origin), getString(R.string.opt_origin_hint), listOf("Fixed", "Floating"), origins.indexOf(c.options.stickOrigin)) { i ->
                    editor.updateSelected { it.copy(options = it.options.copy(stickOrigin = origins[i])) }
                })
                panel.addView(Ui.divider(this))
                val clicks = listOf(StickClick.OFF, StickClick.DOUBLE_TAP, StickClick.FIRM_PRESS, StickClick.BOTH)
                panel.addView(choice(getString(R.string.opt_click), getString(R.string.opt_click_hint), listOf("Off", "2 taps", "Firm", "Both"), clicks.indexOf(c.options.stickClick)) { i ->
                    editor.updateSelected { it.copy(options = it.options.copy(stickClick = clicks[i])) }
                })
                panel.addView(Ui.divider(this))
                panel.addView(slider(getString(R.string.opt_deadzone), null, 0f, StickMath.MAX_DEADZONE * 100f, 1f, c.options.deadzone * 100f, { "${it.roundToInt()} %" }) { v ->
                    editor.updateSelected { it.copy(options = it.options.copy(deadzone = v / 100f)) }
                })
                panel.addView(Ui.divider(this))
                panel.addView(slider(getString(R.string.opt_curve), null, 0.5f, 3f, 0.1f, c.options.curve, { decimals(it, 1) }) { v ->
                    editor.updateSelected { it.copy(options = it.options.copy(curve = v)) }
                })
            }
            else -> Unit
        }
    }

    private fun buildLayoutPanel(l: PadLayout) {
        panel.addView(header(getString(R.string.opt_layout_title)))
        panel.addView(toggle(getString(R.string.opt_slide), getString(R.string.opt_slide_hint), l.slideToPress) { on ->
            updateLayout { it.copy(slideToPress = on) }
        })
        panel.addView(Ui.divider(this))
        val modes = listOf(GyroAim.Mode.OFF, GyroAim.Mode.ALWAYS, GyroAim.Mode.WHILE_TOUCHING)
        panel.addView(choice(getString(R.string.opt_gyro), getString(R.string.opt_gyro_hint), listOf("Off", "Always", "On stick"), modes.indexOf(l.gyroMode)) { i ->
            updateLayout { it.copy(gyroMode = modes[i]) }
        })
        panel.addView(Ui.divider(this))
        panel.addView(slider(getString(R.string.opt_gyro_sens), null, GyroAim.SENSITIVITY_MIN, GyroAim.SENSITIVITY_MAX, 0.25f, l.gyroSensitivity, { decimals(it, 2) + "x" }) { v ->
            updateLayout { it.copy(gyroSensitivity = v) }
        })
        panel.addView(Ui.divider(this))
        panel.addView(toggle(getString(R.string.opt_gyro_invert), getString(R.string.opt_gyro_invert_hint), l.gyroInvertY) { on ->
            updateLayout { it.copy(gyroInvertY = on) }
        })
    }

    private fun updateLayout(transform: (PadLayout) -> PadLayout) {
        val l = editor.layout ?: return
        editor.setLayout(transform(l))
        onEdited()
    }

    private fun controlName(style: PadStyle, c: PadControl): String = when (c.kind) {
        ControlKind.BUTTON -> PadButton.name(c.binding, style) ?: PadButton.bothNames(c.binding)
        ControlKind.TRIGGER -> if (style == PadStyle.PLAYSTATION) (if (c.binding == 0) "L2" else "R2") else (if (c.binding == 0) "LT" else "RT")
        ControlKind.STICK -> if (c.binding == 0) "Left stick" else "Right stick"
        ControlKind.DPAD -> "D-pad"
        ControlKind.FACE -> "Face buttons"
        ControlKind.TOUCHPAD -> "Touchpad"
    }

    // ------------------------------------------------------------ actions

    private fun save() {
        val legal = editor.legalLayout() ?: return
        store.save(legal)
        editor.setLayout(legal)
        dirty = false
        Toast.makeText(this, R.string.editor_saved, Toast.LENGTH_SHORT).show()
    }

    private fun confirmReset() {
        AlertDialog.Builder(this)
            .setMessage(R.string.editor_reset_confirm)
            .setPositiveButton(R.string.reset) { _, _ ->
                val l = editor.layout ?: return@setPositiveButton
                val def = DefaultLayouts.builtIn(l.id) ?: DefaultLayouts.forStyle(l.style).copy(id = l.id, name = l.name, builtIn = false)
                editor.select(-1)
                editor.setLayout(def)
                editor.legalLayout()?.let { editor.setLayout(it) }
                onEdited()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun close() {
        if (!dirty) {
            finish()
            return
        }
        val name = editor.layout?.name ?: ""
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.editor_unsaved, name))
            .setPositiveButton(R.string.editor_save) { _, _ ->
                save()
                finish()
            }
            .setNegativeButton(R.string.editor_discard) { _, _ -> finish() }
            .setNeutralButton(R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------------ builders

    private fun header(title: String): TextView = Ui.text(this, title, 17f, R.color.text, bold = true).apply {
        setPadding(Ui.dp(context, 16f), Ui.dp(context, 8f), Ui.dp(context, 16f), Ui.dp(context, 6f))
    }

    private fun padded(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(Ui.dp(context, 16f), Ui.dp(context, 10f), Ui.dp(context, 16f), Ui.dp(context, 10f))
    }

    private fun titleLine(title: String, value: TextView?): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(Ui.text(context, title, 15f, R.color.text, bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (value != null) addView(value)
    }

    private fun description(text: String): TextView =
        Ui.text(this, text, 13f, R.color.text_muted).apply { layoutParams = Ui.vertical(context, top = 2f) }

    private fun slider(
        title: String,
        detail: String?,
        min: Float,
        max: Float,
        step: Float,
        current: Float,
        format: (Float) -> String,
        onChange: (Float) -> Unit,
    ): View {
        val steps = ((max - min) / step).roundToInt()
        val value = Ui.text(this, format(current), 14f, R.color.accent, bold = true)
        return padded().apply {
            addView(titleLine(title, value))
            if (detail != null) addView(description(detail))
            addView(SeekBar(context).apply {
                this.max = steps
                progress = ((current.coerceIn(min, max) - min) / step).roundToInt()
                Ui.tintSeekBar(context, this)
                minimumHeight = Ui.dp(context, 44f)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar, p: Int, fromUser: Boolean) {
                        val v = min + p * step
                        value.text = format(v)
                        if (fromUser) onChange(v)
                    }

                    override fun onStartTrackingTouch(bar: SeekBar) = Unit
                    override fun onStopTrackingTouch(bar: SeekBar) = Unit
                })
            }, Ui.vertical(this@PadEditorActivity, top = 4f))
        }
    }

    private fun choice(title: String, detail: String?, options: List<String>, selected: Int, onSelect: (Int) -> Unit): View =
        padded().apply {
            addView(titleLine(title, null))
            if (detail != null) addView(description(detail))
            addView(Ui.segmented(context, options, selected.coerceAtLeast(0), onSelect), Ui.vertical(this@PadEditorActivity, top = 8f))
        }

    private fun toggle(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit): View {
        val sw = Switch(this).apply {
            isChecked = checked
            Ui.tintSwitch(context, this)
            setOnCheckedChangeListener { _, on -> onChange(on) }
        }
        return Ui.row(this, title, detail, end = sw).apply { setOnClickListener { sw.toggle() } }
    }

    private fun decimals(v: Float, n: Int): String = Fmt.fixed(StringBuilder(8), v.toDouble(), n).toString()

    companion object {
        const val EXTRA_PROFILE = "com.slipstream.wheel.EDIT_PROFILE"
    }
}
