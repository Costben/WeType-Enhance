package com.xposed.wetypehook.wetype.graphics

import android.content.Context
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import com.xposed.wetypehook.PropertyUtils
import com.xposed.wetypehook.xposed.Log

/**
 * ColorOS 逐键光感：加载系统自身的 `COUIShadowEdgeDrawable`。
 *
 * 小布输入法给每个按键单独叠这层边缘光（先画键帽底色，再画该 Drawable）。
 * 组件随 SystemUI 打包，用 [PathClassLoader] 从 `com.android.systemui` 的 APK
 * 路径加载；需要硬件加速 Canvas。
 *
 * 参数对齐小布官方实现：cornerType=2、weight=2.3、SHADOW_STYLE_2 / EDGE_STYLE_2、
 * SRC_OVER，暗色 fadeAlpha=(0.10, 0.15)、亮色 (0.60, 0.20)，描线关闭（小布也不描线）。
 *
 * `weight` 必须设置：它的默认值 0 会让 NEW_G2 走 `sdCapsule` 分支，宽大于高的按键
 * 退化成胶囊，光感圆角等于半个按键高度，远大于键帽。小布实测 weight=2.3 才走圆角
 * 矩形 SDF，贴合键帽圆角——这正是“内发光圆角比按键大”的根因。
 *
 * 按键圆角跟随模块设置，并按键帽尺寸收敛到短边一半，避免半径超过短边时 SDF 外鼓。
 */
internal class WeTypeColorOsKeyLight private constructor(
    private val drawable: Drawable,
    private val loader: ClassLoader
) {

    @Volatile
    private var failed = false

    private fun call(name: String, vararg args: Any) {
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
        val enumClass = loader.loadClass("com.coui.appcompat.shadowedge.IShadowEdgeEffect\$$type")
        @Suppress("UNCHECKED_CAST")
        val constant = java.lang.Enum.valueOf(enumClass as Class<out Enum<*>>, value)
        drawable.javaClass.getMethod(method, enumClass).invoke(drawable, constant)
    }

    /**
     * 画出单键光感。[rect] 是键帽在传入 Canvas 坐标系里的矩形；
     * 内部把画布原点平移到键帽左上角，再按键帽尺寸下发分辨率与尺寸。
     */
    fun draw(canvas: Canvas, rect: android.graphics.Rect, keyRadiusPx: Float, dark: Boolean) {
        if (failed) return
        val width = rect.width().toFloat()
        val height = rect.height().toFloat()
        if (!canvas.isHardwareAccelerated || width <= 0f || height <= 0f) return
        runCatching {
            call("setIsDarkMode", dark)
            // 明暗权重随主题切换（对齐小布）：暗色 0.10/0.15、亮色 0.60/0.20。
            call("setFadeAlpha", if (dark) DARK_FADE_IN else LIGHT_FADE_IN, if (dark) DARK_FADE_OUT else LIGHT_FADE_OUT)
            // 半径不能超过短边一半，否则 SDF 圆角外鼓、超出键帽轮廓。
            call("setCornerRadius", keyRadiusPx.coerceIn(0f, minOf(width, height) / 2f))
            call("setResolution", width, height)
            call("setSize", width, height)
            val save = canvas.save()
            canvas.translate(rect.left.toFloat(), rect.top.toFloat())
            drawable.setBounds(0, 0, rect.width(), rect.height())
            drawable.draw(canvas)
            canvas.restoreToCount(save)
        }.onFailure {
            failed = true
            Log.e("Failed: Draw ColorOS key light")
            Log.e(it)
        }
    }

    companion object {
        fun isPlatform(): Boolean = !PropertyUtils["ro.build.version.oplusrom", ""].isNullOrEmpty()

        fun create(context: Context): WeTypeColorOsKeyLight? = runCatching {
            val loader = WeTypeColorOsClassLoader.get(context) ?: return null
            val drawable = loader
                .loadClass("com.coui.appcompat.shadowedge.COUIShadowEdgeDrawable")
                .getConstructor()
                .newInstance() as Drawable
            val light = WeTypeColorOsKeyLight(drawable, loader)
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

        /** 小布实测：weight>=2 时 NEW_G2 走圆角矩形 SDF；低于 2 退化成胶囊/整圆。 */
        private const val KEY_WEIGHT = 2.3f

        /** 小布实测内阴影明暗权重：暗色按键 10% / 15%，亮色 60% / 20%。 */
        private const val DARK_FADE_IN = 0.10f
        private const val DARK_FADE_OUT = 0.15f
        private const val LIGHT_FADE_IN = 0.60f
        private const val LIGHT_FADE_OUT = 0.20f
    }
}
