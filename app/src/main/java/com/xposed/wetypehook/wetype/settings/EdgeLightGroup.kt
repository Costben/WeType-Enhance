package com.xposed.wetypehook.wetype.settings

/**
 * 一类元素（背景 / 图标 / 按键）的光感设置。
 *
 * 光感由两种视觉层组成，两层各有自己的开关、强度与宽度：
 *
 * - [edgeEnabled] 边缘：贴着轮廓的一圈锐利高光，也就是阴影栈里最前面那一层。
 * - [glowEnabled] 内发光：内圈的柔和提亮，加上紧跟其后的压暗层。提亮与压暗同属一层，
 *   共用 [glowIntensity]，不拆成两根滑杆。
 *
 * 角度不在这里：它是三类共用的总控，见 `WeTypeSettings` 的 `edgeLightAngle`。
 *
 * 持久化用紧凑逗号串（[text] / [parse]），不摊成七个独立偏好键——同一批字段要在快照、
 * 写盘、桥接 Bundle、两条读取路径和三个保存入口里各枚举一遍，键一多必漏。
 */
data class EdgeLightGroup(
    val enabled: Boolean = true,
    val edgeEnabled: Boolean = true,
    val edgeIntensity: Int = WeTypeSettings.DEFAULT_EDGE_HIGHLIGHT_INTENSITY,
    val edgeWidth: Int = WeTypeSettings.DEFAULT_EDGE_LIGHT_WIDTH,
    val glowEnabled: Boolean = true,
    val glowIntensity: Int = WeTypeSettings.DEFAULT_GLOW_INTENSITY,
    val glowWidth: Int = WeTypeSettings.DEFAULT_GLOW_WIDTH
) {
    fun text(): String = listOf(
        if (enabled) 1 else 0,
        if (edgeEnabled) 1 else 0,
        edgeIntensity,
        edgeWidth,
        if (glowEnabled) 1 else 0,
        glowIntensity,
        glowWidth
    ).joinToString(SEPARATOR)

    /** 夹进各滑杆的合法区间。手改过的偏好文件不会把绘制倍数顶到荒谬的值。 */
    fun normalized(): EdgeLightGroup = copy(
        edgeIntensity = edgeIntensity.coerceIn(0, WeTypeSettings.MAX_EDGE_HIGHLIGHT_INTENSITY),
        edgeWidth = edgeWidth.coerceIn(
            WeTypeSettings.MIN_EDGE_LIGHT_WIDTH,
            WeTypeSettings.MAX_EDGE_LIGHT_WIDTH
        ),
        glowIntensity = glowIntensity.coerceIn(
            WeTypeSettings.MIN_GLOW_INTENSITY,
            WeTypeSettings.MAX_GLOW_INTENSITY
        ),
        glowWidth = glowWidth.coerceIn(
            WeTypeSettings.MIN_GLOW_WIDTH,
            WeTypeSettings.MAX_GLOW_WIDTH
        )
    )

    companion object {
        private const val SEPARATOR = ","
        private const val FIELD_COUNT = 7

        /**
         * 解析 [text] 写出的串；[fallback] 在字段数不对或含非法数字时整体兜底。
         *
         * 只认全串：半解析出一组错值，比整组退回旧值更难查。
         */
        fun parse(text: String?, fallback: EdgeLightGroup): EdgeLightGroup {
            if (text == null) return fallback
            val parts = text.split(SEPARATOR)
            if (parts.size != FIELD_COUNT) return fallback
            val numbers = parts.map { it.trim().toIntOrNull() ?: return fallback }
            return EdgeLightGroup(
                enabled = numbers[0] != 0,
                edgeEnabled = numbers[1] != 0,
                edgeIntensity = numbers[2],
                edgeWidth = numbers[3],
                glowEnabled = numbers[4] != 0,
                glowIntensity = numbers[5],
                glowWidth = numbers[6]
            ).normalized()
        }
    }
}

/** 光感作用的三类元素。绘制侧按这个标识取自己那一份 [EdgeLightGroup]。 */
enum class EdgeLightTarget {
    BACKGROUND,
    ICON,
    KEY
}
