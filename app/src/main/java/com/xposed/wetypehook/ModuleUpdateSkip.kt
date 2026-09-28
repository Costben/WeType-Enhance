package com.xposed.wetypehook

import android.content.Context
import android.content.SharedPreferences

/**
 * 记住用户勾过「跳过本次更新」的那个版本号。
 *
 * 只存最新一次跳过的版本：勾上即写，取消勾选即清空。下一个版本发布后版本号不同，
 * 提示会重新出现，所以不需要按版本累积历史。
 */
internal object ModuleUpdateSkip {

    private const val PREF_NAME = "module_update_skip"
    private const val KEY_SKIPPED_VERSION = "skipped_version"

    fun isSkipped(context: Context, versionName: String): Boolean =
        preferences(context).getString(KEY_SKIPPED_VERSION, null) == versionName

    /** 传 null 表示取消跳过。 */
    fun setSkipped(context: Context, versionName: String?) {
        preferences(context).edit().apply {
            if (versionName == null) remove(KEY_SKIPPED_VERSION) else putString(KEY_SKIPPED_VERSION, versionName)
        }.apply()
    }

    private fun preferences(context: Context): SharedPreferences {
        val appContext = context.applicationContext ?: context
        return appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }
}
