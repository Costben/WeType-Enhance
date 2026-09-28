package com.xposed.wetypehook.wetype.settings

/**
 * The user-facing catalogue of the ColorOS material presets.
 *
 * The full 16-slot source values live in docs/coloros-material/preset-catalog.js. The module
 * stores only the stable id in preferences; the renderer resolves that id to the matching native
 * shadow family. Keeping ids here prevents display labels from becoming part of the preference
 * format and gives the UI one gate for the normal/advanced lists.
 */
enum class MaterialPresetTarget {
    BACKGROUND,
    ICON,
    KEY
}

data class MaterialPresetDefinition(
    val id: String,
    val label: String,
    val sourceGroup: String,
    val targets: Set<MaterialPresetTarget>
)

object MaterialPresetCatalog {
    /**
     * 分类下拉的第一项：这一类元素不修改。
     *
     * 它是与目录项并列的一个合法选择，不是「没选」。绘制侧必须显式跳过它，绝不能因为查不到
     * 目录项就退回某套默认阴影栈——那会让「不修改」变成「套一层看不出来的默认光感」。
     */
    const val NONE = "none"

    const val DEFAULT_BACKGROUND = "systemui:notif-light"
    const val DEFAULT_ICON = "settings:back-circle-light"
    const val DEFAULT_KEY = "systemui:qs-tile-inactive-light"

    /** 「高级参数调节」创建的自定义预设，id 形如 `custom:classic:3` / `custom:coloros:3`。 */
    const val CUSTOM_PREFIX = "custom:"
    const val FAMILY_CLASSIC = "classic"
    const val FAMILY_COLOROS = "coloros"

    /** 下拉里的「无预设」项；任何对象都可选，不属于任何来源组。 */
    val NONE_DEFINITION = MaterialPresetDefinition(
        id = NONE,
        label = "无预设",
        sourceGroup = NONE,
        targets = MaterialPresetTarget.entries.toSet()
    )

    val all: List<MaterialPresetDefinition> = listOf(
        MaterialPresetDefinition("coui:edge-0", "COUI 边缘光 0", "coui", setOf(MaterialPresetTarget.BACKGROUND, MaterialPresetTarget.ICON, MaterialPresetTarget.KEY)),
        MaterialPresetDefinition("coui:edge-1", "COUI 边缘光 1", "coui", setOf(MaterialPresetTarget.BACKGROUND, MaterialPresetTarget.ICON, MaterialPresetTarget.KEY)),
        MaterialPresetDefinition("coui:edge-2", "COUI 边缘光 2", "coui", setOf(MaterialPresetTarget.BACKGROUND, MaterialPresetTarget.ICON, MaterialPresetTarget.KEY)),
        MaterialPresetDefinition("coui:spec-default", "COUI 默认 Spec", "coui", setOf(MaterialPresetTarget.BACKGROUND, MaterialPresetTarget.ICON, MaterialPresetTarget.KEY)),
        MaterialPresetDefinition("launcher:PAGE_INDICATOR", "桌面 · 页面指示器", "launcher", setOf(MaterialPresetTarget.BACKGROUND, MaterialPresetTarget.ICON)),
        MaterialPresetDefinition("launcher:DOCK", "桌面 · Dock", "launcher", setOf(MaterialPresetTarget.BACKGROUND, MaterialPresetTarget.ICON)),
        MaterialPresetDefinition("launcher:CARD", "桌面 · 卡片", "launcher", setOf(MaterialPresetTarget.BACKGROUND, MaterialPresetTarget.ICON)),
        MaterialPresetDefinition("launcher:GROUP_CARD", "桌面 · 文件夹卡片", "launcher", setOf(MaterialPresetTarget.BACKGROUND, MaterialPresetTarget.ICON)),
        MaterialPresetDefinition("launcher:WIDGET", "桌面 · 小组件", "launcher", setOf(MaterialPresetTarget.BACKGROUND, MaterialPresetTarget.ICON)),
        MaterialPresetDefinition("launcher:MIDDLE_FOLDER", "桌面 · 中型文件夹", "launcher", setOf(MaterialPresetTarget.BACKGROUND, MaterialPresetTarget.ICON)),
        MaterialPresetDefinition("launcher:SMALL_FOLDER", "桌面 · 小型文件夹", "launcher", setOf(MaterialPresetTarget.BACKGROUND, MaterialPresetTarget.ICON)),
        MaterialPresetDefinition("launcher:BIG_FOLDER", "桌面 · 大型文件夹", "launcher", setOf(MaterialPresetTarget.BACKGROUND, MaterialPresetTarget.ICON)),
        MaterialPresetDefinition("launcher:MIDDLE_1_2_FOLDER", "桌面 · 横向文件夹", "launcher", setOf(MaterialPresetTarget.BACKGROUND, MaterialPresetTarget.ICON)),
        MaterialPresetDefinition("launcher:MIDDLE_2_1_FOLDER", "桌面 · 纵向文件夹", "launcher", setOf(MaterialPresetTarget.BACKGROUND, MaterialPresetTarget.ICON)),
        MaterialPresetDefinition("launcher:PRESS_FEEDBACK", "桌面 · 按压反馈", "launcher", setOf(MaterialPresetTarget.ICON, MaterialPresetTarget.KEY)),
        MaterialPresetDefinition("launcher:TOGGLE_TOP_BUTTON", "桌面 · 顶部切换按钮", "launcher", setOf(MaterialPresetTarget.ICON, MaterialPresetTarget.KEY)),
        MaterialPresetDefinition("launcher:TOGGLE_BOTTOM_BUTTON", "桌面 · 底部切换按钮", "launcher", setOf(MaterialPresetTarget.ICON, MaterialPresetTarget.KEY)),
        MaterialPresetDefinition("launcher:ALL_APPS_CATEGORY", "桌面 · 应用分类", "launcher", setOf(MaterialPresetTarget.BACKGROUND, MaterialPresetTarget.ICON)),
        MaterialPresetDefinition("launcher:ALL_APPS_SUGGESTION", "桌面 · 应用推荐", "launcher", setOf(MaterialPresetTarget.BACKGROUND, MaterialPresetTarget.ICON)),
        MaterialPresetDefinition("launcher:PREVIEW_PAGE_EFFECT", "桌面 · 页面预览", "launcher", setOf(MaterialPresetTarget.BACKGROUND, MaterialPresetTarget.ICON)),
        MaterialPresetDefinition("settings:back-circle-light", "设置 · 返回键（浅色）", "settings", setOf(MaterialPresetTarget.ICON)),
        MaterialPresetDefinition("settings:back-circle-dark", "设置 · 返回键（深色）", "settings", setOf(MaterialPresetTarget.ICON)),
        MaterialPresetDefinition("systemui:notif-light", "控制中心 · 通知卡（浅色）", "systemui", setOf(MaterialPresetTarget.BACKGROUND)),
        MaterialPresetDefinition("systemui:notif-dark", "控制中心 · 通知卡（深色）", "systemui", setOf(MaterialPresetTarget.BACKGROUND)),
        MaterialPresetDefinition("systemui:seekbar-bg-light", "控制中心 · 亮度条未填充（浅色）", "systemui", setOf(MaterialPresetTarget.BACKGROUND, MaterialPresetTarget.KEY)),
        MaterialPresetDefinition("systemui:seekbar-bg-dark", "控制中心 · 亮度条未填充（深色）", "systemui", setOf(MaterialPresetTarget.BACKGROUND, MaterialPresetTarget.KEY)),
        MaterialPresetDefinition("systemui:seekbar-pg-light", "控制中心 · 亮度条已填充（浅色）", "systemui", setOf(MaterialPresetTarget.BACKGROUND, MaterialPresetTarget.KEY)),
        MaterialPresetDefinition("systemui:seekbar-pg-dark", "控制中心 · 亮度条已填充（深色）", "systemui", setOf(MaterialPresetTarget.BACKGROUND, MaterialPresetTarget.KEY)),
        MaterialPresetDefinition("systemui:statusbar-capsule", "状态栏 · 锁屏胶囊（单卡）", "statusbar", setOf(MaterialPresetTarget.BACKGROUND)),
        MaterialPresetDefinition("systemui:statusbar-capsule-multi", "状态栏 · 锁屏胶囊（多卡）", "statusbar", setOf(MaterialPresetTarget.BACKGROUND)),
        MaterialPresetDefinition("systemui:qs-tile-inactive-light", "控制中心 · 快捷开关未激活（浅色）", "qs", setOf(MaterialPresetTarget.KEY, MaterialPresetTarget.ICON)),
        MaterialPresetDefinition("systemui:qs-tile-active-light", "控制中心 · 快捷开关激活（浅色）", "qs", setOf(MaterialPresetTarget.KEY, MaterialPresetTarget.ICON)),
        MaterialPresetDefinition("systemui:qs-tile-inactive-dark", "控制中心 · 快捷开关未激活（深色）", "qs", setOf(MaterialPresetTarget.KEY, MaterialPresetTarget.ICON)),
        MaterialPresetDefinition("systemui:qs-tile-active-dark", "控制中心 · 快捷开关激活（深色）", "qs", setOf(MaterialPresetTarget.KEY, MaterialPresetTarget.ICON)),
        MaterialPresetDefinition("systemui:volume-slider-light", "控制中心 · 音量条（浅色）", "volume", setOf(MaterialPresetTarget.KEY, MaterialPresetTarget.BACKGROUND)),
        MaterialPresetDefinition("systemui:volume-slider-dark", "控制中心 · 音量条（深色）", "volume", setOf(MaterialPresetTarget.KEY, MaterialPresetTarget.BACKGROUND))
    )

    private val byId = (all + NONE_DEFINITION).associateBy { it.id }

    fun isNone(id: String): Boolean = id == NONE

    fun isCustom(id: String): Boolean = id.startsWith(CUSTOM_PREFIX)

    /** 绘制侧能不能把这个 id 解析成一套阴影栈；「无预设」和自定义预设都算能。 */
    fun isResolvable(id: String): Boolean = isNone(id) || isCustom(id) || find(id) != null

    /**
     * 目录项落到哪一套阴影栈家族：COUI 那批走经典栈，其余（含自定义预设）走 ColorOS 栈。
     *
     * 绘制侧的 `WeTypeEdgeLightPreset.forMaterialPreset` 读的就是这里；自定义预设把家族写在 id 里，
     * 所以渲染进程不需要为了取家族再去读一遍偏好文件。
     */
    fun shadowFamily(id: String): String = when {
        id.startsWith("$CUSTOM_PREFIX$FAMILY_CLASSIC:") -> FAMILY_CLASSIC
        id.startsWith("$CUSTOM_PREFIX$FAMILY_COLOROS:") -> FAMILY_COLOROS
        id.startsWith("coui:") -> FAMILY_CLASSIC
        else -> FAMILY_COLOROS
    }

    /** 由复制来源与序号生成一个稳定的自定义预设 id。 */
    fun customPresetId(baseId: String, index: Int): String =
        "$CUSTOM_PREFIX${shadowFamily(baseId)}:$index"

    fun find(id: String): MaterialPresetDefinition? = byId[id]

    fun label(id: String): String = find(id)?.label ?: id

    fun options(target: MaterialPresetTarget, allowAll: Boolean): List<MaterialPresetDefinition> =
        if (allowAll) all else all.filter { target in it.targets }

    /** 下拉列表：「无预设」恒在第一位，其后是当前允许的目录项。 */
    fun selectableOptions(
        target: MaterialPresetTarget,
        allowAll: Boolean
    ): List<MaterialPresetDefinition> = listOf(NONE_DEFINITION) + options(target, allowAll)

    fun normalize(id: String, target: MaterialPresetTarget): String = when {
        isNone(id) || isCustom(id) -> id
        find(id)?.let { target in it.targets } == true -> id
        else -> options(target, allowAll = false).firstOrNull()?.id ?: NONE
    }
}
