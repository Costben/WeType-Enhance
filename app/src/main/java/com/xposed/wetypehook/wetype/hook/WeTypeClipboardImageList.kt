package com.xposed.wetypehook.wetype.hook

import android.os.Handler
import android.os.Looper
import android.util.Log as AndroidLog
import com.xposed.wetypehook.xposed.hookAfter
import com.xposed.wetypehook.xposed.hookBefore
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

    fun install(classLoader: ClassLoader): Boolean {
        if (installed) return true
        hostClassLoader = classLoader
        return try {
            // 只做零初始化加载；DAO 单例延后到首次 primeAsync（宿主 DB 已就绪后）。
            itemClass = Class.forName(CLIPBOARD_ITEM_CLASS, false, classLoader)
            hookSetList(classLoader)
            hookDelete(classLoader)
            installed = true
            AndroidLog.i(TAG, "clipboard image list injector installed")
            true
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "clipboard image list injector install failed: ${t.message}")
            false
        }
    }

    /**
     * 零初始化解析 DAO：Room 查询推到首次 [primeAsync]，避免 Application 早期 <clinit>。
     */
    private fun resolveDao(classLoader: ClassLoader) {
        val factory = runCatching { Class.forName(DAO_FACTORY_CLASS, false, classLoader) }.getOrNull() ?: return
        val singleton = factory.declaredFields.firstOrNull {
            Modifier.isStatic(it.modifiers) && it.type == factory
        } ?: return
        val factoryInstance = runCatching {
            singleton.isAccessible = true
            singleton.get(null)
        }.getOrNull() ?: return
        val dao = factory.declaredMethods.firstOrNull {
            it.parameterTypes.isEmpty() && it.returnType != Void.TYPE
        }?.apply { isAccessible = true }?.let {
            runCatching { it.invoke(factoryInstance) }.getOrNull()
        } ?: return
        daoInstance = dao
        daoImages = dao.javaClass.declaredMethods.firstOrNull {
            it.name == "d" && it.parameterTypes.isEmpty() &&
                List::class.java.isAssignableFrom(it.returnType)
        }?.apply { isAccessible = true }
        daoUnshown = dao.javaClass.declaredMethods.firstOrNull {
            it.name == "l" && it.parameterTypes.isEmpty() &&
                List::class.java.isAssignableFrom(it.returnType)
        }?.apply { isAccessible = true }
    }

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
        for (method in listOf(daoImages, daoUnshown)) {
            val m = method ?: continue
            val list = runCatching { m.invoke(dao) as? List<*> }.getOrNull() ?: continue
            for (item in list) {
                if (item == null) continue
                if (WeTypeClipboardImageHost.itemType(item) != 1L) continue
                val id = WeTypeClipboardImageHost.itemId(item) ?: continue
                if (ids.add(id)) collected.add(item)
            }
        }
        collected.sortByDescending { WeTypeClipboardImageHost.itemCreateTime(it) }
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
            mainHandler.post { reapplyAll() }
        }
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
            val load = dao.javaClass.declaredMethods.firstOrNull {
                it.name == "e" && it.parameterTypes.size == 1 &&
                    (it.parameterTypes[0] == Long::class.javaPrimitiveType || it.parameterTypes[0] == Long::class.java)
            }?.apply { isAccessible = true } ?: return false
            val update = dao.javaClass.declaredMethods.firstOrNull {
                it.name == "c" && it.parameterTypes.size == 1 &&
                    itemClass?.isAssignableFrom(it.parameterTypes[0]) == true
            }?.apply { isAccessible = true } ?: return false
            val record = load.invoke(dao, id) ?: return false
            WeTypeClipboardImageHost.setItemPath(record, path)
            WeTypeClipboardImageHost.setItemPathType(record, 0)
            update.invoke(dao, record)
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

    /** 单条删除（宿主 B.y 或列表删除回调）后同步摘除缓存。 */
    fun forgetById(id: Long) {
        synchronized(cacheLock) {
            if (!cachedIds.remove(id)) return
            cachedImages.removeAll { WeTypeClipboardImageHost.itemId(it) == id }
        }
    }

    fun clearCache() {
        synchronized(cacheLock) {
            cachedImages.clear()
            cachedIds.clear()
            lastNativeByScrollView.clear()
        }
    }

    private fun hookSetList(classLoader: ClassLoader) {
        val scrollClass = Class.forName(SCROLLVIEW_CLASS, false, classLoader)
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
            val scrollClass = Class.forName(SCROLLVIEW_CLASS, false, classLoader)
            for (method in scrollClass.declaredMethods) {
                if (method.name != "j") continue
                if (method.parameterTypes.size != 2) continue
                if (!itemClass!!.isAssignableFrom(method.parameterTypes[1])) continue
                method.isAccessible = true
                method.hookAfter { param ->
                    val item = param.args.getOrNull(1) ?: return@hookAfter
                    WeTypeClipboardImageHost.itemId(item)?.let { forgetById(it) }
                }
            }
        }
        runCatching {
            val mgrClass = Class.forName(CLIPBOARD_MGR_CLASS, false, classLoader)
            for (method in mgrClass.declaredMethods) {
                if (method.name == "y" && method.parameterTypes.size == 1 &&
                    itemClass!!.isAssignableFrom(method.parameterTypes[0])
                ) {
                    method.isAccessible = true
                    method.hookAfter { param ->
                        val item = param.args.getOrNull(0) ?: return@hookAfter
                        WeTypeClipboardImageHost.itemId(item)?.let { forgetById(it) }
                    }
                }
                if (method.name == "q" && method.parameterTypes.isEmpty() &&
                    method.returnType == Void.TYPE
                ) {
                    method.isAccessible = true
                    method.hookAfter { clearCache() }
                }
            }
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
        val refresh = adapter.javaClass.declaredMethods.firstOrNull { it.name == "y" && it.parameterTypes.isEmpty() }
            ?: return
        refresh.isAccessible = true
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
}
