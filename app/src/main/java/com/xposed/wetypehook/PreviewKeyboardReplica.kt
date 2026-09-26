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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.xposed.wetypehook.wetype.graphics.WeTypeCornerRadii
import com.xposed.wetypehook.wetype.graphics.WeTypeSystemMaterials
import com.xposed.wetypehook.wetype.graphics.WeTypeSmoothRoundedShape
import com.xposed.wetypehook.wetype.settings.EdgeLightGroup
import com.xposed.wetypehook.wetype.settings.WeTypeSettings
import top.yukonga.miuix.kmp.basic.Text
import kotlin.math.roundToInt


/**
 * 二级页底部那块手绘键盘复刻件。
 *
 * 用途只有一个：对着这台机器的真实屏幕圆角比 [cornerRadius] / [bottomCornerRadius]。所以它跟
 * 真机同尺寸、同材质、同圆角绘制路径 —— 面板走 [weTypePreviewBloom] 与 [WeTypeSmoothRoundedShape]，
 * 材质与按键色的取法与 [PreviewCard] 完全同源，改「键盘外观」这里每个像素都跟着变。
 *
 * 竖直按 [geometry] 缩放，横向按窗口宽度缩放。面板铺满整个 IME 窗口高（含底部那条系统抬高），
 * 与真机一致：真机背板也是从键盘内容顶端一直铺到窗口底边（见 `resolveWeTypeBackgroundBounds`）；
 * 按键只排在键盘本体区域内，系统抬高那条不放任何键。
 *
 * 分两层画，跟真机一样：底下是 [wallpaper]（用户自己挑的「手机壁纸」，用来验面板透明度），
 * 上面才是半透明的键盘面板。
 *
 * 工具栏那一排跟真机一样是**二选一**的：没组词时画 [drawReplicaToolbar]（圆形底 + 七个图标字形）
 * 加 [toolbarLogo]；一进组词整行换成候选词，logo 不画 —— 真机上这两个状态共用同一个宿主视图
 * `ImeCandidateView`，第一个候选的选中底正好压在 logo 那一格上。
 *
 * 点任意键会往前推一格 [demoClickCount]，在两组候选词之间来回切，模拟真机候选条的排版与选中高亮；
 * 点 ⌫ 则是「退出组词」，直接退回工具栏那一态。
 * 第四行键帽画得出来但键面留空：真机上那一行没能可靠辨认，宁可留空也不画错。
 */
@Composable
internal fun KeyboardReplica(
    geometry: ReplicaGeometry,
    color: Int,
    blurRadius: Int,
    cornerRadius: Int,
    bottomCornerRadius: Int,
    keyCornerRadius: Int,
    candidateBackgroundCorner: Float,
    candidateBackgroundAlpha: Int,
    candidateBackgroundLeftMarginDp: Int,
    edgeHighlightEnabled: Boolean,
    backgroundLight: EdgeLightGroup,
    iconEdgeLight: ReplicaEdgeLight,
    keyEdgeLight: ReplicaEdgeLight,
    keyColor: Int,
    isDark: Boolean,
    systemMaterialEnabled: Boolean,
    hyperMaterialEnabled: Boolean,
    nativeEdgeLightEnabled: Boolean,
    nativeEdgeLightIntensity: Int = WeTypeSettings.DEFAULT_NATIVE_EDGE_LIGHT_INTENSITY,
    showCornerGuide: Boolean,
    accentColor: Int,
    toolbarLogo: Bitmap?,
    nativeLogo: Bitmap?,
    logoMode: ReplicaLogoMode,
    wallpaper: Bitmap?,
    onCollapsePreview: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    // 面板按真机的材质分档画，与 PreviewCard 同口径。
    val materialPanel = NativeMaterialPreview.panel(
        color = color,
        isDark = isDark,
        moduleBlurDp = (blurRadius / 3f).coerceAtLeast(0f),
        systemBlurDp = with(density) { NativeMaterialPreview.SYSTEM_BLUR_PX.toDp().value },
        fallbackColor = WeTypeSystemMaterials.fallbackColor(isDark),
        systemMaterialEnabled = systemMaterialEnabled,
        hyperMaterialEnabled = hyperMaterialEnabled,
        colorOsBackend = WeTypeSystemMaterials.isColorOsBackend(),
        edgeHighlightEnabled = edgeHighlightEnabled,
        nativeEdgeLightEnabled = nativeEdgeLightEnabled,
        nativeEdgeLightIntensity = nativeEdgeLightIntensity
    )
    val displayColor = materialPanel.color
    val topCornerDp = cornerRadius.coerceIn(0, WeTypeSettings.MAX_CORNER_RADIUS).dp
    val bottomCornerDp = bottomCornerRadius.coerceIn(0, WeTypeSettings.MAX_BOTTOM_CORNER_RADIUS).dp
    val topCornerPx = with(density) { topCornerDp.toPx() }
    val bottomCornerPx = with(density) { bottomCornerDp.toPx() }
    val panelRadii = WeTypeCornerRadii(
        topLeft = topCornerPx,
        topRight = topCornerPx,
        bottomRight = bottomCornerPx,
        bottomLeft = bottomCornerPx
    )
    val panelShape = WeTypeSmoothRoundedShape(panelRadii)
    val weTypeFontFamily = remember(context) {
        FontFamily(
            Font(
                path = "WE-Regular.ttf",
                assetManager = context.assets
            )
        )
    }
    val keyLabelSize = with(density) { geometry.keyLabelPx.roundToInt().toSp() }
    // 键面字形压在键帽上，所以按键帽（先跟面板合成）取可读色，而不是按面板色。
    val keyTextColor = replicaKeyLabelColor(keyColor, displayColor)
    // 预览里真正画哪颗 Logo。总开关关 → 宿主那颗原生的（颜色就是品牌强调色）；
    // 显示关 → 一颗都不画。
    val displayedLogo = when (logoMode) {
        ReplicaLogoMode.Custom -> toolbarLogo
        ReplicaLogoMode.Native -> nativeLogo
        ReplicaLogoMode.Hidden -> null
    }
    // 点一下键往前推一格：0 是还没点过（照常显示工具栏），之后在两组候选词之间来回切。
    var demoClickCount by remember { mutableIntStateOf(0) }
    val candidateLabels = replicaCandidateLabels(demoClickCount)
    val candidateLabelSize = with(density) { geometry.candidateLabelPx.roundToInt().toSp() }
    // 选中态：底用键帽色（实测 240，与键帽 242 同源），字直接用品牌强调色本身
    // —— 真机实测那条「吃」就是 (47,128,237)，不是按底算出来的可读色。
    val candidateHighlightTextColor = ComposeColor(accentColor)
    val candidateIdleTextColor = previewTextColor(displayColor)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(with(density) { geometry.totalHeightPx.toDp() })
            .clearAndSetSemantics {}
    ) {
        // 第一层：手机壁纸。真机键盘面板是半透明的，底下不铺点东西就完全看不出透明度生效没有。
        if (wallpaper != null) {
            Image(
                bitmap = wallpaper.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize()
            )
        }
        // 第二层：键盘面板本体，铺满整个 IME 窗口高（含底部那条系统抬高）。
        Box(
            modifier = Modifier
                .matchParentSize()
                .weTypePreviewBloom(
                    color = displayColor,
                    topCornerRadius = topCornerDp,
                    bottomCornerRadius = bottomCornerDp,
                    edgeHighlightEnabled = materialPanel.moduleBloom && backgroundLight.enabled,
                    backgroundLight = backgroundLight,
                    angleDegrees = keyEdgeLight.angleDegrees,
                    isDark = isDark
                )
                .clip(panelShape)
        ) {
            // 磨砂背板。真机的毛玻璃模糊的是键盘底下那层东西，所以有壁纸时模糊的就是壁纸本身；
            // 没设壁纸才退回内置纹理。纹理是不透明 JPG，拿它当背板会把壁纸整片盖死，
            // 底下的不透明度滑块也就白调了。
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
                    .nativeMaterialEdgeGlow(panelRadii, materialPanel.nativeEdgeGlow)
            )
            val toolbarTopDp = with(density) { geometry.toolbarTopPx.toDp() }
            val toolbarHeightDp = with(density) { geometry.toolbarHeightPx.toDp() }
            // 工具栏那一排由复刻件自己画：圆形底 + 七个图标字形，与按键、候选条同一条路子。
            // 一进组词整行让给候选词，跟真机一样（两者共用宿主那个 `ImeCandidateView`）。
            if (candidateLabels.isEmpty()) {
                Box(
                    modifier = Modifier
                        .offset(y = toolbarTopDp)
                        .fillMaxWidth()
                        .height(toolbarHeightDp)
                        .drawWithCache {
                            // 七个图标格的圆底同径，光感位图也只画一张，七格共用。
                            val circlePx = geometry.toolbarCircleSizePx
                            val iconLightBitmap = ReplicaEdgeLightRenderer.bitmap(
                                context = context,
                                widthPx = circlePx,
                                heightPx = circlePx,
                                cornerRadiusPx = circlePx / 2f,
                                surfaceColor = color,
                                light = iconEdgeLight,
                                isDark = isDark
                            )
                            onDrawBehind {
                                drawReplicaToolbar(geometry, isDark, iconLightBitmap)
                            }
                        }
                ) {
                    // 最右一格是「收起键盘」：跟真机一样，点它就把键盘预览收起来（真机上则是收键盘）。
                    // 热区就用那一格的圆底范围，跟画出来的圆形对得上。
                    if (onCollapsePreview != null) {
                        Box(
                            modifier = Modifier
                                .offset(
                                    x = with(density) {
                                        geometry.toolbarIconCircleLeftPx(TOOLBAR_COLLAPSE_SLOT_INDEX)
                                            .toDp()
                                    },
                                    y = with(density) {
                                        (geometry.toolbarCircleTopPx - geometry.toolbarTopPx).toDp()
                                    }
                                )
                                .size(with(density) { geometry.toolbarCircleSizePx.toDp() })
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = onCollapsePreview
                                )
                        )
                    }
                }
            }
            // logo 只在没组词时画在真机 logo 位上：真机一进组词，候选条整行接管，
            // 第一个候选的选中底正好压在 logo 那一格上。
            if (displayedLogo != null && candidateLabels.isEmpty()) {
                Image(
                    bitmap = displayedLogo.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .offset(
                            x = with(density) { geometry.toolbarLogoLeftPx.toDp() },
                            y = with(density) { geometry.toolbarLogoTopPx.toDp() }
                        )
                        .size(with(density) { geometry.toolbarLogoSizePx.toDp() })
                        // Logo 格的光感与工具栏图标同一套：圆底同径，也是压在 Logo 之上画
                        // （真机那层 `WeTypeIconEdgeLightLayer` 就是在宿主 drawable 之后铺光感）。
                        .drawWithCache {
                            val logoPx = size.minDimension.roundToInt()
                            val logoLightBitmap = ReplicaEdgeLightRenderer.bitmap(
                                context = context,
                                widthPx = logoPx,
                                heightPx = logoPx,
                                cornerRadiusPx = logoPx / 2f,
                                surfaceColor = color,
                                light = iconEdgeLight,
                                isDark = isDark
                            )
                            onDrawWithContent {
                                drawContent()
                                drawReplicaEdgeLightBitmap(logoLightBitmap)
                            }
                        }
                )
            }
            if (candidateLabels.isNotEmpty()) {
                val itemPaddingDp = with(density) { geometry.candidateItemPaddingPx.toDp() }
                val highlightHeightDp = with(density) { geometry.candidateHighlightHeightPx.toDp() }
                // 第一项左沿：行基础左沿 + 「候选背景左边距」× density（宿主就是 applyDimension(DIP, …)）。
                val candidateRowLeftDp = with(density) {
                    replicaCandidateRowLeftPx(
                        baseLeftPx = geometry.candidateRowLeftPx,
                        marginDp = candidateBackgroundLeftMarginDp,
                        density = this.density
                    ).toDp()
                }
                // 选中底：正圆弧角（宿主自己 drawRoundRect），半径口径见 replicaCandidateCornerPx。
                val highlightShape = RoundedCornerShape(
                    with(density) {
                        replicaCandidateCornerPx(
                            cornerSetting = candidateBackgroundCorner,
                            highlightHeightPx = geometry.candidateHighlightHeightPx
                        ).toDp()
                    }
                )
                // 基色是宿主自己的候选底颜色（模块只压 alpha），不是键帽色。
                val highlightFill = ComposeColor(replicaCandidateBaseColor(isDark)).copy(
                    alpha = replicaCandidateFillAlpha(candidateBackgroundAlpha)
                )
                // 候选词自己摆基线画，不交给 Text 居中 —— CJK 回退字体的行盒上下不对称，
                // 居中会把 CJK 墨迹压到真机位置下方 0.17em。先量好排版盒，才知道选中底要拉多宽。
                val candidateTextStyle = remember(candidateLabelSize, weTypeFontFamily) {
                    TextStyle(fontSize = candidateLabelSize, fontFamily = weTypeFontFamily)
                }
                val candidateMeasurer = rememberTextMeasurer()
                val candidateLayouts = remember(candidateLabels, candidateTextStyle) {
                    candidateLabels.map {
                        candidateMeasurer.measure(AnnotatedString(it), candidateTextStyle)
                    }
                }
                val candidateBaselinePx = with(density) { geometry.candidateBaselineOffsetPx }
                Row(
                    modifier = Modifier
                        .offset(
                            x = candidateRowLeftDp,
                            y = toolbarTopDp + with(density) { geometry.candidateContentDropPx.toDp() }
                        )
                        .height(with(density) { geometry.candidateRowHeightPx.toDp() }),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(
                            with(density) { geometry.candidateItemGapPx.toDp() }
                        )
                    ) {
                        candidateLayouts.forEachIndexed { index, layout ->
                            // 真机候选条：只有第一个候选坐在一块方底上、字用强调色；其余是纯文字。
                            val highlighted = index == 0
                            Box(
                                modifier = Modifier
                                    .height(highlightHeightDp)
                                    .then(
                                        if (highlighted) {
                                            Modifier
                                                .clip(highlightShape)
                                                .background(highlightFill)
                                        } else {
                                            Modifier
                                        }
                                    )
                                    .padding(horizontal = itemPaddingDp),
                                contentAlignment = Alignment.Center
                            ) {
                                Canvas(
                                    modifier = Modifier.size(
                                        width = with(density) { layout.size.width.toDp() },
                                        height = with(density) { layout.size.height.toDp() }
                                    )
                                ) {
                                    // 画布竖直居中，所以基线 = 画布中线再往下 candidateBaselinePx。
                                    val baselineY = size.height / 2f + candidateBaselinePx
                                    drawText(
                                        textLayoutResult = layout,
                                        color = if (highlighted) {
                                            candidateHighlightTextColor
                                        } else {
                                            candidateIdleTextColor
                                        },
                                        topLeft = Offset(0f, baselineY - layout.firstBaseline)
                                    )
                                }
                            }
                        }
                    }
                    Spacer(
                        Modifier.width(with(density) { geometry.candidateDividerGapPx.toDp() })
                    )
                    Box(
                        modifier = Modifier
                            .width(with(density) { geometry.candidateDividerWidthPx.toDp() })
                            .height(with(density) { geometry.candidateDividerHeightPx.toDp() })
                            .background(candidateIdleTextColor.copy(alpha = 0.2f))
                    )
                    Spacer(Modifier.width(with(density) { geometry.candidateMoreGapPx.toDp() }))
                    ReplicaMoreGlyph(
                        color = candidateIdleTextColor.copy(alpha = 0.6f),
                        geometry = geometry
                    )
                }
            }
            geometry.rows.forEachIndexed { rowIndex, row ->
                val faces = REPLICA_KEY_FACES.getOrNull(rowIndex).orEmpty()
                val rowTopDp = with(density) { row.topPx.toDp() }
                val keyHeightDp = with(density) { row.heightPx.toDp() }
                row.keys.forEachIndexed { keyIndex, key ->
                    val face = faces.getOrNull(keyIndex) ?: ReplicaKeyFace.Blank
                    // 真机键帽是正圆弧角矩形（宿主自己 drawRoundRect 画的），不是连续圆角，
                    // 半径口径见 replicaKeyCornerPx：像素值 = min(设置 + 10, 键宽 / 2)。
                    val keyCornerDp = with(density) {
                        replicaKeyCornerPx(keyCornerRadius, key.widthPx).toDp()
                    }
                    val keyShape = RoundedCornerShape(keyCornerDp)
                    Box(
                        modifier = Modifier
                            .offset(
                                x = with(density) { key.leftPx.toDp() },
                                y = rowTopDp
                            )
                            .size(
                                width = with(density) { key.widthPx.toDp() },
                                height = keyHeightDp
                            )
                            .clip(keyShape)
                            .background(ComposeColor(keyColor))
                            // 光感铺在键帽底色之上、字形之下：真机的键帽光感也在键帽自己的绘制流程里，
                            // 字形随宿主正常绘制，压在光感之上。
                            .weTypeKeyEdgeLight(
                                cornerRadius = keyCornerDp,
                                surfaceColor = keyColor,
                                light = keyEdgeLight,
                                isDark = isDark
                            )
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = {
                                    demoClickCount = replicaClickCountAfter(face, demoClickCount)
                                }
                            )
                    ) {
                        when (face) {
                            is ReplicaKeyFace.Letter -> Text(
                                text = face.text,
                                color = keyTextColor,
                                fontSize = keyLabelSize,
                                fontFamily = weTypeFontFamily,
                                modifier = Modifier.align(Alignment.Center)
                            )
                            ReplicaKeyFace.Shift -> ReplicaShiftGlyph(keyTextColor, geometry)
                            ReplicaKeyFace.Backspace -> ReplicaBackspaceGlyph(keyTextColor, geometry)
                            ReplicaKeyFace.Blank -> Unit
                        }
                    }
                }
            }
        }
        // 参考弧画在面板裁剪层之外：它标的是面板底角的几何半径，落在裁剪层里会被自己的形状切掉。
        if (showCornerGuide) {
            KeyboardCornerGuide(
                screenCornerRadiusPx = resolveScreenCornerRadiusPx(LocalView.current),
                panelBottomCornerPx = bottomCornerPx
            )
        }
    }
}

/**
 * 底部两角的参考弧：蓝线是这台机器屏幕自己的圆角，红线是当前配置的
 * [bottomCornerRadius]。两条贴合即说明配置跟屏幕对了。
 */
@Composable
internal fun KeyboardCornerGuide(screenCornerRadiusPx: Float?, panelBottomCornerPx: Float) {
    val strokeWidth = with(LocalDensity.current) { 2.dp.toPx() }
    Canvas(modifier = Modifier.fillMaxSize()) {
        val bottom = size.height
        fun drawCornerPair(radius: Float, color: ComposeColor) {
            // drawArc 的 topLeft/size 是整圆的包围盒，所以边长要铺 2R；铺 R 只会得到半径 R/2 的弧。
            val diameter = radius * 2f
            if (radius <= 0f || diameter > size.width || diameter > bottom) return
            drawArc(
                color = color,
                startAngle = 90f,
                sweepAngle = 90f,
                useCenter = false,
                topLeft = Offset(0f, bottom - diameter),
                size = Size(diameter, diameter),
                style = Stroke(width = strokeWidth)
            )
            drawArc(
                color = color,
                startAngle = 0f,
                sweepAngle = 90f,
                useCenter = false,
                topLeft = Offset(size.width - diameter, bottom - diameter),
                size = Size(diameter, diameter),
                style = Stroke(width = strokeWidth)
            )
        }
        screenCornerRadiusPx?.let { drawCornerPair(it, ComposeColor(0xFF2196F3)) }
        drawCornerPair(panelBottomCornerPx, ComposeColor(0xFFE53935))
    }
}
