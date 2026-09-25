package com.xposed.wetypehook

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import top.yukonga.miuix.kmp.nav.core.NavKey
import top.yukonga.miuix.kmp.nav.core.rememberNavBackStack
import kotlinx.serialization.Serializable


/**
 * 设置界面的导航路由。一级页是返回栈的根，二级页压在其上。
 *
 * 交给 `miuix-nav` 的 `rememberNavBackStack` 托管，所以整条层级必须可序列化，
 * 配置变更与进程重建后由库自行恢复返回栈。
 */
@Serializable
internal sealed interface SettingsRoute : NavKey {
    @Serializable
    data object Home : SettingsRoute

    @Serializable
    data class SubPage(val page: SettingsSubPage) : SettingsRoute
}

/** 一级页里的入口所指向的二级设置页，标题同时用作二级页标题栏文案。 */
@Serializable
internal enum class SettingsSubPage(val title: String) {
    COLORS("颜色"),
    CORNER_BLUR("圆角与模糊"),
    CANDIDATE_TOOLBAR("候选词与工具栏"),
    KEYBOARD_LOGO("键盘 Logo"),
    MATERIAL("高级材质"),
    CLIPBOARD("剪贴板")
}

/** 「界面美化」下暴露实时预览的二级页；剪贴板属于「功能增强」，不涉及外观。 */
internal val APPEARANCE_PREVIEW_SUB_PAGES = setOf(
    SettingsSubPage.COLORS,
    SettingsSubPage.CORNER_BLUR,
    SettingsSubPage.CANDIDATE_TOOLBAR,
    SettingsSubPage.MATERIAL,
    SettingsSubPage.KEYBOARD_LOGO
)
