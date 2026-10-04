package com.xposed.wetypehook.wetype.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 数值锁定手势标签的几何（issue #3）：键帽高度比例驱动边距与字号收缩，墨迹始终夹在键帽内；
 * 压缩键位（悬浮/横屏）再往下让一点，宽扁键位靠右避开居中的字母。
 *
 * 密度 3.0、参考键高 48dp = 144px、默认下边距 15dp = 45px、全尺寸 9sp 标签墨迹半高取 16px。
 */
class GestureLabelLayoutTest {

    private val density = 3f
    private val margin15dp = 45f
    private val fullSizeInkHalf = 16f
    private val minGap = GestureLabelLayout.MIN_EDGE_GAP_DP * density

    /** 复刻画布代码的偏移算法：边距按比例收缩、压缩键位加下移量，再夹进键帽。 */
    private fun offset(
        marginPx: Float = margin15dp,
        keyHeightPx: Float,
        inkHalfPx: Float = fullSizeInkHalf,
        gapPx: Float = minGap
    ): Float {
        val fitScale = GestureLabelLayout.fitScale(density, keyHeightPx)
        val nudgePx = if (GestureLabelLayout.isCompressed(fitScale)) {
            GestureLabelLayout.compressedNudgePx(density, GestureLabelLayout.shrinkTextScale(fitScale))
        } else {
            0f
        }
        return GestureLabelLayout.clampOffsetPx(
            offsetPx = marginPx * fitScale + nudgePx,
            keyHeightPx = keyHeightPx,
            inkHalfPx = inkHalfPx,
            gapPx = gapPx
        )
    }

    /** 全尺寸竖屏键帽（48dp）比例为 1，位置与历史版本完全一致。 */
    @Test fun portraitFullSizeKeepsTheHistoricalOffset() {
        assertEquals(margin15dp, offset(keyHeightPx = 48f * density), 0.01f)
    }

    /** 键帽比参考高也不放大，避免大屏键盘上标签被推得过远。 */
    @Test fun tallerKeyDoesNotGrowTheOffset() {
        assertEquals(margin15dp, offset(keyHeightPx = 66f * density), 0.01f)
    }

    /** 悬浮键盘键帽约 38dp，比例小于 1，仍高于隐藏门槛。 */
    @Test fun floatingKeyboardIsSmallerButStillVisible() {
        val fit = GestureLabelLayout.fitScale(density, 38f * density)
        assertTrue(fit < 1f)
        assertFalse(GestureLabelLayout.hidesAtThisSize(fit))
    }

    /** 横屏分体键帽约 30dp，比例掉到隐藏门槛以下。 */
    @Test fun landscapeSplitKeyboardFallsBelowTheHideThreshold() {
        val fit = GestureLabelLayout.fitScale(density, 30f * density)
        assertTrue(GestureLabelLayout.hidesAtThisSize(fit))
    }

    /** 缩放策略的下限：键帽极矮时字号不再继续缩小。 */
    @Test fun shrinkScaleHasAFloor() {
        assertEquals(
            GestureLabelLayout.MIN_SHRINK_SCALE,
            GestureLabelLayout.shrinkTextScale(0.1f),
            0.001f
        )
        assertEquals(0.625f, GestureLabelLayout.shrinkTextScale(0.625f), 0.001f)
    }

    /** 压缩键位的下移量随密度与缩放走：2dp * density * shrink。 */
    @Test fun compressedNudgeScalesWithDensityAndShrink() {
        assertEquals(3f, GestureLabelLayout.compressedNudgePx(3f, 0.5f), 0.001f)
        assertEquals(6f, GestureLabelLayout.compressedNudgePx(3f, 1f), 0.001f)
        assertEquals(
            GestureLabelLayout.COMPRESSED_NUDGE_DP * density,
            GestureLabelLayout.compressedNudgePx(density, 1f),
            0.001f
        )
    }

    /** 悬浮键盘：偏移随比例收缩，墨迹底边仍在键帽内（下移量被夹紧吸收）。 */
    @Test fun floatingKeyboardStaysInsideTheKeyCap() {
        val keyHeightPx = 38f * density
        val fit = GestureLabelLayout.fitScale(density, keyHeightPx)
        val shrink = GestureLabelLayout.shrinkTextScale(fit)
        val inkHalf = fullSizeInkHalf * shrink
        val gap = minGap * shrink
        val result = offset(keyHeightPx = keyHeightPx, inkHalfPx = inkHalf, gapPx = gap)
        assertTrue("压缩键位仍应比全尺寸更靠内", result < margin15dp)
        assertTrue(
            "墨迹底边不得越过键帽下沿",
            result + inkHalf + gap <= keyHeightPx / 2f + 0.01f
        )
    }

    /** 横屏分体（缩小策略下）：字号与边距一起收缩，墨迹底边仍收在键帽内。 */
    @Test fun landscapeFlatKeyStaysInsideTheKeyCapWhenShrunk() {
        val keyHeightPx = 30f * density
        val fit = GestureLabelLayout.fitScale(density, keyHeightPx)
        val shrink = GestureLabelLayout.shrinkTextScale(fit)
        val inkHalf = fullSizeInkHalf * shrink
        val gap = minGap * shrink
        val result = offset(keyHeightPx = keyHeightPx, inkHalfPx = inkHalf, gapPx = gap)
        assertTrue(
            "墨迹底边不得越过键帽下沿",
            result + inkHalf + gap <= keyHeightPx / 2f + 0.01f
        )
    }

    /** 键帽越矮偏移越小（或持平），不会出现缩放反向。 */
    @Test fun offsetIsMonotonicInKeyHeight() {
        val heights = listOf(48f, 42f, 38f, 34f, 30f, 24f).map { it * density }
        val offsets = heights.map { offset(keyHeightPx = it) }
        offsets.zipWithNext().forEach { (taller, shorter) ->
            assertTrue("键帽变矮偏移不应变大", shorter <= taller + 0.01f)
        }
    }

    /** 边距为 0 时墨迹中心正压中线。 */
    @Test fun zeroMarginKeepsInkCentreOnTheMidline() {
        assertEquals(0f, offset(marginPx = 0f, keyHeightPx = 48f * density), 0.01f)
    }

    /**
     * 键帽矮到连字都放不下时退回 0（墨迹中心压中线），绝不朝中线另一侧偏移 ——
     * 否则「底部」标签会翻到字母上方。
     */
    @Test fun oversizedInkNeverFlipsToTheOtherSide() {
        val result = offset(keyHeightPx = 10f * density, inkHalfPx = 40f)
        assertEquals(0f, result, 0.01f)
    }

    /** 密度异常时不能算出 NaN 或把标签推到键帽外。 */
    @Test fun invalidDensityFallsBackToFullSize() {
        assertEquals(1f, GestureLabelLayout.fitScale(0f, 144f), 0.001f)
        val result = GestureLabelLayout.clampOffsetPx(margin15dp, 144f, fullSizeInkHalf, 6f)
        assertTrue(result.isFinite())
        assertTrue(result >= 0f)
        assertEquals(margin15dp, result, 0.01f)
    }

    /**
     * 宽扁键位判据：横屏分体实机键帽 135x89（比例 1.51）算宽扁，字母居中后右侧留空档；
     * 竖屏 108x145（0.74）与悬浮（约 0.75）都不算，保持原来的居中落点。
     */
    @Test fun wideKeyIsOnlyTheFlatLandscapeCap() {
        assertTrue(GestureLabelLayout.isWideKey(135f, 89f))
        assertFalse(GestureLabelLayout.isWideKey(108f, 145f))
        assertFalse(GestureLabelLayout.isWideKey(89f, 114f))
        assertFalse("高度为 0 时不能判断为宽扁", GestureLabelLayout.isWideKey(135f, 0f))
    }

    /**
     * 「被压矮」只认明显矮下去的键位：竖屏全尺寸键帽实测 ~145px，比例贴着 1，1~2px 的
     * 测量浮动不能把它算成压缩，否则竖屏调好的基线会被额外下移量推走。
     */
    @Test fun onlyMeaningfullyShortKeysCountAsCompressed() {
        assertFalse(GestureLabelLayout.isCompressed(GestureLabelLayout.fitScale(density, 145f)))
        assertFalse("比例 0.95 的轻微偏差不算压缩", GestureLabelLayout.isCompressed(0.95f))
        assertTrue(GestureLabelLayout.isCompressed(0.89f))
        assertTrue(GestureLabelLayout.isCompressed(GestureLabelLayout.fitScale(density, 38f * density)))
        assertTrue(GestureLabelLayout.isCompressed(GestureLabelLayout.fitScale(density, 30f * density)))
    }

    /**
     * 宽扁键位右对齐：墨迹右缘落在键帽右沿内并留出角落留白，且与字母（居中）之间仍有空隙。
     * 实机横屏 Q 键 rect.right=373、字母墨迹右缘 324、标签半宽 14。
     */
    @Test fun rightAlignedInkClearsTheCentredLetter() {
        val rectRight = 373f
        val padding = GestureLabelLayout.WIDE_KEY_EDGE_PADDING_DP * density
        val marginRight = 0f
        val inkHalf = 14f
        val x = rectRight - padding - marginRight - inkHalf
        assertEquals("留白必须真的留出来", 12f, padding, 0.001f)
        assertTrue("墨迹右缘不得越过键帽右沿", x + inkHalf <= rectRight - padding + 0.01f)
        assertTrue("墨迹左缘要离开居中的字母", x - inkHalf > 324f)
    }
}
