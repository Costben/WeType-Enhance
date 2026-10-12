package com.xposed.wetypehook

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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kyant.capsule.ContinuousRoundedRectangle
import com.xposed.wetypehook.wetype.settings.WeTypeSettings
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt


// 两个候选词边距共用同一个取值范围，与 WeTypeSettings.save 里的 coerceIn 同源。
internal val CandidateMarginRange =
    WeTypeSettings.MIN_CANDIDATE_MARGIN_DP.toFloat()..WeTypeSettings.MAX_CANDIDATE_MARGIN_DP.toFloat()

internal fun LazyListScope.CandidateToolbarSubPageContent(
    candidateBackgroundAlpha: Int,
    onCandidateBackgroundAlphaChange: (Int) -> Unit,
    candidateBackgroundLeftMarginDp: Int,
    onCandidateBackgroundLeftMarginDpChange: (Int) -> Unit,
    candidatePinyinLeftMarginDp: Int,
    onCandidatePinyinLeftMarginDpChange: (Int) -> Unit,
    candidateTextColor: Int,
    onCandidateTextColorChange: (Int) -> Unit,
    brandColor: Int,
    toolbarIconBgOpacity: Int,
    onToolbarIconBgOpacityChange: (Int) -> Unit
) {
    item {
        SmallTitle(text = "候选词")
        Card(
            modifier = Modifier.padding(horizontal = 16.dp),
            insideMargin = PaddingValues(0.dp)
        ) {
            CandidateTextColorEditor(
                candidateTextColor = candidateTextColor,
                brandColor = brandColor,
                onCandidateTextColorChange = onCandidateTextColorChange
            )
            SliderPreferenceItem(
                title = stringResource(R.string.settings_key_color_hook_alpha_title),
                value = candidateBackgroundAlpha,
                max = 255,
                onValueChange = onCandidateBackgroundAlphaChange
            )
            SliderPreferenceItem(
                title = stringResource(R.string.settings_candidate_background_left_margin_title),
                summary = stringResource(R.string.settings_candidate_background_left_margin_desc),
                value = candidateBackgroundLeftMarginDp.toFloat(),
                range = CandidateMarginRange,
                step = 1f,
                format = { "${it.roundToInt()} dp" },
                onValueChange = { onCandidateBackgroundLeftMarginDpChange(it.roundToInt()) }
            )
            SliderPreferenceItem(
                title = stringResource(R.string.settings_candidate_pinyin_margin_title),
                summary = stringResource(R.string.settings_candidate_pinyin_margin_desc),
                value = candidatePinyinLeftMarginDp.toFloat(),
                range = CandidateMarginRange,
                step = 1f,
                format = { "${it.roundToInt()} dp" },
                onValueChange = { onCandidatePinyinLeftMarginDpChange(it.roundToInt()) }
            )
        }
    }

    item {
        SmallTitle(text = "工具栏")
        Card(
            modifier = Modifier.padding(horizontal = 16.dp),
            insideMargin = PaddingValues(0.dp)
        ) {
            SliderPreferenceItem(
                title = stringResource(R.string.settings_toolbar_icon_bg_opacity_title),
                value = toolbarIconBgOpacity,
                max = 255,
                onValueChange = onToolbarIconBgOpacityChange
            )
        }
    }
}

/**
 * 候选词文字色：默认（[WeTypeSettings.CANDIDATE_TEXT_COLOR_BRAND]）跟随品牌强调色，
 * 也就是改动之前一直以来的行为；选「自定义颜色」才展开 HEX 输入框。
 *
 * 存的是色值本身而不是「品牌色」这个模式，所以切到自定义再切回品牌色会丢掉自定义值 ——
 * 与「键盘 Logo」页的两种模式保持一致。
 */
@Composable
private fun CandidateTextColorEditor(
    candidateTextColor: Int,
    brandColor: Int,
    onCandidateTextColorChange: (Int) -> Unit
) {
    val isCustom = candidateTextColor != WeTypeSettings.CANDIDATE_TEXT_COLOR_BRAND
    var input by rememberSaveable {
        mutableStateOf(formatRgb(if (isCustom) candidateTextColor else brandColor))
    }

    LaunchedEffect(candidateTextColor) {
        if (candidateTextColor == WeTypeSettings.CANDIDATE_TEXT_COLOR_BRAND) return@LaunchedEffect
        val formatted = formatRgb(candidateTextColor)
        val normalized = candidateTextColor and 0xFFFFFF
        if (!input.equals(formatted, ignoreCase = true) && parseRgbColor(input) != normalized) {
            input = formatted
        }
    }

    OverlayDropdownPreference(
        title = "候选词颜色",
        entry = DropdownEntry(
            items = labeledDropdownItems(
                options = listOf(
                    "品牌色" to "跟随「品牌强调色」",
                    "自定义颜色" to "手动指定候选词文字颜色"
                ),
                selectedIndex = if (isCustom) 1 else 0,
                onSelect = { index ->
                    if (index == 0) {
                        onCandidateTextColorChange(WeTypeSettings.CANDIDATE_TEXT_COLOR_BRAND)
                    } else if (!isCustom) {
                        // 从品牌色切过来时以品牌色起步，视觉上不会突然跳一个颜色。
                        onCandidateTextColorChange(OPAQUE_ALPHA or (brandColor and 0xFFFFFF))
                    }
                }
            )
        )
    )

    SettingExpandGroup(visible = isCustom) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(text = "自定义颜色", style = MiuixTheme.textStyles.main)
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "输入 #RRGGBB，例如 #23C891",
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.body2
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 圆形预览跟「自定义颜色」那一行同一个样式：左边一块色，右边输入框。
                // 输入还没凑够 6 位时退回品牌色，不会闪一块黑。
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(ContinuousRoundedRectangle(999.dp))
                        .background(
                            ComposeColor(
                                parseRgbColor(input)?.let { OPAQUE_ALPHA or (it and 0xFFFFFF) }
                                    ?: brandColor
                            )
                        )
                        .border(
                            1.dp,
                            MiuixTheme.colorScheme.outline,
                            ContinuousRoundedRectangle(999.dp)
                        )
                )
                Spacer(modifier = Modifier.width(12.dp))
                TextField(
                    value = input,
                    onValueChange = { raw ->
                        val sanitized = sanitizeRgbColorInput(raw) ?: return@TextField
                        input = sanitized
                        parseRgbColor(sanitized)?.let { rgb ->
                            onCandidateTextColorChange(OPAQUE_ALPHA or (rgb and 0xFFFFFF))
                        }
                    },
                    label = "#RRGGBB",
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/** 候选词色是不透明文字色，alpha 一律拉满，这样 0 这个取值只会来自「品牌色」。 */
private const val OPAQUE_ALPHA = -0x1000000
