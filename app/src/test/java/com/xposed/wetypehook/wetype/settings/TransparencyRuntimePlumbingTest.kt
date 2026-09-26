package com.xposed.wetypehook.wetype.settings

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the cross-process and alpha paths that previously made sliders appear inert. */
class TransparencyRuntimePlumbingTest {

    private fun source(path: String): String = File(path).readText()

    @Test
    fun inputMethodRefreshDoesNotDependOnPreviewToggle() {
        val liveReload = source(
            "src/main/java/com/xposed/wetypehook/wetype/hook/KeyboardPreviewLiveReload.kt"
        )
        assertFalse(liveReload.contains("isAppearanceStagePreviewEnabled(service)"))
        assertTrue(liveReload.contains("WeTypeSettings.reloadHostPreferences(service)"))
    }

    @Test
    fun standaloneSettingsUseApi102BeforeUidLocalFallback() {
        val restarter = source(
            "src/main/java/com/xposed/wetypehook/wetype/settings/WeTypeProcessRestarter.kt"
        )
        assertTrue(restarter.contains("ModuleApplication.requestHotReloadForWeType()"))
        assertTrue(restarter.contains("info.uid == myUid"))
    }

    @Test
    fun remoteSettingsAreMirroredToTheImeHostBeforeReconcile() {
        val settings = source("src/main/java/com/xposed/wetypehook/wetype/settings/WeTypeSettings.kt")
        val mainHook = source("src/main/java/com/xposed/wetypehook/MainHook.kt")
        assertTrue(settings.contains("fun syncHostSnapshotFromRemote()"))
        assertTrue(settings.contains("KEY_HOST_SYNC_REVISION"))
        assertTrue(settings.contains("putLong(KEY_HOST_SYNC_REVISION, revision)"))
        assertTrue(mainHook.contains("WeTypeSettings.syncHostSnapshotFromRemote()"))
    }

    @Test
    fun colorOsBackplateKeepsTheConfiguredAlpha() {
        val windowHooks = source(
            "src/main/java/com/xposed/wetypehook/wetype/hook/WeTypeWindowHooks.kt"
        )
        assertFalse(windowHooks.contains("MIN_PANEL_ALPHA"))
        assertTrue(windowHooks.contains("carrier, context, style, skipStroke = true"))
    }

    @Test
    fun existingToolbarDrawablesAreRefreshedAfterRemoteChanges() {
        val resourceHooks = source(
            "src/main/java/com/xposed/wetypehook/wetype/hook/WeTypeResourceHooks.kt"
        )
        val settings = source(
            "src/main/java/com/xposed/wetypehook/wetype/settings/WeTypeSettings.kt"
        )
        assertTrue(resourceHooks.contains("reconcileCurrentToolbarIconBackgrounds"))
        assertTrue(settings.contains("setXposedSnapshotChangeListener"))
    }
}
