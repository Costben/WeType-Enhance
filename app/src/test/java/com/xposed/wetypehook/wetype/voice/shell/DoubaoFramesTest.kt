package com.xposed.wetypehook.wetype.voice.shell

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DoubaoFramesTest {

    private val payload = """{"audio":{"format":"pcm"}}""".toByteArray(Charsets.UTF_8)

    @Test
    fun `gzip frame round trips`() {
        val bytes = DoubaoProtocol.encode(
            msgType = DoubaoProtocol.MSG_FULL_CLIENT_REQUEST,
            flags = DoubaoProtocol.FLAG_LAST,
            serialization = DoubaoProtocol.SERIALIZATION_JSON,
            payload = payload,
            gzip = true
        )
        val frame = DoubaoProtocol.decode(bytes)
        assertEquals(DoubaoProtocol.MSG_FULL_CLIENT_REQUEST, frame.msgType)
        assertEquals(DoubaoProtocol.FLAG_LAST, frame.flags)
        assertEquals(DoubaoProtocol.SERIALIZATION_JSON, frame.serialization)
        assertEquals(DoubaoProtocol.COMPRESSION_GZIP, frame.compression)
        assertNull(frame.sequence)
        assertArrayEquals(payload, frame.payload)
    }

    @Test
    fun `raw frame round trips`() {
        val bytes = DoubaoProtocol.encode(
            msgType = DoubaoProtocol.MSG_AUDIO_ONLY_REQUEST,
            flags = DoubaoProtocol.FLAG_LAST,
            serialization = DoubaoProtocol.SERIALIZATION_RAW,
            payload = payload,
            gzip = false
        )
        val frame = DoubaoProtocol.decode(bytes)
        assertEquals(DoubaoProtocol.MSG_AUDIO_ONLY_REQUEST, frame.msgType)
        assertEquals(DoubaoProtocol.COMPRESSION_NONE, frame.compression)
        assertEquals(DoubaoProtocol.SERIALIZATION_RAW, frame.serialization)
        assertArrayEquals(payload, frame.payload)
    }

    @Test
    fun `header bytes follow the reverse engineered layout`() {
        val bytes = DoubaoProtocol.encode(
            msgType = DoubaoProtocol.MSG_SERVER_RESPONSE,
            flags = DoubaoProtocol.FLAG_LAST,
            serialization = DoubaoProtocol.SERIALIZATION_JSON,
            payload = payload,
            gzip = true
        )
        assertEquals(0x11, bytes[0].toInt() and 0xFF)
        assertEquals(
            (DoubaoProtocol.MSG_SERVER_RESPONSE shl 4) or DoubaoProtocol.FLAG_LAST,
            bytes[1].toInt() and 0xFF
        )
        assertEquals(
            (DoubaoProtocol.SERIALIZATION_JSON shl 4) or DoubaoProtocol.COMPRESSION_GZIP,
            bytes[2].toInt() and 0xFF
        )
        assertEquals(0x00, bytes[3].toInt() and 0xFF)
        assertEquals(
            DoubaoProtocol.gzip(payload).size,
            readInt(bytes, DoubaoProtocol.HEADER_BYTES)
        )
    }

    @Test
    fun `payload length is big endian and excludes the header`() {
        val body = DoubaoProtocol.gzip(payload)
        assertEquals(4 + 4 + body.size, DoubaoProtocol.encode(
            DoubaoProtocol.MSG_SERVER_RESPONSE,
            DoubaoProtocol.FLAG_LAST,
            DoubaoProtocol.SERIALIZATION_JSON,
            payload,
            gzip = true
        ).size)
    }

    @Test
    fun `sequence field is read when the flag is set`() {
        val body = DoubaoProtocol.gzip(payload)
        val out = ByteArray(DoubaoProtocol.HEADER_BYTES + 4 + 4 + body.size)
        out[0] = 0x11
        out[1] = ((DoubaoProtocol.MSG_AUDIO_ONLY_REQUEST shl 4) or DoubaoProtocol.FLAG_POS_SEQUENCE).toByte()
        out[2] = (DoubaoProtocol.SERIALIZATION_RAW shl 4 or DoubaoProtocol.COMPRESSION_GZIP).toByte()
        out[3] = 0
        writeInt(out, DoubaoProtocol.HEADER_BYTES, 42)
        writeInt(out, DoubaoProtocol.HEADER_BYTES + 4, body.size)
        System.arraycopy(body, 0, out, DoubaoProtocol.HEADER_BYTES + 8, body.size)

        val frame = DoubaoProtocol.decode(out)
        assertEquals(42, frame.sequence)
        assertArrayEquals(payload, frame.payload)
    }

    @Test
    fun `wrong version is rejected`() {
        val bytes = DoubaoProtocol.encode(
            DoubaoProtocol.MSG_SERVER_RESPONSE,
            DoubaoProtocol.FLAG_LAST,
            DoubaoProtocol.SERIALIZATION_JSON,
            payload
        )
        bytes[0] = 0x21
        expectProtocolError { DoubaoProtocol.decode(bytes) }
    }

    @Test
    fun `wrong header size is rejected`() {
        val bytes = DoubaoProtocol.encode(
            DoubaoProtocol.MSG_SERVER_RESPONSE,
            DoubaoProtocol.FLAG_LAST,
            DoubaoProtocol.SERIALIZATION_JSON,
            payload
        )
        bytes[0] = 0x12
        expectProtocolError { DoubaoProtocol.decode(bytes) }
    }

    @Test
    fun `non zero reserved byte is rejected`() {
        val bytes = DoubaoProtocol.encode(
            DoubaoProtocol.MSG_SERVER_RESPONSE,
            DoubaoProtocol.FLAG_LAST,
            DoubaoProtocol.SERIALIZATION_JSON,
            payload
        )
        bytes[3] = 1
        expectProtocolError { DoubaoProtocol.decode(bytes) }
    }

    @Test
    fun `declared length that does not match the buffer is rejected`() {
        val bytes = DoubaoProtocol.encode(
            DoubaoProtocol.MSG_SERVER_RESPONSE,
            DoubaoProtocol.FLAG_LAST,
            DoubaoProtocol.SERIALIZATION_JSON,
            payload
        )
        val tooLong = bytes.copyOf(bytes.size + 1)
        expectProtocolError { DoubaoProtocol.decode(tooLong) }
    }

    @Test
    fun `truncated frame is rejected`() {
        expectProtocolError { DoubaoProtocol.decode(ByteArray(6)) }
    }

    @Test
    fun `declared gzip that is not gzip is rejected`() {
        val body = payload
        val out = ByteArray(DoubaoProtocol.HEADER_BYTES + 4 + body.size)
        out[0] = 0x11
        out[1] = ((DoubaoProtocol.MSG_SERVER_RESPONSE shl 4) or DoubaoProtocol.FLAG_LAST).toByte()
        out[2] = (DoubaoProtocol.SERIALIZATION_JSON shl 4 or DoubaoProtocol.COMPRESSION_GZIP).toByte()
        out[3] = 0
        writeInt(out, DoubaoProtocol.HEADER_BYTES, body.size)
        System.arraycopy(body, 0, out, DoubaoProtocol.HEADER_BYTES + 4, body.size)
        expectProtocolError { DoubaoProtocol.decode(out) }
    }

    @Test
    fun `result json exposes text and definite`() {
        val json = JSONObject(String(DoubaoJson.result("识别结果", definite = true), Charsets.UTF_8))
        assertEquals("识别结果", json.getJSONObject("result").getString("text"))
        val utterances = json.getJSONObject("result").getJSONArray("utterances")
        assertTrue(utterances.getJSONObject(0).getBoolean("definite"))
    }

    /**
     * 互操作锚点：下面三段字节由一次对 Eta 3.1.0 真实收发的逐字节核对产出（含 Python
     * 版 gzip 的 mtime），这里必须能解出来。两边各自单测只能证明「自己跟自己一致」，
     * 这条才钉住「壳与 Eta 实际收发的线格式」。 */
    @Test
    fun `frames produced by the reference python server decode`() {
        val result = DoubaoProtocol.decode(
            hex(
                "119011000000004f1f8b0800e266c36a02ffab562a4a2d2ecd2951b252a8562a49ad003194129392957414944a4b4a528b12f392538b8182d118d229a96999799925a940a1b4c49ce2d4dad8da5a0015c7b6764f000000"
            )
        )
        assertEquals(DoubaoProtocol.MSG_SERVER_RESPONSE, result.msgType)
        assertEquals(0, result.flags)
        val resultJson = JSONObject(String(result.payload, Charsets.UTF_8))
        assertEquals("abc", resultJson.getJSONObject("result").getString("text"))

        val error = DoubaoProtocol.decode(
            hex("11f01100000000331f8b0800e266c36a02ffab564ace4f4955b25230343034d65150ca4d2d2e4e4c070928259516572ad50200f364ecf921000000")
        )
        assertEquals(DoubaoProtocol.MSG_SERVER_ERROR, error.msgType)
        val errorJson = JSONObject(String(error.payload, Charsets.UTF_8))
        assertEquals(1013, errorJson.getInt("code"))
        assertEquals("busy", errorJson.getString("message"))

        val audio = DoubaoProtocol.decode(
            hex("1121010000000007000000181f8b0800e266c36a02ff636462660100cdfb3cb604000000")
        )
        assertEquals(DoubaoProtocol.MSG_AUDIO_ONLY_REQUEST, audio.msgType)
        assertEquals(DoubaoProtocol.FLAG_POS_SEQUENCE, audio.flags)
        assertEquals(7, audio.sequence)
        assertEquals(4, audio.payload.size)
    }

    private fun hex(value: String): ByteArray = ByteArray(value.length / 2) {
        ((Character.digit(value[it * 2], 16) shl 4) + Character.digit(value[it * 2 + 1], 16)).toByte()
    }

    @Test
    fun `error json exposes code and message`() {
        val json = JSONObject(String(DoubaoJson.error(45000001, "参数错误"), Charsets.UTF_8))
        assertEquals(45000001, json.getInt("code"))
        assertEquals("参数错误", json.getString("message"))
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

    private fun expectProtocolError(block: () -> Unit) {
        try {
            block()
            fail("应当抛 DoubaoProtocolException")
        } catch (expected: DoubaoProtocolException) {
            // 预期路径。
        }
    }
}
