package com.xposed.wetypehook.wetype.voice.shell

import java.util.UUID
import org.json.JSONObject

/**
 * 千问 · 实时识别那条链上，服务端要说的话。
 *
 * 事件名与字段照 Eta 3.1.0 逆出来的协议钉死，并按同版本的收发逐字节核对过：
 *
 * - `session.updated` —— Eta **收到它才开始推音频**，所以 `session.update` 之后必须立刻回。
 * - `conversation.item.input_audio_transcription.text` —— `{item_id, text, stash}`，
 *   Eta 按 `item_id` 存 `text + stash`，可以反复覆盖，用于中间结果。
 * - `conversation.item.input_audio_transcription.completed` —— `{item_id, transcript}`，最终结果。
 * - `session.finished` —— 会话结束，Eta 拿累积文本收尾。
 * - `error` —— Eta 直接报「识别失败」。
 *
 * 放在单独一个文件里是为了能脱离 Android 跑单测：这一层只有字符串进出。
 */
internal object QwenEvents {

    const val TYPE_SESSION_UPDATE = "session.update"
    const val TYPE_SESSION_UPDATED = "session.updated"
    const val TYPE_AUDIO_APPEND = "input_audio_buffer.append"
    const val TYPE_SESSION_FINISH = "session.finish"
    const val TYPE_PARTIAL = "conversation.item.input_audio_transcription.text"
    const val TYPE_COMPLETED = "conversation.item.input_audio_transcription.completed"
    const val TYPE_SESSION_FINISHED = "session.finished"
    const val TYPE_ERROR = "error"

    fun sessionUpdated(session: JSONObject?): String = base(TYPE_SESSION_UPDATED).apply {
        // 把客户端给的 session 原样回显（缺省就回一个空对象，与参考服务端一致）：
        // Eta 不解析它，但真实服务端就是这么回的，少了这一段某些客户端实现会认为
        // 「配置没被接受」。
        put("session", session ?: JSONObject())
    }.toString()

    fun partial(itemId: String, text: String): String = base(TYPE_PARTIAL).apply {
        put("item_id", itemId)
        put("text", text)
        // 桥给的是**累积整句**，没有「还没定稿的尾巴」这个概念，所以 stash 恒为空。
        put("stash", "")
    }.toString()

    fun completed(itemId: String, transcript: String): String = base(TYPE_COMPLETED).apply {
        put("item_id", itemId)
        put("transcript", transcript)
    }.toString()

    fun finished(): String = base(TYPE_SESSION_FINISHED).toString()

    fun error(message: String, type: String = "server_error"): String =
        base(TYPE_ERROR).apply {
            put(
                "error",
                JSONObject().apply {
                    put("type", type)
                    put("message", message)
                }
            )
        }.toString()

    private fun base(type: String): JSONObject = JSONObject().apply {
        put("type", type)
        put("event_id", UUID.randomUUID().toString())
    }
}
