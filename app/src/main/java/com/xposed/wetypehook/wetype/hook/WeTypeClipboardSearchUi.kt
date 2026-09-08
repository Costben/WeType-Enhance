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
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import com.xposed.wetypehook.wetype.settings.WeTypeSettings
import com.xposed.wetypehook.xposed.hookAfter
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.roundToInt

/**
 * Slice 4：剪贴板页面搜索 UI 挂载（只挂载，不做过滤）。
 *
 * 挂点（S1 证据）：剪贴板页面宿主
 * `com.tencent.wetype.plugin.hld.keyboard.S15CustomPhraseAndClipboardKeyboard`，
 * 标题栏容器 `logo_container_rl` 右端为首选挂位，`custom_btn`/`custom2_btn`
 * 为备选锚点（只读定位，不改原生按钮行为）。
 * 实际列表过滤/高亮/防抖/分页是 S5 的事，本片仅暴露
 * [setKeywordListener]/[clearSearch] 扩展点。
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

    // dp 常量（运行时经 TypedValue 换算为 px，禁写死 px）。
    private const val BTN_BOX_DP = 40f
    private const val BTN_ICON_PADDING_DP = 8f
    private const val BTN_MARGIN_DP = 4f
    private const val BOX_MARGIN_H_DP = 12f
    private const val BOX_MARGIN_V_DP = 4f
    // S6：X 一键清空按钮尺寸（运行时经 TypedValue 换算为 px，禁写死 px）。
    private const val CLEAR_BOX_DP = 32f
    private const val CLEAR_ICON_PADDING_DP = 8f

    /** S5 消费：关键词变化回调（S4 只透传，不做过滤）。 */
    @Volatile
    private var keywordListenerImpl: ((String) -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val trackedBoxes: MutableSet<EditText> =
        Collections.newSetFromMap(WeakHashMap<EditText, Boolean>())

    @Volatile
    private var hooked = false

    /** S5 扩展点：注册关键词监听。 */
    fun setKeywordListener(listener: ((String) -> Unit)?) {
        keywordListenerImpl = listener
    }

    /**
     * S5 扩展点：清空关键词。只清搜索框文本并透传空关键词，
     * 恢复原列表的实际过滤动作由 S5 监听空关键词后执行。
     */
    fun clearSearch() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            clearSearchOnMain()
        } else {
            mainHandler.post { clearSearchOnMain() }
        }
    }

    fun install(classLoader: ClassLoader) {
        if (hooked) return
        try {
            val s15 = runCatching { Class.forName(S15_CLASS, false, classLoader) }.getOrNull()
            if (s15 == null) {
                AndroidLog.e(TAG, "S15 host class not found, skip search UI mount")
                return
            }
            // 入页/切页/刷新/空视图等关键方法之后触发挂载（按名匹配全部重载，不依赖签名）。
            val triggerNames = setOf("f1", "Y0", "i0", "a1", "d1", "O")
            var count = 0
            for (method in s15.declaredMethods) {
                if (method.name !in triggerNames) continue
                try {
                    method.isAccessible = true
                    method.hookAfter { param ->
                        try {
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
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "install search UI failed: ${t.message}")
        }
    }

    private fun ensureSearchUi(keyboardObj: Any) {
        try {
            if (!WeTypeSettings.isClipboardSearchEnabledXposed()) {
                removeResidualOnMain(keyboardObj)
                return
            }
            val anchor = findHostView(keyboardObj) ?: run {
                AndroidLog.e(TAG, "S15 host view not found, skip mount")
                return
            }
            if (Looper.myLooper() == Looper.getMainLooper()) {
                mountOnMain(anchor)
            } else {
                anchor.post { mountOnMain(anchor) }
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
                        (container.parent as? ViewGroup)?.removeView(container)
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

    private fun mountOnMain(anchor: View) {
        try {
            if (!WeTypeSettings.isClipboardSearchEnabledXposed()) return
            val hostLoader = anchor.javaClass.classLoader ?: return
            val resources = anchor.resources ?: return

            val root = (anchor.rootView as? ViewGroup) ?: return
            val ids = resolveIds(hostLoader)
            var titleContainer = ids.logoContainerId?.let { root.findViewById<ViewGroup>(it) }
            var anchorName = "logo_container_rl"
            if (titleContainer == null) {
                // 备选锚点：custom_btn/custom2_btn 的父容器（只读定位，不动原生按钮）。
                val custom = ids.customBtnId?.let { root.findViewById<View>(it) }
                    ?: ids.custom2BtnId?.let { root.findViewById<View>(it) }
                titleContainer = custom?.parent as? ViewGroup
                anchorName = "custom_btn/custom2_btn.parent"
            }
            if (titleContainer == null) {
                AndroidLog.e(TAG, "title container not found, skip mount")
                return
            }

            // 1. 放大镜按钮（图标运行时按资源名复用原生搜索 drawable，兜底系统搜索图标）。
            var button = titleContainer.findViewWithTag<View>(TAG_SEARCH_BUTTON) as? ImageView
            if (button == null) {
                val iconRes = resolveSearchIconRes(hostLoader)
                button = ImageView(titleContainer.context).apply {
                    tag = TAG_SEARCH_BUTTON
                    contentDescription = "搜索剪贴板"
                    try {
                        setImageResource(iconRes)
                    } catch (t: Throwable) {
                        AndroidLog.e(TAG, "set search icon failed: ${t.message}")
                    }
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    val pad = dpToPx(resources, BTN_ICON_PADDING_DP)
                    setPadding(pad, pad, pad, pad)
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { toggleSearchBox(root) }
                }
                titleContainer.addView(button)
                val size = dpToPx(resources, BTN_BOX_DP)
                val margin = dpToPx(resources, BTN_MARGIN_DP)
                (button.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                    lp.width = size
                    lp.height = size
                    lp.setMargins(margin, margin, margin, margin)
                    button.layoutParams = lp
                } ?: run {
                    button.layoutParams?.let { lp ->
                        lp.width = size
                        lp.height = size
                        button.layoutParams = lp
                    }
                }
                AndroidLog.i(TAG, "search button mounted at $anchorName")
            }

            // 2. 搜索框：展开时插到标题栏下方/列表上方，不遮挡返回键与标题。
            // S6：容器（横向 LinearLayout）持搜索框 + 右侧 X 一键清空；
            // S4 老布局（裸 EditText）迁移进容器，避免双份挂载。
            var box = root.findViewWithTag<View>(TAG_SEARCH_BOX) as? EditText
            var container = root.findViewWithTag<View>(TAG_SEARCH_BOX_CONTAINER) as? LinearLayout
            if (box == null) {
                val newBox = createSearchBox(titleContainer.context)
                box = newBox
                val freshContainer = LinearLayout(titleContainer.context).apply {
                    tag = TAG_SEARCH_BOX_CONTAINER
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    visibility = View.GONE
                }
                val boxLp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                freshContainer.addView(newBox, boxLp)
                freshContainer.addView(createClearButton(freshContainer.context, newBox))
                insertBelowTitle(root, titleContainer, freshContainer)
                applyBoxContainerMargins(resources, freshContainer)
                container = freshContainer
                synchronized(trackedBoxes) { trackedBoxes.add(newBox) }
                AndroidLog.i(TAG, "search box mounted below title container")
            } else {
                if (container == null) {
                    // S4 迁移：把裸 EditText 搬进新容器。
                    try {
                        (box.parent as? ViewGroup)?.removeView(box)
                    } catch (t: Throwable) {
                        AndroidLog.e(TAG, "detach legacy search box failed: ${t.message}")
                    }
                    val migrated = LinearLayout(titleContainer.context).apply {
                        tag = TAG_SEARCH_BOX_CONTAINER
                        orientation = LinearLayout.HORIZONTAL
                        gravity = android.view.Gravity.CENTER_VERTICAL
                        visibility = box.visibility
                    }
                    box.layoutParams = LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                    )
                    migrated.addView(box)
                    migrated.addView(createClearButton(migrated.context, box))
                    insertBelowTitle(root, titleContainer, migrated)
                    applyBoxContainerMargins(resources, migrated)
                    container = migrated
                    AndroidLog.i(TAG, "legacy search box migrated into container")
                }
                if (container?.findViewWithTag<View>(TAG_SEARCH_CLEAR) == null) {
                    try {
                        container?.addView(createClearButton(container!!.context, box))
                    } catch (t: Throwable) {
                        AndroidLog.e(TAG, "attach clear button failed: ${t.message}")
                    }
                }
                updateClearVisibility(box)
            }
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "mount search UI failed: ${t.message}")
        }
    }

    private fun toggleSearchBox(root: ViewGroup) {
        try {
            val box = root.findViewWithTag<View>(TAG_SEARCH_BOX) as? EditText ?: run {
                AndroidLog.e(TAG, "search box missing on toggle")
                return
            }
            val container = root.findViewWithTag<View>(TAG_SEARCH_BOX_CONTAINER)
            val shown = container?.visibility == View.VISIBLE || box.visibility == View.VISIBLE
            if (shown) {
                box.setText("")
                container?.visibility = View.GONE
                box.visibility = View.GONE
                box.clearFocus()
                updateClearVisibility(box)
                try {
                    keywordListenerImpl?.invoke("")
                } catch (t: Throwable) {
                    AndroidLog.e(TAG, "keyword listener(collapse) failed: ${t.message}")
                }
                AndroidLog.i(TAG, "search box collapsed")
            } else {
                container?.visibility = View.VISIBLE
                box.visibility = View.VISIBLE
                box.requestFocus()
                updateClearVisibility(box)
                AndroidLog.i(TAG, "search box expanded")
            }
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "toggle search box failed: ${t.message}")
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

    /**
     * S6：X 一键清空按钮显隐（有关键词时可见）。由 TextWatcher /
     * 展开 / [clearSearch] 统一调用，不自建过滤链路。
     */
    private fun updateClearVisibility(box: EditText) {
        try {
            val parent = box.parent as? ViewGroup ?: return
            val clear = parent.findViewWithTag<View>(TAG_SEARCH_CLEAR) ?: return
            val show = box.visibility == View.VISIBLE && box.text?.isNotEmpty() == true
            clear.visibility = if (show) View.VISIBLE else View.GONE
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "update clear visibility failed: ${t.message}")
        }
    }

    private fun createSearchBox(context: android.content.Context): EditText {
        val box = EditText(context).apply {
            tag = TAG_SEARCH_BOX
            hint = "搜索剪贴板"
            setSingleLine(true)
            maxLines = 1
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            inputType = InputType.TYPE_CLASS_TEXT
            visibility = View.VISIBLE
        }
        box.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                try {
                    keywordListenerImpl?.invoke(s?.toString().orEmpty())
                } catch (t: Throwable) {
                    AndroidLog.e(TAG, "keyword listener failed: ${t.message}")
                }
                updateClearVisibility(box)
            }
        })
        return box
    }

    /**
     * S6：搜索框内右侧 X 清除按钮。点击走既有 [clearSearch] +
     * 空关键词恢复链路（S5 清空高亮/回放原列表），不折叠搜索框。
     */
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
                    clearSearch()
                } catch (t: Throwable) {
                    AndroidLog.e(TAG, "clear button failed: ${t.message}")
                }
            }
            val size = dpToPx(res, CLEAR_BOX_DP)
            layoutParams = LinearLayout.LayoutParams(size, size)
        }
    }

    private fun insertBelowTitle(root: ViewGroup, titleContainer: ViewGroup, child: View) {
        try {
            val parent = (titleContainer.parent as? ViewGroup) ?: root
            val titleIndex = parent.indexOfChild(titleContainer)
            if (titleIndex >= 0) {
                parent.addView(child, titleIndex + 1)
            } else {
                parent.addView(child)
            }
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "insert search box failed: ${t.message}")
        }
    }

    private fun applyBoxContainerMargins(
        resources: android.content.res.Resources,
        container: LinearLayout
    ) {
        try {
            val marginH = dpToPx(resources, BOX_MARGIN_H_DP)
            val marginV = dpToPx(resources, BOX_MARGIN_V_DP)
            (container.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                lp.width = ViewGroup.LayoutParams.MATCH_PARENT
                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
                lp.setMargins(marginH, marginV, marginH, marginV)
                container.layoutParams = lp
            }
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "apply box margins failed: ${t.message}")
        }
    }

    private fun findHostView(keyboardObj: Any): View? {
        // 字段扫描（含超类）：首个非空 View 即为锚点。
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
        // 兜底：无参且返回 View 的方法。
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
        val logoContainerId: Int?,
        val customBtnId: Int?,
        val custom2BtnId: Int?
    )

    private fun resolveIds(hostLoader: ClassLoader): ResolvedIds {
        return try {
            val sClass = Class.forName(WETYPE_ID_CLASS, false, hostLoader)
            ResolvedIds(
                logoContainerId = runCatching { sClass.getField("logo_container_rl").getInt(null) }.getOrNull(),
                customBtnId = runCatching { sClass.getField("custom_btn").getInt(null) }.getOrNull(),
                custom2BtnId = runCatching { sClass.getField("custom2_btn").getInt(null) }.getOrNull()
            )
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "resolve title IDs failed: ${t.message}")
            ResolvedIds(null, null, null)
        }
    }

    /**
     * 图标来源：运行时按资源名扫描宿主 drawable R 类，命中含 "search"
     * 的原生放大镜 drawable 即复用；找不到则用系统
     * `android.R.drawable.ic_menu_search` 兜底（本次即按此兜底路径可编译，
     * 真机命中情况以日志 `search icon=` 为准）。
     */
    private fun resolveSearchIconRes(hostLoader: ClassLoader): Int {
        try {
            val rClass = Class.forName(WETYPE_DRAWABLE_CLASS, false, hostLoader)
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
