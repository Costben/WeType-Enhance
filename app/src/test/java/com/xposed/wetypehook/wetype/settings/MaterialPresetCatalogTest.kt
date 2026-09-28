package com.xposed.wetypehook.wetype.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MaterialPresetCatalogTest {
    @Test
    fun catalogueContainsAllThirtySixStableIds() {
        assertEquals(36, MaterialPresetCatalog.all.size)
        assertEquals(36, MaterialPresetCatalog.all.map { it.id }.toSet().size)
    }

    @Test
    fun normalModeFiltersByObject() {
        val iconOptions = MaterialPresetCatalog.options(MaterialPresetTarget.ICON, allowAll = false)
        assertTrue(iconOptions.any { it.id == MaterialPresetCatalog.DEFAULT_ICON })
        assertFalse(iconOptions.any { it.id == "systemui:notif-light" })
    }

    @Test
    fun advancedGateExposesEveryPresetToEveryObject() {
        MaterialPresetTarget.entries.forEach { target ->
            assertEquals(MaterialPresetCatalog.all, MaterialPresetCatalog.options(target, allowAll = true))
        }
    }

    /**
     * 分类三行的下拉第一项必须是「无预设」：它是这一类「不修改」的唯一表达，缺了它用户就只能
     * 在三套光感里挑一套，没法把某一类关掉。
     */
    @Test
    fun noneIsAlwaysTheFirstSelectableOption() {
        MaterialPresetTarget.entries.forEach { target ->
            listOf(false, true).forEach { allowAll ->
                assertEquals(
                    MaterialPresetCatalog.NONE,
                    MaterialPresetCatalog.selectableOptions(target, allowAll).first().id
                )
            }
        }
    }

    /** 「无预设」对三个对象都可选，且不能被按对象过滤掉。 */
    @Test
    fun noneIsOfferedToEveryTarget() {
        MaterialPresetTarget.entries.forEach { target ->
            assertTrue(
                MaterialPresetCatalog.options(target, allowAll = false)
                    .none { it.id == MaterialPresetCatalog.NONE }
            )
            assertTrue(
                MaterialPresetCatalog.selectableOptions(target, allowAll = false)
                    .any { it.id == MaterialPresetCatalog.NONE }
            )
        }
    }

    @Test
    fun noneAndCustomIdsSurviveNormalization() {
        MaterialPresetTarget.entries.forEach { target ->
            assertEquals(MaterialPresetCatalog.NONE, MaterialPresetCatalog.normalize(MaterialPresetCatalog.NONE, target))
            assertEquals(
                "custom:coloros:3",
                MaterialPresetCatalog.normalize("custom:coloros:3", target)
            )
            assertTrue(MaterialPresetCatalog.isResolvable(MaterialPresetCatalog.NONE))
            assertTrue(MaterialPresetCatalog.isResolvable("custom:classic:0"))
            assertFalse(MaterialPresetCatalog.isResolvable("nonsense"))
        }
    }

    /** 自定义预设的家族写在 id 里，渲染侧不用读偏好就能取到该用哪套阴影栈。 */
    @Test
    fun customPresetIdsCarryTheirShadowFamily() {
        assertEquals(
            MaterialPresetCatalog.FAMILY_CLASSIC,
            MaterialPresetCatalog.shadowFamily("coui:edge-0")
        )
        assertEquals(
            MaterialPresetCatalog.FAMILY_COLOROS,
            MaterialPresetCatalog.shadowFamily(MaterialPresetCatalog.DEFAULT_KEY)
        )
        assertEquals(
            "custom:classic:1",
            MaterialPresetCatalog.customPresetId("coui:edge-1", 1)
        )
        assertEquals(
            "custom:coloros:1",
            MaterialPresetCatalog.customPresetId(MaterialPresetCatalog.DEFAULT_KEY, 1)
        )
    }

    @Test
    fun extendedGroupEncodingRoundTripsTheSelectedPreset() {
        val group = EdgeLightGroup(presetId = "settings:back-circle-light")
        assertEquals(group, EdgeLightGroup.parse(group.textWithPreset(), EdgeLightGroup()))
    }
}
