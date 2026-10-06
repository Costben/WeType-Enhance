package com.xposed.wetypehook.wetype.voice.shell

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import org.json.JSONArray
import org.json.JSONObject

/**
 * 火山引擎「流式语音识别 2.0」（`bigmodel_async`）的二进制分帧。
 *
 * 协议是从 Eta 3.1.0 的 smali（`rh0` / `ne4` / `lz2` / `s0`）逆出来的，并照 Eta 3.1.0
 * 的真实收发逐字节校验过 —— 位域、gzip、长度字段一律以那次核对为准，不靠印象写。
 *
 * ```
 * 请求帧
 *   byte0  0x11                     version=1, headerSize=1（×4 = 4 字节）
 *   byte1  (msgType << 4) | flags   1=整包请求(JSON)  2=纯音频  flags&2=最后一包
 *   byte2  (serialization << 4) | compression
 *                                  1=JSON / 0=raw；compression=1 表示 payload 是 gzip
 *   byte3  0x00
 *   [flags & 0x1 时] int32 大端 sequence
 *   int32 大端 payload 长度
 *   payload
 * ```
 *
 * 两边都按**无符号**读长度：Java 的 `Int` 是有符号的，用 `ByteArray(len)` 之前必须
 * 先确认它没溢出成负数，否则会抛 `NegativeArraySizeException` 而不是一条能看懂的错误。
 */

/** 帧结构不合法。调用方把这一条连接关掉即可，不影响服务本身。 */
internal class DoubaoProtocolException(message: String) : Exception(message)

internal object DoubaoProtocol {

    const val VERSION = 1
    const val HEADER_BYTES = 4

    const val MSG_FULL_CLIENT_REQUEST = 1
    const val MSG_AUDIO_ONLY_REQUEST = 2
    const val MSG_SERVER_RESPONSE = 9
    const val MSG_SERVER_ERROR = 15

    const val FLAG_POS_SEQUENCE = 0x1
    const val FLAG_LAST = 0x2

    const val COMPRESSION_NONE = 0
    const val COMPRESSION_GZIP = 1

    const val SERIALIZATION_RAW = 0
    const val SERIALIZATION_JSON = 1

    /** 单帧载荷上限。一段 20 秒的 16k/单声道 PCM 也才 640KB。 */
    const val MAX_PAYLOAD_BYTES = 8 * 1024 * 1024

    /** 解一帧。任何越界 / 非法取值都抛 [DoubaoProtocolException]，不做静默兜底。 */
    fun decode(data: ByteArray): DoubaoFrame {
        if (data.size < HEADER_BYTES + 4) {
            throw DoubaoProtocolException("帧太短：${data.size} 字节")
        }
        val byte0 = data[0].toInt() and 0xFF
        val byte1 = data[1].toInt() and 0xFF
        val byte2 = data[2].toInt() and 0xFF
        val byte3 = data[3].toInt() and 0xFF

        val version = byte0 ushr 4
        val headerBytes = (byte0 and 0x0F) * 4
        if (version != VERSION) {
            throw DoubaoProtocolException("协议版本应为 $VERSION，实际 $version")
        }
        if (headerBytes != HEADER_BYTES) {
            throw DoubaoProtocolException("headerSize 应为 $HEADER_BYTES，实际 $headerBytes")
        }
        if (byte3 != 0) throw DoubaoProtocolException("保留字节应为 0，实际 0x%02x".format(byte3))

        val msgType = byte1 ushr 4
        val flags = byte1 and 0x0F
        val serialization = byte2 ushr 4
        val compression = byte2 and 0x0F
        if (compression != COMPRESSION_NONE && compression != COMPRESSION_GZIP) {
            throw DoubaoProtocolException("compression 只认 0/1，实际 $compression")
        }

        var offset = headerBytes
        var sequence: Int? = null
        if (flags and FLAG_POS_SEQUENCE != 0) {
            if (data.size < offset + 4) throw DoubaoProtocolException("声称带 sequence，但长度不够")
            sequence = readInt(data, offset)
            offset += 4
        }
        if (data.size < offset + 4) throw DoubaoProtocolException("长度字段被截断")
        val payloadSize = readInt(data, offset)
        offset += 4
        if (payloadSize < 0) throw DoubaoProtocolException("payload 长度溢出：$payloadSize")
        if (payloadSize > MAX_PAYLOAD_BYTES) {
            throw DoubaoProtocolException("payload 过大：$payloadSize 字节")
        }
        if (data.size != offset + payloadSize) {
            throw DoubaoProtocolException(
                "payload 长度不符：声明 $payloadSize，实际 ${data.size - offset}"
            )
        }

        var payload = data.copyOfRange(offset, data.size)
        if (compression == COMPRESSION_GZIP) payload = gunzip(payload)
        return DoubaoFrame(msgType, flags, serialization, compression, sequence, payload)
    }

    /** 编一帧。服务端回的帧默认 gzip，与 Eta 3.1.0 实际收发里的 `use_gzip=True` 一致。 */
    fun encode(
        msgType: Int,
        flags: Int,
        serialization: Int,
        payload: ByteArray,
        gzip: Boolean = true
    ): ByteArray {
        val body = if (gzip) gzip(payload) else payload
        val compression = if (gzip) COMPRESSION_GZIP else COMPRESSION_NONE
        val out = ByteArray(HEADER_BYTES + 4 + body.size)
        out[0] = ((VERSION shl 4) or 1).toByte()
        out[1] = ((msgType shl 4) or (flags and 0x0F)).toByte()
        out[2] = ((serialization shl 4) or compression).toByte()
        out[3] = 0
        writeInt(out, HEADER_BYTES, body.size)
        System.arraycopy(body, 0, out, HEADER_BYTES + 4, body.size)
        return out
    }

    fun gzip(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(data) }
        return out.toByteArray()
    }

    fun gunzip(data: ByteArray): ByteArray = runCatching {
        GZIPInputStream(ByteArrayInputStream(data)).use { it.readBytes() }
    }.getOrElse {
        throw DoubaoProtocolException("gzip 解压失败：${it.javaClass.simpleName}")
    }

    private fun readInt(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 24) or
            ((data[offset + 1].toInt() and 0xFF) shl 16) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or
            (data[offset + 3].toInt() and 0xFF)

    private fun writeInt(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 24).toByte()
        target[offset + 1] = (value ushr 16).toByte()
        target[offset + 2] = (value ushr 8).toByte()
        target[offset + 3] = value.toByte()
    }
}

/** 解出来的一帧。payload 已经按 compression 还原成明文。 */
internal data class DoubaoFrame(
    val msgType: Int,
    val flags: Int,
    val serialization: Int,
    val compression: Int,
    val sequence: Int?,
    val payload: ByteArray
)

/**
 * 回给 Eta 的 JSON 载荷。字段名照 Eta 3.1.0 真实收发钉死：
 * Eta 只读 `result.text` 与 `result.utterances[].definite`。
 */
internal object DoubaoJson {

    fun result(text: String, definite: Boolean): ByteArray = JSONObject().apply {
        put(
            "result",
            JSONObject().apply {
                put("text", text)
                put(
                    "utterances",
                    JSONArray().put(
                        JSONObject().apply {
                            put("text", text)
                            put("definite", definite)
                        }
                    )
                )
            }
        )
    }.toString().toByteArray(Charsets.UTF_8)

    fun error(code: Int, message: String): ByteArray = JSONObject().apply {
        put("code", code)
        put("message", message)
    }.toString().toByteArray(Charsets.UTF_8)
}
