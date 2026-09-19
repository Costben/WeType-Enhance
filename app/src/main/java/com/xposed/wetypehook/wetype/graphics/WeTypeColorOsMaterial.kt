package com.xposed.wetypehook.wetype.graphics

import android.content.Context
import android.database.ContentObserver
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import com.xposed.wetypehook.PropertyUtils
import com.xposed.wetypehook.xposed.Log

/**
 * ColorOS 原生背景模糊后端。
 *
 * 走系统 IME 自己使用的 `com.oplus.view.ViewRootManager`：
 * `ViewRootManager(view).getBackgroundBlurDrawable()` 拿到的 Drawable 由该 View 所属
 * 窗口的 ViewRootImpl 创建，因此采样的是键盘窗口**后面**的应用内容，而不是窗口内自绘。
 * 参数由 `com.oplus.graphics.OplusBlurParam` 提供（blurType / 染色 / 平滑圆角）。
 *
 * 这些类位于 `oplus-framework.jar`，是可被第三方进程反射调用的平台类（已在 ColorOS V17
 * 的 PLK110 上用独立 APK 实测落像素）。参数锁定小布输入法的实测预设：blur 150px、
 * Kawase 模糊、mix 染色、smoothCornerType=1、weight=3；仅四角半径跟随模块几何。
 */
internal class WeTypeColorOsMaterial(private val view: View) : WeTypeSystemMaterial {

    private var manager: Any? = null
    private var drawable: Drawable? = null
    private var applied = false
    private var lastRadii: WeTypeCornerRadii? = null
    private var lastDark: Boolean? = null

    override fun apply(isDark: Boolean, tintColor: Int?): Boolean {
        if (!isAvailable(view.context)) {
            clear(force = true)
            return false
        }
        applied = true
        return runCatching {
            ensureManager()
            val blurDrawable = checkNotNull(drawable) { "BackgroundBlurDrawable unavailable" }
            val radii = lastRadii ?: WeTypeCornerRadii.uniform(DEFAULT_CORNER_DP * view.resources.displayMetrics.density)
            if (lastDark != isDark || lastRadii == null) {
                applyParams(isDark)
                lastDark = isDark
            }
            applyGeometry(blurDrawable, radii)
            view.setBackground(blurDrawable)
            true
        }.getOrElse {
            Log.e("Failed: Apply ColorOS background blur")
            Log.e(it)
            clear(force = true)
            false
        }
    }

    override fun updateGeometry(cornerRadii: WeTypeCornerRadii) {
        lastRadii = cornerRadii
        if (!applied) return
        runCatching {
            ensureManager()
            val blurDrawable = drawable ?: return
            applyGeometry(blurDrawable, cornerRadii)
        }.onFailure {
            Log.e("Failed: Update ColorOS background geometry")
            Log.e(it)
        }
    }

    override fun clear(force: Boolean) {
        if (!applied && !force) return
        applied = false
        lastDark = null
        runCatching {
            manager?.javaClass?.getMethod("setBlurRadius", Int::class.javaPrimitiveType)
                ?.invoke(manager, 0)
        }
        runCatching { view.setBackground(null) }
        runCatching { drawable?.callback = null }
        drawable = null
        manager = null
    }

    private fun ensureManager() {
        if (manager != null && drawable != null) return
        check(view.isAttachedToWindow) { "Material carrier must be attached before creating blur drawable" }
        val managerClass = requireNotNull(viewRootManagerClass) { "ViewRootManager unavailable" }
        val instance = managerClass.getConstructor(View::class.java).newInstance(view)
        val created = invoke(instance, "getBackgroundBlurDrawable") as? Drawable
        checkNotNull(created) { "BackgroundBlurDrawable is null" }
        manager = instance
        drawable = created
    }

    private fun applyParams(isDark: Boolean) {
        val paramClass = requireNotNull(blurParamClass) { "OplusBlurParam unavailable" }
        val param = paramClass.getConstructor().newInstance()
        invoke(param, "setBlurType", BLUR_TYPE_FAST_KAWASE)
        val preset = if (isDark) DARK_MIX else LIGHT_MIX
        invoke(
            param,
            "setMaterialParams",
            BLEND_MODE_COLOR_MIX,
            floatArrayOf(1f, 1f, 1f, 1f),
            preset
        )
        invoke(param, "setSmoothCornerType", SMOOTH_CORNER_TYPE)
        invoke(param, "setSmoothCornerWeight", WETYPE_COLOROS_SMOOTH_WEIGHT)
        invoke(manager, "setBlurParams", param)
    }

    private fun applyGeometry(blurDrawable: Drawable, radii: WeTypeCornerRadii) {
        invoke(
            manager,
            "setCornerRadius",
            radii.topLeft, radii.topRight, radii.bottomLeft, radii.bottomRight,
            forcedType = Float::class.javaPrimitiveType
        )
        invoke(manager, "setBlurRadius", BLUR_RADIUS_PX)
    }

    /** 反射调用；参数类型按运行时值推断，[forcedType] 用于全 float 的变参签名。 */
    private fun invoke(target: Any?, name: String, vararg args: Any?, forcedType: Class<*>? = null): Any? {
        val receiver = target ?: return null
        val types = Array(args.size) { index ->
            forcedType ?: when (val value = args[index]) {
                is Int -> Int::class.javaPrimitiveType
                is Float -> Float::class.javaPrimitiveType
                is Boolean -> Boolean::class.javaPrimitiveType
                else -> value?.javaClass ?: Any::class.java
            }!!
        }
        val method = receiver.javaClass.getMethod(name, *types)
        return method.invoke(receiver, *args)
    }

    companion object {
        private const val BLUR_TYPE_FAST_KAWASE = 2
        private const val BLEND_MODE_COLOR_MIX = 1
        private const val SMOOTH_CORNER_TYPE = 1
        private const val BLUR_RADIUS_PX = 150
        private const val DEFAULT_CORNER_DP = 28

        private const val MATERIAL_BLUR_SETTING = "system_material_blur_enable"

        /** 小布输入法实测染色：暗色纯黑、亮色浅灰，alpha 分别约 0.6 / 0.8。 */
        private val DARK_MIX = floatArrayOf(0f, 0f, 0f, 0.6f)
        private val LIGHT_MIX = floatArrayOf(221f / 255f, 221f / 255f, 221f / 255f, 204f / 255f)

        private val viewRootManagerClass: Class<*>? by lazy {
            runCatching { Class.forName("com.oplus.view.ViewRootManager") }.getOrNull()
        }
        private val blurParamClass: Class<*>? by lazy {
            runCatching { Class.forName("com.oplus.graphics.OplusBlurParam") }.getOrNull()
        }

        /** ROM 判定：ColorOS / OxygenOS 系一律带此属性，与 HyperOS 的属性互斥。 */
        fun isPlatform(): Boolean = !PropertyUtils["ro.build.version.oplusrom", ""].isNullOrEmpty()

        fun isAvailable(context: Context): Boolean = runCatching {
            isPlatform() && viewRootManagerClass != null && blurParamClass != null &&
                Settings.System.getInt(context.contentResolver, MATERIAL_BLUR_SETTING, 1) == 1
        }.getOrDefault(false)

        fun fallbackColor(isDark: Boolean): Int =
            if (isDark) 0xFF18191B.toInt() else 0xFFE5E6E7.toInt()

        fun observeAvailability(context: Context, onChanged: () -> Unit): () -> Unit {
            val resolver = context.contentResolver
            val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) = onChanged()
            }
            resolver.registerContentObserver(
                Settings.System.getUriFor(MATERIAL_BLUR_SETTING),
                false,
                observer
            )
            return { resolver.unregisterContentObserver(observer) }
        }
    }
}
