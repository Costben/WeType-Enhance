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
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference


internal fun LazyListScope.MaterialSubPageContent(
    systemMaterialEnabled: Boolean,
    onSystemMaterialEnabledChange: (Boolean) -> Unit,
    colorOsMaterialAvailable: Boolean,
    edgeHighlightEnabled: Boolean,
    onOpenEdgeLightPage: () -> Unit,
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
    nativeEdgeLightIntensity: Int,
    onNativeEdgeLightIntensityChange: (Int) -> Unit
) {
    item {
        SmallTitle(text = "光感")
        Card(
            modifier = Modifier.padding(horizontal = 16.dp),
            insideMargin = PaddingValues(0.dp)
        ) {
            // 模块自绘的光感参数已经多到一屏放不下（三类元素 × 边缘/内发光 × 开关/强度/宽度），
            // 所以收进三级页，这里只留入口。ColorOS 原生材质是另一条轨道，仍在下面那一栏。
            ArrowPreference(
                title = SettingsSubPage.EDGE_LIGHT.title,
                summary = if (edgeHighlightEnabled) {
                    "背景、图标、按键的边缘与内发光，共用一套光照方向"
                } else {
                    "总开关已关闭，进去打开后才能逐类调整"
                },
                onClick = onOpenEdgeLightPage
            )
        }
    }

    item {
        SmallTitle(text = "系统材质")
        Card(
            modifier = Modifier.padding(horizontal = 16.dp),
            insideMargin = PaddingValues(0.dp)
        ) {
            // 两套渲染模式互斥：打开系统材质后，模块自绘光感会关闭。
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
                        title = "光照角度",
                        note = "当前 ColorOS 版本未使用该参数：实测 45° 与 225° 在键盘背板上逐像素相同。",
                        onValueChange = onColorOsLightAngleChange
                    )
                    LightWidthSlider(
                        value = nativeEdgeLightWidth,
                        onValueChange = onNativeEdgeLightWidthChange
                    )
                    NativeLightIntensitySlider(
                        value = nativeEdgeLightIntensity,
                        onValueChange = onNativeEdgeLightIntensityChange
                    )
                }
            }
        }
    }
}
