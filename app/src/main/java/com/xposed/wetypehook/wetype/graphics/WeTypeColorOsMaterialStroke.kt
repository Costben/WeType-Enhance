package com.xposed.wetypehook.wetype.graphics

import android.graphics.Color
import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.Shader
import android.view.View
import com.xposed.wetypehook.xposed.Log

/**
 * ColorOS 原生「材质边缘光 + 内阴影」后端。
 *
 * 之前尝试用 `COUIShadowEdgeDrawable` 自绘流光轮廓，实测在这台设备（ColorOS V17 / SDK 37）
 * 上无法产生可见光效：该 Drawable 的 `draw()` 用 `canvas.drawPaint()` 铺满画布，既无视
 * `Drawable.bounds` 偏移，其 `BlendMode.PLUS` 在硬件画布上也不生效，会整片盖黑或完全不可见。
 *
 * 系统真正的通道是 **RenderNode 材质参数**，官方实现见小布输入法 `COUIMaterialStrokeEffect`
 * （`com.coui.appcompat`）：只下发 `setEdgeParams` + `setShadowParams` 两项，
 * 最终落到 `RenderNode.setOplusMaterialEdgeParams(type, lineWidth, lineAlpha, lineAngle)` 与
 * `RenderNode.setOplusMaterialShadowParams(type, fadeWidthScale, fadeInAlpha, fadeOutAlpha)`。
 * 它不调用 `setBaseParams` / `setCornerParams`，也不调用 `RenderNode.setOplusMaterialEffect`。
 *
 * 参数取自系统原生预设 `FRAMEWORK_CAPSULE_PARAMS_1`（light / dark），并按模块设置线性缩放：
 * 边缘线宽随「边缘光效」卡片里的宽度、边缘线透明度与内阴影淡入 alpha 随该卡片里的强度缩放，
 * 光照角度随同一张卡片里的角度下发。这三个参数都只作用于原生后端，与模块自绘的
 * [WeTypeBloomStrokeDrawable] 各自独立。
 *
 * 这些类位于 `oplus-framework.jar`（boot classpath），第三方进程可反射调用；任何一步失败都
 * 只记日志、返回 false，不影响输入法主流程。
 */
internal object WeTypeColorOsMaterialStroke {

    private const val MATERIAL_UTIL = "com.oplus.view.material.OplusMaterialUtil"
    private const val EDGE_PARAMS = "com.oplus.view.material.OplusMaterialEdgeParams"
    private const val SHADOW_PARAMS = "com.oplus.view.material.OplusMaterialShadowParams"
    private const val CORNER_PARAMS = "com.oplus.view.material.OplusMaterialCornerParams"
    private const val BASE_PARAMS = "com.oplus.view.material.OplusMaterialBaseParams"
    private const val BACKDROP_EFFECT = "com.oplus.view.OplusViewBackgroundRenderEffect"
    private const val OPLUS_VIEW = "com.oplus.view.OplusView"

    /** `OplusMaterialEdgeParams.EDGE_TYPE_RECTANGLE`：矩形（非圆/方）边缘光。 */
    private const val EDGE_TYPE_RECTANGLE = 1

    /** 边缘线宽的安全区间（dp）。设置层已裁剪，这里只兜住异常入参。 */
    private const val MIN_EDGE_WIDTH_DP = 0.5f
    private const val MAX_EDGE_WIDTH_DP = 12f

    /**
     * 强度缩放上限，与设置层 `MAX_EDGE_HIGHLIGHT_INTENSITY` 的 100 对应，
     * 由 `WeTypeWindowHooks` 的 `COLOROS_EDGE_INTENSITY_GAIN` 把滑杆值放大到这里。
     */
    private const val MAX_INTENSITY_SCALE = 4f

    /** 内阴影淡入 alpha 相对强度的放大倍数（原生预设值只有 0.01，直接乘强度几乎看不出）。 */
    private const val SHADOW_FADE_IN_GAIN = 12f

    /** 内阴影扩散宽度的基数与随强度的增量。 */
    private const val SHADOW_FADE_SCALE_FLOOR = 0.5f
    private const val SHADOW_FADE_SCALE_GAIN = 0.6f

    /** 节点级背板模糊半径，与 ColorOS 材质模糊预设（150px）保持一致。 */
    private const val BACKDROP_BLUR_PX = 150f

    // FRAMEWORK_CAPSULE_PARAMS_1_LIGHT
    private const val LIGHT_EDGE_ALPHA = 0.2f
    private const val LIGHT_SHADOW_FADE_IN = 0.1f
    private const val LIGHT_SHADOW_FADE_OUT = 1.0f
    private const val LIGHT_SHADOW_FADE_SCALE = 1.15f

    // FRAMEWORK_CAPSULE_PARAMS_1_DARK
    private const val DARK_EDGE_ALPHA = 0.6f
    private const val DARK_SHADOW_FADE_IN = 0.01f
    private const val DARK_SHADOW_FADE_OUT = 1.0f
    private const val DARK_SHADOW_FADE_SCALE = 1.0f

    /**
     * 给 [view] 下发原生材质边缘光与内阴影。`intensityScale` 为 1.0 表示 100% 强度，
     * `angleDegrees` 为光照方向角（度），`edgeWidthDp` 为边缘线宽（dp）。
     */
    fun apply(
        view: View,
        isDark: Boolean,
        intensityScale: Float,
        angleDegrees: Float,
        edgeWidthDp: Float,
        cornerRadiusPx: Float,
        maskColor: Int
    ): Boolean = runCatching {
        val util = Class.forName(MATERIAL_UTIL)
        val edgeClass = Class.forName(EDGE_PARAMS)
        val shadowClass = Class.forName(SHADOW_PARAMS)
        val density = view.resources.displayMetrics.density
        val scale = intensityScale.coerceIn(0f, MAX_INTENSITY_SCALE)

        // 平台在 nSetOplusMaterialEdgeParams 之前会读 RenderNode 的 layerType，只有
        // LAYER_TYPE_HARDWARE 才会走到 OplusMaterialFilterDrawable::draw；软件图层下
        // 参数照写但完全不出像素。
        if (view.layerType != View.LAYER_TYPE_HARDWARE) {
            view.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        }

        // 关键前提：HWUI 只在 RenderNode 挂有 background RenderEffect 时才为该节点建立
        // 材质滤镜。窗口级 `setBackgroundBlurRadius` 与自绘 BackgroundBlurDrawable 都不算，
        // 只下发 edge/shadow 参数不会有任何绘制。
        // `setShadowClippingEnabled(true)` 与背板成对出现（系统 `applyBlur` 的同款顺序），
        // 缺它时材质滤镜照建但边缘光/内阴影不参与绘制。
        setShadowClippingEnabled(view)
        attachBackdrop(view, BACKDROP_BLUR_PX)

        // 材质滤镜绘制在节点内容之下：载体自身背景必须透明，否则边缘光/内阴影被完全遮住。
        // 放在材质滤镜重建成功之后才生效——重建失败时保留原有背景，避免键盘整片变透明。

        val edgeAlpha = (if (isDark) DARK_EDGE_ALPHA else LIGHT_EDGE_ALPHA) * scale
        val widthDp = edgeWidthDp.coerceIn(MIN_EDGE_WIDTH_DP, MAX_EDGE_WIDTH_DP)
        // 边缘线 alpha 会在 1.5 左右被平台吃满（实测 alpha 3.6 与 5.4 出像素完全相同），
        // 所以「强度」不能只压在 alpha 上：内阴影的淡入 alpha 与扩散宽度还有很大余量，
        // 让它们跟着强度一起长，滑杆才在整段范围内都有可见响应。
        val fadeIn = (if (isDark) DARK_SHADOW_FADE_IN else LIGHT_SHADOW_FADE_IN) * scale * SHADOW_FADE_IN_GAIN
        val fadeOut = if (isDark) DARK_SHADOW_FADE_OUT else LIGHT_SHADOW_FADE_OUT
        val fadeScale = (if (isDark) DARK_SHADOW_FADE_SCALE else LIGHT_SHADOW_FADE_SCALE) *
            (SHADOW_FADE_SCALE_FLOOR + scale * SHADOW_FADE_SCALE_GAIN)
        val edgeType = EDGE_TYPE_RECTANGLE
        val shadowType = EDGE_TYPE_RECTANGLE
        val radius = cornerRadiusPx

        // 轮廓与材质源：COUI 的描边 effect 依赖 corner 参数确定绘制轮廓、base mask 提供
        // 材质底色。只下发 edge/shadow 时平台画不出可用的材质面。
        setCornerParams(view, radius)
        setBaseParams(view, maskColor)

        val edge = edgeClass.getConstructor(
            Int::class.javaPrimitiveType,
            Float::class.javaPrimitiveType,
            Float::class.javaPrimitiveType,
            Float::class.javaPrimitiveType
        ).newInstance(edgeType, widthDp * density, edgeAlpha, angleDegrees)

        val shadow = shadowClass.getConstructor(
            Int::class.javaPrimitiveType,
            Float::class.javaPrimitiveType,
            Float::class.javaPrimitiveType,
            Float::class.javaPrimitiveType
        ).newInstance(shadowType, fadeScale, fadeIn, fadeOut)

        val edgeOk = util.getMethod("setEdgeParams", View::class.java, edgeClass)
            .invoke(null, view, edge) as? Boolean ?: false
        val shadowOk = util.getMethod("setShadowParams", View::class.java, shadowClass)
            .invoke(null, view, shadow) as? Boolean ?: false
        // 关键：背板模糊建立时就已经创建过材质滤镜，之后 setEdgeParams/setShadowParams
        // 只会把参数写进 RenderProperties，已存在的滤镜不会重建（参数被丢弃）。
        // 因此先关掉滤镜把缓存清空，再重新打开，让边缘光/内阴影进入新建的滤镜。
        val cleared = setMaterialEffect(view, false)
        val rebuilt = setMaterialEffect(view, true)
        if (rebuilt) {
            view.setBackgroundColor(Color.TRANSPARENT)
        }
        Log.i(
            "MaterialStroke apply edge=$edgeOk shadow=$shadowOk cleared=$cleared rebuilt=$rebuilt " +
                "w=${view.width} h=${view.height} r=$radius alpha=$edgeAlpha " +
                "width=${widthDp}dp angle=$angleDegrees type=$edgeType/$shadowType " +
                "fadeIn=$fadeIn fadeScale=$fadeScale " +
                "attached=${view.isAttachedToWindow} layer=${view.layerType}"
        )
        edgeOk && shadowOk
    }.getOrElse {
        Log.e("Failed: Apply ColorOS material stroke")
        Log.e(it)
        false
    }

    /** `OplusView.setShadowClippingEnabled(View, true)`：材质边缘光/内阴影的绘制开关。 */
    private fun setShadowClippingEnabled(view: View): Boolean = try {
        Class.forName(OPLUS_VIEW)
            .getMethod("setShadowClippingEnabled", View::class.java, Boolean::class.javaPrimitiveType)
            .invoke(null, view, true)
        true
    } catch (t: Throwable) {
        Log.e("MaterialStroke shadowClipping failed: ${t.javaClass.name}: ${t.message}")
        false
    }

    /**
     * 下发材质圆角轮廓（`OplusMaterialUtil.setCornerParams`）。
     *
     * 权重必须与面板轮廓用同一条曲线（[WETYPE_COLOROS_SMOOTH_WEIGHT]）：HWUI 的
     * `OplusMaterialEffectShader::smoothSdfD` 在 `weight < 2` 时走 `sdCapsule` 分支，半径恒为
     * 节点短边的一半、`radius` 参数被丢弃（整块键盘被画成巨型胶囊弧）；`weight > 2` 是超椭圆角，
     * 与正圆弧面板相差最多 26px（33dp@620dpi 实测）。两者都会让边缘光吃不到面板四角。
     */
    private fun setCornerParams(view: View, radiusPx: Float): Boolean = try {
        val util = Class.forName(MATERIAL_UTIL)
        val cornerClass = Class.forName(CORNER_PARAMS)
        val params = cornerClass
            .getConstructor(Float::class.javaPrimitiveType, Float::class.javaPrimitiveType)
            .newInstance(radiusPx, WETYPE_COLOROS_SMOOTH_WEIGHT)
        util.getMethod("setCornerParams", View::class.java, cornerClass)
            .invoke(null, view, params) as? Boolean ?: false
    } catch (t: Throwable) {
        Log.e("MaterialStroke corner failed: ${t.javaClass.name}: ${t.message}")
        false
    }

    /** 下发材质底色（`OplusMaterialUtil.setBaseParams`，mask + innerBounds）。 */
    private fun setBaseParams(view: View, maskColor: Int): Boolean = try {
        val util = Class.forName(MATERIAL_UTIL)
        val baseClass = Class.forName(BASE_PARAMS)
        val params = baseClass
            .getConstructor(Int::class.javaPrimitiveType, Rect::class.java)
            .newInstance(maskColor, Rect(0, 0, view.width, view.height))
        util.getMethod("setBaseParams", View::class.java, baseClass)
            .invoke(null, view, params) as? Boolean ?: false
    } catch (t: Throwable) {
        Log.e("MaterialStroke base failed: ${t.javaClass.name}: ${t.message}")
        false
    }

    /**
     * 给 [view] 挂上**节点级** background RenderEffect（ColorOS 材质模糊的真实入口，
     * 见小布输入法 `COUIMaterialBlurEffect`：`OplusViewBackgroundRenderEffect
     * .setBackgroundRenderEffect(blurEffect, view)`）。
     *
     * 这是原生材质边缘光/内阴影的必要前提：只有存在 background RenderEffect 时，平台才会为该
     * RenderNode 建立 `OplusMaterialFilterDrawable`，`OplusMaterialUtil` 下发的
     * edge/shadow 参数才会参与绘制。仅靠 `ViewRootManager` 的窗口级背景模糊不满足该前提。
     */
    fun attachBackdrop(view: View, blurRadiusPx: Float): Boolean = runCatching {
        val cls = Class.forName(BACKDROP_EFFECT)
        val effect = buildBackdropEffect(blurRadiusPx)
        cls.getMethod("setBackgroundRenderEffect", RenderEffect::class.java, View::class.java)
            .invoke(null, effect, view)
        Log.i("MaterialStroke backdrop attached r=$blurRadiusPx")
        true
    }.getOrElse {
        Log.e("Failed: Attach ColorOS material backdrop")
        Log.e(it)
        false
    }

    /** 节点级背板模糊。 */
    private fun buildBackdropEffect(radiusPx: Float): RenderEffect =
        RenderEffect.createBlurEffect(radiusPx, radiusPx, Shader.TileMode.MIRROR)

    /**
     * 打开/关闭 View 的 RenderNode 材质总开关（`RenderNode.setOplusMaterialEffect`）。
     * `OplusMaterialUtil` 没有暴露这个入口，只能自己从 `View.getViewWrapper().getRenderNode()`
     * 拿到 RenderNode 再反射调用。返回 false 表示状态未变化（例如材质滤镜已存在）。
     */
    private fun setMaterialEffect(view: View, enable: Boolean): Boolean = try {
        val wrapper = View::class.java.getMethod("getViewWrapper").invoke(view)
        val renderNode = wrapper?.javaClass?.getMethod("getRenderNode")?.invoke(wrapper)
        if (renderNode == null) {
            false
        } else {
            Class.forName("android.graphics.RenderNode")
                .getMethod("setOplusMaterialEffect", Boolean::class.javaPrimitiveType)
                .invoke(renderNode, enable) as? Boolean ?: false
        }
    } catch (t: Throwable) {
        Log.e("MaterialStroke effect failed: ${t.javaClass.name}: ${t.message}")
        false
    }

    /** 清空 [view] 上的原生材质边缘光与内阴影。 */
    fun clear(view: View) {
        runCatching {
            setMaterialEffect(view, false)
            detachBackdrop(view)
            if (view.layerType == View.LAYER_TYPE_HARDWARE) {
                view.setLayerType(View.LAYER_TYPE_NONE, null)
            }
            val util = Class.forName(MATERIAL_UTIL)
            val edgeClass = Class.forName(EDGE_PARAMS)
            val shadowClass = Class.forName(SHADOW_PARAMS)
            util.getMethod("setEdgeParams", View::class.java, edgeClass).invoke(null, view, null)
            util.getMethod("setShadowParams", View::class.java, shadowClass).invoke(null, view, null)
        }.onFailure {
            Log.e("Failed: Clear ColorOS material stroke")
            Log.e(it)
        }
    }

    /** 摘掉节点级 background RenderEffect，让 HWUI 释放材质滤镜。 */
    fun detachBackdrop(view: View) {
        runCatching {
            Class.forName(BACKDROP_EFFECT)
                .getMethod("setBackgroundRenderEffect", RenderEffect::class.java, View::class.java)
                .invoke(null, null, view)
        }.onFailure {
            Log.e("Failed: Detach ColorOS material backdrop")
            Log.e(it)
        }
    }
}
