package com.xposed.wetypehook.wetype.voice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionService
import android.speech.RecognitionSupport
import android.speech.SpeechRecognizer
import android.util.Log
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max

/**
 * 模块自带的 [RecognitionService]：**把标准 `SpeechRecognizer` 请求转给微信输入法的识别引擎。**
 *
 * 这是「路线 B」的落地形态。Eta 的「识别服务」里选**系统语音服务**，系统就会把
 * `android.speech.action.RECOGNIZE_SPEECH` 派发到这里；本服务负责：
 *
 * ```
 * Eta ──SpeechRecognizer──▶ 本服务 ──麦克风 PCM──▶ WeTypeVoiceClient
 *                                                      │ TCP 127.0.0.1:18515
 *                                                      ▼
 *                          微信输入法进程 WeTypeVoiceBridge ──▶ 宿主识别引擎
 *                                                      │
 *   Eta ◀──partialResults/results（累积整句）◀──────────┘
 * ```
 *
 * ## 为什么必须是「我们自己开麦克风」
 *
 * Eta 在 SYSTEM 路径上**自己不录音**（它的 `AudioRecord` 只在千问/豆包那几条路上建）。
 * `SpeechRecognizer` 的契约就是：识别服务自己采音，只把文本回吐。所以这里按
 * 16k / 单声道 / PCM16 采音 —— 与 [WeTypeVoiceBridge] 和 Eta 自己的录音器完全同格式，
 * 桥那头不需要任何重采样。
 *
 * ## 权限由**调用方**负责，不是我们
 *
 * AOSP `RecognitionService.dispatchStartListening` 查的是 **caller**（Eta）的
 * `RECORD_AUDIO`：先 `checkPermissionForPreflightNotHardDenied` 决定要不要回调
 * `onStartListening`，再 `checkPermissionAndStartDataDelivery` 决定要不要放行数据。
 * 两步都过了才会真正走到我们这儿。我们**自己**开麦克风时，还要再有一份自己的
 * `RECORD_AUDIO`（见 `AndroidManifest.xml`）。
 *
 * ## 收尾靠静音端点，不靠 `stopListening`
 *
 * Eta 在 SYSTEM 路径上不会主动停（`SpeechRecognizer.stopListening` 只在它自己的
 * `EtaRecognitionService` 兜底路径里出现）。所以本服务自己判「说完了」：检测到语音之后
 * 持续静音 [SILENCE_FINISH_MS] 就发 EOS、等桥把尾巴出完，再回 `results()`。
 *
 * ## 一轮一个连接
 *
 * 桥的会话是**连接驱动**的：连上并推满几帧才拉起宿主语音，收到 EOS 后收尾。
 * 所以每轮识别都要新建连接、结束就关，不能复用 —— 复用的话桥不会 `resetStream()`，
 * 第二轮会被当成第一轮的延续。
 *
 * ## 虚拟麦克风：`voice.debug.wav`
 *
 * 设了这条系统属性就**不碰真麦克风**，改从 WAV 取 PCM（见 [PcmSource]）。存在的理由是
 * 验证整条链路不能靠「对着手机喊」：识别质量、端点判定、转录回传三件事必须在**可复现的
 * 同一段音频**上比对。真麦克风每次进气都不同，出了问题分不清是链路的还是那口气的。
 *
 * 属性只在测试期由 root 设置，正式使用完全不参与；取值是设备上的 WAV 路径：
 *
 * ```
 * adb shell su -c 'setprop voice.debug.wav /data/local/tmp/asr-long.wav'
 * adb shell su -c 'setprop voice.debug.wav ""'      # 关掉，回到真麦克风
 * ```
 */
class WeTypeRecognitionService : RecognitionService() {

    private companion object {
        const val TAG = "WeTypeVoice"

        /** 16k / 单声道 / PCM16 —— 与桥、与 Eta 自己的录音器三方一致。 */
        const val SAMPLE_RATE = 16000

        /** 每次读 100ms。桥那头宿主读线程是 20ms/640B 的节奏，这个粒度足够细。 */
        const val BYTES_PER_READ = SAMPLE_RATE * 2 / 10

        /** 峰值门限，约 -36 dBFS。低于它算静音。 */
        const val SILENCE_PEAK = 500

        /** 连续静音多久算「这句说完了」。 */
        const val SILENCE_FINISH_MS = 1800L

        /** 一句都没说就静音这么久，直接判本轮无语音。 */
        const val NO_SPEECH_FINISH_MS = 6000L

        /**
         * EOS 之后连续多久没有新转录，就认为桥已经出完了。
         *
         * 不用固定时限：桥那头是**拉**模型，宿主读线程按 20ms/640B 的节奏从队列取音，
         * EOS 到达时队列里通常还压着几秒甚至二十秒音频，最后一段转录什么时候到取决于
         * 宿主引擎的排队情况，实测同一段音频会在 EOS 之后 0.6s ~ 1.0s 之间浮动。
         * 固定时限要么切掉尾巴（实测 1200ms 时最后一段卡在 941ms，余量只剩 260ms），
         * 要么无谓地拖长每一轮。改成「安静够了就走」，每次有新转录就把计时重置。
         */
        const val EOS_QUIET_MS = 900L

        /** EOS 之后的绝对上限。宿主自己的收尾宽限是 3000ms，这里必须大于它。 */
        const val EOS_MAX_WAIT_MS = 6000L

        /** 转录回调太密，每这么多条才留一行日志。见 [Session.partialCount]。 */
        const val PARTIAL_LOG_EVERY = 50

        /** 单轮硬上限。Eta 自己 30s 也有看门狗，这里只是兜底。 */
        const val MAX_SESSION_MS = 60_000L

        /**
         * 测试用虚拟麦克风。设了这条系统属性就从 WAV 读 PCM，完全不碰真麦克风。
         *
         * 只在验证时由 root `setprop`，正式使用为空 —— 不做任何持久化、不写进设置界面，
         * 免得把一条测试后门变成长期面。
         */
        const val PROP_DEBUG_WAV = "voice.debug.wav"

        /**
         * 虚拟麦克风每帧之间按真实时间停顿的时长，与 [BYTES_PER_READ] 对应的 100ms 对齐。
         *
         * 必须真等：桥那头按 16k 的实际字节速率放行，一口气把 20 秒音频在几十毫秒里灌完
         * 只会让限速和端点检测全部失真，测出来的东西没有参考价值。
         */
        const val DEBUG_WAV_PACE_MS = 100L
    }

    private val sessionLock = Any()

    @Volatile
    private var session: Session? = null

    // ------------------------------------------------------------------
    // RecognitionService 契约
    // ------------------------------------------------------------------

    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        if (listener == null) return
        // 同一时刻只留一路：旧会话先干净收掉，否则桥那边会出现两路音频抢一个队列。
        finishSession(null, cancelled = true)

        val created = Session(listener)
        synchronized(sessionLock) { session = created }
        created.start()
    }

    override fun onStopListening(listener: Callback?) {
        // 用户主动停：立即收尾，把已有的文本交出去。
        finishSession(listener, cancelled = false)
    }

    override fun onCancel(listener: Callback?) {
        // 取消不是结束：不回结果，直接丢弃。
        finishSession(listener, cancelled = true)
    }

    override fun onDestroy() {
        finishSession(null, cancelled = true)
        super.onDestroy()
    }

    /** 一次会话只服务一路请求。桥本身也是单客户端模型。 */
    override fun getMaxConcurrentSessionsCount(): Int = 1

    /**
     * 回报「这个语言本机支持」，让 Eta 直接进 START 分支。
     *
     * 不实现也能跑：Eta 的 `gi1.onError` 会兜底去 `startListening`（它把
     * `ERROR_CANNOT_CHECK_SUPPORT` 当「查不了，那就直接开」）。但那样会先白等一次
     * 超时、还多打一条错误日志，所以这里主动回一个干净的 START。
     *
     * 任何异常都退回 `onError`，让 Eta 走它原本的兜底路径 —— 不把可选优化变成故障点。
     */
    override fun onCheckRecognitionSupport(
        recognizerIntent: Intent,
        supportCallback: RecognitionService.SupportCallback
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            supportCallback.onError(SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT)
            return
        }
        runCatching {
            val tag = Locale.getDefault().toLanguageTag()
            val support = RecognitionSupport.Builder()
                .setInstalledOnDeviceLanguages(listOf(tag))
                .build()
            Log.i(TAG, "recognition support: installedOnDevice=[$tag]")
            supportCallback.onSupportResult(support)
        }.onFailure {
            Log.w(TAG, "support check failed: ${it.javaClass.simpleName}: ${it.message}")
            runCatching { supportCallback.onError(SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT) }
        }
    }

    // ------------------------------------------------------------------
    // 会话
    // ------------------------------------------------------------------

    /** 结束当前会话。传 null 表示「不等它了，直接拆」。 */
    private fun finishSession(listener: RecognitionService.Callback?, cancelled: Boolean) {
        val current = synchronized(sessionLock) {
            val s = session
            if (s == null) return
            if (listener != null && s.callback !== listener) return
            session = null
            s
        }
        current.shutdown(cancelled)
    }

    private inner class Session(val callback: RecognitionService.Callback) {

        private val finished = AtomicBoolean(false)

        /** 本轮开始的时刻。端点检测的两条时限都相对它算。 */
        private val sessionStart = System.currentTimeMillis()

        @Volatile
        private var latestText = ""

        @Volatile
        private var sawSpeech = false

        @Volatile
        private var lastVoiceAt = 0L

        /** 最近一次收到转录的时刻。EOS 之后用它判断桥是不是已经出完了。 */
        @Volatile
        private var lastTranscriptAt = 0L

        @Volatile
        private var readySent = false

        private val client = WeTypeVoiceClient(
            onTranscript = ::onTranscript,
            onDisconnected = ::onBridgeLost
        )

        private var worker: Thread? = null

        /** 本轮的 PCM 来源。真麦克风或测试用 WAV，见 [openSource]。 */
        @Volatile
        private var source: PcmSource? = null

        fun start() {
            val thread = Thread(::run, "wetype-asr-session")
            thread.isDaemon = true
            worker = thread
            thread.start()
        }

        /**
         * 主动收尾。`cancelled` 时不回任何结果 —— 取消的语义是「当我没说过」。
         *
         * 可能在任意线程被调（AOSP 的 `onStopListening` / `onCancel` 走主线程，
         * 静音端点走工作线程），所以整段用 [finished] 做一次性闸门。
         */
        fun shutdown(cancelled: Boolean) {
            if (!finished.compareAndSet(false, true)) return
            client.close()
            closeSource()
            if (cancelled) {
                Log.i(TAG, "session cancelled (text=${latestText.length})")
                return
            }
            deliverResult()
        }

        // ---- 工作线程：采音 → 推桥 ----

        private fun run() {
            val port = WeTypeVoiceProtocol.DEFAULT_PORT
            if (!client.connect(port)) {
                // 桥不在（微信输入法进程没起来 / 18515 没监听）。这是环境问题，不是识别失败。
                Log.w(TAG, "bridge unreachable on 127.0.0.1:$port")
                fail(SpeechRecognizer.ERROR_SERVER)
                return
            }
            val open = openSource() ?: return

            val startedAt = System.currentTimeMillis()
            val buffer = ByteArray(BYTES_PER_READ)
            while (!finished.get()) {
                val read = runCatching { open.read(buffer) }.getOrDefault(-1)
                if (read <= 0) {
                    // 0 = 音频放完了（虚拟麦克风）；负数 = 出错。两种都当本轮结束处理。
                    Log.i(TAG, "pcm source done ($read), ending turn")
                    break
                }
                if (!client.sendPcm(buffer, 0, read)) {
                    Log.w(TAG, "bridge write failed, ending turn")
                    break
                }
                if (!readySent) {
                    readySent = true
                    runCatching { callback.readyForSpeech(Bundle()) }
                }
                trackVoice(buffer, read)
                val now = System.currentTimeMillis()
                if (now - startedAt > MAX_SESSION_MS) {
                    Log.i(TAG, "session hit max duration, ending turn")
                    break
                }
                if (shouldEndTurn(now)) break
                if (open.paced) runCatching { Thread.sleep(DEBUG_WAV_PACE_MS) }
            }
            if (finished.get()) return

            // 让桥把队列里剩下的音频吃完再出最后一段。判据是「安静够了」而不是固定时限：
            // 桥出完最后一段之后不会再有任何转录，所以连续 [EOS_QUIET_MS] 没有新转录就是收尾了。
            client.sendEos()
            val eosAt = System.currentTimeMillis()
            lastTranscriptAt = eosAt
            while (!finished.get()) {
                val now = System.currentTimeMillis()
                if (now - eosAt > EOS_MAX_WAIT_MS) {
                    Log.i(TAG, "EOS wait hit ceiling, ending turn")
                    break
                }
                if (now - lastTranscriptAt > EOS_QUIET_MS) break
                runCatching { Thread.sleep(50) }
            }
            finishTurn()
        }

        /**
         * 选 PCM 来源：系统属性指了 WAV 就用 WAV，否则开真麦克风。
         *
         * 属性读不到、文件打不开、格式不对，一律**退回真麦克风**并打日志 ——
         * 测试开关不该成为正式路径上的故障点。
         */
        private fun openSource(): PcmSource? {
            val path = runCatching {
                Class.forName("android.os.SystemProperties")
                    .getMethod("get", String::class.java)
                    .invoke(null, PROP_DEBUG_WAV) as? String
            }.getOrNull()?.takeIf { it.isNotBlank() }

            if (path != null) {
                val wav = WavPcmSource.open(path)
                if (wav != null) {
                    source = wav
                    Log.i(TAG, "session started, VIRTUAL mic from $path")
                    return wav
                }
                Log.w(TAG, "debug wav unusable at $path, falling back to real mic")
            }

            val mic = openMicrophone() ?: return null
            source = mic
            return mic
        }

        private fun openMicrophone(): PcmSource? {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                Log.w(TAG, "module lacks RECORD_AUDIO; cannot capture for WeType")
                fail(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
                return null
            }
            val minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val created = runCatching {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    max(minBuffer, BYTES_PER_READ * 4)
                )
            }.getOrNull()
            if (created == null || created.state != AudioRecord.STATE_INITIALIZED) {
                Log.w(TAG, "mic init failed (minBuffer=$minBuffer)")
                runCatching { created?.release() }
                fail(SpeechRecognizer.ERROR_AUDIO)
                return null
            }
            val started = runCatching {
                created.startRecording()
                created.recordingState == AudioRecord.RECORDSTATE_RECORDING
            }.getOrDefault(false)
            if (!started) {
                Log.w(TAG, "mic unavailable (busy?)")
                runCatching { created.release() }
                fail(SpeechRecognizer.ERROR_AUDIO)
                return null
            }
            Log.i(TAG, "session started, mic ${SAMPLE_RATE}Hz mono pcm16")
            return MicPcmSource(created)
        }

        private fun closeSource() {
            val current = source
            source = null
            runCatching { current?.close() }
        }

        // ---- 端点检测 ----

        private fun trackVoice(buffer: ByteArray, length: Int) {
            val peak = peakOf(buffer, length)
            // 顺手把电平喂给 Eta 的波形。Eta 把入参当「0..10 的 dB」用（`f/10` 再夹到 0..1），
            // 所以这里给的是 0..10 的线性代理值 —— 严格 dBFS 是负的，喂进去会被夹成 0，表就不动了。
            runCatching { callback.rmsChanged((peak * 10f / 32767f).coerceIn(0f, 10f)) }
            val now = System.currentTimeMillis()
            if (peak >= SILENCE_PEAK) {
                if (!sawSpeech) {
                    sawSpeech = true
                    runCatching { callback.beginningOfSpeech() }
                    Log.i(TAG, "speech detected")
                }
                lastVoiceAt = now
            }
        }

        private fun shouldEndTurn(now: Long): Boolean {
            // 还没开口：给 [NO_SPEECH_FINISH_MS] 的机会，超了就判本轮无语音。
            if (!sawSpeech) return now - sessionStart > NO_SPEECH_FINISH_MS
            // 开过口：从最后一次有声算起静音够久就算说完。
            return now - lastVoiceAt > SILENCE_FINISH_MS
        }

        // ---- 桥回调 ----

        /**
         * 已收到的转录条数。桥对**每一小段音频**都会回调一次（实测一轮 20 秒音频出 369 条），
         * 逐条打日志会把 logcat 冲垮，所以只留首条、尾条和每 [PARTIAL_LOG_EVERY] 条。
         */
        private var partialCount = 0

        private fun onTranscript(text: String, endFlag: Boolean) {
            if (finished.get()) return
            // 桥给的是**累积整句**，不是增量。Eta 那边也是整句替换，直接透传即可。
            latestText = text
            lastTranscriptAt = System.currentTimeMillis()
            partialCount++
            if (partialCount <= 2 || partialCount % PARTIAL_LOG_EVERY == 0) {
                Log.i(TAG, "partial#$partialCount len=${text.length} text=${text.take(30)}")
            }
            val bundle = Bundle().apply {
                putStringArrayList(
                    SpeechRecognizer.RESULTS_RECOGNITION,
                    arrayListOf(text)
                )
            }
            runCatching { callback.partialResults(bundle) }
        }

        private fun onBridgeLost() {
            if (finished.get()) return
            Log.w(TAG, "bridge closed mid-session")
            fail(SpeechRecognizer.ERROR_SERVER)
        }

        // ---- 收尾 ----

        private fun finishTurn() {
            if (!finished.compareAndSet(false, true)) return
            client.close()
            closeSource()
            deliverResult()
        }

        private fun fail(code: Int) {
            if (!finished.compareAndSet(false, true)) return
            client.close()
            closeSource()
            Log.w(TAG, "session failed with code=$code")
            runCatching { callback.error(code) }
        }

        /** 把 `finished` 已经置位之后的收尾动作做完。调用方负责保证只进一次。 */
        private fun deliverResult() {
            val text = latestText
            if (text.isEmpty()) {
                Log.i(TAG, "turn ended with no transcript")
                runCatching { callback.error(SpeechRecognizer.ERROR_NO_MATCH) }
                return
            }
            runCatching { callback.endOfSpeech() }
            val bundle = Bundle().apply {
                putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
            }
            Log.i(TAG, "turn done len=${text.length} text=${text.take(40)}")
            runCatching { callback.results(bundle) }
        }

        private fun peakOf(data: ByteArray, length: Int): Int {
            val end = (minOf(length, data.size) / 2) * 2
            var peak = 0
            var i = 0
            while (i + 1 < end) {
                val raw = (data[i].toInt() and 0xFF) or (data[i + 1].toInt() shl 8)
                val value = if (raw >= 0x8000) raw - 0x10000 else raw
                val magnitude = abs(value)
                if (magnitude > peak) peak = magnitude
                i += 2
            }
            return peak
        }
    }
}

/**
 * 一轮会话的 PCM 来源。真麦克风和测试用 WAV 都实现它，`Session` 只认这个接口，
 * 不关心音频从哪来。
 */
internal interface PcmSource {

    /** 需要按真实时间喂音吗？WAV 要，真麦克风本来就实时。 */
    val paced: Boolean

    /** 读一段 16k / 单声道 / PCM16。返回 0 表示没有更多音频，负数表示出错。 */
    fun read(dst: ByteArray): Int

    fun close()
}

/** 真麦克风。 */
internal class MicPcmSource(private val record: AudioRecord) : PcmSource {

    override val paced: Boolean get() = false

    override fun read(dst: ByteArray): Int =
        runCatching { record.read(dst, 0, dst.size) }.getOrDefault(-1)

    override fun close() {
        runCatching { record.stop() }
        runCatching { record.release() }
    }
}

/**
 * 测试用虚拟麦克风：从 WAV 文件读 PCM，完全不碰硬件。
 *
 * 只接受 **16k / 单声道 / PCM16** 的 WAV —— 和真麦克风、和桥三方同格式。别的格式直接
 * 拒绝（[open] 返回 null，调用方退回真麦克风），不在这里做重采样：一个测试开关不值得
 * 引入重采样器，而且格式不符时静默重采样会让人误判识别质量。
 *
 * 用 [DataInputStream] 读，所以 WAV 头里的小端字段要手工按小端解 —— 别顺手写成
 * `readInt()`，那个是大端，读出来全是离谱值。
 */
internal class WavPcmSource private constructor(
    private val stream: DataInputStream,
    private val dataBytes: Int
) : PcmSource {

    private var consumed = 0

    override val paced: Boolean get() = true

    override fun read(dst: ByteArray): Int {
        if (consumed >= dataBytes) return 0
        val want = minOf(dst.size, dataBytes - consumed)
        val read = stream.read(dst, 0, want)
        if (read <= 0) return 0
        consumed += read
        return read
    }

    override fun close() {
        runCatching { stream.close() }
    }

    companion object {
        private const val TAG = "WeTypeVoice"
        private const val WANT_RATE = 16000
        private const val WANT_CHANNELS = 1
        private const val WANT_BITS = 16

        /** 打开并校验一个 WAV。任何不符都返回 null，由调用方退回真麦克风。 */
        fun open(path: String): WavPcmSource? = runCatching {
            val stream = DataInputStream(BufferedInputStream(FileInputStream(File(path))))
            val riff = ByteArray(4).also { stream.readFully(it) }
            if (String(riff, Charsets.US_ASCII) != "RIFF") {
                Log.w(TAG, "wav: not RIFF")
                stream.close()
                return null
            }
            stream.skipBytes(4) // 文件长度，不校验
            val wave = ByteArray(4).also { stream.readFully(it) }
            if (String(wave, Charsets.US_ASCII) != "WAVE") {
                Log.w(TAG, "wav: not WAVE")
                stream.close()
                return null
            }

            var rate = 0
            var channels = 0
            var bits = 0
            var dataBytes = -1

            while (dataBytes < 0) {
                val id = ByteArray(4)
                if (stream.read(id) != 4) break
                val size = readLeInt(stream)
                if (size < 0) break
                when (String(id, Charsets.US_ASCII)) {
                    "fmt " -> {
                        val fmt = DataInputStream(ByteArray(size).also { stream.readFully(it) }
                            .inputStream())
                        readLeShort(fmt)                 // 音频格式，1 = PCM
                        channels = readLeShort(fmt)
                        rate = readLeInt(fmt)
                        fmt.skipBytes(6)                 // 字节率 + 块对齐
                        bits = readLeShort(fmt)
                    }
                    "data" -> dataBytes = size
                    else -> stream.skipBytes(size + (size and 1)) // 块按偶数对齐
                }
            }

            if (dataBytes <= 0 || rate != WANT_RATE || channels != WANT_CHANNELS ||
                bits != WANT_BITS
            ) {
                Log.w(
                    TAG,
                    "wav: need ${WANT_RATE}Hz/${WANT_CHANNELS}ch/${WANT_BITS}bit, " +
                        "got ${rate}Hz/${channels}ch/${bits}bit data=$dataBytes"
                )
                stream.close()
                return null
            }
            WavPcmSource(stream, dataBytes)
        }.onFailure {
            Log.w(TAG, "wav open failed: ${it.javaClass.simpleName}: ${it.message}")
        }.getOrNull()

        /** 小端 16 位。 */
        private fun readLeShort(input: java.io.InputStream): Int {
            val lo = input.read()
            val hi = input.read()
            if (lo < 0 || hi < 0) return -1
            return (lo and 0xFF) or ((hi and 0xFF) shl 8)
        }

        /** 小端 32 位。 */
        private fun readLeInt(input: java.io.InputStream): Int {
            val b0 = input.read()
            val b1 = input.read()
            val b2 = input.read()
            val b3 = input.read()
            if (b0 < 0 || b1 < 0 || b2 < 0 || b3 < 0) return -1
            return (b0 and 0xFF) or ((b1 and 0xFF) shl 8) or
                ((b2 and 0xFF) shl 16) or ((b3 and 0xFF) shl 24)
        }
    }
}
