package com.xposed.wetypehook.wetype.hook

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.util.Log as AndroidLog
import java.io.FileOutputStream

private const val TAG = "WeTypeToolbarStrip"

/** 条带最长边上限。宿主工具栏不可能有这么宽，超了说明抓到的不是工具栏。 */
private const val MAX_STRIP_EDGE_PX = 4096

/** 结构摘要的遍历上限：宿主改版后照旧只打一行，不做全树 dump。 */
private const val TREE_MAX_DEPTH = 3
private const val TREE_MAX_NODES = 24

/**
 * 把真机键盘的工具栏那一行原样渲染成一张条带 PNG，供设置页的手绘复刻件当工具栏用。
 *
 * ## 为什么不按资源名去取宿主的图标
 *
 * 宿主 R 类的**类名**每次更新都可能变，图标集合本身也会变，照着名字硬编码等于把
 * 「哪一版宿主」写进模块。这里直接渲染活着的那一行：顺序、尺寸、间距、当前 logo
 * （含自定义图片、当前深浅色）全是宿主自己画的，天然 1:1，也天然跨版本。
 *
 * ## 为什么只渲染行、不渲染整条屏幕带
 *
 * 行背景是透明的，复刻件自己的面板材质照常透出来；抓整条屏幕带会把面板底色一起带上，
 * 用户改背景色时条带就成了过期底色。
 */
internal object KeyboardToolbarStripCapture {

    /**
     * 抓一次工具栏条带并落盘；抓到返回 true。
     *
     * 只在输入法进程调用。找不到工具栏行、尺寸不合法、渲染或写盘失败都返回 false，
     * 设置页那边照旧退回「只画 logo」。
     */
    fun capture(context: Context, decor: ViewGroup): Boolean {
        val row = runCatching { findKeyboardToolbarRow(decor) }.getOrNull()
        if (row == null) {
            AndroidLog.i(TAG, "toolbar row not found, strip skipped")
            return false
        }
        val width = row.width
        val height = row.height
        if (width <= 0 || height <= 0 || width > MAX_STRIP_EDGE_PX || height > MAX_STRIP_EDGE_PX) {
            AndroidLog.i(TAG, "toolbar row size unusable: ${width}x$height")
            return false
        }
        val bitmap = runCatching {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        }.getOrNull() ?: return false
        val rendered = runCatching {
            row.draw(Canvas(bitmap))
            true
        }.getOrDefault(false)
        if (!rendered) {
            AndroidLog.i(TAG, "toolbar row draw failed: ${width}x$height")
            return false
        }
        return runCatching {
            val target = KeyboardToolbarStrip.file(context)
            target.parentFile?.mkdirs()
            FileOutputStream(target).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            AndroidLog.i(
                TAG,
                "toolbar strip captured: ${width}x$height window=${decor.width} row=${describeRow(row)}"
            )
            true
        }.getOrDefault(false)
    }

    /**
     * 找键盘顶部那条工具栏。
     *
     * 判定只看形状，不认类名、不认资源 id —— 宿主每次更新都会换掉类名与资源名，认名字等于把
     * 「哪一版宿主」写进模块。工具栏的形状是：**占满窗口宽、比窗口窄边矮得多、且自己带子视图**。
     * 键盘本体是自绘的（`S9DoublePinQwertyKeyboard` 这类只有一个空壳 View），候选区、按键区
     * 外壳都比这条判据高或窄，所以筛下来只剩工具栏那一行。
     *
     * 命中多个时取屏幕纵坐标最小的那个：工具栏就在键盘最顶上。
     */
    private fun findKeyboardToolbarRow(decor: ViewGroup): ViewGroup? {
        val windowWidth = decor.width
        if (windowWidth <= 0) return null
        val maxHeight = windowWidth / 3
        var best: ViewGroup? = null
        var bestTop = Int.MAX_VALUE
        val loc = IntArray(2)
        val queue: ArrayDeque<View> = ArrayDeque()
        queue.add(decor)
        var hops = 0
        while (queue.isNotEmpty() && hops < 400) {
            val view = queue.removeFirst()
            hops++
            if (view is ViewGroup && view.visibility == View.VISIBLE) {
                if (view.width == windowWidth && view.height in 1..maxHeight &&
                    visibleChildCount(view) >= 2
                ) {
                    runCatching { view.getLocationOnScreen(loc) }
                    if (loc[1] < bestTop) {
                        bestTop = loc[1]
                        best = view
                    }
                }
                for (i in 0 until view.childCount) {
                    view.getChildAt(i)?.let { queue.add(it) }
                }
            }
        }
        return best
    }

    private fun visibleChildCount(group: ViewGroup): Int {
        var count = 0
        for (i in 0 until group.childCount) {
            if (group.getChildAt(i)?.visibility == View.VISIBLE) count++
        }
        return count
    }

    /**
     * 工具栏那一行的结构摘要（id 名 + 相对父容器的位置 + 文本），一行输出。
     *
     * 宿主改版后「抓到的还是不是工具栏」只能靠这行判断，所以它跟条带尺寸一起常驻日志，
     * 不做全树 dump：节点数封顶 [TREE_MAX_NODES]、深度封顶 [TREE_MAX_DEPTH]。
     */
    private fun describeRow(row: ViewGroup): String {
        val parts = mutableListOf<String>()
        var budget = TREE_MAX_NODES
        fun walk(view: View, depth: Int, name: String) {
            if (budget <= 0) return
            budget--
            val id = runCatching {
                view.id.takeIf { it != View.NO_ID }?.let { view.resources.getResourceEntryName(it) }
            }.getOrNull() ?: "-"
            val text = (view as? TextView)?.text?.toString()?.take(12)
            parts += buildString {
                append(name)
                append('/').append(id)
                append('[').append(view.left).append(',').append(view.top)
                append(' ').append(view.width).append('x').append(view.height).append(']')
                if (!text.isNullOrEmpty()) append('"').append(text).append('"')
            }
            if (view is ViewGroup && depth < TREE_MAX_DEPTH) {
                for (i in 0 until view.childCount) {
                    val child = view.getChildAt(i) ?: continue
                    walk(child, depth + 1, "$name.$i")
                }
            }
        }
        walk(row, 0, "row")
        return parts.joinToString(" ")
    }
}
