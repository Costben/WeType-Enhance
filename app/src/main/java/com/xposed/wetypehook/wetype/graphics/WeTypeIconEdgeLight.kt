package com.xposed.wetypehook.wetype.graphics

import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.view.View
import com.xposed.wetypehook.wetype.settings.EdgeLightTarget
import com.xposed.wetypehook.wetype.settings.WeTypeSettings
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Which coordinate space supplies an icon's native overlay geometry. */
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
 * 画手有两个：ColorOS 系统原生 RenderNode 材质与模块自绘（任何 ROM 上都可用）。
 * 图标光感受「光感设置」总控与「图标边缘光感」两级开关约束，判据统一收在 [isIconEdgeLightActive]。
 */
internal object WeTypeIconEdgeLight {

    private val iconLightByView = WeakHashMap<
        View,
        MutableMap<WeTypeIconEdgeLightTarget, WeTypeColorOsKeyLight>
    >()

    private val selfDrawnLight = WeTypeSelfDrawnEdgeLightCache(EdgeLightTarget.ICON)

    fun reset() {
        synchronized(iconLightByView) {
            iconLightByView.clear()
        }
        selfDrawnLight.reset()
        WeTypeNativeEdgeLightManager.reset()
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

        val native = nativeLight(view, target)
        val selfDrawn = WeTypeEdgeLightSource { canvas, rect, radiusPx, dark ->
            val resolved = selfDrawnLight.resolve(
                view.context,
                WeTypeSettings.getCurrentBackgroundColorXposed(view.context)
            )
            resolved?.draw(canvas, rect, radiusPx, dark) == true
        }
        val enabled = isIconEdgeLightActive(view.context)
        val selfDrawnEnabled = WeTypeSettings.isEdgeHighlightEnabledXposed(view.context)
        val nativeAvailable = native != null &&
            WeTypeSystemMaterials.isColorOsBackend() &&
            WeTypeSystemMaterials.isNativeStrokeEnabled(view.context) &&
            WeTypeSettings.isSystemMaterialEnabledXposed() &&
            WeTypeSettings.isNativeEdgeLightEnabledXposed() &&
            !WeTypeSettings.isEdgeHighlightEnabledXposed(view.context)
        val backend = resolveEdgeLightBackend(
            enabled = enabled,
            nativeSourceAvailable = nativeAvailable,
            selfDrawnSourceEnabled = selfDrawnEnabled
        )
        val dark = (view.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        return WeTypeIconEdgeLightLayer(
            context = view.context,
            host = inner,
            light = native ?: selfDrawn,
            backend = backend,
            dark = dark,
            fallback = selfDrawn,
            hostViewRef = WeakReference(view),
            nativeSource = native,
            target = target
        )
    }

    /**
     * ColorOS 原生源，按宿主图标 View 弱引用缓存，支持 RenderNode 材质覆盖层。
     */
    private fun nativeLight(
        view: View,
        target: WeTypeIconEdgeLightTarget
    ): WeTypeColorOsKeyLight? {
        if (!WeTypeSystemMaterials.isColorOsBackend()) {
            return null
        }
        synchronized(iconLightByView) {
            val byTarget = iconLightByView.getOrPut(view) { mutableMapOf() }
            byTarget[target]?.let { return it }
            val created = WeTypeColorOsKeyLight.forIconView(view, target)
            if (created != null) {
                byTarget[target] = created
            }
            return created
        }
    }

}

/**
 * 图标光感此刻是否生效：「光感设置」总控与「图标边缘光感」都开着才算。
 *
 * 建层与每帧复核必须走同一个判据，否则会出现「层还在、光已经该消失」的画法错配。
 */
internal fun isIconEdgeLightActive(context: Context): Boolean =
    WeTypeSettings.isIconEdgeLightEnabledXposed() &&
        (WeTypeSettings.isEdgeHighlightEnabledXposed(context) ||
            (WeTypeSettings.isSystemMaterialEnabledXposed() &&
                WeTypeSettings.isNativeEdgeLightEnabledXposed()))
