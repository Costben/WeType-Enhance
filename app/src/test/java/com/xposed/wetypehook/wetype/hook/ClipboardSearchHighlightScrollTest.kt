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
        val setText = source.indexOf("tv.setText(spannable, TextView.BufferType.SPANNABLE)")
        assertTrue("span setText must exist", setText >= 0)
        val call = source.indexOf("scrollContentToHighlight(tv, firstStart)", setText)
        assertTrue("scroll must be triggered after span setText", call > setText)
    }
}
