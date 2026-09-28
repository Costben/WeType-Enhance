package com.xposed.wetypehook

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import com.xposed.wetypehook.wetype.graphics.WeTypeBloomStrokeDrawable
import com.xposed.wetypehook.wetype.graphics.WeTypeCornerRadii
import com.xposed.wetypehook.wetype.graphics.WeTypeEdgeLightPreset
import com.xposed.wetypehook.wetype.graphics.WeTypeSelfDrawnEdgeLight
import com.xposed.wetypehook.wetype.graphics.createWeTypeSmoothRoundedPath
import com.xposed.wetypehook.wetype.settings.EdgeLightGroup
import com.xposed.wetypehook.wetype.settings.MaterialPresetCatalog
import kotlin.math.min

/**
 * 预览里模块自绘光感（键帽、工具栏图标、Logo）的一组参数。
 *
 * 真机这一层由 `WeTypeSelfDrawnEdgeLight` 画，里面是 clipPath + BlurMaskFilter + Path.op，在
 * Compose 的硬件加速录制画布上不可靠，所以预览改成「软件位图里跑真机同一个 drawable，再把位图
 * 贴进 Compose 画布」，见 [ReplicaEdgeLightRenderer]。
 *
 * 三个数值与设置项同源，交给 drawable 的方式也跟真机完全一致：角度进
 * [WeTypeBloomStrokeDrawable] 的角度参数，[ReplicaEdgeLight.group] 里边缘与内发光各自的开关、
 * 强度、宽度分别作用于对应的阴影层。
 *
 * 图标与按键在真机上是两个开关、两条取源路径，所以调用方各传一份进来。
 */
internal data class ReplicaEdgeLight(
    val enabled: Boolean,
    val angleDegrees: Int,
    val group: EdgeLightGroup = EdgeLightGroup()
)

/**
 * 预览里模块自绘光感的渲染器：按真机的 [WeTypeBloomStrokeDrawable]（[WeTypeEdgeLightPreset.COMPACT]）
 * 把光感渲染成一张位图，调用方把它贴到自己的位置与尺寸上。
 *
 * 参数逐项照抄真机自绘源的 `WeTypeSelfDrawnEdgeLightCache.resolve`，所以预览里的亮边、模糊和那层
 * 压暗的内阴影与键盘上是同一个算法、同一套数值。
 *
 * 位图按参数缓存：滑杆不动时键盘上几十个按键只会命中少数几张位图，不会每帧重新渲染。
 */
internal object ReplicaEdgeLightRenderer {

    /** 缓存上限。一屏键帽的尺寸种类不多，几十张足够覆盖整块键盘。 */
    private const val MAX_CACHED_BITMAPS = 48

    private data class Key(
        val widthPx: Int,
        val heightPx: Int,
        val cornerRadiusPx: Float,
        val angleDegrees: Int,
        val group: EdgeLightGroup,
        val surfaceColor: Int,
        val isDark: Boolean
    )

    private val cache = object : LinkedHashMap<Key, Bitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Bitmap>): Boolean =
            size > MAX_CACHED_BITMAPS
    }

    /**
     * 取一张光感位图：形状是 [widthPx] × [heightPx]、圆角 [cornerRadiusPx] 的圆角矩形（圆形图标
     * 就是圆角取短边一半），[surfaceColor] 是亮边底下那层的底色。
     *
     * 底色只参与亮度的微调，但两个调用点传的不是同一个东西：真机上按键传键帽色、图标传键盘面板色
     * （见 `WeTypeSelfDrawnEdgeLightCache` 的两处 `resolve`），这里照抄。
     */
    fun bitmap(
        context: Context,
        widthPx: Int,
        heightPx: Int,
        cornerRadiusPx: Float,
        surfaceColor: Int,
        light: ReplicaEdgeLight,
        isDark: Boolean
    ): Bitmap? {
        if (!light.enabled || widthPx <= 0 || heightPx <= 0) return null
        // 「无预设」与真机自绘源同一条口径：这一类不修改，预览也不许套默认阴影栈。
        if (MaterialPresetCatalog.isNone(light.group.presetId)) return null
        val radius = cornerRadiusPx.coerceIn(0f, min(widthPx, heightPx) / 2f)
        val key = Key(
            widthPx = widthPx,
            heightPx = heightPx,
            cornerRadiusPx = radius,
            angleDegrees = light.angleDegrees,
            group = light.group,
            surfaceColor = surfaceColor,
            isDark = isDark
        )
        cache[key]?.let { return it }
        val bitmap = runCatching {
            render(context, widthPx, heightPx, radius, surfaceColor, light, isDark)
        }.getOrNull() ?: return null
        cache[key] = bitmap
        return bitmap
    }

    private fun render(
        context: Context,
        widthPx: Int,
        heightPx: Int,
        cornerRadiusPx: Float,
        surfaceColor: Int,
        light: ReplicaEdgeLight,
        isDark: Boolean
    ): Bitmap {
        val cornerRadii = WeTypeCornerRadii.uniform(cornerRadiusPx)
        val group = light.group
        val drawable = WeTypeBloomStrokeDrawable(
            // 明暗档由 drawable 自己按 Context 的 uiMode 判，所以必须用预览的档、不能用宿主的。
            context = createPreviewContext(context, isDark),
            cornerRadii = cornerRadii,
            surfaceColor = surfaceColor,
            edgeIntensityScale = WeTypeSelfDrawnEdgeLight.intensityScale(group.edgeIntensity),
            edgeWidthScale = WeTypeSelfDrawnEdgeLight.strokeWidthScale(group.edgeWidth),
            glowIntensityScale = WeTypeSelfDrawnEdgeLight.glowLayerScale(group.glowIntensity),
            glowWidthScale = WeTypeSelfDrawnEdgeLight.strokeWidthScale(group.glowWidth),
            lightAngleDegrees = light.angleDegrees.toFloat(),
            innerShadowScale = WeTypeSelfDrawnEdgeLight.glowInnerShadowScale(group.glowIntensity),
            edgeHighlightEnabled = group.edgeEnabled,
            glowEnabled = group.glowEnabled,
            preset = WeTypeEdgeLightPreset.forMaterialPreset(group.presetId, compact = true)
        )
        drawable.setBounds(0, 0, widthPx, heightPx)
        val clip = createWeTypeSmoothRoundedPath(
            width = widthPx.toFloat(),
            height = heightPx.toFloat(),
            cornerRadii = cornerRadii
        )
        return Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            canvas.clipPath(clip)
            drawable.draw(canvas)
        }
    }
}

/** 把 [ReplicaEdgeLightRenderer.bitmap] 产出的位图贴到 [topLeft]，尺寸就是位图本身的尺寸。 */
internal fun DrawScope.drawReplicaEdgeLightBitmap(bitmap: Bitmap?, topLeft: Offset = Offset.Zero) {
    if (bitmap == null) return
    drawIntoCanvas { canvas ->
        canvas.nativeCanvas.drawBitmap(
            bitmap,
            Rect(0, 0, bitmap.width, bitmap.height),
            RectF(
                topLeft.x,
                topLeft.y,
                topLeft.x + bitmap.width,
                topLeft.y + bitmap.height
            ),
            null
        )
    }
}

/**
 * 预览专用的 Context：只把 uiMode 换成预览当前那一档，其余沿用宿主。
 *
 * `WeTypeBloomStrokeDrawable` 自己按 `context.resources.configuration.uiMode` 判明暗衰减，面板
 * 光感与按键光感都得拿到预览的档位，否则预览切到深色时那两层仍按宿主的档在画。
 */
internal fun createPreviewContext(baseContext: Context, isDark: Boolean): Context {
    val configuration = Configuration(baseContext.resources.configuration).apply {
        uiMode =
            (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (isDark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
    }
    return baseContext.createConfigurationContext(configuration)
}
