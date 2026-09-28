package com.xposed.wetypehook.wetype.settings

import org.json.JSONArray
import org.json.JSONObject

/**
 * 「高级参数调节」里由用户创建的光感预设。
 *
 * 它是 [EdgeLightGroup] 那几个字段的命名副本：边缘与内发光各自的开关、强度、宽度，加上三类
 * 共用的角度。创建时从一个内置目录项复制（[baseId] 记下来源，[id] 里带着它所属的阴影栈家族），
 * 之后每一项都可以在合法区间里改。
 *
 * 目录里那 32 个原生槽位（`preset-catalog.js` 的 `edgeArray` / `shadowArray`）渲染侧并不消费，
 * 所以这里不把它们搬进来假装可编辑：能安全接入绘制的只有 [EdgeLightGroup] 已经在用的这几个标量，
 * 自定义预设就只覆盖这些。
 *
 * 内置目录项本身永远是只读的——自定义预设是它的副本，不是它的覆盖层。
 */
data class AdvancedLightPreset(
    val id: String,
    val label: String,
    /** 复制来源的稳定目录 id；只用于展示与校验，不参与绘制。 */
    val baseId: String,
    val angle: Int = WeTypeSettings.DEFAULT_EDGE_LIGHT_ANGLE,
    val edgeEnabled: Boolean = true,
    val edgeIntensity: Int = WeTypeSettings.DEFAULT_EDGE_HIGHLIGHT_INTENSITY,
    val edgeWidth: Int = WeTypeSettings.DEFAULT_EDGE_LIGHT_WIDTH,
    val glowEnabled: Boolean = true,
    val glowIntensity: Int = WeTypeSettings.DEFAULT_GLOW_INTENSITY,
    val glowWidth: Int = WeTypeSettings.DEFAULT_GLOW_WIDTH
) {

    /** 夹回各滑杆的合法区间。手改过的偏好文件不会把绘制倍数顶到荒谬的值。 */
    fun normalized(): AdvancedLightPreset = copy(
        angle = angle.coerceIn(WeTypeSettings.MIN_EDGE_LIGHT_ANGLE, WeTypeSettings.MAX_EDGE_LIGHT_ANGLE),
        edgeIntensity = edgeIntensity.coerceIn(0, WeTypeSettings.MAX_EDGE_HIGHLIGHT_INTENSITY),
        edgeWidth = edgeWidth.coerceIn(
            WeTypeSettings.MIN_EDGE_LIGHT_WIDTH,
            WeTypeSettings.MAX_EDGE_LIGHT_WIDTH
        ),
        glowIntensity = glowIntensity.coerceIn(
            WeTypeSettings.MIN_GLOW_INTENSITY,
            WeTypeSettings.MAX_GLOW_INTENSITY
        ),
        glowWidth = glowWidth.coerceIn(
            WeTypeSettings.MIN_GLOW_WIDTH,
            WeTypeSettings.MAX_GLOW_WIDTH
        ),
        baseId = baseId.takeIf { MaterialPresetCatalog.find(it) != null }
            ?: MaterialPresetCatalog.DEFAULT_BACKGROUND
    )

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("label", label)
        put("baseId", baseId)
        put("angle", angle)
        put("edgeEnabled", edgeEnabled)
        put("edgeIntensity", edgeIntensity)
        put("edgeWidth", edgeWidth)
        put("glowEnabled", glowEnabled)
        put("glowIntensity", glowIntensity)
        put("glowWidth", glowWidth)
    }

    companion object {
        /** 下拉里自定义预设的展示文案：与内置目录项同列，所以带上来源。 */
        fun description(preset: AdvancedLightPreset): String =
            "${MaterialPresetCatalog.label(preset.baseId)} · 自定义"

        /**
         * 从一个内置目录项复制出一份可编辑预设；[index] 是当前已有自定义预设的序号，只用来
         * 生成稳定的 id 与默认名。
         */
        fun create(baseId: String, index: Int, angle: Int): AdvancedLightPreset {
            val base = baseId.takeIf { MaterialPresetCatalog.find(it) != null }
                ?: MaterialPresetCatalog.DEFAULT_BACKGROUND
            return AdvancedLightPreset(
                id = MaterialPresetCatalog.customPresetId(base, index),
                label = "自定义预设 ${index + 1}",
                baseId = base,
                angle = angle
            ).normalized()
        }

        /**
         * 下一个不冲突的序号。删掉中间几项之后再「增加预设」不能撞上还活着的 id——撞了的话
         * 编辑其中一份会改到另一份。
         */
        fun nextIndex(existing: List<AdvancedLightPreset>): Int {
            val used = existing.mapNotNull { it.id.substringAfterLast(':').toIntOrNull() }.toSet()
            var candidate = 0
            while (candidate in used) candidate++
            return candidate
        }

        /** 持久化编码：一个 JSON 数组，字段缺一即整项丢弃。 */
        fun encode(presets: List<AdvancedLightPreset>): String {
            val array = JSONArray()
            presets.forEach { array.put(it.toJson()) }
            return array.toString()
        }

        /** 读回来时整项校验：id 必须是自定义预设、来源必须是内置目录项。坏项直接丢掉。 */
        fun decode(text: String?): List<AdvancedLightPreset> {
            if (text.isNullOrBlank()) return emptyList()
            val array = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
            return buildList {
                for (index in 0 until array.length()) {
                    val obj = array.optJSONObject(index) ?: continue
                    val id = obj.optString("id", "")
                    val baseId = obj.optString("baseId", "")
                    if (!MaterialPresetCatalog.isCustom(id)) continue
                    if (MaterialPresetCatalog.find(baseId) == null) continue
                    add(
                        AdvancedLightPreset(
                            id = id,
                            label = obj.optString("label", "").takeIf { it.isNotEmpty() }
                                ?: "自定义预设",
                            baseId = baseId,
                            angle = obj.optInt("angle", WeTypeSettings.DEFAULT_EDGE_LIGHT_ANGLE),
                            edgeEnabled = obj.optBoolean("edgeEnabled", true),
                            edgeIntensity = obj.optInt(
                                "edgeIntensity",
                                WeTypeSettings.DEFAULT_EDGE_HIGHLIGHT_INTENSITY
                            ),
                            edgeWidth = obj.optInt(
                                "edgeWidth",
                                WeTypeSettings.DEFAULT_EDGE_LIGHT_WIDTH
                            ),
                            glowEnabled = obj.optBoolean("glowEnabled", true),
                            glowIntensity = obj.optInt(
                                "glowIntensity",
                                WeTypeSettings.DEFAULT_GLOW_INTENSITY
                            ),
                            glowWidth = obj.optInt(
                                "glowWidth",
                                WeTypeSettings.DEFAULT_GLOW_WIDTH
                            )
                        ).normalized()
                    )
                }
            }
        }
    }
}
