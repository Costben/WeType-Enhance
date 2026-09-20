package com.xposed.wetypehook

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 模块更新检查结果。
 *
 * @param versionName 远端最新版本号（去掉 v 前缀）
 * @param changelog 清洗后的更新说明
 * @param releaseUrl Release 页面地址
 * @param downloadUrl 签名 APK 直链，缺失时回退到 releaseUrl
 */
internal data class ModuleUpdateInfo(
    val versionName: String,
    val changelog: String,
    val releaseUrl: String,
    val downloadUrl: String?
) {
    val targetUrl: String get() = downloadUrl ?: releaseUrl
}

/**
 * 通过 GitHub Releases API 检查 `Costben/WeType-Enhance` 是否有新版本。
 * 网络失败、限流、无更新一律返回 null，调用方静默处理。
 */
internal object ModuleUpdateChecker {

    private const val RELEASES_API =
        "https://api.github.com/repos/Costben/WeType-Enhance/releases/latest"
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 8_000

    suspend fun check(currentVersionName: String): ModuleUpdateInfo? =
        withContext(Dispatchers.IO) {
            runCatching { requestLatest(currentVersionName) }.getOrNull()
        }

    private fun requestLatest(currentVersionName: String): ModuleUpdateInfo? {
        val connection = (URL(RELEASES_API).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "WeType-Enhance")
        }
        return try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            parseReleaseResponse(body, currentVersionName)
        } finally {
            connection.disconnect()
        }
    }

    internal fun parseReleaseResponse(
        body: String,
        currentVersionName: String
    ): ModuleUpdateInfo? = runCatching {
        parseRelease(JSONObject(body), currentVersionName)
    }.getOrNull()

    private fun parseRelease(json: JSONObject, currentVersionName: String): ModuleUpdateInfo? {
        val versionName = json.optString("tag_name")
            .trim()
            .removePrefix("v")
            .removePrefix("V")
        if (versionName.isBlank() || !isNewerVersion(versionName, currentVersionName)) {
            return null
        }
        val downloadUrl = json.optJSONArray("assets")?.let { assets ->
            (0 until assets.length())
                .map { assets.getJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) }
                ?.optString("browser_download_url")
                ?.takeIf { it.isNotBlank() }
        }
        return ModuleUpdateInfo(
            versionName = versionName,
            changelog = formatChangelog(json.optString("body")),
            releaseUrl = json.optString("html_url"),
            downloadUrl = downloadUrl
        )
    }

    /** 逐段比较点分版本号，缺位按 0 处理。 */
    internal fun isNewerVersion(candidate: String, current: String): Boolean {
        val candidateParts = parseVersionParts(candidate)
        val currentParts = parseVersionParts(current)
        val size = maxOf(candidateParts.size, currentParts.size)
        for (index in 0 until size) {
            val candidatePart = candidateParts.getOrElse(index) { 0 }
            val currentPart = currentParts.getOrElse(index) { 0 }
            if (candidatePart != currentPart) return candidatePart > currentPart
        }
        return false
    }

    private fun parseVersionParts(version: String): List<Int> =
        version.split('.', '-', '_', '+')
            .mapNotNull { segment -> segment.takeWhile(Char::isDigit).toIntOrNull() }
            .ifEmpty { listOf(0) }

    /** 把 Release 正文里的 Markdown 标题、分隔线与 versionCode 行清理成纯文本条目。 */
    internal fun formatChangelog(raw: String): String = raw
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .filterNot { it.startsWith("#") || it.matches(VERSION_CODE_LINE) }
        .map { line ->
            when {
                line.startsWith("- ") || line.startsWith("* ") -> "· " + line.substring(2).trim()
                else -> line
            }
        }
        .joinToString("\n")

    private val VERSION_CODE_LINE = Regex("^versionCode\\s*\\d+.*$", RegexOption.IGNORE_CASE)
}
