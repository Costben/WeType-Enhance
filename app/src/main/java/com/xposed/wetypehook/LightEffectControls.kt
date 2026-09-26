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
    title: String = "光照方向",
    onValueChange: (Int) -> Unit
) {
    SliderPreference(
        value = value.coerceIn(MIN_LIGHT_ANGLE, MAX_LIGHT_ANGLE).toFloat(),
        onValueChange = {
            onValueChange(it.roundToInt().coerceIn(MIN_LIGHT_ANGLE, MAX_LIGHT_ANGLE))
        },
        modifier = Modifier.semantics { contentDescription = title },
        title = title,
        summary = buildString {
            append("光感方向的总控，背景、图标、按键共用：0°/180° 照亮左右，90°/270° 照亮上下，45°/135° 为对角。")
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
    onValueChange: (Int) -> Unit,
    title: String = "宽度"
) {
    SliderPreference(
        value = value.coerceIn(MIN_EDGE_LIGHT_WIDTH, MAX_EDGE_LIGHT_WIDTH).toFloat(),
        onValueChange = {
            onValueChange(it.roundToInt().coerceIn(MIN_EDGE_LIGHT_WIDTH, MAX_EDGE_LIGHT_WIDTH))
        },
        modifier = Modifier.semantics { contentDescription = title },
        title = title,
        valueText = "${value}dp",
        valueRange = MIN_EDGE_LIGHT_WIDTH.toFloat()..MAX_EDGE_LIGHT_WIDTH.toFloat(),
        steps = (MAX_EDGE_LIGHT_WIDTH - MIN_EDGE_LIGHT_WIDTH - 1).coerceAtLeast(0)
    )
}

@Composable
internal fun LightIntensitySlider(
    value: Int,
    onValueChange: (Int) -> Unit,
    title: String = "强度",
    summary: String? = null,
    min: Int = 0,
    max: Int = MAX_EDGE_HIGHLIGHT_INTENSITY
) {
    SliderPreference(
        value = value.coerceIn(min, max).toFloat(),
        onValueChange = {
            onValueChange(it.roundToInt().coerceIn(min, max))
        },
        modifier = Modifier.semantics { contentDescription = title },
        title = title,
        summary = summary,
        valueText = "$value",
        valueRange = min.toFloat()..max.toFloat(),
        steps = (max - min - 1).coerceAtLeast(0)
    )
}

@Composable
internal fun NativeLightIntensitySlider(
    value: Int,
    onValueChange: (Int) -> Unit
) {
    SliderPreference(
        value = value.coerceIn(0, MAX_EDGE_HIGHLIGHT_INTENSITY).toFloat(),
        onValueChange = {
            onValueChange(it.roundToInt().coerceIn(0, MAX_EDGE_HIGHLIGHT_INTENSITY))
        },
        modifier = Modifier.semantics { contentDescription = "ColorOS 原生材质强度" },
        title = "原生材质强度",
        summary = "只作用于 ColorOS 系统材质的按键、工具栏图标、Logo 与背板。",
        valueText = "$value",
        valueRange = 0f..MAX_EDGE_HIGHLIGHT_INTENSITY.toFloat(),
        steps = (MAX_EDGE_HIGHLIGHT_INTENSITY - 1).coerceAtLeast(0)
    )
}
