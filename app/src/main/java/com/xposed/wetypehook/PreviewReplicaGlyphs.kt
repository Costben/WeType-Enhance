package com.xposed.wetypehook

import android.graphics.Canvas
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.LocalDensity


/** 复刻件 ⇧ 的归一化顶点（顶点 → 左肩 → 左竖杠 → 底边 → 右竖杠 → 右肩），真机键帽实测。 */
internal val REPLICA_SHIFT_OUTLINE = listOf(
    0.5f to 0f,
    0f to 0.584f,
    0.278f to 0.584f,
    0.278f to 0.987f,
    0.722f to 0.987f,
    0.722f to 0.584f,
    1f to 0.584f
)

/** 复刻件 ⌫ 的归一化五边形（左尖 → 右上 → 右下），真机键帽实测。 */
internal val REPLICA_BACKSPACE_OUTLINE = listOf(
    0f to 0.54f,
    0.3f to 0f,
    1f to 0f,
    1f to 1f,
    0.3f to 1f
)

/** ⌫ 内部那个 ×：两笔分开画，张开不闭合。 */
internal val REPLICA_BACKSPACE_CROSS = listOf(
    listOf(0.476f to 0.324f, 0.728f to 0.703f),
    listOf(0.728f to 0.324f, 0.476f to 0.703f)
)

/** 把归一化顶点映射进 [left]/[top]/[width]/[height] 框出的矩形。 */
internal fun replicaGlyphPath(
    points: List<Pair<Float, Float>>,
    left: Float,
    top: Float,
    width: Float,
    height: Float,
    close: Boolean = true
): Path = Path().apply {
    points.forEachIndexed { index, (nx, ny) ->
        val x = left + nx * width
        val y = top + ny * height
        if (index == 0) moveTo(x, y) else lineTo(x, y)
    }
    if (close) close()
}

/**
 * 描边空心画一个归一化顶点集，尺寸与笔画都取自 [ReplicaGeometry] 里的真机实测值。
 *
 * 接头固定用 [StrokeJoin.Round]：⇧ 两肩是尖角，斜接会把外沿顶出去，字形就比真机胖。
 */
@Composable
internal fun ReplicaGlyph(
    points: List<Pair<Float, Float>>,
    color: ComposeColor,
    pathWidthPx: Float,
    pathHeightPx: Float,
    strokePx: Float
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val left = (size.width - pathWidthPx) / 2f
        val top = (size.height - pathHeightPx) / 2f
        drawPath(
            path = replicaGlyphPath(points, left, top, pathWidthPx, pathHeightPx),
            color = color,
            style = Stroke(width = strokePx, join = StrokeJoin.Round)
        )
    }
}

/** 候选条末尾的展开箭头 ⌄：真机墨迹框 44×26、笔画 6、圆角接头。 */
@Composable
internal fun ReplicaMoreGlyph(color: ComposeColor, geometry: ReplicaGeometry) {
    val density = LocalDensity.current
    Canvas(
        modifier = Modifier.size(
            width = with(density) { geometry.candidateMoreWidthPx.toDp() },
            height = with(density) { geometry.candidateMoreHeightPx.toDp() }
        )
    ) {
        val stroke = geometry.candidateMoreStrokePx
        val half = stroke / 2f
        // 路径盒就是墨迹框每边内缩半个描边，外沿才正好落在 44×26 上。
        val path = Path().apply {
            moveTo(half, half)
            lineTo(size.width / 2f, size.height - half)
            lineTo(size.width - half, half)
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}

/** 真机 ⇧ 键帽字形。 */
@Composable
internal fun ReplicaShiftGlyph(color: ComposeColor, geometry: ReplicaGeometry) {
    ReplicaGlyph(
        points = REPLICA_SHIFT_OUTLINE,
        color = color,
        pathWidthPx = geometry.shiftPathWidthPx,
        pathHeightPx = geometry.shiftPathHeightPx,
        strokePx = geometry.shiftStrokePx
    )
}

/** 真机 ⌫ 键帽字形：左侧五边形外框加内部一个 ×。 */
@Composable
internal fun ReplicaBackspaceGlyph(color: ComposeColor, geometry: ReplicaGeometry) {
    ReplicaGlyph(
        points = REPLICA_BACKSPACE_OUTLINE,
        color = color,
        pathWidthPx = geometry.backspacePathWidthPx,
        pathHeightPx = geometry.backspacePathHeightPx,
        strokePx = geometry.backspaceStrokePx
    )
    Canvas(modifier = Modifier.fillMaxSize()) {
        val left = (size.width - geometry.backspacePathWidthPx) / 2f
        val top = (size.height - geometry.backspacePathHeightPx) / 2f
        val stroke = Stroke(width = geometry.backspaceCrossStrokePx)
        REPLICA_BACKSPACE_CROSS.forEach { crossLine ->
            drawPath(
                path = replicaGlyphPath(
                    crossLine,
                    left,
                    top,
                    geometry.backspacePathWidthPx,
                    geometry.backspacePathHeightPx,
                    close = false
                ),
                color = color,
                style = stroke
            )
        }
    }
}
