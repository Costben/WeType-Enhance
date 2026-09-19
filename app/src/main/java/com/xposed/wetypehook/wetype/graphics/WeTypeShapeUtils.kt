package com.xposed.wetypehook.wetype.graphics

import android.graphics.Path
import android.graphics.RectF
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline as ComposeOutline
import androidx.compose.ui.graphics.asAndroidPath
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
 * ColorOS 原生平滑圆角权重，与 [WeTypeColorOsMaterial] 下发给 `OplusBlurParam`
 * 的 `smoothCornerWeight` 必须一致，否则背景与边缘高光的圆角曲线对不上。
 */
internal const val WETYPE_COLOROS_SMOOTH_WEIGHT = 3f

private val colorOsPathAdapterClass: Class<*>? by lazy {
    runCatching { Class.forName("com.oplus.graphics.OplusPathAdapter") }.getOrNull()
}

/**
 * 与键盘背景取同一套圆角几何。
 *
 * ColorOS 的背景模糊由系统按 `smoothCornerType=1` + `smoothCornerWeight` 生成平滑圆角，
 * 模块自绘的边缘高光若继续走 [createWeTypeContinuousRoundedPath]（G2 连续圆角），两套曲线
 * 会有肉眼可见的偏差——高光边缘的圆角看起来比背景更大。这里在 ColorOS 上改用与背景完全
 * 相同的原生路径算法（`OplusPathAdapter` NEW_PATH_SMOOTH + 同权重），非 ColorOS 平台
 * 自动退回 G2 路径。
 */
internal fun createWeTypeSmoothRoundedPath(
    width: Float,
    height: Float,
    cornerRadii: WeTypeCornerRadii
): Path = createColorOsSmoothRoundedPath(width, height, cornerRadii)
    ?: createWeTypeContinuousRoundedPath(width, height, cornerRadii)

private fun createColorOsSmoothRoundedPath(
    width: Float,
    height: Float,
    cornerRadii: WeTypeCornerRadii
): Path? {
    val adapterClass = colorOsPathAdapterClass ?: return null
    if (width <= 0f || height <= 0f) return null
    return runCatching {
        val path = Path()
        val adapter = adapterClass
            .getConstructor(Path::class.java, Int::class.javaPrimitiveType)
            .newInstance(path, COLOROS_NEW_PATH_SMOOTH)
        adapterClass.getMethod(
            "addSmoothRoundRect",
            RectF::class.java,
            FloatArray::class.java,
            Path.Direction::class.java,
            Float::class.javaPrimitiveType
        ).invoke(
            adapter,
            RectF(0f, 0f, width, height),
            cornerRadii.toArray(),
            Path.Direction.CW,
            WETYPE_COLOROS_SMOOTH_WEIGHT
        )
        path.takeIf { !it.isEmpty }
    }.getOrNull()
}

private const val COLOROS_NEW_PATH_SMOOTH = 1

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
