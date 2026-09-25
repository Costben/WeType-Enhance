package com.xposed.wetypehook

import android.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kyant.capsule.ContinuousRoundedRectangle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme


@Composable
internal fun AppearanceColorGroupEditor(
    title: String,
    summary: String,
    color: Int,
    onColorChange: (Int) -> Unit
) {
    var input by rememberSaveable(title) { mutableStateOf(formatArgb(color)) }

    LaunchedEffect(color) {
        val formatted = formatArgb(color)
        if (!input.equals(formatted, ignoreCase = true) && parseHexColor(input) != color) {
            input = formatted
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(32.dp)
                    .height(32.dp)
                    .clip(ContinuousRoundedRectangle(999.dp))
                    .background(ComposeColor(color))
                    .border(1.dp, MiuixTheme.colorScheme.outline, ContinuousRoundedRectangle(999.dp))
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = title,
                    style = MiuixTheme.textStyles.main
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = summary,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.body2
                )
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        TextField(
            value = input,
            onValueChange = { raw ->
                val sanitized = sanitizeHexColorInput(raw) ?: return@TextField
                input = sanitized
                parseHexColor(sanitized)?.let(onColorChange)
            },
            label = stringResource(R.string.settings_appearance_color_input_label),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
internal fun KeyColorEditor(
    title: String,
    summary: String,
    color: Int,
    onColorChange: (Int) -> Unit
) {
    var input by rememberSaveable(title) { mutableStateOf(formatRgb(color)) }

    LaunchedEffect(color) {
        val formatted = formatRgb(color)
        val normalizedColor = Color.rgb(Color.red(color), Color.green(color), Color.blue(color))
        if (!input.equals(formatted, ignoreCase = true) && parseRgbColor(input) != normalizedColor) {
            input = formatted
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 16.dp)
        ) {
            Text(
                text = title,
                style = MiuixTheme.textStyles.main
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = summary,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.body2
            )
        }
        // Per-mode key opacity, baked directly into the key color's alpha channel (same logic as
        // the background opacity slider above).
        SliderPreferenceItem(
            title = stringResource(R.string.settings_key_opacity_title),
            value = Color.alpha(color),
            max = 255,
            onValueChange = { alpha ->
                onColorChange((alpha shl 24) or (color and 0xFFFFFF))
            }
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
        ) {
            TextField(
                value = input,
                onValueChange = { raw ->
                    val sanitized = sanitizeRgbColorInput(raw) ?: return@TextField
                    input = sanitized
                    parseRgbColor(sanitized)?.let { rgb ->
                        onColorChange((color and 0xFF000000.toInt()) or (rgb and 0xFFFFFF))
                    }
                },
                label = stringResource(R.string.settings_key_color_input_label),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
