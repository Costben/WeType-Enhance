package com.xposed.wetypehook.wetype.graphics

import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.view.View
import com.xposed.wetypehook.wetype.settings.WeTypeSettings

/**
 * 把 ColorOS 逐键光感复用到圆形图标（工具栏按钮、Logo）上。
 *
 * 图标本身是圆底且没有可用的宿主键帽半径，因此半径直接取 `min(w, h) / 2`，
 * 让 SDF 轮廓与圆形背景重合。
 *
 * 不挂 `View.foreground`：宿主工具栏容器的前景并不参与绘制，实测光感不会被画出来。
 * 改为把边缘光直接包进图标自己的 drawable —— 工具栏包背景、Logo 包图片 drawable，
 * 随宿主自己的绘制流程一起画出来，可靠且不依赖前景语义。
 */
internal object WeTypeIconEdgeLight {

    @Volatile
    private var keyLight: WeTypeColorOsKeyLight? = null

    fun reset() {
        keyLight = null
    }

    /** 工具栏圆形图标：把边缘光叠在圆形背景之上。 */
    fun wrapBackground(view: View, background: Drawable?): Drawable? {
        return wrap(view, background)
    }

    /** Logo：把边缘光叠在 Logo 图片 drawable 之上。 */
    fun wrapDrawable(view: View, drawable: Drawable?): Drawable? {
        return wrap(view, drawable)
    }

    private fun wrap(view: View, inner: Drawable?): WeTypeIconEdgeLightLayer? {
        if (!WeTypeSettings.isIconEdgeLightEnabledXposed()) return null
        if (WeTypeSystemMaterials.isColorOsBackend() &&
            !WeTypeSystemMaterials.isNativeStrokeEnabled(view.context)
        ) {
            return null
        }
        val light = resolve(view) ?: return null
        val dark = (view.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        return WeTypeIconEdgeLightLayer(view.context, inner, light, dark)
    }

    private fun resolve(view: View): WeTypeColorOsKeyLight? {
        keyLight?.let { return it }
        val created = WeTypeColorOsKeyLight.create(view.context)
        if (created != null) keyLight = created
        return created
    }
}
