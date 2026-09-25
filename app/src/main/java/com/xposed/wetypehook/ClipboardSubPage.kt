package com.xposed.wetypehook

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference


internal fun LazyListScope.ClipboardSubPageContent(
    showCrossDeviceClipboard: Boolean,
    onShowCrossDeviceClipboardChange: (Boolean) -> Unit,
    removeClipboardRetentionLimit: Boolean,
    onRemoveClipboardRetentionLimitChange: (Boolean) -> Unit,
    removeClipboardTextLimit: Boolean,
    onRemoveClipboardTextLimitChange: (Boolean) -> Unit,
    clipboardSearchEnabled: Boolean,
    onClipboardSearchEnabledChange: (Boolean) -> Unit,
    clipboardSearchClearOnBack: Boolean,
    onClipboardSearchClearOnBackChange: (Boolean) -> Unit,
    clipboardImageAdjustRatio: Boolean,
    onClipboardImageAdjustRatioChange: (Boolean) -> Unit,
    clipboardImageCrop: Boolean,
    onClipboardImageCropChange: (Boolean) -> Unit,
    clipboardImageUniformRowHeight: Boolean,
    onClipboardImageUniformRowHeightChange: (Boolean) -> Unit,
    clipboardImageMaxCount: Int,
    onClipboardImageMaxCountChange: (Int) -> Unit,
    clipboardImageMaxSizeMb: Int,
    onClipboardImageMaxSizeMbChange: (Int) -> Unit,
    onOpenClipboardBackup: () -> Unit
) {
    item {
        SmallTitle(text = "基础增强")
        Card(
            modifier = Modifier.padding(horizontal = 16.dp),
            insideMargin = PaddingValues(0.dp)
        ) {
            SwitchPreference(
                title = "跨设备条目可见化持久保存",
                summary = "自动将多端同步的剪贴板远程条目转换为本地可见条目保存",
                checked = showCrossDeviceClipboard,
                onCheckedChange = onShowCrossDeviceClipboardChange
            )
            SwitchPreference(
                title = "解除保留上限与时长限制",
                summary = "剪贴板保存条数上限提升至 100,000 条，留存时长永久",
                checked = removeClipboardRetentionLimit,
                onCheckedChange = onRemoveClipboardRetentionLimitChange
            )
            SwitchPreference(
                title = "解除单条文本长度限制",
                summary = "剪贴板文本长度上限提升至 1 亿字符，抑制超限提示",
                checked = removeClipboardTextLimit,
                onCheckedChange = onRemoveClipboardTextLimitChange
            )
        }
    }

    item {
        SmallTitle(text = "搜索")
        Card(
            modifier = Modifier.padding(horizontal = 16.dp),
            insideMargin = PaddingValues(0.dp)
        ) {
            SwitchPreference(
                title = "剪贴板搜索",
                summary = "在剪贴板页面显示搜索框，按文本拼音分词过滤",
                checked = clipboardSearchEnabled,
                onCheckedChange = onClipboardSearchEnabledChange
            )
            SwitchPreference(
                title = "返回时清理搜索关键词",
                summary = "在剪贴板页面点击返回键时清理搜索关键词，恢复完整列表；关闭则保留上次搜索结果",
                checked = clipboardSearchClearOnBack,
                onCheckedChange = onClipboardSearchClearOnBackChange
            )
        }
    }

    item {
        SmallTitle(text = "图片缩略图")
        Card(
            modifier = Modifier.padding(horizontal = 16.dp),
            insideMargin = PaddingValues(0.dp)
        ) {
            SwitchPreference(
                title = "图片缩略图保持原比例",
                summary = "宽度按原图比例缩放，最长不超过行宽；关闭后缩略图统一为正方形",
                checked = clipboardImageAdjustRatio,
                onCheckedChange = onClipboardImageAdjustRatioChange
            )
            SwitchPreference(
                title = "图片缩略图裁剪填满",
                summary = "在缩略图框内居中裁剪填满，可能裁掉图片边缘；关闭则完整显示",
                checked = clipboardImageCrop,
                onCheckedChange = onClipboardImageCropChange
            )
            SwitchPreference(
                title = "图片缩略图统一行高",
                summary = "所有图片条目统一为两行高度；关闭后图片按单行高度显示",
                checked = clipboardImageUniformRowHeight,
                onCheckedChange = onClipboardImageUniformRowHeightChange
            )
            val imageCountOptions = listOf(0, 20, 50, 100, 200, 500)
            val imageSizeOptions = listOf(0, 128, 256, 512, 1024, 2048)
            OverlayDropdownPreference(
                title = "图片数量上限",
                items = imageCountOptions.map { if (it == 0) "无上限" else "$it 张" },
                selectedIndex = imageCountOptions.indexOf(clipboardImageMaxCount).coerceAtLeast(0),
                onSelectedIndexChange = { index ->
                    onClipboardImageMaxCountChange(imageCountOptions[index])
                }
            )
            OverlayDropdownPreference(
                title = "图片容量上限",
                items = imageSizeOptions.map {
                    when {
                        it == 0 -> "无上限"
                        it >= 1024 -> "${it / 1024} GB"
                        else -> "$it MB"
                    }
                },
                selectedIndex = imageSizeOptions.indexOf(clipboardImageMaxSizeMb).coerceAtLeast(0),
                onSelectedIndexChange = { index ->
                    onClipboardImageMaxSizeMbChange(imageSizeOptions[index])
                }
            )
        }
    }

    item {
        SmallTitle(text = "备份与恢复")
        Card(
            modifier = Modifier.padding(horizontal = 16.dp),
            insideMargin = PaddingValues(0.dp)
        ) {
            ArrowPreference(
                title = "剪贴板备份与恢复",
                summary = "导出 zip / WebDAV 备份 / 导入还原",
                onClick = onOpenClipboardBackup
            )
        }
    }
}
