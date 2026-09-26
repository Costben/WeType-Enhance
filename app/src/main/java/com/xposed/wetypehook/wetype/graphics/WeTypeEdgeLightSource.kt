package com.xposed.wetypehook.wetype.graphics

import android.graphics.Canvas
import android.graphics.Rect

/**
 * 一处边缘光的画法：把光感画进 [rect] 这块区域。
 *
 * 两个实现可以互换，调用方只按「拿得到哪个就用哪个」选一次：
 * [WeTypeColorOsKeyLight] 是 ColorOS 系统自带的 SDF 内阴影，[WeTypeSelfDrawnEdgeLight]
 * 是模块自己的绘制引擎。后者在任何 ROM 上都建得起来，用来兜住前者在非 ColorOS 上
 * 必然为空的那条路。
 *
 * [radiusPx] 是该区域的圆角半径；[dark] 是调用方按自己那份 resources 判定的明暗。
 */
internal fun interface WeTypeEdgeLightSource {
    /** 返回是否实际完成了绘制；原生实现失败时调用方可以换用自绘源。 */
    fun draw(canvas: Canvas, rect: Rect, radiusPx: Float, dark: Boolean): Boolean
}

/** 一处边缘光最终由谁画。 */
internal enum class WeTypeEdgeLightBackend {
    /** 两个源都用不了，不画。 */
    NONE,
    /** ColorOS 系统组件的原生内阴影。 */
    NATIVE,
    /** 模块自绘的紧凑面板光感。 */
    SELF_DRAWN
}

/**
 * 选源顺序：**原生优先，拿不到退回自绘**。
 *
 * 原生源（ColorOS 的 `COUIShadowEdgeDrawable`）在真机上已经量过、观感是基准，只要拿得到
 * 就必须用它；它在非 ColorOS 上必然为 null，此时由模块自绘顶上，让「光感设置」在任何
 * ROM 上都画得出来。两个都不可用就不画。
 *
 * @param enabled 该处光感自己的总开关（「光感设置」里图标 / 按键那一组）。
 * @param nativeSourceAvailable 原生源此刻是否可用（平台判定 + 系统描边开关 + 组件加载成功）。
 * @param selfDrawnSourceEnabled 自绘源的总控（「光感设置」总开关）。
 */
internal fun resolveEdgeLightBackend(
    enabled: Boolean,
    nativeSourceAvailable: Boolean,
    selfDrawnSourceEnabled: Boolean
): WeTypeEdgeLightBackend = when {
    !enabled -> WeTypeEdgeLightBackend.NONE
    nativeSourceAvailable -> WeTypeEdgeLightBackend.NATIVE
    selfDrawnSourceEnabled -> WeTypeEdgeLightBackend.SELF_DRAWN
    else -> WeTypeEdgeLightBackend.NONE
}

/**
 * 建层时选中的源，此刻是否仍然可用。
 *
 * 判据必须与 [resolveEdgeLightBackend] 当时的那一条**完全一致**，否则会出现「层还在、
 * 源已经失效」的画法错配：原生源的可用性挂在平台与系统描边开关上，自绘源挂在「光感设置」
 * 的开关上。两者都不看的项一律传 false。
 */
internal fun isEdgeLightSourceStillValid(
    backend: WeTypeEdgeLightBackend,
    nativeSourceStillAvailable: Boolean,
    selfDrawnSourceStillEnabled: Boolean
): Boolean = when (backend) {
    WeTypeEdgeLightBackend.NATIVE -> nativeSourceStillAvailable
    WeTypeEdgeLightBackend.SELF_DRAWN -> selfDrawnSourceStillEnabled
    WeTypeEdgeLightBackend.NONE -> false
}
