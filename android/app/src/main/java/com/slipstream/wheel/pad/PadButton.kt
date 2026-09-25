package com.slipstream.wheel.pad

import com.slipstream.wheel.protocol.Slp

/** Controller look, which also sets the STYLE_PS flag (PROTOCOL.md 12.1). */
enum class PadStyle { PLAYSTATION, XBOX }

/**
 * Canonical PAD buttons (PROTOCOL.md 12.2). The bit numbers are the wire format; names
 * follow the style the player picked.
 */
object PadButton {
    const val CROSS = 0 // A
    const val CIRCLE = 1 // B
    const val SQUARE = 2 // X
    const val TRIANGLE = 3 // Y
    const val L1 = 4 // LB
    const val R1 = 5 // RB
    const val L3 = 6 // left stick press
    const val R3 = 7 // right stick press
    const val CREATE = 8 // View
    const val OPTIONS = 9 // Menu
    const val HOME = 10 // PS, Xbox
    const val TOUCHPAD = 11 // PlayStation only
    const val UP = 12
    const val DOWN = 13
    const val LEFT = 14
    const val RIGHT = 15
    const val MUTE = 16 // PlayStation only
    const val SHARE = 17 // Xbox only

    const val COUNT = Slp.PAD_BUTTONS

    /** D-pad direction bits, all four. */
    const val DPAD_MASK = (1 shl UP) or (1 shl DOWN) or (1 shl LEFT) or (1 shl RIGHT)

    private val PS = arrayOf(
        "Cross", "Circle", "Square", "Triangle", "L1", "R1", "L3", "R3", "Create", "Options",
        "PS", "Touchpad click", "D-pad up", "D-pad down", "D-pad left", "D-pad right", "Mute", null,
    )
    private val XBOX = arrayOf(
        "A", "B", "X", "Y", "LB", "RB", "Left stick press", "Right stick press", "View", "Menu",
        "Xbox", null, "D-pad up", "D-pad down", "D-pad left", "D-pad right", null, "Share",
    )

    fun isValid(bit: Int): Boolean = bit in 0 until COUNT

    /** Name in [style], or null when that style has no such button (for example Mute on Xbox). */
    fun name(bit: Int, style: PadStyle): String? {
        if (!isValid(bit)) return null
        return if (style == PadStyle.PLAYSTATION) PS[bit] else XBOX[bit]
    }

    /** Both names, for settings that apply to every profile, e.g. "Cross / A". */
    fun bothNames(bit: Int): String {
        val p = name(bit, PadStyle.PLAYSTATION)
        val x = name(bit, PadStyle.XBOX)
        return when {
            p != null && x != null && p != x -> "$p / $x"
            p != null -> p
            x != null -> x
            else -> "Button $bit"
        }
    }
}
