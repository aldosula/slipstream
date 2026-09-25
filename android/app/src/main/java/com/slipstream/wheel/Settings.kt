package com.slipstream.wheel

import android.content.Context
import android.content.SharedPreferences
import com.slipstream.wheel.input.SteeringProcessor
import com.slipstream.wheel.protocol.PairingKey
import com.slipstream.wheel.protocol.Slp

/** How the phone reaches the PC. */
enum class LinkMode { WIFI, USB, MULTIPATH, BLUETOOTH }

enum class PedalMode { SWIPE, ABSOLUTE }

/** Fixed landscape for the drive screen. The wheel must not flip mid-corner. */
enum class DriveOrientation { LANDSCAPE, REVERSE_LANDSCAPE }

/** What the phone becomes: the racing wheel, or a gamepad (controller mode, 0.2.0). */
enum class PlayAs { WHEEL, CONTROLLER }

/**
 * SharedPreferences-backed settings. Reads are cheap and happen when a screen opens, never
 * on the input path. The pairing code is stored only on this phone and excluded from
 * backups (see res/xml/data_extraction_rules.xml).
 */
class Settings(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("slipstream", Context.MODE_PRIVATE)

    // Steering
    var lockDeg: Int
        get() = prefs.getInt(K_LOCK, 90).coerceIn(LOCK_MIN, LOCK_MAX)
        set(v) = put { putInt(K_LOCK, v) }
    var deadzoneDeg: Float
        get() = prefs.getFloat(K_DEADZONE, 0.5f)
        set(v) = put { putFloat(K_DEADZONE, v) }
    var steerCurve: Float
        get() = prefs.getFloat(K_STEER_CURVE, 1.0f)
        set(v) = put { putFloat(K_STEER_CURVE, v) }
    var smoothing: Int
        get() = prefs.getInt(K_SMOOTHING, 2).coerceIn(0, 10)
        set(v) = put { putInt(K_SMOOTHING, v) }
    var steerCenterRad: Float
        get() = prefs.getFloat(K_CENTER, 0f)
        set(v) = put { putFloat(K_CENTER, v) }
    var orientation: DriveOrientation
        get() = enumOr(prefs.getString(K_ORIENTATION, null), DriveOrientation.LANDSCAPE)
        set(v) = put { putString(K_ORIENTATION, v.name) }

    // Pedals
    var pedalMode: PedalMode
        get() = enumOr(prefs.getString(K_PEDAL_MODE, null), PedalMode.SWIPE)
        set(v) = put { putString(K_PEDAL_MODE, v.name) }
    var travelPct: Int
        get() = prefs.getInt(K_TRAVEL, 35).coerceIn(TRAVEL_MIN, TRAVEL_MAX)
        set(v) = put { putInt(K_TRAVEL, v) }
    var swapZones: Boolean
        get() = prefs.getBoolean(K_SWAP_ZONES, false)
        set(v) = put { putBoolean(K_SWAP_ZONES, v) }
    var throttleCurve: Float
        get() = prefs.getFloat(K_THROTTLE_CURVE, 1.0f)
        set(v) = put { putFloat(K_THROTTLE_CURVE, v) }
    var brakeCurve: Float
        get() = prefs.getFloat(K_BRAKE_CURVE, 1.0f)
        set(v) = put { putFloat(K_BRAKE_CURVE, v) }

    // Buttons and feedback
    var volumeSwap: Boolean
        get() = prefs.getBoolean(K_VOLUME_SWAP, false)
        set(v) = put { putBoolean(K_VOLUME_SWAP, v) }
    var haptics: Boolean
        get() = prefs.getBoolean(K_HAPTICS, true)
        set(v) = put { putBoolean(K_HAPTICS, v) }
    var rumble: Boolean
        get() = prefs.getBoolean(K_RUMBLE, true)
        set(v) = put { putBoolean(K_RUMBLE, v) }

    // Controller mode
    var playAs: PlayAs
        get() = enumOr(prefs.getString(K_PLAY_AS, null), PlayAs.WHEEL)
        set(v) = put { putString(K_PLAY_AS, v.name) }

    /** Canonical pad button the volume up key holds in controller mode, or [UNMAPPED]. */
    var padVolumeUp: Int
        get() = prefs.getInt(K_PAD_VOL_UP, UNMAPPED).let { if (it in 0 until Slp.PAD_BUTTONS) it else UNMAPPED }
        set(v) = put { putInt(K_PAD_VOL_UP, v) }
    var padVolumeDown: Int
        get() = prefs.getInt(K_PAD_VOL_DOWN, UNMAPPED).let { if (it in 0 until Slp.PAD_BUTTONS) it else UNMAPPED }
        set(v) = put { putInt(K_PAD_VOL_DOWN, v) }

    fun buttonLabel(i: Int): String =
        prefs.getString(K_LABEL + i, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_LABELS[i]

    fun setButtonLabel(i: Int, label: String) = put { putString(K_LABEL + i, label.trim().take(LABEL_MAX)) }

    // Link
    var rateHz: Int
        get() = prefs.getInt(K_RATE, Slp.RATE_DEFAULT_HZ).let { if (it in Slp.RATES_HZ) it else Slp.RATE_DEFAULT_HZ }
        set(v) = put { putInt(K_RATE, v) }
    var mode: LinkMode
        get() = enumOr(prefs.getString(K_MODE, null), LinkMode.MULTIPATH)
        set(v) = put { putString(K_MODE, v.name) }

    // Pairing
    var pairCode: String?
        get() = prefs.getString(K_CODE, null)
        set(v) = put { if (v == null) remove(K_CODE) else putString(K_CODE, v) }
    var hubName: String?
        get() = prefs.getString(K_HUB_NAME, null)
        set(v) = put { putString(K_HUB_NAME, v) }

    /** Hub addresses from the QR code, comma separated, best first. */
    var hubHosts: List<String>
        get() = prefs.getString(K_HUB_HOSTS, "")!!.split(',').filter { it.isNotBlank() }
        set(v) = put { putString(K_HUB_HOSTS, v.joinToString(",")) }
    var udpPort: Int
        get() = prefs.getInt(K_UDP_PORT, Slp.PORT_UDP)
        set(v) = put { putInt(K_UDP_PORT, v) }
    var tcpPort: Int
        get() = prefs.getInt(K_TCP_PORT, Slp.PORT_TCP)
        set(v) = put { putInt(K_TCP_PORT, v) }

    fun pairingKey(): PairingKey? = pairCode?.let { PairingKey.fromInput(it) }

    fun steeringConfig(): SteeringProcessor.Config = SteeringProcessor.Config(
        lockDeg = lockDeg.toDouble(),
        deadzoneDeg = deadzoneDeg.toDouble(),
        curve = steerCurve.toDouble(),
        smoothing = smoothing,
        centerRad = steerCenterRad.toDouble(),
    )

    fun resetDriving() = put {
        for (k in listOf(
            K_LOCK, K_DEADZONE, K_STEER_CURVE, K_SMOOTHING, K_CENTER, K_ORIENTATION, K_PEDAL_MODE,
            K_TRAVEL, K_SWAP_ZONES, K_THROTTLE_CURVE, K_BRAKE_CURVE, K_VOLUME_SWAP, K_HAPTICS,
            K_RUMBLE, K_RATE,
        )) {
            remove(k)
        }
        for (i in 0 until 4) remove(K_LABEL + i)
    }

    private inline fun put(block: SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(block).apply()
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, fallback: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: fallback

    companion object {
        const val LOCK_MIN = 30
        const val LOCK_MAX = 180
        const val TRAVEL_MIN = 10
        const val TRAVEL_MAX = 80
        const val LABEL_MAX = 6

        /** A volume key that keeps changing the volume in controller mode. */
        const val UNMAPPED = -1
        val DEFAULT_LABELS = listOf("1", "2", "3", "4")

        private const val K_LOCK = "steer.lock_deg"
        private const val K_DEADZONE = "steer.deadzone_deg"
        private const val K_STEER_CURVE = "steer.curve"
        private const val K_SMOOTHING = "steer.smoothing"
        private const val K_CENTER = "steer.center_rad"
        private const val K_ORIENTATION = "drive.orientation"
        private const val K_PEDAL_MODE = "pedal.mode"
        private const val K_TRAVEL = "pedal.travel_pct"
        private const val K_SWAP_ZONES = "pedal.swap_zones"
        private const val K_THROTTLE_CURVE = "pedal.throttle_curve"
        private const val K_BRAKE_CURVE = "pedal.brake_curve"
        private const val K_VOLUME_SWAP = "keys.volume_swap"
        private const val K_HAPTICS = "feedback.haptics"
        private const val K_RUMBLE = "feedback.rumble"
        private const val K_LABEL = "buttons.label."
        private const val K_PLAY_AS = "play.as"
        private const val K_PAD_VOL_UP = "pad.volume_up"
        private const val K_PAD_VOL_DOWN = "pad.volume_down"
        private const val K_RATE = "link.rate_hz"
        private const val K_MODE = "link.mode"
        private const val K_CODE = "pair.code"
        private const val K_HUB_NAME = "pair.hub_name"
        private const val K_HUB_HOSTS = "pair.hub_hosts"
        private const val K_UDP_PORT = "pair.udp_port"
        private const val K_TCP_PORT = "pair.tcp_port"
    }
}
