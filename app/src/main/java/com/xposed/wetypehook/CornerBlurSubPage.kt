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


internal fun LazyListScope.CornerBlurSubPageContent(
    systemMaterialActive: Boolean,
    blurRadius: Int,
    onBlurRadiusChange: (Int) -> Unit,
    cornerRadius: Int,
    onCornerRadiusChange: (Int) -> Unit,
    bottomCornerRadius: Int,
    onBottomCornerRadiusChange: (Int) -> Unit,
    keyCornerRadius: Int,
    onKeyCornerRadiusChange: (Int) -> Unit,
    candidateBackgroundCorner: Int,
    onCandidateBackgroundCornerChange: (Int) -> Unit
) {
    item {
        SmallTitle(text = "模糊")
        Card(
            modifier = Modifier.padding(horizontal = 16.dp),
            insideMargin = PaddingValues(0.dp)
        ) {
            SliderPreferenceItem(
                title = stringResource(R.string.settings_blur_title),
                value = blurRadius,
                max = 100,
                enabled = !systemMaterialActive,
                onValueChange = onBlurRadiusChange
            )
        }
    }

    item {
        SmallTitle(text = "圆角")
        Card(
            modifier = Modifier.padding(horizontal = 16.dp),
            insideMargin = PaddingValues(0.dp)
        ) {
            SliderPreferenceItem(
                title = stringResource(R.string.settings_corner_title),
                value = cornerRadius,
                max = WeTypeSettings.MAX_CORNER_RADIUS,
                onValueChange = onCornerRadiusChange
            )
            SliderPreferenceItem(
                title = stringResource(R.string.settings_bottom_corner_title),
                value = bottomCornerRadius,
                max = WeTypeSettings.MAX_BOTTOM_CORNER_RADIUS,
                onValueChange = onBottomCornerRadiusChange
            )
            SliderPreferenceItem(
                title = stringResource(R.string.settings_key_corner_title),
                value = keyCornerRadius,
                max = WeTypeSettings.MAX_KEY_CORNER_RADIUS,
                onValueChange = onKeyCornerRadiusChange
            )
            SliderPreferenceItem(
                title = stringResource(R.string.settings_candidate_corner_title),
                value = candidateBackgroundCorner,
                max = WeTypeSettings.MAX_CANDIDATE_BACKGROUND_CORNER,
                onValueChange = onCandidateBackgroundCornerChange
            )
        }
    }
}
