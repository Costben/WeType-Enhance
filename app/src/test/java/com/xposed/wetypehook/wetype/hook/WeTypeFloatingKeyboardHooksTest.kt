package com.xposed.wetypehook.wetype.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeTypeFloatingKeyboardHooksTest {
    @Test
    fun panelRadiusFollowsTheHostWhenItCanBeRead() {
        // 216 实机：宿主面板圆角 50px（density 3.0）。
        assertEquals(50f, resolveFloatingPanelRadiusPx(50f, 3f), 0f)
    }

    @Test
    fun panelRadiusFallsBackToDpWhenTheHostRadiusIsMissingOrNotPositive() {
        assertEquals(48f, resolveFloatingPanelRadiusPx(null, 3f), 0f)
        assertEquals(48f, resolveFloatingPanelRadiusPx(0f, 3f), 0f)
        // Outline.getRadius() 对非圆角矩形轮廓返回 -1。
        assertEquals(48f, resolveFloatingPanelRadiusPx(-1f, 3f), 0f)
    }

    @Test
    fun onlyTheHostPanelColourIsTreatedAsTheOpaqueFill() {
        val lightFill = 0xFFE5E6EB.toInt()
        assertTrue(isHostPanelFill(lightFill, lightFill))
        // 深色皮肤的面板底另算一个色值，随本体的色值走。
        assertTrue(isHostPanelFill(0xFF1E1E1E.toInt(), 0xFF1E1E1E.toInt()))
        // 候选栏药丸、按键帽、拖动条（#1A000000）不是面板底，不能被清掉。
        assertFalse(isHostPanelFill(0x1A000000, lightFill))
        assertFalse(isHostPanelFill(0xFFFFFFFF.toInt(), lightFill))
    }

    @Test
    fun anUnknownOrFullyTransparentColourIsNeverAFill() {
        val lightFill = 0xFFE5E6EB.toInt()
        assertFalse(isHostPanelFill(null, lightFill))
        assertFalse(isHostPanelFill(lightFill, null))
        // 已经被我们清成透明的视图不再命中，避免每帧重复替换。
        assertFalse(isHostPanelFill(0, 0))
    }
}
