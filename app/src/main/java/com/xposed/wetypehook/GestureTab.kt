package com.xposed.wetypehook

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xposed.wetypehook.wetype.settings.WeTypeSettings
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference


internal fun LazyListScope.GestureTabContent(
    qwertyGestureEnabled: Boolean,
    onQwertyGestureEnabledChange: (Boolean) -> Unit,
    t9GestureEnabled: Boolean,
    onT9GestureEnabledChange: (Boolean) -> Unit,
    gestureVibration: Boolean,
    onGestureVibrationChange: (Boolean) -> Unit,
    t9GestureVibration: Boolean,
    onT9GestureVibrationChange: (Boolean) -> Unit,
    gestureThreshold: Int,
    onGestureThresholdChange: (Int) -> Unit,
    t9GestureThreshold: Int,
    onT9GestureThresholdChange: (Int) -> Unit,
    showGestureKeyLabels: Boolean,
    onShowGestureKeyLabelsChange: (Boolean) -> Unit,
    gestureLabelTextSizeSp: Int,
    onGestureLabelTextSizeSpChange: (Int) -> Unit,
    gestureLabelAlpha: Int,
    onGestureLabelAlphaChange: (Int) -> Unit,
    gestureLabelPosition: Int,
    onGestureLabelPositionChange: (Int) -> Unit,
    gestureLabelMarginTopDp: Int,
    onGestureLabelMarginTopDpChange: (Int) -> Unit,
    gestureLabelMarginBottomDp: Int,
    onGestureLabelMarginBottomDpChange: (Int) -> Unit,
    gestureLabelMarginLeftDp: Int,
    onGestureLabelMarginLeftDpChange: (Int) -> Unit,
    gestureLabelMarginRightDp: Int,
    onGestureLabelMarginRightDpChange: (Int) -> Unit,
    gestureBindingsJson: String,
    onGestureBindingsJsonChange: (String) -> Unit
) {
    // 1. 手势总控卡片
    item {
        SmallTitle(text = "手势总控")
        Card(
            modifier = Modifier.padding(horizontal = 16.dp),
            insideMargin = PaddingValues(0.dp)
        ) {
            Column {
                SwitchPreference(
                    title = "启用 26 键 QWERTY 下滑手势",
                    summary = "全键盘按键向下滑动触发绑定动作 (默认 Z/X/C/V)",
                    checked = qwertyGestureEnabled,
                    onCheckedChange = onQwertyGestureEnabledChange
                )
                SwitchPreference(
                    title = "启用九宫格 T9 下滑手势",
                    summary = "支持 1~9 号键位向下滑动触发绑定动作",
                    checked = t9GestureEnabled,
                    onCheckedChange = onT9GestureEnabledChange
                )
                SwitchPreference(
                    title = "QWERTY 手势触觉反馈",
                    summary = "26 键手势触发时调用键盘触觉振动",
                    checked = gestureVibration,
                    onCheckedChange = onGestureVibrationChange
                )
                SwitchPreference(
                    title = "T9 九宫格手势触觉反馈",
                    summary = "九宫格手势触发时调用键盘触觉振动",
                    checked = t9GestureVibration,
                    onCheckedChange = onT9GestureVibrationChange
                )
            }
        }
    }

    // 2. 灵敏度阈值卡片
    item {
        SmallTitle(text = "灵敏度阈值")
        Card(
            modifier = Modifier.padding(horizontal = 16.dp),
            insideMargin = PaddingValues(0.dp)
        ) {
            Column {
                SliderPreferenceItem(
                    title = "QWERTY 触发滑动阈值: ${gestureThreshold} dp",
                    value = gestureThreshold,
                    max = 48,
                    onValueChange = { onGestureThresholdChange(it.coerceIn(10, 48)) }
                )
                SliderPreferenceItem(
                    title = "T9 触发滑动阈值: ${t9GestureThreshold} dp",
                    value = t9GestureThreshold,
                    max = 48,
                    onValueChange = { onT9GestureThresholdChange(it.coerceIn(10, 48)) }
                )
            }
        }
    }

    // 3. 标签样式卡片
    item {
        SmallTitle(text = "标签样式")
        Card(
            modifier = Modifier.padding(horizontal = 16.dp),
            insideMargin = PaddingValues(0.dp)
        ) {
            Column {
                SwitchPreference(
                    title = "显示按键手势标签",
                    summary = "在已绑定手势的按键上显示动作名称角标",
                    checked = showGestureKeyLabels,
                    onCheckedChange = onShowGestureKeyLabelsChange
                )
                SliderPreferenceItem(
                    title = "标签文字大小: ${gestureLabelTextSizeSp} sp",
                    value = gestureLabelTextSizeSp,
                    max = 16,
                    onValueChange = { onGestureLabelTextSizeSpChange(it.coerceIn(6, 16)) }
                )
                SliderPreferenceItem(
                    title = "标签不透明度: ${gestureLabelAlpha}",
                    value = gestureLabelAlpha,
                    max = 255,
                    onValueChange = { onGestureLabelAlphaChange(it.coerceIn(0, 255)) }
                )
                OverlayDropdownPreference(
                    title = "标签位置",
                    items = listOf("底部", "顶部"),
                    selectedIndex = when (gestureLabelPosition) {
                        WeTypeSettings.GESTURE_LABEL_POSITION_TOP -> 1
                        else -> 0
                    },
                    onSelectedIndexChange = { index ->
                        onGestureLabelPositionChange(
                            if (index == 1) {
                                WeTypeSettings.GESTURE_LABEL_POSITION_TOP
                            } else {
                                WeTypeSettings.GESTURE_LABEL_POSITION_BOTTOM
                            }
                        )
                    }
                )
                SliderPreferenceItem(
                    title = "标签上边距: ${gestureLabelMarginTopDp} dp",
                    value = gestureLabelMarginTopDp,
                    max = WeTypeSettings.GESTURE_LABEL_MARGIN_MAX_DP,
                    onValueChange = {
                        onGestureLabelMarginTopDpChange(
                            it.coerceIn(
                                WeTypeSettings.GESTURE_LABEL_MARGIN_MIN_DP,
                                WeTypeSettings.GESTURE_LABEL_MARGIN_MAX_DP
                            )
                        )
                    }
                )
                SliderPreferenceItem(
                    title = "标签下边距: ${gestureLabelMarginBottomDp} dp",
                    value = gestureLabelMarginBottomDp,
                    max = WeTypeSettings.GESTURE_LABEL_MARGIN_MAX_DP,
                    onValueChange = {
                        onGestureLabelMarginBottomDpChange(
                            it.coerceIn(
                                WeTypeSettings.GESTURE_LABEL_MARGIN_MIN_DP,
                                WeTypeSettings.GESTURE_LABEL_MARGIN_MAX_DP
                            )
                        )
                    }
                )
                SliderPreferenceItem(
                    title = "标签左边距: ${gestureLabelMarginLeftDp} dp",
                    value = gestureLabelMarginLeftDp,
                    max = WeTypeSettings.GESTURE_LABEL_MARGIN_MAX_DP,
                    onValueChange = {
                        onGestureLabelMarginLeftDpChange(
                            it.coerceIn(
                                WeTypeSettings.GESTURE_LABEL_MARGIN_MIN_DP,
                                WeTypeSettings.GESTURE_LABEL_MARGIN_MAX_DP
                            )
                        )
                    }
                )
                SliderPreferenceItem(
                    title = "标签右边距: ${gestureLabelMarginRightDp} dp",
                    value = gestureLabelMarginRightDp,
                    max = WeTypeSettings.GESTURE_LABEL_MARGIN_MAX_DP,
                    onValueChange = {
                        onGestureLabelMarginRightDpChange(
                            it.coerceIn(
                                WeTypeSettings.GESTURE_LABEL_MARGIN_MIN_DP,
                                WeTypeSettings.GESTURE_LABEL_MARGIN_MAX_DP
                            )
                        )
                    }
                )
            }
        }
    }

    // 4. 可视化键位动作映射编辑器卡片
    item {
        SmallTitle(text = "按键手势映射")
        Card(
            modifier = Modifier.padding(horizontal = 16.dp),
            insideMargin = PaddingValues(0.dp)
        ) {
            GestureKeyBindingEditor(
                bindingsJson = gestureBindingsJson,
                onBindingsChange = onGestureBindingsJsonChange
            )
        }
    }
}
