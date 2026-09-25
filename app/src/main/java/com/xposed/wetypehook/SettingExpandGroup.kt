package com.xposed.wetypehook

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.IntSize
import top.yukonga.miuix.kmp.anim.SinOutEasing
import top.yukonga.miuix.kmp.anim.folmeSpring


/**
 * 设置页「开关展开子项」的统一动效。
 *
 * 节奏取自 Miuix 自身的显隐基准：显示用 `folmeSpring(1.0f, 0.3f)`、隐藏用
 * `folmeSpring(1.0f, 0.15f)`，`ListPopup` 的 tween 版本是 300ms 进 / 150ms 出配
 * `SinOutEasing`。锚点固定顶边，子项在开关下方向下展开、向上收起，不顶动上方内容。
 */
internal val SettingExpandEnter: EnterTransition =
    fadeIn(tween(durationMillis = 300, easing = SinOutEasing)) +
        expandVertically(
            animationSpec = folmeSpring(
                damping = 1.0f,
                response = 0.3f,
                visibilityThreshold = IntSize.VisibilityThreshold
            ),
            expandFrom = Alignment.Top
        )

internal val SettingExpandExit: ExitTransition =
    fadeOut(tween(durationMillis = 150, easing = SinOutEasing)) +
        shrinkVertically(
            animationSpec = folmeSpring(
                damping = 1.0f,
                response = 0.15f,
                visibilityThreshold = IntSize.VisibilityThreshold
            ),
            shrinkTowards = Alignment.Top
        )

/** 卡片内受开关控制的子项分组；展开与收起统一走 [SettingExpandEnter] / [SettingExpandExit]。 */
@Composable
internal fun SettingExpandGroup(
    visible: Boolean,
    content: @Composable ColumnScope.() -> Unit
) {
    AnimatedVisibility(visible = visible, enter = SettingExpandEnter, exit = SettingExpandExit) {
        Column(content = content)
    }
}
