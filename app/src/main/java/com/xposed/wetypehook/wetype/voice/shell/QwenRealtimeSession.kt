package com.xposed.wetypehook.wetype.voice.shell

import android.util.Log
import com.xposed.wetypehook.wetype.settings.VoiceTuning
import com.xposed.wetypehook.wetype.voice.WeTypeVoiceProtocol
import java.util.Base64
import java.util.UUID
import org.json.JSONObject

/**
 * `WS /api-ws/v1/realtime` —— 千问 · 实时识别那条链的服务端。
 *
 * 时序（Eta 3.1.0 实测）：
 *
 * 1. `session.update` → **必须立刻回 `session.updated`**，Eta 收到才开始推音频。
 * 2. `input_audio_buffer.append`（`audio` 是 base64 的 16k/单声道/s16le）→ 解码后喂桥。
 * 3. `session.finish` → 发 EOS、等桥出完最后一段、回 `completed` + `session.finished`。
 *
 * 请求头（`Authorization: Bearer …`）**不校验**：壳不认识也不关心任何 key，Eta 侧随便
 * 填一个非空串即可。
 */
internal class QwenRealtimeSession(
    sendText: (ByteArray) -> Boolean,
    sendBinary: (ByteArray) -> Boolean,
    abort: () -> Unit,
    tuning: VoiceTuning,
    bridgePort: Int = WeTypeVoiceProtocol.DEFAULT_PORT
) : AsrShellSession(sendText, sendBinary, abort, tuning, bridgePort) {

    /** 整场会话共用一个 item_id：桥给的是**一整句累积文本**，没有多句切分。 */
    private val itemId = "item_" + UUID.randomUUID().toString().replace("-", "").take(12)

    /**
     * 处理一条客户端文本帧。返回 false 表示这条连接该关了。
     *
     * 跑在连接线程上，可以阻塞（收尾那一段就在这里面等）。
     */
    fun onText(text: String): Boolean {
        val payload = runCatching { JSONObject(text) }.getOrElse {
            Log.w(ASR_SHELL_TAG, "qwen: 非 JSON 文本帧，忽略")
            return true
        }
        when (payload.optString("type")) {
            QwenEvents.TYPE_SESSION_UPDATE -> {
                if (!connectBridge()) return failToReachBridge()
                sendJson(QwenEvents.sessionUpdated(payload.optJSONObject("session")))
            }

            QwenEvents.TYPE_AUDIO_APPEND -> {
                val encoded = payload.optString("audio")
                if (encoded.isEmpty()) return true
                val pcm = runCatching { Base64.getDecoder().decode(encoded) }.getOrElse {
                    Log.w(ASR_SHELL_TAG, "qwen: audio 不是合法 base64，忽略这一帧")
                    return true
                }
                pushPcm(pcm, 0, pcm.size)
            }

            QwenEvents.TYPE_SESSION_FINISH -> {
                awaitBridgeDrain()
                val finalText = transcript
                sendJson(QwenEvents.completed(itemId, finalText))
                sendJson(QwenEvents.finished())
                isDone = true
                Log.i(ASR_SHELL_TAG, "qwen: session finished, ${finalText.length} chars")
                return false
            }

            else -> Log.i(ASR_SHELL_TAG, "qwen: 未处理事件 ${payload.optString("type")}")
        }
        return true
    }

    /** 防御性：真有客户端把裸 PCM 当二进制帧推过来也收。 */
    fun onBinary(payload: ByteArray) {
        pushPcm(payload, 0, payload.size)
    }

    override fun emitPartial(text: String) {
        sendJson(QwenEvents.partial(itemId, text))
    }

    override fun onBridgeLost() {
        sendJson(QwenEvents.error("WeType 语音桥已断开，本轮识别中断"))
    }

    /** 桥连不上：**不回落到宿主引擎**，直接报错。静默换路会让用户以为壳生效了。 */
    private fun failToReachBridge(): Boolean {
        Log.w(ASR_SHELL_TAG, "qwen: bridge unreachable, failing the session")
        sendJson(QwenEvents.error("无法连接微信输入法语音桥（请确认输入法在运行、且「启用识别能力」已打开）"))
        isDone = true
        return false
    }
}
