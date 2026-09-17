package com.xposed.wetypehook.wetype.hook

/**
 * Resolves a gesture label against the bounds of the key currently being drawn.
 *
 * Left/right are directional offsets kept as separate values for compatibility
 * with existing preferences: increasing left moves the label right, increasing
 * right moves it left. They must not be treated as insets of a shared row.
 */
internal fun resolveKeyLabelPosition(
    keyLeft: Float,
    keyTop: Float,
    keyRight: Float,
    keyBottom: Float,
    fontAscent: Float,
    fontDescent: Float,
    anchorTop: Boolean,
    marginLeft: Float,
    marginTop: Float,
    marginRight: Float,
    marginBottom: Float
): Pair<Float, Float>? {
    if (keyRight <= keyLeft || keyBottom <= keyTop) return null

    val keyCenterX = keyLeft + (keyRight - keyLeft) / 2f
    val x = keyCenterX + marginLeft - marginRight
    val y = if (anchorTop) {
        keyTop + marginTop - fontAscent
    } else {
        keyBottom - marginBottom - fontDescent
    }
    return x to y
}
