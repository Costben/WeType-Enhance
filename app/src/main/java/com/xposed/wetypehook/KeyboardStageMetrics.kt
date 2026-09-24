package com.xposed.wetypehook

import android.content.Context
import android.view.RoundedCorner
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.xposed.wetypehook.wetype.settings.HostPreferencesFile
import java.io.File
import kotlin.math.abs

/** 低于这个变化量的重复上报直接丢掉，避免 IME 每帧回写。 */
private const val KEYBOARD_HEIGHT_EPSILON_PX = 4

/**
 * 判断窗口高度是否还是同一块屏。
 *
 * 两侧量的是各自窗口的装饰高度：IME 侧来自 `decorView.rootView.height`，设置页侧来自
 * `Configuration.screenHeightDp`（是否扣掉导航栏随 ROM 而异）。用相对容忍度放掉这类
 * 小幅差异，同时仍然能拦住转头这类真正换了屏的情况。
 */
private const val WINDOW_HEIGHT_TOLERANCE_RATIO = 0.1f
private const val WINDOW_HEIGHT_TOLERANCE_MIN_PX = 48

private const val METRICS_PREFS_NAME = "wetype_keyboard_metrics"
private const val KEY_KEYBOARD_HEIGHT_PX = "keyboard_height_px"
private const val KEY_WINDOW_HEIGHT_PX = "window_height_px"

/**
 * 宿主真实键盘尺寸的落盘读数。
 *
 * 写入端是输入法进程 `com.tencent.wetype:hld`（`WeTypeWindowHooks` 在 IME 上报可见高度时），
 * 读取端是模块设置页所在的宿主主进程 `com.tencent.wetype`。两个进程的 SharedPreferences
 * 内存缓存互不可见，所以读的那一侧直接解析 `shared_prefs` 里的文件。
 *
 * 刻意不引用任何 Xposed API：设置页进程与 `:hld` 侧的圆角参考弧都要加载这个类。
 */
internal object WeTypeKeyboardMetrics {

    @Volatile
    private var lastWrittenKeyboardHeightPx: Int? = null

    @Volatile
    private var lastWrittenWindowHeightPx: Int? = null

    fun record(context: Context, keyboardHeightPx: Int, windowHeightPx: Int) {
        if (keyboardHeightPx <= 0 || windowHeightPx <= 0) return
        val previousHeight = lastWrittenKeyboardHeightPx
        if (lastWrittenWindowHeightPx == windowHeightPx &&
            previousHeight != null &&
            abs(previousHeight - keyboardHeightPx) < KEYBOARD_HEIGHT_EPSILON_PX
        ) {
            return
        }
        val committed = runCatching {
            context.applicationContext
                .getSharedPreferences(METRICS_PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_KEYBOARD_HEIGHT_PX, keyboardHeightPx)
                .putInt(KEY_WINDOW_HEIGHT_PX, windowHeightPx)
                .commit()
        }.getOrDefault(false)
        if (committed) {
            lastWrittenKeyboardHeightPx = keyboardHeightPx
            lastWrittenWindowHeightPx = windowHeightPx
        }
    }

    /**
     * 取宿主当前键盘高度。
     *
     * 键盘收起时**故意不清除**读数：设置页正是从键盘工具栏拉起来的，拉起动作本身就会
     * 收起键盘，清掉就等于永远读不到。窗口高度对不上（转头、换屏）才作废。
     */
    fun keyboardHeightForWindow(context: Context, windowHeightPx: Int): Int? = runCatching {
        val dataDir = context.applicationContext.dataDir
        val values = HostPreferencesFile(File(dataDir, "shared_prefs/$METRICS_PREFS_NAME.xml")).read()
        val height = values[KEY_KEYBOARD_HEIGHT_PX] as? Int ?: return@runCatching null
        val recordedWindow = values[KEY_WINDOW_HEIGHT_PX] as? Int ?: return@runCatching null
        height.takeIf { it > 0 && isSameWindow(recordedWindow, windowHeightPx) }
    }.getOrNull()
}

/** 记录时的窗口高度与当前窗口高度是否还算同一块屏。 */
internal fun isSameWindow(recordedWindowPx: Int, windowHeightPx: Int): Boolean =
    abs(recordedWindowPx - windowHeightPx) <= maxOf(
        WINDOW_HEIGHT_TOLERANCE_MIN_PX,
        (minOf(recordedWindowPx, windowHeightPx) * WINDOW_HEIGHT_TOLERANCE_RATIO).toInt()
    )

/** 设备屏幕底部圆角半径（px）；窗口还没拿到 insets 时返回 null。 */
internal fun resolveScreenCornerRadiusPx(view: View): Float? {
    val insets = view.rootWindowInsets ?: return null
    return runCatching {
        listOf(RoundedCorner.POSITION_BOTTOM_LEFT, RoundedCorner.POSITION_BOTTOM_RIGHT)
            .mapNotNull { position -> insets.getRoundedCorner(position)?.radius?.toFloat() }
            .maxOrNull()
            ?.takeIf { it > 0f }
    }.getOrNull()
}

/**
 * 底部那条「系统抬高」的高度（px）：真机 IME 窗口里不放按键、由系统占掉的那一截。
 *
 * 必须现取运行时值：各 ROM 的导航方式与手势条高度都不同，写死任何数字都会在某台机器上错位。
 */
internal fun resolveNavigationBarInsetPx(view: View): Int = runCatching {
    ViewCompat.getRootWindowInsets(view)
        ?.getInsets(WindowInsetsCompat.Type.navigationBars())
        ?.bottom
        ?: 0
}.getOrDefault(0)

