package com.xposed.wetypehook.wetype.graphics

import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.view.View
import com.xposed.wetypehook.wetype.settings.WeTypeSettings

/**
 * 把边缘光复用到圆形图标（工具栏按钮、Logo）上。
 *
 * 图标本身是圆底且没有可用的宿主键帽半径，因此半径直接取 `min(w, h) / 2`，
 * 让光感轮廓与圆形背景重合。
 *
 * 画手有两个：ColorOS 系统组件（原生，观感是基准）与模块自绘（任何 ROM 上都可用）。
 * [wrap] 只在这里选一次，之后由 [WeTypeIconEdgeLightLayer] 按选中的那个复核。
 *
 * 图标光感受「光感设置」总控与「图标边缘光感」两级开关约束，判据统一收在 [isIconEdgeLightActive]。
 *
 * 不挂 `View.foreground`：宿主工具栏容器的前景并不参与绘制，实测光感不会被画出来。
 * 改为把边缘光直接包进图标自己的 drawable —— 工具栏包背景、Logo 包图片 drawable，
 * 随宿主自己的绘制流程一起画出来，可靠且不依赖前景语义。
 */
internal object WeTypeIconEdgeLight {

    @Volatile
    private var keyLight: WeTypeColorOsKeyLight? = null

    private val selfDrawnLight = WeTypeSelfDrawnEdgeLightCache()

    fun reset() {
        keyLight = null
        selfDrawnLight.reset()
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
        val enabled = isIconEdgeLightActive(view.context)
        val native = if (enabled) nativeLight(view) else null
        val selfDrawnEnabled = WeTypeSettings.isEdgeHighlightEnabledXposed(view.context)
        val backend = resolveEdgeLightBackend(
            enabled = enabled,
            nativeSourceAvailable = native != null,
            selfDrawnSourceEnabled = selfDrawnEnabled
        )
        val source = when (backend) {
            WeTypeEdgeLightBackend.NATIVE -> native
            // 图标自己的圆底是半透明色，真正垫在下面的是键盘面板，自绘光感按面板底色取近似。
            WeTypeEdgeLightBackend.SELF_DRAWN -> selfDrawnLight.resolve(
                view.context,
                WeTypeSettings.getCurrentBackgroundColorXposed(view.context)
            )
            WeTypeEdgeLightBackend.NONE -> null
        } ?: return null
        val dark = (view.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        return WeTypeIconEdgeLightLayer(view.context, inner, source, backend, dark)
    }

    /**
     * ColorOS 原生源。只在 ColorOS 且系统描边开关打开时才有意义——开关关掉时系统那条路
     * 不画任何东西，拿了也是白拿。
     */
    private fun nativeLight(view: View): WeTypeColorOsKeyLight? {
        if (!WeTypeSystemMaterials.isColorOsBackend() ||
            !WeTypeSystemMaterials.isNativeStrokeEnabled(view.context)
        ) {
            return null
        }
        keyLight?.let { return it }
        val created = WeTypeColorOsKeyLight.create(view.context)
        if (created != null) keyLight = created
        return created
    }
}

/**
 * 图标光感此刻是否生效：「光感设置」总控与「图标边缘光感」都开着才算。
 *
 * 建层与每帧复核必须走同一个判据，否则会出现「层还在、光已经该消失」的画法错配。
 */
internal fun isIconEdgeLightActive(context: Context): Boolean =
    WeTypeSettings.isEdgeHighlightEnabledXposed(context) &&
        WeTypeSettings.isIconEdgeLightEnabledXposed()
