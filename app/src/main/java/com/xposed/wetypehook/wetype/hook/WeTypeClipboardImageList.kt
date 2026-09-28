package com.xposed.wetypehook.wetype.hook

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log as AndroidLog
import com.xposed.wetypehook.wetype.host.HostContractId
import com.xposed.wetypehook.wetype.host.WeTypeHostContracts
import com.xposed.wetypehook.wetype.host.pickHostMethod
import com.xposed.wetypehook.wetype.settings.WeTypeSettings
import com.xposed.wetypehook.xposed.hookAfter
import com.xposed.wetypehook.xposed.hookBefore
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.WeakHashMap
import java.util.concurrent.Executors

/**
 * 剪贴板图片条目注入（ADR-0002 前置条件）。
 *
 * 实机 3.5.3 实证：宿主面板列表只喂 `ImeClipboardMgr.clipboardRecordItems`，
 * 该缓存由 `z(dao.p())` 过滤为 `type==0 && source==0`（纯文本），type==1 图片
 * 条目不会进入 Adapter。本对象从宿主 DAO 读图片记录（d()=type1/state1、
 * l()=state0 未展示），在 `ImeClipboardScrollView.setList` 前合入，使
 * `v.onBindViewHolder` 能绑定图片条目（渲染见 [WeTypeClipboardImageEntries]）。
 */
internal object WeTypeClipboardImageList {

    private const val TAG = WeTypeClipboardImageHost.TAG
    private const val SCROLLVIEW_CLASS = "com.tencent.wetype.plugin.hld.clipboard.ImeClipboardScrollView"
    private const val CLIPBOARD_ITEM_CLASS = "com.tencent.wetype.plugin.hld.clipboard.C"
    private const val DAO_FACTORY_CLASS = "com.tencent.wetype.plugin.hld.dao.c"
    private const val CLIPBOARD_MGR_CLASS = "com.tencent.wetype.plugin.hld.clipboard.B"
    private const val ITEM_GET_CREATE_TIME = "c"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "WeTypeClipboardImageList").apply { isDaemon = true }
    }

    @Volatile
    private var installed = false

    @Volatile
    private var hostClassLoader: ClassLoader? = null

    @Volatile
    private var daoInstance: Any? = null

    @Volatile
    private var daoImages: Method? = null

    @Volatile
    private var daoUnshown: Method? = null

    @Volatile
    private var itemClass: Class<*>? = null

    private val cacheLock = Any()
    private val cachedImages = ArrayList<Any>()
    private val cachedIds = HashSet<Long>()
    private val lastNativeByScrollView = WeakHashMap<Any, ArrayList<Any>>()

    @Volatile
    private var refreshQueued = false

    @Volatile
    private var injecting = false

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var daoInsertHooked = false

    @Volatile
    private var daoResolveAttempts = 0

    fun install(classLoader: ClassLoader): Boolean {
        if (installed) return true
        hostClassLoader = classLoader
        return try {
            // 只做零初始化加载；DAO 单例延后解析（宿主 DB 就绪后才安全）。
            itemClass = resolveClipboardItem(classLoader)
                ?: throw ClassNotFoundException(CLIPBOARD_ITEM_CLASS)
            hookSetList(classLoader)
            hookDelete(classLoader)
            WeTypeClipboardRetentionGuard.install(classLoader)
            installed = true
            AndroidLog.i(TAG, "clipboard image list injector installed")
            scheduleDaoResolve(1000L)
            true
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "clipboard image list injector install failed: ${t.message}")
            false
        }
    }

    /**
     * 宿主启动早期解析 Room 单例可能触发 <clinit> 风险，延后并重试；
     * 解析成功后立即挂钩 DAO 插入点，做到「同步条目一落库就采集」。
     */
    fun onDaoResolved(dao: Any) {
        if (daoInstance != null) return
        val images = pickHostMethod(dao.javaClass, "d") {
            it.parameterTypes.isEmpty() &&
                List::class.java.isAssignableFrom(it.returnType)
        }
        val unshown = pickHostMethod(dao.javaClass, "l") {
            it.parameterTypes.isEmpty() &&
                List::class.java.isAssignableFrom(it.returnType)
        }
        if (images != null || unshown != null) {
            daoInstance = dao
            daoImages = images
            daoUnshown = unshown
            AndroidLog.i(
                TAG,
                "clipboard dao bound eagerly via factory hook: ${dao.javaClass.name} " +
                    "images=${images != null} unshown=${unshown != null}"
            )
            hookDaoInserts(dao)
            WeTypeClipboardRetentionGuard.installDaoHooks(dao)
            primeAsync()
        }
    }

    private fun scheduleDaoResolve(delayMs: Long) {
        mainHandler.postDelayed({
            executor.execute {
                if (daoInstance != null) return@execute
                val cl = hostClassLoader ?: return@execute
                resolveDao(cl)
                if (daoInstance == null && daoResolveAttempts < 10) {
                    daoResolveAttempts++
                    scheduleDaoResolve(3000L)
                }
            }
        }, delayMs)
    }

    /**
     * 解析剪贴板 DAO。工厂类 `dao.c` 有多个零参 getter（反馈库/记录库等），
     * `getDeclaredMethods()` 顺序不保证，必须选带 `d()`/`l()` 查询的那个，
     * 否则在部分设备上会静默拿到错误 DAO、永远采不到图片。
     */
    private fun resolveDao(classLoader: ClassLoader) {
        val factory = resolveDaoFactory(classLoader)
            ?: run {
                AndroidLog.e(TAG, "clipboard dao factory missing: $DAO_FACTORY_CLASS")
                return
            }
        val singleton = factory.declaredFields.firstOrNull {
            Modifier.isStatic(it.modifiers) && it.type == factory
        } ?: run {
            AndroidLog.e(TAG, "clipboard dao factory singleton missing")
            return
        }
        val factoryInstance = runCatching {
            singleton.isAccessible = true
            singleton.get(null)
        }.getOrNull() ?: run {
            AndroidLog.e(TAG, "clipboard dao factory instance missing")
            return
        }
        var dao: Any? = null
        var images: Method? = null
        var unshown: Method? = null
        for (getter in factory.declaredMethods) {
            if (getter.parameterTypes.isNotEmpty() || getter.returnType == Void.TYPE) continue
            getter.isAccessible = true
            val candidate = runCatching { getter.invoke(factoryInstance) }.getOrNull() ?: continue
            val candidateImages = pickHostMethod(candidate.javaClass, "d") {
                it.parameterTypes.isEmpty() &&
                    List::class.java.isAssignableFrom(it.returnType)
            }
            val candidateUnshown = pickHostMethod(candidate.javaClass, "l") {
                it.parameterTypes.isEmpty() &&
                    List::class.java.isAssignableFrom(it.returnType)
            }
            if (candidateImages != null || candidateUnshown != null) {
                dao = candidate
                images = candidateImages
                unshown = candidateUnshown
                break
            }
        }
        val resolved = dao ?: run {
            AndroidLog.e(TAG, "clipboard dao not found on factory ${factory.name}")
            return
        }
        daoInstance = resolved
        daoImages = images?.apply { isAccessible = true }
        daoUnshown = unshown?.apply { isAccessible = true }
        AndroidLog.i(
            TAG,
            "clipboard dao resolved: ${resolved.javaClass.name} " +
                "images=${images != null} unshown=${unshown != null}"
        )
        hookDaoInserts(resolved)
        WeTypeClipboardRetentionGuard.installDaoHooks(resolved)
    }

    /** Room 生成实现的单条插入（`long insert(C)`）；落库完成即刷新采集缓存。 */
    private fun hookDaoInserts(dao: Any) {
        if (daoInsertHooked) return
        val itemType = itemClass ?: return
        var count = 0
        for (method in dao.javaClass.declaredMethods) {
            if (Modifier.isStatic(method.modifiers)) continue
            if (method.parameterTypes.size != 1 || method.parameterTypes[0] != itemType) continue
            if (method.returnType != Long::class.javaPrimitiveType &&
                method.returnType != Long::class.java
            ) {
                continue
            }
            method.isAccessible = true
            method.hookAfter { primeAsync() }
            count++
        }
        if (count > 0) {
            daoInsertHooked = true
            AndroidLog.i(TAG, "hooked dao insert methods: $count")
        }
    }

    /** 备份/恢复用：宿主类加载器快照。 */
    fun hostClassLoaderSnapshot(): ClassLoader? = hostClassLoader

    /** 备份/恢复用：解析并返回宿主剪贴板 DAO（独立于图片缓存）。 */
    fun daoOrResolve(): Any? = daoInstanceOrResolve()

    /** 当前缓存的图片条目（快照，供过滤快照合入）。 */
    fun cachedImagesSnapshot(): List<Any> = synchronized(cacheLock) { ArrayList(cachedImages) }

    /** 把缓存图片按 id 去重追加到 [dest]（搜索过滤快照用）。 */
    fun appendCachedUnique(dest: MutableList<Any>) {
        val images = synchronized(cacheLock) { if (cachedImages.isEmpty()) null else ArrayList(cachedImages) }
            ?: return
        val seen = HashSet<Long>(dest.size + images.size)
        for (item in dest) {
            WeTypeClipboardImageHost.itemId(item)?.let { seen.add(it) }
        }
        for (image in images) {
            val id = WeTypeClipboardImageHost.itemId(image) ?: continue
            if (seen.add(id)) dest.add(image)
        }
    }

    /**
     * 原生列表 + 已缓存图片（按 createTime 倒序、按 id 去重）。
     * 缓存为空时原样返回，零开销。
     */
    fun mergeInto(nativeList: List<Any>): List<Any> {
        val images = synchronized(cacheLock) { if (cachedImages.isEmpty()) null else ArrayList(cachedImages) }
            ?: return nativeList
        val seen = HashSet<Long>(nativeList.size + images.size)
        for (item in nativeList) {
            WeTypeClipboardImageHost.itemId(item)?.let { seen.add(it) }
        }
        val merged = ArrayList<Any>(nativeList.size + images.size)
        merged.addAll(nativeList)
        for (image in images) {
            val id = WeTypeClipboardImageHost.itemId(image) ?: continue
            if (seen.add(id)) merged.add(image)
        }
        if (merged.size == nativeList.size) return nativeList
        return merged.sortedWith(
            compareByDescending { WeTypeClipboardImageHost.itemCreateTime(it) }
        )
    }

    /** 由搜索过滤的 setList 快照点调用：记录原生列表并触发后台刷新。 */
    fun onNativeSetList(scrollView: Any, nativeList: List<Any>) {
        synchronized(cacheLock) {
            lastNativeByScrollView[scrollView] = ArrayList(nativeList)
        }
        primeAsync()
    }

    fun primeAsync() {
        if (refreshQueued) return
        refreshQueued = true
        executor.execute {
            try {
                refreshCacheFromDao()
            } catch (t: Throwable) {
                AndroidLog.e(TAG, "clipboard image cache refresh failed: ${t.message}")
            } finally {
                refreshQueued = false
            }
        }
    }

    private fun refreshCacheFromDao() {
        val dao = daoInstance ?: run {
            val cl = hostClassLoader ?: return
            resolveDao(cl)
            daoInstance
        } ?: return
        val collected = ArrayList<Any>()
        val ids = HashSet<Long>()
        val context = currentApplicationContext()
        for (method in listOf(daoImages, daoUnshown)) {
            val m = method ?: continue
            val list = runCatching { m.invoke(dao) as? List<*> }.getOrNull() ?: continue
            for (item in list) {
                if (item == null) continue
                if (WeTypeClipboardImageHost.itemType(item) != 1L) continue
                val id = WeTypeClipboardImageHost.itemId(item) ?: continue
                if (!ids.add(id)) continue
                if (context != null && !ensureLocalFileAvailable(item, id, dao, context)) {
                    ids.remove(id)
                    continue
                }
                collected.add(item)
            }
        }
        collected.sortByDescending { WeTypeClipboardImageHost.itemCreateTime(it) }
        runCatching { enforceImageLimits(collected, ids) }
            .onFailure { AndroidLog.e(TAG, "enforce image limits failed: ${it.message}") }
        val changed: Boolean
        synchronized(cacheLock) {
            changed = cachedIds != ids
            if (changed) {
                cachedImages.clear()
                cachedImages.addAll(collected)
                cachedIds.clear()
                cachedIds.addAll(ids)
            }
        }
        if (changed) {
            AndroidLog.i(TAG, "clipboard image cache refreshed: ${collected.size} item(s)")
            if (WeTypeSettings.isRemoveClipboardRetentionLimitXposed()) {
                runCatching { extendExpiryForImages(collected) }
                    .onFailure { AndroidLog.e(TAG, "extend image expiry failed: ${it.message}") }
            }
            mainHandler.post {
                autoRequestDownloads(collected)
                reapplyAll()
            }
        } else if (WeTypeSettings.isRemoveClipboardRetentionLimitXposed() && collected.isNotEmpty()) {
            runCatching { extendExpiryForImages(collected) }
                .onFailure { AndroidLog.e(TAG, "extend image expiry failed: ${it.message}") }
        }
    }

    /**
     * ADR-0007：本地文件可用性保障（非主线程）。
     * - `pathType != 0`：远程载荷未下载，保留等下载；
     * - 宿主文件存在：保留，并补一份模块私有副本（迁移旧图，防下一次清缓存）；
     * - 宿主文件丢失但私有副本在：回写副本路径自愈；
     * - 两者都无：死记录（无文件/无副本/无远程链接），按用户选择删除记录与副本。
     *
     * @return true = 条目保留（仍可渲染）
     */
    private fun ensureLocalFileAvailable(item: Any, id: Long, dao: Any, context: Context): Boolean {
        if (WeTypeClipboardImageHost.itemPathType(item) != 0) return true
        val path = WeTypeClipboardImageHost.itemPath(item)
        val hostFile = if (path.isNullOrEmpty()) null else File(path)
        if (hostFile != null && hostFile.isFile) {
            if (WeTypeClipboardImageStore.find(context, id) == null) {
                WeTypeClipboardImageStore.importFromFile(context, id, hostFile)
            }
            return true
        }
        val stored = WeTypeClipboardImageStore.find(context, id)
        if (stored != null) {
            val healPath = stored.absolutePath
            WeTypeClipboardImageHost.setItemPath(item, healPath)
            WeTypeClipboardImageHost.setItemPathType(item, 0)
            runCatching { persistImagePath(id, healPath) }
                .onFailure { AndroidLog.e(TAG, "heal image path failed id=$id: ${it.message}") }
            AndroidLog.i(TAG, "image path healed from module store id=$id path=$healPath")
            return true
        }
        val delete = daoDeleteMethod(dao) ?: return true
        runCatching { delete.invoke(dao, item) }
            .onFailure { AndroidLog.e(TAG, "prune dead image failed id=$id: ${it.message}") }
        WeTypeClipboardImageStore.delete(context, id)
        AndroidLog.i(TAG, "pruned dead image id=$id (host file missing, no payload, no store copy)")
        return false
    }

    /**
     * 图片留存上限：数量/容量超限时从最旧的图片开始清理（数据库记录 + 本地文件）。
     * 列表已按 createTime 倒序；保留最新的若干张，直到触达任一上限，之后全部删除。
     * 受“解除剪贴板留存上限”开关控制（关闭时宿主本就会清图片，不叠加限制）。
     * 必须在非主线程调用。
     */
    private fun enforceImageLimits(items: MutableList<Any>, ids: MutableSet<Long>) {
        if (!WeTypeSettings.isRemoveClipboardRetentionLimitXposed()) return
        val countSetting = WeTypeSettings.getClipboardImageMaxCountXposed()
        val sizeSetting = WeTypeSettings.getClipboardImageMaxSizeMbXposed()
        val unlimited = WeTypeSettings.CLIPBOARD_IMAGE_LIMIT_UNLIMITED
        if (countSetting <= unlimited && sizeSetting <= unlimited) return
        val maxCount = if (countSetting <= unlimited) {
            Int.MAX_VALUE
        } else {
            countSetting.coerceAtLeast(WeTypeSettings.CLIPBOARD_IMAGE_MIN_COUNT)
        }
        val maxBytes = if (sizeSetting <= unlimited) {
            Long.MAX_VALUE
        } else {
            sizeSetting.coerceAtLeast(WeTypeSettings.CLIPBOARD_IMAGE_MIN_SIZE_MB)
                .toLong() * 1024L * 1024L
        }
        var kept = 0
        var totalBytes = 0L
        var keeping = true
        val deleted = ArrayList<Any>()
        for (item in items) {
            if (keeping) {
                val size = imageFileSize(item)
                if (kept == 0 || (kept < maxCount && totalBytes + size <= maxBytes)) {
                    kept++
                    totalBytes += size
                    continue
                }
                keeping = false
            }
            deleted.add(item)
        }
        if (deleted.isEmpty()) return
        val dao = daoInstanceOrResolve() ?: return
        val delete = daoDeleteMethod(dao) ?: return
        for (item in deleted) {
            runCatching { delete.invoke(dao, item) }
                .onFailure {
                    AndroidLog.e(TAG, "trim image failed id=${WeTypeClipboardImageHost.itemId(item)}: ${it.message}")
                }
            deleteImageFile(item)
            WeTypeClipboardImageHost.itemId(item)?.let { ids.remove(it) }
        }
        items.removeAll(deleted)
        AndroidLog.i(
            TAG,
            "image retention trimmed ${deleted.size} item(s), kept=$kept, size=${totalBytes / 1024}KB"
        )
    }

    private fun imageFileSize(item: Any): Long {
        if (WeTypeClipboardImageHost.itemPathType(item) != 0) return 0L
        val path = WeTypeClipboardImageHost.itemPath(item) ?: return 0L
        return runCatching { File(path).length() }.getOrDefault(0L)
    }

    private fun deleteImageFile(item: Any) {
        if (WeTypeClipboardImageHost.itemPathType(item) != 0) return
        val path = WeTypeClipboardImageHost.itemPath(item) ?: return
        runCatching {
            val file = File(path)
            if (file.isFile) file.delete()
        }
    }

    private fun daoDeleteMethod(dao: Any): Method? {
        return pickHostMethod(dao.javaClass, "f") {
            it.returnType == Void.TYPE && it.parameterTypes.size == 1 &&
                itemClass?.isAssignableFrom(it.parameterTypes[0]) == true
        }
    }

    /**
     * 宿主对 `type=1 and expireTimestamp<?` 的过期图片有清理查询；
     * 本地图片 expireTimestamp 仅 3 分钟，同步图片约 5 分钟，未及时展示就会被删除。
     * 这里在采集到图片后直接把全部图片的过期时间延后（受“解除剪贴板留存上限”开关控制），
     * 保证图片不会在用户打开面板前被宿主清掉。必须在非主线程调用。
     */
    private fun extendExpiryForImages(items: List<Any>) {
        val dao = daoInstanceOrResolve() ?: return
        val update = daoUpdateMethod(dao) ?: return
        val now = System.currentTimeMillis()
        val threshold = now + WeTypeClipboardImageHost.EXPIRY_EXTEND_WINDOW_MS
        val target = now + WeTypeClipboardImageHost.EXPIRY_EXTEND_TARGET_MS
        for (item in items) {
            if (WeTypeClipboardImageHost.itemType(item) != 1L) continue
            if (WeTypeClipboardImageHost.itemExpireTimestamp(item) > threshold) continue
            if (!WeTypeClipboardImageHost.setItemExpireTimestamp(item, target)) continue
            runCatching { update.invoke(dao, item) }
                .onFailure {
                    AndroidLog.e(TAG, "extend image expiry failed id=${WeTypeClipboardImageHost.itemId(item)}: ${it.message}")
                }
        }
    }

    /** 图片到达即预下载：签名 URL 有效期很短，不能等面板打开才触发。主线程调用。 */
    private fun autoRequestDownloads(items: List<Any>) {
        val context = currentApplicationContext() ?: return
        for (item in items) {
            if (WeTypeClipboardImageHost.itemType(item) != 1L) continue
            if (WeTypeClipboardImageHost.itemPathType(item) != 1) continue
            WeTypeClipboardImageLoader.request(item, context) { updated ->
                WeTypeClipboardImageEntries.refreshItem(updated)
                mainHandler.post { reapplyAll() }
            }
        }
    }

    private fun daoInstanceOrResolve(): Any? {
        daoInstance?.let { return it }
        val cl = hostClassLoader ?: return null
        resolveDao(cl)
        return daoInstance
    }

    private fun daoUpdateMethod(dao: Any): Method? {
        return pickHostMethod(dao.javaClass, "c") {
            it.parameterTypes.size == 1 &&
                itemClass?.isAssignableFrom(it.parameterTypes[0]) == true
        }
    }

    private fun currentApplicationContext(): Context? {
        appContext?.let { return it }
        val context = runCatching {
            val activityThread = Class.forName("android.app.ActivityThread")
            activityThread.getMethod("currentApplication").invoke(null) as? Context
        }.getOrNull() ?: return null
        appContext = context
        return context
    }

    /**
     * 直接把解密后的本地路径写回 DB（等价宿主 `B.P` 的 DAO 路径，避开宿主 suspend 链路）。
     * 必须在非主线程调用。
     */
    fun persistImagePath(id: Long, path: String): Boolean {
        return try {
            val dao = daoInstance ?: run {
                val cl = hostClassLoader ?: return false
                resolveDao(cl)
                daoInstance
            } ?: return false
            val load = pickHostMethod(dao.javaClass, "e") {
                it.parameterTypes.size == 1 &&
                    (it.parameterTypes[0] == Long::class.javaPrimitiveType || it.parameterTypes[0] == Long::class.java)
            } ?: return false
            val update = pickHostMethod(dao.javaClass, "c") {
                it.parameterTypes.size == 1 &&
                    itemClass?.isAssignableFrom(it.parameterTypes[0]) == true
            } ?: return false
            val record = load.invoke(dao, id) ?: return false
            WeTypeClipboardImageHost.setItemPath(record, path)
            WeTypeClipboardImageHost.setItemPathType(record, 0)
            if (WeTypeSettings.isRemoveClipboardRetentionLimitXposed()) {
                val target = System.currentTimeMillis() + WeTypeClipboardImageHost.EXPIRY_EXTEND_TARGET_MS
                WeTypeClipboardImageHost.setItemExpireTimestamp(record, target)
            }
            update.invoke(dao, record)
            synchronized(cacheLock) {
                for (item in cachedImages) {
                    if (WeTypeClipboardImageHost.itemId(item) == id) {
                        WeTypeClipboardImageHost.setItemPath(item, path)
                        WeTypeClipboardImageHost.setItemPathType(item, 0)
                        if (WeTypeSettings.isRemoveClipboardRetentionLimitXposed()) {
                            val target = System.currentTimeMillis() + WeTypeClipboardImageHost.EXPIRY_EXTEND_TARGET_MS
                            WeTypeClipboardImageHost.setItemExpireTimestamp(item, target)
                        }
                        break
                    }
                }
            }
            true
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "persist image path via dao failed id=$id: ${t.message}")
            false
        }
    }

    /** 图片下载落盘/DB 变更后由调用方触发一次列表重放。 */
    fun reapplyAll() {
        if (injecting) return
        if (WeTypeClipboardSearchFilter.isReplaying()) return
        if (WeTypeClipboardSearchFilter.currentKeywordValue().isNotEmpty()) return
        val targets = synchronized(cacheLock) {
            lastNativeByScrollView.entries.map { it.key to ArrayList(it.value) }
        }
        if (targets.isEmpty()) return
        for ((scrollView, nativeList) in targets) {
            val merged = mergeInto(nativeList)
            if (merged.size == nativeList.size) continue
            injecting = true
            try {
                invokeSetList(scrollView, merged)
                invokeAdapterRefresh(scrollView)
                invokeAdapterNotifyDataSetChanged(scrollView)
            } catch (t: Throwable) {
                AndroidLog.e(TAG, "clipboard image list reapply failed: ${t.message}")
            } finally {
                injecting = false
            }
        }
    }

    fun isInjecting(): Boolean = injecting

    /** 单条删除（宿主 B.y 或列表删除回调）后同步摘除缓存与模块私有副本。 */
    fun forgetById(id: Long) {
        synchronized(cacheLock) {
            cachedIds.remove(id)
            cachedImages.removeAll { WeTypeClipboardImageHost.itemId(it) == id }
        }
        currentApplicationContext()?.let { WeTypeClipboardImageStore.delete(it, id) }
    }

    fun clearCache() {
        synchronized(cacheLock) {
            cachedImages.clear()
            cachedIds.clear()
            lastNativeByScrollView.clear()
        }
    }

    private fun hookSetList(classLoader: ClassLoader) {
        val scrollClass = resolveClipboardScrollView(classLoader)
            ?: throw ClassNotFoundException(SCROLLVIEW_CLASS)
        var count = 0
        for (method in scrollClass.declaredMethods) {
            if (method.name != "setList") continue
            if (method.parameterTypes.size != 1) continue
            if (!List::class.java.isAssignableFrom(method.parameterTypes[0])) continue
            method.isAccessible = true
            method.hookBefore { param ->
                try {
                    if (injecting || WeTypeClipboardSearchFilter.isReplaying()) return@hookBefore
                    val raw = param.args.getOrNull(0) as? List<*> ?: return@hookBefore
                    val nativeList = raw.filterNotNull()
                    if (nativeList.isEmpty()) return@hookBefore
                    val merged = mergeInto(nativeList)
                    if (merged.size != nativeList.size) {
                        param.args[0] = ArrayList(merged)
                    }
                    onNativeSetList(param.thisObject, nativeList)
                } catch (t: Throwable) {
                    AndroidLog.e(TAG, "image list injection failed: ${t.message}")
                }
            }
            count++
        }
        AndroidLog.i(TAG, "hooked setList for image injection: $count overload(s)")
    }

    /** 宿主删除单条：`ImeClipboardScrollView.j(int, C)` 或 `ImeClipboardMgr.y(C)`。 */
    private fun hookDelete(classLoader: ClassLoader) {
        runCatching {
            val scrollClass = resolveClipboardScrollView(classLoader) ?: return@runCatching
            pickHostMethod(scrollClass, "j") {
                it.parameterTypes.size == 2 && itemClass!!.isAssignableFrom(it.parameterTypes[1])
            }?.hookAfter { param ->
                val item = param.args.getOrNull(1) ?: return@hookAfter
                WeTypeClipboardImageHost.itemId(item)?.let { forgetById(it) }
            }
        }
        runCatching {
            val mgrClass = resolveClipboardManager(classLoader) ?: return@runCatching
            pickHostMethod(mgrClass, "y") {
                it.parameterTypes.size == 1 && itemClass!!.isAssignableFrom(it.parameterTypes[0])
            }?.hookAfter { param ->
                val item = param.args.getOrNull(0) ?: return@hookAfter
                WeTypeClipboardImageHost.itemId(item)?.let { forgetById(it) }
            }
            pickHostMethod(mgrClass, "q") {
                it.parameterTypes.isEmpty() && it.returnType == Void.TYPE
            }?.hookAfter { clearCache() }
        }
    }

    private fun invokeSetList(scrollView: Any, list: List<Any>) {
        val method = scrollView.javaClass.declaredMethods.firstOrNull { m ->
            m.name == "setList" && m.parameterTypes.size == 1 &&
                List::class.java.isAssignableFrom(m.parameterTypes[0])
        } ?: return
        method.isAccessible = true
        method.invoke(scrollView, ArrayList(list))
    }

    private fun invokeAdapterRefresh(scrollView: Any) {
        val getter = scrollView.javaClass.declaredMethods.firstOrNull { it.name == "getListAdapter" } ?: return
        getter.isAccessible = true
        val adapter = getter.invoke(scrollView) ?: return
        val refresh = pickHostMethod(adapter.javaClass, "y") { it.parameterTypes.isEmpty() } ?: return
        refresh.invoke(adapter)
    }

    private fun invokeAdapterNotifyDataSetChanged(scrollView: Any) {
        val getter = scrollView.javaClass.declaredMethods.firstOrNull { it.name == "getListAdapter" } ?: return
        getter.isAccessible = true
        val adapter = getter.invoke(scrollView) ?: return
        val notify = runCatching {
            adapter.javaClass.getMethod("notifyDataSetChanged")
        }.getOrNull() ?: return
        notify.isAccessible = true
        notify.invoke(adapter)
    }

    /**
     * 契约优先、写死的短名兜底。契约解析出来的 Class 自带宿主 ClassLoader，不用再传 loader。
     */
    private fun resolveClipboardItem(classLoader: ClassLoader?): Class<*>? =
        runCatching { WeTypeHostContracts.classOf(HostContractId.CLIPBOARD_ITEM) }.getOrNull()
            ?: classLoader?.let {
                runCatching { Class.forName(CLIPBOARD_ITEM_CLASS, false, it) }.getOrNull()
            }

    private fun resolveDaoFactory(classLoader: ClassLoader?): Class<*>? =
        runCatching { WeTypeHostContracts.classOf(HostContractId.CLIPBOARD_DAO_FACTORY) }.getOrNull()
            ?: classLoader?.let {
                runCatching { Class.forName(DAO_FACTORY_CLASS, false, it) }.getOrNull()
            }

    private fun resolveClipboardManager(classLoader: ClassLoader?): Class<*>? =
        runCatching { WeTypeHostContracts.classOf(HostContractId.CLIPBOARD_MANAGER) }.getOrNull()
            ?: classLoader?.let {
                runCatching { Class.forName(CLIPBOARD_MGR_CLASS, false, it) }.getOrNull()
            }

    private fun resolveClipboardScrollView(classLoader: ClassLoader?): Class<*>? =
        runCatching { WeTypeHostContracts.classOf(HostContractId.CLIPBOARD_SCROLLVIEW) }.getOrNull()
            ?: classLoader?.let {
                runCatching { Class.forName(SCROLLVIEW_CLASS, false, it) }.getOrNull()
            }
}
