package com.xposed.wetypehook.wetype.hook

import android.content.Context
import android.text.TextUtils
import android.util.AttributeSet
import android.util.Log as AndroidLog
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.xposed.wetypehook.wetype.clipboard.ClipboardWaterfallLayoutLogic
import com.xposed.wetypehook.wetype.host.HostContractId
import com.xposed.wetypehook.wetype.host.HostResources
import com.xposed.wetypehook.wetype.host.WeTypeHostContracts
import com.xposed.wetypehook.wetype.settings.WeTypeSettings
import com.xposed.wetypehook.xposed.hookAfter
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap

/**
 * 剪贴板双列瀑布流（Waterfall Flow）。
 *
 * 原生剪贴板列表是「单列 + 单行横向滚动」：
 * - `ImeClipboardScrollView.s()`（继承自 `view.M`）给列表 RecyclerView 装
 *   `ImePagingLinearLayoutManager`（单列垂直）；
 * - ViewHolder `clipboard.z#h()` 每次绑定都把 `clipboard_item_line1` 的宽高写死成
 *   「面板宽 - 2*margin」×「单行高」，文本控件 `clipboard_content_tv` 被包在
 *   `HorizontalScrollView` 里做单行横向滚动。
 *
 * 本对象在开关打开时把上面两处改成「双列交错 + 高度随内容自适应」：
 * - 列表换 `StaggeredGridLayoutManager(2, VERTICAL)`，列间距全部由条目左右外边距给，
 *   列表水平内边距归零（垂直内边距沿用原生，那是视口的一部分）；
 * - 条目宽度解绑为 MATCH_PARENT（自适应列宽），行高解绑为 WRAP_CONTENT；
 * - 文本改成多行折行，并按「卡片最大高度」折算成最大行数，超出的部分在末行显示省略号；
 * - 卡片里两处按整屏宽度配平的部件（更多按钮宽度、卡片上下内边距）按列宽重新配平；
 * - 点 ⋮ 展开的操作栏（常用语 / 选词 / 删除）原生合计约 726px，比半列宽 456px 还宽，
 *   滚到头会把最左的按钮挤出视口，改成只剩图标的紧凑按钮，并给 ⋮ 留出位置以便再点收起；
 * - 图片条目由 [WeTypeClipboardImageEntries] 侧读取「卡片最大高度」后等比缩放封顶。
 *
 * 开关关闭时把布局管理器、原生高度与卡片配平值一并还原（首次见到时留底）。
 */
internal object WeTypeClipboardWaterfallHooks {

    private const val TAG = "WeTypeClipboardWaterfall"
    private const val SCROLLVIEW_CLASS = "com.tencent.wetype.plugin.hld.clipboard.ImeClipboardScrollView"
    private const val IDS_CLASS = "com.tencent.wetype.plugin.hld.s"
    private const val RV_CLASS_NAME = "androidx.recyclerview.widget.RecyclerView"
    private const val LAYOUT_MANAGER_CLASS_NAME =
        "androidx.recyclerview.widget.RecyclerView\$LayoutManager"
    private const val SGLM_CLASS_NAME = "androidx.recyclerview.widget.StaggeredGridLayoutManager"

    private const val SPAN_COUNT = ClipboardWaterfallLayoutLogic.SPAN_COUNT

    /**
     * 列间距（dp）。整个间距都由条目左右外边距给（列表左右内边距为 0），所以
     * 中缝 = 2 × 半间距，而屏幕外侧留白 = 列表相对屏幕的偏移 + 半间距 ——
     * 实机列表是 1008px 居中于 1080px 屏（左右各 36px 偏移），取 16dp 时
     * 外侧 60px（20dp）、中缝 48px（16dp），接近常规双列栅格。
     *
     * 取 8dp 时中缝只有 24px、外侧却有 60px，中缝不到外侧一半，两列看起来像被硬切开的
     * 整块面板；再往大取会让中缝反超外侧，同时把卡片压窄、正文版心跟着变窄。
     */
    private const val COLUMN_GAP_DP = 16
    private const val ROW_GAP_DP = 8

    /**
     * dp → px。宿主自带那套「设计像素」换算（`rawToPx` / `q1.e0`）在 1080 宽屏上是恒等映射，
     * 拿它当 dp 用会把 140dp 算成 140px（≈47dp）。间距用条目自己的 Resources，
     * 没有 View 时（设置读取路径）退回系统 density —— 输入法就在默认显示屏上，两者一致。
     */
    private fun dpToPx(view: View, dp: Int): Int =
        (dp * (view.resources?.displayMetrics?.density ?: 1f)).toInt().coerceAtLeast(1)

    private fun dpToPx(dp: Int): Int {
        val density = android.content.res.Resources.getSystem().displayMetrics.density
        return (dp * density).toInt().coerceAtLeast(1)
    }

    /** 每个 RV 的原生布局留底，用于开关关闭时还原。 */
    private class NativeState(
        val layoutManager: Any?,
        val height: Int,
        val paddingLeft: Int,
        val paddingTop: Int,
        val paddingRight: Int,
        val paddingBottom: Int
    )

    private val nativeByRv: MutableMap<Any, NativeState> =
        Collections.synchronizedMap(WeakHashMap())

    private val waterfallByRv: MutableSet<Any> =
        Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))

    /** 最近一次启用瀑布流的列表句柄：绑定期 itemView 还没挂上父链，只能靠它拿列宽。 */
    @Volatile
    private var lastRv: java.lang.ref.WeakReference<View>? = null

    private val idsResolved = java.util.concurrent.atomic.AtomicBoolean(false)

    @Volatile
    private var containerId: Int = 0

    /** 展开操作栏里的三颗按钮与其中的文字；取不到就是 0，对应那一颗保持原生。 */
    private class ActionIds(
        val deleteId: Int,
        val addId: Int,
        val addTextId: Int,
        val bombId: Int,
        val bombTextId: Int
    )

    @Volatile
    private var actionIds: ActionIds? = null

    @Volatile
    private var rvGetter: Method? = null

    @Volatile
    private var installed = false

    // ---- 对外：最大高度（文本卡片按行数封顶，图片卡片由图片条目侧复用这个值） ----

    /** 卡片最大高度（px）；开关关闭时返回 0 表示不封顶。 */
    fun maxContentHeightPx(): Int {
        if (!isWaterfallEnabled()) return 0
        return dpToPx(WeTypeSettings.getClipboardWaterfallMaxHeightDpXposed())
    }

    fun isWaterfallEnabled(): Boolean = WeTypeSettings.isClipboardWaterfallFlowEnabledXposed()

    /**
     * 条目还没测量出宽度时的卡片宽兜底：优先用启用瀑布流时缓存的列表句柄
     * （绑定期 `itemView` 还没挂到 RecyclerView 上，父链那条路走不通），
     * 再退化为沿父链找列表。口径与卡片一致 —— 列宽扣掉条目自身左右外边距。
     * 取不到或开关关闭返回 0。
     */
    fun columnWidthFallbackPx(view: View): Int {
        if (!isWaterfallEnabled()) return 0
        val halfGap = ClipboardWaterfallLayoutLogic.columnHalfGapPx(dpToPx(view, COLUMN_GAP_DP))
        fun cardWidth(list: View): Int {
            val span = ClipboardWaterfallLayoutLogic.columnWidthPx(
                list.width, list.paddingLeft, list.paddingRight, SPAN_COUNT
            )
            return (span - 2 * halfGap).coerceAtLeast(0)
        }
        lastRv?.get()?.let { rv -> cardWidth(rv).takeIf { it > 0 }?.let { return it } }
        var parent: View? = view.parent as? View
        while (parent != null) {
            if (parent.javaClass.name == RV_CLASS_NAME) return cardWidth(parent)
            parent = parent.parent as? View
        }
        return 0
    }

    // ---- 安装 ----

    /**
     * 宿主对自己的 `androidx.recyclerview` 做过 R8 裁剪：`StaggeredGridLayoutManager`
     * 在 4.0.0 上只剩 View 风格的四参构造函数，`(int,int)` 已被裁掉。这里把保留下来的
     * 构造函数打一行，宿主升级后先看这行再决定要不要换路子。
     */
    private fun probeRecyclerViewLibrary(classLoader: ClassLoader) {
        listOf(
            SGLM_CLASS_NAME,
            "androidx.recyclerview.widget.GridLayoutManager"
        ).forEach { name ->
            runCatching {
                val cls = Class.forName(name, false, classLoader)
                val ctors = cls.declaredConstructors.joinToString(" | ") { ctor ->
                    ctor.parameterTypes.joinToString(",", "(", ")") { it.simpleName }
                }
                AndroidLog.i(TAG, "rvlib $name ctors=[$ctors]")
            }.onFailure { AndroidLog.w(TAG, "rvlib $name missing: ${it.message}") }
        }
    }

    fun install(classLoader: ClassLoader) {
        if (installed) return
        resolveIds(classLoader)
        probeRecyclerViewLibrary(classLoader)
        runCatching { hookScrollViewInit(classLoader) }
            .onFailure { AndroidLog.e(TAG, "hook scroll view init failed: ${it.message}") }
        runCatching { hookSetList(classLoader) }
            .onFailure { AndroidLog.e(TAG, "hook setList failed: ${it.message}") }
        runCatching { hookSizeChanged(classLoader) }
            .onFailure { AndroidLog.e(TAG, "hook onSizeChanged failed: ${it.message}") }
        runCatching { hookBind(classLoader) }
            .onFailure { AndroidLog.e(TAG, "hook onBindViewHolder failed: ${it.message}") }
        installed = true
        AndroidLog.i(TAG, "waterfall hooks installed (container=$containerId line1=${WeTypeClipboardImageHost.line1Id})")
    }

    private fun resolveIds(classLoader: ClassLoader) {
        if (!idsResolved.compareAndSet(false, true)) return
        runCatching {
            val ids = HostResources.rClass(IDS_CLASS, classLoader)
                ?: Class.forName(IDS_CLASS, false, classLoader)
            containerId = ids.getField("clipboard_item_container").getInt(null)
            fun optionalId(name: String): Int =
                runCatching { ids.getField(name).getInt(null) }.getOrDefault(0)
            actionIds = ActionIds(
                deleteId = optionalId("clipboard_action_delete"),
                addId = optionalId("clipboard_action_add_container"),
                addTextId = optionalId("clipboard_action_add_text"),
                bombId = optionalId("clipboard_action_bomb_container"),
                bombTextId = optionalId("clipboard_action_bomb_text")
            )
        }.onFailure { AndroidLog.e(TAG, "resolve waterfall ids failed: ${it.message}") }
    }

    private fun resolveScrollClass(classLoader: ClassLoader?): Class<*>? =
        runCatching { WeTypeHostContracts.classOf(HostContractId.CLIPBOARD_SCROLLVIEW) }.getOrNull()
            ?: classLoader?.let { runCatching { Class.forName(SCROLLVIEW_CLASS, false, it) }.getOrNull() }

    private fun hookScrollViewInit(classLoader: ClassLoader) {
        val scrollClass = resolveScrollClass(classLoader) ?: run {
            AndroidLog.e(TAG, "clipboard scroll view class missing")
            return
        }
        rvGetter = scrollClass.methods.firstOrNull {
            it.parameterTypes.isEmpty() && it.returnType.name == RV_CLASS_NAME
        }
        val sMethod = scrollClass.declaredMethods.firstOrNull {
            it.name == "s" && it.parameterTypes.isEmpty() && it.returnType == Void.TYPE
        } ?: run {
            AndroidLog.e(TAG, "clipboard scroll view init method (s) missing")
            return
        }
        sMethod.isAccessible = true
        sMethod.hookAfter { param ->
            runCatching { applyLayoutMode(param.thisObject) }
                .onFailure { AndroidLog.e(TAG, "apply layout on init failed: ${it.message}") }
        }
        AndroidLog.i(TAG, "hooked clipboard scroll view init (rvGetter=${rvGetter != null})")
    }

    private fun hookSetList(classLoader: ClassLoader) {
        val scrollClass = resolveScrollClass(classLoader) ?: return
        var count = 0
        for (method in scrollClass.declaredMethods) {
            if (method.name != "setList") continue
            if (method.parameterTypes.size != 1) continue
            if (!List::class.java.isAssignableFrom(method.parameterTypes[0])) continue
            method.isAccessible = true
            method.hookAfter { param ->
                runCatching { applyLayoutMode(param.thisObject) }
                    .onFailure { AndroidLog.e(TAG, "apply layout on setList failed: ${it.message}") }
            }
            count++
        }
        AndroidLog.i(TAG, "hooked setList for waterfall: $count overload(s)")
    }

    /**
     * 宿主 `onSizeChanged -> y()` 会把 RV 的内边距重新写回原生值，这里补一次。
     * `onSizeChanged` 是 `View` 的 protected 覆写，不在 `getMethods()` 里，必须沿继承链找 declared。
     */
    private fun hookSizeChanged(classLoader: ClassLoader) {
        val scrollClass = resolveScrollClass(classLoader) ?: return
        var current: Class<*>? = scrollClass
        var sizeMethod: Method? = null
        while (current != null && current != Any::class.java) {
            sizeMethod = current.declaredMethods.firstOrNull {
                it.name == "onSizeChanged" && it.parameterTypes.size == 4
            }
            if (sizeMethod != null) break
            current = current.superclass
        }
        val target = sizeMethod ?: run {
            AndroidLog.e(TAG, "onSizeChanged not found on clipboard scroll view")
            return
        }
        target.isAccessible = true
        target.hookAfter { param ->
            runCatching { applyLayoutMode(param.thisObject) }
                .onFailure { AndroidLog.e(TAG, "apply layout on size change failed: ${it.message}") }
        }
        AndroidLog.i(TAG, "hooked onSizeChanged for waterfall (${target.declaringClass.simpleName})")
    }

    private fun hookBind(classLoader: ClassLoader) {
        val adapterClass = runCatching {
            WeTypeHostContracts.classOf(HostContractId.CLIPBOARD_ADAPTER)
        }.getOrNull() ?: runCatching {
            Class.forName("com.tencent.wetype.plugin.hld.clipboard.v", false, classLoader)
        }.getOrNull() ?: run {
            AndroidLog.e(TAG, "clipboard adapter class missing")
            return
        }
        var count = 0
        for (method in adapterClass.declaredMethods) {
            if (method.name != "onBindViewHolder") continue
            if (method.parameterTypes.size != 2) continue
            method.isAccessible = true
            method.hookAfter { param ->
                runCatching {
                    val holder = param.args.getOrNull(0) ?: return@runCatching
                    onBind(holder)
                }.onFailure { AndroidLog.e(TAG, "waterfall item adjust failed: ${it.message}") }
            }
            count++
        }
        AndroidLog.i(TAG, "hooked onBindViewHolder for waterfall: $count overload(s)")
    }

    // ---- 列表层 ----

    private fun scrollContainerRv(scrollView: Any): Any? {
        val getter = rvGetter ?: return null
        return runCatching { getter.invoke(scrollView) }.getOrNull()
    }

    private fun applyLayoutMode(scrollView: Any?) {
        if (scrollView == null) return
        val rv = scrollContainerRv(scrollView) as? View ?: return
        val enabled = WeTypeSettings.isClipboardWaterfallFlowEnabledXposed()
        if (enabled) {
            enableWaterfall(rv)
        } else {
            disableWaterfall(rv)
        }
    }

    private fun enableWaterfall(rv: View) {
        val state = nativeByRv.getOrPut(rv) {
            NativeState(
                layoutManager = currentLayoutManager(rv),
                height = rv.layoutParams?.height ?: ViewGroup.LayoutParams.WRAP_CONTENT,
                paddingLeft = rv.paddingLeft,
                paddingTop = rv.paddingTop,
                paddingRight = rv.paddingRight,
                paddingBottom = rv.paddingBottom
            )
        }
        if (waterfallByRv.contains(rv)) return
        val lm = createStaggeredGridManager(rv) ?: return
        runCatching { setLayoutManager(rv, lm) }
            .onFailure {
                AndroidLog.e(TAG, "set staggered layout manager failed: ${it.message}")
                return
            }
        waterfallByRv.add(rv)
        lastRv = java.lang.ref.WeakReference(rv)
        // 列间距全部由条目左右外边距给：列表内边距留 0，中缝正好等于「半间距 × 2」，
        // 与屏幕外侧留白（列表相对屏幕的偏移 + 半间距）目视齐平。
        // 垂直方向一律沿用原生内边距：原生用 `height = 可见列表高 * 2` + `paddingBottom = 可见高 + 36`
        // 把真正的视口压成约一屏（实机 rv 1402 高 / paddingTop 16 / paddingBottom 737 → 视口 649），
        // 动高度或垂直内边距都会把这个视口算坏。
        rv.setPadding(0, state.paddingTop, 0, state.paddingBottom)
        AndroidLog.i(
            TAG,
            "waterfall enabled: requested=${lm.javaClass.simpleName} " +
                "actual=${currentLayoutManager(rv)?.javaClass?.name} nativeH=${state.height} " +
                "pad=${rv.paddingLeft},${rv.paddingTop},${rv.paddingRight},${rv.paddingBottom} " +
                "rvW=${rv.width} rvH=${rv.height}"
        )
    }

    private fun disableWaterfall(rv: View) {
        if (!waterfallByRv.remove(rv)) return
        val state = nativeByRv[rv]
        // 卡片链被写成了显式 px，关开关后交回 wrap，宿主下次绑定会写回原生宽度。
        val group = rv as? ViewGroup
        if (group != null) {
            for (i in 0 until group.childCount) resetItemWidths(group.getChildAt(i))
        }
        rv.setPadding(
            state?.paddingLeft ?: 0,
            state?.paddingTop ?: rv.paddingTop,
            state?.paddingRight ?: 0,
            state?.paddingBottom ?: rv.paddingBottom
        )
        state?.layoutManager?.let {
            runCatching { setLayoutManager(rv, it) }
                .onFailure { error -> AndroidLog.e(TAG, "restore layout manager failed: ${error.message}") }
        }
        if (state != null && state.height > 0) setViewHeight(rv, state.height)
        AndroidLog.i(TAG, "waterfall disabled: restored native layout")
    }

    private fun resetItemWidths(itemView: View) {
        listOf(containerId, WeTypeClipboardImageHost.line1Id, WeTypeClipboardImageHost.keyInfoId)
            .forEach { id -> findItemView(itemView, id)?.let(::wrapWidth) }
        findItemView(itemView, WeTypeClipboardImageHost.contentScrollId)?.let(::matchConstraintWidth)
        (findItemView(itemView, WeTypeClipboardImageHost.contentTvId) as? TextView)?.let { tv ->
            if (tv.maxWidth != Int.MAX_VALUE) tv.maxWidth = Int.MAX_VALUE
            if (tv.maxHeight != Int.MAX_VALUE) tv.maxHeight = Int.MAX_VALUE
        }
        restoreCardChrome(itemView)
        restoreActionMenu(itemView)
    }

    private fun wrapWidth(view: View) {
        val lp = view.layoutParams ?: return
        if (lp.width == ViewGroup.LayoutParams.WRAP_CONTENT) return
        lp.width = ViewGroup.LayoutParams.WRAP_CONTENT
        view.layoutParams = lp
    }

    /**
     * 宿主 R8 会把 `RecyclerView` 的公开方法一起改名：4.0.0 上按名字找 `setLayoutManager`
     * 拿到的是 null，于是整个换布局管理器静默失效（外观上仍是单列，日志却写着成功）。
     * 一律按参数 / 返回类型找，别按名字。
     */
    private fun layoutManagerSetter(rv: View): Method? =
        rv.javaClass.methods.firstOrNull {
            it.parameterTypes.size == 1 && it.parameterTypes[0].name == LAYOUT_MANAGER_CLASS_NAME
        }

    private fun currentLayoutManager(rv: View): Any? =
        rv.javaClass.methods.firstOrNull {
            it.parameterTypes.isEmpty() && it.returnType.name == LAYOUT_MANAGER_CLASS_NAME
        }?.let { runCatching { it.invoke(rv) }.getOrNull() }

    private fun setLayoutManager(rv: View, lm: Any) {
        val method = layoutManagerSetter(rv)
            ?: error("RecyclerView.setLayoutManager not found by shape on ${rv.javaClass.name}")
        method.invoke(rv, lm)
    }

    /**
     * 造一个宿主可用的 `StaggeredGridLayoutManager`。
     *
     * 只能用 View 风格的四参构造函数：宿主 R8 把 `(int,int)` 裁掉了（4.0.0 实测
     * `NoSuchMethodException: <init> [int, int]`）。attrs 传 null 时它落下来的
     * **spanCount 是 1 而不是 SDK 里的 2**（实机实测：列表子项宽度 = 整个内容宽，成了单列），
     * 而 `setSpanCount` 同样被改名，没法按名字点。所以造完再走一次自证配对把列数拨正。
     */
    private fun createStaggeredGridManager(rv: View): Any? {
        val hostLoader = rv.javaClass.classLoader ?: return null
        val outcome = runCatching {
            val cls = Class.forName(SGLM_CLASS_NAME, true, hostLoader)
            val ctor = cls.declaredConstructors.firstOrNull { candidate ->
                candidate.parameterTypes.size == 4 &&
                    candidate.parameterTypes[0] == Context::class.java &&
                    candidate.parameterTypes[1] == AttributeSet::class.java &&
                    candidate.parameterTypes[2] == Int::class.javaPrimitiveType &&
                    candidate.parameterTypes[3] == Int::class.javaPrimitiveType
            } ?: error("no (Context, AttributeSet, int, int) constructor")
            ctor.isAccessible = true
            val lm = ctor.newInstance(rv.context, null, 0, 0)
            applySpanCount(lm, SPAN_COUNT)
            lm
        }
        outcome.getOrNull()?.let { return it }
        AndroidLog.e(TAG, "staggered grid manager unavailable: ${outcome.exceptionOrNull()}")
        return null
    }

    /**
     * 把 spanCount 拨到 [spanCount]。`setSpanCount` 被 R8 改了名，只能自证配对：
     * 逐个试 `(int)void` 的 setter，看哪一个能让某个「原本恒为 1」的无参 int getter
     * 变成目标值 —— 只有真正管 span 的那一对会成立。`setOrientation` / `setGapStrategy`
     * 收到非法值会抛 `IllegalArgumentException`，天然被排除。
     */
    private fun applySpanCount(lm: Any, spanCount: Int): Boolean {
        val cls = lm.javaClass
        val getters = cls.methods.filter {
            it.parameterTypes.isEmpty() && it.returnType == Int::class.javaPrimitiveType
        }
        if (getters.isEmpty()) return false
        val setters = cls.methods.filter {
            it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                it.returnType == Void.TYPE
        }
        for (setter in setters) {
            val before = getters.associateWith { g -> runCatching { g.invoke(lm) as Int }.getOrNull() }
            val invoked = runCatching { setter.invoke(lm, spanCount); true }.getOrDefault(false)
            if (!invoked) continue
            val after = getters.associateWith { g -> runCatching { g.invoke(lm) as Int }.getOrNull() }
            val flipped = before.filter { (g, v) -> v == 1 && after[g] == spanCount }
            if (flipped.isNotEmpty()) {
                AndroidLog.i(
                    TAG,
                    "spanCount setter resolved: ${cls.simpleName}#${setter.name} -> $spanCount"
                )
                return true
            }
        }
        AndroidLog.e(TAG, "spanCount setter not found; grid will fall back to a single column")
        return false
    }

    // ---- 条目层 ----

    private fun onBind(holder: Any) {
        if (!WeTypeSettings.isClipboardWaterfallFlowEnabledXposed()) return
        val itemView = holderItemView(holder) ?: return
        if (findItemView(itemView, containerId) == null) {
            // 分割条 / 页脚：占满整行。
            markFullSpan(itemView)
            return
        }
        applyItemLayout(itemView)
    }

    /**
     * 条目级布局。
     *
     * 宿主 ViewHolder `clipboard.z#h()` 每次绑定都把 `clipboard_item_line1` /
     * `clipboard_key_information_rv` 的宽度写成「面板宽 - 2*margin」—— 那是原生单列的口径。
     * 瀑布流里条目只有半屏宽，照抄这个宽度会让卡片顶出条目的可见区，右侧连同两个圆角一起
     * 被条目左对齐裁掉。
     *
     * 而条目内部的整条链子挂在 `ImeCustomPhraseItemScrollView`（HorizontalScrollView）之下，
     * 宽度测量是 UNSPECIFIED —— 那里 `MATCH_PARENT` / `WRAP_CONTENT` 都不跟列宽绑定，所以卡片链
     * 只能用**条目实测宽度**写死 px。条目实测宽度要等布局完成才有，因此真正的校准挂在
     * [itemLayoutListener] 上，这里只做绑定期的预备工作。
     */
    private fun applyItemLayout(itemView: View) {
        val halfGap = ClipboardWaterfallLayoutLogic.columnHalfGapPx(dpToPx(itemView, COLUMN_GAP_DP))
        val rowHalfGap = ClipboardWaterfallLayoutLogic.rowHalfGapPx(dpToPx(itemView, ROW_GAP_DP))
        applyItemMargins(itemView, halfGap, rowHalfGap)

        // 条目宽度交给 StaggeredGridLayoutManager：它按列宽把子项测量成「列宽 - 条目外边距」。
        matchWidth(itemView)
        setWrapHeight(itemView)
        applyCardChrome(itemView)
        prepareContentText(itemView)
        attachCalibration(itemView)
        if (itemView.width > 0) calibrateItem(itemView, itemView.width)
    }

    /**
     * 更多按钮（⋮）在瀑布流里保留的宽度（dp）。
     *
     * 原生按钮宽 120px（40dp），里面几乎全是空白 —— 字形只有约 10px 宽、居中摆放。
     * 在 1008px 的单列里它只吃掉 12% 的卡片宽；换到 456px 的双列里就吃掉 26%，
     * 正文每行只剩 6 个全角字、右侧白扔约 148px。收窄到 28dp（84px）后正文可用宽由
     * 296px 提到 332px，每行多约 1.5 个全角字（74 字的长文本从 13 行降到 10 行），
     * 同时点击区仍有 28×25dp —— 再窄就只剩字形本身，按起来会开始发飘。
     * 按钮高度是 `line1` 的内容高（原生 `height=MATCH_PARENT`，被 [CARD_VERTICAL_PADDING_DP]
     * 挤掉上下各 8dp），所以点击区按「宽 × 行高 - 2×上下留白」算。
     */
    private const val MORE_BUTTON_WIDTH_DP = 28

    /** 卡片上下内边距（dp）。原生靠固定的单行行高留白，自适应行高后要补回来。 */
    private const val CARD_VERTICAL_PADDING_DP = 8

    /**
     * 展开操作栏的尺寸（dp）。三颗按钮边长由列宽反推（详见 [applyActionMenu]），
     * 这里只是夹取范围与间隙：上限 40dp 是原生图标按钮宽，下限 28dp 再小就点不中了；
     * 卡片尾部除了 ⋮ 再留 10dp，让收起用的 ⋮ 不是紧贴着操作栏的边。
     * 456px（152dp）的半列里算出来是 32dp 边长、6dp 间隙，视口里卡片尾部剩 38dp。
     */
    private const val ACTION_BUTTON_MIN_DP = 28
    private const val ACTION_BUTTON_MAX_DP = 40
    private const val ACTION_GAP_DP = 6
    private const val ACTION_TAIL_PADDING_DP = 10

    private val nativeMoreWidth: MutableMap<View, Int> =
        Collections.synchronizedMap(WeakHashMap())

    private val nativeLinePadding: MutableMap<View, IntArray> =
        Collections.synchronizedMap(WeakHashMap())

    /**
     * 卡片里两个「按整屏宽度调过」的部件，列宽减半后必须重新配平（详见两个常量的注释）：
     * 收窄更多按钮、给卡片补上下内边距。两个值在首次改写前留底，开关关闭时还原。
     */
    private fun applyCardChrome(itemView: View) {
        findItemView(itemView, WeTypeClipboardImageHost.moreBtnId)?.let { more ->
            nativeMoreWidth.getOrPut(more) { more.layoutParams?.width ?: 0 }
            forceWidth(more, dpToPx(itemView, MORE_BUTTON_WIDTH_DP))
        }
        findItemView(itemView, WeTypeClipboardImageHost.line1Id)?.let { line1 ->
            nativeLinePadding.getOrPut(line1) { intArrayOf(line1.paddingTop, line1.paddingBottom) }
            val pad = dpToPx(itemView, CARD_VERTICAL_PADDING_DP)
            if (line1.paddingTop != pad || line1.paddingBottom != pad) {
                line1.setPadding(line1.paddingLeft, pad, line1.paddingRight, pad)
            }
        }
    }

    private fun restoreCardChrome(itemView: View) {
        findItemView(itemView, WeTypeClipboardImageHost.moreBtnId)?.let { more ->
            nativeMoreWidth.remove(more)?.takeIf { it > 0 }?.let { forceWidth(more, it) }
        }
        findItemView(itemView, WeTypeClipboardImageHost.line1Id)?.let { line1 ->
            nativeLinePadding.remove(line1)?.let { original ->
                if (line1.paddingTop != original[0] || line1.paddingBottom != original[1]) {
                    line1.setPadding(line1.paddingLeft, original[0], line1.paddingRight, original[1])
                }
            }
        }
    }

    /**
     * 文本改成多行折行（原生是单行横向滚动），高度交给 [estimateTextCap] 封顶。
     *
     * **不能**用 `maxHeight` 封：它只裁剪、`ellipsize` 对它不生效，超出的行会被静默吞掉，
     * 界面上既没有省略号也看不到后几行，等于把用户复制的内容删了。
     */
    private fun prepareContentText(itemView: View) {
        val tv = contentTv(itemView) ?: return
        tv.isSingleLine = false
        tv.ellipsize = TextUtils.TruncateAt.END
        tv.setHorizontallyScrolling(false)
        if (tv.maxHeight != Int.MAX_VALUE) tv.maxHeight = Int.MAX_VALUE
        estimateTextCap(itemView)
    }

    /**
     * 绑定时先按 `TextView.lineHeight` 估一个文本最大行数，让超出的部分在末行显示省略号。
     *
     * 估算要扣掉卡片上不属于文本行的固定占位 —— `line1` 的上下内边距、文本自身内边距，
     * 以及可见时的药丸标签行。`lineHeight` 只反映主字体的行距，含中文 / emoji 的行会回落到更高的字体，
     * 实机 62px 对 72px，所以这个估算会略高，由 [correctTextOverflow] 在布局后按实测纠偏。
     * `setSingleLine(false)` 会把 `maxLines` 重置成无限，所以每次绑定后都要重写一遍。
     */
    private fun estimateTextCap(itemView: View) {
        val tv = contentTv(itemView) ?: return
        val lines = ClipboardWaterfallLayoutLogic.textMaxLines(
            maxCardPx = maxContentHeightPx(),
            chromePx = textChromePx(itemView, tv),
            lineHeightPx = tv.lineHeight
        )
        if (tv.maxLines != lines) tv.maxLines = lines
    }

    /**
     * 布局完成后量一次：卡片实际高度仍超过上限，就按实测行距少排几行（只减不增）。
     * 每次绑定会重新估算，所以这里不会把行数永久压低。
     */
    private fun correctTextOverflow(itemView: View) {
        val cap = maxContentHeightPx()
        if (cap <= 0) return
        val tv = contentTv(itemView) ?: return
        val card = findItemView(itemView, containerId) ?: return
        val layout = tv.layout ?: return
        val shown = tv.lineCount
        if (shown <= 1 || card.height <= cap) return
        val pitch = (layout.getLineBottom(shown - 1) - layout.getLineTop(0)) / shown
        val corrected = ClipboardWaterfallLayoutLogic.correctedMaxLines(
            currentMaxLines = tv.maxLines,
            displayedLines = shown,
            overflowPx = card.height - cap,
            linePitchPx = pitch
        )
        if (tv.maxLines != corrected) tv.maxLines = corrected
    }

    private fun textChromePx(itemView: View, tv: TextView): Int {
        var chrome = tv.paddingTop + tv.paddingBottom
        findItemView(itemView, WeTypeClipboardImageHost.line1Id)?.let { line1 ->
            chrome += line1.paddingTop + line1.paddingBottom
        }
        findItemView(itemView, WeTypeClipboardImageHost.keyInfoId)
            ?.takeIf { it.visibility == View.VISIBLE }
            ?.let { keyInfo ->
                chrome += keyInfo.layoutParams?.height?.takeIf { it > 0 } ?: keyInfo.height
            }
        return chrome
    }

    /**
     * 点 ⋮ 展开的操作栏（常用语 / 选词 / 删除）。
     *
     * 宿主展开时只做一件事：把条目横向滚动「操作栏宽度」那么远（`smoothScrollTo(width, 0)`，
     * 触摸被 `OnTouchListener` 整个吞掉，不能手动拖）。原生三颗按钮合计约 726px，半列视口
     * 只有 456px，滚到头会把最左的「常用语」整个挤出视口，⋮ 也跟着滚没了，用户没法再点它收起。
     *
     * 所以在瀑布流里把三颗按钮改成只剩图标的方形按钮，边长按 [ClipboardWaterfallLayoutLogic.actionButtonPx]
     * 从列宽反推：视口里给卡片尾部留出 ⋮ 加一点留白，剩下的平分。文字挪进 `contentDescription`。
     * 「确认删除」只有一颗、264px 放得下，保持原样。
     * 宿主的 `h()` 只在 ViewHolder 构造时跑一次，改过的 LayoutParams 不会被它改回去。
     */
    private class NativeActionButton(
        val width: Int,
        val height: Int,
        val marginStart: Int,
        val paddingLeft: Int,
        val paddingTop: Int,
        val paddingRight: Int,
        val paddingBottom: Int,
        val gravity: Int,
        val contentDescription: CharSequence?,
        val textVisibility: Int
    )

    private val nativeActionButtons: MutableMap<View, NativeActionButton> =
        Collections.synchronizedMap(WeakHashMap())

    private fun applyActionMenu(itemView: View, columnWidth: Int) {
        val ids = actionIds ?: return
        val gap = dpToPx(itemView, ACTION_GAP_DP)
        val button = ClipboardWaterfallLayoutLogic.actionButtonPx(
            columnWidthPx = columnWidth,
            moreButtonPx = dpToPx(itemView, MORE_BUTTON_WIDTH_DP),
            tailPaddingPx = dpToPx(itemView, ACTION_TAIL_PADDING_DP),
            gapPx = gap,
            minButtonPx = dpToPx(itemView, ACTION_BUTTON_MIN_DP),
            maxButtonPx = dpToPx(itemView, ACTION_BUTTON_MAX_DP)
        )
        if (button <= 0) return
        compactActionButton(itemView, ids.deleteId, 0, button, gap)
        compactActionButton(itemView, ids.addId, ids.addTextId, button, gap)
        compactActionButton(itemView, ids.bombId, ids.bombTextId, button, gap)
    }

    private fun compactActionButton(itemView: View, id: Int, textId: Int, size: Int, gap: Int) {
        val view = findItemView(itemView, id) ?: return
        val text = findItemView(view, textId) as? TextView
        nativeActionButtons.getOrPut(view) {
            val lp = view.layoutParams as? ViewGroup.MarginLayoutParams
            NativeActionButton(
                width = lp?.width ?: ViewGroup.LayoutParams.WRAP_CONTENT,
                height = lp?.height ?: ViewGroup.LayoutParams.WRAP_CONTENT,
                marginStart = lp?.marginStart ?: 0,
                paddingLeft = view.paddingLeft,
                paddingTop = view.paddingTop,
                paddingRight = view.paddingRight,
                paddingBottom = view.paddingBottom,
                gravity = (view as? LinearLayout)?.gravity ?: Gravity.NO_GRAVITY,
                contentDescription = view.contentDescription,
                textVisibility = text?.visibility ?: View.VISIBLE
            )
        }
        text?.let {
            if (it.visibility != View.GONE) it.visibility = View.GONE
            if (view.contentDescription != it.text) view.contentDescription = it.text
        }
        if (view.paddingLeft != 0 || view.paddingTop != 0 || view.paddingRight != 0 || view.paddingBottom != 0) {
            view.setPadding(0, 0, 0, 0)
        }
        (view as? LinearLayout)?.let { if (it.gravity != Gravity.CENTER) it.gravity = Gravity.CENTER }
        val lp = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        if (lp.width == size && lp.height == size && lp.marginStart == gap) return
        lp.width = size
        lp.height = size
        lp.marginStart = gap
        view.layoutParams = lp
    }

    private fun restoreActionMenu(itemView: View) {
        val ids = actionIds ?: return
        listOf(
            ids.deleteId to 0,
            ids.addId to ids.addTextId,
            ids.bombId to ids.bombTextId
        ).forEach { (id, textId) ->
            val view = findItemView(itemView, id) ?: return@forEach
            val native = nativeActionButtons.remove(view) ?: return@forEach
            (findItemView(view, textId) as? TextView)?.visibility = native.textVisibility
            view.contentDescription = native.contentDescription
            view.setPadding(native.paddingLeft, native.paddingTop, native.paddingRight, native.paddingBottom)
            (view as? LinearLayout)?.gravity = native.gravity
            (view.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                lp.width = native.width
                lp.height = native.height
                lp.marginStart = native.marginStart
                view.layoutParams = lp
            }
        }
    }

    /**
     * 按条目实测宽度校准卡片链。
     *
     * 卡片 / `line1` / 键信息列表的宽度测量是 UNSPECIFIED，只能用显式 px 跟住列宽；
     * 文本滚动容器反过来是 ConstraintLayout 里 `0dp`(MATCH_CONSTRAINT) 的约束子项，给它写死
     * 宽度会让 ConstraintLayout 在「左缩进 → 更多按钮」这段区间里居中（实测 `left=-39`，
     * 每行开头被切掉），所以它必须交回约束自己去算。
     */
    private fun calibrateItem(itemView: View, width: Int) {
        if (width <= 0 || !isWaterfallEnabled()) return
        findItemView(itemView, containerId)?.let { card ->
            forceWidth(card, width)
            setWrapHeight(card)
        }
        findItemView(itemView, WeTypeClipboardImageHost.line1Id)?.let { line1 ->
            forceWidth(line1, width)
            setWrapHeight(line1)
        }
        findItemView(itemView, WeTypeClipboardImageHost.keyInfoId)?.let { forceWidth(it, width) }
        correctTextOverflow(itemView)
        applyActionMenu(itemView, width)
        val scroll = findItemView(itemView, WeTypeClipboardImageHost.contentScrollId) ?: return
        matchConstraintWidth(scroll)
        val tv = contentTv(itemView) ?: return
        val viewport = scroll.width - tv.paddingLeft - tv.paddingRight
        if (viewport > 0 && tv.maxWidth != viewport) tv.maxWidth = viewport
    }

    /** holder 会复用同一条 itemView，listener 与校准都按 View 去重。 */
    private val calibratedViews: MutableSet<View> =
        Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<View, Boolean>()))

    private val itemLayoutListener = View.OnLayoutChangeListener { view, _, _, right, _, _, _, _, _ ->
        val width = right - view.left
        if (width <= 0) return@OnLayoutChangeListener
        runCatching { calibrateItem(view, width) }
            .onFailure { AndroidLog.e(TAG, "waterfall calibrate item failed: ${it.message}") }
    }

    private fun attachCalibration(itemView: View) {
        if (!calibratedViews.add(itemView)) return
        itemView.addOnLayoutChangeListener(itemLayoutListener)
    }

    private fun findItemView(itemView: View, id: Int): View? =
        WeTypeClipboardImageHost.findView(itemView, id)

    private fun contentTv(itemView: View): TextView? =
        findItemView(itemView, WeTypeClipboardImageHost.contentTvId) as? TextView

    private fun forceWidth(view: View, width: Int) {
        if (width <= 0) return
        val lp = view.layoutParams ?: return
        if (lp.width == width) return
        lp.width = width
        view.layoutParams = lp
    }

    /** 把 `0dp`(MATCH_CONSTRAINT) 写回，让 ConstraintLayout 用约束算宽度与位置。 */
    private fun matchConstraintWidth(view: View) {
        val lp = view.layoutParams ?: return
        if (lp.width == 0) return
        lp.width = 0
        view.layoutParams = lp
    }

    private fun applyItemMargins(itemView: View, horizontal: Int, vertical: Int) {
        val lp = itemView.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        if (lp.leftMargin == horizontal && lp.rightMargin == horizontal &&
            lp.topMargin == vertical && lp.bottomMargin == vertical
        ) {
            return
        }
        lp.setMargins(horizontal, vertical, horizontal, vertical)
        itemView.layoutParams = lp
    }

    private fun markFullSpan(itemView: View) {
        val lp = itemView.layoutParams ?: return
        // 同样不能按名字找：SGLM$LayoutParams 的公开方法也会被 R8 改名。
        val setter = lp.javaClass.methods.firstOrNull {
            it.parameterTypes.size == 1 && it.parameterTypes[0] == Boolean::class.javaPrimitiveType
        } ?: return
        runCatching { setter.invoke(lp, true) }
            .onFailure { AndroidLog.e(TAG, "setFullSpan failed: ${it.message}") }
    }

    private fun matchWidth(view: View) {
        val lp = view.layoutParams ?: return
        if (lp.width == ViewGroup.LayoutParams.MATCH_PARENT) return
        lp.width = ViewGroup.LayoutParams.MATCH_PARENT
        view.layoutParams = lp
    }

    private fun setWrapHeight(view: View) {
        val lp = view.layoutParams ?: return
        if (lp.height == ViewGroup.LayoutParams.WRAP_CONTENT) return
        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
        view.layoutParams = lp
    }

    private fun setViewHeight(view: View, height: Int) {
        val lp = view.layoutParams ?: return
        if (lp.height == height) return
        lp.height = height
        view.layoutParams = lp
    }

    private fun holderItemView(holder: Any): View? {
        var clazz: Class<*>? = holder.javaClass
        while (clazz != null && clazz != Any::class.java) {
            for (field in clazz.declaredFields) {
                if (!View::class.java.isAssignableFrom(field.type)) continue
                field.isAccessible = true
                (field.get(holder) as? View)?.let { return it }
            }
            clazz = clazz.superclass
        }
        return null
    }

}
