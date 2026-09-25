package com.xposed.wetypehook

import android.app.Activity
import android.graphics.Color
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xposed.wetypehook.wetype.settings.GlassMaterialOverrides
import com.xposed.wetypehook.wetype.settings.GlassOverrideField
import com.xposed.wetypehook.wetype.settings.WeTypeSettings

    /**
     * 「界面美化」二级页的实时预览。键盘 Logo 页看的是 Logo 本身（与 hook 侧同一份渲染），
     * 其余页面看的是键盘外观。
     */
    @Composable
internal fun WeTypeSettingsState.renderAppearancePreview(
    subPage: SettingsSubPage,
    pinned: Boolean,
    onTogglePin: () -> Unit
) {
        // 预览底图跟虚拟键盘共用同一张：没有它就看不出面板透明度到底生效没有。
        val previewWallpaper = remember(previewWallpaperName) {
            PreviewWallpaper.load(settingsContext)
        }
        if (subPage == SettingsSubPage.KEYBOARD_LOGO) {
            LogoImagePreviewCard(
                lightBackgroundColor = lightColor,
                darkBackgroundColor = darkColor,
                lightKeyColor = keyColorValue(false),
                darkKeyColor = keyColorValue(true),
                logoColorMode = logoColorMode,
                brandColor = appearanceGroupColors.getOrNull(groupIndex("theme_color"))
                    ?: WeTypeSettings.DEFAULT_LOGO_CUSTOM_COLOR,
                logoCustomColor = parseLogoCustomColor(logoCustomColorInput),
                toolbarIconBgOpacity = toolbarIconBgOpacity,
                imageEnabled = logoImageEnabled,
                imageType = logoImageType,
                svgRecolor = logoSvgRecolorEnabled,
                pngBase64 = logoImagePngBase64,
                svgText = logoImageSvgText,
                footerText = if (logoImageEnabled) {
                    "主体颜色跟随本页设置实时预览"
                } else {
                    null
                },
                pinned = pinned,
                onTogglePin = onTogglePin
            )
        } else {
            PreviewSection(
                color = currentColor(),
                blurRadius = blurRadius,
                cornerRadius = cornerRadius,
                bottomCornerRadius = bottomCornerRadius,
                keyCornerRadius = keyCornerRadius,
                edgeHighlightEnabled = edgeHighlightEnabled,
                edgeHighlightIntensity = edgeHighlightIntensity,
                keyEdgeLight = ReplicaEdgeLight(
                    enabled = edgeHighlightEnabled && keyEdgeLightEnabled,
                    angleDegrees = edgeLightAngle,
                    widthDp = edgeLightWidth,
                    intensity = edgeHighlightIntensity,
                    glow = glowIntensity
                ),
                lightKeyColor = keyColorValue(false),
                darkKeyColor = keyColorValue(true),
                isDark = currentModeIsDark,
                systemMaterialEnabled = systemMaterialEnabled,
                hyperMaterialEnabled = hyperMaterialEnabled,
                nativeEdgeLightEnabled = nativeEdgeLightEnabled,
                pinned = pinned,
                onTogglePin = onTogglePin,
                keyboardPreviewEnabled = keyboardPreviewEnabled,
                onToggleKeyboardPreview = {
                    setAppearanceStagePreview(!keyboardPreviewEnabled)
                },
                onToggleDarkMode = { setPreviewDarkMode(!currentModeIsDark) },
                wallpaper = previewWallpaper
            )
        }
}

internal fun WeTypeSettingsState.renderSubPageContent(
    subPage: SettingsSubPage,
    listScope: LazyListScope
) {
        with(listScope) {
            // 实时预览常驻每个「界面美化」二级页的首位；固定时改由标题栏承担。
            if (subPage in APPEARANCE_PREVIEW_SUB_PAGES && !appearancePreviewPinned) {
                item(key = "appearance_preview") {
                    // 左右缩进跟下面的卡片对齐：预览本身没有卡片底衬，缩进只能自己给。
                    Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                        renderAppearancePreview(
                            subPage = subPage,
                            pinned = false,
                            onTogglePin = { appearancePreviewPinned = true }
                        )
                    }
                }
            }
            when (subPage) {
                SettingsSubPage.COLORS -> ColorsSubPageContent(
                    currentModeIsDark = currentModeIsDark,
                    onModeChange = ::setPreviewDarkMode,
                    currentColor = currentColor(),
                    alphaValue = alphaValue,
                    onAlphaChange = {
                        alphaValue = it
                        val rgb = currentColor() and 0xFFFFFF
                        updateColorFromArgb((alphaValue shl 24) or rgb)
                        colorInput = formatRgb(currentColor())
                    },
                    colorInput = colorInput,
                    onColorInputChange = { input ->
                        val trimmed = input.trim()
                        val hasPrefix = trimmed.startsWith("#")
                        val body = trimmed.removePrefix("#")
                        if (body.length <= 6 && body.matches(Regex("^[0-9a-fA-F]*$"))) {
                            colorInput = if (hasPrefix || body.isNotEmpty()) "#$body" else ""

                            if (body.length == 6) {
                                runCatching {
                                    val opaque = Color.parseColor("#$body")
                                    val argb = Color.argb(
                                        alphaValue.coerceIn(0, 255),
                                        Color.red(opaque),
                                        Color.green(opaque),
                                        Color.blue(opaque)
                                    )
                                    updateColorFromArgb(argb)
                                }
                            }
                        }
                    },
                    onColorSelect = { presetRgb ->
                        val rgb = presetRgb and 0xFFFFFF
                        val argb = (alphaValue.coerceIn(0, 255) shl 24) or rgb
                        updateColorFromArgb(argb)
                        colorInput = formatRgb(rgb)
                    },
                    appearanceSectionGroups = appearanceSectionGroups,
                    appearanceGroupColors = appearanceGroupColors,
                    groupIndex = ::groupIndex,
                    currentKeyGroup = keyColorGroup(currentModeIsDark),
                    currentKeyGroupIndex = groupIndex(keyColorGroup(currentModeIsDark).id),
                    onKeyColorChange = {
                        appearanceGroupColors[groupIndex(keyColorGroup(currentModeIsDark).id)] = it
                    }
                )

                SettingsSubPage.CORNER_BLUR -> CornerBlurSubPageContent(
                    systemMaterialActive = systemMaterialActive,
                    blurRadius = blurRadius,
                    onBlurRadiusChange = { blurRadius = it },
                    cornerRadius = cornerRadius,
                    onCornerRadiusChange = { cornerRadius = it },
                    bottomCornerRadius = bottomCornerRadius,
                    onBottomCornerRadiusChange = { bottomCornerRadius = it },
                    keyCornerRadius = keyCornerRadius,
                    onKeyCornerRadiusChange = { keyCornerRadius = it },
                    candidateBackgroundCorner = candidateBackgroundCorner,
                    onCandidateBackgroundCornerChange = { candidateBackgroundCorner = it }
                )

                SettingsSubPage.CANDIDATE_TOOLBAR -> CandidateToolbarSubPageContent(
                    candidateBackgroundAlpha = candidateBackgroundAlpha,
                    onCandidateBackgroundAlphaChange = { candidateBackgroundAlpha = it },
                    candidateBackgroundLeftMarginDp = candidateBackgroundLeftMarginDp,
                    onCandidateBackgroundLeftMarginDpChange = { candidateBackgroundLeftMarginDp = it },
                    candidatePinyinLeftMarginDp = candidatePinyinLeftMarginDp,
                    onCandidatePinyinLeftMarginDpChange = { candidatePinyinLeftMarginDp = it },
                    toolbarIconBgOpacity = toolbarIconBgOpacity,
                    onToolbarIconBgOpacityChange = { toolbarIconBgOpacity = it }
                )

                SettingsSubPage.MATERIAL -> MaterialSubPageContent(
                    systemMaterialEnabled = systemMaterialEnabled,
                    onSystemMaterialEnabledChange = { systemMaterialEnabled = it },
                    colorOsMaterialAvailable = colorOsMaterialAvailable,
                    edgeHighlightEnabled = edgeHighlightEnabled,
                    onEdgeHighlightEnabledChange = { edgeHighlightEnabled = it },
                    edgeLightAngle = edgeLightAngle,
                    onEdgeLightAngleChange = { edgeLightAngle = it },
                    edgeLightWidth = edgeLightWidth,
                    onEdgeLightWidthChange = { edgeLightWidth = it },
                    hyperMaterialEnabled = hyperMaterialEnabled,
                    onHyperMaterialEnabledChange = { hyperMaterialEnabled = it },
                    hyperMaterialAvailable = hyperMaterialAvailable,
                    glassSupported = glassSupported,
                    glassInput = glassInput,
                    onGlassInputChange = { index, value -> glassInput[index] = value },
                    onGlassReset = {
                        val defaults = GlassMaterialOverrides().withGlassEnabled(true)
                        GlassOverrideField.entries.forEach { glassInput[it.ordinal] = defaults.text(it) }
                    },
                    nativeEdgeLightEnabled = nativeEdgeLightEnabled,
                    onNativeEdgeLightEnabledChange = { nativeEdgeLightEnabled = it },
                    colorOsLightAngle = colorOsLightAngle,
                    onColorOsLightAngleChange = { colorOsLightAngle = it },
                    nativeEdgeLightWidth = nativeEdgeLightWidth,
                    onNativeEdgeLightWidthChange = { nativeEdgeLightWidth = it },
                    edgeHighlightIntensity = edgeHighlightIntensity,
                    onEdgeHighlightIntensityChange = { edgeHighlightIntensity = it },
                    glowIntensity = glowIntensity,
                    onGlowIntensityChange = { glowIntensity = it },
                    iconEdgeLightEnabled = iconEdgeLightEnabled,
                    onIconEdgeLightEnabledChange = { iconEdgeLightEnabled = it },
                    keyEdgeLightEnabled = keyEdgeLightEnabled,
                    onKeyEdgeLightEnabledChange = { keyEdgeLightEnabled = it }
                )

                SettingsSubPage.CLIPBOARD -> ClipboardSubPageContent(
                    showCrossDeviceClipboard = showCrossDeviceClipboard,
                    onShowCrossDeviceClipboardChange = { showCrossDeviceClipboard = it },
                    removeClipboardRetentionLimit = removeClipboardRetentionLimit,
                    onRemoveClipboardRetentionLimitChange = { removeClipboardRetentionLimit = it },
                    removeClipboardTextLimit = removeClipboardTextLimit,
                    onRemoveClipboardTextLimitChange = { removeClipboardTextLimit = it },
                    clipboardSearchEnabled = clipboardSearchEnabled,
                    onClipboardSearchEnabledChange = { clipboardSearchEnabled = it },
                    clipboardSearchClearOnBack = clipboardSearchClearOnBack,
                    onClipboardSearchClearOnBackChange = { clipboardSearchClearOnBack = it },
                    clipboardImageAdjustRatio = clipboardImageAdjustRatio,
                    onClipboardImageAdjustRatioChange = { clipboardImageAdjustRatio = it },
                    clipboardImageCrop = clipboardImageCrop,
                    onClipboardImageCropChange = { clipboardImageCrop = it },
                    clipboardImageUniformRowHeight = clipboardImageUniformRowHeight,
                    onClipboardImageUniformRowHeightChange = { clipboardImageUniformRowHeight = it },
                    clipboardImageMaxCount = clipboardImageMaxCount,
                    onClipboardImageMaxCountChange = { clipboardImageMaxCount = it },
                    clipboardImageMaxSizeMb = clipboardImageMaxSizeMb,
                    onClipboardImageMaxSizeMbChange = { clipboardImageMaxSizeMb = it },
                    onOpenClipboardBackup = {
                        WeTypeHostLauncher.launchBackupPage(settingsContext as? Activity)
                    }
                )

                SettingsSubPage.KEYBOARD_LOGO -> KeyboardLogoSubPageContent(
                    logoEnabled = logoEnabled,
                    onLogoEnabledChange = { logoEnabled = it },
                    logoShowEnabled = logoShowEnabled,
                    onLogoShowEnabledChange = { logoShowEnabled = it },
                    logoColorMode = logoColorMode,
                    onLogoColorModeChange = { logoColorMode = it },
                    logoCustomColorInput = logoCustomColorInput,
                    onLogoCustomColorInputChange = { logoCustomColorInput = it },
                    logoImageEnabled = logoImageEnabled,
                    onLogoImageEnabledChange = { logoImageEnabled = it },
                    logoImageType = logoImageType,
                    logoSvgRecolorEnabled = logoSvgRecolorEnabled,
                    onLogoSvgRecolorEnabledChange = { logoSvgRecolorEnabled = it },
                    logoImagePngBase64 = logoImagePngBase64,
                    logoImageSvgText = logoImageSvgText,
                    logoImageName = logoImageName,
                    logoImageMessage = logoImageMessage,
                    logoImageImporting = logoImageImporting,
                    onPickLogoPng = { pickLogoPng() },
                    onPickLogoSvg = { pickLogoSvg() },
                    onActivateLogoImageType = { logoImageType = it },
                    onClearLogoImages = { clearLogoImages() },
                    onResetLogo = {
                        logoEnabled = WeTypeSettings.DEFAULT_LOGO_ENABLED
                        logoShowEnabled = WeTypeSettings.DEFAULT_LOGO_SHOW_ENABLED
                        logoColorMode = WeTypeSettings.DEFAULT_LOGO_COLOR_MODE
                        logoCustomColorInput = formatRgb(WeTypeSettings.DEFAULT_LOGO_CUSTOM_COLOR)
                        logoImageEnabled = WeTypeSettings.DEFAULT_LOGO_IMAGE_ENABLED
                        logoImageType = WeTypeSettings.DEFAULT_LOGO_IMAGE_TYPE
                        logoSvgRecolorEnabled = WeTypeSettings.DEFAULT_LOGO_SVG_RECOLOR_ENABLED
                        logoImageMessage = ""
                    }
                )
            }
        }
}
