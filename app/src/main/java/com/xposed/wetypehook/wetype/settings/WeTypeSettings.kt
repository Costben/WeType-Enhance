package com.xposed.wetypehook.wetype.settings

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log as AndroidLog
import com.xposed.wetypehook.ModuleBridgeContract
import java.util.concurrent.atomic.AtomicBoolean
import java.util.UUID

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
    private const val KEY_BLUR_RADIUS = "blur_radius"
    private const val KEY_CORNER_RADIUS = "corner_radius"
    private const val KEY_KEY_CORNER_RADIUS = "key_corner_radius"
    private const val KEY_EDGE_HIGHLIGHT_ENABLED = "edge_highlight_enabled"
    private const val KEY_EDGE_HIGHLIGHT_INTENSITY = "edge_highlight_intensity"
    private const val KEY_KEY_OPACITY = "key_opacity"
    private const val KEY_KEY_OPACITY_MIGRATED = "key_opacity_migrated"
    // Keep the original preference key so existing saved values still migrate cleanly.
    private const val KEY_CANDIDATE_BACKGROUND_ALPHA = "key_color_hook_alpha"
    private const val KEY_CANDIDATE_BACKGROUND_CORNER = "candidate_background_corner"
    private const val KEY_CANDIDATE_BACKGROUND_LEFT_MARGIN_DP =
        "candidate_background_left_margin_dp"
    private const val KEY_CANDIDATE_PINYIN_LEFT_MARGIN_DP = "candidate_pinyin_left_margin_dp"
    private const val KEY_APPEARANCE_COLOR_PREFIX = "appearance_color_"
    private const val KEY_DISABLE_HOT_UPDATE = "disable_hot_update"
    private const val KEY_TOOLBAR_ICON_BG_OPACITY = "toolbar_icon_bg_opacity"
    const val KEY_SHOW_CROSS_DEVICE_CLIPBOARD = "show_cross_device_clipboard"
    const val KEY_REMOVE_CLIPBOARD_RETENTION_LIMIT = "remove_clipboard_retention_limit"
    const val KEY_REMOVE_CLIPBOARD_TEXT_LIMIT = "remove_clipboard_text_limit"
    const val KEY_CLIPBOARD_SEARCH = "clipboard_search_enabled"
    const val KEY_CLIPBOARD_IMAGE_ADJUST_RATIO = "clipboard_image_adjust_ratio"
    const val KEY_CLIPBOARD_IMAGE_CROP = "clipboard_image_crop"
    const val KEY_CLIPBOARD_IMAGE_UNIFORM_ROW_HEIGHT = "clipboard_image_uniform_row_height"
    const val KEY_CLIPBOARD_IMAGE_MAX_COUNT = "clipboard_image_max_count"
    const val KEY_CLIPBOARD_IMAGE_MAX_SIZE_MB = "clipboard_image_max_size_mb"

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

    const val DEFAULT_SHOW_GESTURE_KEY_LABELS = true
    const val DEFAULT_GESTURE_LABEL_TEXT_SIZE_SP = 9
    const val DEFAULT_GESTURE_LABEL_ALPHA = 153
    const val DEFAULT_GESTURE_LABEL_POSITION = GESTURE_LABEL_POSITION_BOTTOM
    const val DEFAULT_GESTURE_LABEL_MARGIN_TOP_DP = 0
    const val DEFAULT_GESTURE_LABEL_MARGIN_BOTTOM_DP = 3
    const val DEFAULT_GESTURE_LABEL_MARGIN_LEFT_DP = 0
    const val DEFAULT_GESTURE_LABEL_MARGIN_RIGHT_DP = 0

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
    const val DEFAULT_KEY_CORNER_RADIUS = 10
    const val MAX_KEY_CORNER_RADIUS = 40
    const val DEFAULT_EDGE_HIGHLIGHT_ENABLED = true
    const val DEFAULT_EDGE_HIGHLIGHT_INTENSITY = 80
    const val DEFAULT_CANDIDATE_BACKGROUND_ALPHA = 150
    const val DEFAULT_CANDIDATE_BACKGROUND_CORNER = 60f
    const val MAX_CANDIDATE_BACKGROUND_CORNER = 60
    const val DEFAULT_CANDIDATE_BACKGROUND_LEFT_MARGIN_DP = 6
    const val DEFAULT_CANDIDATE_PINYIN_LEFT_MARGIN_DP = 16
    const val DEFAULT_TOOLBAR_ICON_BG_OPACITY = 150
    const val DEFAULT_DISABLE_HOT_UPDATE = true

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
        val keyCornerRadius: Int,
        val edgeHighlightEnabled: Boolean,
        val edgeHighlightIntensity: Int,
        val candidateBackgroundAlpha: Int,
        val candidateBackgroundCorner: Float,
        val candidateBackgroundLeftMarginDp: Int,
        val candidatePinyinLeftMarginDp: Int,
        val appearanceColors: Map<String, Int>,
        val toolbarIconBgOpacity: Int,
        val disableHotUpdate: Boolean,
        val showCrossDeviceClipboard: Boolean = DEFAULT_SHOW_CROSS_DEVICE_CLIPBOARD,
        val removeClipboardRetentionLimit: Boolean = DEFAULT_REMOVE_CLIPBOARD_RETENTION_LIMIT,
        val removeClipboardTextLimit: Boolean = DEFAULT_REMOVE_CLIPBOARD_TEXT_LIMIT,
        val clipboardSearchEnabled: Boolean = DEFAULT_CLIPBOARD_SEARCH_ENABLED,
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
        val fontMode: Int = DEFAULT_FONT_MODE
    )

    fun isShowCrossDeviceClipboard(context: Context): Boolean = readSnapshot(context).showCrossDeviceClipboard
    fun isRemoveClipboardRetentionLimit(context: Context): Boolean = readSnapshot(context).removeClipboardRetentionLimit
    fun isRemoveClipboardTextLimit(context: Context): Boolean = readSnapshot(context).removeClipboardTextLimit
    fun isClipboardSearchEnabled(context: Context): Boolean = readSnapshot(context).clipboardSearchEnabled
    fun isClipboardImageAdjustRatio(context: Context): Boolean = readSnapshot(context).clipboardImageAdjustRatio
    fun isClipboardImageCrop(context: Context): Boolean = readSnapshot(context).clipboardImageCrop
    fun isClipboardImageUniformRowHeight(context: Context): Boolean =
        readSnapshot(context).clipboardImageUniformRowHeight

    fun getClipboardImageMaxCount(context: Context): Int =
        readSnapshot(context).clipboardImageMaxCount

    fun getClipboardImageMaxSizeMb(context: Context): Int =
        readSnapshot(context).clipboardImageMaxSizeMb

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

    fun isLogoEnabledXposed(): Boolean = readSnapshotXposed().logoEnabled
    fun isLogoShowEnabledXposed(): Boolean = readSnapshotXposed().logoShowEnabled
    fun getLogoColorModeXposed(): String = readSnapshotXposed().logoColorMode
    fun getLogoCustomColorXposed(): Int = readSnapshotXposed().logoCustomColor

    fun getFontMode(context: Context): Int = readSnapshot(context).fontMode

    fun getFontModeXposed(): Int = readSnapshotXposed().fontMode

    fun getLightColor(context: Context): Int = readSnapshot(context).lightColor

    fun getDarkColor(context: Context): Int = readSnapshot(context).darkColor

    fun getBlurRadius(context: Context): Int = readSnapshot(context).blurRadius

    fun getCornerRadius(context: Context): Int = readSnapshot(context).cornerRadius

    fun getKeyCornerRadius(context: Context): Int = readSnapshot(context).keyCornerRadius

    fun isEdgeHighlightEnabled(context: Context): Boolean = readSnapshot(context).edgeHighlightEnabled

    fun getEdgeHighlightIntensity(context: Context): Int = readSnapshot(context).edgeHighlightIntensity

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

    fun bindModuleBridgePendingIntent(pendingIntent: PendingIntent?) {
        moduleBridgePendingIntent = pendingIntent
    }

    fun ensureHostSnapshot(context: Context) {
        val appContext = context.applicationContext ?: context
        val localPreferences = appPreferences(appContext)
        val localSnapshot = localPreferences.toSnapshotOrNull()
        if (appContext.packageName == MODULE_PACKAGE_NAME) {
            synchronizeRemotePreferences(appContext)
            return
        }
        hostContextRef = java.lang.ref.WeakReference(appContext)
        val remoteSnapshot = remotePreferences?.toSnapshotOrNull()
        val hostSyncPending = localPreferences.getBoolean(KEY_HOST_SYNC_PENDING, false)

        when {
            hostSyncPending && localSnapshot != null -> {
                cachedXposedSnapshot = localSnapshot
                val revision = localPreferences.getLong(KEY_HOST_SYNC_REVISION, 0L)
                sendSnapshotToModule(appContext, localSnapshot, revision) { accepted ->
                    acknowledgeHostSnapshot(appContext, revision, accepted)
                }
            }

            remoteSnapshot != null -> {
                writeSnapshot(localPreferences, remoteSnapshot)
                cachedXposedSnapshot = remoteSnapshot
            }

            localSnapshot != null -> {
                cachedXposedSnapshot = localSnapshot
                val revision = nextHostRevision(localPreferences)
                val staged = localPreferences.edit()
                    .putBoolean(KEY_HOST_SYNC_PENDING, true)
                    .putLong(KEY_HOST_SYNC_REVISION, revision)
                    .commit()
                if (staged) {
                    sendSnapshotToModule(appContext, localSnapshot, revision) { accepted ->
                        acknowledgeHostSnapshot(appContext, revision, accepted)
                    }
                }
            }
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
        keyCornerRadius: Int,
        edgeHighlightEnabled: Boolean,
        edgeHighlightIntensity: Int,
        candidateBackgroundAlpha: Int,
        candidateBackgroundCorner: Float,
        candidateBackgroundLeftMarginDp: Int,
        candidatePinyinLeftMarginDp: Int,
        toolbarIconBgOpacity: Int,
        appearanceColors: Map<String, Int>,
        disableHotUpdate: Boolean = DEFAULT_DISABLE_HOT_UPDATE,
        showCrossDeviceClipboard: Boolean = DEFAULT_SHOW_CROSS_DEVICE_CLIPBOARD,
        removeClipboardRetentionLimit: Boolean = DEFAULT_REMOVE_CLIPBOARD_RETENTION_LIMIT,
        removeClipboardTextLimit: Boolean = DEFAULT_REMOVE_CLIPBOARD_TEXT_LIMIT,
        clipboardSearchEnabled: Boolean = DEFAULT_CLIPBOARD_SEARCH_ENABLED,
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
        fontMode: Int = DEFAULT_FONT_MODE,
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
            keyCornerRadius = keyCornerRadius,
            edgeHighlightEnabled = edgeHighlightEnabled,
            edgeHighlightIntensity = edgeHighlightIntensity,
            candidateBackgroundAlpha = candidateBackgroundAlpha,
            candidateBackgroundCorner = candidateBackgroundCorner,
            candidateBackgroundLeftMarginDp = candidateBackgroundLeftMarginDp,
            candidatePinyinLeftMarginDp = candidatePinyinLeftMarginDp,
            toolbarIconBgOpacity = toolbarIconBgOpacity,
            appearanceColors = sanitizedAppearanceColors,
            disableHotUpdate = disableHotUpdate,
            showCrossDeviceClipboard = showCrossDeviceClipboard,
            removeClipboardRetentionLimit = removeClipboardRetentionLimit,
            removeClipboardTextLimit = removeClipboardTextLimit,
            clipboardSearchEnabled = clipboardSearchEnabled,
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
            gestureLabelPosition = gestureLabelPosition.coerceIn(
                GESTURE_LABEL_POSITION_BOTTOM,
                GESTURE_LABEL_POSITION_TOP
            ),
            gestureLabelMarginTopDp = gestureLabelMarginTopDp.coerceIn(0, 24),
            gestureLabelMarginBottomDp = gestureLabelMarginBottomDp.coerceIn(0, 24),
            gestureLabelMarginLeftDp = gestureLabelMarginLeftDp.coerceIn(0, 24),
            gestureLabelMarginRightDp = gestureLabelMarginRightDp.coerceIn(0, 24),
            logoEnabled = logoEnabled,
            logoShowEnabled = logoShowEnabled,
            logoColorMode = logoColorMode,
            logoCustomColor = logoCustomColor,
            fontMode = fontMode,
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

    fun getKeyCornerRadiusXposed(): Int = readSnapshotXposed().keyCornerRadius

    fun isEdgeHighlightEnabledXposed(context: Context): Boolean =
        readSnapshotXposed().edgeHighlightEnabled

    fun getEdgeHighlightIntensityXposed(context: Context): Int =
        readSnapshotXposed().edgeHighlightIntensity

    fun getCandidateBackgroundAlphaXposed(): Int =
        readSnapshotXposed().candidateBackgroundAlpha

    fun getCandidateBackgroundCornerXposed(): Float =
        readSnapshotXposed().candidateBackgroundCorner

    fun getCandidateBackgroundLeftMarginDpXposed(): Int =
        readSnapshotXposed().candidateBackgroundLeftMarginDp

    fun getToolbarIconBgOpacityXposed(): Int =
        readSnapshotXposed().toolbarIconBgOpacity

    fun getCandidatePinyinLeftMarginDpXposed(): Int =
        readSnapshotXposed().candidatePinyinLeftMarginDp

    fun isDisableHotUpdateXposed(): Boolean = readSnapshotXposed().disableHotUpdate

    fun getAppearanceColorXposed(groupId: String): Int =
        readSnapshotXposed().appearanceColors[groupId]
            ?: WeTypeAppearanceColorGroups.findById(groupId)?.defaultColor
            ?: 0

    fun getAppearanceColorsXposed(): Map<String, Int> = readSnapshotXposed().appearanceColors

    fun readSnapshot(context: Context): Snapshot {
        return remotePreferences?.toSnapshotOrNull()
            ?: appPreferences(context).toSnapshotOrNull()
            ?: defaultSnapshot()
    }

    private fun readSnapshotXposed(): Snapshot {
        cachedXposedSnapshot?.let { return it }

        synchronized(remotePrefsLock) {
            cachedXposedSnapshot?.let { return it }
            // ① 远端偏好（模块 App）→ ② 按需重绑远端偏好 → ③ 宿主本地偏好 → ④ 默认值（不缓存）。
            // 默认值（强调色 #23C891 绿）绝不能被缓存：一次瞬态读取失败曾会让整个进程
            // 的强调色/美化设置退回默认且无法自愈（“Logo/强调色莫名回绿”）。
            val remoteSnapshot = resolvedRemotePreferencesLocked()?.toSnapshotOrNull()
            if (remoteSnapshot != null) {
                cachedXposedSnapshot = remoteSnapshot
                return remoteSnapshot
            }
            val localSnapshot = hostContextRef?.get()?.let { appPreferences(it).toSnapshotOrNull() }
            if (localSnapshot != null) {
                cachedXposedSnapshot = localSnapshot
                AndroidLog.w(TAG, "remote prefs unavailable, falling back to host local snapshot")
                return localSnapshot
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

    private fun saveDirect(
        context: Context,
        lightColor: Int,
        darkColor: Int,
        blurRadius: Int,
        cornerRadius: Int,
        keyCornerRadius: Int,
        edgeHighlightEnabled: Boolean,
        edgeHighlightIntensity: Int,
        candidateBackgroundAlpha: Int,
        candidateBackgroundCorner: Float,
        candidateBackgroundLeftMarginDp: Int,
        candidatePinyinLeftMarginDp: Int,
        toolbarIconBgOpacity: Int,
        appearanceColors: Map<String, Int>,
        disableHotUpdate: Boolean,
        showCrossDeviceClipboard: Boolean = DEFAULT_SHOW_CROSS_DEVICE_CLIPBOARD,
        removeClipboardRetentionLimit: Boolean = DEFAULT_REMOVE_CLIPBOARD_RETENTION_LIMIT,
        removeClipboardTextLimit: Boolean = DEFAULT_REMOVE_CLIPBOARD_TEXT_LIMIT,
        clipboardSearchEnabled: Boolean = DEFAULT_CLIPBOARD_SEARCH_ENABLED,
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
        fontMode: Int = DEFAULT_FONT_MODE,
        onPersisted: (Boolean) -> Unit
    ): Boolean {
        val snapshot = Snapshot(
            lightColor = lightColor,
            darkColor = darkColor,
            blurRadius = blurRadius.coerceIn(0, 100),
            cornerRadius = cornerRadius.coerceIn(0, MAX_CORNER_RADIUS),
            keyCornerRadius = keyCornerRadius.coerceIn(0, MAX_KEY_CORNER_RADIUS),
            edgeHighlightEnabled = edgeHighlightEnabled,
            edgeHighlightIntensity = edgeHighlightIntensity.coerceIn(0, 200),
            candidateBackgroundAlpha = candidateBackgroundAlpha.coerceIn(0, 255),
            candidateBackgroundCorner = candidateBackgroundCorner.coerceIn(
                0f,
                MAX_CANDIDATE_BACKGROUND_CORNER.toFloat()
            ),
            candidateBackgroundLeftMarginDp = candidateBackgroundLeftMarginDp.coerceIn(0, 64),
            candidatePinyinLeftMarginDp = candidatePinyinLeftMarginDp.coerceIn(0, 64),
            toolbarIconBgOpacity = toolbarIconBgOpacity.coerceIn(0, 255),
            appearanceColors = WeTypeAppearanceColorGroups.groups.associate { group ->
                group.id to (appearanceColors[group.id] ?: group.defaultColor)
            },
            disableHotUpdate = disableHotUpdate,
            showCrossDeviceClipboard = showCrossDeviceClipboard,
            removeClipboardRetentionLimit = removeClipboardRetentionLimit,
            removeClipboardTextLimit = removeClipboardTextLimit,
            clipboardSearchEnabled = clipboardSearchEnabled,
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
            gestureLabelPosition = gestureLabelPosition.coerceIn(
                GESTURE_LABEL_POSITION_BOTTOM,
                GESTURE_LABEL_POSITION_TOP
            ),
            gestureLabelMarginTopDp = gestureLabelMarginTopDp.coerceIn(0, 24),
            gestureLabelMarginBottomDp = gestureLabelMarginBottomDp.coerceIn(0, 24),
            gestureLabelMarginLeftDp = gestureLabelMarginLeftDp.coerceIn(0, 24),
            gestureLabelMarginRightDp = gestureLabelMarginRightDp.coerceIn(0, 24),
            logoEnabled = logoEnabled,
            logoShowEnabled = logoShowEnabled,
            logoColorMode = normalizeLogoColorMode(logoColorMode),
            logoCustomColor = logoCustomColor,
            fontMode = fontMode.coerceIn(FONT_MODE_OFFICIAL, FONT_MODE_SYSTEM)
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
            sendSnapshotToModule(appContext, snapshot, revision) { accepted ->
                acknowledgeHostSnapshot(appContext, revision, accepted)
                onPersisted(accepted)
            }
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
            .putInt(KEY_KEY_CORNER_RADIUS, snapshot.keyCornerRadius)
            .putBoolean(KEY_EDGE_HIGHLIGHT_ENABLED, snapshot.edgeHighlightEnabled)
            .putInt(KEY_EDGE_HIGHLIGHT_INTENSITY, snapshot.edgeHighlightIntensity)
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
            .putBoolean(KEY_DISABLE_HOT_UPDATE, snapshot.disableHotUpdate)
            .putBoolean(KEY_SHOW_CROSS_DEVICE_CLIPBOARD, snapshot.showCrossDeviceClipboard)
            .putBoolean(KEY_REMOVE_CLIPBOARD_RETENTION_LIMIT, snapshot.removeClipboardRetentionLimit)
            .putBoolean(KEY_REMOVE_CLIPBOARD_TEXT_LIMIT, snapshot.removeClipboardTextLimit)
            .putBoolean(KEY_CLIPBOARD_SEARCH, snapshot.clipboardSearchEnabled)
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
            .putBoolean(KEY_LOGO_ENABLED, snapshot.logoEnabled)
            .putBoolean(KEY_LOGO_SHOW_ENABLED, snapshot.logoShowEnabled)
            .putString(KEY_LOGO_COLOR_MODE, snapshot.logoColorMode)
            .putInt(KEY_LOGO_CUSTOM_COLOR, snapshot.logoCustomColor)
            .putInt(KEY_FONT_MODE, snapshot.fontMode)
            .putBoolean(KEY_KEY_OPACITY_MIGRATED, true)
            .remove(KEY_KEY_OPACITY)
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

    private fun sendSnapshotToModule(
        context: Context,
        snapshot: Snapshot,
        revision: Long,
        onAccepted: (Boolean) -> Unit
    ): Boolean {
        if (context.packageName != WETYPE_PACKAGE_NAME) {
            onAccepted(false)
            return false
        }
        val appContext = context.applicationContext ?: context
        val acknowledgementAction = "${ModuleBridgeContract.ACTION_ACK_PREFIX}.${UUID.randomUUID()}"
        val acknowledgementToken = UUID.randomUUID().toString()
        val intent = ModuleBridgeContract.explicitBridgeIntent()
            .putExtra(ModuleBridgeContract.EXTRA_MESSAGE_TYPE, ModuleBridgeContract.MESSAGE_SAVE_SETTINGS)
            .putExtra(ModuleBridgeContract.EXTRA_SETTINGS, snapshot.toBundle())
            .putExtra(ModuleBridgeContract.EXTRA_REVISION, revision)
            .putExtra(ModuleBridgeContract.EXTRA_ACK_ACTION, acknowledgementAction)
            .putExtra(ModuleBridgeContract.EXTRA_ACK_TOKEN, acknowledgementToken)
        val mainHandler = Handler(Looper.getMainLooper())
        val finished = AtomicBoolean(false)
        var registered = false
        lateinit var acknowledgementReceiver: BroadcastReceiver

        fun finish(accepted: Boolean) {
            if (!finished.compareAndSet(false, true)) return
            mainHandler.removeCallbacksAndMessages(null)
            if (registered) runCatching { appContext.unregisterReceiver(acknowledgementReceiver) }
            onAccepted(accepted)
        }

        acknowledgementReceiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, acknowledgement: Intent) {
                if (acknowledgement.action != acknowledgementAction ||
                    acknowledgement.getStringExtra(ModuleBridgeContract.EXTRA_ACK_TOKEN) !=
                    acknowledgementToken ||
                    acknowledgement.getLongExtra(
                        ModuleBridgeContract.EXTRA_REVISION,
                        Long.MIN_VALUE
                    ) != revision
                ) {
                    return
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                    receiverContext.packageManager.getPackagesForUid(sentFromUid)
                        ?.contains(MODULE_PACKAGE_NAME) != true
                ) {
                    finish(false)
                    return
                }
                finish(
                    acknowledgement.getIntExtra(ModuleBridgeContract.EXTRA_RESULT, 0) ==
                        ModuleBridgeContract.RESULT_ACCEPTED
                )
            }
        }
        val didRegister = runCatching {
            val filter = IntentFilter(acknowledgementAction)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(
                    acknowledgementReceiver,
                    filter,
                    Context.RECEIVER_EXPORTED
                )
            } else {
                @Suppress("DEPRECATION")
                appContext.registerReceiver(acknowledgementReceiver, filter)
            }
            true
        }.getOrDefault(false)
        registered = didRegister
        val sentViaPendingIntent = moduleBridgePendingIntent?.let { pendingIntent ->
            runCatching {
                pendingIntent.send(appContext, 0, intent)
            }.onFailure {
                moduleBridgePendingIntent = null
            }.isSuccess
        } == true
        if (!didRegister ||
            (!sentViaPendingIntent && !ModuleBridgeContract.sendWithIdentity(appContext, intent))
        ) {
            finish(false)
            return false
        }
        mainHandler.postDelayed({ finish(false) }, ModuleBridgeContract.ACK_TIMEOUT_MILLIS)
        return true
    }

    private fun acknowledgeHostSnapshot(context: Context, revision: Long, accepted: Boolean) {
        if (!accepted) return
        val preferences = appPreferences(context)
        if (preferences.getLong(KEY_HOST_SYNC_REVISION, Long.MIN_VALUE) != revision) return
        preferences.edit().putBoolean(KEY_HOST_SYNC_PENDING, false).commit()
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
        putInt(KEY_KEY_CORNER_RADIUS, keyCornerRadius)
        putBoolean(KEY_EDGE_HIGHLIGHT_ENABLED, edgeHighlightEnabled)
        putInt(KEY_EDGE_HIGHLIGHT_INTENSITY, edgeHighlightIntensity)
        putInt(KEY_CANDIDATE_BACKGROUND_ALPHA, candidateBackgroundAlpha)
        putFloat(KEY_CANDIDATE_BACKGROUND_CORNER, candidateBackgroundCorner)
        putInt(KEY_CANDIDATE_BACKGROUND_LEFT_MARGIN_DP, candidateBackgroundLeftMarginDp)
        putInt(KEY_CANDIDATE_PINYIN_LEFT_MARGIN_DP, candidatePinyinLeftMarginDp)
        putInt(KEY_TOOLBAR_ICON_BG_OPACITY, toolbarIconBgOpacity)
        putBoolean(KEY_DISABLE_HOT_UPDATE, disableHotUpdate)
        putBoolean(KEY_SHOW_CROSS_DEVICE_CLIPBOARD, showCrossDeviceClipboard)
        putBoolean(KEY_REMOVE_CLIPBOARD_RETENTION_LIMIT, removeClipboardRetentionLimit)
        putBoolean(KEY_REMOVE_CLIPBOARD_TEXT_LIMIT, removeClipboardTextLimit)
        putBoolean(KEY_CLIPBOARD_SEARCH, clipboardSearchEnabled)
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
        putBoolean(KEY_LOGO_ENABLED, logoEnabled)
        putBoolean(KEY_LOGO_SHOW_ENABLED, logoShowEnabled)
        putString(KEY_LOGO_COLOR_MODE, logoColorMode)
        putInt(KEY_LOGO_CUSTOM_COLOR, logoCustomColor)
        putInt(KEY_FONT_MODE, fontMode)
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
            keyCornerRadius = getInt(KEY_KEY_CORNER_RADIUS, defaults.keyCornerRadius)
                .coerceIn(0, MAX_KEY_CORNER_RADIUS),
            edgeHighlightEnabled = getBoolean(
                KEY_EDGE_HIGHLIGHT_ENABLED,
                defaults.edgeHighlightEnabled
            ),
            edgeHighlightIntensity = getInt(
                KEY_EDGE_HIGHLIGHT_INTENSITY,
                defaults.edgeHighlightIntensity
            ).coerceIn(0, 200),
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
            disableHotUpdate = getBoolean(KEY_DISABLE_HOT_UPDATE, defaults.disableHotUpdate),
            showCrossDeviceClipboard = getBoolean(KEY_SHOW_CROSS_DEVICE_CLIPBOARD, defaults.showCrossDeviceClipboard),
            removeClipboardRetentionLimit = getBoolean(KEY_REMOVE_CLIPBOARD_RETENTION_LIMIT, defaults.removeClipboardRetentionLimit),
            removeClipboardTextLimit = getBoolean(KEY_REMOVE_CLIPBOARD_TEXT_LIMIT, defaults.removeClipboardTextLimit),
            clipboardSearchEnabled = getBoolean(KEY_CLIPBOARD_SEARCH, defaults.clipboardSearchEnabled),
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
            gestureLabelPosition = getInt(KEY_GESTURE_LABEL_POSITION, defaults.gestureLabelPosition)
                .coerceIn(GESTURE_LABEL_POSITION_BOTTOM, GESTURE_LABEL_POSITION_TOP),
            gestureLabelMarginTopDp = getInt(KEY_GESTURE_LABEL_MARGIN_TOP_DP, defaults.gestureLabelMarginTopDp)
                .coerceIn(0, 24),
            gestureLabelMarginBottomDp = getInt(KEY_GESTURE_LABEL_MARGIN_BOTTOM_DP, defaults.gestureLabelMarginBottomDp)
                .coerceIn(0, 24),
            gestureLabelMarginLeftDp = getInt(KEY_GESTURE_LABEL_MARGIN_LEFT_DP, defaults.gestureLabelMarginLeftDp)
                .coerceIn(0, 24),
            gestureLabelMarginRightDp = getInt(KEY_GESTURE_LABEL_MARGIN_RIGHT_DP, defaults.gestureLabelMarginRightDp)
                .coerceIn(0, 24),
            logoEnabled = getBoolean(KEY_LOGO_ENABLED, defaults.logoEnabled),
            logoShowEnabled = getBoolean(KEY_LOGO_SHOW_ENABLED, defaults.logoShowEnabled),
            logoColorMode = normalizeLogoColorMode(getString(KEY_LOGO_COLOR_MODE) ?: defaults.logoColorMode),
            logoCustomColor = getInt(KEY_LOGO_CUSTOM_COLOR, defaults.logoCustomColor),
            fontMode = getInt(KEY_FONT_MODE, defaults.fontMode)
                .coerceIn(FONT_MODE_OFFICIAL, FONT_MODE_SYSTEM)
        )
    }

    private fun SharedPreferences.toSnapshot(): Snapshot {
        val shouldMigrateLegacyKeyOpacity = contains(KEY_KEY_OPACITY) &&
            !getBoolean(KEY_KEY_OPACITY_MIGRATED, false)
        val legacyKeyOpacity = if (shouldMigrateLegacyKeyOpacity) {
            getInt(KEY_KEY_OPACITY, 255).coerceIn(0, 255)
        } else {
            null
        }
        return Snapshot(
            lightColor = getInt(KEY_LIGHT_COLOR, DEFAULT_LIGHT_COLOR),
            darkColor = getInt(KEY_DARK_COLOR, DEFAULT_DARK_COLOR),
            blurRadius = getInt(KEY_BLUR_RADIUS, DEFAULT_BLUR_RADIUS),
            cornerRadius = getInt(KEY_CORNER_RADIUS, DEFAULT_CORNER_RADIUS)
                .coerceIn(0, MAX_CORNER_RADIUS),
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
            gestureLabelPosition = getInt(KEY_GESTURE_LABEL_POSITION, DEFAULT_GESTURE_LABEL_POSITION)
                .coerceIn(GESTURE_LABEL_POSITION_BOTTOM, GESTURE_LABEL_POSITION_TOP),
            gestureLabelMarginTopDp = getInt(KEY_GESTURE_LABEL_MARGIN_TOP_DP, DEFAULT_GESTURE_LABEL_MARGIN_TOP_DP)
                .coerceIn(0, 24),
            gestureLabelMarginBottomDp = getInt(KEY_GESTURE_LABEL_MARGIN_BOTTOM_DP, DEFAULT_GESTURE_LABEL_MARGIN_BOTTOM_DP)
                .coerceIn(0, 24),
            gestureLabelMarginLeftDp = getInt(KEY_GESTURE_LABEL_MARGIN_LEFT_DP, DEFAULT_GESTURE_LABEL_MARGIN_LEFT_DP)
                .coerceIn(0, 24),
            gestureLabelMarginRightDp = getInt(KEY_GESTURE_LABEL_MARGIN_RIGHT_DP, DEFAULT_GESTURE_LABEL_MARGIN_RIGHT_DP)
                .coerceIn(0, 24),
            logoEnabled = getBoolean(KEY_LOGO_ENABLED, DEFAULT_LOGO_ENABLED),
            logoShowEnabled = getBoolean(KEY_LOGO_SHOW_ENABLED, DEFAULT_LOGO_SHOW_ENABLED),
            logoColorMode = normalizeLogoColorMode(getString(KEY_LOGO_COLOR_MODE, DEFAULT_LOGO_COLOR_MODE)),
            logoCustomColor = getInt(KEY_LOGO_CUSTOM_COLOR, DEFAULT_LOGO_CUSTOM_COLOR),
            fontMode = getInt(KEY_FONT_MODE, DEFAULT_FONT_MODE)
                .coerceIn(FONT_MODE_OFFICIAL, FONT_MODE_SYSTEM)
        )
    }

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
        keyCornerRadius = DEFAULT_KEY_CORNER_RADIUS,
        edgeHighlightEnabled = DEFAULT_EDGE_HIGHLIGHT_ENABLED,
        edgeHighlightIntensity = DEFAULT_EDGE_HIGHLIGHT_INTENSITY,
        candidateBackgroundAlpha = DEFAULT_CANDIDATE_BACKGROUND_ALPHA,
        candidateBackgroundCorner = DEFAULT_CANDIDATE_BACKGROUND_CORNER,
        candidateBackgroundLeftMarginDp = DEFAULT_CANDIDATE_BACKGROUND_LEFT_MARGIN_DP,
        candidatePinyinLeftMarginDp = DEFAULT_CANDIDATE_PINYIN_LEFT_MARGIN_DP,
        toolbarIconBgOpacity = DEFAULT_TOOLBAR_ICON_BG_OPACITY,
        appearanceColors = WeTypeAppearanceColorGroups.defaultColors(),
        disableHotUpdate = DEFAULT_DISABLE_HOT_UPDATE,
        showCrossDeviceClipboard = DEFAULT_SHOW_CROSS_DEVICE_CLIPBOARD,
        removeClipboardRetentionLimit = DEFAULT_REMOVE_CLIPBOARD_RETENTION_LIMIT,
        removeClipboardTextLimit = DEFAULT_REMOVE_CLIPBOARD_TEXT_LIMIT,
        clipboardSearchEnabled = DEFAULT_CLIPBOARD_SEARCH_ENABLED,
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
        fontMode = DEFAULT_FONT_MODE
    )

    private fun SharedPreferences.containsAnyPersistedSetting(): Boolean {
        if (contains(KEY_LIGHT_COLOR) ||
            contains(KEY_DARK_COLOR) ||
            contains(KEY_BLUR_RADIUS) ||
            contains(KEY_CORNER_RADIUS) ||
            contains(KEY_KEY_CORNER_RADIUS) ||
            contains(KEY_EDGE_HIGHLIGHT_ENABLED) ||
            contains(KEY_EDGE_HIGHLIGHT_INTENSITY) ||
            contains(KEY_KEY_OPACITY) ||
            contains(KEY_CANDIDATE_BACKGROUND_ALPHA) ||
            contains(KEY_CANDIDATE_BACKGROUND_CORNER) ||
            contains(KEY_CANDIDATE_BACKGROUND_LEFT_MARGIN_DP) ||
            contains(KEY_CANDIDATE_PINYIN_LEFT_MARGIN_DP) ||
            contains(KEY_TOOLBAR_ICON_BG_OPACITY) ||
            contains(KEY_DISABLE_HOT_UPDATE) ||
            contains(KEY_SHOW_CROSS_DEVICE_CLIPBOARD) ||
            contains(KEY_REMOVE_CLIPBOARD_RETENTION_LIMIT) ||
            contains(KEY_REMOVE_CLIPBOARD_TEXT_LIMIT) ||
            contains(KEY_CLIPBOARD_SEARCH) ||
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
            contains(KEY_FONT_MODE)
        ) {
            return true
        }
        return WeTypeAppearanceColorGroups.groups.any { group ->
            contains("$KEY_APPEARANCE_COLOR_PREFIX${group.id}")
        }
    }

}
