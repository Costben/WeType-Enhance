package com.xposed.wetypehook.wetype.graphics

import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import com.xposed.wetypehook.wetype.settings.WeTypeSettings

/**
 * 模块自绘的边缘光：把 [WeTypeBloomStrokeDrawable] 的紧凑预设当成一个可以按矩形落笔的绘制源。
 *
 * 存在的理由是 ColorOS 原生 `COUIShadowEdgeDrawable` 的替代品——它在非 ColorOS 上必然加载
 * 失败，边缘光会整条消失。这里用模块自己的绘制引擎顶上，让「光感设置」里的角度、宽度、强度
 * 在任何 ROM 上都画得出来。
 *
 * 圆角是逐次绘制才知道的（键帽半径由宿主下发、图标取 `min(w, h) / 2`），而绘制对象把它当构造
 * 参数，因此按半径缓存一份：半径不变时键帽每帧绘制不会产生新对象，半径一变就重建。
 *
 * [draw] 的 `dark` 不参与绘制——明暗由 [WeTypeBloomStrokeDrawable] 自己按 Context 的 uiMode
 * 判定，与调用方取自同一份 resources 配置。
 */
internal class WeTypeSelfDrawnEdgeLight(
    private val context: Context,
    private val surfaceColor: Int,
    private val intensityScale: Float,
    private val strokeWidthScale: Float,
    private val lightAngleDegrees: Float,
    private val innerShadowScale: Float = 1f
) : WeTypeEdgeLightSource {

    private var drawable: WeTypeBloomStrokeDrawable? = null
    private var drawableRadius = Float.NaN

    override fun draw(canvas: Canvas, rect: Rect, radiusPx: Float, dark: Boolean) {
        if (rect.isEmpty) return
        val light = drawableFor(radiusPx)
        val save = canvas.save()
        canvas.translate(rect.left.toFloat(), rect.top.toFloat())
        light.setBounds(0, 0, rect.width(), rect.height())
        light.draw(canvas)
        canvas.restoreToCount(save)
    }

    private fun drawableFor(radiusPx: Float): WeTypeBloomStrokeDrawable {
        val radius = radiusPx.coerceAtLeast(0f)
        drawable?.let { if (drawableRadius == radius) return it }
        return WeTypeBloomStrokeDrawable(
            context = context,
            cornerRadii = WeTypeCornerRadii.uniform(radius),
            surfaceColor = surfaceColor,
            intensityScale = intensityScale,
            strokeWidthScale = strokeWidthScale,
            lightAngleDegrees = lightAngleDegrees,
            innerShadowScale = innerShadowScale,
            preset = WeTypeEdgeLightPreset.COMPACT
        ).also {
            drawable = it
            drawableRadius = radius
        }
    }

    companion object {
        /**
         * 自绘高光的基准宽度（dp）。CSS 阴影栈里的偏移量就是按这个宽度写的，
         * `strokeWidthScale = 宽度设置 / 本值` 把「宽度」滑杆映射成几何缩放。
         */
        const val BASE_EDGE_WIDTH_DP = 2f

        /**
         * 强度滑杆（0..100）到自绘高光 alpha 的倍数。
         *
         * 滑杆从 0..300 收到 0..100 后倍数相应提高，使 100 与原来的 300 等效——自绘高光的
         * 可达上限不变，只是每一格对应的调整量更大。
         */
        const val EDGE_INTENSITY_GAIN = 3f

        fun intensityScale(intensity: Int): Float = intensity / 100f * EDGE_INTENSITY_GAIN

        fun strokeWidthScale(width: Int): Float = width / BASE_EDGE_WIDTH_DP

        /**
         * 「按键发光强度」到亮层 alpha 的倍数：100 为基准，200 时亮层强度翻倍。
         */
        fun glowLightScale(glow: Int): Float = glow / 100f

        /**
         * 「按键发光强度」到暗层 alpha 的倍数。
         *
         * 阴影栈里那层黑色负责把内缘压出内凹感。它此前和亮层同向缩放，于是拉高「强度」只是让
         * 按键整体更暗——实测按键内缘的净变化是负的（min −32 / max +12），看起来像凹进去而不是
         * 发光。这里让它反向走：100 保持原样，拉到 200 时黑色完全抽走，内缘只剩亮层叠加。
         */
        fun glowInnerShadowScale(glow: Int): Float {
            val base = WeTypeSettings.DEFAULT_GLOW_INTENSITY
            val span = (WeTypeSettings.MAX_GLOW_INTENSITY - base).coerceAtLeast(1)
            return ((WeTypeSettings.MAX_GLOW_INTENSITY - glow).toFloat() / span).coerceIn(0f, 1f)
        }
    }
}

/**
 * 按设置值缓存一份 [WeTypeSelfDrawnEdgeLight]。
 *
 * 两个调用点（图标、按键）都在绘制热路径上，每帧重建不划算；而自绘源的角度/宽度/强度/发光强度是构造
 * 参数，设置一改又必须立刻生效，所以缓存键里带上这四个设置值——设置没动就复用同一份，
 * 动了就重建。
 */
internal class WeTypeSelfDrawnEdgeLightCache {

    private data class Key(
        val angle: Int,
        val width: Int,
        val intensity: Int,
        val glow: Int,
        val surfaceColor: Int
    )

    private var source: WeTypeSelfDrawnEdgeLight? = null
    private var key: Key? = null

    fun resolve(context: Context, surfaceColor: Int): WeTypeSelfDrawnEdgeLight {
        val current = Key(
            angle = WeTypeSettings.getEdgeLightAngleXposed(),
            width = WeTypeSettings.getEdgeLightWidthXposed(),
            intensity = WeTypeSettings.getEdgeHighlightIntensityXposed(context),
            glow = WeTypeSettings.getGlowIntensityXposed(),
            surfaceColor = surfaceColor
        )
        source?.let { if (key == current) return it }
        return WeTypeSelfDrawnEdgeLight(
            context = context,
            surfaceColor = surfaceColor,
            intensityScale = WeTypeSelfDrawnEdgeLight.intensityScale(current.intensity) *
                WeTypeSelfDrawnEdgeLight.glowLightScale(current.glow),
            strokeWidthScale = WeTypeSelfDrawnEdgeLight.strokeWidthScale(current.width),
            lightAngleDegrees = current.angle.toFloat(),
            innerShadowScale = WeTypeSelfDrawnEdgeLight.glowInnerShadowScale(current.glow)
        ).also {
            source = it
            key = current
        }
    }

    fun reset() {
        source = null
        key = null
    }
}
