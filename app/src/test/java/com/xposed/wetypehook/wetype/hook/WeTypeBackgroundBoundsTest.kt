package com.xposed.wetypehook.wetype.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 本文件随上游同步引入（b7184ee）。其后 b497e38 按实机证据放宽了两条判定，用例已同步改写：
 * 不再用 `isLayoutRequested` 一票否决整次采样；相对顶端为 0 的内容视图直接判脏。
 * 再次同步上游时不要把这两条改回去，理由见 `resolveWeTypeBackgroundBounds` 的注释。
 */
class WeTypeBackgroundBoundsTest {
    private val decor = layout(0, 2400)

    @Test
    fun doesNotUseContentThatHasNotBeenLaidOutYet() {
        assertNull(resolveWeTypeBackgroundBounds(decor, listOf(
            layout(0, 0).copy(isLaidOut = false)
        )))
        assertNull(resolveWeTypeBackgroundBounds(decor, listOf(
            layout(1000, 1400).copy(isLaidOut = false)
        )))
    }

    @Test
    fun ignoresPendingLayoutBecauseTheHostNeverSettlesIt() {
        // 微信输入法在键盘显示后持续 requestLayout()，decor 与内容视图的 isLayoutRequested
        // 恒为 true。把它当硬失败会让背板一次都拿不到有效 bounds，永久隐藏。
        assertEquals(WeTypeBackgroundBounds(1000, 1400), resolveWeTypeBackgroundBounds(
            decor, listOf(layout(1000, 1400).copy(isLayoutRequested = true))
        ))
        assertEquals(WeTypeBackgroundBounds(1400, 1000), resolveWeTypeBackgroundBounds(
            decor.copy(isLayoutRequested = true), listOf(layout(1400, 1000))
        ))
        assertEquals(WeTypeBackgroundBounds(1400, 1000), resolveWeTypeBackgroundBounds(
            decor, listOf(layout(1400, 80), layout(1480, 920).copy(isLayoutRequested = true))
        ))
    }

    @Test
    fun missingOrHiddenContentDoesNotBecomeAFullWindowBackground() {
        assertNull(resolveWeTypeBackgroundBounds(decor, emptyList()))
        assertNull(resolveWeTypeBackgroundBounds(decor, listOf(layout(0, 2400).copy(isShown = false))))
        assertNull(resolveWeTypeBackgroundBounds(decor, listOf(layout(0, 0))))
    }

    @Test
    fun hiddenOrUnlaidOutDecorYieldsNoBounds() {
        assertNull(resolveWeTypeBackgroundBounds(
            decor.copy(isShown = false), listOf(layout(1400, 1000))
        ))
        assertNull(resolveWeTypeBackgroundBounds(
            decor.copy(isLaidOut = false), listOf(layout(1400, 1000))
        ))
    }

    @Test
    fun rejectsPositionsOutsideTheDecorInsteadOfClampingThemToFullHeight() {
        assertNull(resolveWeTypeBackgroundBounds(decor, listOf(layout(-100, 1000))))
        assertNull(resolveWeTypeBackgroundBounds(decor, listOf(layout(2400, 1000))))
    }

    @Test
    fun usesDecorLocalCoordinates() {
        assertEquals(WeTypeBackgroundBounds(400, 600), resolveWeTypeBackgroundBounds(
            layout(200, 1000), listOf(layout(600, 600))
        ))
    }

    @Test
    fun rejectsContentFlushWithTheDecorTop() {
        // 相对顶端为 0 时算出的背板高度必然等于 decor 高度，调用方 collectBackgroundBounds
        // 的 `height < decorHeight` 校验同样会丢弃它，这里直接判脏。
        assertNull(resolveWeTypeBackgroundBounds(
            layout(200, 1000), listOf(layout(200, 1000))
        ))
        assertNull(resolveWeTypeBackgroundBounds(
            decor, listOf(layout(0, 2400))
        ))
    }

    @Test
    fun tracksPositionOnlyChangesAndKeepsTheBottomBarCovered() {
        assertEquals(WeTypeBackgroundBounds(1400, 1000), resolveWeTypeBackgroundBounds(
            decor, listOf(layout(1400, 80), layout(1480, 820))
        ))
        assertEquals(WeTypeBackgroundBounds(1500, 900), resolveWeTypeBackgroundBounds(
            decor, listOf(layout(1500, 80), layout(1580, 820))
        ))
    }

    private fun layout(top: Int, height: Int) = WeTypeBackgroundLayout(top, height, true, true, false)
}
