package com.xposed.wetypehook

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.xposed.wetypehook.wetype.settings.GlassMaterialOverrides
import com.xposed.wetypehook.wetype.settings.GlassOverrideField
import com.xposed.wetypehook.wetype.settings.GlassSliderParameter
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt


@Composable
internal fun GlassOverrideEditor(
    values: List<String>,
    enabled: Boolean,
    onValueChange: (Int, String) -> Unit,
    onReset: () -> Unit
) {
    fun read(field: GlassOverrideField) = runCatching {
        GlassMaterialOverrides.parse(mapOf(field to values[field.ordinal]))
    }.getOrNull()
    val glass = read(GlassOverrideField.GLASS)?.glass
    val radii = read(GlassOverrideField.BLUR_RADII)?.blurRadii ?: GlassSliderParameter.startingBlurRadii()
    val bloom = read(GlassOverrideField.BLOOM)?.bloom ?: GlassSliderParameter.startingBloom()
    val valid = GlassOverrideField.entries.all { read(it) != null }
    SwitchPreference(
        title = stringResource(R.string.settings_glass_custom),
        summary = stringResource(
            if (enabled) R.string.settings_glass_custom_desc else R.string.settings_glass_unavailable
        ),
        checked = glass != null,
        enabled = enabled && valid,
        onCheckedChange = { checked ->
            val current = GlassMaterialOverrides(glass, radii, bloom)
            val updated = current.withGlassEnabled(checked)
            GlassOverrideField.entries.forEach { onValueChange(it.ordinal, updated.text(it)) }
        }
    )
    if (glass == null && valid) return
    HorizontalDivider()
    if (glass != null) {
        val labels = mapOf(
            GlassSliderParameter.LIGHT_ANGLE to R.string.settings_glass_light_angle,
            GlassSliderParameter.LIGHT_INTENSITY to R.string.settings_glass_light_intensity,
            GlassSliderParameter.REFRACTION to R.string.settings_glass_refraction,
            GlassSliderParameter.DEPTH to R.string.settings_glass_depth,
            GlassSliderParameter.SPLAY to R.string.settings_glass_splay,
            GlassSliderParameter.THICKNESS to R.string.settings_glass_thickness,
            GlassSliderParameter.EDGE_CURVE to R.string.settings_glass_edge_curve
        )
        @Composable
        fun ParameterSlider(parameter: GlassSliderParameter, bloomMode: Boolean = false) {
            val label = stringResource(
                if (!bloomMode && parameter == GlassSliderParameter.LIGHT_INTENSITY) R.string.settings_glass_edge_lighten
                else if (!bloomMode && parameter == GlassSliderParameter.SPLAY) R.string.settings_glass_lighten_angle
                else labels.getValue(parameter)
            )
            SliderPreferenceItem(
                title = if (bloomMode) stringResource(R.string.settings_glass_highlight_parameter, label) else label,
                value = if (bloomMode) parameter.readBloom(bloom) else parameter.read(glass),
                range = parameter.range,
                step = parameter.step,
                enabled = enabled && valid,
                format = { value ->
                    when (parameter) {
                        GlassSliderParameter.LIGHT_ANGLE, GlassSliderParameter.SPLAY -> "${value.roundToInt()}°"
                        GlassSliderParameter.LIGHT_INTENSITY -> "${value.roundToInt()}%"
                        GlassSliderParameter.DEPTH, GlassSliderParameter.THICKNESS -> "${value.roundToInt()} px"
                        else -> String.format(java.util.Locale.ROOT, "%.2f", value)
                    }
                },
                onValueChange = {
                    val field = if (bloomMode) GlassOverrideField.BLOOM else GlassOverrideField.GLASS
                    val updated = if (bloomMode) parameter.writeBloom(bloom, it) else parameter.write(glass, it)
                    onValueChange(field.ordinal, updated.joinToString(", "))
                }
            )
        }
        GlassSliderParameter.entries.forEach { ParameterSlider(it) }
        val unsupported = stringResource(R.string.settings_glass_unsupported)
        SliderPreferenceItem(
            title = stringResource(R.string.settings_glass_dispersion),
            value = 0f, range = 0f..100f, step = 1f,
            enabled = false, format = { unsupported }, onValueChange = {}
        )
        listOf(R.string.settings_glass_frost_small, R.string.settings_glass_frost_large).forEachIndexed { index, title ->
            SliderPreferenceItem(
                title = stringResource(title), value = radii[index].toFloat(),
                range = 0f..400f, step = 1f, enabled = enabled && valid,
                format = { "${it.roundToInt()} px" },
                onValueChange = { value ->
                    val updated = radii.toMutableList().apply { this[index] = value.roundToInt() }
                    onValueChange(GlassOverrideField.BLUR_RADII.ordinal, updated.joinToString(", "))
                }
            )
        }
        GlassSliderParameter.entries.filter { it.bloomIndex != null }.forEach {
            ParameterSlider(it, bloomMode = true)
        }
    }
    HorizontalDivider()
    var rawExpanded by rememberSaveable { mutableStateOf(false) }
    val showRaw = rawExpanded || !valid
    val arrowRotation by animateFloatAsState(
        targetValue = if (showRaw) 90f else 0f,
        animationSpec = tween(durationMillis = 200),
        label = "GlassRawArrowRotation"
    )
    BasicComponent(
        title = stringResource(R.string.settings_glass_raw),
        summary = stringResource(if (showRaw) R.string.settings_glass_collapse else R.string.settings_glass_expand),
        onClick = { rawExpanded = !rawExpanded },
        endActions = {
            Icon(
                imageVector = MiuixIcons.Basic.ArrowRight,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                modifier = Modifier.size(width = 10.dp, height = 16.dp).rotate(arrowRotation)
            )
        }
    )
    if (showRaw) GlassRawOverrideEditor(values, enabled, onValueChange)
    ArrowPreference(
        title = stringResource(R.string.settings_glass_reset),
        summary = stringResource(R.string.settings_glass_reset_desc),
        onClick = onReset
    )
}

@Composable
internal fun GlassRawOverrideEditor(
    values: List<String>,
    enabled: Boolean,
    onValueChange: (Int, String) -> Unit
) {
    val titles = listOf(
        R.string.settings_glass_params, R.string.settings_glass_blur,
        R.string.settings_glass_bloom, R.string.settings_glass_type
    )
    val descriptions = listOf(
        R.string.settings_glass_params_desc, R.string.settings_glass_blur_desc,
        R.string.settings_glass_bloom_desc, R.string.settings_glass_type_desc
    )
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text(
            text = stringResource(R.string.settings_glass_raw_summary),
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
        )
        GlassOverrideField.entries.filter { it != GlassOverrideField.MATERIAL_TYPE }.forEach { field ->
            val index = field.ordinal
            val valid = GlassMaterialOverrides.isValid(field, values[index])
            Column(Modifier.fillMaxWidth().padding(top = 16.dp).alpha(if (enabled) 1f else 0.38f)) {
                Text(stringResource(titles[index]), style = MiuixTheme.textStyles.main)
                Text(
                    stringResource(descriptions[index]), style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
                Spacer(Modifier.height(8.dp))
                TextField(
                    value = values[index],
                    enabled = enabled,
                    onValueChange = { if (it.length <= 4096) onValueChange(index, it) },
                    label = stringResource(R.string.settings_glass_default),
                    singleLine = field == GlassOverrideField.MATERIAL_TYPE || field == GlassOverrideField.BLUR_RADII,
                    modifier = Modifier.fillMaxWidth()
                )
                if (!valid) {
                    Text(
                        stringResource(R.string.settings_glass_invalid),
                        color = MiuixTheme.colorScheme.error,
                        style = MiuixTheme.textStyles.body2
                    )
                }
            }
        }
    }
}
