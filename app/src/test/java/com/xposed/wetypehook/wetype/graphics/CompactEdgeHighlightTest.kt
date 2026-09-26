package com.xposed.wetypehook.wetype.graphics

import com.xposed.wetypehook.wetype.settings.WeTypeSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactEdgeHighlightTest {
    @Test
    fun disablingHighlightOnlyRemovesSharpLayer() {
        val compact = WeTypeEdgeLightPreset.COMPACT
        assertEquals(0f, compact.layerScale(0, false, true, 2.4f, 1.5f, 0.5f), 0f)
        assertEquals(1.5f, compact.layerScale(1, false, true, 2.4f, 1.5f, 0.5f), 0f)
        assertEquals(1.5f, compact.layerScale(2, false, true, 2.4f, 1.5f, 0.5f), 0f)
        assertEquals(0.75f, compact.layerScale(3, false, true, 2.4f, 1.5f, 0.5f), 0f)
    }

    @Test
    fun disablingInnerGlowOnlyRemovesTheGlowLayers() {
        val compact = WeTypeEdgeLightPreset.COMPACT
        assertEquals(2.4f, compact.layerScale(0, true, false, 2.4f, 1.5f, 1f), 0f)
        assertEquals(0f, compact.layerScale(1, true, false, 2.4f, 1.5f, 1f), 0f)
        assertEquals(0f, compact.layerScale(3, true, false, 2.4f, 1.5f, 1f), 0f)
    }

    @Test
    fun highlightAndInnerGlowHaveIndependentIntensities() {
        val compact = WeTypeEdgeLightPreset.COMPACT
        assertEquals(2.4f, compact.layerScale(0, true, true, 2.4f, 0f, 1f), 0f)
        assertEquals(0f, compact.layerScale(1, true, true, 2.4f, 0f, 1f), 0f)
        assertEquals(0f, compact.layerScale(0, true, true, 0f, 1.5f, 1f), 0f)
        assertEquals(1.5f, compact.layerScale(1, true, true, 0f, 1.5f, 1f), 0f)
    }

    @Test
    fun widthScalesAreChosenPerGroup() {
        // 宽度不参与 layerScale，由 rebuild 按 index 选 edgeWidthScale / glowWidthScale；
        // 这里只钉住「第 0 层是边缘、其余是内发光」这条分组线。
        assertEquals(0, WeTypeEdgeLightPreset.COMPACT.boxShadows.indexOfFirst { it.spread < 0f })
        assertTrue(WeTypeEdgeLightPreset.COMPACT.boxShadows.size > 1)
    }

    @Test
    fun innerGlowFollowsGlowSliderOnlyAndStaysSoftAtBaseline() {
        assertEquals(0f, WeTypeSelfDrawnEdgeLight.glowLayerScale(0), 0f)
        assertEquals(0.5f, WeTypeSelfDrawnEdgeLight.glowLayerScale(50), 0f)
        // 100 就是上限：滑杆再往上没有档位，也不该有，见 MAX_GLOW_INTENSITY 的说明。
        assertEquals(1f, WeTypeSelfDrawnEdgeLight.glowLayerScale(WeTypeSettings.MAX_GLOW_INTENSITY), 0f)

        // 基准值下最亮的白层（0xCC）乘上亮度微调下限后仍须低于 255：顶到 255 会把模糊削平，
        // 内发光退化成贴着内缘的一圈硬边，这也是「强度」调不动那圈边的根因。
        val surfaceAlphaScaleFloor = 0.88f
        val baselineGlowAlpha = 0xCC * surfaceAlphaScaleFloor *
            WeTypeEdgeLightPreset.COMPACT.layerScale(
                index = 1,
                edgeHighlightEnabled = true,
                glowEnabled = true,
                edgeIntensity = WeTypeSelfDrawnEdgeLight.intensityScale(
                    WeTypeSettings.DEFAULT_EDGE_HIGHLIGHT_INTENSITY
                ),
                glowIntensity = WeTypeSelfDrawnEdgeLight.glowLayerScale(
                    WeTypeSettings.DEFAULT_GLOW_INTENSITY
                ),
                innerShadowScale = 1f
            )
        assertTrue("baseline inner glow layer alpha = $baselineGlowAlpha", baselineGlowAlpha < 255f)
    }

    @Test
    fun bothPresetsShareTheSameLayerRule() {
        // 两个预设只在阴影栈的数值上不同；分层规则（第 0 层是边缘、其余是内发光）必须一致，
        // 否则背板与键帽会出现「同一组开关、两套行为」。
        WeTypeEdgeLightPreset.entries.forEach { preset ->
            assertEquals(0f, preset.layerScale(0, false, true, 2.4f, 1.5f, 1f), 0f)
            assertEquals(2.4f, preset.layerScale(0, true, true, 2.4f, 1.5f, 1f), 0f)
            assertEquals(1.5f, preset.layerScale(1, true, true, 2.4f, 1.5f, 1f), 0f)
            assertEquals(0.75f, preset.layerScale(3, true, true, 2.4f, 1.5f, 0.5f), 0f)
        }
    }
}
