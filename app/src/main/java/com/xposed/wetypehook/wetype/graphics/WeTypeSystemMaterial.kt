package com.xposed.wetypehook.wetype.graphics

import android.content.Context
import android.view.View
import com.xposed.wetypehook.wetype.settings.GlassMaterialOverrides

/**
 * 系统背景材质的统一接口。
 *
 * 目前有两种后端：HyperOS 4 的液态/磨砂材质（[WeTypeHyperMaterial]），
 * 以及 ColorOS 的原生背景模糊（[WeTypeColorOsMaterial]）。窗口层只依赖本接口，
 * 具体后端由 [WeTypeSystemMaterials.create] 按 ROM 选择。
 */
internal interface WeTypeSystemMaterial {
    fun apply(isDark: Boolean, tintColor: Int?): Boolean
    fun updateGeometry(cornerRadii: WeTypeCornerRadii)
    fun clear(force: Boolean = false)
}

/**
 * 按 ROM 选择系统材质后端，并转发与后端无关的查询。
 *
 * 选型只在首次使用时求值一次：ColorOS 通过 `ro.build.version.oplusrom` 判定，
 * 与 HyperOS 的属性（`ro.mi.os.version.code`）互斥。
 */
internal object WeTypeSystemMaterials {

    private val useColorOs by lazy { WeTypeColorOsMaterial.isPlatform() }

    fun create(view: View, overrides: GlassMaterialOverrides): WeTypeSystemMaterial =
        if (useColorOs) WeTypeColorOsMaterial(view) else WeTypeHyperMaterial(view, overrides)

    fun isAvailable(context: Context): Boolean =
        if (useColorOs) WeTypeColorOsMaterial.isAvailable(context) else WeTypeHyperMaterial.isAvailable(context)

    /** HyperOS 专属的原始玻璃参数；ColorOS 后端不使用。 */
    fun areGlassOverridesAvailable(): Boolean =
        !useColorOs && WeTypeHyperMaterial.areGlassOverridesAvailable()

    /** 当前是否为 ColorOS 后端；其原生模糊不自带面板边缘高光，需要额外叠加。 */
    fun isColorOsBackend(): Boolean = useColorOs

    /**
     * 「流光轮廓」系统开关；ColorOS 之外的平台恒为 true（模块自绘高光不受该开关约束）。
     */
    fun isNativeStrokeEnabled(context: Context): Boolean =
        if (useColorOs) WeTypeColorOsMaterial.isNativeStrokeEnabled(context) else true

    fun fallbackColor(isDark: Boolean): Int =
        if (useColorOs) WeTypeColorOsMaterial.fallbackColor(isDark) else WeTypeHyperMaterial.fallbackColor(isDark)

    fun observeAvailability(context: Context, onChanged: () -> Unit): () -> Unit =
        if (useColorOs) WeTypeColorOsMaterial.observeAvailability(context, onChanged)
        else WeTypeHyperMaterial.observeAvailability(context, onChanged)
}
