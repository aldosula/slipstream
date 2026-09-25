package com.slipstream.wheel.ui

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.slipstream.wheel.DriveOrientation
import com.slipstream.wheel.R
import com.slipstream.wheel.Settings
import com.slipstream.wheel.link.LinkEngine
import com.slipstream.wheel.link.LinkStats
import com.slipstream.wheel.pad.ButtonHolds
import com.slipstream.wheel.pad.KeyHold
import com.slipstream.wheel.pad.MotionSource
import com.slipstream.wheel.pad.PadState
import com.slipstream.wheel.pad.PadStyle
import com.slipstream.wheel.pad.layout.LayoutStore
import com.slipstream.wheel.pad.layout.PadLayout
import com.slipstream.wheel.protocol.Slp

/**
 * The controller play screen (ARCHITECTURE.md 7.1): fixed landscape, immersive, screen kept
 * on, sustained performance mode where the phone has it. Owns the session: the pad state,
 * the hub link sending PAD packets, raw motion and gyro aim, haptics and rumble.
 *
 * PAUSED is sent whenever the screen is not resumed or the pause menu is open; then no touch
 * or key drives anything and the motion sensors are off. The link runs from onStart to
 * onStop, like the drive screen. Volume keys are left to the system unless the player mapped
 * them to a button in settings.
 */
class PadActivity : ComponentActivity(), PadSurfaceView.Listener, PauseMenu.Listener {
    private lateinit var settings: Settings
    private lateinit var store: LayoutStore
    private lateinit var layout: PadLayout
    private val state = PadState()
    private val holds = ButtonHolds(state)
    private val volumeUpHold = KeyHold(holds)
    private val volumeDownHold = KeyHold(holds)
    private lateinit var haptics: Haptics
    private lateinit var surface: PadSurfaceView
    private lateinit var menu: PauseMenu
    private var link: HubLink? = null
    private var motion: MotionSource? = null
    private var resumed = false
    private var volumeUpBit = Settings.UNMAPPED
    private var volumeDownBit = Settings.UNMAPPED

    private val ui = Handler(Looper.getMainLooper())
    private val snapshot = LinkStats.Snapshot(LinkEngine.TRANSPORT_SLOTS)
    private val chip = StringBuilder(96)
    private lateinit var chipBusy: String
    private lateinit var chipConnecting: String
    private lateinit var chipNoReply: String
    private lateinit var chipOutputError: String
    private var chipShown = false
    private var lastBadNs = 0L

    private val statusTick = object : Runnable {
        override fun run() {
            updateStatus()
            ui.postDelayed(this, STATUS_PERIOD_MS)
        }
    }

    private val back = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            if (menu.isShowing) onMenuResume() else openMenu()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        store = LayoutStore.open(this)
        intent.getStringExtra(EXTRA_PROFILE)?.let { store.lastUsedId = it }
        layout = store.current()
        haptics = Haptics(this)
        chipBusy = getString(R.string.chip_busy)
        chipConnecting = getString(R.string.chip_connecting)
        chipNoReply = getString(R.string.chip_no_reply)
        chipOutputError = getString(R.string.chip_output_error)

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

        surface = PadSurfaceView(this, state, holds, haptics)
        surface.listener = this
        menu = PauseMenu(this, this)
        menu.setProfiles { store.all() to layout.id }
        val root = FrameLayout(this)
        root.addView(surface, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(menu, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        ViewCompat.setOnApplyWindowInsetsListener(surface) { _, insets ->
            // The drawing area is the view minus visible system bars (none while immersive);
            // controls are kept clear of the cutout itself, not of its whole strip.
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val cut = insets.displayCutout?.boundingRects ?: emptyList()
            surface.setInsets(bars.left, bars.top, bars.right, bars.bottom, cut)
            insets
        }
        setContentView(root)
        onBackPressedDispatcher.addCallback(this, back)

        link = HubLink.create(this, settings, intent, state, if (settings.rumble) haptics.rumbleSink else null)
        if (link == null) {
            finish()
            return
        }
        applyLayout(layout)
    }

    override fun onStart() {
        super.onStart()
        // Back from the editor or the profile list: show what is saved now.
        val fresh = store.load(layout.id) ?: store.current()
        if (fresh != layout) applyLayout(fresh)
        volumeUpBit = settings.padVolumeUp
        volumeDownBit = settings.padVolumeDown
        link?.start()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        hideSystemBars()
        updatePaused()
        ui.post(statusTick)
    }

    override fun onPause() {
        resumed = false
        updatePaused()
        surface.releaseAllPointers()
        ui.removeCallbacks(statusTick)
        super.onPause()
    }

    override fun onStop() {
        link?.stop()
        motion?.stop()
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
            motion?.rotation = currentRotation()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        motion?.rotation = currentRotation()
    }

    // ------------------------------------------------------------ layout and motion

    private fun applyLayout(l: PadLayout) {
        layout = l
        store.lastUsedId = l.id
        state.setFlag(Slp.FLAG_STYLE_PS, l.style == PadStyle.PLAYSTATION)
        surface.setLayout(l)
        // Raw motion goes to the hub in PlayStation style; gyro aim works in both styles.
        motion?.stop()
        val m = MotionSource(this, state, l.style == PadStyle.PLAYSTATION, l.gyroMode, l.gyroSensitivity, l.gyroInvertY)
        motion = if (m.needed) m else null
        if (playing) startMotion()
    }

    private fun startMotion() {
        val m = motion ?: return
        m.rotation = currentRotation()
        m.start()
    }

    // ------------------------------------------------------------ pause

    /** Resumed with the menu closed: the only state in which input and motion are live. */
    private val playing: Boolean get() = resumed && !menu.isShowing

    /**
     * PAUSED, the input gate and the motion sensors follow [playing] together. While paused
     * nothing can press a button, so a PAUSED packet never carries a new tap, and the gyro
     * and accelerometer (SENSOR_DELAY_FASTEST) are off instead of draining the battery.
     */
    private fun updatePaused() {
        val live = playing
        state.setFlag(Slp.FLAG_PAUSED, !live)
        surface.setInputEnabled(live)
        if (live) startMotion() else motion?.stop()
    }

    override fun onPauseRequested() = openMenu()

    private fun openMenu() {
        if (menu.isShowing) return
        surface.releaseAllPointers()
        menu.show(layout)
        updatePaused()
        haptics.tick(0.5f)
    }

    override fun onMenuResume() {
        menu.hide()
        updatePaused()
        hideSystemBars()
    }

    override fun onMenuEditLayout() {
        startActivity(Intent(this, PadEditorActivity::class.java).putExtra(PadEditorActivity.EXTRA_PROFILE, layout.id))
    }

    override fun onMenuSelectProfile(id: String) {
        val next = store.load(id) ?: return
        applyLayout(next)
        menu.show(next)
    }

    override fun onMenuExit() = finish()

    // ------------------------------------------------------------ keys

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val bit = volumeBit(keyCode)
        if (bit == Settings.UNMAPPED) return super.onKeyDown(keyCode, event) // the volume changes
        if (event.repeatCount == 0 && playing) {
            if (keyHold(keyCode).down(bit)) haptics.tick(0.5f)
            surface.invalidate()
        }
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val bit = volumeBit(keyCode)
        if (bit == Settings.UNMAPPED) return super.onKeyUp(keyCode, event)
        // Releases only what this key's own down pressed: an ignored down (menu open) or a
        // hold already wiped with every finger must not release a finger on the same button.
        keyHold(keyCode).up()
        surface.invalidate()
        return true
    }

    private fun keyHold(keyCode: Int): KeyHold = if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) volumeUpHold else volumeDownHold

    private fun volumeBit(keyCode: Int): Int = when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_UP -> volumeUpBit
        KeyEvent.KEYCODE_VOLUME_DOWN -> volumeDownBit
        else -> Settings.UNMAPPED
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

    /**
     * The chip appears only when the link degrades (smoothed RTT above 30 ms, loss above 5 %
     * over the last second, or no STATUS for 500 ms) and hides after 3 s of good link.
     */
    private fun updateStatus() {
        val l = link ?: return
        l.tick()
        val e = l.engine
        val now = System.nanoTime()
        e.stats.snapshot(snapshot, now)
        var lastStatus = 0L
        for (t in e.stats.transports) if (t.lastStatusNs > lastStatus) lastStatus = t.lastStatusNs
        val silentNs = now - maxOf(lastStatus, e.startedNs)
        val rtt = snapshot.linkRttUs
        val loss = snapshot.lossLastSecond
        val silent = silentNs > SILENT_NS
        val slow = !rtt.isNaN() && rtt > SLOW_RTT_US
        val lossy = !loss.isNaN() && loss > LOSSY
        val bad = silent || slow || lossy || snapshot.outputError
        if (bad) {
            lastBadNs = now
            chipShown = true
        } else if (chipShown && now - lastBadNs >= RECOVERED_NS) {
            chipShown = false
        }
        chip.setLength(0)
        when {
            silent && snapshot.hubTracksOtherEpoch -> chip.append(chipBusy)
            silent && lastStatus == 0L && now - e.startedNs < NO_REPLY_NS -> chip.append(chipConnecting)
            silent -> chip.append(chipNoReply)
            snapshot.outputError -> chip.append(chipOutputError)
            else -> {
                chip.append(if (snapshot.liveCount > 1) "USB + WI-FI   " else if (snapshot.state[LinkEngine.SLOT_USB] == LinkStats.TransportState.LIVE) "USB   " else "WI-FI   ")
                Fmt.fixed(chip, rtt / 1000.0, 1).append(" ms   ")
                Fmt.fixed(chip, loss * 100.0, 1).append(" % loss")
            }
        }
        surface.setChip(chipShown, bad, chip)
    }

    companion object {
        const val EXTRA_PROFILE = "com.slipstream.wheel.PROFILE"
        private const val STATUS_PERIOD_MS = 100L
        private const val SILENT_NS = 500_000_000L
        private const val SLOW_RTT_US = 30_000.0
        private const val LOSSY = 0.05
        private const val RECOVERED_NS = 3_000_000_000L
        private const val NO_REPLY_NS = 3_000_000_000L
    }
}
