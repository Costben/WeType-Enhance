package com.xposed.wetypehook.wetype.graphics

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import com.xposed.wetypehook.wetype.settings.WeTypeSettings

/**
 * 把边缘光叠在图标自身的 drawable 之上。
 *
 * 工具栏图标与 Logo 的圆底都是圆形，直接取 `min(w, h) / 2` 作半径即可让轮廓与真实形状重合；
 * 光感由模块自绘 [light] 画。
 */
internal class WeTypeIconEdgeLightLayer(
    private val context: Context,
    private val host: Drawable?,
    private val light: WeTypeEdgeLightSource,
    private val dark: Boolean,
    private val target: WeTypeIconEdgeLightTarget = WeTypeIconEdgeLightTarget.IMAGE_CONTENT
) : Drawable() {

    private var callbackForwarded = false

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        if (bounds.isEmpty) return
        forwardCallback()
        refreshHostOpacity()
        host?.let {
            it.setBounds(bounds)
            it.draw(canvas)
        }

        val backend = resolveEdgeLightBackend(
            enabled = isIconEdgeLightActive(context),
            selfDrawnSourceEnabled = WeTypeSettings.isEdgeHighlightEnabledXposed(context)
        )
        if (backend != WeTypeEdgeLightBackend.SELF_DRAWN) return

        val save = canvas.save()
        canvas.clipRect(bounds)
        light.draw(canvas, Rect(bounds), minOf(bounds.width(), bounds.height()) / 2f, dark)
        canvas.restoreToCount(save)
    }

    override fun setAlpha(alpha: Int) {
        host?.alpha = alpha
    }

    /** Re-reads the toolbar opacity without requiring the host to recreate its background drawable. */
    fun refreshHostOpacity() {
        if (target == WeTypeIconEdgeLightTarget.BACKGROUND) {
            host?.alpha = WeTypeSettings.getToolbarIconBgOpacityXposed()
        }
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        host?.colorFilter = colorFilter
    }

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
