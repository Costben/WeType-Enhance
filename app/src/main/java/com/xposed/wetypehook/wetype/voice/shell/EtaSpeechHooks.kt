package com.xposed.wetypehook.wetype.voice.shell

import com.xposed.wetypehook.xposed.Log
import com.xposed.wetypehook.xposed.hookBefore
import com.xposed.wetypehook.wetype.voice.shell.eta.DexMethodRef
import com.xposed.wetypehook.wetype.voice.shell.eta.EtaDexAnchors
import java.io.File
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile

/**
 * 在 Eta 进程里放行两条「服务端一定在云端」的假设，让本地壳可以直接用明文地址。
 *
 * ## 为什么按字符串常量找方法
 *
 * Eta 的 release 包被 R8 全量混淆：OkHttp 的 `Headers` 与 Eta 自己的 `speechBaseUrl` 名字
 * 全丢，按类名或方法名 hook 必挂。但**方法体里的字符串常量不会被混淆**，所以拿它们当锚点
 * 反查混淆后的方法（见 [EtaDexAnchors]）：
 *
 * - `speechBaseUrl` 抛的「服务地址须为不含认证信息和查询参数的 HTTPS 地址」
 * - OkHttp `Headers.checkName` / `checkValue` 抛的「Unexpected char 0x」
 *
 * ## 两条 hook
 *
 * - **明文地址**：`speechBaseUrl` 只认 `https://`，而壳跑在回环上、收明文。填
 *   `http://127.0.0.1:18516` 直接放行；填回环上的 `https://127.0.0.1:18516`（上一版要求
 *   的写法，用户地址栏里多半还留着）改写成 `http://`。回环之外的原样交回，不再抛那条
 *   CONFIGURATION 的只有这两种形状。
 * - **任意 API Key**：OkHttp 的 header 值只接受 `\t` 与 `\u0020..\u007e`。API Key 里只要有
 *   中文，Eta 在建请求时就抛 `IllegalArgumentException`，界面显示「语音服务返回了无法处理的
 *   数据」—— 看着像服务端坏了，其实请求根本没发出去。壳不校验凭据，所以跳过这条校验，
 *   让非 ASCII 原样进 header。
 *
 * 装不上只记日志，绝不抛出：最坏是功能退回改地址前的行为，不能把 Eta 搞崩。
 */
internal object EtaSpeechHooks {

    /** `speechBaseUrl` 抛 CONFIGURATION 时用的那句话。它只出现在这一个方法体里。 */
    private const val ANCHOR_SPEECH_BASE_URL = "服务地址须为不含认证信息和查询参数的 HTTPS 地址"

    /** OkHttp `Headers` 的两个校验方法共用的报错前缀。 */
    private const val ANCHOR_OKHTTP_HEADER_CHECK = "Unexpected char 0x"

    private val DEX_ENTRY = Regex("classes\\d*\\.dex")

    /** 非 ASCII header 放行只记一次日志，免得每个 header 都刷屏。 */
    private val nonAsciiHeaderSeen = AtomicBoolean(false)

    fun install(classLoader: ClassLoader, sourceDir: String?) {
        runCatching {
            val anchors = setOf(ANCHOR_SPEECH_BASE_URL, ANCHOR_OKHTTP_HEADER_CHECK)
            val hits = collectAnchors(sourceDir, anchors)
            installPlaintextAddress(classLoader, hits[ANCHOR_SPEECH_BASE_URL].orEmpty())
            installHeaderValueBypass(classLoader, hits[ANCHOR_OKHTTP_HEADER_CHECK].orEmpty())
        }.onFailure {
            Log.e("Failed:Install Eta speech hooks")
            Log.i(it)
        }
    }

    /**
     * `speechBaseUrl(String): String` —— 明文地址直接放行，回环上的 `https://` 改写成
     * `http://`，其余形状一律放回原方法。
     */
    private fun installPlaintextAddress(classLoader: ClassLoader, refs: List<DexMethodRef>) {
        val ref = refs.firstOrNull { it.parameterTypes == listOf(STRING) }
        val method = ref?.resolve(classLoader)
        if (method == null) {
            Log.i("Eta speech hooks: speechBaseUrl anchor not found; plaintext addresses stay rejected")
            return
        }
        method.hookBefore { param ->
            val value = param.args.getOrNull(0) as? String ?: return@hookBefore
            plaintextBaseUrl(value)?.let { param.result = it }
        }
        Log.i("Eta speech hooks: plaintext service address accepted by $ref")
    }

    /**
     * `Headers.checkValue(String, String): void` —— 只跳过校验，header 值本身不动。
     *
     * `checkValue` 是纯校验函数，真正写进 header 的是调用方手里的原值，所以这里返回
     * 等于「放行」，不需要改参数。
     */
    private fun installHeaderValueBypass(classLoader: ClassLoader, refs: List<DexMethodRef>) {
        val ref = refs.firstOrNull { it.parameterTypes == listOf(STRING, STRING) }
        val method = ref?.resolve(classLoader)
        if (method == null) {
            Log.i("Eta speech hooks: okhttp header value check not found; non-ASCII keys stay broken")
            return
        }
        method.hookBefore { param ->
            // 记一笔「确实放行了非 ASCII header」：这条日志是 R8 没有把 checkValue 内联掉的
            // 现场证据，也是「中文 API Key 为什么现在能过」的唯一可观测点。值本身不落日志。
            //
            // okhttp 5 的签名是 `checkValue(value, name)` —— 反汇编 `invoke-static {v2, v1}`
            // 可证值在 args[0]。但 R8 的 class merging 下参数顺序不值得赌，两个都扫一遍。
            val nonAscii = param.args.any { it is String && it.any { c -> c.code > ASCII_MAX } }
            if (nonAscii && nonAsciiHeaderSeen.compareAndSet(false, true)) {
                Log.i("Eta speech hooks: allowed a non-ASCII header value through okhttp")
            }
            param.result = null
        }
        Log.i("Eta speech hooks: okhttp header value check bypassed at $ref")
    }

    /**
     * 壳期望的服务地址：`http://` 原样放行，**回环上的** `https://` 改写成 `http://`。
     *
     * 壳只跑明文，`https://` 打过去必然撞 TLS 握手失败 —— 而用户手上的地址栏里往往还留着
     * 上一版要求填的 `https://127.0.0.1:18516`。回环地址不可能是云端服务，改写不会误伤
     * 真实接口；回环之外一律返回 null，交回 Eta 自己校验，**绝不把外网 https 降级成明文**。
     *
     * 口径与 `speechBaseUrl` 对齐：不接受认证信息、查询串、片段 —— 放行一个它后面会撞墙的
     * 地址，比不放行更糟。
     */
    /** 单测直接钉这条口径（见 `EtaSpeechHooksTest`），所以不设 `private`。 */
    fun plaintextBaseUrl(value: String): String? {
        val trimmed = value.trim().trimEnd('/')
        if (trimmed.regionMatches(0, PLAIN_SCHEME, 0, PLAIN_SCHEME.length, ignoreCase = true)) {
            val rest = trimmed.substring(PLAIN_SCHEME.length)
            return if (usableAddress(rest)) trimmed else null
        }
        if (trimmed.regionMatches(0, TLS_SCHEME, 0, TLS_SCHEME.length, ignoreCase = true)) {
            val rest = trimmed.substring(TLS_SCHEME.length)
            if (!usableAddress(rest)) return null
            return if (isLoopbackAuthority(rest.substringBefore('/'))) PLAIN_SCHEME + rest else null
        }
        return null
    }

    /** 排掉带认证信息、查询串、片段，或压根没有 host 的形状。 */
    private fun usableAddress(rest: String): Boolean =
        rest.isNotEmpty() &&
            rest.none { it == '@' || it == '?' || it == '#' } &&
            rest.substringBefore('/').isNotEmpty()

    /** `host[:port]` 或 `[v6][:port]` 里的 host 是不是回环。 */
    private fun isLoopbackAuthority(authority: String): Boolean {
        val host = if (authority.startsWith("[")) {
            authority.substringBefore(']').removePrefix("[")
        } else {
            authority.substringBefore(':')
        }
        if (host == "localhost" || host == "::1") return true
        // 只认字面 IPv4 回环：`127.0.0.1.evil.com` 这类"长得像"的主机名不能放行。
        val parts = host.split('.')
        return parts.size == 4 && parts[0] == "127" &&
            parts.all { it.isNotEmpty() && it.length <= 3 && it.all(Char::isDigit) }
    }

    private const val PLAIN_SCHEME = "http://"
    private const val TLS_SCHEME = "https://"

    /** okhttp 的 header 值上界（`\u0020..\u007e`）。 */
    private const val ASCII_MAX = 0x7e

    /** 逐 dex 扫描并合并同名锚点的命中。缺 dex、扫描失败都只当作「没命中」。 */
    private fun collectAnchors(
        sourceDir: String?,
        anchors: Set<String>
    ): Map<String, List<DexMethodRef>> {
        val dexFiles = dexBytes(sourceDir)
        if (dexFiles.isEmpty()) {
            Log.i("Eta speech hooks: no dex under $sourceDir")
            return emptyMap()
        }
        val merged = LinkedHashMap<String, MutableList<DexMethodRef>>()
        dexFiles.forEach { dex ->
            runCatching { EtaDexAnchors.scan(dex, anchors) }
                .onFailure { Log.i("Eta speech hooks: dex scan failed: ${it.javaClass.simpleName}") }
                .getOrNull()
                ?.forEach { (anchor, refs) -> merged.getOrPut(anchor) { mutableListOf() } += refs }
        }
        return merged
    }

    /** APK 里所有 `classes*.dex` 的字节。Eta 只有一个 dex，但入口按通用写。 */
    private fun dexBytes(sourceDir: String?): List<ByteArray> {
        val apk = sourceDir?.let(::File)?.takeIf { it.isFile } ?: return emptyList()
        return runCatching {
            ZipFile(apk).use { zip ->
                zip.entries().asSequence()
                    .filter { DEX_ENTRY.matches(it.name) }
                    .sortedBy { it.name }
                    .mapNotNull { entry -> zip.getInputStream(entry).use { it.readBytes() } }
                    .toList()
            }
        }.getOrDefault(emptyList())
    }

    private fun DexMethodRef.resolve(classLoader: ClassLoader): Method? = runCatching {
        val expected = parameterTypes.map(::typeName)
        classLoader.loadClass(className())
            .declaredMethods
            .firstOrNull { it.name == methodName && it.parameterTypes.map(Class<*>::getName) == expected }
    }.getOrNull()

    /** `Lk04;` → `k04`。R8 的 class merging 会把不相干的方法塞进同一个类，名字本身不可信。 */
    private fun DexMethodRef.className(): String =
        classDescriptor.removePrefix("L").removeSuffix(";").replace('/', '.')

    private fun typeName(descriptor: String): String = when (descriptor) {
        "V" -> "void"
        "Z" -> "boolean"
        "B" -> "byte"
        "C" -> "char"
        "S" -> "short"
        "I" -> "int"
        "J" -> "long"
        "F" -> "float"
        "D" -> "double"
        else -> descriptor.removePrefix("L").removeSuffix(";").replace('/', '.')
    }

    private const val STRING = "Ljava/lang/String;"
}
