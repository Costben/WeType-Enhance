package com.xposed.wetypehook

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xposed.wetypehook.wetype.graphics.WeTypeEdgeLightPreset
import com.xposed.wetypehook.wetype.settings.AdvancedLightPreset
import com.xposed.wetypehook.wetype.settings.MaterialPresetCatalog
import com.xposed.wetypehook.wetype.settings.MaterialPresetDefinition
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 「高级参数调节」四级页。从「光感设置」的「高级参数」区推进来。
 *
 * 两段：
 *
 * 1. **内置预设**：36 项目录项只读展示。参数真源是 `docs/coloros-material/preset-catalog.js`，
 *    这一页只把它们列出来（稳定 id、名称、来源组、所属阴影栈与自带角度），不给任何编辑控件——
 *    内置项不可变。
 * 2. **自定义预设**：从某个内置项复制一份可编辑副本，副本覆盖绘制侧真正消费的那几个参数：
 *    边缘与内发光各自的开关、强度、宽度，以及三类共用的角度。全部落在 [LightEffectControls]
 *    已经定义好的合法区间里，越界值由状态层夹回后才落盘。
 *
 * 副本创建后出现在「光感设置」分类三行的下拉末尾，三个对象都可以选它。
 */
internal fun LazyListScope.AdvancedParametersSubPageContent(
    builtInPresets: List<MaterialPresetDefinition>,
    customPresets: List<AdvancedLightPreset>,
    draftBaseId: String,
    onDraftBaseIdChange: (String) -> Unit,
    onAddPreset: () -> Unit,
    onUpdatePreset: (AdvancedLightPreset) -> Unit,
    onRemovePreset: (String) -> Unit
) {
    item {
        SmallTitle(text = "高级参数调节")
        Card(
            modifier = Modifier.padding(horizontal = 16.dp),
            insideMargin = PaddingValues(0.dp)
        ) {
            ArrowPreference(
                title = "增加预设",
                summary = "从「${MaterialPresetCatalog.label(draftBaseId)}」复制一份可编辑副本",
                onClick = onAddPreset
            )
        }
    }

    item {
        SmallTitle(text = "内置预设")
        Card(
            modifier = Modifier.padding(horizontal = 16.dp),
            insideMargin = PaddingValues(0.dp)
        ) {
            Column {
                Text(
                    text = "36 项内置预设来自目录文件，参数只读。要改参数请从下面复制一份自定义预设。",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                )
                builtInPresets.forEach { definition ->
                    HorizontalDivider()
                    ArrowPreference(
                        title = definition.label,
                        summary = builtInSummary(definition),
                        onClick = { onDraftBaseIdChange(definition.id) }
                    )
                }
            }
        }
    }

    item {
        SmallTitle(text = "自定义预设")
        Card(
            modifier = Modifier.padding(horizontal = 16.dp),
            insideMargin = PaddingValues(0.dp)
        ) {
            val options = builtInPresets.map { it.id }
            val selectedIndex = options.indexOf(draftBaseId).coerceAtLeast(0)
            OverlayDropdownPreference(
                title = "复制自",
                summary = "选择「增加预设」时从哪一个内置预设复制",
                entry = DropdownEntry(
                    items = builtInPresets.mapIndexed { index, definition ->
                        top.yukonga.miuix.kmp.basic.DropdownItem(
                            text = definition.label,
                            summary = definition.id,
                            selected = index == selectedIndex,
                            onClick = { onDraftBaseIdChange(definition.id) }
                        )
                    }
                )
            )
            HorizontalDivider()
            if (customPresets.isEmpty()) {
                HorizontalDivider()
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "还没有自定义预设。",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
            }
        }
    }

    customPresets.forEach { preset ->
        item(key = "advanced_preset_${preset.id}") {
            Card(
                modifier = Modifier.padding(horizontal = 16.dp),
                insideMargin = PaddingValues(0.dp)
            ) {
                Column {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Text(text = preset.label, style = MiuixTheme.textStyles.main)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = AdvancedLightPreset.description(preset),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    }
                    AdvancedPresetEditors(preset = preset, onUpdate = onUpdatePreset)
                    HorizontalDivider()
                    ArrowPreference(
                        title = "删除此预设",
                        summary = "正在使用它的对象会回到「无预设」",
                        onClick = { onRemovePreset(preset.id) }
                    )
                }
            }
        }
    }
}

/** 一份自定义预设的全部可编辑项。区间与「光感设置」原先那几根滑杆完全一致。 */
@Composable
private fun ColumnScope.AdvancedPresetEditors(
    preset: AdvancedLightPreset,
    onUpdate: (AdvancedLightPreset) -> Unit
) {
    HorizontalDivider()
    LightAngleSlider(
        value = preset.angle,
        title = "光照方向",
        onValueChange = { onUpdate(preset.copy(angle = it)) }
    )
    HorizontalDivider()
    SwitchPreference(
        title = "边缘",
        summary = "贴着轮廓的一圈锐利高光。",
        checked = preset.edgeEnabled,
        onCheckedChange = { onUpdate(preset.copy(edgeEnabled = it)) }
    )
    SettingExpandGroup(visible = preset.edgeEnabled) {
        HorizontalDivider()
        LightIntensitySlider(
            value = preset.edgeIntensity,
            title = "边缘强度",
            summary = "只调这一圈高光的明暗。",
            onValueChange = { onUpdate(preset.copy(edgeIntensity = it)) }
        )
        LightWidthSlider(
            value = preset.edgeWidth,
            title = "边缘宽度",
            onValueChange = { onUpdate(preset.copy(edgeWidth = it)) }
        )
    }
    HorizontalDivider()
    SwitchPreference(
        title = "内发光",
        summary = "内圈的柔和提亮与追随其后的压暗，两者一起变。",
        checked = preset.glowEnabled,
        onCheckedChange = { onUpdate(preset.copy(glowEnabled = it)) }
    )
    SettingExpandGroup(visible = preset.glowEnabled) {
        HorizontalDivider()
        LightIntensitySlider(
            value = preset.glowIntensity,
            title = "内发光强度",
            summary = "同时决定提亮与压暗的深浅；调到 0 相当于整层撤掉。",
            min = MIN_GLOW_INTENSITY,
            max = MAX_GLOW_INTENSITY,
            onValueChange = { onUpdate(preset.copy(glowIntensity = it)) }
        )
        LightWidthSlider(
            value = preset.glowWidth,
            title = "内发光宽度",
            onValueChange = { onUpdate(preset.copy(glowWidth = it)) }
        )
    }
}

/** 内置项那行摘要：稳定 id、来源组、所属阴影栈与自带角度。全部来自目录，不可改。 */
private fun builtInSummary(definition: MaterialPresetDefinition): String {
    val preset = WeTypeEdgeLightPreset.forMaterialPreset(definition.id, compact = true)
    val family = if (MaterialPresetCatalog.shadowFamily(definition.id) == MaterialPresetCatalog.FAMILY_CLASSIC) {
        "经典栈"
    } else {
        "ColorOS 栈"
    }
    val angle = preset.authoredAngleDegrees.roundToInt()
    return "${definition.id} · ${definition.sourceGroup} · $family · 自带角度 ${angle}°"
}
