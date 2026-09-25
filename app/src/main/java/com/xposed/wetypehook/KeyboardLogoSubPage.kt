package com.xposed.wetypehook

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.xposed.wetypehook.wetype.settings.WeTypeSettings
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme


internal fun LazyListScope.KeyboardLogoSubPageContent(
    logoEnabled: Boolean,
    onLogoEnabledChange: (Boolean) -> Unit,
    logoShowEnabled: Boolean,
    onLogoShowEnabledChange: (Boolean) -> Unit,
    logoColorMode: String,
    onLogoColorModeChange: (String) -> Unit,
    logoCustomColorInput: String,
    onLogoCustomColorInputChange: (String) -> Unit,
    logoImageEnabled: Boolean,
    onLogoImageEnabledChange: (Boolean) -> Unit,
    logoImageType: String,
    logoSvgRecolorEnabled: Boolean,
    onLogoSvgRecolorEnabledChange: (Boolean) -> Unit,
    logoImagePngBase64: String,
    logoImageSvgText: String,
    logoImageName: String,
    logoImageMessage: String,
    logoImageImporting: Boolean,
    onPickLogoPng: () -> Unit,
    onPickLogoSvg: () -> Unit,
    onActivateLogoImageType: (String) -> Unit,
    onClearLogoImages: () -> Unit,
    onResetLogo: () -> Unit
) {
    item {
        val context = LocalContext.current
        val isPng = logoImageType == WeTypeSettings.LOGO_IMAGE_TYPE_PNG
        val nameSuffix = logoImageName.takeIf { it.isNotEmpty() }?.let { "：$it" }.orEmpty()
        val pngSummary = when {
            logoImagePngBase64.isEmpty() -> "未上传，点击选择 PNG 图片（原色显示）"
            isPng -> "生效中$nameSuffix，点击重新选择"
            else -> "已上传，点击切换为 PNG 生效"
        }
        val svgSummary = when {
            logoImageSvgText.isEmpty() -> "未上传，点击选择 SVG 图片"
            !isPng -> "生效中$nameSuffix，点击重新选择"
            else -> "已上传，点击切换为 SVG 生效"
        }
        val logoColorModeOptions = listOf(
            WeTypeSettings.LOGO_COLOR_MODE_BRAND,
            WeTypeSettings.LOGO_COLOR_MODE_SYSTEM,
            WeTypeSettings.LOGO_COLOR_MODE_CUSTOM
        )
        val cardModifier = Modifier.padding(horizontal = 16.dp)
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // 1. 总开关
            Card(modifier = cardModifier, insideMargin = PaddingValues(0.dp)) {
                SwitchPreference(
                    title = "启用 Logo 替换",
                    summary = "关闭则显示输入法的原生 Logo",
                    checked = logoEnabled,
                    onCheckedChange = onLogoEnabledChange
                )
            }
            SettingExpandGroup(visible = logoEnabled) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    // 2. 显示与主体颜色
                    Card(modifier = cardModifier, insideMargin = PaddingValues(0.dp)) {
                        SwitchPreference(
                            title = "显示 Logo",
                            summary = "关闭则隐藏键盘上的 Logo",
                            checked = logoShowEnabled,
                            onCheckedChange = onLogoShowEnabledChange
                        )
                        OverlayDropdownPreference(
                            title = "Logo 主体颜色",
                            entry = DropdownEntry(
                                items = labeledDropdownItems(
                                    options = listOf(
                                        "跟随品牌色" to "官方彩色",
                                        "跟随系统" to "自适应黑白",
                                        "自定义颜色" to "手动指定颜色"
                                    ),
                                    selectedIndex = logoColorModeOptions.indexOf(logoColorMode)
                                        .coerceAtLeast(0),
                                    onSelect = { index ->
                                        onLogoColorModeChange(logoColorModeOptions[index])
                                    }
                                )
                            )
                        )
                        SettingExpandGroup(
                            visible = logoColorMode == WeTypeSettings.LOGO_COLOR_MODE_CUSTOM
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp)
                            ) {
                                Text(
                                    text = "自定义颜色",
                                    style = MiuixTheme.textStyles.main
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "输入 #RRGGBB，例如 #23C891",
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    style = MiuixTheme.textStyles.body2
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                TextField(
                                    value = logoCustomColorInput,
                                    onValueChange = { input ->
                                        val trimmed = input.trim()
                                        val hasPrefix = trimmed.startsWith("#")
                                        val body = trimmed.removePrefix("#")
                                        if (body.length <= 6 && body.matches(Regex("^[0-9a-fA-F]*$"))) {
                                            onLogoCustomColorInputChange(if (hasPrefix || body.isNotEmpty()) "#$body" else "")
                                        }
                                    },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                    label = "#RRGGBB"
                                )
                            }
                        }
                    }
                    // 3. 图片替换：开启后才展开后面的上传与生效选项
                    Card(modifier = cardModifier, insideMargin = PaddingValues(0.dp)) {
                        SwitchPreference(
                            title = "替换为图片",
                            summary = "关闭则用回矢量 Logo，已上传文件保留",
                            checked = logoImageEnabled,
                            onCheckedChange = onLogoImageEnabledChange
                        )
                        SettingExpandGroup(visible = logoImageEnabled) {
                            Column {
                                ArrowPreference(
                                    title = "替换为 PNG",
                                    summary = pngSummary,
                                    onClick = {
                                        // 已上传但未生效时点一下就直接切过去，否则重新选文件。
                                        if (logoImagePngBase64.isNotEmpty() && !isPng) {
                                            onActivateLogoImageType(WeTypeSettings.LOGO_IMAGE_TYPE_PNG)
                                        } else {
                                            onPickLogoPng()
                                        }
                                    }
                                )
                                ArrowPreference(
                                    title = "替换为 SVG",
                                    summary = svgSummary,
                                    onClick = {
                                        if (logoImageSvgText.isNotEmpty() && isPng) {
                                            onActivateLogoImageType(WeTypeSettings.LOGO_IMAGE_TYPE_SVG)
                                        } else {
                                            onPickLogoSvg()
                                        }
                                    }
                                )
                                SwitchPreference(
                                    title = "替换 SVG 颜色",
                                    summary = "仅对 SVG 生效，染色为 Logo 主体颜色；PNG 始终原色",
                                    checked = logoSvgRecolorEnabled,
                                    onCheckedChange = onLogoSvgRecolorEnabledChange
                                )
                                ArrowPreference(
                                    title = "清除已上传图片",
                                    summary = "删除 PNG 与 SVG，恢复矢量 Logo",
                                    onClick = onClearLogoImages
                                )
                                if (logoImageImporting || logoImageMessage.isNotEmpty()) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp)
                                    ) {
                                        Text(
                                            text = logoImageMessage,
                                            style = MiuixTheme.textStyles.body2,
                                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                        )
                                    }
                                }
                            }
                        }
                    }
                    // 4. 重置
                    Card(modifier = cardModifier, insideMargin = PaddingValues(0.dp)) {
                        ArrowPreference(
                            title = "重置 Logo 设置",
                            summary = "恢复 Logo 默认开启、品牌色状态",
                            onClick = {
                                onResetLogo()
                                Toast.makeText(context, "Logo 设置已重置", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                }
            }
        }
    }
}
