package com.xposed.wetypehook.wetype.clipboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardSnippetLogicTest {

    private fun visibleEnd(
        lineCount: Int,
        maxLines: Int,
        capLineEnd: Int = 0,
        lastLineStart: Int = 0,
        lastLineEnd: Int = 0,
        ellipsisStart: Int = 0,
        ellipsisCount: Int = 0
    ) = ClipboardSnippetLogic.visibleEnd(
        lineCount, maxLines, capLineEnd, lastLineStart, lastLineEnd, ellipsisStart, ellipsisCount
    )

    /** 实机：maxLines=4 而排版还有 13 行（没封顶），屏幕上只画前 4 行，终点是第 4 行的行尾。 */
    @Test fun uncappedLayoutSeesOnlyFirstMaxLines() {
        val end = visibleEnd(lineCount = 13, maxLines = 4, capLineEnd = 28, lastLineStart = 93, lastLineEnd = 100)
        assertEquals(28, end)
        assertTrue(ClipboardSnippetLogic.isMatchHidden(matchEnd = 99, visibleEnd = end))
        assertFalse(ClipboardSnippetLogic.isMatchHidden(matchEnd = 20, visibleEnd = end))
    }

    /** 封顶后的排版：最后一行带省略号，`getLineEnd` 仍是全文末尾，必须用省略号起点判断。 */
    @Test fun cappedLayoutUsesEllipsisStartNotLineEnd() {
        // 4 行、末行从 21 起、省略号从行内 5 开始（= 绝对 26），整段 100 字。
        val end = visibleEnd(4, 4, lastLineStart = 21, lastLineEnd = 100, ellipsisStart = 5, ellipsisCount = 74)
        assertEquals(26, end)
        assertTrue(ClipboardSnippetLogic.isMatchHidden(99, end))
        assertFalse(ClipboardSnippetLogic.isMatchHidden(24, end))
        // 命中刚好压在省略号起点上：已经被吃掉了。
        assertTrue(ClipboardSnippetLogic.isMatchHidden(26, end))
    }

    @Test fun layoutThatFitsShowsEverything() {
        val end = visibleEnd(2, 4, lastLineStart = 23, lastLineEnd = 27)
        assertEquals(27, end)
        assertFalse(ClipboardSnippetLogic.isMatchHidden(26, end))
    }

    @Test fun unlimitedMaxLinesShowsEverything() {
        val end = visibleEnd(13, Int.MAX_VALUE, lastLineStart = 93, lastLineEnd = 100)
        assertEquals(100, end)
        assertFalse(ClipboardSnippetLogic.isMatchHidden(99, end))
    }

    @Test fun emptyLayoutShowsNothing() {
        assertEquals(0, visibleEnd(0, 4))
    }

    /** 实机截图：58 字原文，命中在第 55 位，卡片原本能显示 28 字 —— 窗口要贴着原文末尾往前取。 */
    @Test fun windowFillsCardWhenMatchIsNearTheEnd() {
        val start = ClipboardSnippetLogic.snippetWindowStart(matchStart = 55, textLength = 58, capacity = 28)
        assertEquals(30, start)
        // 窗口 [30, 58) 恰好是 28 字，命中（55..56）落在里面、后面还有上下文。
        assertTrue(start <= 55 && 58 - start >= 28)
    }

    @Test fun windowCentersTheMatchInLongText() {
        val start = ClipboardSnippetLogic.snippetWindowStart(matchStart = 9118, textLength = 13415, capacity = 28)
        assertEquals(9104, start)
    }

    @Test fun windowNeverStartsAfterTheMatchOrBeforeZero() {
        assertEquals(0, ClipboardSnippetLogic.snippetWindowStart(matchStart = 5, textLength = 100, capacity = 28))
        assertEquals(10, ClipboardSnippetLogic.snippetWindowStart(matchStart = 10, textLength = 100, capacity = 0))
    }

    @Test fun compactStartKeepsLeadAndNeverGoesNegative() {
        assertEquals(95, ClipboardSnippetLogic.snippetStart(98, 3))
        assertEquals(0, ClipboardSnippetLogic.snippetStart(2, 3))
    }
}
