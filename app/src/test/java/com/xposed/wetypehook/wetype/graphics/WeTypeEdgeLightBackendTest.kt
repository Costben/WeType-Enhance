package com.xposed.wetypehook.wetype.graphics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 边缘光的选源矩阵：原生优先、拿不到退回自绘、两者都没有就不画。
 *
 * 这条决策同时管着图标与按键两处，所以抽成纯函数——任何一处漏判都会让某个 ROM 上
 * 「光感设置」整条失效，或者出现两个源叠着画。
 */
class WeTypeEdgeLightBackendTest {

    @Test
    fun nativeWinsWheneverItIsAvailable() {
        assertEquals(
            WeTypeEdgeLightBackend.NATIVE,
            resolveEdgeLightBackend(
                enabled = true,
                nativeSourceAvailable = true,
                selfDrawnSourceEnabled = true
            )
        )
        // 原生可用时，自绘总控关不关都不影响：一个区域只画一层光。
        assertEquals(
            WeTypeEdgeLightBackend.NATIVE,
            resolveEdgeLightBackend(
                enabled = true,
                nativeSourceAvailable = true,
                selfDrawnSourceEnabled = false
            )
        )
    }

    @Test
    fun selfDrawnTakesOverWhenNativeIsMissing() {
        assertEquals(
            WeTypeEdgeLightBackend.SELF_DRAWN,
            resolveEdgeLightBackend(
                enabled = true,
                nativeSourceAvailable = false,
                selfDrawnSourceEnabled = true
            )
        )
    }

    @Test
    fun nothingIsDrawnWithoutEitherSource() {
        assertEquals(
            WeTypeEdgeLightBackend.NONE,
            resolveEdgeLightBackend(
                enabled = true,
                nativeSourceAvailable = false,
                selfDrawnSourceEnabled = false
            )
        )
    }

    /** 这一处光感自己的总开关关掉时，两个源都不许顶上来。 */
    @Test
    fun theFeatureSwitchOffBeatsBothSources() {
        assertEquals(
            WeTypeEdgeLightBackend.NONE,
            resolveEdgeLightBackend(
                enabled = false,
                nativeSourceAvailable = true,
                selfDrawnSourceEnabled = true
            )
        )
        assertEquals(
            WeTypeEdgeLightBackend.NONE,
            resolveEdgeLightBackend(
                enabled = false,
                nativeSourceAvailable = false,
                selfDrawnSourceEnabled = true
            )
        )
    }

    /**
     * 建层时选中的源，之后每帧复核的判据必须与建层时一致：原生层只看原生那条条件，
     * 自绘层只看「光感设置」那条。看错一条就会把还该画的光掐掉，或者让失效的源继续画。
     */
    @Test
    fun validityRecheckUsesTheConditionThatBuiltTheLayer() {
        assertTrue(
            isEdgeLightSourceStillValid(
                WeTypeEdgeLightBackend.NATIVE,
                nativeSourceStillAvailable = true,
                selfDrawnSourceStillEnabled = false
            )
        )
        assertFalse(
            isEdgeLightSourceStillValid(
                WeTypeEdgeLightBackend.NATIVE,
                nativeSourceStillAvailable = false,
                selfDrawnSourceStillEnabled = true
            )
        )
        assertTrue(
            isEdgeLightSourceStillValid(
                WeTypeEdgeLightBackend.SELF_DRAWN,
                nativeSourceStillAvailable = false,
                selfDrawnSourceStillEnabled = true
            )
        )
        assertFalse(
            isEdgeLightSourceStillValid(
                WeTypeEdgeLightBackend.SELF_DRAWN,
                nativeSourceStillAvailable = true,
                selfDrawnSourceStillEnabled = false
            )
        )
        assertFalse(
            isEdgeLightSourceStillValid(
                WeTypeEdgeLightBackend.NONE,
                nativeSourceStillAvailable = true,
                selfDrawnSourceStillEnabled = true
            )
        )
    }

    /** 复核结论只由自己那条条件决定，另一条怎么变都不参与。 */
    @Test
    fun validityRecheckIgnoresTheOtherTrack() {
        for (other in listOf(true, false)) {
            assertEquals(
                true,
                isEdgeLightSourceStillValid(
                    WeTypeEdgeLightBackend.NATIVE,
                    nativeSourceStillAvailable = true,
                    selfDrawnSourceStillEnabled = other
                )
            )
            assertEquals(
                true,
                isEdgeLightSourceStillValid(
                    WeTypeEdgeLightBackend.SELF_DRAWN,
                    nativeSourceStillAvailable = other,
                    selfDrawnSourceStillEnabled = true
                )
            )
        }
    }
}
