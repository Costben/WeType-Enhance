package com.xposed.wetypehook.wetype.hook

import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import com.xposed.wetypehook.wetype.settings.WeTypeSettings
import java.io.File
import java.lang.ref.WeakReference

/**
 * 把设置页刚写下的改动原地重放到活着的输入法窗口上。
 *
 * ## 为什么需要它
 *
 * 设置页（`com.tencent.wetype`）与输入法本体（`com.tencent.wetype:hld`）是两个进程，
 * 而 [WeTypeSettings] 的快照是按进程缓存的 —— 设置页写盘之后，`:hld` 那份仍是旧的。
 * 原有的做法（[com.xposed.wetypehook.wetype.settings.WeTypeProcessRestarter]）是杀掉
 * `:hld` 让下次 `onStartInputView` 重新读；预览开着时，那等于「每改一个值键盘闪一次」，
 * 没法边拖滑块边看。
 *
 * ## 怎么做到不用 IPC
 *
 * 两个进程同包同 uid，写的是同一个 `shared_prefs/wetype_settings.xml`。`:hld` 只要盯着
 * 这个文件的时间戳就够：变了就让 [WeTypeSettings] 重读偏好、然后把当前窗口重放一遍
 * （[WeTypeWindowHooks.reconcileCurrentInputMethodService]）。
 *
 * 键盘正在显示时轮询；键盘隐藏后停止，避免后台常驻定时器。
 */
internal object KeyboardPreviewLiveReload {

    private const val POLL_INTERVAL_MS = 300L

    /** 一次落盘会先后动 `.xml` 和 `.xml.bak`，合并到一个窗口里再重放，避免连做两次。 */
    private const val COALESCE_DELAY_MS = 200L

    private val handler = Handler(Looper.getMainLooper())
    private var serviceRef: WeakReference<InputMethodService>? = null
    private var running = false
    private var lastStamp: String? = null

    private val pollTick = object : Runnable {
        override fun run() {
            val service = serviceRef?.get()
            if (!running || service == null) {
                stop()
                return
            }
            val stamp = currentStamp(service)
            if (stamp != lastStamp) {
                // 第一次只播种指纹：窗口刚显示时读到的就是当前值，不该立刻重放一次。
                val changed = lastStamp != null
                lastStamp = stamp
                if (changed) {
                    WeTypeSettings.reloadHostPreferences(service)
                    handler.removeCallbacks(reloadTick)
                    handler.postDelayed(reloadTick, COALESCE_DELAY_MS)
                }
            }
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    private val reloadTick = Runnable {
        if (!running) return@Runnable
        val service = serviceRef?.get() ?: return@Runnable
        WeTypeWindowHooks.reconcileCurrentInputMethodService(service)
    }

    fun start(service: InputMethodService) {
        serviceRef = WeakReference(service)
        running = true
        handler.removeCallbacks(pollTick)
        handler.removeCallbacks(reloadTick)
        lastStamp = currentStamp(service)
        handler.postDelayed(pollTick, POLL_INTERVAL_MS)
    }

    fun stop() {
        running = false
        serviceRef = null
        lastStamp = null
        handler.removeCallbacks(pollTick)
        handler.removeCallbacks(reloadTick)
    }

    private fun currentStamp(service: InputMethodService): String? {
        val file = WeTypeSettings.hostPreferencesFile(service)
        return preferencesFileStamp(file, File("${file.path}.bak"))
    }
}

/**
 * 偏好文件（含 `SharedPreferences` 的 `.bak`）的时间戳与长度合起来当指纹。
 *
 * 两个都算上：写盘期间真正的有效内容在 `.bak` 里，只看 `.xml` 会漏掉这半个窗口。
 * 文件都不存在时返回 `null`，与「有文件但指纹为空」区分开。
 */
internal fun preferencesFileStamp(vararg files: File): String? {
    val parts = files.mapNotNull { file ->
        if (!file.exists()) return@mapNotNull null
        "${file.lastModified()}:${file.length()}"
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString("|")
}
