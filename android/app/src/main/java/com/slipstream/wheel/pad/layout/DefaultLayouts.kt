package com.slipstream.wheel.pad.layout

import com.slipstream.wheel.pad.PadButton
import com.slipstream.wheel.pad.PadStyle

/**
 * The two built-in profiles, exactly the tables of ARCHITECTURE.md 7.2 (approved drawings of
 * 25/09/2026, Note 20 Ultra, 882 x 411 dp). Centres are fractions of the drawing area, sizes
 * are dp.
 */
object DefaultLayouts {
    const val REF_WIDTH_DP = 882f
    const val REF_HEIGHT_DP = 411f

    const val PLAYSTATION_ID = "playstation"
    const val XBOX_ID = "xbox"

    /** Pause ring centre, both layouts. */
    const val PAUSE_CX = 0.500f
    const val PAUSE_CY = 0.054f

    const val FACE_BUTTON_DP = 50f

    fun isBuiltIn(id: String): Boolean = id == PLAYSTATION_ID || id == XBOX_ID

    fun builtIn(id: String): PadLayout? = when (id) {
        PLAYSTATION_ID -> playStation()
        XBOX_ID -> xbox()
        else -> null
    }

    fun forStyle(style: PadStyle): PadLayout = if (style == PadStyle.PLAYSTATION) playStation() else xbox()

    fun playStation(): PadLayout = PadLayout(
        id = PLAYSTATION_ID,
        name = "PlayStation",
        style = PadStyle.PLAYSTATION,
        builtIn = true,
        controls = listOf(
            trigger("l2", 0, 0.134f, 0.105f),
            trigger("r2", 1, 0.866f, 0.105f),
            button("l1", PadButton.L1, 0.134f, 0.230f, 171f, 39f, ButtonShape.RECT),
            button("r1", PadButton.R1, 0.866f, 0.230f, 171f, 39f, ButtonShape.RECT),
            button("create", PadButton.CREATE, 0.313f, 0.098f, 77f, 30f, ButtonShape.PILL),
            PadControl("touchpad", ControlKind.TOUCHPAD, 0, 0.500f, 0.193f, 226f, 124f),
            button("options", PadButton.OPTIONS, 0.692f, 0.098f, 85f, 30f, ButtonShape.PILL),
            dpad(0.144f, 0.480f),
            stick("left_stick", 0, 0.303f, 0.764f),
            stick("right_stick", 1, 0.697f, 0.764f),
            face(),
            button("ps", PadButton.HOME, 0.500f, 0.480f, 47f, 47f, ButtonShape.ROUND),
            button("mute", PadButton.MUTE, 0.500f, 0.598f, 55f, 25f, ButtonShape.PILL),
        ),
    )

    fun xbox(): PadLayout = PadLayout(
        id = XBOX_ID,
        name = "Xbox",
        style = PadStyle.XBOX,
        builtIn = true,
        controls = listOf(
            trigger("lt", 0, 0.134f, 0.105f),
            trigger("rt", 1, 0.866f, 0.105f),
            button("lb", PadButton.L1, 0.134f, 0.230f, 171f, 39f, ButtonShape.RECT),
            button("rb", PadButton.R1, 0.866f, 0.230f, 171f, 39f, ButtonShape.RECT),
            stick("left_stick", 0, 0.153f, 0.507f),
            dpad(0.328f, 0.784f),
            stick("right_stick", 1, 0.681f, 0.764f),
            face(),
            button("view", PadButton.CREATE, 0.413f, 0.419f, 36f, 36f, ButtonShape.ROUND),
            button("xbox", PadButton.HOME, 0.500f, 0.419f, 55f, 55f, ButtonShape.ROUND),
            button("menu", PadButton.OPTIONS, 0.588f, 0.419f, 36f, 36f, ButtonShape.ROUND),
            button("share", PadButton.SHARE, 0.500f, 0.605f, 61f, 25f, ButtonShape.PILL),
        ),
    )

    private fun trigger(id: String, side: Int, cx: Float, cy: Float) =
        PadControl(id, ControlKind.TRIGGER, side, cx, cy, 171f, 47f)

    private fun button(id: String, bit: Int, cx: Float, cy: Float, w: Float, h: Float, shape: ButtonShape) =
        PadControl(id, ControlKind.BUTTON, bit, cx, cy, w, h, options = ControlOptions(shape = shape))

    private fun stick(id: String, side: Int, cx: Float, cy: Float) =
        PadControl(id, ControlKind.STICK, side, cx, cy, 116f, 116f)

    private fun dpad(cx: Float, cy: Float) = PadControl("dpad", ControlKind.DPAD, 0, cx, cy, 127f, 127f)

    /** Face cluster: top Triangle / Y, right Circle / B, bottom Cross / A, left Square / X. */
    private fun face() = PadControl(
        "face", ControlKind.FACE, 0, 0.856f, 0.500f, 154f, 154f,
        options = ControlOptions(faceButtonDp = FACE_BUTTON_DP),
    )

    /** Face cluster positions to canonical bits: top, right, bottom, left. */
    val FACE_BITS = intArrayOf(PadButton.TRIANGLE, PadButton.CIRCLE, PadButton.CROSS, PadButton.SQUARE)
}
