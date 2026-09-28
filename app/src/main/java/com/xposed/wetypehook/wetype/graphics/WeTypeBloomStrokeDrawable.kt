package com.xposed.wetypehook.wetype.graphics

import android.content.Context
import android.content.res.Configuration
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.TypedValue
import com.xposed.wetypehook.wetype.settings.MaterialPresetCatalog
import com.xposed.wetypehook.wetype.settings.WeTypeEdgeLightStyle
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A single CSS-style box-shadow layer. All length values are expressed in CSS px and are mapped to
 * density-independent pixels at draw time, matching the rest of this drawable.
 */
internal data class BoxShadow(
    val inset: Boolean,
    val offsetX: Float,
    val offsetY: Float,
    val blur: Float,
    val spread: Float,
    val color: Int
)

/**
 * Which shadow stack [WeTypeBloomStrokeDrawable] renders.
 *
 * 两套风格 × 两种块面。块面这一维是照系统抄的 —— ColorOS 那边大面积（键盘背板）和小面积
 * （键帽、圆形图标）用的就是两组不同参数，差别最大的一条是小面积**根本不描边**。
 *
 * - [CLASSIC_*]：模块最早那套，四层 inset 的偏移按「光源在左上 45°」写死，出处是 Apple 的
 *   设计规范（`780f22f`）。背板那 8dp 的黑色内阴影会把「拉高强度」变成「内缘越压越暗」，
 *   所以背板调用点整层关掉它（`innerShadowScale = 0`）。
 * - [COLOROS_*]：照 ColorOS 原生参数画的，数值来自真机像素量测，见两个栈各自的注释。
 */
internal enum class WeTypeEdgeLightPreset {
    CLASSIC_PANEL,
    CLASSIC_COMPACT,
    COLOROS_PANEL,
    COLOROS_COMPACT;

    /** Resolve one of the 36 catalog ids to the closest native shadow family. */
    companion object {
        // Source-compatible aliases for the existing renderer tests and older call sites.
        internal val PANEL: WeTypeEdgeLightPreset get() = CLASSIC_PANEL
        internal val COMPACT: WeTypeEdgeLightPreset get() = CLASSIC_COMPACT

        /**
         * Resolve one of the 36 catalog ids — or a custom preset id — to the closest native shadow
         * family.
         *
         * `custom:<family>:<n>` carries the family in the id itself, so the renderer never has to
         * read the preference file to resolve a user-made preset.
         */
        internal fun forMaterialPreset(id: String, compact: Boolean): WeTypeEdgeLightPreset {
            val style = if (MaterialPresetCatalog.shadowFamily(id) == MaterialPresetCatalog.FAMILY_CLASSIC) {
                WeTypeEdgeLightStyle.CLASSIC
            } else {
                WeTypeEdgeLightStyle.COLOROS
            }
            return of(style, compact)
        }
        /** 风格挑形状，块面大小挑尺寸。 */
        internal fun of(style: WeTypeEdgeLightStyle, compact: Boolean): WeTypeEdgeLightPreset =
            when (style) {
                WeTypeEdgeLightStyle.COLOROS -> if (compact) COLOROS_COMPACT else COLOROS_PANEL
                WeTypeEdgeLightStyle.CLASSIC -> if (compact) CLASSIC_COMPACT else CLASSIC_PANEL
            }
    }

    internal val boxShadows: List<BoxShadow>
        get() = when (this) {
            CLASSIC_PANEL -> CLASSIC_PANEL_BOX_SHADOWS
            CLASSIC_COMPACT -> CLASSIC_COMPACT_BOX_SHADOWS
            COLOROS_PANEL -> COLOROS_PANEL_BOX_SHADOWS
            COLOROS_COMPACT -> COLOROS_COMPACT_BOX_SHADOWS
        }

    /**
     * 这一栈的偏移是按哪个光源方位写死的，`lightAngleDegrees` 相对它旋转。
     *
     * 两套风格的原点不同：经典那套的偏移按「左上 45°」（Apple 那份规范的原始朝向），
     * ColorOS 那套按「正下方 270°」（原生块面的光都在底边）。旋转量就是
     * `lightAngleDegrees - authoredAngleDegrees`，所以把角度设成这个值即原样落笔。
     */
    internal val authoredAngleDegrees: Float
        get() = when (this) {
            CLASSIC_PANEL, CLASSIC_COMPACT -> 45f
            COLOROS_PANEL, COLOROS_COMPACT -> 270f
        }

    /**
     * How strongly each layer of [boxShadows] is painted.
     *
     * Layer 0 is the crisp edge highlight; every later layer belongs to the inner glow (the white
     * bloom layers and, in the classic stacks, the single black one). The two groups therefore
     * answer to their own switch and their own intensity — a category can keep its inner glow while
     * its edge highlight is off, and vice versa. [innerShadowScale] damps that black layer alone, and
     * in the ColorOS stacks — which have no black layer — it simply rides along with the glow.
     */
    internal fun layerScale(
        index: Int,
        edgeHighlightEnabled: Boolean,
        glowEnabled: Boolean,
        edgeIntensity: Float,
        glowIntensity: Float,
        innerShadowScale: Float
    ): Float {
        val scale = if (index == 0) {
            if (edgeHighlightEnabled) edgeIntensity else 0f
        } else if (glowEnabled) {
            glowIntensity
        } else {
            0f
        }
        return scale * (if (index == boxShadows.lastIndex) innerShadowScale else 1f)
    }
}

private class RenderedShadow(
    val inset: Boolean,
    val path: Path,
    val paint: Paint
)

/**
 * Renders the keyboard edge highlight as a faithful reproduction of the following CSS box-shadow
 * stack (the first listed shadow paints on top, per the CSS spec):
 *
 * ```
 * box-shadow:
 *   inset 2px 2px 0.25px -1.5px rgba(255, 255, 255, 0.70),
 *   inset 1px 1px 2px 0 rgb(255 255 255 / 80%),
 *   inset -1px -1px 2px 0 rgb(255 255 255 / 60%),
 *   inset 0 0 8px 1px rgba(0, 0, 0, 0.20);
 * ```
 *
 * Outer shadows are clipped to the region outside the content shape so the overlay never darkens the
 * surface interior; inset shadows are clipped to the inside of the content shape.
 *
 * The stack is split into two independently controlled groups. Layer 0 is the edge highlight
 * ([edgeIntensityScale], [edgeWidthScale], gated by [edgeHighlightEnabled]); the remaining layers are
 * the inner glow ([glowIntensityScale], [glowWidthScale], gated by [glowEnabled]). Width scales apply
 * per group, so a wide soft glow no longer forces the crisp highlight wide as well. [innerShadowScale]
 * scales the stack's black layer alone, which is what turns "less glow" into "more recessed" instead
 * of "uniformly dimmer".
 *
 * [lightAngleDegrees] steers the whole stack. The layer offsets above are authored for a light source
 * 45° up and to the left, so every offset is rotated by `lightAngleDegrees - 45` around the panel
 * centre: 0° lights the left edge, the angle grows clockwise, 45° therefore reproduces the
 * authored look exactly, and 225° mirrors the highlight onto the opposite corner.
 *
 * [preset] picks the shadow stack itself: the `CLASSIC_*` pair is the stack documented above and its
 * small-surface retune, the `COLOROS_*` pair the ones measured off the system's own round toolbar
 * buttons and panel.
 */
internal class WeTypeBloomStrokeDrawable(
    private val context: Context,
    private val cornerRadii: WeTypeCornerRadii,
    private val surfaceColor: Int,
    private val edgeIntensityScale: Float = 1f,
    private val edgeWidthScale: Float = 1f,
    private val glowIntensityScale: Float = 1f,
    private val glowWidthScale: Float = 1f,
    private val lightAngleDegrees: Float = BASE_LIGHT_ANGLE_DEGREES,
    private val innerShadowScale: Float = 1f,
    private val edgeHighlightEnabled: Boolean = true,
    private val glowEnabled: Boolean = true,
    private val preset: WeTypeEdgeLightPreset = WeTypeEdgeLightPreset.CLASSIC_PANEL
) : Drawable() {
    private val contentPath = Path()
    private val renderedShadows = mutableListOf<RenderedShadow>()

    private var drawableAlpha = 255
    private var activeColorFilter: ColorFilter? = null

    override fun draw(canvas: Canvas) {
        if (contentPath.isEmpty || renderedShadows.isEmpty()) return
        renderedShadows.forEach { shadow ->
            val saveCount = canvas.save()
            if (shadow.inset) {
                canvas.clipPath(contentPath)
            } else {
                canvas.clipOutPath(contentPath)
            }
            canvas.drawPath(shadow.path, shadow.paint)
            canvas.restoreToCount(saveCount)
        }
    }

    override fun setAlpha(alpha: Int) {
        val clamped = alpha.coerceIn(0, 255)
        if (clamped == drawableAlpha) return
        drawableAlpha = clamped
        rebuild(bounds)
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        activeColorFilter = colorFilter
        renderedShadows.forEach { it.paint.colorFilter = colorFilter }
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        rebuild(bounds)
    }

    private fun rebuild(bounds: Rect) {
        contentPath.reset()
        renderedShadows.clear()
        if (bounds.width() <= 0 || bounds.height() <= 0) return

        val alphaScale = surfaceAlphaScale(surfaceColor) *
            (drawableAlpha / 255f) *
            if (isDarkMode()) DARK_MODE_ALPHA_SCALE else 1f
        if (alphaScale <= 0f) return

        val contentRect = RectF(bounds).apply { inset(0.5f, 0.5f) }
        if (contentRect.width() <= 0f || contentRect.height() <= 0f) return
        contentPath.set(createOffsetRoundedPath(contentRect, cornerRadii))

        // CSS paints the first listed shadow on top, so build the list reversed: earlier list
        // entries are appended last and therefore drawn last (on top).
        preset.boxShadows.withIndex().toList().asReversed().forEach { (index, shadow) ->
            val layerScale = preset.layerScale(
                index = index,
                edgeHighlightEnabled = edgeHighlightEnabled,
                glowEnabled = glowEnabled,
                edgeIntensity = edgeIntensityScale.coerceAtLeast(0f),
                glowIntensity = glowIntensityScale.coerceAtLeast(0f),
                innerShadowScale = innerShadowScale
            )
            val widthScale = (if (index == 0) edgeWidthScale else glowWidthScale)
                .coerceAtLeast(0f)
            buildShadow(shadow, contentRect, alphaScale * layerScale, widthScale)
                ?.let(renderedShadows::add)
        }
    }

    /** The stack's single black layer: it recesses the surface while the white layers bloom on it. */
    private fun buildShadow(
        shadow: BoxShadow,
        contentRect: RectF,
        alphaScale: Float,
        widthScale: Float
    ): RenderedShadow? {
        val color = scaleColorAlpha(shadow.color, alphaScale)
        if (Color.alpha(color) == 0) return null

        val baseOffsetX = dp(shadow.offsetX) * widthScale
        val baseOffsetY = dp(shadow.offsetY) * widthScale
        val rotation = Math.toRadians((lightAngleDegrees - preset.authoredAngleDegrees).toDouble())
        val cosRotation = cos(rotation).toFloat()
        val sinRotation = sin(rotation).toFloat()
        val offsetX = baseOffsetX * cosRotation - baseOffsetY * sinRotation
        val offsetY = baseOffsetX * sinRotation + baseOffsetY * cosRotation
        val spread = dp(shadow.spread) * widthScale
        val blur = dp(shadow.blur) * widthScale

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            this.color = color
            colorFilter = activeColorFilter
            val maskRadius = blur * CSS_BLUR_TO_MASK_RADIUS
            if (maskRadius > MIN_MASK_RADIUS_PX) {
                maskFilter = BlurMaskFilter(maskRadius, BlurMaskFilter.Blur.NORMAL)
            }
        }

        val path = if (shadow.inset) {
            buildInsetShadowPath(contentRect, offsetX, offsetY, spread)
        } else {
            buildOuterShadowPath(contentRect, offsetX, offsetY, spread)
        } ?: return null

        return RenderedShadow(shadow.inset, path, paint)
    }

    /**
     * Builds the fill region for an inset shadow: everything inside the content shape except the
     * "hole" (the content shape contracted by [spread] and translated by the offset). Drawing this
     * while clipped to the content shape produces a shadow that hugs the inner edges, exactly like a
     * CSS `inset` box-shadow. A negative spread expands the hole, leaving only a thin band on the
     * edge opposite the offset direction (e.g. the top white highlight).
     */
    private fun buildInsetShadowPath(
        contentRect: RectF,
        offsetX: Float,
        offsetY: Float,
        spread: Float
    ): Path? {
        val holeRect = RectF(contentRect).apply { inset(spread, spread) }
        if (holeRect.width() <= 0f || holeRect.height() <= 0f) {
            // Hole fully collapsed: the whole interior is in shadow.
            return Path(contentPath)
        }
        val holePath = createOffsetRoundedPath(holeRect, cornerRadii.inset(spread)).apply {
            offset(offsetX, offsetY)
        }
        val coverInset = -(abs(offsetX) + abs(offsetY) + dp(64f))
        val fill = Path().apply {
            addRect(RectF(contentRect).apply { inset(coverInset, coverInset) }, Path.Direction.CW)
        }
        fill.op(holePath, Path.Op.DIFFERENCE)
        return fill
    }

    /**
     * Builds the silhouette for an outer shadow: the content shape grown by [spread] and translated
     * by the offset. Drawing it while clipped to the area outside the content shape keeps the overlay
     * from tinting the surface interior.
     */
    private fun buildOuterShadowPath(
        contentRect: RectF,
        offsetX: Float,
        offsetY: Float,
        spread: Float
    ): Path? {
        val shapeRect = RectF(contentRect).apply { inset(-spread, -spread) }
        if (shapeRect.width() <= 0f || shapeRect.height() <= 0f) return null
        return createOffsetRoundedPath(shapeRect, cornerRadii.outset(spread)).apply {
            offset(offsetX, offsetY)
        }
    }

    private fun createOffsetRoundedPath(rect: RectF, cornerRadii: WeTypeCornerRadii): Path =
        createWeTypeSmoothRoundedPath(rect.width(), rect.height(), cornerRadii).apply {
            offset(rect.left, rect.top)
        }

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics)

    private fun isDarkMode(): Boolean =
        context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES

    private fun surfaceAlphaScale(color: Int): Float {
        val alpha = Color.alpha(color) / 255f
        return (1.10f - alpha * 0.20f).coerceIn(0.88f, 1.08f)
    }

    private fun scaleColorAlpha(color: Int, scale: Float): Int {
        val scaledAlpha = (Color.alpha(color) * scale).roundToInt().coerceIn(0, 255)
        return Color.argb(scaledAlpha, Color.red(color), Color.green(color), Color.blue(color))
    }

    private companion object {
        // Android BlurMaskFilter radius maps to a Gaussian sigma of ~0.5773*radius, while a CSS blur
        // radius maps to sigma = blur/2. Matching the two sigmas gives radius ≈ 0.866 * cssBlur.
        private const val CSS_BLUR_TO_MASK_RADIUS = 0.8660f
        private const val MIN_MASK_RADIUS_PX = 0.05f

        /** The light-source azimuth the panel stack offsets are authored for: 45° up and to the left. */
        private const val BASE_LIGHT_ANGLE_DEGREES = 45f

        // The highlight reads much brighter on dark keyboards, so dim every shadow layer's opacity
        // in night mode (mirrors the previous bloom behaviour).
        private const val DARK_MODE_ALPHA_SCALE = 0.3f
    }
}

/**
 * 经典风格 · 大面积。The authored stack, byte for byte, and the reference the class doc's CSS is
 * copied from: nothing here may drift while [WeTypeEdgeLightPreset.CLASSIC_COMPACT] is tuned. The
 * keyboard panel runs it with the black layer switched off (`innerShadowScale = 0`) — see the two
 * call sites in `WeTypeWindowHooks`.
 */
private val CLASSIC_PANEL_BOX_SHADOWS = listOf(
    BoxShadow(inset = true, offsetX = 2f, offsetY = 2f, blur = 0.25f, spread = -1.5f, color = 0xB3FFFFFF.toInt()),
    BoxShadow(inset = true, offsetX = 1f, offsetY = 1f, blur = 2f, spread = 0f, color = 0xCCFFFFFF.toInt()),
    BoxShadow(inset = true, offsetX = -1f, offsetY = -1f, blur = 2f, spread = 0f, color = 0x99FFFFFF.toInt()),
    BoxShadow(inset = true, offsetX = 0f, offsetY = 0f, blur = 8f, spread = 1f, color = 0x33000000)
)

/**
 * 经典风格 · 小面积。Same four layers and the same colours, retuned for small surfaces (~24dp–40dp:
 * key caps and the round toolbar icons). Offsets and spreads are halved and blurs drop to 0.3–0.4× of
 * the panel values, so the highlight reads as an edge rather than a haze that swallows the surface:
 * on a 40dp key cap the panel's 8dp dark blur alone spans a fifth of the piece, while its 2dp white
 * blurs wash out the hairline highlight.
 */
private val CLASSIC_COMPACT_BOX_SHADOWS = listOf(
    BoxShadow(inset = true, offsetX = 1f, offsetY = 1f, blur = 0.1f, spread = -0.75f, color = 0xB3FFFFFF.toInt()),
    BoxShadow(inset = true, offsetX = 0.5f, offsetY = 0.5f, blur = 0.75f, spread = 0f, color = 0xCCFFFFFF.toInt()),
    BoxShadow(inset = true, offsetX = -0.5f, offsetY = -0.5f, blur = 0.75f, spread = 0f, color = 0x99FFFFFF.toInt()),
    BoxShadow(inset = true, offsetX = 0f, offsetY = 0f, blur = 2.5f, spread = 0.5f, color = 0x33000000)
)

/**
 * ColorOS 风格 · 小面积（键帽、圆形图标）。栈按「光源在正下方」排布，也就是角度 270°。
 *
 * 原生侧的真源：`COUIShadowEdgeDrawable` 上 `setEdgeEnable(false)`（**不描边**）、
 * `SHADOW_STYLE_2`、`setFadeWidthScale(1f)`、`setFadeAlpha(0.60, 0.20)`（浅色）/ `(0.10, 0.15)`
 * （深色）—— 一圈内阴影，alpha 沿周长从 fadeIn 衰减到 fadeOut。
 *
 * 真机量测（PLK110 / ColorOS V17 / 深色模式）：设置页工具栏三个 40dp 圆钮（返回键、扫一扫、
 * 更多选项），圆半径 77.5px，三者读数**逐位相同**，说明是系统统一下发的一套 drawable。相对按钮
 * 自身填充（亮度 41）的抬升：
 *
 * | 方位 | 亮度 | 抬升 | 折算白色叠加 alpha |
 * | :-- | ---: | ---: | ---: |
 * | 下 | 61.6 | +20.4 | 0.095 |
 * | 上 | 50.0 | +8.8 | 0.041 |
 * | 左 / 右 | 46.5 / 46.8 | +5.3 / +5.6 | 0.026 |
 *
 * 辉光向内渗透约 7dp（r 从 0.97R 一路抬到 0.55R），所以第 1、3 层给了较大的 blur。
 *
 * 上面三个 alpha 是**深色档**的观感；本绘制器在夜间会把每一层乘 `DARK_MODE_ALPHA_SCALE`，所以
 * 这里写的是浅色档的基准值（量测值 ÷ 0.3），各层再按滑杆倍数缩放：第 0 层乘边缘强度（默认
 * 80 档 = ×2.4），其余层乘内发光强度（默认 100 档 = ×1.0）。落笔后正好回到上表。
 */
private val COLOROS_COMPACT_BOX_SHADOWS = listOf(
    // 0x13 = 0.0745 × 2.4 = 0.179（浅）→ 0.054（深）：底边最亮的那道细弧
    BoxShadow(inset = true, offsetX = 0f, offsetY = -0.8f, blur = 0.5f, spread = -0.3f, color = 0x13FFFFFF.toInt()),
    // 0x17 = 0.090（浅）→ 0.027（深）：底边宽辉光，向内渗透最深
    BoxShadow(inset = true, offsetX = 0f, offsetY = -1.6f, blur = 4.5f, spread = 0.2f, color = 0x17FFFFFF.toInt()),
    // 0x16 = 0.086（浅）→ 0.026（深）：顶边细白，比底边弱得多
    BoxShadow(inset = true, offsetX = 0f, offsetY = 0.9f, blur = 2f, spread = 0f, color = 0x16FFFFFF.toInt()),
    // 0x0D = 0.051（浅）→ 0.015（深）：整圈内提亮，也是左右两侧那 0.026 的来源
    BoxShadow(inset = true, offsetX = 0f, offsetY = 0f, blur = 6f, spread = 1f, color = 0x0DFFFFFF.toInt())
)

/**
 * ColorOS 风格 · 大面积（键盘背板）。
 *
 * 原生侧大面积走的是另一条通路（`OplusMaterialUtil` / RenderNode），参数是
 * `FRAMEWORK_CAPSULE_PARAMS_1`：**描边是开的**（浅色 `edgeAlpha 0.2` / 深色 `0.6`），
 * 另有 `fadeIn/fadeOut` = 浅 0.1/1.0、深 0.01/1.0 的内阴影。所以这一栈比小面积多一层
 * **整圈均匀的描边**（第 0 层，offset 为 0，靠 spread 撑出线宽），方向性交给第 1、2 层。
 *
 * 大面积这一侧仓库里两份像素记录互相打架（`docs/coloros-native-material-demo.md:617` 记
 * 「左右最亮、顶部次之、底部没有」；`artifacts/coloros-material/20260923-1210-native-edge-light`
 * 实测顶带 19.17 / 底带 33.08）。这里取后者，与小面积那份可信度更高的量测同向 —— 也就是
 * **底 > 顶**，但落差比圆钮小得多。这一档属于低置信外推，改它之前先补一次实机量测。
 */
private val COLOROS_PANEL_BOX_SHADOWS = listOf(
    // 0x15 = 0.082 × 2.4 = 0.197（浅）→ 0.059（深）：整圈描边，对齐原生 edgeAlpha 0.2 / 0.6
    BoxShadow(inset = true, offsetX = 0f, offsetY = 0f, blur = 0.5f, spread = 0.5f, color = 0x15FFFFFF.toInt()),
    // 底部宽辉光
    BoxShadow(inset = true, offsetX = 0f, offsetY = -3f, blur = 9f, spread = 0.5f, color = 0x0BFFFFFF.toInt()),
    // 顶部细白
    BoxShadow(inset = true, offsetX = 0f, offsetY = 1.6f, blur = 3.5f, spread = 0f, color = 0x08FFFFFF.toInt()),
    // 整圈内提亮
    BoxShadow(inset = true, offsetX = 0f, offsetY = 0f, blur = 12f, spread = 1.5f, color = 0x04FFFFFF.toInt())
)
