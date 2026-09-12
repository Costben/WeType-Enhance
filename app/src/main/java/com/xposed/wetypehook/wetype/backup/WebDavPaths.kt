package com.xposed.wetypehook.wetype.backup

import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal data class WebDavRemoteEntry(
    val name: String,
    val url: String,
    val sizeBytes: Long,
    val lastModified: Long
)

/** WebDAV 路径拼接与转义（纯逻辑）。 */
internal object WebDavPaths {

    fun normalizeBase(raw: String): String {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.isEmpty()) return ""
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "https://$trimmed"
        }
    }

    fun splitSegments(path: String): List<String> =
        path.split('/').filter { it.isNotEmpty() }

    fun join(base: String, segments: List<String>): String {
        val normalized = normalizeBase(base)
        if (segments.isEmpty()) return normalized
        val tail = segments.joinToString("/") { encodeSegment(it) }
        return if (normalized.isEmpty()) tail else "$normalized/$tail"
    }

    fun encodeSegment(segment: String): String =
        URLEncoder.encode(segment, StandardCharsets.UTF_8.name()).replace("+", "%20")

    fun decodeSegment(segment: String): String {
        if ('%' !in segment) return segment
        val bytes = ByteArrayOutputStream(segment.length)
        var i = 0
        while (i < segment.length) {
            val c = segment[i]
            if (c == '%' && i + 2 < segment.length) {
                val code = segment.substring(i + 1, i + 3).toIntOrNull(16)
                if (code != null) {
                    bytes.write(code)
                    i += 3
                    continue
                }
            }
            bytes.write(c.toString().toByteArray(StandardCharsets.UTF_8))
            i++
        }
        return String(bytes.toByteArray(), StandardCharsets.UTF_8)
    }

    fun fileNameFromHref(href: String): String {
        val path = href.substringBefore('#').substringBefore('?').trimEnd('/')
        return decodeSegment(path.substringAfterLast('/'))
    }

    fun resolveHref(baseUrl: String, href: String): String = runCatching {
        URI(baseUrl.trimEnd('/') + "/").resolve(href).toString()
    }.getOrDefault(href)
}

/** WebDAV 远程包保留策略：按时间倒序保留最近 N 份。 */
internal object WebDavRetention {

    /** [entries] 需为按时间倒序的列表；keepCount <= 0 表示不限。 */
    fun selectForDeletion(
        entries: List<WebDavRemoteEntry>,
        keepCount: Int
    ): List<WebDavRemoteEntry> {
        if (keepCount <= 0 || entries.size <= keepCount) return emptyList()
        return entries.drop(keepCount)
    }
}
