package com.xposed.wetypehook.wetype.aiwriter

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.inputmethod.ExtractedTextRequest
import android.widget.ImageView
import android.widget.Toast
import androidx.core.graphics.PathParser
import com.xposed.wetypehook.oplus.OplusAiWriterHooks
import com.xposed.wetypehook.wetype.gesture.GestureActionExecutor
import com.xposed.wetypehook.xposed.Log

/**
 * 微信输入法进程侧调起 ColorOS AI 写作面板 (KitMainPage) 的启动器与系统图标复用器。
 */
object WeTypeAiWriterLauncher {

    private const val TAG = "WeTypeAiWriterLauncher"
    private const val AIWRITER_PACKAGE = "com.oplus.aiwriter"

    /**
     * ColorOS 系统 AI 写作图标的可见尺寸比微信输入法宿主工具栏图标更满。
     *
     * 宿主常用图标（如剪贴板、AI助手、换肤）在 64x64 或 120x120 视口中的实际图形跨度约为 58%~76%。
     * ColorOS 官方矢量图标（ic_setting_entrance_os17）在 24x24 视口中斜向充满几乎整格（跨度达 83%）。
     * 若直接 1.0 满幅绘制，视觉量感明显大一档。
     * 采用 0.80f 的缩放系数，墨迹比例收敛至 ~66%，与宿主相邻图标的视觉重心和线条粗细完美对齐。
     */
    private const val ICON_FILL_SCALE = 0.80f

    /**
     * 提取自 ColorOS 17 官方系统 AI 写作 (com.oplus.aiwriter) 的原生矢量图标
     * (res/drawable/ic_setting_entrance_os17.xml, 24x24 viewport)。
     * 造型为 ColorOS 标志性的「斜画笔 + 两颗四芒星」。
     *
     * 直接内置为单色矢量路径，彻底根除跨应用加载的延迟、类加载器隔离问题，
     * 以及系统 APK 内硬编码 #00bd13 绿色底色导致的异常着色。
     */
    private const val COLOROS_AI_WRITER_PATH_DATA =
        "M18.849,16.703C18.898,16.645 18.989,16.645 19.038,16.703C19.052,16.721 19.066,16.764 19.094,16.848C19.236,17.272 19.307,17.485 19.402,17.675C19.699,18.274 20.184,18.759 20.783,19.056C20.973,19.151 21.186,19.221 21.61,19.363C21.694,19.391 21.736,19.405 21.753,19.42C21.812,19.469 21.812,19.559 21.753,19.608C21.736,19.622 21.694,19.637 21.61,19.665C21.186,19.807 20.973,19.877 20.783,19.972C20.184,20.269 19.699,20.755 19.402,21.354C19.307,21.544 19.236,21.756 19.094,22.18C19.066,22.264 19.052,22.307 19.038,22.324C18.989,22.383 18.898,22.383 18.849,22.324C18.834,22.307 18.82,22.264 18.792,22.18C18.651,21.756 18.579,21.544 18.485,21.354C18.187,20.755 17.703,20.269 17.104,19.972C16.914,19.877 16.701,19.807 16.278,19.665C16.194,19.637 16.151,19.622 16.133,19.608C16.075,19.559 16.075,19.469 16.133,19.42C16.151,19.405 16.194,19.391 16.278,19.363C16.701,19.221 16.913,19.15 17.104,19.056C17.702,18.759 18.187,18.274 18.485,17.675C18.579,17.485 18.651,17.272 18.792,16.848C18.82,16.764 18.834,16.721 18.849,16.703ZM15.548,3.779C16.676,2.651 18.504,2.652 19.632,3.779L20.952,5.098C22.079,6.226 22.08,8.054 20.953,9.182L11.22,18.914C10.903,19.231 10.517,19.47 10.092,19.612L4.483,21.481C4.133,21.597 3.746,21.506 3.485,21.245C3.224,20.984 3.133,20.597 3.249,20.247L5.12,14.639C5.261,14.214 5.5,13.827 5.817,13.51L15.548,3.779ZM7.196,14.889C7.093,14.992 7.015,15.118 6.969,15.255L5.716,19.014L9.475,17.761C9.613,17.715 9.738,17.638 9.841,17.535L16.555,10.821C16.451,10.432 16.184,9.795 15.56,9.171C14.934,8.546 14.296,8.28 13.908,8.176L7.196,14.889ZM18.254,5.157C17.888,4.792 17.293,4.792 16.927,5.157L15.408,6.676C15.906,6.929 16.436,7.29 16.939,7.793C17.441,8.296 17.803,8.824 18.055,9.322L19.573,7.803C19.939,7.437 19.939,6.842 19.573,6.476L18.254,5.157ZM4.934,2.978C4.983,2.919 5.073,2.92 5.122,2.978C5.137,2.996 5.152,3.038 5.18,3.122C5.322,3.546 5.392,3.759 5.487,3.949C5.784,4.548 6.269,5.033 6.868,5.33C7.058,5.425 7.271,5.495 7.695,5.637C7.779,5.665 7.82,5.68 7.838,5.695C7.897,5.744 7.897,5.833 7.838,5.882C7.82,5.897 7.779,5.912 7.695,5.94C7.271,6.081 7.058,6.152 6.868,6.246C6.269,6.544 5.784,7.03 5.487,7.628C5.392,7.819 5.322,8.031 5.18,8.454C5.152,8.538 5.137,8.581 5.122,8.599C5.073,8.657 4.983,8.657 4.934,8.599C4.919,8.581 4.905,8.538 4.877,8.454C4.736,8.031 4.664,7.819 4.57,7.628C4.272,7.03 3.788,6.544 3.189,6.246C2.999,6.152 2.786,6.081 2.363,5.94C2.279,5.912 2.236,5.897 2.218,5.882C2.16,5.833 2.16,5.744 2.218,5.695C2.236,5.68 2.279,5.665 2.363,5.637C2.786,5.495 2.999,5.425 3.189,5.33C3.787,5.033 4.272,4.548 4.57,3.949C4.664,3.759 4.736,3.546 4.877,3.122C4.905,3.038 4.919,2.996 4.934,2.978Z"

    private val sharedBasePath: Path by lazy {
        PathParser.createPathFromPathData(COLOROS_AI_WRITER_PATH_DATA) ?: Path()
    }

    /**
     * 获取 ColorOS 系统 AI 写作原生矢量图标。
     * 每次返回独立包装的 [AiWriterVectorDrawable] 实例，各 ViewHolder 拥有互不干扰的独立 bounds 与状态。
     */
    fun loadSystemAiWriterIcon(context: Context, isDark: Boolean = false): Drawable {
        return AiWriterVectorDrawable(context, isDark)
    }

    /**
     * 基于 ColorOS 17 原生 AI 写作矢量路径构建的单色矢量 Drawable。
     *
     * 核心特性：
     * 1. 纯净单色：默认黑色/深灰单色填充，绝对不含任何硬编码绿色；
     * 2. 自动跟随皮肤：在每次 [draw] 前自动从宿主 ImageView 提取当前生效的 [ColorFilter]；
     *    同时响应 [setColorFilter]、[setTint]、[setTintList]；
     * 3. 完美尺寸比例：在 [onBoundsChange] 矩阵运算中直接按 [ICON_FILL_SCALE] 居中缩放，
     *    与微信输入法工具栏各同源图标（如剪贴板、换肤、设置）视觉尺寸与重心严格一致。
     */
    class AiWriterVectorDrawable(
        context: Context,
        isDark: Boolean = false
    ) : Drawable() {
        private val density = context.resources.displayMetrics.density
        private val intrinsicSizePx = (24f * density + 0.5f).toInt()
        private val scaledPath = Path()
        private val matrix = Matrix()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = if (isDark) 0xE6FFFFFF.toInt() else 0xE6000000.toInt()
        }

        override fun getIntrinsicWidth(): Int = intrinsicSizePx
        override fun getIntrinsicHeight(): Int = intrinsicSizePx

        override fun onBoundsChange(bounds: Rect) {
            super.onBoundsChange(bounds)
            val w = bounds.width().toFloat()
            val h = bounds.height().toFloat()
            if (w > 0f && h > 0f) {
                val scaledW = w * ICON_FILL_SCALE
                val scaledH = h * ICON_FILL_SCALE
                val offsetX = bounds.left.toFloat() + (w - scaledW) / 2f
                val offsetY = bounds.top.toFloat() + (h - scaledH) / 2f
                matrix.reset()
                matrix.setScale(scaledW / 24f, scaledH / 24f)
                matrix.postTranslate(offsetX, offsetY)
                sharedBasePath.transform(matrix, scaledPath)
            }
        }

        override fun draw(canvas: Canvas) {
            if (scaledPath.isEmpty && !bounds.isEmpty) {
                onBoundsChange(bounds)
            }
            // 绘制前自动与宿主 ImageView 当前下发的皮肤 ColorFilter 同步
            val host = callback as? ImageView
            val hostFilter = host?.colorFilter
            if (hostFilter != null && paint.colorFilter !== hostFilter) {
                paint.colorFilter = hostFilter
            }
            canvas.drawPath(scaledPath, paint)
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
            invalidateSelf()
        }

        override fun setTint(tintColor: Int) {
            paint.color = tintColor
            invalidateSelf()
        }

        override fun setTintList(tint: ColorStateList?) {
            tint?.let { paint.color = it.defaultColor }
            invalidateSelf()
        }

        @Suppress("DEPRECATION")
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    /**
     * 唤起 AI 写作主面板 (KitMainPage)。
     */
    fun launch(view: View, providedIms: InputMethodService? = null): Boolean {
        val context = view.context ?: return false
        val ims = providedIms ?: GestureActionExecutor.resolveInputMethodService(context)
        val appContext = ims?.applicationContext ?: context.applicationContext ?: context

        val targetPackage = runCatching {
            ims?.currentInputEditorInfo?.packageName?.takeIf {
                it.isNotEmpty() && it != "com.tencent.wetype" && it != AIWRITER_PACKAGE
            }
        }.getOrNull()

        val inputText = runCatching {
            val ic = ims?.currentInputConnection
            val selected = ic?.getSelectedText(0)?.toString()
            if (!selected.isNullOrEmpty()) {
                selected
            } else {
                ic?.getExtractedText(ExtractedTextRequest(), 0)?.text?.toString()
            }
        }.getOrNull().orEmpty()

        Log.i("[$TAG] Launching AI Writer: targetPackage=$targetPackage, textLength=${inputText.length}")

        // 优先发送有序广播到 com.oplus.aiwriter 进程内的 Receiver（无闪烁、直接启动 AIKitEntryService）
        val broadcastIntent = Intent(OplusAiWriterHooks.ACTION_OPEN_AIWRITER).apply {
            setPackage(AIWRITER_PACKAGE)
            if (!targetPackage.isNullOrEmpty()) {
                putExtra(OplusAiWriterHooks.EXTRA_TARGET_PACKAGE, targetPackage)
            }
            if (inputText.isNotEmpty()) {
                putExtra(OplusAiWriterHooks.EXTRA_TEXT, inputText)
            }
            addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
        }

        return runCatching {
            appContext.sendOrderedBroadcast(
                broadcastIntent,
                null,
                object : BroadcastReceiver() {
                    override fun onReceive(brContext: Context?, brIntent: Intent?) {
                        if (resultCode == Activity.RESULT_OK) {
                            Log.i("[$TAG] AI Writer successfully triggered via broadcast bridge")
                        } else {
                            Log.i("[$TAG] Broadcast bridge returned $resultCode; falling back to TextSelectorEntrance")
                            launchFallbackEntrance(appContext, targetPackage, inputText)
                        }
                    }
                },
                null,
                Activity.RESULT_CANCELED,
                null,
                null
            )
            true
        }.onFailure { e ->
            Log.e("[$TAG] sendOrderedBroadcast failed: ${e.message}, trying fallback")
            launchFallbackEntrance(appContext, targetPackage, inputText)
        }.getOrDefault(false)
    }

    private fun launchFallbackEntrance(context: Context, targetPackage: String?, text: String) {
        val fallbackIntent = Intent(Intent.ACTION_PROCESS_TEXT).apply {
            component = ComponentName(AIWRITER_PACKAGE, "com.oplus.aiwriter.entrance.TextSelectorEntrance")
            type = "text/plain"
            putExtra(Intent.EXTRA_PROCESS_TEXT, text)
            putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)
            putExtra(OplusAiWriterHooks.EXTRA_FROM_WETYPE, true)
            if (!targetPackage.isNullOrEmpty()) {
                putExtra(OplusAiWriterHooks.EXTRA_TARGET_PACKAGE, targetPackage)
            }
            if (text.isNotEmpty()) {
                putExtra(OplusAiWriterHooks.EXTRA_TEXT, text)
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        }
        runCatching {
            context.startActivity(fallbackIntent)
            Log.i("[$TAG] Fallback TextSelectorEntrance started")
        }.onFailure { e ->
            Log.e("[$TAG] Fallback TextSelectorEntrance failed: ${e.message}")
            runCatching {
                Toast.makeText(
                    context,
                    "无法唤起 AI 写作，请确认 LSPosed 已勾选「AI写作 (com.oplus.aiwriter)」作用域",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
}
