package com.slipstream.wheel.hid

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.RequiresApi
import com.slipstream.wheel.input.ControllerState
import com.slipstream.wheel.link.ThreadBoost
import com.slipstream.wheel.protocol.InputFrame
import com.slipstream.wheel.protocol.Slp
import java.util.concurrent.Executor
import java.util.concurrent.locks.LockSupport

/**
 * Experimental: the phone registers as a Bluetooth HID gamepad (Android 9+), so Windows sees
 * a real game controller with no hub and no driver. Some phone makers disable the HID device
 * profile; then [GamepadStatus.UNSUPPORTED] is reported and nothing else happens.
 *
 * Reports are sent on change, at most 250 per second, from a dedicated thread. Pulse
 * counters are rendered here as 60 ms presses with 40 ms gaps, because there is no hub.
 *
 * Every Bluetooth call below runs only after [hasPermissions] returned true in [start];
 * that is why MissingPermission is suppressed for the class.
 */
@SuppressLint("MissingPermission")
@RequiresApi(Build.VERSION_CODES.P)
class BluetoothGamepad(
    context: Context,
    private val state: ControllerState,
    private val listener: (GamepadStatus, String?) -> Unit,
) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { main.post(it) }
    private val adapter: BluetoothAdapter? = app.getSystemService(BluetoothManager::class.java)?.adapter

    @Volatile private var hid: BluetoothHidDevice? = null
    @Volatile private var host: BluetoothDevice? = null
    @Volatile private var running = false
    @Volatile private var forceSend = false
    private var thread: Thread? = null

    var status: GamepadStatus = GamepadStatus.STOPPED
        private set
    var hostName: String? = null
        private set

    private val frame = InputFrame()
    private val renderer = PulseRenderer(PULSE_MS, GAP_MS)
    private val report = ByteArray(HidReport.PAYLOAD_LEN)
    private val lastSent = ByteArray(HidReport.PAYLOAD_LEN)
    private val reportLock = Any()

    private val proxyTimeout = Runnable {
        if (running && hid == null) setStatus(GamepadStatus.UNSUPPORTED)
    }

    private val serviceListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            main.removeCallbacks(proxyTimeout)
            val device = proxy as BluetoothHidDevice
            hid = device
            if (!running) {
                adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, device)
                hid = null
                return
            }
            register(device)
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            hid = null
            host = null
            hostName = null
            // Bluetooth switched off takes the profile service down too: that is not "this
            // phone has no HID profile", and it must stay recoverable once Bluetooth is back.
            if (running) setStatus(if (adapterEnabled()) GamepadStatus.UNSUPPORTED else GamepadStatus.BLUETOOTH_OFF)
        }
    }

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            if (!running) return
            if (registered) {
                setStatus(GamepadStatus.WAITING_FOR_HOST)
                // A PC this phone was paired with as a gamepad before: reconnect to it.
                if (pluggedDevice != null) hid?.connect(pluggedDevice)
            } else if (!adapterEnabled()) {
                setStatus(GamepadStatus.BLUETOOTH_OFF)
            } else if (status != GamepadStatus.UNSUPPORTED) {
                setStatus(GamepadStatus.STARTING)
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    host = device
                    hostName = device.name ?: device.address
                    forceSend = true
                    thread?.let { LockSupport.unpark(it) }
                    setStatus(GamepadStatus.CONNECTED)
                }
                BluetoothProfile.STATE_DISCONNECTED -> if (device == host) {
                    host = null
                    hostName = null
                    if (running) setStatus(GamepadStatus.WAITING_FOR_HOST)
                }
            }
        }

        override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
            val dev = hid ?: return
            if (id.toInt() != HidReport.REPORT_ID) {
                dev.reportError(device, BluetoothHidDevice.ERROR_RSP_INVALID_RPT_ID)
                return
            }
            val copy = synchronized(reportLock) { lastSent.copyOf() }
            dev.replyReport(device, type, id, copy)
        }
    }

    private fun adapterEnabled(): Boolean = try {
        adapter?.isEnabled == true
    } catch (e: RuntimeException) {
        false
    }

    fun hasPermissions(): Boolean = GamepadSupport.requiredPermissions().all {
        app.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    fun start() {
        if (running) return
        running = true
        val a = adapter
        when {
            a == null -> return setStatus(GamepadStatus.UNSUPPORTED)
            !hasPermissions() -> return setStatus(GamepadStatus.NO_PERMISSION)
            !a.isEnabled -> return setStatus(GamepadStatus.BLUETOOTH_OFF)
        }
        setStatus(GamepadStatus.STARTING)
        if (!a!!.getProfileProxy(app, serviceListener, BluetoothProfile.HID_DEVICE)) {
            return setStatus(GamepadStatus.UNSUPPORTED)
        }
        main.postDelayed(proxyTimeout, PROXY_TIMEOUT_MS)
        renderer.reset()
        thread = Thread({ reportLoop() }, "slip-hid").also {
            it.isDaemon = true
            it.start()
        }
    }

    fun stop() {
        if (!running) return
        running = false
        main.removeCallbacks(proxyTimeout)
        thread?.let {
            LockSupport.unpark(it)
            it.join(300)
        }
        thread = null
        val dev = hid
        if (dev != null) {
            try {
                host?.let { dev.disconnect(it) }
                dev.unregisterApp()
            } catch (e: RuntimeException) {
                // The stack may already be gone.
            }
            adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, dev)
        }
        hid = null
        host = null
        hostName = null
        setStatus(GamepadStatus.STOPPED)
    }

    private fun register(device: BluetoothHidDevice) {
        val sdp = BluetoothHidDeviceAppSdpSettings(
            SDP_NAME,
            SDP_DESCRIPTION,
            SDP_PROVIDER,
            BluetoothHidDevice.SUBCLASS2_GAMEPAD,
            HidReport.DESCRIPTOR,
        )
        val ok = try {
            device.registerApp(sdp, null, null, mainExecutor, callback)
        } catch (e: RuntimeException) {
            false
        }
        if (!ok) setStatus(GamepadStatus.UNSUPPORTED)
    }

    private fun setStatus(s: GamepadStatus) {
        status = s
        val name = hostName
        if (Looper.myLooper() == Looper.getMainLooper()) listener(s, name) else main.post { listener(s, name) }
    }

    private fun reportLoop() {
        ThreadBoost.setCurrent(-8)
        val signal = state.signal
        val me = Thread.currentThread()
        signal.attach(me)
        var lastSendMs = 0L
        try {
            while (running) {
                val h = host
                val dev = hid
                if (h == null || dev == null) {
                    LockSupport.parkNanos(IDLE_WAIT_NS)
                    continue
                }
                val now = SystemClock.uptimeMillis()
                val pulseDue = renderer.nextChangeMs() <= now
                if (!signal.isPending() && !pulseDue && !forceSend) {
                    val next = minOf(renderer.nextChangeMs(), now + KEEPALIVE_MS)
                    LockSupport.parkNanos((next - now).coerceAtLeast(1) * 1_000_000L)
                    continue
                }
                val since = now - lastSendMs
                if (since < MIN_INTERVAL_MS) {
                    LockSupport.parkNanos((MIN_INTERVAL_MS - since) * 1_000_000L)
                    continue
                }
                signal.consume()
                val force = forceSend
                forceSend = false
                state.snapshot(frame)
                val pulses = renderer.update(frame.pulses, now)
                val paused = (frame.flags and Slp.FLAG_PAUSED) != 0
                // Mirrors the hub failsafe: PAUSED means pedals and held buttons released and
                // steering centred; queued pulses still finish.
                HidReport.write(
                    report,
                    steer = if (paused) 0 else frame.steer,
                    throttle = if (paused) 0 else frame.throttle,
                    brake = if (paused) 0 else frame.brake,
                    clutch = if (paused) 0 else frame.clutch,
                    handbrake = if (paused) 0 else frame.handbrake,
                    buttons = HidReport.buttons(pulses, if (paused) 0 else frame.buttons),
                )
                if (!force && report.contentEquals(lastSent)) continue
                val sent = try {
                    dev.sendReport(h, HidReport.REPORT_ID, report)
                } catch (e: RuntimeException) {
                    false
                }
                if (sent) {
                    synchronized(reportLock) { System.arraycopy(report, 0, lastSent, 0, report.size) }
                    lastSendMs = now
                }
            }
        } finally {
            signal.detach(me)
        }
    }

    companion object {
        const val SDP_NAME = "Slipstream Wheel"
        const val SDP_DESCRIPTION = "Phone racing wheel"
        const val SDP_PROVIDER = "Slipstream"
        const val PULSE_MS = 60L
        const val GAP_MS = 40L

        /** 250 Hz cap. */
        const val MIN_INTERVAL_MS = 4L
        const val KEEPALIVE_MS = 1000L
        const val PROXY_TIMEOUT_MS = 4000L
        const val IDLE_WAIT_NS = 100_000_000L
    }
}

enum class GamepadStatus { STOPPED, UNSUPPORTED, NO_PERMISSION, BLUETOOTH_OFF, STARTING, WAITING_FOR_HOST, CONNECTED }

/** What callers on any API level may ask before touching [BluetoothGamepad]. */
object GamepadSupport {
    val isApiSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P

    fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            emptyArray()
        }
}
