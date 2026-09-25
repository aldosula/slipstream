package com.slipstream.wheel.pad.layout

import com.slipstream.wheel.pad.GyroAim
import com.slipstream.wheel.pad.PadStyle

/**
 * A profile: a named list of controls plus the options that apply to the whole layout.
 * [refWidthDp] x [refHeightDp] is the drawing area the layout was made on; on other screens
 * positions scale with the area and sizes stay in dp, shrunk uniformly only when two
 * controls that are apart on the reference area would otherwise overlap.
 */
data class PadLayout(
    val id: String,
    val name: String,
    val style: PadStyle,
    val builtIn: Boolean,
    val controls: List<PadControl>,
    val slideToPress: Boolean = true,
    val gyroMode: GyroAim.Mode = GyroAim.Mode.OFF,
    val gyroSensitivity: Float = GyroAim.SENSITIVITY_DEFAULT,
    val gyroInvertY: Boolean = false,
    val refWidthDp: Float = DefaultLayouts.REF_WIDTH_DP,
    val refHeightDp: Float = DefaultLayouts.REF_HEIGHT_DP,
) {
    fun control(id: String): PadControl? = controls.firstOrNull { it.id == id }
}
