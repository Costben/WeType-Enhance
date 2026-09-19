package com.xposed.wetypehook.wetype.graphics

import android.graphics.Path
import android.graphics.RectF
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline as ComposeOutline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.kyant.capsule.ContinuousRoundedRectangle
import com.kyant.capsule.continuities.G2Continuity
import com.kyant.capsule.continuities.G2ContinuityProfile

internal data class WeTypeCornerRadii(
    val topLeft: Float,
    val topRight: Float,
    val bottomRight: Float,
    val bottomLeft: Float
) {
    fun inset(amount: Float): WeTypeCornerRadii = WeTypeCornerRadii(
        topLeft = (topLeft - amount).coerceAtLeast(0f),
        topRight = (topRight - amount).coerceAtLeast(0f),
        bottomRight = (bottomRight - amount).coerceAtLeast(0f),
        bottomLeft = (bottomLeft - amount).coerceAtLeast(0f)
    )

    fun outset(amount: Float): WeTypeCornerRadii = WeTypeCornerRadii(
        topLeft = topLeft + amount,
        topRight = topRight + amount,
        bottomRight = bottomRight + amount,
        bottomLeft = bottomLeft + amount
    )

    fun maxRadius(): Float = maxOf(topLeft, topRight, bottomRight, bottomLeft)

    fun toArray(): FloatArray = floatArrayOf(
        topLeft, topLeft,
        topRight, topRight,
        bottomRight, bottomRight,
        bottomLeft, bottomLeft
    )

    companion object {
        fun uniform(radius: Float): WeTypeCornerRadii = WeTypeCornerRadii(
            topLeft = radius,
            topRight = radius,
            bottomRight = radius,
            bottomLeft = radius
        )
    }
}

private val weTypeSmoothContinuity = G2Continuity(
    profile = G2ContinuityProfile(
        extendedFraction = 0.66,
        arcFraction = 0.38,
        bezierCurvatureScale = 1.10,
        arcCurvatureScale = 1.10
    ),
    capsuleProfile = G2ContinuityProfile.Capsule
)

/**
 * ColorOS 标准圆角权重（2 表示关闭平滑曲线），与 [WeTypeColorOsMaterial] 下发给 `OplusBlurParam`
 * 的 `smoothCornerWeight` 必须一致，否则背景与边缘高光的圆角曲线对不上。
 */
internal const val WETYPE_COLOROS_SMOOTH_WEIGHT = 2f

/**
 * Use the same standard rounded rectangle as the ColorOS compositor with weight=2.
 * Its weight=3 blur silhouette differs from the framework Path, even with identical radii.
 * Keeping background, clipping and highlight on circular arcs avoids a second visible contour.
 */
internal fun createWeTypeSmoothRoundedPath(
    width: Float,
    height: Float,
    cornerRadii: WeTypeCornerRadii
): Path {
    if (!WeTypeSystemMaterials.isColorOsBackend()) {
        return createWeTypeContinuousRoundedPath(width, height, cornerRadii)
    }
    return Path().apply {
        if (width > 0f && height > 0f) {
            addRoundRect(RectF(0f, 0f, width, height), cornerRadii.toArray(), Path.Direction.CW)
        }
    }
}

/**
 * Compose 侧的 [Shape]，几何与 [createWeTypeSmoothRoundedPath] 完全一致。
 *
 * 设置预览此前用 kyant G2 圆角裁剪背景、却用原生平滑路径绘制内高光，两者在 ColorOS 上
 * 曲率不同；换成同一个 [Shape] 后预览与真机保持同一套几何。
 */
internal data class WeTypeSmoothRoundedShape(
    val cornerRadii: WeTypeCornerRadii
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): ComposeOutline = ComposeOutline.Generic(
        createWeTypeSmoothRoundedPath(size.width, size.height, cornerRadii).asComposePath()
    )
}

internal fun createWeTypeContinuousRoundedPath(
    width: Float,
    height: Float,
    cornerRadii: WeTypeCornerRadii
): Path {
    val outline = ContinuousRoundedRectangle(
        topStart = CornerSize(cornerRadii.topLeft),
        topEnd = CornerSize(cornerRadii.topRight),
        bottomEnd = CornerSize(cornerRadii.bottomRight),
        bottomStart = CornerSize(cornerRadii.bottomLeft),
        continuity = weTypeSmoothContinuity
    ).createOutline(
        size = Size(width, height),
        layoutDirection = LayoutDirection.Ltr,
        density = Density(1f)
    )
    return when (outline) {
        is ComposeOutline.Generic -> outline.path.asAndroidPath()
        is ComposeOutline.Rounded -> Path().apply {
            addRoundRect(
                0f,
                0f,
                width,
                height,
                cornerRadii.toArray(),
                Path.Direction.CW
            )
        }
        is ComposeOutline.Rectangle -> Path().apply {
            addRect(0f, 0f, width, height, Path.Direction.CW)
        }
    }
}

internal fun createWeTypeContinuousRoundedPath(
    width: Float,
    height: Float,
    radius: Float
): Path = createWeTypeContinuousRoundedPath(width, height, WeTypeCornerRadii.uniform(radius))
