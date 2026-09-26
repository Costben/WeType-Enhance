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
    fun toolbarBackgroundUsesTheWholeContainerAndIgnoresImageTransform() {
        val geometry = resolveIconOverlayGeometry(
            target = WeTypeIconEdgeLightTarget.BACKGROUND,
            targetWidth = 120,
            targetHeight = 80,
            paddingLeft = 18,
            paddingTop = 12,
            paddingRight = 18,
            paddingBottom = 12,
            boundsLeft = 0,
            boundsTop = 0,
            boundsRight = 40,
            boundsBottom = 40,
            imageMatrix = null,
            offsetX = 7,
            offsetY = -3
        )

        assertEquals(7, geometry!!.left)
        assertEquals(-3, geometry.top)
        assertEquals(120, geometry.width)
        assertEquals(80, geometry.height)
    }

    @Test
    fun logoImageFollowsMatrixOnceAndStaysInsidePaddedContent() {
        val geometry = resolveIconOverlayGeometry(
            target = WeTypeIconEdgeLightTarget.IMAGE_CONTENT,
            targetWidth = 100,
            targetHeight = 80,
            paddingLeft = 10,
            paddingTop = 5,
            paddingRight = 10,
            paddingBottom = 5,
            // Bounds already contain the ImageView matrix result (5,3)-(85,63).
            imageMatrix = null,
            boundsLeft = 5,
            boundsTop = 3,
            boundsRight = 85,
            boundsBottom = 63,
            offsetX = 7,
            offsetY = -2
        )

        assertEquals(17, geometry!!.left)
        assertEquals(3, geometry.top)
        assertEquals(75, geometry.width)
        assertEquals(58, geometry.height)
    }

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

    @Test
    fun reconcilerReusesSameSlotForIdenticalKeyBoundsAcrossPasses() {
        val reconciler = WeTypeKeySlotReconciler(16)
        val slot1 = reconciler.submit(10, 20, 110, 160, 24f, dark = true, passId = 1L)
        val slot2 = reconciler.submit(120, 20, 220, 160, 24f, dark = true, passId = 1L)
        assertTrue(slot1 != null && slot2 != null)
        assertEquals(0, slot1!!.id)
        assertEquals(1, slot2!!.id)

        // 下一帧相同矩形直接命中原槽位，不重新分配新槽位
        val slot1Again = reconciler.submit(10, 20, 110, 160, 28f, dark = false, passId = 2L)
        assertEquals(0, slot1Again!!.id)
        assertEquals(28f, slot1Again.radiusPx, 0.001f)
        assertEquals(false, slot1Again.dark)
        assertEquals(2, reconciler.slots.count { it.active })
    }

    @Test
    fun reconcilerEvictsIntersectingSlotsOnLayoutSwitchAndPrunesOnlyOnFullPass() {
        val reconciler = WeTypeKeySlotReconciler(32)
        // 模拟第一帧全量绘制 10 个按键
        for (i in 0 until 10) {
            val left = i * 100
            reconciler.submit(left, 0, left + 90, 120, 20f, dark = true, passId = 10L)
        }
        assertEquals(10, reconciler.slots.count { it.active })

        // 单键局部重绘（仅 1 个键更新，不足 8 键阈值）：不误删其余 9 个键
        reconciler.submit(200, 0, 290, 120, 20f, dark = true, passId = 11L)
        reconciler.pruneUnseen(passId = 11L, minSeenCountForPrune = 8)
        assertEquals(10, reconciler.slots.count { it.active })

        // 布局切换（宽键同时跨过原本 0..190 的两个窄键）：相交旧槽位被逐出与合并
        val merged = reconciler.submit(0, 0, 190, 120, 24f, dark = true, passId = 12L)
        assertTrue(merged != null)
        assertEquals(9, reconciler.slots.count { it.active })

        // 模拟切换到 8 键新布局并执行全量修剪
        for (i in 1 until 8) {
            val left = 200 + i * 100
            reconciler.submit(left, 0, left + 90, 120, 24f, dark = true, passId = 12L)
        }
        reconciler.pruneUnseen(passId = 12L, minSeenCountForPrune = 8)
        assertEquals(8, reconciler.slots.count { it.active })

        reconciler.clear()
        assertEquals(0, reconciler.slots.count { it.active })
    }
}
