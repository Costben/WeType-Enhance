package com.xposed.wetypehook

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.xposed.wetypehook.wetype.logo.LogoImageRenderer
import com.xposed.wetypehook.wetype.settings.WeTypeSettings
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Pin
import top.yukonga.miuix.kmp.icon.extended.Unpin
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val PREVIEW_BITMAP_EDGE = 192

// 与 WeTypeResourceHooks 的 LOGO_*_BG_ALPHA_FRACTION 保持一致。
private const val PREVIEW_LOGO_LIGHT_BG_FRACTION = 0.9f
private const val PREVIEW_LOGO_DARK_BG_FRACTION = 0.2f

private fun previewAccent(
    logoColorMode: String,
    brandColor: Int,
    customColor: Int,
    isNight: Boolean
): Int = LogoImageRenderer.resolveAccentColor(
    colorMode = logoColorMode,
    brandColor = brandColor,
    customColor = customColor,
    isNight = isNight
)

/**
 * 与 hook 侧同一渲染路径：自定义图片优先（PNG 原色 / SVG 可选 Tint），
 * 未启用或无图/解码失败则回退矢量 Logo。
 */
internal fun renderPreviewBitmap(
    logoColorMode: String,
    brandColor: Int,
    logoCustomColor: Int,
    toolbarIconBgOpacity: Int,
    enabled: Boolean,
    imageType: String,
    recolor: Boolean,
    pngBase64: String,
    svgText: String,
    isNight: Boolean
): Bitmap {
    if (enabled) {
        val type = WeTypeSettings.normalizeLogoImageType(imageType)
        if (type == WeTypeSettings.LOGO_IMAGE_TYPE_SVG && svgText.isNotEmpty()) {
            LogoImageRenderer.renderSvg(svgText, PREVIEW_BITMAP_EDGE)?.let { bitmap ->
                val tint = if (recolor) {
                    previewAccent(logoColorMode, brandColor, logoCustomColor, isNight)
                } else {
                    null
                }
                return LogoImageRenderer.tintSrcIn(bitmap, tint)
            }
        }
        if (type == WeTypeSettings.LOGO_IMAGE_TYPE_PNG && pngBase64.isNotEmpty()) {
            LogoImageRenderer.decodePng(pngBase64)?.let { return it }
        }
    }
    return LogoImageRenderer.renderVectorFallback(
        accent = previewAccent(logoColorMode, brandColor, logoCustomColor, isNight),
        backgroundAlpha = toolbarIconBgOpacity,
        isDark = isNight,
        backgroundAlphaFraction = if (isNight) {
            PREVIEW_LOGO_DARK_BG_FRACTION
        } else {
            PREVIEW_LOGO_LIGHT_BG_FRACTION
        },
        sizePx = PREVIEW_BITMAP_EDGE
    )
}

/**
 * 模块没接管 Logo 时（总开关关闭）预览该画的原生 Logo。
 *
 * 关掉总开关后 hook 直接 return，真机上就是宿主自己那颗 Logo。宿主画它用的是 Brand 色资源，
 * 而模块的品牌强调色覆盖正好也打在同一个资源上，所以它的颜色就等于当前的品牌强调色 ——
 * 因此这里走矢量回退路径、把 [brandColor] 当成强调色画一遍即可，不需要另存一份素材。
 */
internal fun renderNativePreviewBitmap(
    brandColor: Int,
    toolbarIconBgOpacity: Int,
    isNight: Boolean
): Bitmap = LogoImageRenderer.renderVectorFallback(
    accent = brandColor,
    backgroundAlpha = toolbarIconBgOpacity,
    isDark = isNight,
    backgroundAlphaFraction = if (isNight) {
        PREVIEW_LOGO_DARK_BG_FRACTION
    } else {
        PREVIEW_LOGO_LIGHT_BG_FRACTION
    },
    sizePx = PREVIEW_BITMAP_EDGE
)

/**
 * 浅色 / 深色两档 Logo 预览卡，直接长在「键盘 Logo」二级页里（不再单独开页）。
 * [onTogglePin] 非空时整卡可点击，用于把预览固定到标题栏下方。
 */
@Composable
internal fun LogoImagePreviewCard(
    lightBackgroundColor: Int,
    darkBackgroundColor: Int,
    lightKeyColor: Int,
    darkKeyColor: Int,
    logoColorMode: String,
    brandColor: Int,
    logoCustomColor: Int,
    toolbarIconBgOpacity: Int,
    imageEnabled: Boolean,
    imageType: String,
    svgRecolor: Boolean,
    pngBase64: String,
    svgText: String,
    footerText: String?,
    pinned: Boolean = false,
    onTogglePin: (() -> Unit)? = null
) {
    val lightPreview = remember(
        logoColorMode,
        brandColor,
        logoCustomColor,
        toolbarIconBgOpacity,
        imageEnabled,
        imageType,
        svgRecolor,
        pngBase64,
        svgText
    ) {
        renderPreviewBitmap(
            logoColorMode = logoColorMode,
            brandColor = brandColor,
            logoCustomColor = logoCustomColor,
            toolbarIconBgOpacity = toolbarIconBgOpacity,
            enabled = imageEnabled,
            imageType = imageType,
            recolor = svgRecolor,
            pngBase64 = pngBase64,
            svgText = svgText,
            isNight = false
        )
    }
    val darkPreview = remember(
        logoColorMode,
        brandColor,
        logoCustomColor,
        toolbarIconBgOpacity,
        imageEnabled,
        imageType,
        svgRecolor,
        pngBase64,
        svgText
    ) {
        renderPreviewBitmap(
            logoColorMode = logoColorMode,
            brandColor = brandColor,
            logoCustomColor = logoCustomColor,
            toolbarIconBgOpacity = toolbarIconBgOpacity,
            enabled = imageEnabled,
            imageType = imageType,
            recolor = svgRecolor,
            pngBase64 = pngBase64,
            svgText = svgText,
            isNight = true
        )
    }
    val pinToggleModifier = if (onTogglePin != null) {
        Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onTogglePin
        )
    } else {
        Modifier
    }

    Card(
        // 左右缩进由承载它的容器统一给（跟列表卡片对齐），这里不再自带。
        modifier = pinToggleModifier,
        insideMargin = PaddingValues(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    LogoPreviewStrip(
                        label = "浅色模式",
                        backgroundColor = lightBackgroundColor,
                        keyColor = lightKeyColor,
                        logo = lightPreview
                    )
                    LogoPreviewStrip(
                        label = "深色模式",
                        backgroundColor = darkBackgroundColor,
                        keyColor = darkKeyColor,
                        logo = darkPreview
                    )
                }
                if (onTogglePin != null) {
                    Icon(
                        imageVector = if (pinned) MiuixIcons.Unpin else MiuixIcons.Pin,
                        contentDescription = if (pinned) {
                            "取消固定预览"
                        } else {
                            "把预览固定在标题栏下方"
                        },
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier
                            .padding(start = 12.dp)
                            .size(20.dp)
                    )
                }
            }
            if (footerText != null) {
                Text(
                    text = footerText,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }
        }
    }
}

@Composable
private fun LogoPreviewStrip(
    label: String,
    backgroundColor: Int,
    keyColor: Int,
    logo: Bitmap
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(84.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(ComposeColor(backgroundColor))
                .padding(12.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Image(
                    bitmap = logo.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    // 与 hook 侧 CustomLogoDrawable.centerCrop 同语义：方形槽位居中裁切，保证所见即所得。
                    contentScale = ContentScale.Crop
                )
                repeat(3) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(ComposeColor(keyColor))
                    )
                }
            }
        }
    }
}
