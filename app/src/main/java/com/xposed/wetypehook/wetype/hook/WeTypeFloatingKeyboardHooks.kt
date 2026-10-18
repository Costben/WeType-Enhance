package com.xposed.wetypehook.wetype.hook

import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.NinePatchDrawable
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.ImageView
import android.widget.PopupWindow
import com.xposed.wetypehook.xposed.Log
import com.xposed.wetypehook.xposed.hookAfter
import com.xposed.wetypehook.xposed.invokeMethodAs
import java.lang.ref.WeakReference
import java.util.WeakHashMap

private const val POPUP_DECOR_CLASS = "android.widget.PopupWindow\$PopupDecorView"
private const val FLOATING_PACKAGE_PREFIX = "com.tencent.wetype.plugin.hld.float."
private const val RADIUS_LINEAR_LAYOUT = "com.tencent.wetype.plugin.hld.view.ImeRadiusLinearLayout"
private const val IME_ROOT_VIEW = "com.tencent.wetype.plugin.hld.view.ImeRootView"
private const val FLOATING_PANEL_FALLBACK_RADIUS_DP = 16f
private const val FLOATING_BODY_SEARCH_DEPTH = 4

/** 面板圆角：优先用宿主自己的，读不到或非正才退回默认值。 */
internal fun resolveFloatingPanelRadiusPx(hostRadiusPx: Float?, density: Float): Float =
    hostRadiusPx?.takeIf { it > 0f } ?: (FLOATING_PANEL_FALLBACK_RADIUS_DP * density)

/**
 * 是不是宿主给悬浮面板铺的那层不透明底：只认与面板本体**同色**且不全透明的纯色，
 * 按键帽、药丸、叠加面板自己的底色一概不碰。
 */
internal fun isHostPanelFill(color: Int?, hostFill: Int?): Boolean =
    color != null && hostFill != null && color == hostFill && (color ushr 24) != 0

/**
 * 悬浮键盘跟随模块的背景设置（颜色与透明度、背景模糊、边缘光）。
 *
 * 悬浮键盘不在输入法窗口里：宿主把它放进一个挂在输入法 token 上的独立 PopupWindow
 * （`TYPE_APPLICATION_ATTACHED_DIALOG`），整宽背板载体盖不到它。停靠态的键盘区之所以透明，
 * 是因为模块把宿主的几项皮肤色置成了透明；悬浮态用的却是另一项 `ime_skin_keyboard_background`
 * （属性 `keyboard_background`），面板本体、候选栏、键盘区各铺一层同色不透明底，模块的透明度
 * 设置对它没有任何作用。
 *
 * 做法：面板本体换成模块自己的背景；与它同色的其余宿主不透明底（候选栏、键盘区、叠加面板）
 * 置成透明，宿主烘焙的「实心边 + 阴影」9-patch 框藏起来。叠加面板盖住键盘时的遮挡与停靠态
 * 一样交给 [WeTypeOverlayCompositor]。
 */
internal object WeTypeFloatingKeyboardHooks {
    private class PanelState {
        var observer: WeakReference<ViewTreeObserver>? = null
        var preDraw: ViewTreeObserver.OnPreDrawListener? = null
        var layout: ViewTreeObserver.OnGlobalLayoutListener? = null
        var attachListener: View.OnAttachStateChangeListener? = null
        var dirty = true
        var body: WeakReference<View>? = null
        var bodyDrawable: Drawable? = null
        var styleKey: Any? = null
        var viewRoot: Any? = null
        var hostFill: Int? = null
        var hostRadius: Float? = null
        var cleared: List<WeakReference<View>> = emptyList()
        var frames: List<WeakReference<View>> = emptyList()
    }

    private val panels = WeakHashMap<View, PanelState>()

    private val mainHandler = Handler(Looper.getMainLooper())
    private var floatingActive = false
    private var floatingStateCheckScheduled = false

    /** 被我们换掉背景的视图 → 宿主原来的背景，热重载与撤销时还原。 */
    private val originals = WeakHashMap<View, Drawable?>()

    /** 被我们藏起来的宿主边框容器 → 原来的可见性。 */
    private val hiddenFrames = WeakHashMap<View, Int>()

    fun install() {
        runCatching {
            // 所有显示路径（showAtLocation / showAsDropDown / update 重新显示）都汇到这里；
            // 在窗口加进 WindowManager 之后、第一帧绘制之前挂监听，不会闪一帧宿主的不透明底。
            val invokePopup = PopupWindow::class.java.getDeclaredMethod(
                "invokePopup", WindowManager.LayoutParams::class.java
            ).apply { isAccessible = true }
            invokePopup.hookAfter { param ->
                val popup = param.thisObject as? PopupWindow ?: return@hookAfter
                popup.contentView?.rootView?.let(::track)
            }
            Log.i("Success: Hook WeType floating keyboard panel")
        }.onFailure {
            Log.i("Failed: Hook WeType floating keyboard panel")
            Log.i(it)
        }
    }

    /** 设置变更后重放：窗口已经在显示时，挂监听并重算一遍。 */
    fun reconcile(rootViews: List<View>) {
        rootViews.map { it.rootView }.distinct().forEach(::track)
        refresh()
    }

    /** 已显示的悬浮窗口标脏并要一帧，下一次预绘制里按最新设置重建背景。 */
    fun refresh() {
        synchronized(panels) { panels.entries.toList() }.forEach { (decor, state) ->
            state.dirty = true
            decor.invalidate()
        }
    }

    fun clearAll() {
        synchronized(panels) {
            panels.entries.toList().forEach { (decor, state) -> unregister(decor, state, removeAttach = true) }
            panels.clear()
        }
        originals.entries.toList().forEach { (view, original) ->
            runCatching { view.background = original }
        }
        originals.clear()
        hiddenFrames.entries.toList().forEach { (view, visibility) ->
            runCatching { view.visibility = visibility }
        }
        hiddenFrames.clear()
        floatingStateCheckScheduled = false
        if (floatingActive) {
            floatingActive = false
            WeTypeWindowHooks.onFloatingKeyboardStateChanged(false)
        }
    }

    private fun track(decor: View) {
        if (decor.javaClass.name != POPUP_DECOR_CLASS) return
        if (!isFloatingKeyboard(decor)) return
        val state = synchronized(panels) { panels.getOrPut(decor) { PanelState() } }
        if (state.attachListener == null) {
            val reference = WeakReference(decor)
            val listener = object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    reference.get()?.let {
                        register(it, state)
                        notifyFloatingState()
                    }
                }

                override fun onViewDetachedFromWindow(v: View) {
                    reference.get()?.let {
                        unregister(it, state, removeAttach = false)
                        notifyFloatingState()
                    }
                }
            }
            state.attachListener = listener
            decor.addOnAttachStateChangeListener(listener)
        }
        if (decor.isAttachedToWindow) {
            register(decor, state)
            notifyFloatingState()
        }
    }

    /**
     * 悬浮键盘是否正显示：宿主把面板放进独立 PopupWindow，面板挂上窗口即悬浮态。
     *
     * 退出悬浮（弹窗销毁）或收起键盘时随之解除。用一帧去重，避免宿主重建弹窗时
     * 「先 detach 再 attach」把输入法底栏闪回来。
     */
    private fun notifyFloatingState() {
        if (floatingStateCheckScheduled) return
        floatingStateCheckScheduled = true
        mainHandler.post {
            floatingStateCheckScheduled = false
            val active = synchronized(panels) { panels.keys.any { it.isAttachedToWindow } }
            if (active == floatingActive) return@post
            floatingActive = active
            Log.i("Floating keyboard bottom bar ${if (active) "hidden" else "restored"}")
            WeTypeWindowHooks.onFloatingKeyboardStateChanged(active)
        }
    }

    /**
     * 宿主 4.0.0 的悬浮键盘视图类（`float.e`）名字会随版本变，包名稳定；包名对不上时退到
     * 结构判断：里面有宿主的键盘根视图 `ImeRootView`。
     */
    private fun isFloatingKeyboard(decor: View): Boolean {
        val content = (decor as? ViewGroup)?.takeIf { it.childCount > 0 }?.getChildAt(0) ?: return false
        if (content.javaClass.name.startsWith(FLOATING_PACKAGE_PREFIX)) return true
        return containsClass(content, IME_ROOT_VIEW, FLOATING_BODY_SEARCH_DEPTH + 2)
    }

    private fun containsClass(view: View, className: String, depth: Int): Boolean {
        if (view.javaClass.name == className) return true
        if (depth <= 0 || view !is ViewGroup) return false
        return (0 until view.childCount).any { containsClass(view.getChildAt(it), className, depth - 1) }
    }

    private fun register(decor: View, state: PanelState) {
        val observer = decor.viewTreeObserver
        if (!observer.isAlive) return
        if (state.observer?.get() === observer && state.preDraw != null) {
            state.dirty = true
            return
        }
        unregister(decor, state, removeAttach = false)
        val reference = WeakReference(decor)
        val layout = ViewTreeObserver.OnGlobalLayoutListener { state.dirty = true }
        val preDraw = ViewTreeObserver.OnPreDrawListener {
            reference.get()?.let { root ->
                if (state.dirty || needsRecheck(state)) {
                    runCatching { apply(root, state) }.onFailure {
                        Log.i("Failed: Apply floating keyboard panel background")
                        Log.i(it)
                    }
                }
            }
            true
        }
        state.observer = WeakReference(observer)
        state.layout = layout
        state.preDraw = preDraw
        state.dirty = true
        // 新窗口有新的 ViewRoot，背景模糊 drawable 属于上一次挂载，必须重建。
        state.bodyDrawable = null
        state.viewRoot = null
        observer.addOnGlobalLayoutListener(layout)
        observer.addOnPreDrawListener(preDraw)
        // 叠加面板（设置、剪贴板、表情……）的底色清掉之后不再自己遮住下面的键盘，
        // 与停靠态一样交给合成器在合成时补上这层遮挡。
        WeTypeOverlayCompositor.reconcile(listOf(decor))
    }

    private fun unregister(decor: View, state: PanelState, removeAttach: Boolean) {
        // 窗口 attach 时观察者会被换成活的那个，存下来的可能已经死了，监听却活在新的上面。
        listOfNotNull(state.observer?.get(), decor.viewTreeObserver).distinct()
            .filter { it.isAlive }
            .forEach { observer ->
                state.layout?.let(observer::removeOnGlobalLayoutListener)
                state.preDraw?.let(observer::removeOnPreDrawListener)
            }
        state.observer = null
        state.layout = null
        state.preDraw = null
        if (removeAttach) {
            state.attachListener?.let(decor::removeOnAttachStateChangeListener)
            state.attachListener = null
        }
    }

    /** 每帧只做几个视图的廉价核对：宿主换皮肤时会原地重铺底色，不一定伴随布局。 */
    private fun needsRecheck(state: PanelState): Boolean {
        val body = state.body?.get() ?: return false
        val drawable = state.bodyDrawable
        if (drawable != null && body.background !== drawable) return true
        // 宿主的圆角在视图排版之后才设，设置圆角只 invalidate、不触发布局。
        if (drawable != null && hostRadiusPx(body) != state.hostRadius) return true
        if (state.frames.any { it.get()?.visibility == View.VISIBLE }) return true
        val fill = state.hostFill ?: return false
        return state.cleared.any { reference ->
            isHostPanelFill((reference.get()?.background as? ColorDrawable)?.color, fill)
        }
    }

    private fun apply(decor: View, state: PanelState) {
        state.dirty = false
        val body = findBody(decor) ?: return
        state.body = WeakReference(body)
        val current = body.background
        if (current is ColorDrawable) {
            // 宿主刚（重新）铺了不透明底：记下色值，之后用它识别同色的其余层。
            state.hostFill = current.color
            remember(body, current)
        }
        val fill = state.hostFill ?: return
        ensureBackground(body, state)
        hideHostFrame(body, state)
        clearHostFills(decor, body, state, fill)
    }

    /**
     * 本体外面那圈宿主边框：与本体同级的容器，里面只有 9-patch 的 ImageView（宿主烘焙好的
     * 「9px 实心边 + 阴影」，不跟皮肤色走）。本体换成半透明之后，这圈不透明边会露成双层边框，
     * 面板的轮廓改由模块背景的边缘光负责，边框藏起来。
     * 用 INVISIBLE 而不是 GONE：不动布局；宿主拖动时改的是子视图 alpha，不碰容器可见性。
     */
    private fun hideHostFrame(body: View, state: PanelState) {
        val parent = body.parent as? ViewGroup ?: return
        val frames = mutableListOf<WeakReference<View>>()
        for (index in 0 until parent.childCount) {
            val child = parent.getChildAt(index)
            if (child === body || !isNinePatchFrame(child)) continue
            if (!hiddenFrames.containsKey(child)) hiddenFrames[child] = child.visibility
            if (child.visibility != View.INVISIBLE) child.visibility = View.INVISIBLE
            frames += WeakReference(child)
        }
        state.frames = frames
    }

    private fun isNinePatchFrame(view: View): Boolean {
        if (view !is ViewGroup || view.childCount == 0) return false
        val images = (0 until view.childCount).map(view::getChildAt)
        return images.all { it is ImageView } &&
            images.any { (it as ImageView).drawable is NinePatchDrawable }
    }

    private fun ensureBackground(body: View, state: PanelState) {
        val density = body.resources.displayMetrics.density
        val hostRadius = hostRadiusPx(body)
        val radius = resolveFloatingPanelRadiusPx(hostRadius, density)
        val viewRoot = runCatching { body.invokeMethodAs<Any>("getViewRootImpl") }.getOrNull()
        val style = WeTypeWindowHooks.floatingPanelStyle(body, radius)
        val unchanged = state.bodyDrawable != null && body.background === state.bodyDrawable &&
            state.styleKey == style && state.viewRoot === viewRoot
        state.hostRadius = hostRadius
        if (unchanged) return
        val drawable = WeTypeWindowHooks.createFloatingPanelBackground(body, style)
        body.background = drawable
        state.bodyDrawable = drawable
        state.styleKey = style
        state.viewRoot = viewRoot
        Log.i(
            "Floating keyboard panel background applied: radius=${radius}px hostRadius=$hostRadius " +
                "size=${body.width}x${body.height} hostFill=#${Integer.toHexString(state.hostFill ?: 0)}"
        )
    }

    /**
     * 面板圆角取自宿主给它设的轮廓：让 outlineProvider 填一个 Outline 再读半径，不依赖宿主任何
     * 命名。216 实机 `ImeRadiusLinearLayout.getRadius()` 读回 0（圆角不走它存），轮廓里却是真值。
     */
    private fun hostRadiusPx(body: View): Float? {
        val fromOutline = runCatching {
            val outline = Outline()
            body.outlineProvider?.getOutline(body, outline)
            outline.radius.takeIf { it > 0f }
        }.getOrNull()
        if (fromOutline != null) return fromOutline
        return runCatching { (body.javaClass.getMethod("getRadius").invoke(body) as? Int)?.toFloat() }.getOrNull()
    }

    private fun clearHostFills(decor: View, body: View, state: PanelState, fill: Int) {
        val cleared = mutableListOf<WeakReference<View>>()
        fun visit(view: View) {
            if (view !== body) {
                val color = (view.background as? ColorDrawable)?.color
                if (isHostPanelFill(color, fill)) {
                    remember(view, view.background)
                    view.background = ColorDrawable(Color.TRANSPARENT)
                    cleared += WeakReference(view)
                }
            }
            if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
        }
        visit(decor)
        // 保留此前已清掉、仍存活的视图：核对每帧只看这份名单。
        state.cleared = (state.cleared.filter { it.get() != null } + cleared)
            .distinctBy { System.identityHashCode(it.get()) }
    }

    private fun remember(view: View, original: Drawable?) {
        if (!originals.containsKey(view)) originals[view] = original
    }

    /**
     * 面板本体：悬浮视图根下带宿主圆角的 `ImeRadiusLinearLayout`，底是不透明纯色。
     * 只认最上面一层，里面嵌套的同类容器不是面板。
     */
    private fun findBody(root: View): View? {
        fun search(view: View, depth: Int): View? {
            if (view.javaClass.name == RADIUS_LINEAR_LAYOUT && view.background != null) return view
            if (depth <= 0 || view !is ViewGroup) return null
            for (index in 0 until view.childCount) {
                search(view.getChildAt(index), depth - 1)?.let { return it }
            }
            return null
        }
        return search(root, FLOATING_BODY_SEARCH_DEPTH)
    }
}
