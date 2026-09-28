package com.xposed.wetypehook

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xposed.wetypehook.wetype.settings.AdvancedLightPreset
import com.xposed.wetypehook.wetype.settings.EdgeLightGroup
import com.xposed.wetypehook.wetype.settings.MaterialPresetCatalog
import com.xposed.wetypehook.wetype.settings.MaterialPresetTarget
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

/**
 * 「光感设置」三级页。从「高级材质」推进来，是模块自绘光感的唯一入口。
 *
 * 页面自上而下四段，顺序固定：
 *
 * 1. **总控**：光感总开关与光照方向。方向是三类自绘元素共用的，所以只出现在这里。
 * 2. **分类**：背景 / 按钮 / 图标三行下拉。每行直接选预设，第一项是「无预设」（这一类不修改）；
 *    边缘与内发光的开关、滑杆不在这里，只从「高级参数」进。
 * 3. **高级参数**：允许全部 36 个预设、进入「高级参数调节」的入口、预设参数文档。
 * 4. **恢复默认**：三类回到「无预设」，总控与高级参数草稿一起回滚。
 *
 * [SettingExpandGroup] 只在 `ColumnScope` 里可用，所以展开分组写成 `ColumnScope` 的扩展。
 */
internal fun LazyListScope.EdgeLightSubPageContent(
    edgeHighlightEnabled: Boolean,
    onEdgeHighlightEnabledChange: (Boolean) -> Unit,
    edgeLightAngle: Int,
    onEdgeLightAngleChange: (Int) -> Unit,
    backgroundLight: EdgeLightGroup,
    iconLight: EdgeLightGroup,
    keyLight: EdgeLightGroup,
    customPresets: List<AdvancedLightPreset>,
    onMaterialPresetSelected: (MaterialPresetTarget, String) -> Unit,
    allMaterialPresetsEnabled: Boolean,
    onAllMaterialPresetsEnabledChange: (Boolean) -> Unit,
    onOpenAdvancedParameters: () -> Unit,
    onOpenPresetDocumentation: () -> Unit,
    onRestoreDefaults: () -> Unit
) {
    item {
        SmallTitle(text = "总控")
        Card(
            modifier = Modifier.padding(horizontal = 16.dp),
            insideMargin = PaddingValues(0.dp)
        ) {
            SwitchPreference(
                title = "光感",
                summary = "总开关。关闭后背板、图标、按键的光感全部停止绘制。",
                checked = edgeHighlightEnabled,
                onCheckedChange = onEdgeHighlightEnabledChange
            )
            SettingExpandGroup(visible = edgeHighlightEnabled) {
                HorizontalDivider()
                LightAngleSlider(
                    value = edgeLightAngle,
                    onValueChange = onEdgeLightAngleChange
                )
            }
        }
    }

    item {
        SmallTitle(text = "分类")
        Card(
            modifier = Modifier.padding(horizontal = 16.dp),
            insideMargin = PaddingValues(0.dp)
        ) {
            LightTargetPresetRow(
                title = "背景",
                summary = "键盘背板四周的流光；这里的内发光只提亮，不加压暗。",
                group = backgroundLight,
                presetTarget = MaterialPresetTarget.BACKGROUND,
                allowAllPresets = allMaterialPresetsEnabled,
                customPresets = customPresets,
                onSelect = { onMaterialPresetSelected(MaterialPresetTarget.BACKGROUND, it) }
            )
            HorizontalDivider()
            LightTargetPresetRow(
                title = "按钮",
                summary = "每一颗键帽。",
                group = keyLight,
                presetTarget = MaterialPresetTarget.KEY,
                allowAllPresets = allMaterialPresetsEnabled,
                customPresets = customPresets,
                onSelect = { onMaterialPresetSelected(MaterialPresetTarget.KEY, it) }
            )
            HorizontalDivider()
            LightTargetPresetRow(
                title = "图标",
                summary = "工具栏圆形图标与 Logo。",
                group = iconLight,
                presetTarget = MaterialPresetTarget.ICON,
                allowAllPresets = allMaterialPresetsEnabled,
                customPresets = customPresets,
                onSelect = { onMaterialPresetSelected(MaterialPresetTarget.ICON, it) }
            )
        }
    }

    item {
        SmallTitle(text = "高级参数")
        Card(
            modifier = Modifier.padding(horizontal = 16.dp),
            insideMargin = PaddingValues(0.dp)
        ) {
            SwitchPreference(
                title = "允许全部 36 个预设",
                summary = if (allMaterialPresetsEnabled) {
                    "背景、图标、按钮都可以互相套用"
                } else {
                    "关闭时只显示对应对象和相近对象的预设"
                },
                checked = allMaterialPresetsEnabled,
                onCheckedChange = onAllMaterialPresetsEnabledChange
            )
            HorizontalDivider()
            ArrowPreference(
                title = "高级参数调节",
                summary = "查看 36 个内置预设，并创建可编辑的自定义预设",
                onClick = onOpenAdvancedParameters
            )
            HorizontalDivider()
            ArrowPreference(
                title = "预设参数文档",
                summary = "查看 36 项预设的来源、槽位和适用对象",
                onClick = onOpenPresetDocumentation
            )
        }
    }

    item {
        Card(
            modifier = Modifier.padding(horizontal = 16.dp),
            insideMargin = PaddingValues(0.dp)
        ) {
            ArrowPreference(
                title = "恢复默认",
                summary = "背景、按钮、图标回到「无预设」，总控与高级参数一起回到默认。",
                onClick = onRestoreDefaults
            )
        }
    }
}

/**
 * 分类里的一行：标题 + 一条预设下拉，直接选这一类套用哪套光感。
 *
 * 下拉的第一项固定是「无预设」（[MaterialPresetCatalog.NONE]），表示这一类不修改；其后是当前
 * 允许的内置目录项，最后追加用户在「高级参数调节」里创建的自定义预设。三类各自独立，所以同一份
 * 预设可以被三个对象分别选中，也可以互不相同。
 */
@Composable
private fun LightTargetPresetRow(
    title: String,
    summary: String,
    group: EdgeLightGroup,
    presetTarget: MaterialPresetTarget,
    allowAllPresets: Boolean,
    customPresets: List<AdvancedLightPreset>,
    onSelect: (String) -> Unit
) {
    val builtInOptions = MaterialPresetCatalog.selectableOptions(presetTarget, allowAllPresets)
    val items = buildList {
        builtInOptions.forEach { option ->
            add(
                DropdownItem(
                    text = option.label,
                    summary = when {
                        MaterialPresetCatalog.isNone(option.id) -> "这一类不修改，不套用任何预设"
                        option.sourceGroup == "coui" -> "COUI 参数"
                        else -> "${option.sourceGroup} 参数"
                    },
                    selected = option.id == group.presetId,
                    onClick = { onSelect(option.id) }
                )
            )
        }
        customPresets.forEach { preset ->
            add(
                DropdownItem(
                    text = preset.label,
                    summary = AdvancedLightPreset.description(preset),
                    selected = preset.id == group.presetId,
                    onClick = { onSelect(preset.id) }
                )
            )
        }
    }
    OverlayDropdownPreference(
        title = title,
        summary = summary,
        entry = DropdownEntry(items = items)
    )
}
