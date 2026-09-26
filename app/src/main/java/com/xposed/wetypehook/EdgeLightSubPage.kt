package com.xposed.wetypehook

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xposed.wetypehook.wetype.settings.EdgeLightGroup
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.SwitchPreference

/**
 * 「光感设置」三级页。从「高级材质」推进来，是模块自绘光感的唯一入口。
 *
 * 光感由两层组成，每一层在一个元素上都各有开关、强度、宽度：
 *
 * - 边缘：贴着轮廓的一圈锐利高光。
 * - 内发光：内圈的柔和提亮，加上紧跟其后的压暗；两者同进同退，共用一根强度。
 *
 * 光照方向是三类共用的总控，所以只出现在总控里，每个元素下不再各给一根角度滑杆。
 * 元素各自的总开关关掉时，它下面两层连行带滑杆一起收起，不给「调了没反应」的机会。
 *
 * [SettingExpandGroup] 只在 `ColumnScope` 里可用，所以元素分组写成 `ColumnScope` 的扩展。
 */
internal fun LazyListScope.EdgeLightSubPageContent(
    edgeHighlightEnabled: Boolean,
    onEdgeHighlightEnabledChange: (Boolean) -> Unit,
    edgeLightAngle: Int,
    onEdgeLightAngleChange: (Int) -> Unit,
    backgroundLight: EdgeLightGroup,
    onBackgroundLightChange: (EdgeLightGroup) -> Unit,
    iconLight: EdgeLightGroup,
    onIconLightChange: (EdgeLightGroup) -> Unit,
    keyLight: EdgeLightGroup,
    onKeyLightChange: (EdgeLightGroup) -> Unit
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
        CategoryCard {
            EdgeLightGroupSection(
                title = "背景",
                summary = "键盘背板四周的流光；这里的内发光只提亮，不加压暗。",
                group = backgroundLight,
                masterEnabled = edgeHighlightEnabled,
                onChange = onBackgroundLightChange
            )
        }
    }

    item {
        CategoryCard {
            EdgeLightGroupSection(
                title = "图标",
                summary = "工具栏圆形图标与 Logo。",
                group = iconLight,
                masterEnabled = edgeHighlightEnabled,
                onChange = onIconLightChange
            )
        }
    }

    item {
        CategoryCard {
            EdgeLightGroupSection(
                title = "按键",
                summary = "每一颗键帽。",
                group = keyLight,
                masterEnabled = edgeHighlightEnabled,
                onChange = onKeyLightChange
            )
        }
    }
}

/**
 * 一类元素独占一张卡片。
 *
 * 三类共用一张卡时，只要展开其中一类，那张卡就会连着下面两类一起被拉长，分割线一层套
 * 一层；分卡之后展开只会长高自己那一张，其余两类不受影响。
 */
@Composable
private fun CategoryCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.padding(horizontal = 16.dp),
        insideMargin = PaddingValues(0.dp)
    ) {
        content()
    }
}

/**
 * 一类元素的光感。[masterEnabled] 是页面顶部那个总控：它关掉时这里的开关置灰，
 * 因为绘制侧确实会整条跳过。
 */
@Composable
private fun ColumnScope.EdgeLightGroupSection(
    title: String,
    summary: String,
    group: EdgeLightGroup,
    masterEnabled: Boolean,
    onChange: (EdgeLightGroup) -> Unit
) {
    SwitchPreference(
        title = title,
        summary = summary,
        checked = group.enabled,
        enabled = masterEnabled,
        onCheckedChange = { onChange(group.copy(enabled = it)) }
    )
    SettingExpandGroup(visible = masterEnabled && group.enabled) {
        HorizontalDivider()
        SwitchPreference(
            title = "边缘",
            summary = "贴着轮廓的一圈锐利高光。",
            checked = group.edgeEnabled,
            onCheckedChange = { onChange(group.copy(edgeEnabled = it)) }
        )
        SettingExpandGroup(visible = group.edgeEnabled) {
            HorizontalDivider()
            LightIntensitySlider(
                value = group.edgeIntensity,
                title = "边缘强度",
                summary = "只调这一圈高光的明暗。",
                onValueChange = { onChange(group.copy(edgeIntensity = it)) }
            )
            LightWidthSlider(
                value = group.edgeWidth,
                title = "边缘宽度",
                onValueChange = { onChange(group.copy(edgeWidth = it)) }
            )
        }
        HorizontalDivider()
        SwitchPreference(
            title = "内发光",
            summary = "内圈的柔和提亮与追随其后的压暗，两者一起变。",
            checked = group.glowEnabled,
            onCheckedChange = { onChange(group.copy(glowEnabled = it)) }
        )
        SettingExpandGroup(visible = group.glowEnabled) {
            HorizontalDivider()
            LightIntensitySlider(
                value = group.glowIntensity,
                title = "内发光强度",
                summary = "同时决定提亮与压暗的深浅；调到 0 相当于整层撤掉。",
                min = MIN_GLOW_INTENSITY,
                max = MAX_GLOW_INTENSITY,
                onValueChange = { onChange(group.copy(glowIntensity = it)) }
            )
            LightWidthSlider(
                value = group.glowWidth,
                title = "内发光宽度",
                onValueChange = { onChange(group.copy(glowWidth = it)) }
            )
        }
    }
}
