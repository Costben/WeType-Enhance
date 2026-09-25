package com.xposed.wetypehook

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import com.xposed.wetypehook.wetype.graphics.WeTypeBloomStrokeDrawable
import com.xposed.wetypehook.wetype.graphics.WeTypeCornerRadii
import com.xposed.wetypehook.wetype.graphics.WeTypeEdgeLightPreset
import com.xposed.wetypehook.wetype.graphics.WeTypeSelfDrawnEdgeLight
import com.xposed.wetypehook.wetype.graphics.createWeTypeSmoothRoundedPath
import com.xposed.wetypehook.wetype.settings.WeTypeSettings
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 系统材质（ColorOS 后端）在预览里的复刻。
 *
 * 真机两条路，开关组合决定走哪条：
 *
 * 1. ColorOS 后端 + 「ColorOS 系统材质」开 —— 背板是模块自己的底色（不透明度抬到
 *    [MIN_PANEL_ALPHA]），背板模糊走系统材质的固定 150px，边缘光与内阴影交给系统材质通道，
 *    模块自绘的流光轮廓让位。
 * 2. 其余情况 —— 不是 ColorOS 后端，或者 ColorOS 上没开那一行：HyperOS 开了背板材质开关时
 *    沿用 [WeTypeSystemMaterials.fallbackColor]，否则就是模块自绘的底色与模糊；边缘光都由
 *    「光感设置」那条独立轨道决定画不画。
 *
 * 两条路都以外层「使用系统材质」总控为前置：总控关掉时系统材质一律不接管，背板、模糊与
 * 边缘光全部退回模块自绘那一档，与子开关怎么设无关。
 *
 * 观感在 165（ColorOS V17 / PLK110 / density 3.875）上按像素量过，这里按量出来的剖面复刻：
 *
 * - **面板实色**：原生边缘光开时，底色 12.5% 与 58% 两种不透明度给出同一块面板（都 212），
 *   纯红底色给出的就是纯红（255,0,0）——彩条底下一像素都不透。所以这层按「用户底色盖住中性
 *   底板」合成，而不是直接把半透明色铺到壁纸上。
 * - **边缘光是内辉光**：左右最亮、顶部次之、底部没有。亮色档左右峰值 +26、摊开 130px，顶部
 *   +19、摊开 88px；暗色档反过来（左右 +5 / 45px，顶部 +23 / 58px，暗底上白光的可见度更高）。
 * - **只有强度有效**：辉光随「强度」线性长大，随「宽度」完全不变（2dp 与 10dp 逐像素相同），
 *   光照角度在这版 ColorOS 上也不起作用。
 */
internal object NativeMaterialPreview {

    /** 与 hook 侧 `MIN_PANEL_ALPHA` 同值：原生边缘光生效时给面板的最低不透明度。 */
    const val MIN_PANEL_ALPHA = 0xC8

    /** 系统材质的背板模糊半径。真机是固定 150px，不随 density 缩放。 */
    const val SYSTEM_BLUR_PX = 150f

    /** 系统材质染色预设，取自 ColorOS 小布输入法的实测值：暗色纯黑 0.6、亮色浅灰 0.8。 */
    private const val LIGHT_MIX = 0xCCDDDDDD.toInt()
    private const val DARK_MIX = 0x99000000.toInt()

    /** 强度滑杆到平台倍数的换算，与 hook 侧 `COLOROS_EDGE_INTENSITY_GAIN` 同式。 */
    private const val INTENSITY_GAIN = 4f

    /** 实测基准：强度 32 时的平台倍数。下面的绝对值都按这一档归一。 */
    private const val REFERENCE_SCALE = 1.28f

    /** 抬高 [color] 的不透明度到至少 [MIN_PANEL_ALPHA]，保留 RGB。 */
    fun withMinimumAlpha(color: Int): Int =
        (color and 0x00FFFFFF) or (maxOf(color ushr 24, MIN_PANEL_ALPHA) shl 24)

    /** 系统材质的叠加染色。 */
    fun systemTint(isDark: Boolean): Int = if (isDark) DARK_MIX else LIGHT_MIX

    /**
     * 原生边缘光生效时的面板实色：用户底色（不透明度抬到 [MIN_PANEL_ALPHA]）盖在系统材质的
     * 中性底板上。
     *
     * 底板取染色本身的实色：亮色是那块浅灰，暗色是纯黑——实测暗色档面板落在 1~2，亮色档
     * 落在 212（用户底色 #D4D4D4），两者都对得上。
     */
    fun panelColor(color: Int, isDark: Boolean): Int =
        srcOver(withMinimumAlpha(color), systemTint(isDark) or (0xFF shl 24))

    /**
     * 边缘光剖面。左右按强度线性长大；顶部的亮度长得很慢（实测 32 → 100 只涨三成，横向摊开
     * 却快了近四倍），所以顶部单独用四次方根。左右与顶部的摊开距离是两个独立量，实测里
     * 亮色档左右 130px / 顶部 88px，暗色档反过来，共用一条会把顶部画得过长。
     */
    fun edgeGlow(isDark: Boolean, intensity: Int): MaterialEdgeGlow {
        val scale = intensity.coerceIn(0, 100) / 100f * INTENSITY_GAIN
        val growth = (scale / REFERENCE_SCALE).coerceAtLeast(0f)
        val spread = growth.pow(1.2f)
        return if (isDark) {
            MaterialEdgeGlow(
                sideAlpha = (0.016f * scale).coerceIn(0f, 1f),
                topAlpha = (0.09f * growth.pow(0.25f)).coerceIn(0f, 1f),
                sideSpreadDp = 11.6f * spread,
                topSpreadDp = 15f * spread
            )
        } else {
            MaterialEdgeGlow(
                sideAlpha = (0.47f * scale).coerceIn(0f, 1f),
                topAlpha = (0.44f * growth.pow(0.25f)).coerceIn(0f, 1f),
                sideSpreadDp = 33.5f * spread,
                topSpreadDp = 22.7f * spread
            )
        }
    }

    /**
     * 预览面板该按哪条路画。调用方只需照着返回的三个值铺背板、盖色层、画边缘光。
     *
     * @param moduleBlurDp 「键盘模糊」设置折算出来的背板模糊。
     * @param systemBlurDp 系统材质那条路的固定模糊，由 [SYSTEM_BLUR_PX] 按当前 density 折算。
     * @param fallbackColor 非 ColorOS 后端（宿主自家材质）沿用下来的兜底色。
     * @param systemMaterialEnabled 外层「使用系统材质」总控；关掉时下面两条系统材质轨道一律不生效。
     */
    fun panel(
        color: Int,
        isDark: Boolean,
        moduleBlurDp: Float,
        systemBlurDp: Float,
        fallbackColor: Int,
        systemMaterialEnabled: Boolean,
        hyperMaterialEnabled: Boolean,
        colorOsBackend: Boolean,
        edgeHighlightEnabled: Boolean,
        nativeEdgeLightEnabled: Boolean,
        edgeHighlightIntensity: Int
    ): MaterialPreviewPanel {
        // ColorOS 那套系统材质（背板模糊 + 原生边缘光 + 内阴影）整体由「ColorOS 系统材质」
        // 那一行驱动；HyperOS 的背板材质开关在 ColorOS 后端不参与。两条都以外层总控为门禁——hook 侧同口径。
        val colorOsMaterial = systemMaterialEnabled && colorOsBackend && nativeEdgeLightEnabled
        val hyperMaterial = systemMaterialEnabled && !colorOsBackend && hyperMaterialEnabled
        val nativeGlow = if (colorOsMaterial) {
            edgeGlow(isDark, edgeHighlightIntensity)
        } else {
            null
        }
        return when {
            colorOsMaterial -> MaterialPreviewPanel(
                color = panelColor(color, isDark),
                backdropBlurDp = systemBlurDp,
                nativeEdgeGlow = nativeGlow,
                moduleBloom = false
            )
            hyperMaterial -> MaterialPreviewPanel(
                color = fallbackColor,
                backdropBlurDp = moduleBlurDp,
                nativeEdgeGlow = null,
                moduleBloom = edgeHighlightEnabled
            )
            else -> MaterialPreviewPanel(
                color = color,
                backdropBlurDp = moduleBlurDp,
                nativeEdgeGlow = null,
                moduleBloom = edgeHighlightEnabled
            )
        }
    }

    /** 标准的 source-over 合成，两端都按 8 位通道算。 */
    private fun srcOver(src: Int, dst: Int): Int {
        val srcAlpha = (src ushr 24) / 255f
        val dstAlpha = (dst ushr 24) / 255f
        val outAlpha = srcAlpha + dstAlpha * (1f - srcAlpha)
        if (outAlpha <= 0f) return 0
        fun channel(shift: Int): Int {
            val s = (src ushr shift) and 0xFF
            val d = (dst ushr shift) and 0xFF
            val blended = (s * srcAlpha + d * dstAlpha * (1f - srcAlpha)) / outAlpha
            return blended.roundToInt().coerceIn(0, 0xFF)
        }
        return ((outAlpha * 0xFF).roundToInt().coerceIn(0, 0xFF) shl 24) or
            (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }
}

/**
 * 预览面板的一层：底色、背板模糊，以及边缘光交给谁画。
 */
internal data class MaterialPreviewPanel(
    /** 铺在模糊背板之上的色层。 */
    val color: Int,
    /** 背板模糊半径，dp。 */
    val backdropBlurDp: Float,
    /** 非 null 时改画原生材质的内辉光。 */
    val nativeEdgeGlow: MaterialEdgeGlow?,
    /** 是否画模块自绘的流光轮廓。 */
    val moduleBloom: Boolean
) {
    /** 色层不透时背板整片被盖住，那一层模糊白算，直接不画。 */
    val backdropVisible: Boolean
        get() = (color ushr 24) < 0xFF
}

/**
 * 边缘光的内辉光剖面：左右、顶部各自的峰值透明度，以及从边缘往里摊开的距离（dp）。
 * 底部没有辉光，所以不设参数。
 */
internal data class MaterialEdgeGlow(
    val sideAlpha: Float,
    val topAlpha: Float,
    val sideSpreadDp: Float,
    val topSpreadDp: Float
) {
    val visible: Boolean
        get() = sideAlpha > 0.004f || topAlpha > 0.004f
}

/**
 * 把 [MaterialEdgeGlow] 画成面板内侧的辉光。
 *
 * 顶部一条、左右各一条，都是「边缘最亮、往里线性淡出到零」的线性渐变，整体裁进面板轮廓。
 * 只画顶部与左右——真机底部实测没有辉光，角度参数在这版 ColorOS 上也不起作用。
 */
internal fun Modifier.nativeMaterialEdgeGlow(
    cornerRadii: WeTypeCornerRadii,
    glow: MaterialEdgeGlow?
): Modifier {
    if (glow == null || !glow.visible) return this
    return drawBehind {
        val sideSpreadPx = glow.sideSpreadDp.dp.toPx()
        val topSpreadPx = glow.topSpreadDp.dp.toPx()
        val panelPath = createWeTypeSmoothRoundedPath(
            width = size.width,
            height = size.height,
            cornerRadii = cornerRadii
        ).asComposePath()
        clipPath(panelPath) {
            if (glow.topAlpha > 0.004f && topSpreadPx > 0f) {
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color.White.copy(alpha = glow.topAlpha), Color.Transparent),
                        startY = 0f,
                        endY = topSpreadPx
                    ),
                    topLeft = Offset.Zero,
                    size = Size(size.width, topSpreadPx)
                )
            }
            if (glow.sideAlpha > 0.004f && sideSpreadPx > 0f) {
                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(Color.White.copy(alpha = glow.sideAlpha), Color.Transparent),
                        startX = 0f,
                        endX = sideSpreadPx
                    ),
                    topLeft = Offset.Zero,
                    size = Size(sideSpreadPx, size.height)
                )
                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(Color.Transparent, Color.White.copy(alpha = glow.sideAlpha)),
                        startX = size.width - sideSpreadPx,
                        endX = size.width
                    ),
                    topLeft = Offset(size.width - sideSpreadPx, 0f),
                    size = Size(sideSpreadPx, size.height)
                )
            }
        }
    }
}

/**
 * 预览里模块自绘光感（键帽、工具栏图标、Logo）的一组参数。
 *
 * 真机这一层由 `WeTypeSelfDrawnEdgeLight` 画，里面是 clipPath + BlurMaskFilter + Path.op，在
 * Compose 的硬件加速录制画布上不可靠，所以预览改成「软件位图里跑真机同一个 drawable，再把位图
 * 贴进 Compose 画布」，见 [ReplicaEdgeLightRenderer]。
 *
 * 四个数值与设置项同源，交给 drawable 的方式也跟真机完全一致：角度进
 * [WeTypeBloomStrokeDrawable] 的角度参数、宽度进 `strokeWidthScale`、强度与发光强度合成
 * `intensityScale`，发光强度另外反向决定阴影栈里那层黑色内阴影。
 *
 * 图标与按键在真机上是两个开关、两条取源路径，所以调用方各传一份进来。
 */
internal data class ReplicaEdgeLight(
    val enabled: Boolean,
    val angleDegrees: Int,
    val widthDp: Int,
    val intensity: Int,
    val glow: Int = WeTypeSettings.DEFAULT_GLOW_INTENSITY
)

/**
 * 预览里模块自绘光感的渲染器：按真机的 [WeTypeBloomStrokeDrawable]（[WeTypeEdgeLightPreset.COMPACT]）
 * 把光感渲染成一张位图，调用方把它贴到自己的位置与尺寸上。
 *
 * 参数逐项照抄真机自绘源的 `WeTypeSelfDrawnEdgeLightCache.resolve`，所以预览里的亮边、模糊和那层
 * 压暗的内阴影与键盘上是同一个算法、同一套数值。
 *
 * 之前这里用「裁进形状 + 沿光照方向的线性渐变」近似，三个地方对不上真机：没有模糊；阴影栈里那层
 * 黑色内阴影根本没画；线性渐变只能产出「半平面」，而负 spread 产出的是一条贴着内缘的窄带 —— 45°
 * 时渐变那条半平面会切进形状内部，把对角的两个键角整片染白。
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
        val widthDp: Int,
        val intensity: Int,
        val glow: Int,
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
        val radius = cornerRadiusPx.coerceIn(0f, min(widthPx, heightPx) / 2f)
        val key = Key(
            widthPx = widthPx,
            heightPx = heightPx,
            cornerRadiusPx = radius,
            angleDegrees = light.angleDegrees,
            widthDp = light.widthDp,
            intensity = light.intensity,
            glow = light.glow,
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
        val drawable = WeTypeBloomStrokeDrawable(
            // 明暗档由 drawable 自己按 Context 的 uiMode 判，所以必须用预览的档、不能用宿主的。
            context = createPreviewContext(context, isDark),
            cornerRadii = cornerRadii,
            surfaceColor = surfaceColor,
            intensityScale = WeTypeSelfDrawnEdgeLight.intensityScale(light.intensity) *
                WeTypeSelfDrawnEdgeLight.glowLightScale(light.glow),
            strokeWidthScale = WeTypeSelfDrawnEdgeLight.strokeWidthScale(light.widthDp),
            lightAngleDegrees = light.angleDegrees.toFloat(),
            innerShadowScale = WeTypeSelfDrawnEdgeLight.glowInnerShadowScale(light.glow),
            preset = WeTypeEdgeLightPreset.COMPACT
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
