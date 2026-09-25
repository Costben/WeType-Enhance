package com.xposed.wetypehook

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.capsule.ContinuousRoundedRectangle
import com.xposed.wetypehook.wetype.graphics.WeTypeBloomStrokeDrawable
import com.xposed.wetypehook.wetype.graphics.WeTypeCornerRadii
import com.xposed.wetypehook.wetype.graphics.WeTypeSystemMaterials
import com.xposed.wetypehook.wetype.graphics.WeTypeSmoothRoundedShape
import com.xposed.wetypehook.wetype.graphics.createWeTypeSmoothRoundedPath
import com.xposed.wetypehook.wetype.settings.WeTypeSettings
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Pin
import top.yukonga.miuix.kmp.icon.extended.Unpin
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlinx.coroutines.delay
import kotlin.math.roundToInt


@Composable
internal fun PreviewSection(
    color: Int,
    blurRadius: Int,
    cornerRadius: Int,
    bottomCornerRadius: Int,
    keyCornerRadius: Int,
    edgeHighlightEnabled: Boolean,
    edgeHighlightIntensity: Int,
    keyEdgeLight: ReplicaEdgeLight,
    lightKeyColor: Int,
    darkKeyColor: Int,
    isDark: Boolean,
    systemMaterialEnabled: Boolean,
    hyperMaterialEnabled: Boolean = false,
    nativeEdgeLightEnabled: Boolean = false,
    pinned: Boolean = false,
    onTogglePin: (() -> Unit)? = null,
    keyboardPreviewEnabled: Boolean = false,
    onToggleKeyboardPreview: (() -> Unit)? = null,
    onToggleDarkMode: (() -> Unit)? = null,
    wallpaper: Bitmap? = null
) {
    PreviewCard(
        color = color,
        blurRadius = blurRadius,
        cornerRadius = cornerRadius,
        bottomCornerRadius = bottomCornerRadius,
        keyCornerRadius = keyCornerRadius,
        edgeHighlightEnabled = edgeHighlightEnabled,
        edgeHighlightIntensity = edgeHighlightIntensity,
        keyEdgeLight = keyEdgeLight,
        lightKeyColor = lightKeyColor,
        darkKeyColor = darkKeyColor,
        isDark = isDark,
        systemMaterialEnabled = systemMaterialEnabled,
        hyperMaterialEnabled = hyperMaterialEnabled,
        nativeEdgeLightEnabled = nativeEdgeLightEnabled,
        showPinToggle = onTogglePin != null,
        pinned = pinned,
        onTogglePin = onTogglePin,
        keyboardPreviewEnabled = keyboardPreviewEnabled,
        onToggleKeyboardPreview = onToggleKeyboardPreview,
        onToggleDarkMode = onToggleDarkMode,
        wallpaper = wallpaper
    )
}

@Composable
internal fun PreviewCard(
    color: Int,
    blurRadius: Int,
    cornerRadius: Int,
    bottomCornerRadius: Int,
    keyCornerRadius: Int,
    edgeHighlightEnabled: Boolean,
    edgeHighlightIntensity: Int,
    keyEdgeLight: ReplicaEdgeLight,
    lightKeyColor: Int,
    darkKeyColor: Int,
    isDark: Boolean,
    systemMaterialEnabled: Boolean,
    hyperMaterialEnabled: Boolean = false,
    nativeEdgeLightEnabled: Boolean = false,
    showPinToggle: Boolean = false,
    pinned: Boolean = false,
    onTogglePin: (() -> Unit)? = null,
    keyboardPreviewEnabled: Boolean = false,
    onToggleKeyboardPreview: (() -> Unit)? = null,
    onToggleDarkMode: (() -> Unit)? = null,
    wallpaper: Bitmap? = null
) {
    val context = LocalContext.current
    val previewDensity = LocalDensity.current
    // 标题与副标题的写法跟着「真正在生效的那套系统材质」：ColorOS 后端看「ColorOS 系统材质」，
    // 其余后端看「MIUI 系统材质」——只看 MIUI 那个开关的话，ColorOS 上会写错。
    val systemMaterialActive = systemMaterialEnabled &&
        if (WeTypeSystemMaterials.isColorOsBackend()) nativeEdgeLightEnabled else hyperMaterialEnabled
    // 面板按真机的材质分档画：系统材质接管背板时底色与模糊都不是模块那一套。
    val materialPanel = NativeMaterialPreview.panel(
        color = color,
        isDark = isDark,
        moduleBlurDp = (blurRadius / 3f).coerceAtLeast(0f),
        systemBlurDp = with(previewDensity) { NativeMaterialPreview.SYSTEM_BLUR_PX.toDp().value },
        fallbackColor = WeTypeSystemMaterials.fallbackColor(isDark),
        systemMaterialEnabled = systemMaterialEnabled,
        hyperMaterialEnabled = hyperMaterialEnabled,
        colorOsBackend = WeTypeSystemMaterials.isColorOsBackend(),
        edgeHighlightEnabled = edgeHighlightEnabled,
        nativeEdgeLightEnabled = nativeEdgeLightEnabled,
        edgeHighlightIntensity = edgeHighlightIntensity
    )
    val displayColor = materialPanel.color
    val weTypeFontFamily = remember(context) {
        FontFamily(
            Font(
                path = "WE-Regular.ttf",
                assetManager = context.assets
            )
        )
    }
    val previewCornerValue = cornerRadius.coerceIn(0, WeTypeSettings.MAX_CORNER_RADIUS)
    val previewBottomCornerValue = bottomCornerRadius.coerceIn(0, WeTypeSettings.MAX_BOTTOM_CORNER_RADIUS)
    val previewCorner = previewCornerValue.dp
    val previewBottomCorner = previewBottomCornerValue.dp
    val previewMinHeight = maxOf(88.dp, (previewCornerValue + previewBottomCornerValue).dp)
    val previewRadiusPx = with(previewDensity) { previewCorner.toPx() }
    val previewBottomRadiusPx = with(previewDensity) { previewBottomCorner.toPx() }
    val previewRadii = WeTypeCornerRadii(
        topLeft = previewRadiusPx,
        topRight = previewRadiusPx,
        bottomRight = previewBottomRadiusPx,
        bottomLeft = previewBottomRadiusPx
    )
    val previewShape = WeTypeSmoothRoundedShape(previewRadii)
    val previewKeyColor = if (isDark) darkKeyColor else lightKeyColor
    // 键面字形压在键帽上：键帽色是半透明的，先跟面板合成再定字色。
    val previewKeyLabelColor = replicaKeyLabelColor(previewKeyColor, displayColor)
    val previewKeyShape = ContinuousRoundedRectangle(keyCornerRadius.dp)
    // 真机键盘预览的读数：屏幕圆角与宿主实测键盘高度，都是「配置跟这台机器贴不贴」的依据。
    val previewView = LocalView.current
    var screenCornerRadiusPx by remember(previewView) {
        mutableStateOf(resolveScreenCornerRadiusPx(previewView))
    }
    LaunchedEffect(previewView) {
        // 首次组合时窗口 insets 可能还没下发，等一帧补读一次。
        if (screenCornerRadiusPx == null) {
            delay(120L)
            screenCornerRadiusPx = resolveScreenCornerRadiusPx(previewView)
        }
    }
    val screenCornerRadiusDp = screenCornerRadiusPx?.let { with(previewDensity) { it.toDp().value } }
    val hostKeyboardHeightPx = if (keyboardPreviewEnabled) {
        val windowHeightPx = with(previewDensity) {
            LocalConfiguration.current.screenHeightDp.dp.roundToPx()
        }
        WeTypeKeyboardMetrics.keyboardHeightForWindow(context, windowHeightPx)
    } else {
        null
    }
    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = previewMinHeight)
                    .weTypePreviewBloom(
                        color = displayColor,
                        topCornerRadius = previewCorner,
                        bottomCornerRadius = previewBottomCorner,
                        edgeHighlightEnabled = materialPanel.moduleBloom,
                        edgeHighlightIntensity = edgeHighlightIntensity,
                        isDark = isDark
                    )
                    .clip(previewShape)
            ) {
                // 第一层：手机壁纸。真机面板半透明，底下不铺点东西就完全看不出透明度生效没有。
                if (wallpaper != null) {
                    Image(
                        bitmap = wallpaper.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.matchParentSize()
                    )
                }
                // 背板：真机的毛玻璃模糊的是键盘底下那层东西，所以有壁纸时模糊的就是壁纸本身；
                // 没设壁纸才退回内置纹理。
                if (materialPanel.backdropVisible) {
                    val backdrop: Painter = wallpaper
                        ?.let { BitmapPainter(it.asImageBitmap()) }
                        ?: painterResource(R.drawable.natural_texture_004)
                    Image(
                        painter = backdrop,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .matchParentSize()
                            .blur(materialPanel.backdropBlurDp.dp)
                    )
                }
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(ComposeColor(displayColor))
                        .nativeMaterialEdgeGlow(previewRadii, materialPanel.nativeEdgeGlow)
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp)
                ) {
                    // 右上角图标只占第一行。放在整列右侧的话会把下面那排键帽挤窄，
                    // 「色号 / 系统材质 + A B C」那一条就摆不开了。
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (isDark) stringResource(R.string.settings_preview_mode_dark) else stringResource(R.string.settings_preview_mode_light),
                            color = previewTextColor(displayColor).copy(alpha = 0.7f),
                            style = MiuixTheme.textStyles.body2,
                            modifier = Modifier.weight(1f)
                        )
                        // 三颗图标顶在键盘背景右上角：切浅色/深色、召唤真机键盘、固定预览。
                        Row(
                            modifier = Modifier.padding(start = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (onToggleDarkMode != null) {
                                Icon(
                                    imageVector = if (isDark) PreviewSunIcon else PreviewMoonIcon,
                                    contentDescription = if (isDark) {
                                        "切换到浅色预览"
                                    } else {
                                        "切换到深色预览"
                                    },
                                    tint = previewTextColor(displayColor).copy(alpha = 0.85f),
                                    modifier = Modifier
                                        .size(20.dp)
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                            onClick = onToggleDarkMode
                                        )
                                )
                            }
                            if (onToggleKeyboardPreview != null) {
                                PreviewKeyboardToggle(
                                    enabled = keyboardPreviewEnabled,
                                    tint = previewTextColor(displayColor),
                                    onClick = onToggleKeyboardPreview
                                )
                            }
                            if (showPinToggle) {
                                Icon(
                                    imageVector = if (pinned) MiuixIcons.Unpin else MiuixIcons.Pin,
                                    contentDescription = if (pinned) {
                                        "取消固定预览"
                                    } else {
                                        "把预览固定在标题栏下方"
                                    },
                                    tint = previewTextColor(displayColor).copy(alpha = 0.85f),
                                    modifier = Modifier
                                        .size(20.dp)
                                        .then(
                                            if (onTogglePin != null) {
                                                Modifier.clickable(
                                                    interactionSource = remember {
                                                        MutableInteractionSource()
                                                    },
                                                    indication = null,
                                                    onClick = onTogglePin
                                                )
                                            } else {
                                                Modifier
                                            }
                                        )
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(previewKeyShape)
                                .background(ComposeColor(previewKeyColor))
                                .weTypeKeyEdgeLight(
                                    cornerRadius = keyCornerRadius.dp,
                                    surfaceColor = previewKeyColor,
                                    light = keyEdgeLight,
                                    isDark = isDark
                                )
                                .padding(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = if (systemMaterialActive) stringResource(R.string.settings_hyper_material_preview) else formatArgb(color),
                                color = previewKeyLabelColor,
                                style = MiuixTheme.textStyles.headline1,
                                fontFamily = weTypeFontFamily
                            )
                        }

                        for (i in 'A'..'C') {
                            Box(
                                modifier = Modifier
                                    .clip(previewKeyShape)
                                    .background(ComposeColor(previewKeyColor))
                                    .weTypeKeyEdgeLight(
                                        cornerRadius = keyCornerRadius.dp,
                                        surfaceColor = previewKeyColor,
                                        light = keyEdgeLight,
                                        isDark = isDark
                                    )
                                    .padding(horizontal = 14.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = i.toString(),
                                    color = previewKeyLabelColor,
                                    style = MiuixTheme.textStyles.headline1,
                                    fontFamily = weTypeFontFamily
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (systemMaterialActive) {
                            "${stringResource(R.string.settings_corner_label)} $cornerRadius / $bottomCornerRadius"
                        } else {
                            "${stringResource(R.string.settings_blur_label)} $blurRadius · ${stringResource(R.string.settings_corner_label)} $cornerRadius / $bottomCornerRadius"
                        },
                        color = previewTextColor(displayColor).copy(alpha = 0.7f),
                        style = MiuixTheme.textStyles.body2
                    )
                    if (keyboardPreviewEnabled) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = buildString {
                                append(
                                    hostKeyboardHeightPx?.let { "键盘高度 ${it}px" }
                                        ?: "键盘高度 未测到"
                                )
                                append(" · ")
                                append(
                                    screenCornerRadiusDp?.let { "屏幕圆角 ${it.roundToInt()}dp" }
                                        ?: "屏幕圆角 未知"
                                )
                            },
                            color = previewTextColor(displayColor).copy(alpha = 0.7f),
                            style = MiuixTheme.textStyles.body2
                        )
                    }
                }
            }
        }
    }
}

/**
 * 预览卡右上角的「召唤真机键盘」开关：键盘字形外面套一圈虚线环。
 *
 * 旁边两颗图标是纯字形的一次性动作（切深浅、固定），这颗是开关，虚线环用来把它区分开；
 * 环和字形共用同一档透明度，关着的时候整颗一起变淡。
 */
@Composable
internal fun PreviewKeyboardToggle(
    enabled: Boolean,
    tint: ComposeColor,
    onClick: () -> Unit
) {
    val iconAlpha = if (enabled) 0.9f else 0.45f
    Box(
        modifier = Modifier
            .size(width = 26.dp, height = 20.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .drawBehind {
                val strokeWidth = 1.dp.toPx()
                val dash = 2.dp.toPx()
                // 虚线环：描边比盒子的高度还细，内缩半个线宽才不会被裁掉。
                drawRoundRect(
                    color = tint.copy(alpha = iconAlpha),
                    topLeft = Offset(strokeWidth / 2f, strokeWidth / 2f),
                    size = Size(size.width - strokeWidth, size.height - strokeWidth),
                    cornerRadius = CornerRadius(6.dp.toPx()),
                    style = Stroke(
                        width = strokeWidth,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash))
                    )
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = SettingsKeyboardIcon,
            contentDescription = if (enabled) "收起真机键盘预览" else "召唤真机键盘预览",
            tint = tint.copy(alpha = iconAlpha),
            modifier = Modifier.size(15.dp)
        )
    }
}


/**
 * 预览里样例键帽的内发光：形状沿用键帽自己的圆角，铺在底色之上、字形之下。
 *
 * 光感是内发光，所以调用方必须已经用同一个圆角把节点裁好（真机上光感也是被键帽轮廓裁着的）。
 * [surfaceColor] 是键帽底色，真机自绘源也拿它微调亮度，两边同一个口径。
 */
@Composable
internal fun Modifier.weTypeKeyEdgeLight(
    cornerRadius: androidx.compose.ui.unit.Dp,
    surfaceColor: Int,
    light: ReplicaEdgeLight,
    isDark: Boolean
): Modifier {
    val context = LocalContext.current
    val density = LocalDensity.current
    val radiusPx = with(density) { cornerRadius.toPx() }
    return this.drawWithCache {
        val bitmap = ReplicaEdgeLightRenderer.bitmap(
            context = context,
            widthPx = size.width.roundToInt(),
            heightPx = size.height.roundToInt(),
            cornerRadiusPx = radiusPx,
            surfaceColor = surfaceColor,
            light = light,
            isDark = isDark
        )
        onDrawBehind { drawReplicaEdgeLightBitmap(bitmap) }
    }
}

@Composable
internal fun Modifier.weTypePreviewBloom(
    color: Int,
    topCornerRadius: androidx.compose.ui.unit.Dp,
    bottomCornerRadius: androidx.compose.ui.unit.Dp,
    edgeHighlightEnabled: Boolean,
    edgeHighlightIntensity: Int,
    isDark: Boolean
): Modifier {
    val context = LocalContext.current
    val density = LocalDensity.current
    val previewContext = remember(context, isDark) {
        createPreviewContext(context, isDark)
    }
    val topRadiusPx = with(density) { topCornerRadius.toPx() }
    val bottomRadiusPx = with(density) { bottomCornerRadius.toPx() }
    return this.drawWithCache {
        val previewCornerRadii = WeTypeCornerRadii(
            topLeft = topRadiusPx,
            topRight = topRadiusPx,
            bottomRight = bottomRadiusPx,
            bottomLeft = bottomRadiusPx
        )
        val widthPx = size.width.roundToInt()
        val heightPx = size.height.roundToInt()
        // The bloom overlay relies on clipPath + BlurMaskFilter + Path.op, which are not reliably
        // supported on Compose's hardware-accelerated recording canvas and crash the preview. Render
        // it once into an offscreen software bitmap (which supports every operation) and blit the
        // result, keeping the preview pixel-accurate.
        val overlayBitmap = if (edgeHighlightEnabled && widthPx > 0 && heightPx > 0) {
            runCatching {
                val bloomDrawable = WeTypeBloomStrokeDrawable(
                    context = previewContext,
                    cornerRadii = previewCornerRadii,
                    surfaceColor = color,
                    intensityScale = edgeHighlightIntensity / 100f
                )
                bloomDrawable.setBounds(0, 0, widthPx, heightPx)
                val clipPath = createWeTypeSmoothRoundedPath(
                    width = widthPx.toFloat(),
                    height = heightPx.toFloat(),
                    cornerRadii = previewCornerRadii
                )
                Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888).also { bitmap ->
                    val bitmapCanvas = Canvas(bitmap)
                    bitmapCanvas.clipPath(clipPath)
                    bloomDrawable.draw(bitmapCanvas)
                }
            }.getOrNull()
        } else {
            null
        }

        onDrawWithContent {
            drawContent()
            val bitmap = overlayBitmap ?: return@onDrawWithContent
            drawIntoCanvas { canvas ->
                canvas.nativeCanvas.drawBitmap(bitmap, 0f, 0f, null)
            }
        }
    }
}
