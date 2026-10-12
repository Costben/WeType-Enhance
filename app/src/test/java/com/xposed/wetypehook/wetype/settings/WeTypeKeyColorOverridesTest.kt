package com.xposed.wetypehook.wetype.settings

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeTypeKeyColorOverridesTest {

    @Test fun decodesInvertAlongsideColors() {
        val overrides = WeTypeKeyColorOverrides.decode(
            """{"enabled":true,"keys":{"q":"brand","w":"#FF202080"},"invert":["w"]}"""
        )
        assertTrue(overrides.enabled)
        assertEquals(WeTypeKeyColorOverrides.BRAND, overrides.colors["q"])
        assertEquals("#FF202080", overrides.colors["w"])
        assertEquals(setOf("w"), overrides.invert)
    }

    /** 反色只对已设色的键有意义，孤立 token 读进来直接丢掉。 */
    @Test fun invertEntriesWithoutAColorAreDropped() {
        val overrides = WeTypeKeyColorOverrides.decode(
            """{"enabled":true,"keys":{"q":"brand"},"invert":["q","nope"]}"""
        )
        assertEquals(setOf("q"), overrides.invert)
    }

    @Test fun encodeRoundTripsColorsAndInvert() {
        val overrides = PerKeyColorOverrides(
            enabled = true,
            colors = mapOf("q" to WeTypeKeyColorOverrides.BRAND, "w" to "#FF202080"),
            invert = setOf("w")
        )
        assertEquals(overrides, WeTypeKeyColorOverrides.decode(WeTypeKeyColorOverrides.encode(overrides)))
    }

    /** 关掉总开关只是停止生效，已调好的颜色与反色都要留着。 */
    @Test fun disablingKeepsColorsAndInvert() {
        val overrides = PerKeyColorOverrides(
            enabled = false,
            colors = mapOf("q" to WeTypeKeyColorOverrides.BRAND),
            invert = setOf("q")
        )
        assertEquals(overrides, WeTypeKeyColorOverrides.decode(WeTypeKeyColorOverrides.encode(overrides)))
    }

    /** 清掉某键颜色时它的反色记录一并消失，不留一条永远读不回来的条目。 */
    @Test fun clearingAColorAlsoClearsItsInvert() {
        val cleared = PerKeyColorOverrides(
            enabled = true,
            colors = mapOf("w" to "#FF202080"),
            invert = setOf("w")
        ).withColor("w", null)
        assertTrue(cleared.invert.isEmpty())
        assertFalse(JSONObject(WeTypeKeyColorOverrides.encode(cleared)).has("invert"))
    }

    /** 落盘前与 colors 取交集：脏数据不该被原样写回去。 */
    @Test fun encodeNeverWritesAnInvertForAnUncoloredKey() {
        val encoded = JSONObject(
            WeTypeKeyColorOverrides.encode(
                PerKeyColorOverrides(
                    enabled = true,
                    colors = mapOf("q" to WeTypeKeyColorOverrides.BRAND),
                    invert = setOf("q", "w")
                )
            )
        )
        val invert = encoded.getJSONArray("invert")
        assertEquals(1, invert.length())
        assertEquals("q", invert.getString(0))
    }

    @Test fun withInvertTogglesOnlyTheGivenToken() {
        val overrides = PerKeyColorOverrides(
            enabled = true,
            colors = mapOf("q" to WeTypeKeyColorOverrides.BRAND, "w" to "#FF202080")
        )
        assertEquals(setOf("w"), overrides.withInvert("w", true).invert)
        assertEquals(setOf("w"), overrides.withInvert("w", true).withInvert("w", true).invert)
        assertTrue(overrides.withInvert("w", true).withInvert("w", false).invert.isEmpty())
    }

    @Test fun emptyOverridesEncodeToEmptyString() {
        assertEquals("", WeTypeKeyColorOverrides.encode(PerKeyColorOverrides()))
        assertEquals(PerKeyColorOverrides(), WeTypeKeyColorOverrides.decode(""))
        assertEquals(PerKeyColorOverrides(), WeTypeKeyColorOverrides.decode("not json"))
    }
}
