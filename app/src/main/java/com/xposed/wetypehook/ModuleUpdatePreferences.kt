package com.xposed.wetypehook

import android.content.Context
import android.content.SharedPreferences

/**
 * 模块更新检查的偏好。
 *
 * 只影响模块 App 自己的更新提示，不进输入法设置管道：输入法进程侧的 hook 不关心它，
 * 也就没必要让它跟着整套设置快照跨进程同步。
 */
internal object ModuleUpdatePreferences {

    private const val PREF_NAME = "module_update"
    private const val KEY_RECEIVE_ALPHA = "receive_alpha"

    fun isReceiveAlpha(context: Context): Boolean =
        preferences(context).getBoolean(KEY_RECEIVE_ALPHA, false)

    fun setReceiveAlpha(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(KEY_RECEIVE_ALPHA, enabled).apply()
    }

    private fun preferences(context: Context): SharedPreferences {
        val appContext = context.applicationContext ?: context
        return appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }
}
