package com.xposed.wetypehook.wetype.settings

import androidx.annotation.StringRes
import com.xposed.wetypehook.R

const val LIGHT_KEY_COLOR_GROUP_ID = "transparent_h6"
const val DARK_KEY_COLOR_GROUP_ID = "transparent_l0"

data class WeTypeAppearanceColorGroup(
    val id: String,
    val displayName: String,
    val defaultColor: Int,
    val colorResourceNames: Set<String> = emptySet(),
    val themeAttributeNames: Set<String> = emptySet()
) {
    val entryCount: Int
        get() = colorResourceNames.size + themeAttributeNames.size

    val isKeyColorGroup: Boolean
        get() = id == LIGHT_KEY_COLOR_GROUP_ID || id == DARK_KEY_COLOR_GROUP_ID
}

object WeTypeAppearanceColorGroups {
    val groups: List<WeTypeAppearanceColorGroup> = listOf(
        WeTypeAppearanceColorGroup(
            id = "theme_color",
            displayName = "品牌强调色",
            defaultColor = 0xFF23c891.toInt(),
            colorResourceNames = setOf(
                "Brand", "Brand_100", "Brand_100_CARE", "Brand_120", "Brand_170",
                "Brand_80", "Brand_80_CARE", "Brand_90", "Brand_90_CARE",
                "Brand_Alpha_0_1", "Brand_Alpha_0_2", "Brand_Alpha_0_2_CARE",
                "Brand_Alpha_0_3", "Brand_Alpha_0_5", "Brand_BG_100",
                "Brand_BG_100_CARE", "Brand_BG_110", "Brand_BG_130", "Brand_BG_90",
                "Green", "Green_100", "Green_100_CARE", "Green_120", "Green_170",
                "Green_80", "Green_80_CARE", "Green_90", "Green_90_CARE",
                "Green_BG_100", "Green_BG_110", "Green_BG_130", "Green_BG_90",
                "LightGreen", "LightGreen_100", "LightGreen_100_CARE", "LightGreen_120",
                "LightGreen_170", "LightGreen_80", "LightGreen_80_CARE",
                "LightGreen_90", "LightGreen_90_CARE", "LightGreen_BG_100",
                "LightGreen_BG_110", "LightGreen_BG_130", "LightGreen_BG_90",
                "af", "bf", "dq", "dr", "ds", "dt",
                "ime_color_14_Alpha_10", "ime_color_14_Alpha_10_light",
                "ime_color_14_Alpha_20", "ime_color_14_Alpha_20_light",
                "ime_color_14_Alpha_30", "ime_color_14_Alpha_30_light",
                "ime_color_14_Alpha_50_dark", "ime_color_14_Alpha_50_light",
                "ime_color_14_dark", "ime_color_14_light",
                "g7", "h_", "ha", "hb", "hg", "i1", "i2", "i3", "i4", "ih",
                "k4", "l4", "l5", "l6", "ly", "qn", "qq", "qr", "xt", "a2i"
            )
        ),
        WeTypeAppearanceColorGroup(
            id = LIGHT_KEY_COLOR_GROUP_ID,
            displayName = "浅色模式按键色",
            defaultColor = 0xFFfcfcfe.toInt(),
            colorResourceNames = setOf("h6")
        ),
        WeTypeAppearanceColorGroup(
            id = DARK_KEY_COLOR_GROUP_ID,
            displayName = "深色模式按键色",
            defaultColor = 0xFF707070.toInt(),
            colorResourceNames = setOf("l0")
        )
    )

    val obsoleteGroupIds: Set<String> = setOf(
        "transparent",
        "dark_gray",
        "accent_blue",
        "accent_blue_overlay",
        "medium_gray",
        "pale_blue",
        "pale_surface",
        "off_white",
        "white"
    )

    private val groupsById = groups.associateBy { it.id }
    private val colorNameToGroup = groups.flatMap { group ->
        group.colorResourceNames.map { name -> name to group }
    }.toMap()
    private val themeAttributeToGroup = groups.flatMap { group ->
        group.themeAttributeNames.map { name -> name to group }
    }.toMap()

    fun defaultColors(): Map<String, Int> = groups.associate { it.id to it.defaultColor }

    fun findById(id: String): WeTypeAppearanceColorGroup? = groupsById[id]

    fun findByColorName(name: String): WeTypeAppearanceColorGroup? = colorNameToGroup[name]

    fun findByThemeAttributeName(name: String): WeTypeAppearanceColorGroup? = themeAttributeToGroup[name]
}
