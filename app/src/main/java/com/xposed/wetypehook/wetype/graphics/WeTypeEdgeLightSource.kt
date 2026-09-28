package com.xposed.wetypehook.wetype.graphics

import android.graphics.Canvas
import android.graphics.Rect

/**
 * 一处边缘光的画法：把光感画进 [rect] 这块区域。
 *
 * 目前只有 [WeTypeSelfDrawnEdgeLight] 一个实现——模块自己的绘制引擎，在任何 ROM 上都建得起来。
 *
 * [radiusPx] 是该区域的圆角半径；[dark] 是调用方按自己那份 resources 判定的明暗。
 */
internal fun interface WeTypeEdgeLightSource {
    /** 返回是否实际完成了绘制。 */
    fun draw(canvas: Canvas, rect: Rect, radiusPx: Float, dark: Boolean): Boolean
}

/** 一处边缘光最终由谁画。 */
internal enum class WeTypeEdgeLightBackend {
    /** 源用不了，不画。 */
    NONE,
    /** 模块自绘的紧凑面板光感。 */
    SELF_DRAWN
}

/**
 * 选源：只有自绘一个源，判据就是「这一处的总开关开着」且「自绘源本身可用」。
 *
 * @param enabled 该处光感自己的总开关（「光感设置」里图标 / 按键那一组）。
 * @param selfDrawnSourceEnabled 自绘源的总控（「光感设置」总开关）。
 */
internal fun resolveEdgeLightBackend(
    enabled: Boolean,
    selfDrawnSourceEnabled: Boolean
): WeTypeEdgeLightBackend = when {
    !enabled -> WeTypeEdgeLightBackend.NONE
    selfDrawnSourceEnabled -> WeTypeEdgeLightBackend.SELF_DRAWN
    else -> WeTypeEdgeLightBackend.NONE
}

/**
 * 建层时选中的源，此刻是否仍然可用。
 *
 * 判据必须与 [resolveEdgeLightBackend] 当时的那一条**完全一致**，否则会出现「层还在、
 * 源已经失效」的画法错配。自绘源挂在「光感设置」的开关上。
 */
internal fun isEdgeLightSourceStillValid(
    backend: WeTypeEdgeLightBackend,
    selfDrawnSourceStillEnabled: Boolean
): Boolean = when (backend) {
    WeTypeEdgeLightBackend.SELF_DRAWN -> selfDrawnSourceStillEnabled
    WeTypeEdgeLightBackend.NONE -> false
}
