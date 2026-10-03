package com.xposed.wetypehook.wetype.voice

import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 回环语音桥的**客户端**，跑在模块自己的进程里（不像 [WeTypeVoiceBridge] 那样在微信输入法进程内）。
 *
 * 与 [WeTypeRecognitionService] 的分工：服务负责对 Eta 说话（`RecognitionService.Callback`），
 * 本类只负责对微信输入法说话（`127.0.0.1:18515`）。协议见 [WeTypeVoiceProtocol]。
 *
 * 会话生命周期**由连接驱动**（宿主侧 [WeTypeVoiceBridge] 的设计如此）：客户端连上并推满
 * 若干帧 PCM 后，宿主才会拉起它自己的语音会话；客户端发 EOS 后，宿主吃掉队列里剩余的音频、
 * 出完最后一段转录，再收尾。所以这里必须真的把麦克风数据推过去，不能只连不发。
 *
 * 线程模型：写入只由采集线程做（[sendPcm] / [sendEos]），读取由本类自己的读线程做，
 * 回调 [onTranscript] 就在读线程上触发 —— 调用方要保证回调里不做阻塞操作。
 */
internal class WeTypeVoiceClient(
    private val onTranscript: (text: String, endFlag: Boolean) -> Unit,
    private val onDisconnected: () -> Unit
) {

    private companion object {
        const val TAG = "WeTypeVoice"

        /** 连接超时。桥只在本机回环，连不上基本就是微信输入法进程没起来，没必要久等。 */
        const val CONNECT_TIMEOUT_MS = 800

        /** 单帧上限，与宿主侧对齐。 */
        const val MAX_FRAME_BYTES = WeTypeVoiceProtocol.MAX_FRAME_BYTES
    }

    @Volatile
    private var socket: Socket? = null

    @Volatile
    private var output: DataOutputStream? = null

    @Volatile
    private var running = false

    private var readerThread: Thread? = null

    /** 串行化写入，避免 EOS 与 PCM 帧在流里交错。 */
    private val writeLock = Any()

    val isConnected: Boolean get() = running && socket?.isConnected == true

    /** 连上宿主桥。重复调用会先断旧连接。 */
    fun connect(port: Int): Boolean {
        close()
        val connected = try {
            Socket().apply {
                tcpNoDelay = true
                // 必须显式 IPv4 回环：宿主侧只绑了 127.0.0.1，解析成 ::1 会直接 Connection refused。
                connect(
                    InetSocketAddress(InetAddress.getByName("127.0.0.1"), port),
                    CONNECT_TIMEOUT_MS
                )
            }
        } catch (t: Throwable) {
            // 一定把原因打出来：连不上可能是「桥没起」也可能是「被策略拦了」，
            // 两者的排查方向完全不同，只报一句 failed 等于把线索扔掉。
            Log.w(TAG, "bridge connect failed on 127.0.0.1:$port: ${t.javaClass.name}: ${t.message}")
            return false
        }
        val stream = runCatching { DataOutputStream(connected.getOutputStream()) }.getOrNull()
        if (stream == null) {
            runCatching { connected.close() }
            return false
        }
        socket = connected
        output = stream
        running = true
        readerThread = Thread({ readLoop(connected) }, "wetype-asr-read").apply {
            isDaemon = true
            start()
        }
        Log.i(TAG, "bridge connected on 127.0.0.1:$port")
        return true
    }

    /** 推一段 16k / 单声道 / PCM16。返回 false 表示连接已断。 */
    fun sendPcm(data: ByteArray, offset: Int, length: Int): Boolean {
        if (length <= 0 || offset < 0 || offset + length > data.size) return false
        if (length > MAX_FRAME_BYTES) return false
        val stream = output ?: return false
        return runCatching {
            synchronized(writeLock) {
                stream.writeByte(WeTypeVoiceProtocol.FRAME_PCM)
                stream.writeInt(length)
                stream.write(data, offset, length)
                stream.flush()
            }
            true
        }.getOrElse {
            Log.w(TAG, "pcm write failed: ${it.javaClass.simpleName}: ${it.message}")
            false
        }
    }

    /** 本轮结束。宿主收到后会吃完剩余音频再收尾，并回最后一段转录。 */
    fun sendEos(): Boolean {
        val stream = output ?: return false
        return runCatching {
            synchronized(writeLock) {
                stream.writeByte(WeTypeVoiceProtocol.FRAME_EOS)
                stream.flush()
            }
            Log.i(TAG, "EOS sent")
            true
        }.getOrElse {
            Log.w(TAG, "eos write failed: ${it.javaClass.simpleName}: ${it.message}")
            false
        }
    }

    fun close() {
        running = false
        val current = socket
        socket = null
        output = null
        runCatching { current?.close() }
        readerThread = null
    }

    private fun readLoop(connected: Socket) {
        val input = runCatching { DataInputStream(connected.getInputStream()) }.getOrNull()
        if (input == null) {
            notifyDisconnected(connected)
            return
        }
        try {
            while (running && !connected.isClosed) {
                val type = input.readUnsignedByte()
                if (type != WeTypeVoiceProtocol.FRAME_TRANSCRIPT) {
                    Log.w(TAG, "unknown frame type $type from bridge, dropping connection")
                    break
                }
                val endFlag = input.readUnsignedByte() != 0
                val length = input.readInt()
                if (length < 0 || length > MAX_FRAME_BYTES) {
                    Log.w(TAG, "bad transcript length $length, dropping connection")
                    break
                }
                val bytes = ByteArray(length)
                input.readFully(bytes)
                onTranscript(String(bytes, Charsets.UTF_8), endFlag)
            }
        } catch (_: EOFException) {
            Log.i(TAG, "bridge closed the connection")
        } catch (t: Throwable) {
            if (running) Log.w(TAG, "bridge read failed: ${t.javaClass.simpleName}: ${t.message}")
        } finally {
            notifyDisconnected(connected)
        }
    }

    private fun notifyDisconnected(connected: Socket) {
        // 只有「当前这条连接」断了才算掉线；被新连接顶掉的旧连接不该惊动调用方。
        val wasCurrent = synchronized(this) {
            if (socket !== connected) return
            running = false
            socket = null
            output = null
            true
        }
        if (wasCurrent) onDisconnected()
    }
}