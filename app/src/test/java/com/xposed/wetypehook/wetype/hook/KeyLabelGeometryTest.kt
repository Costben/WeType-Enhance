package com.xposed.wetypehook.wetype.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeyLabelGeometryTest {

    @Test
    fun zeroHorizontalOffsetsUseEachKeysOwnCenter() {
        val first = position(left = 0f, right = 90f)
        val second = position(left = 90f, right = 210f)

        assertEquals(45f, first.first, 0f)
        assertEquals(150f, second.first, 0f)
    }

    @Test
    fun leftAndRightValuesAreFullDirectionalOffsets() {
        val shiftedRight = position(left = 20f, right = 100f, marginLeft = 8f)
        val shiftedLeft = position(left = 20f, right = 100f, marginRight = 8f)

        assertEquals(68f, shiftedRight.first, 0f)
        assertEquals(52f, shiftedLeft.first, 0f)
    }

    @Test
    fun equalHorizontalOffsetsKeepTheKeyCentered() {
        val result = position(left = 30f, right = 110f, marginLeft = 7f, marginRight = 7f)

        assertEquals(70f, result.first, 0f)
    }

    @Test
    fun verticalPositionUsesTheSelectedEdgeOfTheKey() {
        val top = position(anchorTop = true, marginTop = 3f)
        val bottom = position(anchorTop = false, marginBottom = 4f)

        assertEquals(25f, top.second, 0f)
        assertEquals(93f, bottom.second, 0f)
    }

    @Test
    fun emptyKeyBoundsAreRejected() {
        assertNull(
            resolveKeyLabelPosition(
                keyLeft = 10f,
                keyTop = 10f,
                keyRight = 10f,
                keyBottom = 40f,
                fontAscent = -12f,
                fontDescent = 3f,
                anchorTop = false,
                marginLeft = 0f,
                marginTop = 0f,
                marginRight = 0f,
                marginBottom = 0f
            )
        )
    }

    private fun position(
        left: Float = 10f,
        top: Float = 10f,
        right: Float = 110f,
        bottom: Float = 100f,
        anchorTop: Boolean = false,
        marginLeft: Float = 0f,
        marginTop: Float = 0f,
        marginRight: Float = 0f,
        marginBottom: Float = 0f
    ): Pair<Float, Float> = requireNotNull(
        resolveKeyLabelPosition(
            keyLeft = left,
            keyTop = top,
            keyRight = right,
            keyBottom = bottom,
            fontAscent = -12f,
            fontDescent = 3f,
            anchorTop = anchorTop,
            marginLeft = marginLeft,
            marginTop = marginTop,
            marginRight = marginRight,
            marginBottom = marginBottom
        )
    )
}
