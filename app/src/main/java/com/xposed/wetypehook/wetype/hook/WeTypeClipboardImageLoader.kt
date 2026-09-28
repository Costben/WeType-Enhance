package com.xposed.wetypehook.wetype.hook

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log as AndroidLog
import com.xposed.wetypehook.wetype.clipboard.ClipboardImageEntryLogic
import com.xposed.wetypehook.wetype.clipboard.ClipboardRemoteImagePayload
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED

/**
 * 跨设备图片后台下载/解密落盘管线（复用宿主 Glide + k6.a AES 解密 + B.P 持久化）。
 *
 * 宿主同款链路（3.5.4 与 4.0.0 逐指令一致）：
 * 1. path 含 "version" 时为 JSON：{key, md5, path(原图 URL)}；
 * 2. `com.bumptech.glide.c.u(context).r().L0(url).G0(listener).Q0()`；
 * 3. onResourceReady(File) 后 `k6.a.b(key, 下载文件, 目标文件)` AES/CTR 解密；
 * 4. `k6.e.d(目标文件)` 与 md5 比对；
 * 5. heic/heif 经 `com.tencent.wetype.plugin.hld.utils.e` 转 jpeg；
 * 6. `clipboard.B.P(id, path, Continuation)` 持久化，成功后回写内存 C 的 path/pathType。
 *
 * 第 2 步的短名随 R8 重排（4.0.0 由 `M0/H0/R0` 变成 `L0/G0/Q0`），所以整条链交给
 * [WeTypeGlideChain] 按方法形状定位，这里只保留两个稳定的入口类名。
 */
internal object WeTypeClipboardImageLoader {

    private const val TAG = WeTypeClipboardImageHost.TAG

    private const val GLIDE_CLASS = "com.bumptech.glide.c"
    private const val LISTENER_INTERFACE = "q1.h"
    private const val DECRYPT_CLASS = "k6.a"
    private const val MD5_CLASS = "k6.e"
    private const val WX_IME_UTIL_CLASS = "com.tencent.wetype.plugin.hld.utils.WxImeUtil"
    private const val BITMAP_UTIL_CLASS = "com.tencent.wetype.plugin.hld.utils.e"
    private const val CLIPBOARD_MGR_CLASS = "com.tencent.wetype.plugin.hld.clipboard.B"

    /**
     * 宿主临时路径工具类候选。4.0.0 把 `utils.x1` 改成了 ReplacementSpan 子类，同形的
     * 静态 `(String)->String` 工具搬到了 `utils.w1`。判据是形状（见 [resolveTempPathExtension]），
     * 不是类名，所以同一份列表在两版都能命中正确的那个。
     */
    private val TEMP_PATH_CLASSES = arrayOf(
        "com.tencent.wetype.plugin.hld.utils.x1",
        "com.tencent.wetype.plugin.hld.utils.w1",
        "com.tencent.wetype.plugin.hld.utils.j0",
        "com.tencent.wetype.plugin.hld.utils.y1"
    )

    private const val FAILURE_BACKOFF_MS = 30_000L
    private const val SUSPEND_WAIT_MS = 5_000L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "WeTypeClipboardImageLoad").apply { isDaemon = true }
    }

    private val inFlight = ConcurrentHashMap.newKeySet<Long>()
    private val failedUntil = ConcurrentHashMap<Long, Long>()
    private val activeTargets = ConcurrentHashMap<Long, Any>()

    @Volatile
    private var hostClassLoader: ClassLoader? = null

    @Volatile
    private var listenerInterface: Class<*>? = null

    @Volatile
    private var glideWith: Method? = null

    @Volatile
    private var decryptMethod: Method? = null

    @Volatile
    private var md5Method: Method? = null

    @Volatile
    private var wxImeUtil: Any? = null

    @Volatile
    private var wxImeUtilX: Method? = null

    @Volatile
    private var x1Extension: Method? = null

    @Volatile
    private var bitmapUtil: Any? = null

    @Volatile
    private var bitmapUtilIsHeic: Method? = null

    @Volatile
    private var bitmapUtilTranscode: Method? = null

    @Volatile
    private var clipboardMgr: Any? = null

    @Volatile
    private var clipboardPersistPath: Method? = null

    @Volatile
    private var glideBuilderFactory: Method? = null

    @Volatile
    private var glideChain: GlideChain? = null

    /** 一次解析出来的 Glide 链：构建器上的载入 / 监听 / 启动三个方法，按形状定位。 */
    private class GlideChain(
        val builderClass: Class<*>,
        val load: Method,
        val listener: Method,
        val start: Method
    )

    fun install(classLoader: ClassLoader): Boolean {
        hostClassLoader = classLoader
        return try {
            val listener = Class.forName(LISTENER_INTERFACE, false, classLoader)
            listenerInterface = listener
            val glide = Class.forName(GLIDE_CLASS, false, classLoader)
            glideWith = WeTypeGlideChain.resolveManager(glide, Context::class.java, listener)
            val decrypt = Class.forName(DECRYPT_CLASS, false, classLoader)
            decryptMethod = decrypt.declaredMethods.firstOrNull {
                Modifier.isStatic(it.modifiers) && it.parameterTypes.size == 3 &&
                    it.parameterTypes[0] == String::class.java &&
                    it.parameterTypes[1] == String::class.java &&
                    it.parameterTypes[2] == String::class.java &&
                    (it.returnType == Boolean::class.javaPrimitiveType || it.returnType == Boolean::class.java)
            }?.apply { isAccessible = true }
            val md5 = Class.forName(MD5_CLASS, false, classLoader)
            md5Method = md5.declaredMethods.firstOrNull {
                Modifier.isStatic(it.modifiers) && it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == String::class.java && it.returnType == String::class.java
            }?.apply { isAccessible = true }
            AndroidLog.i(
                TAG,
                "image loader installed: glide=${glideWith != null}" +
                    " manager=${glideWith?.declaringClass?.name}#${glideWith?.name}" +
                    " decrypt=${decryptMethod != null} md5=${md5Method != null}"
            )
            true
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "image loader install failed: ${t.message}")
            false
        }
    }

    @Volatile
    private var runtimeReady = false

    /** 宿主单例（WxImeUtil/BitmapUtil/ImeClipboardMgr）延后到首次下载时解析，避开早期 <clinit>。 */
    private fun ensureRuntime() {
        if (runtimeReady) return
        synchronized(this) {
            if (runtimeReady) return
            val cl = hostClassLoader ?: return
            runCatching { resolveTempPathHandles(cl) }
                .onFailure { AndroidLog.e(TAG, "resolve temp path handles failed: ${it.message}") }
            runCatching { resolveBitmapUtil(cl) }
                .onFailure { AndroidLog.e(TAG, "resolve bitmap util failed: ${it.message}") }
            runCatching { resolvePersistHandle(cl) }
                .onFailure { AndroidLog.e(TAG, "resolve persist handle failed: ${it.message}") }
            runtimeReady = true
            AndroidLog.i(TAG, "image loader runtime resolved: persist=${clipboardPersistPath != null}")
        }
    }

    private fun resolveTempPathHandles(classLoader: ClassLoader) {
        x1Extension = resolveTempPathExtension(classLoader)
        val util = runCatching { Class.forName(WX_IME_UTIL_CLASS, false, classLoader) }.getOrNull() ?: return
        wxImeUtil = staticSelfInstance(util)
        wxImeUtilX = util.declaredMethods.firstOrNull {
            it.name == "X" && it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == String::class.java && it.returnType == String::class.java
        }?.apply { isAccessible = true } ?: util.declaredMethods.firstOrNull {
            it.parameterTypes.size == 1 && it.parameterTypes[0] == String::class.java &&
                it.returnType == String::class.java
        }?.apply { isAccessible = true }
    }

    /**
     * 临时路径工具：在候选类里找「至少两条静态 `(String)->String` + 至少一条静态 `()->String`
     * 且没有静态字段」的那个。3.5.4 命中 `utils.x1`，4.0.0 的 `x1` 已变成 ReplacementSpan
     * 子类、`j0` 多了静态 Map 字段且缺 `()->String`，只有 `utils.w1` 满足。
     * 都不满足就返回 null，退回宿主 cacheDir（见 [makeOutputPath]），不猜。
     */
    private fun resolveTempPathExtension(classLoader: ClassLoader): Method? {
        val picked = WeTypeTempPath.resolve(classLoader, TEMP_PATH_CLASSES)
        if (picked != null) {
            AndroidLog.i(TAG, "temp path extension: ${picked.declaringClass.name}#${picked.name}")
        } else {
            AndroidLog.e(TAG, "temp path extension: none of ${TEMP_PATH_CLASSES.joinToString()} matched")
        }
        return picked
    }

    private fun resolveBitmapUtil(classLoader: ClassLoader) {
        val util = runCatching { Class.forName(BITMAP_UTIL_CLASS, false, classLoader) }.getOrNull() ?: return
        bitmapUtil = staticSelfInstance(util)
        bitmapUtilIsHeic = util.declaredMethods.firstOrNull {
            it.name == "a" && it.parameterTypes.size == 1 && it.parameterTypes[0] == String::class.java &&
                (it.returnType == Boolean::class.javaPrimitiveType || it.returnType == Boolean::class.java)
        }?.apply { isAccessible = true } ?: util.declaredMethods.firstOrNull {
            it.parameterTypes.size == 1 && it.parameterTypes[0] == String::class.java &&
                (it.returnType == Boolean::class.javaPrimitiveType || it.returnType == Boolean::class.java)
        }?.apply { isAccessible = true }
        bitmapUtilTranscode = util.declaredMethods.firstOrNull {
            it.name == "b" && it.parameterTypes.size == 2 && it.parameterTypes[0] == String::class.java &&
                isContinuationType(it.parameterTypes[1])
        }?.apply { isAccessible = true } ?: util.declaredMethods.firstOrNull {
            it.parameterTypes.size == 2 && it.parameterTypes[0] == String::class.java &&
                isContinuationType(it.parameterTypes[1])
        }?.apply { isAccessible = true }
    }

    /** 宿主 Kotlin stdlib 被混淆，Continuation 类名不可靠；按 resumeWith 形状识别。 */
    private fun isContinuationType(type: Class<*>): Boolean {
        if (!type.isInterface) return false
        return type.declaredMethods.any { it.name == "resumeWith" && it.parameterTypes.size == 1 }
    }

    private fun resolvePersistHandle(classLoader: ClassLoader) {
        val mgr = runCatching { Class.forName(CLIPBOARD_MGR_CLASS, false, classLoader) }.getOrNull() ?: return
        clipboardMgr = staticSelfInstance(mgr)
        val persistMatch: (Method) -> Boolean = {
            !Modifier.isStatic(it.modifiers) && it.parameterTypes.size == 3 &&
                (it.parameterTypes[0] == Long::class.javaPrimitiveType || it.parameterTypes[0] == Long::class.java) &&
                it.parameterTypes[1] == String::class.java &&
                isContinuationType(it.parameterTypes[2])
        }
        clipboardPersistPath = mgr.declaredMethods.firstOrNull {
            it.name == "P" && persistMatch(it)
        }?.apply { isAccessible = true } ?: mgr.declaredMethods.firstOrNull(persistMatch)?.apply { isAccessible = true }
    }

    private fun staticSelfInstance(cls: Class<*>): Any? {
        val field = cls.declaredFields.firstOrNull {
            Modifier.isStatic(it.modifiers) && it.type == cls
        } ?: return null
        return runCatching {
            field.isAccessible = true
            field.get(null)
        }.getOrNull()
    }

    /**
     * 触发一次后台下载；同一 id 去重，失败 30s 内不重试（支持 [forceRetry] 强制重试）。
     * [onUpdated] 在主线程回调（已落盘且内存 C 已更新）。
     */
    fun request(
        item: Any,
        context: Context,
        forceRetry: Boolean = false,
        onUpdated: (Any) -> Unit
    ) {
        ensureRuntime()
        val id = WeTypeClipboardImageHost.itemId(item) ?: return
        if (forceRetry) {
            failedUntil.remove(id)
        }
        val now = SystemClock.uptimeMillis()
        if ((failedUntil[id] ?: 0L) > now) return
        if (WeTypeClipboardImageHost.itemPathType(item) == 0) return
        val path = WeTypeClipboardImageHost.itemPath(item)
        val payload = ClipboardImageEntryLogic.parseRemotePayload(path) ?: return
        if (!inFlight.add(id)) return
        try {
            startDownload(item, id, payload, context, onUpdated)
        } catch (t: Throwable) {
            inFlight.remove(id)
            failedUntil[id] = SystemClock.uptimeMillis() + FAILURE_BACKOFF_MS
            AndroidLog.e(TAG, "image download dispatch failed id=$id: ${t.message}")
        }
    }

    private fun startDownload(
        item: Any,
        id: Long,
        payload: ClipboardRemoteImagePayload,
        context: Context,
        onUpdated: (Any) -> Unit
    ) {
        val output = makeOutputPath(context, id, WeTypeClipboardImageHost.itemPath(item) ?: "")
        val listener = createListener(
            onReady = { file ->
                executor.execute {
                    val finalPath = runCatching { process(item, id, payload, file, output, context) }.getOrNull()
                    activeTargets.remove(id)
                    if (finalPath != null) {
                        inFlight.remove(id)
                        failedUntil.remove(id)
                        mainHandler.post { onUpdated(item) }
                    } else {
                        inFlight.remove(id)
                        failedUntil[id] = SystemClock.uptimeMillis() + FAILURE_BACKOFF_MS
                        AndroidLog.e(TAG, "image process failed id=$id path=${output.absolutePath}")
                    }
                }
            },
            onFailed = {
                activeTargets.remove(id)
                inFlight.remove(id)
                failedUntil[id] = SystemClock.uptimeMillis() + FAILURE_BACKOFF_MS
                AndroidLog.e(TAG, "image download failed id=$id url=${payload.url}")
            }
        )
        val manager = glideWith?.invoke(null, context) ?: error("Glide.with unavailable")
        val builder = resolveBuilder(manager)
        val chain = resolveChain(builder.javaClass)
        val loaded = chain.load.invoke(builder, payload.url) ?: error("load() unavailable")
        val withListener = chain.listener.invoke(loaded, listener) ?: error("listener() unavailable")
        val target = chain.start.invoke(withListener) ?: error("submit() unavailable")
        // 持有 Request 引用直到回调：Glide 的请求表是弱引用，会被提前回收。
        val request = runCatching { WeTypeGlideChain.resolveRequest(target.javaClass)?.invoke(target) }
            .getOrNull()
        activeTargets[id] = request ?: target
    }

    /** RequestManager -> RequestBuilder<File>：按转码类型挑，避免误选 asBitmap / asDrawable。 */
    private fun resolveBuilder(manager: Any): Any {
        val factory = glideBuilderFactory ?: run {
            val listener = listenerInterface ?: error("listener interface missing")
            WeTypeGlideChain.resolveBuilderFactory(manager, listener, File::class.java)
                ?.also {
                    glideBuilderFactory = it
                    AndroidLog.i(TAG, "glide builder factory: ${it.declaringClass.name}#${it.name}")
                } ?: error("RequestBuilder unavailable")
        }
        return factory.invoke(manager) ?: error("RequestBuilder unavailable")
    }

    private fun resolveChain(builderClass: Class<*>): GlideChain {
        glideChain?.takeIf { it.builderClass == builderClass }?.let { return it }
        val listener = listenerInterface ?: error("listener interface missing")
        val resolved = GlideChain(
            builderClass = builderClass,
            load = WeTypeGlideChain.resolveLoad(builderClass) ?: error("load() unavailable"),
            listener = WeTypeGlideChain.resolveListener(builderClass, listener)
                ?: error("listener() unavailable"),
            start = WeTypeGlideChain.resolveStart(builderClass) ?: error("submit() unavailable")
        )
        AndroidLog.i(
            TAG,
            "glide chain: builder=${builderClass.name} load=${resolved.load.name}" +
                " listener=${resolved.listener.name} start=${resolved.start.name}" +
                " startType=${resolved.start.returnType.name}"
        )
        glideChain = resolved
        return resolved
    }

    private fun makeOutputPath(context: Context, id: Long, sourcePath: String): File {
        val hostPath = runCatching {
            val ext = x1Extension?.invoke(null, sourcePath) as? String
            val util = wxImeUtil ?: return@runCatching null
            wxImeUtilX?.invoke(util, ext) as? String
        }.getOrNull()
        val file = if (!hostPath.isNullOrEmpty()) File(hostPath) else {
            File(context.cacheDir, "wetype_clip_img_$id")
        }
        val unique = File(file.parentFile, "${file.nameWithoutExtension}_$id.${file.extension.ifEmpty { "jpg" }}")
        unique.parentFile?.mkdirs()
        return unique
    }

    private fun createListener(onReady: (File) -> Unit, onFailed: () -> Unit): Any {
        val iface = listenerInterface ?: error("listener interface missing")
        val loader = hostClassLoader ?: error("host loader missing")
        return Proxy.newProxyInstance(loader, arrayOf(iface)) { proxy, method, args ->
            when {
                method.name == "equals" -> proxy === args?.getOrNull(0)
                method.name == "hashCode" -> System.identityHashCode(proxy)
                method.name == "toString" -> "WeTypeClipboardImageListener"
                args != null && args.size == 5 -> {
                    (args[0] as? File)?.let(onReady)
                    true
                }
                args != null && args.size == 4 -> {
                    onFailed()
                    true
                }
                else -> false
            }
        }
    }

    private fun process(
        item: Any,
        id: Long,
        payload: ClipboardRemoteImagePayload,
        downloaded: File,
        output: File,
        context: Context
    ): String? {
        if (payload.encrypted) {
            val method = decryptMethod ?: return null
            val ok = method.invoke(null, payload.key, downloaded.absolutePath, output.absolutePath) as? Boolean ?: false
            if (!ok) {
                AndroidLog.e(TAG, "decrypt returned false id=$id")
                return null
            }
        } else {
            downloaded.inputStream().use { input ->
                output.outputStream().use { out -> input.copyTo(out) }
            }
        }
        if (payload.md5.isNotEmpty()) {
            val method = md5Method ?: return null
            val actual = method.invoke(null, output.absolutePath) as? String
            if (actual == null || !actual.equals(payload.md5, ignoreCase = true)) {
                AndroidLog.e(TAG, "md5 mismatch id=$id actual=$actual expected=${payload.md5}")
                output.delete()
                return null
            }
        }
        val finalPath = maybeTranscode(output.absolutePath)
        // ADR-0007：再存一份到模块私有目录，宿主清缓存后仍可渲染/自愈。
        val durable = WeTypeClipboardImageStore.importFromFile(context, id, File(finalPath))
        val storedPath = durable?.absolutePath ?: finalPath
        if (durable != null) {
            AndroidLog.i(TAG, "image stored durably id=$id path=$storedPath")
        }
        persist(item, id, storedPath)
        return storedPath
    }

    private fun maybeTranscode(path: String): String {
        val util = bitmapUtil ?: return path
        val isHeic = runCatching { bitmapUtilIsHeic?.invoke(util, path) as? Boolean ?: false }.getOrDefault(false)
        if (!isHeic) return path
        val method = bitmapUtilTranscode ?: return path
        val continuation = newHostContinuation(method.parameterTypes[1]) ?: return path
        val returned = runCatching { method.invoke(util, path, continuation.proxy) }.getOrElse { return path }
        if (returned === COROUTINE_SUSPENDED) {
            continuation.latch.await(SUSPEND_WAIT_MS, TimeUnit.MILLISECONDS)
            return continuation.result as? String ?: path
        }
        return returned as? String ?: path
    }

    private fun persist(item: Any, id: Long, path: String) {
        val persisted = WeTypeClipboardImageList.persistImagePath(id, path)
        if (!persisted) {
            // DAO 路径不可用时的兜底：宿主原生 B.P。
            val manager = clipboardMgr
            val method = clipboardPersistPath
            if (manager != null && method != null) {
                val continuation = newHostContinuation(method.parameterTypes[2])
                if (continuation != null) {
                    runCatching { method.invoke(manager, id, path, continuation.proxy) }
                        .onFailure { AndroidLog.e(TAG, "persist path failed id=$id: ${it.message}") }
                }
            }
        }
        // 内存态无论如何都更新，保证当前会话列表立即转双行缩略图。
        WeTypeClipboardImageHost.setItemPath(item, path)
        WeTypeClipboardImageHost.setItemPathType(item, 0)
    }

    /**
     * 宿主 kotlin-stdlib 与模块各自打包，`kotlin.coroutines.Continuation` 不是同一个类；
     * 必须用宿主类加载器动态代理出宿主 Continuation 实例才能反射调用宿主 suspend 方法。
     */
    private class HostContinuation {
        lateinit var proxy: Any
        val latch = CountDownLatch(1)

        @Volatile
        var result: Any? = null
    }

    private fun newHostContinuation(iface: Class<*>): HostContinuation? {
        val cl = hostClassLoader ?: return null
        return runCatching {
            val contextType = runCatching {
                iface.methods.firstOrNull { it.name == "getContext" }?.returnType
            }.getOrNull()
            val emptyContext = contextType?.let { type ->
                runCatching {
                    type.declaredFields.firstOrNull {
                        Modifier.isStatic(it.modifiers) && it.type == type
                    }?.apply { isAccessible = true }?.get(null)
                }.getOrNull()
            }
            val holder = HostContinuation()
            val proxy = Proxy.newProxyInstance(cl, arrayOf(iface)) { p, method, args ->
                when (method.name) {
                    "resumeWith" -> {
                        holder.result = runCatching {
                            val result = args?.getOrNull(0)
                            val field = result?.javaClass?.getDeclaredField("value")
                            field?.isAccessible = true
                            field?.get(result)
                        }.getOrNull()
                        holder.latch.countDown()
                        null
                    }
                    "getContext" -> emptyContext
                    "toString" -> "WeTypeClipboardContinuation"
                    "hashCode" -> System.identityHashCode(p)
                    "equals" -> p === args?.getOrNull(0)
                    else -> null
                }
            }
            holder.proxy = proxy
            holder
        }.getOrNull()
    }
}

/**
 * 宿主临时路径工具类解析：只认形状，不认类名。
 *
 * 判据是「至少两条静态 `(String)->String` + 至少一条静态 `()->String`」。3.5.4 命中 `utils.x1`；
 * 4.0.0 把 `utils.x1` 改成了 `android.text.style.ReplacementSpan` 子类（没有静态 String 方法），
 * 同形工具搬到 `utils.w1`（`utils.j0` 缺无参 String 方法、`utils.y1` 只有一条，都不满足）。
 */
internal object WeTypeTempPath {

    fun resolve(classLoader: ClassLoader, candidates: Array<String>): Method? {
        for (name in candidates) {
            val cls = runCatching { Class.forName(name, false, classLoader) }.getOrNull() ?: continue
            val stringTo = cls.declaredMethods.filter {
                Modifier.isStatic(it.modifiers) && it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == String::class.java && it.returnType == String::class.java
            }
            val noArgString = cls.declaredMethods.any {
                Modifier.isStatic(it.modifiers) && it.parameterTypes.isEmpty() &&
                    it.returnType == String::class.java
            }
            if (stringTo.size < 2 || !noArgString) continue
            val picked = stringTo.firstOrNull { it.name == "a" } ?: stringTo.minByOrNull { it.name }
            picked?.isAccessible = true
            return picked
        }
        return null
    }
}
