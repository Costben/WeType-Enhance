package com.xposed.wetypehook

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
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
 *
 * 正式版与预发布混在同一个列表里，由 [includePrereleases] 决定要不要看预发布：
 * 关着就只在正式版里挑最新的，开着连 alpha 一起比。
 */
internal object ModuleUpdateChecker {

    private const val RELEASES_API =
        "https://api.github.com/repos/Costben/WeType-Enhance/releases?per_page=30"
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 8_000

    suspend fun check(
        currentVersionCode: Int,
        currentVersionName: String,
        includePrereleases: Boolean = false
    ): ModuleUpdateInfo? = withContext(Dispatchers.IO) {
        runCatching {
            requestLatest(currentVersionCode, currentVersionName, includePrereleases)
        }.getOrNull()
    }

    private fun requestLatest(
        currentVersionCode: Int,
        currentVersionName: String,
        includePrereleases: Boolean
    ): ModuleUpdateInfo? {
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
            parseReleasesResponse(body, currentVersionCode, currentVersionName, includePrereleases)
        } finally {
            connection.disconnect()
        }
    }

    internal fun parseReleasesResponse(
        body: String,
        currentVersionCode: Int,
        currentVersionName: String,
        includePrereleases: Boolean
    ): ModuleUpdateInfo? = runCatching {
        val releases = JSONArray(body)
        var newest: Candidate? = null
        for (index in 0 until releases.length()) {
            val candidate = parseRelease(
                releases.getJSONObject(index),
                currentVersionCode,
                currentVersionName,
                includePrereleases
            ) ?: continue
            if (newest == null || isCandidateNewer(candidate, newest)) {
                newest = candidate
            }
        }
        newest?.info
    }.getOrNull()

    private fun parseRelease(
        json: JSONObject,
        currentVersionCode: Int,
        currentVersionName: String,
        includePrereleases: Boolean
    ): Candidate? {
        if (json.optBoolean("draft")) return null
        if (json.optBoolean("prerelease") && !includePrereleases) return null
        val versionName = json.optString("tag_name")
            .trim()
            .removePrefix("v")
            .removePrefix("V")
        if (versionName.isBlank()) return null
        val body = json.optString("body")
        val versionCode = parseVersionCode(body)
        // 版本号排序在这条发布线上不够用：`1.28.19` 是 alpha 轮之前发的预发布，按 SemVer
        // 却比 `1.28.19-alpha2` 更高，会把用户顶回旧包。正文里的 versionCode 才是真实顺序，
        // 缺失时才退回版本号比较。
        val newer = if (versionCode != null) {
            versionCode > currentVersionCode
        } else {
            isNewerVersion(versionName, currentVersionName)
        }
        if (!newer) return null
        val downloadUrl = json.optJSONArray("assets")?.let { assets ->
            (0 until assets.length())
                .map { assets.getJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) }
                ?.optString("browser_download_url")
                ?.takeIf { it.isNotBlank() }
        }
        return Candidate(
            info = ModuleUpdateInfo(
                versionName = versionName,
                changelog = formatChangelog(body),
                releaseUrl = json.optString("html_url"),
                downloadUrl = downloadUrl
            ),
            versionCode = versionCode
        )
    }

    private fun isCandidateNewer(candidate: Candidate, current: Candidate): Boolean {
        val candidateCode = candidate.versionCode
        val currentCode = current.versionCode
        return if (candidateCode != null && currentCode != null) {
            candidateCode > currentCode
        } else {
            isNewerVersion(candidate.info.versionName, current.info.versionName)
        }
    }

    private data class Candidate(val info: ModuleUpdateInfo, val versionCode: Int?)

    internal fun parseVersionCode(body: String): Int? = body.lineSequence()
        .map { it.trim() }
        .firstNotNullOfOrNull { line ->
            VERSION_CODE_LINE.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()
        }

    /**
     * 逐段比较点分版本号，缺位按 0 处理；数字段相同时正式版高于同号预发布。
     *
     * 预发布编号按预发布段里的数字比：`1.28.19-alpha2` 高于 `1.28.19-alpha1`，
     * 但都低于 `1.28.19`。这样开了预发布的用户拿到正式版之后，不会再被同号的 alpha
     * 反向顶回旧包。
     */
    internal fun isNewerVersion(candidate: String, current: String): Boolean {
        val candidateVersion = parseVersion(candidate)
        val currentVersion = parseVersion(current)
        val numberCount = maxOf(candidateVersion.numbers.size, currentVersion.numbers.size)
        for (index in 0 until numberCount) {
            val candidatePart = candidateVersion.numbers.getOrElse(index) { 0 }
            val currentPart = currentVersion.numbers.getOrElse(index) { 0 }
            if (candidatePart != currentPart) return candidatePart > currentPart
        }
        val candidatePre = candidateVersion.prerelease
        val currentPre = currentVersion.prerelease
        if (candidatePre == null || currentPre == null) {
            return candidatePre == null && currentPre != null
        }
        val preCount = maxOf(candidatePre.size, currentPre.size)
        for (index in 0 until preCount) {
            val candidatePart = candidatePre.getOrElse(index) { 0 }
            val currentPart = currentPre.getOrElse(index) { 0 }
            if (candidatePart != currentPart) return candidatePart > currentPart
        }
        return false
    }

    private data class ParsedVersion(val numbers: List<Int>, val prerelease: List<Int>?)

    private fun parseVersion(version: String): ParsedVersion {
        val normalized = version.trim().removePrefix("v").removePrefix("V")
        val core = normalized.takeWhile { it.isDigit() || it == '.' }
        val numbers = core.split('.')
            .mapNotNull { segment -> segment.takeWhile(Char::isDigit).toIntOrNull() }
            .ifEmpty { listOf(0) }
        val suffix = normalized.drop(core.length).trimStart('-', '_', '+')
        val prerelease = suffix.takeIf { it.isNotBlank() }
            ?.split('.', '-', '_', '+')
            ?.map { segment -> segment.takeLastWhile(Char::isDigit).toIntOrNull() ?: 0 }
        return ParsedVersion(numbers, prerelease)
    }

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

    private val VERSION_CODE_LINE = Regex("^versionCode\\s*(\\d+).*$", RegexOption.IGNORE_CASE)
}
