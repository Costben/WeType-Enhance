package com.xposed.wetypehook.wetype.settings

import android.app.ActivityManager
import android.content.Context
import android.os.Process
import android.util.Log

/**
 * 让「保存」真正生效的那一步：把微信输入法的**输入法进程**杀掉，系统随即重新拉起。
 *
 * ## 为什么需要它
 *
 * 模块的设置全部走 Xposed hook 在运行时读取，输入法进程只在 `onStartInputView` 时
 * 取一次快照。设置页（`ImeMainSettingActivity`）与输入法本体是两个进程：
 *
 * | 进程 | 作用 |
 * | :--- | :--- |
 * | `com.tencent.wetype` | 设置页、关于页所在进程 |
 * | `com.tencent.wetype:hld` | 输入法本尊，模块 hook 在这里生效 |
 *
 * 在设置页里改的值写进了文件，但 `:hld` 里那份内存快照还是旧的 —— 所以必须重启
 * `:hld` 才会重新读取。用户看到的「没法保存」并不是真的没保存，而是**没生效**。
 *
 * ## 为什么不是 `am force-stop`
 *
 * 历史实现（`2fde42d` 之前）走的是 `su -c "am force-stop com.tencent.wetype && ..."`，
 * 要求 root，且在 LSPatch 场景下会把宿主输入法整个停掉。同一 uid 内其实不需要 root：
 * `Process.sendSignal(pid, SIGNAL_KILL)` 是公开 API，对自己的进程永远合法。
 *
 * 只杀 `:hld`，不动设置页所在的主进程 —— 面板不会当场关闭，可以接着改下一项。
 * 进程下次被系统拉起时重新读取配置，效果与「强行停止」一致。
 */
internal object WeTypeProcessRestarter {

    private const val TAG = "WeTypeRestart"
    private const val WETYPE_PACKAGE_NAME = "com.tencent.wetype"

    /** 输入法本体进程名。主进程同名不带后缀，设置页在那个进程里，不能杀。 */
    private const val IME_PROCESS_SUFFIX = ":hld"

    /**
     * 杀掉当前应用所属 uid 下、进程名以 [IME_PROCESS_SUFFIX] 结尾的进程。
     *
     * 必须是**同 uid**：`Process.sendSignal` 只对自己的 uid 有效，这正是
     * 「LSPatch 内嵌模块跑在微信输入法进程里」这一前提带来的便利 —— 模块代码
     * 本身就活在 `:hld` 的 uid 下，杀自己人无需任何权限。
     *
     * @return 是否至少成功发出了一次信号。
     */
    fun restartImeProcess(context: Context): Boolean {
        val appContext = context.applicationContext ?: context
        val myUid = Process.myUid()
        val activityManager = runCatching {
            appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        }.getOrNull()
        if (activityManager == null) {
            Log.w(TAG, "ActivityManager unavailable; cannot enumerate processes")
            return false
        }

        val targets = runCatching { activityManager.runningAppProcesses }
            .getOrNull()
            ?.filter { info ->
                info.uid == myUid &&
                    info.processName != null &&
                    info.processName.endsWith(IME_PROCESS_SUFFIX) &&
                    info.pid != Process.myPid()
            }
            .orEmpty()

        if (targets.isEmpty()) {
            Log.w(TAG, "No $WETYPE_PACKAGE_NAME$IME_PROCESS_SUFFIX process found under uid $myUid")
            return false
        }

        var signaled = false
        targets.forEach { info ->
            val sent = runCatching {
                Process.sendSignal(info.pid, Process.SIGNAL_KILL)
                true
            }.onFailure {
                Log.e(TAG, "Failed to signal pid ${info.pid} (${info.processName})", it)
            }.getOrDefault(false)
            if (sent) {
                signaled = true
                Log.i(TAG, "Signaled ${info.processName} (pid ${info.pid}) to restart")
            }
        }
        return signaled
    }
}
