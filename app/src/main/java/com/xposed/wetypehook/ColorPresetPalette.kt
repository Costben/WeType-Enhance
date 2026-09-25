package com.xposed.wetypehook

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.capsule.ContinuousRoundedRectangle
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.theme.MiuixTheme


internal data class PresetColorItem(
    val name: String,
    val color: Int
)

internal val lightColorPresets = listOf(
    PresetColorItem("默认灰", 0xD4D4D4),
    PresetColorItem("纯白", 0xFFFFFF),
    PresetColorItem("冰川蓝", 0xD0E4F5),
    PresetColorItem("薄荷绿", 0xD2EBD9),
    PresetColorItem("樱花粉", 0xFCE4EC),
    PresetColorItem("薰衣草", 0xEDE7F6),
    PresetColorItem("暖阳米", 0xFFF3E0),
    PresetColorItem("曜石灰", 0x3C3F41)
)

internal val darkColorPresets = listOf(
    PresetColorItem("默认黑", 0x000000),
    PresetColorItem("深空灰", 0x1E1E1E),
    PresetColorItem("极夜黑", 0x121212),
    PresetColorItem("夜空蓝", 0x1A2238),
    PresetColorItem("青墨绿", 0x1B262C),
    PresetColorItem("暗夜紫", 0x261C2C),
    PresetColorItem("葡萄酒", 0x2D1B22),
    PresetColorItem("炭石灰", 0x2B2D30)
)

@Composable
internal fun ColorPresetPalette(
    isDark: Boolean,
    currentColorRgb: Int,
    onSelectColor: (Int) -> Unit
) {
    val presets = remember(isDark) {
        if (isDark) darkColorPresets else lightColorPresets
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            text = "预设色卡",
            style = MiuixTheme.textStyles.main
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = "点击快速应用推荐底色",
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            style = MiuixTheme.textStyles.body2
        )
        Spacer(modifier = Modifier.height(10.dp))

        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(presets.take(4), presets.drop(4)).forEach { rowPresets ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    rowPresets.forEach { item ->
                        val isSelected = (currentColorRgb and 0xFFFFFF) == (item.color and 0xFFFFFF)
                        val itemComposeColor = ComposeColor(item.color or 0xFF000000.toInt())
                        val isLight = isLightColor(item.color or 0xFF000000.toInt())

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                                .border(
                                    width = if (isSelected) 2.dp else 1.dp,
                                    color = if (isSelected) MiuixTheme.colorScheme.primary
                                    else MiuixTheme.colorScheme.outline.copy(alpha = 0.25f),
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .clickable { onSelectColor(item.color) }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .clip(ContinuousRoundedRectangle(999.dp))
                                        .background(itemComposeColor)
                                        .border(
                                            width = 1.dp,
                                            color = if (isLight) ComposeColor.Black.copy(alpha = 0.15f)
                                            else ComposeColor.White.copy(alpha = 0.2f),
                                            shape = ContinuousRoundedRectangle(999.dp)
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isSelected) {
                                        Icon(
                                            imageVector = MiuixIcons.Ok,
                                            contentDescription = null,
                                            tint = if (isLight) ComposeColor.Black else ComposeColor.White,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = item.name,
                                    style = MiuixTheme.textStyles.body2,
                                    fontSize = 11.sp,
                                    color = if (isSelected) MiuixTheme.colorScheme.primary
                                    else MiuixTheme.colorScheme.onSurface,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 下拉项：主文案只放短标签，解释下沉为 [DropdownItem.summary] 的 14sp 小字。
 *
 * Miuix 把下拉项的文本列写死在 216dp（`DropdownDefaults.MaxItemTextWidth`，外部改不了），16sp
 * 下一行只放得下约 13 个汉字；长标签折行后末行可能只剩一个字，孤字由此而来。
 */
internal fun labeledDropdownItems(
    options: List<Pair<String, String>>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit
): List<DropdownItem> = options.mapIndexed { index, (text, summary) ->
    DropdownItem(
        text = text,
        summary = summary,
        selected = index == selectedIndex,
        onClick = { onSelect(index) }
    )
}
