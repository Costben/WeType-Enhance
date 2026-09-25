package com.xposed.wetypehook

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.mapSaver

/**
 * [WeTypeSettingsState] 的 `SavedStateRegistry` 存取。
 *
 * 单独放一个文件，是因为这张表是唯一需要在新增可保存字段时同步的地方：`save` 与
 * `restore` 必须成对出现，键名必须一致。加字段时两处一起加，漏了的话该字段在旋转
 * 屏幕或进程重建后会悄悄退回默认值。
 */
internal fun weTypeSettingsStateSaver(
    create: () -> WeTypeSettingsState
): Saver<WeTypeSettingsState, Any> = mapSaver(
    save = { state ->
        mapOf(
            "lightColor" to state.lightColor,
            "darkColor" to state.darkColor,
            "blurRadius" to state.blurRadius,
            "cornerRadius" to state.cornerRadius,
            "bottomCornerRadius" to state.bottomCornerRadius,
            "keyCornerRadius" to state.keyCornerRadius,
            "edgeHighlightEnabled" to state.edgeHighlightEnabled,
            "edgeHighlightIntensity" to state.edgeHighlightIntensity,
            "edgeLightAngle" to state.edgeLightAngle,
            "edgeLightWidth" to state.edgeLightWidth,
            "glowIntensity" to state.glowIntensity,
            "nativeEdgeLightEnabled" to state.nativeEdgeLightEnabled,
            "nativeEdgeLightWidth" to state.nativeEdgeLightWidth,
            "colorOsLightAngle" to state.colorOsLightAngle,
            "candidateBackgroundAlpha" to state.candidateBackgroundAlpha,
            "candidateBackgroundCorner" to state.candidateBackgroundCorner,
            "candidateBackgroundLeftMarginDp" to state.candidateBackgroundLeftMarginDp,
            "candidatePinyinLeftMarginDp" to state.candidatePinyinLeftMarginDp,
            "toolbarIconBgOpacity" to state.toolbarIconBgOpacity,
            "iconEdgeLightEnabled" to state.iconEdgeLightEnabled,
            "keyEdgeLightEnabled" to state.keyEdgeLightEnabled,
            "disableHotUpdate" to state.disableHotUpdate,
            "showCrossDeviceClipboard" to state.showCrossDeviceClipboard,
            "removeClipboardRetentionLimit" to state.removeClipboardRetentionLimit,
            "removeClipboardTextLimit" to state.removeClipboardTextLimit,
            "clipboardSearchEnabled" to state.clipboardSearchEnabled,
            "clipboardSearchClearOnBack" to state.clipboardSearchClearOnBack,
            "clipboardImageAdjustRatio" to state.clipboardImageAdjustRatio,
            "clipboardImageCrop" to state.clipboardImageCrop,
            "clipboardImageUniformRowHeight" to state.clipboardImageUniformRowHeight,
            "clipboardImageMaxCount" to state.clipboardImageMaxCount,
            "clipboardImageMaxSizeMb" to state.clipboardImageMaxSizeMb,
            "qwertyGestureEnabled" to state.qwertyGestureEnabled,
            "t9GestureEnabled" to state.t9GestureEnabled,
            "gestureThreshold" to state.gestureThreshold,
            "t9GestureThreshold" to state.t9GestureThreshold,
            "gestureVibration" to state.gestureVibration,
            "t9GestureVibration" to state.t9GestureVibration,
            "gestureBindingsJson" to state.gestureBindingsJson,
            "showGestureKeyLabels" to state.showGestureKeyLabels,
            "gestureLabelTextSizeSp" to state.gestureLabelTextSizeSp,
            "gestureLabelAlpha" to state.gestureLabelAlpha,
            "gestureLabelPosition" to state.gestureLabelPosition,
            "gestureLabelMarginTopDp" to state.gestureLabelMarginTopDp,
            "gestureLabelMarginBottomDp" to state.gestureLabelMarginBottomDp,
            "gestureLabelMarginLeftDp" to state.gestureLabelMarginLeftDp,
            "gestureLabelMarginRightDp" to state.gestureLabelMarginRightDp,
            "logoEnabled" to state.logoEnabled,
            "logoShowEnabled" to state.logoShowEnabled,
            "logoColorMode" to state.logoColorMode,
            "logoCustomColorInput" to state.logoCustomColorInput,
            "logoImageEnabled" to state.logoImageEnabled,
            "logoImageType" to state.logoImageType,
            "logoSvgRecolorEnabled" to state.logoSvgRecolorEnabled,
            "logoImageName" to state.logoImageName,
            "logoImageUpdatedAt" to state.logoImageUpdatedAt,
            "fontMode" to state.fontMode,
            "systemMaterialEnabled" to state.systemMaterialEnabled,
            "hyperMaterialEnabled" to state.hyperMaterialEnabled,
            "currentModeIsDark" to state.currentModeIsDark,
            "appearancePreviewPinned" to state.appearancePreviewPinned,
            "keyboardPreviewEnabled" to state.keyboardPreviewEnabled,
            "previewWallpaperName" to state.previewWallpaperName,
            "colorInput" to state.colorInput,
            "alphaValue" to state.alphaValue,
        )
    },
    restore = { saved ->
        create().apply {
            lightColor = saved["lightColor"] as? Int ?: lightColor
            darkColor = saved["darkColor"] as? Int ?: darkColor
            blurRadius = saved["blurRadius"] as? Int ?: blurRadius
            cornerRadius = saved["cornerRadius"] as? Int ?: cornerRadius
            bottomCornerRadius = saved["bottomCornerRadius"] as? Int ?: bottomCornerRadius
            keyCornerRadius = saved["keyCornerRadius"] as? Int ?: keyCornerRadius
            edgeHighlightEnabled = saved["edgeHighlightEnabled"] as? Boolean ?: edgeHighlightEnabled
            edgeHighlightIntensity = saved["edgeHighlightIntensity"] as? Int ?: edgeHighlightIntensity
            edgeLightAngle = saved["edgeLightAngle"] as? Int ?: edgeLightAngle
            edgeLightWidth = saved["edgeLightWidth"] as? Int ?: edgeLightWidth
            glowIntensity = saved["glowIntensity"] as? Int ?: glowIntensity
            nativeEdgeLightEnabled = saved["nativeEdgeLightEnabled"] as? Boolean ?: nativeEdgeLightEnabled
            nativeEdgeLightWidth = saved["nativeEdgeLightWidth"] as? Int ?: nativeEdgeLightWidth
            colorOsLightAngle = saved["colorOsLightAngle"] as? Int ?: colorOsLightAngle
            candidateBackgroundAlpha = saved["candidateBackgroundAlpha"] as? Int ?: candidateBackgroundAlpha
            candidateBackgroundCorner = saved["candidateBackgroundCorner"] as? Int ?: candidateBackgroundCorner
            candidateBackgroundLeftMarginDp = saved["candidateBackgroundLeftMarginDp"] as? Int ?: candidateBackgroundLeftMarginDp
            candidatePinyinLeftMarginDp = saved["candidatePinyinLeftMarginDp"] as? Int ?: candidatePinyinLeftMarginDp
            toolbarIconBgOpacity = saved["toolbarIconBgOpacity"] as? Int ?: toolbarIconBgOpacity
            iconEdgeLightEnabled = saved["iconEdgeLightEnabled"] as? Boolean ?: iconEdgeLightEnabled
            keyEdgeLightEnabled = saved["keyEdgeLightEnabled"] as? Boolean ?: keyEdgeLightEnabled
            disableHotUpdate = saved["disableHotUpdate"] as? Boolean ?: disableHotUpdate
            showCrossDeviceClipboard = saved["showCrossDeviceClipboard"] as? Boolean ?: showCrossDeviceClipboard
            removeClipboardRetentionLimit = saved["removeClipboardRetentionLimit"] as? Boolean ?: removeClipboardRetentionLimit
            removeClipboardTextLimit = saved["removeClipboardTextLimit"] as? Boolean ?: removeClipboardTextLimit
            clipboardSearchEnabled = saved["clipboardSearchEnabled"] as? Boolean ?: clipboardSearchEnabled
            clipboardSearchClearOnBack = saved["clipboardSearchClearOnBack"] as? Boolean ?: clipboardSearchClearOnBack
            clipboardImageAdjustRatio = saved["clipboardImageAdjustRatio"] as? Boolean ?: clipboardImageAdjustRatio
            clipboardImageCrop = saved["clipboardImageCrop"] as? Boolean ?: clipboardImageCrop
            clipboardImageUniformRowHeight = saved["clipboardImageUniformRowHeight"] as? Boolean ?: clipboardImageUniformRowHeight
            clipboardImageMaxCount = saved["clipboardImageMaxCount"] as? Int ?: clipboardImageMaxCount
            clipboardImageMaxSizeMb = saved["clipboardImageMaxSizeMb"] as? Int ?: clipboardImageMaxSizeMb
            qwertyGestureEnabled = saved["qwertyGestureEnabled"] as? Boolean ?: qwertyGestureEnabled
            t9GestureEnabled = saved["t9GestureEnabled"] as? Boolean ?: t9GestureEnabled
            gestureThreshold = saved["gestureThreshold"] as? Int ?: gestureThreshold
            t9GestureThreshold = saved["t9GestureThreshold"] as? Int ?: t9GestureThreshold
            gestureVibration = saved["gestureVibration"] as? Boolean ?: gestureVibration
            t9GestureVibration = saved["t9GestureVibration"] as? Boolean ?: t9GestureVibration
            gestureBindingsJson = saved["gestureBindingsJson"] as? String ?: gestureBindingsJson
            showGestureKeyLabels = saved["showGestureKeyLabels"] as? Boolean ?: showGestureKeyLabels
            gestureLabelTextSizeSp = saved["gestureLabelTextSizeSp"] as? Int ?: gestureLabelTextSizeSp
            gestureLabelAlpha = saved["gestureLabelAlpha"] as? Int ?: gestureLabelAlpha
            gestureLabelPosition = saved["gestureLabelPosition"] as? Int ?: gestureLabelPosition
            gestureLabelMarginTopDp = saved["gestureLabelMarginTopDp"] as? Int ?: gestureLabelMarginTopDp
            gestureLabelMarginBottomDp = saved["gestureLabelMarginBottomDp"] as? Int ?: gestureLabelMarginBottomDp
            gestureLabelMarginLeftDp = saved["gestureLabelMarginLeftDp"] as? Int ?: gestureLabelMarginLeftDp
            gestureLabelMarginRightDp = saved["gestureLabelMarginRightDp"] as? Int ?: gestureLabelMarginRightDp
            logoEnabled = saved["logoEnabled"] as? Boolean ?: logoEnabled
            logoShowEnabled = saved["logoShowEnabled"] as? Boolean ?: logoShowEnabled
            logoColorMode = saved["logoColorMode"] as? String ?: logoColorMode
            logoCustomColorInput = saved["logoCustomColorInput"] as? String ?: logoCustomColorInput
            logoImageEnabled = saved["logoImageEnabled"] as? Boolean ?: logoImageEnabled
            logoImageType = saved["logoImageType"] as? String ?: logoImageType
            logoSvgRecolorEnabled = saved["logoSvgRecolorEnabled"] as? Boolean ?: logoSvgRecolorEnabled
            logoImageName = saved["logoImageName"] as? String ?: logoImageName
            logoImageUpdatedAt = saved["logoImageUpdatedAt"] as? Long ?: logoImageUpdatedAt
            fontMode = saved["fontMode"] as? Int ?: fontMode
            systemMaterialEnabled = saved["systemMaterialEnabled"] as? Boolean ?: systemMaterialEnabled
            hyperMaterialEnabled = saved["hyperMaterialEnabled"] as? Boolean ?: hyperMaterialEnabled
            currentModeIsDark = saved["currentModeIsDark"] as? Boolean ?: currentModeIsDark
            appearancePreviewPinned = saved["appearancePreviewPinned"] as? Boolean ?: appearancePreviewPinned
            keyboardPreviewEnabled = saved["keyboardPreviewEnabled"] as? Boolean ?: keyboardPreviewEnabled
            previewWallpaperName = saved["previewWallpaperName"] as? String ?: previewWallpaperName
            colorInput = saved["colorInput"] as? String ?: colorInput
            alphaValue = saved["alphaValue"] as? Int ?: alphaValue
        }
    }
)
