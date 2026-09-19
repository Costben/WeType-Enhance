package com.xposed.wetypehook.wetype.graphics

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
    private val host: Drawable?,
    private val light: WeTypeColorOsKeyLight,
    private val dark: Boolean
) : Drawable() {

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        if (bounds.isEmpty) return
        host?.let {
            it.setBounds(bounds)
            it.draw(canvas)
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

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = host?.opacity ?: PixelFormat.TRANSLUCENT

    override fun getIntrinsicWidth(): Int = host?.intrinsicWidth ?: -1

    override fun getIntrinsicHeight(): Int = host?.intrinsicHeight ?: -1
}
