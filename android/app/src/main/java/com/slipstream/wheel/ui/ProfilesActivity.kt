package com.slipstream.wheel.ui

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.slipstream.wheel.R
import com.slipstream.wheel.pad.PadStyle
import com.slipstream.wheel.pad.layout.LayoutStore
import com.slipstream.wheel.pad.layout.PadLayout

/**
 * Controller profiles (ARCHITECTURE.md 7.1): tap a profile to use it. The built-ins,
 * PlayStation and Xbox, can be edited, duplicated and reset, never deleted or renamed; your
 * own profiles can be edited, duplicated, renamed and deleted. The choice is remembered.
 */
class ProfilesActivity : ComponentActivity() {
    private lateinit var store: LayoutStore
    private lateinit var column: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        store = LayoutStore.open(this)
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
        setContentView(scroll)
    }

    override fun onStart() {
        super.onStart()
        populate() // back from the editor: names and layouts may have changed
    }

    private fun populate() {
        column.removeAllViews()
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageButton(context).apply {
                setImageResource(R.drawable.ic_back)
                contentDescription = getString(R.string.back)
                setBackgroundResource(R.drawable.bg_row)
                setOnClickListener { finish() }
            }, LinearLayout.LayoutParams(Ui.dp(this@ProfilesActivity, 48f), Ui.dp(this@ProfilesActivity, 48f)))
            addView(Ui.title(context, getString(R.string.profiles)).apply {
                setPadding(Ui.dp(context, 8f), 0, 0, 0)
                textSize = 24f
            })
        }
        column.addView(header, Ui.vertical(this, top = 8f))

        val all = store.all()
        val current = store.lastUsedId
        section(getString(R.string.profiles_built_in)).apply {
            all.filter { it.builtIn }.forEachIndexed { i, p ->
                if (i > 0) addView(Ui.divider(context))
                addView(row(p, p.id == current))
            }
        }
        section(getString(R.string.profiles_custom)).apply {
            val mine = all.filter { !it.builtIn }
            if (mine.isEmpty()) {
                addView(Ui.body(context, getString(R.string.profiles_empty), muted = true).apply {
                    val p = Ui.dp(context, 16f)
                    setPadding(p, p, p, p)
                })
            }
            mine.forEachIndexed { i, p ->
                if (i > 0) addView(Ui.divider(context))
                addView(row(p, p.id == current))
            }
        }
    }

    private fun section(name: String): LinearLayout {
        column.addView(Ui.sectionLabel(this, name))
        val panel = Ui.panel(this)
        column.addView(panel)
        return panel
    }

    private fun row(p: PadLayout, inUse: Boolean): View {
        val more = Ui.secondaryButton(this, getString(R.string.more)).apply {
            setOnClickListener { actions(p) }
        }
        val detail = styleName(p.style) + if (inUse) "  |  " + getString(R.string.in_use) else ""
        return Ui.row(this, p.name, detail, end = more, start = Ui.radio(this, inUse)).apply {
            setOnClickListener {
                store.lastUsedId = p.id
                populate()
            }
        }
    }

    private fun actions(p: PadLayout) {
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        labels.add(getString(R.string.use_profile)); actions.add { store.lastUsedId = p.id; populate() }
        labels.add(getString(R.string.edit_layout)); actions.add { edit(p) }
        labels.add(getString(R.string.duplicate)); actions.add { store.duplicate(p.id)?.let { store.lastUsedId = it.id }; populate() }
        if (p.builtIn) {
            labels.add(getString(R.string.reset)); actions.add { confirm(getString(R.string.reset_confirm, p.name)) { store.reset(p.id); populate() } }
        } else {
            labels.add(getString(R.string.rename)); actions.add { rename(p) }
            labels.add(getString(R.string.reset)); actions.add { confirm(getString(R.string.reset_confirm, p.name)) { store.reset(p.id); populate() } }
            labels.add(getString(R.string.delete)); actions.add { confirm(getString(R.string.delete_confirm, p.name)) { store.delete(p.id); populate() } }
        }
        AlertDialog.Builder(this)
            .setTitle(p.name)
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun edit(p: PadLayout) {
        store.lastUsedId = p.id
        startActivity(Intent(this, PadEditorActivity::class.java).putExtra(PadEditorActivity.EXTRA_PROFILE, p.id))
    }

    private fun confirm(message: String, onYes: () -> Unit) {
        AlertDialog.Builder(this)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok) { _, _ -> onYes() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun rename(p: PadLayout) {
        val input = EditText(this).apply {
            setText(p.name)
            setSelection(text.length)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            filters = arrayOf(InputFilter.LengthFilter(LayoutStore.NAME_MAX))
            isSingleLine = true
            setTextColor(Ui.color(context, R.color.text))
            setBackgroundResource(R.drawable.bg_input)
            val pad = Ui.dp(context, 14f)
            setPadding(pad, Ui.dp(context, 12f), pad, Ui.dp(context, 12f))
            minHeight = Ui.dp(context, 48f)
        }
        val box = FrameLayout(this).apply {
            addView(input, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                val m = Ui.dp(context, 22f)
                setMargins(m, Ui.dp(context, 8f), m, 0)
            })
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.rename_title)
            .setView(box)
            .setPositiveButton(R.string.rename) { _, _ ->
                store.rename(p.id, input.text.toString())
                populate()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun styleName(style: PadStyle): String =
        getString(if (style == PadStyle.PLAYSTATION) R.string.style_ps else R.string.style_xbox)
}
