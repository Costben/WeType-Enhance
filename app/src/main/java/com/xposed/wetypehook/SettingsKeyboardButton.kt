package com.xposed.wetypehook

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton

/** 首次 show 偶尔会被系统丢掉，跟激活页一样的重试节奏。 */
private const val KEYBOARD_SHOW_RETRY_COUNT = 3
private const val KEYBOARD_SHOW_RETRY_DELAY_MS = 450L

/**
 * 设置界面右上角「刷新」左边那颗按钮：把真实输入法键盘叫出来。
 *
 * 设置页跑在宿主进程里，当前输入法就是微信输入法本身，所以这里把软键盘叫起来，看到的就是
 * 模块 hook 生效后的**真机键盘**——调完参数不用切到别的应用去验。
 *
 * 实现方式是一个 1dp 的隐形输入框：系统只认「当前窗口里有焦点的可编辑节点」，
 * 没有它 `show()` 是空操作。焦点在点击之后才请求，免得被按钮自己的点击先抢走。
 * 收起交给键盘自己的收起键，这里不做开关——判断键盘是否在屏上依赖 `WindowInsets.ime`，
 * 在 adjustPan 的窗口里读不到，误判就会变成「点一下反而收起来」。
 */
@Composable
internal fun SettingsKeyboardButton(modifier: Modifier = Modifier) {
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    var showTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(showTick) {
        if (showTick == 0) return@LaunchedEffect
        repeat(KEYBOARD_SHOW_RETRY_COUNT) { attempt ->
            if (attempt > 0) delay(KEYBOARD_SHOW_RETRY_DELAY_MS)
            focusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    Box(modifier = modifier) {
        // 放在按钮之前：这颗 1dp 的输入框不该挡住按钮的点击。
        BasicTextField(
            value = "",
            onValueChange = {},
            cursorBrush = SolidColor(Color.Transparent),
            modifier = Modifier
                .size(1.dp)
                .alpha(0f)
                .focusRequester(focusRequester)
        )
        IconButton(onClick = { showTick++ }) {
            Icon(
                imageVector = SettingsKeyboardIcon,
                contentDescription = stringResource(R.string.settings_keyboard_title)
            )
        }
    }
}

/**
 * 键盘字形。miuix 图标集里没有键盘，按 24×24 自己画一个：圆角机身挖掉两排键点加一条空格。
 *
 * 机身与键点共用一条路径、靠偶奇填充把键点挖成孔，所以整颗图标只有一种填充色，
 * `Icon` 的着色照样生效。路径只用 `M` / `L` / `Z`，不留空白——跟工具栏图标一样的抄写约束。
 */
internal val SettingsKeyboardIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "SettingsKeyboard",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).addPath(
        pathData = addPathNodes(KEYBOARD_ICON_PATH),
        // 必须显式给填充：`ImageVector.Builder.addPath` 的默认 fill 是 null，
        // 不填的路径既不描边也不上色，整颗图标会静默画成空白。
        fill = SolidColor(Color.Black),
        pathFillType = PathFillType.EvenOdd
    ).build()
}

internal const val KEYBOARD_ICON_PATH =
    "M3,5L21,5L22.4,5.6L23,7L23,17L22.4,18.4L21,19L3,19L1.6,18.4L1,17L1,7L1.6,5.6L3,5Z" +
        "M3.1,7.4L5.7,7.4L5.7,9.6L3.1,9.6Z" +
        "M6.9,7.4L9.5,7.4L9.5,9.6L6.9,9.6Z" +
        "M10.7,7.4L13.3,7.4L13.3,9.6L10.7,9.6Z" +
        "M14.5,7.4L17.1,7.4L17.1,9.6L14.5,9.6Z" +
        "M18.3,7.4L20.9,7.4L20.9,9.6L18.3,9.6Z" +
        "M3.1,10.8L5.7,10.8L5.7,13L3.1,13Z" +
        "M6.9,10.8L9.5,10.8L9.5,13L6.9,13Z" +
        "M10.7,10.8L13.3,10.8L13.3,13L10.7,13Z" +
        "M14.5,10.8L17.1,10.8L17.1,13L14.5,13Z" +
        "M18.3,10.8L20.9,10.8L20.9,13L18.3,13Z" +
        "M8,14.4L16,14.4L16,16.6L8,16.6Z"
