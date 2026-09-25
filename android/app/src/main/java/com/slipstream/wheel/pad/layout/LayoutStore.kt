package com.slipstream.wheel.pad.layout

import android.content.Context
import android.content.SharedPreferences
import com.slipstream.wheel.pad.GyroAim
import com.slipstream.wheel.pad.PadButton
import com.slipstream.wheel.pad.PadStyle
import com.slipstream.wheel.pad.StickMath
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Profile JSON (one object per profile). Decoding never throws: anything unreadable is null,
 * out-of-range numbers are clamped, unknown names take the default, and a control whose
 * binding cannot be right for its kind (a button bit outside 0..17, a stick or trigger side
 * other than 0 or 1) is dropped.
 */
object LayoutCodec {
    const val VERSION = 1

    fun encode(l: PadLayout): String {
        val controls = JSONArray()
        for (c in l.controls) {
            val o = c.options
            controls.put(
                JSONObject()
                    .put("id", c.id)
                    .put("kind", c.kind.name.lowercase())
                    .put("binding", c.binding)
                    .put("cx", c.cx.toDouble())
                    .put("cy", c.cy.toDouble())
                    .put("width_dp", c.widthDp.toDouble())
                    .put("height_dp", c.heightDp.toDouble())
                    .put("opacity", c.opacity.toDouble())
                    .put("haptic", c.haptic.toDouble())
                    .put(
                        "options",
                        JSONObject()
                            .put("shape", o.shape.name.lowercase())
                            .put("trigger_mode", o.triggerMode.name.lowercase())
                            .put("stick_origin", o.stickOrigin.name.lowercase())
                            .put("stick_click", o.stickClick.name.lowercase())
                            .put("deadzone", o.deadzone.toDouble())
                            .put("curve", o.curve.toDouble())
                            .put("face_button_dp", o.faceButtonDp.toDouble()),
                    ),
            )
        }
        return JSONObject()
            .put("v", VERSION)
            .put("id", l.id)
            .put("name", l.name)
            .put("style", l.style.name.lowercase())
            .put("built_in", l.builtIn)
            .put("slide_to_press", l.slideToPress)
            .put(
                "gyro",
                JSONObject()
                    .put("mode", l.gyroMode.name.lowercase())
                    .put("sensitivity", l.gyroSensitivity.toDouble())
                    .put("invert_y", l.gyroInvertY),
            )
            .put("ref_width_dp", l.refWidthDp.toDouble())
            .put("ref_height_dp", l.refHeightDp.toDouble())
            .put("controls", controls)
            .toString()
    }

    fun decode(text: String?): PadLayout? {
        if (text.isNullOrEmpty()) return null
        return try {
            val j = JSONObject(text)
            if (j.optInt("v", 0) != VERSION) return null
            val style = enumOr(j.optString("style"), PadStyle.PLAYSTATION)
            val arr = j.getJSONArray("controls")
            val controls = ArrayList<PadControl>(arr.length())
            for (i in 0 until arr.length()) {
                val c = arr.getJSONObject(i)
                val kind = enumOrNull<ControlKind>(c.optString("kind")) ?: continue
                val binding = binding(kind, c.optInt("binding", 0)) ?: continue
                val o = c.optJSONObject("options") ?: JSONObject()
                val defaults = ControlOptions()
                controls.add(
                    PadControl(
                        id = c.getString("id"),
                        kind = kind,
                        binding = binding,
                        cx = num(c, "cx", 0.5f, 0f, 1f),
                        cy = num(c, "cy", 0.5f, 0f, 1f),
                        widthDp = num(c, "width_dp", 60f, PadGeometry.MIN_SIZE_DP, PadGeometry.MAX_SIZE_DP),
                        heightDp = num(c, "height_dp", 60f, PadGeometry.MIN_SIZE_DP, PadGeometry.MAX_SIZE_DP),
                        opacity = num(c, "opacity", 1f, MIN_OPACITY, 1f),
                        haptic = num(c, "haptic", PadControl.DEFAULT_HAPTIC, 0f, 1f),
                        options = ControlOptions(
                            shape = enumOr(o.optString("shape"), defaults.shape),
                            triggerMode = enumOr(o.optString("trigger_mode"), defaults.triggerMode),
                            stickOrigin = enumOr(o.optString("stick_origin"), defaults.stickOrigin),
                            stickClick = enumOr(o.optString("stick_click"), defaults.stickClick),
                            deadzone = num(o, "deadzone", StickMath.DEFAULT_DEADZONE, 0f, StickMath.MAX_DEADZONE),
                            curve = num(o, "curve", StickMath.DEFAULT_CURVE, 0.2f, 5f),
                            faceButtonDp = num(o, "face_button_dp", DefaultLayouts.FACE_BUTTON_DP, PadGeometry.MIN_SIZE_DP, PadGeometry.MAX_SIZE_DP),
                        ),
                    ),
                )
            }
            val gyro = j.optJSONObject("gyro") ?: JSONObject()
            PadLayout(
                id = j.getString("id"),
                name = j.optString("name").trim().ifEmpty { "Controller" },
                style = style,
                builtIn = j.optBoolean("built_in", false),
                controls = controls,
                slideToPress = j.optBoolean("slide_to_press", true),
                gyroMode = enumOr(gyro.optString("mode"), GyroAim.Mode.OFF),
                gyroSensitivity = num(gyro, "sensitivity", GyroAim.SENSITIVITY_DEFAULT, GyroAim.SENSITIVITY_MIN, GyroAim.SENSITIVITY_MAX),
                gyroInvertY = gyro.optBoolean("invert_y", false),
                refWidthDp = num(j, "ref_width_dp", DefaultLayouts.REF_WIDTH_DP, 100f, 5000f),
                refHeightDp = num(j, "ref_height_dp", DefaultLayouts.REF_HEIGHT_DP, 100f, 5000f),
            )
        } catch (e: JSONException) {
            null
        }
    }

    private const val MIN_OPACITY = 0.15f

    /**
     * The binding a stored control may have, or null when it cannot be right: a button needs a
     * canonical bit (PROTOCOL.md 12.2), a stick or trigger side 0 or 1 (any other value would
     * drive the other stick or trigger), and the D-pad, face cluster and touchpad have fixed bits.
     */
    private fun binding(kind: ControlKind, stored: Int): Int? = when (kind) {
        ControlKind.BUTTON -> stored.takeIf { PadButton.isValid(it) }
        ControlKind.STICK, ControlKind.TRIGGER -> stored.takeIf { it == 0 || it == 1 }
        ControlKind.DPAD, ControlKind.FACE, ControlKind.TOUCHPAD -> 0
    }

    private fun num(o: JSONObject, key: String, fallback: Float, min: Float, max: Float): Float {
        val d = o.optDouble(key, Double.NaN)
        if (d.isNaN() || d.isInfinite()) return fallback
        return d.toFloat().coerceIn(min, max)
    }

    private inline fun <reified E : Enum<E>> enumOrNull(name: String?): E? {
        if (name.isNullOrEmpty()) return null
        return enumValues<E>().firstOrNull { it.name.equals(name, ignoreCase = true) }
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, fallback: E): E = enumOrNull<E>(name) ?: fallback
}

/** The few string keys the store needs, so it runs on SharedPreferences and in unit tests alike. */
interface KeyValueStore {
    fun get(key: String): String?
    fun put(key: String, value: String?)
}

class PrefsKeyValueStore(private val prefs: SharedPreferences) : KeyValueStore {
    override fun get(key: String): String? = prefs.getString(key, null)
    override fun put(key: String, value: String?) {
        prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
    }
}

/**
 * Profiles (ARCHITECTURE.md 7.1): the two built-ins, PlayStation and Xbox, which can be
 * edited and reset but never deleted or renamed, and any number of custom profiles, which can
 * be duplicated, renamed and deleted. The last used profile is remembered.
 *
 * Keys: `profiles` (JSON array of custom ids, in creation order), `profile.<id>` (the
 * profile's JSON; for a built-in, its edited copy), `last` (id), `next` (id counter).
 */
class LayoutStore(private val kv: KeyValueStore) {

    fun ids(): List<String> = listOf(DefaultLayouts.PLAYSTATION_ID, DefaultLayouts.XBOX_ID) + customIds()

    fun all(): List<PadLayout> = ids().mapNotNull { load(it) }

    /** The profile, or null for an unknown id. A built-in is never null. */
    fun load(id: String): PadLayout? {
        val stored = LayoutCodec.decode(kv.get(K_PROFILE + id))?.takeIf { it.id == id }
        val builtIn = DefaultLayouts.builtIn(id)
        if (builtIn != null) {
            // An edited built-in keeps its identity: fixed name, style and flag.
            return stored?.copy(name = builtIn.name, style = builtIn.style, builtIn = true) ?: builtIn
        }
        if (id !in customIds()) return null
        return stored?.copy(builtIn = false)
    }

    fun save(layout: PadLayout) {
        if (!DefaultLayouts.isBuiltIn(layout.id) && layout.id !in customIds()) {
            kv.put(K_PROFILES, JSONArray(customIds() + layout.id).toString())
        }
        kv.put(K_PROFILE + layout.id, LayoutCodec.encode(layout))
    }

    /** Back to the default of the profile's style. A custom profile keeps its id and name. */
    fun reset(id: String): PadLayout? {
        val current = load(id) ?: return null
        val def = DefaultLayouts.forStyle(current.style)
        if (current.builtIn) {
            kv.put(K_PROFILE + id, null)
            return DefaultLayouts.builtIn(id)
        }
        val next = def.copy(id = id, name = current.name, builtIn = false)
        save(next)
        return next
    }

    /** A new custom profile copied from [id], named [name] (or "<name> copy"). */
    fun duplicate(id: String, name: String? = null): PadLayout? {
        val src = load(id) ?: return null
        val copy = src.copy(id = newId(), name = cleanName(name) ?: (src.name + " copy"), builtIn = false)
        save(copy)
        return copy
    }

    fun rename(id: String, name: String): Boolean {
        if (DefaultLayouts.isBuiltIn(id)) return false
        val clean = cleanName(name) ?: return false
        val l = load(id) ?: return false
        save(l.copy(name = clean))
        return true
    }

    fun delete(id: String): Boolean {
        if (DefaultLayouts.isBuiltIn(id)) return false
        val ids = customIds()
        if (id !in ids) return false
        kv.put(K_PROFILES, JSONArray(ids - id).toString())
        kv.put(K_PROFILE + id, null)
        if (kv.get(K_LAST) == id) kv.put(K_LAST, null)
        return true
    }

    /** The last used profile; PlayStation until one is chosen, or if it was deleted. */
    var lastUsedId: String
        get() = kv.get(K_LAST)?.takeIf { it in ids() } ?: DefaultLayouts.PLAYSTATION_ID
        set(v) {
            if (v in ids()) kv.put(K_LAST, v)
        }

    fun current(): PadLayout = load(lastUsedId) ?: DefaultLayouts.playStation()

    private fun customIds(): List<String> {
        val text = kv.get(K_PROFILES) ?: return emptyList()
        return try {
            val a = JSONArray(text)
            (0 until a.length()).map { a.getString(it) }.filter { !DefaultLayouts.isBuiltIn(it) }.distinct()
        } catch (e: JSONException) {
            emptyList()
        }
    }

    private fun newId(): String {
        var n = kv.get(K_NEXT)?.toIntOrNull() ?: 1
        val existing = customIds()
        while ("custom-$n" in existing) n++
        kv.put(K_NEXT, (n + 1).toString())
        return "custom-$n"
    }

    private fun cleanName(name: String?): String? = name?.trim()?.take(NAME_MAX)?.takeIf { it.isNotEmpty() }

    companion object {
        const val NAME_MAX = 32
        private const val PREFS = "slipstream_pad"
        private const val K_PROFILES = "profiles"
        private const val K_PROFILE = "profile."
        private const val K_LAST = "last"
        private const val K_NEXT = "next"

        fun open(context: Context): LayoutStore =
            LayoutStore(PrefsKeyValueStore(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)))
    }
}
