package com.xposed.wetypehook

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.xposed.wetypehook.wetype.settings.WeTypeSettings
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference


internal fun LazyListScope.AppearanceTabContent(
    wallpaperName: String,
    onPickWallpaper: () -> Unit,
    onClearWallpaper: () -> Unit,
    onOpenSubPage: (SettingsSubPage) -> Unit,
    fontMode: Int,
    onFontModeChange: (Int) -> Unit,
    onResetFont: () -> Unit
) {
    // 1. 预览底图：预览本身只在二级页出现（一级页没有可调项），这里只留换底图的入口。
    item {
        Card(
            modifier = Modifier.padding(horizontal = 16.dp),
            insideMargin = PaddingValues(0.dp)
        ) {
            BasicComponent(
                title = "预览背景",
                summary = wallpaperName.takeIf { it.isNotEmpty() }
                    ?.let { "已设置：$it，点击可换一张" }
                    ?: "未设置，键盘面板下面只有设置页底色，看不出透明度",
                onClick = onPickWallpaper
            )
            SettingExpandGroup(visible = wallpaperName.isNotEmpty()) {
                HorizontalDivider()
                BasicComponent(
                    title = "清除预览背景",
                    summary = "去掉这层壁纸，预览恢复成纯色底",
                    onClick = onClearWallpaper
                )
            }
        }
    }

    // 2. 外观设置入口
    item {
        SmallTitle(
            text = stringResource(R.string.settings_group_appearance)
        )
        Card(
            modifier = Modifier.padding(horizontal = 16.dp),
            insideMargin = PaddingValues(0.dp)
        ) {
            Column {
                ArrowPreference(
                    title = SettingsSubPage.COLORS.title,
                    summary = "背景颜色与不透明度、按键颜色、品牌强调色",
                    onClick = { onOpenSubPage(SettingsSubPage.COLORS) }
                )
                HorizontalDivider()
                ArrowPreference(
                    title = SettingsSubPage.CORNER_BLUR.title,
                    summary = "键盘模糊强度与顶部、底部、按键圆角",
                    onClick = { onOpenSubPage(SettingsSubPage.CORNER_BLUR) }
                )
                HorizontalDivider()
                ArrowPreference(
                    title = SettingsSubPage.CANDIDATE_TOOLBAR.title,
                    summary = "候选词背景、候选栏边距与工具栏图标",
                    onClick = { onOpenSubPage(SettingsSubPage.CANDIDATE_TOOLBAR) }
                )
                HorizontalDivider()
                ArrowPreference(
                    title = SettingsSubPage.KEYBOARD_LOGO.title,
                    summary = "替换 Logo 显示、主体颜色与自定义图片",
                    onClick = { onOpenSubPage(SettingsSubPage.KEYBOARD_LOGO) }
                )
                HorizontalDivider()
                ArrowPreference(
                    title = SettingsSubPage.MATERIAL.title,
                    summary = "光感、MIUI 与 ColorOS 系统材质",
                    onClick = { onOpenSubPage(SettingsSubPage.MATERIAL) }
                )
            }
        }
    }

    // 3. 字体替换卡片
    item {
        val context = LocalContext.current
        val fontModeOptions = listOf(
            WeTypeSettings.FONT_MODE_OFFICIAL,
            WeTypeSettings.FONT_MODE_MODULE,
            WeTypeSettings.FONT_MODE_SYSTEM
        )
        SmallTitle(text = "字体替换")
        Card(
            modifier = Modifier.padding(horizontal = 16.dp),
            insideMargin = PaddingValues(0.dp)
        ) {
            Column {
                OverlayDropdownPreference(
                    title = "字体来源",
                    entry = DropdownEntry(
                        items = labeledDropdownItems(
                            options = listOf(
                                "微信官方" to "放行宿主原生字体",
                                "模块内置" to "WE-Regular 优化字体",
                                "跟随系统" to "系统默认字体"
                            ),
                            selectedIndex = fontModeOptions.indexOf(fontMode).coerceAtLeast(0),
                            onSelect = { index -> onFontModeChange(fontModeOptions[index]) }
                        )
                    )
                )
                HorizontalDivider()
                ArrowPreference(
                    title = "重置字体设置",
                    summary = "恢复跟随系统默认字体",
                    onClick = {
                        onResetFont()
                        Toast.makeText(context, "字体设置已重置", Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }
    }
}
