package com.xposed.wetypehook

import android.graphics.Color
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color as ComposeColor
import com.xposed.wetypehook.wetype.settings.WeTypeSettings


internal fun previewTextColor(color: Int): ComposeColor =
    if (isLightColor(color)) ComposeColor.Black else ComposeColor.White

/**
 * 键面字色：半透明键帽要先与面板合成再判明暗。直接看键帽色自身的 RGB（浅色 0x2BEDEDED /
 * 深色 0x2BEEEEED）在深色档会判出黑字，而合成到深色面板上其实是深键帽、该配白字。
 * 深色档用真机截图实测的 235 灰，纯白在真机键帽上从未出现。
 */
internal fun replicaKeyLabelColor(keyColor: Int, panelColor: Int): ComposeColor =
    if (isLightColor(replicaCompositeOver(keyColor, panelColor))) {
        ComposeColor.Black
    } else {
        ComposeColor(REPLICA_KEY_LABEL_DARK_ARGB)
    }

internal fun parseLogoCustomColor(input: String): Int {
    return parseRgbColor(input) ?: WeTypeSettings.DEFAULT_LOGO_CUSTOM_COLOR
}

internal fun formatRgb(color: Int): String = String.format("#%06X", color and 0xFFFFFF)

internal fun formatArgb(color: Int): String = String.format("#%08X", color)

internal fun sanitizeHexColorInput(input: String): String? {
    val trimmed = input.trim()
    val hasPrefix = trimmed.startsWith("#")
    val body = trimmed.removePrefix("#")
    if (body.length > 8 || !body.matches(Regex("^[0-9a-fA-F]*$"))) {
        return null
    }
    return if (hasPrefix || body.isNotEmpty()) "#$body" else ""
}

internal fun sanitizeRgbColorInput(input: String): String? {
    val trimmed = input.trim()
    val hasPrefix = trimmed.startsWith("#")
    val body = trimmed.removePrefix("#")
    if (body.length > 6 || !body.matches(Regex("^[0-9a-fA-F]*$"))) {
        return null
    }
    return if (hasPrefix || body.isNotEmpty()) "#$body" else ""
}

internal fun parseHexColor(input: String): Int? {
    val body = input.trim().removePrefix("#")
    return when (body.length) {
        6, 8 -> runCatching { Color.parseColor("#$body") }.getOrNull()
        else -> null
    }
}

internal fun parseRgbColor(input: String): Int? {
    val body = input.trim().removePrefix("#")
    if (body.length != 6) return null
    return runCatching { Color.parseColor("#$body") }.getOrNull()
}

internal fun isLightColor(color: Int): Boolean {
    val luminance =
        (Color.red(color) * 0.299 + Color.green(color) * 0.587 + Color.blue(color) * 0.114) / 255
    return luminance > 0.5
}
