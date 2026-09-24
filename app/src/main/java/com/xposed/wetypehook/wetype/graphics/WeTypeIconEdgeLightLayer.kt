package com.xposed.wetypehook.wetype.graphics

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable

/**
 * 把官方边缘光叠在图标自身的 drawable 之上。
 *
 * 工具栏图标与 Logo 的圆底都是圆形，直接取 `min(w, h) / 2` 作半径即可让 SDF
 * 与真实轮廓重合；按键那套「键帽半径」在这里不适用。
 *
 * 光感来自 `COUIShadowEdgeDrawable`，它用 `canvas.drawPaint` 铺满当前裁剪区，
 * 因此这里必须先把画布裁到 drawable bounds，否则 shader 会以整块窗口为边界、
 * 在图标上画不出任何东西。
 */
internal class WeTypeIconEdgeLightLayer(
    private val context: Context,
    private val host: Drawable?,
    private val light: WeTypeColorOsKeyLight,
    private val dark: Boolean
) : Drawable() {

    /** [forwardCallback] 是否已经补过 callback，避免每帧重复写。 */
    private var callbackForwarded = false

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        if (bounds.isEmpty) return
        forwardCallback()
        host?.let {
            it.setBounds(bounds)
            it.draw(canvas)
        }
        if (WeTypeSystemMaterials.isColorOsBackend() && !WeTypeSystemMaterials.isNativeStrokeEnabled(context)) {
            return
        }
        val save = canvas.save()
        canvas.clipRect(bounds)
        light.draw(canvas, Rect(bounds), minOf(bounds.width(), bounds.height()) / 2f, dark)
        canvas.restoreToCount(save)
    }

    override fun setAlpha(alpha: Int) {
        host?.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        host?.colorFilter = colorFilter
    }

    /**
     * 包装层要把 callback 转给内层 drawable，否则内层拿不到宿主 View，它的
     * `invalidateSelf()` 会被直接丢弃，改 alpha 或颜色后不触发重绘。
     *
     * 不能 override `setCallback` —— 它在 `Drawable` 里是 final。改在首次绘制时补一次：
     * `Drawable.setCallback` 只写字段、不触发失效，所以这里不会造成重绘递归。
     */
    private fun forwardCallback() {
        if (callbackForwarded) return
        val cb = callback ?: return
        callbackForwarded = true
        runCatching { host?.callback = cb }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = host?.opacity ?: PixelFormat.TRANSLUCENT

    override fun getIntrinsicWidth(): Int = host?.intrinsicWidth ?: -1

    override fun getIntrinsicHeight(): Int = host?.intrinsicHeight ?: -1
}
