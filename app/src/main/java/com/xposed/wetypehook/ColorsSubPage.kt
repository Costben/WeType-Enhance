package com.xposed.wetypehook

import android.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kyant.capsule.ContinuousRoundedRectangle
import com.xposed.wetypehook.wetype.settings.WeTypeAppearanceColorGroup
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TabRowWithContour
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme


internal fun LazyListScope.ColorsSubPageContent(
    currentModeIsDark: Boolean,
    onModeChange: (Boolean) -> Unit,
    currentColor: Int,
    alphaValue: Int,
    onAlphaChange: (Int) -> Unit,
    colorInput: String,
    onColorInputChange: (String) -> Unit,
    onColorSelect: (Int) -> Unit,
    appearanceSectionGroups: List<WeTypeAppearanceColorGroup>,
    appearanceGroupColors: MutableList<Int>,
    groupIndex: (String) -> Int,
    currentKeyGroup: WeTypeAppearanceColorGroup,
    currentKeyGroupIndex: Int,
    onKeyColorChange: (Int) -> Unit
) {
    item {
        SmallTitle(text = stringResource(R.string.settings_section_mode))
        Card(
            modifier = Modifier.padding(horizontal = 16.dp),
            insideMargin = PaddingValues(16.dp)
        ) {
            val tabs = listOf(
                stringResource(R.string.settings_light_mode),
                stringResource(R.string.settings_dark_mode)
            )
            TabRowWithContour(
                tabs = tabs,
                selectedTabIndex = if (currentModeIsDark) 1 else 0,
                onTabSelected = { index ->
                    onModeChange(index == 1)
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    item {
        SmallTitle(text = stringResource(R.string.settings_group_color))
        Card(
            modifier = Modifier.padding(horizontal = 16.dp),
            insideMargin = PaddingValues(0.dp)
        ) {
            Column {
                // 预设色卡快速选择器
                ColorPresetPalette(
                    isDark = currentModeIsDark,
                    currentColorRgb = currentColor and 0xFFFFFF,
                    onSelectColor = onColorSelect
                )

                HorizontalDivider()

                // 自定义 HEX 颜色输入
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = stringResource(R.string.settings_custom_color),
                        style = MiuixTheme.textStyles.main
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.settings_color_helper),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        style = MiuixTheme.textStyles.body2
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(ContinuousRoundedRectangle(999.dp))
                                .background(ComposeColor(currentColor))
                                .border(
                                    1.dp,
                                    MiuixTheme.colorScheme.outline,
                                    ContinuousRoundedRectangle(999.dp)
                                )
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        TextField(
                            value = colorInput,
                            onValueChange = onColorInputChange,
                            label = stringResource(R.string.settings_color_label),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                HorizontalDivider()

                SliderPreferenceItem(
                    title = stringResource(R.string.settings_alpha_title),
                    value = alphaValue,
                    max = 255,
                    onValueChange = onAlphaChange
                )
            }
        }
    }

    item {
        SmallTitle(text = "按键颜色")
        Card(
            modifier = Modifier.padding(horizontal = 16.dp),
            insideMargin = PaddingValues(0.dp)
        ) {
            KeyColorEditor(
                title = if (currentModeIsDark) {
                    stringResource(R.string.settings_dark_key_color_title)
                } else {
                    stringResource(R.string.settings_light_key_color_title)
                },
                summary = stringResource(
                    R.string.settings_key_color_group_summary,
                    Color.alpha(appearanceGroupColors[currentKeyGroupIndex]),
                    currentKeyGroup.entryCount
                ),
                color = appearanceGroupColors[currentKeyGroupIndex],
                onColorChange = onKeyColorChange
            )
        }
    }

    item {
        SmallTitle(text = "品牌强调色")
        Card(
            modifier = Modifier.padding(horizontal = 16.dp),
            insideMargin = PaddingValues(0.dp)
        ) {
            Column {
                appearanceSectionGroups.forEach { group ->
                    val index = groupIndex(group.id)
                    AppearanceColorGroupEditor(
                        title = group.displayName,
                        summary = stringResource(
                            R.string.settings_appearance_color_group_summary,
                            group.entryCount,
                            formatArgb(group.defaultColor)
                        ),
                        color = appearanceGroupColors[index],
                        onColorChange = { appearanceGroupColors[index] = it }
                    )
                }
            }
        }
    }
}
