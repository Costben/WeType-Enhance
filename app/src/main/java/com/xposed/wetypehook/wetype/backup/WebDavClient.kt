package com.xposed.wetypehook.wetype.backup

import com.xposed.wetypehook.wetype.clipboard.ClipboardBackupArchive
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

internal class WebDavHttpException(
    val statusCode: Int,
    message: String
) : Exception(message)

internal class WebDavCancelledException : Exception("cancelled")

/**
 * WebDAV 薄客户端（零新依赖）。
 *
 * Android 的 `HttpURLConnection#setRequestMethod` 拒绝 PROPFIND / MKCOL
 * （`Expected one of [OPTIONS, GET, HEAD, POST, PUT, DELETE, TRACE, PATCH]`），
 * 因此这里用 Socket/SSLSocket 直接实现极简 HTTP/1.1，覆盖
 * PROPFIND / MKCOL / PUT / GET / DELETE。
 *
 * 约束：单请求单连接（`Connection: close`），不支持系统代理。
 */
internal class WebDavClient(
    rawBaseUrl: String,
    private val username: String,
    private val password: String,
    private val allowInsecureTls: Boolean,
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 120_000
) {

    val baseUrl: String = WebDavPaths.normalizeBase(rawBaseUrl)

    /** 校验地址可达与账号权限（对根地址做 Depth 0 的 PROPFIND）。 */
    fun testConnection(): Result<Unit> = runCatching {
        request(
            "PROPFIND",
            URL(baseUrl),
            depth = "0",
            contentType = XML_CONTENT_TYPE,
            body = PROPFIND_ALLPROP.toByteArray(StandardCharsets.UTF_8)
        )
            .use { response -> ensureSuccess(response.statusCode, "PROPFIND") }
    }

    /** 逐级创建远程目录；已存在（405）视为成功。 */
    fun ensureDirectory(remoteDir: String): Result<Unit> = runCatching {
        val segments = WebDavPaths.splitSegments(remoteDir)
        if (segments.isEmpty()) return@runCatching
        var path = ""
        for (segment in segments) {
            path = if (path.isEmpty()) segment else "$path/$segment"
            val url = URL(WebDavPaths.join(baseUrl, WebDavPaths.splitSegments(path)))
            request("MKCOL", url).use { response ->
                val code = response.statusCode
                if (code !in 200..299 && code != 405) {
                    throw WebDavHttpException(code, "MKCOL $path -> HTTP $code")
                }
            }
        }
    }

    fun upload(
        remoteDir: String,
        file: File,
        fileName: String,
        isCancelled: (() -> Boolean)? = null,
        onProgress: ((written: Long, total: Long) -> Unit)? = null
    ): Result<Unit> = runCatching {
        val url = URL(WebDavPaths.join(baseUrl, WebDavPaths.splitSegments(remoteDir) + fileName))
        val total = file.length()
        var written = 0L
        request(
            method = "PUT",
            url = url,
            contentType = ClipboardBackupArchive.MIME_ZIP,
            bodyLength = total
        ) { output ->
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    written += read
                    onProgress?.invoke(written, total)
                    if (isCancelled?.invoke() == true) throw WebDavCancelledException()
                }
            }
        }.use { response -> ensureSuccess(response.statusCode, "PUT") }
    }

    fun list(remoteDir: String): Result<List<WebDavRemoteEntry>> = runCatching {
        val dirUrl = WebDavPaths.join(baseUrl, WebDavPaths.splitSegments(remoteDir))
        request(
            method = "PROPFIND",
            url = URL(dirUrl),
            depth = "1",
            contentType = XML_CONTENT_TYPE,
            body = PROPFIND_ALLPROP.toByteArray(StandardCharsets.UTF_8)
        ).use { response ->
            ensureSuccess(response.statusCode, "PROPFIND")
            val body = response.readBodyText()
            WebDavPropfindParser.parse(body, dirUrl)
        }
    }

    fun download(
        entry: WebDavRemoteEntry,
        dest: File,
        isCancelled: (() -> Boolean)? = null,
        onProgress: ((read: Long, total: Long) -> Unit)? = null
    ): Result<Unit> = runCatching {
        dest.parentFile?.mkdirs()
        request("GET", URL(entry.url)).use { response ->
            ensureSuccess(response.statusCode, "GET")
            val total = response.contentLength()?.takeIf { it > 0 } ?: entry.sizeBytes
            response.body.use { input ->
                FileOutputStream(dest).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var readTotal = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        readTotal += read
                        onProgress?.invoke(readTotal, total)
                        if (isCancelled?.invoke() == true) throw WebDavCancelledException()
                    }
                }
            }
        }
    }

    /** 删除远程文件；404 视为已经不存在。 */
    fun delete(entry: WebDavRemoteEntry): Result<Unit> = runCatching {
        request("DELETE", URL(entry.url)).use { response ->
            val code = response.statusCode
            if (code !in 200..299 && code != 404) {
                throw WebDavHttpException(code, "DELETE ${entry.name} -> HTTP $code")
            }
        }
    }

    private fun request(
        method: String,
        url: URL,
        depth: String? = null,
        contentType: String? = null,
        body: ByteArray? = null,
        bodyLength: Long? = null,
        writeBody: ((OutputStream) -> Unit)? = null
    ): RawHttpResponse {
        val socket = openSocket(url)
        try {
            val output = BufferedOutputStream(socket.getOutputStream(), 32 * 1024)
            output.write("$method ${requestPath(url)} HTTP/1.1\r\n".toByteArray(StandardCharsets.US_ASCII))
            output.write("Host: ${hostHeader(url)}\r\n".toByteArray(StandardCharsets.US_ASCII))
            output.write("Connection: close\r\n".toByteArray(StandardCharsets.US_ASCII))
            output.write("User-Agent: WeType-Enhance\r\n".toByteArray(StandardCharsets.US_ASCII))
            if (username.isNotEmpty() || password.isNotEmpty()) {
                val token = Base64.getEncoder()
                    .encodeToString("$username:$password".toByteArray(StandardCharsets.UTF_8))
                output.write("Authorization: Basic $token\r\n".toByteArray(StandardCharsets.US_ASCII))
            }
            depth?.let {
                output.write("Depth: $it\r\n".toByteArray(StandardCharsets.US_ASCII))
            }
            contentType?.let {
                output.write("Content-Type: $it\r\n".toByteArray(StandardCharsets.UTF_8))
            }
            val length = body?.size?.toLong() ?: bodyLength
            if (length != null) {
                output.write("Content-Length: $length\r\n".toByteArray(StandardCharsets.US_ASCII))
            }
            output.write("\r\n".toByteArray(StandardCharsets.US_ASCII))
            when {
                body != null -> output.write(body)
                writeBody != null -> writeBody(output)
            }
            output.flush()

            val input = BufferedInputStream(socket.getInputStream(), 32 * 1024)
            val statusLine = readLine(input) ?: error("服务器未返回响应")
            val statusCode = statusLine.split(" ", limit = 3)
                .getOrNull(1)?.toIntOrNull() ?: error("响应状态无效：$statusLine")
            val headers = LinkedHashMap<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) {
                    headers[line.substring(0, colon).trim().lowercase()] =
                        line.substring(colon + 1).trim()
                }
            }
            val bodyStream: InputStream = when {
                headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true ->
                    ChunkedInputStream(input)

                headers["content-length"]?.toLongOrNull() != null ->
                    LimitedInputStream(input, headers.getValue("content-length").toLong())

                else -> input
            }
            return RawHttpResponse(statusCode, headers, bodyStream, socket)
        } catch (t: Throwable) {
            runCatching { socket.close() }
            throw t
        }
    }

    private fun openSocket(url: URL): Socket {
        val port = if (url.port > 0) url.port else if (url.protocol == "https") 443 else 80
        return if (url.protocol == "https") {
            val factory = if (allowInsecureTls) insecureSocketFactory else defaultSocketFactory
            val socket = factory.createSocket() as SSLSocket
            if (!allowInsecureTls) {
                socket.sslParameters = socket.sslParameters.apply {
                    endpointIdentificationAlgorithm = "HTTPS"
                }
            }
            socket.connect(InetSocketAddress(url.host, port), connectTimeoutMs)
            socket.soTimeout = readTimeoutMs
            socket.startHandshake()
            socket
        } else {
            Socket().apply {
                connect(InetSocketAddress(url.host, port), connectTimeoutMs)
                soTimeout = readTimeoutMs
            }
        }
    }

    private fun requestPath(url: URL): String {
        val path = url.path.ifEmpty { "/" }
        val query = url.query
        return if (query.isNullOrEmpty()) path else "$path?$query"
    }

    private fun hostHeader(url: URL): String {
        val defaultPort = if (url.protocol == "https") 443 else 80
        return if (url.port <= 0 || url.port == defaultPort) url.host else "${url.host}:${url.port}"
    }

    private fun ensureSuccess(code: Int, method: String) {
        if (code !in 200..299) throw WebDavHttpException(code, "$method -> HTTP $code")
    }

    private class RawHttpResponse(
        val statusCode: Int,
        private val headers: Map<String, String>,
        val body: InputStream,
        private val socket: Socket
    ) : AutoCloseable {

        fun contentLength(): Long? = headers["content-length"]?.toLongOrNull()

        fun readBodyText(): String = body.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }

        override fun close() {
            runCatching { body.close() }
            runCatching { socket.close() }
        }
    }

    /** 按 Content-Length 限制读取，防止服务端 keep-alive 场景下读挂。 */
    private class LimitedInputStream(
        private val source: InputStream,
        private var remaining: Long
    ) : InputStream() {

        override fun read(): Int {
            if (remaining <= 0L) return -1
            val value = source.read()
            if (value >= 0) remaining--
            return value
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0L) return -1
            val toRead = minOf(len.toLong(), remaining).toInt()
            val read = source.read(b, off, toRead)
            if (read > 0) remaining -= read
            return read
        }
    }

    private class ChunkedInputStream(private val source: InputStream) : InputStream() {

        private var remaining = 0L
        private var finished = false

        override fun read(): Int {
            val single = ByteArray(1)
            return if (read(single, 0, 1) < 0) -1 else single[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (finished) return -1
            if (remaining == 0L && !readChunkHeader()) return -1
            val toRead = minOf(len.toLong(), remaining).toInt()
            val read = source.read(b, off, toRead)
            if (read < 0) {
                finished = true
                return -1
            }
            remaining -= read
            if (remaining == 0L) readLine(source)
            return read
        }

        private fun readChunkHeader(): Boolean {
            val line = readLine(source) ?: return false
            val size = line.substringBefore(';').trim().toIntOrNull(16) ?: return false
            if (size <= 0) {
                while (true) {
                    val trailer = readLine(source) ?: break
                    if (trailer.isEmpty()) break
                }
                finished = true
                return false
            }
            remaining = size.toLong()
            return true
        }
    }

    private val defaultSocketFactory: SSLSocketFactory by lazy {
        SSLContext.getInstance("TLS").apply { init(null, null, null) }.socketFactory
    }

    private val insecureSocketFactory: SSLSocketFactory by lazy {
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        })
        SSLContext.getInstance("TLS").apply {
            init(null, trustAll, SecureRandom())
        }.socketFactory
    }

    companion object {
        private const val PROPFIND_ALLPROP =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                "<d:propfind xmlns:d=\"DAV:\"><d:allprop/></d:propfind>"
        private const val XML_CONTENT_TYPE = "application/xml; charset=utf-8"

        private fun readLine(input: InputStream): String? {
            val sb = StringBuilder(128)
            while (true) {
                val value = input.read()
                if (value < 0) return if (sb.isEmpty()) null else sb.toString()
                if (value == '\n'.code) return sb.toString().removeSuffix("\r")
                sb.append(value.toChar())
            }
        }
    }
}
