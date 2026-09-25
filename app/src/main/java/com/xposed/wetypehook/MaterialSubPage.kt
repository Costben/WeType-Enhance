package com.xposed.wetypehook

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.SwitchPreference


internal fun LazyListScope.MaterialSubPageContent(
    systemMaterialEnabled: Boolean,
    onSystemMaterialEnabledChange: (Boolean) -> Unit,
    colorOsMaterialAvailable: Boolean,
    edgeHighlightEnabled: Boolean,
    onEdgeHighlightEnabledChange: (Boolean) -> Unit,
    edgeLightAngle: Int,
    onEdgeLightAngleChange: (Int) -> Unit,
    edgeLightWidth: Int,
    onEdgeLightWidthChange: (Int) -> Unit,
    hyperMaterialEnabled: Boolean,
    onHyperMaterialEnabledChange: (Boolean) -> Unit,
    hyperMaterialAvailable: Boolean,
    glassSupported: Boolean,
    glassInput: List<String>,
    onGlassInputChange: (Int, String) -> Unit,
    onGlassReset: () -> Unit,
    nativeEdgeLightEnabled: Boolean,
    onNativeEdgeLightEnabledChange: (Boolean) -> Unit,
    colorOsLightAngle: Int,
    onColorOsLightAngleChange: (Int) -> Unit,
    nativeEdgeLightWidth: Int,
    onNativeEdgeLightWidthChange: (Int) -> Unit,
    edgeHighlightIntensity: Int,
    onEdgeHighlightIntensityChange: (Int) -> Unit,
    glowIntensity: Int,
    onGlowIntensityChange: (Int) -> Unit,
    iconEdgeLightEnabled: Boolean,
    onIconEdgeLightEnabledChange: (Boolean) -> Unit,
    keyEdgeLightEnabled: Boolean,
    onKeyEdgeLightEnabledChange: (Boolean) -> Unit
) {
    item {
        SmallTitle(text = "光感")
        Card(
            modifier = Modifier.padding(horizontal = 16.dp),
            insideMargin = PaddingValues(0.dp)
        ) {
            SwitchPreference(
                title = "光感设置",
                summary = "光感总开关，关闭后背板与按键、图标的边缘光感一并关闭",
                checked = edgeHighlightEnabled,
                onCheckedChange = onEdgeHighlightEnabledChange
            )
            SettingExpandGroup(visible = edgeHighlightEnabled) {
                HorizontalDivider()
                // 自绘边缘光不依赖系统材质，任何平台都能用；与 ColorOS 原生边缘光是两条独立轨道。
                LightAngleSlider(
                    value = edgeLightAngle,
                    onValueChange = onEdgeLightAngleChange
                )
                LightWidthSlider(
                    value = edgeLightWidth,
                    onValueChange = onEdgeLightWidthChange
                )
                // 强度跟着自绘那一套参数走，所以和角度、宽度同组；ColorOS 分组里那份读的是同一个值。
                LightIntensitySlider(
                    value = edgeHighlightIntensity,
                    onValueChange = onEdgeHighlightIntensityChange
                )
                // 发光强度只作用于模块自绘的内发光，ColorOS 的系统边缘光没有对应参数，所以不进它的分组。
                LightGlowSlider(
                    value = glowIntensity,
                    onValueChange = onGlowIntensityChange
                )
                // 两个目标开关也收进总控：总控关掉时它们本来就没有效果，行本身一并收起。
                HorizontalDivider()
                SwitchPreference(
                    title = stringResource(R.string.settings_key_edge_light_title),
                    summary = stringResource(R.string.settings_key_edge_light_desc),
                    checked = keyEdgeLightEnabled,
                    onCheckedChange = onKeyEdgeLightEnabledChange
                )
                HorizontalDivider()
                SwitchPreference(
                    title = stringResource(R.string.settings_icon_edge_light_title),
                    summary = stringResource(R.string.settings_icon_edge_light_desc),
                    checked = iconEdgeLightEnabled,
                    onCheckedChange = onIconEdgeLightEnabledChange
                )
            }
        }
    }

    item {
        SmallTitle(text = "系统材质")
        Card(
            modifier = Modifier.padding(horizontal = 16.dp),
            insideMargin = PaddingValues(0.dp)
        ) {
            // 总控：关掉时下面两条系统材质轨道都不生效，背板与边缘光全部退回模块自绘。
            SwitchPreference(
                title = stringResource(R.string.settings_hyper_material_title),
                summary = stringResource(R.string.settings_hyper_material_desc),
                checked = systemMaterialEnabled,
                onCheckedChange = onSystemMaterialEnabledChange
            )
            SettingExpandGroup(visible = systemMaterialEnabled) {
                // 后端不支持时存储值照旧保留，只把显示掩成「关」且不展开子项，
                // 避免出现置灰却仍显示为已开、子项还摊开的行。
                val miuiMaterialOn = hyperMaterialEnabled && hyperMaterialAvailable
                HorizontalDivider()
                SwitchPreference(
                    title = stringResource(R.string.settings_miui_material_title),
                    summary = stringResource(
                        if (hyperMaterialAvailable) R.string.settings_miui_material_desc
                        else R.string.settings_hyper_material_unavailable
                    ),
                    checked = miuiMaterialOn,
                    enabled = hyperMaterialAvailable,
                    onCheckedChange = onHyperMaterialEnabledChange
                )
                SettingExpandGroup(visible = miuiMaterialOn) {
                    HorizontalDivider()
                    GlassOverrideEditor(
                        values = glassInput,
                        enabled = hyperMaterialAvailable && glassSupported,
                        onValueChange = onGlassInputChange,
                        onReset = onGlassReset
                    )
                }
                HorizontalDivider()
                val colorOsMaterialOn = nativeEdgeLightEnabled && colorOsMaterialAvailable
                SwitchPreference(
                    title = stringResource(R.string.settings_coloros_material_title),
                    summary = stringResource(
                        if (colorOsMaterialAvailable) R.string.settings_coloros_material_desc
                        else R.string.settings_hyper_material_unavailable
                    ),
                    checked = colorOsMaterialOn,
                    enabled = colorOsMaterialAvailable,
                    onCheckedChange = onNativeEdgeLightEnabledChange
                )
                SettingExpandGroup(visible = colorOsMaterialOn) {
                    HorizontalDivider()
                    LightAngleSlider(
                        value = colorOsLightAngle,
                        note = "当前 ColorOS 版本未使用该参数：实测 45° 与 225° 在键盘背板上逐像素相同。",
                        onValueChange = onColorOsLightAngleChange
                    )
                    LightWidthSlider(
                        value = nativeEdgeLightWidth,
                        onValueChange = onNativeEdgeLightWidthChange
                    )
                    LightIntensitySlider(
                        value = edgeHighlightIntensity,
                        onValueChange = onEdgeHighlightIntensityChange
                    )
                }
            }
        }
    }
}
