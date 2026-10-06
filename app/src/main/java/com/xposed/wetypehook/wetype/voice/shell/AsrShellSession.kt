package com.xposed.wetypehook.wetype.voice.shell

import android.util.Log
import com.xposed.wetypehook.wetype.settings.VoiceTuning
import com.xposed.wetypehook.wetype.voice.WeTypeVoiceClient
import com.xposed.wetypehook.wetype.voice.WeTypeVoiceProtocol

/** 壳的日志 TAG。四个文件共用一份，免得某处拼错之后 grep 漏掉。 */
internal const val ASR_SHELL_TAG = "WeTypeAsrShell"

/**
 * 一条协议会话的公共部分：**只**负责跟微信输入法的回环桥打交道。
 *
 * 数据方向是单向的，音频只来自客户端推过来的 PCM —— 本类**绝不开麦克风**：
 *
 * ```
 * Eta ──协议帧──▶ AsrShellServer ──PCM──▶ WeTypeVoiceClient ──▶ 回环桥 ──▶ 宿主识别引擎
 *      ◀──协议形状的转录回包──────────── onTranscript（累积整句）◀──────┘
 * ```
 *
 * ## 收尾沿用系统识别服务那一套判据
 *
 * 推完音频发 `sendEos()` 之后，桥还要吃完队列里压着的音频才出最后一段。所以判据不是
 * 固定时限，而是「连续 [VoiceTuning.eosQuietMs] 没有新转录」+ 绝对上限
 * [VoiceTuning.eosMaxWaitMs]，与 [com.xposed.wetypehook.wetype.voice.WeTypeRecognitionService]
 * 的 `Session.run()` 逐条一致。这两个值来自设置页，不写死。
 *
 * ## 转录回调跑在桥的读线程上
 *
 * [WeTypeVoiceClient.onTranscript] 在读线程上触发，所以 [emitPartial] 里**不能阻塞**。
 * 写 socket 由调用方给的 `sendText` / `sendBinary` 串行化（[AsrShellServer] 内部加锁），
 * 不会与连接线程写的 pong / close 帧交错。
 */
internal abstract class AsrShellSession(
    private val sendText: (ByteArray) -> Boolean,
    private val sendBinary: (ByteArray) -> Boolean,
    private val abort: () -> Unit,
    private val tuning: VoiceTuning,
    private val bridgePort: Int = WeTypeVoiceProtocol.DEFAULT_PORT
) {

    private companion object {
        /**
         * 中间结果的最小间隔。
         *
         * 桥对**每一小段音频**都回调一次（实测一轮 20 秒音频出 369 条），逐条透传等于
         * 每秒刷 18 次客户端界面。真实云端 ASR 的中间结果频率也就几赫兹，所以这里按
         * 固定间隔合流，只把「最新的整句」发出去；最终结果不受这个间隔影响，收尾时
         * 一定单独发一条。
         */
        const val PARTIAL_INTERVAL_MS = 300L

        /** 收尾等待的轮询间隔，与识别服务里的 50ms 一致。 */
        const val EOS_POLL_MS = 50L
    }

    private val bridge = WeTypeVoiceClient(
        onTranscript = { text, _ -> handleTranscript(text) },
        onDisconnected = { handleBridgeLost() }
    )

    @Volatile
    private var bridgeConnected = false

    /** 会话已经进入拆除流程（调用方不再需要它了）。 */
    @Volatile
    private var closing = false

    /** EOS 已经发出，正在等桥出完最后一段。 */
    @Volatile
    private var eosSent = false

    @Volatile
    private var latestText = ""

    @Volatile
    private var lastTranscriptAt = 0L

    private var lastPartialAt = 0L
    private var lastPartialText = ""

    /** 会话已经走完，连接可以关了。 */
    @Volatile
    var isDone = false
        protected set

    /** 到目前为止的**累积整句**文本（桥给的就是累积值，不是增量）。 */
    protected val transcript: String get() = latestText

    /** 连上回环桥。重复调用幂等；返回 false 表示桥不在（输入法进程没起来 / 开关没开）。 */
    fun connectBridge(): Boolean {
        if (bridgeConnected) return true
        bridgeConnected = bridge.connect(bridgePort)
        return bridgeConnected
    }

    /** 推一段 16k / 单声道 / PCM16。桥不在时静默丢弃，由收尾那条错误回包交代。 */
    fun pushPcm(data: ByteArray, offset: Int, length: Int) {
        if (!bridgeConnected || length <= 0) return
        if (!bridge.sendPcm(data, offset, length)) {
            Log.w(ASR_SHELL_TAG, "bridge pcm write failed")
        }
    }

    /**
     * 收尾：发 EOS，然后等桥把队列吃完。**会阻塞**，只允许在连接线程上调。
     *
     * 桥那头是**拉**模型，EOS 到达时队列里通常还压着几秒音频；立刻返回会把没吃完的
     * 音频连同最终转录一起丢掉。
     */
    fun awaitBridgeDrain() {
        if (!bridgeConnected) return
        eosSent = true
        bridge.sendEos()
        val eosAt = System.currentTimeMillis()
        lastTranscriptAt = eosAt
        while (!closing) {
            val now = System.currentTimeMillis()
            if (now - eosAt > tuning.eosMaxWaitMs) {
                Log.i(ASR_SHELL_TAG, "EOS wait hit ceiling (${tuning.eosMaxWaitMs}ms)")
                break
            }
            if (now - lastTranscriptAt > tuning.eosQuietMs) break
            runCatching { Thread.sleep(EOS_POLL_MS) }
        }
    }

    /** 拆掉会话。必须在连接收尾时调一次，否则桥那条连接会挂着。 */
    fun close() {
        closing = true
        bridge.close()
    }

    private fun handleTranscript(text: String) {
        latestText = text
        lastTranscriptAt = System.currentTimeMillis()
        if (text.isEmpty() || text == lastPartialText) return
        val now = System.currentTimeMillis()
        if (now - lastPartialAt < PARTIAL_INTERVAL_MS) return
        lastPartialAt = now
        lastPartialText = text
        runCatching { emitPartial(text) }
    }

    /**
     * 桥把连接关了。
     *
     * 收尾阶段（EOS 已发）断开是异常，但手上这段文本仍然有效，交给收尾流程正常回给
     * 客户端，不再打断；会话中途断开才是真失败，回一条错误并把连接拆掉。
     */
    private fun handleBridgeLost() {
        bridgeConnected = false
        if (closing) return
        if (eosSent) {
            Log.w(ASR_SHELL_TAG, "bridge closed while draining EOS")
            return
        }
        Log.w(ASR_SHELL_TAG, "bridge closed mid-session")
        isDone = true
        runCatching { onBridgeLost() }
        abort()
    }

    /** 发一条中间结果。跑在桥的读线程上，实现里不要阻塞。 */
    protected abstract fun emitPartial(text: String)

    /** 会话中途失去桥。实现里给客户端回一条协议形状的错误即可。 */
    protected open fun onBridgeLost() {}

    protected fun sendJson(json: String): Boolean = sendText(json.toByteArray(Charsets.UTF_8))

    protected fun sendBytes(bytes: ByteArray): Boolean = sendBinary(bytes)
}
