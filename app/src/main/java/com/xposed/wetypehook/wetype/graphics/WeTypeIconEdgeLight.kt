package com.xposed.wetypehook.wetype.graphics

import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.view.View
import com.xposed.wetypehook.wetype.settings.EdgeLightTarget
import com.xposed.wetypehook.wetype.settings.WeTypeSettings

/** Which coordinate space supplies an icon's overlay geometry. */
internal enum class WeTypeIconEdgeLightTarget {
    /** The toolbar container background: cover the complete host view. */
    BACKGROUND,
    /** The Logo ImageView drawable: follow the transformed image content. */
    IMAGE_CONTENT
}

/**
 * 把边缘光复用到圆形图标（工具栏按钮、Logo）上。
 *
 * 图标本身是圆底且没有可用的宿主键帽半径，因此半径直接取 `min(w, h) / 2`，
 * 让光感轮廓与圆形背景重合。
 *
 * 画手只有一个：模块自绘，任何 ROM 上都可用。图标光感受「光感设置」总控与「图标边缘光感」
 * 两级开关约束，判据统一收在 [isIconEdgeLightActive]。
 */
internal object WeTypeIconEdgeLight {

    private val selfDrawnLight = WeTypeSelfDrawnEdgeLightCache(EdgeLightTarget.ICON)

    fun reset() {
        selfDrawnLight.reset()
    }

    /** 工具栏圆形图标：把边缘光叠在圆形背景之上。 */
    fun wrapBackground(view: View, background: Drawable?): Drawable? {
        return wrap(view, background, WeTypeIconEdgeLightTarget.BACKGROUND)
    }

    /** Logo：把边缘光叠在 Logo 图片 drawable 之上。 */
    fun wrapDrawable(view: View, drawable: Drawable?): Drawable? {
        return wrap(view, drawable, WeTypeIconEdgeLightTarget.IMAGE_CONTENT)
    }

    private fun wrap(
        view: View,
        inner: Drawable?,
        target: WeTypeIconEdgeLightTarget
    ): WeTypeIconEdgeLightLayer? {
        if (inner == null) return null
        if (inner is WeTypeIconEdgeLightLayer) return inner

        val selfDrawn = WeTypeEdgeLightSource { canvas, rect, radiusPx, dark ->
            val resolved = selfDrawnLight.resolve(
                view.context,
                WeTypeSettings.getCurrentBackgroundColorXposed(view.context)
            )
            resolved?.draw(canvas, rect, radiusPx, dark) == true
        }
        val dark = (view.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        return WeTypeIconEdgeLightLayer(
            context = view.context,
            host = inner,
            light = selfDrawn,
            dark = dark,
            target = target
        )
    }
}

/**
 * 图标光感此刻是否生效：总控与「分类 → 图标」都开着。
 *
 * 建层与每帧复核必须走同一个判据，否则会出现「层还在、光已经该消失」的画法错配。
 */
internal fun isIconEdgeLightActive(context: Context): Boolean =
    WeTypeSettings.isIconEdgeLightEnabledXposed() &&
        WeTypeSettings.isEdgeHighlightEnabledXposed(context)
