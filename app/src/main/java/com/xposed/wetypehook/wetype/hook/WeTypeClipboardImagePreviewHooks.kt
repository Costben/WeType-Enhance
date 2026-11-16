package com.xposed.wetypehook.wetype.hook

import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import com.xposed.wetypehook.xposed.Log
import com.xposed.wetypehook.xposed.hookAfter
import java.util.WeakHashMap

/**
 * 剪贴板大图预览页（`S33ImagePreviewKeyboard`）的背景。
 *
 * 宿主把预览页放进 `input_upper_view`，**盖在**候选栏与键盘页之上，而页面自己不画背景：
 * 卡片只有 13% 白的 `ime_color_16`，页面根视图的 `GradientDrawable` 没有颜色。模块把皮肤色
 * 做成透明背板之后，这些透明处直接透出下层的候选栏文字与 QWERTY 键帽，看上去就是
 * 「预览页塌成一块浮在键盘上的白块」。
 *
 * 修法不额外铺色：预览页显示期间，把页容器所在层（`t1`）里其余可见兄弟
 * （候选栏 + 键盘页容器）置 `INVISIBLE`，让模块背板透出来 —— 颜色、模糊、边缘光、深浅色
 * 都与其它面板一致，也不用猜任何色值。用 `INVISIBLE` 而不是 `GONE`：保留布局位置，
 * IME 的 insets 与窗口高度都不动。
 *
 * 还原走每帧 preDraw 判定，不依赖宿主走哪条关闭路径（移除页面 / 隐藏容器都成立）。
 */
internal object WeTypeClipboardImagePreviewHooks {

    private const val TAG = "WeTypeClipboardImage"

    /** 预览页挂上窗口后最多等这么多帧还不显示，就当它不会显示，把键盘层还原。 */
    private const val MAX_SHOW_WAIT_FRAMES = 60

    /** 被隐藏的键盘层视图 → 原可见性。 */
    private val originals = WeakHashMap<View, Int>()

    private var page: View? = null
    private var observer: ViewTreeObserver? = null

    /** 页面是否真的显示过。没显示过之前不能按「不可见」判成已关闭。 */
    private var seenShown = false
    private var waitedFrames = 0

    fun install(previewClass: Class<*>) {
        previewClass.declaredConstructors.forEach { constructor ->
            constructor.isAccessible = true
            constructor.hookAfter { param ->
                val view = param.thisObject as? View ?: return@hookAfter
                view.post { apply(view) }
                view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(attached: View) {
                        attached.post { apply(attached) }
                    }

                    override fun onViewDetachedFromWindow(detached: View) {
                        if (page === detached) restore("preview detached")
                    }
                })
            }
        }
        Log.i(TAG, "image preview background hook installed on ${previewClass.name}")
    }

    fun prepareForHotReload() {
        restore("hot reload")
    }

    private fun apply(pageView: View) {
        if (page === pageView && originals.isNotEmpty()) return
        if (!pageView.isAttachedToWindow) return
        val pageContainer = pageView.parent as? ViewGroup ?: return
        val layerHost = pageContainer.parent as? ViewGroup ?: return
        if (page !== null) restore("preview replaced")

        page = pageView
        seenShown = false
        waitedFrames = 0

        var hidden = 0
        for (index in 0 until layerHost.childCount) {
            val child = layerHost.getChildAt(index)
            if (child === pageContainer) continue
            if (child.visibility != View.VISIBLE) continue
            originals[child] = child.visibility
            child.visibility = View.INVISIBLE
            hidden++
        }

        val treeObserver = layerHost.viewTreeObserver
        if (treeObserver.isAlive) {
            treeObserver.addOnPreDrawListener(guard)
            observer = treeObserver
        }
        Log.i(TAG, "keyboard layer hidden for image preview ($hidden views under ${layerHost.javaClass.simpleName})")
    }

    /** 宿主有可能在预览期间把候选栏重新显示出来，每帧廉价核对一次。 */
    private fun reassert() {
        if (originals.isEmpty()) return
        originals.keys.toList().forEach { view ->
            if (view.visibility != View.INVISIBLE) view.visibility = View.INVISIBLE
        }
    }

    private val guard = object : ViewTreeObserver.OnPreDrawListener {
        override fun onPreDraw(): Boolean {
            val current = page ?: return true
            if (!current.isAttachedToWindow) {
                restore("preview detached")
                return true
            }
            if (current.isShown) {
                seenShown = true
                reassert()
                return true
            }
            if (seenShown) {
                restore("preview hidden")
                return true
            }
            if (++waitedFrames > MAX_SHOW_WAIT_FRAMES) {
                restore("preview never shown")
            }
            return true
        }
    }

    private fun restore(reason: String) {
        val entries = originals.entries.toList()
        originals.clear()
        entries.forEach { (view, visibility) ->
            runCatching { if (view.visibility != visibility) view.visibility = visibility }
        }
        detachGuard()
        page = null
        seenShown = false
        waitedFrames = 0
        if (entries.isNotEmpty()) {
            Log.i(TAG, "keyboard layer restored ($reason, ${entries.size} views)")
        }
    }

    private fun detachGuard() {
        val treeObserver = observer
        observer = null
        if (treeObserver != null) {
            runCatching { if (treeObserver.isAlive) treeObserver.removeOnPreDrawListener(guard) }
        }
    }
}
