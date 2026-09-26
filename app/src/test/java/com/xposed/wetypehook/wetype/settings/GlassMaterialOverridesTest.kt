package com.xposed.wetypehook.wetype.settings

import org.junit.Assert.*
import org.junit.Test

class GlassMaterialOverridesTest {
    @Test fun emptyFieldsKeepAllNativeDefaults() {
        assertEquals(GlassMaterialOverrides(), GlassMaterialOverrides.parse(emptyMap()))
        assertEquals(GlassMaterialOverrides(), GlassMaterialOverrides.read(emptyMap()))
    }

    @Test fun parsesNativeArrayLengthsAndUnits() {
        val overrides = GlassMaterialOverrides.parse(mapOf(
            GlassOverrideField.GLASS to List(42) { "0.5" }.joinToString(","),
            GlassOverrideField.BLOOM to List(16) { "1" }.joinToString("，"),
            GlassOverrideField.BLUR_RADII to "[12, 140]",
            GlassOverrideField.MATERIAL_TYPE to "1"
        ))
        assertEquals(42, overrides.glass!!.size)
        assertEquals(16, overrides.bloom!!.size)
        assertEquals(listOf(12, 140), overrides.blurRadii)
        assertEquals(1, overrides.materialType)
        assertEquals(overrides, GlassMaterialOverrides.parse(GlassOverrideField.entries.associateWith { overrides.text(it) }))
    }

    @Test fun rejectsIncorrectNativeArrayLengths() {
        for (count in listOf(1, 41, 43)) {
            assertFalse(GlassMaterialOverrides.isValid(GlassOverrideField.GLASS, List(count) { "0" }.joinToString(",")))
        }
        for (count in listOf(1, 11, 17)) {
            assertFalse(GlassMaterialOverrides.isValid(GlassOverrideField.BLOOM, List(count) { "0" }.joinToString(",")))
        }
        for (count in 12..16) {
            assertTrue(GlassMaterialOverrides.isValid(GlassOverrideField.BLOOM, List(count) { "0" }.joinToString(",")))
        }
    }

    @Test fun rejectsNonFiniteValuesAndIncompleteEdits() {
        for (value in listOf("NaN", "Infinity", "-Infinity", "1e99", "oops")) {
            assertFalse(GlassMaterialOverrides.isValid(GlassOverrideField.GLASS, (List(41) { "1" } + value).joinToString(",")))
        }
        for (value in listOf("[]", "1,", ",1", "1,,2", "1, 2.5", "-1, 2", "1, 401")) {
            assertFalse(GlassMaterialOverrides.isValid(GlassOverrideField.BLUR_RADII, value))
        }
        for (value in listOf("-1", "2", "1.0", "0,1")) {
            assertFalse(GlassMaterialOverrides.isValid(GlassOverrideField.MATERIAL_TYPE, value))
        }
    }

    @Test fun corruptStoredArraysDoNotReachNativeRendering() {
        assertEquals(GlassMaterialOverrides(), GlassMaterialOverrides.read(mapOf("glass_params_count" to Int.MAX_VALUE)))
        assertEquals(GlassMaterialOverrides(), GlassMaterialOverrides.read(mapOf("glass_params_count" to 42)))
        assertEquals(GlassMaterialOverrides(), GlassMaterialOverrides.read(mapOf("glass_blur_small" to 12)))
        assertEquals(GlassMaterialOverrides(), GlassMaterialOverrides.read(mapOf("glass_material_type" to 2)))
    }

    @Test fun readsIndexedPreferencesWithoutConvertingPixels() {
        val stored = mutableMapOf<String, Any>(
            "glass_params_count" to 42, "glass_bloom_count" to 12,
            "glass_blur_small" to 0, "glass_blur_large" to 400, "glass_material_type" to 0
        )
        repeat(42) { stored["glass_params_$it"] = it.toFloat() }
        repeat(12) { stored["glass_bloom_$it"] = it / 10f }
        val read = GlassMaterialOverrides.read(stored)
        assertEquals(41f, read.glass!!.last())
        assertEquals(1.1f, read.bloom!!.last())
        assertEquals(listOf(0, 400), read.blurRadii)
        assertEquals(0, read.materialType)
    }

    @Test fun enablingGlassSelectsTheGlassRendererAndDisablingRestoresDefaults() {
        val otherOverrides = GlassMaterialOverrides(blurRadii = listOf(12, 140), bloom = List(12) { 0.5f })
        val enabled = otherOverrides.withGlassEnabled(true)
        assertEquals(1, enabled.materialType)
        assertEquals(42, enabled.glass!!.size)
        assertTrue(enabled.glass.all { it.isFinite() })
        assertEquals(otherOverrides.blurRadii, enabled.blurRadii)
        assertEquals(otherOverrides.bloom, enabled.bloom)
        assertEquals(GlassMaterialOverrides(), enabled.withGlassEnabled(false))
        assertEquals(GlassMaterialOverrides(), GlassMaterialOverrides().withGlassEnabled(true).withGlassEnabled(false))
    }

    @Test fun liquidGlassIncludesFrostAndHighlightDefaults() {
        val enabled = GlassMaterialOverrides().withGlassEnabled(true)
        assertEquals(listOf(60, 16), enabled.blurRadii)
        assertEquals(GlassSliderParameter.startingBloom(), enabled.bloom)
        assertTrue(enabled.isLiquidGlassEnabled)
        assertEquals(enabled, enabled.forLiquidGlass())
    }

    @Test fun liquidGlassStateMatchesGlassParameterPresence() {
        val glass = GlassSliderParameter.startingGlass()
        assertTrue(GlassMaterialOverrides(glass = glass).isLiquidGlassEnabled)
        assertTrue(GlassMaterialOverrides(glass = glass, materialType = 0).isLiquidGlassEnabled)
        assertTrue(GlassMaterialOverrides(glass = glass, materialType = 1).isLiquidGlassEnabled)
        assertEquals(1, GlassMaterialOverrides(glass = glass, materialType = 0).forLiquidGlass().materialType)
    }

    @Test fun defaultsMatchTheSelectedNativePresetsExactly() {
        val expectedGlass = "0.0, 0.33333334, 0.6666667, 1.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0, 1.0, 50.0, 1.0, 160.0, 0.0, 0.0, 0.0, 0.0, 1.0, 1.0, 0.5, 0.0, 0.5, 1.1764705, 2.0, 1.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0"
        val expectedBloom = "30.0, 1.0, 0.0, 0.99999994, 1.0, 0.1, 0.0, 0.5, 0.0, 0.0, 0.0, 0.0"
        val defaults = GlassMaterialOverrides().withGlassEnabled(true)
        assertEquals(expectedGlass, defaults.text(GlassOverrideField.GLASS))
        assertEquals("60, 16", defaults.text(GlassOverrideField.BLUR_RADII))
        assertEquals(expectedBloom, defaults.text(GlassOverrideField.BLOOM))
        assertEquals(defaults.glass, GlassSliderParameter.migrateStartingGlass(defaults.glass))
    }

    @Test fun legacyIndependentOverridesFollowTheMasterSwitch() {
        val disabled = GlassMaterialOverrides(blurRadii = listOf(12, 140), bloom = List(12) { 0.5f }, materialType = 0)
        assertEquals(GlassMaterialOverrides(), disabled.forLiquidGlass())
        val enabled = disabled.copy(glass = GlassSliderParameter.startingGlass()).forLiquidGlass()
        assertEquals(1, enabled.materialType)
        assertEquals(disabled.blurRadii, enabled.blurRadii)
        assertEquals(disabled.bloom, enabled.bloom)
    }

    @Test fun tintUsesSavedArgbWithoutChangingOpticalParametersOrStoredValues() {
        val original = GlassMaterialOverrides(glass = List(42) { it / 42f })
        val tinted = original.glassWithTint(0x804080C0.toInt())!!
        assertEquals(64 / 255f, tinted[11])
        assertEquals(128 / 255f, tinted[12])
        assertEquals(192 / 255f, tinted[13])
        assertEquals(0f, tinted[14])
        assertEquals(128 / 255f, tinted[36])
        original.glass!!.indices.filter { it !in listOf(11, 12, 13, 14, 36) }.forEach {
            assertEquals(original.glass[it], tinted[it])
        }
        assertEquals(List(42) { it / 42f }, original.glass)
        assertEquals(original.glass, original.glassWithTint(null))
    }

    @Test fun tintPreservesTransparentAndOpaqueColorEndpoints() {
        val overrides = GlassMaterialOverrides().withGlassEnabled(true)
        assertEquals(0f, overrides.glassWithTint(0x00123456)!![36])
        assertEquals(1f, overrides.glassWithTint(0xFF123456.toInt())!![36])
        assertNull(GlassMaterialOverrides().glassWithTint(0xFF123456.toInt()))
    }

    @Test fun slidersWriteVerifiedNativeSlotsWithoutChangingOtherValues() {
        val original = List(42) { it / 10f }
        val cases = listOf(
            Triple(GlassSliderParameter.REFRACTION, 1.7f, 32 to 1.7f),
            Triple(GlassSliderParameter.DEPTH, 70f, 19 to 70f),
            Triple(GlassSliderParameter.THICKNESS, 80f, 21 to 80f),
            Triple(GlassSliderParameter.SPLAY, 90f, 30 to 0.5f),
            Triple(GlassSliderParameter.LIGHT_INTENSITY, 25f, 28 to 0.25f),
            Triple(GlassSliderParameter.EDGE_CURVE, 2f, 20 to 2f)
        )
        for ((parameter, input, expected) in cases) {
            val updated = parameter.write(original, input)
            assertEquals(expected.second, updated[expected.first], 0.0001f)
            assertEquals(input, parameter.read(updated), 0.0001f)
            original.indices.filter { it != expected.first }.forEach { assertEquals(original[it], updated[it]) }
        }
    }

    @Test fun changingAzimuthPreservesLightElevationAndUnrelatedParameters() {
        val original = GlassSliderParameter.startingGlass().toMutableList().apply {
            this[25] = 3f; this[26] = 4f; this[27] = -2f
        }
        val updated = GlassSliderParameter.LIGHT_ANGLE.write(original, 90f)
        assertEquals(5f, updated[25], 0.0001f)
        assertEquals(0f, updated[26], 0.0001f)
        assertEquals(90f, GlassSliderParameter.LIGHT_ANGLE.read(updated), 0.0001f)
        original.indices.filter { it !in 25..26 }.forEach { assertEquals(original[it], updated[it]) }
    }

    @Test fun sliderOverridesSurviveTheExistingScalarPreferenceFormat() {
        val custom = GlassMaterialOverrides().withGlassEnabled(true).let {
            it.copy(glass = GlassSliderParameter.DEPTH.write(it.glass!!, 42f))
        }
        val values = mutableMapOf<String, Any>("glass_params_count" to 42, "glass_material_type" to 1)
        custom.glass!!.forEachIndexed { index, value -> values["glass_params_$index"] = value }
        values["glass_blur_small"] = custom.blurRadii!![0]
        values["glass_blur_large"] = custom.blurRadii[1]
        values["glass_bloom_count"] = custom.bloom!!.size
        custom.bloom.forEachIndexed { index, value -> values["glass_bloom_$index"] = value }
        assertEquals(custom, GlassMaterialOverrides.read(values))
    }

    @Test fun bloomSlidersUseTheJniArrayOffsetsAndPreserveExtensions() {
        val original = GlassSliderParameter.startingBloom() + listOf(0.2f, 0.3f, 0.4f, 0.5f)
        val cases = listOf(
            Triple(GlassSliderParameter.LIGHT_INTENSITY, 70f, 5 to 0.7f),
            Triple(GlassSliderParameter.DEPTH, 24f, 0 to 24f),
            Triple(GlassSliderParameter.SPLAY, 36f, 7 to 0.2f),
            Triple(GlassSliderParameter.EDGE_CURVE, 0.5f, 1 to 0.5f)
        )
        for ((parameter, input, expected) in cases) {
            val updated = parameter.writeBloom(original, input)
            assertEquals(input, parameter.readBloom(updated), 0.0001f)
            assertEquals(expected.second, updated[expected.first], 0.0001f)
            original.indices.filter { it != expected.first }.forEach { assertEquals(original[it], updated[it]) }
        }
        val rotated = GlassSliderParameter.LIGHT_ANGLE.writeBloom(original, 180f)
        assertEquals(0f, rotated[2], 0.0001f)
        assertEquals(-1f, rotated[3], 0.0001f)
        assertEquals(original[4], rotated[4])
        assertEquals(original.subList(12, 16), rotated.subList(12, 16))
    }

    @Test fun glassBrightnessDefaultsAreNeutralAdditiveOffsets() {
        val glass = GlassSliderParameter.startingGlass()
        assertEquals(0f, glass[6], 0f)
        assertEquals(0f, glass[34], 0f)
        assertEquals(1f, glass[5], 0f)
        assertEquals(1f, glass[33], 0f)
    }

    @Test fun upgradesOnlyTheExactWhiteProducingStarterWithoutLosingOtherOverrides() {
        val corrected = GlassSliderParameter.startingGlass().toMutableList().apply {
            this[19] = 30f
            this[21] = 30f
            this[28] = 0.25f
            this[32] = 1.5f
        }
        val old = corrected.toMutableList().apply { this[6] = 1f; this[34] = 1f }
        val stored = mutableMapOf<String, Any>("glass_params_count" to 42,
            "glass_material_type" to 1, "glass_blur_small" to 12, "glass_blur_large" to 64)
        old.forEachIndexed { index, value -> stored["glass_params_$index"] = value }
        val migrated = GlassMaterialOverrides.read(stored)
        assertEquals(corrected, migrated.glass)
        assertEquals(listOf(12, 64), migrated.blurRadii)
        assertEquals(1, migrated.materialType)
        assertEquals(corrected, GlassSliderParameter.migrateStartingGlass(corrected))
        assertNull(GlassSliderParameter.migrateStartingGlass(null))
        val intentional = old.toMutableList().apply { this[6] = 0.15f }
        assertEquals(intentional, GlassSliderParameter.migrateStartingGlass(intentional))
    }
}
