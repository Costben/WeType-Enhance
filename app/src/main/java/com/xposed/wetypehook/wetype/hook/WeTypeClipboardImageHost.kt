package com.xposed.wetypehook.wetype.hook

import android.os.Bundle
import android.util.Log as AndroidLog
import android.view.View
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

/**
 * 剪贴板图片增强的宿主反射句柄（C/z 数据与视图、尺寸换算、S33 导航）。
 *
 * 3.5.3 与 3.5.4 实证：
 * - C 访问器二进制名一致：q()=type、i()=path、j()=pathType、k()=receiveTimestampFromServer、
 *   f()=id、z(String)=setPath、A(int)=setPathType；
 * - 尺寸换算类 3.5.3=`...utils.m1`、3.5.4=`...utils.n1`（实例单例 + l0(Integer)）；
 *   原始设计 px 换算 3.5.3=`...utils.q1.e0`、3.5.4=`...utils.r1.e0`（静态）；
 * - 面板枚举二进制名一致 `...keyboard.t`（含 ImagePreview(509)）；
 * - S33 导航：优先 n3(enum, Bundle)，3.5.3 退 p3/k3，均为 (面板枚举, Bundle) -> void。
 */
internal object WeTypeClipboardImageHost {

    const val TAG = "WeTypeClipboardImage"

    private const val IDS_CLASS = "com.tencent.wetype.plugin.hld.s"
    private const val DIMEN_CLASS = "com.tencent.wetype.plugin.hld.q"
    private const val PANEL_ENUM_CLASS = "com.tencent.wetype.plugin.hld.keyboard.t"
    private const val N_CLASS = "com.tencent.wetype.plugin.hld.model.N"
    private const val CLIPBOARD_ITEM_CLASS = "com.tencent.wetype.plugin.hld.clipboard.C"

    private val SCALE_INSTANCE_CLASSES = arrayOf(
        "com.tencent.wetype.plugin.hld.utils.n1",
        "com.tencent.wetype.plugin.hld.utils.m1"
    )
    private val RAW_SCALE_CLASSES = arrayOf(
        "com.tencent.wetype.plugin.hld.utils.r1",
        "com.tencent.wetype.plugin.hld.utils.q1"
    )

    private const val KEY_CLIPBOARD_ID = "key_clipboard_id"
    private const val PANEL_IMAGE_PREVIEW = "ImagePreview"

    @Volatile
    private var installed = false

    @Volatile
    private var hostClassLoader: ClassLoader? = null

    @Volatile
    private var itemClass: Class<*>? = null

    @Volatile
    private var scaleInstance: Any? = null

    @Volatile
    private var scaleL0Method: Method? = null

    @Volatile
    private var rawScaleMethod: Method? = null

    @Volatile
    private var panelClass: Class<*>? = null

    @Volatile
    private var navManager: Any? = null

    @Volatile
    private var navMethod: Method? = null

    @Volatile
    private var navPanel: Any? = null

    @Volatile
    var contentTvId: Int = 0
        private set

    @Volatile
    var contentScrollId: Int = 0
        private set

    @Volatile
    var line1Id: Int = 0
        private set

    @Volatile
    var keyInfoId: Int = 0
        private set

    @Volatile
    var line1HeightRes: Int = 0
        private set

    @Volatile
    var line2HeightRes: Int = 0
        private set

    @Volatile
    var inputIndentRes: Int = 0
        private set

    private val getterCache = ConcurrentHashMap<String, Method>()
    private val setterCache = ConcurrentHashMap<String, Method>()
    private val holderItemFieldCache = ConcurrentHashMap<Class<*>, Field>()

    @Volatile
    private var runtimeReady = false

    /**
     * 只做零初始化的类加载与资源 ID 读取。
     * 禁止在此读宿主静态字段/枚举：宿主 `utils.m1` 的 <clinit> 会碰 MMKV，
     * 在 Application 早启动阶段触发会直接杀死微信输入法进程（实机实证）。
     */
    fun install(classLoader: ClassLoader): Boolean {
        if (installed) return true
        hostClassLoader = classLoader
        return try {
            itemClass = Class.forName(CLIPBOARD_ITEM_CLASS, false, classLoader)
            resolveIds(classLoader)
            installed = true
            AndroidLog.i(TAG, "image host handles installed (line1=$line1HeightRes line2=$line2HeightRes)")
            true
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "image host handles unavailable: ${t.message}")
            false
        }
    }

    fun isReady(): Boolean = installed

    /** 首次实际渲染/导航时再解析宿主单例与枚举（此时宿主已完成自身初始化）。 */
    private fun ensureRuntime() {
        if (runtimeReady) return
        synchronized(this) {
            if (runtimeReady) return
            val cl = hostClassLoader ?: return
            runCatching { resolveScaleHandles(cl) }
                .onFailure { AndroidLog.e(TAG, "resolve scale handles failed: ${it.message}") }
            runCatching { resolveNavigation(cl) }
                .onFailure { AndroidLog.e(TAG, "resolve navigation failed: ${it.message}") }
            runtimeReady = true
            AndroidLog.i(
                TAG,
                "image host runtime resolved: line1=${dimenToPxRaw(line1HeightRes)} " +
                    "line2=${dimenToPxRaw(line2HeightRes)} inset12=${rawToPxRaw(12)} " +
                    "indent=${dimenToPxRaw(inputIndentRes)} nav=${navMethod != null}"
            )
        }
    }

    private fun dimenToPxRaw(resId: Int): Int {
        if (resId == 0) return 0
        val method = scaleL0Method ?: return resId
        val instance = scaleInstance ?: return resId
        return runCatching { method.invoke(instance, resId) as? Int }.getOrNull() ?: resId
    }

    private fun rawToPxRaw(designPx: Int): Int {
        val method = rawScaleMethod ?: return designPx
        return runCatching { method.invoke(null, designPx) as? Int }.getOrNull() ?: designPx
    }

    private fun resolveIds(classLoader: ClassLoader) {
        val ids = Class.forName(IDS_CLASS, false, classLoader)
        contentTvId = ids.getField("clipboard_content_tv").getInt(null)
        contentScrollId = ids.getField("clipboard_content_scrollview").getInt(null)
        line1Id = ids.getField("clipboard_item_line1").getInt(null)
        keyInfoId = ids.getField("clipboard_key_information_rv").getInt(null)
        val dimen = Class.forName(DIMEN_CLASS, false, classLoader)
        line1HeightRes = dimen.getField("keyboard_custom_phrase_item_line1_height").getInt(null)
        line2HeightRes = dimen.getField("keyboard_clipboard_item_line2_height").getInt(null)
        inputIndentRes = dimen.getField("keyboard_custom_phrase_item_input_code_margin_start").getInt(null)
    }

    private fun resolveScaleHandles(classLoader: ClassLoader) {
        for (name in SCALE_INSTANCE_CLASSES) {
            val cls = runCatching { Class.forName(name, false, classLoader) }.getOrNull() ?: continue
            val instance = staticSelfInstance(cls) ?: continue
            val l0 = cls.declaredMethods.firstOrNull {
                it.name == "l0" && it.parameterTypes.size == 1 &&
                    (it.parameterTypes[0] == Integer::class.java || it.parameterTypes[0] == Int::class.javaPrimitiveType) &&
                    (it.returnType == Int::class.javaPrimitiveType || it.returnType == Integer::class.java)
            } ?: continue
            l0.isAccessible = true
            scaleInstance = instance
            scaleL0Method = l0
            break
        }
        for (name in RAW_SCALE_CLASSES) {
            val cls = runCatching { Class.forName(name, false, classLoader) }.getOrNull() ?: continue
            val e0 = cls.declaredMethods.firstOrNull {
                it.name == "e0" && Modifier.isStatic(it.modifiers) &&
                    (it.parameterTypes.size == 1) &&
                    (it.parameterTypes[0] == Int::class.javaPrimitiveType || it.parameterTypes[0] == Integer::class.java) &&
                    (it.returnType == Int::class.javaPrimitiveType || it.returnType == Integer::class.java)
            } ?: continue
            e0.isAccessible = true
            rawScaleMethod = e0
            break
        }
        if (scaleL0Method == null || rawScaleMethod == null) {
            AndroidLog.e(TAG, "scale handles missing: l0=${scaleL0Method != null} e0=${rawScaleMethod != null}")
        }
    }

    private fun resolveNavigation(classLoader: ClassLoader) {
        panelClass = Class.forName(PANEL_ENUM_CLASS, false, classLoader)
        val panel = panelClass ?: return
        navPanel = panel.enumConstants?.firstOrNull { (it as? Enum<*>)?.name == PANEL_IMAGE_PREVIEW }
        if (navPanel == null) {
            AndroidLog.e(TAG, "ImagePreview panel enum missing")
            return
        }
        val n = runCatching { Class.forName(N_CLASS, false, classLoader) }.getOrNull() ?: return
        navManager = staticSelfInstance(n)
        val candidates = arrayOf("n3", "p3", "k3", "l3")
        for (name in candidates) {
            val m = n.declaredMethods.firstOrNull {
                it.name == name && it.parameterTypes.size == 2 &&
                    it.parameterTypes[0] == panel && it.parameterTypes[1] == Bundle::class.java &&
                    it.returnType == Void.TYPE
            } ?: continue
            m.isAccessible = true
            navMethod = m
            break
        }
        if (navMethod == null) {
            AndroidLog.e(TAG, "S33 navigation method missing (n3/p3/k3)")
        }
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

    fun openImagePreview(item: Any): Boolean {
        return try {
            ensureRuntime()
            val manager = navManager ?: return false
            val method = navMethod ?: return false
            val panel = navPanel ?: return false
            val id = itemId(item) ?: return false
            val bundle = Bundle().apply { putLong(KEY_CLIPBOARD_ID, id) }
            method.invoke(manager, panel, bundle)
            true
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "open image preview failed: ${t.message}")
            false
        }
    }

    // ---- C 条目数据面 ----

    private fun bindGetter(item: Any, name: String, returnType: Class<*>?): Method? {
        if (returnType == null) return null
        val cls = item.javaClass
        val key = "${cls.name}#$name"
        getterCache[key]?.let { return it }
        val m = cls.declaredMethods.firstOrNull {
            it.name == name && it.parameterTypes.isEmpty() && it.returnType == returnType
        } ?: return null
        m.isAccessible = true
        getterCache[key] = m
        return m
    }

    fun itemType(item: Any): Long = invokeLong(item, "q", 0L)

    fun itemId(item: Any): Long? {
        val m = bindGetter(item, "f", Long::class.javaPrimitiveType) ?: return null
        return runCatching { m.invoke(item) as? Long }.getOrNull()
    }

    fun itemPath(item: Any): String? {
        val m = bindGetter(item, "i", String::class.java) ?: return null
        return runCatching { m.invoke(item) as? String }.getOrNull()
    }

    fun itemPathType(item: Any): Int {
        val m = bindGetter(item, "j", Int::class.javaPrimitiveType) ?: return 0
        return runCatching { m.invoke(item) as? Int }.getOrNull() ?: 0
    }

    fun itemReceiveTimestamp(item: Any): Long = invokeLong(item, "k", 0L)

    fun itemCreateTime(item: Any): Long = invokeLong(item, "c", 0L)

    private fun invokeLong(item: Any, name: String, fallback: Long): Long {
        val m = bindGetter(item, name, Long::class.javaPrimitiveType) ?: return fallback
        return runCatching { m.invoke(item) as? Long }.getOrNull() ?: fallback
    }

    fun setItemPath(item: Any, path: String): Boolean {
        val m = bindSetter(item, "z", String::class.java) ?: return false
        return runCatching { m.invoke(item, path); true }.getOrDefault(false)
    }

    fun setItemPathType(item: Any, pathType: Int): Boolean {
        val m = bindSetter(item, "A", Int::class.javaPrimitiveType) ?: return false
        return runCatching { m.invoke(item, pathType); true }.getOrDefault(false)
    }

    private fun bindSetter(item: Any, name: String, paramType: Class<*>?): Method? {
        if (paramType == null) return null
        val cls = item.javaClass
        val key = "${cls.name}#$name"
        setterCache[key]?.let { return it }
        val m = cls.declaredMethods.firstOrNull {
            it.name == name && it.parameterTypes.size == 1 && it.parameterTypes[0] == paramType
        } ?: return null
        m.isAccessible = true
        setterCache[key] = m
        return m
    }

    fun holderRecordItem(holder: Any): Any? {
        val cls = holder.javaClass
        holderItemFieldCache[cls]?.let { field ->
            return runCatching { field.get(holder) }.getOrNull()
        }
        val field = findRecordField(cls) ?: return null
        holderItemFieldCache[cls] = field
        return runCatching { field.get(holder) }.getOrNull()
    }

    private fun findRecordField(cls: Class<*>): Field? {
        val target = itemClass
        var current: Class<*>? = cls
        while (current != null) {
            for (field in current.declaredFields) {
                if (target != null && field.type == target) {
                    runCatching { field.isAccessible = true }
                    return field
                }
            }
            current = current.superclass
        }
        return null
    }

    // ---- 尺寸换算 ----

    fun dimenToPx(resId: Int): Int {
        ensureRuntime()
        return dimenToPxRaw(resId)
    }

    fun rawToPx(designPx: Int): Int {
        ensureRuntime()
        return rawToPxRaw(designPx)
    }

    fun findView(itemView: View, id: Int): View? {
        if (id == 0) return null
        return runCatching { itemView.findViewById<View>(id) }.getOrNull()
    }
}
