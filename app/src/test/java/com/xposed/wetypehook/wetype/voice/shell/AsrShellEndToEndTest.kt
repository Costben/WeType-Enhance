package com.xposed.wetypehook.wetype.voice.shell

import com.xposed.wetypehook.wetype.settings.VoiceTuning
import com.xposed.wetypehook.wetype.voice.WeTypeVoiceProtocol
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 壳的端到端契约测试：真起 [AsrShellServer]，用一个假回环桥替掉微信输入法。
 *
 * 单测里 `android.util.Log` 本来会抛 "not mocked"（`build.gradle.kts` 没开
 * `unitTests.isReturnDefaultValues`），这里靠测试源集里的 `android/util/Log.java` 补一个
 * 能打印的实现，才能把 `AsrShellServer` / `AsrShellSession` 这些碰 Log 的类也跑起来。
 * 桩只把「抛异常」换成「打印」，不会让既有测试从通过变失败。
 *
 * 三条链路各钉一段：
 * - 千问：`session.updated` 必须先于音频回；PCM 逐字节到桥；`completed` 用的是收尾阶段
 *   才到的那句转录（"你好世界"），不是中途的中间结果（"你好"）。
 * - 豆包：握手回非最终 msgType 9；中间结果 `definite=false`；最后一包回
 *   `flags=0x2` + `definite=true`。
 * - 单会话锁：第二路拿到协议错误 + `CLOSE(1013)`，第一路不受影响。
 */
class AsrShellEndToEndTest {

    private val loopback: InetAddress = InetAddress.getByName("127.0.0.1")

    private fun freePort(): Int = ServerSocket(0, 4, loopback).use { it.localPort }

    /** 壳只跑明文，客户端直接裸连即可。 */
    private fun plainSocket(port: Int): Socket = Socket(loopback, port)

    private fun frameTranscript(output: DataOutputStream, text: String, end: Boolean) {
        val payload = text.toByteArray(Charsets.UTF_8)
        output.writeByte(WeTypeVoiceProtocol.FRAME_TRANSCRIPT)
        output.writeByte(if (end) 1 else 0)
        output.writeInt(payload.size)
        output.write(payload)
        output.flush()
    }

    @Test
    fun qwenSessionRunsEndToEnd() {
        val bridge = ServerSocket(0, 4, loopback)
        val receivedPcm = ByteArrayOutputStream()
        val bridgeThread = Thread {
            runCatching {
                bridge.accept().use { socket ->
                    val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
                    val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
                    while (true) {
                        when (input.readUnsignedByte()) {
                            WeTypeVoiceProtocol.FRAME_PCM -> {
                                val length = input.readInt()
                                val bytes = ByteArray(length)
                                input.readFully(bytes)
                                receivedPcm.write(bytes)
                                frameTranscript(output, "你好", end = false)
                            }

                            WeTypeVoiceProtocol.FRAME_EOS -> {
                                frameTranscript(output, "你好世界", end = true)
                                return@use
                            }

                            else -> return@use
                        }
                    }
                }
            }
        }.apply { isDaemon = true; start() }

        val shellPort = freePort()
        val server = AsrShellServer(
            port = shellPort,
            allowLan = false,
            tuning = { VoiceTuning(1000, 5000, 500, 200, 3000) },
            bridgePort = bridge.localPort
        )
        assertTrue("壳没能起监听", server.start())

        try {
            plainSocket(shellPort).use { socket ->
                val out = BufferedOutputStream(socket.getOutputStream())
                val input = BufferedInputStream(socket.getInputStream())
                handshake(out, input, AsrShellServer.PATH_QWEN)

                val reader = { readServerFrame(input) }
                val send = { json: String ->
                    out.write(WebSocketFrames.encode(WsOpcode.TEXT, json.toByteArray(), mask = true))
                    out.flush()
                }
                val next = { JSONObject(String(reader().second, Charsets.UTF_8)) }

                send("""{"type":"session.update","session":{"sample_rate":16000}}""")
                assertEquals("session.updated", next().getString("type"))

                val pcm = ByteArray(3200) { 1 }
                send(
                    JSONObject()
                        .put("type", "input_audio_buffer.append")
                        .put("audio", Base64.getEncoder().encodeToString(pcm))
                        .toString()
                )
                val partial = next()
                assertEquals(
                    "conversation.item.input_audio_transcription.text",
                    partial.getString("type")
                )
                assertEquals("你好", partial.getString("text"))
                assertEquals("", partial.getString("stash"))

                send("""{"type":"session.finish"}""")
                val completed = next()
                assertEquals(
                    "conversation.item.input_audio_transcription.completed",
                    completed.getString("type")
                )
                assertEquals("你好世界", completed.getString("transcript"))
                assertEquals("session.finished", next().getString("type"))
                assertEquals(WsOpcode.CLOSE, reader().first)

                assertArrayEquals("推给桥的 PCM 被改动了", pcm, receivedPcm.toByteArray())
            }
        } finally {
            server.stop()
            runCatching { bridge.close() }
        }
    }

    @Test
    fun doubaoSessionRunsEndToEnd() {
        val bridge = ServerSocket(0, 4, loopback)
        val receivedPcm = ByteArrayOutputStream()
        val bridgeThread = Thread {
            runCatching {
                bridge.accept().use { socket ->
                    val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
                    val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
                    while (true) {
                        when (input.readUnsignedByte()) {
                            WeTypeVoiceProtocol.FRAME_PCM -> {
                                val length = input.readInt()
                                val bytes = ByteArray(length)
                                input.readFully(bytes)
                                receivedPcm.write(bytes)
                                frameTranscript(output, "你好", end = false)
                            }

                            WeTypeVoiceProtocol.FRAME_EOS -> {
                                frameTranscript(output, "你好世界", end = true)
                                return@use
                            }

                            else -> return@use
                        }
                    }
                }
            }
        }.apply { isDaemon = true; start() }

        val shellPort = freePort()
        val server = AsrShellServer(
            port = shellPort,
            allowLan = false,
            tuning = { VoiceTuning(1000, 5000, 500, 200, 3000) },
            bridgePort = bridge.localPort
        )
        assertTrue("壳没能起监听", server.start())

        try {
            plainSocket(shellPort).use { socket ->
                val out = BufferedOutputStream(socket.getOutputStream())
                val input = BufferedInputStream(socket.getInputStream())
                handshake(out, input, AsrShellServer.PATH_DOUBAO)
                val sendBinary = { bytes: ByteArray ->
                    out.write(WebSocketFrames.encode(WsOpcode.BINARY, bytes, mask = true))
                    out.flush()
                }

                sendBinary(
                    DoubaoProtocol.encode(
                        DoubaoProtocol.MSG_FULL_CLIENT_REQUEST,
                        DoubaoProtocol.FLAG_LAST,
                        DoubaoProtocol.SERIALIZATION_JSON,
                        """{"user":{"uid":"u"},"audio":{"format":"pcm"},"request":{"model_name":"m"}}"""
                            .toByteArray(),
                        gzip = true
                    )
                )
                val ackFrame = DoubaoProtocol.decode(readServerFrame(input).second)
                assertEquals(DoubaoProtocol.MSG_SERVER_RESPONSE, ackFrame.msgType)
                assertEquals(0, ackFrame.flags)

                val pcm = ByteArray(3200) { 2 }
                sendBinary(
                    DoubaoProtocol.encode(
                        DoubaoProtocol.MSG_AUDIO_ONLY_REQUEST, 0,
                        DoubaoProtocol.SERIALIZATION_RAW, pcm, gzip = true
                    )
                )
                val partial = JSONObject(
                    String(DoubaoProtocol.decode(readServerFrame(input).second).payload, Charsets.UTF_8)
                ).getJSONObject("result")
                assertEquals("你好", partial.getString("text"))
                assertFalse(partial.getJSONArray("utterances").getJSONObject(0).getBoolean("definite"))

                sendBinary(
                    DoubaoProtocol.encode(
                        DoubaoProtocol.MSG_AUDIO_ONLY_REQUEST, DoubaoProtocol.FLAG_LAST,
                        DoubaoProtocol.SERIALIZATION_RAW, ByteArray(160), gzip = true
                    )
                )
                val finalFrame = DoubaoProtocol.decode(readServerFrame(input).second)
                assertEquals(DoubaoProtocol.FLAG_LAST, finalFrame.flags)
                val finalResult = JSONObject(String(finalFrame.payload, Charsets.UTF_8))
                    .getJSONObject("result")
                assertEquals("你好世界", finalResult.getString("text"))
                assertTrue(finalResult.getJSONArray("utterances").getJSONObject(0).getBoolean("definite"))
                assertEquals(WsOpcode.CLOSE, readServerFrame(input).first)

                assertEquals(3200 + 160, receivedPcm.size())
            }
        } finally {
            server.stop()
            runCatching { bridge.close() }
        }
    }

    @Test
    fun secondSessionIsRejectedWhileTheFirstIsRunning() {
        val bridge = ServerSocket(0, 4, loopback)
        Thread {
            runCatching { bridge.accept().use { Thread.sleep(5000) } }
        }.apply { isDaemon = true; start() }

        val shellPort = freePort()
        val server = AsrShellServer(
            port = shellPort,
            allowLan = false,
            tuning = { VoiceTuning(1000, 5000, 500, 200, 3000) },
            bridgePort = bridge.localPort
        )
        assertTrue(server.start())

        try {
            plainSocket(shellPort).use { first ->
                val firstOut = BufferedOutputStream(first.getOutputStream())
                val firstIn = BufferedInputStream(first.getInputStream())
                handshake(firstOut, firstIn, AsrShellServer.PATH_QWEN)
                firstOut.write(
                    WebSocketFrames.encode(
                        WsOpcode.TEXT,
                        """{"type":"session.update","session":{}}""".toByteArray(),
                        mask = true
                    )
                )
                firstOut.flush()
                val updated = JSONObject(String(readServerFrame(firstIn).second, Charsets.UTF_8))
                assertEquals("session.updated", updated.getString("type"))

                plainSocket(shellPort).use { second ->
                    val secondOut = BufferedOutputStream(second.getOutputStream())
                    val secondIn = BufferedInputStream(second.getInputStream())
                    handshake(secondOut, secondIn, AsrShellServer.PATH_QWEN)
                    secondOut.write(
                        WebSocketFrames.encode(
                            WsOpcode.TEXT,
                            """{"type":"session.update","session":{}}""".toByteArray(),
                            mask = true
                        )
                    )
                    secondOut.flush()

                    val error = JSONObject(String(readServerFrame(secondIn).second, Charsets.UTF_8))
                    assertEquals("error", error.getString("type"))
                    val close = readServerFrame(secondIn)
                    assertEquals(WsOpcode.CLOSE, close.first)
                    val code = ((close.second[0].toInt() and 0xFF) shl 8) or
                        (close.second[1].toInt() and 0xFF)
                    assertEquals(WsOpcode.CLOSE_TRY_AGAIN_LATER, code)
                }
            }
        } finally {
            server.stop()
            runCatching { bridge.close() }
        }
    }

    private fun handshake(out: BufferedOutputStream, input: BufferedInputStream, path: String) {
        val key = Base64.getEncoder().encodeToString(ByteArray(16) { it.toByte() })
        out.write(
            (
                "GET $path HTTP/1.1\r\n" +
                    "Host: 127.0.0.1\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Key: $key\r\n" +
                    "Sec-WebSocket-Version: 13\r\n\r\n"
                ).toByteArray()
        )
        out.flush()
        val head = readHttpHead(input)
        assertTrue("握手没成功：\n$head", head.startsWith("HTTP/1.1 101"))
    }

    /** 服务端帧按协议**不掩码**，所以不能用服务端那个 reader，这里写个最小的。 */
    private fun readServerFrame(input: BufferedInputStream): Pair<Int, ByteArray> {
        val byte0 = readByte(input)
        val byte1 = readByte(input)
        assertEquals("服务端帧不能掩码", 0, byte1 and 0x80)
        val opcode = byte0 and 0x0F
        var length = byte1 and 0x7F
        if (length == 126) {
            length = (readByte(input) shl 8) or readByte(input)
        } else if (length == 127) {
            length = 0
            repeat(8) { length = (length shl 8) or readByte(input) }
        }
        val payload = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(payload, offset, length - offset)
            assertTrue("载荷被截断", read > 0)
            offset += read
        }
        return opcode to payload
    }

    private fun readByte(input: BufferedInputStream): Int {
        val value = input.read()
        assertTrue("流提前结束", value >= 0)
        return value
    }

    private fun readHttpHead(input: BufferedInputStream): String {
        val builder = StringBuilder()
        while (true) {
            val line = readLine(input) ?: break
            builder.append(line).append('\n')
            if (line.isEmpty()) break
        }
        return builder.toString()
    }

    private fun readLine(input: BufferedInputStream): String? {
        val buffer = ByteArrayOutputStream()
        while (true) {
            val byte = input.read()
            if (byte < 0) return if (buffer.size() == 0) null else buffer.toString("ISO-8859-1")
            if (byte == '\n'.code) return buffer.toString("ISO-8859-1").trimEnd('\r')
            buffer.write(byte)
        }
    }
}
