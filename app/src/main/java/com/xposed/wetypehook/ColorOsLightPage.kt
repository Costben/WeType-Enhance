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
private const val MIN_EDGE_LIGHT_WIDTH = WeTypeSettings.MIN_EDGE_LIGHT_WIDTH
private const val MAX_EDGE_LIGHT_WIDTH = WeTypeSettings.MAX_EDGE_LIGHT_WIDTH

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
    var nativeLightAngle by remember { mutableStateOf(saved.colorOsLightAngle) }
    var iconEdgeLightEnabled by remember { mutableStateOf(saved.iconEdgeLightEnabled) }
    var nativeEdgeLightEnabled by remember { mutableStateOf(saved.nativeEdgeLightEnabled) }
    var edgeLightWidth by remember { mutableStateOf(saved.edgeLightWidth) }
    var edgeLightAngle by remember { mutableStateOf(saved.edgeLightAngle) }
    var nativeEdgeLightWidth by remember { mutableStateOf(saved.nativeEdgeLightWidth) }
    var message by remember { mutableStateOf("") }

    fun persist() {
        WeTypeSettings.saveColorOsLight(
            context = settingsContext,
            edgeHighlightEnabled = edgeHighlightEnabled,
            edgeHighlightIntensity = edgeHighlightIntensity,
            colorOsLightAngle = nativeLightAngle,
            iconEdgeLightEnabled = iconEdgeLightEnabled,
            nativeEdgeLightEnabled = nativeEdgeLightEnabled,
            edgeLightWidth = edgeLightWidth,
            edgeLightAngle = edgeLightAngle,
            nativeEdgeLightWidth = nativeEdgeLightWidth,
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
                        text = "「背板边缘光」由模块自绘，可在任意模糊背景上显示；「ColorOS 原生边缘光」改用系统材质通道，仅在使用系统原生模糊背景时生效。两者各自独立，同时开启时以原生为准。",
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
                            title = "边缘高光",
                            summary = "模块自绘，在键盘背板四周显示边缘高光",
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
                            value = edgeLightAngle,
                            enabled = edgeHighlightEnabled && !nativeEdgeLightEnabled,
                            onValueChange = {
                                edgeLightAngle = it
                                persist()
                            }
                        )
                        WidthSlider(
                            value = edgeLightWidth,
                            enabled = edgeHighlightEnabled && !nativeEdgeLightEnabled,
                            onValueChange = {
                                edgeLightWidth = it
                                persist()
                            }
                        )
                    }
                }
            }
            item {
                SmallTitle(text = "边缘光效")
            }
            item {
                Card(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    insideMargin = PaddingValues(0.dp)
                ) {
                    Column {
                        BasicComponent(
                            title = "ColorOS 原生边缘光",
                            summary = "改用系统原生材质绘制边缘光与内阴影，下方宽度与强度同时作用于它",
                            endActions = {
                                Switch(
                                    checked = nativeEdgeLightEnabled,
                                    onCheckedChange = {
                                        nativeEdgeLightEnabled = it
                                        message = ""
                                        persist()
                                    }
                                )
                            },
                            onClick = {
                                nativeEdgeLightEnabled = !nativeEdgeLightEnabled
                                message = ""
                                persist()
                            }
                        )
                        LightAngleSlider(
                            value = nativeLightAngle,
                            enabled = edgeHighlightEnabled && nativeEdgeLightEnabled,
                            note = "当前 ColorOS 版本未使用该参数：实测 45° 与 225° 在键盘背板上逐像素相同。",
                            onValueChange = {
                                nativeLightAngle = it
                                persist()
                            }
                        )
                        WidthSlider(
                            value = nativeEdgeLightWidth,
                            enabled = edgeHighlightEnabled && nativeEdgeLightEnabled,
                            onValueChange = {
                                nativeEdgeLightWidth = it
                                persist()
                            }
                        )
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
    note: String? = null,
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
        if (note != null) {
            Text(
                text = note,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
        }
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
private fun WidthSlider(
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
                text = "宽度",
                style = MiuixTheme.textStyles.main,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "${value}dp",
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.main
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Slider(
            enabled = enabled,
            value = value.coerceIn(MIN_EDGE_LIGHT_WIDTH, MAX_EDGE_LIGHT_WIDTH).toFloat(),
            onValueChange = {
                onValueChange(it.roundToInt().coerceIn(MIN_EDGE_LIGHT_WIDTH, MAX_EDGE_LIGHT_WIDTH))
            },
            valueRange = MIN_EDGE_LIGHT_WIDTH.toFloat()..MAX_EDGE_LIGHT_WIDTH.toFloat(),
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "边缘光宽度" }
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
