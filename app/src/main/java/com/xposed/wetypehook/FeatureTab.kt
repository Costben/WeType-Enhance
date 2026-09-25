package com.xposed.wetypehook

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Badge
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme


internal fun LazyListScope.FeatureTabContent(
    onOpenSubPage: (SettingsSubPage) -> Unit,
    disableHotUpdate: Boolean,
    onDisableHotUpdateChange: (Boolean) -> Unit,
    activationStatus: ModuleActivationTracker.ActivationStatus,
    onRestoreDefaults: () -> Unit
) {
    // 1. 剪贴板增强卡片
    item {
        Card(
            modifier = Modifier.padding(horizontal = 16.dp),
            insideMargin = PaddingValues(0.dp)
        ) {
            ArrowPreference(
                title = "剪贴板增强",
                summary = "条目保留、搜索与图片缩略图，以及备份与恢复",
                onClick = { onOpenSubPage(SettingsSubPage.CLIPBOARD) }
            )
        }
    }

    // 2. 进阶系统防护卡片
    item {
        SmallTitle(text = "进阶系统防护")
        Card(
            modifier = Modifier.padding(horizontal = 16.dp),
            insideMargin = PaddingValues(0.dp)
        ) {
            Column {
                SwitchPreference(
                    title = stringResource(R.string.settings_disable_hot_update_title),
                    summary = stringResource(R.string.settings_disable_hot_update_desc),
                    checked = disableHotUpdate,
                    onCheckedChange = onDisableHotUpdateChange
                )
            }
        }
    }

    // 5. 关于与重置卡片
    item {
        val context = LocalContext.current
        SmallTitle(text = "关于与重置")
        Card(
            modifier = Modifier.padding(horizontal = 16.dp),
            insideMargin = PaddingValues(0.dp)
        ) {
            Column {
                BasicComponent(
                    title = "模块版本",
                    summary = "v${BuildConfig.VERSION_NAME} (Code ${BuildConfig.VERSION_CODE})",
                    endActions = {
                        Badge(
                            containerColor = if (activationStatus.isActive) ComposeColor(0xFF4F9A71) else ComposeColor(0xFFC86F67),
                            contentColor = ComposeColor.White
                        ) {
                            Text(text = if (activationStatus.isActive) "已激活" else "未激活")
                        }
                    }
                )
                ArrowPreference(
                    title = stringResource(R.string.settings_visit_github_title),
                    summary = "https://github.com/Costben/WeType-Enhance",
                    titleColor = BasicComponentDefaults.titleColor(
                        color = MiuixTheme.colorScheme.primary
                    ),
                    onClick = {
                        val intent = Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://github.com/Costben/WeType-Enhance")
                        )
                        context.startActivity(intent)
                    }
                )
                ArrowPreference(
                    title = stringResource(R.string.settings_reset_title),
                    summary = stringResource(R.string.settings_reset_desc),
                    onClick = onRestoreDefaults
                )
            }
        }
    }
}
