package com.xposed.wetypehook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「字符反色」这条规则的钉子：键面字色是按 WCAG 对比度**量**出来的，不是按亮度过半猜的。
 * 期望值都是从公式算出来的数，改阈值或改候选色都会让这里先红。
 */
class PerKeyLabelColorTest {

    @Test fun contrastRatioHitsWcagEndpoints() {
        assertEquals(21f, contrastRatio(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), 0.01f)
        assertEquals(1f, contrastRatio(0xFF3A7BD5.toInt(), 0xFF3A7BD5.toInt()), 0.01f)
        assertEquals(
            contrastRatio(0xFF000000.toInt(), 0xFFFFFFFF.toInt()),
            contrastRatio(0xFFFFFFFF.toInt(), 0xFF000000.toInt()),
            0.0001f
        )
    }

    /** 不透明自定义色是主路径 —— 用户输入的颜色几乎都带 FF。 */
    @Test fun opaqueKeycapsPickTheHigherContrastInk() {
        // #202080 深藏青：黑字 1.6:1、浅色字 11.2:1
        assertEquals(REPLICA_KEY_LABEL_DARK_ARGB, perKeyLabelColor(0xFF202080.toInt()))
        // #EEEEEE 浅灰：黑字 18.1:1、浅色字 1.0:1
        assertEquals(REPLICA_KEY_LABEL_LIGHT_ARGB, perKeyLabelColor(0xFFEEEEEE.toInt()))
        // #2F80ED 品牌蓝：黑字 5.4:1 高过浅色字 3.2:1 —— 蓝色键帽配黑字，这是量出来的结果
        assertEquals(REPLICA_KEY_LABEL_LIGHT_ARGB, perKeyLabelColor(0xFF2F80ED.toInt()))
    }

    /** 中间色两个候选几乎打平（#707070 差 0.09），这正是「字符反色」开关存在的理由。 */
    @Test fun midToneIsReportedAsATie() {
        val choices = perKeyLabelChoices(0xFF707070.toInt())
        assertEquals(2, choices.size)
        assertTrue(choices[0].contrast - choices[1].contrast < 0.5f)
    }

    @Test fun invertFlipsBetweenExactlyTwoCandidates() {
        for (background in listOf(0xFF2F80ED, 0xFF202080, 0xFFEEEEEE, 0xFF707070)) {
            val choices = perKeyLabelChoices(background.toInt())
            assertEquals(2, choices.size)
            assertEquals(
                setOf(REPLICA_KEY_LABEL_LIGHT_ARGB, REPLICA_KEY_LABEL_DARK_ARGB),
                choices.map { it.color }.toSet()
            )
            assertTrue(choices[0].contrast >= choices[1].contrast)
            assertEquals(choices[0], autoPerKeyLabel(background.toInt()))
            assertEquals(choices[0].color, perKeyLabelColor(background.toInt(), invert = false))
            assertEquals(choices[1].color, perKeyLabelColor(background.toInt(), invert = true))
            assertNotEquals(
                perKeyLabelColor(background.toInt(), invert = false),
                perKeyLabelColor(background.toInt(), invert = true)
            )
        }
    }

    /**
     * 半透明键帽按中灰背板合成后再判，与设置页预览、真机 hook 走同一条规则。
     * 宿主那两档默认键帽合成到中灰上都是 #929292 附近，两边都判黑字。
     */
    @Test fun semiTransparentKeycapsAreCompositedBeforeJudging() {
        assertEquals(REPLICA_KEY_LABEL_LIGHT_ARGB, perKeyLabelColor(0x2BEDEDED))
        assertEquals(REPLICA_KEY_LABEL_LIGHT_ARGB, perKeyLabelColor(0x2BEEEEED))
        // 全透明等于只看背板
        assertEquals(perKeyLabelColor(0xFF808080.toInt()), perKeyLabelColor(0x00808080))
    }
}
