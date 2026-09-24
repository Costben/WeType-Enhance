package com.xposed.wetypehook

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.xposed.wetypehook.wetype.settings.WeTypeProcessRestarter
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Refresh

/**
 * 设置界面右上角的统一动作，让输入法进程重新读取刚写入的配置。
 *
 * 一级页的滑块与开关只改内存状态，点击它才会写盘并重启输入法进程；
 * 二级页自身已经即时写盘，点击它只负责让改动生效。
 */
@Composable
internal fun SettingsRefreshButton(modifier: Modifier = Modifier, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = modifier
    ) {
        Icon(
            imageVector = MiuixIcons.Refresh,
            contentDescription = stringResource(R.string.settings_refresh_title)
        )
    }
}

/** 让输入法进程重新读取已写入的配置，二级页点「刷新」时调用。 */
internal fun applySettingsToImeProcess(context: Context): Boolean =
    WeTypeProcessRestarter.restartImeProcess(context)
