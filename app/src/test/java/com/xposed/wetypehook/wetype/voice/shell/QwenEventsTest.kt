package com.xposed.wetypehook.wetype.voice.shell

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QwenEventsTest {

    private fun parse(raw: String): JSONObject = JSONObject(raw)

    @Test
    fun `session updated echoes the client session and carries an event id`() {
        val session = JSONObject().put("input_audio_format", "pcm16")
        val json = parse(QwenEvents.sessionUpdated(session))
        assertEquals(QwenEvents.TYPE_SESSION_UPDATED, json.getString("type"))
        assertTrue(json.getString("event_id").isNotEmpty())
        assertEquals("pcm16", json.getJSONObject("session").getString("input_audio_format"))
    }

    @Test
    fun `session updated falls back to an empty session object`() {
        val json = parse(QwenEvents.sessionUpdated(null))
        assertEquals(QwenEvents.TYPE_SESSION_UPDATED, json.getString("type"))
        assertEquals(0, json.getJSONObject("session").length())
    }

    @Test
    fun `partial carries item id text and an empty stash`() {
        val json = parse(QwenEvents.partial("item_abc", "你好"))
        assertEquals(QwenEvents.TYPE_PARTIAL, json.getString("type"))
        assertEquals("item_abc", json.getString("item_id"))
        assertEquals("你好", json.getString("text"))
        assertEquals("", json.getString("stash"))
        assertTrue(json.getString("event_id").isNotEmpty())
    }

    @Test
    fun `completed carries the final transcript`() {
        val json = parse(QwenEvents.completed("item_abc", "你好世界"))
        assertEquals(QwenEvents.TYPE_COMPLETED, json.getString("type"))
        assertEquals("item_abc", json.getString("item_id"))
        assertEquals("你好世界", json.getString("transcript"))
    }

    @Test
    fun `finished is a bare session finished event`() {
        val json = parse(QwenEvents.finished())
        assertEquals(QwenEvents.TYPE_SESSION_FINISHED, json.getString("type"))
        assertTrue(json.getString("event_id").isNotEmpty())
    }

    @Test
    fun `error nests type and message`() {
        val json = parse(QwenEvents.error("桥未连接"))
        assertEquals(QwenEvents.TYPE_ERROR, json.getString("type"))
        assertEquals("server_error", json.getJSONObject("error").getString("type"))
        assertEquals("桥未连接", json.getJSONObject("error").getString("message"))
    }

    @Test
    fun `error accepts a custom type`() {
        val json = parse(QwenEvents.error("忙", type = "server_busy"))
        assertEquals("server_busy", json.getJSONObject("error").getString("type"))
    }
}
