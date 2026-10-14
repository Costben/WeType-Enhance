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
    fun actionButtonsSplitWhatIsLeftAfterTheMoreButtonTail() {
        // 实机口径：列宽 456、⋮ 84、尾部留白 30、间隙 18：
        // 菜单预算 456 - 84 - 30 = 342，扣三段间隙 54 → 288 / 3 = 96。
        val button = ClipboardWaterfallLayoutLogic.actionButtonPx(456, 84, 30, 18, 72, 120)
        assertEquals(96, button)
        // 总宽 3 × (96 + 18) = 342，视口里卡片尾部剩 456 - 342 = 114 = ⋮ 84 + 留白 30。
        val menu = ClipboardWaterfallLayoutLogic.actionMenuWidthPx(button, 18)
        assertEquals(342, menu)
        assertEquals(114, 456 - menu)
    }

    @Test
    fun actionButtonsNeverGrowPastTheNativeIconButtonWidth() {
        // 列宽富裕（1008px 的单列）时不放大，维持原生图标按钮 120px。
        assertEquals(120, ClipboardWaterfallLayoutLogic.actionButtonPx(1008, 84, 30, 18, 72, 120))
    }

    @Test
    fun actionButtonsStopShrinkingAtTheMinimumEvenIfTheMenuOverflows() {
        // 列宽小到装不下时宁可溢出也不继续缩（再小就点不中了）。
        assertEquals(72, ClipboardWaterfallLayoutLogic.actionButtonPx(300, 84, 30, 18, 72, 120))
    }

    @Test
    fun actionButtonsStayNativeWhenTheColumnWidthIsUnknown() {
        assertEquals(0, ClipboardWaterfallLayoutLogic.actionButtonPx(0, 84, 30, 18, 72, 120))
        assertEquals(0, ClipboardWaterfallLayoutLogic.actionButtonPx(-5, 84, 30, 18, 72, 120))
        assertEquals(0, ClipboardWaterfallLayoutLogic.actionButtonPx(456, 84, 30, 18, 72, 120, count = 0))
    }
}
