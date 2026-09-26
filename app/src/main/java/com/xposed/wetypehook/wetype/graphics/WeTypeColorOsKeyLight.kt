package com.xposed.wetypehook.wetype.graphics

import android.content.Context
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.View
import com.xposed.wetypehook.PropertyUtils
import com.xposed.wetypehook.xposed.Log
import java.lang.ref.WeakReference

/**
 * ColorOS 逐键与图标原生光感后端。
 *
 * 优先走 ColorOS 17 原生 RenderNode 材质通道（[WeTypeNativeEdgeLightManager] +
 * [WeTypeColorOsMaterialStroke]），通过透明硬件图层节点挂载 `OplusMaterialUtil` 的
 * `setEdgeParams` 与 `setShadowParams`；
 * 当宿主视图未就绪或非硬件加速 Canvas 时返回 false，由调用方平滑回退至模块自绘引擎
 * （[WeTypeSelfDrawnEdgeLight]）。
 */
internal class WeTypeColorOsKeyLight private constructor(
    private val hostViewRef: WeakReference<View>?,
    private val isIconTarget: Boolean,
    private val iconTarget: WeTypeIconEdgeLightTarget = WeTypeIconEdgeLightTarget.IMAGE_CONTENT,
    private val legacyDrawable: Drawable?,
    private val loader: ClassLoader?
) : WeTypeEdgeLightSource {

    @Volatile
    private var legacyFailed = false

    private fun call(name: String, vararg args: Any) {
        val drawable = legacyDrawable ?: return
        val types = Array(args.size) { index ->
            when (val value = args[index]) {
                is Float -> Float::class.javaPrimitiveType
                is Int -> Int::class.javaPrimitiveType
                is Boolean -> Boolean::class.javaPrimitiveType
                else -> value.javaClass
            }!!
        }
        drawable.javaClass.getMethod(name, *types).invoke(drawable, *args)
    }

    private fun enumCall(method: String, type: String, value: String) {
        val drawable = legacyDrawable ?: return
        val clsLoader = loader ?: return
        val enumClass = clsLoader.loadClass("com.coui.appcompat.shadowedge.IShadowEdgeEffect\$$type")
        @Suppress("UNCHECKED_CAST")
        val constant = java.lang.Enum.valueOf(enumClass as Class<out Enum<*>>, value)
        drawable.javaClass.getMethod(method, enumClass).invoke(drawable, constant)
    }

    override fun draw(canvas: Canvas, rect: Rect, radiusPx: Float, dark: Boolean): Boolean {
        val width = rect.width().toFloat()
        val height = rect.height().toFloat()
        if (!canvas.isHardwareAccelerated || width <= 0f || height <= 0f) return false

        // 1. 优先通过 ColorOS 原生 RenderNode 材质 Overlay 渲染
        val targetView = hostViewRef?.get()
        if (targetView != null) {
            val submitted = if (isIconTarget) {
                WeTypeNativeEdgeLightManager.submitIcon(
                    targetView,
                    rect,
                    radiusPx,
                    dark,
                    iconTarget
                )
            } else {
                WeTypeNativeEdgeLightManager.submitKey(targetView, rect, radiusPx, dark)
            }
            if (submitted) {
                return true
            }
        }

        // 2. 若 RenderNode 材质未命中且 legacy Drawable 可用（旧版无原生材质冲突场景），尝试 legacy 绘制
        val drawable = legacyDrawable
        if (legacyFailed || drawable == null) return false
        return runCatching {
            call("setIsDarkMode", dark)
            call("setFadeAlpha", if (dark) DARK_FADE_IN else LIGHT_FADE_IN, if (dark) DARK_FADE_OUT else LIGHT_FADE_OUT)
            call("setCornerRadius", radiusPx.coerceIn(0f, minOf(width, height) / 2f))
            call("setResolution", width, height)
            call("setSize", width, height)
            val save = canvas.save()
            canvas.translate(rect.left.toFloat(), rect.top.toFloat())
            drawable.setBounds(0, 0, rect.width(), rect.height())
            drawable.draw(canvas)
            canvas.restoreToCount(save)
            true
        }.onFailure {
            legacyFailed = true
            Log.e("Failed: Draw ColorOS key light")
            Log.e(it)
        }.getOrDefault(false)
    }

    companion object {
        fun isPlatform(): Boolean = !PropertyUtils["ro.build.version.oplusrom", ""].isNullOrEmpty()

        fun forKeyView(keyView: View): WeTypeColorOsKeyLight? {
            if (!isPlatform()) return null
            if (WeTypeColorOsMaterialStroke.isAvailable()) {
                return WeTypeColorOsKeyLight(
                    hostViewRef = WeakReference(keyView),
                    isIconTarget = false,
                    legacyDrawable = null,
                    loader = null
                )
            }
            return createLegacy(keyView.context, keyView, isIconTarget = false)
        }

        fun forIconView(
            iconView: View,
            target: WeTypeIconEdgeLightTarget
        ): WeTypeColorOsKeyLight? {
            if (!isPlatform()) return null
            if (WeTypeColorOsMaterialStroke.isAvailable()) {
                return WeTypeColorOsKeyLight(
                    hostViewRef = WeakReference(iconView),
                    isIconTarget = true,
                    iconTarget = target,
                    legacyDrawable = null,
                    loader = null
                )
            }
            return createLegacy(iconView.context, iconView, isIconTarget = true)
        }

        fun create(context: Context): WeTypeColorOsKeyLight? =
            createLegacy(context, null, isIconTarget = false)

        private fun createLegacy(
            context: Context,
            targetView: View?,
            isIconTarget: Boolean
        ): WeTypeColorOsKeyLight? = runCatching {
            val loader = WeTypeColorOsClassLoader.get(context) ?: return null
            val drawable = loader
                .loadClass("com.coui.appcompat.shadowedge.COUIShadowEdgeDrawable")
                .getConstructor()
                .newInstance() as Drawable
            val light = WeTypeColorOsKeyLight(
                hostViewRef = targetView?.let { WeakReference(it) },
                isIconTarget = isIconTarget,
                iconTarget = WeTypeIconEdgeLightTarget.IMAGE_CONTENT,
                legacyDrawable = drawable,
                loader = loader
            )
            light.call("setDensity", context.resources.displayMetrics.density)
            light.call("setDrawableEnabled", true)
            light.call("setCornerType", CORNER_TYPE)
            light.call("setShadowEnable", true)
            light.call("setWeight", KEY_WEIGHT)
            light.call("setFadeWidthScale", 1f)
            drawable.javaClass.getMethod("setPaintBlendMode", BlendMode::class.java)
                .invoke(drawable, BlendMode.SRC_OVER)
            light.enumCall("setShadowStyle", "ShadowStyle", "SHADOW_STYLE_2")
            light.enumCall("setEdgeStyle", "EdgeStyle", "EDGE_STYLE_2")
            light.call("setEdgeEnable", false)
            light
        }.getOrElse {
            Log.e("Failed: Load ColorOS key light drawable")
            Log.e(it)
            null
        }

        private const val CORNER_TYPE = 2
        private const val KEY_WEIGHT = 2.3f
        private const val DARK_FADE_IN = 0.10f
        private const val DARK_FADE_OUT = 0.15f
        private const val LIGHT_FADE_IN = 0.60f
        private const val LIGHT_FADE_OUT = 0.20f
    }
}
