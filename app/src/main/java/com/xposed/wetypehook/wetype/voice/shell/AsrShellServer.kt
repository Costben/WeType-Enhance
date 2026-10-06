package com.xposed.wetypehook.wetype.voice.shell

import android.util.Log
import com.xposed.wetypehook.wetype.settings.VoiceTuning
import com.xposed.wetypehook.wetype.voice.WeTypeVoiceProtocol
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.locks.ReentrantLock

/**
 * 模块内的本地 HTTP / WebSocket 壳：把微信输入法的识别引擎按 **千问 · 实时** 与
 * **豆包 · 流式** 两种协议外观吐出去，供 Eta 直接填地址使用。
 *
 * ```
 * Eta ──千问/豆包 协议──▶ 本服务 ──PCM──▶ WeTypeVoiceClient ──TCP 127.0.0.1:18515──▶ 微信输入法进程内的桥 ──▶ 宿主识别引擎
 *                                                                                        │
 *                                              Eta ◀──协议形状的转录回包◀──────────────────────┘
 * ```
 *
 * ## 本服务**不碰麦克风**
 *
 * 音频只有一个来源：客户端通过协议推过来的 PCM。壳自己开 `AudioRecord` 是错的方向 ——
 * 那样 Eta 说什么、什么时候停都不再可控，识别质量也无法与用户实际说的话对齐。
 *
 * ## 线程模型
 *
 * 一个连接一个线程，没有线程池抽象：这是只服务回环的壳，并发上限就是「Eta 开几路」，
 * 而下面那条单会话锁把并发压到 1。
 *
 * ## 单会话锁
 *
 * 回环桥（[com.xposed.wetypehook.wetype.voice.WeTypeVoiceBridge]）是**单客户端模型** ——
 * 新连接会把旧连接顶掉。所以这里同一时刻只放行一路识别会话，第二路直接回一条「忙」的
 * 协议错误并关连接，而不是让两路互相把对方的音频抽走。
 *
 * ## 容错
 *
 * 端口被占、客户端半路断开、发非法帧，全部只记日志并干净关掉**那一条连接**，
 * 不影响监听套接字，也不影响其它连接。
 */
internal class AsrShellServer(
    private val port: Int,
    private val allowLan: Boolean,
    private val tuning: () -> VoiceTuning,
    private val bridgePort: Int = WeTypeVoiceProtocol.DEFAULT_PORT
) {

    companion object {
        const val PATH_HEALTHZ = "/healthz"
        const val PATH_QWEN = "/api-ws/v1/realtime"
        const val PATH_DOUBAO = "/api/v3/sauc/bigmodel_async"

        /** 握手请求头上限。Eta 的握手也就几百字节，16KB 是防呆。 */
        private const val MAX_HEADER_BYTES = 16 * 1024
        private const val MAX_HEADER_LINES = 64
        private const val MAX_LINE_BYTES = 4 * 1024
    }

    private val sessionLock = ReentrantLock()
    private val connections = Collections.synchronizedSet(mutableSetOf<Socket>())

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var acceptThread: Thread? = null

    @Volatile
    private var running = false

    val isRunning: Boolean get() = running

    /** 起监听。返回 false 表示端口被占 / 地址不可用；调用方负责把它报给用户。 */
    @Synchronized
    fun start(): Boolean {
        if (running) return true
        val address = runCatching {
            // 只绑回环时不解析成 `::1`：Eta 与 Python 客户端默认走 IPv4，
            // 只监听 IPv6 会直接 Connection refused。
            InetAddress.getByName(if (allowLan) "0.0.0.0" else "127.0.0.1")
        }.getOrNull() ?: return false

        return runCatching {
            // 只跑明文 HTTP：直接起一个普通监听套接字，不做任何 TLS 协商。
            val socket = ServerSocket()
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(address, port), 4)
            serverSocket = socket
            running = true
            acceptThread = Thread({ acceptLoop(socket) }, "asr-shell-accept").apply {
                isDaemon = true
                start()
            }
            Log.i(ASR_SHELL_TAG, "listening on ${address.hostAddress}:$port")
            true
        }.onFailure {
            Log.w(
                ASR_SHELL_TAG,
                "listen failed on ${address.hostAddress}:$port: ${it.javaClass.simpleName}: ${it.message}"
            )
        }.getOrDefault(false)
    }

    @Synchronized
    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptThread = null
        // `connections` 是 synchronizedSet，迭代必须自己持锁：连接线程的 remove 会与
        // 这里的 toList 并发，不持锁会撞上 fail-fast 迭代器。
        val live = synchronized(connections) { connections.toList() }
        connections.clear()
        live.forEach { runCatching { it.close() } }
        Log.i(ASR_SHELL_TAG, "stopped")
    }

    private fun acceptLoop(server: ServerSocket) {
        while (running) {
            val socket = runCatching { server.accept() }.getOrNull() ?: break
            connections.add(socket)
            Thread({ handleConnection(socket) }, "asr-shell-conn").apply {
                isDaemon = true
                start()
            }
        }
        Log.i(ASR_SHELL_TAG, "accept loop exited")
    }

    // ------------------------------------------------------------------
    // 连接
    // ------------------------------------------------------------------

    private fun handleConnection(socket: Socket) {
        try {
            runCatching { socket.tcpNoDelay = true }
            val connection = Connection(socket)
            try {
                serve(connection)
            } finally {
                connection.close()
            }
        } catch (t: Throwable) {
            Log.w(ASR_SHELL_TAG, "connection failed: ${t.javaClass.simpleName}: ${t.message}")
        } finally {
            connections.remove(socket)
        }
    }

    private fun serve(connection: Connection) {
        val request = readRequest(connection.input)
        when {
            request == null -> Unit

            request.method == "GET" && request.path == PATH_HEALTHZ ->
                connection.write(
                    httpResponse(
                        200, "OK", "text/plain; charset=utf-8", "ok".toByteArray()
                    )
                )

            request.isWebSocketUpgrade && request.path == PATH_QWEN ->
                serveSession(connection, request, qwen = true)

            request.isWebSocketUpgrade && request.path == PATH_DOUBAO ->
                serveSession(connection, request, qwen = false)

            else -> connection.write(
                httpResponse(
                    404, "Not Found", "text/plain; charset=utf-8", "not found".toByteArray()
                )
            )
        }
    }

    private fun serveSession(connection: Connection, request: HttpRequest, qwen: Boolean) {
        if (!performHandshake(connection, request)) return

        val sendText: (ByteArray) -> Boolean =
            { connection.write(WebSocketFrames.encode(WsOpcode.TEXT, it)) }
        val sendBinary: (ByteArray) -> Boolean =
            { connection.write(WebSocketFrames.encode(WsOpcode.BINARY, it)) }

        if (!sessionLock.tryLock()) {
            Log.w(ASR_SHELL_TAG, "another session is running, rejecting ${request.path}")
            rejectBusy(connection, sendText, sendBinary, qwen)
            return
        }

        val snapshot = runCatching { tuning() }.getOrNull() ?: VoiceTuning(
            silenceFinishMs = 0,
            noSpeechFinishMs = 0,
            silencePeak = 0,
            eosQuietMs = 900,
            eosMaxWaitMs = 6000
        )
        val session: AsrShellSession = if (qwen) {
            QwenRealtimeSession(sendText, sendBinary, connection::close, snapshot, bridgePort)
        } else {
            DoubaoRealtimeSession(sendText, sendBinary, connection::close, snapshot, bridgePort)
        }

        var closeCode: Int? = WsOpcode.CLOSE_NORMAL
        try {
            closeCode = runFrameLoop(connection, session, qwen)
        } finally {
            session.close()
            sessionLock.unlock()
        }
        closeCode?.let { code ->
            connection.write(
                WebSocketFrames.encode(
                    WsOpcode.CLOSE,
                    WebSocketFrames.closePayload(code)
                )
            )
        }
    }

    /**
     * 帧循环。返回要回给对端的 close code；返回 null 表示连接已经坏了，什么都不用回。
     */
    private fun runFrameLoop(
        connection: Connection,
        session: AsrShellSession,
        qwen: Boolean
    ): Int? {
        val reader = WebSocketFrameReader(connection.input)
        try {
            while (true) {
                val message = reader.read() ?: return null
                when (message.opcode) {
                    WsOpcode.TEXT -> {
                        if (qwen) {
                            val keepGoing =
                                (session as QwenRealtimeSession).onText(
                                    String(message.payload, Charsets.UTF_8)
                                )
                            if (!keepGoing) return WsOpcode.CLOSE_NORMAL
                        } else {
                            Log.w(ASR_SHELL_TAG, "doubao: 收到文本帧，按协议只认二进制")
                        }
                    }

                    WsOpcode.BINARY -> {
                        val keepGoing = if (qwen) {
                            (session as QwenRealtimeSession).onBinary(message.payload)
                            true
                        } else {
                            (session as DoubaoRealtimeSession).onBinary(message.payload)
                        }
                        if (!keepGoing) return WsOpcode.CLOSE_NORMAL
                    }

                    WsOpcode.PING ->
                        connection.write(WebSocketFrames.encode(WsOpcode.PONG, message.payload))

                    WsOpcode.PONG -> Unit

                    WsOpcode.CLOSE -> {
                        Log.i(ASR_SHELL_TAG, "client sent close")
                        return WsOpcode.CLOSE_NORMAL
                    }

                    else -> Log.w(ASR_SHELL_TAG, "ignoring opcode ${message.opcode}")
                }
            }
        } catch (t: WebSocketProtocolException) {
            Log.w(ASR_SHELL_TAG, "protocol error: ${t.message}")
            return WsOpcode.CLOSE_PROTOCOL_ERROR
        } catch (t: Throwable) {
            // 对端半路断开 / 帧被截断：只影响这一条连接。
            Log.w(ASR_SHELL_TAG, "session aborted: ${t.javaClass.simpleName}: ${t.message}")
            return null
        }
    }

    /** 已经有会话在跑。桥是单客户端模型，第二路会把第一路顶掉，所以直接拒。 */
    private fun rejectBusy(
        connection: Connection,
        sendText: (ByteArray) -> Boolean,
        sendBinary: (ByteArray) -> Boolean,
        qwen: Boolean
    ) {
        val message = "同一时刻只允许一路识别，请稍后重试"
        if (qwen) {
            sendText(QwenEvents.error(message).toByteArray(Charsets.UTF_8))
        } else {
            sendBinary(
                DoubaoProtocol.encode(
                    msgType = DoubaoProtocol.MSG_SERVER_ERROR,
                    flags = 0,
                    serialization = DoubaoProtocol.SERIALIZATION_JSON,
                    payload = DoubaoJson.error(1013, message)
                )
            )
        }
        connection.write(
            WebSocketFrames.encode(
                WsOpcode.CLOSE,
                WebSocketFrames.closePayload(WsOpcode.CLOSE_TRY_AGAIN_LATER, message)
            )
        )
    }

    // ------------------------------------------------------------------
    // HTTP
    // ------------------------------------------------------------------

    private fun performHandshake(connection: Connection, request: HttpRequest): Boolean {
        val key = request.header("sec-websocket-key")?.trim().orEmpty()
        if (key.isEmpty()) {
            connection.write(
                httpResponse(
                    400, "Bad Request", "text/plain; charset=utf-8",
                    "missing Sec-WebSocket-Key".toByteArray()
                )
            )
            return false
        }
        val response = buildString {
            append("HTTP/1.1 101 Switching Protocols\r\n")
            append("Upgrade: websocket\r\n")
            append("Connection: Upgrade\r\n")
            append("Sec-WebSocket-Accept: ").append(WebSocketFrames.acceptKey(key)).append("\r\n")
            append("\r\n")
        }
        return connection.write(response.toByteArray(Charsets.US_ASCII))
    }

    private fun readRequest(input: InputStream): HttpRequest? {
        val requestLine = readHttpLine(input) ?: return null
        if (requestLine.isBlank()) return null
        val parts = requestLine.split(' ')
        if (parts.size < 3) throw WebSocketProtocolException("请求行不合法：$requestLine")

        val headers = LinkedHashMap<String, String>()
        var total = requestLine.length
        while (true) {
            val line = readHttpLine(input) ?: break
            if (line.isEmpty()) break
            total += line.length
            if (total > MAX_HEADER_BYTES) throw WebSocketProtocolException("请求头过大")
            if (headers.size >= MAX_HEADER_LINES) throw WebSocketProtocolException("请求头太多")
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        return HttpRequest(parts[0].uppercase(), parts[1], parts[2], headers)
    }

    /** 读一行（LF 结尾，CRLF 也认）。返回 null 表示读到流尾且一个字节都没有。 */
    private fun readHttpLine(input: InputStream): String? {
        val buffer = ByteArrayOutputStream()
        while (true) {
            val byte = input.read()
            if (byte < 0) return if (buffer.size() == 0) null else decodeLine(buffer)
            if (byte == '\n'.code) return decodeLine(buffer)
            if (buffer.size() > MAX_LINE_BYTES) throw WebSocketProtocolException("HTTP 行过长")
            buffer.write(byte)
        }
    }

    private fun decodeLine(buffer: ByteArrayOutputStream): String {
        val bytes = buffer.toByteArray()
        val length = if (bytes.isNotEmpty() && bytes[bytes.size - 1] == '\r'.code.toByte()) {
            bytes.size - 1
        } else {
            bytes.size
        }
        return String(bytes, 0, length, Charsets.ISO_8859_1)
    }

    private fun httpResponse(
        status: Int,
        reason: String,
        contentType: String,
        body: ByteArray
    ): ByteArray {
        val head = buildString {
            append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
            append("Content-Type: ").append(contentType).append("\r\n")
            append("Content-Length: ").append(body.size).append("\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }.toByteArray(Charsets.US_ASCII)
        return head + body
    }

    private class HttpRequest(
        val method: String,
        val target: String,
        val version: String,
        private val headers: Map<String, String>
    ) {
        val path: String = target.substringBefore('?')

        val isWebSocketUpgrade: Boolean
            get() = headers["upgrade"]?.contains("websocket", ignoreCase = true) == true

        fun header(name: String): String? = headers[name.lowercase()]
    }

    /** 一条连接。写操作串行化，因为转录回调与连接线程会同时写。 */
    private class Connection(val socket: Socket) {

        val input: InputStream = BufferedInputStream(socket.getInputStream())

        private val output: OutputStream = BufferedOutputStream(socket.getOutputStream())
        private val lock = Any()

        @Volatile
        private var closed = false

        fun write(bytes: ByteArray): Boolean = synchronized(lock) {
            if (closed) return false
            try {
                output.write(bytes)
                output.flush()
                true
            } catch (t: Throwable) {
                Log.w(ASR_SHELL_TAG, "write failed: ${t.javaClass.simpleName}: ${t.message}")
                closed = true
                false
            }
        }

        fun close() {
            synchronized(lock) { closed = true }
            runCatching { socket.close() }
        }
    }
}
