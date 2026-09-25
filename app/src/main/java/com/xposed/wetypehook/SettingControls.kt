package com.xposed.wetypehook

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import top.yukonga.miuix.kmp.preference.SliderPreference
import kotlin.math.roundToInt


@Composable
internal fun SliderPreferenceItem(
    title: String,
    value: Int,
    max: Int,
    enabled: Boolean = true,
    onValueChange: (Int) -> Unit
) = SliderPreference(
    value = value.toFloat(),
    onValueChange = { onValueChange(it.roundToInt()) },
    title = title,
    valueText = value.toString(),
    enabled = enabled,
    valueRange = 0f..max.toFloat(),
    steps = (max - 1).coerceAtLeast(0)
)

@Composable
internal fun SliderPreferenceItem(
    title: String,
    summary: String? = null,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    enabled: Boolean = true,
    format: (Float) -> String,
    onValueChange: (Float) -> Unit
) = SliderPreference(
    value = value.coerceIn(range),
    onValueChange = { onValueChange(((it / step).roundToInt() * step).coerceIn(range)) },
    title = title,
    summary = summary,
    valueText = format(value),
    enabled = enabled,
    valueRange = range,
    steps = (((range.endInclusive - range.start) / step).roundToInt() - 1).coerceAtLeast(0)
)
