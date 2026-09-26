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
 * [PANEL] is the stack the keyboard panel was tuned against — hundreds of dp across, so its 8dp
 * dark inner shadow and 2dp white blurs read as a soft edge. That dark layer is also what turns
 * "raise 强度" into "recess the inner edge", so the panel call sites drop it (`innerShadowScale = 0`)
 * and keep only the white layers. Stamped onto a 40dp key cap or a 24dp
 * circular icon the same numbers smear across most of the surface, so [COMPACT] keeps the four-layer
 * structure and colours but pulls every length down to the piece size.
 */
internal enum class WeTypeEdgeLightPreset {
    PANEL,
    COMPACT;

    internal val boxShadows: List<BoxShadow>
        get() = when (this) {
            PANEL -> PANEL_BOX_SHADOWS
            COMPACT -> COMPACT_BOX_SHADOWS
        }

    /**
     * How strongly each layer of [boxShadows] is painted.
     *
     * Layer 0 is the crisp edge highlight; every later layer belongs to the inner glow (the white
     * bloom layers and the stack's single black one). The two groups therefore answer to their own
     * switch and their own intensity — a category can keep its inner glow while its edge highlight
     * is off, and vice versa. [innerShadowScale] still damps the black layer alone.
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
 * [preset] picks the shadow stack itself: [WeTypeEdgeLightPreset.PANEL] is the stack documented
 * above, [WeTypeEdgeLightPreset.COMPACT] the one for key caps and round icons.
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
    private val preset: WeTypeEdgeLightPreset = WeTypeEdgeLightPreset.PANEL
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
        val rotation = Math.toRadians((lightAngleDegrees - BASE_LIGHT_ANGLE_DEGREES).toDouble())
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
 * The authored stack, byte for byte, and the reference the class doc's CSS is copied from: nothing
 * here may drift while [WeTypeEdgeLightPreset.COMPACT] is tuned. The keyboard panel runs it with the
 * black layer switched off (`innerShadowScale = 0`) — see the two call sites in `WeTypeWindowHooks`.
 */
private val PANEL_BOX_SHADOWS = listOf(
    BoxShadow(inset = true, offsetX = 2f, offsetY = 2f, blur = 0.25f, spread = -1.5f, color = 0xB3FFFFFF.toInt()),
    BoxShadow(inset = true, offsetX = 1f, offsetY = 1f, blur = 2f, spread = 0f, color = 0xCCFFFFFF.toInt()),
    BoxShadow(inset = true, offsetX = -1f, offsetY = -1f, blur = 2f, spread = 0f, color = 0x99FFFFFF.toInt()),
    BoxShadow(inset = true, offsetX = 0f, offsetY = 0f, blur = 8f, spread = 1f, color = 0x33000000)
)

/**
 * Same four layers and the same colours, retuned for small surfaces (~24dp–40dp: key caps and the
 * round toolbar icons). Offsets and spreads are halved and blurs drop to 0.3–0.4× of the panel
 * values, so the highlight reads as an edge rather than a haze that swallows the surface: on a 40dp
 * key cap the panel's 8dp dark blur alone spans a fifth of the piece, while its 2dp white blurs wash
 * out the hairline highlight.
 */
private val COMPACT_BOX_SHADOWS = listOf(
    BoxShadow(inset = true, offsetX = 1f, offsetY = 1f, blur = 0.1f, spread = -0.75f, color = 0xB3FFFFFF.toInt()),
    BoxShadow(inset = true, offsetX = 0.5f, offsetY = 0.5f, blur = 0.75f, spread = 0f, color = 0xCCFFFFFF.toInt()),
    BoxShadow(inset = true, offsetX = -0.5f, offsetY = -0.5f, blur = 0.75f, spread = 0f, color = 0x99FFFFFF.toInt()),
    BoxShadow(inset = true, offsetX = 0f, offsetY = 0f, blur = 2.5f, spread = 0.5f, color = 0x33000000)
)
