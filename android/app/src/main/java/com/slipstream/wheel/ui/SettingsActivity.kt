package com.slipstream.wheel.ui

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.slipstream.wheel.BuildConfig
import com.slipstream.wheel.DriveOrientation
import com.slipstream.wheel.LinkMode
import com.slipstream.wheel.PedalMode
import com.slipstream.wheel.R
import com.slipstream.wheel.Settings
import com.slipstream.wheel.pad.PadButton
import com.slipstream.wheel.protocol.Slp
import kotlin.math.roundToInt

/**
 * Every tunable of ARCHITECTURE.md sections 3 and 7, grouped by what the player touches. Each
 * change is saved at once and applies the next time the drive or play screen opens.
 */
class SettingsActivity : ComponentActivity() {
    private lateinit var settings: Settings
    private lateinit var column: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        setContentView(buildContent())
    }

    private fun buildContent(): View {
        val gutter = resources.getDimensionPixelSize(R.dimen.gutter)
        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(gutter, Ui.dp(context, 8f), gutter, Ui.dp(context, 32f))
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Ui.color(context, R.color.bg))
            addView(column)
        }
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        populate()
        return scroll
    }

    private fun populate() {
        column.removeAllViews()
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageButton(context).apply {
                setImageResource(R.drawable.ic_back)
                contentDescription = "Back"
                setBackgroundResource(R.drawable.bg_row)
                setOnClickListener { finish() }
            }, LinearLayout.LayoutParams(Ui.dp(this@SettingsActivity, 48f), Ui.dp(this@SettingsActivity, 48f)))
            addView(Ui.title(context, getString(R.string.settings)).apply {
                setPadding(Ui.dp(context, 8f), 0, 0, 0)
            })
        }
        column.addView(header, Ui.vertical(this, top = 8f))

        // Steering
        section("Steering").apply {
            addView(slider(
                "Lock angle", "Tilt that gives full steer, each way.",
                Settings.LOCK_MIN.toFloat(), Settings.LOCK_MAX.toFloat(), 5f, settings.lockDeg.toFloat(),
                { "±" + it.roundToInt() + "°" },
            ) { settings.lockDeg = it.roundToInt() })
            addView(Ui.divider(context))
            addView(slider(
                "Deadzone", "Small tilt around center that is ignored.",
                0f, 10f, 0.5f, settings.deadzoneDeg,
                { oneDecimal(it) + "°" },
            ) { settings.deadzoneDeg = it })
            addView(Ui.divider(context))
            addView(slider(
                "Response curve", "1.0 is linear. Higher is finer near center.",
                0.5f, 3f, 0.1f, settings.steerCurve,
                { oneDecimal(it) },
            ) { settings.steerCurve = it })
            addView(Ui.divider(context))
            addView(slider(
                "Smoothing", "Filters hand tremor. Fast turns stay sharp at any level.",
                0f, 10f, 1f, settings.smoothing.toFloat(),
                { if (it.roundToInt() == 0) "Off" else it.roundToInt().toString() },
            ) { settings.smoothing = it.roundToInt() })
            addView(Ui.divider(context))
            addView(choice(
                "Orientation", "Fixed while driving, so the screen never flips mid-corner.",
                listOf("Landscape", "Reverse"),
                if (settings.orientation == DriveOrientation.LANDSCAPE) 0 else 1,
            ) { settings.orientation = if (it == 0) DriveOrientation.LANDSCAPE else DriveOrientation.REVERSE_LANDSCAPE })
            addView(Ui.divider(context))
            addView(action("Reset steering center", "Or press CENTER on the drive screen while holding the phone straight.") {
                settings.steerCenterRad = 0f
            })
        }

        // Pedals
        section("Pedals").apply {
            addView(choice(
                "Pedal mode", "Swipe: press is how far you slide up from where you touched. Absolute: press is the finger's height.",
                listOf("Swipe", "Absolute"),
                if (settings.pedalMode == PedalMode.SWIPE) 0 else 1,
            ) { settings.pedalMode = if (it == 0) PedalMode.SWIPE else PedalMode.ABSOLUTE })
            addView(Ui.divider(context))
            addView(slider(
                "Swipe travel", "Slide distance for a full press, as a share of the screen height.",
                Settings.TRAVEL_MIN.toFloat(), Settings.TRAVEL_MAX.toFloat(), 5f, settings.travelPct.toFloat(),
                { it.roundToInt().toString() + " %" },
            ) { settings.travelPct = it.roundToInt() })
            addView(Ui.divider(context))
            addView(toggle("Swap pedal zones", "Gas on the left, brake on the right.", settings.swapZones) {
                settings.swapZones = it
            })
            addView(Ui.divider(context))
            addView(slider(
                "Throttle curve", "1.0 is linear. Higher gives finer control at light throttle.",
                0.5f, 3f, 0.1f, settings.throttleCurve,
                { oneDecimal(it) },
            ) { settings.throttleCurve = it })
            addView(Ui.divider(context))
            addView(slider(
                "Brake curve", "1.0 is linear. Higher makes trail braking easier.",
                0.5f, 3f, 0.1f, settings.brakeCurve,
                { oneDecimal(it) },
            ) { settings.brakeCurve = it })
        }

        // Buttons and feedback
        section("Buttons and feedback").apply {
            addView(toggle("Swap volume keys", "Volume up shifts down, volume down shifts up.", settings.volumeSwap) {
                settings.volumeSwap = it
            })
            addView(Ui.divider(context))
            addView(toggle("Haptic tick", "A short tick on every gear shift.", settings.haptics) {
                settings.haptics = it
            })
            addView(Ui.divider(context))
            addView(toggle("Rumble", "Vibrate with the force feedback the game sends to the hub.", settings.rumble) {
                settings.rumble = it
            })
            addView(Ui.divider(context))
            addView(labels())
        }

        // Controller mode
        section(getString(R.string.settings_controller)).apply {
            addView(volumeKey(getString(R.string.volume_up_key), settings.padVolumeUp) { settings.padVolumeUp = it })
            addView(Ui.divider(context))
            addView(volumeKey(getString(R.string.volume_down_key), settings.padVolumeDown) { settings.padVolumeDown = it })
            addView(Ui.divider(context))
            addView(Ui.row(context, getString(R.string.profiles), getString(R.string.settings_profiles_hint)).apply {
                setOnClickListener { startActivity(Intent(context, ProfilesActivity::class.java)) }
            })
        }

        // Link
        section("Link").apply {
            val modes = listOf(LinkMode.MULTIPATH, LinkMode.WIFI, LinkMode.USB, LinkMode.BLUETOOTH)
            addView(choice(
                "Connection", "Multipath sends every packet on Wi-Fi and USB; the first to arrive wins.",
                listOf("Both", "Wi-Fi", "USB", "BT"),
                modes.indexOf(settings.mode).coerceAtLeast(0),
            ) { settings.mode = modes[it] })
            addView(Ui.divider(context))
            val rates = Slp.RATES_HZ.toList()
            addView(choice(
                "Send rate when idle", "Changes are sent at once; this is the repeat rate that covers packet loss.",
                rates.map { "$it Hz" },
                rates.indexOf(settings.rateHz).coerceAtLeast(0),
            ) { settings.rateHz = rates[it] })
        }

        // About
        section("About").apply {
            addView(Ui.row(
                context,
                "Slipstream Wheel " + BuildConfig.VERSION_NAME,
                "Protocol SLP/1. Nothing leaves your local network: no account, no cloud, no telemetry.",
            ))
            addView(Ui.divider(context))
            addView(action("Reset driving settings", "Steering, pedals, buttons and link back to defaults. Pairing is kept.") {
                settings.resetDriving()
                populate()
            })
        }
    }

    // ---------------------------------------------------------------- builders

    private fun section(name: String): LinearLayout {
        column.addView(Ui.sectionLabel(this, name))
        val panel = Ui.panel(this)
        column.addView(panel)
        return panel
    }

    private fun padded(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(Ui.dp(context, 16f), Ui.dp(context, 14f), Ui.dp(context, 16f), Ui.dp(context, 14f))
    }

    private fun titleLine(title: String, value: TextView?): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(Ui.text(context, title, 16f, R.color.text, bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (value != null) addView(value)
    }

    private fun description(text: String): TextView =
        Ui.text(this, text, 13.5f, R.color.text_muted).apply { layoutParams = Ui.vertical(context, top = 2f) }

    private fun slider(
        title: String,
        detail: String,
        min: Float,
        max: Float,
        step: Float,
        current: Float,
        format: (Float) -> String,
        onChange: (Float) -> Unit,
    ): View {
        val steps = ((max - min) / step).roundToInt()
        val value = Ui.text(this, format(current), 15f, R.color.accent, bold = true)
        return padded().apply {
            addView(titleLine(title, value))
            addView(description(detail))
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
            }, Ui.vertical(this@SettingsActivity, top = 6f))
        }
    }

    private fun toggle(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit): View {
        val sw = Switch(this).apply {
            isChecked = checked
            Ui.tintSwitch(context, this)
            setOnCheckedChangeListener { _, on -> onChange(on) }
        }
        return Ui.row(this, title, detail, end = sw).apply {
            setOnClickListener { sw.toggle() }
        }
    }

    private fun choice(title: String, detail: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit): View =
        padded().apply {
            addView(titleLine(title, null))
            addView(description(detail))
            addView(Ui.segmented(context, options, selected, onSelect), Ui.vertical(this@SettingsActivity, top = 10f))
        }

    /** A volume key's button in controller mode: not mapped (the volume changes) or any canonical button. */
    private fun volumeKey(title: String, current: Int, onPick: (Int) -> Unit): View {
        val badge = Ui.badge(this, mappingName(current), accent = current != Settings.UNMAPPED)
        return Ui.row(this, title, getString(R.string.volume_key_hint), end = badge).apply {
            setOnClickListener {
                val names = arrayOf(getString(R.string.not_mapped)) + Array(PadButton.COUNT) { PadButton.bothNames(it) }
                val checked = if (current == Settings.UNMAPPED) 0 else current + 1
                AlertDialog.Builder(context)
                    .setTitle(title)
                    .setSingleChoiceItems(names, checked) { dialog, which ->
                        onPick(if (which == 0) Settings.UNMAPPED else which - 1)
                        dialog.dismiss()
                        populate()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }
    }

    private fun mappingName(bit: Int): String =
        if (bit == Settings.UNMAPPED) getString(R.string.not_mapped) else PadButton.bothNames(bit)

    private fun action(title: String, detail: String, onClick: () -> Unit): View =
        Ui.row(this, title, detail, end = Ui.badge(this, "Reset", accent = false)).apply {
            setOnClickListener { onClick() }
        }

    private fun labels(): View = padded().apply {
        addView(titleLine("Button labels", null))
        addView(description("The four held buttons on the drive screen. Map them in the game."))
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        for (i in 0 until 4) {
            val input = EditText(context).apply {
                setText(settings.buttonLabel(i))
                filters = arrayOf(InputFilter.LengthFilter(Settings.LABEL_MAX), InputFilter.AllCaps())
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                isSingleLine = true
                gravity = Gravity.CENTER
                setTextColor(Ui.color(context, R.color.text))
                setBackgroundResource(R.drawable.bg_input)
                minHeight = Ui.dp(context, 48f)
                contentDescription = "Button " + (i + 1) + " label"
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                    override fun afterTextChanged(s: Editable?) {
                        settings.setButtonLabel(i, s?.toString().orEmpty())
                    }
                })
            }
            row.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i > 0) marginStart = Ui.dp(this@SettingsActivity, 8f)
            })
        }
        addView(row, Ui.vertical(this@SettingsActivity, top = 10f))
    }

    private fun oneDecimal(v: Float): String {
        val sb = StringBuilder(8)
        return Fmt.fixed(sb, v.toDouble(), 1).toString()
    }
}
