package com.xposed.wetypehook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 系统材质预览的常量来自 165（ColorOS V17 / PLK110 / density 3.875）的真机像素测量：
 * 强度 32 时左右辉光峰值 +26、摊开 130px，顶部 +19、摊开 88px；强度 100 时左右顶到饱和并
 * 摊开到约 500px。这些比例改坏了，预览就会跟真机对不上。
 */
class NativeMaterialPreviewTest {

    /** 真机面板底色的灰度值，用来把 alpha 折算成肉眼看到的亮度差。 */
    private companion object {
        const val LIGHT_PANEL = 212f
        const val DARK_PANEL = 2f
        const val MODULE_BLUR_DP = 10f
        const val SYSTEM_BLUR_DP = 38.7f
        const val FALLBACK_COLOR = 0xFFE5E6E7.toInt()
    }

    private val reference = NativeMaterialPreview.edgeGlow(isDark = false, intensity = 32)
    private val saturation = NativeMaterialPreview.edgeGlow(isDark = false, intensity = 100)
    private val dark = NativeMaterialPreview.edgeGlow(isDark = true, intensity = 32)

    /**
     * 辉光叠在面板底色上，肉眼看到的亮度差是 `alpha * (255 - 底色)`，所以比较强弱必须带上底色。
     */
    private fun MaterialEdgeGlow.sideDelta(panel: Float) = sideAlpha * (255f - panel)

    private fun MaterialEdgeGlow.topDelta(panel: Float) = topAlpha * (255f - panel)

    private fun panel(
        color: Int = 0x93D4D4D4.toInt(),
        isDark: Boolean = false,
        systemMaterialEnabled: Boolean = true,
        hyperMaterialEnabled: Boolean = true,
        colorOsBackend: Boolean = true,
        edgeHighlightEnabled: Boolean = true,
        nativeEdgeLightEnabled: Boolean = true,
        intensity: Int = 32
    ) = NativeMaterialPreview.panel(
        color = color,
        isDark = isDark,
        moduleBlurDp = MODULE_BLUR_DP,
        systemBlurDp = SYSTEM_BLUR_DP,
        fallbackColor = FALLBACK_COLOR,
        systemMaterialEnabled = systemMaterialEnabled,
        hyperMaterialEnabled = hyperMaterialEnabled,
        colorOsBackend = colorOsBackend,
        edgeHighlightEnabled = edgeHighlightEnabled,
        nativeEdgeLightEnabled = nativeEdgeLightEnabled,
        edgeHighlightIntensity = intensity
    )

    @Test
    fun minimumAlphaRaisesOpacityAndKeepsRgb() {
        assertEquals(0xC8D4D4D4.toInt(), NativeMaterialPreview.withMinimumAlpha(0x93D4D4D4.toInt()))
        assertEquals(0xFF2F80ED.toInt(), NativeMaterialPreview.withMinimumAlpha(0xFF2F80ED.toInt()))
        assertEquals(0xC8000000.toInt(), NativeMaterialPreview.withMinimumAlpha(0x40000000))
    }

    @Test
    fun systemTintMatchesColorOsPresets() {
        assertEquals(0xCCDDDDDD.toInt(), NativeMaterialPreview.systemTint(isDark = false))
        assertEquals(0x99000000.toInt(), NativeMaterialPreview.systemTint(isDark = true))
    }

    @Test
    fun nativeEdgeLightPanelMatchesDevicePixels() {
        // 真机亮色档面板实测 212（用户底色 #D4D4D4 盖住浅灰底板），容差留给回读时的抖动。
        val light = NativeMaterialPreview.panelColor(0x93D4D4D4.toInt(), isDark = false)
        assertEquals(212f, (light and 0xFF).toFloat(), 3f)
        assertEquals(0xFF, light ushr 24)
        // 暗色档面板实测 1~2（25% 黑被抬到 78%，盖在纯黑底板上）。
        val darkColor = NativeMaterialPreview.panelColor(0x40000000, isDark = true)
        assertEquals(0f, (darkColor and 0xFF).toFloat(), 3f)
        assertEquals(0xFF, darkColor ushr 24)
    }

    @Test
    fun opaqueUserColorSurvivesThePlate() {
        // 真机拿纯红底色量到的是纯红（255,0,0），底板一点都不参与。
        assertEquals(0xFFFF0000.toInt(), NativeMaterialPreview.panelColor(0xFFFF0000.toInt(), false))
    }

    @Test
    fun referenceGlowMatchesDeviceMeasurement() {
        assertEquals(0.60f, reference.sideAlpha, 0.01f)
        assertEquals(0.44f, reference.topAlpha, 0.01f)
        assertEquals(33.5f, reference.sideSpreadDp, 0.1f)
        assertEquals(22.7f, reference.topSpreadDp, 0.1f)
        assertTrue(reference.visible)
    }

    @Test
    fun darkGlowIsWeakerOnTheSidesAndStrongerOnTheTop() {
        assertTrue(dark.sideDelta(DARK_PANEL) < reference.sideDelta(LIGHT_PANEL))
        assertTrue(dark.topDelta(DARK_PANEL) > reference.topDelta(LIGHT_PANEL))
        assertTrue(dark.sideSpreadDp < reference.sideSpreadDp)
    }

    @Test
    fun glowDeltasMatchDeviceMeasurement() {
        assertEquals(26f, reference.sideDelta(LIGHT_PANEL), 2f)
        assertEquals(19f, reference.topDelta(LIGHT_PANEL), 2f)
        assertEquals(5f, dark.sideDelta(DARK_PANEL), 2f)
        assertEquals(23f, dark.topDelta(DARK_PANEL), 2f)
    }

    @Test
    fun intensitySaturatesAndWidens() {
        assertEquals(1f, saturation.sideAlpha, 0.001f)
        assertTrue(saturation.topAlpha > reference.topAlpha)
        assertTrue(saturation.topAlpha < 1f)
        assertTrue(saturation.sideSpreadDp > reference.sideSpreadDp * 3f)
    }

    @Test
    fun zeroIntensityDrawsNothing() {
        val off = NativeMaterialPreview.edgeGlow(isDark = false, intensity = 0)
        assertFalse(off.visible)
        assertEquals(0f, off.sideAlpha, 0.001f)
        assertEquals(0f, off.topAlpha, 0.001f)
    }

    @Test
    fun glowGrowsMonotonicallyWithIntensity() {
        var previous = NativeMaterialPreview.edgeGlow(isDark = false, intensity = 1)
        for (intensity in 2..100) {
            val current = NativeMaterialPreview.edgeGlow(isDark = false, intensity = intensity)
            assertTrue(current.sideAlpha >= previous.sideAlpha)
            assertTrue(current.topAlpha >= previous.topAlpha)
            assertTrue(current.sideSpreadDp >= previous.sideSpreadDp)
            assertTrue(current.topSpreadDp >= previous.topSpreadDp)
            previous = current
        }
    }

    @Test
    fun intensityIsClampedToSliderRange() {
        assertEquals(
            NativeMaterialPreview.edgeGlow(isDark = false, intensity = 100),
            NativeMaterialPreview.edgeGlow(isDark = false, intensity = 400)
        )
        assertEquals(
            NativeMaterialPreview.edgeGlow(isDark = false, intensity = 0),
            NativeMaterialPreview.edgeGlow(isDark = false, intensity = -20)
        )
    }

    @Test
    fun colorOsBackendWithNativeEdgeLightTakesTheModulePanel() {
        val panel = panel()
        assertEquals(NativeMaterialPreview.panelColor(0x93D4D4D4.toInt(), false), panel.color)
        assertEquals(SYSTEM_BLUR_DP, panel.backdropBlurDp, 0.001f)
        assertTrue(panel.nativeEdgeGlow != null)
        assertFalse(panel.moduleBloom)
    }

    @Test
    fun colorOsBackendWithoutTheColorOsRowKeepsTheModuleSurface() {
        // ColorOS 上「ColorOS 系统材质」没开时整块退回模块自绘：米 UI 那条材质开关在 ColorOS
        // 后端不参与，背板也不再单独铺一层系统模糊。
        val panel = panel(nativeEdgeLightEnabled = false, hyperMaterialEnabled = true)
        assertEquals(0x93D4D4D4.toInt(), panel.color)
        assertEquals(MODULE_BLUR_DP, panel.backdropBlurDp, 0.001f)
        assertNull(panel.nativeEdgeGlow)
        assertTrue(panel.moduleBloom)
        assertTrue(panel.backdropVisible)
    }

    @Test
    fun opaquePanelSkipsTheBackdropBlur() {
        // ColorOS 材质生效时面板不透，背板那层模糊白算。
        assertFalse(panel().backdropVisible)
        assertTrue(panel(nativeEdgeLightEnabled = false).backdropVisible)
    }

    @Test
    fun nativeEdgeLightIgnoresTheModuleEdgeHighlightSwitch() {
        // 两条轨道各自独立：自绘总控关着也不影响原生边缘光生效。
        val panel = panel(edgeHighlightEnabled = false)
        assertEquals(NativeMaterialPreview.panelColor(0x93D4D4D4.toInt(), false), panel.color)
        assertTrue(panel.nativeEdgeGlow != null)
        assertFalse(panel.moduleBloom)
    }

    @Test
    fun moduleBloomStaysOffWhenBothTracksAreOff() {
        val panel = panel(edgeHighlightEnabled = false, nativeEdgeLightEnabled = false)
        assertNull(panel.nativeEdgeGlow)
        assertFalse(panel.moduleBloom)
    }

    @Test
    fun colorOsMaterialIgnoresTheMiuiMaterialSwitch() {
        // ColorOS 那套材质整体由「ColorOS 系统材质」那一行驱动，米 UI 材质开关关不关都照走。
        val panel = panel(hyperMaterialEnabled = false)
        assertEquals(NativeMaterialPreview.panelColor(0x93D4D4D4.toInt(), false), panel.color)
        assertEquals(SYSTEM_BLUR_DP, panel.backdropBlurDp, 0.001f)
        assertTrue(panel.nativeEdgeGlow != null)
        assertFalse(panel.moduleBloom)
    }

    @Test
    fun hyperOsBackendKeepsTheFallbackSurface() {
        // 宿主自己那套材质读不到真机像素（手里没有 HyperOS 设备），保持原有的 fallback 纯色：
        // 背板与模糊都留在模块档，原生边缘光不介入，自绘流光照旧。
        val panel = panel(colorOsBackend = false)
        assertEquals(FALLBACK_COLOR, panel.color)
        assertEquals(MODULE_BLUR_DP, panel.backdropBlurDp, 0.001f)
        assertNull(panel.nativeEdgeGlow)
        assertTrue(panel.moduleBloom)
    }

    @Test
    fun materialOffKeepsTheModuleSurface() {
        val panel = panel(hyperMaterialEnabled = false, nativeEdgeLightEnabled = false)
        assertEquals(0x93D4D4D4.toInt(), panel.color)
        assertEquals(MODULE_BLUR_DP, panel.backdropBlurDp, 0.001f)
        assertNull(panel.nativeEdgeGlow)
        assertTrue(panel.moduleBloom)
    }

    @Test
    fun systemMaterialSwitchOffRestoresTheModuleSurface() {
        // 总控关掉时两条系统材质轨道整体失效：米 UI 与原生边缘光都开着，背板、模糊与边缘光
        // 也全回模块自绘那一档。
        val panel = panel(
            systemMaterialEnabled = false,
            hyperMaterialEnabled = true,
            nativeEdgeLightEnabled = true
        )
        assertEquals(0x93D4D4D4.toInt(), panel.color)
        assertEquals(MODULE_BLUR_DP, panel.backdropBlurDp, 0.001f)
        assertNull(panel.nativeEdgeGlow)
        assertTrue(panel.moduleBloom)
    }

    @Test
    fun systemMaterialSwitchOffLeavesTheBloomToTheEdgeHighlightSwitch() {
        // 退回模块自绘后，流光轮廓重新只听「光感设置」那条独立轨道。
        val panel = panel(systemMaterialEnabled = false, edgeHighlightEnabled = false)
        assertNull(panel.nativeEdgeGlow)
        assertFalse(panel.moduleBloom)
    }

    @Test
    fun systemMaterialSwitchOnKeepsTheDeviceBehaviour() {
        // 对照：同一组开关，总控开着时仍走系统材质那条路。
        val panel = panel(systemMaterialEnabled = true)
        assertEquals(NativeMaterialPreview.panelColor(0x93D4D4D4.toInt(), false), panel.color)
        assertEquals(SYSTEM_BLUR_DP, panel.backdropBlurDp, 0.001f)
        assertTrue(panel.nativeEdgeGlow != null)
        assertFalse(panel.moduleBloom)
    }
}
