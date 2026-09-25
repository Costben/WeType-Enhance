package com.xposed.wetypehook.wetype.settings

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `key_edge_light_enabled` 必须完整镜像 `icon_edge_light_enabled`。
 *
 * 设置要落到九个地方才算真的能存能读：常量、默认值、Snapshot 字段、三个保存入口的参数与
 * 透传、写盘、两条读取路径、默认快照、迁移判据。漏掉任何一处都不是编译错误——表现是
 * 保存后不生效、或者重启后被悄悄改回默认，极难从现象倒推。所以这里按「每个图标光感的
 * 落点后面必须紧跟同形的按键光感落点」逐个核对，而不是只检查常量存在。
 */
class KeyEdgeLightSettingsMirrorTest {

    private val settings = File(
        "src/main/java/com/xposed/wetypehook/wetype/settings/WeTypeSettings.kt"
    ).readText()

    @Test
    fun everyIconFieldOccurrenceIsMirroredForTheKeySwitch() {
        assertMirroredEverywhere(
            icon = "iconEdgeLightEnabled",
            key = "keyEdgeLightEnabled",
            minimum = 10
        )
    }

    @Test
    fun everyIconPreferenceKeyIsMirroredForTheKeySwitch() {
        assertMirroredEverywhere(
            icon = "KEY_ICON_EDGE_LIGHT_ENABLED",
            key = "KEY_KEY_EDGE_LIGHT_ENABLED",
            minimum = 5
        )
    }

    @Test
    fun everyIconDefaultIsMirroredForTheKeySwitch() {
        assertMirroredEverywhere(
            icon = "DEFAULT_ICON_EDGE_LIGHT_ENABLED",
            key = "DEFAULT_KEY_EDGE_LIGHT_ENABLED",
            minimum = 5
        )
    }

    @Test
    fun theNewSwitchHasBothReaders() {
        assertTrue(settings.contains("fun isKeyEdgeLightEnabled(context: Context): Boolean"))
        assertTrue(settings.contains("fun isKeyEdgeLightEnabledXposed(): Boolean"))
    }

    @Test
    fun theNewSwitchDefaultsToOn() {
        assertTrue(settings.contains("const val DEFAULT_KEY_EDGE_LIGHT_ENABLED = true"))
        assertTrue(settings.contains("private const val KEY_KEY_EDGE_LIGHT_ENABLED = \"key_edge_light_enabled\""))
    }

    /**
     * 每个图标落点之后的一段窗口里必须有对应的按键落点。窗口按相邻行取，够宽到容下
     * 「参数名 → 透传值」这种跨行的写法，又窄到不会把下一个无关落点算进来。
     */
    private fun assertMirroredEverywhere(icon: String, key: String, minimum: Int) {
        var index = settings.indexOf(icon)
        var seen = 0
        while (index >= 0) {
            val window = settings.substring(index, minOf(index + MIRROR_WINDOW_CHARS, settings.length))
            assertTrue(
                "第 $seen 个 $icon 落点没有对应的 $key：${window.lineSequence().first().trim()}",
                window.contains(key)
            )
            seen++
            index = settings.indexOf(icon, index + 1)
        }
        assertTrue("$icon 的落点数少于预期（$seen < $minimum）", seen >= minimum)
    }

    private companion object {
        const val MIRROR_WINDOW_CHARS = 240
    }
}
