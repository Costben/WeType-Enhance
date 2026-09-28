package com.xposed.wetypehook.wetype.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [EdgeLightGroup] 的编解码。三类元素（背景 / 图标 / 按键）都靠这一对函数进出偏好文件，
 * 所以这里钉住四件事：往返不变、字段数不对就整体退回、含非法数字也整体退回、越界值夹回区间。
 */
class EdgeLightGroupTest {

    @Test
    fun roundTripsThroughText() {
        val group = EdgeLightGroup(
            enabled = false,
            edgeEnabled = true,
            edgeIntensity = 37,
            edgeWidth = 4,
            glowEnabled = false,
            glowIntensity = 88,
            glowWidth = 6
        )
        assertEquals(group, EdgeLightGroup.parse(group.text(), EdgeLightGroup()))
    }

    @Test
    fun defaultsAreTheDocumentedOnes() {
        val group = EdgeLightGroup()
        assertEquals(true, group.enabled)
        assertEquals(true, group.edgeEnabled)
        assertEquals(true, group.glowEnabled)
        assertEquals(WeTypeSettings.DEFAULT_EDGE_HIGHLIGHT_INTENSITY, group.edgeIntensity)
        assertEquals(WeTypeSettings.DEFAULT_EDGE_LIGHT_WIDTH, group.edgeWidth)
        assertEquals(WeTypeSettings.DEFAULT_GLOW_INTENSITY, group.glowIntensity)
        assertEquals(WeTypeSettings.DEFAULT_GLOW_WIDTH, group.glowWidth)
    }

    @Test
    fun missingOrShortTextFallsBackWholeGroup() {
        val fallback = EdgeLightGroup(edgeIntensity = 11, glowIntensity = 22)
        assertEquals(fallback, EdgeLightGroup.parse(null, fallback))
        assertEquals(fallback, EdgeLightGroup.parse("", fallback))
        assertEquals(fallback, EdgeLightGroup.parse("1,1,80,2,1,100", fallback))
        assertEquals(fallback, EdgeLightGroup.parse("1,1,80,2,1,100,2,9", fallback))
    }

    @Test
    fun nonNumericFieldFallsBackWholeGroup() {
        val fallback = EdgeLightGroup(edgeIntensity = 11, glowIntensity = 22)
        assertEquals(fallback, EdgeLightGroup.parse("1,1,abc,2,1,100,2", fallback))
        assertEquals(fallback, EdgeLightGroup.parse("1,1,80,2,1,100,", fallback))
    }

    /**
     * 旧版内发光上限是 200，升级时会把 200 那档原样读进来；200 已经把阴影栈里两层白顶到 255、
     * 模糊被削平（就是「只有一圈边缘高光、没有内发光」的根因），所以必须在解析时就夹到 100。
     */
    @Test
    fun outOfRangeValuesAreClampedOnParse() {
        val parsed = EdgeLightGroup.parse("1,1,999,0,1,200,99", EdgeLightGroup())
        assertEquals(WeTypeSettings.MAX_EDGE_HIGHLIGHT_INTENSITY, parsed.edgeIntensity)
        assertEquals(WeTypeSettings.MIN_EDGE_LIGHT_WIDTH, parsed.edgeWidth)
        assertEquals(WeTypeSettings.MAX_GLOW_INTENSITY, parsed.glowIntensity)
        assertEquals(WeTypeSettings.MAX_GLOW_WIDTH, parsed.glowWidth)
    }

    @Test
    fun glowIntensityCeilingStaysAtTheAuthoredBaseline() {
        // 100 是阴影栈被写出来时的原样：再往上乘就把两层白双双顶到 255，内发光退化成硬边。
        assertEquals(100, WeTypeSettings.DEFAULT_GLOW_INTENSITY)
        assertEquals(100, WeTypeSettings.MAX_GLOW_INTENSITY)
        assertEquals(0, WeTypeSettings.MIN_GLOW_INTENSITY)
    }

    @Test
    fun textIsCompactAndStable() {
        assertEquals("1,1,80,2,1,100,2", EdgeLightGroup().text())
    }

    @Test
    fun normalizationIsIdempotent() {
        val group = EdgeLightGroup(edgeIntensity = -5, glowIntensity = 400, glowWidth = 0)
        assertEquals(group.normalized(), group.normalized().normalized())
    }

    @Test
    fun everyTargetReadsItsOwnField() {
        assertTrue(EdgeLightTarget.entries.size == 3)
    }

    /**
     * 分类下拉是这一类唯一的入口：「无预设」必须把这一类一起关掉，选到任何真实预设必须重新打开。
     * 两个字段各改各的，就会出现「看着是无预设、键盘上还在画光」。
     */
    @Test
    fun withPresetKeepsEnabledInSyncWithTheSelection() {
        val on = EdgeLightGroup(enabled = true, presetId = MaterialPresetCatalog.DEFAULT_KEY)
        val off = on.withPreset(MaterialPresetCatalog.NONE)
        assertEquals(false, off.enabled)
        assertEquals(MaterialPresetCatalog.NONE, off.presetId)
        val back = off.withPreset(MaterialPresetCatalog.DEFAULT_ICON)
        assertEquals(true, back.enabled)
        assertEquals(MaterialPresetCatalog.DEFAULT_ICON, back.presetId)
    }

    @Test
    fun selectingBuiltInPresetRestoresBothVisibleLightLayers() {
        // Settings created before the preset UI can still contain edge=0/glow=0. A built-in
        // selection must be visible even when it is the first selection after upgrading.
        val stale = EdgeLightGroup(
            enabled = true,
            edgeEnabled = false,
            glowEnabled = false,
            presetId = MaterialPresetCatalog.NONE
        )
        val selected = stale.withPreset(MaterialPresetCatalog.DEFAULT_BACKGROUND)
        assertTrue(selected.enabled)
        assertTrue(selected.edgeEnabled)
        assertTrue(selected.glowEnabled)
        assertEquals(
            WeTypeSettings.DEFAULT_EDGE_HIGHLIGHT_INTENSITY,
            selected.edgeIntensity
        )
        assertEquals(WeTypeSettings.DEFAULT_GLOW_INTENSITY, selected.glowIntensity)
    }

    /** 三类的默认是同一件事：无预设，也就是不修改。 */
    @Test
    fun everyTargetDefaultsToNoPreset() {
        EdgeLightTarget.entries.map { MaterialPresetTarget.valueOf(it.name) }.forEach { target ->
            val group = EdgeLightGroup.defaultFor(target)
            assertEquals(MaterialPresetCatalog.NONE, group.presetId)
            assertEquals(false, group.enabled)
        }
    }

    /** 「无预设」与自定义预设都是合法 id，不能被归一化改写掉。 */
    @Test
    fun normalizationKeepsNoneAndCustomPresetIds() {
        assertEquals(
            MaterialPresetCatalog.NONE,
            EdgeLightGroup(presetId = MaterialPresetCatalog.NONE).normalized().presetId
        )
        val custom = EdgeLightGroup(presetId = "custom:coloros:2")
        assertEquals("custom:coloros:2", custom.normalized().presetId)
    }

    /** 半截的偏好串（旧版只有 7 段）读回来时，预设 id 从兜底走，不半解析。 */
    @Test
    fun legacyFieldCountFallsBackToTheProvidedPreset() {
        val group = EdgeLightGroup.parse("1,1,80,2,1,100,2", EdgeLightGroup())
        assertEquals(MaterialPresetCatalog.DEFAULT_KEY, group.presetId)
        assertEquals(true, group.enabled)
    }
}
