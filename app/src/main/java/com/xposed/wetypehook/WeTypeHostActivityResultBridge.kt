package com.xposed.wetypehook

import android.content.Intent
import java.util.concurrent.ConcurrentHashMap

/**
 * 宿主 Activity 结果桥（WeType 进程内）。
 *
 * 嵌入设置运行在 `ComponentDialog` 中，该容器不提供 `ActivityResultRegistryOwner`，
 * 无法使用 Compose 的 ActivityResult API。这里统一注册请求码，由 MainHook 挂钩的
 * `Activity.onActivityResult` 转发结果。
 */
object WeTypeHostActivityResultBridge {

    const val REQUEST_PICK_TREE = 0x7A11
    const val REQUEST_PICK_FILE = 0x7A12

    private val callbacks = ConcurrentHashMap<Int, (resultCode: Int, data: Intent?) -> Unit>()

    fun register(requestCode: Int, callback: (resultCode: Int, data: Intent?) -> Unit) {
        callbacks[requestCode] = callback
    }

    fun unregister(requestCode: Int) {
        callbacks.remove(requestCode)
    }

    /** @return true 表示该结果已被桥接消费，宿主无需再处理。 */
    fun dispatch(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        val callback = callbacks.remove(requestCode) ?: return false
        runCatching { callback(resultCode, data) }
        return true
    }
}
