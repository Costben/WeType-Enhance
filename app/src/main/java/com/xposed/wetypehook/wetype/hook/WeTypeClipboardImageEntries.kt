package com.xposed.wetypehook.wetype.hook

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log as AndroidLog
import android.util.LruCache
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.widget.ImageView
import android.widget.TextView
import com.xposed.wetypehook.wetype.clipboard.ClipboardImageEntryLogic
import com.xposed.wetypehook.wetype.clipboard.ClipboardImageRowState
import com.xposed.wetypehook.wetype.settings.WeTypeSettings
import com.xposed.wetypehook.xposed.hookBefore
import java.io.File
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * 剪贴板图片条目渲染与交互（ADR-0002）：
 * - 本设备图片单行纯圆角缩略图；
 * - 跨设备图片未落盘时单行「来自关联设备的图片」，后台下载解密后转双行大缩略图；
 * - 点击图片条目统一跳宿主 S33 大图预览，不直接粘贴；更多按钮保持原生；
 * - type==0 行严格复位注入视图与单行高度，防 RecyclerView 复用污染。
 */
internal object WeTypeClipboardImageEntries {

    private const val TAG = WeTypeClipboardImageHost.TAG
    private const val Z_CLASS = "com.tencent.wetype.plugin.hld.clipboard.z"
    private const val RECYCLER_VIEW_CLASS = "androidx.recyclerview.widget.RecyclerView"
    private const val CONSTRAINT_LAYOUT_PARAMS_CLASS =
        "androidx.constraintlayout.widget.ConstraintLayout\$LayoutParams"
    private const val THUMB_INSET_DP = 12
    private const val THUMB_CORNER_DP = 6
    private const val THUMB_BG_COLOR = 0x14000000
    private const val TAG_ROW_CLICK = "wetype_clipboard_image_row_click"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val decodeExecutor = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "WeTypeClipboardImageDecode").apply { isDaemon = true }
    }

    private val bitmapCache = object : LruCache<Long, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: Long, value: Bitmap): Int = value.byteCount
    }

    /** 原图尺寸（outWidth/outHeight），用于按比例计算缩略图框。 */
    private val sizeCache = LruCache<Long, IntArray>(64)

    private val thumbnailByHolder: MutableMap<Any, WeakReference<ImageView>> =
        Collections.synchronizedMap(WeakHashMap())
    private val rowClickByHolder: MutableMap<Any, WeakReference<View>> =
        Collections.synchronizedMap(WeakHashMap())
    private val boundItemByHolder: MutableMap<Any, Any> =
        Collections.synchronizedMap(WeakHashMap())
    private val itemViewByItem: MutableMap<Any, WeakReference<View>> =
        Collections.synchronizedMap(WeakHashMap())
    private val decodeTokenByView: MutableMap<ImageView, Long> =
        Collections.synchronizedMap(WeakHashMap())

    @Volatile
    private var hooked = false

    fun install(classLoader: ClassLoader) {
        if (hooked) return
        if (!WeTypeClipboardImageHost.isReady()) {
            AndroidLog.e(TAG, "image entries install skipped: host handles not ready")
            return
        }
        try {
            hookHolderClick(classLoader)
            hooked = true
            AndroidLog.i(TAG, "clipboard image entries installed")
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "clipboard image entries install failed: ${t.message}")
        }
    }

    /** 由 `v.onBindViewHolder` 后处理调用（主线程，ViewHolder 复用防污染的唯一入口）。 */
    fun onBind(holder: Any) {
        if (!WeTypeClipboardImageHost.isReady()) return
        try {
            val item = WeTypeClipboardImageHost.holderRecordItem(holder) ?: return
            val itemView = getHolderItemView(holder) ?: return
            val contentTv = WeTypeClipboardImageHost.findView(itemView, WeTypeClipboardImageHost.contentTvId) as? TextView
            val contentScroll = WeTypeClipboardImageHost.findView(itemView, WeTypeClipboardImageHost.contentScrollId)
            val line1 = WeTypeClipboardImageHost.findView(itemView, WeTypeClipboardImageHost.line1Id)
            val keyInfo = WeTypeClipboardImageHost.findView(itemView, WeTypeClipboardImageHost.keyInfoId)
            val line1Px = WeTypeClipboardImageHost.dimenToPx(WeTypeClipboardImageHost.line1HeightRes)
            val line2Px = WeTypeClipboardImageHost.dimenToPx(WeTypeClipboardImageHost.line2HeightRes)
            if (line1Px <= 0) return
            val type = WeTypeClipboardImageHost.itemType(item)
            if (type != 1L) {
                boundItemByHolder.remove(holder)
                thumbnailByHolder[holder]?.get()?.visibility = View.GONE
                rowClickByHolder[holder]?.get()?.visibility = View.GONE
                contentScroll?.visibility = View.VISIBLE
                applyLineHeight(line1, line1Px)
                return
            }
            boundItemByHolder[holder] = item
            itemViewByItem[item] = WeakReference(itemView)
            val path = WeTypeClipboardImageHost.itemPath(item)
            val pathType = WeTypeClipboardImageHost.itemPathType(item)
            val receiveTs = WeTypeClipboardImageHost.itemReceiveTimestamp(item)
            val fileExists = pathType == 0 && !path.isNullOrEmpty() && File(path).exists()
            val state = ClipboardImageEntryLogic.classify(type, receiveTs, pathType, fileExists)
            // 图片行的第二行不展示分词：缩略图统一走纯图。
            keyInfo?.visibility = View.GONE
            val thumb = ensureThumbnail(holder, itemView, line1) ?: return
            val inset = WeTypeClipboardImageHost.rawToPx(THUMB_INSET_DP).coerceAtLeast(1)
            val indent = resolveIndent(contentScroll)
            // 统一高度 = 两行高（默认）；关闭 = 单行高。缩略图高 = 行高 - 2*inset，
            // 宽按原比例，超过行宽则等比缩高（ADR-0002 修订）。
            val uniform = WeTypeSettings.isClipboardImageUniformRowHeightXposed()
            val rowHeight = if (uniform) line1Px + line2Px else line1Px
            val targetHeight = (rowHeight - 2 * inset).coerceAtLeast(1)
            val adjustRatio = WeTypeSettings.isClipboardImageAdjustRatioXposed()
            val crop = WeTypeSettings.isClipboardImageCropXposed()
            when (state) {
                ClipboardImageRowState.LOCAL_THUMBNAIL,
                ClipboardImageRowState.REMOTE_THUMBNAIL -> {
                    contentScroll?.visibility = View.GONE
                    applyLineHeight(line1, rowHeight)
                    ensureRowClickTarget(holder, itemView, line1)?.visibility = View.VISIBLE
                    showThumbnail(
                        thumb, item, path, targetHeight, rowHeight, indent, adjustRatio, crop, line1
                    )
                }
                ClipboardImageRowState.REMOTE_PENDING -> {
                    thumb.visibility = View.GONE
                    rowClickByHolder[holder]?.get()?.visibility = View.GONE
                    contentScroll?.visibility = View.VISIBLE
                    contentTv?.text = ClipboardImageEntryLogic.REMOTE_PENDING_TEXT
                    applyLineHeight(line1, line1Px)
                    WeTypeClipboardImageLoader.request(item, itemView.context) { updated ->
                        refreshItem(updated)
                    }
                }
                ClipboardImageRowState.NOT_IMAGE -> Unit
            }
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "image onBind failed: ${t.message}")
        }
    }

    // ---- 缩略图视图 ----

    private fun ensureThumbnail(holder: Any, itemView: View, line1: View?): ImageView? {
        thumbnailByHolder[holder]?.get()?.let { return it }
        val parent = line1 as? ViewGroup ?: return null
        val context = itemView.context ?: return null
        val thumb = ImageView(context).apply {
            id = View.generateViewId()
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            visibility = View.GONE
            background = GradientDrawable().apply {
                cornerRadius = WeTypeClipboardImageHost.rawToPx(THUMB_CORNER_DP).toFloat()
                setColor(THUMB_BG_COLOR)
            }
            setOnClickListener { view ->
                val bound = boundItemByHolder[holder] ?: return@setOnClickListener
                if (WeTypeClipboardImageHost.itemType(bound) != 1L) return@setOnClickListener
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                WeTypeClipboardImageHost.openImagePreview(bound)
            }
        }
        val attached = runCatching {
            parent.addView(thumb, buildLayoutParams(parent, thumb))
            true
        }.getOrDefault(false)
        if (!attached) {
            // 宿主布局非 ConstraintLayout 时退化为 MarginLayoutParams 手工摆位。
            val lp = ViewGroup.MarginLayoutParams(1, 1)
            parent.addView(thumb, lp)
        }
        thumbnailByHolder[holder] = WeakReference(thumb)
        return thumb
    }

    /**
     * 整行空白点击兜底：宿主横向 ScrollView 的 OnTouchListener 恒返回 true，
     * 行内非缩略图区域（例如正方形图片右侧留白）不会产生点击事件。
     * 在 line1 上铺一层透明可点视图（缩略图 brought-to-front 盖在其上），
     * 点空白等同点缩略图：打开 S33 大图预览。
     */
    private fun ensureRowClickTarget(holder: Any, itemView: View, line1: View?): View? {
        rowClickByHolder[holder]?.get()?.let { return it }
        val parent = line1 as? ViewGroup ?: return null
        val overlay = View(itemView.context).apply {
            tag = TAG_ROW_CLICK
            isClickable = true
            isFocusable = false
            visibility = View.GONE
            setOnClickListener { view ->
                val bound = boundItemByHolder[holder] ?: return@setOnClickListener
                if (WeTypeClipboardImageHost.itemType(bound) != 1L) return@setOnClickListener
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                WeTypeClipboardImageHost.openImagePreview(bound)
            }
        }
        val lp = buildLayoutParams(parent, overlay).apply {
            width = ViewGroup.LayoutParams.MATCH_PARENT
            height = ViewGroup.LayoutParams.MATCH_PARENT
        }
        parent.addView(overlay, lp)
        rowClickByHolder[holder] = WeakReference(overlay)
        AndroidLog.i(TAG, "image row blank click target mounted")
        return overlay
    }

    private fun buildLayoutParams(parent: ViewGroup, view: View): ViewGroup.LayoutParams {
        val clClass = runCatching {
            Class.forName(CONSTRAINT_LAYOUT_PARAMS_CLASS, false, parent.javaClass.classLoader)
        }.getOrNull() ?: return ViewGroup.MarginLayoutParams(1, 1)
        val ctor = clClass.getConstructor(
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType
        )
        val lp = ctor.newInstance(1, 1) as ViewGroup.MarginLayoutParams
        // ConstraintLayout.PARENT_ID == 0；start+top+bottom 三约束使固定尺寸居中。
        runCatching {
            clClass.getField("startToStart").setInt(lp, 0)
            clClass.getField("topToTop").setInt(lp, 0)
            clClass.getField("bottomToBottom").setInt(lp, 0)
        }
        return lp
    }

    private fun showThumbnail(
        thumb: ImageView,
        item: Any,
        path: String?,
        targetHeight: Int,
        rowHeight: Int,
        indent: Int,
        adjustRatio: Boolean,
        crop: Boolean,
        line1: View?
    ) {
        thumb.visibility = View.VISIBLE
        val id = WeTypeClipboardImageHost.itemId(item) ?: return
        val size = if (adjustRatio) loadImageSize(id, path) else null
        val boxWidth: Int
        val boxHeight: Int
        if (adjustRatio && size != null && size[1] > 0) {
            val ratio = size[0].toDouble() / size[1].toDouble()
            var height = targetHeight
            var width = (height * ratio).roundToInt().coerceAtLeast(1)
            val maxWidth = rowMaxWidth(line1, indent)
            if (width > maxWidth) {
                width = maxWidth
                height = (width / ratio).roundToInt().coerceAtLeast(1)
            }
            boxWidth = width
            boxHeight = height
        } else {
            boxWidth = targetHeight
            boxHeight = targetHeight
        }
        val lp = thumb.layoutParams ?: return
        if (lp.width != boxWidth || lp.height != boxHeight) {
            lp.width = boxWidth
            lp.height = boxHeight
        }
        if (lp is ViewGroup.MarginLayoutParams) {
            runCatching { lp.marginStart = 0 }
            lp.topMargin = 0
        }
        thumb.layoutParams = lp
        // 保持比例时框=原图比例，完整显示；正方形模式下 crop 决定裁剪填满还是完整显示。
        thumb.scaleType = if (!adjustRatio && crop) {
            ImageView.ScaleType.CENTER_CROP
        } else {
            ImageView.ScaleType.FIT_CENTER
        }
        // 部分宿主版本 ConstraintLayout 不给无约束子视图应用 margin，统一用平移定位。
        thumb.translationX = indent.toFloat()
        thumb.translationY = ((rowHeight - boxHeight) / 2).toFloat()
        // 整行空白点击层后挂，缩略图保持在最上层（点击仍有原生按压反馈）。
        thumb.bringToFront()
        // 更多按钮与缩略图同为 line1 子级，必须留在点击层之上保持原生菜单。
        val moreBtnId = WeTypeClipboardImageHost.moreBtnId
        if (moreBtnId != 0) {
            (line1 as? ViewGroup)?.findViewById<View>(moreBtnId)?.bringToFront()
        }
        // bind 时行宽可能尚未测量（宽图用屏宽兜底会超行宽）：布局后用真实行宽重算一次。
        if (adjustRatio && line1 != null && line1.width <= 0) {
            line1.post {
                if (line1.width > 0 && decodeTokenByView[thumb] == id && thumb.isAttachedToWindow) {
                    showThumbnail(
                        thumb, item, path, targetHeight, rowHeight, indent, adjustRatio, crop, line1
                    )
                }
            }
        }
        decodeTokenByView[thumb] = id
        val cached = bitmapCache.get(id)
        if (cached != null && !cached.isRecycled) {
            thumb.setImageBitmap(cached)
            return
        }
        thumb.setImageDrawable(null)
        if (path.isNullOrEmpty()) return
        decodeExecutor.execute {
            val bitmap = decodeSampled(path, boxWidth, boxHeight)
            if (bitmap != null) bitmapCache.put(id, bitmap)
            mainHandler.post {
                if (decodeTokenByView[thumb] != id) return@post
                thumb.setImageBitmap(bitmap)
            }
        }
    }

    /** 原图尺寸（首读走 bounds，缓存后随取）。 */
    private fun loadImageSize(id: Long, path: String?): IntArray? {
        if (path.isNullOrEmpty()) return null
        sizeCache.get(id)?.let { return it }
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val size = intArrayOf(bounds.outWidth, bounds.outHeight)
            sizeCache.put(id, size)
            size
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "decode thumbnail bounds failed: ${t.message}")
            null
        }
    }

    /** 行的可用宽度：行宽（未布局时退化为屏宽）- 缩略图左缩进 - 右留白。 */
    private fun rowMaxWidth(line1: View?, indent: Int): Int {
        val rowWidth = line1?.width?.takeIf { it > 0 }
            ?: line1?.resources?.displayMetrics?.widthPixels
            ?: return Int.MAX_VALUE
        val reserve = WeTypeClipboardImageHost.rawToPx(THUMB_INSET_DP)
        return (rowWidth - indent - reserve).coerceAtLeast(1)
    }

    private fun decodeSampled(path: String, targetWidth: Int, targetHeight: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                AndroidLog.e(TAG, "decode bounds failed path=$path out=${bounds.outWidth}x${bounds.outHeight}")
                return null
            }
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= targetWidth &&
                bounds.outHeight / (sample * 2) >= targetHeight
            ) {
                sample *= 2
            }
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
            }
            BitmapFactory.decodeFile(path, options)
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "decode thumbnail failed: ${t.message}")
            null
        }
    }

    private fun resolveIndent(contentScroll: View?): Int {
        if (contentScroll != null) {
            val lp = contentScroll.layoutParams
            if (lp is ViewGroup.MarginLayoutParams) {
                val margin = runCatching { lp.marginStart }.getOrDefault(0)
                if (margin > 0) return margin
                if (lp.leftMargin > 0) return lp.leftMargin
            }
        }
        return WeTypeClipboardImageHost.dimenToPx(WeTypeClipboardImageHost.inputIndentRes)
    }

    private fun applyLineHeight(line1: View?, heightPx: Int) {
        val view = line1 ?: return
        val lp = view.layoutParams ?: return
        if (lp.height != heightPx) {
            lp.height = heightPx
            view.layoutParams = lp
        }
    }

    // ---- 下载完成后局部刷新 ----

    fun refreshItem(item: Any) {
        mainHandler.post {
            try {
                val view = itemViewByItem[item]?.get() ?: return@post
                if (!view.isAttachedToWindow) return@post
                val recycler = findRecyclerView(view) ?: return@post
                val position = recycler.javaClass
                    .getMethod("getChildAdapterPosition", View::class.java)
                    .invoke(recycler, view) as? Int ?: return@post
                if (position < 0) return@post
                val adapter = recycler.javaClass.getMethod("getAdapter").invoke(recycler) ?: return@post
                adapter.javaClass
                    .getMethod("notifyItemChanged", Int::class.javaPrimitiveType)
                    .invoke(adapter, position)
            } catch (t: Throwable) {
                AndroidLog.e(TAG, "refresh image item failed: ${t.message}")
            }
        }
    }

    private fun findRecyclerView(view: View): View? {
        var parent: ViewParent? = view.parent
        while (parent != null) {
            if (parent.javaClass.name == RECYCLER_VIEW_CLASS) return parent as? View
            parent = parent.parent
        }
        return null
    }

    // ---- 点击路由（禁止图片条目直接粘贴） ----

    private fun hookHolderClick(classLoader: ClassLoader) {
        val holderClass = Class.forName(Z_CLASS, false, classLoader)
        var count = 0
        for (method in holderClass.declaredMethods) {
            if (method.name != "onClick") continue
            if (method.parameterTypes.size != 1) continue
            if (method.parameterTypes[0] != View::class.java) continue
            if (method.returnType != Void.TYPE) continue
            try {
                method.isAccessible = true
                method.hookBefore { param ->
                    try {
                        val holder = param.thisObject
                        val item = WeTypeClipboardImageHost.holderRecordItem(holder) ?: return@hookBefore
                        if (WeTypeClipboardImageHost.itemType(item) != 1L) return@hookBefore
                        val clicked = param.args.getOrNull(0) as? View ?: return@hookBefore
                        val isContent = clicked.id == WeTypeClipboardImageHost.contentTvId
                        val isThumb = thumbnailByHolder[holder]?.get() === clicked
                        if (isContent || isThumb) {
                            if (isContent) {
                                clicked.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                if (WeTypeClipboardImageHost.itemPathType(item) != 0) {
                                    WeTypeClipboardImageLoader.request(item, clicked.context, forceRetry = true) { updated ->
                                        refreshItem(updated)
                                        WeTypeClipboardImageList.reapplyAll()
                                    }
                                }
                            }
                            WeTypeClipboardImageHost.openImagePreview(item)
                            param.result = null
                        }
                    } catch (t: Throwable) {
                        AndroidLog.e(TAG, "image click dispatch failed: ${t.message}")
                    }
                }
                count++
            } catch (t: Throwable) {
                AndroidLog.e(TAG, "hook holder onClick failed: ${t.message}")
            }
        }
        AndroidLog.i(TAG, "hooked clipboard holder onClick overloads: $count")
    }

    private fun getHolderItemView(holder: Any): View? {
        try {
            var clazz: Class<*>? = holder.javaClass
            while (clazz != null && clazz != Any::class.java) {
                for (field in clazz.declaredFields) {
                    if (!View::class.java.isAssignableFrom(field.type)) continue
                    field.isAccessible = true
                    val view = field.get(holder) as? View
                    if (view != null) return view
                }
                clazz = clazz.superclass
            }
        } catch (t: Throwable) {
            AndroidLog.e(TAG, "get holder itemView failed: ${t.message}")
        }
        return null
    }
}
