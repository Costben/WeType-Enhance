package com.xposed.wetypehook.wetype.clipboard

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 双列瀑布流的纯算术：间距拆半、列宽均分、原生 2 倍高度收敛。
 *
 * 这些数字直接决定实机上的列宽与可视高度，算错了在设置页里看不出来，
 * 只能靠实机截图才能发现，所以在这里钉住。
 */
class ClipboardWaterfallLayoutLogicTest {

    @Test
    fun gapIsSplitIntoTwoHalvesSoColumnsEndUpOneFullGapApart() {
        assertEquals(4, ClipboardWaterfallLayoutLogic.columnHalfGapPx(8))
        assertEquals(4, ClipboardWaterfallLayoutLogic.rowHalfGapPx(8))
        // 间距为奇数时向下取整，但绝不退化成 0（0 会让两列贴死）。
        assertEquals(1, ClipboardWaterfallLayoutLogic.columnHalfGapPx(3))
        assertEquals(1, ClipboardWaterfallLayoutLogic.columnHalfGapPx(0))
        assertEquals(1, ClipboardWaterfallLayoutLogic.rowHalfGapPx(1))
    }

    @Test
    fun columnWidthIsTheUsableWidthSplitAcrossSpans() {
        // 1080 - 8 - 8 = 1064，两列各 532。
        assertEquals(532, ClipboardWaterfallLayoutLogic.columnWidthPx(1080, 8, 8))
        assertEquals(532, ClipboardWaterfallLayoutLogic.columnWidthPx(1080, 8, 8, 2))
        // 三列时均分。
        assertEquals(354, ClipboardWaterfallLayoutLogic.columnWidthPx(1080, 8, 8, 3))
    }

    @Test
    fun columnWidthIsZeroWhenThereIsNoUsableWidth() {
        assertEquals(0, ClipboardWaterfallLayoutLogic.columnWidthPx(0, 0, 0))
        assertEquals(0, ClipboardWaterfallLayoutLogic.columnWidthPx(10, 8, 8))
        assertEquals(0, ClipboardWaterfallLayoutLogic.columnWidthPx(1080, 0, 0, 0))
    }

    @Test
    fun textLinesAreWhateverFitsUnderTheCardCapAfterTheFixedChrome() {
        // 实机口径：上限 140dp = 420px，卡片上下内边距 2 × 24px，行高 54px → (420 - 48) / 54 = 6 行。
        assertEquals(6, ClipboardWaterfallLayoutLogic.textMaxLines(420, 48, 54))
        // 带药丸标签行（实测约 90px）时再让出一截：(420 - 48 - 90) / 54 = 5 行。
        assertEquals(5, ClipboardWaterfallLayoutLogic.textMaxLines(420, 48 + 90, 54))
        // 恰好整除也算放得下。
        assertEquals(7, ClipboardWaterfallLayoutLogic.textMaxLines(48 + 7 * 54, 48, 54))
    }

    @Test
    fun textKeepsAtLeastOneLineEvenWhenTheCapIsSmallerThanTheChrome() {
        assertEquals(1, ClipboardWaterfallLayoutLogic.textMaxLines(100, 200, 54))
        assertEquals(1, ClipboardWaterfallLayoutLogic.textMaxLines(60, 48, 54))
    }

    @Test
    fun textIsUncappedWhenTheCapOrTheLineHeightIsUnknown() {
        assertEquals(Int.MAX_VALUE, ClipboardWaterfallLayoutLogic.textMaxLines(0, 48, 54))
        assertEquals(Int.MAX_VALUE, ClipboardWaterfallLayoutLogic.textMaxLines(-1, 48, 54))
        assertEquals(Int.MAX_VALUE, ClipboardWaterfallLayoutLogic.textMaxLines(420, 48, 0))
    }

    @Test
    fun overflowIsCorrectedByDroppingWholeLinesRoundedUp() {
        // 实机口径：上限 420px，4 行排出来 429px，实测行距 72px：超 9px → 少 1 行。
        assertEquals(3, ClipboardWaterfallLayoutLogic.correctedMaxLines(4, 4, 9, 72))
        // 超出一行半 → 向上取整少 2 行，宁可略少也不能仍然超限。
        assertEquals(4, ClipboardWaterfallLayoutLogic.correctedMaxLines(6, 6, 108, 72))
        // 恰好超出整数行。
        assertEquals(5, ClipboardWaterfallLayoutLogic.correctedMaxLines(6, 6, 72, 72))
    }

    @Test
    fun overflowCorrectionNeverRaisesTheLimitOrDropsBelowOneLine() {
        // 只减不增：当前上限比实际显示的行数更小时保持不变。
        assertEquals(2, ClipboardWaterfallLayoutLogic.correctedMaxLines(2, 5, 9, 72))
        // 至少 1 行。
        assertEquals(1, ClipboardWaterfallLayoutLogic.correctedMaxLines(6, 3, 1000, 72))
        // 没超出、或只有 1 行可减、或行距未知：不动。
        assertEquals(6, ClipboardWaterfallLayoutLogic.correctedMaxLines(6, 6, 0, 72))
        assertEquals(6, ClipboardWaterfallLayoutLogic.correctedMaxLines(6, 6, -5, 72))
        assertEquals(6, ClipboardWaterfallLayoutLogic.correctedMaxLines(6, 1, 50, 72))
        assertEquals(6, ClipboardWaterfallLayoutLogic.correctedMaxLines(6, 6, 50, 0))
    }

    @Test
    fun actionButtonsAreCappedAtTheCompactWidthWhenTheColumnHasRoom() {
        // 实机口径：列宽 456、⋮ 84、尾部留白 30、间隙 9、紧凑宽度上限 90：
        // 菜单预算 456 - 84 - 30 = 342，扣两段间隙 18 → 324 / 2 = 162，夹到上限 90。
        assertEquals(90, ClipboardWaterfallLayoutLogic.actionButtonPx(456, 84, 30, 9, 60, 90))
        // 总宽 2 × (90 + 9) = 198，视口里卡片尾部剩 456 - 198 = 258，远大于 ⋮ 84 + 留白 30。
        assertEquals(198, ClipboardWaterfallLayoutLogic.actionMenuWidthPx(90, 9))
        assertEquals(258, 456 - ClipboardWaterfallLayoutLogic.actionMenuWidthPx(90, 9))
    }

    @Test
    fun actionButtonsShrinkToKeepTheMoreButtonReachableInNarrowColumns() {
        // 列宽 300：预算 300 - 84 - 30 = 186，扣间隙 18 → 168 / 2 = 84。
        assertEquals(84, ClipboardWaterfallLayoutLogic.actionButtonPx(300, 84, 30, 9, 60, 90))
        // 列宽小到装不下时宁可溢出也不继续缩（再小就点不中了）。
        assertEquals(60, ClipboardWaterfallLayoutLogic.actionButtonPx(200, 84, 30, 9, 60, 90))
    }

    @Test
    fun actionButtonsStayNativeWhenTheColumnWidthIsUnknown() {
        assertEquals(0, ClipboardWaterfallLayoutLogic.actionButtonPx(0, 84, 30, 9, 60, 90))
        assertEquals(0, ClipboardWaterfallLayoutLogic.actionButtonPx(-5, 84, 30, 9, 60, 90))
        assertEquals(0, ClipboardWaterfallLayoutLogic.actionButtonPx(456, 84, 30, 9, 60, 90, count = 0))
    }

    @Test
    fun linesCardHeightIsTheChromePlusWholeLines() {
        // 实机口径：上下内边距共 48px，行距 72px：两行 192px，三行 264px。
        assertEquals(192, ClipboardWaterfallLayoutLogic.linesCardHeightPx(48, 72, ClipboardWaterfallLayoutLogic.CARD_MIN_LINES))
        assertEquals(264, ClipboardWaterfallLayoutLogic.linesCardHeightPx(48, 72, ClipboardWaterfallLayoutLogic.ACTION_MAX_LINES))
        // 占位为负按 0 算。
        assertEquals(144, ClipboardWaterfallLayoutLogic.linesCardHeightPx(-5, 72, 2))
    }

    @Test
    fun linesCardHeightIsUnknownBeforeTheTextHasALinePitch() {
        assertEquals(0, ClipboardWaterfallLayoutLogic.linesCardHeightPx(48, 0, 2))
        assertEquals(0, ClipboardWaterfallLayoutLogic.linesCardHeightPx(48, 72, 0))
    }

    @Test
    fun actionBlockFollowsTheCardHeightUpToTheThreeLineCap() {
        // 两行卡片：按钮总高正好等于卡片高度。
        assertEquals(192, ClipboardWaterfallLayoutLogic.actionBlockHeightPx(192, 264))
        // 介于两行与三行之间：照样等于卡片高度。
        assertEquals(230, ClipboardWaterfallLayoutLogic.actionBlockHeightPx(230, 264))
        // 内容很长、卡片比三行还高：按钮总高封顶在三行高度。
        assertEquals(264, ClipboardWaterfallLayoutLogic.actionBlockHeightPx(420, 264))
    }

    @Test
    fun actionBlockStaysNativeWithoutACardHeightAndIsUncappedWithoutACap() {
        assertEquals(0, ClipboardWaterfallLayoutLogic.actionBlockHeightPx(0, 264))
        assertEquals(0, ClipboardWaterfallLayoutLogic.actionBlockHeightPx(-1, 264))
        assertEquals(420, ClipboardWaterfallLayoutLogic.actionBlockHeightPx(420, 0))
    }

    @Test
    fun actionRowsAddUpToExactlyTheBlockHeight() {
        // 192 - 9 = 183：上排 91，下排拿余数 92，91 + 9 + 92 = 192。
        val (top, bottom) = ClipboardWaterfallLayoutLogic.actionRowHeightsPx(192, 9)
        assertEquals(91, top)
        assertEquals(92, bottom)
        assertEquals(192, top + 9 + bottom)
        // 偶数时两排等高。
        assertEquals(96 to 96, ClipboardWaterfallLayoutLogic.actionRowHeightsPx(201, 9))
    }

    @Test
    fun actionRowsNeverCollapseAndStayNativeWithoutABlockHeight() {
        assertEquals(0 to 0, ClipboardWaterfallLayoutLogic.actionRowHeightsPx(0, 9))
        assertEquals(0 to 0, ClipboardWaterfallLayoutLogic.actionRowHeightsPx(-3, 9))
        // 总高比间隙还小：每排至少 1px，不出现 0 或负数。
        val (top, bottom) = ClipboardWaterfallLayoutLogic.actionRowHeightsPx(5, 9)
        assertEquals(1, top)
        assertEquals(1, bottom)
    }

    @Test
    fun actionRadiusMatchesTheCardButNeverTurnsIntoAPill() {
        // 卡片圆角 32px、最矮一排 91px：与卡片一致。
        assertEquals(32, ClipboardWaterfallLayoutLogic.actionCornerRadiusPx(32, 91))
        // 行高很小时封在一半以内。
        assertEquals(20, ClipboardWaterfallLayoutLogic.actionCornerRadiusPx(32, 40))
    }

    @Test
    fun actionRadiusFallsBackToARatioOfTheRowWhenTheCardRadiusIsUnknown() {
        assertEquals(27, ClipboardWaterfallLayoutLogic.actionCornerRadiusPx(0, 91))
        assertEquals(27, ClipboardWaterfallLayoutLogic.actionCornerRadiusPx(-1, 91))
        assertEquals(0, ClipboardWaterfallLayoutLogic.actionCornerRadiusPx(32, 0))
    }

    @Test
    fun textViewportDropsThePartTheScrollViewBorrowedFromTheStaleCardWidth() {
        // 布局已经跟上列宽：滚动容器实测宽就是视口（实机 456 的列里卡片 456、滚动容器 332）。
        assertEquals(332, ClipboardWaterfallLayoutLogic.textViewportPx(332, 456, 456, 124, 0))
        // 绑定时量到的还是宿主原生口径（卡片 1008、滚动容器 884）：卡片比条目宽 552，
        // 滚动容器虚高的部分一样多，减掉才回到这一列真正的视口。
        assertEquals(332, ClipboardWaterfallLayoutLogic.textViewportPx(884, 1008, 456, -1, 0))
        // 卡片反而比条目窄（测量落后于写进去的宽度）时不算虚高。
        assertEquals(332, ClipboardWaterfallLayoutLogic.textViewportPx(332, 400, 456, -1, 0))
        // 文本自身左右内边距要扣掉。
        assertEquals(316, ClipboardWaterfallLayoutLogic.textViewportPx(332, 456, 456, 124, 16))
    }

    @Test
    fun textViewportFallsBackToTheKnownInsetBeforeTheScrollViewIsMeasured() {
        // 绑定期滚动容器还没排版（0）：用「卡片宽 - 滚动容器宽」这个与列宽无关的常量从条目宽反推。
        assertEquals(332, ClipboardWaterfallLayoutLogic.textViewportPx(0, 0, 456, 124, 0))
        // 还没量到过那个常量、或条目宽未知：算不出来，调用方保持原样等下一次布局。
        assertEquals(0, ClipboardWaterfallLayoutLogic.textViewportPx(0, 0, 456, -1, 0))
        assertEquals(0, ClipboardWaterfallLayoutLogic.textViewportPx(0, 0, 0, 124, 0))
    }

    @Test
    fun textViewportGoesNegativeWhenTheScrollViewOutrunsTheStaleCard() {
        // 滚动容器已经是新值、卡片还是旧值：扣完只剩负数，调用方据此跳过，不写坏 maxWidth。
        assertEquals(-220, ClipboardWaterfallLayoutLogic.textViewportPx(332, 1008, 456, -1, 0))
    }
}
