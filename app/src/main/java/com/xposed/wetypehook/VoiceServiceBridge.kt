package com.xposed.wetypehook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import android.util.Log

/**
 * 宿主进程 → 模块 App 的「代写 `Settings.Secure`」通道。
 *
 * ## 为什么不能就地写
 *
 * 写 `voice_recognition_service` 要 shell 权限，而本机拿 shell 权限的两条路在宿主进程里
 * 都走不通：
 *
 * - **root**：能走，但那要用户先给过授权，且不是每台机器都有。
 * - **Shizuku**：binder 只递给「声明了 `rikka.shizuku.ShizukuProvider` 的进程」，也就是
 *   模块 App 自己。宿主进程里 `Shizuku.getBinder()` 恒为 null，反射
 *   `ServiceManager.getService("shizuku")` 又会撞上 targetSdk 35 的 hidden API 限制。
 *
 * 所以顺序反过来：**先在模块 App 里试 Shizuku，不行再回宿主走 root**。这条通道是前半段。
 *
 * ## 为什么用广播而不是别的
 *
 * 结果必须是**同步拿到**的 —— 宿主得知道 Shizuku 成没成，才知道要不要接着走 root。用广播
 * 发出去、在 IO 线程上等回执，既不用猜等待时间，也不会卡住主线程（回执走主线程投递）。
 */
internal object VoiceServiceBridge {

    /** 模块 App 那一步的结论。 */
    enum class Channel { APPLIED, NEEDS_PERMISSION, UNAVAILABLE, FAILED }

    private const val TAG = "MIUIIME.VoiceBridge"
    private const val MODULE_PACKAGE_NAME = "com.xposed.wetypehook"
    private const val DEFAULT_TIMEOUT_MILLIS = 6_000L

    private val lock = Object()
    private var receiverRegistered = false

    @Volatile
    private var pendingChannel: String? = null

    /**
     * 请模块 App 代写一次。
     *
     * 返回 null 表示回执没等到（模块 App 起不来、被系统拦了、或者超时）—— 与
     * [Channel.UNAVAILABLE] 一样按「这条路不通」处理，调用方继续走 root。
     *
     * **必须在非主线程调用**：它会阻塞到回执到达或超时。
     */
    fun request(context: Context, enable: Boolean, timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS): Channel? {
        ensureRegistered(context)
        synchronized(lock) { pendingChannel = null }
        val sent = ModuleBridgeContract.sendWithIdentity(
            context,
            ModuleBridgeContract.voiceServiceRequestIntent(enable)
        )
        if (!sent) {
            Log.w(TAG, "failed to dispatch the voice service request")
            return Channel.UNAVAILABLE
        }
        synchronized(lock) {
            val deadline = SystemClock.elapsedRealtime() + timeoutMillis
            while (pendingChannel == null) {
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining <= 0) break
                runCatching { lock.wait(remaining) }
            }
            val channel = pendingChannel
            if (channel == null) {
                Log.w(TAG, "timed out waiting for the voice service result")
                return null
            }
            return when (channel) {
                ModuleBridgeContract.VOICE_CHANNEL_APPLIED -> Channel.APPLIED
                ModuleBridgeContract.VOICE_CHANNEL_NEEDS_PERMISSION -> Channel.NEEDS_PERMISSION
                ModuleBridgeContract.VOICE_CHANNEL_UNAVAILABLE -> Channel.UNAVAILABLE
                else -> Channel.FAILED
            }
        }
    }

    /**
     * 注册一次回执接收器。
     *
     * 回执从模块 App（另一个 uid）发来，所以必须 `RECEIVER_EXPORTED`；发送方身份在
     * `onReceive` 里按包名核对。注册一次就够，输入法进程活多久它活多久。
     */
    private fun ensureRegistered(context: Context) {
        synchronized(lock) {
            if (receiverRegistered) return
            receiverRegistered = true
        }
        val filter = IntentFilter(ModuleBridgeContract.ACTION_VOICE_SERVICE_RESULT)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                if (!isFromModuleApp(receiverContext, sentFromUid)) {
                    Log.w(TAG, "ignored a voice service result from an untrusted sender")
                    return
                }
                val channel = intent.getStringExtra(ModuleBridgeContract.EXTRA_VOICE_SERVICE_CHANNEL)
                    ?: return
                synchronized(lock) {
                    pendingChannel = channel
                    lock.notifyAll()
                }
            }
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(receiver, filter)
            }
        }.onFailure {
            Log.e(TAG, "failed to register the voice service result receiver", it)
            synchronized(lock) { receiverRegistered = false }
        }
    }

    private fun isFromModuleApp(context: Context, senderUid: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        return context.packageManager
            .getPackagesForUid(senderUid)
            ?.contains(MODULE_PACKAGE_NAME) == true
    }
}
