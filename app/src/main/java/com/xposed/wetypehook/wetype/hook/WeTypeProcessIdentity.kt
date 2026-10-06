package com.xposed.wetypehook.wetype.hook

import android.app.Application
import android.content.ComponentName
import android.content.Context

/**
 * 判断当前代码跑在微信输入法的哪个进程里。
 *
 * 微信输入法有两个进程（主进程与 `:hld`），两个都在作用域里、都会装同一套 hook。凡是
 * 「只该在跑着输入法的那个进程里做」的事（回环桥 18515、壳监听 18516），都必须先过这里，
 * 否则两个进程会抢同一个端口，谁赢纯看启动顺序，而只有输入法进程才有活的录音器与会话。
 */
internal object WeTypeProcessIdentity {

    /** 输入法服务。只用来问系统「输入法跑在哪个进程」，不 hook 它。 */
    private const val IME_SERVICE_CLASS = "com.tencent.wetype.plugin.hld.WxHldService"

    /**
     * 本进程是不是**输入法进程**。
     *
     * 判据不写死 `:hld`：直接问 PackageManager 输入法服务的 `processName`，由系统替我们
     * 解析出进程名。查不到就按「是」处理 —— 宁可回到原来的抢端口行为，也不要因为一次
     * 查询失败把功能关死。
     */
    fun isImeProcess(context: Context): Boolean {
        val info = runCatching {
            context.packageManager.getServiceInfo(
                ComponentName(context.packageName, IME_SERVICE_CLASS), 0
            )
        }.getOrNull() ?: return true
        val current = runCatching { Application.getProcessName() }.getOrNull() ?: return true
        return info.processName == current
    }
}
