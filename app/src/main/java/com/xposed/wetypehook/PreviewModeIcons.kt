package com.xposed.wetypehook

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/*
 * 图标来源：Tabler Icons (https://github.com/tabler/tabler-icons)，MIT License，
 * Copyright (c) 2020-2025 Paweł Kuna。
 * 路径数据逐字抄自 icons/outline/sun.svg 与 icons/outline/moon.svg，未做任何改动。
 */

/**
 * 预览卡片右上角那颗「切浅色 / 切深色」按钮的字形。
 *
 * miuix 0.9.4 那 163 颗图标里没有任何太阳 / 月亮 / 明暗相关的字形——最接近的 `MiuixIcons.Theme`
 * 是支滚筒刷，`Show` / `Hide` 是眼睛，放在这里看不出在切明暗，所以只能外借。
 *
 * 选 Tabler 的理由是量出来的，不是眼缘：把「切换图标 + 键盘展开按钮 + 置顶按钮」按真机那一排
 * 摆在一起比过一轮，Tabler 的 `sun` / `moon` 在 24 格上占 19.3×20.0，miuix 的 `Pin` / `Unpin`
 * 占 20.0×19.8——两边都停在画布的 83% 上，肩并肩时视觉重量一致。别的几套要么偏小（Material
 * Symbols 18.0、Fluent 18.3），要么顶满画布（Bootstrap 24.0，比邻居胖 20%）。
 *
 * 两条路径都是**中心线**，交给 `addPath` 的 stroke 参数描边，跟 miuix 那样把描边展开成填充轮廓
 * 的做法不同——中心线短得多，改线宽只动一个常量。切记 `fill` 必须留 null：一旦给上填充，太阳
 * 的中心圆和月牙的缺口都会被糊成实心块。
 */
internal val PreviewSunIcon: ImageVector by lazy {
    previewModeIcon(name = "PreviewSun", path = SUN_ICON_PATH)
}

internal val PreviewMoonIcon: ImageVector by lazy {
    previewModeIcon(name = "PreviewMoon", path = MOON_ICON_PATH)
}

/**
 * 描边线宽，单位是 24 单位画布上的单位，24dp 下正好 2dp。
 *
 * 用的是 Tabler 自己的 2，不是 miuix 量出来的 1.75——选型时预览页上摆的就是这个重量，
 * 邻居那两颗在 1.75 上下，两者差 14%，肩并肩看不出来。
 */
internal const val PREVIEW_MODE_ICON_STROKE_WIDTH = 2f

/**
 * 太阳：中心圆 + 八道射线。
 *
 * 上游拆成两条 `<path>`（圆一条、射线一条），这里拼成一条——射线那条以绝对 `M3 12` 开头，
 * 与圆那条的收笔点无关，拼接不会改变任何一段的起点。
 */
internal const val SUN_ICON_PATH =
    "M8 12a4 4 0 1 0 8 0a4 4 0 1 0 -8 0" +
        "M3 12h1m8 -9v1m8 8h1m-9 8v1m-6.4 -15.4l.7 .7m12.1 -.7l-.7 .7m0 11.4l.7 .7m-12.1 -.7l-.7 .7"

/**
 * 月亮：一条月牙，由一段三次贝塞尔收头、两条圆弧盘出缺口（半径 7.5 与 9，扫掠方向相反）。
 *
 * 上游就一条 `<path>`，原样保留。
 */
internal const val MOON_ICON_PATH =
    "M12 3c.132 0 .263 0 .393 0a7.5 7.5 0 0 0 7.92 12.446a9 9 0 1 1 -8.313 -12.454l0 .008"

/**
 * 两颗图标走完全相同的构建参数：24 单位画布、只描边不填充、圆头圆角。
 *
 * `fill = null` 是刻意的，不是漏写；`stroke` 必须显式给，Builder 的默认值是 null，
 * 漏了会静默画成空白。
 */
private fun previewModeIcon(name: String, path: String): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).addPath(
        pathData = addPathNodes(path),
        fill = null,
        stroke = SolidColor(Color.Black),
        strokeLineWidth = PREVIEW_MODE_ICON_STROKE_WIDTH,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round
    ).build()
