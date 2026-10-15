package com.xposed.wetypehook.wetype.hook

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
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
import com.xposed.wetypehook.wetype.host.isItemAnimatorSetter
import com.xposed.wetypehook.wetype.host.viewHolderItemViewField
import com.xposed.wetypehook.wetype.settings.WeTypeSettings
import com.xposed.wetypehook.xposed.hookAfter
import com.xposed.wetypehook.xposed.Log as ModuleLog
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap

/**
 * 剪贴板双列瀑布流（Waterfall Flow）。
 *
 * 原生剪贴板列表是「单列 + 单行横向滚动」：
 * - `ImeClipboardScrollView` 的构造函数末尾会调一次自己的初始化方法（继承自 `view.M`，
 *   混淆名每版都变，4.0.0 是 `s()`），给列表 RecyclerView 装 `ImePagingLinearLayoutManager`
 *   （单列垂直）和宿主自己的列表动画器；
 * - ViewHolder `clipboard.z#h()` 每次绑定都把 `clipboard_item_line1` 的宽高写死成
 *   「面板宽 - 2*margin」×「单行高」，文本控件 `clipboard_content_tv` 被包在
 *   `HorizontalScrollView` 里做单行横向滚动。
 *
 * 本对象在开关打开时把上面两处改成「双列交错 + 高度随内容自适应」：
 * - 列表换 `StaggeredGridLayoutManager(2, VERTICAL)`，列间距全部由条目左右外边距给，
 *   列表水平内边距归零（垂直内边距沿用原生，那是视口的一部分）；
 * - 条目宽度解绑为 MATCH_PARENT（自适应列宽），行高解绑为 WRAP_CONTENT；
 * - 文本改成多行折行，并按「卡片最大高度」折算成最大行数，超出的部分在末行显示省略号；
 *   单行卡片也至少撑到两行高（`minLines`），展开后的三个按钮才有足够的点击面积；
 * - 卡片里两处按整屏宽度配平的部件（更多按钮宽度、卡片上下内边距）按列宽重新配平；
 * - 点 ⋮ 展开的操作栏（常用语 / 选词 / 删除）原生横排合计约 726px，比半列宽 456px 还宽，
 *   滚到头会把最左的按钮挤出视口，改成「上面两颗、下面一颗」的紧凑图标圆角矩形块：
 *   三颗的总高等于盖住它们的卡片高度、最多到三行文本的卡片高度，圆角取卡片自己的圆角，
 *   并给 ⋮ 留出位置以便再点收起；
 * - 卡片底部的「快速选择关键词」那一排左右各留一条边界：原生只有左边距，词条会顶到卡片边框；
 * - 关掉宿主的列表动画：点 ⋮ 只是那一条的变更，但宿主动画器会让双列里的其余卡片整片做重排动画；
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
        val itemAnimator: Any?,
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

    /** 展开操作栏里的视图 id；取不到就是 0，对应那一颗保持原生。 */
    private class ActionIds(
        val containerId: Int,
        val addId: Int,
        val addIconId: Int,
        val addTextId: Int,
        val bombId: Int,
        val bombIconId: Int,
        val bombTextId: Int,
        val deleteId: Int,
        val deleteIconId: Int,
        val confirmId: Int
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
        runCatching { hookBind() }
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
                containerId = optionalId("clipboard_action_container"),
                addId = optionalId("clipboard_action_add_container"),
                addIconId = optionalId("clipboard_action_add_icon"),
                addTextId = optionalId("clipboard_action_add_text"),
                bombId = optionalId("clipboard_action_bomb_container"),
                bombIconId = optionalId("clipboard_action_bomb_icon"),
                bombTextId = optionalId("clipboard_action_bomb_text"),
                deleteId = optionalId("clipboard_action_delete"),
                deleteIconId = optionalId("clipboard_action_delete_icon"),
                confirmId = optionalId("clipboard_action_delete_confirm")
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
        // 宿主的初始化方法（4.0.0 叫 `s()`，名字每版都变）只在这个类自己的构造函数末尾被调用一次，
        // dex 里没有第二个调用点，所以「初始化方法之后」就是「构造函数返回之后」。
        // 链式构造（2 参 → 3 参 → 4 参）里真正干活的是参数最多的那一个，别的只是转调。
        val widest = scrollClass.declaredConstructors.maxOfOrNull { it.parameterTypes.size } ?: run {
            AndroidLog.e(TAG, "clipboard scroll view has no constructor")
            return
        }
        val constructors = scrollClass.declaredConstructors.filter { it.parameterTypes.size == widest }
        constructors.forEach { constructor ->
            constructor.isAccessible = true
            constructor.hookAfter { param ->
                runCatching { applyLayoutMode(param.thisObject) }
                    .onFailure { AndroidLog.e(TAG, "apply layout on init failed: ${it.message}") }
            }
        }
        ModuleLog.i(
            "$TAG: hooked clipboard scroll view init via constructor($widest params) x${constructors.size} " +
                "(rvGetter=${rvGetter?.name})"
        )
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

    private fun hookBind() {
        // 契约自己带名字兜底（HostContracts 的 CLIPBOARD_ADAPTER_CLASS），这里不再重复抄一份短名。
        val adapterClass = runCatching {
            WeTypeHostContracts.classOf(HostContractId.CLIPBOARD_ADAPTER)
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
                }.onFailure { ModuleLog.e("$TAG: waterfall item adjust failed: $it") }
            }
            count++
        }
        AndroidLog.i(TAG, "hooked onBindViewHolder for waterfall: $count overload(s)")
    }

    /**
     * 列表每次布局之后再兜一遍所有可见条目。
     *
     * 条目自己的 `OnLayoutChangeListener` 只在**条目边界发生变化**时才触发。复用的 ViewHolder 重新
     * 绑定之后边界往往没变（上一个条目也是 456x240），回调就不会来 —— 于是「必须等测量」的那部分
     * （操作栏尺寸、行数纠偏）永远补不上：实机抓到操作栏停在原生 834x240，比 166px 的卡片高
     * 68~74px，卡片上下多出一大块空白，而且会一直卡在这个 ViewHolder 上。
     * 绑定时 `post` 一次不管用：条目此时多半还没挂上窗口，`View.post` 会把 runnable 攒到
     * attach 时执行，那时还没测量，拿到的卡片高仍是 0。所以挂在列表的布局回调上。
     */
    private fun hookRecyclerLayout(cls: Class<*>) {
        if (!rvLayoutHooked.compareAndSet(false, true)) return
        val method = cls.declaredMethods.firstOrNull {
            it.name == "onLayout" && it.parameterTypes.size == 5
        } ?: run {
            ModuleLog.e("$TAG: onLayout not found on ${cls.name}")
            return
        }
        method.isAccessible = true
        method.hookAfter { param ->
            val rv = param.thisObject as? View ?: return@hookAfter
            if (!isWaterfallEnabled() || !waterfallByRv.contains(rv)) return@hookAfter
            runCatching { recalibrateChildren(rv) }
                .onFailure { ModuleLog.e("$TAG: waterfall recalibrate children failed: $it") }
        }
        ModuleLog.i("$TAG: hooked onLayout for waterfall (${cls.name})")
    }

    private val rvLayoutHooked = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun recalibrateChildren(rv: View) {
        if (rv !is ViewGroup) return
        for (i in 0 until rv.childCount) {
            val child = rv.getChildAt(i) ?: continue
            if (child.width <= 0 || findItemView(child, containerId) == null) continue
            runCatching { calibrateItem(child, child.width, measured = true) }
                .onFailure { ModuleLog.e("$TAG: waterfall recalibrate item failed: $it") }
        }
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
            dropItemAnimator(rv)
        } else {
            disableWaterfall(rv)
        }
    }

    private fun enableWaterfall(rv: View) {
        val state = nativeByRv.getOrPut(rv) {
            NativeState(
                layoutManager = currentLayoutManager(rv),
                itemAnimator = currentItemAnimator(rv),
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
        // 布局兜底挂在**实例的类**上，不按名字找：宿主对 androidx.recyclerview 做过 R8 处理，
        // 按名字取类有取不到的风险，而这里一定拿得到真类。
        hookRecyclerLayout(rv.javaClass)
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
        state?.itemAnimator?.let { runCatching { setItemAnimator(rv, it) } }
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
            if (tv.minLines != 1) tv.minLines = 1
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

    /**
     * 点 ⋮ 展开只是宿主对那一条 `notifyItemChanged`，但宿主给列表装了自己的动画器：变更会交叉淡入淡出
     * （还会多 inflate 一个 ViewHolder），双列交错布局里其余卡片也会跟着做移动动画，看上去就是
     * 「下面的内容整片重排」。瀑布流下直接关掉列表动画，变更原地重绑、位置变化一步到位。
     *
     * **连 `ItemAnimator` 这个类型名都不能写**：4.0.0 的 dex 里它已经被改成 `RecyclerView$m`
     * （`LayoutManager` / `ViewHolder` 却还叫原名），按全名去比对，setter 与 getter 都找不到，
     * `dropItemAnimator` 就静默 `return` —— 宿主动画器一直没被摘掉，日志里却一行错误也没有。
     * 所以类型按形状认（[isItemAnimatorSetter]：RecyclerView 的嵌套类 + ItemAnimator 的抽象方法形状），
     * getter 再用「返回类型与 setter 参数类型相同」配对。**只认形状会认错**：`View.setContentCaptureSession`
     * 的参数类型形状相同，真机上被当成 setter 调了 `setContentCaptureSession(null)`，所以必须叠加嵌套关系。
     */
    private fun itemAnimatorSetter(rv: View): Method? =
        rv.javaClass.methods.firstOrNull(::isItemAnimatorSetter)

    private fun currentItemAnimator(rv: View): Any? {
        val type = itemAnimatorSetter(rv)?.parameterTypes?.get(0) ?: return null
        return rv.javaClass.methods.firstOrNull {
            it.parameterTypes.isEmpty() && it.returnType == type
        }?.let { runCatching { it.invoke(rv) }.getOrNull() }
    }

    private fun setItemAnimator(rv: View, animator: Any?) {
        val method = itemAnimatorSetter(rv)
            ?: error("RecyclerView.setItemAnimator not found by shape on ${rv.javaClass.name}")
        method.invoke(rv, animator)
    }

    private val itemAnimatorMissingLogged = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * 宿主可能在别处再装回动画器，所以每次应用布局模式都确认一次；已经是空就不动。
     * 找不到 setter 必须留痕（只留一次）：这一步以前就是静默落空的。
     */
    private fun dropItemAnimator(rv: View) {
        if (itemAnimatorSetter(rv) == null) {
            if (itemAnimatorMissingLogged.compareAndSet(false, true)) {
                ModuleLog.e("$TAG: item animator setter not found by shape on ${rv.javaClass.name}; list keeps host animations")
            }
            return
        }
        val animator = currentItemAnimator(rv) ?: return
        runCatching { setItemAnimator(rv, null) }
            .onSuccess { ModuleLog.i("$TAG: dropped host item animator ${animator.javaClass.name}") }
            .onFailure { ModuleLog.e("$TAG: drop item animator failed: ${it.message}") }
    }

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
        // 校准必须先挂上：它是「卡片右边缘 + 文本折行」的唯一入口，绑定期后面任何一步出岔子
        // 都不能让这条条目永远失去校准（症状是右边框连同圆角一起消失、文本退回单行横滚，
        // 而且只在滚动到新创建的条目上时才出现）。
        attachCalibration(itemView)

        val halfGap = ClipboardWaterfallLayoutLogic.columnHalfGapPx(dpToPx(itemView, COLUMN_GAP_DP))
        val rowHalfGap = ClipboardWaterfallLayoutLogic.rowHalfGapPx(dpToPx(itemView, ROW_GAP_DP))
        applyItemMargins(itemView, halfGap, rowHalfGap)

        // 条目宽度交给 StaggeredGridLayoutManager：它按列宽把子项测量成「列宽 - 条目外边距」。
        matchWidth(itemView)
        setWrapHeight(itemView)
        // 下面三步互不依赖，各自兜底：一步失败不能让另外两步跟着不执行。
        runCatching { applyCardChrome(itemView) }
            .onFailure { ModuleLog.e("$TAG: waterfall card chrome failed: $it") }
        runCatching { prepareContentText(itemView) }
            .onFailure { ModuleLog.e("$TAG: waterfall content text failed: $it") }
        runCatching { ensureActionMenuStructure(itemView) }
            .onFailure { ModuleLog.e("$TAG: waterfall action menu structure failed: $it") }
        // 新建的 holder 在绑定时还没测量过（width = 0），拿不到条目宽就只能按列表宽反推列宽先写一遍。
        // 漏掉这一步，宿主的原生宽度会一直留到这一轮测量：第一帧画出来就是「卡片顶出条目、
        // 右边框连同两个圆角一起被裁掉、文本退回单行横滚」，滚动时新建的条目全都这样。
        // 绑定期只写宽度与折行视口，不碰要等布局的实测几何（见 calibrateItem 的 measured）。
        val width = itemView.width.takeIf { it > 0 } ?: columnWidthFallbackPx(itemView)
        if (width > 0) {
            runCatching { calibrateItem(itemView, width, measured = false) }
                .onFailure { ModuleLog.e("$TAG: waterfall calibrate item failed: $it") }
        }
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
     * 展开操作栏的尺寸（dp）。上面一行两颗按钮的宽度由列宽反推（详见 [applyActionMenu]），
     * 这里只是夹取范围与间隙：紧凑宽度上限 30dp，卡片尾部除了 ⋮ 再留 10dp，
     * 让收起用的 ⋮ 不是紧贴着操作栏的边。三颗按钮的总高度不在这里定：它等于盖住它们的卡片高度，
     * 最多到三行文本的卡片高度（[ClipboardWaterfallLayoutLogic.ACTION_MAX_LINES]）；
     * 图标最大 22dp，行再高也不会跟着变大；圆角与卡片一致，是圆角矩形而不是胶囊 / 圆形。
     */
    private const val ACTION_BUTTON_MIN_DP = 24
    private const val ACTION_BUTTON_MAX_DP = 30
    private const val ACTION_GAP_DP = 3
    private const val ACTION_TAIL_PADDING_DP = 10
    private const val ACTION_ICON_MAX_DP = 22
    private const val ACTION_ICON_INSET_DP = 2

    /**
     * 卡片底部「快速选择关键词」那一排（`clipboard_key_information_rv`）左右各留的边界（dp）。
     * 原生只有左边 28px 的 `paddingStart`（`keyboard_clipboard_key_information_clip_padding_left`），
     * 右边没有，而且 `clipToPadding=false`：词条横向滚动时直接顶到卡片边框上。
     * 左右都留边并按内边距裁剪，词条就停在边界内。
     */
    private const val KEY_INFO_SIDE_PADDING_DP = 10

    private val nativeMoreWidth: MutableMap<View, Int> =
        Collections.synchronizedMap(WeakHashMap())

    private val nativeLinePadding: MutableMap<View, IntArray> =
        Collections.synchronizedMap(WeakHashMap())

    /** 关键词排的原生左右内边距与 `clipToPadding`，开关关闭时还原。 */
    private class NativeKeyInfo(val paddingLeft: Int, val paddingRight: Int, val clipToPadding: Boolean)

    private val nativeKeyInfo: MutableMap<View, NativeKeyInfo> =
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
        (findItemView(itemView, WeTypeClipboardImageHost.keyInfoId) as? ViewGroup)?.let { keyInfo ->
            nativeKeyInfo.getOrPut(keyInfo) {
                NativeKeyInfo(keyInfo.paddingLeft, keyInfo.paddingRight, keyInfo.clipToPadding)
            }
            val side = dpToPx(itemView, KEY_INFO_SIDE_PADDING_DP)
            if (keyInfo.paddingLeft != side || keyInfo.paddingRight != side) {
                keyInfo.setPadding(side, keyInfo.paddingTop, side, keyInfo.paddingBottom)
            }
            if (!keyInfo.clipToPadding) keyInfo.clipToPadding = true
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
        (findItemView(itemView, WeTypeClipboardImageHost.keyInfoId) as? ViewGroup)?.let { keyInfo ->
            nativeKeyInfo.remove(keyInfo)?.let { original ->
                keyInfo.setPadding(original.paddingLeft, keyInfo.paddingTop, original.paddingRight, keyInfo.paddingBottom)
                keyInfo.clipToPadding = original.clipToPadding
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
        // 单行卡片也撑到两行高：展开后的三个按钮高度跟着卡片走，一行高的卡片按钮会小得没法点。
        // 文本控件自己 `gravity=center_vertical`，多出来的那一行高度会让文字在卡片里垂直居中。
        if (tv.minLines != ClipboardWaterfallLayoutLogic.CARD_MIN_LINES) {
            tv.minLines = ClipboardWaterfallLayoutLogic.CARD_MIN_LINES
        }
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
        val shown = tv.lineCount
        if (tv.layout == null || shown <= 1 || card.height <= cap) return
        val pitch = linePitchPx(tv)
        val corrected = ClipboardWaterfallLayoutLogic.correctedMaxLines(
            currentMaxLines = tv.maxLines,
            displayedLines = shown,
            overflowPx = card.height - cap,
            linePitchPx = pitch
        )
        if (tv.maxLines != corrected) tv.maxLines = corrected
        if (shown > tv.maxLines) rebuildTextLayout(tv, tv.maxLines)
    }

    /**
     * `maxLines` 改小之后 `TextView` 的旧排版不一定会重建 —— 实机反复抓到 `maxLines=5` 而
     * `lineCount` 仍是 6，末行被视图底边切掉半行、也没有省略号（只补 `requestLayout()` 无效）。
     * 重设一次同样的文本能逼它丢掉旧排版（`nullLayouts()`），下一帧的测量才会按 `maxLines`
     * 重排、末行显示「…」。放在 `post` 里做，避免在布局过程中把排版置空。
     */
    private fun rebuildTextLayout(tv: TextView, maxLines: Int) {
        tv.post {
            val layout = tv.layout
            if (isWaterfallEnabled() && tv.maxLines == maxLines &&
                layout != null && layout.lineCount > maxLines
            ) {
                tv.setText(tv.text)
            }
        }
    }

    /** 文本实际排出来的行距（含中文 / emoji 回落字体）；还没排版时退回 `lineHeight`。 */
    private fun linePitchPx(tv: TextView): Int {
        val layout = tv.layout
        val shown = tv.lineCount
        if (layout != null && shown > 0) {
            val pitch = (layout.getLineBottom(shown - 1) - layout.getLineTop(0)) / shown
            if (pitch > 0) return pitch
        }
        return tv.lineHeight
    }

    /** 卡片上不属于文本行、也不含药丸标签行的固定占位：文本与 `line1` 的上下内边距。 */
    private fun baseChromePx(itemView: View, tv: TextView): Int {
        var chrome = tv.paddingTop + tv.paddingBottom
        findItemView(itemView, WeTypeClipboardImageHost.line1Id)?.let { line1 ->
            chrome += line1.paddingTop + line1.paddingBottom
        }
        return chrome
    }

    private fun textChromePx(itemView: View, tv: TextView): Int {
        var chrome = baseChromePx(itemView, tv)
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
     * 触摸被 `OnTouchListener` 整个吞掉，不能手动拖）。原生三颗按钮横排合计约 726px，半列视口
     * 只有 456px，滚到头会把最左的「常用语」整个挤出视口，⋮ 也跟着滚没了，用户没法再点它收起。
     *
     * 瀑布流里改成「上面两颗、下面一颗」的紧凑块：常用语、选词并排在上，删除单独占下面一整行，
     * 全部是只剩图标的圆角矩形（文字挪进 `contentDescription`）。操作栏容器是横向 `LinearLayout`，
     * 摆不出这种排法，所以把三颗按钮挪进一个竖向列容器（内含一个横向的上排），
     * 宿主按 id 持有的引用不受影响。块宽 = 两颗按钮 + 间隙，视口里卡片尾部留出 ⋮；
     * 每行高度跟着卡片高度走，保证操作栏不比卡片高、不会把卡片撑高挤动下面的卡片。
     * 「确认删除」只有一颗，保持原有样式，只把高度收成与整块一致。
     * 宿主的 `h()` 只在 ViewHolder 构造时跑一次，改过的 LayoutParams 不会被它改回去。
     */
    private class NativeActionView(
        val width: Int,
        val height: Int,
        val marginStart: Int,
        val marginTop: Int,
        val paddingLeft: Int,
        val paddingTop: Int,
        val paddingRight: Int,
        val paddingBottom: Int,
        val gravity: Int,
        val contentDescription: CharSequence?,
        val textVisibility: Int,
        val radius: Int
    )

    /** 挪动过按钮的两层容器：竖向的列、列里横向的上排。键是操作栏容器。 */
    private class ActionMenuStructure(val column: LinearLayout, val topRow: LinearLayout)

    private val actionMenus: MutableMap<View, ActionMenuStructure> =
        Collections.synchronizedMap(WeakHashMap())

    private val nativeActionViews: MutableMap<View, NativeActionView> =
        Collections.synchronizedMap(WeakHashMap())

    /** 圆角取值方法按类缓存；`ImeRadiusLinearLayout.setRadius/getRadius` 没被改名，取不到就保持原圆角。 */
    private val radiusMethods: MutableMap<Class<*>, Pair<Method, Method>?> =
        Collections.synchronizedMap(WeakHashMap())

    /**
     * 绑定时把三颗按钮挪进「上排两颗 + 下面一颗」的容器结构，只做一次。
     * 放在绑定而不是布局回调里：布局过程中增删子视图会触发多余的二次布局。
     * 缺任何一颗就整体保持原生，不摆出缺角的 2+1。
     */
    private fun ensureActionMenuStructure(itemView: View) {
        val ids = actionIds ?: return
        val menu = findItemView(itemView, ids.containerId) as? LinearLayout ?: return
        if (actionMenus.containsKey(menu)) return
        val add = findItemView(itemView, ids.addId) ?: return
        val bomb = findItemView(itemView, ids.bombId) ?: return
        val delete = findItemView(itemView, ids.deleteId) ?: return
        if (add.parent !== menu || bomb.parent !== menu || delete.parent !== menu) return
        // 插入位置按「挪走三颗之后」的子项数夹一次：操作栏容器里除了这三颗还住着「确认删除」，
        // 用挪走之前的下标直接插，容器子项数变少时会越界抛异常。
        val index = menu.indexOfChild(add)
        val context = menu.context
        val topRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        menu.removeView(add)
        menu.removeView(bomb)
        menu.removeView(delete)
        topRow.addView(add)
        topRow.addView(bomb)
        column.addView(topRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        column.addView(delete)
        menu.addView(column, index.coerceIn(0, menu.childCount), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER_VERTICAL })
        actionMenus[menu] = ActionMenuStructure(column, topRow)
    }

    private fun applyActionMenu(itemView: View, columnWidth: Int) {
        val ids = actionIds ?: return
        val menu = findItemView(itemView, ids.containerId) ?: return
        val structure = actionMenus[menu] ?: return
        val card = findItemView(itemView, containerId) ?: return
        val gap = dpToPx(itemView, ACTION_GAP_DP)
        val buttonWidth = ClipboardWaterfallLayoutLogic.actionButtonPx(
            columnWidthPx = columnWidth,
            moreButtonPx = dpToPx(itemView, MORE_BUTTON_WIDTH_DP),
            tailPaddingPx = dpToPx(itemView, ACTION_TAIL_PADDING_DP),
            gapPx = gap,
            minButtonPx = dpToPx(itemView, ACTION_BUTTON_MIN_DP),
            maxButtonPx = dpToPx(itemView, ACTION_BUTTON_MAX_DP)
        )
        val block = actionBlockHeightPx(itemView, card)
        val (topRow, bottomRow) = ClipboardWaterfallLayoutLogic.actionRowHeightsPx(block, gap)
        if (buttonWidth <= 0 || topRow <= 0 || bottomRow <= 0) return
        val blockWidth = 2 * buttonWidth + gap
        val radius = ClipboardWaterfallLayoutLogic.actionCornerRadiusPx(
            cardCornerRadiusPx(card), minOf(topRow, bottomRow)
        )
        val icon = (minOf(topRow, bottomRow) - 2 * dpToPx(itemView, ACTION_ICON_INSET_DP))
            .coerceIn(1, dpToPx(itemView, ACTION_ICON_MAX_DP))

        setMarginStart(structure.column, gap)
        styleActionButton(
            itemView, ids.addId, ids.addTextId, ids.addIconId,
            buttonWidth, topRow, 0, 0, radius, icon
        )
        styleActionButton(
            itemView, ids.bombId, ids.bombTextId, ids.bombIconId,
            buttonWidth, topRow, gap, 0, radius, icon
        )
        styleActionButton(
            itemView, ids.deleteId, 0, ids.deleteIconId,
            blockWidth, bottomRow, 0, gap, radius, icon
        )
        // 本函数是从布局回调里调用的，写 LayoutParams 只会落到**下一次**布局上。更麻烦的是：布局
        // 过程中 `View.setLayoutParams` 触发的 requestLayout 会被 `ViewRootImpl.requestLayoutDuringLayout`
        // 丢掉，那颗按钮就不会被标成「需要重新测量」；下一轮 `measureChildWithMargins` 给它的测量
        // 规格没变、又没有 FORCE_LAYOUT 标记，`View.measure` 直接复用缓存 —— 实测高永远追不上
        // LayoutParams（实机 trace：lp 124 / 实测 112，卡在这个 ViewHolder 上不再收敛）。
        // 所以 requestLayout 必须补在**按钮自己**身上：补在菜单容器上没用 —— 容器重新测量时给
        // 子项的规格没变，子项同样被跳过。
        // 「确认删除」（confirmId）也要算进来：它和竖列容器是**兄弟**，菜单高度取两者的较大值，
        // 它保持原生高度时（1080px 屏 240px，density 3.875 的屏约 303px）会把菜单撑到卡片之上，
        // 条目跟着变高，卡片下方空出一大块。GONE 的按钮实测高是 0，跳过它。
        // 同一个「实测高/声明高」组合只补一次，避免布局回调与重新布局互相触发；声明高再变就是
        // 新的组合，那时再补一次。
        findItemView(itemView, ids.confirmId)?.let { confirm ->
            snapshotActionView(confirm, null)
            setRadius(confirm, radius)
            (confirm.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                if (lp.height != block || lp.marginStart != gap) {
                    lp.height = block
                    lp.marginStart = gap
                    confirm.layoutParams = lp
                }
            }
        }
        listOf(ids.addId, ids.bombId, ids.deleteId, ids.confirmId).forEach { id ->
            val row = findItemView(itemView, id) ?: return@forEach
            if (row.visibility == View.GONE) return@forEach
            val lp = row.layoutParams as? ViewGroup.MarginLayoutParams ?: return@forEach
            if (lp.height == row.height) return@forEach
            val signature = (row.height.toLong() shl 32) or (lp.height.toLong() and 0xffffffffL)
            if (actionRowFixRequests.put(row, signature) == signature) return@forEach
            row.post { row.requestLayout() }
        }
    }

    /**
     * 三个按钮组合起来的总高：等于盖住它们的卡片高度，最多到三行文本的卡片高度
     * （上下内边距 + 3 个实测行距，卡片带关键词那一排时再加上那一排的高度）。卡片两行高时正好一样高；
     * 内容很长、卡片更高时按钮块封顶在三行，在卡片里垂直居中。取不到行距（文本还没排版）时只跟卡片走，不封顶。
     */
    private fun actionBlockHeightPx(itemView: View, card: View): Int {
        val tv = contentTv(itemView)
        val maxBlock = if (tv == null) 0 else ClipboardWaterfallLayoutLogic.linesCardHeightPx(
            chromePx = textChromePx(itemView, tv),
            linePitchPx = linePitchPx(tv),
            lines = ClipboardWaterfallLayoutLogic.ACTION_MAX_LINES
        )
        return ClipboardWaterfallLayoutLogic.actionBlockHeightPx(card.height, maxBlock)
    }

    /**
     * 卡片的圆角：皮肤给 `clipboard_item_container` 铺的圆角底。按钮圆角与它一致，
     * 取不到（背景还没铺、或不是 GradientDrawable）返回 0，调用方退回按行高取比例。
     */
    private fun cardCornerRadiusPx(card: View): Int {
        val background = card.background ?: return 0
        val shape = background as? GradientDrawable
            ?: (background as? LayerDrawable)?.let { layer ->
                (0 until layer.numberOfLayers).firstNotNullOfOrNull { layer.getDrawable(it) as? GradientDrawable }
            }
            ?: return 0
        return runCatching {
            shape.cornerRadius.toInt().takeIf { it > 0 } ?: shape.cornerRadii?.maxOrNull()?.toInt() ?: 0
        }.getOrDefault(0)
    }

    /** 按列宽与块高摆放一颗按钮。 */
    private fun styleActionButton(
        itemView: View, id: Int, textId: Int, iconId: Int,
        width: Int, height: Int, marginStart: Int, marginTop: Int, radius: Int, iconSize: Int
    ) {
        val view = findItemView(itemView, id) ?: return
        val text = findItemView(view, textId) as? TextView
        snapshotActionView(view, text)
        text?.let {
            if (it.visibility != View.GONE) it.visibility = View.GONE
            if (view.contentDescription != it.text) view.contentDescription = it.text
        }
        if (view.paddingLeft != 0 || view.paddingTop != 0 || view.paddingRight != 0 || view.paddingBottom != 0) {
            view.setPadding(0, 0, 0, 0)
        }
        (view as? LinearLayout)?.let { if (it.gravity != Gravity.CENTER) it.gravity = Gravity.CENTER }
        setRadius(view, radius)
        findItemView(view, iconId)?.let { iconView ->
            snapshotActionView(iconView, null)
            val iconLp = iconView.layoutParams
            if (iconLp != null && (iconLp.width != iconSize || iconLp.height != iconSize)) {
                iconLp.width = iconSize
                iconLp.height = iconSize
                iconView.layoutParams = iconLp
            }
        }
        val lp = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        if (lp.width == width && lp.height == height && lp.marginStart == marginStart && lp.topMargin == marginTop) {
            return
        }
        lp.width = width
        lp.height = height
        lp.marginStart = marginStart
        lp.topMargin = marginTop
        view.layoutParams = lp
    }

    private fun setMarginStart(view: View, marginStart: Int) {
        val lp = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        if (lp.marginStart == marginStart) return
        lp.marginStart = marginStart
        view.layoutParams = lp
    }

    private fun snapshotActionView(view: View, text: TextView?) {
        nativeActionViews.getOrPut(view) {
            val lp = view.layoutParams as? ViewGroup.MarginLayoutParams
            NativeActionView(
                width = lp?.width ?: ViewGroup.LayoutParams.WRAP_CONTENT,
                height = lp?.height ?: ViewGroup.LayoutParams.WRAP_CONTENT,
                marginStart = lp?.marginStart ?: 0,
                marginTop = lp?.topMargin ?: 0,
                paddingLeft = view.paddingLeft,
                paddingTop = view.paddingTop,
                paddingRight = view.paddingRight,
                paddingBottom = view.paddingBottom,
                gravity = (view as? LinearLayout)?.gravity ?: Gravity.NO_GRAVITY,
                contentDescription = view.contentDescription,
                textVisibility = text?.visibility ?: View.VISIBLE,
                radius = getRadius(view)
            )
        }
    }

    private fun radiusAccessors(view: View): Pair<Method, Method>? =
        radiusMethods.getOrPut(view.javaClass) {
            val setter = view.javaClass.methods.firstOrNull {
                it.name == "setRadius" && it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == Int::class.javaPrimitiveType
            }
            val getter = view.javaClass.methods.firstOrNull {
                it.name == "getRadius" && it.parameterTypes.isEmpty() &&
                    it.returnType == Int::class.javaPrimitiveType
            }
            if (setter != null && getter != null) setter to getter else null
        }

    private fun getRadius(view: View): Int =
        radiusAccessors(view)?.let { (_, getter) -> runCatching { getter.invoke(view) as Int }.getOrNull() } ?: -1

    private fun setRadius(view: View, radius: Int) {
        val (setter, getter) = radiusAccessors(view) ?: return
        if (runCatching { getter.invoke(view) as Int }.getOrNull() == radius) return
        runCatching { setter.invoke(view, radius) }
    }

    private fun restoreActionMenu(itemView: View) {
        val ids = actionIds ?: return
        val menu = findItemView(itemView, ids.containerId) as? LinearLayout
        val structure = menu?.let { actionMenus.remove(it) }
        val add = findItemView(itemView, ids.addId)
        val bomb = findItemView(itemView, ids.bombId)
        val delete = findItemView(itemView, ids.deleteId)
        if (menu != null && structure != null && add != null && bomb != null && delete != null) {
            val index = menu.indexOfChild(structure.column).coerceAtLeast(0)
            structure.topRow.removeAllViews()
            structure.column.removeAllViews()
            menu.removeView(structure.column)
            val at = index.coerceIn(0, menu.childCount)
            menu.addView(add, at)
            menu.addView(bomb, at + 1)
            menu.addView(delete, at + 2)
        }
        listOf(
            ids.addId to ids.addTextId, ids.bombId to ids.bombTextId,
            ids.deleteId to 0, ids.confirmId to 0
        ).forEach { (id, textId) ->
            val view = findItemView(itemView, id) ?: return@forEach
            val native = nativeActionViews.remove(view) ?: return@forEach
            (findItemView(view, textId) as? TextView)?.visibility = native.textVisibility
            view.contentDescription = native.contentDescription
            view.setPadding(native.paddingLeft, native.paddingTop, native.paddingRight, native.paddingBottom)
            (view as? LinearLayout)?.gravity = native.gravity
            if (native.radius >= 0) setRadius(view, native.radius)
            (view.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                lp.width = native.width
                lp.height = native.height
                lp.marginStart = native.marginStart
                lp.topMargin = native.marginTop
                view.layoutParams = lp
            }
        }
        listOf(ids.addIconId to ids.addId, ids.bombIconId to ids.bombId, ids.deleteIconId to ids.deleteId)
            .forEach { (iconId, ownerId) ->
                val owner = findItemView(itemView, ownerId) ?: return@forEach
                val icon = findItemView(owner, iconId) ?: return@forEach
                val native = nativeActionViews.remove(icon) ?: return@forEach
                icon.layoutParams?.let { lp ->
                    lp.width = native.width
                    lp.height = native.height
                    icon.layoutParams = lp
                }
            }
    }

    /**
     * 卡片链在文本滚动容器之外占掉的固定宽度（卡片宽 - 滚动容器宽），与列宽无关的布局常量。
     * 量到一次「卡片已经跟住列宽」的布局就记下来，供绑定期（滚动容器还没排版）从条目宽反推视口。
     * -1 表示还没量到过。
     */
    @Volatile
    private var viewportInsetPx: Int = -1

    /**
     * 按条目实测宽度校准卡片链。
     *
     * 卡片 / `line1` / 键信息列表的宽度测量是 UNSPECIFIED，只能用显式 px 跟住列宽；
     * 文本滚动容器反过来是 ConstraintLayout 里 `0dp`(MATCH_CONSTRAINT) 的约束子项，给它写死
     * 宽度会让 ConstraintLayout 在「左缩进 → 更多按钮」这段区间里居中（实测 `left=-39`，
     * 每行开头被切掉），所以它必须交回约束自己去算。
     *
     * @param measured 这一轮几何是不是布局之后量出来的。绑定期传 `false`：那时条目还没测量，
     *   读到的卡片高与文本行数都是上一个条目的残留，据此封行数会把新内容多封掉几行、
     *   据此定操作栏会把按钮定成上一个条目的尺寸；宽度与折行视口则照写不误。
     */
    private fun calibrateItem(itemView: View, width: Int, measured: Boolean) {
        if (width <= 0 || !isWaterfallEnabled()) return
        val card = findItemView(itemView, containerId)
        card?.let {
            forceWidth(it, width)
            setWrapHeight(it)
        }
        findItemView(itemView, WeTypeClipboardImageHost.line1Id)?.let { line1 ->
            forceWidth(line1, width)
            setWrapHeight(line1)
        }
        findItemView(itemView, WeTypeClipboardImageHost.keyInfoId)?.let { forceWidth(it, width) }
        // 折行必需的两步先做完，再碰展开操作栏：后者出任何差错都不能让卡片丢掉右边框、文本不再折行。
        val scroll = findItemView(itemView, WeTypeClipboardImageHost.contentScrollId)
        scroll?.let { matchConstraintWidth(it) }
        contentTv(itemView)?.let { tv ->
            val viewport = ClipboardWaterfallLayoutLogic.textViewportPx(
                scrollWidthPx = scroll?.width ?: 0,
                cardWidthPx = card?.width ?: 0,
                itemWidthPx = width,
                knownInsetPx = viewportInsetPx,
                paddingPx = tv.paddingLeft + tv.paddingRight
            )
            if (viewport > 0 && tv.maxWidth != viewport) tv.maxWidth = viewport
        }
        if (measured) {
            if (card != null && scroll != null && scroll.width > 0 && card.width == width) {
                viewportInsetPx = (card.width - scroll.width).coerceAtLeast(0)
            }
            correctTextOverflow(itemView)
        }
        // 操作栏不跟着 measured 走：绑定期就按当时的卡片高摆一次，第一个布局就是对的尺寸，
        // 不会先露一帧原生的大按钮。卡片高取到 0（新建的条目）时它自己会跳过。
        runCatching { applyActionMenu(itemView, width) }
            .onFailure { ModuleLog.e("$TAG: waterfall action menu failed: $it") }
        if (measured) clampItemHeight(itemView, card)
    }

    /** 条目高度补布局请求去重：键是条目，值是上次请求时的「实测高/卡片高」。 */
    private val itemHeightFixRequests: MutableMap<View, Long> =
        Collections.synchronizedMap(WeakHashMap<View, Long>())

    /** 操作栏按钮补布局请求去重：键是按钮，值是上次请求时的「实测高/声明高」。 */
    private val actionRowFixRequests: MutableMap<View, Long> =
        Collections.synchronizedMap(WeakHashMap<View, Long>())

    /**
     * 条目高度比卡片高时补一次布局请求。
     *
     * 条目高度 = max(卡片高, 操作栏高)，而操作栏的设计高度本来就 ≤ 卡片高，所以两者相等才是收敛态。
     * 不相等时条目被操作栏钉住：条目自己的测量值是**缓存**的 —— 测量规格没变又没有 FORCE_LAYOUT 标记时
     * `View.measure` 直接复用上一次的实测值，菜单第一次还是原生大小时量出来的那个高度会一直留着，
     * 而三颗按钮收敛之后已经没有任何东西再去改条目边界，条目也就再不会重新测量。实机（1080px 屏）
     * 原生菜单 240px 对 228px 的卡片只空 12px，看不出问题；density 3.875 的屏上原生菜单约 303px
     * 对 189px 的卡片就是 114px，用户看到的是「卡片突然变小、间距突然变大」。
     *
     * 这里**只请求重新测量**，不把条目高度写死：写死会把条目夹住，卡片底部那排关键词（宿主异步填的）
     * 再出现时卡片长不高，会被静默裁掉。同一个「实测高/卡片高」组合只请求一次，避免布局回调与
     * 重新布局互相触发；高度真的变了会换成新的组合，那时再请求一次。
     */
    private fun clampItemHeight(itemView: View, card: View?) {
        val expected = card?.height ?: return
        if (expected <= 0 || itemView.height <= expected) return
        val signature = (itemView.height.toLong() shl 32) or (expected.toLong() and 0xffffffffL)
        if (itemHeightFixRequests.put(itemView, signature) == signature) return
        itemView.post { itemView.requestLayout() }
    }

    /** holder 会复用同一条 itemView，listener 与校准都按 View 去重。 */
    private val calibratedViews: MutableSet<View> =
        Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<View, Boolean>()))

    private val itemLayoutListener = View.OnLayoutChangeListener { view, _, _, right, _, _, _, _, _ ->
        val width = right - view.left
        if (width <= 0) return@OnLayoutChangeListener
        runCatching { calibrateItem(view, width, measured = true) }
            .onFailure { ModuleLog.e("$TAG: waterfall calibrate item failed: $it") }
    }

    /** 卡片 → 它所属的条目：卡片的布局回调里要拿到条目才能校准。 */
    private val cardOwners: MutableMap<View, View> = Collections.synchronizedMap(WeakHashMap())

    /**
     * 卡片自己的布局回调。
     *
     * 卡片会「先变高、再变矮」：底部那排关键词是宿主异步填进去的，出现时卡片 166 → 234，
     * 消失后又回到 166。而这时**条目高度被操作栏钉住没有变化**（操作栏是 min(卡片高, 三行高)，
     * 卡片 234 时它也是 234），于是条目的 `OnLayoutChangeListener` 不会触发，操作栏就停在 234、
     * 比 166px 的卡片高出 68px，一直卡在这个 ViewHolder 上 —— 实机就是「滑上来之后某张卡片
     * 突然变小、上下空出一大块」。所以卡片自己的布局回调也要兜一次。
     */
    private val cardLayoutListener = View.OnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
        val itemView = cardOwners[view] ?: return@OnLayoutChangeListener
        if (itemView.width <= 0 || !isWaterfallEnabled()) return@OnLayoutChangeListener
        runCatching { calibrateItem(itemView, itemView.width, measured = true) }
            .onFailure { ModuleLog.e("$TAG: waterfall card calibrate failed: $it") }
    }

    private fun attachCalibration(itemView: View) {
        if (!calibratedViews.add(itemView)) return
        itemView.addOnLayoutChangeListener(itemLayoutListener)
        findItemView(itemView, containerId)?.let { card ->
            cardOwners[card] = itemView
            card.addOnLayoutChangeListener(cardLayoutListener)
        }
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

    /**
     * 分割条 / 页脚：占满整行。
     *
     * 宿主这版 RecyclerView 被 R8 削过：`StaggeredGridLayoutManager$LayoutParams#setFullSpan(boolean)`
     * **整个被删掉了**（宿主自己不用），只留下 `isFullSpan()`。所以按「单个 boolean 参数的方法」找
     * 永远找不到 —— 原来的写法在这里静默 `return`，分割条就一直占着**单列**，文字居中在左列而不是
     * 屏幕中间，看上去就是「布局不对」（实机 1272px 屏：文字中心 x=336，左列中心 338，屏幕中心 636）。
     *
     * 名字全被混淆（实测 LP 类是 `StaggeredGridLayoutManager$c`、字段 `b`、getter `a()`），所以只能
     * 按类型找：LP 类**自己**只声明两个字段（一个 Span、一个 boolean），取那个 boolean 直接写。
     * 写完用保留下来的无参 boolean getter 自证一次 —— 找错字段时它不会变成 true，会留下日志。
     */
    private fun markFullSpan(itemView: View) {
        val lp = itemView.layoutParams ?: return
        val field = lp.javaClass.declaredFields.firstOrNull {
            it.type == Boolean::class.javaPrimitiveType && !Modifier.isStatic(it.modifiers)
        } ?: run {
            ModuleLog.e("$TAG: fullSpan field not found on ${lp.javaClass.name}")
            return
        }
        val applied = runCatching {
            field.isAccessible = true
            field.setBoolean(lp, true)
            true
        }.getOrElse {
            ModuleLog.e("$TAG: setFullSpan field failed: ${it.message}")
            false
        }
        if (!applied) return
        val getter = lp.javaClass.methods.firstOrNull {
            it.parameterTypes.isEmpty() && it.returnType == Boolean::class.javaPrimitiveType
        }
        if (getter != null && runCatching { getter.invoke(lp) as? Boolean }.getOrNull() != true) {
            ModuleLog.e("$TAG: fullSpan did not stick on ${lp.javaClass.name}")
        }
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

    /**
     * ViewHolder 的根视图：`RecyclerView.ViewHolder.itemView`，按形状取（[viewHolderItemViewField]）。
     *
     * 以前是「沿继承链取第一个非空的 View 类型字段」：宿主行 ViewHolder（4.0.0 的 `clipboard.z`）自己
     * 声明了十几个 View 字段，取到哪个全看字段的声明顺序（4.0.0 上第一个恰好就是根视图，实机核对过
     * 两种取法是同一个 View），换一版重排字段就可能拿到卡片或文本控件，而整条瀑布流的条目校准都挂在
     * 这个视图上。
     */
    private fun holderItemView(holder: Any): View? {
        val field = viewHolderItemViewField(holder.javaClass)
        if (field == null) {
            if (itemViewMissingLogged.compareAndSet(false, true)) {
                ModuleLog.e("$TAG: ViewHolder.itemView not found by shape on ${holder.javaClass.name}; waterfall items stay native")
            }
            return null
        }
        return field.get(holder) as? View
    }

    private val itemViewMissingLogged = java.util.concurrent.atomic.AtomicBoolean(false)
}
