package com.xposed.wetypehook.wetype.voice

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.xposed.wetypehook.xposed.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Eta ⇄ 微信输入法语音桥（**在微信输入法进程内运行**）。
 *
 * 思路：不碰真实麦克风，把宿主录音器里的 `AudioRecord` 换成模块自写的假货，
 * 假货的 `read()` 从一个阻塞队列取 PCM。于是宿主整条识别链路（读线程 → 识别引擎 →
 * 转录回调）照常跑，只是音频来源变成了外部。Eta 只要把 PCM 推进队列、把转录读走，
 * 就等于借到了微信输入法的识别能力。
 *
 * ## 为什么是回环 TCP 而不是 unix socket
 *
 * 实测本机 SELinux 是 `Enforcing`，且 `com.tencent.wetype`（`s0:c131,…`）与
 * `com.xposed.wetypehook`（`s0:c140,…`）的 **MLS 类别不同**，跨 app 的 unix socket
 * 要走 `connectto`，在这套策略下不保证放行；`run-as` 又被 `package not debuggable` 挡住，
 * 连验证都做不了。回环 TCP 走 `tcp_socket`，是 `untrusted_app` 域的标准放行项，
 * 且两个 app 都已持有 `INTERNET` 权限。代价只是数据过一遍 loopback，不落网卡，
 * 对 16k/单声道 PCM（32 KB/s）可以忽略。
 *
 * ## 协议（全部小端）
 *
 * ```
 * Eta → 模块
 *   0x01 <u32 len> <pcm bytes>   推一段 PCM，16k / 单声道 / PCM16
 *   0x02                         本轮结束：停止喂音并收尾会话
 *
 * 模块 → Eta
 *   0x11 <u8 flag> <u32 len> <utf8 text>   转录
 *        flag bit0 = 宿主给的 endFlag
 * ```
 *
 * **`text` 是累积的，不是增量**：每次回调给的都是「到目前为止的整句」，客户端要**替换**
 * 而不是追加。实测序列 `你好` → `你好 微` → `你好 微信输入` → … → `你好 微信输入法语音识别测试1234`。
 * 另外宿主 3.5.3 每次回调都把 `endFlag` 置 true，所以**不能**拿它当「这句说完了」用；
 * 判断收尾请用 EOS 之后的静默期（见下）。
 *
 * ## 会话生命周期由连接驱动
 *
 * 客户端连上并推满 [START_AFTER_FRAMES] 帧 → 模块拉起宿主语音会话；
 * 客户端发 EOS（或断开）→ 延迟 [EOS_GRACE_MS] 让宿主吃完剩余音频，再收尾。
 * 推的是存量音频还是实时麦克风，模块不关心 —— 限速见 [pace]。
 *
 * ## 两条硬约束
 *
 * 1. **`read()` 永不返回 0**（除非请求长度本身 ≤ 0）。宿主把 `read() == 0` 当作
 *    「录音已结束」，会当场收尾会话。队列空的时候宁可补静音也不能返回 0。
 * 2. PCM 必须是 **16k / 单声道 / PCM16**。宿主录音器是按 `(16000, 1, 3)` 建的，
 *    喂别的格式识别质量会崩（这里不做隐式重采样，格式不符直接拒绝）。
 * 3. **补静音不能补纯数字零**，必须补 ±1 LSB 抖动。宿主 `l6.e.m()` 逐块统计全零块，
 *    连续 100 块（200ms/块，约 2 秒）就判 `RECORDER_DATAZERO_ERROR`，再抛成语音引擎的
 *    「PCM 麦克风故障」（`localErrorType=19`），宿主随即执行自毁恢复 ——
 *    `WxImeUtil.J1()` 杀掉**全部**微信输入法进程。详见 [SILENCE_DITHER]。
 */
internal object WeTypeVoiceBridge {

    private const val TAG = "WeTypeVoice"

    /** 默认监听端口。只绑回环，不对外网暴露。 */
    const val DEFAULT_PORT = WeTypeVoiceProtocol.DEFAULT_PORT

    private const val FRAME_PCM = WeTypeVoiceProtocol.FRAME_PCM
    private const val FRAME_EOS = WeTypeVoiceProtocol.FRAME_EOS
    private const val FRAME_TRANSCRIPT = WeTypeVoiceProtocol.FRAME_TRANSCRIPT

    /** 单帧上限。Eta 一次推 100ms 也才 3200 字节，64K 是防呆。 */
    private const val MAX_FRAME_BYTES = WeTypeVoiceProtocol.MAX_FRAME_BYTES

    /** 待回传转录的队列上限。够长到不会影响正常出字，又能在客户端掉线时封顶内存。 */
    private const val TRANSCRIPT_QUEUE_CAPACITY = 256

    /** 允许比真实时间轴超前喂出的余量，见 [pace]。 */
    private const val LEAD_MS = 500L

    /**
     * 攒够这么多帧再拉起宿主会话。见 [maybeStartSession] —— 必须让读线程**第一口就吃到
     * 真音频**，否则识别引擎先收到一段静音，会把这一轮判成「没有语音」。
     */
    private const val START_AFTER_FRAMES = 3

    /** 队列空时的单次等待上限。宿主读线程的节奏就是 20ms/640 字节，对齐它。 */
    private const val STARVE_POLL_MS = 20L

    /** 16k / 单声道 / PCM16 的字节速率。按它给喂音限速，见 [pace]。 */
    private const val BYTES_PER_SEC = 16000 * 2

    /**
     * 补静音时叠加的抖动振幅（16 位采样，±1 LSB，约 -90 dBFS）。
     *
     * 队列空的时候不能补**纯零**：宿主 `l6.e.m()` 会逐块统计全零块，连续 100 块
     * （200ms/块，约 2 秒）就判 `RECORDER_DATAZERO_ERROR`，抛成语音引擎的
     * 「PCM 麦克风故障」（`localErrorType=19`），宿主随即 `WxImeUtil.J1()` 杀掉
     * **全部**微信输入法进程 —— 表现就是「壳跑完一轮，输入法被重启」。
     *
     * ±1 LSB 在识别上等价于静音，但足以让全零判据永远不成立。抖动是确定性的
     * （按采样序号异或），不引入随机数，保证同一段输入每次补出来的字节完全一致。
     */
    private const val SILENCE_DITHER = 1

    /**
     * EOS 之后留给宿主识别引擎收尾的时间。宿主读线程是**拉**模型，EOS 到达时队列里
     * 可能还压着几秒音频；立刻停会话会把没吃完的音频连同最终转录一起丢掉。
     */
    private const val EOS_GRACE_MS = 3000L

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var acceptor: Thread? = null

    @Volatile
    private var client: Socket? = null

    @Volatile
    private var clientOut: DataOutputStream? = null

    @Volatile
    private var running = false

    /** Eta 推进来的 PCM，按到达顺序排队。 */
    private val pcmQueue = LinkedBlockingQueue<ByteArray>()

    /** 待回传给 Eta 的转录，由 [writerLoop] 消费。text to endFlag。 */
    private val transcriptQueue = LinkedBlockingQueue<Pair<String, Boolean>>(TRANSCRIPT_QUEUE_CAPACITY)

    /** 本轮是否已收到 EOS。收到后不再等数据，只补静音直到宿主收尾。 */
    @Volatile
    private var eos = false

    /** 上一段没喂完的尾巴。宿主每次要 640 字节，Eta 推的段长不一定整除。 */
    @Volatile
    private var pending: ByteArray? = null

    private var pendingOffset = 0

    private val framesIn = AtomicLong()
    private val bytesIn = AtomicLong()
    private val silenceFrames = AtomicLong()
    private val transcriptsOut = AtomicInteger()

    /**
     * 喂音闸门。宿主读线程是**拉**模型，队列里一有数据就会全速读完 —— 若不限速，
     * 「边录边推」的实时流会被瞬间抽干，识别引擎拿到的是不连续的时间轴，转录会碎。
     * 所以这里按 16k/单声道 的真实字节速率放行，恒定比读线程慢，让它每轮都刚好等到一点。
     */
    private val paceGate = Any()

    @Volatile
    private var paceStartAt = 0L

    @Volatile
    private var paceBytes = 0L

    /** Eta 连上来时回调（参数是端口）。会话还没起时，这里可以顺势拉起宿主语音。 */
    @Volatile
    var onClientConnected: ((Int) -> Unit)? = null

    /** 收到 EOS 时回调。宿主侧在这里收尾语音会话。 */
    @Volatile
    var onStreamEnd: (() -> Unit)? = null

    // ------------------------------------------------------------------
    // 服务端
    // ------------------------------------------------------------------

    /** 起监听。重复调用幂等。只允许在微信输入法进程里调。 */
    @Synchronized
    fun startServer(port: Int = DEFAULT_PORT): Boolean {
        if (running) return true
        return runCatching {
            val ss = ServerSocket()
            ss.reuseAddress = true
            // 必须显式绑 IPv4 回环：`InetAddress.getLoopbackAddress()` 在这台设备上
            // 解析成 `::1`，只监听 IPv6，IPv4 客户端（Python / nc / 大多数 app 的默认）会
            // 直接 Connection refused。
            ss.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 1)
            serverSocket = ss
            running = true
            acceptor = Thread({ acceptLoop(ss) }, "wetype-voice-accept").apply {
                isDaemon = true
                start()
            }
            Thread(::writerLoop, "wetype-voice-writer").apply {
                isDaemon = true
                start()
            }
            Log.i("$TAG: listening on 127.0.0.1:$port")
            true
        }.onFailure {
            Log.i("$TAG: listen failed on $port: ${it.javaClass.simpleName}: ${it.message}")
        }.getOrDefault(false)
    }

    @Synchronized
    fun stopServer() {
        running = false
        runCatching { client?.close() }
        runCatching { serverSocket?.close() }
        client = null
        clientOut = null
        serverSocket = null
        acceptor = null
    }

    val isListening: Boolean get() = running

    val isClientConnected: Boolean get() = client?.isConnected == true

    private fun acceptLoop(ss: ServerSocket) {
        while (running) {
            val socket = runCatching { ss.accept() }.getOrNull() ?: break
            attachClient(socket)
        }
        Log.i("$TAG: accept loop exited")
    }

    @Synchronized
    private fun attachClient(socket: Socket) {
        // 单客户端模型：新连接顶掉旧的。Eta 重连时不必先等旧连接超时。
        runCatching { client?.close() }
        runCatching { socket.tcpNoDelay = true }
        client = socket
        clientOut = runCatching { DataOutputStream(socket.getOutputStream()) }.getOrNull()
        resetStream()
        Log.i("$TAG: client connected from ${socket.inetAddress?.hostAddress}")
        Thread({ readLoop(socket) }, "wetype-voice-client").apply {
            isDaemon = true
            start()
        }
    }

    /** 客户端还没有按 [START_AFTER_FRAMES] 攒够帧时，最多先替它挡住读线程多久。 */
    private const val STARTUP_POLL_MS = 10L

    /**
     * 攒够 [START_AFTER_FRAMES] 帧再拉起宿主会话。
     *
     * 为什么不能一连上就拉：宿主读线程一旦起来就会连续拉数据，而我们拉起的动作比
     * 客户端推第一帧要早几毫秒，读线程会先吃到一段静音。识别引擎把「开头的静音」
     * 当作这一轮没有语音，后面补上真音频也不再出字（实测：帧数 42、静音计数 0、
     * 转录 0）。所以让音频先到，再开会话。
     *
     * 另外，会话拉起之前读线程必须尽快拿到数据：它在 [nextChunk] 里拿到 null 就会补静音，
     * 连补 100 帧（约 2 秒）宿主就判 `RECORDER_DATAZERO_ERROR` 收尾。所以未拉起阶段用
     * [STARTUP_POLL_MS] 短周期轮询，把「等客户端第一帧」的静音量压到可忽略。
     */
    private fun maybeStartSession() {
        if (sessionStartRequested) return
        if (framesIn.get() < START_AFTER_FRAMES) return
        sessionStartRequested = true
        runCatching { onClientConnected?.invoke(0) }
    }

    private fun readLoop(socket: Socket) {
        val input = runCatching { DataInputStream(socket.getInputStream()) }.getOrNull() ?: return
        try {
            while (running && !socket.isClosed) {
                val type = input.readUnsignedByte()
                when (type) {
                    FRAME_PCM -> {
                        val len = input.readInt()
                        if (len <= 0 || len > MAX_FRAME_BYTES) {
                            Log.i("$TAG: bad pcm frame len=$len, dropping connection")
                            break
                        }
                        val pcm = ByteArray(len)
                        input.readFully(pcm)
                        pcmQueue.offer(pcm)
                        framesIn.incrementAndGet()
                        bytesIn.addAndGet(len.toLong())
                        maybeStartSession()
                    }
                    FRAME_EOS -> {
                        eos = true
                        Log.i("$TAG: EOS received (frames=${framesIn.get()} bytes=${bytesIn.get()})")
                        scheduleStreamEnd()
                    }
                    else -> {
                        Log.i("$TAG: unknown frame type $type, dropping connection")
                        break
                    }
                }
            }
        } catch (_: EOFException) {
            Log.i("$TAG: client closed")
        } catch (t: Throwable) {
            if (running) Log.i("$TAG: client read failed: ${t.javaClass.simpleName}: ${t.message}")
        } finally {
            synchronized(this) {
                if (client === socket) {
                    client = null
                    clientOut = null
                }
            }
            runCatching { socket.close() }
        }
    }

    // ------------------------------------------------------------------
    // 转录回传
    // ------------------------------------------------------------------

    /** 宿主识别引擎出字时调这里。没有客户端时静默丢弃。 */
    fun emitTranscript(text: String, endFlag: Boolean) {
        val n = transcriptsOut.incrementAndGet()
        if (n <= 3 || n % 20 == 0) {
            Log.i("$TAG: transcript#$n end=$endFlag len=${text.length} text=${text.take(40)}")
        }
        if (text.isEmpty() && !endFlag) return
        // 只入队，绝不在这里写 socket：转录回调跑在**主线程**上，主线程做网络 I/O
        // 会直接抛 NetworkOnMainThreadException（实测就是这样，一个字都没发出去）。
        // 真正的写由 [writerLoop] 在独立线程上做。
        if (!transcriptQueue.offer(text to endFlag)) {
            Log.i("$TAG: transcript queue full, dropping")
        }
    }

    /**
     * 转录回传线程。把 [emitTranscript] 入队的文本写到客户端。
     *
     * 队列有界（[TRANSCRIPT_QUEUE_CAPACITY]）：客户端掉线时宁可丢转录，也不能让
     * 识别引擎的回调线程堆在内存里。
     */
    private fun writerLoop() {
        while (running) {
            val item = runCatching {
                transcriptQueue.poll(STARVE_POLL_MS, TimeUnit.MILLISECONDS)
            }.getOrNull() ?: continue
            val (text, endFlag) = item
            val out = clientOut ?: continue
            runCatching {
                val bytes = text.toByteArray(Charsets.UTF_8)
                synchronized(this) {
                    out.writeByte(FRAME_TRANSCRIPT)
                    out.writeByte(if (endFlag) 1 else 0)
                    out.writeInt(bytes.size)
                    out.write(bytes)
                    out.flush()
                }
            }.onFailure {
                Log.i("$TAG: transcript write failed: ${it.javaClass.simpleName}: ${it.message}")
            }
        }
    }

    // ------------------------------------------------------------------
    // 供数（被 FakeAudioRecord 调用）
    // ------------------------------------------------------------------

    /** 开一轮新会话：清队列、清 EOS、清尾巴、重置限速基准。 */
    @Synchronized
    fun resetStream() {
        pcmQueue.clear()
        eos = false
        pending = null
        pendingOffset = 0
        silenceFrames.set(0)
        sessionStartRequested = false
        synchronized(paceGate) {
            paceStartAt = System.currentTimeMillis()
            paceBytes = 0
        }
    }

    /**
     * 取 [size] 字节 PCM。**永远返回 [size]**（除非 [size] ≤ 0）。
     *
     * 队列空就最多等 [STARVE_POLL_MS] 一次；还没数据就补零静音。
     * 这是刻意的：返回 0 会让宿主认为录音结束并收尾会话。
     */
    fun pull(dst: ByteArray, dstOffset: Int, size: Int): Int {
        if (size <= 0 || dstOffset < 0 || dstOffset + size > dst.size) return 0
        pace(size)
        var filled = 0
        while (filled < size) {
            val chunk = nextChunk() ?: break
            val take = minOf(size - filled, chunk.size - pendingOffset)
            System.arraycopy(chunk, pendingOffset, dst, dstOffset + filled, take)
            pendingOffset += take
            filled += take
            if (pendingOffset >= chunk.size) {
                pending = null
                pendingOffset = 0
            }
        }
        if (filled < size) {
            fillSilence(dst, dstOffset + filled, size - filled)
            silenceFrames.incrementAndGet()
        }
        return size
    }

    /**
     * 补 [len] 字节「静音」。**不是全零**，见 [SILENCE_DITHER]：交替写 `0x01 0x00`
     * （即每个 16 位采样 +1 LSB），保证宿主每一块都能扫到非零字节。
     */
    private fun fillSilence(dst: ByteArray, offset: Int, len: Int) {
        var i = 0
        while (i < len) {
            dst[offset + i] = if ((offset + i) and 1 == 0) SILENCE_DITHER.toByte() else 0
            i++
        }
    }

    /**
     * 按真实字节速率放行，并设一个「已喂到的音频时刻」上限。
     *
     * 两层作用：
     * 1. 实时流（Eta 边录边推）不会被读线程瞬间抽干。
     * 2. 存量流（一次性把整段推完）**也不会被抢跑** —— 已喂出的字节数不允许超过
     *    「开流至今 × 字节速率 + [LEAD_MS] 的余量」，于是宿主永远拿到时间连续的音频。
     */
    private fun pace(size: Int) {
        while (true) {
            val waitMs: Long
            synchronized(paceGate) {
                val elapsed = System.currentTimeMillis() - paceStartAt
                val budget = elapsed * BYTES_PER_SEC / 1000 + LEAD_MS * BYTES_PER_SEC / 1000
                if (paceBytes + size <= budget) {
                    paceBytes += size
                    return
                }
                waitMs = (paceBytes + size - budget) * 1000 / BYTES_PER_SEC
            }
            // 单次最多睡 20ms，睡完重新算 —— 会话中途 reset 也不会睡死。
            runCatching { Thread.sleep(waitMs.coerceIn(1L, 20L)) }
        }
    }

    /** 取下一段可用的 PCM，没有就阻塞至多 [STARVE_POLL_MS]，再没有返回 null（补静音）。 */
    private fun nextChunk(): ByteArray? {
        pending?.let { if (pendingOffset < it.size) return it }
        if (eos) return null
        // 会话还没拉起来时用短周期轮询：这一轮随时可能开始，而读线程一旦在这里
        // 拿到 null 就会退出。见 [maybeStartSession]。
        val wait = if (sessionStartRequested) STARVE_POLL_MS else STARTUP_POLL_MS
        val next = runCatching { pcmQueue.poll(wait, TimeUnit.MILLISECONDS) }.getOrNull()
            ?: return null
        pending = next
        pendingOffset = 0
        return next
    }

    /**
     * EOS 之后留给宿主收尾。延迟 [EOS_GRACE_MS] 再回调 [onStreamEnd]，
     * 让读线程把队列里剩下的音频吃完、出完最后一段转录。
     */
    private fun scheduleStreamEnd() {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        handler.postDelayed({
            if (!running) return@postDelayed
            Log.i("$TAG: EOS grace elapsed, ${stats()}")
            runCatching { onStreamEnd?.invoke() }
        }, EOS_GRACE_MS)
    }

    /** 本轮是否已请求拉起宿主会话。 */
    @Volatile
    private var sessionStartRequested = false

    /** 诊断串，进日志用。 */    fun stats(): String =
        "listen=$running client=${isClientConnected} frames=${framesIn.get()} " +
            "bytes=${bytesIn.get()} queued=${pcmQueue.size} eos=$eos " +
            "silence=${silenceFrames.get()} transcripts=${transcriptsOut.get()}"
}

/**
 * 顶替宿主录音器里那个真 `AudioRecord` 的假货。
 *
 * 必须是真子类（宿主会对它做 `startRecording()` / `getRecordingState()` / `instanceof`），
 * 但所有碰原生层的方法全部空实现 —— 于是 app op → audioserver 静音这条链彻底不参与，
 * 键盘可见性也不再影响录音。
 *
 * `read()` 从 [WeTypeVoiceBridge] 的队列取数据，队列空补静音，**永不返回 0**。
 */
internal class FakeAudioRecord(
    sampleRate: Int,
    channelMask: Int,
    encoding: Int,
    bufferSize: Int
) : AudioRecord(
    MediaRecorder.AudioSource.MIC, sampleRate, channelMask, encoding, bufferSize
) {

    override fun getState(): Int = STATE_INITIALIZED

    override fun getRecordingState(): Int = RECORDSTATE_RECORDING

    override fun startRecording() {
        // 不碰硬件。宿主靠 getRecordingState() 判断是否成功，已经覆盖。
    }

    override fun stop() {
        // 同上。
    }

    override fun release() {
        // 不 release 原生对象：宿主 stopRecord 会对它调一次，
        // release 掉下次会话就得重建。
    }

    private fun fill(dst: ByteArray, dstOffset: Int, size: Int): Int =
        WeTypeVoiceBridge.pull(dst, dstOffset, size)

    override fun read(audioData: ByteArray, offsetInBytes: Int, sizeInBytes: Int): Int =
        fill(audioData, offsetInBytes, sizeInBytes)

    // API 23 起 `read` 多了一个 readMode 尾巴，宿主 3.5.3 走的就是带尾巴的那几个重载。
    // 只盖三参数版本会让调用落到真 `AudioRecord.read` 上 —— 那个原生对象从没 startRecording
    // 过，于是永远读不出数据：表现为「换了录音器、日志也正常，但一帧都没进引擎」。
    // 所有重载必须一起盖，否则走哪条完全取决于宿主的编译期选择。
    override fun read(
        audioData: ByteArray,
        offsetInBytes: Int,
        sizeInBytes: Int,
        readMode: Int
    ): Int = fill(audioData, offsetInBytes, sizeInBytes)

    override fun read(audioData: ShortArray, offsetInShorts: Int, sizeInShorts: Int): Int {
        val bytes = ByteArray(sizeInShorts * 2)
        val n = fill(bytes, 0, bytes.size)
        var i = 0
        while (i + 1 < n) {
            audioData[offsetInShorts + i / 2] =
                ((bytes[i].toInt() and 0xFF) or (bytes[i + 1].toInt() shl 8)).toShort()
            i += 2
        }
        return n / 2
    }

    override fun read(
        audioData: ShortArray,
        offsetInShorts: Int,
        sizeInShorts: Int,
        readMode: Int
    ): Int = read(audioData, offsetInShorts, sizeInShorts)

    override fun read(audioData: java.nio.ByteBuffer, sizeInBytes: Int): Int {
        val bytes = ByteArray(sizeInBytes)
        val n = fill(bytes, 0, sizeInBytes)
        audioData.put(bytes, 0, n)
        return n
    }

    override fun read(audioData: java.nio.ByteBuffer, sizeInBytes: Int, readMode: Int): Int =
        read(audioData, sizeInBytes)

    /** API 33 的浮点重载。宿主没用到，但盖全了才不会随版本漂移。 */
    override fun read(
        audioData: FloatArray,
        offsetInFloats: Int,
        sizeInFloats: Int,
        readMode: Int
    ): Int {
        val shorts = ShortArray(sizeInFloats)
        val n = read(shorts, 0, sizeInFloats)
        var i = 0
        while (i < n) {
            audioData[offsetInFloats + i] = shorts[i] / 32768f
            i++
        }
        return n
    }

    companion object {
        /** 按真实实例的采样参数造一个假货。 */
        fun from(real: AudioRecord): FakeAudioRecord? = runCatching {
            val sampleRate = real.sampleRate
            val channels = real.channelCount
            val encoding = real.audioFormat
            val mask = if (channels >= 2) AudioFormat.CHANNEL_IN_STEREO
            else AudioFormat.CHANNEL_IN_MONO
            val minBuf = AudioRecord.getMinBufferSize(sampleRate, mask, encoding)
            val bufferSize = if (minBuf > 0) minBuf * 4 else sampleRate * channels * 2 * 40
            FakeAudioRecord(sampleRate, mask, encoding, bufferSize)
        }.getOrNull()
    }
}
