package com.xposed.wetypehook.wetype.hook

import android.os.Handler
import android.os.Looper
import android.util.Log as AndroidLog
import android.view.View
import android.view.ViewGroup
import android.view.ViewStub
import android.widget.TextView
import com.xposed.wetypehook.wetype.settings.WeTypeSettings
import java.util.Collections
import java.util.WeakHashMap

/**
 * Slice 6：剪贴板搜索空态（无结果文案 + 恢复原生）。
 *
 * 挂点（S1 证据 15/34/35 行）：空视图开关 `S15.d1(boolean)`，
 * 空 ViewStub `empty_clipboard_view_vs` + 子 ID（`clipboard_empty_bg` /
 * `clipboard_empty_title`）。本片复用原生空视图显隐链路（`d1(true)` 语义），
 * 不自建布局；无结果时仅改标题区文案为含当前关键词的
 * “无与“xx”匹配的剪贴板”，关键词清空后连同列表一并恢复原生。
 *
 * 驱动方：S5 `WeTypeClipboardSearchFilter` 在主线程回放后调用
 * [onFilterResult]；开关关闭时调用 [onSwitchOff] 恢复。S2 引擎 /
 * S4 `setKeywordListener/clearSearch` 只引用不改名。
 */
internal object WeTypeClipboardSearchEmpty {

    private const val TAG = "WeTypeClipboardSearch"
    private const val WETYPE_ID_CLASS = "com.tencent.wetype.plugin.hld.s"

    private val mainHandler = Handler(Looper.getMainLooper())

    // 原生空标题缓存：首次覆写前记录，恢复时写回，避免残留搜索文案。
    private val originalTitles: MutableMap<TextView, CharSequence> =
        Collections.synchronizedMap(WeakHashMap<TextView, CharSequence>())

    /** S5 回放后调用（已在主线程；非主则抛回主线程）。 */
    fun onFilterResult(targets: List<Any>, keyword: String, filteredEmpty: Boolean) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { onFilterResult(targets, keyword, filteredEmpty) }
            return
        }
        try {
            if (!WeTypeSettings.isClipboardSearchEnabledXposed()) {
                restoreAll(targets)
                return
            }
            for (target in targets) {
                try {
                    handleTarget(target, keyword, filteredEmpty)
                } catch (t: Throwable) {
                    AndroidLog.e(TAG, "empty target failed: ${t.message}")
                }
            }
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "onFilterResult failed: ${t.message}")
        }
    }

    /** 开关关闭：取消搜索文案，一并恢复原生标题（不碰列表，列表由 S5 侧停排）。 */
    fun onSwitchOff(targets: List<Any>) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { onSwitchOff(targets) }
            return
        }
        try {
            restoreAll(targets)
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "onSwitchOff failed: ${t.message}")
        }
    }

    private fun handleTarget(target: Any, keyword: String, filteredEmpty: Boolean) {
        val view = target as? View ?: return
        val hostLoader = view.javaClass.classLoader ?: return
        val root = view.rootView as? ViewGroup ?: return
        val ids = resolveIds(hostLoader)
        if (keyword.isEmpty()) {
            // 空关键词恢复链路：文案恢复原生；显隐回到 d1 语义
            //（有数据则藏空视图，无数据则显原生空视图）。
            restoreTitle(root, ids.titleId)
            if (filteredEmpty) {
                ensureEmptyVisible(root, ids, withSearchText = null)
            } else {
                ensureEmptyHidden(root, ids)
            }
            return
        }
        if (filteredEmpty) {
            showNoResult(root, ids, keyword)
        } else {
            restoreTitle(root, ids.titleId)
            ensureEmptyHidden(root, ids)
        }
    }

    private fun showNoResult(root: ViewGroup, ids: ResolvedIds, keyword: String) {
        try {
            var title = ids.titleId?.let { root.findViewById<TextView>(it) }
            if (title == null) {
                inflateEmptyStub(root, ids.vsId)
                title = ids.titleId?.let { root.findViewById<TextView>(it) }
            }
            if (title == null) {
                AndroidLog.e(TAG, "empty title not found, skip no-result text")
                return
            }
            if (!originalTitles.containsKey(title)) {
                originalTitles[title] = title.text?.toString().orEmpty()
            }
            val kw = keyword.trim()
            title.text = "无与“" + kw + "”匹配的剪贴板"
            ensureEmptyVisible(root, ids, withSearchText = null)
            AndroidLog.i(TAG, "empty no-result shown")
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "showNoResult failed: ${t.message}")
        }
    }

    private fun restoreAll(targets: List<Any>) {
        for (target in targets) {
            try {
                val view = target as? View ?: continue
                val hostLoader = view.javaClass.classLoader ?: continue
                val root = view.rootView as? ViewGroup ?: continue
                restoreTitle(root, resolveIds(hostLoader).titleId)
            } catch (t: Throwable) {
                AndroidLog.e(TAG, "restoreAll target failed: ${t.message}")
            }
        }
    }

    private fun restoreTitle(root: ViewGroup, titleId: Int?) {
        try {
            if (titleId == null) return
            val title = root.findViewById<TextView>(titleId) ?: return
            val orig = originalTitles.remove(title) ?: return
            title.text = orig
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "restoreTitle failed: ${t.message}")
        }
    }

    /** d1(true) 语义：复用原生空视图（inflate + 可见），不自建布局。 */
    private fun ensureEmptyVisible(root: ViewGroup, ids: ResolvedIds, withSearchText: CharSequence?) {
        try {
            if (withSearchText != null) {
                ids.titleId?.let { root.findViewById<TextView>(it)?.text = withSearchText }
            }
            val bg = ids.bgId?.let { root.findViewById<View>(it) }
            if (bg != null) {
                setVisibleChain(bg)
                return
            }
            ids.titleId?.let { id ->
                val title = root.findViewById<View>(id) ?: return
                setVisibleChain(title)
            }
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "ensureEmptyVisible failed: ${t.message}")
        }
    }

    /** d1(false) 语义：有结果时藏起原生空视图。 */
    private fun ensureEmptyHidden(root: ViewGroup, ids: ResolvedIds) {
        try {
            val bg = ids.bgId?.let { root.findViewById<View>(it) }
            if (bg != null) {
                bg.visibility = View.GONE
                return
            }
            ids.titleId?.let { id ->
                val title = root.findViewById<View>(id) ?: return
                // 只藏空视图容器层：标题父链向上两层（logo 区/空根），不碰列表。
                var p = title.parent as? View
                repeat(2) {
                    if (p == null) return@repeat
                    p.visibility = View.GONE
                    p = p.parent as? View
                }
            }
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "ensureEmptyHidden failed: ${t.message}")
        }
    }

    private fun setVisibleChain(leaf: View) {
        var cur: View? = leaf
        repeat(3) {
            if (cur == null) return
            if (cur.visibility != View.VISIBLE) cur.visibility = View.VISIBLE
            cur = cur.parent as? View
        }
    }

    private fun inflateEmptyStub(root: ViewGroup, vsId: Int?) {
        try {
            if (vsId == null) return
            val stub = root.findViewById<ViewStub>(vsId) ?: return
            stub.inflate()
        } catch (t: Throwable) {
            // 已 inflate / 不可见等情况走日志，不抛（getMessage 可能为 null，兜底类名）。
            AndroidLog.e(TAG, "inflate empty stub failed: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    private data class ResolvedIds(val vsId: Int?, val titleId: Int?, val bgId: Int?)

    private fun resolveIds(hostLoader: ClassLoader): ResolvedIds {
        return try {
            val sClass = Class.forName(WETYPE_ID_CLASS, false, hostLoader)
            ResolvedIds(
                vsId = runCatching { sClass.getField("empty_clipboard_view_vs").getInt(null) }.getOrNull(),
                titleId = runCatching { sClass.getField("clipboard_empty_title").getInt(null) }.getOrNull(),
                bgId = runCatching { sClass.getField("clipboard_empty_bg").getInt(null) }.getOrNull()
            )
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "resolve empty IDs failed: ${t.message}")
            ResolvedIds(null, null, null)
        }
    }
}
