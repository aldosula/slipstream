package com.slipstream.wheel.ui

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.slipstream.wheel.DriveOrientation
import com.slipstream.wheel.LinkMode
import com.slipstream.wheel.R
import com.slipstream.wheel.Settings
import com.slipstream.wheel.hid.BluetoothGamepad
import com.slipstream.wheel.hid.GamepadStatus
import com.slipstream.wheel.hid.GamepadSupport
import com.slipstream.wheel.input.ControllerState
import com.slipstream.wheel.input.PulseCounters
import com.slipstream.wheel.input.SteeringSource
import com.slipstream.wheel.link.BeaconListener
import com.slipstream.wheel.link.DiscoveredHub
import com.slipstream.wheel.link.LinkConfig
import com.slipstream.wheel.link.LinkEngine
import com.slipstream.wheel.link.LinkStats
import com.slipstream.wheel.link.NetworkBinder
import com.slipstream.wheel.link.WifiLatencyLock
import com.slipstream.wheel.protocol.PairUri
import com.slipstream.wheel.protocol.Slp
import java.net.InetAddress

/**
 * The drive screen: fixed landscape, immersive, screen kept on, sustained performance mode
 * where the phone has it. Owns the session: controller state, steering sensor, and either
 * the network link (Wi-Fi, USB or both) or the Bluetooth gamepad.
 *
 * The link runs from onStart to onStop; while the activity is not resumed every packet
 * carries PAUSED, so the hub outputs neutral. Volume keys are consumed here and become
 * shift pulses.
 *
 * With Wi-Fi on, the beacons of the paired hub are followed while driving: when the Wi-Fi
 * path has been silent for [RETARGET_AFTER_NS] (no hub address at start, or the PC got a new
 * address) it moves to the address the paired hub's beacon comes from.
 */
class DriveActivity : ComponentActivity() {
    private lateinit var settings: Settings
    private lateinit var mode: LinkMode
    private val state = ControllerState()
    private lateinit var surface: DriveSurfaceView
    private lateinit var steering: SteeringSource
    private lateinit var haptics: Haptics
    private var engine: LinkEngine? = null
    private var wifiLock: WifiLatencyLock? = null
    private var networkBinder: NetworkBinder? = null
    private var driveBeacons: BeaconListener? = null
    private var driveHubs: List<DiscoveredHub> = emptyList()
    private var lastRetargetNs = 0L
    private var gamepad: Any? = null // BluetoothGamepad, only created on API 28+

    private var btPanel: LinearLayout? = null
    private var btText: TextView? = null
    private var btAction: Button? = null
    private var btStatus: GamepadStatus = GamepadStatus.STOPPED
    private var btHost: String? = null
    private var askedPermission = false

    private val ui = Handler(Looper.getMainLooper())
    private val snapshot = LinkStats.Snapshot(LinkEngine.TRANSPORT_SLOTS)
    private val line1 = StringBuilder(64)
    private val line2 = StringBuilder(64)

    private val statusTick = object : Runnable {
        override fun run() {
            updateStatus()
            ui.postDelayed(this, STATUS_PERIOD_MS)
        }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { restartGamepad() }
    private val discoverableLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }
    private val enableLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { restartGamepad() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        mode = settings.mode
        haptics = Haptics(this)

        requestedOrientation = when (settings.orientation) {
            DriveOrientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            DriveOrientation.REVERSE_LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val power = getSystemService(PowerManager::class.java)
        if (power?.isSustainedPerformanceModeSupported == true) window.setSustainedPerformanceMode(true)

        surface = DriveSurfaceView(
            this,
            state,
            DriveSurfaceView.Config(
                pedalMode = settings.pedalMode,
                travelFraction = settings.travelPct / 100f,
                swapZones = settings.swapZones,
                throttleCurve = settings.throttleCurve,
                brakeCurve = settings.brakeCurve,
                lockDeg = settings.lockDeg,
                labels = Array(4) { settings.buttonLabel(it) },
            ),
        )
        surface.listener = object : DriveSurfaceView.Listener {
            override fun onShift(up: Boolean) = shift(up, fromKey = false)
            override fun onRecenter() {
                steering.recenter()
                if (settings.haptics) haptics.tick()
            }
        }
        val root = FrameLayout(this)
        root.addView(surface, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        ViewCompat.setOnApplyWindowInsetsListener(surface) { _, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            surface.setSafeInsets(safe.left, safe.top, safe.right, safe.bottom)
            insets
        }
        setContentView(root)

        steering = SteeringSource(this, state, settings.steeringConfig())

        if (mode == LinkMode.BLUETOOTH) {
            buildBluetoothPanel(root)
        } else {
            val key = settings.pairingKey()
            if (key == null) {
                finish()
                return
            }
            val hostText = intent.getStringExtra(EXTRA_HOST)
            // Only numeric IPv4 literals are accepted, so this never touches DNS.
            val host = hostText?.takeIf { PairUri.isIpv4(it) }?.let { InetAddress.getByName(it) }
            val useWifi = mode == LinkMode.WIFI || mode == LinkMode.MULTIPATH
            val useUsb = mode == LinkMode.USB || mode == LinkMode.MULTIPATH
            val link = LinkEngine(
                state,
                LinkConfig(
                    key = key.key,
                    host = host,
                    udpPort = intent.getIntExtra(EXTRA_UDP_PORT, Slp.PORT_UDP),
                    tcpPort = intent.getIntExtra(EXTRA_TCP_PORT, Slp.PORT_TCP),
                    useWifi = useWifi,
                    useUsb = useUsb,
                    rateHz = settings.rateHz,
                ),
                extraSink = if (settings.rumble) haptics.rumbleSink else null,
            )
            engine = link
            if (useWifi) {
                // The Wi-Fi path exists even without a host yet: a beacon can supply it.
                wifiLock = WifiLatencyLock(this)
                networkBinder = NetworkBinder(this) { link.onNetworkChanged() }
                driveBeacons = BeaconListener(this) { list ->
                    driveHubs = list
                    maybeRetargetWifi()
                }.also { it.setPairing(key) }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        steering.rotation = currentRotation()
        steering.start()
        // Before the link opens its sockets: route to the hub over the network that reaches
        // it, and keep that routing right while networks come and go.
        networkBinder?.start(engine?.wifiTarget?.address)
        lastRetargetNs = 0L
        engine?.start()
        wifiLock?.acquire()
        driveBeacons?.start()
        startGamepad()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        steering.rotation = currentRotation()
        state.setFlag(Slp.FLAG_PAUSED, false)
        ui.post(statusTick)
    }

    override fun onPause() {
        state.setFlag(Slp.FLAG_PAUSED, true)
        surface.releaseAllPointers()
        ui.removeCallbacks(statusTick)
        settings.steerCenterRad = steering.center.toFloat()
        super.onPause()
    }

    override fun onStop() {
        engine?.stop() // sends PAUSED first, over the current routing
        driveBeacons?.stop()
        networkBinder?.stop()
        steering.stop()
        wifiLock?.release()
        stopGamepad()
        haptics.stopRumble()
        super.onStop()
    }

    override fun onDestroy() {
        haptics.release()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemBars()
            steering.rotation = currentRotation()
            // Back from the quick settings shade or a system dialog: Bluetooth may be on now.
            if (btStatus == GamepadStatus.BLUETOOTH_OFF) restartGamepad()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        steering.rotation = currentRotation()
    }

    // ------------------------------------------------------------ keys

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event.repeatCount == 0) {
                val up = (keyCode == KeyEvent.KEYCODE_VOLUME_UP) != settings.volumeSwap
                shift(up, fromKey = true)
            }
            return true // consumed: the phone volume does not change
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) return true
        return super.onKeyUp(keyCode, event)
    }

    private fun shift(up: Boolean, fromKey: Boolean) {
        state.pulse(if (up) PulseCounters.SHIFT_UP else PulseCounters.SHIFT_DOWN)
        if (settings.haptics) haptics.tick()
        if (fromKey) surface.flashPaddle(up)
    }

    // ------------------------------------------------------------ window

    private fun hideSystemBars() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun currentRotation(): Int {
        val d = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay
        }
        return d?.rotation ?: 1
    }

    // ------------------------------------------------------------ status chip

    private fun updateStatus() {
        maybeRetargetWifi()
        line1.setLength(0)
        line2.setLength(0)
        val live: Boolean
        val e = engine
        if (e != null) {
            val now = System.nanoTime()
            e.stats.snapshot(snapshot, now)
            val wifi = snapshot.state[LinkEngine.SLOT_WIFI]
            val usb = snapshot.state[LinkEngine.SLOT_USB]
            live = snapshot.liveCount > 0
            when {
                snapshot.liveCount == 2 -> line1.append("USB + WI-FI")
                usb == LinkStats.TransportState.LIVE -> line1.append("USB")
                wifi == LinkStats.TransportState.LIVE -> line1.append("WI-FI")
                snapshot.hubTracksOtherEpoch -> line1.append("HUB BUSY")
                snapshot.openCount > 0 -> line1.append("WAITING FOR HUB")
                else -> line1.append("CONNECTING")
            }
            if (live) {
                line1.append("   ")
                Fmt.fixed(line1, snapshot.linkRttUs / 1000.0, 1).append(" ms")
                val loss = snapshot.lossLastSecond
                line1.append("   ")
                Fmt.fixed(line1, loss * 100.0, 1) // "--" until a full second has been measured
                line1.append("% loss")
            }
            when {
                snapshot.outputError -> line2.append("hub output error")
                !live -> line2.append(if (e.hasUsb && !e.hasWifi) "adb reverse on port 47802" else "is Slipstream Hub running?")
                snapshot.output == Slp.OUTPUT_VJOY -> line2.append("vJoy")
                snapshot.output == Slp.OUTPUT_X360 -> line2.append("Xbox 360")
                else -> line2.append("no output device")
            }
        } else {
            live = btStatus == GamepadStatus.CONNECTED
            line1.append("BLUETOOTH")
            line2.append(if (live) (btHost ?: "connected") else "not connected")
        }
        line2.append("  |  ")
        when (steering.kind) {
            SteeringSource.Kind.ROTATION_VECTOR -> line2.append("gyro ")
            SteeringSource.Kind.GRAVITY -> line2.append("gravity ")
            SteeringSource.Kind.ACCELEROMETER -> line2.append("accel ")
            SteeringSource.Kind.NONE -> line2.append("no sensor")
        }
        if (steering.kind != SteeringSource.Kind.NONE) line2.append(steering.rateHz).append(" Hz")
        surface.setStatus(line1, line2, live)
    }

    // ------------------------------------------------------------ Wi-Fi target

    /**
     * Moves the Wi-Fi path to the paired hub's beacon address when the current target has
     * been silent for [RETARGET_AFTER_NS]. A working target is never switched, so a PC that
     * beacons from two interfaces does not make the path flap.
     */
    private fun maybeRetargetWifi() {
        val e = engine ?: return
        if (!e.isRunning || !e.hasWifi || driveHubs.isEmpty()) return
        val now = System.nanoTime()
        val current = e.wifiTarget
        if (current != null && e.wifiSilentNs(now) < RETARGET_AFTER_NS) return
        if (lastRetargetNs != 0L && now - lastRetargetNs < RETARGET_AFTER_NS) return
        val currentHost = current?.address?.hostAddress
        for (hub in driveHubs) {
            if (!hub.paired || !PairUri.isIpv4(hub.address)) continue
            if (current != null && hub.address == currentHost && hub.udpPort == current.port) continue
            val address = InetAddress.getByName(hub.address) // numeric literal: no DNS
            e.setWifiTarget(address, hub.udpPort)
            networkBinder?.setHost(address)
            settings.hubHosts = listOf(hub.address) + settings.hubHosts.filter { it != hub.address }
            lastRetargetNs = now
            return
        }
    }

    // ------------------------------------------------------------ Bluetooth

    private fun buildBluetoothPanel(root: FrameLayout) {
        val pad = Ui.dp(this, 16f)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_panel)
            setPadding(pad, pad, pad, pad)
            addView(Ui.text(context, getString(R.string.bt_title), 16f, R.color.text, bold = true))
            addView(Ui.badge(context, getString(R.string.experimental), accent = false).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = Ui.dp(context, 6f) }
            })
        }
        val text = Ui.body(this, "", muted = true).apply { layoutParams = Ui.vertical(context, top = 10f) }
        val action = Ui.secondaryButton(this, "").apply {
            layoutParams = Ui.vertical(context, top = 12f)
            setOnClickListener { onBluetoothAction() }
        }
        panel.addView(text)
        panel.addView(action)
        root.addView(panel, FrameLayout.LayoutParams(Ui.dp(this, 340f), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        btPanel = panel
        btText = text
        btAction = action
        renderBluetooth()
    }

    private fun startGamepad() {
        if (mode != LinkMode.BLUETOOTH) return
        if (!GamepadSupport.isApiSupported) {
            onGamepadStatus(GamepadStatus.UNSUPPORTED, null)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val g = (gamepad as? BluetoothGamepad) ?: BluetoothGamepad(this, state) { s, host -> onGamepadStatus(s, host) }
            gamepad = g
            g.start()
        }
    }

    private fun stopGamepad() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) (gamepad as? BluetoothGamepad)?.stop()
    }

    private fun restartGamepad() {
        stopGamepad()
        startGamepad()
    }

    private fun onGamepadStatus(s: GamepadStatus, host: String?) {
        btStatus = s
        btHost = host
        renderBluetooth()
        if (s == GamepadStatus.NO_PERMISSION && !askedPermission) {
            askedPermission = true
            permissionLauncher.launch(GamepadSupport.requiredPermissions())
        }
    }

    private fun renderBluetooth() {
        val panel = btPanel ?: return
        val text = btText ?: return
        val action = btAction ?: return
        panel.visibility = if (btStatus == GamepadStatus.CONNECTED) View.GONE else View.VISIBLE
        text.text = when (btStatus) {
            GamepadStatus.UNSUPPORTED -> getString(R.string.bt_unsupported)
            GamepadStatus.NO_PERMISSION -> getString(R.string.bt_no_permission)
            GamepadStatus.BLUETOOTH_OFF -> getString(R.string.bt_off)
            GamepadStatus.WAITING_FOR_HOST -> getString(R.string.bt_waiting)
            GamepadStatus.CONNECTED -> getString(R.string.bt_connected, btHost ?: "PC")
            else -> getString(R.string.bt_starting)
        }
        when (btStatus) {
            GamepadStatus.NO_PERMISSION -> {
                action.visibility = View.VISIBLE
                action.text = getString(if (permissionBlocked()) R.string.bt_open_settings else R.string.bt_grant)
            }
            GamepadStatus.BLUETOOTH_OFF -> {
                action.visibility = View.VISIBLE
                action.text = getString(R.string.bt_turn_on)
            }
            GamepadStatus.WAITING_FOR_HOST -> {
                action.visibility = View.VISIBLE
                action.text = getString(R.string.bt_discoverable)
            }
            else -> action.visibility = View.GONE
        }
    }

    private fun onBluetoothAction() {
        when (btStatus) {
            GamepadStatus.NO_PERMISSION -> {
                if (permissionBlocked()) {
                    // "Don't ask again": the system no longer shows the prompt, only Settings can.
                    val details = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.fromParts("package", packageName, null))
                    try {
                        startActivity(details)
                    } catch (e: RuntimeException) {
                        // No settings screen on this build.
                    }
                } else {
                    askedPermission = true
                    permissionLauncher.launch(GamepadSupport.requiredPermissions())
                }
            }
            GamepadStatus.BLUETOOTH_OFF -> {
                try {
                    enableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                } catch (e: RuntimeException) {
                    // No enable dialog on this build (or no permission): the quick settings
                    // toggle works too, and focus coming back retries the gamepad.
                }
            }
            GamepadStatus.WAITING_FOR_HOST -> {
                val intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                    .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, DISCOVERABLE_SECONDS)
                try {
                    discoverableLauncher.launch(intent)
                } catch (e: RuntimeException) {
                    // No settings activity for it on this build: pairing from the PC still works
                    // while the Bluetooth settings screen is open on the phone.
                }
            }
            else -> Unit
        }
    }

    /** Asked before, still denied, and the system will not ask again. */
    private fun permissionBlocked(): Boolean = askedPermission && GamepadSupport.requiredPermissions().any {
        checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED && !shouldShowRequestPermissionRationale(it)
    }

    companion object {
        const val EXTRA_HOST = "com.slipstream.wheel.HOST"
        const val EXTRA_UDP_PORT = "com.slipstream.wheel.UDP_PORT"
        const val EXTRA_TCP_PORT = "com.slipstream.wheel.TCP_PORT"
        private const val STATUS_PERIOD_MS = 100L
        private const val DISCOVERABLE_SECONDS = 120

        /** A silent Wi-Fi path is moved to the paired hub's beacon address after this. */
        private const val RETARGET_AFTER_NS = 3_000_000_000L
    }
}
