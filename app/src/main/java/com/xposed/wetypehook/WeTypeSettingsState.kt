package com.xposed.wetypehook

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import com.xposed.wetypehook.wetype.graphics.WeTypeSystemMaterials
import com.xposed.wetypehook.wetype.logo.LogoImageStore
import com.xposed.wetypehook.wetype.settings.DARK_KEY_COLOR_GROUP_ID
import com.xposed.wetypehook.wetype.settings.GlassMaterialOverrides
import com.xposed.wetypehook.wetype.settings.GlassOverrideField
import com.xposed.wetypehook.wetype.settings.LIGHT_KEY_COLOR_GROUP_ID
import com.xposed.wetypehook.wetype.settings.WeTypeAppearanceColorGroups
import com.xposed.wetypehook.wetype.settings.WeTypeProcessRestarter
import com.xposed.wetypehook.wetype.settings.WeTypeSettings
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavKey
import top.yukonga.miuix.kmp.nav.core.rememberNavBackStack

/**
 * 「设置」界面的全部可变状态与动作。
 *
 * 这个容器是为拆分 [WeTypeSettingsScreen] 而立的：状态、读写它们的动作、以及各二级页
 * 的渲染过去都挤在同一个 1258 行的 Composable 里，谁也单独测不了、单独读不了。现在
 * Composable 只负责组合，状态与动作在这里，二级页渲染在 `WeTypeSettingsSubPages.kt`。
 *
 * 可保存字段（那些 `rememberSaveable` 的）仍由 [rememberWeTypeSettingsState] 逐个用
 * `rememberSaveable` 建出来再注入，所以旋转屏幕与进程重建后的恢复行为与拆分前一致；
 * [weTypeSettingsStateSaver] 只负责把它们存进 / 取回 `SavedStateRegistry`。
 *
 * 非可保存字段（大字符串、观察器回填的值等）在这里直接 `mutableStateOf` 建，随容器
 * 生命周期存在。
 */
@Stable
internal class WeTypeSettingsState(
    val context: Context,
    val settingsContext: Context,
    val preferencesContext: Context,
    val isEmbeddedHost: Boolean,
    val systemDarkMode: Boolean,
    val snapshot: WeTypeSettings.Snapshot,
    val coroutineScope: CoroutineScope,
    val navBackStack: SnapshotStateList<NavKey>,
    val categoryTabs: List<String>,
    val categoryPagerState: PagerState,
    val scrollBehavior: ScrollBehavior,
    val glassInput: SnapshotStateList<String>,
    val appearanceGroupColors: SnapshotStateList<Int>
) {
    val appearanceGroups = WeTypeAppearanceColorGroups.groups
    val appearanceSectionGroups = appearanceGroups.filterNot { it.isKeyColorGroup }
    val glassSupported = WeTypeSystemMaterials.areGlassOverridesAvailable()
    val colorOsMaterialAvailable = WeTypeSystemMaterials.isColorOsBackend()

    var activationStatus by mutableStateOf(ModuleActivationTracker.resolveStatusForUi(preferencesContext))
    var lightColor by mutableIntStateOf(snapshot.lightColor)
    var darkColor by mutableIntStateOf(snapshot.darkColor)
    var blurRadius by mutableIntStateOf(snapshot.blurRadius)
    var cornerRadius by mutableIntStateOf(snapshot.cornerRadius)
    var bottomCornerRadius by mutableIntStateOf(snapshot.bottomCornerRadius)
    var keyCornerRadius by mutableIntStateOf(snapshot.keyCornerRadius)
    var edgeHighlightEnabled by mutableStateOf(snapshot.edgeHighlightEnabled)
    var edgeHighlightIntensity by mutableIntStateOf(snapshot.edgeHighlightIntensity)
    var edgeLightAngle by mutableIntStateOf(snapshot.edgeLightAngle)
    var edgeLightWidth by mutableIntStateOf(snapshot.edgeLightWidth)
    var glowIntensity by mutableIntStateOf(snapshot.glowIntensity)
    var nativeEdgeLightEnabled by mutableStateOf(snapshot.nativeEdgeLightEnabled)
    var nativeEdgeLightWidth by mutableIntStateOf(snapshot.nativeEdgeLightWidth)
    var colorOsLightAngle by mutableIntStateOf(snapshot.colorOsLightAngle)
    var candidateBackgroundAlpha by mutableIntStateOf(snapshot.candidateBackgroundAlpha)
    var candidateBackgroundCorner by mutableIntStateOf(snapshot.candidateBackgroundCorner.roundToInt())
    var candidateBackgroundLeftMarginDp by mutableIntStateOf(snapshot.candidateBackgroundLeftMarginDp)
    var candidatePinyinLeftMarginDp by mutableIntStateOf(snapshot.candidatePinyinLeftMarginDp)
    var toolbarIconBgOpacity by mutableIntStateOf(snapshot.toolbarIconBgOpacity)
    var iconEdgeLightEnabled by mutableStateOf(snapshot.iconEdgeLightEnabled)
    var keyEdgeLightEnabled by mutableStateOf(snapshot.keyEdgeLightEnabled)
    var disableHotUpdate by mutableStateOf(snapshot.disableHotUpdate)
    var showCrossDeviceClipboard by mutableStateOf(snapshot.showCrossDeviceClipboard)
    var removeClipboardRetentionLimit by mutableStateOf(snapshot.removeClipboardRetentionLimit)
    var removeClipboardTextLimit by mutableStateOf(snapshot.removeClipboardTextLimit)
    var clipboardSearchEnabled by mutableStateOf(snapshot.clipboardSearchEnabled)
    var clipboardSearchClearOnBack by mutableStateOf(snapshot.clipboardSearchClearOnBack)
    var clipboardImageAdjustRatio by mutableStateOf(snapshot.clipboardImageAdjustRatio)
    var clipboardImageCrop by mutableStateOf(snapshot.clipboardImageCrop)
    var clipboardImageUniformRowHeight by mutableStateOf(snapshot.clipboardImageUniformRowHeight)
    var clipboardImageMaxCount by mutableIntStateOf(snapshot.clipboardImageMaxCount)
    var clipboardImageMaxSizeMb by mutableIntStateOf(snapshot.clipboardImageMaxSizeMb)
    var qwertyGestureEnabled by mutableStateOf(snapshot.qwertyGestureEnabled)
    var t9GestureEnabled by mutableStateOf(snapshot.t9GestureEnabled)
    var gestureThreshold by mutableIntStateOf(snapshot.gestureThreshold)
    var t9GestureThreshold by mutableIntStateOf(snapshot.t9GestureThreshold)
    var gestureVibration by mutableStateOf(snapshot.gestureVibration)
    var t9GestureVibration by mutableStateOf(snapshot.t9GestureVibration)
    var gestureBindingsJson by mutableStateOf(snapshot.gestureBindingsJson)
    var showGestureKeyLabels by mutableStateOf(snapshot.showGestureKeyLabels)
    var gestureLabelTextSizeSp by mutableIntStateOf(snapshot.gestureLabelTextSizeSp)
    var gestureLabelAlpha by mutableIntStateOf(snapshot.gestureLabelAlpha)
    var gestureLabelPosition by mutableIntStateOf(snapshot.gestureLabelPosition)
    var gestureLabelMarginTopDp by mutableIntStateOf(snapshot.gestureLabelMarginTopDp)
    var gestureLabelMarginBottomDp by mutableIntStateOf(snapshot.gestureLabelMarginBottomDp)
    var gestureLabelMarginLeftDp by mutableIntStateOf(snapshot.gestureLabelMarginLeftDp)
    var gestureLabelMarginRightDp by mutableIntStateOf(snapshot.gestureLabelMarginRightDp)
    var logoEnabled by mutableStateOf(snapshot.logoEnabled)
    var logoShowEnabled by mutableStateOf(snapshot.logoShowEnabled)
    var logoColorMode by mutableStateOf(WeTypeSettings.normalizeLogoColorMode(snapshot.logoColorMode))
    var logoCustomColorInput by mutableStateOf(formatRgb(snapshot.logoCustomColor))
    // 自定义图片 Logo 由「键盘 Logo」二级页编辑；主页面只透传保存，用普通 remember 避免大字符串进 savedState。
    var logoImageEnabled by mutableStateOf(snapshot.logoImageEnabled)
    var logoImageType by mutableStateOf(WeTypeSettings.normalizeLogoImageType(snapshot.logoImageType))
    var logoSvgRecolorEnabled by mutableStateOf(snapshot.logoSvgRecolorEnabled)
    var logoImagePngBase64 by mutableStateOf(snapshot.logoImagePngBase64)
    var logoImageSvgText by mutableStateOf(snapshot.logoImageSvgText)
    var logoImageName by mutableStateOf(snapshot.logoImageName)
    var logoImageUpdatedAt by mutableStateOf(snapshot.logoImageUpdatedAt)
    var logoImageMessage by mutableStateOf("")
    var logoImageImporting by mutableStateOf(false)
    var fontMode by mutableIntStateOf(snapshot.fontMode)
    var previewGlassOverrides by mutableStateOf(snapshot.glassOverrides)
    var systemMaterialEnabled by mutableStateOf(snapshot.systemMaterialEnabled)
    var hyperMaterialEnabled by mutableStateOf(snapshot.hyperMaterialEnabled)
    // MIUI/HyperOS 的背板材质：ColorOS 后端由「ColorOS 系统材质」那一行接管，这里恒不可用。
    var hyperMaterialAvailable by mutableStateOf(!WeTypeSystemMaterials.isColorOsBackend() && WeTypeSystemMaterials.isAvailable(preferencesContext))
    var currentModeIsDark by mutableStateOf(systemDarkMode)
    // 实时预览固定在标题栏下方，跨二级页共享；只在界面美化的二级页里生效。
    var appearancePreviewPinned by mutableStateOf(false)
    // 真机键盘预览：设置页展示偏好，独立落库、不进 Snapshot。
    var keyboardPreviewEnabled by mutableStateOf(WeTypeSettings.isAppearanceStagePreviewEnabled(context))
    // 预览底图（那层「手机壁纸」）：只是设置页的展示偏好，独立落库、不进 Snapshot。
    var previewWallpaperName by mutableStateOf(PreviewWallpaper.displayName(context))
    var colorInput by mutableStateOf(formatRgb(if (currentModeIsDark) darkColor else lightColor))
    var alphaValue by mutableIntStateOf(Color.alpha(if (currentModeIsDark) darkColor else lightColor))
    var updateInfo by mutableStateOf<ModuleUpdateInfo?>(null)
    var showUpdateSheet by mutableStateOf(false)

    /** 玻璃参数编辑框的解析结果，非法时是 null。每次读都重算，与拆分前的局部 val 同义。 */
    val parsedGlassOverrides: GlassMaterialOverrides?
        get() = runCatching {
        GlassMaterialOverrides.parse(GlassOverrideField.entries.associateWith { glassInput[it.ordinal] })
        }.getOrNull()

    // 系统材质是否真的在生效：ColorOS 后端由「ColorOS 系统材质」那一行决定，其余后端看「MIUI 系统材质」。
    // 预览标题与「键盘模糊」滑杆的联动都按这个口径，不能只看 MIUI 那个开关。
    val systemMaterialActive = systemMaterialEnabled &&
        if (colorOsMaterialAvailable) nativeEdgeLightEnabled else hyperMaterialEnabled

    val openSubPage: (SettingsSubPage) -> Unit = { targetSubPage ->
        // 现有二级页只有一个返回按钮，深度恒为 1。
        if (navBackStack.size == 1) {
            navBackStack.add(SettingsRoute.SubPage(targetSubPage))
        }
    }

    fun setAppearanceStagePreview(enabled: Boolean) {
        keyboardPreviewEnabled = enabled
        WeTypeSettings.setAppearanceStagePreviewEnabled(context, enabled)
    }

    fun currentColor(): Int = if (currentModeIsDark) darkColor else lightColor

    fun syncEditorFromState() {
        alphaValue = Color.alpha(currentColor())
        colorInput = formatRgb(currentColor())
    }

    fun updateColorFromArgb(argb: Int) {
        if (currentModeIsDark) darkColor = argb else lightColor = argb
    }

    /**
     * 预览右上角那个浅色/深色按钮。
     *
     * 切的是「预览按哪一档配色渲染」，与颜色页的「浅色/深色」Tab 共用同一份状态；切完把颜色
     * 编辑框同步过去，免得颜色页里编辑的档位和预览显示的不是一回事。它不写任何设置，也不改
     * 宿主键盘的实际深浅色 —— 那个由宿主按运行时 uiMode 自己决定。
     */
    fun setPreviewDarkMode(dark: Boolean) {
        if (dark == currentModeIsDark) return
        currentModeIsDark = dark
        syncEditorFromState()
    }

    fun currentAppearanceColors(): Map<String, Int> = appearanceGroups.mapIndexed { index, group ->
        group.id to appearanceGroupColors[index]
    }.toMap()

    fun groupIndex(groupId: String): Int =
        appearanceGroups.indexOfFirst { it.id == groupId }

    fun keyColorGroup(isDark: Boolean) = appearanceGroups.first {
        it.id == if (isDark) {
            DARK_KEY_COLOR_GROUP_ID
        } else {
            LIGHT_KEY_COLOR_GROUP_ID
        }
    }

    fun keyColorValue(isDark: Boolean): Int {
        val group = keyColorGroup(isDark)
        return appearanceGroupColors[groupIndex(group.id)]
    }

    /**
     * 唤起文件选择器。嵌入设置跑在 `ComponentDialog` 里，没有 `ActivityResultRegistryOwner`，
     * 只能走 [WeTypeHostActivityResultBridge]；而该桥在派发时会消费掉回调，
     * 所以必须每次唤起前重新注册，否则第二次选文件不会有任何反应。
     */
    fun launchLogoImagePicker(requestCode: Int, intent: Intent, onUri: (Uri) -> Unit) {
        val hostActivity = settingsContext as? Activity
        if (hostActivity == null) {
            logoImageMessage = "无法获取宿主窗口，请重试"
            return
        }
        WeTypeHostActivityResultBridge.register(requestCode) { resultCode, data ->
            val uri = if (resultCode == Activity.RESULT_OK) data?.data else null
            if (uri != null) onUri(uri)
        }
        runCatching {
            hostActivity.startActivityForResult(intent, requestCode)
        }.onFailure {
            WeTypeHostActivityResultBridge.unregister(requestCode)
            logoImageMessage = "无法打开文件选择器"
        }
    }

    fun pickLogoPng() {
        launchLogoImagePicker(
            requestCode = WeTypeHostActivityResultBridge.REQUEST_PICK_LOGO_PNG,
            intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/png"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        ) { uri ->
            if (logoImageImporting) return@launchLogoImagePicker
            logoImageImporting = true
            logoImageMessage = "正在导入 PNG…"
            coroutineScope.launch {
                val result = withContext(Dispatchers.IO) {
                    LogoImageStore.importPng(settingsContext.contentResolver, uri)
                }
                result.onSuccess { png ->
                    // 最后上传者胜：导入即成为待生效类型。
                    logoImagePngBase64 = png.base64
                    logoImageName = png.name
                    logoImageType = WeTypeSettings.LOGO_IMAGE_TYPE_PNG
                    logoImageUpdatedAt = System.currentTimeMillis()
                    logoImageMessage = "PNG 已就绪：${png.name}，点「保存」后生效"
                }.onFailure { error ->
                    logoImageMessage = "导入失败：${error.message ?: "未知错误"}"
                }
                logoImageImporting = false
            }
        }
    }

    fun pickLogoSvg() {
        launchLogoImagePicker(
            requestCode = WeTypeHostActivityResultBridge.REQUEST_PICK_LOGO_SVG,
            intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/svg+xml"
                putExtra(
                    Intent.EXTRA_MIME_TYPES,
                    arrayOf("image/svg+xml", "image/svg", "text/xml")
                )
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        ) { uri ->
            if (logoImageImporting) return@launchLogoImagePicker
            logoImageImporting = true
            logoImageMessage = "正在导入 SVG…"
            coroutineScope.launch {
                val result = withContext(Dispatchers.IO) {
                    LogoImageStore.importSvg(settingsContext.contentResolver, uri)
                }
                result.onSuccess { svg ->
                    logoImageSvgText = svg.text
                    logoImageName = svg.name
                    logoImageType = WeTypeSettings.LOGO_IMAGE_TYPE_SVG
                    logoImageUpdatedAt = System.currentTimeMillis()
                    logoImageMessage = "SVG 已就绪：${svg.name}，点「保存」后生效"
                }.onFailure { error ->
                    logoImageMessage = "导入失败：${error.message ?: "未知错误"}"
                }
                logoImageImporting = false
            }
        }
    }

    fun clearLogoImages() {
        logoImagePngBase64 = ""
        logoImageSvgText = ""
        logoImageName = ""
        logoImageUpdatedAt = 0L
        logoImageMessage = "已清除自定义图片，点「保存」后恢复矢量 Logo"
    }

    /**
     * 换预览里那层「手机壁纸」。
     *
     * 跟 logo 选图同一条桥（嵌入设置没有 `ActivityResultRegistryOwner`），但落库方式不同：
     * 图片缩放后直接存进模块自己的 prefs，不记 URI —— 预览只在设置进程里画，不需要跨进程授权。
     */
    fun pickPreviewWallpaper() {
        val hostActivity = settingsContext as? Activity
        if (hostActivity == null) {
            Toast.makeText(context, "无法获取宿主窗口，请重试", Toast.LENGTH_SHORT).show()
            return
        }
        val requestCode = WeTypeHostActivityResultBridge.REQUEST_PICK_PREVIEW_WALLPAPER
        WeTypeHostActivityResultBridge.register(requestCode) { resultCode, data ->
            val uri = if (resultCode == Activity.RESULT_OK) data?.data else null
            if (uri != null) {
                coroutineScope.launch {
                    val result = withContext(Dispatchers.IO) {
                        PreviewWallpaper.import(settingsContext, uri)
                    }
                    result.onSuccess { name ->
                        previewWallpaperName = name
                    }.onFailure { error ->
                        Toast.makeText(
                            context,
                            "导入失败：${error.message ?: "未知错误"}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }
        runCatching {
            hostActivity.startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "image/*"
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                requestCode
            )
        }.onFailure {
            WeTypeHostActivityResultBridge.unregister(requestCode)
            Toast.makeText(context, "无法打开文件选择器", Toast.LENGTH_SHORT).show()
        }
    }

    fun clearPreviewWallpaper() {
        PreviewWallpaper.clear(settingsContext)
        previewWallpaperName = ""
    }

    fun saveSettings(
        successMessage: Int = R.string.settings_saved,
        glassOverridesToSave: GlassMaterialOverrides? = parsedGlassOverrides,
        restartIme: Boolean = false
    ): Boolean {
        if (glassOverridesToSave == null) {
            Toast.makeText(context, R.string.settings_glass_invalid, Toast.LENGTH_SHORT).show()
            return false
        }
        return WeTypeSettings.save(
            context = preferencesContext,
            lightColor = lightColor,
            darkColor = darkColor,
            blurRadius = blurRadius,
            cornerRadius = cornerRadius,
            bottomCornerRadius = bottomCornerRadius,
            keyCornerRadius = keyCornerRadius,
            edgeHighlightEnabled = edgeHighlightEnabled,
            edgeHighlightIntensity = edgeHighlightIntensity,
            colorOsLightAngle = colorOsLightAngle,
            nativeEdgeLightEnabled = nativeEdgeLightEnabled,
            edgeLightWidth = edgeLightWidth,
            nativeEdgeLightWidth = nativeEdgeLightWidth,
            edgeLightAngle = edgeLightAngle,
            glowIntensity = glowIntensity,
            candidateBackgroundAlpha = candidateBackgroundAlpha,
            candidateBackgroundCorner = candidateBackgroundCorner.toFloat(),
            candidateBackgroundLeftMarginDp = candidateBackgroundLeftMarginDp,
            candidatePinyinLeftMarginDp = candidatePinyinLeftMarginDp,
            toolbarIconBgOpacity = toolbarIconBgOpacity,
            iconEdgeLightEnabled = iconEdgeLightEnabled,
            keyEdgeLightEnabled = keyEdgeLightEnabled,
            appearanceColors = currentAppearanceColors(),
            disableHotUpdate = disableHotUpdate,
            showCrossDeviceClipboard = showCrossDeviceClipboard,
            removeClipboardRetentionLimit = removeClipboardRetentionLimit,
            removeClipboardTextLimit = removeClipboardTextLimit,
            clipboardSearchEnabled = clipboardSearchEnabled,
            clipboardSearchClearOnBack = clipboardSearchClearOnBack,
            clipboardImageAdjustRatio = clipboardImageAdjustRatio,
            clipboardImageCrop = clipboardImageCrop,
            clipboardImageUniformRowHeight = clipboardImageUniformRowHeight,
            clipboardImageMaxCount = clipboardImageMaxCount,
            clipboardImageMaxSizeMb = clipboardImageMaxSizeMb,
            qwertyGestureEnabled = qwertyGestureEnabled,
            t9GestureEnabled = t9GestureEnabled,
            gestureThreshold = gestureThreshold,
            t9GestureThreshold = t9GestureThreshold,
            gestureVibration = gestureVibration,
            t9GestureVibration = t9GestureVibration,
            gestureBindingsJson = gestureBindingsJson,
            showGestureKeyLabels = showGestureKeyLabels,
            gestureLabelTextSizeSp = gestureLabelTextSizeSp,
            gestureLabelAlpha = gestureLabelAlpha,
            gestureLabelPosition = gestureLabelPosition,
            gestureLabelMarginTopDp = gestureLabelMarginTopDp,
            gestureLabelMarginBottomDp = gestureLabelMarginBottomDp,
            gestureLabelMarginLeftDp = gestureLabelMarginLeftDp,
            gestureLabelMarginRightDp = gestureLabelMarginRightDp,
            logoEnabled = logoEnabled,
            logoShowEnabled = logoShowEnabled,
            logoColorMode = logoColorMode,
            logoCustomColor = parseLogoCustomColor(logoCustomColorInput),
            logoImageEnabled = logoImageEnabled,
            logoImageType = logoImageType,
            logoSvgRecolorEnabled = logoSvgRecolorEnabled,
            logoImagePngBase64 = logoImagePngBase64,
            logoImageSvgText = logoImageSvgText,
            logoImageName = logoImageName,
            logoImageUpdatedAt = logoImageUpdatedAt,
            fontMode = fontMode,
            systemMaterialEnabled = systemMaterialEnabled,
            hyperMaterialEnabled = hyperMaterialEnabled,
            glassOverrides = glassOverridesToSave,
            onPersisted = { saved ->
                val restarted = saved && restartIme &&
                    WeTypeProcessRestarter.restartImeProcess(preferencesContext)
                val message = when {
                    restarted -> R.string.settings_saved_restarted
                    saved -> successMessage
                    else -> R.string.settings_save_failed
                }
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        )
    }

    fun restoreDefaults() {
        lightColor = WeTypeSettings.DEFAULT_LIGHT_COLOR
        darkColor = WeTypeSettings.DEFAULT_DARK_COLOR
        blurRadius = WeTypeSettings.DEFAULT_BLUR_RADIUS
        cornerRadius = WeTypeSettings.DEFAULT_CORNER_RADIUS
        bottomCornerRadius = WeTypeSettings.DEFAULT_BOTTOM_CORNER_RADIUS
        keyCornerRadius = WeTypeSettings.DEFAULT_KEY_CORNER_RADIUS
        edgeHighlightEnabled = WeTypeSettings.DEFAULT_EDGE_HIGHLIGHT_ENABLED
        edgeHighlightIntensity = WeTypeSettings.DEFAULT_EDGE_HIGHLIGHT_INTENSITY
        edgeLightAngle = WeTypeSettings.DEFAULT_EDGE_LIGHT_ANGLE
        edgeLightWidth = WeTypeSettings.DEFAULT_EDGE_LIGHT_WIDTH
        glowIntensity = WeTypeSettings.DEFAULT_GLOW_INTENSITY
        nativeEdgeLightEnabled = WeTypeSettings.DEFAULT_NATIVE_EDGE_LIGHT_ENABLED
        nativeEdgeLightWidth = WeTypeSettings.DEFAULT_NATIVE_EDGE_LIGHT_WIDTH
        colorOsLightAngle = WeTypeSettings.DEFAULT_COLOROS_LIGHT_ANGLE
        candidateBackgroundAlpha = WeTypeSettings.DEFAULT_CANDIDATE_BACKGROUND_ALPHA
        candidateBackgroundCorner = WeTypeSettings.DEFAULT_CANDIDATE_BACKGROUND_CORNER.roundToInt()
        candidateBackgroundLeftMarginDp = WeTypeSettings.DEFAULT_CANDIDATE_BACKGROUND_LEFT_MARGIN_DP
        candidatePinyinLeftMarginDp = WeTypeSettings.DEFAULT_CANDIDATE_PINYIN_LEFT_MARGIN_DP
        toolbarIconBgOpacity = WeTypeSettings.DEFAULT_TOOLBAR_ICON_BG_OPACITY
        iconEdgeLightEnabled = WeTypeSettings.DEFAULT_ICON_EDGE_LIGHT_ENABLED
        keyEdgeLightEnabled = WeTypeSettings.DEFAULT_KEY_EDGE_LIGHT_ENABLED
        disableHotUpdate = WeTypeSettings.DEFAULT_DISABLE_HOT_UPDATE
        showCrossDeviceClipboard = WeTypeSettings.DEFAULT_SHOW_CROSS_DEVICE_CLIPBOARD
        removeClipboardRetentionLimit = WeTypeSettings.DEFAULT_REMOVE_CLIPBOARD_RETENTION_LIMIT
        removeClipboardTextLimit = WeTypeSettings.DEFAULT_REMOVE_CLIPBOARD_TEXT_LIMIT
        clipboardSearchEnabled = WeTypeSettings.DEFAULT_CLIPBOARD_SEARCH_ENABLED
        clipboardSearchClearOnBack = WeTypeSettings.DEFAULT_CLIPBOARD_SEARCH_CLEAR_ON_BACK
        clipboardImageAdjustRatio = WeTypeSettings.DEFAULT_CLIPBOARD_IMAGE_ADJUST_RATIO
        clipboardImageCrop = WeTypeSettings.DEFAULT_CLIPBOARD_IMAGE_CROP
        clipboardImageUniformRowHeight = WeTypeSettings.DEFAULT_CLIPBOARD_IMAGE_UNIFORM_ROW_HEIGHT
        clipboardImageMaxCount = WeTypeSettings.DEFAULT_CLIPBOARD_IMAGE_MAX_COUNT
        clipboardImageMaxSizeMb = WeTypeSettings.DEFAULT_CLIPBOARD_IMAGE_MAX_SIZE_MB
        qwertyGestureEnabled = WeTypeSettings.DEFAULT_QWERTY_GESTURE_ENABLED
        t9GestureEnabled = WeTypeSettings.DEFAULT_T9_GESTURE_ENABLED
        gestureThreshold = WeTypeSettings.DEFAULT_GESTURE_THRESHOLD
        t9GestureThreshold = WeTypeSettings.DEFAULT_T9_GESTURE_THRESHOLD
        gestureVibration = WeTypeSettings.DEFAULT_GESTURE_VIBRATION
        t9GestureVibration = WeTypeSettings.DEFAULT_T9_GESTURE_VIBRATION
        gestureBindingsJson = WeTypeSettings.DEFAULT_GESTURE_BINDINGS_JSON
        showGestureKeyLabels = WeTypeSettings.DEFAULT_SHOW_GESTURE_KEY_LABELS
        gestureLabelTextSizeSp = WeTypeSettings.DEFAULT_GESTURE_LABEL_TEXT_SIZE_SP
        gestureLabelAlpha = WeTypeSettings.DEFAULT_GESTURE_LABEL_ALPHA
        gestureLabelPosition = WeTypeSettings.DEFAULT_GESTURE_LABEL_POSITION
        gestureLabelMarginTopDp = WeTypeSettings.DEFAULT_GESTURE_LABEL_MARGIN_TOP_DP
        gestureLabelMarginBottomDp = WeTypeSettings.DEFAULT_GESTURE_LABEL_MARGIN_BOTTOM_DP
        gestureLabelMarginLeftDp = WeTypeSettings.DEFAULT_GESTURE_LABEL_MARGIN_LEFT_DP
        gestureLabelMarginRightDp = WeTypeSettings.DEFAULT_GESTURE_LABEL_MARGIN_RIGHT_DP
        logoEnabled = WeTypeSettings.DEFAULT_LOGO_ENABLED
        logoShowEnabled = WeTypeSettings.DEFAULT_LOGO_SHOW_ENABLED
        logoColorMode = WeTypeSettings.DEFAULT_LOGO_COLOR_MODE
        logoCustomColorInput = formatRgb(WeTypeSettings.DEFAULT_LOGO_CUSTOM_COLOR)
        // 全局重置时图片开关回到关闭（用回矢量 Logo），已上传文件保留，可在二级页清除。
        logoImageEnabled = WeTypeSettings.DEFAULT_LOGO_IMAGE_ENABLED
        logoImageType = WeTypeSettings.DEFAULT_LOGO_IMAGE_TYPE
        logoSvgRecolorEnabled = WeTypeSettings.DEFAULT_LOGO_SVG_RECOLOR_ENABLED
        fontMode = WeTypeSettings.DEFAULT_FONT_MODE
        glassInput.indices.forEach { glassInput[it] = "" }
        previewGlassOverrides = GlassMaterialOverrides()
        systemMaterialEnabled = WeTypeSettings.DEFAULT_SYSTEM_MATERIAL_ENABLED
        hyperMaterialEnabled = WeTypeSettings.DEFAULT_HYPER_MATERIAL_ENABLED
        appearanceGroups.forEachIndexed { index, group ->
            appearanceGroupColors[index] = group.defaultColor
        }
        syncEditorFromState()
        saveSettings(
            successMessage = R.string.settings_reset_toast,
            glassOverridesToSave = GlassMaterialOverrides()
        )
    }
}

@Composable
internal fun rememberWeTypeSettingsState(settingsContext: Context): WeTypeSettingsState {
    val context = LocalContext.current
    val preferencesContext = remember(settingsContext) { settingsContext }
    val isEmbeddedHost = remember(settingsContext) {
        (context.applicationContext ?: context).packageName != "com.xposed.wetypehook"
    }
    val snapshot = remember(preferencesContext) { WeTypeSettings.readSnapshot(preferencesContext) }
    val systemDarkMode = isSystemInDarkTheme()
    val appearanceGroups = WeTypeAppearanceColorGroups.groups
    val glassInput = rememberSaveable(
        saver = listSaver(
            save = { it.toList() },
            restore = { restored -> mutableStateListOf(*restored.toTypedArray()) }
        )
    ) {
        mutableStateListOf(
            *GlassOverrideField.entries.map { snapshot.glassOverrides.text(it) }.toTypedArray()
        )
    }
    val appearanceGroupColors = rememberSaveable(
        saver = listSaver(
            save = { it.toList() },
            restore = { restored -> mutableStateListOf(*restored.toTypedArray()) }
        )
    ) {
        mutableStateListOf(
            *appearanceGroups.map { group ->
                snapshot.appearanceColors[group.id] ?: group.defaultColor
            }.toTypedArray()
        )
    }
    // 一级页是返回栈的根，二级页逐个压栈。返回栈交给 miuix-nav 托管：进出场动画、
    // 预测性返回、边缘圆角裁剪与遮罩统一由 NavDisplay 处理。
    val navBackStack = rememberNavBackStack<SettingsRoute>(SettingsRoute.Home)
    val categoryTabs = remember { listOf("界面美化", "按键手势", "功能增强") }
    val categoryPagerState = rememberPagerState(pageCount = { categoryTabs.size })
    val coroutineScope = rememberCoroutineScope()
    val scrollBehavior = MiuixScrollBehavior(state = rememberTopAppBarState())
    val createState = {
        WeTypeSettingsState(
            context = context,
            settingsContext = settingsContext,
            preferencesContext = preferencesContext,
            isEmbeddedHost = isEmbeddedHost,
            systemDarkMode = systemDarkMode,
            snapshot = snapshot,
            coroutineScope = coroutineScope,
            navBackStack = navBackStack,
            categoryTabs = categoryTabs,
            categoryPagerState = categoryPagerState,
            scrollBehavior = scrollBehavior,
            glassInput = glassInput,
            appearanceGroupColors = appearanceGroupColors
        )
    }
    val saver = remember(settingsContext) { weTypeSettingsStateSaver(createState) }
    return rememberSaveable(saver = saver) { createState() }
}
