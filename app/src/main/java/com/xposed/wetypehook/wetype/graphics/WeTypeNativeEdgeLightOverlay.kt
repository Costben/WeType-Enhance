package com.xposed.wetypehook.wetype.graphics

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import com.xposed.wetypehook.wetype.settings.WeTypeSettings
import com.xposed.wetypehook.xposed.Log
import java.util.WeakHashMap
import kotlin.math.ceil
import kotlin.math.floor

/**
 * 键盘按键槽位状态数据（纯数据结构，便于单元测试与几何复用）。
 */
internal data class KeySlotData(
    val id: Int,
    var left: Int = 0,
    var top: Int = 0,
    var right: Int = 0,
    var bottom: Int = 0,
    var radiusPx: Float = 0f,
    var dark: Boolean = false,
    var active: Boolean = false,
    var lastSeenPassId: Long = 0L
) {
    fun matches(l: Int, t: Int, r: Int, b: Int): Boolean =
        left == l && top == t && right == r && bottom == b

    fun intersects(l: Int, t: Int, r: Int, b: Int): Boolean =
        left < r && l < right && top < b && t < bottom

    fun setBounds(l: Int, t: Int, r: Int, b: Int) {
        left = l
        top = t
        right = r
        bottom = b
    }

    fun clearBounds() {
        left = 0
        top = 0
        right = 0
        bottom = 0
    }
}

/**
 * 空间槽位对齐与生命周期对账器。
 *
 * 核心机制：
 * 1. 命中相同矩形（left, top, right, bottom）：同一按键重绘直接复用原槽位，零重排零重划；
 * 2. 命中相交但尺寸不同的旧矩形（布局切换，如 QWERTY -> T9）：逐出并复用相交旧槽位；
 * 3. 无相交：分配未激活槽位；
 * 4. 全键盘换帧（一帧内提交 >= 8 键）：修剪本批未见到的残留槽位；单键局部重绘（< 8 键）则不影响其他按键。
 */
internal class WeTypeKeySlotReconciler(private val maxSlots: Int = 64) {
    val slots = ArrayList<KeySlotData>(maxSlots)

    init {
        for (i in 0 until maxSlots) {
            slots.add(KeySlotData(id = i))
        }
    }

    fun submit(
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        radiusPx: Float,
        dark: Boolean,
        passId: Long
    ): KeySlotData? {
        val width = right - left
        val height = bottom - top
        if (width <= 0 || height <= 0) return null

        // 1. 精确匹配：同一键盘布局下按键坐标完全一致
        for (slot in slots) {
            if (slot.active && slot.matches(left, top, right, bottom)) {
                slot.radiusPx = radiusPx
                slot.dark = dark
                slot.lastSeenPassId = passId
                return slot
            }
        }

        // 2. 相交旧槽位匹配（处理布局切换导致的键位互斥）
        var reusedSlot: KeySlotData? = null
        for (slot in slots) {
            if (slot.active && slot.intersects(left, top, right, bottom)) {
                if (reusedSlot == null) {
                    slot.setBounds(left, top, right, bottom)
                    slot.radiusPx = radiusPx
                    slot.dark = dark
                    slot.lastSeenPassId = passId
                    reusedSlot = slot
                } else {
                    slot.active = false
                    slot.clearBounds()
                }
            }
        }
        if (reusedSlot != null) {
            return reusedSlot
        }

        // 3. 分配空闲槽位
        for (slot in slots) {
            if (!slot.active) {
                slot.setBounds(left, top, right, bottom)
                slot.radiusPx = radiusPx
                slot.dark = dark
                slot.active = true
                slot.lastSeenPassId = passId
                return slot
            }
        }

        return null
    }

    fun pruneUnseen(passId: Long, minSeenCountForPrune: Int = 8) {
        var seenInPassCount = 0
        for (slot in slots) {
            if (slot.active && slot.lastSeenPassId == passId) {
                seenInPassCount++
            }
        }
        if (seenInPassCount >= minSeenCountForPrune) {
            for (slot in slots) {
                if (slot.active && slot.lastSeenPassId != passId) {
                    slot.active = false
                    slot.clearBounds()
                }
            }
        }
    }

    fun clear() {
        for (slot in slots) {
            slot.active = false
            slot.clearBounds()
            slot.lastSeenPassId = 0L
        }
    }
}

/** Pixel geometry for one icon overlay in the overlay host's local coordinates. */
internal data class WeTypeIconOverlayGeometry(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int
)

/**
 * Resolves the two icon coordinate spaces independently.
 *
 * Toolbar backgrounds belong to the complete container view. Logo drawables belong to the
 * ImageView content and therefore follow its image matrix and padding exactly once.
 */
internal fun resolveIconOverlayGeometry(
    target: WeTypeIconEdgeLightTarget,
    targetWidth: Int,
    targetHeight: Int,
    paddingLeft: Int,
    paddingTop: Int,
    paddingRight: Int,
    paddingBottom: Int,
    boundsLeft: Int,
    boundsTop: Int,
    boundsRight: Int,
    boundsBottom: Int,
    imageMatrix: android.graphics.Matrix?,
    offsetX: Int,
    offsetY: Int
): WeTypeIconOverlayGeometry? {
    if (targetWidth <= 0 || targetHeight <= 0 || boundsRight <= boundsLeft || boundsBottom <= boundsTop) {
        return null
    }

    var localLeft: Int
    var localTop: Int
    var localRight: Int
    var localBottom: Int
    when (target) {
        WeTypeIconEdgeLightTarget.BACKGROUND -> {
            localLeft = 0
            localTop = 0
            localRight = targetWidth
            localBottom = targetHeight
        }
        WeTypeIconEdgeLightTarget.IMAGE_CONTENT -> {
            if (imageMatrix == null) {
                localLeft = boundsLeft
                localTop = boundsTop
                localRight = boundsRight
                localBottom = boundsBottom
            } else {
                val mapped = RectF(
                    boundsLeft.toFloat(),
                    boundsTop.toFloat(),
                    boundsRight.toFloat(),
                    boundsBottom.toFloat()
                )
                imageMatrix.mapRect(mapped)
                localLeft = floor(mapped.left).toInt()
                localTop = floor(mapped.top).toInt()
                localRight = ceil(mapped.right).toInt()
                localBottom = ceil(mapped.bottom).toInt()
            }

            // ImageView clips drawable content to its padded content box. Clamping here keeps
            // center-crop/matrix transforms from creating a light ring outside the icon view.
            val contentLeft = paddingLeft.coerceIn(0, targetWidth)
            val contentTop = paddingTop.coerceIn(0, targetHeight)
            val contentRight = (targetWidth - paddingRight).coerceAtLeast(contentLeft)
            val contentBottom = (targetHeight - paddingBottom).coerceAtLeast(contentTop)
            localLeft = localLeft.coerceIn(contentLeft, contentRight)
            localTop = localTop.coerceIn(contentTop, contentBottom)
            localRight = localRight.coerceIn(contentLeft, contentRight)
            localBottom = localBottom.coerceIn(contentTop, contentBottom)
        }
    }

    if (localRight <= localLeft || localBottom <= localTop) return null
    return WeTypeIconOverlayGeometry(
        left = offsetX + localLeft,
        top = offsetY + localTop,
        width = localRight - localLeft,
        height = localBottom - localTop
    ).takeIf { it.width > 0 && it.height > 0 }
}

/**
 * ColorOS 原生 RenderNode 材质边缘光管理器。
 *
 * 覆盖三处场景：
 * 1. 键盘按键（Keys）：宿主以单个 Canvas 绘制所有按键，通过在 IME Window 的 DecorView
 *    层挂载一个无事件穿透的 Overlay 容器，以对象池复用 View，为每个可见按键对齐位置、
 *    下发 OplusMaterialUtil 材质参数；
 * 2. 工具栏图标（Toolbar Icons）：宿主为 custom_toolbar_item_container_view，同样由
 *    Overlay 容器对齐位置与圆形轮廓下发材质；
 * 3. 键盘 Logo（logo_iv）：由 Overlay 容器对齐其显示范围与圆形轮廓下发材质。
 *
 * 参数与大背板共享同一套 ColorOS 原生材质通道（WeTypeColorOsMaterialStroke），使用
 * 透明 base mask 与极小的背景模糊（0.5px）激活 HWUI 材质滤镜管线，使原生的定向高光和内阴影
 * 附着于按键与图标边界，且不抹除或模糊宿主图标与文本。
 */
internal object WeTypeNativeEdgeLightManager {

    private const val TAG_OVERLAY_HOST = "wetype_native_edge_light_host"
    private const val MAX_KEY_POOL_SIZE = 64

    private val locKeyView = IntArray(2)
    private val locOverlayHost = IntArray(2)
    private val locTarget = IntArray(2)

    private val hostReferences = WeakHashMap<ViewGroup, WeTypeNativeEdgeLightOverlayHost>()

    /**
     * 检查原生边缘光的前置条件是否全部满足。
     */
    fun isNativeEligible(context: Context): Boolean =
            WeTypeColorOsMaterialStroke.isAvailable() &&
            WeTypeSystemMaterials.isColorOsBackend() &&
            WeTypeSystemMaterials.isNativeStrokeEnabled(context) &&
            WeTypeSettings.isSystemMaterialEnabledXposed() &&
            WeTypeSettings.isNativeEdgeLightEnabledXposed() &&
            !WeTypeSettings.isEdgeHighlightEnabledXposed(context)

    /**
     * 获取或创建挂载于 decorGroup 顶部的 OverlayHost。
     */
    fun ensureHost(decorGroup: ViewGroup): WeTypeNativeEdgeLightOverlayHost? {
        hostReferences[decorGroup]?.let { existing ->
            if (existing.parent === decorGroup) {
                if (decorGroup.indexOfChild(existing) != decorGroup.childCount - 1) {
                    existing.bringToFront()
                }
                return existing
            }
        }
        for (i in decorGroup.childCount - 1 downTo 0) {
            val child = decorGroup.getChildAt(i)
            if (child is WeTypeNativeEdgeLightOverlayHost) {
                hostReferences[decorGroup] = child
                child.bringToFront()
                return child
            }
        }
        return runCatching {
            val host = WeTypeNativeEdgeLightOverlayHost(decorGroup.context).apply {
                tag = TAG_OVERLAY_HOST
            }
            decorGroup.addView(
                host,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            hostReferences[decorGroup] = host
            host
        }.getOrNull()
    }

    /**
     * 键盘显示时恢复 Overlay 可见性并同步当前状态。
     */
    fun onWindowShown(decorGroup: ViewGroup?) {
        if (decorGroup == null) return
        val context = decorGroup.context
        if (!isNativeEligible(context)) {
            onWindowHidden(decorGroup)
            return
        }
        hostReferences[decorGroup]?.let { host ->
            if (host.visibility != View.VISIBLE) {
                host.visibility = View.VISIBLE
            }
            if (WeTypeSettings.isKeyEdgeLightEnabledXposed()) {
                host.syncKeySlotVisibilities()
            } else {
                host.hideKeyOverlays()
            }
            if (WeTypeSettings.isIconEdgeLightEnabledXposed()) {
                host.restoreIconOverlays()
            } else {
                host.hideIconOverlays()
            }
        }
    }

    /**
     * 键盘收起或隐藏时隐藏 Overlay（保留槽位几何以便再次弹起时零延迟复用）。
     */
    fun onWindowHidden(decorGroup: ViewGroup?) {
        if (decorGroup == null) return
        hostReferences[decorGroup]?.let { host ->
            host.visibility = View.INVISIBLE
        }
    }

    /**
     * 键盘窗口分离或销毁时彻底移除 Overlay 并释放池化视图。
     */
    fun onWindowRemoved(decorGroup: ViewGroup?) {
        if (decorGroup == null) return
        hostReferences.remove(decorGroup)?.let { host ->
            runCatching {
                host.clearAll()
                decorGroup.removeView(host)
            }
        }
    }

    /**
     * 当按键边缘光开关关闭时隐藏按键 Overlay。
     */
    fun hideKeyOverlays(keyView: View?) {
        val decorGroup = keyView?.rootView as? ViewGroup ?: return
        hostReferences[decorGroup]?.hideKeyOverlays()
    }

    /**
     * 当图标边缘光开关关闭时隐藏指定图标 Overlay。
     */
    fun hideIconOverlay(targetView: View?) {
        val decorGroup = targetView?.rootView as? ViewGroup ?: return
        hostReferences[decorGroup]?.hideIconOverlay(targetView)
    }

    /**
     * 进程重载或状态销毁时彻底释放。
     */
    fun reset() {
        hostReferences.forEach { (parent, host) ->
            runCatching {
                host.clearAll()
                parent.removeView(host)
            }
        }
        hostReferences.clear()
    }

    /**
     * 为单个按键提交原生材质边缘光。
     */
    fun submitKey(keyView: View, rect: Rect, radiusPx: Float, dark: Boolean): Boolean {
        if (!isNativeEligible(keyView.context) ||
            !WeTypeSettings.isKeyEdgeLightEnabledXposed() ||
            rect.isEmpty ||
            !keyView.isAttachedToWindow ||
            !keyView.isShown
        ) {
            return false
        }

        val decorGroup = keyView.rootView as? ViewGroup ?: return false
        val host = ensureHost(decorGroup) ?: return false
        if (host.visibility != View.VISIBLE) {
            host.visibility = View.VISIBLE
        }

        keyView.getLocationInWindow(locKeyView)
        host.getLocationInWindow(locOverlayHost)
        val offsetX = locKeyView[0] - locOverlayHost[0]
        val offsetY = locKeyView[1] - locOverlayHost[1]

        val left = offsetX + rect.left
        val top = offsetY + rect.top
        val width = rect.width()
        val height = rect.height()
        if (width <= 0 || height <= 0) return false

        val passId = keyView.drawingTime.takeIf { it != 0L } ?: (SystemClock.uptimeMillis() / 8L)
        val slot = host.reconciler.submit(
            left = left,
            top = top,
            right = left + width,
            bottom = top + height,
            radiusPx = radiusPx,
            dark = dark,
            passId = passId
        ) ?: return false

        val overlay = host.acquireKeyOverlay(slot.id)
        val clampedRadius = radiusPx.coerceIn(0f, minOf(width, height) / 2f)

        val intensityScale = WeTypeSettings.getNativeEdgeLightIntensityXposed() / 100f * 4f
        val angleDegrees = WeTypeSettings.getColorOsLightAngleXposed().toFloat()
        val edgeWidthDp = WeTypeSettings.getNativeEdgeLightWidthXposed().toFloat()

        val applied = host.updateOverlayGeometryAndParams(
            view = overlay,
            left = left,
            top = top,
            width = width,
            height = height,
            radiusPx = clampedRadius,
            dark = dark,
            intensityScale = intensityScale,
            angleDegrees = angleDegrees,
            edgeWidthDp = edgeWidthDp
        )

        host.reconciler.pruneUnseen(passId)
        host.syncKeySlotVisibilities()

        return applied
    }

    /**
     * 为工具栏圆形图标或 Logo 提交原生材质边缘光。
     */
    fun submitIcon(
        targetView: View,
        bounds: Rect,
        radiusPx: Float,
        dark: Boolean,
        target: WeTypeIconEdgeLightTarget
    ): Boolean {
        if (!isNativeEligible(targetView.context) ||
            !WeTypeSettings.isIconEdgeLightEnabledXposed() ||
            bounds.isEmpty ||
            !targetView.isAttachedToWindow ||
            !targetView.isShown
        ) {
            return false
        }

        val decorGroup = targetView.rootView as? ViewGroup ?: return false
        val host = ensureHost(decorGroup) ?: return false
        if (host.visibility != View.VISIBLE) {
            host.visibility = View.VISIBLE
        }

        targetView.getLocationInWindow(locTarget)
        host.getLocationInWindow(locOverlayHost)
        val offsetX = locTarget[0] - locOverlayHost[0]
        val offsetY = locTarget[1] - locOverlayHost[1]

        val geometry = resolveIconOverlayGeometry(
            target = target,
            targetWidth = targetView.width,
            targetHeight = targetView.height,
            paddingLeft = targetView.paddingLeft,
            paddingTop = targetView.paddingTop,
            paddingRight = targetView.paddingRight,
            paddingBottom = targetView.paddingBottom,
            boundsLeft = bounds.left,
            boundsTop = bounds.top,
            boundsRight = bounds.right,
            boundsBottom = bounds.bottom,
            imageMatrix = (targetView as? ImageView)?.imageMatrix,
            offsetX = offsetX,
            offsetY = offsetY
        ) ?: return false

        val overlay = host.getOrCreateIconOverlay(targetView)
        val clampedRadius = radiusPx.coerceIn(0f, minOf(geometry.width, geometry.height) / 2f)

        val intensityScale = WeTypeSettings.getNativeEdgeLightIntensityXposed() / 100f * 4f
        val angleDegrees = WeTypeSettings.getColorOsLightAngleXposed().toFloat()
        val edgeWidthDp = WeTypeSettings.getNativeEdgeLightWidthXposed().toFloat()

        val applied = host.updateOverlayGeometryAndParams(
            view = overlay,
            left = geometry.left,
            top = geometry.top,
            width = geometry.width,
            height = geometry.height,
            radiusPx = clampedRadius,
            dark = dark,
            intensityScale = intensityScale,
            angleDegrees = angleDegrees,
            edgeWidthDp = edgeWidthDp
        )

        return applied
    }
}

/**
 * 承载按键与图标独立 RenderNode 原生材质覆盖层的透明根容器。
 */
internal class WeTypeNativeEdgeLightOverlayHost(context: Context) : FrameLayout(context) {

    internal val reconciler = WeTypeKeySlotReconciler(64)
    private val keyPool = ArrayList<View>()
    private val iconOverlays = WeakHashMap<View, View>()

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        setBackgroundColor(Color.TRANSPARENT)
        clipChildren = false
        clipToPadding = false
    }

    private data class OverlayConfigKey(
        val width: Int,
        val height: Int,
        val radiusPx: Float,
        val dark: Boolean,
        val intensityScale: Float,
        val angleDegrees: Float,
        val edgeWidthDp: Float
    )

    fun acquireKeyOverlay(index: Int): View {
        while (keyPool.size <= index) {
            val v = View(context).apply {
                isClickable = false
                isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                setBackgroundColor(Color.TRANSPARENT)
                visibility = View.GONE
            }
            keyPool.add(v)
            addView(v, LayoutParams(0, 0))
        }
        val view = keyPool[index]
        if (view.visibility != View.VISIBLE) {
            view.visibility = View.VISIBLE
        }
        return view
    }

    fun syncKeySlotVisibilities() {
        for (i in 0 until minOf(keyPool.size, reconciler.slots.size)) {
            val slot = reconciler.slots[i]
            val view = keyPool[i]
            val expected = if (slot.active) View.VISIBLE else View.GONE
            if (view.visibility != expected) {
                view.visibility = expected
            }
        }
    }

    fun getOrCreateIconOverlay(target: View): View {
        iconOverlays[target]?.let { existing ->
            if (existing.parent === this) {
                if (existing.visibility != View.VISIBLE) {
                    existing.visibility = View.VISIBLE
                }
                return existing
            }
        }
        val v = View(context).apply {
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setBackgroundColor(Color.TRANSPARENT)
            visibility = View.VISIBLE
        }
        iconOverlays[target] = v
        addView(v, LayoutParams(0, 0))
        return v
    }

    fun hideKeyOverlays() {
        reconciler.clear()
        keyPool.forEach { it.visibility = View.GONE }
    }

    fun hideIconOverlay(target: View) {
        iconOverlays[target]?.visibility = View.GONE
    }

    fun hideIconOverlays() {
        iconOverlays.values.forEach { it.visibility = View.GONE }
    }

    fun restoreIconOverlays() {
        iconOverlays.forEach { (target, overlay) ->
            if (target.isAttachedToWindow && target.isShown) {
                overlay.visibility = View.VISIBLE
            }
        }
    }

    fun hideAll() {
        hideKeyOverlays()
        iconOverlays.values.forEach { it.visibility = View.GONE }
    }

    fun clearAll() {
        reconciler.clear()
        keyPool.forEach { v ->
            WeTypeColorOsMaterialStroke.clear(v)
            v.tag = null
        }
        keyPool.clear()
        iconOverlays.values.forEach { v ->
            WeTypeColorOsMaterialStroke.clear(v)
            v.tag = null
        }
        iconOverlays.clear()
        removeAllViews()
    }

    override fun dispatchDraw(canvas: Canvas) {
        val eligible = WeTypeNativeEdgeLightManager.isNativeEligible(context)
        if (!eligible) {
            hideAll()
            return
        }

        if (!WeTypeSettings.isKeyEdgeLightEnabledXposed()) {
            hideKeyOverlays()
        }

        if (!WeTypeSettings.isIconEdgeLightEnabledXposed()) {
            iconOverlays.values.forEach { it.visibility = View.GONE }
        } else {
            iconOverlays.forEach { (target, overlay) ->
                val visible = target.isAttachedToWindow && target.isShown
                val targetVis = if (visible) View.VISIBLE else View.GONE
                if (overlay.visibility != targetVis) {
                    overlay.visibility = targetVis
                }
            }
        }

        super.dispatchDraw(canvas)
    }

    fun updateOverlayGeometryAndParams(
        view: View,
        left: Int,
        top: Int,
        width: Int,
        height: Int,
        radiusPx: Float,
        dark: Boolean,
        intensityScale: Float,
        angleDegrees: Float,
        edgeWidthDp: Float
    ): Boolean {
        val lp = view.layoutParams as? LayoutParams ?: LayoutParams(width, height)
        if (lp.width != width || lp.height != height || lp.leftMargin != left || lp.topMargin != top) {
            lp.width = width
            lp.height = height
            lp.leftMargin = left
            lp.topMargin = top
            view.layoutParams = lp
        }
        if (view.left != left || view.top != top || view.right != left + width || view.bottom != top + height) {
            view.layout(left, top, left + width, top + height)
        }

        val config = OverlayConfigKey(
            width = width,
            height = height,
            radiusPx = radiusPx,
            dark = dark,
            intensityScale = intensityScale,
            angleDegrees = angleDegrees,
            edgeWidthDp = edgeWidthDp
        )

        if (view.tag == config) {
            return true
        }

        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) {
                if (v.width > 0 && v.height > 0) {
                    val r = radiusPx.coerceIn(0f, minOf(v.width, v.height) / 2f)
                    outline.setRoundRect(0, 0, v.width, v.height, r)
                }
            }
        }
        view.clipToOutline = true
        view.invalidateOutline()

        val applied = WeTypeColorOsMaterialStroke.apply(
            view = view,
            isDark = dark,
            intensityScale = intensityScale,
            angleDegrees = angleDegrees,
            edgeWidthDp = edgeWidthDp,
            cornerRadiusPx = radiusPx,
            maskColor = Color.TRANSPARENT,
            blurRadiusPx = WeTypeColorOsMaterialStroke.ELEMENT_OVERLAY_BLUR_PX,
            verboseLog = false
        )
        if (applied) {
            view.tag = config
        }
        return applied
    }
}
