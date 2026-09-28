package com.xposed.wetypehook.wetype.hook

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 翻译守卫与翻译管理器都不得再写死宿主的混淆名。 */
class ClipboardSearchTranslationGuardTest {
    private val source = File("src/main/java/com/xposed/wetypehook/wetype/hook/WeTypeClipboardSearchUi.kt").readText()

    @Test
    fun translationGuardResolvesBothEntryPointsFromContracts() {
        val start = source.indexOf("private fun ensureTranslationNetGuardF41")
        assertTrue("ensureTranslationNetGuardF41 must exist", start >= 0)
        val end = source.indexOf("private fun applySearchHintF41", start)
        assertTrue("applySearchHintF41 must follow", end > start)
        val body = source.substring(start, end)

        assertFalse("不得再写死调度入口的混淆名", body.contains("getDeclaredMethod(\"T0\""))
        assertFalse("不得再写死提交入口的混淆名", body.contains("getDeclaredMethod(\"T\","))
        assertTrue(body.contains("requireMethod(HostContractId.CLIPBOARD_HEIGHT_SET_TEXT)"))
        assertTrue(body.contains("requireMethod(HostContractId.CLIPBOARD_HEIGHT_SET_CHAR)"))
    }

    @Test
    fun translatingManagerPrefersTheContractResolvedClass() {
        val start = source.indexOf("private fun translatingMgr")
        assertTrue("translatingMgr must exist", start >= 0)
        val end = source.indexOf("/** q.t0()", start)
        assertTrue("isTranslating must follow", end > start)
        val body = source.substring(start, end)

        assertTrue(
            "类必须先走契约解析，历史短名只作兜底",
            body.contains("requireClass(HostContractId.CLIPBOARD_HEIGHT_MANAGER)")
        )
    }
}
