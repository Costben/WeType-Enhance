package com.xposed.wetypehook.wetype.graphics

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import com.xposed.wetypehook.wetype.settings.EdgeLightGroup
import com.xposed.wetypehook.wetype.settings.EdgeLightTarget
import com.xposed.wetypehook.wetype.settings.MaterialPresetCatalog
import com.xposed.wetypehook.wetype.settings.WeTypeSettings

/**
 * 模块自绘的边缘光：把 [WeTypeBloomStrokeDrawable] 的紧凑预设当成一个可以按矩形落笔的绘制源。
 *
 * 存在的理由是模块自己的绘制引擎：不依赖系统是否提供对应的原生 drawable，边缘光在任何 ROM 上
 * 都画得出来，角度、宽度、强度全部由「光感设置」决定。
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
    private val glowEnabled: Boolean = true,
    private val preset: WeTypeEdgeLightPreset = WeTypeEdgeLightPreset.CLASSIC_COMPACT
) : WeTypeEdgeLightSource {

    private var drawable: WeTypeBloomStrokeDrawable? = null
    private var drawableRadius = Float.NaN

    private data class BitmapKey(val width: Int, val height: Int, val radius: Int)

    /**
     * 每个键尺寸一份预渲染位图。
     *
     * 边缘光是「四层 BlurMaskFilter 路径」堆出来的，直接在每一帧给 26 个键各画一遍会把
     * RenderThread 打满（实机 p50 从 20ms 涨到 100ms）。
     *
     * 键帽宽高不是整齐的同一值：同一排也会差 1px（布局取整），按精确尺寸做键会永远不命中，
     * 每帧都在新建位图。这里按 [BUCKET] 像素分桶，渲染后用 `drawBitmap(src=null, dst=rect)`
     * 缩到真实矩形，误差最多 4px，肉眼看不出来。
     */
    private val bitmapCache = LinkedHashMap<BitmapKey, Bitmap>(16, 0.75f, true)
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val destination = RectF()

    override fun draw(canvas: Canvas, rect: Rect, radiusPx: Float, dark: Boolean): Boolean {
        if (rect.isEmpty) return false
        val width = rect.width()
        val height = rect.height()
        if (width <= 0 || height <= 0) return false
        val radius = radiusPx.coerceAtLeast(0f)
        val key = BitmapKey(bucket(width.toFloat()), bucket(height.toFloat()), bucket(radius))
        val bitmap = bitmapCache[key] ?: renderBitmap(
            radius,
            key.width * BUCKET,
            key.height * BUCKET
        ).also {
            bitmapCache[key] = it
            if (bitmapCache.size > MAX_CACHED_SIZES) {
                val eldest = bitmapCache.entries.iterator()
                if (eldest.hasNext()) {
                    eldest.next().value.recycle()
                    eldest.remove()
                }
            }
        }
        destination.set(rect)
        canvas.drawBitmap(bitmap, null, destination, bitmapPaint)
        return true
    }

    private fun bucket(value: Float): Int =
        (value / BUCKET).toInt().coerceAtLeast(1)

    private fun renderBitmap(radiusPx: Float, width: Int, height: Int): Bitmap {
        val light = drawableFor(radiusPx)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val bitmapCanvas = Canvas(bitmap)
        light.setBounds(0, 0, width, height)
        light.draw(bitmapCanvas)
        return bitmap
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
            preset = preset
        ).also {
            drawable = it
            drawableRadius = radius
        }
    }

    companion object {
        /** 键帽尺寸的取值很少（普通键 / 功能键 / 空格），留一点余量防止异常布局把缓存撑大。 */
        private const val MAX_CACHED_SIZES = 32

        /** 位图缓存的分桶粒度（px）。 */
        private const val BUCKET = 4

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
        // 「无预设」= 这一类不修改。显式关掉两层，绝不让它因为 id 查不到目录项就退回某套默认
        // 阴影栈——那正是「不修改」被悄悄改成「套一层默认光感」的路径。
        val noneSelected = MaterialPresetCatalog.isNone(group.presetId)
        val current = Key(
            group = group,
            angle = WeTypeSettings.getEdgeLightAngleXposed(),
            surfaceColor = surfaceColor
        )
        source?.let { if (key == current) return it }
        return WeTypeSelfDrawnEdgeLight(
            context = context,
            surfaceColor = surfaceColor,
            edgeIntensityScale = if (noneSelected) 0f else {
                WeTypeSelfDrawnEdgeLight.intensityScale(group.edgeIntensity)
            },
            edgeWidthScale = WeTypeSelfDrawnEdgeLight.strokeWidthScale(group.edgeWidth),
            glowIntensityScale = if (noneSelected) 0f else {
                WeTypeSelfDrawnEdgeLight.glowLayerScale(group.glowIntensity)
            },
            glowWidthScale = WeTypeSelfDrawnEdgeLight.strokeWidthScale(group.glowWidth),
            lightAngleDegrees = current.angle.toFloat(),
            innerShadowScale = if (noneSelected) 0f else {
                WeTypeSelfDrawnEdgeLight.glowInnerShadowScale(group.glowIntensity)
            },
            edgeEnabled = group.edgeEnabled && !noneSelected,
            glowEnabled = group.glowEnabled && !noneSelected,
            preset = WeTypeEdgeLightPreset.forMaterialPreset(
                group.presetId,
                compact = target != EdgeLightTarget.BACKGROUND
            )
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
