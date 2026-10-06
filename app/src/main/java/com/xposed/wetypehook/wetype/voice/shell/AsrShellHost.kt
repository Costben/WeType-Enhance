package com.xposed.wetypehook.wetype.voice.shell

import android.content.Context
import android.util.Log
import com.xposed.wetypehook.wetype.hook.WeTypeProcessIdentity
import com.xposed.wetypehook.wetype.settings.WeTypeSettings
import com.xposed.wetypehook.wetype.voice.WeTypeVoiceProtocol

/**
 * 在**微信输入法输入法进程**（`:hld`）里托管 [AsrShellServer]（18516）。
 *
 * ## 为什么不能放在模块 App 进程
 *
 * 壳最初跑在模块 App 进程的前台服务里，实机直接否掉了那个位置：ColorOS / OPPO 的 Hans
 * （`OplusHansManager`）会对转入后台的 uid 做 **cgroup 级 + netd eBPF 网络冻结**，连
 * loopback 一起掐 —— 日志里 `freeze uid: 10476 ... scene: LcdOn` 之后 `nc -z 127.0.0.1 18515`
 * 都不通，Eta 自然连不上。常驻通知、`deviceidle whitelist`、`set-standby-bucket active`
 * 全都拦不住。
 *
 * 微信输入法进程（`com.tencent.wetype` / `:hld`）从不被冻结，且它本来就是这条链的上游 ——
 * 壳只是 18515 回环桥的协议适配层，放在桥的这一侧才是它该在的地方。
 *
 * ## 生命周期
 *
 * 由 [com.xposed.wetypehook.wetype.hook.WeTypeVoiceHooks] 的 `startLocalServices` 在
 * `Application.attach` 之后拉起，此后由设置页的 `ACTION_SHELL_SYNC` 广播（改配置）驱动。
 * 监听只跑明文 HTTP，不涉及任何证书。
 * 输入法进程活多久，监听活多久；模块 App 进程不参与监听。
 */
internal object AsrShellHost {

    private const val TAG = ASR_SHELL_TAG

    @Volatile
    private var server: AsrShellServer? = null

    private var listeningPort = -1

    private var listeningOnLan = false

    /** 按给定配置起停监听，幂等。只允许在输入法进程里调。 */
    @Synchronized
    fun sync(context: Context, enabled: Boolean, port: Int, allowLan: Boolean) {
        if (!WeTypeProcessIdentity.isImeProcess(context)) {
            Log.i(TAG, "not the IME process; shell listener stays with the IME process")
            return
        }
        if (!enabled) {
            stop()
            return
        }
        val current = server
        if (current != null && current.isRunning &&
            port == listeningPort && allowLan == listeningOnLan
        ) {
            return
        }
        stop()
        val appContext = context.applicationContext ?: context
        start(appContext, port, allowLan)
    }

    @Synchronized
    fun stop() {
        val current = server ?: return
        server = null
        listeningPort = -1
        listeningOnLan = false
        runCatching { current.stop() }
    }

    private fun start(context: Context, port: Int, allowLan: Boolean) {
        val created = AsrShellServer(
            port = port,
            allowLan = allowLan,
            tuning = { WeTypeSettings.voiceTuning(context) },
            bridgePort = WeTypeVoiceProtocol.DEFAULT_PORT
        )
        if (created.start()) {
            server = created
            listeningPort = port
            listeningOnLan = allowLan
            Log.i(TAG, "shell listening on port $port (lan=$allowLan)")
        } else {
            // 端口被占：不改设置，只记日志。下一次 sync（用户再动一次开关/端口）会重试。
            Log.w(TAG, "shell could not listen on port $port; port may be in use")
        }
    }
}
