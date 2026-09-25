package com.xposed.wetypehook

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate

/**
 * 工具栏图标格的实测配色，两档都是「白色圆底 + 单色字形」两层。
 *
 * 浅色：圆底白 α150、字形 #5E5E5E α203。
 * 深色：圆底白 α19、字形纯白 α137。
 *
 * 这些不是从截图上反推的合成色，是宿主自己 `View.draw` 进空位图后的 alpha 通道峰值
 * （每格 90px 见方，α137 有 793 个像素平铺，说明那是填充值、不是抗锯齿的过渡像素）。
 * 只有拿到分层 alpha 才能在任意底色上还原出同样的观感 —— 合成色会随面板色漂移。
 *
 * logo 那一格不在其中：宿主的 logo ImageView 自己没有背景，那个圆底是 logo drawable 自带的
 * （模块合成的矢量 logo 按 `toolbarIconBgOpacity × 0.9` 画圆底）。复刻件贴的就是这张位图，
 * 再画一层圆底只会把用户设的底透明度盖掉。
 */
private const val TOOLBAR_ICON_CIRCLE_ALPHA_LIGHT = 150
private const val TOOLBAR_ICON_CIRCLE_ALPHA_DARK = 19
private const val TOOLBAR_ICON_ALPHA_LIGHT = 203
private const val TOOLBAR_ICON_ALPHA_DARK = 137
private val TOOLBAR_ICON_ARGB_LIGHT = 0xFF5E5E5E.toInt()
private val TOOLBAR_ICON_ARGB_DARK = 0xFFFFFFFF.toInt()

/**
 * 画工具栏那一排的七个图标格：每格一个圆底 + 正中一颗 64px 矢量图标。
 *
 * 几何全部来自 [ReplicaGeometry] 的实测值，字形来自 [ReplicaToolbarIcons]。不再贴宿主那一行的
 * 快照，跟按键、候选条一样由复刻件自己画 —— 宿主往那一行叠临时内容（剪贴板粘贴建议之类）时，
 * 预览里不会跟着变。
 *
 * 光感画在圆底之上、字形之下：真机是把边缘光包进图标自己的背景 drawable 里（见
 * `WeTypeIconEdgeLightLayer`），也就是同一个顺序。
 */
internal fun DrawScope.drawReplicaToolbar(
    geometry: ReplicaGeometry,
    isDark: Boolean,
    iconEdgeLightBitmap: Bitmap?
) {
    val radius = geometry.toolbarCircleSizePx / 2f
    val circleTop = geometry.toolbarCircleTopPx.toFloat()
    val inset = geometry.toolbarIconInsetPx.toFloat()
    val iconScale = geometry.toolbarIconSizePx.toFloat() / ReplicaToolbarIcons.VIEWPORT_PX
    val circleColor = ComposeColor.White.copy(
        alpha = (if (isDark) TOOLBAR_ICON_CIRCLE_ALPHA_DARK else TOOLBAR_ICON_CIRCLE_ALPHA_LIGHT) / 255f
    )
    val glyphColor = ComposeColor(if (isDark) TOOLBAR_ICON_ARGB_DARK else TOOLBAR_ICON_ARGB_LIGHT).copy(
        alpha = (if (isDark) TOOLBAR_ICON_ALPHA_DARK else TOOLBAR_ICON_ALPHA_LIGHT) / 255f
    )
    ReplicaToolbarIcons.paths.forEachIndexed { index, path ->
        val left = geometry.toolbarIconCircleLeftPx(index).toFloat()
        val center = Offset(left + radius, circleTop + radius)
        drawCircle(color = circleColor, radius = radius, center = center)
        drawReplicaEdgeLightBitmap(iconEdgeLightBitmap, Offset(left, circleTop))
        translate(left + inset, circleTop + inset) {
            scale(iconScale, iconScale, Offset.Zero) {
                drawPath(path, color = glyphColor)
            }
        }
    }
}
