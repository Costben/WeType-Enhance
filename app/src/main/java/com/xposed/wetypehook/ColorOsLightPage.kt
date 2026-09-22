package com.xposed.wetypehook

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.xposed.wetypehook.wetype.settings.WeTypeSettings
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

private const val MIN_LIGHT_ANGLE = 0
private const val MAX_LIGHT_ANGLE = WeTypeSettings.MAX_COLOROS_LIGHT_ANGLE
private const val MAX_EDGE_HIGHLIGHT_INTENSITY = WeTypeSettings.MAX_EDGE_HIGHLIGHT_INTENSITY

@Composable
internal fun ColorOsLightApp(
    settingsContext: Context,
    onClose: () -> Unit
) {
    val darkMode = isSystemInDarkTheme()
    MiuixTheme(colors = if (darkMode) darkColorScheme() else lightColorScheme()) {
        SyncSystemBars(darkMode = darkMode)
        ColorOsLightPage(
            settingsContext = settingsContext,
            onClose = onClose
        )
    }
}

@Composable
internal fun ColorOsLightPage(
    settingsContext: Context,
    onClose: () -> Unit
) {
    val saved = remember(settingsContext) { WeTypeSettings.readLocalSnapshot(settingsContext) }
    var edgeHighlightEnabled by remember { mutableStateOf(saved.edgeHighlightEnabled) }
    var edgeHighlightIntensity by remember { mutableStateOf(saved.edgeHighlightIntensity) }
    var lightAngle by remember { mutableStateOf(saved.colorOsLightAngle) }
    var iconEdgeLightEnabled by remember { mutableStateOf(saved.iconEdgeLightEnabled) }
    var message by remember { mutableStateOf("") }

    fun persist() {
        WeTypeSettings.saveColorOsLight(
            context = settingsContext,
            edgeHighlightEnabled = edgeHighlightEnabled,
            edgeHighlightIntensity = edgeHighlightIntensity,
            colorOsLightAngle = lightAngle,
            iconEdgeLightEnabled = iconEdgeLightEnabled,
            onPersisted = { ok -> if (!ok) message = "保存失败，请重试" }
        )
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .consumeWindowInsets(WindowInsets.systemBars.only(WindowInsetsSides.Top))
            .background(MiuixTheme.colorScheme.background),
        topBar = {
            SmallTopAppBar(
                title = "ColorOS 光感设置",
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            imageVector = MiuixIcons.Back,
                            contentDescription = "返回"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .overScrollVertical()
                .imePadding(),
            contentPadding = PaddingValues(
                top = paddingValues.calculateTopPadding(),
                bottom = 40.dp
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Card(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    insideMargin = PaddingValues(16.dp)
                ) {
                    Text(
                        text = "本页光感依赖 ColorOS 系统的硬件流光轮廓能力，仅在使用系统原生模糊背景时生效，与「HyperOS 质感视效」开关相互独立。",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
            }
            item {
                SmallTitle(text = "背板边缘光")
            }
            item {
                Card(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    insideMargin = PaddingValues(0.dp)
                ) {
                    Column {
                        BasicComponent(
                            title = "边缘光效",
                            summary = "在键盘背板四周显示系统原生流光轮廓",
                            endActions = {
                                Switch(
                                    checked = edgeHighlightEnabled,
                                    onCheckedChange = {
                                        edgeHighlightEnabled = it
                                        message = ""
                                        persist()
                                    }
                                )
                            },
                            onClick = {
                                edgeHighlightEnabled = !edgeHighlightEnabled
                                message = ""
                                persist()
                            }
                        )
                        LightAngleSlider(
                            value = lightAngle,
                            enabled = edgeHighlightEnabled,
                            onValueChange = {
                                lightAngle = it
                                persist()
                            }
                        )
                    }
                }
            }
            item {
                SmallTitle(text = "边缘光效强度")
            }
            item {
                Card(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    insideMargin = PaddingValues(0.dp)
                ) {
                    IntensitySlider(
                        value = edgeHighlightIntensity,
                        enabled = edgeHighlightEnabled,
                        onValueChange = {
                            edgeHighlightIntensity = it
                            persist()
                        }
                    )
                }
            }
            item {
                SmallTitle(text = "按键与图标光感")
            }
            item {
                Card(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    insideMargin = PaddingValues(0.dp)
                ) {
                    BasicComponent(
                        title = "工具栏与 Logo 光感",
                        summary = "让工具栏图标与 Logo 跟随系统边缘光效呈现光泽",
                        endActions = {
                            Switch(
                                checked = iconEdgeLightEnabled,
                                onCheckedChange = {
                                    iconEdgeLightEnabled = it
                                    message = ""
                                    persist()
                                }
                            )
                        },
                        onClick = {
                            iconEdgeLightEnabled = !iconEdgeLightEnabled
                            message = ""
                            persist()
                        }
                    )
                }
            }
            if (message.isNotEmpty()) {
                item {
                    Text(
                        text = message,
                        modifier = Modifier.padding(horizontal = 16.dp),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
            }
        }
    }
}

@Composable
private fun LightAngleSlider(
    value: Int,
    enabled: Boolean,
    onValueChange: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "光照角度",
                style = MiuixTheme.textStyles.main,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "$value°",
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.main
            )
        }
        Text(
            text = "0°/180° 照亮上下，90°/270° 照亮左右，45°/135° 为对角。",
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
        )
        Spacer(modifier = Modifier.height(8.dp))
        Slider(
            enabled = enabled,
            value = value.coerceIn(MIN_LIGHT_ANGLE, MAX_LIGHT_ANGLE).toFloat(),
            onValueChange = {
                onValueChange(it.roundToInt().coerceIn(MIN_LIGHT_ANGLE, MAX_LIGHT_ANGLE))
            },
            valueRange = MIN_LIGHT_ANGLE.toFloat()..MAX_LIGHT_ANGLE.toFloat(),
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "光照角度" }
        )
    }
}

@Composable
private fun IntensitySlider(
    value: Int,
    enabled: Boolean,
    onValueChange: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "强度",
                style = MiuixTheme.textStyles.main,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "$value",
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.main
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Slider(
            enabled = enabled,
            value = value.coerceIn(0, MAX_EDGE_HIGHLIGHT_INTENSITY).toFloat(),
            onValueChange = {
                onValueChange(it.roundToInt().coerceIn(0, MAX_EDGE_HIGHLIGHT_INTENSITY))
            },
            valueRange = 0f..MAX_EDGE_HIGHLIGHT_INTENSITY.toFloat(),
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "边缘光效强度" }
        )
    }
}
