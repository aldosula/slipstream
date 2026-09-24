package com.slipstream.wheel.ui

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.slipstream.wheel.LinkMode
import com.slipstream.wheel.R
import com.slipstream.wheel.Settings
import com.slipstream.wheel.hid.GamepadSupport
import com.slipstream.wheel.link.BeaconListener
import com.slipstream.wheel.link.DiscoveredHub
import com.slipstream.wheel.link.HostPicker
import com.slipstream.wheel.protocol.PairUri
import com.slipstream.wheel.protocol.PairingKey

/**
 * Start screen: pairing (QR or typed code), hubs discovered by beacon, connection mode,
 * and the Drive button. A hub whose beacon fingerprint matches the stored key is picked
 * automatically.
 */
class ConnectActivity : ComponentActivity() {
    private lateinit var settings: Settings
    private lateinit var beacons: BeaconListener
    private var hubs: List<DiscoveredHub> = emptyList()
    private var chosenAddress: String? = null

    private lateinit var pairTitle: TextView
    private lateinit var pairDetail: TextView
    private lateinit var forgetButton: Button
    private lateinit var hubPanel: LinearLayout
    private lateinit var modePanel: LinearLayout
    private lateinit var driveButton: Button
    private lateinit var driveHint: TextView

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let { onScanned(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        beacons = BeaconListener(this) { list ->
            hubs = list
            rememberPairedHub()
            renderHubs()
            renderDrive()
        }
        setContentView(buildContent())
    }

    override fun onStart() {
        super.onStart()
        beacons.setPairing(settings.pairingKey())
        beacons.start()
        renderAll()
    }

    override fun onStop() {
        beacons.stop()
        super.onStop()
    }

    // ---------------------------------------------------------------- layout

    private fun buildContent(): ViewGroup {
        val gutter = resources.getDimensionPixelSize(R.dimen.gutter)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.color(context, R.color.bg))
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(gutter, Ui.dp(context, 12f), gutter, Ui.dp(context, 24f))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val titles = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(Ui.title(context, getString(R.string.app_name)))
                addView(Ui.body(context, getString(R.string.tagline), muted = true).apply {
                    layoutParams = Ui.vertical(context, top = 4f)
                })
            }
            addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(ImageButton(context).apply {
                setImageResource(R.drawable.ic_settings)
                contentDescription = getString(R.string.settings)
                setBackgroundResource(R.drawable.bg_button_secondary)
                setOnClickListener { startActivity(Intent(context, SettingsActivity::class.java)) }
            }, LinearLayout.LayoutParams(Ui.dp(this@ConnectActivity, 52f), Ui.dp(this@ConnectActivity, 52f)))
        }
        column.addView(header, Ui.vertical(this, top = 12f))

        // Pairing
        column.addView(Ui.sectionLabel(this, getString(R.string.section_pairing)))
        val pairPanel = Ui.panel(this).apply {
            setPadding(Ui.dp(context, 16f), Ui.dp(context, 16f), Ui.dp(context, 16f), Ui.dp(context, 16f))
        }
        pairTitle = Ui.text(this, "", 17f, R.color.text, bold = true)
        pairDetail = Ui.body(this, "", muted = true).apply { layoutParams = Ui.vertical(context, top = 4f) }
        pairPanel.addView(pairTitle)
        pairPanel.addView(pairDetail)
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = Ui.vertical(context, top = 14f)
        }
        val scan = Ui.secondaryButton(this, getString(R.string.scan_qr)).apply { setOnClickListener { startScan() } }
        val code = Ui.secondaryButton(this, getString(R.string.enter_code)).apply { setOnClickListener { showCodeDialog(null) } }
        forgetButton = Ui.secondaryButton(this, getString(R.string.forget)).apply {
            setOnClickListener {
                settings.pairCode = null
                settings.hubName = null
                settings.hubHosts = emptyList()
                beacons.setPairing(null)
                renderAll()
            }
        }
        actions.addView(scan, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actions.addView(code, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = Ui.dp(this@ConnectActivity, 10f)
        })
        actions.addView(forgetButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = Ui.dp(this@ConnectActivity, 10f)
        })
        pairPanel.addView(actions)
        column.addView(pairPanel)

        // Hubs
        column.addView(Ui.sectionLabel(this, getString(R.string.section_hubs)))
        hubPanel = Ui.panel(this)
        column.addView(hubPanel)

        // Connection mode
        column.addView(Ui.sectionLabel(this, getString(R.string.section_connection)))
        modePanel = Ui.panel(this)
        column.addView(modePanel)

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(column)
        }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Bottom bar: the one primary action.
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.color(context, R.color.surface))
            setPadding(gutter, Ui.dp(context, 12f), gutter, Ui.dp(context, 16f))
        }
        driveHint = Ui.text(this, "", 13.5f, R.color.text_muted).apply { gravity = Gravity.CENTER_HORIZONTAL }
        driveButton = Ui.primaryButton(this, getString(R.string.drive)).apply { setOnClickListener { startDrive() } }
        bar.addView(driveHint, Ui.vertical(this, bottom = 10f))
        bar.addView(driveButton, Ui.vertical(this))
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, 0)
            bar.setPadding(gutter, Ui.dp(this, 12f), gutter, Ui.dp(this, 16f) + bars.bottom)
            insets
        }
        return root
    }

    // ---------------------------------------------------------------- render

    private fun renderAll() {
        renderPairing()
        renderHubs()
        renderModes()
        renderDrive()
    }

    private fun renderPairing() {
        val key = settings.pairingKey()
        if (key == null) {
            pairTitle.text = getString(R.string.not_paired)
            pairDetail.text = getString(R.string.not_paired_detail)
            forgetButton.visibility = android.view.View.GONE
        } else {
            val name = settings.hubName ?: key.display
            pairTitle.text = getString(R.string.paired_with, name)
            pairDetail.text = getString(R.string.paired_detail, key.fingerprintShort)
            forgetButton.visibility = android.view.View.VISIBLE
        }
    }

    private fun renderHubs() {
        hubPanel.removeAllViews()
        if (hubs.isEmpty()) {
            hubPanel.addView(Ui.body(this, getString(R.string.hubs_searching), muted = true).apply {
                setPadding(Ui.dp(context, 16f), Ui.dp(context, 16f), Ui.dp(context, 16f), Ui.dp(context, 16f))
            })
            return
        }
        val active = activeHub()
        hubs.forEachIndexed { i, hub ->
            if (i > 0) hubPanel.addView(Ui.divider(this))
            val badge = when {
                hub.paired && hub.address == active?.address -> Ui.badge(this, getString(R.string.hub_selected), accent = true)
                hub.paired -> Ui.badge(this, getString(R.string.hub_paired), accent = true)
                settings.pairingKey() != null -> Ui.badge(this, getString(R.string.hub_other_code), accent = false)
                else -> null
            }
            val row = Ui.row(this, hub.name, hub.address, end = badge, start = Ui.radio(this, hub.address == active?.address))
            row.setOnClickListener {
                if (hub.paired) {
                    chosenAddress = hub.address
                    rememberPairedHub()
                    renderHubs()
                    renderDrive()
                } else {
                    showCodeDialog(hub)
                }
            }
            hubPanel.addView(row)
        }
    }

    private fun renderModes() {
        modePanel.removeAllViews()
        val current = settings.mode
        val entries = listOf(
            Triple(LinkMode.MULTIPATH, R.string.mode_multipath, R.string.mode_multipath_hint),
            Triple(LinkMode.WIFI, R.string.mode_wifi, R.string.mode_wifi_hint),
            Triple(LinkMode.USB, R.string.mode_usb, R.string.mode_usb_hint),
            Triple(LinkMode.BLUETOOTH, R.string.mode_bluetooth, R.string.mode_bluetooth_hint),
        )
        entries.forEachIndexed { i, (mode, title, hint) ->
            if (i > 0) modePanel.addView(Ui.divider(this))
            val badge = when (mode) {
                LinkMode.MULTIPATH -> Ui.badge(this, getString(R.string.recommended), accent = false)
                LinkMode.BLUETOOTH -> Ui.badge(this, getString(R.string.experimental), accent = false)
                else -> null
            }
            val row = Ui.row(this, getString(title), getString(hint), end = badge, start = Ui.radio(this, mode == current))
            row.setOnClickListener {
                settings.mode = mode
                renderModes()
                renderDrive()
            }
            modePanel.addView(row)
        }
    }

    private fun renderDrive() {
        val mode = settings.mode
        val key = settings.pairingKey()
        val host = targetHost()
        val (enabled, hint) = when {
            mode == LinkMode.BLUETOOTH && !GamepadSupport.isApiSupported -> false to getString(R.string.bt_unsupported)
            mode == LinkMode.BLUETOOTH -> true to getString(R.string.drive_hint_bt)
            key == null -> false to getString(R.string.drive_hint_need_code)
            mode == LinkMode.USB -> true to getString(R.string.drive_hint_usb)
            host == null && mode == LinkMode.WIFI -> false to getString(R.string.drive_hint_need_host)
            host == null -> true to getString(R.string.drive_hint_multipath_usb_only)
            else -> true to getString(R.string.drive_hint_target, host)
        }
        driveButton.isEnabled = enabled
        driveHint.text = hint
    }

    // ---------------------------------------------------------------- target

    private fun activeHub(): DiscoveredHub? {
        val paired = hubs.filter { it.paired }
        return paired.firstOrNull { it.address == chosenAddress } ?: paired.firstOrNull()
    }

    /** Beacon source address first, then the best QR address. */
    private fun targetHost(): String? =
        activeHub()?.address ?: HostPicker.best(settings.hubHosts, HostPicker.phoneAddresses())

    /** Auto-connect: a matching beacon updates the stored name, address and ports. */
    private fun rememberPairedHub() {
        val hub = activeHub() ?: return
        settings.hubName = hub.name
        settings.hubHosts = listOf(hub.address) + settings.hubHosts.filter { it != hub.address }
        settings.udpPort = hub.udpPort
        settings.tcpPort = hub.tcpPort
        renderPairing()
    }

    private fun startDrive() {
        val intent = Intent(this, DriveActivity::class.java)
        if (settings.mode != LinkMode.BLUETOOTH) {
            intent.putExtra(DriveActivity.EXTRA_HOST, targetHost())
            intent.putExtra(DriveActivity.EXTRA_UDP_PORT, settings.udpPort)
            intent.putExtra(DriveActivity.EXTRA_TCP_PORT, settings.tcpPort)
        }
        startActivity(intent)
    }

    // ---------------------------------------------------------------- pairing

    private fun startScan() {
        val options = ScanOptions()
            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setPrompt(getString(R.string.qr_prompt))
            .setBeepEnabled(false)
            .setOrientationLocked(false)
        scanLauncher.launch(options)
    }

    private fun onScanned(text: String) {
        val info = PairUri.parse(text)
        if (info == null) {
            Toast.makeText(this, R.string.qr_invalid, Toast.LENGTH_LONG).show()
            return
        }
        settings.pairCode = info.code
        settings.hubName = info.name
        settings.hubHosts = HostPicker.rank(info.hosts, HostPicker.phoneAddresses())
        settings.udpPort = info.udpPort
        settings.tcpPort = info.tcpPort
        beacons.setPairing(settings.pairingKey())
        Toast.makeText(this, getString(R.string.paired_toast, info.name), Toast.LENGTH_SHORT).show()
        renderAll()
    }

    private fun showCodeDialog(forHub: DiscoveredHub?) {
        val input = EditText(this).apply {
            hint = getString(R.string.code_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_DONE
            filters = arrayOf(InputFilter.AllCaps(), InputFilter.LengthFilter(24))
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 20f
            letterSpacing = 0.05f
            setTextColor(Ui.color(context, R.color.text))
            setBackgroundResource(R.drawable.bg_input)
            setPadding(Ui.dp(context, 14f), Ui.dp(context, 12f), Ui.dp(context, 14f), Ui.dp(context, 12f))
            isSingleLine = true
        }
        val error = Ui.text(this, "", 13.5f, R.color.accent).apply { visibility = android.view.View.GONE }
        val box = FrameLayout(this).apply {
            val col = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(input, Ui.vertical(context))
                addView(error, Ui.vertical(context, top = 8f))
            }
            addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                val m = Ui.dp(context, 22f)
                setMargins(m, Ui.dp(context, 8f), m, 0)
            })
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.code_title)
            .setMessage(R.string.code_message)
            .setView(box)
            .setPositiveButton(R.string.pair, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val key = PairingKey.fromInput(input.text.toString())
                if (key == null) {
                    error.text = getString(R.string.code_invalid)
                    error.visibility = android.view.View.VISIBLE
                    return@setOnClickListener
                }
                if (settings.pairCode != key.code) {
                    // A different hub: the stored name and addresses belonged to the old one.
                    settings.hubName = null
                    settings.hubHosts = emptyList()
                }
                settings.pairCode = key.code
                if (forHub != null) {
                    val fp = BeaconListener.hexToBytes(forHub.fingerprintHex)
                    if (key.matches(fp)) {
                        chosenAddress = forHub.address
                    } else {
                        Toast.makeText(this, getString(R.string.code_mismatch, forHub.name), Toast.LENGTH_LONG).show()
                    }
                }
                beacons.setPairing(key)
                dialog.dismiss()
                renderAll()
            }
            input.requestFocus()
        }
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
    }
}
