package com.xposed.wetypehook.wetype.settings

import com.xposed.wetypehook.wetype.gesture.GestureAction
import org.json.JSONObject

/**
 * 手势按键绑定配置序列化与反序列化
 *
 * ## 编号表
 *
 * `0..23` 与旧版本逐一对齐（`20` = 输入法设置、`21` = 手写找字、`22` = 滑移、`23` = 滑选），
 * 模块设置作为**新动作占用唯一未用过的 24**，全表无空洞。
 *
 * | 编号 | 动作 | 旧版本 |
 * | ---: | :--- | :--- |
 * | 0..23 | 与旧版本一致 | 同编号同动作 |
 * | 24 | `OpenModuleSettings` 模块设置 | 不存在 |
 *
 * ## 为什么不做编号搬运
 *
 * 曾经考虑过把改动过的动作整体 +1，让 21 空出来给模块设置。那会改动 `20..23` 四个动作的
 * 落盘编号，而 `gesture_bindings_json` 是随备份/WebDAV 同步的——旧设备读到新编号会解析成
 * 另一个动作。既然模块设置可以直接往后排，就没有理由动存量编号。
 *
 * 这样上下兼容是单向且干净的：
 * - 本版本读旧配置：`0..23` 原样透传，**零改写**。
 * - 旧版本读本版本配置：只有 `24` 落空变成 `None`（UI 显示「未绑定」），其余全部正常。
 *
 * 因此这里不需要任何 ID 重映射——`id` 就是落盘编号。若将来还要新增动作，继续往后接 25、26。
 */
object WeTypeGestureSettings {

    fun parseBindings(json: String?): Map<Char, GestureAction> {
        if (json.isNullOrEmpty()) return GestureAction.defaultQwertyBindings
        return runCatching {
            val obj = JSONObject(json)
            val result = mutableMapOf<Char, GestureAction>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (key.isNotEmpty()) {
                    val char = if (key.equals("space", ignoreCase = true) || key == " ") {
                        ' '
                    } else {
                        key[0].lowercaseChar()
                    }
                    val action = GestureAction.fromId(obj.getInt(key))
                    if (action != GestureAction.None) {
                        result[char] = action
                    }
                }
            }
            result
        }.getOrDefault(GestureAction.defaultQwertyBindings)
    }

    fun serializeBindings(map: Map<Char, GestureAction>): String {
        val obj = JSONObject()
        map.forEach { (char, action) ->
            if (action != GestureAction.None) {
                val key = if (char == ' ') "space" else char.lowercaseChar().toString()
                obj.put(key, action.id)
            }
        }
        return obj.toString()
    }
}
