package com.xposed.wetypehook.wetype.hook

import android.os.Handler
import android.os.Looper
import android.text.Spannable
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.util.Log as AndroidLog
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.TextView
import com.xposed.wetypehook.wetype.clipboard.ClipboardSearchEngine
import com.xposed.wetypehook.wetype.clipboard.ClipboardSnippetLogic
import com.xposed.wetypehook.wetype.host.HostContractId
import com.xposed.wetypehook.wetype.host.WeTypeHostContracts
import com.xposed.wetypehook.wetype.host.pickHostMethod
import com.xposed.wetypehook.wetype.settings.WeTypeSettings
import com.xposed.wetypehook.xposed.hookAfter
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Slice 5：剪贴板搜索过滤渲染（防抖 + 后台搜 + 前50分页 + 高亮）。
 *
 * 挂点（S1 证据）：
 * - 核心过滤点 `ImeClipboardScrollView.setList(List<C>)`（证据 19 行）：只做全量快照，
 *   实际过滤在后台线程跑完后经原生链路 `setList(过滤后)` + `getListAdapter().y()` 回放
 *   （`y()` 刷新点见证据 23 行，`S15.a1()` 统一刷新见 13 行）。禁绕过 Adapter 自建列表。
 * - 高亮次选点 `Adapter v.onBindViewHolder`（证据 22 行）：后处理加 [ForegroundColorSpan]。
 * - 文本判定 `C.getType()==0` + `getContent()` 为搜索唯一范围（证据 24/25 行）；
 *   图片/文件/远程条目（type != 0）原样保留不参搜。
 * - S4 扩展点：只消费 `WeTypeClipboardSearchUi.setKeywordListener`（`clearSearch`
 *   由 Ui 侧清空搜索框文本并透传空关键词，本片经监听空关键词执行恢复）。
 * - S2 引擎冻结 API：只引用 `search(contents, keyword, limit=50)`。
 */
internal object WeTypeClipboardSearchFilter {

    private const val TAG = "WeTypeClipboardSearch"
    private const val SCROLLVIEW_CLASS =
        "com.tencent.wetype.plugin.hld.clipboard.ImeClipboardScrollView"
    private const val ADAPTER_CLASS = "com.tencent.wetype.plugin.hld.clipboard.v"

    private const val DEBOUNCE_MS = 300L
    private const val PAGE_LIMIT = 50

    // 高亮色来源：README 主题色 #FB7299（模块品牌强调色）。
    // 用前景色（文字色）而非底色，避免与深浅主题底色冲突；清空时移除同色 span 恢复原样。
    private const val HIGHLIGHT_COLOR = 0xFFFB7299.toInt()

    /** 片段起点相对命中往前留的字符数：留一点上下文，又保证命中落在第一行（半列一行约 7 个全角字）。 */
    private const val SNIPPET_LEAD_CHARS = 3

    /** 片段最多取多少字符：卡片只排几行，不必把几万字的原文整段塞给 `TextView`。 */
    private const val SNIPPET_MAX_CHARS = 400

    /** 片段验证没通过（命中被截掉）时最多再收窄几轮窗口，仍不行才退回紧凑片段。 */
    private const val SNIPPET_MAX_ROUNDS = 3

    /** 每轮收窄时在验证量到的可见字数上再扣多少字，留出换行时的误差。 */
    private const val SNIPPET_SHRINK_MARGIN = 3

    /** 卡片文本至少这么长才去找「它是某条完整内容的前缀」（宿主截断显示）：短文本是标签 / 按钮文字。 */
    private const val TRUNCATED_TEXT_MIN_CHARS = 256

    /** 命中片段前面补的省略号（片段不是从原文开头开始时才有）。 */
    private const val SNIPPET_ELLIPSIS = "…"

    /** 被换成「命中片段」的正文 → 完整原文，清空关键词或重新绑定时还原。 */
    private val snippetOriginals: MutableMap<TextView, CharSequence> =
        Collections.synchronizedMap(WeakHashMap())

    @Volatile
    private var hooked = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "WeTypeClipboardSearch").apply { isDaemon = true }
    }

    // 版本号 guards：新关键词/新数据到达即 +1，防后台乱序回写。
    private val version = AtomicLong(0L)

    // 原生 setList 回放时的重入 guard：我们自己调 setList 时跳过快照与重排，避免循环。
    private val applyingFilter = AtomicBoolean(false)

    @Volatile
    private var currentKeyword = ""

    /** 最近一次回放给列表的文本条目的完整内容（只在关键词非空时有），用来找回被宿主截断的卡片原文。 */
    @Volatile
    private var shownContents: List<String> = emptyList()

    /** 供图片注入器判断是否正在回放过滤结果（避免把图片塞回搜索结果）。 */
    internal fun isReplaying(): Boolean = applyingFilter.get()

    /** 供图片注入器判断当前搜索态。 */
    internal fun currentKeywordValue(): String = currentKeyword

    private val lock = Any()
    private val fullItems: MutableList<Any> = ArrayList()
    private val scrollViews: MutableSet<Any> =
        Collections.newSetFromMap(WeakHashMap<Any, Boolean>())

    // C.getType()/getContent() 反射缓存（同类加载器下同类，main/后台线程只读调用）。
    @Volatile
    private var getTypeMethod: Method? = null
    @Volatile
    private var getContentMethod: Method? = null

    private val debounceTask = Runnable {
        try {
            val v = version.get()
            val kw = currentKeyword
            val snapshot: List<Any>
            val targets: List<Any>
            synchronized(lock) {
                snapshot = ArrayList(fullItems)
                targets = ArrayList(scrollViews)
            }
            if (targets.isEmpty()) return@Runnable
            // S6：快照为空时仍走后台/回放，使空态文案能覆盖原生空标题；
            // 后台对空快照直接产出空列表，开销可忽略。
            executor.submit { runFilterInBackground(v, kw, snapshot, targets) }
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "debounce dispatch failed: ${t.message}")
        }
    }

    fun install(classLoader: ClassLoader) {
        if (hooked) return
        try {
            hookSetList(classLoader)
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "hook setList failed: ${t.message}")
        }
        try {
            hookBind(classLoader)
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "hook onBindViewHolder failed: ${t.message}")
        }
        try {
            // S4 扩展点消费：关键词变化只做防抖调度，实际搜索在后台线程。
            WeTypeClipboardSearchUi.setKeywordListener { kw -> onKeyword(kw) }
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "register keyword listener failed: ${t.message}")
        }
        hooked = true
        AndroidLog.i(TAG, "search filter installed (debounce=${DEBOUNCE_MS}ms, limit=$PAGE_LIMIT)")
    }

    // ---- 关键词入口（主线程防抖） ----

    private fun onKeyword(raw: String?) {
        try {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                mainHandler.post { onKeyword(raw) }
                return
            }
            if (!WeTypeSettings.isClipboardSearchEnabledXposed()) {
                // 开关关闭：取消 pending 并抬升版本作废在途后台任务，不碰列表；
                // S6：空态文案一并恢复原生。
                currentKeyword = ""
                version.incrementAndGet()
                mainHandler.removeCallbacks(debounceTask)
                try {
                    val targets: List<Any> = synchronized(lock) { ArrayList(scrollViews) }
                    WeTypeClipboardSearchEmpty.onSwitchOff(targets)
                } catch (t: Throwable) {
                    AndroidLog.e(TAG, "empty restore on switch-off failed: ${t.message}")
                }
                return
            }
            currentKeyword = raw.orEmpty()
            scheduleLocked()
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "onKeyword failed: ${t.message}")
        }
    }

    private fun onNativeSetList(scrollView: Any, listArg: Any?) {
        try {
            if (applyingFilter.get()) return
            if (!WeTypeSettings.isClipboardSearchEnabledXposed()) return
            val list = listArg as? List<*> ?: return
            val runSchedule: Boolean
            synchronized(lock) {
                scrollViews.add(scrollView)
                // 空关键词时仍刷新全量快照（恢复/首挂载路径），不额外调度。
                fullItems.clear()
                for (item in list) {
                    if (item != null) fullItems.add(item)
                }
                // 宿主列表默认排除 type==1 图片；合入模块注入缓存，保证搜索恢复时可见。
                WeTypeClipboardImageList.appendCachedUnique(fullItems)
                runSchedule = currentKeyword.isNotEmpty()
            }
            WeTypeClipboardImageList.onNativeSetList(scrollView, list.filterNotNull())
            // 主线程调度；hookAfter 大概率已在主线程，非主则抛回主线程。
            if (Looper.myLooper() == Looper.getMainLooper()) {
                if (runSchedule) scheduleLocked()
            } else {
                mainHandler.post {
                    try {
                        if (runSchedule &&
                            WeTypeSettings.isClipboardSearchEnabledXposed() &&
                            !applyingFilter.get()
                        ) {
                            scheduleLocked()
                        }
                    } catch (t: Throwable) {
                        AndroidLog.e(TAG, "native setList reschedule failed: ${t.message}")
                    }
                }
            }
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "onNativeSetList failed: ${t.message}")
        }
    }

    private fun scheduleLocked() {
        try {
            version.incrementAndGet()
            mainHandler.removeCallbacks(debounceTask)
            mainHandler.postDelayed(debounceTask, DEBOUNCE_MS)
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "schedule filter failed: ${t.message}")
        }
    }

    // ---- 后台搜索（禁碰 View） ----

    private fun runFilterInBackground(
        v: Long,
        keyword: String,
        snapshot: List<Any>,
        targets: List<Any>
    ) {
        try {
            val filtered: List<Any> = if (keyword.isEmpty()) {
                ArrayList(snapshot)
            } else {
                filterSnapshot(snapshot, keyword)
            }
            // 回放出去的文本条目的完整内容：卡片里的文字被宿主截断时，要靠它找回命中。
            val contents: List<String> = if (keyword.isEmpty()) emptyList() else filtered.mapNotNull { item ->
                try {
                    if (getItemType(item) == 0L) getItemContent(item) else null
                } catch (_: Throwable) {
                    null
                }
            }
            mainHandler.post { applyResultOnMain(v, keyword, targets, filtered, contents) }
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "background filter failed: ${t.message}")
        }
    }

    private fun filterSnapshot(snapshot: List<Any>, keyword: String): List<Any> {
        // 文本条目逐条抽 content 走引擎；非文本原样保留不参搜（S1 证据 B:988 语义）。
        // content 读不到的条目判不准→保留（宁可多留不断数据）。
        val textContents = ArrayList<String>()
        val textToOrig = ArrayList<Int>()
        val unknownContent = HashSet<Int>()
        for (i in snapshot.indices) {
            val item = snapshot[i]
            try {
                if (getItemType(item) != 0L) continue
                val content = getItemContent(item)
                if (content == null) {
                    unknownContent.add(i)
                    continue
                }
                textContents.add(content)
                textToOrig.add(i)
            } catch (t: Throwable) {
                AndroidLog.e(TAG, "snapshot item read failed: ${t.message}")
            }
        }
        val matchedOrig = HashSet<Int>()
        try {
            val hits = ClipboardSearchEngine.search(textContents, keyword, PAGE_LIMIT)
            for (hit in hits) {
                val pos = hit.index
                if (pos in textToOrig.indices) matchedOrig.add(textToOrig[pos])
            }
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "engine search failed: ${t.message}")
            return ArrayList(snapshot)
        }
        val out = ArrayList<Any>(snapshot.size.coerceAtMost(PAGE_LIMIT + 16))
        for (i in snapshot.indices) {
            val item = snapshot[i]
            try {
                val type = getItemType(item)
                if (type != 0L) {
                    // ADR-0002：搜索关键词非空时图片条目全部隐藏；其余非文本保持原样。
                    if (type != 1L) out.add(item)
                } else if (matchedOrig.contains(i) || unknownContent.contains(i)) {
                    out.add(item)
                }
            } catch (t: Throwable) {
                AndroidLog.e(TAG, "filter item failed: ${t.message}")
                out.add(item)
            }
        }
        return out
    }

    // ---- 主线程回放（原生链路刷新） ----

    private fun applyResultOnMain(
        v: Long,
        keyword: String,
        targets: List<Any>,
        filtered: List<Any>,
        contents: List<String>
    ) {
        try {
            if (v != version.get()) return
            if (!WeTypeSettings.isClipboardSearchEnabledXposed()) return
            if (targets.isEmpty()) return
            // 必须在回放之前换好：回放会立刻触发重绑，绑定后的高亮要用它。
            shownContents = contents
            for (scrollView in targets) {
                try {
                    applyingFilter.set(true)
                    invokeSetList(scrollView, filtered)
                    invokeAdapterRefresh(scrollView)
                    // S8：宿主 y() 只重算折叠分区并 notifyItemChanged(0)，可视区其余行
                    // 保持旧绑定（滑出屏幕回收后才重绑，表现为第 2/3 行短暂显示旧内容）。
                    // 这里补一次全量 notifyDataSetChanged 强制重绑所有行。
                    invokeAdapterFullRebind(scrollView)
                } catch (t: Throwable) {
                    AndroidLog.e(TAG, "apply filtered list failed: ${t.message}")
                } finally {
                    applyingFilter.set(false)
                }
            }
            // S6：空态（无结果文案/恢复原生）复用原生空视图链路，主线程 UI。
            try {
                WeTypeClipboardSearchEmpty.onFilterResult(targets, keyword, filtered.isEmpty())
            } catch (t: Throwable) {
                AndroidLog.e(TAG, "empty state dispatch failed: ${t.message}")
            }
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "applyResult failed: ${t.message}")
        }
    }

    private fun invokeSetList(scrollView: Any, filtered: List<Any>) {
        try {
            val method = scrollView.javaClass.declaredMethods.firstOrNull { m ->
                m.name == "setList" &&
                    m.parameterTypes.size == 1 &&
                    List::class.java.isAssignableFrom(m.parameterTypes[0])
            } ?: run {
                AndroidLog.e(TAG, "setList(List) not found on ${scrollView.javaClass.name}")
                return
            }
            method.isAccessible = true
            // 原生 setList 期望 ArrayList<C>；传 ArrayList 保持与原生一致。
            method.invoke(scrollView, ArrayList(filtered))
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "invoke setList failed: ${t.message}")
        }
    }

    private fun invokeAdapterRefresh(scrollView: Any) {
        try {
            val adapter = resolveAdapter(scrollView) ?: return
            val refresh = pickHostMethod(adapter.javaClass, "y") { m ->
                m.parameterTypes.isEmpty() && m.returnType == Void.TYPE
            } ?: run {
                AndroidLog.e(TAG, "adapter y() not found on ${adapter.javaClass.name}")
                return
            }
            refresh.isAccessible = true
            refresh.invoke(adapter)
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "adapter refresh y() failed: ${t.message}")
        }
    }

    /** 全量重绑：宿主 `y()` 不覆盖可视区旧行，需显式 `notifyDataSetChanged`。 */
    private fun invokeAdapterFullRebind(scrollView: Any) {
        try {
            val adapter = resolveAdapter(scrollView) ?: return
            var clazz: Class<*>? = adapter.javaClass
            while (clazz != null && clazz != Any::class.java) {
                for (method in clazz.declaredMethods) {
                    if (method.name == "notifyDataSetChanged" && method.parameterTypes.isEmpty()) {
                        method.isAccessible = true
                        method.invoke(adapter)
                        return
                    }
                }
                clazz = clazz.superclass
            }
            AndroidLog.e(TAG, "notifyDataSetChanged not found on ${adapter.javaClass.name}")
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "adapter full rebind failed: ${t.message}")
        }
    }

    private fun resolveAdapter(scrollView: Any): Any? = try {
        val getter = scrollView.javaClass.declaredMethods.firstOrNull { m ->
            m.name == "getListAdapter" && m.parameterTypes.isEmpty()
        }
        if (getter == null) {
            AndroidLog.e(TAG, "getListAdapter() not found")
            null
        } else {
            getter.isAccessible = true
            getter.invoke(scrollView) ?: run {
                AndroidLog.e(TAG, "getListAdapter() returned null")
                null
            }
        }
    } catch (t: Throwable) {
        AndroidLog.e(TAG, "resolve adapter failed: ${t.message}")
        null
    }

    // ---- 高亮（onBindViewHolder 后处理，主线程碰 View） ----

    private fun hookSetList(classLoader: ClassLoader) {
        val scrollClass = resolveClipboardScrollView(classLoader)
            ?: throw ClassNotFoundException(SCROLLVIEW_CLASS)
        var count = 0
        for (method in scrollClass.declaredMethods) {
            if (method.name != "setList") continue
            if (method.parameterTypes.size != 1) continue
            if (!List::class.java.isAssignableFrom(method.parameterTypes[0])) continue
            try {
                method.isAccessible = true
                method.hookAfter { param ->
                    try {
                        val arg = param.args.getOrNull(0)
                        onNativeSetList(param.thisObject, arg)
                    } catch (t: Throwable) {
                        AndroidLog.e(TAG, "setList hook dispatch failed: ${t.message}")
                    }
                }
                count++
            } catch (t: Throwable) {
                AndroidLog.e(TAG, "hook setList failed: ${t.message}")
            }
        }
        AndroidLog.i(TAG, "hooked setList overloads: $count")
    }

    private fun hookBind(classLoader: ClassLoader) {
        val adapterClass = resolveClipboardAdapter(classLoader) ?: run {
            AndroidLog.e(TAG, "clipboard adapter class not found, skip highlight hook")
            return
        }
        var count = 0
        for (method in adapterClass.declaredMethods) {
            if (method.name != "onBindViewHolder") continue
            if (method.parameterTypes.size != 2) continue
            try {
                method.isAccessible = true
                method.hookAfter { param ->
                    try {
                        val holder = param.args.getOrNull(0) ?: return@hookAfter
                        // 图片条目渲染与搜索开关无关；搜索高亮仍受开关控制。
                        WeTypeClipboardImageEntries.onBind(holder)
                        if (WeTypeSettings.isClipboardSearchEnabledXposed()) {
                            applyHighlightToHolder(holder, currentKeyword)
                        }
                    } catch (t: Throwable) {
                        AndroidLog.e(TAG, "bind highlight dispatch failed: ${t.message}")
                    }
                }
                count++
            } catch (t: Throwable) {
                AndroidLog.e(TAG, "hook onBindViewHolder failed: ${t.message}")
            }
        }
        AndroidLog.i(TAG, "hooked onBindViewHolder overloads: $count")
    }

    private fun applyHighlightToHolder(holder: Any, keyword: String) {
        try {
            val itemView = getHolderItemView(holder) ?: return
            forEachTextView(itemView, keyword)
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "applyHighlight failed: ${t.message}")
        }
    }

    private fun getHolderItemView(holder: Any): View? {
        try {
            var clazz: Class<*>? = holder.javaClass
            while (clazz != null && clazz != Any::class.java) {
                for (field in clazz.declaredFields) {
                    try {
                        if (!View::class.java.isAssignableFrom(field.type)) continue
                        field.isAccessible = true
                        val view = field.get(holder) as? View
                        if (view != null) return view
                    } catch (_: Throwable) {
                        continue
                    }
                }
                clazz = clazz.superclass
            }
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "get holder itemView failed: ${t.message}")
        }
        return null
    }

    private fun forEachTextView(view: View, keyword: String) {
        try {
            if (view is TextView) {
                highlightTextView(view, keyword)
                return
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    try {
                        forEachTextView(view.getChildAt(i), keyword)
                    } catch (t: Throwable) {
                        AndroidLog.e(TAG, "traverse child failed: ${t.message}")
                    }
                }
            }
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "forEachTextView failed: ${t.message}")
        }
    }

    private fun highlightTextView(tv: TextView, keyword: String) {
        try {
            // 上一轮可能把正文换成了「命中片段」，先还原成完整原文再重算。
            restoreSnippet(tv)
            pendingSnippets.remove(tv)
            // 先清除上一轮同色高亮，恢复原样后再按当前关键词重打。
            clearOurSpans(tv)
            if (keyword.isEmpty()) return
            val original = tv.text ?: return
            val text = original.toString()
            if (text.isEmpty()) return
            // 宿主只把正文的前一段放进卡片（实机：13415 字的条目卡片里只有前 3000 字），命中落在
            // 被截掉的部分时，卡片文本里压根没有它 —— 只搜卡片文本就永远标不出来。
            val source = resolveFullContent(text)
            val ranges = try {
                val hits = ClipboardSearchEngine.search(listOf(source), keyword, 1)
                if (hits.isEmpty()) emptyList() else hits[0].ranges
            } catch (t: Throwable) {
                AndroidLog.e(TAG, "highlight search failed: ${t.message}")
                return
            }
            if (ranges.isEmpty()) return
            val firstStart = colorRanges(tv, original, ranges)
            if (firstStart >= 0) scrollContentToHighlight(tv, firstStart)
            scheduleSnippetIfMatchOffscreen(tv, original, source, ranges)
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "highlightTextView failed: ${t.message}")
        }
    }

    /**
     * 卡片文本是某条完整内容的前缀（宿主截断了显示）时返回那条完整内容，否则就是卡片文本本身。
     *
     * 只认够长的文本：药丸标签、操作栏按钮这类几个字的控件，其文字完全可能恰好是某条内容的开头，
     * 认成前缀会把它们的文字换成别的条目的内容。
     */
    private fun resolveFullContent(text: String): String {
        if (text.length < TRUNCATED_TEXT_MIN_CHARS) return text
        for (content in shownContents) {
            if (content.length > text.length && content.startsWith(text)) return content
        }
        return text
    }

    /** 把 [ranges] 逐个染上高亮色并写回，返回首个命中区间的起点（没染上返回 -1）。 */
    private fun colorRanges(tv: TextView, source: CharSequence, ranges: List<IntRange>): Int {
        val spannable = SpannableString(source)
        var firstStart = -1
        for (range in ranges) {
            try {
                val start = range.first.coerceIn(0, spannable.length)
                val endExclusive = (range.last + 1).coerceIn(0, spannable.length)
                if (start >= endExclusive) continue
                spannable.setSpan(
                    ForegroundColorSpan(HIGHLIGHT_COLOR),
                    start,
                    endExclusive,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                if (firstStart < 0) firstStart = start
            } catch (t: Throwable) {
                AndroidLog.e(TAG, "apply span range failed: ${t.message}")
            }
        }
        // 必须是 NORMAL：SPANNABLE 会让 TextView 改用 DynamicLayout，它不按 maxLines 封顶、也没有省略号，
        // 宿主瀑布流的 rebuildTextLayout（判据是 lineCount > maxLines）就永远收敛不了，实机表现为一张长卡片
        // 每帧都在重新排版（空闲 4 秒 255 次）。NORMAL 会把 Spanned 包成 SpannedString，区间保留、走 StaticLayout。
        tv.setText(spannable, TextView.BufferType.NORMAL)
        return firstStart
    }

    /**
     * 「命中片段」待办。[original] 是卡片当前文本，[source] 是完整内容。
     * [applied] 非空表示片段已经换上去了，正在等它自己的排版来验证命中看得见（[appliedMatchEnd] 是命中在片段里的终点）。
     */
    private class PendingSnippet(
        val original: CharSequence,
        val source: String,
        val ranges: List<IntRange>,
        val applied: CharSequence? = null,
        val appliedMatchEnd: Int = -1,
        val round: Int = 0
    )

    /** 还在等排版、可能要换成命中片段的卡片正文。重新绑定或清空关键词时作废。 */
    private val pendingSnippets: MutableMap<TextView, PendingSnippet> =
        Collections.synchronizedMap(WeakHashMap())

    /** 已挂上排版监听的正文（监听只挂一次，`addOnLayoutChangeListener` 自己不去重）。 */
    private val snippetWatched: MutableSet<TextView> =
        Collections.newSetFromMap(Collections.synchronizedMap(WeakHashMap<TextView, Boolean>()))

    private val snippetLayoutListener = View.OnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
        (v as? TextView)?.let { evaluateSnippet(it) }
    }

    /**
     * 命中落在卡片可见行之外时，把正文换成「命中附近的片段」，前面加省略号。
     *
     * 瀑布流会把正文按「卡片最大高度」压成几行（`maxLines` + `ellipsize`），而搜索是按**整条内容**
     * 匹配的 —— 只搜两个拉丁字母时走首字母拼音路径（例如「在吗」「怎么」的 z…m），命中的汉字可能在
     * 很后面。这时 span 确实打上去了，却落在被 `maxLines` 裁掉的那几行里，用户看到的就是「这条没标红」。
     *
     * **判断必须挂在排版上，不能算一次就收工**：绑定当下的排版是上一条内容留下的，`maxLines` 随后又会被
     * 估算、被布局后纠偏改小（而且改小之后排版不一定重建），宽度也要等条目量到才定。实机第一版是 `post`
     * 之后算一次，那一刻 `maxLines=4` 而排版还有 13 行、没有省略号，于是「看不见」被判成「看得见」，
     * 片段一次都没触发过。现在挂一个排版监听，每次排版后重判，直到换成片段或重新绑定。
     *
     * 片段按原卡片能显示的字数取窗口、命中放中间（[ClipboardSnippetLogic.snippetWindowStart]），
     * 换上去后再用片段自己的排版验证：命中被截掉就按量到的可见字数收窄窗口重来（最多 [SNIPPET_MAX_ROUNDS] 轮），
     * 实在不行才退回紧凑片段（命中必在第一行）。
     */
    private fun scheduleSnippetIfMatchOffscreen(
        tv: TextView,
        original: CharSequence,
        source: String,
        ranges: List<IntRange>
    ) {
        if (ranges.first().first <= 0) return
        pendingSnippets[tv] = PendingSnippet(original, source, ranges)
        if (snippetWatched.add(tv)) tv.addOnLayoutChangeListener(snippetLayoutListener)
        tv.post { evaluateSnippet(tv) }
    }

    private fun evaluateSnippet(tv: TextView) {
        try {
            val pending = pendingSnippets[tv] ?: return
            val applied = pending.applied
            // 这一帧已经被重绑/改写，不是我们写进去的那份文本就作废。
            if (!TextUtils.equals(tv.text, applied ?: pending.original)) {
                pendingSnippets.remove(tv)
                return
            }
            val layout = tv.layout
            // 排版还是别的文本的（长度对不上）或宽度没量出来，等下一次排版。
            val expectedLength = (applied ?: pending.original).length
            if (layout == null || layout.text.length != expectedLength || tv.width <= 0 || layout.lineCount <= 0) return
            val visibleEnd = visibleEnd(layout, tv.maxLines)
            if (applied != null) {
                // 片段自己的排版出来了：命中还被截掉（片段里的字比原文宽）就退回紧凑片段，只验证这一次。
                pendingSnippets.remove(tv)
                if (ClipboardSnippetLogic.isMatchHidden(pending.appliedMatchEnd, visibleEnd)) {
                    // 片段里的字比原文宽，窗口取大了：按这次量到的可见字数收窄再来，别一下子退成只剩一行。
                    if (pending.round < SNIPPET_MAX_ROUNDS) {
                        applySnippet(
                            tv, pending, (visibleEnd - SNIPPET_SHRINK_MARGIN).coerceAtLeast(1),
                            compact = false, round = pending.round + 1
                        )
                    } else {
                        applySnippet(tv, pending, visibleEnd = 0, compact = true, round = pending.round)
                    }
                }
                return
            }
            val first = pending.ranges.first()
            // 命中整个落在卡片文本之外（宿主截断了）一定看不见；否则按排版判断。
            val hidden = first.last >= pending.original.length ||
                ClipboardSnippetLogic.isMatchHidden(first.last, visibleEnd)
            if (hidden) applySnippet(tv, pending, visibleEnd, compact = false, round = 0)
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "evaluate highlight snippet failed: ${t.message}")
        }
    }

    /** 把排版量出来的数交给纯算术：卡片上看得见的文字到哪个下标为止，规则见 [ClipboardSnippetLogic.visibleEnd]。 */
    private fun visibleEnd(layout: android.text.Layout, maxLines: Int): Int {
        val last = layout.lineCount - 1
        val capLine = (maxLines - 1).coerceIn(0, last)
        return ClipboardSnippetLogic.visibleEnd(
            lineCount = layout.lineCount,
            maxLines = maxLines,
            capLineEnd = layout.getLineEnd(capLine),
            lastLineStart = layout.getLineStart(last),
            lastLineEnd = layout.getLineEnd(last),
            ellipsisStart = layout.getEllipsisStart(last),
            ellipsisCount = layout.getEllipsisCount(last)
        )
    }

    /**
     * 把正文换成命中附近的片段（前面补省略号），原文留在 [snippetOriginals] 里供还原。
     * [visibleEnd] 是原卡片能显示的字数，用来决定窗口从哪里开始；[compact] 为真时退回紧凑片段。
     */
    private fun applySnippet(
        tv: TextView,
        pending: PendingSnippet,
        visibleEnd: Int,
        compact: Boolean,
        round: Int
    ) {
        val first = pending.ranges.first()
        val compactStart = ClipboardSnippetLogic.snippetStart(first.first, SNIPPET_LEAD_CHARS)
        var start = if (compact) {
            compactStart
        } else {
            ClipboardSnippetLogic.snippetWindowStart(first.first, pending.source.length, visibleEnd)
        }
        // 窗口算出来贴着开头（等于没换）时，至少用紧凑片段，否则命中仍在看不见的位置。
        if (start <= 0) start = compactStart
        if (start <= 0) {
            pendingSnippets.remove(tv)
            return
        }
        val endExclusive = minOf(pending.source.length, start + SNIPPET_MAX_CHARS)
        val head = SNIPPET_ELLIPSIS
        val snippet = SpannableStringBuilder().append(head).append(pending.source, start, endExclusive)
        for (range in pending.ranges) {
            val s0 = (range.first - start).coerceAtLeast(0) + head.length
            val s1 = (range.last + 1 - start).coerceAtMost(endExclusive - start) + head.length
            if (range.last + 1 <= start || range.first >= endExclusive || s0 >= s1) continue
            snippet.setSpan(ForegroundColorSpan(HIGHLIGHT_COLOR), s0, s1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        snippetOriginals[tv] = pending.original
        // 紧凑片段本身就保证命中在第一行，不用再验证。
        if (compact) {
            pendingSnippets.remove(tv)
        } else {
            pendingSnippets[tv] = PendingSnippet(
                pending.original, pending.source, pending.ranges,
                applied = snippet, appliedMatchEnd = first.last - start + head.length, round = round
            )
        }
        tv.setText(snippet, TextView.BufferType.NORMAL)
    }

    /** 把 [snippetOriginals] 里存着的完整原文还回去（正文没被我们换过就什么都不做）。 */
    private fun restoreSnippet(tv: TextView) {
        val original = snippetOriginals.remove(tv) ?: return
        try {
            if (TextUtils.equals(tv.text, original)) return
            tv.setText(original, TextView.BufferType.NORMAL)
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "restore snippet failed: ${t.message}")
        }
    }

    /**
     * 命中片段在可视区之外时，把内容行所在的横向滚动容器滚到首个高亮处，
     * 让长文本的匹配结果直接落在这一行里（原生只会在绑定时复位到 0）。
     */
    private fun scrollContentToHighlight(tv: TextView, offset: Int) {
        try {
            val scroll = tv.parent as? HorizontalScrollView ?: return
            // 布局用的是 transformation 后的文本（SingleLineTransformationMethod 会把
            // \r/\n 换成 \uFEFF，且保持 1:1 长度），必须按变换后的内容与 layout.text 比较。
            val expectedText = runCatching {
                tv.transformationMethod?.getTransformation(tv.text, tv)
            }.getOrNull() ?: tv.text
            scrollToHighlightAttempt(tv, scroll, offset, expectedText, 0)
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "scroll content to highlight failed: ${t.message}")
        }
    }

    private fun scrollToHighlightAttempt(
        tv: TextView,
        scroll: HorizontalScrollView,
        offset: Int,
        expectedText: CharSequence?,
        attempt: Int
    ) {
        if (attempt > 10) return
        try {
            tv.postDelayed({
                try {
                    val layout = tv.layout
                    if (layout != null && TextUtils.equals(layout.text, expectedText) &&
                        tv.width > 0 && scroll.width > 0
                    ) {
                        scrollViewToOffset(tv, scroll, layout, offset)
                    } else {
                        scrollToHighlightAttempt(tv, scroll, offset, expectedText, attempt + 1)
                    }
                } catch (t: Throwable) {
                    AndroidLog.e(TAG, "scroll content to highlight failed: ${t.message}")
                }
            }, 16L)
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "schedule scroll to highlight failed: ${t.message}")
        }
    }

    private fun scrollViewToOffset(
        tv: TextView,
        scroll: HorizontalScrollView,
        layout: android.text.Layout,
        offset: Int
    ) {
        if (offset < 0 || offset >= layout.text.length) return
        val viewport = scroll.width - scroll.paddingLeft - scroll.paddingRight
        if (viewport <= 0) return
        val maxScroll = (tv.width + scroll.paddingLeft + scroll.paddingRight - scroll.width)
            .coerceAtLeast(0)
        if (maxScroll <= 0) return
        val x = tv.left + tv.compoundPaddingLeft + layout.getPrimaryHorizontal(offset)
        val lead = (tv.resources.displayMetrics.density * 12f).toInt().coerceAtMost(viewport / 3)
        val target = (x - lead).toInt().coerceIn(0, maxScroll)
        if (scroll.scrollX != target) {
            scroll.scrollTo(target, 0)
            AndroidLog.i(TAG, "scroll to highlight: offset=$offset x=${x.toInt()} target=$target max=$maxScroll")
        }
    }

    private fun clearOurSpans(tv: TextView) {
        try {
            val spanned = tv.text as? Spanned ?: return
            val spans = spanned.getSpans(0, spanned.length, ForegroundColorSpan::class.java)
            if (spans.isEmpty()) return
            // tv.text 可能是不可变 Spanned：转可变后再清，避免直接 remove 抛异常。
            val editable = SpannableString(spanned)
            var changed = false
            for (span in spans) {
                try {
                    if (span.foregroundColor != HIGHLIGHT_COLOR) continue
                    editable.removeSpan(span)
                    changed = true
                } catch (t: Throwable) {
                    AndroidLog.e(TAG, "remove span failed: ${t.message}")
                }
            }
            if (changed) tv.setText(editable, TextView.BufferType.NORMAL)
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "clear spans failed: ${t.message}")
        }
    }

    // ---- C 条目反射读写（数据面，后台/主线程均可调，不碰 View） ----

    private fun getItemType(item: Any): Long {
        try {
            var m = getTypeMethod
            if (m == null || m.declaringClass != item.javaClass) {
                // S7o：装机版 C.getType 改名（NoSuchMethod 导致全保留、过滤无事
                // 发生）。jadx 354 批注 `renamed from: q` + 真机普查 q():long=0
                // 在文本条目上吻合 type==0，三重印证，直接绑 q。
                m = pickHostMethod(item.javaClass, "q") { c ->
                    c.parameterTypes.isEmpty() &&
                        (c.returnType == Long::class.javaPrimitiveType ||
                            c.returnType == Long::class.java ||
                            c.returnType == Int::class.javaPrimitiveType ||
                            c.returnType == Integer::class.java)
                }
                if (m == null) {
                    AndroidLog.e(TAG, "C.q() not found on ${item.javaClass.name}, keep item")
                    return 1L
                }
                m.isAccessible = true
                getTypeMethod = m
                AndroidLog.i(TAG, "C type accessor bound: q() on ${item.javaClass.name}")
            }
            val v = m.invoke(item)
            return when (v) {
                is Long -> v
                is Int -> v.toLong()
                is Number -> v.toLong()
                else -> 1L
            }
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "getType failed: ${t.message}")
            // 读不到类型时按非文本保留，避免误删数据。
            return 1L
        }
    }

    private fun getItemContent(item: Any): String? {
        try {
            var m = getContentMethod
            if (m == null || m.declaringClass != item.javaClass) {
                // S7o：同上，content 绑 a()（普查 a() 即条目正文，三重印证）。
                // 绑不上返回 null，调用方保留条目。
                m = pickHostMethod(item.javaClass, "a") { c ->
                    c.parameterTypes.isEmpty() &&
                        c.returnType == String::class.java
                }
                if (m == null) {
                    AndroidLog.e(TAG, "C.a() not found on ${item.javaClass.name}, keep item")
                    return null
                }
                m.isAccessible = true
                getContentMethod = m
                AndroidLog.i(TAG, "C content accessor bound: a() on ${item.javaClass.name}")
            }
            return m.invoke(item) as? String
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "getContent failed: ${t.message}")
            return null
        }
    }

    /** 契约优先、写死的短名兜底。契约解析出来的 Class 自带宿主 ClassLoader。 */
    private fun resolveClipboardScrollView(classLoader: ClassLoader?): Class<*>? =
        runCatching { WeTypeHostContracts.classOf(HostContractId.CLIPBOARD_SCROLLVIEW) }.getOrNull()
            ?: classLoader?.let {
                runCatching { Class.forName(SCROLLVIEW_CLASS, false, it) }.getOrNull()
            }

    private fun resolveClipboardAdapter(classLoader: ClassLoader?): Class<*>? =
        runCatching { WeTypeHostContracts.classOf(HostContractId.CLIPBOARD_ADAPTER) }.getOrNull()
            ?: classLoader?.let {
                runCatching { Class.forName(ADAPTER_CLASS, false, it) }.getOrNull()
            }
}
