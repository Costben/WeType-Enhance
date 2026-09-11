package com.xposed.wetypehook.wetype.hook

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Guards the production Android adapter, which plain JVM cannot instantiate. */
class ClipboardSearchSubmitRoutingTest {
    private val source = File("src/main/java/com/xposed/wetypehook/wetype/hook/WeTypeClipboardSearchUi.kt").readText()
    @Test fun ordinaryInputConnectionCommitsDoNotSubmit() {
        for (name in listOf("commitText", "finishComposingText")) {
            val start = source.indexOf("override fun $name(")
            if (start < 0) continue // inherited InputConnectionWrapper pass-through
            var at = source.indexOf('{', start) + 1
            val bodyStart = at
            var depth = 1
            while (depth > 0) { if (source[at] == '{') depth++; if (source[at] == '}') depth--; at++ }
            assertFalse("$name is text delivery, not a search intention", source.substring(bodyStart, at).contains("onEnter?.invoke()"))
        }
    }
    @Test fun translationIsolationDoesNotGuessEveryStringMethod() {
        assertFalse("translation isolation must use verified signatures", source.contains("val hasStr = m.parameterTypes.any"))
    }
    @Test fun clipboardPanelToggleHooksNavigationAndCollapsesExpandedSearchStrip() {
        assertTrue("hookClipboardPanelToggle must be installed", source.contains("hookClipboardPanelToggle(classLoader)"))
        assertTrue("isClipboardPanel must check CustomPhraseAndClipboard", source.contains("enumObj.name == \"CustomPhraseAndClipboard\""))
        val hookIdx = source.indexOf("fun hookClipboardPanelToggle")
        val nextIdx = source.indexOf("fun clampCandidateWindowAfterJ3F31", hookIdx)
        assertTrue("hookClipboardPanelToggle must be present", hookIdx >= 0 && nextIdx > hookIdx)
        val hookBody = source.substring(hookIdx, nextIdx)
        assertTrue("toggle must check isClipboardPanel", hookBody.contains("isClipboardPanel(targetPanel)"))
        assertTrue("toggle must check isSearchStripExpanded", hookBody.contains("isSearchStripExpanded()"))
        assertTrue("toggle must collapse strip synchronously", hookBody.contains("collapseStripF41()"))
        assertFalse("toggle must not cancel navigation to clipboard", hookBody.contains("param.result = null"))
        assertFalse("toggle must not delay navigation with a replay", hookBody.contains("postDelayed"))
    }
    @Test fun bitmapFromImageViewF22PreservesDrawableBounds() {
        val methodIdx = source.indexOf("fun bitmapFromImageViewF22")
        assertTrue("bitmapFromImageViewF22 must exist", methodIdx >= 0)
        val endIdx = source.indexOf("fun bitmapFromViewDrawF22", methodIdx)
        assertTrue("bitmapFromViewDrawF22 must follow", endIdx > methodIdx)
        val body = source.substring(methodIdx, endIdx)
        assertTrue("must save original drawable bounds", body.contains("val savedBounds = android.graphics.Rect(d.bounds)"))
        assertTrue("must restore original drawable bounds", body.contains("d.bounds = savedBounds"))
    }
}
