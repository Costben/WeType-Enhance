package com.xposed.wetypehook.wetype.settings

/**
 * 剪贴板备份与恢复的独立设置（与主 Snapshot 解耦，仍走同一组 remote prefs）。
 * 密码为明文存储（模块私有偏好，经 LSPosed 在两端进程共享）。
 */
data class ClipboardBackupSettings(
    val localFolderUri: String = "",
    val webDavUrl: String = "",
    val webDavUsername: String = "",
    val webDavPassword: String = "",
    val webDavRemoteDir: String = DEFAULT_REMOTE_DIR,
    val webDavKeepCount: Int = DEFAULT_KEEP_COUNT,
    val webDavAllowSelfSigned: Boolean = false,
    val preExportDownload: Boolean = false
) {
    companion object {
        const val DEFAULT_REMOTE_DIR = "WeTypeEnhance"
        const val DEFAULT_KEEP_COUNT = 5
        const val KEEP_COUNT_UNLIMITED = 0
        const val KEEP_COUNT_MIN = 1
        const val KEEP_COUNT_MAX = 100

        fun sanitizeKeepCount(value: Int): Int =
            if (value <= KEEP_COUNT_UNLIMITED) {
                KEEP_COUNT_UNLIMITED
            } else {
                value.coerceIn(KEEP_COUNT_MIN, KEEP_COUNT_MAX)
            }
    }
}
