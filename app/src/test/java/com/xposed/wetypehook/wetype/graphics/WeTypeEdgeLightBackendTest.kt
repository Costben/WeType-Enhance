package com.xposed.wetypehook.wetype.graphics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 边缘光的选源：自绘源可用就画，不可用就不画。
 *
 * 这条决策同时管着图标与按键两处，所以抽成纯函数——任何一处漏判都会让某个 ROM 上
 * 「光感设置」整条失效。
 */
class WeTypeEdgeLightBackendTest {

    /** 这一处光感自己的总开关关掉时，自绘源不许顶上来。 */
    @Test
    fun theFeatureSwitchOffBeatsTheSelfDrawnSource() {
        assertEquals(
            WeTypeEdgeLightBackend.NONE,
            resolveEdgeLightBackend(enabled = false, selfDrawnSourceEnabled = true)
        )
        assertEquals(
            WeTypeEdgeLightBackend.NONE,
            resolveEdgeLightBackend(enabled = false, selfDrawnSourceEnabled = false)
        )
    }

    @Test
    fun selfDrawnIsUsedWhenBothSwitchesAreOn() {
        assertEquals(
            WeTypeEdgeLightBackend.SELF_DRAWN,
            resolveEdgeLightBackend(enabled = true, selfDrawnSourceEnabled = true)
        )
    }

    @Test
    fun nothingIsDrawnWithoutTheSelfDrawnSource() {
        assertEquals(
            WeTypeEdgeLightBackend.NONE,
            resolveEdgeLightBackend(enabled = true, selfDrawnSourceEnabled = false)
        )
    }

    /**
     * 建层时选中的源，之后每帧复核的判据必须与建层时的那一条一致：看错一条就会把还该画的
     * 光掐掉，或者让失效的源继续画。
     */
    @Test
    fun validityRecheckUsesTheConditionThatBuiltTheLayer() {
        assertTrue(
            isEdgeLightSourceStillValid(
                WeTypeEdgeLightBackend.SELF_DRAWN,
                selfDrawnSourceStillEnabled = true
            )
        )
        assertFalse(
            isEdgeLightSourceStillValid(
                WeTypeEdgeLightBackend.SELF_DRAWN,
                selfDrawnSourceStillEnabled = false
            )
        )
        assertFalse(
            isEdgeLightSourceStillValid(
                WeTypeEdgeLightBackend.NONE,
                selfDrawnSourceStillEnabled = true
            )
        )
    }
}
