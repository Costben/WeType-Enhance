package com.xposed.wetypehook.wetype.graphics

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.View
import com.xposed.wetypehook.wetype.settings.WeTypeSettings
import java.lang.ref.WeakReference

/**
 * 把边缘光叠在图标自身的 drawable 之上。
 *
 * 工具栏图标与 Logo 的圆底都是圆形，直接取 `min(w, h) / 2` 作半径即可让轮廓与真实形状重合；
 * 原生模式走 [WeTypeNativeEdgeLightManager] + RenderNode 材质覆盖层，失败或禁用时
 * 平滑降级至模块自绘 [fallback]。
 */
internal class WeTypeIconEdgeLightLayer(
    private val context: Context,
    private val host: Drawable?,
    private val light: WeTypeEdgeLightSource,
    private val backend: WeTypeEdgeLightBackend,
    private val dark: Boolean,
    private val fallback: WeTypeEdgeLightSource?,
    private val hostViewRef: WeakReference<View>? = null,
    private val nativeSource: WeTypeEdgeLightSource? = null,
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

        val active = isIconEdgeLightActive(context)
        val hostView = hostViewRef?.get()
        if (!active) {
            WeTypeNativeEdgeLightManager.hideIconOverlay(hostView)
            return
        }

        val nativeSourceAvailable = nativeSource != null &&
            WeTypeSystemMaterials.isColorOsBackend() &&
            WeTypeSystemMaterials.isNativeStrokeEnabled(context) &&
            WeTypeSettings.isSystemMaterialEnabledXposed() &&
            WeTypeSettings.isNativeEdgeLightEnabledXposed() &&
            !WeTypeSettings.isEdgeHighlightEnabledXposed(context)

        val currentBackend = resolveEdgeLightBackend(
            enabled = active,
            nativeSourceAvailable = nativeSourceAvailable,
            selfDrawnSourceEnabled = WeTypeSettings.isEdgeHighlightEnabledXposed(context)
        )

        if (currentBackend == WeTypeEdgeLightBackend.NONE) {
            WeTypeNativeEdgeLightManager.hideIconOverlay(hostView)
            return
        }

        val save = canvas.save()
        canvas.clipRect(bounds)
        val rect = Rect(bounds)
        val radius = minOf(bounds.width(), bounds.height()) / 2f

        if (currentBackend == WeTypeEdgeLightBackend.NATIVE) {
            val drawn = nativeSource?.draw(canvas, rect, radius, dark) ?: light.draw(canvas, rect, radius, dark)
            if (!drawn) {
                WeTypeNativeEdgeLightManager.hideIconOverlay(hostView)
                if (WeTypeSettings.isEdgeHighlightEnabledXposed(context)) {
                    fallback?.draw(canvas, rect, radius, dark)
                }
            }
        } else {
            WeTypeNativeEdgeLightManager.hideIconOverlay(hostView)
            fallback?.draw(canvas, rect, radius, dark)
        }

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
