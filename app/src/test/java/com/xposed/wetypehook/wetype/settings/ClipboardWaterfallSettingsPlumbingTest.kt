package com.xposed.wetypehook.wetype.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 剪贴板双列瀑布流的两项设置（开关 + 卡片最大高度）必须落满「能存能读」的每一个点。
 *
 * 漏掉任何一处都不是编译错误：表现是设置页里点了没反应、或者重启后被悄悄改回默认，
 * 从现象根本倒推不出来（`WeTypeSettingsStateSaver` 漏一处就是「填了不保存」）。
 * 这里把三条管道（`WeTypeSettings` 快照 / `WeTypeSettingsState` / `WeTypeSettingsStateSaver`）
 * 的落点逐条钉住。
 */
class ClipboardWaterfallSettingsPlumbingTest {

    private val settings = File(
        "src/main/java/com/xposed/wetypehook/wetype/settings/WeTypeSettings.kt"
    ).readText()
    private val state = File(
        "src/main/java/com/xposed/wetypehook/WeTypeSettingsState.kt"
    ).readText()
    private val saver = File(
        "src/main/java/com/xposed/wetypehook/WeTypeSettingsStateSaver.kt"
    ).readText()

    @Test
    fun settingsObjectLandsOnEveryReadWriteSite() {
        val required = listOf(
            // 键与默认值
            "const val KEY_CLIPBOARD_WATERFALL_FLOW = \"clipboard_waterfall_flow_enabled\"",
            "const val KEY_CLIPBOARD_WATERFALL_MAX_HEIGHT_DP = \"clipboard_waterfall_max_height_dp\"",
            "const val DEFAULT_CLIPBOARD_WATERFALL_FLOW_ENABLED = false",
            "const val DEFAULT_CLIPBOARD_WATERFALL_MAX_HEIGHT_DP = 140",
            // 快照字段
            "val clipboardWaterfallFlowEnabled: Boolean = DEFAULT_CLIPBOARD_WATERFALL_FLOW_ENABLED,",
            "val clipboardWaterfallMaxHeightDp: Int = DEFAULT_CLIPBOARD_WATERFALL_MAX_HEIGHT_DP,",
            // 设置页（模块进程）与输入法进程各自的读取入口
            "readSnapshot(context).clipboardWaterfallFlowEnabled",
            "readSnapshot(context).clipboardWaterfallMaxHeightDp",
            "readSnapshotXposed().clipboardWaterfallFlowEnabled",
            "readSnapshotXposed().clipboardWaterfallMaxHeightDp",
            // 整页保存入口的参数（两处同形：saveSettings / 另一个写盘入口）
            "clipboardWaterfallFlowEnabled: Boolean = DEFAULT_CLIPBOARD_WATERFALL_FLOW_ENABLED,",
            "clipboardWaterfallMaxHeightDp: Int = DEFAULT_CLIPBOARD_WATERFALL_MAX_HEIGHT_DP,",
            // 组装快照
            "clipboardWaterfallFlowEnabled = clipboardWaterfallFlowEnabled,",
            "clipboardWaterfallMaxHeightDp = sanitizeClipboardWaterfallMaxHeightDp(clipboardWaterfallMaxHeightDp),",
            // 局部更新读回当前值
            "clipboardWaterfallFlowEnabled = current.clipboardWaterfallFlowEnabled,",
            "clipboardWaterfallMaxHeightDp = current.clipboardWaterfallMaxHeightDp,",
            // 两条写盘路径
            ".putBoolean(KEY_CLIPBOARD_WATERFALL_FLOW, snapshot.clipboardWaterfallFlowEnabled)",
            ".putInt(KEY_CLIPBOARD_WATERFALL_MAX_HEIGHT_DP, snapshot.clipboardWaterfallMaxHeightDp)",
            "putBoolean(KEY_CLIPBOARD_WATERFALL_FLOW, clipboardWaterfallFlowEnabled)",
            "putInt(KEY_CLIPBOARD_WATERFALL_MAX_HEIGHT_DP, clipboardWaterfallMaxHeightDp)",
            // 两条读取路径（带 defaults 的与直接读默认的）
            "defaults.clipboardWaterfallFlowEnabled",
            "getInt(KEY_CLIPBOARD_WATERFALL_MAX_HEIGHT_DP, defaults.clipboardWaterfallMaxHeightDp)",
            "DEFAULT_CLIPBOARD_WATERFALL_FLOW_ENABLED",
            "getInt(KEY_CLIPBOARD_WATERFALL_MAX_HEIGHT_DP, DEFAULT_CLIPBOARD_WATERFALL_MAX_HEIGHT_DP)",
            // 默认快照
            "clipboardWaterfallFlowEnabled = DEFAULT_CLIPBOARD_WATERFALL_FLOW_ENABLED,",
            "clipboardWaterfallMaxHeightDp = DEFAULT_CLIPBOARD_WATERFALL_MAX_HEIGHT_DP,",
            // 高度夹紧
            "fun sanitizeClipboardWaterfallMaxHeightDp(value: Int): Int =",
        )
        required.forEach { snippet ->
            assertTrue("WeTypeSettings 缺少落点：$snippet", settings.contains(snippet))
        }
    }

    @Test
    fun settingsStateCarriesBothFields() {
        val required = listOf(
            "var clipboardWaterfallFlowEnabled by mutableStateOf(snapshot.clipboardWaterfallFlowEnabled)",
            "var clipboardWaterfallMaxHeightDp by mutableIntStateOf(snapshot.clipboardWaterfallMaxHeightDp)",
            "clipboardWaterfallFlowEnabled = clipboardWaterfallFlowEnabled,",
            "clipboardWaterfallMaxHeightDp = clipboardWaterfallMaxHeightDp,",
            "clipboardWaterfallFlowEnabled = WeTypeSettings.DEFAULT_CLIPBOARD_WATERFALL_FLOW_ENABLED",
            "clipboardWaterfallMaxHeightDp = WeTypeSettings.DEFAULT_CLIPBOARD_WATERFALL_MAX_HEIGHT_DP",
        )
        required.forEach { snippet ->
            assertTrue("WeTypeSettingsState 缺少落点：$snippet", state.contains(snippet))
        }
    }

    /** 漏了这里就是「填了不保存」——设置页里的值活不过一次重建。 */
    @Test
    fun savedStateStoresAndRestoresBothFields() {
        val required = listOf(
            "\"clipboardWaterfallFlowEnabled\" to clipboardWaterfallFlowEnabled,",
            "\"clipboardWaterfallMaxHeightDp\" to clipboardWaterfallMaxHeightDp,",
            "clipboardWaterfallFlowEnabled = saved[\"clipboardWaterfallFlowEnabled\"] as? Boolean",
            "clipboardWaterfallMaxHeightDp = saved[\"clipboardWaterfallMaxHeightDp\"] as? Int",
        )
        required.forEach { snippet ->
            assertTrue("WeTypeSettingsStateSaver 缺少落点：$snippet", saver.contains(snippet))
        }
    }

    /** 设置页必须真的把两个回调接上，否则 UI 上点了不动。 */
    @Test
    fun settingsSubPageWiresBothCallbacks() {
        val subPages = File(
            "src/main/java/com/xposed/wetypehook/WeTypeSettingsSubPages.kt"
        ).readText()
        val page = File(
            "src/main/java/com/xposed/wetypehook/ClipboardSubPage.kt"
        ).readText()
        assertTrue(subPages.contains("clipboardWaterfallFlowEnabled = clipboardWaterfallFlowEnabled,"))
        assertTrue(subPages.contains("onClipboardWaterfallFlowEnabledChange = { clipboardWaterfallFlowEnabled = it },"))
        assertTrue(subPages.contains("clipboardWaterfallMaxHeightDp = clipboardWaterfallMaxHeightDp,"))
        assertTrue(subPages.contains("onClipboardWaterfallMaxHeightDpChange = { clipboardWaterfallMaxHeightDp = it },"))
        assertTrue(page.contains("onClipboardWaterfallFlowEnabledChange: (Boolean) -> Unit,"))
        assertTrue(page.contains("onClipboardWaterfallMaxHeightDpChange: (Int) -> Unit,"))
    }

    @Test
    fun maxHeightSanitizerClampsToTheSupportedRange() {
        assertEquals(
            WeTypeSettings.DEFAULT_CLIPBOARD_WATERFALL_MAX_HEIGHT_DP,
            WeTypeSettings.sanitizeClipboardWaterfallMaxHeightDp(
                WeTypeSettings.DEFAULT_CLIPBOARD_WATERFALL_MAX_HEIGHT_DP
            )
        )
        assertEquals(
            WeTypeSettings.CLIPBOARD_WATERFALL_MIN_HEIGHT_DP,
            WeTypeSettings.sanitizeClipboardWaterfallMaxHeightDp(1)
        )
        assertEquals(
            WeTypeSettings.CLIPBOARD_WATERFALL_MAX_HEIGHT_DP_LIMIT,
            WeTypeSettings.sanitizeClipboardWaterfallMaxHeightDp(10_000)
        )
        assertEquals(160, WeTypeSettings.sanitizeClipboardWaterfallMaxHeightDp(160))
    }
}
