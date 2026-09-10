package com.xposed.wetypehook.wetype.hook

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log as AndroidLog
import android.util.LruCache
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.widget.ImageView
import android.widget.TextView
import com.xposed.wetypehook.wetype.clipboard.ClipboardImageEntryLogic
import com.xposed.wetypehook.wetype.clipboard.ClipboardImageRowState
import com.xposed.wetypehook.xposed.hookBefore
import java.io.File
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.Executors

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

    private val mainHandler = Handler(Looper.getMainLooper())
    private val decodeExecutor = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "WeTypeClipboardImageDecode").apply { isDaemon = true }
    }

    private val bitmapCache = object : LruCache<Long, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: Long, value: Bitmap): Int = value.byteCount
    }

    private val thumbnailByHolder: MutableMap<Any, WeakReference<ImageView>> =
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
            // 图片行的第二行不展示分词：本地单行缩略图、跨设备双行大图都走纯图。
            keyInfo?.visibility = View.GONE
            val thumb = ensureThumbnail(holder, itemView, line1) ?: return
            val inset = WeTypeClipboardImageHost.rawToPx(THUMB_INSET_DP).coerceAtLeast(1)
            val singlePx = (line1Px - 2 * inset).coerceAtLeast(1)
            val doublePx = (line1Px + line2Px - 2 * inset).coerceAtLeast(1)
            val indent = resolveIndent(contentScroll)
            when (state) {
                ClipboardImageRowState.LOCAL_THUMBNAIL -> {
                    contentScroll?.visibility = View.GONE
                    applyLineHeight(line1, line1Px)
                    showThumbnail(thumb, item, path, singlePx, indent, line1Px)
                }
                ClipboardImageRowState.REMOTE_PENDING -> {
                    thumb.visibility = View.GONE
                    contentScroll?.visibility = View.VISIBLE
                    contentTv?.text = ClipboardImageEntryLogic.REMOTE_PENDING_TEXT
                    applyLineHeight(line1, line1Px)
                    WeTypeClipboardImageLoader.request(item, itemView.context) { updated ->
                        refreshItem(updated)
                    }
                }
                ClipboardImageRowState.REMOTE_THUMBNAIL -> {
                    contentScroll?.visibility = View.GONE
                    applyLineHeight(line1, line1Px + line2Px)
                    showThumbnail(thumb, item, path, doublePx, indent, line1Px + line2Px)
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
            setOnClickListener {
                val bound = boundItemByHolder[holder] ?: return@setOnClickListener
                if (WeTypeClipboardImageHost.itemType(bound) != 1L) return@setOnClickListener
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

    private fun buildLayoutParams(parent: ViewGroup, thumb: ImageView): ViewGroup.LayoutParams {
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
        sizePx: Int,
        indent: Int,
        lineHeightPx: Int
    ) {
        thumb.visibility = View.VISIBLE
        val lp = thumb.layoutParams ?: return
        if (lp.width != sizePx || lp.height != sizePx) {
            lp.width = sizePx
            lp.height = sizePx
        }
        if (lp is ViewGroup.MarginLayoutParams) {
            runCatching { lp.marginStart = 0 }
            lp.topMargin = 0
        }
        thumb.layoutParams = lp
        // 部分宿主版本 ConstraintLayout 不给无约束子视图应用 margin，统一用平移定位。
        thumb.translationX = indent.toFloat()
        thumb.translationY = ((lineHeightPx - sizePx) / 2).toFloat()
        val id = WeTypeClipboardImageHost.itemId(item) ?: return
        decodeTokenByView[thumb] = id
        val cached = bitmapCache.get(id)
        if (cached != null && !cached.isRecycled) {
            thumb.setImageBitmap(cached)
            return
        }
        thumb.setImageDrawable(null)
        if (path.isNullOrEmpty()) return
        decodeExecutor.execute {
            val bitmap = decodeSampled(path, sizePx)
            if (bitmap != null) bitmapCache.put(id, bitmap)
            mainHandler.post {
                if (decodeTokenByView[thumb] != id) return@post
                thumb.setImageBitmap(bitmap)
            }
        }
    }

    private fun decodeSampled(path: String, targetPx: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                AndroidLog.e(TAG, "decode bounds failed path=$path out=${bounds.outWidth}x${bounds.outHeight}")
                return null
            }
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= targetPx &&
                bounds.outHeight / (sample * 2) >= targetPx
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
