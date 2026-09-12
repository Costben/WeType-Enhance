package com.xposed.wetypehook.wetype.clipboard

import java.io.StringWriter
import java.io.Writer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 备份包内单个条目（纯逻辑，不引用宿主类）。 */
internal sealed class BackupItem {
    abstract val createTime: Long
}

internal data class BackupTextItem(
    val content: String,
    override val createTime: Long
) : BackupItem()

internal data class BackupImageItem(
    val archivePath: String,
    val md5: String,
    override val createTime: Long,
    val sizeBytes: Long
) : BackupItem()

internal data class BackupManifest(
    val formatVersion: Int,
    val exportedAt: Long,
    val textCount: Int,
    val imageCount: Int,
    val skippedRemoteImages: Int
)

/**
 * 剪贴板备份包（zip）的格式定义与编解码。
 *
 * 结构：
 * - `manifest.json`：格式版本、导出时间与计数；
 * - `items.jsonl`：每行一个条目（type 0 文本 / 1 图片）；
 * - `images/<md5>.<ext>`：图片字节，按内容 md5 命名。
 *
 * JSON 为单层对象，手写流式编解码，避免为大包引入额外依赖或整体进内存。
 */
internal object ClipboardBackupArchive {

    const val FORMAT_VERSION = 1
    const val MANIFEST_ENTRY = "manifest.json"
    const val ITEMS_ENTRY = "items.jsonl"
    const val IMAGES_DIR = "images/"
    const val MIME_ZIP = "application/zip"

    private const val GENERATOR = "WeType-Enhance"
    private const val FILE_NAME_PREFIX = "WeTypeEnhance-clipboard-"
    private const val FILE_NAME_SUFFIX = ".zip"

    private val FILE_NAME_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
        .withZone(ZoneId.systemDefault())

    fun buildFileName(timestamp: Long): String =
        FILE_NAME_PREFIX + FILE_NAME_TIME.format(Instant.ofEpochMilli(timestamp)) + FILE_NAME_SUFFIX

    fun imageEntryPath(md5: String, extension: String): String =
        IMAGES_DIR + md5 + "." + extension

    fun manifestJson(manifest: BackupManifest): String =
        StringWriter().also { writeManifest(it, manifest) }.toString()

    fun writeManifest(writer: Writer, manifest: BackupManifest) {
        writer.write("{\"generator\":\"")
        writer.write(GENERATOR)
        writer.write("\",\"formatVersion\":")
        writer.write(manifest.formatVersion.toString())
        writer.write(",\"exportedAt\":")
        writer.write(manifest.exportedAt.toString())
        writer.write(",\"textCount\":")
        writer.write(manifest.textCount.toString())
        writer.write(",\"imageCount\":")
        writer.write(manifest.imageCount.toString())
        writer.write(",\"skippedRemoteImages\":")
        writer.write(manifest.skippedRemoteImages.toString())
        writer.write("}")
    }

    fun parseManifest(json: String): BackupManifest? {
        val map = FlatJson.parseObject(json) ?: return null
        val version = (map["formatVersion"] as? Long)?.toInt() ?: return null
        return BackupManifest(
            formatVersion = version,
            exportedAt = map["exportedAt"] as? Long ?: 0L,
            textCount = (map["textCount"] as? Long)?.toInt() ?: 0,
            imageCount = (map["imageCount"] as? Long)?.toInt() ?: 0,
            skippedRemoteImages = (map["skippedRemoteImages"] as? Long)?.toInt() ?: 0
        )
    }

    fun textItemJson(item: BackupTextItem): String =
        StringWriter().also { writeTextItem(it, item) }.toString()

    /** 流式写出文本条目：大文本不整体复制为转义字符串，避免内存峰值。 */
    fun writeTextItem(writer: Writer, item: BackupTextItem) {
        writer.write("{\"type\":0,\"content\":\"")
        writeJsonEscaped(writer, item.content)
        writer.write("\",\"createTime\":")
        writer.write(item.createTime.toString())
        writer.write("}")
    }

    fun imageItemJson(item: BackupImageItem): String =
        StringWriter().also { writeImageItem(it, item) }.toString()

    fun writeImageItem(writer: Writer, item: BackupImageItem) {
        writer.write("{\"type\":1,\"file\":\"")
        writer.write(item.archivePath)
        writer.write("\",\"md5\":\"")
        writer.write(item.md5)
        writer.write("\",\"createTime\":")
        writer.write(item.createTime.toString())
        writer.write(",\"size\":")
        writer.write(item.sizeBytes.toString())
        writer.write("}")
    }

    fun parseItem(json: String): BackupItem? {
        val map = FlatJson.parseObject(json) ?: return null
        return when ((map["type"] as? Long)?.toInt()) {
            0 -> {
                val content = map["content"] as? String ?: return null
                BackupTextItem(content, map["createTime"] as? Long ?: 0L)
            }

            1 -> {
                val file = map["file"] as? String ?: return null
                val md5 = map["md5"] as? String ?: return null
                BackupImageItem(
                    archivePath = file,
                    md5 = md5,
                    createTime = map["createTime"] as? Long ?: 0L,
                    sizeBytes = map["size"] as? Long ?: 0L
                )
            }

            else -> null
        }
    }

    private fun writeJsonEscaped(writer: Writer, value: String) {
        val buffer = StringBuilder(8192)
        for (c in value) {
            when (c) {
                '"' -> buffer.append("\\\"")
                '\\' -> buffer.append("\\\\")
                '\n' -> buffer.append("\\n")
                '\r' -> buffer.append("\\r")
                '\t' -> buffer.append("\\t")
                '\b' -> buffer.append("\\b")
                '\u000C' -> buffer.append("\\f")
                else -> if (c < ' ') {
                    buffer.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                } else {
                    buffer.append(c)
                }
            }
            if (buffer.length >= 8192) {
                writer.write(buffer.toString())
                buffer.setLength(0)
            }
        }
        if (buffer.isNotEmpty()) writer.write(buffer.toString())
    }

    /**
     * 只解析本格式产出的单层 JSON 对象（字符串/整数/浮点/布尔/null），
     * 遇到嵌套结构、数组或语法错误返回 null。
     */
    private object FlatJson {

        fun parseObject(json: String): Map<String, Any?>? = try {
            val parser = Parser(json)
            val map = parser.readObject()
            parser.ensureFinished()
            map
        } catch (_: JsonFormatException) {
            null
        }

        private class Parser(private val source: String) {
            private var index = 0

            fun readObject(): Map<String, Any?> {
                skipWhitespace()
                expect('{')
                val result = LinkedHashMap<String, Any?>()
                skipWhitespace()
                if (peek() == '}') {
                    index++
                    return result
                }
                while (true) {
                    skipWhitespace()
                    val key = readString()
                    skipWhitespace()
                    expect(':')
                    skipWhitespace()
                    result[key] = readValue()
                    skipWhitespace()
                    when (peek()) {
                        ',' -> index++
                        '}' -> {
                            index++
                            return result
                        }

                        else -> throw JsonFormatException()
                    }
                }
            }

            fun ensureFinished() {
                skipWhitespace()
                if (index != source.length) throw JsonFormatException()
            }

            private fun readValue(): Any? = when (val c = peek()) {
                '"' -> readString()
                '{', '[' -> throw JsonFormatException()
                't' -> {
                    expectLiteral("true")
                    true
                }

                'f' -> {
                    expectLiteral("false")
                    false
                }

                'n' -> {
                    expectLiteral("null")
                    null
                }

                else -> if (c == '-' || c in '0'..'9') readNumber() else throw JsonFormatException()
            }

            private fun readNumber(): Any {
                val start = index
                if (peek() == '-') index++
                while (peek() in '0'..'9') index++
                var isFloating = false
                if (peek() == '.') {
                    isFloating = true
                    index++
                    while (peek() in '0'..'9') index++
                }
                if (peek() == 'e' || peek() == 'E') {
                    isFloating = true
                    index++
                    if (peek() == '+' || peek() == '-') index++
                    while (peek() in '0'..'9') index++
                }
                val token = source.substring(start, index)
                if (token.isEmpty() || token == "-") throw JsonFormatException()
                return if (isFloating) {
                    token.toDouble()
                } else {
                    token.toLongOrNull() ?: throw JsonFormatException()
                }
            }

            private fun readString(): String {
                expect('"')
                val sb = StringBuilder()
                while (true) {
                    if (index >= source.length) throw JsonFormatException()
                    when (val c = source[index++]) {
                        '"' -> return sb.toString()
                        '\\' -> readEscape(sb)
                        else -> if (c < ' ') throw JsonFormatException() else sb.append(c)
                    }
                }
            }

            private fun readEscape(sb: StringBuilder) {
                if (index >= source.length) throw JsonFormatException()
                when (val esc = source[index++]) {
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000C')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'u' -> {
                        if (index + 4 > source.length) throw JsonFormatException()
                        val code = source.substring(index, index + 4).toIntOrNull(16)
                            ?: throw JsonFormatException()
                        sb.append(code.toChar())
                        index += 4
                    }

                    else -> throw JsonFormatException()
                }
            }

            private fun expectLiteral(literal: String) {
                if (!source.startsWith(literal, index)) throw JsonFormatException()
                index += literal.length
            }

            private fun expect(c: Char) {
                if (peek() != c) throw JsonFormatException()
                index++
            }

            private fun peek(): Char = if (index < source.length) source[index] else '\u0000'

            private fun skipWhitespace() {
                while (index < source.length && source[index].isWhitespace()) index++
            }
        }

        private class JsonFormatException : RuntimeException()
    }
}
