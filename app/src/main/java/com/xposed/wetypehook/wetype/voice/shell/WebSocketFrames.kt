package com.xposed.wetypehook.wetype.voice.shell

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.security.MessageDigest
import java.util.Base64

/**
 * RFC 6455 里本壳真正用得到的那一小块：握手 accept key、帧编解码、掩码还原、分片重组。
 *
 * 刻意**不引任何 WebSocket 库**：模块里没有 OkHttp / Ktor / Netty，为一个只服务回环的
 * 壳拉一整套网络栈不划算，而这里要用的东西（SHA-1 + Base64、读几个字节、异或）本来
 * 就是几十行。协议细节全集中在这一个文件，单测可以完全不碰 Android 就把它们钉住。
 *
 * 服务端方向：**发的帧一律不掩码、收的帧一律必须掩码**。这条不对称不是可选项 ——
 * RFC 6455 §5.1 要求客户端掩码，服务端收到未掩码的客户端帧必须当作协议错误关连接。
 * 放行未掩码帧会让「客户端其实没按协议写」这种 bug 一直藏着不暴露。
 */

internal object WsOpcode {
    const val CONTINUATION = 0x0
    const val TEXT = 0x1
    const val BINARY = 0x2
    const val CLOSE = 0x8
    const val PING = 0x9
    const val PONG = 0xA

    /** 控制帧（0x8 那一档）不能分片、载荷不得超过 125 字节。 */
    fun isControl(opcode: Int): Boolean = (opcode and 0x8) != 0

    /** 常见的关闭码。 */
    const val CLOSE_NORMAL = 1000
    const val CLOSE_PROTOCOL_ERROR = 1002
    const val CLOSE_TOO_BIG = 1009
    const val CLOSE_TRY_AGAIN_LATER = 1013
}

/** 对端违反协议。调用方收到它就把这一条连接干净关掉，不上升成服务级故障。 */
internal class WebSocketProtocolException(message: String) : Exception(message)

/** 一条**完整**消息（分片已经拼好）。[opcode] 只会是 TEXT / BINARY / CLOSE / PING / PONG。 */
internal data class WebSocketMessage(val opcode: Int, val payload: ByteArray)

internal object WebSocketFrames {

    /** 单条消息（重组后）的上限。Eta 一次推 100ms 音频也才 3200 字节，8MB 是防呆。 */
    const val MAX_MESSAGE_BYTES = 8 * 1024 * 1024

    /** RFC 6455 §1.3 写死的握手拼接串。 */
    private const val HANDSHAKE_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

    /** `Sec-WebSocket-Accept` = base64(SHA1(key + GUID))。 */
    fun acceptKey(clientKey: String): String = Base64.getEncoder().encodeToString(
        MessageDigest.getInstance("SHA-1")
            .digest((clientKey + HANDSHAKE_GUID).toByteArray(Charsets.US_ASCII))
    )

    /**
     * 编一帧。[mask] 只给单测用：服务端发的帧按协议**必须**不掩码，这里默认关掉，
     * 免得有人误开之后线上出现一个不合规的服务端。
     */
    fun encode(
        opcode: Int,
        payload: ByteArray,
        fin: Boolean = true,
        mask: Boolean = false,
        maskKey: ByteArray = ByteArray(4) { MASK_SEED[it] }
    ): ByteArray {
        val length = payload.size
        val lengthBytes = when {
            length < 126 -> 0
            length <= 0xFFFF -> 2
            else -> 8
        }
        val headerSize = 2 + lengthBytes + if (mask) 4 else 0
        val out = ByteArray(headerSize + length)

        out[0] = ((if (fin) 0x80 else 0x00) or (opcode and 0x0F)).toByte()
        out[1] = ((if (mask) 0x80 else 0x00) or
            when (lengthBytes) {
                0 -> length
                2 -> 126
                else -> 127
            }).toByte()

        var cursor = 2
        when (lengthBytes) {
            2 -> {
                out[cursor++] = (length ushr 8).toByte()
                out[cursor++] = length.toByte()
            }

            8 -> {
                var shift = 56
                while (shift >= 0) {
                    out[cursor++] = (length.toLong() ushr shift).toByte()
                    shift -= 8
                }
            }
        }

        if (mask) {
            val key = if (maskKey.size == 4) maskKey else ByteArray(4) { MASK_SEED[it] }
            for (index in 0 until 4) out[cursor++] = key[index]
            for (index in payload.indices) {
                out[cursor + index] = (payload[index].toInt() xor key[index and 3].toInt()).toByte()
            }
        } else {
            System.arraycopy(payload, 0, out, cursor, length)
        }
        return out
    }

    /** `CLOSE` 帧的载荷：2 字节大端状态码 + UTF-8 原因，原因可以没有。 */
    fun closePayload(code: Int, reason: String = ""): ByteArray {
        val reasonBytes = reason.toByteArray(Charsets.UTF_8)
        val out = ByteArray(2 + reasonBytes.size)
        out[0] = (code ushr 8).toByte()
        out[1] = code.toByte()
        System.arraycopy(reasonBytes, 0, out, 2, reasonBytes.size)
        return out
    }

    /** 固定的掩码种子。只在 [encode] 的单测路径上用到，线格式本身不依赖它。 */
    private val MASK_SEED = byteArrayOf(0x37, 0x11, 0x5A, 0x0C)
}

/**
 * 从输入流上一条条读消息。**会阻塞**，调用方自己决定线程。
 *
 * 分片在这里拼完再交出去：调用方只看到完整消息，不用关心 continuation。
 * `read()` 返回 null 表示对端正常关闭（读到流尾）。
 */
internal class WebSocketFrameReader(private val input: InputStream) {

    private var fragmentOpcode = -1
    private var fragment = ByteArrayOutputStream()

    fun read(): WebSocketMessage? {
        while (true) {
            val frame = readFrame() ?: return null

            if (WsOpcode.isControl(frame.opcode)) {
                if (!frame.fin) throw WebSocketProtocolException("控制帧不能分片")
                return WebSocketMessage(frame.opcode, frame.payload)
            }

            when (frame.opcode) {
                WsOpcode.TEXT, WsOpcode.BINARY -> {
                    if (fragmentOpcode != -1) {
                        throw WebSocketProtocolException("上一批分片还没拼完，又来了一条新消息")
                    }
                    if (frame.fin) return WebSocketMessage(frame.opcode, frame.payload)
                    fragmentOpcode = frame.opcode
                    fragment = ByteArrayOutputStream()
                    appendFragment(frame.payload)
                }

                WsOpcode.CONTINUATION -> {
                    if (fragmentOpcode == -1) {
                        throw WebSocketProtocolException("没有可分片的消息，却收到了 continuation")
                    }
                    appendFragment(frame.payload)
                    if (frame.fin) {
                        val message = WebSocketMessage(fragmentOpcode, fragment.toByteArray())
                        fragmentOpcode = -1
                        fragment = ByteArrayOutputStream()
                        return message
                    }
                }

                else -> throw WebSocketProtocolException("未知 opcode ${frame.opcode}")
            }
        }
    }

    private fun appendFragment(payload: ByteArray) {
        if (fragment.size() + payload.size > WebSocketFrames.MAX_MESSAGE_BYTES) {
            // 直接报错而不是继续吞：拼出一个被截断的「完整消息」交给上层比断开更危险。
            throw WebSocketProtocolException("分片消息超过 ${WebSocketFrames.MAX_MESSAGE_BYTES} 字节")
        }
        fragment.write(payload, 0, payload.size)
    }

    private class RawFrame(val fin: Boolean, val opcode: Int, val payload: ByteArray)

    private fun readFrame(): RawFrame? {
        val byte0 = input.read()
        if (byte0 < 0) return null
        val byte1 = input.read()
        if (byte1 < 0) throw EOFException("帧头被截断")

        if (byte0 and 0x70 != 0) throw WebSocketProtocolException("RSV 位非零：本服务不支持任何扩展")
        val fin = byte0 and 0x80 != 0
        val opcode = byte0 and 0x0F
        val masked = byte1 and 0x80 != 0
        var length = (byte1 and 0x7F).toLong()

        when (length) {
            126L -> length = readLength(2)
            127L -> length = readLength(8)
        }
        if (length > WebSocketFrames.MAX_MESSAGE_BYTES) {
            throw WebSocketProtocolException("帧过大：$length 字节")
        }
        if (WsOpcode.isControl(opcode) && (length > 125 || !fin)) {
            throw WebSocketProtocolException("非法控制帧：len=$length fin=$fin")
        }
        if (!masked) throw WebSocketProtocolException("客户端帧必须掩码（RFC 6455 §5.1）")

        val maskKey = readFully(4)
        val payload = readFully(length.toInt())
        for (index in payload.indices) {
            payload[index] = (payload[index].toInt() xor maskKey[index and 3].toInt()).toByte()
        }
        return RawFrame(fin, opcode, payload)
    }

    /** 大端无符号整数，最多 8 字节。 */
    private fun readLength(size: Int): Long {
        var value = 0L
        repeat(size) { value = (value shl 8) or input.read().toLong() }
        if (value < 0) throw EOFException("长度字段被截断")
        return value
    }

    private fun readFully(size: Int): ByteArray {
        val bytes = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val read = input.read(bytes, offset, size - offset)
            if (read < 0) throw EOFException("载荷被截断：还差 ${size - offset} 字节")
            offset += read
        }
        return bytes
    }
}
