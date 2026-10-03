package com.xposed.wetypehook

import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import android.util.Log
import moe.shizuku.server.IRemoteProcess
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Shizuku 侧的 shell 通道。
 *
 * **只能在模块 App 进程里用。** Shizuku 把 binder 递给「声明了 `rikka.shizuku.ShizukuProvider`
 * 的进程」，而设置页跑在宿主进程里，那边 `Shizuku.getBinder()` 永远是 null —— 这也是
 * [VoiceServiceBridge] 要把 Shizuku 那一步派回模块 App 的原因。
 */
internal object ShizukuShell {
    private const val TAG = "MIUIIME.Shizuku"
    private const val REQUEST_CODE = 4213
    private const val PATH_EXPORT = "PATH=/product/bin:/system/bin:/system/xbin"

    /** Shizuku 管理器（本体）的包名。 */
    const val SHIZUKU_PACKAGE_NAME = "moe.shizuku.privileged.api"

    /** Shizuku 服务本身在不在（装了但没启动时为 false）。 */
    fun isRunning(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    /** Shizuku 管理器装了没有。没装就不必等 binder —— 等满超时纯属浪费。 */
    fun isInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE_NAME, 0)
        true
    }.getOrDefault(false)

    /**
     * 等 binder 到位，最多 [timeoutMs]。
     *
     * 进程是刚被广播拉起来的、还是本来就活着，决定 binder 在不在：Shizuku 服务端把 binder
     * 推给 provider 的时机不由我们掌握，所以这里用 sticky 监听补一次等待，避免把「刚起来
     * 还没收到」误判成「Shizuku 没运行」。
     */
    fun awaitBinder(timeoutMs: Long): Boolean {
        if (isRunning()) return true
        val latch = CountDownLatch(1)
        val listener = Shizuku.OnBinderReceivedListener { latch.countDown() }
        runCatching { Shizuku.addBinderReceivedListenerSticky(listener) }.getOrElse { return false }
        return try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            isRunning()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        } finally {
            runCatching { Shizuku.removeBinderReceivedListener(listener) }
        }
    }

    fun isAuthorized(): Boolean = runCatching {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /**
     * 弹 Shizuku 的授权框。
     *
     * 结果是**异步**送达的（`Shizuku.OnRequestPermissionResultListener`），所以调用方不能在
     * 这里等 —— 授权完由用户再触发一次开关。Shizuku 自己负责弹框与落盘，我们只发请求。
     *
     * 传入 [onResult] 时会先挂监听再发请求，避免结果比监听先到。
     */
    fun requestPermission(onResult: ((granted: Boolean) -> Unit)? = null) {
        if (onResult != null) {
            val listener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
                if (requestCode != REQUEST_CODE) return@OnRequestPermissionResultListener
                onResult(grantResult == PackageManager.PERMISSION_GRANTED)
            }
            permissionListeners[onResult] = listener
            runCatching { Shizuku.addRequestPermissionResultListener(listener) }
        }
        runCatching { Shizuku.requestPermission(REQUEST_CODE) }
            .onFailure { Log.w(TAG, "requestPermission failed: ${it.javaClass.simpleName}: ${it.message}") }
    }

    /** 拆掉 [requestPermission] 挂的监听，避免 Activity 销毁后还回吐。 */
    fun clearPermissionListener(onResult: (Boolean) -> Unit) {
        permissionListeners.remove(onResult)?.let {
            runCatching { Shizuku.removeRequestPermissionResultListener(it) }
        }
    }

    private val permissionListeners = mutableMapOf<(Boolean) -> Unit, Shizuku.OnRequestPermissionResultListener>()

    /**
     * 在 Shizuku 的 shell uid（2000）下跑一条命令。
     *
     * 返回 true 仅代表退出码为 0。Shizuku 没跑、没授权、远程进程起不来一律 false，
     * 由调用方决定是退回 root 还是提示用户。
     */
    fun exec(command: String): Boolean {
        val service = service() ?: return false
        var remote: IRemoteProcess? = null
        return try {
            remote = service.newProcess(arrayOf("sh", "-c", "$PATH_EXPORT; $command"), null, null)
            val output = ParcelFileDescriptor.AutoCloseInputStream(remote.getInputStream())
                .bufferedReader().use { it.readText() }
            val code = remote.waitFor()
            if (code != 0) Log.w(TAG, "command exited $code: ${output.take(200)}")
            code == 0
        } catch (error: Throwable) {
            Log.w(TAG, "exec failed: ${error.javaClass.simpleName}: ${error.message}")
            false
        } finally {
            runCatching { remote?.destroy() }
        }
    }

    private fun service(): IShizukuService? = runCatching {
        Shizuku.getBinder()?.let { IShizukuService.Stub.asInterface(it) }
    }.getOrNull()
}
