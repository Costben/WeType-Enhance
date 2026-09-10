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
        assertTrue("toggle must check CustomPhraseAndClipboard", source.contains("enumObj.name == \"CustomPhraseAndClipboard\""))
        assertTrue("toggle must check isSearchStripExpanded", source.contains("isSearchStripExpanded()"))
        assertTrue("toggle must cancel switch and collapse strip", source.contains("param.result = null") && source.contains("collapseStripF41()"))
    }
}
