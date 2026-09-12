package com.xposed.wetypehook.wetype.backup

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * 解析 WebDAV `PROPFIND Depth: 1` 响应。
 *
 * 不能使用 `DocumentBuilderFactory`：微信输入法进程自带的 Xerces 实现损坏，
 * 会抛 `This parser does not support specification "Unknown" version "0.0"`。
 * 这里手写极简 XML 扫描器，只处理 WebDAV 响应用到的结构。
 * 返回按时间倒序（未知时间靠后）的 zip 条目列表。
 */
internal object WebDavPropfindParser {

    private val HTTP_DATE = DateTimeFormatter.RFC_1123_DATE_TIME

    fun parse(xml: String, baseUrl: String): List<WebDavRemoteEntry> {
        if (xml.isBlank()) return emptyList()
        val entries = ArrayList<WebDavRemoteEntry>()
        var cursor = 0
        while (true) {
            val response = findElement(xml, "response", cursor) ?: break
            cursor = response.end
            val href = elementText(xml, response, "href")?.trim().orEmpty()
            if (href.isEmpty()) continue
            val prop = findProp(xml, response)
            val isCollection = prop
                ?.let { elementInner(xml, it, "resourcetype") }
                ?.let { inner -> innerHasElement(inner, "collection") } == true
            if (isCollection) continue
            val name = WebDavPaths.fileNameFromHref(href)
            if (!name.endsWith(".zip", ignoreCase = true)) continue
            entries.add(
                WebDavRemoteEntry(
                    name = name,
                    url = WebDavPaths.resolveHref(baseUrl, href),
                    sizeBytes = prop?.let { elementText(xml, it, "getcontentlength") }
                        ?.trim()?.toLongOrNull() ?: -1L,
                    lastModified = parseHttpDate(
                        prop?.let { elementText(xml, it, "getlastmodified") }
                    )
                )
            )
        }
        return entries.sortedWith(
            compareByDescending<WebDavRemoteEntry> { it.lastModified }
                .thenByDescending { it.name }
        )
    }

    /** 优先取 200 的 propstat，其次第一个 propstat，最后 response 直属 prop。 */
    private fun findProp(xml: String, response: ElementRange): ElementRange? {
        var best: ElementRange? = null
        var cursor = response.innerStart
        while (true) {
            val propstat = findElement(xml, "propstat", cursor, response.innerEnd) ?: break
            cursor = propstat.end
            val prop = findElement(xml, "prop", propstat.innerStart, propstat.innerEnd)
                ?: continue
            if (best == null) best = prop
            if (elementText(xml, propstat, "status")?.contains(" 200 ") == true) return prop
        }
        return best ?: findElement(xml, "prop", response.innerStart, response.innerEnd)
    }

    private fun parseHttpDate(value: String?): Long {
        if (value.isNullOrBlank()) return -1L
        return runCatching {
            ZonedDateTime.parse(value.trim(), HTTP_DATE).toInstant().toEpochMilli()
        }.getOrDefault(-1L)
    }

    private fun unescape(value: String): String {
        if ('&' !in value) return value
        return value
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&amp;", "&")
    }

    private class ElementRange(val innerStart: Int, val innerEnd: Int, val end: Int)

    private class XmlTag(
        val name: String,
        val start: Int,
        val end: Int,
        val closing: Boolean,
        val selfClosing: Boolean
    )

    private fun nextTag(xml: String, from: Int, to: Int): XmlTag? {
        var i = from
        while (i < to) {
            val lt = xml.indexOf('<', i)
            if (lt < 0 || lt >= to) return null
            if (lt + 1 < to && (xml[lt + 1] == '?' || xml[lt + 1] == '!')) {
                val gt = xml.indexOf('>', lt + 2)
                if (gt < 0 || gt >= to) return null
                i = gt + 1
                continue
            }
            val gt = xml.indexOf('>', lt + 1)
            if (gt < 0 || gt >= to) return null
            val closing = xml[lt + 1] == '/'
            val nameStart = if (closing) lt + 2 else lt + 1
            var nameEnd = nameStart
            while (nameEnd < gt && !xml[nameEnd].isWhitespace() && xml[nameEnd] != '/') nameEnd++
            if (nameEnd == nameStart) {
                i = gt + 1
                continue
            }
            val name = xml.substring(nameStart, nameEnd).substringAfterLast(':').lowercase()
            return XmlTag(
                name = name,
                start = lt,
                end = gt + 1,
                closing = closing,
                selfClosing = gt > lt + 1 && xml[gt - 1] == '/'
            )
        }
        return null
    }

    private fun findElement(
        xml: String,
        name: String,
        from: Int,
        to: Int = xml.length
    ): ElementRange? {
        var i = from
        while (i < to) {
            val tag = nextTag(xml, i, to) ?: return null
            if (tag.closing || tag.name != name) {
                i = tag.end
                continue
            }
            if (tag.selfClosing) return ElementRange(tag.end, tag.end, tag.end)
            var depth = 1
            var j = tag.end
            while (j < to) {
                val inner = nextTag(xml, j, to) ?: break
                if (inner.name == name) {
                    if (inner.closing) {
                        depth--
                        if (depth == 0) return ElementRange(tag.end, inner.start, inner.end)
                    } else if (!inner.selfClosing) {
                        depth++
                    }
                }
                j = inner.end
            }
            return null
        }
        return null
    }

    private fun elementText(xml: String, parent: ElementRange, name: String): String? {
        val range = findElement(xml, name, parent.innerStart, parent.innerEnd) ?: return null
        if (range.innerStart >= range.innerEnd) return ""
        val lt = xml.indexOf('<', range.innerStart)
        val end = if (lt < 0 || lt > range.innerEnd) range.innerEnd else lt
        return unescape(xml.substring(range.innerStart, end))
    }

    private fun elementInner(xml: String, parent: ElementRange, name: String): String? {
        val range = findElement(xml, name, parent.innerStart, parent.innerEnd) ?: return null
        return xml.substring(range.innerStart, range.innerEnd)
    }

    private fun innerHasElement(inner: String, name: String): Boolean {
        var i = 0
        while (i < inner.length) {
            val tag = nextTag(inner, i, inner.length) ?: return false
            if (!tag.closing && tag.name == name) return true
            i = tag.end
        }
        return false
    }
}
