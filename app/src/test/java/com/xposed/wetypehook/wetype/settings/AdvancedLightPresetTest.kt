package com.xposed.wetypehook.wetype.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「高级参数调节」的两条底线：
 *
 * - 内置目录项永远只读——自定义预设是它的**副本**，改副本不能回头动到目录。
 * - 副本的每一项都夹在既有滑杆的合法区间里，越界值在落盘前就被夹回去，手改偏好文件也顶不上去。
 */
class AdvancedLightPresetTest {

    private val baseId = MaterialPresetCatalog.DEFAULT_KEY

    @Test
    fun createdCopyInheritsItsSourceAndStaysEditableWithinRange() {
        val created = AdvancedLightPreset.create(baseId, index = 0, angle = 210)
        assertEquals(MaterialPresetCatalog.customPresetId(baseId, 0), created.id)
        assertEquals(baseId, created.baseId)
        assertEquals(210, created.angle)
        assertTrue(MaterialPresetCatalog.isCustom(created.id))
    }

    /** 复制来源必须是内置目录项；给一个不存在的 id 不能造出一个指向空气的副本。 */
    @Test
    fun createdCopyFallsBackToARealCatalogEntry() {
        val created = AdvancedLightPreset.create("not-a-preset", index = 0, angle = 45)
        assertEquals(MaterialPresetCatalog.DEFAULT_BACKGROUND, created.baseId)
    }

    @Test
    fun everyEditableFieldIsClampedToTheExistingRanges() {
        val wild = AdvancedLightPreset(
            id = "custom:coloros:0",
            label = "wild",
            baseId = baseId,
            angle = 999,
            edgeIntensity = 100_000,
            edgeWidth = 0,
            glowIntensity = 100_000,
            glowWidth = 0
        ).normalized()
        assertEquals(WeTypeSettings.MAX_EDGE_LIGHT_ANGLE, wild.angle)
        assertEquals(WeTypeSettings.MAX_EDGE_HIGHLIGHT_INTENSITY, wild.edgeIntensity)
        assertEquals(WeTypeSettings.MIN_EDGE_LIGHT_WIDTH, wild.edgeWidth)
        assertEquals(WeTypeSettings.MAX_GLOW_INTENSITY, wild.glowIntensity)
        assertEquals(WeTypeSettings.MIN_GLOW_WIDTH, wild.glowWidth)

        val negative = wild.copy(
            angle = -10,
            edgeIntensity = -1,
            edgeWidth = -9,
            glowIntensity = -1,
            glowWidth = -9
        ).normalized()
        assertEquals(WeTypeSettings.MIN_EDGE_LIGHT_ANGLE, negative.angle)
        assertEquals(0, negative.edgeIntensity)
        assertEquals(WeTypeSettings.MIN_EDGE_LIGHT_WIDTH, negative.edgeWidth)
        assertEquals(WeTypeSettings.MIN_GLOW_INTENSITY, negative.glowIntensity)
        assertEquals(WeTypeSettings.MIN_GLOW_WIDTH, negative.glowWidth)
    }

    @Test
    fun normalizationIsIdempotent() {
        val wild = AdvancedLightPreset(
            id = "custom:classic:1",
            label = "wild",
            baseId = "coui:edge-0",
            angle = 720,
            edgeIntensity = 400
        )
        assertEquals(wild.normalized(), wild.normalized().normalized())
    }

    /** 持久化：编码 → 解码必须逐项相同，重启后读回来的就是用户编辑过的那一份。 */    @Test
    fun encodedPresetsRoundTripThroughJson() {
        val presets = listOf(
            AdvancedLightPreset.create(baseId, index = 0, angle = 90).copy(
                edgeEnabled = false,
                edgeIntensity = 33,
                edgeWidth = 7,
                glowEnabled = true,
                glowIntensity = 12,
                glowWidth = 3
            ),
            AdvancedLightPreset.create("coui:edge-2", index = 1, angle = 270)
        )
        val restored = AdvancedLightPreset.decode(AdvancedLightPreset.encode(presets))
        assertEquals(presets, restored)
    }

    /**
     * 「增加预设」用的序号必须避开还活着的 id：删掉中间一项再加，不能撞上剩下的那一份，
     * 否则编辑其中一份会改到另一份。
     */
    @Test
    fun nextIndexAvoidsIdsStillInUse() {
        assertEquals(0, AdvancedLightPreset.nextIndex(emptyList()))

        val first = AdvancedLightPreset.create(baseId, index = 0, angle = 45)
        val second = AdvancedLightPreset.create(baseId, index = 1, angle = 45)
        assertEquals(2, AdvancedLightPreset.nextIndex(listOf(first, second)))

        // 删掉第 0 项之后，下一个空位是 0 而不是又拿 2 之外的重复号。
        assertEquals(0, AdvancedLightPreset.nextIndex(listOf(second)))
        // 不同家族共用同一套序号，所以不会出现 classic:1 与 coloros:1 撞在同一份列表里。
        val classic = AdvancedLightPreset.create("coui:edge-0", index = 0, angle = 45)
        assertEquals(1, AdvancedLightPreset.nextIndex(listOf(classic)))
    }

    /** 坏数据不能把设置页带崩：坏项整项丢掉，剩下的照读。 */
    @Test
    fun decodeDropsMalformedEntries() {
        assertTrue(AdvancedLightPreset.decode(null).isEmpty())
        assertTrue(AdvancedLightPreset.decode("").isEmpty())
        assertTrue(AdvancedLightPreset.decode("not json").isEmpty())

        val kept = AdvancedLightPreset.create(baseId, index = 0, angle = 45)
        val mixed = """
            [
              {"id": "builtin:not-custom", "label": "x", "baseId": "${MaterialPresetCatalog.DEFAULT_KEY}"},
              {"id": "custom:coloros:9", "label": "x", "baseId": "not-a-preset"},
              ${kept.toJson()}
            ]
        """.trimIndent()
        assertEquals(listOf(kept), AdvancedLightPreset.decode(mixed))
    }

    /** 越界的存量值在解码时就被夹回，不等到下一次编辑。 */
    @Test
    fun decodedOutOfRangeValuesAreClamped() {
        val raw = """
            [{
              "id": "custom:coloros:0",
              "label": "legacy",
              "baseId": "${MaterialPresetCatalog.DEFAULT_KEY}",
              "angle": 100000,
              "edgeEnabled": true,
              "edgeIntensity": 9999,
              "edgeWidth": 9999,
              "glowEnabled": true,
              "glowIntensity": 9999,
              "glowWidth": 9999
            }]
        """.trimIndent()
        val preset = AdvancedLightPreset.decode(raw).single()
        assertEquals(WeTypeSettings.MAX_EDGE_LIGHT_ANGLE, preset.angle)
        assertEquals(WeTypeSettings.MAX_EDGE_HIGHLIGHT_INTENSITY, preset.edgeIntensity)
        assertEquals(WeTypeSettings.MAX_EDGE_LIGHT_WIDTH, preset.edgeWidth)
        assertEquals(WeTypeSettings.MAX_GLOW_INTENSITY, preset.glowIntensity)
        assertEquals(WeTypeSettings.MAX_GLOW_WIDTH, preset.glowWidth)
    }

    /**
     * 套用到某一类元素：只有绘制侧真正读的那几个标量搬得动，且一定会把这一类打开。
     */
    @Test
    fun applyingAPresetStampsTheDrawableFacingFields() {
        val preset = AdvancedLightPreset.create(baseId, index = 0, angle = 180).copy(
            edgeEnabled = false,
            edgeIntensity = 44,
            edgeWidth = 5,
            glowEnabled = true,
            glowIntensity = 66,
            glowWidth = 8
        ).normalized()
        val applied = EdgeLightGroup(enabled = false, presetId = MaterialPresetCatalog.NONE).applying(preset)
        assertEquals(preset.id, applied.presetId)
        assertEquals(true, applied.enabled)
        assertEquals(false, applied.edgeEnabled)
        assertEquals(44, applied.edgeIntensity)
        assertEquals(5, applied.edgeWidth)
        assertEquals(true, applied.glowEnabled)
        assertEquals(66, applied.glowIntensity)
        assertEquals(8, applied.glowWidth)
    }

    /** 内置目录项本身不可变：套用副本、编辑副本都不会改到它。 */
    @Test
    fun builtInCatalogueEntriesStayReadOnly() {
        val before = MaterialPresetCatalog.all.map { it.toString() }
        val preset = AdvancedLightPreset.create(MaterialPresetCatalog.DEFAULT_KEY, index = 0, angle = 45)
            .copy(edgeIntensity = 1, glowIntensity = 1)
            .normalized()
        EdgeLightGroup().applying(preset)
        assertEquals(before, MaterialPresetCatalog.all.map { it.toString() })
        assertEquals(36, MaterialPresetCatalog.all.size)
    }

    /** 预设被删掉后，正在用它的这一类不能留一个查不到的 id。 */
    @Test
    fun droppingARemovedPresetFallsBackToNoPreset() {
        val preset = AdvancedLightPreset.create(baseId, index = 0, angle = 45)
        val applied = EdgeLightGroup().applying(preset)
        val dropped = applied.dropPreset(preset.id)
        assertEquals(MaterialPresetCatalog.NONE, dropped.presetId)
        assertFalse(dropped.enabled)
        // 无关的 id 不受影响
        assertEquals(applied, applied.dropPreset("custom:coloros:99"))
    }
}
