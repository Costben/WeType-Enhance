package com.xposed.wetypehook

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * 「Shizuku 授权」入口。
 *
 * 从设置页（宿主进程）拉起，跑在模块 App 自己的进程里 —— 这是必须的：Shizuku 的授权框只认
 * 「声明了 `ShizukuProvider` 的进程」，而且**请求方得有可见界面**，所以不能在宿主进程里
 * 悄悄发一条请求了事。
 *
 * 授权成功与否由 Shizuku 自己的弹窗决定，这里只负责把请求发出去、把结论显示出来。
 */
class ShizukuAuthActivity : Activity() {

    private lateinit var statusView: TextView
    private var permissionListener: ((Boolean) -> Unit)? = null
    private var finished = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        statusView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER
        }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(32), dp(48), dp(32), dp(48))
                addView(
                    TextView(this@ShizukuAuthActivity).apply {
                        text = "Shizuku 授权"
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                        setTextColor(Color.BLACK)
                        gravity = Gravity.CENTER
                    }
                )
                addView(statusView, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
            }
        )
        setStatus("正在检查 Shizuku…")
        checkShizuku()
    }

    private fun checkShizuku() {
        Thread {
            val running = ShizukuShell.awaitBinder(BINDER_WAIT_MILLIS)
            runOnUiThread {
                when {
                    !running -> {
                        setStatus("未检测到运行中的 Shizuku。\n请先安装并启动 Shizuku，或直接授予本模块 root 权限。")
                        finishLater()
                    }

                    ShizukuShell.isAuthorized() -> {
                        setStatus("本模块已获得 Shizuku 授权。")
                        finishLater()
                    }

                    else -> {
                        setStatus("请在 Shizuku 的弹窗中点「允许」。")
                        requestPermission()
                    }
                }
            }
        }.apply { name = "shizuku-check" }.start()
    }

    private fun requestPermission() {
        val listener: (Boolean) -> Unit = { granted ->
            runOnUiThread {
                setStatus(if (granted) "已获得授权，可以返回设置页开启「指向模块的识别服务」。" else "授权被拒绝。")
                finishLater()
            }
        }
        permissionListener = listener
        ShizukuShell.requestPermission(listener)
    }

    private fun setStatus(text: String) {
        statusView.text = text
        Toast.makeText(this, text.replace('\n', ' '), Toast.LENGTH_SHORT).show()
    }

    private fun finishLater() {
        if (finished) return
        finished = true
        statusView.postDelayed({ if (!isFinishing) finish() }, FINISH_DELAY_MILLIS)
    }

    override fun onDestroy() {
        permissionListener?.let { ShizukuShell.clearPermissionListener(it) }
        permissionListener = null
        super.onDestroy()
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    private companion object {
        const val BINDER_WAIT_MILLIS = 3_000L
        const val FINISH_DELAY_MILLIS = 2_500L
    }
}
