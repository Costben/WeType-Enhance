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
    val glowWidth: Int = WeTypeSettings.DEFAULT_GLOW_WIDTH,
    /** Stable id from [MaterialPresetCatalog]; the renderer resolves the actual material stack. */
    val presetId: String = MaterialPresetCatalog.DEFAULT_KEY
) {
    /** Legacy compact form kept stable for existing preference files and migration tests. */
    fun text(): String = listOf(
        if (enabled) 1 else 0,
        if (edgeEnabled) 1 else 0,
        edgeIntensity,
        edgeWidth,
        if (glowEnabled) 1 else 0,
        glowIntensity,
        glowWidth
    ).joinToString(SEPARATOR)

    /** Extended form for callers that need a self-contained export of the selected preset. */
    fun textWithPreset(): String = listOf(text(), presetId).joinToString(SEPARATOR)

    /**
     * 分类下拉选中一项之后的整组状态。
     *
     * 选「无预设」= 这一类不修改，所以 [enabled] 一并落下；选到任何真实预设（内置或自定义）
     * 就重新打开。下拉是这页唯一的入口，两个字段不能各改各的。
     */
    fun withPreset(presetId: String): EdgeLightGroup =
        if (MaterialPresetCatalog.isNone(presetId)) {
            copy(enabled = false, presetId = presetId)
        } else if (!MaterialPresetCatalog.isCustom(presetId)) {
            // Built-in entries are the replacement for the old edge/glow controls. Older
            // installations can still have both switches stored as 0, which would make a
            // newly selected preset render nothing because every layer is gated off.
            copy(
                enabled = true,
                edgeEnabled = true,
                edgeIntensity = WeTypeSettings.DEFAULT_EDGE_HIGHLIGHT_INTENSITY,
                edgeWidth = WeTypeSettings.DEFAULT_EDGE_LIGHT_WIDTH,
                glowEnabled = true,
                glowIntensity = WeTypeSettings.DEFAULT_GLOW_INTENSITY,
                glowWidth = WeTypeSettings.DEFAULT_GLOW_WIDTH,
                presetId = presetId
            )
        } else {
            copy(enabled = true, presetId = presetId)
        }

    /** Bring a stored built-in selection forward after the old edge/glow switches were removed. */
    fun migrateBuiltInPreset(): EdgeLightGroup =
        if (!MaterialPresetCatalog.isNone(presetId) && !MaterialPresetCatalog.isCustom(presetId)) {
            withPreset(presetId)
        } else {
            this
        }

    /**
     * 套用一份自定义预设：把它的边缘/内发光开关、强度、宽度搬进来，并把 id 记成本预设。
     *
     * 自定义预设的角度是三类共用的总控，不在这里——由调用方一起写进 `edgeLightAngle`。
     * 只有开关、强度、宽度这些绘制侧本来就读 [EdgeLightGroup] 的参数才搬得动；目录里那 32 个
     * 槽位渲染侧并不消费，所以这里不假装它们生效。
     */
    fun applying(preset: AdvancedLightPreset): EdgeLightGroup = copy(
        enabled = true,
        edgeEnabled = preset.edgeEnabled,
        edgeIntensity = preset.edgeIntensity,
        edgeWidth = preset.edgeWidth,
        glowEnabled = preset.glowEnabled,
        glowIntensity = preset.glowIntensity,
        glowWidth = preset.glowWidth,
        presetId = preset.id
    )

    /** 正在用这份自定义预设时跟着它的新值走；其余对象原样不动。 */
    fun syncIfUsing(preset: AdvancedLightPreset): EdgeLightGroup =
        if (presetId == preset.id) applying(preset) else this

    /** 预设被删掉后落回「无预设」，不留一个查不到的 id 在偏好文件里。 */
    fun dropPreset(id: String): EdgeLightGroup =
        if (presetId == id) withPreset(MaterialPresetCatalog.NONE) else this

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
        ),
        presetId = presetId.takeIf { MaterialPresetCatalog.isResolvable(it) }
            ?: MaterialPresetCatalog.NONE
    )

    companion object {
        private const val SEPARATOR = ","
        private const val LEGACY_FIELD_COUNT = 7
        private const val FIELD_COUNT = 8

        /**
         * 「光感设置」页上三类的默认状态：**无预设**。
         *
         * 三个对象各自独立，但默认是同一件事——不修改，不套用任何预设。`target` 保留是为了让
         * 三处调用点（背景 / 图标 / 按键）读起来对称，取值目前与它无关。
         */
        fun defaultFor(target: MaterialPresetTarget): EdgeLightGroup = EdgeLightGroup(
            enabled = false,
            presetId = MaterialPresetCatalog.NONE
        )

        /**
         * 解析 [text] 写出的串；[fallback] 在字段数不对或含非法数字时整体兜底。
         *
         * 只认全串：半解析出一组错值，比整组退回旧值更难查。
         */
        fun parse(text: String?, fallback: EdgeLightGroup): EdgeLightGroup {
            if (text == null) return fallback
            val parts = text.split(SEPARATOR)
            if (parts.size != FIELD_COUNT && parts.size != LEGACY_FIELD_COUNT) return fallback
            val parsedPreset = if (parts.size == FIELD_COUNT) {
                parts[7].trim().takeIf { MaterialPresetCatalog.isResolvable(it) } ?: return fallback
            } else {
                fallback.presetId
            }
            val numbers = parts.take(LEGACY_FIELD_COUNT)
                .map { it.trim().toIntOrNull() ?: return fallback }
            return EdgeLightGroup(
                enabled = numbers[0] != 0,
                edgeEnabled = numbers[1] != 0,
                edgeIntensity = numbers[2],
                edgeWidth = numbers[3],
                glowEnabled = numbers[4] != 0,
                glowIntensity = numbers[5],
                glowWidth = numbers[6],
                presetId = parsedPreset
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

/**
 * 光感用哪一套阴影栈。
 *
 * 两个风格各分大面积与小面积两档（见 `WeTypeEdgeLightPreset`）：
 *
 * - [COLOROS]：照 ColorOS 原生的两套参数画。底边最亮、顶边次之、左右最弱，并带一层整圈内提亮；
 *   大面积那档多一圈均匀描边 —— 系统就是这么分的。
 * - [CLASSIC]：模块最早那套，出处是 Apple 的设计规范，四边按光源方向对称展开。
 *
 * [defaultAngle] 是这套风格自带的光照方向：ColorOS 那套按「光源在正下方」排的栈，所以默认 270°；
 * 经典那套的偏移按「光源在左上 45°」写死，默认 45°。
 */
enum class WeTypeEdgeLightStyle(val defaultAngle: Int) {
    COLOROS(270),
    CLASSIC(45);

    companion object {
        fun fromOrdinal(value: Int): WeTypeEdgeLightStyle =
            entries.getOrElse(value) { entries.first() }
    }
}
