package com.xposed.wetypehook

import android.graphics.Color
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color as ComposeColor
import com.xposed.wetypehook.wetype.settings.WeTypeSettings
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow


internal fun previewTextColor(color: Int): ComposeColor =
    if (isLightColor(color)) ComposeColor.Black else ComposeColor.White

/**
 * 键面字色：半透明键帽要先与面板合成再判明暗。直接看键帽色自身的 RGB（浅色 0x2BEDEDED /
 * 深色 0x2BEEEEED）在深色档会判出黑字，而合成到深色面板上其实是深键帽、该配白字。
 * 深色档用真机截图实测的 235 灰，纯白在真机键帽上从未出现。
 */
internal fun replicaKeyLabelColor(keyColor: Int, panelColor: Int): ComposeColor =
    if (isLightColor(replicaCompositeOver(keyColor, panelColor))) {
        ComposeColor(REPLICA_KEY_LABEL_LIGHT_ARGB)
    } else {
        ComposeColor(REPLICA_KEY_LABEL_DARK_ARGB)
    }

/**
 * 半透明键帽算字色时用的中性底。
 *
 * 真机上键帽色是叠在键盘面板上画的，而算字色的两处（hook 与设置页）都拿不到面板色。
 * 用中灰代替，好处只有一条：两处走的是同一条规则，编辑页里看到的字色就是键盘上会出现的。
 */
private const val PER_KEY_NEUTRAL_BACKDROP = 0xFF808080.toInt()

/**
 * WCAG 相对亮度：先把每个通道从 sRGB 转到线性空间再按人眼敏感度加权。
 * 直接拿 0.299/0.587/0.114 算的是 sRGB 值本身，在暗部会明显偏亮，判中间色时容易判反。
 *
 * 通道值自己从 ARGB 里取，不走 `android.graphics.Color` —— 这条规则要能在 JVM 单测里跑
 * （[replicaCompositeOver] 同样只用位运算，是同一个理由）。
 */
private fun relativeLuminance(color: Int): Double {
    fun channel(value: Int): Double {
        val srgb = value / 255.0
        return if (srgb <= 0.03928) srgb / 12.92 else ((srgb + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * channel((color shr 16) and 0xFF) +
        0.7152 * channel((color shr 8) and 0xFF) +
        0.0722 * channel(color and 0xFF)
}

/** WCAG 对比度，取值 1:1 ~ 21:1，与前景背景顺序无关。 */
internal fun contrastRatio(foreground: Int, background: Int): Float {
    val a = relativeLuminance(foreground)
    val b = relativeLuminance(background)
    return ((max(a, b) + 0.05) / (min(a, b) + 0.05)).toFloat()
}

/** 一个候选字色，以及它相对键帽底色的 WCAG 对比度。 */
internal data class PerKeyLabelChoice(val color: Int, val contrast: Float)

/**
 * 逐键覆盖后的两个候选键面字色，按对比度从高到低 —— `first()` 就是自动会选的那个。
 *
 * 宿主只按自己的主题属性给键面文字/图标上色，键帽底色被逐键改掉之后**不会**跟着反色，
 * 深底配原字色就整颗看不见了。两个候选就是真机自己那两档字色：浅色档的黑字与深色档实测的
 * 235 灰，各算一次对比度取高的那个 —— 所以「蓝色键帽该配白字还是黑字」是量出来的，不是按
 * 亮度阈值猜的。
 *
 * 只给设置页用（要把两个数值一起报给用户），键盘绘制路径走 [perKeyLabelColor]。
 */
internal fun perKeyLabelChoices(background: Int): List<PerKeyLabelChoice> {
    val opaque = replicaCompositeOver(background, PER_KEY_NEUTRAL_BACKDROP)
    val black = PerKeyLabelChoice(
        REPLICA_KEY_LABEL_LIGHT_ARGB,
        contrastRatio(REPLICA_KEY_LABEL_LIGHT_ARGB, opaque)
    )
    val light = PerKeyLabelChoice(
        REPLICA_KEY_LABEL_DARK_ARGB,
        contrastRatio(REPLICA_KEY_LABEL_DARK_ARGB, opaque)
    )
    // sortedByDescending 是稳定排序，对比度打平时黑字排前面，与 [perKeyLabelColor] 的
    // `blackContrast >= lightContrast` 取同一个。
    return listOf(black, light).sortedByDescending { it.contrast }
}

/** [perKeyLabelChoices] 里自动会选的那一个。 */
internal fun autoPerKeyLabel(background: Int): PerKeyLabelChoice = perKeyLabelChoices(background).first()

/**
 * 键面字色，[invert] 打开时取另一个候选 —— 「字符反色」开关是给对比度算不出明显胜负的中间色
 * 留的手动兜底，所以它只翻转自动结果，不引入第三个颜色。
 *
 * 规则与 [perKeyLabelChoices] 逐字一致，但刻意不建中间列表：这条路每帧、每颗键都要走一遍，
 * 而键帽解析器缓存 JSON 就是为了不让绘制路径上出现解析开销。
 */
internal fun perKeyLabelColor(background: Int, invert: Boolean = false): Int {
    val opaque = replicaCompositeOver(background, PER_KEY_NEUTRAL_BACKDROP)
    val blackContrast = contrastRatio(REPLICA_KEY_LABEL_LIGHT_ARGB, opaque)
    val lightContrast = contrastRatio(REPLICA_KEY_LABEL_DARK_ARGB, opaque)
    val useBlack = (blackContrast >= lightContrast) != invert
    return if (useBlack) REPLICA_KEY_LABEL_LIGHT_ARGB else REPLICA_KEY_LABEL_DARK_ARGB
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
