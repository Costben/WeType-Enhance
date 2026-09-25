package com.xposed.wetypehook

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.xposed.wetypehook.wetype.settings.WeTypeSettings
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
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
    toolbarIconBgOpacity: Int,
    onToolbarIconBgOpacityChange: (Int) -> Unit
) {
    item {
        SmallTitle(text = "候选词")
        Card(
            modifier = Modifier.padding(horizontal = 16.dp),
            insideMargin = PaddingValues(0.dp)
        ) {
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
