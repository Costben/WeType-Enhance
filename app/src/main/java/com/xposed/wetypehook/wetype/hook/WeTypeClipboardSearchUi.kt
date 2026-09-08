package com.xposed.wetypehook.wetype.hook

import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Log as AndroidLog
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import com.xposed.wetypehook.wetype.settings.WeTypeSettings
import com.xposed.wetypehook.xposed.ProceedWithOriginal
import com.xposed.wetypehook.xposed.hookAfter
import com.xposed.wetypehook.xposed.hookReplace
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.roundToInt

/**
 * Slice 4：剪贴板搜索（键盘页顶部生长一行）。
 *
 * 链路：剪贴板页红钮只导航（N.O2 回键盘），不在剪贴板页挂任何行；
 * 键盘页在 keyboard_container 之前插一行搜索条（纵向父 = 窗口向上
 * 长出一行，键位不被挤；非纵向父则退 decor 浮层，同样不挤键）。
 * 回车/✓ 带词跳回剪贴板走 S5 过滤（S5c：容器序数直点工具栏行第 3 位
 * performClick；先摘条还账等一帧再找行点之）。不准挤剪贴板/键位。
 */
internal object WeTypeClipboardSearchUi {

    private const val TAG = "WeTypeClipboardSearch"
    private const val S15_CLASS = "com.tencent.wetype.plugin.hld.keyboard.S15CustomPhraseAndClipboardKeyboard"
    private const val WETYPE_ID_CLASS = "com.tencent.wetype.plugin.hld.s"
    private const val WETYPE_DRAWABLE_CLASS = "com.tencent.wetype.plugin.hld.r"

    private const val TAG_SEARCH_BUTTON = "wetype_clipboard_search_btn_s4"
    private const val TAG_SEARCH_BOX = "wetype_clipboard_search_box_s4"
    private const val TAG_SEARCH_BOX_CONTAINER = "wetype_clipboard_search_box_container_s6"
    private const val TAG_SEARCH_CLEAR = "wetype_clipboard_search_clear_s6"

    private const val BOX_MARGIN_H_DP = 12f
    private const val BOX_MARGIN_V_DP = 4f
    private const val BTN_GAP_DP = 4f
    private const val BOX_TEXT_SP = 16f
    private const val ROW_PADDING_H_DP = 8f
    private const val ROW_PADDING_V_DP = 4f
    private const val CLEAR_BOX_DP = 32f
    private const val CLEAR_ICON_PADDING_DP = 8f
    private const val ROW_ICON_DP = 20f

    @Volatile
    private var keywordListenerImpl: ((String) -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val trackedBoxes: MutableSet<EditText> =
        Collections.newSetFromMap(WeakHashMap<EditText, Boolean>())

    @Volatile
    private var hooked = false

    private const val TAB_CLIPBOARD = 0
    private const val CLIPBOARD_PANEL_ID = 4
    private val tabIndexByHost: MutableMap<Any, Int> =
        Collections.synchronizedMap(WeakHashMap<Any, Int>())
    /**
     * S5e-A4：host/Method 强引用（WeakHashMap 弱键到跳页时可被 GC 致 map 空，
     * 编程式分支静默返 false；实证跳页全程无 jump via programmatic 日志）。
     * 进程级单例持有，不泄漏出进程；上限 8 个 host 轮转防增生。
     */
    private val tabHostStrongRefs: MutableSet<Any> =
        Collections.synchronizedSet(LinkedHashSet<Any>())
    /** host -> f1(int) 强引用（与 host 同轮转，同上限；兜底动态查找保留）。 */
    private val f1MethodStrongByHost: MutableMap<Any, java.lang.reflect.Method> =
        Collections.synchronizedMap(LinkedHashMap<Any, java.lang.reflect.Method>())
    /**
     * S5e-A4：host -> Y0(Bundle) 强引用（实证当前版本切页通道：Y0 单 Bundle 参，
     * 内含 target_tab_index；f1 为 (int,boolean,boolean) 三参，单参直调已失效）。
     */
    private val y0MethodStrongByHost: MutableMap<Any, java.lang.reflect.Method> =
        Collections.synchronizedMap(LinkedHashMap<Any, java.lang.reflect.Method>())
    /**
     * S5e-A4：最近一次 Y0 实包 clone（同形回放只改 target_tab_index；合成包
     * 切页不完整——只改内部态不渲染列表）。
     */
    private val y0BundleStrongByHost: MutableMap<Any, android.os.Bundle> =
        Collections.synchronizedMap(LinkedHashMap<Any, android.os.Bundle>())
    /**
     * S5e-A4：最近一次 i0 实参 arg0（原生 key 对象，无法合成，只能缓存同形回放；
     * 上限 8 轮转，随 host 联动淘汰）。
     */
    private val i0ArgStrongByHost: MutableMap<Any, Any> =
        Collections.synchronizedMap(LinkedHashMap<Any, Any>())
    /**
     * S5e-A4：N#k3 方法强引用 + 面板实参强引用（类名 -> 面板对象，上限 8 轮转；
     * 实证 key 为 keyboard.t@CustomPhraseAndClipboard）。
     */
    @Volatile
    private var k3MethodStrong: java.lang.reflect.Method? = null
    private val k3PanelStrongRefs: MutableMap<String, Any> =
        Collections.synchronizedMap(LinkedHashMap<String, Any>())

    /** 离页打字流：剪贴板点红钮 → overlayPending → 键盘页顶部长行 → 带词跳回。 */
    @Volatile
    private var overlayPending = false
    @Volatile
    private var pendingKeyword = ""
    @Volatile
    private var hostClassLoader: ClassLoader? = null
    @Volatile
    private var overlayParentRef: java.lang.ref.WeakReference<ViewGroup>? = null
    @Volatile
    private var insetsLogged = false
    /** 容器长高记录：container -> 垫高的 px，拆条时原样还回去（防重复垫/漏还）。 */
    private val grownHeightByContainer: MutableMap<ViewGroup, Int> =
        Collections.synchronizedMap(WeakHashMap<ViewGroup, Int>())
    /** S2R：FrameLayout同步加高记账：container -> 被加高的FrameLayout祖先链（含realParent本体）。 */
    private val grownParentsByContainer: MutableMap<ViewGroup, MutableList<ViewGroup>> =
        Collections.synchronizedMap(WeakHashMap<ViewGroup, MutableList<ViewGroup>>())
    /** S5-A2(a)：加高前原layout_height记账（MATCH/WRAP转显式高度后按原值还）。 */
    private val grownOrigHeightByView: MutableMap<ViewGroup, Int> =
        Collections.synchronizedMap(WeakHashMap<ViewGroup, Int>())

    // S5：调试广播已删（SEARCH_STRIP/test_text/show链路移除，说明保留）。
    // 验收后主控复验走剪贴板红钮链路（点红钮→键盘条），不再走adb广播。

    fun setKeywordListener(listener: ((String) -> Unit)?) {
        keywordListenerImpl = listener
    }

    fun clearSearch() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            clearSearchOnMain()
        } else {
            mainHandler.post { clearSearchOnMain() }
        }
    }

    fun install(classLoader: ClassLoader) {
        if (hooked) return
        hostClassLoader = classLoader
        try {
            val s15 = runCatching { Class.forName(S15_CLASS, false, classLoader) }.getOrNull()
            if (s15 == null) {
                AndroidLog.e(TAG, "S15 host class not found, skip search UI mount")
                return
            }
            val triggerNames = setOf("f1", "Y0", "i0", "a1", "d1", "O")
            var count = 0
            for (method in s15.declaredMethods) {
                if (method.name !in triggerNames) continue
                try {
                    method.isAccessible = true
                    method.hookAfter { param ->
                        try {
                            recordTabIndex(param.thisObject, method.name, param.args)
                            ensureSearchUi(param.thisObject)
                        } catch (t: Throwable) {
                            AndroidLog.e(TAG, "ensureSearchUi dispatch failed: ${t.message}")
                        }
                    }
                    count++
                } catch (t: Throwable) {
                    AndroidLog.e(TAG, "hook S15#${method.name} failed: ${t.message}")
                }
            }
            hooked = true
            AndroidLog.i(TAG, "hooked S15 methods for search UI mount: $count")
            hookSearchInputRouting(classLoader)
            hookKeyboardTeardown(classLoader)
            hookInsetsForStrip(classLoader)
            // S5e-A4：缓存 k3 剪贴板面板实参（正向导航渲染调用，同形回放用）。
            hookK3PanelCache(classLoader)
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "install search UI failed: ${t.message}")
        }
    }

    /**
     * S5e-A4：hook model.N#k3 只缓存面板实参（强引用，上限 8 轮转）。
     * 实证用户点图标行调 k3(keyboard.t@CustomPhraseAndClipboard, Bundle{target=0})。
     */
    private fun hookK3PanelCache(classLoader: ClassLoader) {
        try {
            val nClass = runCatching {
                Class.forName("com.tencent.wetype.plugin.hld.model.N", false, classLoader)
            }.getOrNull() ?: return
            val k3 = nClass.declaredMethods.firstOrNull {
                it.name == "k3" && it.parameterTypes.size == 2 &&
                    it.parameterTypes[1] == android.os.Bundle::class.java
            } ?: run {
                AndroidLog.e(TAG, "k3(panel,Bundle) not found, panel replay disabled")
                return
            }
            k3MethodStrong = k3
            runCatching { k3.isAccessible = true }
            k3.hookAfter { param ->
                try {
                    val panel = param.args.firstOrNull() ?: return@hookAfter
                    synchronized(k3PanelStrongRefs) {
                        if (k3PanelStrongRefs.size >= 8) {
                            val oldest = k3PanelStrongRefs.keys.firstOrNull()
                            if (oldest != null) k3PanelStrongRefs.remove(oldest)
                        }
                        k3PanelStrongRefs[panel.javaClass.name] = panel
                    }
                } catch (_: Throwable) {
                }
            }
            AndroidLog.i(TAG, "hooked N#k3 for panel arg cache")
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "hook k3 panel cache failed: ${t.message}")
        }
    }

    private fun hookSearchInputRouting(classLoader: ClassLoader) {
        try {
            val svc = runCatching {
                Class.forName("com.tencent.wetype.plugin.hld.WxHldService", false, classLoader)
            }.getOrNull() ?: run {
                AndroidLog.e(TAG, "WxHldService not found, skip input routing")
                return
            }
            for (method in svc.declaredMethods) {
                if (method.name != "A") continue
                val pt = method.parameterTypes
                if (pt.size != 1 || pt[0] != java.lang.Boolean.TYPE) continue
                if (!InputConnection::class.java.isAssignableFrom(method.returnType)) continue
                try {
                    method.isAccessible = true
                    method.hookReplace { param ->
                        try {
                            val forceReal = param.args.firstOrNull() as? Boolean ?: false
                            // S5b-A5：路由保留供吃字（commitText 透传）；删由
                            // StripInputConnection wrapper 直删条框并消费。
                            // 条聚焦可见时段一律回条 IC（含 forceReal）。
                            searchInputConnection()?.let {
                                if (forceReal) {
                                    AndroidLog.i(TAG, "search input routing: " +
                                        "forceReal overridden for strip (delete path)")
                                }
                                return@hookReplace it
                            }
                        } catch (t: Throwable) {
                            AndroidLog.e(TAG, "search input routing failed: ${t.message}")
                        }
                        ProceedWithOriginal
                    }
                    AndroidLog.i(TAG, "hooked WxHldService#A for search input routing")
                } catch (t: Throwable) {
                    AndroidLog.e(TAG, "hook WxHldService#A failed: ${t.message}")
                }
            }
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "install input routing failed: ${t.message}")
        }
    }

    /** Finish 拆键盘页搜索条（防泄漏到别页）。 */
    private fun hookKeyboardTeardown(classLoader: ClassLoader) {
        try {
            val svc = runCatching {
                Class.forName("com.tencent.wetype.plugin.hld.WxHldService", false, classLoader)
            }.getOrNull() ?: return
            for (method in svc.declaredMethods) {
                if (method.name != "onFinishInputView") continue
                try {
                    method.isAccessible = true
                    method.hookAfter { teardownStrip() }
                    AndroidLog.i(TAG, "hooked onFinishInputView for strip teardown")
                } catch (t: Throwable) {
                    AndroidLog.e(TAG, "hook onFinishInputView failed: ${t.message}")
                }
            }
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "install keyboard teardown failed: ${t.message}")
        }
    }

    /**
     * 可触摸区上扩：条在键盘上方时（尤其 decor 浮层退路），VISIBLE 模式
     * 可触摸区由 visibleTopInsets 决定，必须同步下扩，否则收起点穿。
     */
    private fun hookInsetsForStrip(classLoader: ClassLoader) {
        try {
            val svc = runCatching {
                Class.forName("com.tencent.wetype.plugin.hld.WxHldService", false, classLoader)
            }.getOrNull() ?: return
            val m = svc.declaredMethods.firstOrNull {
                it.name == "onComputeInsets" && it.parameterTypes.size == 1
            } ?: return
            val imsRegion = android.inputmethodservice.InputMethodService.Insets.TOUCHABLE_INSETS_REGION
            val imsVisible = android.inputmethodservice.InputMethodService.Insets.TOUCHABLE_INSETS_VISIBLE
            m.isAccessible = true
            m.hookAfter { param ->
                try {
                    val insets = param.args[0]
                        as? android.inputmethodservice.InputMethodService.Insets
                        ?: return@hookAfter
                    if (!insetsLogged) {
                        insetsLogged = true
                        AndroidLog.i(TAG, "insets mode: touchable=${insets.touchableInsets} " +
                            "contentTop=${insets.contentTopInsets} " +
                            "visibleTop=${insets.visibleTopInsets}")
                    }
                    if (insets.touchableInsets == imsRegion) return@hookAfter
                    val parent = overlayParentRef?.get() ?: return@hookAfter
                    val card = parent.findViewWithTag<View>(TAG_SEARCH_BOX_CONTAINER)
                        ?: return@hookAfter
                    if (card.visibility != View.VISIBLE) return@hookAfter
                    val loc = IntArray(2)
                    runCatching { card.getLocationOnScreen(loc) }
                    val cardTop = loc[1]
                    if (cardTop <= 0) return@hookAfter
                    val eps = runCatching { dpToPx(card.resources, 4f) }.getOrDefault(0)
                    val target = cardTop - eps
                    var changed = false
                    if (target < insets.contentTopInsets) {
                        insets.contentTopInsets = target
                        changed = true
                    }
                    if (insets.touchableInsets == imsVisible &&
                        target < insets.visibleTopInsets
                    ) {
                        insets.visibleTopInsets = target
                        changed = true
                    }
                    if (changed) {
                        AndroidLog.i(TAG, "insets expanded for strip cardTop=$cardTop " +
                            "visibleTop=${insets.visibleTopInsets}")
                    }
                } catch (_: Throwable) {
                }
            }
            AndroidLog.i(TAG, "hooked onComputeInsets for strip touch")
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "install insets hook failed: ${t.message}")
        }
    }

    private val cachedConnections = WeakHashMap<EditText, InputConnection>()

    /** 展开且聚焦的搜索框的 InputConnection，无则 null 走原生。 */
    private fun searchInputConnection(): InputConnection? {
        try {
            synchronized(trackedBoxes) {
                val it = trackedBoxes.iterator()
                while (it.hasNext()) {
                    val box = it.next()
                    try {
                        if (box.parent == null) {
                            it.remove()
                            cachedConnections.remove(box)
                            continue
                        }
                        if (box.visibility != View.VISIBLE || !box.hasFocus()) continue
                        var p = box.parent
                        var hidden = false
                        while (p is View) {
                            if ((p as View).visibility != View.VISIBLE) { hidden = true; break }
                            p = (p as View).parent
                        }
                        if (hidden) continue
                        val ei = EditorInfo().apply {
                            inputType = InputType.TYPE_CLASS_TEXT
                            imeOptions = EditorInfo.IME_ACTION_SEARCH
                        }

                        val cached = cachedConnections[box]
                        if (cached != null) return cached

                        // S5b-A5：条框 IC 外包 wrapper 直删条框（删走 wrapper，
                        // 吃字 commitText 透传不动）。A(true) 路由保留供吃字，
                        // 删不再依赖路由旁路。
                        val raw = box.onCreateInputConnection(ei) ?: continue
                        val wrapper = StripInputConnection(
                            raw,
                            java.lang.ref.WeakReference(box)
                        )
                        cachedConnections[box] = wrapper
                        return wrapper
                    } catch (_: Throwable) {
                        continue
                    }
                }
            }
        } catch (_: Throwable) {
        }
        return null
    }

    /**
     * S5e-A5：条框 IC wrapper 删路径（实证 2026-09-09 新鲜进程：点⌫键盘调
     * `sendKeyEvent(DOWN/UP code=67)`，不走 deleteSurroundingText；super 转发到
     * 会话外现造的 raw IC 被静默吞掉，条仍 hello 且零日志）。DEL 在 wrapper 内
     * 直删条框一字并消费返 true（DOWN 删字，UP 配对吞掉）；super 实证无删字
     * 副作用，无双发。吃字 commitText 透传不动。只包条框 IC。
     */
    private class StripInputConnection(
        target: InputConnection,
        private val boxRef: java.lang.ref.WeakReference<EditText>
    ) : InputConnectionWrapper(target, true) {

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            try {
                val box = boxRef.get()
                if (box != null && isActive(box)) {
                    deleteOne(box, "deleteSurroundingText")
                    return true
                }
            } catch (t: Throwable) {
                AndroidLog.e(TAG, "strip delete wrapper failed: ${t.message}")
            }
            return super.deleteSurroundingText(beforeLength, afterLength)
        }

        override fun deleteSurroundingTextInCodePoints(
            beforeLength: Int,
            afterLength: Int
        ): Boolean {
            try {
                val box = boxRef.get()
                if (box != null && isActive(box)) {
                    deleteOne(box, "deleteSurroundingTextInCodePoints")
                    return true
                }
            } catch (t: Throwable) {
                AndroidLog.e(TAG, "strip delete wrapper failed: ${t.message}")
            }
            return super.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
        }

        // S5e-A5：DEL 走 sendKeyEvent（实证），wrapper 内消费直删一字返 true。
        // DOWN 删字；UP 配对吞掉（super 实证无副作用，吞 UP 防残留分发）。
        // 非 DEL 一律 super（吃字/导航不动）。非激活态（条已摘）走 super。
        override fun sendKeyEvent(event: android.view.KeyEvent): Boolean {
            try {
                if (event.keyCode == android.view.KeyEvent.KEYCODE_DEL) {
                    val box = boxRef.get()
                    if (box != null && isActive(box)) {
                        if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                            deleteOne(box, "sendKeyEvent")
                        }
                        return true
                    }
                }
            } catch (t: Throwable) {
                AndroidLog.e(TAG, "strip del keyevent failed: ${t.message}")
            }
            return super.sendKeyEvent(event)
        }

        /** 自包含激活态判定（与外层 searchInputConnection 同判据，不依赖外层接收者）。 */
        private fun isActive(box: EditText): Boolean {
            return try {
                if (box.parent == null) return false
                if (box.visibility != View.VISIBLE || !box.hasFocus()) return false
                var p = box.parent
                while (p is View) {
                    if ((p as View).visibility != View.VISIBLE) return false
                    p = (p as View).parent
                }
                true
            } catch (_: Throwable) {
                false
            }
        }

        /**
         * S5b-R2 单删：直删条框一字（box.post 保主线程；TextWatcher 自动透传
         * 关键词走 S5 过滤）。空文本 no-op 返 true（消费空删）。log 固定
         * `strip del 1 via surrounding`（1:1 判据：hello 点 1 删 hell）。
         */
        private fun deleteOne(box: EditText, via: String) {
            try {
                box.post {
                    try {
                        val editable = box.text
                        if (editable == null || editable.isEmpty()) {
                            AndroidLog.i(TAG, "strip del 1 via surrounding: empty, consumed via=$via")
                        } else {
                            val sel = box.selectionStart.coerceIn(0, editable.length)
                            val from = if (sel > 0) sel - 1 else editable.length - 1
                            val to = if (sel > 0) sel else editable.length
                            editable.delete(from.coerceAtLeast(0), to)
                            AndroidLog.i(TAG, "strip del 1 via surrounding ok len=${editable.length} via=$via")
                        }
                    } catch (t: Throwable) {
                        AndroidLog.e(TAG, "strip delete apply failed: ${t.message}")
                    }
                }
            } catch (t: Throwable) {
                AndroidLog.e(TAG, "strip delete dispatch failed: ${t.message}")
            }
        }
    }

    private fun recordTabIndex(host: Any, methodName: String, args: Array<Any?>) {
        try {
            when (methodName) {
                "f1" -> {
                    val index = args.firstOrNull() as? Int ?: return
                    tabIndexByHost[host] = index
                    pinTabHost(host)
                }
                "Y0" -> {
                    val bundle = args.firstOrNull() as? android.os.Bundle ?: return
                    if (bundle.containsKey("target_tab_index")) {
                        tabIndexByHost[host] = bundle.getInt("target_tab_index", TAB_CLIPBOARD)
                        pinTabHost(host)
                        // S5e-A4：缓存 Y0 实包 clone 供跳页同形回放（上限 8 轮转）。
                        try {
                            val clone = android.os.Bundle()
                            runCatching { clone.putAll(bundle) }
                            synchronized(y0BundleStrongByHost) {
                                if (y0BundleStrongByHost.size >= 8 &&
                                    !y0BundleStrongByHost.containsKey(host)
                                ) {
                                    val oldest = y0BundleStrongByHost.keys.firstOrNull()
                                    if (oldest != null) y0BundleStrongByHost.remove(oldest)
                                }
                                y0BundleStrongByHost[host] = clone
                            }
                        } catch (_: Throwable) {
                        }
                    }
                }
                // S5e-A4：缓存 i0 原生 key 实参（无法合成，回放渲染必需）。
                "i0" -> {
                    val key = args.firstOrNull() ?: return
                    pinTabHost(host)
                    try {
                        synchronized(i0ArgStrongByHost) {
                            if (i0ArgStrongByHost.size >= 8 &&
                                !i0ArgStrongByHost.containsKey(host)
                            ) {
                                val oldest = i0ArgStrongByHost.keys.firstOrNull()
                                if (oldest != null) i0ArgStrongByHost.remove(oldest)
                            }
                            i0ArgStrongByHost[host] = key
                        }
                    } catch (_: Throwable) {
                    }
                }
            }
        } catch (_: Throwable) {
        }
    }

    /**
     * S5e-A4：记录时即强引用 host + 预解析切页方法强存（防跳页时 GC 失联）。
     * 上限 8 轮转（进程级单例，不泄漏出进程）。f1 单参（旧版）与 Y0 Bundle
     * （当前版本）双通道都解析，有哪个存哪个。
     */
    private fun pinTabHost(host: Any) {
        try {
            synchronized(tabHostStrongRefs) {
                if (!tabHostStrongRefs.contains(host) && tabHostStrongRefs.size >= 8) {
                    val oldest = tabHostStrongRefs.iterator().next()
                    tabHostStrongRefs.remove(oldest)
                    synchronized(f1MethodStrongByHost) { f1MethodStrongByHost.remove(oldest) }
                    synchronized(y0MethodStrongByHost) { y0MethodStrongByHost.remove(oldest) }
                    synchronized(y0BundleStrongByHost) { y0BundleStrongByHost.remove(oldest) }
                    synchronized(i0ArgStrongByHost) { i0ArgStrongByHost.remove(oldest) }
                }
                tabHostStrongRefs.add(host)
            }
            if (!f1MethodStrongByHost.containsKey(host)) {
                val m = findF1SingleInt(host)
                if (m != null) {
                    runCatching { m.isAccessible = true }
                    putCapped(f1MethodStrongByHost, host, m)
                }
            }
            if (!y0MethodStrongByHost.containsKey(host)) {
                val m = findY0Bundle(host)
                if (m != null) {
                    runCatching { m.isAccessible = true }
                    putCapped(y0MethodStrongByHost, host, m)
                } else if (!f1MethodStrongByHost.containsKey(host)) {
                    AndroidLog.w(TAG, "pinTabHost: no jump channel (f1(int)/Y0(Bundle)) on " +
                        "${host.javaClass.name}, programmatic jump will fallback")
                }
            }
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "pinTabHost failed: ${t.message}")
        }
    }

    private fun findF1SingleInt(host: Any): java.lang.reflect.Method? {
        return try {
            host.javaClass.declaredMethods.firstOrNull {
                it.name == "f1" && it.parameterTypes.size == 1 &&
                    (it.parameterTypes[0] == Int::class.javaPrimitiveType ||
                        it.parameterTypes[0] == Integer::class.java)
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun findY0Bundle(host: Any): java.lang.reflect.Method? {
        return try {
            host.javaClass.declaredMethods.firstOrNull {
                it.name == "Y0" && it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == android.os.Bundle::class.java
            }
        } catch (_: Throwable) {
            null
        }
    }

    /** S5e-A4：当前版本 f1 三参形 (int,boolean,boolean)，实证用户点图标行调 f1(0,false,false)。 */
    private fun findF1Triple(host: Any): java.lang.reflect.Method? {
        return try {
            host.javaClass.declaredMethods.firstOrNull {
                it.name == "f1" && it.parameterTypes.size == 3 &&
                    it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                    it.parameterTypes[1] == java.lang.Boolean.TYPE &&
                    it.parameterTypes[2] == java.lang.Boolean.TYPE
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun putCapped(
        map: MutableMap<Any, java.lang.reflect.Method>,
        host: Any,
        m: java.lang.reflect.Method
    ) {
        try {
            synchronized(map) {
                if (map.size >= 8 && !map.containsKey(host)) {
                    val oldest = map.keys.firstOrNull()
                    if (oldest != null) map.remove(oldest)
                }
                map[host] = m
            }
        } catch (_: Throwable) {
        }
    }

    private fun isCustomPhraseTab(host: Any): Boolean {
        return try {
            tabIndexByHost[host]?.let { it != TAB_CLIPBOARD } ?: false
        } catch (_: Throwable) {
            false
        }
    }

    private fun hideForCustomTab(keyboardObj: Any) {
        val anchor = runCatching { findHostView(keyboardObj) }.getOrNull() ?: return
        val action = {
            try {
                val root = anchor.rootView as? ViewGroup
                if (root != null) {
                    root.findViewWithTag<View>(TAG_SEARCH_BUTTON)?.visibility = View.GONE
                    clearSearchOnMain()
                    AndroidLog.i(TAG, "custom phrase tab: search UI hidden")
                }
            } catch (t: Throwable) {
                AndroidLog.e(TAG, "hide search UI for custom tab failed: ${t.message}")
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) action() else anchor.post { action() }
    }

    private fun ensureSearchUi(keyboardObj: Any) {
        try {
            if (!WeTypeSettings.isClipboardSearchEnabledXposed()) {
                removeResidualOnMain(keyboardObj)
                return
            }
            if (isCustomPhraseTab(keyboardObj)) {
                hideForCustomTab(keyboardObj)
                return
            }
            val anchor = findHostView(keyboardObj) ?: run {
                AndroidLog.e(TAG, "S15 host view not found, skip mount")
                return
            }
            if (Looper.myLooper() == Looper.getMainLooper()) {
                mountOnMain(keyboardObj, anchor)
            } else {
                anchor.post { mountOnMain(keyboardObj, anchor) }
            }
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "ensureSearchUi failed: ${t.message}")
        }
    }

    private fun removeResidualOnMain(keyboardObj: Any) {
        val anchor = runCatching { findHostView(keyboardObj) }.getOrNull() ?: return
        val action = {
            try {
                val root = anchor.rootView as? ViewGroup
                if (root != null) {
                    root.findViewWithTag<View>(TAG_SEARCH_BOX_CONTAINER)?.let { container ->
                        // S2上撑：经统一出口拆条，先还candidate容器topMargin垫高再摘条，防漏还。
                        removeStripCard(container)
                    }
                    root.findViewWithTag<View>(TAG_SEARCH_BOX)?.let { box ->
                        (box.parent as? ViewGroup)?.removeView(box)
                    }
                    root.findViewWithTag<View>(TAG_SEARCH_BUTTON)?.let { btn ->
                        (btn.parent as? ViewGroup)?.removeView(btn)
                    }
                    clearSearchOnMain()
                }
            } catch (t: Throwable) {
                AndroidLog.e(TAG, "remove residual search UI failed: ${t.message}")
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) action() else anchor.post { action() }
    }

    /**
     * 剪贴板页：只挂红钮，不挂任何行（不挤列表）。跳回带词则直达 S5 过滤。
     */
    private fun mountOnMain(keyboardObj: Any, anchor: View) {
        try {
            if (!WeTypeSettings.isClipboardSearchEnabledXposed()) return
            val hostLoader = anchor.javaClass.classLoader ?: return
            val resources = anchor.resources ?: return

            val root = (anchor.rootView as? ViewGroup) ?: return
            val ids = resolveIds(anchor, hostLoader)
            val clipList = findClipboardListView(root, ids.clipListId)
            if (clipList == null) {
                AndroidLog.i(TAG, "no clipboard list in tree, skip mount (residuals cleared)")
                removeResidualViews(root)
                return
            }
            if (clipList.visibility != View.VISIBLE || isCustomPhraseTab(keyboardObj)) {
                hideForCustomTab(keyboardObj)
                return
            }
            val backBtnId = ids.backBtnId
            if (backBtnId == null) {
                AndroidLog.e(TAG, "resolve back_btn id failed, skip mount")
                return
            }
            val page = findClipboardPageContainer(clipList, backBtnId)
            if (page == null) {
                AndroidLog.e(TAG, "clipboard page container not found, skip mount")
                return
            }
            val backBtn = page.findViewById<View>(backBtnId)
            val bar = backBtn?.parent as? ViewGroup
            if (backBtn == null || bar == null) {
                AndroidLog.e(TAG, "back button or its bar missing, skip mount")
                return
            }

            var button = bar.findViewWithTag<View>(TAG_SEARCH_BUTTON) as? ImageView
            if (button == null) {
                val iconRes = resolveSearchIconRes(anchor, hostLoader)
                button = ImageView(bar.context).apply {
                    tag = TAG_SEARCH_BUTTON
                    contentDescription = "搜索剪贴板"
                    try {
                        setImageResource(iconRes)
                    } catch (t: Throwable) {
                        AndroidLog.e(TAG, "set search icon failed: ${t.message}")
                    }
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    try {
                        val bg = backBtn.background
                        background = bg?.constantState?.newDrawable(resources)?.mutate() ?: bg
                    } catch (t: Throwable) {
                        AndroidLog.e(TAG, "clone back button background failed: ${t.message}")
                    }
                    val padSrc = ids.backBtnIvId?.let { page.findViewById<View>(it) } ?: backBtn
                    setPadding(
                        padSrc.paddingLeft, padSrc.paddingTop,
                        padSrc.paddingRight, padSrc.paddingBottom
                    )
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { onSearchButtonClick(root) }
                }
                val backIndex = bar.indexOfChild(backBtn)
                bar.addView(button, if (backIndex >= 0) backIndex + 1 else 0)
                button?.let { alignSearchButton(it, bar, backBtn, backBtnId) }
                AndroidLog.i(TAG, "search button mounted next to back_btn")
            }
            bar.findViewWithTag<View>(TAG_SEARCH_BUTTON)?.let { existing ->
                alignSearchButton(existing, bar, backBtn, backBtnId)
            }
            root.findViewWithTag<View>(TAG_SEARCH_BUTTON)?.let { btn ->
                if (btn.visibility != View.VISIBLE) {
                    btn.visibility = View.VISIBLE
                    AndroidLog.i(TAG, "search button re-shown on clipboard tab")
                }
            }
            // 剪贴板页不挂行：清掉误留的行（只留按钮），不挤列表。
            removeStripInClipboard(page, root)
            // 跳回带词：直达 S5 过滤链路。
            if (pendingKeyword.isNotEmpty()) {
                val kw = pendingKeyword
                pendingKeyword = ""
                applyKeywordDirect(kw)
            }
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "mount search UI failed: ${t.message}")
        }
    }

    /** 剪贴板页清行：page 内/附近同 tag 行全拆（键盘页的行不在此树，误伤不到）。 */
    private fun removeStripInClipboard(page: ViewGroup, root: ViewGroup) {
        try {
            var removed = 0
            var guard = 0
            while (guard++ < 4) {
                val v = page.findViewWithTag<View>(TAG_SEARCH_BOX_CONTAINER) ?: break
                // S2上撑：经统一出口拆条（无记账则直接摘），防candidate容器垫高泄漏。
                removeStripCard(v)
                removed++
            }
            // 键盘页行若残留在 root（切页未拆），Finish 钩会拆，这里只记数不强拆。
            if (removed > 0) AndroidLog.i(TAG, "clipboard strip residuals removed: $removed")
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "remove clipboard strip failed: ${t.message}")
        }
    }

    fun applyKeywordDirect(keyword: String) {
        try {
            val run = {
                try {
                    val listener = keywordListenerImpl
                    if (listener == null) {
                        AndroidLog.e(TAG, "keyword listener missing, direct apply dropped")
                    } else {
                        listener.invoke(keyword)
                        AndroidLog.i(TAG, "direct keyword applied len=${keyword.length}")
                    }
                } catch (t: Throwable) {
                    AndroidLog.e(TAG, "direct keyword apply failed: ${t.message}")
                }
            }
            if (Looper.myLooper() == Looper.getMainLooper()) run()
            else Handler(Looper.getMainLooper()).post { run() }
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "applyKeywordDirect failed: ${t.message}")
        }
    }

    /**
     * 剪贴板红钮：只导航回键盘，不在剪贴板页展开任何东西。
     * 经 N.O2(false) 离页，键盘页顶部长行（延迟重试自校正）。
     */
    private fun onSearchButtonClick(root: ViewGroup) {
        try {
            AndroidLog.i(TAG, "search button CLICKED")
            val decor = (root.rootView as? ViewGroup) ?: root
            val cl = hostClassLoader
            val kbId = cl?.let { resolveKeyboardContainerId(it) }
            if (kbId == null) {
                AndroidLog.e(TAG, "no keyboard container id, navigation dropped")
                return
            }
            pendingKeyword = ""
            overlayPending = true
            if (!navigateBackToKeyboard()) {
                overlayPending = false
                return
            }
            AndroidLog.i(TAG, "search button: leaving S15 via N.O2, strip pending on keyboard")
            mountStripOnKeyboard(decor, kbId)
            val handler = Handler(Looper.getMainLooper())
            handler.postDelayed({ mountStripOnKeyboard(decor, kbId) }, 500)
            handler.postDelayed({ mountStripOnKeyboard(decor, kbId) }, 1200)
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "search button click failed: ${t.message}")
        }
    }

    /** 经 model.N 回主键盘（单例按形找，路由 O2(false)）。 */
    private fun navigateBackToKeyboard(): Boolean {
        try {
            val cl = hostClassLoader ?: run {
                AndroidLog.e(TAG, "host loader missing, cannot navigate back")
                return false
            }
            val nClass = Class.forName("com.tencent.wetype.plugin.hld.model.N", false, cl)
            var singleton: Any? = null
            var singletonName = "?"
            for (f in nClass.declaredFields) {
                try {
                    if (!java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                    if (f.type != nClass) continue
                    f.isAccessible = true
                    val v = f.get(null)
                    if (v != null) {
                        singleton = v
                        singletonName = f.name
                        break
                    }
                } catch (_: Throwable) {
                    continue
                }
            }
            val target = singleton ?: run {
                AndroidLog.e(TAG, "N singleton (static N field) missing, cannot navigate back")
                return false
            }
            val m = nClass.getDeclaredMethod("O2", Boolean::class.javaPrimitiveType)
            m.isAccessible = true
            m.invoke(target, false)
            AndroidLog.i(TAG, "N.O2(false) invoked on $singletonName for keyboard back")
            return true
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "navigate back to keyboard failed: $t")
            return false
        }
    }

    private fun resolveKeyboardContainerId(cl: ClassLoader): Int? {
        return try {
            val sCls = Class.forName("com.tencent.wetype.plugin.hld.s", false, cl)
            val f = sCls.getDeclaredField("keyboard_container_rl")
            f.isAccessible = true
            val id = f.getInt(null)
            AndroidLog.i(TAG, "keyboard container id resolved: $id")
            id
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "resolve keyboard container id failed: $t")
            null
        }
    }

    /**
     * 键盘页顶部长一行：keyboard_container 之前插条（纵向父 = 窗口向上
     * 长出一行，键位高度不动、不被挤）；非纵向父退 decor 浮层（同不挤键）。
     * 已存在只复用保焦点。赋值式，延迟重试自校正。
     */
    private fun mountStripOnKeyboard(decor: ViewGroup, kbContainerId: Int) {
        try {
            if (!overlayPending) {
                // 非 pending 下误调（如延迟任务撞上已跳回）：已有条则不动。
                if (decor.findViewWithTag<View>(TAG_SEARCH_BOX_CONTAINER) == null) return
            }
            val kb = decor.findViewById<View>(kbContainerId)
            if (kb == null) {
                AndroidLog.e(TAG, "keyboard container not found, strip stays pending")
                return
            }
            val existing = decor.findViewWithTag<View>(TAG_SEARCH_BOX_CONTAINER)
            if (existing != null && existing.parent != null &&
                existing.visibility == View.VISIBLE
            ) {
                // 已有条：按键盘当前高度重校底部边距（O2 切页前后键盘高度会变，
                // 首挂用的可能是剪贴板页旧高度，不校就会压住工具栏）。
                overlayParentRef = java.lang.ref.WeakReference(decor)
                adjustStripMargin(decor, kbContainerId)
                return
            }
            if (existing != null) {
                // 旧条（GONE/ detached）先经统一出口摘除：垫高还回去再摘条，
                // 否则 candidate 容器 topMargin 垫高泄漏（窗留空洞/重复垫）。
                removeStripCard(existing)
            }
            val parent = kb.parent as? ViewGroup
            val ref = parent ?: decor
            val row = buildKeyboardStrip(ref)
            if (parent != null && parent is LinearLayout &&
                parent.orientation == LinearLayout.VERTICAL
            ) {
                val idx = parent.indexOfChild(kb)
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                val mh = dpToPx(row.resources, BOX_MARGIN_H_DP)
                val mv = dpToPx(row.resources, BOX_MARGIN_V_DP)
                lp.setMargins(mh, mv, mh, mv)
                row.layoutParams = lp
                // 关键：插在键盘之前 = 向上长一行，键盘自身 LP/高度不动。
                parent.addView(row, if (idx >= 0) idx else 0)
                AndroidLog.i(TAG, "strip grown above keyboard (sibling, no squeeze)")
            } else {
                // 首选（仿翻译）：插候选条顶部容器 s0(view,0,LP)，容器长高→窗口
                // 向上撑，条底透出候选灰，与键盘同一块底，不挤键。
                if (!mountStripInCandidateContainer(decor, row)) {
                    // 退路：decor 浮层。条全幅宽、零缝贴住键盘顶；此时浮在透明
                    // 带上，必须给不透明底，否则隐形。
                    paintStripOpaqueFallback(row)
                    val kbH = if (kb.height > 0) kb.height else 0
                    val lp = android.widget.FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        android.view.Gravity.BOTTOM
                    )
                    lp.setMargins(0, 0, 0, kbH)
                    decor.addView(row, lp)
                    AndroidLog.i(TAG, "strip mounted as decor overlay above keyboard (no squeeze)")
                }
            }
            overlayParentRef = java.lang.ref.WeakReference(decor)
            overlayPending = false
            row.post {
                try {
                    adjustStripMargin(decor, kbContainerId)
                    val box = row.findViewWithTag<View>(TAG_SEARCH_BOX) as? EditText
                    val ok = box?.requestFocus() ?: false
                    AndroidLog.i(TAG, "strip mounted; box focus: requested=$ok " +
                        "hasFocus=${box?.hasFocus()} class=${box?.javaClass?.name}")
                } catch (t: Throwable) {
                    AndroidLog.e(TAG, "strip post failed: ${t.message}")
                }
            }
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "mount strip failed: $t")
            overlayPending = false
        }
    }

    /**
     * 仿翻译挂载：条插候选条顶部容器。翻译 `q` 切翻译态即
     * `ImeCandidateView.s0(k, 0, FrameLayout(MATCH,WRAP))`（`q.java:1656`），
     * 容器长高→输入法窗向上撑，卡底透出候选灰。方法名按形找（View,int,
     * FrameLayout.LP），混淆漂移也不怕。成功 true，失败 false 走 decor 退路。
     */
    private fun mountStripInCandidateContainer(decor: ViewGroup, row: View): Boolean {
        try {
            val cl = hostClassLoader ?: return false
            val candCls = runCatching {
                Class.forName(
                    "com.tencent.wetype.plugin.hld.candidate.ImeCandidateView", false, cl
                )
            }.getOrNull() ?: run {
                AndroidLog.e(TAG, "candidate view class not found")
                return false
            }
            var candView: ViewGroup? = null
            val q: ArrayDeque<View> = ArrayDeque()
            q.add(decor)
            var hops = 0
            while (q.isNotEmpty() && hops < 400) {
                val v = q.removeFirst()
                hops++
                if (candCls.isInstance(v)) {
                    candView = v as? ViewGroup
                    break
                }
                if (v is ViewGroup) {
                    for (i in 0 until minOf(v.childCount, 25)) {
                        v.getChildAt(i)?.let { q.add(it) }
                    }
                }
            }
            val cand = candView ?: run {
                AndroidLog.e(TAG, "candidate view not found in decor")
                return false
            }
            val m = cand.javaClass.declaredMethods.firstOrNull { mm ->
                val pt = mm.parameterTypes
                pt.size == 3 && View::class.java.isAssignableFrom(pt[0]) &&
                    pt[1] == Int::class.javaPrimitiveType &&
                    android.widget.FrameLayout.LayoutParams::class.java.isAssignableFrom(pt[2])
            } ?: run {
                AndroidLog.e(TAG, "candidate s0-shape not found on ${cand.javaClass.name}")
                return false
            }
            m.isAccessible = true
            val lp = android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            m.invoke(cand, row, 0, lp)
            AndroidLog.i(TAG, "strip mounted via candidate ${m.name} " +
                "on ${cand.javaClass.simpleName}")
            // 容器长高：FrameLayout 孩子叠放，条不垫高就会盖住工具栏。
            // 注意：必须按条的真实父（s0 内部的顶部容器）垫，之前按
            // ImeCandidateView 本体垫错了目标，margin 污染整窗且拆条时还
            // 不回去（键位整体下沉、工具栏消失的根因）。
            // 条置顶 + 父内其余孩子 topMargin 垫条高 → 容器长高 → 整窗向上撑。
            row.post {
                val realParent = row.parent as? ViewGroup
                if (realParent != null) growCandidateContainerForStrip(realParent, row)
            }
            return true
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "candidate container mount failed: $t")
            return false
        }
    }

    /**
     * 容器长高：其余孩子 topMargin 垫一条高（赋值式，记账防重垫）。
     * 高度未量出（0）则下一帧重试。
     * S2R：FrameLayout不随sibling topMargin自扩，同步加高realParent本体及
     * 其上FrameLayout祖先（candidate/kb/窗根，parent链向上至ImeRootView），
     * 全部记账，remove时原样减回。纵向LinearLayout sibling路径不动。
     */
    private fun growCandidateContainerForStrip(container: ViewGroup, row: View) {
        try {
            if (grownHeightByContainer.containsKey(container)) return
            val h = row.height
            if (h <= 0) {
                row.post { growCandidateContainerForStrip(container, row) }
                return
            }
            for (i in 0 until container.childCount) {
                val c = container.getChildAt(i) ?: continue
                if (c === row) continue
                val lp = c.layoutParams as? ViewGroup.MarginLayoutParams ?: continue
                lp.topMargin = lp.topMargin + h
                c.layoutParams = lp
            }
            grownHeightByContainer[container] = h
            container.requestLayout()
            AndroidLog.i(TAG, "candidate container grown by strip h=$h")
            growFrameParentsForStrip(container, h)
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "grow container failed: $t")
        }
    }

    /**
     * S5-A2(a)：realParent layout_height += h；祖先链至ImeRootView（含）止步数上限15，
     * 其中FrameLayout/ImeRootView且height>0者同步+=h；MATCH/WRAP（≤0）但实测height>0
     * 者转显式height+h（否则kb/窗MATCH_PARENT永远长不动，只长1祖先致工具栏被推出）。
     * 全部记账原值，remove时按原值还。纵向LinearLayout sibling路径不动。
     */
    private fun growFrameParentsForStrip(container: ViewGroup, h: Int) {
        try {
            val grown = mutableListOf<ViewGroup>()
            runCatching {
                val clp = container.layoutParams
                if (clp != null) {
                    if (!grownOrigHeightByView.containsKey(container)) {
                        grownOrigHeightByView[container] = clp.height
                    }
                    if (clp.height > 0) {
                        clp.height = clp.height + h
                    } else if (container.height > 0) {
                        clp.height = container.height + h
                    } else {
                        grownOrigHeightByView.remove(container)
                        return@runCatching
                    }
                    container.layoutParams = clp
                    grown.add(container)
                }
            }
            var node = container.parent as? ViewGroup
            var depth = 0
            while (node != null && depth < 15) {
                depth++
                val simple = runCatching { node.javaClass.simpleName }.getOrDefault("")
                val full = runCatching { node.javaClass.name }.getOrDefault("")
                val isRoot = simple.contains("ImeRootView") || full.contains("ImeRootView")
                val isFrame = node is android.widget.FrameLayout
                if (isFrame || isRoot) {
                    val lpH = runCatching { node.layoutParams?.height ?: 0 }.getOrDefault(0)
                    runCatching {
                        val lp = node.layoutParams ?: return@runCatching
                        if (!grownOrigHeightByView.containsKey(node)) {
                            grownOrigHeightByView[node] = lp.height
                        }
                        if (lpH > 0) {
                            lp.height = lp.height + h
                        } else if (node.height > 0) {
                            lp.height = node.height + h
                        } else {
                            grownOrigHeightByView.remove(node)
                            return@runCatching
                        }
                        node.layoutParams = lp
                        grown.add(node)
                    }
                }
                if (isRoot) break
                node = node.parent as? ViewGroup
            }
            if (grown.isNotEmpty()) {
                grownParentsByContainer[container] = grown
                for (p in grown) runCatching { p.requestLayout() }
            }
            AndroidLog.i(TAG, "candidate frame grown parents=${grown.size} h=$h")
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "grow frame parents failed: $t")
        }
    }

    /** 拆条统一出口：先把垫高还回去，再摘条（防窗留空洞/重复垫）。 */
    private fun removeStripCard(card: View) {
        try {
            val parent = card.parent as? ViewGroup
            if (parent != null) {
                val h = grownHeightByContainer.remove(parent)
                if (h != null && h > 0) {
                    for (i in 0 until parent.childCount) {
                        val c = parent.getChildAt(i) ?: continue
                        if (c === card) continue
                        val lp = c.layoutParams as? ViewGroup.MarginLayoutParams ?: continue
                        lp.topMargin = (lp.topMargin - h).coerceAtLeast(0)
                        c.layoutParams = lp
                    }
                    parent.requestLayout()
                    AndroidLog.i(TAG, "candidate container ungrown h=$h")
                    ungrowFrameParentsForStrip(parent, h)
                } else {
                    // 无topMargin记账也尝试还FrameLayout加高（防半记账残留）。
                    val fallbackH = rowHeightOf(card)
                    if (fallbackH > 0) ungrowFrameParentsForStrip(parent, fallbackH)
                }
                parent.removeView(card)
            }
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "remove strip failed: $t")
            runCatching { (card.parent as? ViewGroup)?.removeView(card) }
        }
    }

    /** S5-A2(a)：FrameLayout加高按加高前原值还（MATCH/WRAP转显式者同样还原）+requestLayout。 */
    private fun ungrowFrameParentsForStrip(container: ViewGroup, h: Int) {
        try {
            val grown = grownParentsByContainer.remove(container) ?: return
            for (p in grown) {
                runCatching {
                    val lp = p.layoutParams ?: return@runCatching
                    val orig = grownOrigHeightByView.remove(p)
                    if (orig != null) {
                        lp.height = orig
                    } else {
                        lp.height = (lp.height - h).coerceAtLeast(0)
                    }
                    p.layoutParams = lp
                    p.requestLayout()
                }
            }
            AndroidLog.i(TAG, "candidate frame ungrown parents=${grown.size} h=$h")
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "ungrow frame parents failed: $t")
        }
    }

    private fun rowHeightOf(card: View): Int {
        return try {
            if (card.height > 0) card.height else 0
        } catch (_: Throwable) {
            0
        }
    }

    /**
     * S2R：主路径不透明键盘灰取色。优先读ref及3级祖先的solid色（ColorDrawable主色，
     * 须不透明才采用）；取不到则按night mode fallback：亮色0xFFE0E0E0（224，
     * 实测键隙灰219 Δ≈5<12可过），暗色0xFF2B2B2D保留（常用暗键灰）。
     */
    private fun resolveStripOpaqueColor(ref: View): Int {
        try {
            var node: View? = ref
            var depth = 0
            while (node != null && depth < 4) {
                depth++
                val solid = runCatching {
                    (node.background as? android.graphics.drawable.ColorDrawable)?.color
                }.getOrNull()
                if (solid != null && (solid ushr 24) == 0xFF) return solid
                node = node.parent as? View
            }
        } catch (_: Throwable) {
        }
        return try {
            val night = (ref.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
            if (night) 0xFF2B2B2D.toInt() else 0xFFE0E0E0.toInt()
        } catch (_: Throwable) {
            0xFFE0E0E0.toInt()
        }
    }

    /** decor 浮退路专用：不透明键盘灰底（顶圆角底方），浮透明带上才看得见。 */    private fun paintStripOpaqueFallback(row: View) {
        try {
            val r = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 16f, row.resources.displayMetrics
            )
            row.background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                setColor(0xFFE0E0E0.toInt())
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
            AndroidLog.i(TAG, "strip painted opaque fallback")
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "paint strip fallback failed: ${t.message}")
        }
    }

    /**
     * 重校条底边距：decor 浮层退路专用。键盘布局完成后按实测高度贴住键盘顶
     *（再往上抬 2dp 留缝，避免压住工具栏）；赋值式，重复调自校正。
     */
    private fun adjustStripMargin(decor: ViewGroup, kbContainerId: Int) {
        try {
            val card = decor.findViewWithTag<View>(TAG_SEARCH_BOX_CONTAINER) ?: return
            val kb = decor.findViewById<View>(kbContainerId) ?: return
            if (kb.height <= 0 || card.parent !== decor) return
            (card.layoutParams as? android.widget.FrameLayout.LayoutParams)?.let { flp ->
                val lift = dpToPx(decor.resources, 2f)
                val target = kb.height + lift
                if (flp.bottomMargin != target) {
                    flp.bottomMargin = target
                    flp.leftMargin = 0
                    flp.rightMargin = 0
                    flp.topMargin = 0
                    card.layoutParams = flp
                    AndroidLog.i(TAG, "strip margin adjusted to keyboard h=${kb.height}")
                }
            }
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "adjust strip margin failed: ${t.message}")
        }
    }

    /**
     * 键盘页条本体：`[放大镜 | 输入框(1f) | X | 收起]`。
     * S5：红套装已拆（无描边不透明灰底、图标去染红、收起主题灰、hint去DEBUG）。
     */
    private fun buildKeyboardStrip(ref: ViewGroup): LinearLayout {
        val context = ref.context
        val res = context.resources
        // S5：主路径不透明键盘灰（A1亮0xFFE0E0E0/暗0xFF2B2B2D）。无描边，dp逻辑不动。
        val opaque = resolveStripOpaqueColor(ref)
        val row = LinearLayout(context).apply {
            tag = TAG_SEARCH_BOX_CONTAINER
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            visibility = View.VISIBLE
            try {
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    setColor(opaque)
                }
            } catch (t: Throwable) {
                AndroidLog.e(TAG, "build strip background failed: ${t.message}")
            }
            val padH = dpToPx(res, ROW_PADDING_H_DP)
            val padV = dpToPx(res, ROW_PADDING_V_DP)
            setPadding(padH, padV, padH, padV)
            isClickable = true
            isFocusable = false
        }
        val iconSize = dpToPx(res, ROW_ICON_DP)
        val icon = ImageView(context).apply {
            contentDescription = "搜索"
            try {
                setImageResource(resolveSearchIconRes(row, hostClassLoader ?: context.classLoader))
            } catch (_: Throwable) {
            }
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            isClickable = false
            isFocusable = false
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                marginEnd = dpToPx(res, 4f)
            }
        }
        row.addView(icon)

        val box = createSearchBox(context)
        box.visibility = View.VISIBLE
        row.addView(box, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        row.addView(createClearButton(context, box))

        val collapse = android.widget.TextView(context).apply {
            text = "收起"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(0xFF616161.toInt())
            val pad = dpToPx(res, ROW_PADDING_H_DP)
            setPadding(pad, 0, 0, 0)
            isClickable = true
            isFocusable = true
            setOnClickListener { collapseStrip() }
        }
        row.addView(collapse)
        return row
    }

    /** 条收起：拆条 + 清词恢复，不跳页（用户只想关行）。 */
    private fun collapseStrip() {
        try {
            pendingKeyword = ""
            overlayPending = false
            val parent = overlayParentRef?.get()
            overlayParentRef = null
            parent?.findViewWithTag<View>(TAG_SEARCH_BOX_CONTAINER)?.let { card ->
                removeStripCard(card)
            }
            clearSearch()
            AndroidLog.i(TAG, "strip collapsed")
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "strip collapse failed: ${t.message}")
        }
    }

    /**
     * S5c-A4：回车/✓带词跳回剪贴板（容器第 3 位直点）。
     * 顺序：decor 拆条前捕获 → 摘条还账（工具栏复归，第 3 位可见）→ 等一帧 →
     * 找行点第 3 位 performClick。行/目标找不到返 false（禁假 ok=true）。
     * pendingKeyword 点前已存框词，剪贴板页经 applyKeywordDirect 走 S5 过滤。
     */
    private fun jumpBackToClipboard(card: View) {
        try {
            val box = card.findViewWithTag<View>(TAG_SEARCH_BOX) as? EditText
            pendingKeyword = box?.text?.toString().orEmpty()
            overlayPending = false
            val parent = card.parent as? ViewGroup
            // S4：decor 必须在拆条前捕获。removeStripCard 后 card 已 detached，
            // card.rootView 会退化成 card 自身，后捕获就找不到图标。
            val decor = (parent?.rootView ?: overlayParentRef?.get() ?: card.rootView) as? View
                ?: return
            removeStripCard(card)
            overlayParentRef = null
            AndroidLog.i(TAG, "strip ungrown for jump, keyword len=${pendingKeyword.length}")
            // 等一帧（工具栏复归后再找图标；条盖住时工具栏暂隐是 S5 已知局限）。
            decor.post {
                try {
                    var ok = jumpBackProgrammatically(decor)
                    if (!ok) {
                        ok = jumpBackViaToolbarIcon(decor)
                    }
                    AndroidLog.i(TAG, "jumping back to clipboard ok=$ok " +
                        "keyword len=${pendingKeyword.length}")
                } catch (t: Throwable) {
                    AndroidLog.e(TAG, "jump via icon dispatch failed: ${t.message}")
                }
            }
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "jump back to clipboard failed: ${t.message}")
        }
    }

    private fun jumpBackProgrammatically(decor: View): Boolean {
        try {
            val hosts = synchronized(tabIndexByHost) { tabIndexByHost.keys.toList() }
            // S5e-A4：空分支禁静默（WeakHashMap GC 失联即 error，S5c fallback 照走）。
            if (hosts.isEmpty()) {
                val strong = synchronized(tabHostStrongRefs) { tabHostStrongRefs.size }
                AndroidLog.e(TAG, "programmatic jump: no hosts recorded " +
                    "(weakMap=0 strongRefs=$strong)")
                return false
            }
            AndroidLog.i(TAG, "programmatic jump: hosts=${hosts.size}")
            for (host in hosts) {
                if (host == null) continue
                // S5e-A4：同形回放用户点图标行的触发序列
                // f1(0,false,false)→Y0(实包clone)→a1()→i0(缓存key)→d1(false)
                //（实证用户点图标行的 S15 触发序列）。Y0 调用成功
                // 即返 true（与旧语义一致），渲染由截图+落页日志双验。
                var y0ok = false
                try {
                    val f1t = findF1Triple(host)
                    if (f1t != null) {
                        f1t.isAccessible = true
                        f1t.invoke(host, TAB_CLIPBOARD, false, false)
                        AndroidLog.i(TAG, "jump replay f1(0,false,false) on " +
                            host.javaClass.simpleName)
                    } else {
                        AndroidLog.w(TAG, "jump replay: f1(int,bool,bool) not found on " +
                            host.javaClass.name)
                    }
                } catch (t: Throwable) {
                    AndroidLog.e(TAG, "jump replay f1 failed: $t")
                }
                val y0 = synchronized(y0MethodStrongByHost) { y0MethodStrongByHost[host] }
                    ?: findY0Bundle(host)
                if (y0 != null) {
                    try {
                        y0.isAccessible = true
                        val cachedBundle = synchronized(y0BundleStrongByHost) {
                            y0BundleStrongByHost[host]
                        }
                        val bundle = if (cachedBundle != null) {
                            android.os.Bundle(cachedBundle).apply {
                                putInt("target_tab_index", TAB_CLIPBOARD)
                            }
                        } else {
                            android.os.Bundle().apply {
                                putInt("target_tab_index", TAB_CLIPBOARD)
                            }
                        }
                        y0.invoke(host, bundle)
                        AndroidLog.i(TAG, "jump via programmatic Y0 on " +
                            "${host.javaClass.simpleName} ok=true " +
                            "replayed=${cachedBundle != null}")
                        y0ok = true
                    } catch (t: Throwable) {
                        AndroidLog.e(TAG, "programmatic jump Y0 invoke failed on " +
                            "${host.javaClass.simpleName}: $t, try next channel")
                    }
                }
                try {
                    val a1 = host.javaClass.declaredMethods.firstOrNull {
                        it.name == "a1" && it.parameterTypes.isEmpty()
                    }
                    if (a1 != null) {
                        a1.isAccessible = true
                        a1.invoke(host)
                        AndroidLog.i(TAG, "jump replay a1() on ${host.javaClass.simpleName}")
                    }
                } catch (t: Throwable) {
                    AndroidLog.e(TAG, "jump replay a1 failed: $t")
                }
                // S5e-A4：i0(key,bundle) 疑为渲染调用（4 调回放不渲染）。
                // key 取最近用户导航实参同形回放；无缓存则跳过（禁合成）。
                // 顺序按实证 f1→Y0→a1→i0→d1。
                try {
                    val i0m = host.javaClass.declaredMethods.firstOrNull {
                        it.name == "i0" && it.parameterTypes.size == 2 &&
                            it.parameterTypes[1] == android.os.Bundle::class.java
                    }
                    val keyArg = synchronized(i0ArgStrongByHost) { i0ArgStrongByHost[host] }
                    if (i0m != null && keyArg != null) {
                        if (i0m.parameterTypes[0].isInstance(keyArg)) {
                            i0m.isAccessible = true
                            val bundle = android.os.Bundle().apply {
                                putInt("target_tab_index", TAB_CLIPBOARD)
                            }
                            i0m.invoke(host, keyArg, bundle)
                            AndroidLog.i(TAG, "jump replay i0(key,bundle) on " +
                                host.javaClass.simpleName)
                        } else {
                            AndroidLog.w(TAG, "jump replay i0 skipped: cached key type " +
                                "${keyArg.javaClass.name} mismatches " +
                                i0m.parameterTypes[0].name)
                        }
                    } else {
                        AndroidLog.w(TAG, "jump replay i0 skipped: " +
                            "method=${i0m != null} cachedKey=${keyArg != null}")
                    }
                } catch (t: Throwable) {
                    AndroidLog.e(TAG, "jump replay i0 failed: $t")
                }
                try {
                    val d1 = host.javaClass.declaredMethods.firstOrNull {
                        it.name == "d1" && it.parameterTypes.size == 1 &&
                            it.parameterTypes[0] == java.lang.Boolean.TYPE
                    }
                    if (d1 != null) {
                        d1.isAccessible = true
                        d1.invoke(host, false)
                        AndroidLog.i(TAG, "jump replay d1(false) on " +
                            host.javaClass.simpleName)
                    }
                } catch (t: Throwable) {
                    AndroidLog.e(TAG, "jump replay d1 failed: $t")
                }
                if (y0ok) {
                    // S5e-A4：N#k3 面板渲染回放（S15 序列只改态不渲染，实证）。
                    replayK3Panel()
                    scheduleJumpLandedCheck(decor)
                    return true
                }
                // S5e-A4：旧版 f1 单参通道保留（有则调，无则记 warn 走 S5c fallback）。
                val cached = synchronized(f1MethodStrongByHost) { f1MethodStrongByHost[host] }
                val m = cached ?: findF1SingleInt(host)
                if (m == null) {
                    AndroidLog.w(TAG, "programmatic jump: f1(int) not found on " +
                        "${host.javaClass.name}, try next host")
                    continue
                }
                if (cached == null) {
                    AndroidLog.i(TAG, "programmatic jump: using dynamic f1 on " +
                        host.javaClass.simpleName)
                }
                try {
                    m.isAccessible = true
                    m.invoke(host, TAB_CLIPBOARD)
                    AndroidLog.i(TAG, "jump via programmatic f1 on ${host.javaClass.simpleName} ok=true")
                    scheduleJumpLandedCheck(decor)
                    return true
                } catch (t: Throwable) {
                    AndroidLog.e(TAG, "programmatic jump invoke failed on " +
                        "${host.javaClass.simpleName}: $t, try next host")
                    continue
                }
            }
            AndroidLog.e(TAG, "programmatic jump: f1 not invoked on any host " +
                "(hosts=${hosts.size})")
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "programmatic jump failed: $t")
        }
        return false
    }

    /**
     * S5e-A4：N#k3 面板渲染回放。实证用户点图标行级联调
     * k3(keyboard.t@CustomPhraseAndClipboard, Bundle{target=0})；S15 序列只改
     * 内部态不渲染。面板实参取用户导航缓存，无缓存则试 keyboard.t 枚举同名
     * 常量（toString 一致），都无则跳过。异常只记日志，不影响 ok 语义。
     */
    private fun replayK3Panel() {
        try {
            val k3 = k3MethodStrong ?: return
            val target = hostClassLoader?.let { resolveNTarget(it) } ?: run {
                AndroidLog.e(TAG, "k3 replay: N singleton missing, skipped")
                return
            }
            val panel = resolveClipboardPanel() ?: run {
                AndroidLog.w(TAG, "k3 replay: no panel arg (no cache, no enum), skipped")
                return
            }
            if (!k3.parameterTypes[0].isInstance(panel)) {
                AndroidLog.w(TAG, "k3 replay: panel type mismatch " +
                    "${panel.javaClass.name} vs ${k3.parameterTypes[0].name}, skipped")
                return
            }
            k3.isAccessible = true
            val bundle = android.os.Bundle().apply {
                putInt("target_tab_index", TAB_CLIPBOARD)
            }
            k3.invoke(target, panel, bundle)
            AndroidLog.i(TAG, "jump replay k3(panel,bundle) ok=true panel=${panel}")
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "jump replay k3 failed: $t")
        }
    }

    /** N 单例（与 navigateBackToKeyboard 同找法：静态 N 类型字段首个非空值）。 */
    private fun resolveNTarget(classLoader: ClassLoader): Any? {
        return try {
            val nClass = Class.forName("com.tencent.wetype.plugin.hld.model.N", false, classLoader)
            for (f in nClass.declaredFields) {
                try {
                    if (!java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                    if (f.type != nClass) continue
                    f.isAccessible = true
                    val v = f.get(null)
                    if (v != null) return v
                } catch (_: Throwable) {
                    continue
                }
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    /** 剪贴板面板实参：用户导航缓存优先；否则 keyboard.t 枚举同名常量兜底。 */
    private fun resolveClipboardPanel(): Any? {
        try {
            synchronized(k3PanelStrongRefs) {
                for ((_, panel) in k3PanelStrongRefs) {
                    if (panel.toString() == "CustomPhraseAndClipboard") return panel
                }
                if (k3PanelStrongRefs.isNotEmpty()) return k3PanelStrongRefs.values.firstOrNull()
            }
        } catch (_: Throwable) {
        }
        try {
            val cl = hostClassLoader ?: return null
            val tClass = Class.forName(
                "com.tencent.wetype.plugin.hld.keyboard.t", false, cl
            )
            if (tClass.isEnum) {
                val constants = tClass.enumConstants ?: return null
                for (c in constants) {
                    if (c.toString() == "CustomPhraseAndClipboard") return c
                }
            }
        } catch (_: Throwable) {
        }
        return null
    }

    /** 跳页落页异步校验（Y0/f1 双通道共用；只记日志，不改 ok 语义）。 */
    private fun scheduleJumpLandedCheck(decor: View) {
        runCatching {
            if (decor is ViewGroup) {
                decor.postDelayed({
                    try {
                        val landed = checkJumpLanded(decor)
                        AndroidLog.i(TAG, "jump landed $landed")
                    } catch (t: Throwable) {
                        AndroidLog.e(TAG, "jump landed check failed: ${t.message}")
                    }
                }, 500)
            }
        }
    }

    /**
     * S5c-A4：容器序数直点（终修首轮）。decor 树 BFS 找工具栏行容器
     * （横向 LinearLayout/Row，直接图标槽位≥6，y 在键盘窗上半区带内、
     * 宽≈屏宽），取其第 3 个孩子（index 2）；孩子若是容器则深入取其内
     * 可点 Image 叶子点之。禁 contentDescription 加分依赖，禁点非第 3 位，
     * 找不到行/不足 3 孩/无目标一律返 false（禁假 ok=true）。
     * 自家搜索条 tag 排除，第 3 位是运行时序数非像素写死（bounds 现算）。
     */
    private fun jumpBackViaToolbarIcon(decorRoot: View): Boolean {
        try {
            val decor = (decorRoot.rootView ?: decorRoot) as? ViewGroup
                ?: return false.also {
                    AndroidLog.e(TAG, "jump icon not found (no decor)")
                }
            var maxKids = 0
            var row: ViewGroup? = null
            var rowBounds = ""
            var hops = 0
            val q: ArrayDeque<View> = ArrayDeque()
            q.add(decor)
            while (q.isNotEmpty() && hops < 400) {
                val v = q.removeFirst()
                hops++
                // 行候选：横向 LinearLayout/Row 形 + 图标槽位计数（禁 desc 依赖）。
                val kids = runCatching {
                    if (v is ViewGroup && v.getTag() != TAG_SEARCH_BUTTON &&
                        v.getTag() != TAG_SEARCH_BOX_CONTAINER &&
                        v.visibility == View.VISIBLE && isToolbarRowShape(v)
                    ) {
                        countRowIconSlots(v)
                    } else -1
                }.getOrDefault(-1)
                if (kids > maxKids) maxKids = kids
                if (row == null && kids >= 6 && v is ViewGroup &&
                    isToolbarRowGeometry(v, decor)
                ) {
                    row = v
                    rowBounds = viewBoundsOf(v)
                }
                runCatching {
                    if (v is ViewGroup) {
                        for (i in 0 until minOf(v.childCount, 25)) {
                            v.getChildAt(i)?.let { q.add(it) }
                        }
                    }
                }
            }
            val bar = row ?: run {
                AndroidLog.e(TAG, "jump toolbar row not found kids=$maxKids hops=$hops")
                return false
            }
            val slotCount = runCatching { bar.childCount }.getOrDefault(0)
            if (slotCount < 3) {
                AndroidLog.e(TAG, "jump toolbar row kids<3 count=$slotCount " +
                    "row=$rowBounds hops=$hops")
                return false
            }
            val slot = runCatching { bar.getChildAt(2) }.getOrNull() ?: run {
                AndroidLog.e(TAG, "jump toolbar idx2 target missing slot=null row=$rowBounds")
                return false
            }
            val target = resolveIdx2Target(slot) ?: run {
                val slotInfo = runCatching { slot.javaClass.simpleName }.getOrDefault("?")
                AndroidLog.e(TAG, "jump toolbar idx2 target missing slot=$slotInfo " +
                    "bounds=${viewBoundsOf(slot)} row=$rowBounds")
                return false
            }
            val targetBounds = viewBoundsOf(target)
            val clicked = runCatching { target.performClick() }.getOrElse { t ->
                AndroidLog.e(TAG, "jump icon performClick threw: ${t.message}")
                return false
            }
            AndroidLog.i(TAG, "jump via toolbar idx2 click ok=$clicked bounds=$targetBounds " +
                "row=$rowBounds")
            if (!clicked) return false
            // click 后落页校验（异步）：剪贴板/常用语 tabs+列表才算到页，
            // 手写页（字迹/手写）算失败。禁假 ok=true 掩盖。
            runCatching {
                decor.postDelayed({
                    try {
                        val landed = checkJumpLanded(decor)
                        AndroidLog.i(TAG, "jump landed $landed")
                    } catch (t: Throwable) {
                        AndroidLog.e(TAG, "jump landed check failed: ${t.message}")
                    }
                }, 500)
            }
            return true
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "jump via toolbar icon failed: ${t.message}")
            return false
        }
    }

    /**
     * S5c 行形：横向 LinearLayout，或类名含 Row 的横向行容器。
     * 纵向 LinearLayout 一律排除（键列/列表非工具栏行）。
     */
    private fun isToolbarRowShape(row: ViewGroup): Boolean {
        return try {
            if (row is LinearLayout) {
                row.orientation == LinearLayout.HORIZONTAL
            } else {
                val simple = runCatching { row.javaClass.simpleName }.getOrDefault("")
                simple.contains("Row")
            }
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "toolbar row shape failed: ${t.message}")
            false
        }
    }

    /**
     * S5c 槽位计数：行直接孩子中图标槽位数（禁 desc 依赖）。
     * 直孩 Image 叶子计 1；直孩容器内有可见 Image 叶子计 1
     * （R2 根因：每图标包独立容器，叶子模型直数失效）。
     * 自家搜索条 tag 与 GONE 直孩跳过不计。
     */
    private fun countRowIconSlots(row: ViewGroup): Int {
        return try {
            var n = 0
            for (i in 0 until row.childCount) {
                val kid = runCatching { row.getChildAt(i) }.getOrNull() ?: continue
                if (kid.getTag() == TAG_SEARCH_BUTTON ||
                    kid.getTag() == TAG_SEARCH_BOX_CONTAINER
                ) {
                    continue
                }
                if (kid.visibility != View.VISIBLE) continue
                if (kid is ViewGroup) {
                    if (rowSlotHasImage(kid)) n++
                } else if (isToolbarImageLeaf(kid)) {
                    n++
                }
            }
            n
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "count row icon slots failed: ${t.message}")
            0
        }
    }

    /** S5c 槽内探针：容器内（3 层/每层 25 孩上限）是否有可见 Image 叶子。 */
    private fun rowSlotHasImage(slot: ViewGroup): Boolean {
        return try {
            val q: ArrayDeque<Pair<View, Int>> = ArrayDeque()
            q.add(slot to 0)
            var hops = 0
            while (q.isNotEmpty() && hops < 100) {
                val (v, depth) = q.removeFirst()
                hops++
                if (v.getTag() == TAG_SEARCH_BUTTON ||
                    v.getTag() == TAG_SEARCH_BOX_CONTAINER
                ) {
                    continue
                }
                if (v.visibility != View.VISIBLE) continue
                if (v !is ViewGroup) {
                    if (isToolbarImageLeaf(v)) return true
                } else if (depth < 3) {
                    for (i in 0 until minOf(v.childCount, 25)) {
                        v.getChildAt(i)?.let { q.add(it to depth + 1) }
                    }
                }
            }
            false
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "row slot probe failed: ${t.message}")
            false
        }
    }

    /**
     * S5c 几何门：宽≈屏宽（运行时屏宽分数，无写死 px）+ y 在键盘窗
     * 上半区带内（decor 下半窗偏上，排除底部按键行与顶部应用区）。
     * 未量出（宽/顶为 0）不卡，靠槽位计数定行。
     */
    private fun isToolbarRowGeometry(row: ViewGroup, decor: ViewGroup): Boolean {
        return try {
            val dm = row.resources.displayMetrics
            val screenW = dm.widthPixels
            val screenH = dm.heightPixels
            val rowW = row.width
            if (rowW > 0 && screenW > 0 &&
                rowW < (screenW * 0.8f).roundToInt()
            ) {
                return false
            }
            val loc = IntArray(2)
            runCatching { row.getLocationOnScreen(loc) }
            val rowTop = loc[1]
            if (rowTop <= 0) return true
            val dloc = IntArray(2)
            runCatching { decor.getLocationOnScreen(dloc) }
            val decorH = if (decor.height > 0) decor.height else screenH
            val top = dloc[1]
            rowTop > top + (decorH * 0.3f).roundToInt() &&
                rowTop < top + (decorH * 0.8f).roundToInt()
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "toolbar row geometry failed: ${t.message}")
            true
        }
    }

    /**
     * S5c 第 3 位解析：直孩 Image 叶子直点；容器深入取首个可点 Image 叶子
     * （无可点则取首个可见 Image 叶子）；非 Image 直孩返 null（禁点非第 3 位，
     * 禁回退他位，找不到就 false）。
     */
    private fun resolveIdx2Target(slot: View): View? {
        return try {
            if (slot.getTag() == TAG_SEARCH_BUTTON ||
                slot.getTag() == TAG_SEARCH_BOX_CONTAINER
            ) {
                return null
            }
            if (slot.visibility != View.VISIBLE) return null
            if (slot !is ViewGroup) {
                return if (isToolbarImageLeaf(slot)) slot else null
            }
            var fallback: View? = null
            val q: ArrayDeque<View> = ArrayDeque()
            q.add(slot)
            var hops = 0
            while (q.isNotEmpty() && hops < 100) {
                val v = q.removeFirst()
                hops++
                if (v !== slot) {
                    if (v.getTag() == TAG_SEARCH_BUTTON ||
                        v.getTag() == TAG_SEARCH_BOX_CONTAINER
                    ) {
                        continue
                    }
                    if (v.visibility != View.VISIBLE) continue
                    if (v !is ViewGroup && isToolbarImageLeaf(v)) {
                        if (v.isClickable) return v
                        if (fallback == null) fallback = v
                        continue
                    }
                }
                if (v is ViewGroup) {
                    for (i in 0 until minOf(v.childCount, 25)) {
                        v.getChildAt(i)?.let { q.add(it) }
                    }
                }
            }
            fallback
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "resolve idx2 target failed: ${t.message}")
            null
        }
    }

    /** Image 系叶子判定：ImageView（含 ImageButton 子类）或类名含 Image 系。 */
    private fun isToolbarImageLeaf(v: View): Boolean {
        return try {
            if (v is ImageView) return true
            val simple = runCatching { v.javaClass.simpleName }.getOrDefault("")
            val full = runCatching { v.javaClass.name }.getOrDefault("")
            simple.contains("ImageView") || simple.contains("ImageButton") ||
                full.contains("ImageView") || full.contains("ImageButton")
        } catch (_: Throwable) {
            false
        }
    }

    private fun viewBoundsOf(v: View): String {
        return try {
            val loc = IntArray(2)
            runCatching { v.getLocationOnScreen(loc) }
            if (loc[0] != 0 || loc[1] != 0) {
                "${loc[0]},${loc[1]}-${v.width}x${v.height}"
            } else {
                "l=${v.left},t=${v.top},w=${v.width},h=${v.height}"
            }
        } catch (_: Throwable) {
            "?"
        }
    }

    /**
     * 落页校验：decor 树找剪贴板/常用语 tabs+列表 vs 手写页（字迹/手写）。
     * 返回 `clipboard ok=true` / `handwriting FAIL` / `unknown`。
     */
    private fun checkJumpLanded(decor: ViewGroup): String {
        return try {
            var hasClipTab = false
            var hasCommonTab = false
            var hasHandwrite = false
            var hops = 0
            val q: ArrayDeque<View> = ArrayDeque()
            q.add(decor)
            while (q.isNotEmpty() && hops < 400) {
                val v = q.removeFirst()
                hops++
                try {
                    val text = runCatching {
                        (v as? android.widget.TextView)?.text?.toString() ?: ""
                    }.getOrDefault("")
                    if (text.contains("剪贴板")) hasClipTab = true
                    if (text.contains("常用语")) hasCommonTab = true
                    if (text.contains("手写") || text.contains("字迹")) hasHandwrite = true
                    val desc = runCatching {
                        v.contentDescription?.toString() ?: ""
                    }.getOrDefault("")
                    if (desc.contains("手写") || desc.contains("字迹")) hasHandwrite = true
                } catch (_: Throwable) {
                }
                try {
                    if (v is ViewGroup) {
                        for (i in 0 until minOf(v.childCount, 25)) {
                            v.getChildAt(i)?.let { q.add(it) }
                        }
                    }
                } catch (_: Throwable) {
                }
                if (hasClipTab && hasCommonTab) break
            }
            if (hasHandwrite && !(hasClipTab && hasCommonTab)) return "handwriting FAIL"
            if (hasClipTab && hasCommonTab) return "clipboard ok=true"
            "unknown"
        } catch (_: Throwable) {
            "unknown"
        }
    }

    private fun teardownStrip() {
        try {
            overlayPending = false
            val parent = overlayParentRef?.get()
            overlayParentRef = null
            if (parent == null) return
            parent.findViewWithTag<View>(TAG_SEARCH_BOX_CONTAINER)?.let { card ->
                removeStripCard(card)
                AndroidLog.i(TAG, "strip torn down on input finish")
            }
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "teardown strip failed: ${t.message}")
        }
    }

    private fun clearSearchOnMain() {
        try {
            synchronized(trackedBoxes) {
                val it = trackedBoxes.iterator()
                while (it.hasNext()) {
                    val box = it.next()
                    try {
                        if (box.parent == null) {
                            it.remove()
                            continue
                        }
                        if (box.text?.isNotEmpty() == true) box.setText("")
                        updateClearVisibility(box)
                    } catch (t: Throwable) {
                        AndroidLog.e(TAG, "clear box failed: ${t.message}")
                    }
                }
            }
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "clearSearch failed: ${t.message}")
        }
    }

    private fun updateClearVisibility(box: EditText) {
        try {
            val parent = box.parent as? ViewGroup ?: return
            val clear = parent.findViewWithTag<View>(TAG_SEARCH_CLEAR) ?: return
            var v: Boolean? = null
            var node: View? = parent
            while (node != null) {
                if (node.getTag() == TAG_SEARCH_BOX_CONTAINER) { v = node.visibility == View.VISIBLE; break }
                node = node.parent as? View
            }
            val show = (v ?: (box.visibility == View.VISIBLE)) && box.text?.isNotEmpty() == true
            clear.visibility = if (show) View.VISIBLE else View.GONE
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "update clear visibility failed: ${t.message}")
        }
    }

    private fun createSearchBox(context: android.content.Context): EditText {
        val box = createHostBox(context) ?: EditText(context)
        AndroidLog.i(TAG, "search box widget: ${box.javaClass.name}")
        box.apply {
            tag = TAG_SEARCH_BOX
            hint = "搜索剪贴板"
            setSingleLine(true)
            maxLines = 1
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            inputType = InputType.TYPE_CLASS_TEXT
            visibility = View.VISIBLE
            background = null
            setHintTextColor(0xFF9E9E9E.toInt())
            setTextColor(0xFF212121.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, BOX_TEXT_SP)
            isFocusable = true
            isFocusableInTouchMode = true
        }
        synchronized(trackedBoxes) { trackedBoxes.add(box) }
        box.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                try {
                    val raw = s?.toString().orEmpty()
                    // 换行键兜底：该键盘回车送的是 commitText("\n") 而非 editor
                    // action（日志里 editor action 从未触发）。见换行就去掉并
                    // 当回车跳回剪贴板；框保持单行干净，退格逐字删除恢复正常。
                    if (raw.contains('\n') || raw.contains('\r')) {
                        val clean = raw.replace("\n", "").replace("\r", "")
                        box.post {
                            try {
                                box.setText(clean)
                                box.setSelection(clean.length.coerceAtMost(box.text?.length ?: 0))
                                var node: View? = box
                                while (node != null && node.getTag() != TAG_SEARCH_BOX_CONTAINER) {
                                    node = node.parent as? View
                                }
                                if (node != null) {
                                    AndroidLog.i(TAG, "strip newline-as-enter: jumping back")
                                    jumpBackToClipboard(node)
                                }
                            } catch (t: Throwable) {
                                AndroidLog.e(TAG, "newline jump failed: ${t.message}")
                            }
                        }
                        pendingKeyword = clean
                        keywordListenerImpl?.invoke(clean)
                        updateClearVisibility(box)
                        return
                    }
                    pendingKeyword = raw
                    keywordListenerImpl?.invoke(pendingKeyword)
                } catch (t: Throwable) {
                    AndroidLog.e(TAG, "keyword listener failed: ${t.message}")
                }
                updateClearVisibility(box)
            }
        })
        // S5e-A5：点⌫触摸经 StripInputConnection.sendKeyEvent(DEL) 直删 1 字
        // （实证不走 deleteSurroundingText；wrapper 消费禁双发）。
        box.setOnEditorActionListener { _, _, _ ->
            // 键盘页回车 = ✓跳回剪贴板看结果。
            try {
                AndroidLog.i(TAG, "strip editor action: jumping back")
                var node: View? = box
                while (node != null && node.getTag() != TAG_SEARCH_BOX_CONTAINER) {
                    node = node.parent as? View
                }
                if (node != null) jumpBackToClipboard(node)
            } catch (t: Throwable) {
                AndroidLog.e(TAG, "strip search action failed: ${t.message}")
            }
            true
        }
        return box
    }

    private fun createHostBox(context: android.content.Context): EditText? {
        return try {
            val cl = hostClassLoader ?: return null
            val cls = Class.forName(
                "com.tencent.wetype.plugin.hld.view.imeedittext.ImeEditText", false, cl
            )
            val ctor = cls.getDeclaredConstructor(android.content.Context::class.java)
            ctor.isAccessible = true
            ctor.newInstance(context) as? EditText
        } catch (t: Throwable) {
            AndroidLog.i(TAG, "host ImeEditText unavailable, fallback EditText: ${t.message}")
            null
        }
    }

    private fun createClearButton(context: android.content.Context, box: EditText): ImageView {
        return ImageView(context).apply {
            tag = TAG_SEARCH_CLEAR
            contentDescription = "清除搜索"
            try {
                setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            } catch (t: Throwable) {
                AndroidLog.e(TAG, "set clear icon failed: ${t.message}")
            }
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val res = resources
            val pad = dpToPx(res, CLEAR_ICON_PADDING_DP)
            setPadding(pad, pad, pad, pad)
            isClickable = true
            isFocusable = true
            visibility = View.GONE
            setOnClickListener {
                try {
                    box.setText("")
                    clearSearch()
                } catch (t: Throwable) {
                    AndroidLog.e(TAG, "clear button failed: ${t.message}")
                }
            }
            val size = dpToPx(res, CLEAR_BOX_DP)
            layoutParams = LinearLayout.LayoutParams(size, size)
        }
    }

    private fun alignSearchButton(btn: View, bar: ViewGroup, backBtn: View, backBtnId: Int) {
        try {
            bar.post {
                try {
                    val bw = backBtn.width
                    val bh = backBtn.height
                    if (bw <= 0 || bh <= 0) {
                        AndroidLog.e(TAG, "back button not laid out ($bw x $bh), align deferred")
                        return@post
                    }
                    val lp = btn.layoutParams as? ViewGroup.MarginLayoutParams ?: run {
                        AndroidLog.e(TAG, "button LP not MarginLP, cannot size")
                        return@post
                    }
                    lp.width = bw
                    lp.height = bh
                    val gap = dpToPx(bar.resources, BTN_GAP_DP)
                    runCatching {
                        val lpClass = lp.javaClass
                        lpClass.getField("startToEnd").setInt(lp, backBtnId)
                        lpClass.getField("topToTop").setInt(lp, backBtnId)
                        lpClass.getField("bottomToBottom").setInt(lp, backBtnId)
                    }
                    lp.setMargins(backBtn.left + bw + gap, backBtn.top, 0, 0)
                    btn.layoutParams = lp
                    btn.post {
                        try {
                            val targetLeft = backBtn.left + bw + gap
                            val targetTop = backBtn.top
                            btn.translationX = (targetLeft - btn.left).toFloat()
                            btn.translationY = (targetTop - btn.top).toFloat()
                            val loc = IntArray(2)
                            runCatching { btn.getLocationOnScreen(loc) }
                            AndroidLog.i(TAG, "search button placed screen=${loc[0]},${loc[1]} " +
                                "tx=${btn.translationX} ty=${btn.translationY}")
                        } catch (t: Throwable) {
                            AndroidLog.e(TAG, "translate button failed: ${t.message}")
                        }
                    }
                } catch (t: Throwable) {
                    AndroidLog.e(TAG, "align search button failed: ${t.message}")
                }
            }
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "align search button failed: ${t.message}")
        }
    }

    private fun findHostView(keyboardObj: Any): View? {
        var clazz: Class<*>? = keyboardObj.javaClass
        while (clazz != null && clazz != Any::class.java) {
            for (field in clazz.declaredFields) {
                try {
                    if (!View::class.java.isAssignableFrom(field.type)) continue
                    field.isAccessible = true
                    val view = field.get(keyboardObj) as? View
                    if (view != null) return view
                } catch (_: Throwable) {
                    continue
                }
            }
            clazz = clazz.superclass
        }
        var search: Class<*>? = keyboardObj.javaClass
        while (search != null && search != Any::class.java) {
            for (method in search.declaredMethods) {
                try {
                    if (method.parameterTypes.isNotEmpty()) continue
                    if (!View::class.java.isAssignableFrom(method.returnType)) continue
                    method.isAccessible = true
                    val view = method.invoke(keyboardObj) as? View
                    if (view != null) return view
                } catch (_: Throwable) {
                    continue
                }
            }
            search = search.superclass
        }
        return null
    }

    private data class ResolvedIds(
        val backBtnId: Int?,
        val backBtnIvId: Int?,
        val clipListId: Int?
    )

    private fun findClipboardListView(root: ViewGroup, clipListId: Int?): View? {
        if (clipListId == null) return null
        return try {
            root.findViewById(clipListId)
        } catch (_: Throwable) {
            null
        }
    }

    private fun findClipboardPageContainer(listView: View, backBtnId: Int): ViewGroup? {
        var node = listView.parent
        var depth = 0
        while (node is ViewGroup && depth < 10) {
            if (node.findViewById<View>(backBtnId) != null) return node
            node = node.parent
            depth++
        }
        return null
    }

    private fun idClassLoaders(anchor: View, hostLoader: ClassLoader): List<ClassLoader?> {
        val ctx = anchor.context
        return listOf(hostLoader, ctx?.classLoader, ctx?.applicationContext?.classLoader)
    }

    private fun findIdClass(anchor: View, hostLoader: ClassLoader): Class<*>? {
        for (cl in idClassLoaders(anchor, hostLoader)) {
            if (cl == null) continue
            runCatching { Class.forName(WETYPE_ID_CLASS, false, cl) }.getOrNull()?.let { return it }
        }
        return null
    }

    private fun removeResidualViews(root: ViewGroup) {
        try {
            root.findViewWithTag<View>(TAG_SEARCH_BOX_CONTAINER)?.let { container ->
                // S2上撑：经统一出口拆条，先还candidate容器topMargin垫高再摘条，防漏还。
                removeStripCard(container)
            }
            root.findViewWithTag<View>(TAG_SEARCH_BOX)?.let { box ->
                (box.parent as? ViewGroup)?.removeView(box)
            }
            root.findViewWithTag<View>(TAG_SEARCH_BUTTON)?.let { btn ->
                (btn.parent as? ViewGroup)?.removeView(btn)
            }
            clearSearchOnMain()
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "remove residual search UI failed: ${t.message}")
        }
    }

    private fun resolveIds(anchor: View, hostLoader: ClassLoader): ResolvedIds {
        val sClass = findIdClass(anchor, hostLoader)
        if (sClass == null) {
            AndroidLog.e(TAG, "resolve clipboard page IDs failed: $WETYPE_ID_CLASS")
            return ResolvedIds(null, null, null)
        }
        return ResolvedIds(
            backBtnId = runCatching { sClass.getField("back_btn").getInt(null) }.getOrNull(),
            backBtnIvId = runCatching { sClass.getField("back_btn_iv").getInt(null) }.getOrNull(),
            clipListId = runCatching { sClass.getField("t15_clipboard_list").getInt(null) }.getOrNull()
        ).also {
            if (it.backBtnId == null && it.clipListId == null) {
                AndroidLog.e(TAG, "resolve clipboard page IDs failed: fields missing in $WETYPE_ID_CLASS")
            }
        }
    }

    private fun resolveSearchIconRes(anchor: View, hostLoader: ClassLoader): Int {
        for (cl in idClassLoaders(anchor, hostLoader)) {
            if (cl == null) continue
            try {
                val rClass = Class.forName(WETYPE_DRAWABLE_CLASS, false, cl)
                val fields = rClass.declaredFields
                var fallback: Int? = null
                for (field in fields) {
                    val name = field.name.lowercase()
                    if (!name.contains("search")) continue
                    val resId = runCatching { field.getInt(null) }.getOrNull() ?: continue
                    if (fallback == null) fallback = resId
                    if (name.contains("icon") || name.contains("magnif") || name.contains("loupe")) {
                        AndroidLog.i(TAG, "search icon=$name")
                        return resId
                    }
                }
                if (fallback != null) {
                    AndroidLog.i(TAG, "search icon=first search match (fallback pick)")
                    return fallback
                }
            } catch (t: Throwable) {
                AndroidLog.e(TAG, "resolve search icon failed: ${t.message}")
            }
        }
        AndroidLog.i(TAG, "search icon=android.R.drawable.ic_menu_search (system fallback)")
        return android.R.drawable.ic_menu_search
    }

    private fun dpToPx(resources: android.content.res.Resources, dp: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            resources.displayMetrics
        ).roundToInt()
    }
}
