package com.xposed.wetypehook.wetype.graphics

import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import com.xposed.wetypehook.wetype.settings.EdgeLightGroup
import com.xposed.wetypehook.wetype.settings.EdgeLightTarget
import com.xposed.wetypehook.wetype.settings.WeTypeSettings

/**
 * 模块自绘的边缘光：把 [WeTypeBloomStrokeDrawable] 的紧凑预设当成一个可以按矩形落笔的绘制源。
 *
 * 存在的理由是 ColorOS 原生 `COUIShadowEdgeDrawable` 的替代品——它在非 ColorOS 上必然加载
 * 失败，边缘光会整条消失。这里用模块自己的绘制引擎顶上，让「光感设置」里的角度、宽度、强度
 * 在任何 ROM 上都画得出来。
 *
 * 边缘与内发光是两组独立参数：各自有开关、强度与宽度，只有角度是共用的。
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
    private val edgeIntensityScale: Float,
    private val edgeWidthScale: Float,
    private val glowIntensityScale: Float,
    private val glowWidthScale: Float,
    private val lightAngleDegrees: Float,
    private val innerShadowScale: Float = 1f,
    private val edgeEnabled: Boolean = true,
    private val glowEnabled: Boolean = true
) : WeTypeEdgeLightSource {

    private var drawable: WeTypeBloomStrokeDrawable? = null
    private var drawableRadius = Float.NaN

    override fun draw(canvas: Canvas, rect: Rect, radiusPx: Float, dark: Boolean): Boolean {
        if (rect.isEmpty) return false
        val light = drawableFor(radiusPx)
        val save = canvas.save()
        canvas.translate(rect.left.toFloat(), rect.top.toFloat())
        light.setBounds(0, 0, rect.width(), rect.height())
        light.draw(canvas)
        canvas.restoreToCount(save)
        return true
    }

    private fun drawableFor(radiusPx: Float): WeTypeBloomStrokeDrawable {
        val radius = radiusPx.coerceAtLeast(0f)
        drawable?.let { if (drawableRadius == radius) return it }
        return WeTypeBloomStrokeDrawable(
            context = context,
            cornerRadii = WeTypeCornerRadii.uniform(radius),
            surfaceColor = surfaceColor,
            edgeIntensityScale = edgeIntensityScale,
            edgeWidthScale = edgeWidthScale,
            glowIntensityScale = glowIntensityScale,
            glowWidthScale = glowWidthScale,
            lightAngleDegrees = lightAngleDegrees,
            innerShadowScale = innerShadowScale,
            edgeHighlightEnabled = edgeEnabled,
            glowEnabled = glowEnabled,
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
         *
         * 边缘与内发光各乘各的：两条宽度滑杆独立。
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
         * 内发光强度（0..100）到亮层 alpha 的倍数。100 就是阴影栈被写出来时的原样。
         *
         * 再往上乘会把两层白双双顶到 255：模糊被削平，内发光退化成贴着内缘的一圈硬边，
         * 看起来「只有边缘高光、没有内发光」。所以上限停在 100。
         */
        fun glowLightScale(glow: Int): Float = glow / 100f

        /**
         * 内发光（阴影栈里除第一层窄亮边以外的所有层）的 alpha 倍数。
         *
         * 它只跟内发光强度走，不跟边缘强度：两者同乘时滑杆互相抵消，而且默认值就把亮层顶到
         * 255，模糊被削平，内发光变成一圈硬边——「强度」怎么拉都不再变化。
         */
        fun glowLayerScale(glow: Int): Float = glowLightScale(glow)

        /**
         * 内发光强度到暗层 alpha 的倍数。
         *
         * 阴影栈里那层黑色负责把内缘压出内凹感，它和内发光同属一层「内发光」：用户把内发光
         * 调淡，提亮与压暗一起变淡；调到 0 就是整层撤掉，键帽回到平的。
         */
        fun glowInnerShadowScale(glow: Int): Float = glow / 100f
    }
}

/**
 * 按设置值缓存一份 [WeTypeSelfDrawnEdgeLight]。
 *
 * 三个调用点（背板、图标、按键）都在绘制热路径上，每帧重建不划算；而自绘源的角度、
 * 两组的开关/强度/宽度都是构造参数，设置一改又必须立刻生效，所以缓存键里带上这些设置值——
 * 设置没动就复用同一份，动了就重建。
 *
 * [target] 决定读三类元素里的哪一份：背板、图标、按键各读自己的。
 */
internal class WeTypeSelfDrawnEdgeLightCache(
    private val target: EdgeLightTarget
) {

    private data class Key(
        val group: EdgeLightGroup,
        val angle: Int,
        val surfaceColor: Int
    )

    private var source: WeTypeSelfDrawnEdgeLight? = null
    private var key: Key? = null

    fun resolve(context: Context, surfaceColor: Int): WeTypeSelfDrawnEdgeLight {
        val group = WeTypeSettings.getEdgeLightGroupXposed(target)
        val current = Key(
            group = group,
            angle = WeTypeSettings.getEdgeLightAngleXposed(),
            surfaceColor = surfaceColor
        )
        source?.let { if (key == current) return it }
        return WeTypeSelfDrawnEdgeLight(
            context = context,
            surfaceColor = surfaceColor,
            edgeIntensityScale = WeTypeSelfDrawnEdgeLight.intensityScale(group.edgeIntensity),
            edgeWidthScale = WeTypeSelfDrawnEdgeLight.strokeWidthScale(group.edgeWidth),
            glowIntensityScale = WeTypeSelfDrawnEdgeLight.glowLayerScale(group.glowIntensity),
            glowWidthScale = WeTypeSelfDrawnEdgeLight.strokeWidthScale(group.glowWidth),
            lightAngleDegrees = current.angle.toFloat(),
            innerShadowScale = WeTypeSelfDrawnEdgeLight.glowInnerShadowScale(group.glowIntensity),
            edgeEnabled = group.edgeEnabled,
            glowEnabled = group.glowEnabled
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
