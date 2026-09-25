package com.slipstream.wheel.pad.layout

import com.slipstream.wheel.pad.PadButton
import com.slipstream.wheel.pad.StickMath
import com.slipstream.wheel.pad.TriggerMath

/** What a control is, which decides how it draws and how a finger drives it. */
enum class ControlKind { BUTTON, TRIGGER, STICK, DPAD, FACE, TOUCHPAD }

enum class ButtonShape { RECT, PILL, ROUND }

enum class StickOrigin { FIXED, FLOATING }

/** How a stick press (L3 / R3) is made while the thumb is on the stick. */
enum class StickClick { OFF, DOUBLE_TAP, FIRM_PRESS, BOTH }

/** Per control options. Only the fields of the control's kind are used. */
data class ControlOptions(
    val shape: ButtonShape = ButtonShape.RECT,
    val triggerMode: TriggerMath.Mode = TriggerMath.Mode.SLIDE,
    val stickOrigin: StickOrigin = StickOrigin.FIXED,
    val stickClick: StickClick = StickClick.DOUBLE_TAP,
    val deadzone: Float = StickMath.DEFAULT_DEADZONE,
    val curve: Float = StickMath.DEFAULT_CURVE,
    /** Face cluster: diameter of each of the four buttons. */
    val faceButtonDp: Float = 50f,
)

/**
 * One on-screen control (ARCHITECTURE.md 7.1). [cx] and [cy] are the centre as a fraction
 * of the drawing area; sizes are in dp. [binding] depends on [kind]: BUTTON, the canonical
 * bit; TRIGGER and STICK, 0 left and 1 right; DPAD, FACE and TOUCHPAD, 0 (their bits are
 * fixed by PROTOCOL.md 12.2). [haptic] is the tick strength on press, 0 is off.
 */
data class PadControl(
    val id: String,
    val kind: ControlKind,
    val binding: Int,
    val cx: Float,
    val cy: Float,
    val widthDp: Float,
    val heightDp: Float,
    val opacity: Float = 1f,
    val haptic: Float = DEFAULT_HAPTIC,
    val options: ControlOptions = ControlOptions(),
) {
    /** Kinds that stay square when resized. */
    val keepsAspect: Boolean
        get() = kind == ControlKind.STICK || kind == ControlKind.DPAD || kind == ControlKind.FACE ||
            (kind == ControlKind.BUTTON && options.shape == ButtonShape.ROUND)

    /** The canonical button a stick press sends: L3 for the left stick, R3 for the right. */
    val stickClickBit: Int get() = if (binding == 0) PadButton.L3 else PadButton.R3

    companion object {
        const val DEFAULT_HAPTIC = 0.5f
    }
}
