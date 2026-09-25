package com.xposed.wetypehook.wetype.graphics

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阴影栈的两个预设。PANEL 是背板那条轨道，数值按真机像素量过，改一个数背板观感就变；
 * COMPACT 是键帽与圆形图标那条，结构与颜色照搬 PANEL、几何按小块面收小。
 */
class WeTypeEdgeLightPresetTest {

    private val panel = WeTypeEdgeLightPreset.PANEL.boxShadows
    private val compact = WeTypeEdgeLightPreset.COMPACT.boxShadows

    /**
     * 背板那套 CSS 阴影栈的原文，逐字节钉死：
     * `inset 2px 2px 0.25px -1.5px / inset 1px 1px 2px / inset -1px -1px 2px / inset 0 0 8px 1px`。
     */
    @Test
    fun panelPresetKeepsTheAuthoredCssStack() {
        assertEquals(
            listOf(
                BoxShadow(inset = true, offsetX = 2f, offsetY = 2f, blur = 0.25f, spread = -1.5f, color = 0xB3FFFFFF.toInt()),
                BoxShadow(inset = true, offsetX = 1f, offsetY = 1f, blur = 2f, spread = 0f, color = 0xCCFFFFFF.toInt()),
                BoxShadow(inset = true, offsetX = -1f, offsetY = -1f, blur = 2f, spread = 0f, color = 0x99FFFFFF.toInt()),
                BoxShadow(inset = true, offsetX = 0f, offsetY = 0f, blur = 8f, spread = 1f, color = 0x33000000)
            ),
            panel
        )
    }

    /** 紧凑预设只改几何：层数、是否内阴影、每层的颜色都必须是面板那套的复制。 */
    @Test
    fun compactPresetKeepsTheFourLayerStructureAndColours() {
        assertEquals(panel.size, compact.size)
        assertEquals(panel.map { it.inset }, compact.map { it.inset })
        assertEquals(panel.map { it.color }, compact.map { it.color })
    }

    /** 上/左上亮边 + 对侧弱亮边 + 一圈极淡暗内阴影：偏移方向不能画反。 */
    @Test
    fun compactPresetKeepsTheLightDirections() {
        assertTrue(compact[1].offsetX > 0f && compact[1].offsetY > 0f)
        assertTrue(compact[2].offsetX < 0f && compact[2].offsetY < 0f)
        assertEquals(0f, compact[3].offsetX, 0f)
        assertEquals(0f, compact[3].offsetY, 0f)
    }

    /**
     * 小块面用的几何必须真的更紧：blur 收到面板的 1/5~1/2，offset 收到 1/4~3/4。
     * 面板的 8dp 暗内阴影摊在 40dp 键帽上会糊掉整个键面，这条就是防止它被改回去。
     */
    @Test
    fun compactPresetShrinksEveryLength() {
        compact.zip(panel).forEachIndexed { index, (small, big) ->
            assertTrue("第 $index 层 blur 必须收小", small.blur < big.blur)
            assertTrue(
                "第 $index 层 blur 收得太少（${small.blur} / ${big.blur}）",
                small.blur <= big.blur * 0.5f
            )
            assertTrue(
                "第 $index 层 blur 收得太多（${small.blur} / ${big.blur}）",
                small.blur >= big.blur * 0.2f
            )
            if (big.offsetX != 0f) {
                assertTrue("第 $index 层 offsetX 必须收小", abs(small.offsetX) <= abs(big.offsetX))
            }
            assertTrue("第 $index 层 spread 必须收小", abs(small.spread) <= abs(big.spread))
        }
    }

    /**
     * 高光的可见宽度是 `offset + spread`：内阴影的洞按 spread 反向缩放后再平移 offset，
     * 两者相抵到 0 亮边就整条消失。两个预设的前两层都必须留出正的带宽。
     */
    @Test
    fun highlightLayersKeepAPositiveEdgeBand() {
        listOf(panel, compact).forEach { stack ->
            assertTrue(stack[0].offsetX + stack[0].spread > 0f)
            assertTrue(stack[1].offsetX + stack[1].spread > 0f)
        }
    }
}
