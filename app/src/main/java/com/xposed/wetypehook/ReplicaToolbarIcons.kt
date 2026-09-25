package com.xposed.wetypehook

import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.vector.PathParser

/**
 * 复刻件工具栏那一排图标的矢量字形。
 *
 * 宿主每个工具栏项都是「90px 圆形底 + 64px 矢量图标」，[REPLICA_TOOLBAR_ICON_PATHS] 描的就是
 * 那个 64×64 图标本身。复刻件按 [VIEWPORT_PX] 到目标边长的比例缩放后填充，所以换屏幕宽度也不用重描。
 *
 * 为什么不沿用「把宿主那一行渲染成 PNG 存盘」的老做法：那样贴上去的是**某一瞬间的整行快照**，
 * 宿主在那一行上叠临时内容（剪贴板粘贴建议之类）时，预览里出现的就不是工具栏。描成矢量以后
 * 工具栏由复刻件自己画，与按键、候选条同一条路子。
 *
 * 代价要写清楚：这里描的是当前宿主版本的图标画法。宿主换图标以后要重新描一遍 ——
 * 位置、圆形底尺寸、图标 64px 这些几何常量是稳的，只有路径数据要更新。
 */
internal object ReplicaToolbarIcons {

    /** 描图时的基准视口边长：宿主图标是 64×64 的矢量。 */
    const val VIEWPORT_PX = 64f

    /** 从左到右七个工具栏图标。解析一次就缓存，路径是常量、不需要重建。 */
    val paths: List<Path> by lazy(LazyThreadSafetyMode.NONE) {
        REPLICA_TOOLBAR_ICON_PATHS.map {
            PathParser().parsePathString(it).toPath().apply { fillType = PathFillType.EvenOdd }
        }
    }
}
