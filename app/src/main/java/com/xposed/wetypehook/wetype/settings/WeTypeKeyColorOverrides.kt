package com.xposed.wetypehook.wetype.settings

import org.json.JSONArray
import org.json.JSONObject

/**
 * 「更多样的按键颜色」的落盘格式：单个 JSON 字符串，随 Snapshot 一起跨进程。
 *
 * ```json
 * {"enabled":true,"keys":{"q":"brand","a":"#FF112233"},"invert":["a"]}
 * ```
 *
 * - `enabled` 是总开关。关掉只停止生效，**不删除**已调好的键帽颜色。
 * - `keys` 的值只有两种形态：字面量 `brand`（跟随品牌强调色）或 `#AARRGGBB` 自定义色。
 *   没有条目的键 = 跟随默认按键色，也就是保持宿主原有表现。
 * - `invert` 是「字符反色」的键名表，只对 `keys` 里已有的键有意义：键面字色默认按对比度
 *   自动选，列进来的键强制用另一个候选色。两种字色对比度接近时它是唯一的手动兜底。
 *
 * 存 `brand` 这个**模式**而不是当时的色值，是为了让用户改品牌色时这些键跟着变。
 */
internal object WeTypeKeyColorOverrides {

    /** 「跟随品牌色」的字面量取值，与自定义色的 `#` 前缀天然区分。 */
    const val BRAND = "brand"

    private const val FIELD_ENABLED = "enabled"
    private const val FIELD_KEYS = "keys"
    private const val FIELD_INVERT = "invert"

    // 键位 id 归一化：宿主的键 id 形如 `S2_key_q` / `S1_key_1` / `S12_key_symbol_3`，
    // 前缀是具体布局号（S2 中文 26 键、S3 英文 26 键、S17 五笔、S28 手写找字…）。
    // 同一颗字母键在不同布局里前缀不同，所以必须剥掉前缀，否则用户在 26 键页设定的 Q
    // 换个输入方案就失效。函数键的 id 本身就是全布局共用的名字，原样保留。
    private val LETTER_OR_DIGIT = Regex("^s\\d+_key_([a-z0-9])$")
    private val SYMBOL = Regex("^s\\d+_key_symbol_(\\d+)$")

    /**
     * 把一个宿主键 id 归一成存储用的 token；认不出来则返回 `null`（该键不参与覆盖）。
     *
     * 语言切换键在中英文布局里叫 `chSwitch` / `enSwitch`，语义是同一颗键，统一成 `lang`。
     */
    fun tokenOf(rawId: String?): String? {
        val id = rawId?.trim()?.lowercase() ?: return null
        if (id.isEmpty()) return null
        LETTER_OR_DIGIT.find(id)?.let { return it.groupValues[1] }
        SYMBOL.find(id)?.let { return "sym" + it.groupValues[1] }
        if (id.endsWith("recycleview")) return "recycle"
        return when (id) {
            "chswitch", "enswitch" -> "lang"
            else -> id
        }
    }

    fun decode(json: String?): PerKeyColorOverrides {
        if (json.isNullOrEmpty()) return PerKeyColorOverrides()
        return runCatching {
            val obj = JSONObject(json)
            val keysObject = obj.optJSONObject(FIELD_KEYS)
            val colors = buildMap {
                keysObject?.keys()?.forEach { token ->
                    val value = keysObject.optString(token).takeIf { it.isNotEmpty() }
                        ?: return@forEach
                    if (value == BRAND || value.startsWith("#")) put(token, value)
                }
            }
            val invertArray = obj.optJSONArray(FIELD_INVERT) ?: JSONArray()
            val invert = buildSet {
                for (index in 0 until invertArray.length()) {
                    val token = invertArray.optString(index)
                    if (token.isNotEmpty() && token in colors) add(token)
                }
            }
            PerKeyColorOverrides(
                enabled = obj.optBoolean(FIELD_ENABLED, false),
                colors = colors,
                invert = invert
            )
        }.getOrDefault(PerKeyColorOverrides())
    }

    fun encode(overrides: PerKeyColorOverrides): String {
        if (overrides.colors.isEmpty() && !overrides.enabled) return ""
        val keysObject = JSONObject()
        overrides.colors.forEach { (token, value) -> keysObject.put(token, value) }
        val result = JSONObject()
            .put(FIELD_ENABLED, overrides.enabled)
            .put(FIELD_KEYS, keysObject)
        // 反色只对已设色的键有意义，写出去之前与 keys 取一次交集 —— 清掉某键颜色之后，
        // 不该留下一条永远读不回来的反色记录。
        val inverted = overrides.invert.filter { it in overrides.colors }
        if (inverted.isNotEmpty()) {
            result.put(FIELD_INVERT, JSONArray(inverted))
        }
        return result.toString()
    }
}

/**
 * @param enabled 「更多样的按键颜色」总开关。
 * @param colors token → `brand` 或 `#AARRGGBB`。
 * @param invert 强制反色的 token；只可能是 [colors] 的子集。
 */
internal data class PerKeyColorOverrides(
    val enabled: Boolean = false,
    val colors: Map<String, String> = emptyMap(),
    val invert: Set<String> = emptySet()
) {
    fun withColor(token: String, value: String?): PerKeyColorOverrides = copy(
        colors = if (value == null) colors - token else colors + (token to value),
        invert = if (value == null) invert - token else invert
    )

    fun withInvert(token: String, invert: Boolean): PerKeyColorOverrides = copy(
        invert = if (invert) this.invert + token else this.invert - token
    )
}
