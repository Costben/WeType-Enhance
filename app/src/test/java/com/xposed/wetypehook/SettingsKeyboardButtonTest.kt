package com.xposed.wetypehook

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.VectorPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 键盘图标是手写的路径串：抄错一个字符不会报错，只会把图标静默画歪。这里守住三件事 ——
 * 路径能解析成多段轮廓、机身与键点共用偶奇填充、整串没有可丢的空白。
 */
class SettingsKeyboardButtonTest {

    @Test
    fun iconKeepsTheTwentyFourUnitGrid() {
        val icon = SettingsKeyboardIcon
        assertEquals(24f, icon.defaultWidth.value, 0.001f)
        assertEquals(24f, icon.defaultHeight.value, 0.001f)
        assertEquals(24f, icon.viewportWidth, 0.001f)
        assertEquals(24f, icon.viewportHeight, 0.001f)
    }

    @Test
    fun pathParsesIntoBodyPlusKeyHoles() {
        val path = SettingsKeyboardIcon.root.first() as VectorPath
        assertEquals(PathFillType.EvenOdd, path.pathFillType)
        // 一段机身 + 两排各五颗键点 + 一条空格。
        assertEquals(12, path.pathData.count { it::class.simpleName == "MoveTo" })
    }

    @Test
    fun pathIsFilledSoTheGlyphIsVisible() {
        val path = SettingsKeyboardIcon.root.first() as VectorPath
        // Builder 的默认 fill 是 null，整条路径会静默画成空白；必须显式给一个填充笔刷。
        assertEquals(Color.Black, (path.fill as? SolidColor)?.value)
    }

    @Test
    fun pathStringHasNoWhitespaceAndOnlyLineVerbs() {
        assertFalse(KEYBOARD_ICON_PATH.any { it.isWhitespace() })
        assertTrue(KEYBOARD_ICON_PATH.all { it.isDigit() || it in "MLZ.,-" })
        assertEquals(KEYBOARD_ICON_PATH.count { it == 'M' }, KEYBOARD_ICON_PATH.count { it == 'Z' })
    }
}
