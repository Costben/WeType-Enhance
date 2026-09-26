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
 * 写盘由设置页自己负责：一级页与二级页的改动会在停手之后自动落盘，这里只负责让输入法进程
 * 立刻重读。正常路径下不需要点它 —— `:hld` 自己会轮询偏好文件时间戳；它是输入法没跟上时的
 * 显式兜底。
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
