package com.xposed.wetypehook.wetype.voice.shell

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WebSocketFramesTest {

    private fun concat(vararg chunks: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        chunks.forEach { out.write(it) }
        return out.toByteArray()
    }

    private fun readerOf(vararg chunks: ByteArray): WebSocketFrameReader =
        WebSocketFrameReader(ByteArrayInputStream(concat(*chunks)))

    private fun clientFrame(
        opcode: Int,
        payload: ByteArray,
        fin: Boolean = true
    ): ByteArray = WebSocketFrames.encode(opcode, payload, fin = fin, mask = true)

    @Test
    fun `accept key matches rfc 6455 sample`() {
        assertEquals(
            "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=",
            WebSocketFrames.acceptKey("dGhlIHNhbXBsZSBub25jZQ==")
        )
    }

    @Test
    fun `server frames are unmasked by default`() {
        val frame = WebSocketFrames.encode(WsOpcode.TEXT, "hi".toByteArray())
        assertEquals(0x81, frame[0].toInt() and 0xFF)
        assertEquals(0x00, frame[1].toInt() and 0x80)
    }

    @Test
    fun `text frame round trips through the reader`() {
        val payload = "你好，世界".toByteArray(Charsets.UTF_8)
        val message = readerOf(clientFrame(WsOpcode.TEXT, payload)).read()!!
        assertEquals(WsOpcode.TEXT, message.opcode)
        assertArrayEquals(payload, message.payload)
    }

    @Test
    fun `binary frame round trips with the 16 bit length form`() {
        val payload = ByteArray(200) { it.toByte() }
        val frame = clientFrame(WsOpcode.BINARY, payload)
        assertEquals(126, frame[1].toInt() and 0x7F)
        val message = readerOf(frame).read()!!
        assertEquals(WsOpcode.BINARY, message.opcode)
        assertArrayEquals(payload, message.payload)
    }

    @Test
    fun `binary frame round trips with the 64 bit length form`() {
        val payload = ByteArray(70_000) { (it and 0xFF).toByte() }
        val frame = clientFrame(WsOpcode.BINARY, payload)
        assertEquals(127, frame[1].toInt() and 0x7F)
        val message = readerOf(frame).read()!!
        assertArrayEquals(payload, message.payload)
    }

    @Test
    fun `masked payload is unmasked back to the original bytes`() {
        val payload = "masked".toByteArray(Charsets.UTF_8)
        val frame = clientFrame(WsOpcode.TEXT, payload)
        assertTrue("客户端帧必须带掩码位", frame[1].toInt() and 0x80 != 0)
        assertArrayEquals(payload, readerOf(frame).read()!!.payload)
    }

    @Test
    fun `fragmented message is reassembled before it is handed out`() {
        val bytes = concat(
            clientFrame(WsOpcode.TEXT, "前半".toByteArray(Charsets.UTF_8), fin = false),
            clientFrame(WsOpcode.CONTINUATION, "后半".toByteArray(Charsets.UTF_8))
        )
        val message = readerOf(bytes).read()!!
        assertEquals(WsOpcode.TEXT, message.opcode)
        assertArrayEquals("前半后半".toByteArray(Charsets.UTF_8), message.payload)
    }

    @Test
    fun `three way fragmentation keeps the original opcode`() {
        val bytes = concat(
            clientFrame(WsOpcode.BINARY, byteArrayOf(1), fin = false),
            clientFrame(WsOpcode.CONTINUATION, byteArrayOf(2), fin = false),
            clientFrame(WsOpcode.CONTINUATION, byteArrayOf(3))
        )
        val message = readerOf(bytes).read()!!
        assertEquals(WsOpcode.BINARY, message.opcode)
        assertArrayEquals(byteArrayOf(1, 2, 3), message.payload)
    }

    @Test
    fun `control frames are returned as they are`() {
        val reader = readerOf(
            clientFrame(WsOpcode.PING, "ping".toByteArray()),
            clientFrame(WsOpcode.PONG, "pong".toByteArray()),
            clientFrame(WsOpcode.CLOSE, WebSocketFrames.closePayload(WsOpcode.CLOSE_NORMAL, "bye"))
        )
        assertEquals(WsOpcode.PING, reader.read()!!.opcode)
        assertEquals(WsOpcode.PONG, reader.read()!!.opcode)
        assertEquals(WsOpcode.CLOSE, reader.read()!!.opcode)
    }

    @Test
    fun `close payload carries the code big endian plus reason`() {
        val payload = WebSocketFrames.closePayload(WsOpcode.CLOSE_PROTOCOL_ERROR, "协议错误")
        assertEquals(1002, ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF))
        assertEquals("协议错误", String(payload, 2, payload.size - 2, Charsets.UTF_8))
    }

    @Test
    fun `close payload without a reason is just the code`() {
        val payload = WebSocketFrames.closePayload(WsOpcode.CLOSE_NORMAL)
        assertArrayEquals(byteArrayOf(0x03, (1000 and 0xFF).toByte()), payload)
    }

    @Test
    fun `stream end reads as null`() {
        assertNull(WebSocketFrameReader(ByteArrayInputStream(ByteArray(0))).read())
    }

    @Test
    fun `unmasked client frame is a protocol error`() {
        val frame = WebSocketFrames.encode(WsOpcode.TEXT, "x".toByteArray(), mask = false)
        expectProtocolError { readerOf(frame).read() }
    }

    @Test
    fun `fragmented control frame is a protocol error`() {
        val frame = clientFrame(WsOpcode.PING, "x".toByteArray(), fin = false)
        expectProtocolError { readerOf(frame).read() }
    }

    @Test
    fun `oversized control frame is a protocol error`() {
        val frame = clientFrame(WsOpcode.PING, ByteArray(126))
        expectProtocolError { readerOf(frame).read() }
    }

    @Test
    fun `continuation without a start is a protocol error`() {
        val frame = clientFrame(WsOpcode.CONTINUATION, "x".toByteArray())
        expectProtocolError { readerOf(frame).read() }
    }

    @Test
    fun `new message before the previous fragmentation finished is a protocol error`() {
        val bytes = concat(
            clientFrame(WsOpcode.TEXT, "a".toByteArray(), fin = false),
            clientFrame(WsOpcode.TEXT, "b".toByteArray())
        )
        expectProtocolError { readerOf(bytes).read() }
    }

    @Test
    fun `reserved bits are a protocol error`() {
        val frame = clientFrame(WsOpcode.TEXT, "x".toByteArray())
        frame[0] = (frame[0].toInt() or 0x40).toByte()
        expectProtocolError { readerOf(frame).read() }
    }

    private fun expectProtocolError(block: () -> Unit) {
        try {
            block()
            fail("应当抛 WebSocketProtocolException")
        } catch (expected: WebSocketProtocolException) {
            // 预期路径。
        }
    }
}
