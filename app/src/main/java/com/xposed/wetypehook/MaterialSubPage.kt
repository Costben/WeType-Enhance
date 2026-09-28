package com.xposed.wetypehook

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
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
    edgeHighlightEnabled: Boolean,
    onOpenEdgeLightPage: () -> Unit,
    hyperMaterialEnabled: Boolean,
    onHyperMaterialEnabledChange: (Boolean) -> Unit,
    hyperMaterialAvailable: Boolean,
    glassSupported: Boolean,
    glassInput: List<String>,
    onGlassInputChange: (Int, String) -> Unit,
    onGlassReset: () -> Unit
) {
    item {
        SmallTitle(text = "光感")
        Card(
            modifier = Modifier.padding(horizontal = 16.dp),
            insideMargin = PaddingValues(0.dp)
        ) {
            // 模块自绘的光感参数已经多到一屏放不下（三类元素 × 边缘/内发光 × 开关/强度/宽度），
            // 所以收进三级页，这里只留入口。
            ArrowPreference(
                title = "光感预设与参数",
                summary = if (edgeHighlightEnabled) {
                    "分别为背景、图标、按键选择预设并深入调整参数"
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
            }
        }
    }
}
