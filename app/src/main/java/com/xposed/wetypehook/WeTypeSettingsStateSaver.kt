package com.xposed.wetypehook

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.mapSaver
import com.xposed.wetypehook.wetype.settings.EdgeLightGroup

/**
 * [WeTypeSettingsState] 的 `SavedStateRegistry` 存取。
 *
 * 单独放一个文件，是因为这张字段表是唯一需要在新增可保存字段时同步的地方：`save` 与
 * `restore` 必须成对出现，键名必须一致。加字段时两处一起加，漏了的话该字段在旋转
 * 屏幕或进程重建后会悄悄退回默认值。
 */
internal fun weTypeSettingsStateSaver(
    create: () -> WeTypeSettingsState
): Saver<WeTypeSettingsState, Any> = mapSaver(
    save = { it.toSavedStateMap() },
    restore = { saved -> create().apply { restoreFromSavedStateMap(saved) } }
)

/**
 * 一级页会写进偏好文件的全部字段。
 *
 * `save` 与 [persistedFingerprint] 共用这一份：新增可保存字段时只补这里一处，
 * 不会再出现「Saver 补了、自动落盘指纹漏了」的半截状态。
 */
internal fun WeTypeSettingsState.toSavedStateMap(): Map<String, Any> = mapOf(
    "lightColor" to lightColor,
    "darkColor" to darkColor,
    "blurRadius" to blurRadius,
    "cornerRadius" to cornerRadius,
    "bottomCornerRadius" to bottomCornerRadius,
    "keyCornerRadius" to keyCornerRadius,
    "edgeHighlightEnabled" to edgeHighlightEnabled,
    // 三类光感各存一行紧凑串：`rememberSaveable` 只认 Bundle 能装的值（基本类型、String、
    // Parcelable、Serializable），直接放 EdgeLightGroup 会在合成时抛
    // 「item at index N can't be saved」。串本身也是偏好文件里的同一份编码，不会两套真相。
    "backgroundLight" to backgroundLight.text(),
    "iconLight" to iconLight.text(),
    "keyLight" to keyLight.text(),
    "edgeLightAngle" to edgeLightAngle,
    "nativeEdgeLightEnabled" to nativeEdgeLightEnabled,
    "nativeEdgeLightWidth" to nativeEdgeLightWidth,
    "nativeEdgeLightIntensity" to nativeEdgeLightIntensity,
    "colorOsLightAngle" to colorOsLightAngle,
    "candidateBackgroundAlpha" to candidateBackgroundAlpha,
    "candidateBackgroundCorner" to candidateBackgroundCorner,
    "candidateBackgroundLeftMarginDp" to candidateBackgroundLeftMarginDp,
    "candidatePinyinLeftMarginDp" to candidatePinyinLeftMarginDp,
    "toolbarIconBgOpacity" to toolbarIconBgOpacity,
    "disableHotUpdate" to disableHotUpdate,
    "showCrossDeviceClipboard" to showCrossDeviceClipboard,
    "removeClipboardRetentionLimit" to removeClipboardRetentionLimit,
    "removeClipboardTextLimit" to removeClipboardTextLimit,
    "clipboardSearchEnabled" to clipboardSearchEnabled,
    "clipboardSearchClearOnBack" to clipboardSearchClearOnBack,
    "clipboardImageAdjustRatio" to clipboardImageAdjustRatio,
    "clipboardImageCrop" to clipboardImageCrop,
    "clipboardImageUniformRowHeight" to clipboardImageUniformRowHeight,
    "clipboardImageMaxCount" to clipboardImageMaxCount,
    "clipboardImageMaxSizeMb" to clipboardImageMaxSizeMb,
    "qwertyGestureEnabled" to qwertyGestureEnabled,
    "t9GestureEnabled" to t9GestureEnabled,
    "gestureThreshold" to gestureThreshold,
    "t9GestureThreshold" to t9GestureThreshold,
    "gestureVibration" to gestureVibration,
    "t9GestureVibration" to t9GestureVibration,
    "gestureBindingsJson" to gestureBindingsJson,
    "showGestureKeyLabels" to showGestureKeyLabels,
    "gestureLabelTextSizeSp" to gestureLabelTextSizeSp,
    "gestureLabelAlpha" to gestureLabelAlpha,
    "gestureLabelPosition" to gestureLabelPosition,
    "gestureLabelMarginTopDp" to gestureLabelMarginTopDp,
    "gestureLabelMarginBottomDp" to gestureLabelMarginBottomDp,
    "gestureLabelMarginLeftDp" to gestureLabelMarginLeftDp,
    "gestureLabelMarginRightDp" to gestureLabelMarginRightDp,
    "logoEnabled" to logoEnabled,
    "logoShowEnabled" to logoShowEnabled,
    "logoColorMode" to logoColorMode,
    "logoCustomColorInput" to logoCustomColorInput,
    "logoImageEnabled" to logoImageEnabled,
    "logoImageType" to logoImageType,
    "logoSvgRecolorEnabled" to logoSvgRecolorEnabled,
    "logoImageName" to logoImageName,
    "logoImageUpdatedAt" to logoImageUpdatedAt,
    "fontMode" to fontMode,
    "systemMaterialEnabled" to systemMaterialEnabled,
    "hyperMaterialEnabled" to hyperMaterialEnabled,
    "currentModeIsDark" to currentModeIsDark,
    "appearancePreviewPinned" to appearancePreviewPinned,
    "keyboardPreviewEnabled" to keyboardPreviewEnabled,
    "previewWallpaperName" to previewWallpaperName,
    "colorInput" to colorInput,
    "alphaValue" to alphaValue,
)

/**
 * 只在界面里活着、不会写进偏好文件的键。
 *
 * 它们要么是设置页自己的展示偏好（明暗预览、实时预览开关与固定、预览底图），要么是编辑草稿
 * （颜色输入框、透明度滑杆的中间值）。改它们不需要写盘，所以不进 [persistedFingerprint]。
 *
 * 单测会把这个集合原样钉住：往这里加名字等于「这个字段以后不再自动落盘」，必须是刻意决定。
 */
internal val UI_ONLY_SAVED_STATE_KEYS = setOf(
    "currentModeIsDark",
    "appearancePreviewPinned",
    "keyboardPreviewEnabled",
    "previewWallpaperName",
    "colorInput",
    "alphaValue",
)

/**
 * 「需要落盘的字段此刻长什么样」的指纹。
 *
 * 一级页与二级页的控件只改内存状态，写盘统一由自动保存负责，它用这个指纹判断这一格改动值不值得
 * 写盘：指纹没变就什么都不做。拖动滑杆时状态每帧都在变，指纹也跟着变，但写盘发生在拖动停下
 * 之后（见 `WeTypeSettingsScreen` 里的防抖），所以拖动过程本身不碰磁盘。
 *
 * [WeTypeSettingsState.parsedGlassOverrides] 是另一份 42 项的表，不在 [toSavedStateMap] 里，
 * 单独并进来；否则在材质二级页改玻璃参数不会被自动落盘。解析不出来时它是 null，指纹仍然会变，
 * 由 [WeTypeSettingsState.autoSave] 自己静默跳过。品牌强调色与按键颜色同理（另一份列表）。
 */
internal fun WeTypeSettingsState.persistedFingerprint(): Int = persistedStateFingerprint(
    savedState = toSavedStateMap(),
    glassOverridesHash = parsedGlassOverrides?.hashCode(),
    appearanceColors = appearanceGroupColors
)

/**
 * [persistedFingerprint] 的算法本体：跳过界面专用键，其余逐项混入 `hashCode`。
 *
 * 参数是值而不是状态对象，单测不用真的造一个 `WeTypeSettingsState`（它要 Context 和 Android 运行时）。
 * `Map` 是 `LinkedHashMap`，迭代顺序稳定，所以同一份状态每次都得到同一个指纹。
 */
internal fun persistedStateFingerprint(
    savedState: Map<String, Any>,
    glassOverridesHash: Int?,
    appearanceColors: List<Int> = emptyList()
): Int {
    var hash = 1
    savedState.forEach { (key, value) ->
        if (key in UI_ONLY_SAVED_STATE_KEYS) return@forEach
        hash = 31 * hash + value.hashCode()
    }
    hash = 31 * hash + (glassOverridesHash ?: 0)
    appearanceColors.forEach { hash = 31 * hash + it }
    return hash
}

private fun WeTypeSettingsState.restoreFromSavedStateMap(saved: Map<String, Any?>) {
    lightColor = saved["lightColor"] as? Int ?: lightColor
    darkColor = saved["darkColor"] as? Int ?: darkColor
    blurRadius = saved["blurRadius"] as? Int ?: blurRadius
    cornerRadius = saved["cornerRadius"] as? Int ?: cornerRadius
    bottomCornerRadius = saved["bottomCornerRadius"] as? Int ?: bottomCornerRadius
    keyCornerRadius = saved["keyCornerRadius"] as? Int ?: keyCornerRadius
    edgeHighlightEnabled = saved["edgeHighlightEnabled"] as? Boolean ?: edgeHighlightEnabled
    backgroundLight = EdgeLightGroup.parse(saved["backgroundLight"] as? String, backgroundLight)
    iconLight = EdgeLightGroup.parse(saved["iconLight"] as? String, iconLight)
    keyLight = EdgeLightGroup.parse(saved["keyLight"] as? String, keyLight)
    edgeLightAngle = saved["edgeLightAngle"] as? Int ?: edgeLightAngle
    nativeEdgeLightEnabled = saved["nativeEdgeLightEnabled"] as? Boolean ?: nativeEdgeLightEnabled
    nativeEdgeLightWidth = saved["nativeEdgeLightWidth"] as? Int ?: nativeEdgeLightWidth
    nativeEdgeLightIntensity = saved["nativeEdgeLightIntensity"] as? Int ?: nativeEdgeLightIntensity
    colorOsLightAngle = saved["colorOsLightAngle"] as? Int ?: colorOsLightAngle
    candidateBackgroundAlpha = saved["candidateBackgroundAlpha"] as? Int ?: candidateBackgroundAlpha
    candidateBackgroundCorner = saved["candidateBackgroundCorner"] as? Int ?: candidateBackgroundCorner
    candidateBackgroundLeftMarginDp = saved["candidateBackgroundLeftMarginDp"] as? Int
        ?: candidateBackgroundLeftMarginDp
    candidatePinyinLeftMarginDp = saved["candidatePinyinLeftMarginDp"] as? Int
        ?: candidatePinyinLeftMarginDp
    toolbarIconBgOpacity = saved["toolbarIconBgOpacity"] as? Int ?: toolbarIconBgOpacity
    disableHotUpdate = saved["disableHotUpdate"] as? Boolean ?: disableHotUpdate
    showCrossDeviceClipboard = saved["showCrossDeviceClipboard"] as? Boolean ?: showCrossDeviceClipboard
    removeClipboardRetentionLimit = saved["removeClipboardRetentionLimit"] as? Boolean
        ?: removeClipboardRetentionLimit
    removeClipboardTextLimit = saved["removeClipboardTextLimit"] as? Boolean ?: removeClipboardTextLimit
    clipboardSearchEnabled = saved["clipboardSearchEnabled"] as? Boolean ?: clipboardSearchEnabled
    clipboardSearchClearOnBack = saved["clipboardSearchClearOnBack"] as? Boolean
        ?: clipboardSearchClearOnBack
    clipboardImageAdjustRatio = saved["clipboardImageAdjustRatio"] as? Boolean
        ?: clipboardImageAdjustRatio
    clipboardImageCrop = saved["clipboardImageCrop"] as? Boolean ?: clipboardImageCrop
    clipboardImageUniformRowHeight = saved["clipboardImageUniformRowHeight"] as? Boolean
        ?: clipboardImageUniformRowHeight
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
    gestureLabelMarginBottomDp = saved["gestureLabelMarginBottomDp"] as? Int
        ?: gestureLabelMarginBottomDp
    gestureLabelMarginLeftDp = saved["gestureLabelMarginLeftDp"] as? Int ?: gestureLabelMarginLeftDp
    gestureLabelMarginRightDp = saved["gestureLabelMarginRightDp"] as? Int
        ?: gestureLabelMarginRightDp
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
    when {
        nativeEdgeLightEnabled -> selectNativeEdgeLightEnabled(true)
        edgeHighlightEnabled -> selectEdgeHighlightEnabled(true)
        systemMaterialEnabled -> selectSystemMaterialEnabled(true)
    }
}
