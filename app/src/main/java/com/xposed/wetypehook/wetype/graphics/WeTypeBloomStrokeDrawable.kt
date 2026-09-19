package com.xposed.wetypehook.wetype.graphics

import android.content.Context
import android.content.res.Configuration
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.util.TypedValue
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A single CSS-style box-shadow layer. All length values are expressed in CSS px and are mapped to
 * density-independent pixels at draw time, matching the rest of this drawable.
 */
private data class BoxShadow(
    val inset: Boolean,
    val offsetX: Float,
    val offsetY: Float,
    val blur: Float,
    val spread: Float,
    val color: Int
)

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
 * surface interior; inset shadows are clipped to the inside of the content shape. The public API is
 * unchanged so existing call sites keep working.
 */
internal class WeTypeBloomStrokeDrawable(
    private val context: Context,
    private val cornerRadii: WeTypeCornerRadii,
    private val surfaceColor: Int,
    private val intensityScale: Float = 1f
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
            intensityScale.coerceAtLeast(0f) *
            if (isDarkMode()) DARK_MODE_ALPHA_SCALE else 1f
        if (alphaScale <= 0f) return

        // ColorOS blur and its highlight share the full drawable bounds.
        val followSurfaceContour = WeTypeSystemMaterials.isColorOsBackend()
        val contentRect = RectF(bounds).apply {
            if (!followSurfaceContour) inset(0.5f, 0.5f)
        }
        if (contentRect.width() <= 0f || contentRect.height() <= 0f) return
        contentPath.set(createOffsetRoundedPath(contentRect, cornerRadii))

        if (followSurfaceContour) {
            buildSurfaceContourShadows(alphaScale)
            return
        }

        // CSS paints the first listed shadow on top, so build the list reversed: earlier list
        // entries are appended last and therefore drawn last (on top).
        BOX_SHADOWS.asReversed().forEach { shadow ->
            buildShadow(shadow, contentRect, alphaScale)?.let(renderedShadows::add)
        }
    }

    /**
     * 「流光轮廓」复刻：沿背景同一路径做内描边，一半在路径外被裁掉，因此不会改变圆角几何。
     *
     * 分两层还原系统观感：
     * 1. 柔和的均匀内辉光，保证轮廓在任意底色上都可辨识；
     * 2. 带方向的亮边——光来自左上，沿对角线衰减到右下，形成「流光」的流动感。
     * 宽度只决定光向内渗透的深度，圆角仍由设置里的唯一半径决定。
     */
    private fun buildSurfaceContourShadows(alphaScale: Float) {
        val width = bounds.width().toFloat()
        val height = bounds.height().toFloat()
        if (width <= 0f || height <= 0f) return

        val softGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(3f) * 2f
            color = scaleColorAlpha(0x38FFFFFF, alphaScale)
            colorFilter = activeColorFilter
            maskFilter = BlurMaskFilter(dp(2f) * CSS_BLUR_TO_MASK_RADIUS, BlurMaskFilter.Blur.NORMAL)
        }
        renderedShadows.add(RenderedShadow(true, Path(contentPath), softGlow))

        val directional = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(1f) * 2f
            colorFilter = activeColorFilter
            shader = LinearGradient(
                0f, 0f, width, height,
                intArrayOf(
                    scaleColorAlpha(0xE6FFFFFF.toInt(), alphaScale),
                    scaleColorAlpha(0x1FFFFFFF, alphaScale)
                ),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        renderedShadows.add(RenderedShadow(true, Path(contentPath), directional))
    }

    private fun buildShadow(
        shadow: BoxShadow,
        contentRect: RectF,
        alphaScale: Float
    ): RenderedShadow? {
        val color = scaleColorAlpha(shadow.color, alphaScale)
        if (Color.alpha(color) == 0) return null

        val offsetX = dp(shadow.offsetX)
        val offsetY = dp(shadow.offsetY)
        val spread = dp(shadow.spread)
        val blur = dp(shadow.blur)

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

        // The highlight reads much brighter on dark keyboards, so dim every shadow layer's opacity
        // in night mode (mirrors the previous bloom behaviour).
        private const val DARK_MODE_ALPHA_SCALE = 0.25f

        private val BOX_SHADOWS = listOf(
            BoxShadow(inset = true, offsetX = 2f, offsetY = 2f, blur = 0.25f, spread = -1.5f, color = 0xB3FFFFFF.toInt()),
            BoxShadow(inset = true, offsetX = 1f, offsetY = 1f, blur = 2f, spread = 0f, color = 0xCCFFFFFF.toInt()),
            BoxShadow(inset = true, offsetX = -1f, offsetY = -1f, blur = 2f, spread = 0f, color = 0x99FFFFFF.toInt()),
            BoxShadow(inset = true, offsetX = 0f, offsetY = 0f, blur = 10f, spread = 0.5f, color = 0x18000000)
        )
    }
}
