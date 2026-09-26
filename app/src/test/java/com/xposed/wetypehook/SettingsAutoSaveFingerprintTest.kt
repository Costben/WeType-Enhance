package com.xposed.wetypehook

import com.xposed.wetypehook.wetype.settings.EdgeLightGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 自动落盘的指纹只回答一个问题：这一格改动值不值得写盘。
 *
 * 两条规矩反过来都会出事——真正落盘的字段漏出指纹，就是「改了不保存」；界面专用字段混进指纹，
 * 就是「切个明暗预览也白写一次盘」。这里各钉一条。
 */
class SettingsAutoSaveFingerprintTest {

    @Test
    fun persistedFieldChangesMoveTheFingerprint() {
        val base = sampleState()
        val baseline = persistedStateFingerprint(base, null)
        listOf(
            "lightColor" to 0x80112233.toInt(),
            "blurRadius" to 19,
            "cornerRadius" to 31,
            "backgroundLight" to EdgeLightGroup(glowIntensity = 137).text(),
            "iconLight" to EdgeLightGroup(glowWidth = 5).text(),
            "keyLight" to EdgeLightGroup(edgeIntensity = 11).text(),
            "edgeLightAngle" to 180,
            "systemMaterialEnabled" to true,
            "candidateBackgroundAlpha" to 33,
            "toolbarIconBgOpacity" to 44,
            "gestureThreshold" to 21,
            "showGestureKeyLabels" to false,
            "fontMode" to 2,
        ).forEach { (key, value) ->
            val changed = LinkedHashMap(base).apply { this[key] = value }
            assertNotEquals(
                "$key 是真要落盘的字段，改了指纹必须跟着变",
                baseline,
                persistedStateFingerprint(changed, null)
            )
        }
    }

    @Test
    fun uiOnlyFieldChangesLeaveTheFingerprintAlone() {
        val base = sampleState()
        val baseline = persistedStateFingerprint(base, null)
        UI_ONLY_SAVED_STATE_KEYS.forEach { key ->
            val changed = LinkedHashMap(base).apply { this[key] = "changed-$key" }
            assertEquals(
                "$key 只是界面状态，不该触发写盘",
                baseline,
                persistedStateFingerprint(changed, null)
            )
        }
    }

    @Test
    fun glassOverridesArePartOfTheFingerprint() {
        val base = sampleState()
        assertNotEquals(
            "玻璃参数不进 toSavedStateMap，不单独并进来就永远不会被自动落盘",
            persistedStateFingerprint(base, null),
            persistedStateFingerprint(base, 42)
        )
    }

    @Test
    fun appearanceColorsArePartOfTheFingerprint() {
        val base = sampleState()
        assertNotEquals(
            "品牌强调色与按键颜色不在 toSavedStateMap 里，不并进来就改完不落盘",
            persistedStateFingerprint(base, null, listOf(0xFF112233.toInt())),
            persistedStateFingerprint(base, null, listOf(0xFF445566.toInt()))
        )
    }

    @Test
    fun uiOnlyKeySetCannotWidenSilently() {
        assertEquals(
            "往排除表里加名字等于「这个字段以后不再自动落盘」，必须是刻意决定",
            setOf(
                "currentModeIsDark",
                "appearancePreviewPinned",
                "keyboardPreviewEnabled",
                "previewWallpaperName",
                "colorInput",
                "alphaValue",
            ),
            UI_ONLY_SAVED_STATE_KEYS
        )
    }

    /** 一份够用的样例状态：键名与真表一致，值只要互不相等就行。 */
    private fun sampleState(): Map<String, Any> = linkedMapOf(
        "lightColor" to 0xFF112233.toInt(),
        "blurRadius" to 18,
        "cornerRadius" to 30,
        "backgroundLight" to EdgeLightGroup().text(),
        "iconLight" to EdgeLightGroup(enabled = false).text(),
        "keyLight" to EdgeLightGroup(edgeWidth = 3).text(),
        "edgeLightAngle" to 45,
        "systemMaterialEnabled" to false,
        "candidateBackgroundAlpha" to 200,
        "toolbarIconBgOpacity" to 200,
        "gestureThreshold" to 20,
        "showGestureKeyLabels" to true,
        "fontMode" to 0,
        "currentModeIsDark" to false,
        "appearancePreviewPinned" to false,
        "keyboardPreviewEnabled" to false,
        "previewWallpaperName" to "",
        "colorInput" to "#112233",
        "alphaValue" to 255,
    )
}
