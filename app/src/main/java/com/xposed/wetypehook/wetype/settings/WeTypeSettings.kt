package com.xposed.wetypehook.wetype.settings

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.util.Log as AndroidLog
import com.xposed.wetypehook.ModuleBridgeContract
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

object WeTypeSettings {
    const val PREF_GROUP = "wetype_settings"
    private const val TAG = "WeTypeSettings"
    private const val MODULE_PACKAGE_NAME = "com.xposed.wetypehook"
    private const val WETYPE_PACKAGE_NAME = "com.tencent.wetype"
    private const val EXTRA_APPEARANCE_COLORS = "appearance_colors"
    private const val KEY_REMOTE_SYNC_PENDING = "remote_sync_pending"
    private const val KEY_HOST_SYNC_PENDING = "host_sync_pending"
    private const val KEY_HOST_SYNC_REVISION = "host_sync_revision"
    private const val KEY_LAST_IMPORTED_REVISION = "last_imported_revision"
    private const val KEY_LIGHT_COLOR = "light_color"
    private const val KEY_DARK_COLOR = "dark_color"
    private const val KEY_SYSTEM_MATERIAL_ENABLED = "system_material_enabled"
    private const val KEY_HYPER_MATERIAL_ENABLED = "hyper_material_enabled"
    private const val KEY_GLASS_PARAMS = "glass_params"
    private const val KEY_GLASS_BLOOM = "glass_bloom"
    private const val KEY_GLASS_BLUR_SMALL = "glass_blur_small"
    private const val KEY_GLASS_BLUR_LARGE = "glass_blur_large"
    private const val KEY_GLASS_MATERIAL_TYPE = "glass_material_type"
    private const val KEY_BLUR_RADIUS = "blur_radius"
    private const val KEY_CORNER_RADIUS = "corner_radius"
    private const val KEY_BOTTOM_CORNER_RADIUS = "bottom_corner_radius"
    private const val KEY_KEY_CORNER_RADIUS = "key_corner_radius"
    private const val KEY_EDGE_HIGHLIGHT_ENABLED = "edge_highlight_enabled"
    private const val KEY_EDGE_HIGHLIGHT_INTENSITY = "edge_highlight_intensity"
    private const val KEY_COLOROS_LIGHT_ANGLE = "coloros_light_angle"
    private const val KEY_KEY_OPACITY = "key_opacity"
    private const val KEY_KEY_OPACITY_MIGRATED = "key_opacity_migrated"
    private const val KEY_GESTURE_LABEL_MIDLINE_MIGRATED = "gesture_label_midline_migrated"
    // Keep the original preference key so existing saved values still migrate cleanly.
    private const val KEY_CANDIDATE_BACKGROUND_ALPHA = "key_color_hook_alpha"
    private const val KEY_CANDIDATE_BACKGROUND_CORNER = "candidate_background_corner"
    private const val KEY_CANDIDATE_BACKGROUND_LEFT_MARGIN_DP =
        "candidate_background_left_margin_dp"
    private const val KEY_CANDIDATE_PINYIN_LEFT_MARGIN_DP = "candidate_pinyin_left_margin_dp"
    private const val KEY_APPEARANCE_COLOR_PREFIX = "appearance_color_"
    private const val KEY_DISABLE_HOT_UPDATE = "disable_hot_update"
    private const val KEY_TOOLBAR_ICON_BG_OPACITY = "toolbar_icon_bg_opacity"
    private const val KEY_ICON_EDGE_LIGHT_ENABLED = "icon_edge_light_enabled"
    private const val KEY_KEY_EDGE_LIGHT_ENABLED = "key_edge_light_enabled"
    private const val KEY_NATIVE_EDGE_LIGHT_ENABLED = "native_edge_light_enabled"
    private const val KEY_EDGE_LIGHT_WIDTH = "edge_light_width"
    private const val KEY_NATIVE_EDGE_LIGHT_WIDTH = "native_edge_light_width"
    private const val KEY_EDGE_LIGHT_ANGLE = "edge_light_angle"
    private const val KEY_GLOW_INTENSITY = "glow_intensity"
    const val KEY_SHOW_CROSS_DEVICE_CLIPBOARD = "show_cross_device_clipboard"
    const val KEY_REMOVE_CLIPBOARD_RETENTION_LIMIT = "remove_clipboard_retention_limit"
    const val KEY_REMOVE_CLIPBOARD_TEXT_LIMIT = "remove_clipboard_text_limit"
    const val KEY_CLIPBOARD_SEARCH = "clipboard_search_enabled"
    const val KEY_CLIPBOARD_SEARCH_CLEAR_ON_BACK = "clipboard_search_clear_on_back"
    const val KEY_CLIPBOARD_IMAGE_ADJUST_RATIO = "clipboard_image_adjust_ratio"
    const val KEY_CLIPBOARD_IMAGE_CROP = "clipboard_image_crop"
    const val KEY_CLIPBOARD_IMAGE_UNIFORM_ROW_HEIGHT = "clipboard_image_uniform_row_height"
    const val KEY_CLIPBOARD_IMAGE_MAX_COUNT = "clipboard_image_max_count"
    const val KEY_CLIPBOARD_IMAGE_MAX_SIZE_MB = "clipboard_image_max_size_mb"

    // 剪贴板备份与恢复（独立读写，不进入 Snapshot）。
    const val KEY_BACKUP_LOCAL_FOLDER_URI = "clipboard_backup_local_folder_uri"
    const val KEY_BACKUP_WEBDAV_URL = "clipboard_backup_webdav_url"
    const val KEY_BACKUP_WEBDAV_USERNAME = "clipboard_backup_webdav_username"
    const val KEY_BACKUP_WEBDAV_PASSWORD = "clipboard_backup_webdav_password"
    const val KEY_BACKUP_WEBDAV_REMOTE_DIR = "clipboard_backup_webdav_remote_dir"
    const val KEY_BACKUP_WEBDAV_KEEP_COUNT = "clipboard_backup_webdav_keep_count"
    const val KEY_BACKUP_WEBDAV_ALLOW_SELF_SIGNED = "clipboard_backup_webdav_allow_self_signed"
    const val KEY_BACKUP_PRE_EXPORT_DOWNLOAD = "clipboard_backup_pre_export_download"

    // 设置页展示偏好（独立读写，不进入 Snapshot）。
    const val KEY_APPEARANCE_STAGE_PREVIEW = "appearance_stage_preview"

    const val KEY_QWERTY_GESTURE_ENABLED = "qwerty_gesture_enabled"
    const val KEY_T9_GESTURE_ENABLED = "t9_gesture_enabled"
    const val KEY_GESTURE_THRESHOLD = "gesture_threshold"
    const val KEY_T9_GESTURE_THRESHOLD = "t9_gesture_threshold"
    const val KEY_GESTURE_VIBRATION = "gesture_vibration"
    const val KEY_T9_GESTURE_VIBRATION = "t9_gesture_vibration"
    const val KEY_GESTURE_BINDINGS_JSON = "gesture_bindings_json"

    const val DEFAULT_SHOW_CROSS_DEVICE_CLIPBOARD = true
    const val DEFAULT_REMOVE_CLIPBOARD_RETENTION_LIMIT = true
    const val DEFAULT_REMOVE_CLIPBOARD_TEXT_LIMIT = true
    const val DEFAULT_CLIPBOARD_SEARCH_ENABLED = true
    const val DEFAULT_CLIPBOARD_SEARCH_CLEAR_ON_BACK = true

    // 剪贴板图片缩略图：默认保持原比例、完整显示、统一两行高度。
    // adjustRatio=false 时统一为正方形；crop=true 时在目标框内居中裁剪填满。
    const val DEFAULT_CLIPBOARD_IMAGE_ADJUST_RATIO = true
    const val DEFAULT_CLIPBOARD_IMAGE_CROP = false
    const val DEFAULT_CLIPBOARD_IMAGE_UNIFORM_ROW_HEIGHT = true

    // 剪贴板图片留存上限：默认 100 张 / 512 MB，超出后从最旧开始清理。
    // 0 表示无上限。
    const val DEFAULT_CLIPBOARD_IMAGE_MAX_COUNT = 100
    const val DEFAULT_CLIPBOARD_IMAGE_MAX_SIZE_MB = 512
    const val CLIPBOARD_IMAGE_LIMIT_UNLIMITED = 0
    const val CLIPBOARD_IMAGE_MIN_COUNT = 1
    const val CLIPBOARD_IMAGE_MAX_COUNT_LIMIT = 2000
    const val CLIPBOARD_IMAGE_MIN_SIZE_MB = 16
    const val CLIPBOARD_IMAGE_MAX_SIZE_MB_LIMIT = 8192

    fun sanitizeClipboardImageMaxCount(value: Int): Int =
        if (value <= CLIPBOARD_IMAGE_LIMIT_UNLIMITED) {
            CLIPBOARD_IMAGE_LIMIT_UNLIMITED
        } else {
            value.coerceIn(CLIPBOARD_IMAGE_MIN_COUNT, CLIPBOARD_IMAGE_MAX_COUNT_LIMIT)
        }

    fun sanitizeClipboardImageMaxSizeMb(value: Int): Int =
        if (value <= CLIPBOARD_IMAGE_LIMIT_UNLIMITED) {
            CLIPBOARD_IMAGE_LIMIT_UNLIMITED
        } else {
            value.coerceIn(CLIPBOARD_IMAGE_MIN_SIZE_MB, CLIPBOARD_IMAGE_MAX_SIZE_MB_LIMIT)
        }

    const val DEFAULT_QWERTY_GESTURE_ENABLED = true
    const val DEFAULT_T9_GESTURE_ENABLED = false
    const val DEFAULT_GESTURE_THRESHOLD = 20
    const val DEFAULT_T9_GESTURE_THRESHOLD = 20
    const val DEFAULT_GESTURE_VIBRATION = true
    const val DEFAULT_T9_GESTURE_VIBRATION = true
    const val DEFAULT_GESTURE_BINDINGS_JSON = ""

    // 按键底部手势标签（对标 WeType Tool 显示底部标签）。
    const val KEY_SHOW_GESTURE_KEY_LABELS = "show_gesture_key_labels"
    const val KEY_GESTURE_LABEL_TEXT_SIZE_SP = "gesture_label_text_size_sp"
    const val KEY_GESTURE_LABEL_ALPHA = "gesture_label_alpha"
    const val KEY_GESTURE_LABEL_POSITION = "gesture_label_position"
    const val KEY_GESTURE_LABEL_MARGIN_TOP_DP = "gesture_label_margin_top_dp"
    const val KEY_GESTURE_LABEL_MARGIN_BOTTOM_DP = "gesture_label_margin_bottom_dp"
    const val KEY_GESTURE_LABEL_MARGIN_LEFT_DP = "gesture_label_margin_left_dp"
    const val KEY_GESTURE_LABEL_MARGIN_RIGHT_DP = "gesture_label_margin_right_dp"

    const val GESTURE_LABEL_POSITION_BOTTOM = 0
    const val GESTURE_LABEL_POSITION_TOP = 1

    // 边距上限取 48dp：默认 15dp 之后向下只剩约 8dp 余量（216 实测键格高 143px、
    // 中线到底边 71px≈23.7dp），再往上加标签就顶穿按键底边，所以上限只作兜底，
    // 不再往上抬——想让标签跑出按键是用户的自由，但默认值必须留呼吸感。
    const val GESTURE_LABEL_MARGIN_MIN_DP = 0
    const val GESTURE_LABEL_MARGIN_MAX_DP = 48

    const val DEFAULT_SHOW_GESTURE_KEY_LABELS = true
    const val DEFAULT_GESTURE_LABEL_TEXT_SIZE_SP = 9
    const val DEFAULT_GESTURE_LABEL_ALPHA = 153
    // 默认底部锚定：基准是按键区域的垂直中线，边距是沿中线向下的偏移量。
    // 216 实测（密度 3.0，键格 y=[1494,1636]、高 143px、中线 1565，字母墨迹底 1590）：
    // 10dp(30px) 墨迹中心只落到中线下方 34px，仍挤在字母上；
    // 20dp(60px) 落到中线下方 60.5px，顶到按键底边（1637 > 1636），太局促；
    // 15dp(45px) 落在中线下方约 45px，字母与按键底边之间才留出上下呼吸。
    const val DEFAULT_GESTURE_LABEL_POSITION = GESTURE_LABEL_POSITION_BOTTOM
    const val DEFAULT_GESTURE_LABEL_MARGIN_TOP_DP = 15
    const val DEFAULT_GESTURE_LABEL_MARGIN_BOTTOM_DP = 15
    const val DEFAULT_GESTURE_LABEL_MARGIN_LEFT_DP = 0
    const val DEFAULT_GESTURE_LABEL_MARGIN_RIGHT_DP = 0

    // 旧版默认边距（上 0 / 下 3）把标签压在按键最底部，正是"怎么调都不居中"的成因。
    // 成对命中说明用户从没动过这两个滑块，读配置时提升到新默认值；只动过其中一个
    // 的用户不会被覆盖。非持久迁移：不改写存量文件，下次保存才落 KEY_..._MIGRATED。
    private const val LEGACY_DEFAULT_GESTURE_LABEL_MARGIN_TOP_DP = 0
    private const val LEGACY_DEFAULT_GESTURE_LABEL_MARGIN_BOTTOM_DP = 3

    const val KEY_LOGO_ENABLED = "logo_enabled"
    const val KEY_LOGO_SHOW_ENABLED = "logo_show_enabled"
    const val KEY_LOGO_COLOR_MODE = "logo_color_mode"
    const val KEY_LOGO_CUSTOM_COLOR = "logo_custom_color"

    const val LOGO_COLOR_MODE_BRAND = "brand"
    const val LOGO_COLOR_MODE_SYSTEM = "system"
    const val LOGO_COLOR_MODE_BLACK = "black"
    const val LOGO_COLOR_MODE_WHITE = "white"
    const val LOGO_COLOR_MODE_CUSTOM = "custom"

    fun normalizeLogoColorMode(mode: String?): String = when (mode) {
        LOGO_COLOR_MODE_SYSTEM, LOGO_COLOR_MODE_BLACK, LOGO_COLOR_MODE_WHITE -> LOGO_COLOR_MODE_SYSTEM
        LOGO_COLOR_MODE_CUSTOM -> LOGO_COLOR_MODE_CUSTOM
        else -> LOGO_COLOR_MODE_BRAND
    }

    const val DEFAULT_LOGO_ENABLED = true
    const val DEFAULT_LOGO_SHOW_ENABLED = true
    const val DEFAULT_LOGO_COLOR_MODE = LOGO_COLOR_MODE_BRAND
    const val DEFAULT_LOGO_CUSTOM_COLOR = 0xFF23C891.toInt()

    // 自定义图片 Logo（二级页）：图片数据以内联字符串存偏好，走既有远端偏好/Bundle 桥同步，
    // hook 侧与 UI 侧都只读本进程偏好，无跨进程文件读取。
    // PNG 在导入时压缩到边长上限并转 Base64；SVG 存原始文本（上限内）。
    const val KEY_LOGO_IMAGE_ENABLED = "logo_image_enabled"
    const val KEY_LOGO_IMAGE_TYPE = "logo_image_type"
    const val KEY_LOGO_SVG_RECOLOR_ENABLED = "logo_svg_recolor_enabled"
    const val KEY_LOGO_IMAGE_PNG_BASE64 = "logo_image_png_base64"
    const val KEY_LOGO_IMAGE_SVG_TEXT = "logo_image_svg_text"
    const val KEY_LOGO_IMAGE_NAME = "logo_image_name"
    const val KEY_LOGO_IMAGE_UPDATED_AT = "logo_image_updated_at"

    const val LOGO_IMAGE_TYPE_PNG = "png"
    const val LOGO_IMAGE_TYPE_SVG = "svg"

    fun normalizeLogoImageType(type: String?): String = when (type) {
        LOGO_IMAGE_TYPE_SVG -> LOGO_IMAGE_TYPE_SVG
        else -> LOGO_IMAGE_TYPE_PNG
    }

    const val DEFAULT_LOGO_IMAGE_ENABLED = false
    const val DEFAULT_LOGO_IMAGE_TYPE = LOGO_IMAGE_TYPE_PNG
    const val DEFAULT_LOGO_SVG_RECOLOR_ENABLED = true
    const val DEFAULT_LOGO_IMAGE_PNG_BASE64 = ""
    const val DEFAULT_LOGO_IMAGE_SVG_TEXT = ""
    const val DEFAULT_LOGO_IMAGE_NAME = ""
    const val DEFAULT_LOGO_IMAGE_UPDATED_AT = 0L

    // 字体来源：键名/语义/默认值与 Z1/Z2 已发布包内逻辑保持一致，已安装端存量设置可直接兼容。
    // 0=微信官方（放行宿主字体），1=模块内置（assets/WE-Regular.ttf），2=跟随系统（Typeface.DEFAULT）。
    const val KEY_FONT_MODE = "font_mode"
    const val FONT_MODE_OFFICIAL = 0
    const val FONT_MODE_MODULE = 1
    const val FONT_MODE_SYSTEM = 2
    const val DEFAULT_FONT_MODE = FONT_MODE_SYSTEM

    const val DEFAULT_LIGHT_COLOR = 0xBDD4D4D4.toInt()
    const val DEFAULT_DARK_COLOR = 0x40000000
    const val DEFAULT_BLUR_RADIUS = 60
    const val DEFAULT_CORNER_RADIUS = 28
    const val MAX_CORNER_RADIUS = DEFAULT_CORNER_RADIUS * 2
    const val DEFAULT_BOTTOM_CORNER_RADIUS = 28
    const val MAX_BOTTOM_CORNER_RADIUS = 80
    const val DEFAULT_KEY_CORNER_RADIUS = 10
    const val MAX_KEY_CORNER_RADIUS = 40
    const val DEFAULT_EDGE_HIGHLIGHT_ENABLED = true
    const val DEFAULT_EDGE_HIGHLIGHT_INTENSITY = 80
    const val MAX_EDGE_HIGHLIGHT_INTENSITY = 100
    const val DEFAULT_COLOROS_LIGHT_ANGLE = 45
    const val MAX_COLOROS_LIGHT_ANGLE = 360
    const val DEFAULT_CANDIDATE_BACKGROUND_ALPHA = 150
    const val DEFAULT_CANDIDATE_BACKGROUND_CORNER = 60f
    const val MAX_CANDIDATE_BACKGROUND_CORNER = 60
    const val DEFAULT_CANDIDATE_BACKGROUND_LEFT_MARGIN_DP = 6
    const val DEFAULT_CANDIDATE_PINYIN_LEFT_MARGIN_DP = 16
    const val MIN_CANDIDATE_MARGIN_DP = 0
    const val MAX_CANDIDATE_MARGIN_DP = 64
    const val DEFAULT_TOOLBAR_ICON_BG_OPACITY = 150
    const val DEFAULT_ICON_EDGE_LIGHT_ENABLED = true
    const val DEFAULT_KEY_EDGE_LIGHT_ENABLED = true
    const val DEFAULT_NATIVE_EDGE_LIGHT_ENABLED = false
    const val DEFAULT_EDGE_LIGHT_WIDTH = 2
    const val MIN_EDGE_LIGHT_WIDTH = 1
    const val MAX_EDGE_LIGHT_WIDTH = 10
    const val DEFAULT_NATIVE_EDGE_LIGHT_WIDTH = 2
    const val MIN_NATIVE_EDGE_LIGHT_WIDTH = 1
    const val MAX_NATIVE_EDGE_LIGHT_WIDTH = 10
    const val DEFAULT_EDGE_LIGHT_ANGLE = 45
    const val MIN_EDGE_LIGHT_ANGLE = 0
    const val MAX_EDGE_LIGHT_ANGLE = 360
    const val DEFAULT_GLOW_INTENSITY = 100
    const val MIN_GLOW_INTENSITY = 0
    const val MAX_GLOW_INTENSITY = 200
    const val DEFAULT_DISABLE_HOT_UPDATE = true
    const val DEFAULT_SYSTEM_MATERIAL_ENABLED = true
    const val DEFAULT_HYPER_MATERIAL_ENABLED = false

    private val legacyKeyColorDefaults = mapOf(
        LIGHT_KEY_COLOR_GROUP_ID to 0xFFfcfcfe.toInt(),
        DARK_KEY_COLOR_GROUP_ID to 0xFF707070.toInt()
    )

    private val remotePrefsLock = Any()
    private val settingsSyncLock = Any()

    @Volatile
    private var cachedXposedSnapshot: Snapshot? = null

    @Volatile
    private var remotePreferences: SharedPreferences? = null

    /**
     * 模块 App 是否真的作为一个包存在。
     *
     * ## 为什么要问这个
     *
     * 保存后会走 [sendSnapshotToModule]，把快照镜像给 `com.xposed.wetypehook` 的
     * [ModuleBridgeContract] 接收器。这条路径成立的前提是**模块 App 被安装成了独立包**
     * （LSPosed 作用域模式）。
     *
     * 而 **LSPatch 内嵌模式**下模块是被塞进微信输入法内部加载的，
     * `com.xposed.wetypehook` 这个包根本不存在。广播发给一个不存在的组件只会白发一趟，
     * 白白唤醒一次系统广播调度。
     *
     * 保存结果不受这里影响：判据始终是宿主本地那份文件写成功没有，镜像无论如何都只是
     * 尽力而为。此处探包只为省掉一次注定送不到的广播，并把待同步标记正确地留在
     * false，免得每次 [ensureHostSnapshot] 都重试一遍。
     */
    private fun isModulePackagePresent(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(MODULE_PACKAGE_NAME, 0)
        true
    }.getOrDefault(false)

    /**
     * 宿主进程内重新解析远端偏好（模块 App 偏好）的入口。热重载被拒绝/服务短暂不可用时
     * `remotePreferences` 会被解绑，若无此入口则所有颜色读取会回退默认值（强调色变绿 #23C891）。
     */
    @Volatile
    private var remotePreferencesProvider: (() -> SharedPreferences?)? = null

    /** 宿主进程 Application Context：远端偏好暂时不可用时用宿主本地偏好兜底，避免回退默认色。 */
    @Volatile
    private var hostContextRef: java.lang.ref.WeakReference<Context>? = null

    private val defaultFallbackLogged = AtomicBoolean(false)

    @Volatile
    private var moduleBridgePendingIntent: PendingIntent? = null

    private val remotePrefChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        cachedXposedSnapshot = null
    }

    data class Snapshot(
        val lightColor: Int,
        val darkColor: Int,
        val blurRadius: Int,
        val cornerRadius: Int,
        val bottomCornerRadius: Int = DEFAULT_BOTTOM_CORNER_RADIUS,
        val keyCornerRadius: Int,
        val edgeHighlightEnabled: Boolean,
        val edgeHighlightIntensity: Int,
        val colorOsLightAngle: Int = DEFAULT_COLOROS_LIGHT_ANGLE,
        val candidateBackgroundAlpha: Int,
        val candidateBackgroundCorner: Float,
        val candidateBackgroundLeftMarginDp: Int,
        val candidatePinyinLeftMarginDp: Int,
        val appearanceColors: Map<String, Int>,
        val toolbarIconBgOpacity: Int,
        val iconEdgeLightEnabled: Boolean = DEFAULT_ICON_EDGE_LIGHT_ENABLED,
        val keyEdgeLightEnabled: Boolean = DEFAULT_KEY_EDGE_LIGHT_ENABLED,
        val nativeEdgeLightEnabled: Boolean = DEFAULT_NATIVE_EDGE_LIGHT_ENABLED,
        val edgeLightWidth: Int = DEFAULT_EDGE_LIGHT_WIDTH,
        val nativeEdgeLightWidth: Int = DEFAULT_NATIVE_EDGE_LIGHT_WIDTH,
        val edgeLightAngle: Int = DEFAULT_EDGE_LIGHT_ANGLE,
        val glowIntensity: Int = DEFAULT_GLOW_INTENSITY,
        val disableHotUpdate: Boolean,
        val showCrossDeviceClipboard: Boolean = DEFAULT_SHOW_CROSS_DEVICE_CLIPBOARD,
        val removeClipboardRetentionLimit: Boolean = DEFAULT_REMOVE_CLIPBOARD_RETENTION_LIMIT,
        val removeClipboardTextLimit: Boolean = DEFAULT_REMOVE_CLIPBOARD_TEXT_LIMIT,
        val clipboardSearchEnabled: Boolean = DEFAULT_CLIPBOARD_SEARCH_ENABLED,
        val clipboardSearchClearOnBack: Boolean = DEFAULT_CLIPBOARD_SEARCH_CLEAR_ON_BACK,
        val clipboardImageAdjustRatio: Boolean = DEFAULT_CLIPBOARD_IMAGE_ADJUST_RATIO,
        val clipboardImageCrop: Boolean = DEFAULT_CLIPBOARD_IMAGE_CROP,
        val clipboardImageUniformRowHeight: Boolean = DEFAULT_CLIPBOARD_IMAGE_UNIFORM_ROW_HEIGHT,
        val clipboardImageMaxCount: Int = DEFAULT_CLIPBOARD_IMAGE_MAX_COUNT,
        val clipboardImageMaxSizeMb: Int = DEFAULT_CLIPBOARD_IMAGE_MAX_SIZE_MB,
        val qwertyGestureEnabled: Boolean = DEFAULT_QWERTY_GESTURE_ENABLED,
        val t9GestureEnabled: Boolean = DEFAULT_T9_GESTURE_ENABLED,
        val gestureThreshold: Int = DEFAULT_GESTURE_THRESHOLD,
        val t9GestureThreshold: Int = DEFAULT_T9_GESTURE_THRESHOLD,
        val gestureVibration: Boolean = DEFAULT_GESTURE_VIBRATION,
        val t9GestureVibration: Boolean = DEFAULT_T9_GESTURE_VIBRATION,
        val gestureBindingsJson: String = DEFAULT_GESTURE_BINDINGS_JSON,
        val showGestureKeyLabels: Boolean = DEFAULT_SHOW_GESTURE_KEY_LABELS,
        val gestureLabelTextSizeSp: Int = DEFAULT_GESTURE_LABEL_TEXT_SIZE_SP,
        val gestureLabelAlpha: Int = DEFAULT_GESTURE_LABEL_ALPHA,
        val gestureLabelPosition: Int = DEFAULT_GESTURE_LABEL_POSITION,
        val gestureLabelMarginTopDp: Int = DEFAULT_GESTURE_LABEL_MARGIN_TOP_DP,
        val gestureLabelMarginBottomDp: Int = DEFAULT_GESTURE_LABEL_MARGIN_BOTTOM_DP,
        val gestureLabelMarginLeftDp: Int = DEFAULT_GESTURE_LABEL_MARGIN_LEFT_DP,
        val gestureLabelMarginRightDp: Int = DEFAULT_GESTURE_LABEL_MARGIN_RIGHT_DP,
        val logoEnabled: Boolean = DEFAULT_LOGO_ENABLED,
        val logoShowEnabled: Boolean = DEFAULT_LOGO_SHOW_ENABLED,
        val logoColorMode: String = DEFAULT_LOGO_COLOR_MODE,
        val logoCustomColor: Int = DEFAULT_LOGO_CUSTOM_COLOR,
        val logoImageEnabled: Boolean = DEFAULT_LOGO_IMAGE_ENABLED,
        val logoImageType: String = DEFAULT_LOGO_IMAGE_TYPE,
        val logoSvgRecolorEnabled: Boolean = DEFAULT_LOGO_SVG_RECOLOR_ENABLED,
        val logoImagePngBase64: String = DEFAULT_LOGO_IMAGE_PNG_BASE64,
        val logoImageSvgText: String = DEFAULT_LOGO_IMAGE_SVG_TEXT,
        val logoImageName: String = DEFAULT_LOGO_IMAGE_NAME,
        val logoImageUpdatedAt: Long = DEFAULT_LOGO_IMAGE_UPDATED_AT,
        val fontMode: Int = DEFAULT_FONT_MODE,
        val systemMaterialEnabled: Boolean = DEFAULT_SYSTEM_MATERIAL_ENABLED,
        val hyperMaterialEnabled: Boolean = DEFAULT_HYPER_MATERIAL_ENABLED,
        val glassOverrides: GlassMaterialOverrides = GlassMaterialOverrides()
    )

    fun isShowCrossDeviceClipboard(context: Context): Boolean = readSnapshot(context).showCrossDeviceClipboard
    fun isRemoveClipboardRetentionLimit(context: Context): Boolean = readSnapshot(context).removeClipboardRetentionLimit
    fun isRemoveClipboardTextLimit(context: Context): Boolean = readSnapshot(context).removeClipboardTextLimit
    fun isClipboardSearchEnabled(context: Context): Boolean = readSnapshot(context).clipboardSearchEnabled
    fun isClipboardSearchClearOnBack(context: Context): Boolean =
        readSnapshot(context).clipboardSearchClearOnBack
    fun isClipboardImageAdjustRatio(context: Context): Boolean = readSnapshot(context).clipboardImageAdjustRatio
    fun isClipboardImageCrop(context: Context): Boolean = readSnapshot(context).clipboardImageCrop
    fun isClipboardImageUniformRowHeight(context: Context): Boolean =
        readSnapshot(context).clipboardImageUniformRowHeight

    fun getClipboardImageMaxCount(context: Context): Int =
        readSnapshot(context).clipboardImageMaxCount

    fun getClipboardImageMaxSizeMb(context: Context): Int =
        readSnapshot(context).clipboardImageMaxSizeMb

    private val backupSettingKeys = arrayOf(
        KEY_BACKUP_LOCAL_FOLDER_URI,
        KEY_BACKUP_WEBDAV_URL,
        KEY_BACKUP_WEBDAV_USERNAME,
        KEY_BACKUP_WEBDAV_PASSWORD,
        KEY_BACKUP_WEBDAV_REMOTE_DIR,
        KEY_BACKUP_WEBDAV_KEEP_COUNT,
        KEY_BACKUP_WEBDAV_ALLOW_SELF_SIGNED,
        KEY_BACKUP_PRE_EXPORT_DOWNLOAD
    )

    /**
     * 真机键盘预览：设置页展示偏好，不进 Snapshot，因此也不会被宿主侧的设置回写覆盖。
     */
    fun isAppearanceStagePreviewEnabled(context: Context): Boolean =
        appPreferences(context).getBoolean(KEY_APPEARANCE_STAGE_PREVIEW, false)

    fun setAppearanceStagePreviewEnabled(context: Context, enabled: Boolean): Boolean =
        appPreferences(context).edit().putBoolean(KEY_APPEARANCE_STAGE_PREVIEW, enabled).commit()

    fun readClipboardBackupSettings(context: Context): ClipboardBackupSettings {
        val local = appPreferences(context)
        // 备份设置由嵌入设置（宿主进程）写入宿主本地偏好；remote prefs 仅作兼容兜底读取。
        val preferences = if (backupSettingKeys.any { local.contains(it) }) {
            local
        } else {
            synchronized(remotePrefsLock) { resolvedRemotePreferencesLocked() } ?: local
        }
        return ClipboardBackupSettings(
            localFolderUri = preferences.getString(KEY_BACKUP_LOCAL_FOLDER_URI, "").orEmpty(),
            webDavUrl = preferences.getString(KEY_BACKUP_WEBDAV_URL, "").orEmpty(),
            webDavUsername = preferences.getString(KEY_BACKUP_WEBDAV_USERNAME, "").orEmpty(),
            webDavPassword = preferences.getString(KEY_BACKUP_WEBDAV_PASSWORD, "").orEmpty(),
            webDavRemoteDir = preferences.getString(
                KEY_BACKUP_WEBDAV_REMOTE_DIR,
                ClipboardBackupSettings.DEFAULT_REMOTE_DIR
            ).orEmpty(),
            webDavKeepCount = ClipboardBackupSettings.sanitizeKeepCount(
                preferences.getInt(
                    KEY_BACKUP_WEBDAV_KEEP_COUNT,
                    ClipboardBackupSettings.DEFAULT_KEEP_COUNT
                )
            ),
            webDavAllowSelfSigned = preferences.getBoolean(
                KEY_BACKUP_WEBDAV_ALLOW_SELF_SIGNED,
                false
            ),
            preExportDownload = preferences.getBoolean(KEY_BACKUP_PRE_EXPORT_DOWNLOAD, false)
        )
    }

    fun saveClipboardBackupSettings(
        context: Context,
        settings: ClipboardBackupSettings
    ): Boolean {
        val appContext = context.applicationContext ?: context
        val localPreferences = appPreferences(appContext)
        val localSaved = writeBackupPreferences(localPreferences, settings)
        // 宿主进程直接写 remote prefs 不可靠（现有架构由宿主→模块广播桥同步），
        // 这里只做 best-effort，读取始终以宿主本地为准。
        synchronized(remotePrefsLock) {
            resolvedRemotePreferencesLocked()
        }?.takeIf { it !== localPreferences }?.let { remote ->
            runCatching { writeBackupPreferences(remote, settings) }
        }
        return localSaved
    }

    private fun writeBackupPreferences(
        preferences: SharedPreferences,
        settings: ClipboardBackupSettings
    ): Boolean = preferences.edit()
        .putString(KEY_BACKUP_LOCAL_FOLDER_URI, settings.localFolderUri)
        .putString(KEY_BACKUP_WEBDAV_URL, settings.webDavUrl)
        .putString(KEY_BACKUP_WEBDAV_USERNAME, settings.webDavUsername)
        .putString(KEY_BACKUP_WEBDAV_PASSWORD, settings.webDavPassword)
        .putString(
            KEY_BACKUP_WEBDAV_REMOTE_DIR,
            settings.webDavRemoteDir.ifBlank { ClipboardBackupSettings.DEFAULT_REMOTE_DIR }
        )
        .putInt(
            KEY_BACKUP_WEBDAV_KEEP_COUNT,
            ClipboardBackupSettings.sanitizeKeepCount(settings.webDavKeepCount)
        )
        .putBoolean(KEY_BACKUP_WEBDAV_ALLOW_SELF_SIGNED, settings.webDavAllowSelfSigned)
        .putBoolean(KEY_BACKUP_PRE_EXPORT_DOWNLOAD, settings.preExportDownload)
        .commit()

    fun isQwertyGestureEnabled(context: Context): Boolean = readSnapshot(context).qwertyGestureEnabled
    fun isT9GestureEnabled(context: Context): Boolean = readSnapshot(context).t9GestureEnabled
    fun getGestureThreshold(context: Context): Int = readSnapshot(context).gestureThreshold
    fun getT9GestureThreshold(context: Context): Int = readSnapshot(context).t9GestureThreshold
    fun isGestureVibration(context: Context): Boolean = readSnapshot(context).gestureVibration
    fun isT9GestureVibration(context: Context): Boolean = readSnapshot(context).t9GestureVibration
    fun getGestureBindingsJson(context: Context): String = readSnapshot(context).gestureBindingsJson
    fun isShowGestureKeyLabels(context: Context): Boolean = readSnapshot(context).showGestureKeyLabels
    fun getGestureLabelTextSizeSp(context: Context): Int = readSnapshot(context).gestureLabelTextSizeSp
    fun getGestureLabelAlpha(context: Context): Int = readSnapshot(context).gestureLabelAlpha
    fun getGestureLabelPosition(context: Context): Int = readSnapshot(context).gestureLabelPosition
    fun getGestureLabelMarginTopDp(context: Context): Int = readSnapshot(context).gestureLabelMarginTopDp
    fun getGestureLabelMarginBottomDp(context: Context): Int = readSnapshot(context).gestureLabelMarginBottomDp
    fun getGestureLabelMarginLeftDp(context: Context): Int = readSnapshot(context).gestureLabelMarginLeftDp
    fun getGestureLabelMarginRightDp(context: Context): Int = readSnapshot(context).gestureLabelMarginRightDp

    fun isShowCrossDeviceClipboardXposed(): Boolean = readSnapshotXposed().showCrossDeviceClipboard
    fun isRemoveClipboardRetentionLimitXposed(): Boolean = readSnapshotXposed().removeClipboardRetentionLimit
    fun isRemoveClipboardTextLimitXposed(): Boolean = readSnapshotXposed().removeClipboardTextLimit
    fun isClipboardSearchEnabledXposed(): Boolean = readSnapshotXposed().clipboardSearchEnabled
    fun isClipboardSearchClearOnBackXposed(): Boolean =
        readSnapshotXposed().clipboardSearchClearOnBack
    fun isClipboardImageAdjustRatioXposed(): Boolean = readSnapshotXposed().clipboardImageAdjustRatio
    fun isClipboardImageCropXposed(): Boolean = readSnapshotXposed().clipboardImageCrop
    fun isClipboardImageUniformRowHeightXposed(): Boolean =
        readSnapshotXposed().clipboardImageUniformRowHeight

    fun getClipboardImageMaxCountXposed(): Int = readSnapshotXposed().clipboardImageMaxCount

    fun getClipboardImageMaxSizeMbXposed(): Int = readSnapshotXposed().clipboardImageMaxSizeMb

    fun isQwertyGestureEnabledXposed(): Boolean = readSnapshotXposed().qwertyGestureEnabled
    fun isT9GestureEnabledXposed(): Boolean = readSnapshotXposed().t9GestureEnabled
    fun getGestureThresholdXposed(): Int = readSnapshotXposed().gestureThreshold
    fun getT9GestureThresholdXposed(): Int = readSnapshotXposed().t9GestureThreshold
    fun isGestureVibrationXposed(): Boolean = readSnapshotXposed().gestureVibration
    fun isT9GestureVibrationXposed(): Boolean = readSnapshotXposed().t9GestureVibration
    fun getGestureBindingsJsonXposed(): String = readSnapshotXposed().gestureBindingsJson
    fun isShowGestureKeyLabelsXposed(): Boolean = readSnapshotXposed().showGestureKeyLabels
    fun getGestureLabelTextSizeSpXposed(): Int = readSnapshotXposed().gestureLabelTextSizeSp
    fun getGestureLabelAlphaXposed(): Int = readSnapshotXposed().gestureLabelAlpha
    fun getGestureLabelPositionXposed(): Int = readSnapshotXposed().gestureLabelPosition
    fun getGestureLabelMarginTopDpXposed(): Int = readSnapshotXposed().gestureLabelMarginTopDp
    fun getGestureLabelMarginBottomDpXposed(): Int = readSnapshotXposed().gestureLabelMarginBottomDp
    fun getGestureLabelMarginLeftDpXposed(): Int = readSnapshotXposed().gestureLabelMarginLeftDp
    fun getGestureLabelMarginRightDpXposed(): Int = readSnapshotXposed().gestureLabelMarginRightDp

    fun isLogoEnabled(context: Context): Boolean = readSnapshot(context).logoEnabled
    fun isLogoShowEnabled(context: Context): Boolean = readSnapshot(context).logoShowEnabled
    fun getLogoColorMode(context: Context): String = readSnapshot(context).logoColorMode
    fun getLogoCustomColor(context: Context): Int = readSnapshot(context).logoCustomColor
    fun isLogoImageEnabled(context: Context): Boolean = readSnapshot(context).logoImageEnabled
    fun getLogoImageType(context: Context): String = readSnapshot(context).logoImageType
    fun isLogoSvgRecolorEnabled(context: Context): Boolean = readSnapshot(context).logoSvgRecolorEnabled
    fun getLogoImagePngBase64(context: Context): String = readSnapshot(context).logoImagePngBase64
    fun getLogoImageSvgText(context: Context): String = readSnapshot(context).logoImageSvgText
    fun getLogoImageName(context: Context): String = readSnapshot(context).logoImageName
    fun getLogoImageUpdatedAt(context: Context): Long = readSnapshot(context).logoImageUpdatedAt

    fun isLogoEnabledXposed(): Boolean = readSnapshotXposed().logoEnabled
    fun isLogoShowEnabledXposed(): Boolean = readSnapshotXposed().logoShowEnabled
    fun getLogoColorModeXposed(): String = readSnapshotXposed().logoColorMode
    fun getLogoCustomColorXposed(): Int = readSnapshotXposed().logoCustomColor
    fun isLogoImageEnabledXposed(): Boolean = readSnapshotXposed().logoImageEnabled
    fun getLogoImageTypeXposed(): String = readSnapshotXposed().logoImageType
    fun isLogoSvgRecolorEnabledXposed(): Boolean = readSnapshotXposed().logoSvgRecolorEnabled
    fun getLogoImagePngBase64Xposed(): String = readSnapshotXposed().logoImagePngBase64
    fun getLogoImageSvgTextXposed(): String = readSnapshotXposed().logoImageSvgText
    fun getLogoImageUpdatedAtXposed(): Long = readSnapshotXposed().logoImageUpdatedAt

    fun getFontMode(context: Context): Int = readSnapshot(context).fontMode

    fun getFontModeXposed(): Int = readSnapshotXposed().fontMode

    fun getLightColor(context: Context): Int = readSnapshot(context).lightColor

    fun getDarkColor(context: Context): Int = readSnapshot(context).darkColor

    fun getBlurRadius(context: Context): Int = readSnapshot(context).blurRadius

    fun getCornerRadius(context: Context): Int = readSnapshot(context).cornerRadius

    fun getBottomCornerRadius(context: Context): Int = readSnapshot(context).bottomCornerRadius

    fun getKeyCornerRadius(context: Context): Int = readSnapshot(context).keyCornerRadius

    fun isEdgeHighlightEnabled(context: Context): Boolean = readSnapshot(context).edgeHighlightEnabled

    fun getEdgeHighlightIntensity(context: Context): Int = readSnapshot(context).edgeHighlightIntensity

    fun getColorOsLightAngle(context: Context): Int = readSnapshot(context).colorOsLightAngle

    /**
     * 光感相关字段的定点更新入口：读当前快照后只覆盖光感字段，
     * 其余设置原样回写。同时把 ColorOS 光感与 HyperOS 质感开关解耦——
     * 光感是否生效只看这里的 `edgeHighlightEnabled`。
     */
    fun saveColorOsLight(
        context: Context,
        edgeHighlightEnabled: Boolean? = null,
        edgeHighlightIntensity: Int? = null,
        colorOsLightAngle: Int? = null,
        iconEdgeLightEnabled: Boolean? = null,
        keyEdgeLightEnabled: Boolean? = null,
        nativeEdgeLightEnabled: Boolean? = null,
        edgeLightWidth: Int? = null,
        nativeEdgeLightWidth: Int? = null,
        edgeLightAngle: Int? = null,
        glowIntensity: Int? = null,
        onPersisted: (Boolean) -> Unit = {}
    ): Boolean {
        val current = readLocalSnapshot(context)
        return save(
            context = context,
            lightColor = current.lightColor,
            darkColor = current.darkColor,
            blurRadius = current.blurRadius,
            cornerRadius = current.cornerRadius,
            bottomCornerRadius = current.bottomCornerRadius,
            keyCornerRadius = current.keyCornerRadius,
            edgeHighlightEnabled = edgeHighlightEnabled ?: current.edgeHighlightEnabled,
            edgeHighlightIntensity = edgeHighlightIntensity ?: current.edgeHighlightIntensity,
            colorOsLightAngle = colorOsLightAngle ?: current.colorOsLightAngle,
            candidateBackgroundAlpha = current.candidateBackgroundAlpha,
            candidateBackgroundCorner = current.candidateBackgroundCorner,
            candidateBackgroundLeftMarginDp = current.candidateBackgroundLeftMarginDp,
            candidatePinyinLeftMarginDp = current.candidatePinyinLeftMarginDp,
            toolbarIconBgOpacity = current.toolbarIconBgOpacity,
            iconEdgeLightEnabled = iconEdgeLightEnabled ?: current.iconEdgeLightEnabled,
            keyEdgeLightEnabled = keyEdgeLightEnabled ?: current.keyEdgeLightEnabled,
            nativeEdgeLightEnabled = nativeEdgeLightEnabled ?: current.nativeEdgeLightEnabled,
            edgeLightWidth = edgeLightWidth ?: current.edgeLightWidth,
            nativeEdgeLightWidth = nativeEdgeLightWidth ?: current.nativeEdgeLightWidth,
            edgeLightAngle = edgeLightAngle ?: current.edgeLightAngle,
            glowIntensity = glowIntensity ?: current.glowIntensity,
            appearanceColors = current.appearanceColors,
            disableHotUpdate = current.disableHotUpdate,
            showCrossDeviceClipboard = current.showCrossDeviceClipboard,
            removeClipboardRetentionLimit = current.removeClipboardRetentionLimit,
            removeClipboardTextLimit = current.removeClipboardTextLimit,
            clipboardSearchEnabled = current.clipboardSearchEnabled,
            clipboardSearchClearOnBack = current.clipboardSearchClearOnBack,
            clipboardImageAdjustRatio = current.clipboardImageAdjustRatio,
            clipboardImageCrop = current.clipboardImageCrop,
            clipboardImageUniformRowHeight = current.clipboardImageUniformRowHeight,
            clipboardImageMaxCount = current.clipboardImageMaxCount,
            clipboardImageMaxSizeMb = current.clipboardImageMaxSizeMb,
            qwertyGestureEnabled = current.qwertyGestureEnabled,
            t9GestureEnabled = current.t9GestureEnabled,
            gestureThreshold = current.gestureThreshold,
            t9GestureThreshold = current.t9GestureThreshold,
            gestureVibration = current.gestureVibration,
            t9GestureVibration = current.t9GestureVibration,
            gestureBindingsJson = current.gestureBindingsJson,
            showGestureKeyLabels = current.showGestureKeyLabels,
            gestureLabelTextSizeSp = current.gestureLabelTextSizeSp,
            gestureLabelAlpha = current.gestureLabelAlpha,
            gestureLabelPosition = current.gestureLabelPosition,
            gestureLabelMarginTopDp = current.gestureLabelMarginTopDp,
            gestureLabelMarginBottomDp = current.gestureLabelMarginBottomDp,
            gestureLabelMarginLeftDp = current.gestureLabelMarginLeftDp,
            gestureLabelMarginRightDp = current.gestureLabelMarginRightDp,
            logoEnabled = current.logoEnabled,
            logoShowEnabled = current.logoShowEnabled,
            logoColorMode = current.logoColorMode,
            logoCustomColor = current.logoCustomColor,
            logoImageEnabled = current.logoImageEnabled,
            logoImageType = current.logoImageType,
            logoSvgRecolorEnabled = current.logoSvgRecolorEnabled,
            logoImagePngBase64 = current.logoImagePngBase64,
            logoImageSvgText = current.logoImageSvgText,
            logoImageName = current.logoImageName,
            logoImageUpdatedAt = current.logoImageUpdatedAt,
            fontMode = current.fontMode,
            systemMaterialEnabled = current.systemMaterialEnabled,
            hyperMaterialEnabled = current.hyperMaterialEnabled,
            glassOverrides = current.glassOverrides,
            onPersisted = onPersisted
        )
    }

    fun getCandidateBackgroundAlpha(context: Context): Int =
        readSnapshot(context).candidateBackgroundAlpha

    fun getCandidateBackgroundCorner(context: Context): Float =
        readSnapshot(context).candidateBackgroundCorner

    fun getCandidateBackgroundLeftMarginDp(context: Context): Int =
        readSnapshot(context).candidateBackgroundLeftMarginDp

    fun getCandidatePinyinLeftMarginDp(context: Context): Int =
        readSnapshot(context).candidatePinyinLeftMarginDp

    fun getAppearanceColors(context: Context): Map<String, Int> = readSnapshot(context).appearanceColors

    fun isDisableHotUpdate(context: Context): Boolean = readSnapshot(context).disableHotUpdate

    /**
     * 注册远端偏好解析器（仅宿主进程）：`remotePreferences` 被解绑或读取失败时按需重绑，
     * 防止强调色等设置永久回退默认值。lambda 持有 Xposed 接口实例，仅存活于当前模块代际。
     */
    fun bindRemotePreferencesProvider(provider: (() -> SharedPreferences?)?) {
        remotePreferencesProvider = provider
    }

    fun bindRemotePreferences(preferences: SharedPreferences) {
        synchronized(remotePrefsLock) {
            if (remotePreferences === preferences) return
            remotePreferences?.let { previous ->
                runCatching {
                    previous.unregisterOnSharedPreferenceChangeListener(remotePrefChangeListener)
                }
            }
            remotePreferences = preferences
            runCatching {
                preferences.registerOnSharedPreferenceChangeListener(remotePrefChangeListener)
            }
        }
        cachedXposedSnapshot = null
    }

    fun unbindRemotePreferences() {
        synchronized(remotePrefsLock) {
            remotePreferences?.let { preferences ->
                runCatching {
                    preferences.unregisterOnSharedPreferenceChangeListener(remotePrefChangeListener)
                }
            }
            remotePreferences = null
        }
        cachedXposedSnapshot = null
    }

    fun prepareForHotReload() = unbindRemotePreferences()

    /**
     * 让本进程那份宿主偏好的内存表跟磁盘上的文件对齐，并丢掉快照缓存。
     *
     * 存在的理由是「实时效果预览」：设置页（`com.tencent.wetype`）与输入法本体（`:hld`）
     * 是两个进程，设置页写盘后 `:hld` 里那份内存快照还是旧的。[WeTypeProcessRestarter]
     * 靠杀掉 `:hld` 让下次 `onStartInputView` 重新读，代价是整块键盘重来一次 ——
     * 预览开着时改一个值就得重来一次，没法边调边看。这里给
     * [com.xposed.wetypehook.wetype.hook.KeyboardPreviewLiveReload] 一个定点失效的入口，
     * 配合 `reconcileCurrentInputMethodService` 就能原地重放。
     *
     * 光清 [cachedXposedSnapshot] 不够：`SharedPreferences` 自己就有一层进程内缓存，
     * 别的进程改了文件它并不知道。`MODE_MULTI_PROCESS` 自 O 起标为废弃，但
     * `ContextImpl.getSharedPreferences` 里那条 `startReloadIfChangedUnexpectedly()` 分支
     * 至今还在：它比对文件的 mtime/size，变了就把内存表标成待加载并从磁盘重读，紧接着的
     * 任一次 getter 会被 `awaitLoadedLocked()` 拦到重读结束。所以这个调用返回时，本进程
     * 后续读到的一定是设置页刚写下的那份。
     */
    fun reloadHostPreferences(context: Context) {
        val appContext = context.applicationContext ?: context
        @Suppress("DEPRECATION")
        val preferences = appContext.getSharedPreferences(
            PREF_GROUP,
            Context.MODE_PRIVATE or Context.MODE_MULTI_PROCESS
        )
        // 触发那次重读之后随便读一个键，把异步的磁盘加载等到结束。
        preferences.contains(KEY_APPEARANCE_STAGE_PREVIEW)
        cachedXposedSnapshot = null
    }

    /**
     * 宿主偏好落盘的那个文件。`:hld` 侧靠比对它的 mtime/size 发现设置页刚写下的改动 ——
     * 两个进程同包同 uid，写的就是同一个文件，不需要任何 IPC。
     */
    fun hostPreferencesFile(context: Context): File {
        val appContext = context.applicationContext ?: context
        return File(appContext.dataDir, "shared_prefs/$PREF_GROUP.xml")
    }

    fun bindModuleBridgePendingIntent(pendingIntent: PendingIntent?) {
        moduleBridgePendingIntent = pendingIntent
    }

    /**
     * `:hld` 进程启动时决定"认哪份设置"的唯一入口。
     *
     * ## 判据：宿主本地那份文件是唯一事实来源
     *
     * `:hld` 是输入法本体所在进程，它自己就是保存动作的落笔处 —— [save] 直接写宿主
     * `shared_prefs`。所以这里**必须**先认本地那份，远端（模块 App）只作为本地从未
     * 落过盘时的初始化种子。
     *
     * 早前这里是反的：`remoteSnapshot != null ->` 排在 `localSnapshot != null` 前面，
     * 于是宿主每次拉起都会拿模块 App 里那份**旧副本**回写覆盖本地。用户在设置页刚存好
     * 的手势绑定，输入法重启一次就被抹回上一次桥接成功时的样子 —— 这正是「q/r 上的
     * 模块设置绑定保存不了」的真凶：保存其实成功了，是被随后的启动流程自己覆盖掉的。
     *
     * 远端副本的作用仅剩一个：首次安装、本地还没有任何快照时提供初始值。
     */
    fun ensureHostSnapshot(context: Context) {
        val appContext = context.applicationContext ?: context
        val localPreferences = appPreferences(appContext)
        val localSnapshot = localPreferences.toSnapshotOrNull()
        if (appContext.packageName == MODULE_PACKAGE_NAME) {
            synchronizeRemotePreferences(appContext)
            return
        }
        hostContextRef = java.lang.ref.WeakReference(appContext)
        val hostSyncPending = localPreferences.getBoolean(KEY_HOST_SYNC_PENDING, false)

        // 宿主本地已有快照：它就是权威值。待同步标记可能来自上一次镜像没送达，
        // 这里补发一次即可 —— 补发成功就清掉标记，失败就留着下次再试，本地并不会
        // 因此变旧。
        if (localSnapshot != null) {
            cachedXposedSnapshot = localSnapshot
            if (hostSyncPending) {
                val revision = localPreferences.getLong(KEY_HOST_SYNC_REVISION, 0L)
                val delivered = sendSnapshotToModule(appContext, localSnapshot, revision)
                localPreferences.edit().putBoolean(KEY_HOST_SYNC_PENDING, !delivered).commit()
            }
            return
        }

        // 本地还没有快照（首次安装 / 数据被清）：这时才轮到用模块 App 那份当初始值。
        val remoteSnapshot = synchronized(remotePrefsLock) { resolvedRemotePreferencesLocked() }
            ?.toSnapshotOrNull()
        if (remoteSnapshot != null) {
            writeSnapshot(localPreferences, remoteSnapshot)
            cachedXposedSnapshot = remoteSnapshot
        }
    }

    fun synchronizeRemotePreferences(context: Context) {
        val appContext = context.applicationContext ?: context
        if (appContext.packageName != MODULE_PACKAGE_NAME) return
        synchronized(settingsSyncLock) {
            synchronizeRemotePreferencesLocked(appContext)
        }
    }

    private fun synchronizeRemotePreferencesLocked(context: Context) {
        val remote = remotePreferences ?: return
        val localPreferences = appPreferences(context)
        val localSnapshot = localPreferences.toSnapshotOrNull()
        val remoteSnapshot = remote.toSnapshotOrNull()
        val pending = localPreferences.getBoolean(KEY_REMOTE_SYNC_PENDING, false)
        when {
            pending && localSnapshot != null -> {
                val synced = runCatching { writeSnapshot(remote, localSnapshot) }
                    .getOrDefault(false)
                if (synced) {
                    localPreferences.edit().putBoolean(KEY_REMOTE_SYNC_PENDING, false).commit()
                }
                if (synced) cachedXposedSnapshot = localSnapshot
            }

            remoteSnapshot == null && localSnapshot != null -> {
                val synced = runCatching { writeSnapshot(remote, localSnapshot) }
                    .getOrDefault(false)
                if (synced) {
                    localPreferences.edit().putBoolean(KEY_REMOTE_SYNC_PENDING, false).commit()
                }
                if (synced) cachedXposedSnapshot = localSnapshot
            }

            remoteSnapshot != null -> {
                val mirrored = writeSnapshot(localPreferences, remoteSnapshot) { editor ->
                    editor.putBoolean(KEY_REMOTE_SYNC_PENDING, false)
                }
                if (mirrored) cachedXposedSnapshot = remoteSnapshot
            }
        }
    }

    fun importBridgedSettings(context: Context, settings: Bundle): Boolean {
        val appContext = context.applicationContext ?: context
        if (appContext.packageName != MODULE_PACKAGE_NAME) {
            return false
        }
        return synchronized(settingsSyncLock) {
            val localPreferences = appPreferences(appContext)
            val fallbackColors = localPreferences.toSnapshotOrNull()?.appearanceColors.orEmpty()
            val snapshot = settings.toSnapshot(fallbackColors)
            val revision = settings.getLong(ModuleBridgeContract.EXTRA_REVISION, 0L)
            val lastImportedRevision = localPreferences.getLong(KEY_LAST_IMPORTED_REVISION, 0L)
            if (revision > 0L && revision < lastImportedRevision) {
                return@synchronized true
            }
            val staged = writeSnapshot(localPreferences, snapshot) { editor ->
                editor
                    .putBoolean(KEY_REMOTE_SYNC_PENDING, true)
                    .putLong(KEY_LAST_IMPORTED_REVISION, revision)
            }
            if (!staged) return@synchronized false
            cachedXposedSnapshot = snapshot
            synchronizeRemotePreferencesLocked(appContext)
            true
        }
    }

    fun save(
        context: Context,
        lightColor: Int,
        darkColor: Int,
        blurRadius: Int,
        cornerRadius: Int,
        bottomCornerRadius: Int = DEFAULT_BOTTOM_CORNER_RADIUS,
        keyCornerRadius: Int,
        edgeHighlightEnabled: Boolean,
        edgeHighlightIntensity: Int,
        colorOsLightAngle: Int = DEFAULT_COLOROS_LIGHT_ANGLE,
        candidateBackgroundAlpha: Int,
        candidateBackgroundCorner: Float,
        candidateBackgroundLeftMarginDp: Int,
        candidatePinyinLeftMarginDp: Int,
        toolbarIconBgOpacity: Int,
        iconEdgeLightEnabled: Boolean = DEFAULT_ICON_EDGE_LIGHT_ENABLED,
        keyEdgeLightEnabled: Boolean = DEFAULT_KEY_EDGE_LIGHT_ENABLED,
        nativeEdgeLightEnabled: Boolean = DEFAULT_NATIVE_EDGE_LIGHT_ENABLED,
        edgeLightWidth: Int = DEFAULT_EDGE_LIGHT_WIDTH,
        nativeEdgeLightWidth: Int = DEFAULT_NATIVE_EDGE_LIGHT_WIDTH,
        edgeLightAngle: Int = DEFAULT_EDGE_LIGHT_ANGLE,
        glowIntensity: Int = DEFAULT_GLOW_INTENSITY,
        appearanceColors: Map<String, Int>,
        disableHotUpdate: Boolean = DEFAULT_DISABLE_HOT_UPDATE,
        showCrossDeviceClipboard: Boolean = DEFAULT_SHOW_CROSS_DEVICE_CLIPBOARD,
        removeClipboardRetentionLimit: Boolean = DEFAULT_REMOVE_CLIPBOARD_RETENTION_LIMIT,
        removeClipboardTextLimit: Boolean = DEFAULT_REMOVE_CLIPBOARD_TEXT_LIMIT,
        clipboardSearchEnabled: Boolean = DEFAULT_CLIPBOARD_SEARCH_ENABLED,
        clipboardSearchClearOnBack: Boolean = DEFAULT_CLIPBOARD_SEARCH_CLEAR_ON_BACK,
        clipboardImageAdjustRatio: Boolean = DEFAULT_CLIPBOARD_IMAGE_ADJUST_RATIO,
        clipboardImageCrop: Boolean = DEFAULT_CLIPBOARD_IMAGE_CROP,
        clipboardImageUniformRowHeight: Boolean = DEFAULT_CLIPBOARD_IMAGE_UNIFORM_ROW_HEIGHT,
        clipboardImageMaxCount: Int = DEFAULT_CLIPBOARD_IMAGE_MAX_COUNT,
        clipboardImageMaxSizeMb: Int = DEFAULT_CLIPBOARD_IMAGE_MAX_SIZE_MB,
        qwertyGestureEnabled: Boolean = DEFAULT_QWERTY_GESTURE_ENABLED,
        t9GestureEnabled: Boolean = DEFAULT_T9_GESTURE_ENABLED,
        gestureThreshold: Int = DEFAULT_GESTURE_THRESHOLD,
        t9GestureThreshold: Int = DEFAULT_T9_GESTURE_THRESHOLD,
        gestureVibration: Boolean = DEFAULT_GESTURE_VIBRATION,
        t9GestureVibration: Boolean = DEFAULT_T9_GESTURE_VIBRATION,
        gestureBindingsJson: String = DEFAULT_GESTURE_BINDINGS_JSON,
        showGestureKeyLabels: Boolean = DEFAULT_SHOW_GESTURE_KEY_LABELS,
        gestureLabelTextSizeSp: Int = DEFAULT_GESTURE_LABEL_TEXT_SIZE_SP,
        gestureLabelAlpha: Int = DEFAULT_GESTURE_LABEL_ALPHA,
        gestureLabelPosition: Int = DEFAULT_GESTURE_LABEL_POSITION,
        gestureLabelMarginTopDp: Int = DEFAULT_GESTURE_LABEL_MARGIN_TOP_DP,
        gestureLabelMarginBottomDp: Int = DEFAULT_GESTURE_LABEL_MARGIN_BOTTOM_DP,
        gestureLabelMarginLeftDp: Int = DEFAULT_GESTURE_LABEL_MARGIN_LEFT_DP,
        gestureLabelMarginRightDp: Int = DEFAULT_GESTURE_LABEL_MARGIN_RIGHT_DP,
        logoEnabled: Boolean = DEFAULT_LOGO_ENABLED,
        logoShowEnabled: Boolean = DEFAULT_LOGO_SHOW_ENABLED,
        logoColorMode: String = DEFAULT_LOGO_COLOR_MODE,
        logoCustomColor: Int = DEFAULT_LOGO_CUSTOM_COLOR,
        logoImageEnabled: Boolean = DEFAULT_LOGO_IMAGE_ENABLED,
        logoImageType: String = DEFAULT_LOGO_IMAGE_TYPE,
        logoSvgRecolorEnabled: Boolean = DEFAULT_LOGO_SVG_RECOLOR_ENABLED,
        logoImagePngBase64: String = DEFAULT_LOGO_IMAGE_PNG_BASE64,
        logoImageSvgText: String = DEFAULT_LOGO_IMAGE_SVG_TEXT,
        logoImageName: String = DEFAULT_LOGO_IMAGE_NAME,
        logoImageUpdatedAt: Long = DEFAULT_LOGO_IMAGE_UPDATED_AT,
        fontMode: Int = DEFAULT_FONT_MODE,
        systemMaterialEnabled: Boolean = DEFAULT_SYSTEM_MATERIAL_ENABLED,
        hyperMaterialEnabled: Boolean = DEFAULT_HYPER_MATERIAL_ENABLED,
        glassOverrides: GlassMaterialOverrides = GlassMaterialOverrides(),
        onPersisted: (Boolean) -> Unit = {}
    ): Boolean {
        val sanitizedAppearanceColors = WeTypeAppearanceColorGroups.groups.associate { group ->
            group.id to (appearanceColors[group.id] ?: group.defaultColor)
        }
        return saveDirect(
            context = context,
            lightColor = lightColor,
            darkColor = darkColor,
            blurRadius = blurRadius,
            cornerRadius = cornerRadius,
            bottomCornerRadius = bottomCornerRadius,
            keyCornerRadius = keyCornerRadius,
            edgeHighlightEnabled = edgeHighlightEnabled,
            edgeHighlightIntensity = edgeHighlightIntensity,
            colorOsLightAngle = colorOsLightAngle,
            candidateBackgroundAlpha = candidateBackgroundAlpha,
            candidateBackgroundCorner = candidateBackgroundCorner,
            candidateBackgroundLeftMarginDp = candidateBackgroundLeftMarginDp,
            candidatePinyinLeftMarginDp = candidatePinyinLeftMarginDp,
            toolbarIconBgOpacity = toolbarIconBgOpacity,
            iconEdgeLightEnabled = iconEdgeLightEnabled,
            keyEdgeLightEnabled = keyEdgeLightEnabled,
            nativeEdgeLightEnabled = nativeEdgeLightEnabled,
            edgeLightWidth = edgeLightWidth,
            nativeEdgeLightWidth = nativeEdgeLightWidth,
            edgeLightAngle = edgeLightAngle,
            glowIntensity = glowIntensity,
            appearanceColors = sanitizedAppearanceColors,
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
            gestureLabelTextSizeSp = gestureLabelTextSizeSp.coerceIn(6, 16),
            gestureLabelAlpha = gestureLabelAlpha.coerceIn(0, 255),
            gestureLabelPosition = normalizeGestureLabelPosition(gestureLabelPosition),
            gestureLabelMarginTopDp = gestureLabelMarginTopDp.coerceIn(GESTURE_LABEL_MARGIN_MIN_DP, GESTURE_LABEL_MARGIN_MAX_DP),
            gestureLabelMarginBottomDp = gestureLabelMarginBottomDp.coerceIn(GESTURE_LABEL_MARGIN_MIN_DP, GESTURE_LABEL_MARGIN_MAX_DP),
            gestureLabelMarginLeftDp = gestureLabelMarginLeftDp.coerceIn(GESTURE_LABEL_MARGIN_MIN_DP, GESTURE_LABEL_MARGIN_MAX_DP),
            gestureLabelMarginRightDp = gestureLabelMarginRightDp.coerceIn(GESTURE_LABEL_MARGIN_MIN_DP, GESTURE_LABEL_MARGIN_MAX_DP),
            logoEnabled = logoEnabled,
            logoShowEnabled = logoShowEnabled,
            logoColorMode = logoColorMode,
            logoCustomColor = logoCustomColor,
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
            glassOverrides = glassOverrides,
            onPersisted = onPersisted
        )
    }

    /**
     * 二级图片 Logo 页的定点更新入口：读当前快照后只覆盖图片相关字段，
     * 其余设置原样回写，避免整页保存误删无关配置。
     * pngBase64/svgText 传 null 表示保持，传 "" 表示清空对应图片。
     */
    fun saveLogoImage(
        context: Context,
        enabled: Boolean? = null,
        imageType: String? = null,
        svgRecolorEnabled: Boolean? = null,
        pngBase64: String? = null,
        svgText: String? = null,
        imageName: String? = null,
        updatedAt: Long? = null,
        onPersisted: (Boolean) -> Unit = {}
    ): Boolean {
        val current = readLocalSnapshot(context)
        return save(
            context = context,
            lightColor = current.lightColor,
            darkColor = current.darkColor,
            blurRadius = current.blurRadius,
            cornerRadius = current.cornerRadius,
            bottomCornerRadius = current.bottomCornerRadius,
            keyCornerRadius = current.keyCornerRadius,
            edgeHighlightEnabled = current.edgeHighlightEnabled,
            edgeHighlightIntensity = current.edgeHighlightIntensity,
            colorOsLightAngle = current.colorOsLightAngle,
            candidateBackgroundAlpha = current.candidateBackgroundAlpha,
            candidateBackgroundCorner = current.candidateBackgroundCorner,
            candidateBackgroundLeftMarginDp = current.candidateBackgroundLeftMarginDp,
            candidatePinyinLeftMarginDp = current.candidatePinyinLeftMarginDp,
            toolbarIconBgOpacity = current.toolbarIconBgOpacity,
            iconEdgeLightEnabled = current.iconEdgeLightEnabled,
            keyEdgeLightEnabled = current.keyEdgeLightEnabled,
            nativeEdgeLightEnabled = current.nativeEdgeLightEnabled,
            edgeLightWidth = current.edgeLightWidth,
            nativeEdgeLightWidth = current.nativeEdgeLightWidth,
            edgeLightAngle = current.edgeLightAngle,
            glowIntensity = current.glowIntensity,
            appearanceColors = current.appearanceColors,
            disableHotUpdate = current.disableHotUpdate,
            showCrossDeviceClipboard = current.showCrossDeviceClipboard,
            removeClipboardRetentionLimit = current.removeClipboardRetentionLimit,
            removeClipboardTextLimit = current.removeClipboardTextLimit,
            clipboardSearchEnabled = current.clipboardSearchEnabled,
            clipboardSearchClearOnBack = current.clipboardSearchClearOnBack,
            clipboardImageAdjustRatio = current.clipboardImageAdjustRatio,
            clipboardImageCrop = current.clipboardImageCrop,
            clipboardImageUniformRowHeight = current.clipboardImageUniformRowHeight,
            clipboardImageMaxCount = current.clipboardImageMaxCount,
            clipboardImageMaxSizeMb = current.clipboardImageMaxSizeMb,
            qwertyGestureEnabled = current.qwertyGestureEnabled,
            t9GestureEnabled = current.t9GestureEnabled,
            gestureThreshold = current.gestureThreshold,
            t9GestureThreshold = current.t9GestureThreshold,
            gestureVibration = current.gestureVibration,
            t9GestureVibration = current.t9GestureVibration,
            gestureBindingsJson = current.gestureBindingsJson,
            showGestureKeyLabels = current.showGestureKeyLabels,
            gestureLabelTextSizeSp = current.gestureLabelTextSizeSp,
            gestureLabelAlpha = current.gestureLabelAlpha,
            gestureLabelPosition = current.gestureLabelPosition,
            gestureLabelMarginTopDp = current.gestureLabelMarginTopDp,
            gestureLabelMarginBottomDp = current.gestureLabelMarginBottomDp,
            gestureLabelMarginLeftDp = current.gestureLabelMarginLeftDp,
            gestureLabelMarginRightDp = current.gestureLabelMarginRightDp,
            logoEnabled = current.logoEnabled,
            logoShowEnabled = current.logoShowEnabled,
            logoColorMode = current.logoColorMode,
            logoCustomColor = current.logoCustomColor,
            logoImageEnabled = enabled ?: current.logoImageEnabled,
            logoImageType = normalizeLogoImageType(imageType ?: current.logoImageType),
            logoSvgRecolorEnabled = svgRecolorEnabled ?: current.logoSvgRecolorEnabled,
            logoImagePngBase64 = pngBase64 ?: current.logoImagePngBase64,
            logoImageSvgText = svgText ?: current.logoImageSvgText,
            logoImageName = imageName ?: current.logoImageName,
            logoImageUpdatedAt = updatedAt ?: current.logoImageUpdatedAt,
            fontMode = current.fontMode,
            systemMaterialEnabled = current.systemMaterialEnabled,
            hyperMaterialEnabled = current.hyperMaterialEnabled,
            glassOverrides = current.glassOverrides,
            onPersisted = onPersisted
        )
    }

    fun getCurrentBackgroundColorXposed(context: Context): Int {
        val snapshot = readSnapshotXposed()
        val isDarkMode =
            context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
        return if (isDarkMode) snapshot.darkColor else snapshot.lightColor
    }

    fun getBlurRadiusXposed(context: Context): Int = readSnapshotXposed().blurRadius

    fun getCornerRadiusXposed(context: Context): Int = readSnapshotXposed().cornerRadius

    fun getBottomCornerRadiusXposed(context: Context): Int = readSnapshotXposed().bottomCornerRadius

    fun getKeyCornerRadiusXposed(): Int = readSnapshotXposed().keyCornerRadius

    fun isEdgeHighlightEnabledXposed(context: Context): Boolean =
        readSnapshotXposed().edgeHighlightEnabled

    fun getEdgeHighlightIntensityXposed(context: Context): Int =
        readSnapshotXposed().edgeHighlightIntensity

    fun getColorOsLightAngleXposed(): Int = readSnapshotXposed().colorOsLightAngle

    fun getCandidateBackgroundAlphaXposed(): Int =
        readSnapshotXposed().candidateBackgroundAlpha

    fun getCandidateBackgroundCornerXposed(): Float =
        readSnapshotXposed().candidateBackgroundCorner

    fun getCandidateBackgroundLeftMarginDpXposed(): Int =
        readSnapshotXposed().candidateBackgroundLeftMarginDp

    fun getToolbarIconBgOpacityXposed(): Int =
        readSnapshotXposed().toolbarIconBgOpacity

    fun isIconEdgeLightEnabledXposed(): Boolean =
        readSnapshotXposed().iconEdgeLightEnabled

    fun isKeyEdgeLightEnabled(context: Context): Boolean =
        readSnapshot(context).keyEdgeLightEnabled

    fun isKeyEdgeLightEnabledXposed(): Boolean =
        readSnapshotXposed().keyEdgeLightEnabled

    fun isNativeEdgeLightEnabledXposed(): Boolean =
        readSnapshotXposed().nativeEdgeLightEnabled

    fun getEdgeLightWidthXposed(): Int = readSnapshotXposed().edgeLightWidth

    fun getNativeEdgeLightWidthXposed(): Int = readSnapshotXposed().nativeEdgeLightWidth

    fun getEdgeLightAngleXposed(): Int = readSnapshotXposed().edgeLightAngle

    fun getGlowIntensityXposed(): Int = readSnapshotXposed().glowIntensity

    fun getCandidatePinyinLeftMarginDpXposed(): Int =
        readSnapshotXposed().candidatePinyinLeftMarginDp

    fun isDisableHotUpdateXposed(): Boolean = readSnapshotXposed().disableHotUpdate

    fun isSystemMaterialEnabled(context: Context): Boolean = readSnapshot(context).systemMaterialEnabled

    fun isSystemMaterialEnabledXposed(): Boolean = readSnapshotXposed().systemMaterialEnabled

    fun isHyperMaterialEnabled(context: Context): Boolean = readSnapshot(context).hyperMaterialEnabled

    fun isHyperMaterialEnabledXposed(): Boolean = readSnapshotXposed().hyperMaterialEnabled

    fun getGlassOverrides(context: Context): GlassMaterialOverrides =
        readSnapshot(context).glassOverrides

    fun getGlassOverridesXposed(): GlassMaterialOverrides = readSnapshotXposed().glassOverrides

    fun getAppearanceColorXposed(groupId: String): Int =
        readSnapshotXposed().appearanceColors[groupId]
            ?: WeTypeAppearanceColorGroups.findById(groupId)?.defaultColor
            ?: 0

    fun getAppearanceColorsXposed(): Map<String, Int> = readSnapshotXposed().appearanceColors

    /**
     * 本进程可见的最新快照。**本进程落盘的那份优先**。
     *
     * 宿主进程里 `appPreferences` 就是 `:hld` 保存时写的那份文件，天然最新；
     * `remotePreferences`（模块 App 的副本）只是镜像，可能落后一整个桥接周期。
     * 顺序反过来会让刚保存的设置立刻读回旧值。
     */
    fun readSnapshot(context: Context): Snapshot {
        return appPreferences(context).toSnapshotOrNull()
            ?: remotePreferences?.toSnapshotOrNull()
            ?: defaultSnapshot()
    }

    /**
     * 只读本进程偏好：宿主内对话框与二级页同进程并发写时，本地永远最新，
     * 用它做定点更新的基线，避免读到桥接尚未送达的远端旧值后回写覆盖。
     */
    fun readLocalSnapshot(context: Context): Snapshot {
        return appPreferences(context).toSnapshotOrNull()
            ?: readSnapshot(context)
    }

    internal fun readSnapshotXposed(): Snapshot {
        cachedXposedSnapshot?.let { return it }

        synchronized(remotePrefsLock) {
            cachedXposedSnapshot?.let { return it }
            // ① 本进程偏好 → ② 远端偏好（模块 App 镜像）→ ③ 默认值（不缓存）。
            //
            // 顺序不能反。宿主进程里 `appPreferences` 就是 [saveDirect] 落笔的那个文件，
            // 天然最新；远端那份是镜像，后台同步失败时会长期停在旧值上，把它排在前面
            // 等于让刚保存的设置立刻回滚。
            //
            // 这条顺序对三个进程都成立：
            // - `:hld`：`hostContextRef` 已注入，读到保存时写的那份文件。
            // - WeType 主进程（设置页）：`hostContextRef` 同样已注入，读到的就是设置页
            //   自己写的 WeType 偏好 —— 比原来读模块 App 的旧镜像正确得多。
            // - 模块 App 进程：`hostContextRef` 为空（`ensureHostSnapshot` 对模块包提前
            //   返回，不注入），直接落到远端，行为与改动前一致。
            //
            // 默认值（强调色 #23C891 绿）绝不能被缓存：一次瞬态读取失败曾会让整个进程
            // 的强调色/美化设置退回默认且无法自愈（“Logo/强调色莫名回绿”）。
            val localSnapshot = hostContextRef?.get()?.let { appPreferences(it).toSnapshotOrNull() }
            if (localSnapshot != null) {
                cachedXposedSnapshot = localSnapshot
                return localSnapshot
            }
            val remoteSnapshot = resolvedRemotePreferencesLocked()?.toSnapshotOrNull()
            if (remoteSnapshot != null) {
                cachedXposedSnapshot = remoteSnapshot
                AndroidLog.w(TAG, "host prefs unavailable, falling back to remote module snapshot")
                return remoteSnapshot
            }
            if (defaultFallbackLogged.compareAndSet(false, true)) {
                AndroidLog.w(TAG, "no persisted settings snapshot, serving defaults (uncached)")
            }
            return defaultSnapshot()
        }
    }

    private fun resolvedRemotePreferencesLocked(): SharedPreferences? {
        remotePreferences?.let { return it }
        val provider = remotePreferencesProvider ?: return null
        val resolved = runCatching { provider() }.getOrNull() ?: return null
        remotePreferences = resolved
        runCatching {
            resolved.registerOnSharedPreferenceChangeListener(remotePrefChangeListener)
        }
        AndroidLog.i(TAG, "remote prefs lazily rebound after unbind")
        return resolved
    }

    private fun appPreferences(context: Context): SharedPreferences {
        val appContext = context.applicationContext ?: context
        return appContext.getSharedPreferences(PREF_GROUP, Context.MODE_PRIVATE)
    }

    /**
     * 监听本进程偏好变更：主设置页用它刷新二级页改动带来的摘要，
     * 只更新摘要状态，不覆盖编辑中的值。
     *
     * @return 注销函数。
     */
    fun observeLocalChanges(
        context: Context,
        listener: SharedPreferences.OnSharedPreferenceChangeListener
    ): () -> Unit {
        val preferences = appPreferences(context.applicationContext ?: context)
        preferences.registerOnSharedPreferenceChangeListener(listener)
        return {
            runCatching { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
        }
    }

    private fun saveDirect(
        context: Context,
        lightColor: Int,
        darkColor: Int,
        blurRadius: Int,
        cornerRadius: Int,
        bottomCornerRadius: Int = DEFAULT_BOTTOM_CORNER_RADIUS,
        keyCornerRadius: Int,
        edgeHighlightEnabled: Boolean,
        edgeHighlightIntensity: Int,
        colorOsLightAngle: Int = DEFAULT_COLOROS_LIGHT_ANGLE,
        candidateBackgroundAlpha: Int,
        candidateBackgroundCorner: Float,
        candidateBackgroundLeftMarginDp: Int,
        candidatePinyinLeftMarginDp: Int,
        toolbarIconBgOpacity: Int,
        iconEdgeLightEnabled: Boolean = DEFAULT_ICON_EDGE_LIGHT_ENABLED,
        keyEdgeLightEnabled: Boolean = DEFAULT_KEY_EDGE_LIGHT_ENABLED,
        nativeEdgeLightEnabled: Boolean = DEFAULT_NATIVE_EDGE_LIGHT_ENABLED,
        edgeLightWidth: Int = DEFAULT_EDGE_LIGHT_WIDTH,
        nativeEdgeLightWidth: Int = DEFAULT_NATIVE_EDGE_LIGHT_WIDTH,
        edgeLightAngle: Int = DEFAULT_EDGE_LIGHT_ANGLE,
        glowIntensity: Int = DEFAULT_GLOW_INTENSITY,
        appearanceColors: Map<String, Int>,
        disableHotUpdate: Boolean,
        showCrossDeviceClipboard: Boolean = DEFAULT_SHOW_CROSS_DEVICE_CLIPBOARD,
        removeClipboardRetentionLimit: Boolean = DEFAULT_REMOVE_CLIPBOARD_RETENTION_LIMIT,
        removeClipboardTextLimit: Boolean = DEFAULT_REMOVE_CLIPBOARD_TEXT_LIMIT,
        clipboardSearchEnabled: Boolean = DEFAULT_CLIPBOARD_SEARCH_ENABLED,
        clipboardSearchClearOnBack: Boolean = DEFAULT_CLIPBOARD_SEARCH_CLEAR_ON_BACK,
        clipboardImageAdjustRatio: Boolean = DEFAULT_CLIPBOARD_IMAGE_ADJUST_RATIO,
        clipboardImageCrop: Boolean = DEFAULT_CLIPBOARD_IMAGE_CROP,
        clipboardImageUniformRowHeight: Boolean = DEFAULT_CLIPBOARD_IMAGE_UNIFORM_ROW_HEIGHT,
        clipboardImageMaxCount: Int = DEFAULT_CLIPBOARD_IMAGE_MAX_COUNT,
        clipboardImageMaxSizeMb: Int = DEFAULT_CLIPBOARD_IMAGE_MAX_SIZE_MB,
        qwertyGestureEnabled: Boolean = DEFAULT_QWERTY_GESTURE_ENABLED,
        t9GestureEnabled: Boolean = DEFAULT_T9_GESTURE_ENABLED,
        gestureThreshold: Int = DEFAULT_GESTURE_THRESHOLD,
        t9GestureThreshold: Int = DEFAULT_T9_GESTURE_THRESHOLD,
        gestureVibration: Boolean = DEFAULT_GESTURE_VIBRATION,
        t9GestureVibration: Boolean = DEFAULT_T9_GESTURE_VIBRATION,
        gestureBindingsJson: String = DEFAULT_GESTURE_BINDINGS_JSON,
        showGestureKeyLabels: Boolean = DEFAULT_SHOW_GESTURE_KEY_LABELS,
        gestureLabelTextSizeSp: Int = DEFAULT_GESTURE_LABEL_TEXT_SIZE_SP,
        gestureLabelAlpha: Int = DEFAULT_GESTURE_LABEL_ALPHA,
        gestureLabelPosition: Int = DEFAULT_GESTURE_LABEL_POSITION,
        gestureLabelMarginTopDp: Int = DEFAULT_GESTURE_LABEL_MARGIN_TOP_DP,
        gestureLabelMarginBottomDp: Int = DEFAULT_GESTURE_LABEL_MARGIN_BOTTOM_DP,
        gestureLabelMarginLeftDp: Int = DEFAULT_GESTURE_LABEL_MARGIN_LEFT_DP,
        gestureLabelMarginRightDp: Int = DEFAULT_GESTURE_LABEL_MARGIN_RIGHT_DP,
        logoEnabled: Boolean = DEFAULT_LOGO_ENABLED,
        logoShowEnabled: Boolean = DEFAULT_LOGO_SHOW_ENABLED,
        logoColorMode: String = DEFAULT_LOGO_COLOR_MODE,
        logoCustomColor: Int = DEFAULT_LOGO_CUSTOM_COLOR,
        logoImageEnabled: Boolean = DEFAULT_LOGO_IMAGE_ENABLED,
        logoImageType: String = DEFAULT_LOGO_IMAGE_TYPE,
        logoSvgRecolorEnabled: Boolean = DEFAULT_LOGO_SVG_RECOLOR_ENABLED,
        logoImagePngBase64: String = DEFAULT_LOGO_IMAGE_PNG_BASE64,
        logoImageSvgText: String = DEFAULT_LOGO_IMAGE_SVG_TEXT,
        logoImageName: String = DEFAULT_LOGO_IMAGE_NAME,
        logoImageUpdatedAt: Long = DEFAULT_LOGO_IMAGE_UPDATED_AT,
        fontMode: Int = DEFAULT_FONT_MODE,
        systemMaterialEnabled: Boolean = DEFAULT_SYSTEM_MATERIAL_ENABLED,
        hyperMaterialEnabled: Boolean = DEFAULT_HYPER_MATERIAL_ENABLED,
        glassOverrides: GlassMaterialOverrides = GlassMaterialOverrides(),
        onPersisted: (Boolean) -> Unit
    ): Boolean {
        val snapshot = Snapshot(
            lightColor = lightColor,
            darkColor = darkColor,
            blurRadius = blurRadius.coerceIn(0, 100),
            cornerRadius = cornerRadius.coerceIn(0, MAX_CORNER_RADIUS),
            bottomCornerRadius = bottomCornerRadius.coerceIn(0, MAX_BOTTOM_CORNER_RADIUS),
            keyCornerRadius = keyCornerRadius.coerceIn(0, MAX_KEY_CORNER_RADIUS),
            edgeHighlightEnabled = edgeHighlightEnabled,
            edgeHighlightIntensity = edgeHighlightIntensity.coerceIn(0, MAX_EDGE_HIGHLIGHT_INTENSITY),
            colorOsLightAngle = colorOsLightAngle.coerceIn(0, MAX_COLOROS_LIGHT_ANGLE),
            candidateBackgroundAlpha = candidateBackgroundAlpha.coerceIn(0, 255),
            candidateBackgroundCorner = candidateBackgroundCorner.coerceIn(
                0f,
                MAX_CANDIDATE_BACKGROUND_CORNER.toFloat()
            ),
            candidateBackgroundLeftMarginDp = candidateBackgroundLeftMarginDp
                .coerceIn(MIN_CANDIDATE_MARGIN_DP, MAX_CANDIDATE_MARGIN_DP),
            candidatePinyinLeftMarginDp = candidatePinyinLeftMarginDp
                .coerceIn(MIN_CANDIDATE_MARGIN_DP, MAX_CANDIDATE_MARGIN_DP),
            toolbarIconBgOpacity = toolbarIconBgOpacity.coerceIn(0, 255),
            iconEdgeLightEnabled = iconEdgeLightEnabled,
            keyEdgeLightEnabled = keyEdgeLightEnabled,
            nativeEdgeLightEnabled = nativeEdgeLightEnabled,
            edgeLightWidth = edgeLightWidth.coerceIn(MIN_EDGE_LIGHT_WIDTH, MAX_EDGE_LIGHT_WIDTH),
            nativeEdgeLightWidth = nativeEdgeLightWidth
                .coerceIn(MIN_NATIVE_EDGE_LIGHT_WIDTH, MAX_NATIVE_EDGE_LIGHT_WIDTH),
            edgeLightAngle = edgeLightAngle.coerceIn(MIN_EDGE_LIGHT_ANGLE, MAX_EDGE_LIGHT_ANGLE),
            glowIntensity = glowIntensity.coerceIn(MIN_GLOW_INTENSITY, MAX_GLOW_INTENSITY),
            appearanceColors = WeTypeAppearanceColorGroups.groups.associate { group ->
                group.id to (appearanceColors[group.id] ?: group.defaultColor)
            },
            disableHotUpdate = disableHotUpdate,
            showCrossDeviceClipboard = showCrossDeviceClipboard,
            removeClipboardRetentionLimit = removeClipboardRetentionLimit,
            removeClipboardTextLimit = removeClipboardTextLimit,
            clipboardSearchEnabled = clipboardSearchEnabled,
            clipboardSearchClearOnBack = clipboardSearchClearOnBack,
            clipboardImageAdjustRatio = clipboardImageAdjustRatio,
            clipboardImageCrop = clipboardImageCrop,
            clipboardImageUniformRowHeight = clipboardImageUniformRowHeight,
            clipboardImageMaxCount = sanitizeClipboardImageMaxCount(clipboardImageMaxCount),
            clipboardImageMaxSizeMb = sanitizeClipboardImageMaxSizeMb(clipboardImageMaxSizeMb),
            qwertyGestureEnabled = qwertyGestureEnabled,
            t9GestureEnabled = t9GestureEnabled,
            gestureThreshold = gestureThreshold.coerceIn(10, 48),
            t9GestureThreshold = t9GestureThreshold.coerceIn(10, 48),
            gestureVibration = gestureVibration,
            t9GestureVibration = t9GestureVibration,
            gestureBindingsJson = gestureBindingsJson,
            showGestureKeyLabels = showGestureKeyLabels,
            gestureLabelTextSizeSp = gestureLabelTextSizeSp.coerceIn(6, 16),
            gestureLabelAlpha = gestureLabelAlpha.coerceIn(0, 255),
            gestureLabelPosition = normalizeGestureLabelPosition(gestureLabelPosition),
            gestureLabelMarginTopDp = gestureLabelMarginTopDp.coerceIn(GESTURE_LABEL_MARGIN_MIN_DP, GESTURE_LABEL_MARGIN_MAX_DP),
            gestureLabelMarginBottomDp = gestureLabelMarginBottomDp.coerceIn(GESTURE_LABEL_MARGIN_MIN_DP, GESTURE_LABEL_MARGIN_MAX_DP),
            gestureLabelMarginLeftDp = gestureLabelMarginLeftDp.coerceIn(GESTURE_LABEL_MARGIN_MIN_DP, GESTURE_LABEL_MARGIN_MAX_DP),
            gestureLabelMarginRightDp = gestureLabelMarginRightDp.coerceIn(GESTURE_LABEL_MARGIN_MIN_DP, GESTURE_LABEL_MARGIN_MAX_DP),
            logoEnabled = logoEnabled,
            logoShowEnabled = logoShowEnabled,
            logoColorMode = normalizeLogoColorMode(logoColorMode),
            logoCustomColor = logoCustomColor,
            logoImageEnabled = logoImageEnabled,
            logoImageType = normalizeLogoImageType(logoImageType),
            logoSvgRecolorEnabled = logoSvgRecolorEnabled,
            logoImagePngBase64 = logoImagePngBase64,
            logoImageSvgText = logoImageSvgText,
            logoImageName = logoImageName.take(128),
            logoImageUpdatedAt = logoImageUpdatedAt.coerceAtLeast(0L),
            fontMode = fontMode.coerceIn(FONT_MODE_OFFICIAL, FONT_MODE_SYSTEM),
            systemMaterialEnabled = systemMaterialEnabled,
            hyperMaterialEnabled = hyperMaterialEnabled,
            glassOverrides = glassOverrides
        )
        val appContext = context.applicationContext ?: context
        val localPreferences = appPreferences(appContext)
        return if (appContext.packageName == MODULE_PACKAGE_NAME) {
            val persisted = synchronized(settingsSyncLock) {
                val synced = remotePreferences?.let { preferences ->
                    runCatching { writeSnapshot(preferences, snapshot) }.getOrDefault(false)
                } ?: false
                writeSnapshot(localPreferences, snapshot) { editor ->
                    editor.putBoolean(KEY_REMOTE_SYNC_PENDING, !synced)
                }.also { saved ->
                    if (saved) cachedXposedSnapshot = snapshot
                }
            }
            onPersisted(persisted)
            persisted
        } else {
            val revision = nextHostRevision(localPreferences)
            val staged = writeSnapshot(localPreferences, snapshot) { editor ->
                editor
                    .putBoolean(KEY_HOST_SYNC_PENDING, true)
                    .putLong(KEY_HOST_SYNC_REVISION, revision)
            }
            if (!staged) {
                onPersisted(false)
                return false
            }
            cachedXposedSnapshot = snapshot
            // 判据只有一条：**宿主本地这份文件写成功没有**。
            //
            // 桥接是尽力而为的镜像，不是保存的前提。远端那份副本只服务于模块 App
            // 自己的界面，`:hld` 读的是宿主这份文件 —— 镜像没送到既不影响生效，
            // 也没资格把结果说成失败。
            //
            // 历史实现要等模块 App 回执，等满 5 秒就把整次保存判成失败，
            // 而本地其实早已写好。那条广播被拦下是日常情形：ColorOS 的自启动管理会直接
            // 掐掉唤醒模块 App 的广播
            // （`OplusAppStartupManager: prevent start .../ModuleBridgeReceiver`），
            // 于是每次保存都白等 5 秒，再弹一个与事实相反的「设置保存失败」。
            //
            // 标记的写法因此也反过来了：先把待同步标记落成 false（保存这一瞬间本地就是
            // 最新，没有欠账），只在镜像确实没送出去时才置回 true，留给下一次
            // ensureHostSnapshot 补发。旧写法无条件先置 true、再由回执清掉，一旦回执
            // 丢失就永久卡在"欠同步"，每次启动都白发一条广播。
            var mirrorDelivered = false
            if (isModulePackagePresent(appContext)) {
                mirrorDelivered = sendSnapshotToModule(appContext, snapshot, revision)
            } else {
                // LSPatch 内嵌模式：模块 App 根本不存在，没有镜像可送。
                AndroidLog.i(
                    TAG,
                    "Module app is not installed (LSPatch embed); snapshot kept locally, skipping bridge"
                )
            }
            localPreferences.edit()
                .putBoolean(KEY_HOST_SYNC_PENDING, !mirrorDelivered)
                .commit()
            onPersisted(true)
            return true
        }
    }

    private fun writeSnapshot(
        preferences: SharedPreferences,
        snapshot: Snapshot,
        configureEditor: (SharedPreferences.Editor) -> Unit = {}
    ): Boolean {
        val editor = preferences.edit()
            .putInt(KEY_LIGHT_COLOR, snapshot.lightColor)
            .putInt(KEY_DARK_COLOR, snapshot.darkColor)
            .putInt(KEY_BLUR_RADIUS, snapshot.blurRadius)
            .putInt(KEY_CORNER_RADIUS, snapshot.cornerRadius)
            .putInt(KEY_BOTTOM_CORNER_RADIUS, snapshot.bottomCornerRadius)
            .putInt(KEY_KEY_CORNER_RADIUS, snapshot.keyCornerRadius)
            .putBoolean(KEY_EDGE_HIGHLIGHT_ENABLED, snapshot.edgeHighlightEnabled)
            .putInt(KEY_EDGE_HIGHLIGHT_INTENSITY, snapshot.edgeHighlightIntensity)
            .putInt(KEY_COLOROS_LIGHT_ANGLE, snapshot.colorOsLightAngle)
            .putInt(KEY_CANDIDATE_BACKGROUND_ALPHA, snapshot.candidateBackgroundAlpha)
            .putFloat(KEY_CANDIDATE_BACKGROUND_CORNER, snapshot.candidateBackgroundCorner)
            .putInt(
                KEY_CANDIDATE_BACKGROUND_LEFT_MARGIN_DP,
                snapshot.candidateBackgroundLeftMarginDp
            )
            .putInt(
                KEY_CANDIDATE_PINYIN_LEFT_MARGIN_DP,
                snapshot.candidatePinyinLeftMarginDp
            )
            .putInt(KEY_TOOLBAR_ICON_BG_OPACITY, snapshot.toolbarIconBgOpacity)
            .putBoolean(KEY_ICON_EDGE_LIGHT_ENABLED, snapshot.iconEdgeLightEnabled)
            .putBoolean(KEY_KEY_EDGE_LIGHT_ENABLED, snapshot.keyEdgeLightEnabled)
            .putBoolean(KEY_NATIVE_EDGE_LIGHT_ENABLED, snapshot.nativeEdgeLightEnabled)
            .putInt(KEY_EDGE_LIGHT_WIDTH, snapshot.edgeLightWidth)
            .putInt(KEY_NATIVE_EDGE_LIGHT_WIDTH, snapshot.nativeEdgeLightWidth)
            .putInt(KEY_EDGE_LIGHT_ANGLE, snapshot.edgeLightAngle)
            .putInt(KEY_GLOW_INTENSITY, snapshot.glowIntensity)
            .putBoolean(KEY_SYSTEM_MATERIAL_ENABLED, snapshot.systemMaterialEnabled)
            .putBoolean(KEY_HYPER_MATERIAL_ENABLED, snapshot.hyperMaterialEnabled)
            .putBoolean(KEY_DISABLE_HOT_UPDATE, snapshot.disableHotUpdate)
            .putBoolean(KEY_SHOW_CROSS_DEVICE_CLIPBOARD, snapshot.showCrossDeviceClipboard)
            .putBoolean(KEY_REMOVE_CLIPBOARD_RETENTION_LIMIT, snapshot.removeClipboardRetentionLimit)
            .putBoolean(KEY_REMOVE_CLIPBOARD_TEXT_LIMIT, snapshot.removeClipboardTextLimit)
            .putBoolean(KEY_CLIPBOARD_SEARCH, snapshot.clipboardSearchEnabled)
            .putBoolean(KEY_CLIPBOARD_SEARCH_CLEAR_ON_BACK, snapshot.clipboardSearchClearOnBack)
            .putBoolean(KEY_CLIPBOARD_IMAGE_ADJUST_RATIO, snapshot.clipboardImageAdjustRatio)
            .putBoolean(KEY_CLIPBOARD_IMAGE_CROP, snapshot.clipboardImageCrop)
            .putBoolean(KEY_CLIPBOARD_IMAGE_UNIFORM_ROW_HEIGHT, snapshot.clipboardImageUniformRowHeight)
            .putInt(KEY_CLIPBOARD_IMAGE_MAX_COUNT, snapshot.clipboardImageMaxCount)
            .putInt(KEY_CLIPBOARD_IMAGE_MAX_SIZE_MB, snapshot.clipboardImageMaxSizeMb)
            .putBoolean(KEY_QWERTY_GESTURE_ENABLED, snapshot.qwertyGestureEnabled)
            .putBoolean(KEY_T9_GESTURE_ENABLED, snapshot.t9GestureEnabled)
            .putInt(KEY_GESTURE_THRESHOLD, snapshot.gestureThreshold)
            .putInt(KEY_T9_GESTURE_THRESHOLD, snapshot.t9GestureThreshold)
            .putBoolean(KEY_GESTURE_VIBRATION, snapshot.gestureVibration)
            .putBoolean(KEY_T9_GESTURE_VIBRATION, snapshot.t9GestureVibration)
            .putString(KEY_GESTURE_BINDINGS_JSON, snapshot.gestureBindingsJson)
            .putBoolean(KEY_SHOW_GESTURE_KEY_LABELS, snapshot.showGestureKeyLabels)
            .putInt(KEY_GESTURE_LABEL_TEXT_SIZE_SP, snapshot.gestureLabelTextSizeSp)
            .putInt(KEY_GESTURE_LABEL_ALPHA, snapshot.gestureLabelAlpha)
            .putInt(KEY_GESTURE_LABEL_POSITION, snapshot.gestureLabelPosition)
            .putInt(KEY_GESTURE_LABEL_MARGIN_TOP_DP, snapshot.gestureLabelMarginTopDp)
            .putInt(KEY_GESTURE_LABEL_MARGIN_BOTTOM_DP, snapshot.gestureLabelMarginBottomDp)
            .putInt(KEY_GESTURE_LABEL_MARGIN_LEFT_DP, snapshot.gestureLabelMarginLeftDp)
            .putInt(KEY_GESTURE_LABEL_MARGIN_RIGHT_DP, snapshot.gestureLabelMarginRightDp)
            .putBoolean(KEY_GESTURE_LABEL_MIDLINE_MIGRATED, true)
            .putBoolean(KEY_LOGO_ENABLED, snapshot.logoEnabled)
            .putBoolean(KEY_LOGO_SHOW_ENABLED, snapshot.logoShowEnabled)
            .putString(KEY_LOGO_COLOR_MODE, snapshot.logoColorMode)
            .putInt(KEY_LOGO_CUSTOM_COLOR, snapshot.logoCustomColor)
            .putBoolean(KEY_LOGO_IMAGE_ENABLED, snapshot.logoImageEnabled)
            .putString(KEY_LOGO_IMAGE_TYPE, snapshot.logoImageType)
            .putBoolean(KEY_LOGO_SVG_RECOLOR_ENABLED, snapshot.logoSvgRecolorEnabled)
            .putString(KEY_LOGO_IMAGE_PNG_BASE64, snapshot.logoImagePngBase64)
            .putString(KEY_LOGO_IMAGE_SVG_TEXT, snapshot.logoImageSvgText)
            .putString(KEY_LOGO_IMAGE_NAME, snapshot.logoImageName)
            .putLong(KEY_LOGO_IMAGE_UPDATED_AT, snapshot.logoImageUpdatedAt)
            .putInt(KEY_FONT_MODE, snapshot.fontMode)
            .putBoolean(KEY_KEY_OPACITY_MIGRATED, true)
            .remove(KEY_KEY_OPACITY)
        fun writeFloatParameters(key: String, values: List<Float>?, maxCount: Int) {
            editor.remove("${key}_count")
            repeat(maxCount) { editor.remove("${key}_$it") }
            values?.let {
                editor.putInt("${key}_count", it.size)
                it.forEachIndexed { index, value -> editor.putFloat("${key}_$index", value) }
            }
        }
        writeFloatParameters(KEY_GLASS_PARAMS, snapshot.glassOverrides.glass, 42)
        writeFloatParameters(KEY_GLASS_BLOOM, snapshot.glassOverrides.bloom, 16)
        editor.remove(KEY_GLASS_BLUR_SMALL).remove(KEY_GLASS_BLUR_LARGE).remove(KEY_GLASS_MATERIAL_TYPE)
        snapshot.glassOverrides.blurRadii?.let {
            editor.putInt(KEY_GLASS_BLUR_SMALL, it[0]).putInt(KEY_GLASS_BLUR_LARGE, it[1])
        }
        snapshot.glassOverrides.materialType?.let { editor.putInt(KEY_GLASS_MATERIAL_TYPE, it) }
        WeTypeAppearanceColorGroups.groups.forEach { group ->
            editor.putInt(
                "$KEY_APPEARANCE_COLOR_PREFIX${group.id}",
                snapshot.appearanceColors[group.id] ?: group.defaultColor
            )
        }
        WeTypeAppearanceColorGroups.obsoleteGroupIds.forEach { groupId ->
            editor.remove("$KEY_APPEARANCE_COLOR_PREFIX$groupId")
        }
        configureEditor(editor)
        return editor.commit()
    }

    /**
     * 把宿主快照镜像给模块 App（远端偏好），**尽力而为，不等回执**。
     *
     * 这个镜像只服务于模块 App 自己的设置界面；`:hld` 读的是宿主本地那份文件，
     * 所以送不到不影响设置生效，更不该影响保存结果。
     *
     * 历史实现会注册一个一次性 `BroadcastReceiver` 等 ACK，等满 5 秒就判失败。那段逻辑
     * 有两个致命问题：
     *
     * 1. **把镜像失败谎报成保存失败**。本地早已写盘成功，用户却看到「设置保存失败」。
     * 2. **超时是常态而非异常**。ColorOS 的自启动管理会直接掐掉唤醒模块 App 的广播
     *    （`OplusAppStartupManager: prevent start .../ModuleBridgeReceiver`），于是每次
     *    保存都白等 5 秒。而 ACK 广播本身还要求接收方拥有唤醒宿主进程的权限 ——
     *    宿主是输入法，常年后台，回执经常在路上就被系统丢掉。
     *
     * 保留的是 [moduleBridgePendingIntent] 这条路：`PendingIntent.send()` 以模块 App 的
     * 身份发出广播，接收方看到的 sender 就是模块 App，不依赖发送方 uid 的"共享身份"
     * 豁免。这条广播已不含回执信息，`ModuleBridgeReceiver` 也不再回执。
     *
     * @return 是否应该留下待补发标记（true = 这次没送出去，下次启动再试）。
     */
    private fun sendSnapshotToModule(context: Context, snapshot: Snapshot, revision: Long): Boolean {
        if (context.packageName != WETYPE_PACKAGE_NAME) return false
        val appContext = context.applicationContext ?: context
        val intent = ModuleBridgeContract.explicitBridgeIntent()
            .putExtra(ModuleBridgeContract.EXTRA_MESSAGE_TYPE, ModuleBridgeContract.MESSAGE_SAVE_SETTINGS)
            .putExtra(ModuleBridgeContract.EXTRA_SETTINGS, snapshot.toBundle())
            .putExtra(ModuleBridgeContract.EXTRA_REVISION, revision)
        val sent = moduleBridgePendingIntent?.let { pendingIntent ->
            runCatching { pendingIntent.send(appContext, 0, intent) }
                .onFailure {
                    // PendingIntent 可能因为模块 App 被覆盖安装/卸载而失效（CANCEL_CURRENT），
                    // 丢掉它，下次回退到直接广播。
                    moduleBridgePendingIntent = null
                }
                .isSuccess
        } ?: false
        val delivered = sent || ModuleBridgeContract.sendWithIdentity(appContext, intent)
        if (!delivered) {
            AndroidLog.w(
                TAG,
                "Settings mirror to module app was not delivered (host snapshot is still authoritative)"
            )
        }
        return delivered
    }

    private fun nextHostRevision(preferences: SharedPreferences): Long {
        val previous = preferences.getLong(KEY_HOST_SYNC_REVISION, 0L)
        return maxOf(System.currentTimeMillis(), previous + 1L)
    }

    private fun Snapshot.toBundle(): Bundle = Bundle().apply {
        putInt(KEY_LIGHT_COLOR, lightColor)
        putInt(KEY_DARK_COLOR, darkColor)
        putInt(KEY_BLUR_RADIUS, blurRadius)
        putInt(KEY_CORNER_RADIUS, cornerRadius)
        putInt(KEY_BOTTOM_CORNER_RADIUS, bottomCornerRadius)
        putInt(KEY_KEY_CORNER_RADIUS, keyCornerRadius)
        putBoolean(KEY_EDGE_HIGHLIGHT_ENABLED, edgeHighlightEnabled)
        putInt(KEY_EDGE_HIGHLIGHT_INTENSITY, edgeHighlightIntensity)
        putInt(KEY_CANDIDATE_BACKGROUND_ALPHA, candidateBackgroundAlpha)
        putFloat(KEY_CANDIDATE_BACKGROUND_CORNER, candidateBackgroundCorner)
        putInt(KEY_CANDIDATE_BACKGROUND_LEFT_MARGIN_DP, candidateBackgroundLeftMarginDp)
        putInt(KEY_CANDIDATE_PINYIN_LEFT_MARGIN_DP, candidatePinyinLeftMarginDp)
        putInt(KEY_TOOLBAR_ICON_BG_OPACITY, toolbarIconBgOpacity)
        putBoolean(KEY_ICON_EDGE_LIGHT_ENABLED, iconEdgeLightEnabled)
        putBoolean(KEY_KEY_EDGE_LIGHT_ENABLED, keyEdgeLightEnabled)
        putBoolean(KEY_NATIVE_EDGE_LIGHT_ENABLED, nativeEdgeLightEnabled)
        putInt(KEY_EDGE_LIGHT_WIDTH, edgeLightWidth)
        putInt(KEY_COLOROS_LIGHT_ANGLE, colorOsLightAngle)
        putInt(KEY_NATIVE_EDGE_LIGHT_WIDTH, nativeEdgeLightWidth)
        putInt(KEY_EDGE_LIGHT_ANGLE, edgeLightAngle)
        putInt(KEY_GLOW_INTENSITY, glowIntensity)
        putBoolean(KEY_DISABLE_HOT_UPDATE, disableHotUpdate)
        putBoolean(KEY_SHOW_CROSS_DEVICE_CLIPBOARD, showCrossDeviceClipboard)
        putBoolean(KEY_REMOVE_CLIPBOARD_RETENTION_LIMIT, removeClipboardRetentionLimit)
        putBoolean(KEY_REMOVE_CLIPBOARD_TEXT_LIMIT, removeClipboardTextLimit)
        putBoolean(KEY_CLIPBOARD_SEARCH, clipboardSearchEnabled)
        putBoolean(KEY_CLIPBOARD_SEARCH_CLEAR_ON_BACK, clipboardSearchClearOnBack)
        putBoolean(KEY_CLIPBOARD_IMAGE_ADJUST_RATIO, clipboardImageAdjustRatio)
        putBoolean(KEY_CLIPBOARD_IMAGE_CROP, clipboardImageCrop)
        putBoolean(KEY_CLIPBOARD_IMAGE_UNIFORM_ROW_HEIGHT, clipboardImageUniformRowHeight)
        putInt(KEY_CLIPBOARD_IMAGE_MAX_COUNT, clipboardImageMaxCount)
        putInt(KEY_CLIPBOARD_IMAGE_MAX_SIZE_MB, clipboardImageMaxSizeMb)
        putBoolean(KEY_QWERTY_GESTURE_ENABLED, qwertyGestureEnabled)
        putBoolean(KEY_T9_GESTURE_ENABLED, t9GestureEnabled)
        putInt(KEY_GESTURE_THRESHOLD, gestureThreshold)
        putInt(KEY_T9_GESTURE_THRESHOLD, t9GestureThreshold)
        putBoolean(KEY_GESTURE_VIBRATION, gestureVibration)
        putBoolean(KEY_T9_GESTURE_VIBRATION, t9GestureVibration)
        putString(KEY_GESTURE_BINDINGS_JSON, gestureBindingsJson)
        putBoolean(KEY_SHOW_GESTURE_KEY_LABELS, showGestureKeyLabels)
        putInt(KEY_GESTURE_LABEL_TEXT_SIZE_SP, gestureLabelTextSizeSp)
        putInt(KEY_GESTURE_LABEL_ALPHA, gestureLabelAlpha)
        putInt(KEY_GESTURE_LABEL_POSITION, gestureLabelPosition)
        putInt(KEY_GESTURE_LABEL_MARGIN_TOP_DP, gestureLabelMarginTopDp)
        putInt(KEY_GESTURE_LABEL_MARGIN_BOTTOM_DP, gestureLabelMarginBottomDp)
        putInt(KEY_GESTURE_LABEL_MARGIN_LEFT_DP, gestureLabelMarginLeftDp)
        putInt(KEY_GESTURE_LABEL_MARGIN_RIGHT_DP, gestureLabelMarginRightDp)
        putBoolean(KEY_GESTURE_LABEL_MIDLINE_MIGRATED, true)
        putBoolean(KEY_LOGO_ENABLED, logoEnabled)
        putBoolean(KEY_LOGO_SHOW_ENABLED, logoShowEnabled)
        putString(KEY_LOGO_COLOR_MODE, logoColorMode)
        putInt(KEY_LOGO_CUSTOM_COLOR, logoCustomColor)
        putBoolean(KEY_LOGO_IMAGE_ENABLED, logoImageEnabled)
        putString(KEY_LOGO_IMAGE_TYPE, logoImageType)
        putBoolean(KEY_LOGO_SVG_RECOLOR_ENABLED, logoSvgRecolorEnabled)
        putString(KEY_LOGO_IMAGE_PNG_BASE64, logoImagePngBase64)
        putString(KEY_LOGO_IMAGE_SVG_TEXT, logoImageSvgText)
        putString(KEY_LOGO_IMAGE_NAME, logoImageName)
        putLong(KEY_LOGO_IMAGE_UPDATED_AT, logoImageUpdatedAt)
        putInt(KEY_FONT_MODE, fontMode)
        putBoolean(KEY_SYSTEM_MATERIAL_ENABLED, systemMaterialEnabled)
        putBoolean(KEY_HYPER_MATERIAL_ENABLED, hyperMaterialEnabled)
        glassOverrides.glass?.let { putFloatArray(KEY_GLASS_PARAMS, it.toFloatArray()) }
        glassOverrides.bloom?.let { putFloatArray(KEY_GLASS_BLOOM, it.toFloatArray()) }
        glassOverrides.blurRadii?.let {
            putInt(KEY_GLASS_BLUR_SMALL, it[0])
            putInt(KEY_GLASS_BLUR_LARGE, it[1])
        }
        glassOverrides.materialType?.let { putInt(KEY_GLASS_MATERIAL_TYPE, it) }
        putBundle(
            EXTRA_APPEARANCE_COLORS,
            Bundle().apply {
                appearanceColors.forEach { (groupId, color) -> putInt(groupId, color) }
            }
        )
    }

    /**
     * [fallbackAppearanceColors]：桥接 Bundle 里缺少某个颜色分组时用当前已持久化的值补齐，
     * 避免部分更新把未携带的分组重置回默认色（强调色默认即绿色 #23C891）。
     */
    private fun Bundle.toSnapshot(
        fallbackAppearanceColors: Map<String, Int> = emptyMap()
    ): Snapshot {
        val defaults = defaultSnapshot()
        val appearanceBundle = getBundle(EXTRA_APPEARANCE_COLORS)
        return Snapshot(
            lightColor = getInt(KEY_LIGHT_COLOR, defaults.lightColor),
            darkColor = getInt(KEY_DARK_COLOR, defaults.darkColor),
            blurRadius = getInt(KEY_BLUR_RADIUS, defaults.blurRadius).coerceIn(0, 100),
            cornerRadius = getInt(KEY_CORNER_RADIUS, defaults.cornerRadius)
                .coerceIn(0, MAX_CORNER_RADIUS),
            bottomCornerRadius = getInt(KEY_BOTTOM_CORNER_RADIUS, getInt(KEY_CORNER_RADIUS, defaults.bottomCornerRadius))
                .coerceIn(0, MAX_BOTTOM_CORNER_RADIUS),
            keyCornerRadius = getInt(KEY_KEY_CORNER_RADIUS, defaults.keyCornerRadius)
                .coerceIn(0, MAX_KEY_CORNER_RADIUS),
            edgeHighlightEnabled = getBoolean(
                KEY_EDGE_HIGHLIGHT_ENABLED,
                defaults.edgeHighlightEnabled
            ),
            edgeHighlightIntensity = getInt(
                KEY_EDGE_HIGHLIGHT_INTENSITY,
                defaults.edgeHighlightIntensity
            ).coerceIn(0, MAX_EDGE_HIGHLIGHT_INTENSITY),
            colorOsLightAngle = getInt(
                KEY_COLOROS_LIGHT_ANGLE,
                defaults.colorOsLightAngle
            ).coerceIn(0, MAX_COLOROS_LIGHT_ANGLE),
            candidateBackgroundAlpha = getInt(
                KEY_CANDIDATE_BACKGROUND_ALPHA,
                defaults.candidateBackgroundAlpha
            ).coerceIn(0, 255),
            candidateBackgroundCorner = getFloat(
                KEY_CANDIDATE_BACKGROUND_CORNER,
                defaults.candidateBackgroundCorner
            ).coerceIn(0f, MAX_CANDIDATE_BACKGROUND_CORNER.toFloat()),
            candidateBackgroundLeftMarginDp = getInt(
                KEY_CANDIDATE_BACKGROUND_LEFT_MARGIN_DP,
                defaults.candidateBackgroundLeftMarginDp
            ).coerceIn(0, 64),
            candidatePinyinLeftMarginDp = getInt(
                KEY_CANDIDATE_PINYIN_LEFT_MARGIN_DP,
                defaults.candidatePinyinLeftMarginDp
            ).coerceIn(0, 64),
            appearanceColors = WeTypeAppearanceColorGroups.groups.associate { group ->
                val bundled = if (appearanceBundle?.containsKey(group.id) == true) {
                    appearanceBundle.getInt(group.id, group.defaultColor)
                } else {
                    null
                }
                group.id to (bundled
                    ?: fallbackAppearanceColors[group.id]
                    ?: group.defaultColor)
            },
            toolbarIconBgOpacity = getInt(
                KEY_TOOLBAR_ICON_BG_OPACITY,
                defaults.toolbarIconBgOpacity
            ).coerceIn(0, 255),
            iconEdgeLightEnabled = getBoolean(
                KEY_ICON_EDGE_LIGHT_ENABLED,
                defaults.iconEdgeLightEnabled
            ),
            keyEdgeLightEnabled = getBoolean(
                KEY_KEY_EDGE_LIGHT_ENABLED,
                defaults.keyEdgeLightEnabled
            ),
            nativeEdgeLightEnabled = getBoolean(
                KEY_NATIVE_EDGE_LIGHT_ENABLED,
                defaults.nativeEdgeLightEnabled
            ),
            edgeLightWidth = getInt(
                KEY_EDGE_LIGHT_WIDTH,
                defaults.edgeLightWidth
            ).coerceIn(MIN_EDGE_LIGHT_WIDTH, MAX_EDGE_LIGHT_WIDTH),
            nativeEdgeLightWidth = getInt(
                KEY_NATIVE_EDGE_LIGHT_WIDTH,
                defaults.nativeEdgeLightWidth
            ).coerceIn(MIN_NATIVE_EDGE_LIGHT_WIDTH, MAX_NATIVE_EDGE_LIGHT_WIDTH),
            edgeLightAngle = getInt(
                KEY_EDGE_LIGHT_ANGLE,
                defaults.edgeLightAngle
            ).coerceIn(MIN_EDGE_LIGHT_ANGLE, MAX_EDGE_LIGHT_ANGLE),
            glowIntensity = getInt(
                KEY_GLOW_INTENSITY,
                defaults.glowIntensity
            ).coerceIn(MIN_GLOW_INTENSITY, MAX_GLOW_INTENSITY),
            disableHotUpdate = getBoolean(KEY_DISABLE_HOT_UPDATE, defaults.disableHotUpdate),
            showCrossDeviceClipboard = getBoolean(KEY_SHOW_CROSS_DEVICE_CLIPBOARD, defaults.showCrossDeviceClipboard),
            removeClipboardRetentionLimit = getBoolean(KEY_REMOVE_CLIPBOARD_RETENTION_LIMIT, defaults.removeClipboardRetentionLimit),
            removeClipboardTextLimit = getBoolean(KEY_REMOVE_CLIPBOARD_TEXT_LIMIT, defaults.removeClipboardTextLimit),
            clipboardSearchEnabled = getBoolean(KEY_CLIPBOARD_SEARCH, defaults.clipboardSearchEnabled),
            clipboardSearchClearOnBack = getBoolean(
                KEY_CLIPBOARD_SEARCH_CLEAR_ON_BACK,
                defaults.clipboardSearchClearOnBack
            ),
            clipboardImageAdjustRatio = getBoolean(
                KEY_CLIPBOARD_IMAGE_ADJUST_RATIO,
                defaults.clipboardImageAdjustRatio
            ),
            clipboardImageCrop = getBoolean(
                KEY_CLIPBOARD_IMAGE_CROP,
                defaults.clipboardImageCrop
            ),
            clipboardImageUniformRowHeight = getBoolean(
                KEY_CLIPBOARD_IMAGE_UNIFORM_ROW_HEIGHT,
                defaults.clipboardImageUniformRowHeight
            ),
            clipboardImageMaxCount = sanitizeClipboardImageMaxCount(
                getInt(KEY_CLIPBOARD_IMAGE_MAX_COUNT, defaults.clipboardImageMaxCount)
            ),
            clipboardImageMaxSizeMb = sanitizeClipboardImageMaxSizeMb(
                getInt(KEY_CLIPBOARD_IMAGE_MAX_SIZE_MB, defaults.clipboardImageMaxSizeMb)
            ),
            qwertyGestureEnabled = getBoolean(KEY_QWERTY_GESTURE_ENABLED, defaults.qwertyGestureEnabled),
            t9GestureEnabled = getBoolean(KEY_T9_GESTURE_ENABLED, defaults.t9GestureEnabled),
            gestureThreshold = getInt(KEY_GESTURE_THRESHOLD, defaults.gestureThreshold).coerceIn(10, 48),
            t9GestureThreshold = getInt(KEY_T9_GESTURE_THRESHOLD, defaults.t9GestureThreshold).coerceIn(10, 48),
            gestureVibration = getBoolean(KEY_GESTURE_VIBRATION, defaults.gestureVibration),
            t9GestureVibration = getBoolean(KEY_T9_GESTURE_VIBRATION, defaults.t9GestureVibration),
            gestureBindingsJson = getString(KEY_GESTURE_BINDINGS_JSON) ?: defaults.gestureBindingsJson,
            showGestureKeyLabels = getBoolean(KEY_SHOW_GESTURE_KEY_LABELS, defaults.showGestureKeyLabels),
            gestureLabelTextSizeSp = getInt(KEY_GESTURE_LABEL_TEXT_SIZE_SP, defaults.gestureLabelTextSizeSp)
                .coerceIn(6, 16),
            gestureLabelAlpha = getInt(KEY_GESTURE_LABEL_ALPHA, defaults.gestureLabelAlpha)
                .coerceIn(0, 255),
            gestureLabelPosition = normalizeGestureLabelPosition(
                getInt(KEY_GESTURE_LABEL_POSITION, defaults.gestureLabelPosition)
            ),
            gestureLabelMarginTopDp = getInt(KEY_GESTURE_LABEL_MARGIN_TOP_DP, defaults.gestureLabelMarginTopDp)
                .coerceIn(GESTURE_LABEL_MARGIN_MIN_DP, GESTURE_LABEL_MARGIN_MAX_DP),
            gestureLabelMarginBottomDp = getInt(KEY_GESTURE_LABEL_MARGIN_BOTTOM_DP, defaults.gestureLabelMarginBottomDp)
                .coerceIn(GESTURE_LABEL_MARGIN_MIN_DP, GESTURE_LABEL_MARGIN_MAX_DP),
            gestureLabelMarginLeftDp = getInt(KEY_GESTURE_LABEL_MARGIN_LEFT_DP, defaults.gestureLabelMarginLeftDp)
                .coerceIn(GESTURE_LABEL_MARGIN_MIN_DP, GESTURE_LABEL_MARGIN_MAX_DP),
            gestureLabelMarginRightDp = getInt(KEY_GESTURE_LABEL_MARGIN_RIGHT_DP, defaults.gestureLabelMarginRightDp)
                .coerceIn(GESTURE_LABEL_MARGIN_MIN_DP, GESTURE_LABEL_MARGIN_MAX_DP),
            logoEnabled = getBoolean(KEY_LOGO_ENABLED, defaults.logoEnabled),
            logoShowEnabled = getBoolean(KEY_LOGO_SHOW_ENABLED, defaults.logoShowEnabled),
            logoColorMode = normalizeLogoColorMode(getString(KEY_LOGO_COLOR_MODE) ?: defaults.logoColorMode),
            logoCustomColor = getInt(KEY_LOGO_CUSTOM_COLOR, defaults.logoCustomColor),
            logoImageEnabled = getBoolean(KEY_LOGO_IMAGE_ENABLED, defaults.logoImageEnabled),
            logoImageType = normalizeLogoImageType(getString(KEY_LOGO_IMAGE_TYPE) ?: defaults.logoImageType),
            logoSvgRecolorEnabled = getBoolean(KEY_LOGO_SVG_RECOLOR_ENABLED, defaults.logoSvgRecolorEnabled),
            logoImagePngBase64 = getString(KEY_LOGO_IMAGE_PNG_BASE64) ?: defaults.logoImagePngBase64,
            logoImageSvgText = getString(KEY_LOGO_IMAGE_SVG_TEXT) ?: defaults.logoImageSvgText,
            logoImageName = getString(KEY_LOGO_IMAGE_NAME) ?: defaults.logoImageName,
            logoImageUpdatedAt = getLong(KEY_LOGO_IMAGE_UPDATED_AT, defaults.logoImageUpdatedAt).coerceAtLeast(0L),
            fontMode = getInt(KEY_FONT_MODE, defaults.fontMode)
                .coerceIn(FONT_MODE_OFFICIAL, FONT_MODE_SYSTEM),
            systemMaterialEnabled = getBoolean(KEY_SYSTEM_MATERIAL_ENABLED, defaults.systemMaterialEnabled),
            hyperMaterialEnabled = getBoolean(KEY_HYPER_MATERIAL_ENABLED, defaults.hyperMaterialEnabled),
            glassOverrides = GlassMaterialOverrides(
                glass = getFloatArray(KEY_GLASS_PARAMS)?.toList(),
                blurRadii = if (containsKey(KEY_GLASS_BLUR_SMALL)) {
                    listOf(
                        getInt(KEY_GLASS_BLUR_SMALL, 0),
                        getInt(KEY_GLASS_BLUR_LARGE, 0)
                    )
                } else null,
                bloom = getFloatArray(KEY_GLASS_BLOOM)?.toList(),
                materialType = if (containsKey(KEY_GLASS_MATERIAL_TYPE)) {
                    getInt(KEY_GLASS_MATERIAL_TYPE, 0)
                } else null
            )
        )
    }

    /** 标签位置归一化：只认顶部/底部（含已下线的旧"居中"=2），其余回到默认。 */
    private fun normalizeGestureLabelPosition(value: Int): Int =
        if (value == GESTURE_LABEL_POSITION_TOP || value == GESTURE_LABEL_POSITION_BOTTOM) {
            value
        } else {
            DEFAULT_GESTURE_LABEL_POSITION
        }

    private fun SharedPreferences.toSnapshot(): Snapshot {
        val shouldMigrateLegacyKeyOpacity = contains(KEY_KEY_OPACITY) &&
            !getBoolean(KEY_KEY_OPACITY_MIGRATED, false)
        val legacyKeyOpacity = if (shouldMigrateLegacyKeyOpacity) {
            getInt(KEY_KEY_OPACITY, 255).coerceIn(0, 255)
        } else {
            null
        }
        // 旧“居中”(2)已下线：只有顶部/底部合法，非法存量一律回到默认（底部）。
        val storedLabelPosition = getInt(KEY_GESTURE_LABEL_POSITION, DEFAULT_GESTURE_LABEL_POSITION)
        val migratedLabelPosition = normalizeGestureLabelPosition(storedLabelPosition)
        // 存量里的旧默认上下边距提升到中线默认值，见 LEGACY_DEFAULT_GESTURE_LABEL_MARGIN_*。
        val shouldMigrateLabelMargins = !getBoolean(KEY_GESTURE_LABEL_MIDLINE_MIGRATED, false) &&
            getInt(KEY_GESTURE_LABEL_MARGIN_TOP_DP, -1) == LEGACY_DEFAULT_GESTURE_LABEL_MARGIN_TOP_DP &&
            getInt(KEY_GESTURE_LABEL_MARGIN_BOTTOM_DP, -1) == LEGACY_DEFAULT_GESTURE_LABEL_MARGIN_BOTTOM_DP
        val migratedMarginTopDp = if (shouldMigrateLabelMargins) {
            DEFAULT_GESTURE_LABEL_MARGIN_TOP_DP
        } else {
            getInt(KEY_GESTURE_LABEL_MARGIN_TOP_DP, DEFAULT_GESTURE_LABEL_MARGIN_TOP_DP)
        }
        val migratedMarginBottomDp = if (shouldMigrateLabelMargins) {
            DEFAULT_GESTURE_LABEL_MARGIN_BOTTOM_DP
        } else {
            getInt(KEY_GESTURE_LABEL_MARGIN_BOTTOM_DP, DEFAULT_GESTURE_LABEL_MARGIN_BOTTOM_DP)
        }
        return Snapshot(
            lightColor = getInt(KEY_LIGHT_COLOR, DEFAULT_LIGHT_COLOR),
            darkColor = getInt(KEY_DARK_COLOR, DEFAULT_DARK_COLOR),
            blurRadius = getInt(KEY_BLUR_RADIUS, DEFAULT_BLUR_RADIUS),
            cornerRadius = getInt(KEY_CORNER_RADIUS, DEFAULT_CORNER_RADIUS)
                .coerceIn(0, MAX_CORNER_RADIUS),
            bottomCornerRadius = getInt(KEY_BOTTOM_CORNER_RADIUS, getInt(KEY_CORNER_RADIUS, DEFAULT_BOTTOM_CORNER_RADIUS))
                .coerceIn(0, MAX_BOTTOM_CORNER_RADIUS),
            keyCornerRadius = getInt(KEY_KEY_CORNER_RADIUS, DEFAULT_KEY_CORNER_RADIUS)
                .coerceIn(0, MAX_KEY_CORNER_RADIUS),
            edgeHighlightEnabled = getBoolean(
                KEY_EDGE_HIGHLIGHT_ENABLED,
                DEFAULT_EDGE_HIGHLIGHT_ENABLED
            ),
            edgeHighlightIntensity = getInt(
                KEY_EDGE_HIGHLIGHT_INTENSITY,
                DEFAULT_EDGE_HIGHLIGHT_INTENSITY
            ),
            colorOsLightAngle = getInt(
                KEY_COLOROS_LIGHT_ANGLE,
                DEFAULT_COLOROS_LIGHT_ANGLE
            ),
            candidateBackgroundAlpha = getInt(
                KEY_CANDIDATE_BACKGROUND_ALPHA,
                DEFAULT_CANDIDATE_BACKGROUND_ALPHA
            ),
            candidateBackgroundCorner = getFloat(
                KEY_CANDIDATE_BACKGROUND_CORNER,
                DEFAULT_CANDIDATE_BACKGROUND_CORNER
            ).coerceIn(0f, MAX_CANDIDATE_BACKGROUND_CORNER.toFloat()),
            candidateBackgroundLeftMarginDp = getInt(
                KEY_CANDIDATE_BACKGROUND_LEFT_MARGIN_DP,
                DEFAULT_CANDIDATE_BACKGROUND_LEFT_MARGIN_DP
            ).coerceIn(0, 64),
            candidatePinyinLeftMarginDp = getInt(
                KEY_CANDIDATE_PINYIN_LEFT_MARGIN_DP,
                DEFAULT_CANDIDATE_PINYIN_LEFT_MARGIN_DP
            ).coerceIn(0, 64),
            toolbarIconBgOpacity = getInt(KEY_TOOLBAR_ICON_BG_OPACITY, DEFAULT_TOOLBAR_ICON_BG_OPACITY).coerceIn(0, 255),
            iconEdgeLightEnabled = getBoolean(
                KEY_ICON_EDGE_LIGHT_ENABLED,
                DEFAULT_ICON_EDGE_LIGHT_ENABLED
            ),
            keyEdgeLightEnabled = getBoolean(
                KEY_KEY_EDGE_LIGHT_ENABLED,
                DEFAULT_KEY_EDGE_LIGHT_ENABLED
            ),
            nativeEdgeLightEnabled = getBoolean(
                KEY_NATIVE_EDGE_LIGHT_ENABLED,
                DEFAULT_NATIVE_EDGE_LIGHT_ENABLED
            ),
            edgeLightWidth = getInt(KEY_EDGE_LIGHT_WIDTH, DEFAULT_EDGE_LIGHT_WIDTH)
                .coerceIn(MIN_EDGE_LIGHT_WIDTH, MAX_EDGE_LIGHT_WIDTH),
            nativeEdgeLightWidth = getInt(KEY_NATIVE_EDGE_LIGHT_WIDTH, DEFAULT_NATIVE_EDGE_LIGHT_WIDTH)
                .coerceIn(MIN_NATIVE_EDGE_LIGHT_WIDTH, MAX_NATIVE_EDGE_LIGHT_WIDTH),
            edgeLightAngle = getInt(KEY_EDGE_LIGHT_ANGLE, DEFAULT_EDGE_LIGHT_ANGLE)
                .coerceIn(MIN_EDGE_LIGHT_ANGLE, MAX_EDGE_LIGHT_ANGLE),
            glowIntensity = getInt(KEY_GLOW_INTENSITY, DEFAULT_GLOW_INTENSITY)
                .coerceIn(MIN_GLOW_INTENSITY, MAX_GLOW_INTENSITY),
            appearanceColors = WeTypeAppearanceColorGroups.groups.associate { group ->
                val key = "$KEY_APPEARANCE_COLOR_PREFIX${group.id}"
                val fallbackColor = if (legacyKeyOpacity != null) {
                    legacyKeyColorDefaults[group.id] ?: group.defaultColor
                } else {
                    group.defaultColor
                }
                val color = getInt(key, fallbackColor)
                group.id to migrateLegacyKeyOpacity(group, color, legacyKeyOpacity)
            },
            disableHotUpdate = getBoolean(KEY_DISABLE_HOT_UPDATE, DEFAULT_DISABLE_HOT_UPDATE),
            showCrossDeviceClipboard = getBoolean(KEY_SHOW_CROSS_DEVICE_CLIPBOARD, DEFAULT_SHOW_CROSS_DEVICE_CLIPBOARD),
            removeClipboardRetentionLimit = getBoolean(KEY_REMOVE_CLIPBOARD_RETENTION_LIMIT, DEFAULT_REMOVE_CLIPBOARD_RETENTION_LIMIT),
            removeClipboardTextLimit = getBoolean(KEY_REMOVE_CLIPBOARD_TEXT_LIMIT, DEFAULT_REMOVE_CLIPBOARD_TEXT_LIMIT),
            clipboardSearchEnabled = getBoolean(KEY_CLIPBOARD_SEARCH, DEFAULT_CLIPBOARD_SEARCH_ENABLED),
            clipboardSearchClearOnBack = getBoolean(
                KEY_CLIPBOARD_SEARCH_CLEAR_ON_BACK,
                DEFAULT_CLIPBOARD_SEARCH_CLEAR_ON_BACK
            ),
            clipboardImageAdjustRatio = getBoolean(
                KEY_CLIPBOARD_IMAGE_ADJUST_RATIO,
                DEFAULT_CLIPBOARD_IMAGE_ADJUST_RATIO
            ),
            clipboardImageCrop = getBoolean(KEY_CLIPBOARD_IMAGE_CROP, DEFAULT_CLIPBOARD_IMAGE_CROP),
            clipboardImageUniformRowHeight = getBoolean(
                KEY_CLIPBOARD_IMAGE_UNIFORM_ROW_HEIGHT,
                DEFAULT_CLIPBOARD_IMAGE_UNIFORM_ROW_HEIGHT
            ),
            clipboardImageMaxCount = sanitizeClipboardImageMaxCount(
                getInt(KEY_CLIPBOARD_IMAGE_MAX_COUNT, DEFAULT_CLIPBOARD_IMAGE_MAX_COUNT)
            ),
            clipboardImageMaxSizeMb = sanitizeClipboardImageMaxSizeMb(
                getInt(KEY_CLIPBOARD_IMAGE_MAX_SIZE_MB, DEFAULT_CLIPBOARD_IMAGE_MAX_SIZE_MB)
            ),
            qwertyGestureEnabled = getBoolean(KEY_QWERTY_GESTURE_ENABLED, DEFAULT_QWERTY_GESTURE_ENABLED),
            t9GestureEnabled = getBoolean(KEY_T9_GESTURE_ENABLED, DEFAULT_T9_GESTURE_ENABLED),
            gestureThreshold = getInt(KEY_GESTURE_THRESHOLD, DEFAULT_GESTURE_THRESHOLD).coerceIn(10, 48),
            t9GestureThreshold = getInt(KEY_T9_GESTURE_THRESHOLD, DEFAULT_T9_GESTURE_THRESHOLD).coerceIn(10, 48),
            gestureVibration = getBoolean(KEY_GESTURE_VIBRATION, DEFAULT_GESTURE_VIBRATION),
            t9GestureVibration = getBoolean(KEY_T9_GESTURE_VIBRATION, DEFAULT_T9_GESTURE_VIBRATION),
            gestureBindingsJson = getString(KEY_GESTURE_BINDINGS_JSON, DEFAULT_GESTURE_BINDINGS_JSON) ?: DEFAULT_GESTURE_BINDINGS_JSON,
            showGestureKeyLabels = getBoolean(KEY_SHOW_GESTURE_KEY_LABELS, DEFAULT_SHOW_GESTURE_KEY_LABELS),
            gestureLabelTextSizeSp = getInt(KEY_GESTURE_LABEL_TEXT_SIZE_SP, DEFAULT_GESTURE_LABEL_TEXT_SIZE_SP)
                .coerceIn(6, 16),
            gestureLabelAlpha = getInt(KEY_GESTURE_LABEL_ALPHA, DEFAULT_GESTURE_LABEL_ALPHA)
                .coerceIn(0, 255),
            gestureLabelPosition = migratedLabelPosition,
            gestureLabelMarginTopDp = migratedMarginTopDp
                .coerceIn(GESTURE_LABEL_MARGIN_MIN_DP, GESTURE_LABEL_MARGIN_MAX_DP),
            gestureLabelMarginBottomDp = migratedMarginBottomDp
                .coerceIn(GESTURE_LABEL_MARGIN_MIN_DP, GESTURE_LABEL_MARGIN_MAX_DP),
            gestureLabelMarginLeftDp = getInt(KEY_GESTURE_LABEL_MARGIN_LEFT_DP, DEFAULT_GESTURE_LABEL_MARGIN_LEFT_DP)
                .coerceIn(GESTURE_LABEL_MARGIN_MIN_DP, GESTURE_LABEL_MARGIN_MAX_DP),
            gestureLabelMarginRightDp = getInt(KEY_GESTURE_LABEL_MARGIN_RIGHT_DP, DEFAULT_GESTURE_LABEL_MARGIN_RIGHT_DP)
                .coerceIn(GESTURE_LABEL_MARGIN_MIN_DP, GESTURE_LABEL_MARGIN_MAX_DP),
            logoEnabled = getBoolean(KEY_LOGO_ENABLED, DEFAULT_LOGO_ENABLED),
            logoShowEnabled = getBoolean(KEY_LOGO_SHOW_ENABLED, DEFAULT_LOGO_SHOW_ENABLED),
            logoColorMode = normalizeLogoColorMode(getString(KEY_LOGO_COLOR_MODE, DEFAULT_LOGO_COLOR_MODE)),
            logoCustomColor = getInt(KEY_LOGO_CUSTOM_COLOR, DEFAULT_LOGO_CUSTOM_COLOR),
            logoImageEnabled = getBoolean(KEY_LOGO_IMAGE_ENABLED, DEFAULT_LOGO_IMAGE_ENABLED),
            logoImageType = normalizeLogoImageType(getString(KEY_LOGO_IMAGE_TYPE, DEFAULT_LOGO_IMAGE_TYPE)),
            logoSvgRecolorEnabled = getBoolean(KEY_LOGO_SVG_RECOLOR_ENABLED, DEFAULT_LOGO_SVG_RECOLOR_ENABLED),
            logoImagePngBase64 = getString(KEY_LOGO_IMAGE_PNG_BASE64, DEFAULT_LOGO_IMAGE_PNG_BASE64)
                ?: DEFAULT_LOGO_IMAGE_PNG_BASE64,
            logoImageSvgText = getString(KEY_LOGO_IMAGE_SVG_TEXT, DEFAULT_LOGO_IMAGE_SVG_TEXT)
                ?: DEFAULT_LOGO_IMAGE_SVG_TEXT,
            logoImageName = getString(KEY_LOGO_IMAGE_NAME, DEFAULT_LOGO_IMAGE_NAME)
                ?: DEFAULT_LOGO_IMAGE_NAME,
            logoImageUpdatedAt = getLong(KEY_LOGO_IMAGE_UPDATED_AT, DEFAULT_LOGO_IMAGE_UPDATED_AT)
                .coerceAtLeast(0L),
            fontMode = getInt(KEY_FONT_MODE, DEFAULT_FONT_MODE)
                .coerceIn(FONT_MODE_OFFICIAL, FONT_MODE_SYSTEM),
            systemMaterialEnabled = getBoolean(KEY_SYSTEM_MATERIAL_ENABLED, DEFAULT_SYSTEM_MATERIAL_ENABLED),
            hyperMaterialEnabled = getBoolean(KEY_HYPER_MATERIAL_ENABLED, DEFAULT_HYPER_MATERIAL_ENABLED),
            glassOverrides = readGlassOverrides()
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun SharedPreferences.readGlassOverrides(): GlassMaterialOverrides =
        GlassMaterialOverrides.read(all.filterValues { it != null } as Map<String, Any>)

    private fun migrateLegacyKeyOpacity(
        group: WeTypeAppearanceColorGroup,
        color: Int,
        legacyKeyOpacity: Int?
    ): Int {
        if (!group.isKeyColorGroup || legacyKeyOpacity == null || Color.alpha(color) != 0xFF) {
            return color
        }
        return (legacyKeyOpacity shl 24) or (color and 0x00FFFFFF)
    }

    private fun SharedPreferences.toSnapshotOrNull(): Snapshot? {
        if (!containsAnyPersistedSetting()) return null
        return toSnapshot()
    }

    private fun defaultSnapshot(): Snapshot = Snapshot(
        lightColor = DEFAULT_LIGHT_COLOR,
        darkColor = DEFAULT_DARK_COLOR,
        blurRadius = DEFAULT_BLUR_RADIUS,
        cornerRadius = DEFAULT_CORNER_RADIUS,
        bottomCornerRadius = DEFAULT_BOTTOM_CORNER_RADIUS,
        keyCornerRadius = DEFAULT_KEY_CORNER_RADIUS,
        edgeHighlightEnabled = DEFAULT_EDGE_HIGHLIGHT_ENABLED,
        edgeHighlightIntensity = DEFAULT_EDGE_HIGHLIGHT_INTENSITY,
        colorOsLightAngle = DEFAULT_COLOROS_LIGHT_ANGLE,
        candidateBackgroundAlpha = DEFAULT_CANDIDATE_BACKGROUND_ALPHA,
        candidateBackgroundCorner = DEFAULT_CANDIDATE_BACKGROUND_CORNER,
        candidateBackgroundLeftMarginDp = DEFAULT_CANDIDATE_BACKGROUND_LEFT_MARGIN_DP,
        candidatePinyinLeftMarginDp = DEFAULT_CANDIDATE_PINYIN_LEFT_MARGIN_DP,
        toolbarIconBgOpacity = DEFAULT_TOOLBAR_ICON_BG_OPACITY,
        iconEdgeLightEnabled = DEFAULT_ICON_EDGE_LIGHT_ENABLED,
        keyEdgeLightEnabled = DEFAULT_KEY_EDGE_LIGHT_ENABLED,
        nativeEdgeLightEnabled = DEFAULT_NATIVE_EDGE_LIGHT_ENABLED,
        edgeLightWidth = DEFAULT_EDGE_LIGHT_WIDTH,
        nativeEdgeLightWidth = DEFAULT_NATIVE_EDGE_LIGHT_WIDTH,
        edgeLightAngle = DEFAULT_EDGE_LIGHT_ANGLE,
        glowIntensity = DEFAULT_GLOW_INTENSITY,
        appearanceColors = WeTypeAppearanceColorGroups.defaultColors(),
        disableHotUpdate = DEFAULT_DISABLE_HOT_UPDATE,
        showCrossDeviceClipboard = DEFAULT_SHOW_CROSS_DEVICE_CLIPBOARD,
        removeClipboardRetentionLimit = DEFAULT_REMOVE_CLIPBOARD_RETENTION_LIMIT,
        removeClipboardTextLimit = DEFAULT_REMOVE_CLIPBOARD_TEXT_LIMIT,
        clipboardSearchEnabled = DEFAULT_CLIPBOARD_SEARCH_ENABLED,
        clipboardSearchClearOnBack = DEFAULT_CLIPBOARD_SEARCH_CLEAR_ON_BACK,
        clipboardImageAdjustRatio = DEFAULT_CLIPBOARD_IMAGE_ADJUST_RATIO,
        clipboardImageCrop = DEFAULT_CLIPBOARD_IMAGE_CROP,
        clipboardImageUniformRowHeight = DEFAULT_CLIPBOARD_IMAGE_UNIFORM_ROW_HEIGHT,
        clipboardImageMaxCount = DEFAULT_CLIPBOARD_IMAGE_MAX_COUNT,
        clipboardImageMaxSizeMb = DEFAULT_CLIPBOARD_IMAGE_MAX_SIZE_MB,
        qwertyGestureEnabled = DEFAULT_QWERTY_GESTURE_ENABLED,
        t9GestureEnabled = DEFAULT_T9_GESTURE_ENABLED,
        gestureThreshold = DEFAULT_GESTURE_THRESHOLD,
        t9GestureThreshold = DEFAULT_T9_GESTURE_THRESHOLD,
        gestureVibration = DEFAULT_GESTURE_VIBRATION,
        t9GestureVibration = DEFAULT_T9_GESTURE_VIBRATION,
        gestureBindingsJson = DEFAULT_GESTURE_BINDINGS_JSON,
        showGestureKeyLabels = DEFAULT_SHOW_GESTURE_KEY_LABELS,
        gestureLabelTextSizeSp = DEFAULT_GESTURE_LABEL_TEXT_SIZE_SP,
        gestureLabelAlpha = DEFAULT_GESTURE_LABEL_ALPHA,
        gestureLabelPosition = DEFAULT_GESTURE_LABEL_POSITION,
        gestureLabelMarginTopDp = DEFAULT_GESTURE_LABEL_MARGIN_TOP_DP,
        gestureLabelMarginBottomDp = DEFAULT_GESTURE_LABEL_MARGIN_BOTTOM_DP,
        gestureLabelMarginLeftDp = DEFAULT_GESTURE_LABEL_MARGIN_LEFT_DP,
        gestureLabelMarginRightDp = DEFAULT_GESTURE_LABEL_MARGIN_RIGHT_DP,
        logoEnabled = DEFAULT_LOGO_ENABLED,
        logoShowEnabled = DEFAULT_LOGO_SHOW_ENABLED,
        logoColorMode = DEFAULT_LOGO_COLOR_MODE,
        logoCustomColor = DEFAULT_LOGO_CUSTOM_COLOR,
        logoImageEnabled = DEFAULT_LOGO_IMAGE_ENABLED,
        logoImageType = DEFAULT_LOGO_IMAGE_TYPE,
        logoSvgRecolorEnabled = DEFAULT_LOGO_SVG_RECOLOR_ENABLED,
        logoImagePngBase64 = DEFAULT_LOGO_IMAGE_PNG_BASE64,
        logoImageSvgText = DEFAULT_LOGO_IMAGE_SVG_TEXT,
        logoImageName = DEFAULT_LOGO_IMAGE_NAME,
        logoImageUpdatedAt = DEFAULT_LOGO_IMAGE_UPDATED_AT,
        fontMode = DEFAULT_FONT_MODE
    )

    private fun SharedPreferences.containsAnyPersistedSetting(): Boolean {
        if (contains(KEY_LIGHT_COLOR) ||
            contains(KEY_DARK_COLOR) ||
            contains(KEY_BLUR_RADIUS) ||
            contains(KEY_CORNER_RADIUS) ||
            contains(KEY_BOTTOM_CORNER_RADIUS) ||
            contains(KEY_KEY_CORNER_RADIUS) ||
            contains(KEY_EDGE_HIGHLIGHT_ENABLED) ||
            contains(KEY_EDGE_HIGHLIGHT_INTENSITY) ||
            contains(KEY_COLOROS_LIGHT_ANGLE) ||
            contains(KEY_KEY_OPACITY) ||
            contains(KEY_CANDIDATE_BACKGROUND_ALPHA) ||
            contains(KEY_CANDIDATE_BACKGROUND_CORNER) ||
            contains(KEY_CANDIDATE_BACKGROUND_LEFT_MARGIN_DP) ||
            contains(KEY_CANDIDATE_PINYIN_LEFT_MARGIN_DP) ||
            contains(KEY_TOOLBAR_ICON_BG_OPACITY) ||
            contains(KEY_ICON_EDGE_LIGHT_ENABLED) ||
            contains(KEY_KEY_EDGE_LIGHT_ENABLED) ||
            contains(KEY_NATIVE_EDGE_LIGHT_ENABLED) ||
            contains(KEY_EDGE_LIGHT_WIDTH) ||
            contains(KEY_NATIVE_EDGE_LIGHT_WIDTH) ||
            contains(KEY_EDGE_LIGHT_ANGLE) ||
            contains(KEY_GLOW_INTENSITY) ||
            contains(KEY_DISABLE_HOT_UPDATE) ||
            contains(KEY_SHOW_CROSS_DEVICE_CLIPBOARD) ||
            contains(KEY_REMOVE_CLIPBOARD_RETENTION_LIMIT) ||
            contains(KEY_REMOVE_CLIPBOARD_TEXT_LIMIT) ||
            contains(KEY_CLIPBOARD_SEARCH) ||
            contains(KEY_CLIPBOARD_SEARCH_CLEAR_ON_BACK) ||
            contains(KEY_CLIPBOARD_IMAGE_MAX_COUNT) ||
            contains(KEY_CLIPBOARD_IMAGE_MAX_SIZE_MB) ||
            contains(KEY_QWERTY_GESTURE_ENABLED) ||
            contains(KEY_T9_GESTURE_ENABLED) ||
            contains(KEY_GESTURE_THRESHOLD) ||
            contains(KEY_T9_GESTURE_THRESHOLD) ||
            contains(KEY_GESTURE_VIBRATION) ||
            contains(KEY_T9_GESTURE_VIBRATION) ||
            contains(KEY_GESTURE_BINDINGS_JSON) ||
            contains(KEY_SHOW_GESTURE_KEY_LABELS) ||
            contains(KEY_GESTURE_LABEL_TEXT_SIZE_SP) ||
            contains(KEY_GESTURE_LABEL_ALPHA) ||
            contains(KEY_GESTURE_LABEL_POSITION) ||
            contains(KEY_GESTURE_LABEL_MARGIN_TOP_DP) ||
            contains(KEY_GESTURE_LABEL_MARGIN_BOTTOM_DP) ||
            contains(KEY_GESTURE_LABEL_MARGIN_LEFT_DP) ||
            contains(KEY_GESTURE_LABEL_MARGIN_RIGHT_DP) ||
            contains(KEY_LOGO_ENABLED) ||
            contains(KEY_LOGO_SHOW_ENABLED) ||
            contains(KEY_LOGO_COLOR_MODE) ||
            contains(KEY_LOGO_CUSTOM_COLOR) ||
            contains(KEY_LOGO_IMAGE_ENABLED) ||
            contains(KEY_LOGO_IMAGE_TYPE) ||
            contains(KEY_LOGO_SVG_RECOLOR_ENABLED) ||
            contains(KEY_LOGO_IMAGE_PNG_BASE64) ||
            contains(KEY_LOGO_IMAGE_SVG_TEXT) ||
            contains(KEY_LOGO_IMAGE_NAME) ||
            contains(KEY_LOGO_IMAGE_UPDATED_AT) ||
            contains(KEY_FONT_MODE) ||
            contains(KEY_SYSTEM_MATERIAL_ENABLED) ||
            contains(KEY_HYPER_MATERIAL_ENABLED) ||
            contains("${KEY_GLASS_PARAMS}_count") ||
            contains(KEY_GLASS_BLUR_SMALL) ||
            contains("${KEY_GLASS_BLOOM}_count") ||
            contains(KEY_GLASS_MATERIAL_TYPE)
        ) {
            return true
        }
        return WeTypeAppearanceColorGroups.groups.any { group ->
            contains("$KEY_APPEARANCE_COLOR_PREFIX${group.id}")
        }
    }

}
