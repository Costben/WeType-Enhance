package com.xposed.wetypehook

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * 低于 API 34 时代替 `sentFromUid` 的发送方凭据。
 *
 * 跨 App 广播的发送方 uid 要到 API 34（`BroadcastOptions.setShareIdentityEnabled`）才拿得到，
 * 而 minSdk 是 31。Android 12 / 13 上不校验就等于任何 App 都能把壳开到局域网、或让模块
 * 借 Shizuku 改系统默认识别服务。
 *
 * 令牌由模块 App 生成一次、存在自己的私有偏好里，再写进 LSPosed 远端偏好的独立分组
 * [REMOTE_GROUP]。远端偏好只有模块 App 与被 hook 的作用域进程读得到，所以宿主进程能带上
 * 令牌，别的 App 拿不到。不放进设置那个分组：设置会被导出备份，令牌不能跟着出去。
 *
 * 令牌在模块 App 第一次连上 LSPosed 服务时才生成；在那之前、或框架不提供远端偏好
 * （LSPatch 内嵌）时，低版本上这两条广播一律拒收。
 */
object ModuleBridgeSecret {
    private const val TAG = "MIUIIME.ModuleBridge"

    const val EXTRA_TOKEN = "bridge_secret"
    const val REMOTE_GROUP = "module_bridge_secret"
    private const val LOCAL_PREFERENCES = "module_bridge_secret"
    private const val KEY_TOKEN = "token"
    private const val TOKEN_BYTES = 32

    @Volatile
    private var remoteProvider: (() -> SharedPreferences?)? = null

    /** 宿主进程（hook 侧）注册远端偏好的读取入口。lambda 持有 Xposed 接口，热重载时整体替换。 */
    fun bindRemoteProvider(provider: (() -> SharedPreferences?)?) {
        remoteProvider = provider
    }

    /** 模块 App 连上 LSPosed 服务后调用：保证本地有令牌，并把它推到远端偏好。 */
    fun publish(context: Context, remote: SharedPreferences) {
        val token = localToken(context)
        if (remote.getString(KEY_TOKEN, null) != token) {
            remote.edit().putString(KEY_TOKEN, token).apply()
        }
    }

    /** 给要发出的广播带上令牌；本进程拿不到令牌时原样返回。 */
    fun attach(context: Context, intent: Intent): Intent = intent.apply {
        current(context)?.let { putExtra(EXTRA_TOKEN, it) }
    }

    /**
     * 发送方是否可信：API 34+ 由调用方先按 uid 判定，[uidTrusted] 为 true 直接放行；
     * 否则比对令牌。
     */
    fun isTrusted(context: Context, intent: Intent, uidTrusted: () -> Boolean): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && uidTrusted()) {
            return true
        }
        val expected = current(context) ?: run {
            Log.w(TAG, "no bridge secret in this process; rejecting ${intent.action}")
            return false
        }
        val presented = intent.getStringExtra(EXTRA_TOKEN) ?: return false
        return MessageDigest.isEqual(expected.toByteArray(), presented.toByteArray())
    }

    private fun current(context: Context): String? =
        if (context.packageName == ModuleBridgeContract.MODULE_PACKAGE_NAME) {
            localToken(context)
        } else {
            runCatching { remoteProvider?.invoke()?.getString(KEY_TOKEN, null) }.getOrNull()
        }

    @Synchronized
    private fun localToken(context: Context): String {
        val preferences = (context.applicationContext ?: context)
            .getSharedPreferences(LOCAL_PREFERENCES, Context.MODE_PRIVATE)
        preferences.getString(KEY_TOKEN, null)?.let { return it }
        val bytes = ByteArray(TOKEN_BYTES).also { SecureRandom().nextBytes(it) }
        val token = bytes.joinToString("") { "%02x".format(it) }
        preferences.edit().putString(KEY_TOKEN, token).commit()
        return token
    }
}
