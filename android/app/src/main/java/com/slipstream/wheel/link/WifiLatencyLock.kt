package com.slipstream.wheel.link

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build

/**
 * Keeps the Wi-Fi radio out of power save while driving: WIFI_MODE_FULL_LOW_LATENCY on
 * Android 10 and later (it only takes effect while the app is in the foreground with the
 * screen on, which is exactly the drive screen), WIFI_MODE_FULL_HIGH_PERF before that.
 * Power-save wake-ups are the 10 to 100 ms spikes a naive app sees.
 */
class WifiLatencyLock(context: Context) {
    private val lock: WifiManager.WifiLock? = run {
        // The WifiManager must come from the application context, or it leaks the activity.
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            @Suppress("DEPRECATION")
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        wifi?.createWifiLock(mode, "slipstream-drive")?.apply { setReferenceCounted(false) }
    }

    val isHeld: Boolean get() = lock?.isHeld == true

    val modeLabel: String
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) "low latency" else "high performance"

    fun acquire() {
        try {
            lock?.acquire()
        } catch (e: RuntimeException) {
            // Some builds refuse the lock without Wi-Fi; the link still works, only slower.
        }
    }

    fun release() {
        try {
            lock?.let { if (it.isHeld) it.release() }
        } catch (e: RuntimeException) {
            // ignore
        }
    }
}
