package com.xposed.wetypehook.wetype.voice.shell

import android.util.Log
import com.xposed.wetypehook.wetype.settings.VoiceTuning
import com.xposed.wetypehook.wetype.voice.WeTypeVoiceProtocol
import org.json.JSONObject

/**
 * `WS /api/v3/sauc/bigmodel_async` —— 火山引擎「流式语音识别 2.0」那条链的服务端。
 *
 * 帧格式见 [DoubaoProtocol]；行为逐条对齐 Eta 3.1.0 的真实收发：
 *
 * 1. 收到**整包请求**（msgType 1，gzip 的 JSON）后回一条**非最终**的 msgType 9 ——
 *    真实服务端也这么回，客户端拿它当「配置已收到」。
 * 2. msgType 2 的纯音频帧逐段喂桥，中间结果用 msgType 9 回（`result.text` 是累积整句，
 *    `result.utterances[].definite = false`）。
 * 3. 带 `flags & 0x2` 的最后一包音频 → 发 EOS、等桥出完、回一条
 *    `flags & 0x2` + `definite = true` 的 msgType 9 收场。
 *
 * 握手头（`X-Api-Key` / `X-Api-Resource-Id` / `X-Api-Request-Id` / `X-Api-Connect-Id`）
 * **不校验**：壳不是火山，不需要也不应该要用户的 key。
 */
internal class DoubaoRealtimeSession(
    sendText: (ByteArray) -> Boolean,
    sendBinary: (ByteArray) -> Boolean,
    abort: () -> Unit,
    tuning: VoiceTuning,
    bridgePort: Int = WeTypeVoiceProtocol.DEFAULT_PORT
) : AsrShellSession(sendText, sendBinary, abort, tuning, bridgePort) {

    private var handshakeSeen = false

    /**
     * 处理一条客户端二进制帧。返回 false 表示这条连接该关了。
     *
     * 跑在连接线程上，可以阻塞（收尾那一段就在这里面等）。
     */
    fun onBinary(payload: ByteArray): Boolean {
        val frame = runCatching { DoubaoProtocol.decode(payload) }.getOrElse { error ->
            Log.w(ASR_SHELL_TAG, "doubao: 非法帧：${error.message}")
            sendError(1001, error.message ?: "bad frame")
            isDone = true
            return false
        }

        return when (frame.msgType) {
            DoubaoProtocol.MSG_FULL_CLIENT_REQUEST -> onHandshake(frame)

            DoubaoProtocol.MSG_AUDIO_ONLY_REQUEST -> {
                if (!handshakeSeen) {
                    // 真实服务端会直接报错；这里宽容一点，先自己补上握手，免得整场白跑。
                    Log.w(ASR_SHELL_TAG, "doubao: 还没收到整包请求就先发了音频")
                    handshakeSeen = true
                }
                if (!connectBridge()) return failToReachBridge()
                pushPcm(frame.payload, 0, frame.payload.size)
                if (frame.flags and DoubaoProtocol.FLAG_LAST != 0) {
                    finishSession()
                    return false
                }
                true
            }

            else -> {
                Log.w(ASR_SHELL_TAG, "doubao: 不认识的 msgType=${frame.msgType}")
                true
            }
        }
    }

    private fun onHandshake(frame: DoubaoFrame): Boolean {
        handshakeSeen = true
        if (frame.serialization != DoubaoProtocol.SERIALIZATION_JSON) {
            Log.w(
                ASR_SHELL_TAG,
                "doubao: 整包请求的 serialization 应为 1，实际 ${frame.serialization}"
            )
        }
        logHandshake(frame.payload)
        if (!connectBridge()) return failToReachBridge()
        // 非最终响应：Eta 拿它当「配置已收到」。
        sendResult(text = "", definite = false, last = false)
        return true
    }

    private fun finishSession() {
        awaitBridgeDrain()
        val finalText = transcript
        sendResult(text = finalText, definite = true, last = true)
        isDone = true
        Log.i(ASR_SHELL_TAG, "doubao: session finished, ${finalText.length} chars")
    }

    override fun emitPartial(text: String) {
        sendResult(text = text, definite = false, last = false)
    }

    override fun onBridgeLost() {
        sendError(1003, "WeType 语音桥已断开，本轮识别中断")
    }

    private fun sendResult(text: String, definite: Boolean, last: Boolean) {
        sendBytes(
            DoubaoProtocol.encode(
                msgType = DoubaoProtocol.MSG_SERVER_RESPONSE,
                flags = if (last) DoubaoProtocol.FLAG_LAST else 0,
                serialization = DoubaoProtocol.SERIALIZATION_JSON,
                payload = DoubaoJson.result(text, definite)
            )
        )
    }

    /** 桥连不上：**不回落到宿主引擎**，直接报错。 */
    private fun failToReachBridge(): Boolean {
        Log.w(ASR_SHELL_TAG, "doubao: bridge unreachable, failing the session")
        sendError(1002, "无法连接微信输入法语音桥（请确认输入法在运行、且「启用识别能力」已打开）")
        isDone = true
        return false
    }

    private fun sendError(code: Int, message: String) {
        sendBytes(
            DoubaoProtocol.encode(
                msgType = DoubaoProtocol.MSG_SERVER_ERROR,
                flags = 0,
                serialization = DoubaoProtocol.SERIALIZATION_JSON,
                payload = DoubaoJson.error(code, message)
            )
        )
    }

    /** 只打一行摘要：整包请求里的字段壳不解析，报出来是为了排查「Eta 到底发了什么」。 */
    private fun logHandshake(payload: ByteArray) {
        val model = runCatching {
            JSONObject(String(payload, Charsets.UTF_8))
                .optJSONObject("request")
                ?.optString("model_name")
        }.getOrNull()
        Log.i(ASR_SHELL_TAG, "doubao: 整包请求 model_name=$model")
    }
}
