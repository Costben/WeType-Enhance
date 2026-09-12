package com.xposed.wetypehook.wetype.hook

import android.util.Log as AndroidLog
import com.xposed.wetypehook.wetype.settings.WeTypeSettings
import com.xposed.wetypehook.xposed.ProceedWithOriginal
import com.xposed.wetypehook.xposed.hookAfter
import com.xposed.wetypehook.xposed.hookBefore
import com.xposed.wetypehook.xposed.hookReplace
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

/**
 * 图片留存守卫。
 *
 * 宿主 `ImeClipboardMgr.clearExpireRecord(fromHideWindow=true)`（键盘窗口隐藏时触发）
 * 会删除全部已展示图片（DAO `d()` = type=1 and state=1）并清理图片文件
 * （`ImeClipboardMgr.s(C)` 删除 `pathType==0` 的本地文件），这就是同步图片“看一眼就没了”
 * 的根因。在“解除剪贴板留存上限”开启时，这里按调用栈区分宿主清理与用户主动操作：
 * - 窗口隐藏/过期清理（`ImeClipboardMgr.u`）→ 批量删除时剔除图片、跳过图片文件清理；
 * - 用户单条删除（deleteRecord 先 DAO `f` 再 `s`）→ 记录 id 后放行；
 * - 清空全部（clearAllRecordImpl 的 `h`/`t`）→ 不干预。
 */
internal object WeTypeClipboardRetentionGuard {

    private const val TAG = "WeTypeClipboardRetention"
    private const val CLIPBOARD_MGR_CLASS = "com.tencent.wetype.plugin.hld.clipboard.B"
    private const val DAO_FACTORY_CLASS = "com.tencent.wetype.plugin.hld.dao.c"
    private const val USER_DELETE_MEMORY_MS = 10_000L

    @Volatile
    private var installed = false

    @Volatile
    private var daoHooksInstalled = false

    private val recentUserDeletes = ConcurrentHashMap<Long, Long>()

    fun install(classLoader: ClassLoader) {
        if (installed) return
        val manager = runCatching {
            Class.forName(CLIPBOARD_MGR_CLASS, false, classLoader)
        }.getOrNull()
        if (manager == null) {
            AndroidLog.e(TAG, "clipboard manager missing: $CLIPBOARD_MGR_CLASS")
            return
        }
        hookResourceCleanup(manager)
        hookDaoFactory(classLoader)
        installed = true
        AndroidLog.i(TAG, "clipboard retention guard installed")
    }

    /**
     * 挂钩 `dao.c#a()` 工厂方法：
     * 宿主任意业务首次访问 DAO 单例时立即安装 DAO hooks，做到零延迟拦截。
     */
    private fun hookDaoFactory(classLoader: ClassLoader) {
        val factoryClass = runCatching {
            Class.forName(DAO_FACTORY_CLASS, false, classLoader)
        }.getOrNull() ?: run {
            AndroidLog.e(TAG, "dao factory class missing: $DAO_FACTORY_CLASS")
            return
        }
        val aMethod = factoryClass.declaredMethods.firstOrNull {
            it.name == "a" && it.parameterTypes.isEmpty() && !Modifier.isStatic(it.modifiers)
        } ?: run {
            AndroidLog.e(TAG, "dao factory method a() missing")
            return
        }
        aMethod.isAccessible = true
        aMethod.hookAfter { param ->
            val dao = param.result ?: return@hookAfter
            installDaoHooks(dao)
            WeTypeClipboardImageList.onDaoResolved(dao)
        }
        AndroidLog.i(TAG, "hooked dao factory method c#a()")
    }

    /** DAO 解析成功后调用：`h(List)`/`f(C)`/`o(long)`/`j(long)`/`d()` 都在 DAO 实现类上。 */
    fun installDaoHooks(dao: Any) {
        if (daoHooksInstalled) return
        hookBatchDelete(dao)
        hookUserDelete(dao)
        hookExpiredQueries(dao)
        daoHooksInstalled = true
        AndroidLog.i(TAG, "clipboard retention dao hooks installed on ${dao.javaClass.name}")
    }

    /**
     * 拦截宿主清理过期/已展示图片的 DAO 查询：
     * - `dao.o(long)`: 查询 `type=1 and expireTimestamp < ?`。宿主在 `B.u` 中据此删图片记录和文件；
     * - `dao.j(long)`: 查询 `source=1 and expireTimestamp < ?`。宿主在 `B.u` 中据此删远程同步记录；
     * - `dao.d()`: 查询 `type=1 and state=1`。宿主在 `B.u(fromHideWindow=true)` 隐藏键盘时据此删所有已展示图片。
     * 在解除留存上限开启时直接替换返回空列表，从根源切断宿主清理链路，且不影响图片面板自身的展示查询。
     */
    private fun hookExpiredQueries(dao: Any) {
        val daoClass = dao.javaClass

        // 1. dao.o(long)
        daoClass.declaredMethods.firstOrNull {
            it.name == "o" && it.parameterTypes.size == 1 &&
                (it.parameterTypes[0] == Long::class.javaPrimitiveType || it.parameterTypes[0] == Long::class.java) &&
                List::class.java.isAssignableFrom(it.returnType)
        }?.let { m ->
            m.isAccessible = true
            m.hookReplace {
                if (WeTypeSettings.isRemoveClipboardRetentionLimitXposed()) {
                    AndroidLog.i(TAG, "intercepted dao.o() expired images query, returning empty list")
                    emptyList<Any>()
                } else {
                    ProceedWithOriginal
                }
            }
        } ?: AndroidLog.e(TAG, "dao.o(long) method missing")

        // 2. dao.j(long)
        daoClass.declaredMethods.firstOrNull {
            it.name == "j" && it.parameterTypes.size == 1 &&
                (it.parameterTypes[0] == Long::class.javaPrimitiveType || it.parameterTypes[0] == Long::class.java) &&
                List::class.java.isAssignableFrom(it.returnType)
        }?.let { m ->
            m.isAccessible = true
            m.hookReplace {
                if (WeTypeSettings.isRemoveClipboardRetentionLimitXposed()) {
                    AndroidLog.i(TAG, "intercepted dao.j() expired sync items query, returning empty list")
                    emptyList<Any>()
                } else {
                    ProceedWithOriginal
                }
            }
        } ?: AndroidLog.e(TAG, "dao.j(long) method missing")

        // 3. dao.d()
        daoClass.declaredMethods.firstOrNull {
            it.name == "d" && it.parameterTypes.isEmpty() &&
                List::class.java.isAssignableFrom(it.returnType)
        }?.let { m ->
            m.isAccessible = true
            m.hookReplace {
                if (WeTypeSettings.isRemoveClipboardRetentionLimitXposed() && stackHasFrame(CLIPBOARD_MGR_CLASS, "u")) {
                    AndroidLog.i(TAG, "intercepted dao.d() clear-shown-images query in B.u, returning empty list")
                    emptyList<Any>()
                } else {
                    ProceedWithOriginal
                }
            }
        } ?: AndroidLog.e(TAG, "dao.d() method missing")
    }

    /** `void h(List<C>)`：宿主批量删除。窗口隐藏清理时剔除图片条目。 */
    private fun hookBatchDelete(dao: Any) {
        val method = dao.javaClass.declaredMethods.firstOrNull {
            it.name == "h" && it.returnType == Void.TYPE &&
                it.parameterTypes.size == 1 && it.parameterTypes[0] == List::class.java
        } ?: run {
            AndroidLog.e(TAG, "batch delete method missing: h(List)")
            return
        }
        method.isAccessible = true
        method.hookBefore { param ->
            if (!WeTypeSettings.isRemoveClipboardRetentionLimitXposed()) return@hookBefore
            if (!stackHasFrame(CLIPBOARD_MGR_CLASS, "u")) return@hookBefore
            val list = param.args[0] as? List<*> ?: return@hookBefore
            val kept = list.filterNot { it != null && WeTypeClipboardImageHost.itemType(it) == 1L }
            if (kept.size == list.size) return@hookBefore
            param.args[0] = ArrayList(kept)
            AndroidLog.i(TAG, "kept ${list.size - kept.size} image(s) from batch delete")
        }
        method.hookAfter { WeTypeClipboardImageList.primeAsync() }
    }

    /** `void s(C)`：宿主清理条目资源（本地图片文件）。非用户删除时跳过图片。 */
    private fun hookResourceCleanup(manager: Class<*>) {
        val method = manager.declaredMethods.firstOrNull {
            it.name == "s" && it.returnType == Void.TYPE &&
                it.parameterTypes.size == 1 &&
                !it.parameterTypes[0].isPrimitive &&
                it.parameterTypes[0] != List::class.java &&
                !Modifier.isStatic(it.modifiers)
        } ?: run {
            AndroidLog.e(TAG, "resource cleanup method missing: s(C)")
            return
        }
        method.isAccessible = true
        method.hookBefore { param ->
            if (!WeTypeSettings.isRemoveClipboardRetentionLimitXposed()) return@hookBefore
            val item = param.args[0] ?: return@hookBefore
            if (WeTypeClipboardImageHost.itemType(item) != 1L) return@hookBefore
            WeTypeClipboardImageHost.itemId(item)?.let { id ->
                if (isRecentUserDelete(id)) return@hookBefore
            }
            if (!stackHasFrame(manager.name, "u")) return@hookBefore
            AndroidLog.i(
                TAG,
                "kept image file id=${WeTypeClipboardImageHost.itemId(item)}"
            )
            param.result = null
        }
    }

    /** `void f(C)`：用户/宿主单条删除。记录用户删除，供资源清理放行。 */
    private fun hookUserDelete(dao: Any) {
        val method = dao.javaClass.declaredMethods.firstOrNull {
            it.name == "f" && it.returnType == Void.TYPE &&
                it.parameterTypes.size == 1 &&
                !it.parameterTypes[0].isPrimitive &&
                it.parameterTypes[0] != List::class.java &&
                !Modifier.isStatic(it.modifiers)
        } ?: run {
            AndroidLog.e(TAG, "single delete method missing: f(C)")
            return
        }
        method.isAccessible = true
        method.hookBefore { param ->
            val item = param.args[0] ?: return@hookBefore
            if (WeTypeClipboardImageHost.itemType(item) != 1L) return@hookBefore
            val id = WeTypeClipboardImageHost.itemId(item) ?: return@hookBefore
            val now = android.os.SystemClock.uptimeMillis()
            recentUserDeletes[id] = now
            if (recentUserDeletes.size > 50) {
                recentUserDeletes.entries.removeAll { now - it.value > USER_DELETE_MEMORY_MS }
            }
        }
        method.hookAfter { WeTypeClipboardImageList.primeAsync() }
    }

    private fun isRecentUserDelete(id: Long): Boolean {
        val at = recentUserDeletes[id] ?: return false
        return android.os.SystemClock.uptimeMillis() - at <= USER_DELETE_MEMORY_MS
    }

    /** 当前调用栈是否来自 [managerClassName] 的 [methodName]（宿主清理在 `B.u` 内同步执行）。 */
    private fun stackHasFrame(managerClassName: String, methodName: String): Boolean {
        val frames = Throwable().stackTrace
        for (i in 1 until frames.size) {
            val frame = frames[i]
            if (frame.className == managerClassName && frame.methodName == methodName) return true
        }
        return false
    }
}
