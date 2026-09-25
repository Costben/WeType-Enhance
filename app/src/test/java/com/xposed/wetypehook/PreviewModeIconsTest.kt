package com.xposed.wetypehook

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.VectorPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 太阳 / 月亮是从 Tabler Icons 逐字抄来的中心线路径，`addPathNodes` 会把它解析成 `PathNode`。
 *
 * 这里守两件事：
 * 1. **解析结果的结构**——子路径数、弧的半径与扫掠方向。抄错一个数字不会报错，只会把图标
 *    静默画歪或画空，所以按上游的形状钉住。
 * 2. **渲染契约**——只描边不填充、线宽 2、圆头圆角。`fill` 一旦被填上，太阳的中心圆和月牙的
 *    缺口会糊成实心块；`stroke` 一旦丢了，整颗图标静默变空白。
 */
class PreviewModeIconsTest {

    private fun onlyPath(icon: ImageVector): VectorPath = icon.root.first() as VectorPath

    private fun nodes(icon: ImageVector) = onlyPath(icon).pathData

    private fun subpathStarts(icon: ImageVector) =
        nodes(icon).count { it is PathNode.MoveTo || it is PathNode.RelativeMoveTo }

    private fun arcs(icon: ImageVector) = nodes(icon).filterIsInstance<PathNode.RelativeArcTo>()

    private fun assertKeepsTheTwentyFourUnitGrid(icon: ImageVector) {
        assertEquals(24f, icon.defaultWidth.value, 0.001f)
        assertEquals(24f, icon.defaultHeight.value, 0.001f)
        assertEquals(24f, icon.viewportWidth, 0.001f)
        assertEquals(24f, icon.viewportHeight, 0.001f)
    }

    @Test
    fun sunKeepsTheTwentyFourUnitGrid() = assertKeepsTheTwentyFourUnitGrid(PreviewSunIcon)

    @Test
    fun moonKeepsTheTwentyFourUnitGrid() = assertKeepsTheTwentyFourUnitGrid(PreviewMoonIcon)

    @Test
    fun bothGlyphsAreStrokedAndNotFilled() {
        for (icon in listOf(PreviewSunIcon, PreviewMoonIcon)) {
            val path = onlyPath(icon)
            assertNull(path.fill)
            assertEquals(Color.Black, (path.stroke as? SolidColor)?.value)
            assertEquals(PREVIEW_MODE_ICON_STROKE_WIDTH, path.strokeLineWidth, 0.001f)
            assertEquals(StrokeCap.Round, path.strokeLineCap)
            assertEquals(StrokeJoin.Round, path.strokeLineJoin)
        }
    }

    /** 用的是 Tabler 自己的 2，不是 miuix 那套的 1.75。 */
    @Test
    fun strokeWeightIsTheUpstreamTwo() {
        assertEquals(2f, PREVIEW_MODE_ICON_STROKE_WIDTH, 0.001f)
    }

    /** 太阳 = 中心圆 + 八道射线，九条子路径。 */
    @Test
    fun sunIsARingPlusEightRays() {
        assertEquals(9, subpathStarts(PreviewSunIcon))
    }

    /** 中心圆是两条半径 4 的弧拼出来的：从 (8,12) 走 +8 到 (16,12)，再走 -8 回到起点。 */
    @Test
    fun sunCentreCircleIsTwoRadiusFourArcs() {
        val arcs = arcs(PreviewSunIcon)
        assertEquals(2, arcs.size)
        for (a in arcs) {
            assertEquals(4f, a.horizontalEllipseRadius, 0.001f)
            assertEquals(4f, a.verticalEllipseRadius, 0.001f)
            assertEquals(0f, a.arcStartDy, 0.001f)
        }
        assertEquals(8f, arcs[0].arcStartDx, 0.001f)
        assertEquals(-8f, arcs[1].arcStartDx, 0.001f)
    }

    /** 月亮 = 一条月牙，一条子路径、两段贝塞尔收头。 */
    @Test
    fun moonIsASingleClosedCrescent() {
        assertEquals(1, subpathStarts(PreviewMoonIcon))
        assertEquals(1, nodes(PreviewMoonIcon).count { it is PathNode.RelativeCurveTo })
    }

    /**
     * 月牙的两条弧半径是 7.5 与 9，**扫掠方向相反**。
     *
     * 同向的话内弧会翻到外弧外面去，画出来是个胖圆而不是月牙。
     */
    @Test
    fun moonArcsUseTheUpstreamRadiiAndOppositeSweeps() {
        val arcs = arcs(PreviewMoonIcon)
        assertEquals(2, arcs.size)
        assertEquals(7.5f, arcs[0].horizontalEllipseRadius, 0.001f)
        assertEquals(9f, arcs[1].horizontalEllipseRadius, 0.001f)
        assertTrue(arcs[0].isPositiveArc != arcs[1].isPositiveArc)
    }

    /**
     * 路径串是从上游逐字抄的，节点类型序列因此是固定的指纹——改一个字符这里就会响。
     */
    @Test
    fun nodeShapeMatchesTheUpstreamFiles() {
        assertEquals(
            listOf("MoveTo", "RelativeArcTo", "RelativeArcTo", "MoveTo", "RelativeHorizontalTo",
                   "RelativeMoveTo", "RelativeVerticalTo", "RelativeMoveTo",
                   "RelativeHorizontalTo", "RelativeMoveTo", "RelativeVerticalTo",
                   "RelativeMoveTo", "RelativeLineTo", "RelativeMoveTo", "RelativeLineTo",
                   "RelativeMoveTo", "RelativeLineTo", "RelativeMoveTo", "RelativeLineTo"),
            nodes(PreviewSunIcon).map { it::class.simpleName }
        )
        assertEquals(
            listOf("MoveTo", "RelativeCurveTo", "RelativeArcTo", "RelativeArcTo",
                   "RelativeLineTo"),
            nodes(PreviewMoonIcon).map { it::class.simpleName }
        )
    }

    /**
     * 把所有节点走一遍、累出绝对锚点。上游用的是相对指令（`m` / `h` / `v` / `l` / `a`），
     * 光看节点里的数字全是增量，量不出位置。
     */
    private fun anchors(icon: ImageVector): List<Pair<Float, Float>> {
        var cx = 0f
        var cy = 0f
        val out = mutableListOf<Pair<Float, Float>>()
        fun mark() = out.add(cx to cy)
        for (node in nodes(icon)) when (node) {
            is PathNode.MoveTo -> { cx = node.x; cy = node.y; mark() }
            is PathNode.RelativeMoveTo -> { cx += node.dx; cy += node.dy; mark() }
            is PathNode.LineTo -> { cx = node.x; cy = node.y; mark() }
            is PathNode.RelativeLineTo -> { cx += node.dx; cy += node.dy; mark() }
            is PathNode.HorizontalTo -> { cx = node.x; mark() }
            is PathNode.RelativeHorizontalTo -> { cx += node.dx; mark() }
            is PathNode.VerticalTo -> { cy = node.y; mark() }
            is PathNode.RelativeVerticalTo -> { cy += node.dy; mark() }
            is PathNode.ArcTo -> { cx = node.arcStartX; cy = node.arcStartY; mark() }
            is PathNode.RelativeArcTo -> { cx += node.arcStartDx; cy += node.arcStartDy; mark() }
            is PathNode.CurveTo -> { cx = node.x3; cy = node.y3; mark() }
            is PathNode.RelativeCurveTo -> { cx += node.dx3; cy += node.dy3; mark() }
            else -> Unit
        }
        return out
    }

    /**
     * 画布占比要跟邻居对得上。
     *
     * miuix 的 `Pin` / `Unpin` 在 24 格上占 20.0×19.8（画布的 83%），Tabler 的 sun / moon 占
     * 19.3×20.0，两者肩并肩时视觉重量一致——这是当初选这套的理由，别被后续改动破坏。
     * 只能按锚点量（弧的极值不在锚点上），所以门槛取 8 这个已知下界。
     */
    @Test
    fun glyphsStayInsideTheCanvas() {
        for (icon in listOf(PreviewSunIcon, PreviewMoonIcon)) {
            val pts = anchors(icon)
            assertTrue("no anchors parsed", pts.isNotEmpty())
            for ((x, y) in pts) {
                assertTrue("$x,$y escapes the 24 grid", x in 2f..22f && y in 2f..22f)
            }
            val w = pts.maxOf { it.first } - pts.minOf { it.first }
            val h = pts.maxOf { it.second } - pts.minOf { it.second }
            assertTrue("too narrow: $w", w >= 8f)
            assertTrue("too short: $h", h >= 8f)
        }
    }
}
