package com.xposed.wetypehook

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.xposed.wetypehook.wetype.settings.WeTypeSettings
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.preference.SliderPreference

internal const val MIN_LIGHT_ANGLE = 0
internal const val MAX_LIGHT_ANGLE = WeTypeSettings.MAX_COLOROS_LIGHT_ANGLE
internal const val MAX_EDGE_HIGHLIGHT_INTENSITY = WeTypeSettings.MAX_EDGE_HIGHLIGHT_INTENSITY
internal const val MIN_GLOW_INTENSITY = WeTypeSettings.MIN_GLOW_INTENSITY
internal const val MAX_GLOW_INTENSITY = WeTypeSettings.MAX_GLOW_INTENSITY
internal const val MIN_EDGE_LIGHT_WIDTH = WeTypeSettings.MIN_EDGE_LIGHT_WIDTH
internal const val MAX_EDGE_LIGHT_WIDTH = WeTypeSettings.MAX_EDGE_LIGHT_WIDTH

@Composable
internal fun LightAngleSlider(
    value: Int,
    note: String? = null,
    onValueChange: (Int) -> Unit
) {
    SliderPreference(
        value = value.coerceIn(MIN_LIGHT_ANGLE, MAX_LIGHT_ANGLE).toFloat(),
        onValueChange = {
            onValueChange(it.roundToInt().coerceIn(MIN_LIGHT_ANGLE, MAX_LIGHT_ANGLE))
        },
        modifier = Modifier.semantics { contentDescription = "光照角度" },
        title = "光照角度",
        summary = buildString {
            append("0°/180° 照亮左右，90°/270° 照亮上下，45°/135° 为对角。")
            if (note != null) append("\n").append(note)
        },
        valueText = "$value°",
        valueRange = MIN_LIGHT_ANGLE.toFloat()..MAX_LIGHT_ANGLE.toFloat(),
        steps = (MAX_LIGHT_ANGLE - MIN_LIGHT_ANGLE - 1).coerceAtLeast(0)
    )
}

@Composable
internal fun LightWidthSlider(
    value: Int,
    onValueChange: (Int) -> Unit
) {
    SliderPreference(
        value = value.coerceIn(MIN_EDGE_LIGHT_WIDTH, MAX_EDGE_LIGHT_WIDTH).toFloat(),
        onValueChange = {
            onValueChange(it.roundToInt().coerceIn(MIN_EDGE_LIGHT_WIDTH, MAX_EDGE_LIGHT_WIDTH))
        },
        modifier = Modifier.semantics { contentDescription = "边缘光宽度" },
        title = "宽度",
        valueText = "${value}dp",
        valueRange = MIN_EDGE_LIGHT_WIDTH.toFloat()..MAX_EDGE_LIGHT_WIDTH.toFloat(),
        steps = (MAX_EDGE_LIGHT_WIDTH - MIN_EDGE_LIGHT_WIDTH - 1).coerceAtLeast(0)
    )
}

@Composable
internal fun LightIntensitySlider(
    value: Int,
    onValueChange: (Int) -> Unit
) {
    SliderPreference(
        value = value.coerceIn(0, MAX_EDGE_HIGHLIGHT_INTENSITY).toFloat(),
        onValueChange = {
            onValueChange(it.roundToInt().coerceIn(0, MAX_EDGE_HIGHLIGHT_INTENSITY))
        },
        modifier = Modifier.semantics { contentDescription = "边缘光效强度" },
        title = "强度",
        valueText = "$value",
        valueRange = 0f..MAX_EDGE_HIGHLIGHT_INTENSITY.toFloat(),
        steps = (MAX_EDGE_HIGHLIGHT_INTENSITY - 1).coerceAtLeast(0)
    )
}

@Composable
internal fun LightGlowSlider(
    value: Int,
    onValueChange: (Int) -> Unit
) {
    SliderPreference(
        value = value.coerceIn(MIN_GLOW_INTENSITY, MAX_GLOW_INTENSITY).toFloat(),
        onValueChange = {
            onValueChange(it.roundToInt().coerceIn(MIN_GLOW_INTENSITY, MAX_GLOW_INTENSITY))
        },
        modifier = Modifier.semantics { contentDescription = "按键发光强度" },
        title = "按键发光强度",
        summary = "同时决定键帽与 Logo／工具栏图标内发光的强弱，调高更亮、调低更内凹。",
        valueText = "$value",
        valueRange = MIN_GLOW_INTENSITY.toFloat()..MAX_GLOW_INTENSITY.toFloat(),
        steps = (MAX_GLOW_INTENSITY - MIN_GLOW_INTENSITY - 1).coerceAtLeast(0)
    )
}
