package com.xposed.wetypehook.wetype.hook

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Guards scroll-to-first-match behavior in the clipboard highlight filter. */
class ClipboardSearchHighlightScrollTest {
    private val source =
        File("src/main/java/com/xposed/wetypehook/wetype/hook/WeTypeClipboardSearchFilter.kt").readText()

    @Test fun highlightScrollsContentToFirstMatch() {
        val idx = source.indexOf("private fun scrollContentToHighlight")
        assertTrue("scrollContentToHighlight must exist", idx >= 0)
        val endIdx = source.indexOf("private fun clearOurSpans", idx)
        assertTrue("clearOurSpans must follow", endIdx > idx)
        val body = source.substring(idx, endIdx)
        assertTrue(
            "must target the content HorizontalScrollView",
            body.contains("tv.parent as? HorizontalScrollView")
        )
        assertTrue(
            "must compute match x from layout",
            body.contains("layout.getPrimaryHorizontal(offset)")
        )
        assertTrue(
            "must clamp into scroll range",
            body.contains("coerceIn(0, maxScroll)")
        )
        assertTrue(
            "must compare against transformation-applied text (single-line replaces \\r\\n with \\uFEFF)",
            body.contains("tv.transformationMethod?.getTransformation(tv.text, tv)")
        )
        assertTrue(
            "must compare layout text by content",
            body.contains("TextUtils.equals(layout.text, expectedText)")
        )
        assertFalse(
            "must not compare raw tv.text to layout text (newline transform mismatch)",
            body.contains("TextUtils.equals(layout.text, tv.text)")
        )
        assertFalse(
            "must not use reference equality on layout text",
            body.contains("layout.text === tv.text")
        )
    }

    @Test fun highlightAppliesScrollAfterSpans() {
        // 染色与写回现在收在 colorRanges 里，highlightTextView 先调它、拿到首个命中起点后再滚。
        val colorIdx = source.indexOf("private fun colorRanges")
        assertTrue("colorRanges must exist", colorIdx >= 0)
        val colorBody = source.substring(colorIdx, source.indexOf("private fun scheduleSnippetIfMatchOffscreen"))
        assertTrue(
            "colorRanges must write the spannable back to the TextView",
            colorBody.contains("tv.setText(spannable, TextView.BufferType.NORMAL)")
        )
        val body = source.substring(source.indexOf("private fun highlightTextView"), colorIdx)
        val applied = body.indexOf("colorRanges(tv, original, ranges)")
        assertTrue("spans must be applied first", applied >= 0)
        val call = body.indexOf("scrollContentToHighlight(tv, firstStart)", applied)
        assertTrue("scroll must be triggered after spans are applied", call > applied)
    }

    @Test fun highlightNeverWritesSpannableBuffer() {
        // SPANNABLE 会让 TextView 改用 DynamicLayout：不按 maxLines 封顶、没有省略号，
        // 瀑布流 rebuildTextLayout 永远收敛不了（每帧重排）。
        assertFalse(
            "highlight must not write BufferType.SPANNABLE",
            source.contains("BufferType.SPANNABLE")
        )
    }

    @Test fun snippetIsJudgedOnEveryLayoutNotOnce() {
        val idx = source.indexOf("private fun scheduleSnippetIfMatchOffscreen")
        assertTrue("scheduleSnippetIfMatchOffscreen must exist", idx >= 0)
        val body = source.substring(idx, source.indexOf("private fun evaluateSnippet"))
        assertTrue(
            "must watch layout changes instead of judging once",
            body.contains("addOnLayoutChangeListener(snippetLayoutListener)")
        )
        assertTrue(
            "decision must go through the pure logic with ellipsis start",
            source.contains("ClipboardSnippetLogic.isMatchHidden") && source.contains("getEllipsisStart(last)")
        )
    }
}
