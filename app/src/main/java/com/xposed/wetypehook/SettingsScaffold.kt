package com.xposed.wetypehook

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.capsule.ContinuousRoundedRectangle
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical


/**
 * 二级设置页的通用外壳：返回按钮回到一级页，右上角「刷新」让输入法进程重读配置。
 *
 * 标题栏固定不随列表滚动（`SmallTopAppBar` 一旦挂上 `MiuixScrollBehavior`，滚动时整条会被推出屏幕），
 * [pinnedContent] 因此直接排进 `topBar` 槽位，成为标题栏的一部分常驻其正下方。
 * 列表内容会从它下方穿过，所以 [pinnedContent] 需要自带不透明背景。
 *
 * [stageContent] 贴窗口底边、排在列表之外，滚动不带走 —— 键盘复刻件靠它常驻屏幕下半部。
 * 列表底部要按 [stageBottomInset] 预留同等高度，否则最后几项会被压在键盘下面。
 */
@Composable
internal fun SettingsSubPageScaffold(
    title: String,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    pinnedContent: (@Composable () -> Unit)? = null,
    stageContent: (@Composable () -> Unit)? = null,
    stageBottomInset: Dp = 0.dp,
    content: LazyListScope.() -> Unit
) {
    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.background),
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MiuixTheme.colorScheme.background)
            ) {
                SmallTopAppBar(
                    title = title,
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = MiuixIcons.Back,
                                contentDescription = "返回"
                            )
                        }
                    },
                    actions = {
                        SettingsKeyboardButton()
                        SettingsRefreshButton(onClick = onRefresh)
                    }
                )
                if (pinnedContent != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            // 左右缩进跟下面列表里的卡片对齐，预览本身仍然没有卡片底衬。
                            .padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
                    ) {
                        pinnedContent()
                    }
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                // 宿主输入法自己升起时（键盘栏上的设置入口），靠这条把列表压到键盘之上。
                .imePadding()
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .overScrollVertical(),
                contentPadding = PaddingValues(
                    top = paddingValues.calculateTopPadding(),
                    bottom = 40.dp + stageBottomInset
                ),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                content()
            }
            if (stageContent != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                ) {
                    stageContent()
                }
            }
        }
    }
}

internal fun openModuleUpdatePage(context: Context, info: ModuleUpdateInfo) {
    val url = info.targetUrl
    if (url.isBlank()) return
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.onFailure {
        Toast.makeText(context, url, Toast.LENGTH_LONG).show()
    }
}

@Composable
internal fun ModuleActivationTag(
    status: ModuleActivationTracker.ActivationStatus,
    modifier: Modifier = Modifier
) {
    val backgroundColor = if (status.isActive) {
        ComposeColor(0xFF4F9A71)
    } else {
        ComposeColor(0xFFC86F67)
    }
    val label = if (status.isActive) {
        stringResource(R.string.settings_module_active_tag)
    } else {
        stringResource(R.string.settings_module_inactive_tag)
    }
    Box(
        modifier = modifier
            .clip(ContinuousRoundedRectangle(999.dp))
            .background(backgroundColor)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = ComposeColor.White,
            style = MiuixTheme.textStyles.body2
        )
    }
}
