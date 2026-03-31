package com.xposed.miuiime

import android.content.res.AssetManager
import android.content.res.ColorStateList
import android.content.res.Resources
import android.content.res.TypedArray
import android.content.Context
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Path
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import android.view.ViewOutlineProvider
import android.view.Window
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import com.github.kyuubiran.ezxhelper.init.EzXHelperInit
import com.github.kyuubiran.ezxhelper.utils.Log
import com.github.kyuubiran.ezxhelper.utils.findMethod
import com.github.kyuubiran.ezxhelper.utils.getObjectAs
import com.github.kyuubiran.ezxhelper.utils.getStaticObject
import com.github.kyuubiran.ezxhelper.utils.hookAfter
import com.github.kyuubiran.ezxhelper.utils.hookBefore
import com.github.kyuubiran.ezxhelper.utils.hookReplace
import com.github.kyuubiran.ezxhelper.utils.hookReturnConstant
import com.github.kyuubiran.ezxhelper.utils.invokeMethodAs
import com.github.kyuubiran.ezxhelper.utils.invokeStaticMethodAuto
import com.github.kyuubiran.ezxhelper.utils.loadClassOrNull
import com.github.kyuubiran.ezxhelper.utils.putStaticObject
import com.github.kyuubiran.ezxhelper.utils.sameAs
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.WeakHashMap

private const val TAG = "miuiime"
private const val WETYPE_PACKAGE = "com.tencent.wetype"
private const val WETYPE_FONT_ASSET = "fonts/WE-Regular.ttf"
private const val MODULE_WETYPE_FONT_ASSET = "WE-Regular.ttf"
private const val WETYPE_BLUR_APPLY_MAX_RETRY = 6
private const val WETYPE_BACKGROUND_SETTLE_RETRY = 3
private val WETYPE_COLOR_REPLACEMENTS = mapOf(
    0xFFE1E3E8.toInt() to Color.TRANSPARENT,
    0xFFE5E6EB.toInt() to Color.TRANSPARENT,
    0xFF202020.toInt() to Color.TRANSPARENT,
    0xFFD5D7DD.toInt() to Color.TRANSPARENT
)

private data class WeTypeWindowState(
    var lifecycleOrder: Int = 0,
    var blurApplyToken: Int = 0,
    var blurEligible: Boolean = false,
    var backgroundCarrier: View? = null,
    var firstPopupLogged: Boolean = false
)

private data class WeTypeViewSnapshot(
    val name: String,
    val locationX: Int,
    val locationY: Int,
    val top: Int,
    val height: Int,
    val measuredHeight: Int
)

private data class WeTypeWindowSnapshot(
    val decorView: WeTypeViewSnapshot?,
    val candidatesFrame: WeTypeViewSnapshot?,
    val inputFrame: WeTypeViewSnapshot?,
    val inputView: WeTypeViewSnapshot?,
    val windowWidth: Int,
    val windowHeight: Int,
    val windowGravity: Int
) {
    fun isLayoutReady(): Boolean {
        val decorReady = (decorView?.height ?: 0) > 0
        val contentReady = listOf(candidatesFrame, inputFrame, inputView)
            .any { snapshot -> snapshot != null && (snapshot.height > 0 || snapshot.measuredHeight > 0) }
        return decorReady && contentReady
    }

    fun backgroundTop(): Int {
        val contentTop = listOf(candidatesFrame, inputFrame, inputView)
            .filter { snapshot -> snapshot != null && (snapshot.height > 0 || snapshot.measuredHeight > 0) }
            .mapNotNull { snapshot ->
                val resolved = snapshot ?: return@mapNotNull null
                resolved.locationY.takeIf { it > 0 } ?: resolved.top.takeIf { it > 0 }
            }
            .minOrNull()
        return contentTop ?: 0
    }
}

class MainHook : IXposedHookLoadPackage, IXposedHookZygoteInit {
    private val miuiImeList: List<String> = listOf(
        "com.iflytek.inputmethod.miui",
        "com.sohu.inputmethod.sogou.xiaomi",
        "com.baidu.input_mi",
        "com.miui.catcherpatch"
    )
    private val weTypeWindowStates = WeakHashMap<Any, WeTypeWindowState>()
    private var navBarColor: Int? = null
    private var bottomViewSourceColor: Int? = null
    private lateinit var modulePath: String

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        modulePath = startupParam.modulePath
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        // 检查是否支持全面屏优化
        if (PropertyUtils["ro.miui.support_miui_ime_bottom", "0"] != "1") return
        EzXHelperInit.initHandleLoadPackage(lpparam)
        EzXHelperInit.setLogTag(TAG)
        Log.i("miuiime is supported")

        if (lpparam.packageName == "android") {
            startPermissionHook()
        } else {
            startHook(lpparam)
        }
    }

    private fun startHook(lpparam: XC_LoadPackage.LoadPackageParam) {
        val isWeType = lpparam.packageName == WETYPE_PACKAGE
        if (isWeType) {
            hookWeTypeFont()
            hookWeTypeTransparentColors()
            hookWeTypeWindowBlur()
            hookWeTypeWindowCorner()
        }

        // 检查是否为小米定制输入法
        val isNonCustomize = !miuiImeList.contains(lpparam.packageName)
        if (isNonCustomize) {
            val sInputMethodServiceInjector =
                loadClassOrNull("android.inputmethodservice.InputMethodServiceInjector")
                    ?: loadClassOrNull("android.inputmethodservice.InputMethodServiceStubImpl")

            sInputMethodServiceInjector?.also {
                hookSIsImeSupport(it)
                hookIsXiaoAiEnable(it)
                setPhraseBgColor(it, isWeType)
            } ?: Log.e("Failed:Class not found: InputMethodServiceInjector")
        }

        hookDeleteNotSupportIme(
            "android.inputmethodservice.InputMethodServiceInjector\$MiuiSwitchInputMethodListener",
            lpparam.classLoader
        )

        // 获取常用语的ClassLoader
        findMethod("android.inputmethodservice.InputMethodModuleManager") {
            name == "loadDex" && parameterTypes.sameAs(ClassLoader::class.java, String::class.java)
        }.hookAfter { param ->
            hookDeleteNotSupportIme(
                "com.miui.inputmethod.InputMethodBottomManager\$MiuiSwitchInputMethodListener",
                param.args[0] as ClassLoader
            )
            loadClassOrNull(
                "com.miui.inputmethod.InputMethodBottomManager",
                param.args[0] as ClassLoader
            )?.also {
                if (isNonCustomize) {
                    hookSIsImeSupport(it)
                    hookIsXiaoAiEnable(it)
                }

                // 针对A11的修复切换输入法列表
                it.getDeclaredMethod("getSupportIme").hookReplace { _ ->
                    it.getStaticObject("sBottomViewHelper")
                        .getObjectAs<InputMethodManager>("mImm").enabledInputMethodList
                }
            } ?: Log.e("Failed:Class not found: com.miui.inputmethod.InputMethodBottomManager")
        }

        Log.i("Hook MIUI IME Done!")
    }

    private fun hookWeTypeFont() {
        runCatching {
            val moduleAssetManager = AssetManager::class.java.getDeclaredConstructor().newInstance()
            val addAssetPath = AssetManager::class.java.getMethod("addAssetPath", String::class.java)
            check(addAssetPath.invoke(moduleAssetManager, modulePath) as Int != 0) {
                "Failed to add module asset path: $modulePath"
            }

            Typeface::class.java.getDeclaredMethod(
                "createFromAsset",
                AssetManager::class.java,
                String::class.java
            ).hookBefore { param ->
                if (param.args[1] != WETYPE_FONT_ASSET) return@hookBefore
                param.result = Typeface.createFromAsset(moduleAssetManager, MODULE_WETYPE_FONT_ASSET)
            }
            Log.i("Success: Hook WeType font replacement")
        }.onFailure {
            Log.i("Failed: Hook WeType font replacement")
            Log.i(it)
        }
    }

    private fun hookWeTypeTransparentColors() {
        runCatching {
            Resources::class.java.getMethod("getColor", Int::class.javaPrimitiveType)
                .hookAfter { param ->
                    param.result = replaceWeTypeColor(param.result as Int)
                }
            Resources::class.java.getMethod(
                "getColor",
                Int::class.javaPrimitiveType,
                Resources.Theme::class.java
            ).hookAfter { param ->
                param.result = replaceWeTypeColor(param.result as Int)
            }
            Resources::class.java.getMethod("getColorStateList", Int::class.javaPrimitiveType)
                .hookAfter { param ->
                    param.result = replaceWeTypeColorStateList(param.result as ColorStateList)
                }
            Resources::class.java.getMethod(
                "getColorStateList",
                Int::class.javaPrimitiveType,
                Resources.Theme::class.java
            ).hookAfter { param ->
                param.result = replaceWeTypeColorStateList(param.result as ColorStateList)
            }
            TypedArray::class.java.getMethod(
                "getColor",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            ).hookAfter { param ->
                param.result = replaceWeTypeColor(param.result as Int)
            }
            TypedArray::class.java.getMethod("getColorStateList", Int::class.javaPrimitiveType)
                .hookAfter { param ->
                    val colorStateList = param.result as? ColorStateList ?: return@hookAfter
                    param.result = replaceWeTypeColorStateList(colorStateList)
                }
            View::class.java.getMethod("setBackgroundColor", Int::class.javaPrimitiveType)
                .hookBefore { param ->
                    param.args[0] = replaceWeTypeColor(param.args[0] as Int)
                }
            GradientDrawable::class.java.getMethod("setColor", Int::class.javaPrimitiveType)
                .hookBefore { param ->
                    param.args[0] = replaceWeTypeColor(param.args[0] as Int)
                }
            runCatching {
                GradientDrawable::class.java.getMethod("setColors", IntArray::class.java)
                    .hookBefore { param ->
                        val colors = param.args[0] as? IntArray ?: return@hookBefore
                        param.args[0] = colors.map(::replaceWeTypeColor).toIntArray()
                    }
            }
            ColorDrawable::class.java.getMethod("setColor", Int::class.javaPrimitiveType)
                .hookBefore { param ->
                    param.args[0] = replaceWeTypeColor(param.args[0] as Int)
                }
            runCatching {
                Drawable::class.java.getMethod(
                    "setTint",
                    Int::class.javaPrimitiveType
                ).hookBefore { param ->
                    param.args[0] = replaceWeTypeColor(param.args[0] as Int)
                }
            }
            runCatching {
                Drawable::class.java.getMethod(
                    "setColorFilter",
                    Int::class.javaPrimitiveType,
                    android.graphics.PorterDuff.Mode::class.java
                ).hookBefore { param ->
                    param.args[0] = replaceWeTypeColor(param.args[0] as Int)
                }
            }
            runCatching {
                Drawable::class.java.getMethod(
                    "setColorFilter",
                    android.graphics.ColorFilter::class.java
                ).hookBefore { param ->
                    val colorFilter = param.args[0] as? PorterDuffColorFilter ?: return@hookBefore
                    val colorField = PorterDuffColorFilter::class.java.getDeclaredField("mColor")
                    colorField.isAccessible = true
                    colorField.setInt(colorFilter, replaceWeTypeColor(colorField.getInt(colorFilter)))
                }
            }
            Log.i("Success: Hook WeType transparent colors")
        }.onFailure {
            Log.i("Failed: Hook WeType transparent colors")
            Log.i(it)
        }
    }

    private fun hookWeTypeWindowCorner() {
        runCatching {
            val inputMethodService = loadClassOrNull("android.inputmethodservice.InputMethodService")
                ?: error("Failed to load InputMethodService")

            inputMethodService.getMethod("onCreate").hookAfter { param ->
                applyWeTypeWindowCorner(param.thisObject)
            }
            inputMethodService.getMethod(
                "onStartInputView",
                EditorInfo::class.java,
                Boolean::class.javaPrimitiveType
            ).hookAfter { param ->
                applyWeTypeWindowCorner(param.thisObject)
            }
            runCatching {
                inputMethodService.getMethod("onWindowShown").hookAfter { param ->
                    applyWeTypeWindowCorner(param.thisObject)
                }
            }
            Log.i("Success: Hook WeType window corner")
        }.onFailure {
            Log.i("Failed: Hook WeType window corner")
            Log.i(it)
        }
    }

    private fun hookWeTypeWindowBlur() {
        runCatching {
            val inputMethodService = loadClassOrNull("android.inputmethodservice.InputMethodService")
                ?: error("Failed to load InputMethodService")

            inputMethodService.getMethod(
                "onStartInputView",
                EditorInfo::class.java,
                Boolean::class.javaPrimitiveType
            ).hookAfter { param ->
                onWeTypeWindowStage(param.thisObject, "onStartInputView")
            }
            runCatching {
                inputMethodService.getMethod("onWindowShown").hookAfter { param ->
                    onWeTypeWindowStage(param.thisObject, "onWindowShown")
                }
            }
            runCatching {
                inputMethodService.getMethod("updateFullscreenMode").hookAfter { param ->
                    onWeTypeWindowStage(param.thisObject, "updateFullscreenMode")
                }
            }
            Log.i("Success: Hook WeType window blur")
        }.onFailure {
            Log.i("Failed: Hook WeType window blur")
            Log.i(it)
        }
    }

    private fun onWeTypeWindowStage(inputMethodService: Any, stage: String) {
        runCatching {
            val state = getWeTypeWindowState(inputMethodService)
            state.lifecycleOrder += 1
            when (stage) {
                "onStartInputView" -> state.blurEligible = false
                "onWindowShown", "updateFullscreenMode" -> state.blurEligible = true
            }

            if (!state.firstPopupLogged) {
                collectWeTypeWindowSnapshot(inputMethodService)?.also { snapshot ->
                    logWeTypeWindowSnapshot(
                        stage = stage,
                        order = state.lifecycleOrder,
                        attempt = 0,
                        snapshot = snapshot
                    )
                }
            }

            if (!state.blurEligible) return@runCatching
            scheduleWeTypeWindowBlur(inputMethodService, stage)
        }.onFailure {
            Log.i("Failed: Handle WeType window stage $stage")
            Log.i(it)
        }
    }

    private fun scheduleWeTypeWindowBlur(inputMethodService: Any, trigger: String) {
        val state = getWeTypeWindowState(inputMethodService)
        val token = ++state.blurApplyToken
        applyWeTypeWindowBlurWhenReady(inputMethodService, trigger, token, 0)
    }

    private fun applyWeTypeWindowBlurWhenReady(
        inputMethodService: Any,
        trigger: String,
        token: Int,
        attempt: Int
    ) {
        runCatching {
            val state = getWeTypeWindowState(inputMethodService)
            if (state.blurApplyToken != token) return

            val context = inputMethodService as? Context ?: return
            val softInputWindow = inputMethodService.invokeMethodAs<Any>("getWindow") ?: return
            val window = softInputWindow.invokeMethodAs<Window>("getWindow") ?: return
            val decorView = window.decorView ?: return
            val snapshot = collectWeTypeWindowSnapshot(inputMethodService) ?: return

            if (snapshot.isLayoutReady()) {
                // 避免在 SoftInputWindow 首次测量前改动窗口背景，先等视图具备稳定尺寸。
                applyWeTypeBackgroundCarrier(window, decorView, context, state, snapshot)
                if (!state.firstPopupLogged) {
                    logWeTypeWindowSnapshot(trigger, state.lifecycleOrder, attempt, snapshot)
                    state.firstPopupLogged = true
                }
                scheduleWeTypeBackgroundSettle(inputMethodService, token, WETYPE_BACKGROUND_SETTLE_RETRY)
                return
            }

            if (attempt >= WETYPE_BLUR_APPLY_MAX_RETRY) {
                if (!state.firstPopupLogged) {
                    logWeTypeWindowSnapshot(trigger, state.lifecycleOrder, attempt, snapshot)
                }
                return
            }

            decorView.post {
                applyWeTypeWindowBlurWhenReady(inputMethodService, trigger, token, attempt + 1)
            }
        }.onFailure {
            Log.i("Failed: Apply WeType window blur")
            Log.i(it)
        }
    }

    private fun scheduleWeTypeBackgroundSettle(
        inputMethodService: Any,
        token: Int,
        remaining: Int
    ) {
        if (remaining <= 0) return
        runCatching {
            val state = getWeTypeWindowState(inputMethodService)
            if (state.blurApplyToken != token) return

            val context = inputMethodService as? Context ?: return
            val softInputWindow = inputMethodService.invokeMethodAs<Any>("getWindow") ?: return
            val window = softInputWindow.invokeMethodAs<Window>("getWindow") ?: return
            val decorView = window.decorView ?: return

            decorView.post {
                runCatching {
                    val latestState = getWeTypeWindowState(inputMethodService)
                    if (latestState.blurApplyToken != token) return@runCatching

                    val latestSoftInputWindow =
                        inputMethodService.invokeMethodAs<Any>("getWindow") ?: return@runCatching
                    val latestWindow =
                        latestSoftInputWindow.invokeMethodAs<Window>("getWindow") ?: return@runCatching
                    val latestDecorView = latestWindow.decorView ?: return@runCatching
                    val snapshot =
                        collectWeTypeWindowSnapshot(inputMethodService) ?: return@runCatching
                    if (!snapshot.isLayoutReady()) {
                        scheduleWeTypeBackgroundSettle(inputMethodService, token, remaining - 1)
                        return@runCatching
                    }
                    applyWeTypeBackgroundCarrier(
                        latestWindow,
                        latestDecorView,
                        context,
                        latestState,
                        snapshot
                    )
                    scheduleWeTypeBackgroundSettle(inputMethodService, token, remaining - 1)
                }.onFailure {
                    Log.i("Failed: Settle WeType background carrier")
                    Log.i(it)
                }
            }
        }
    }

    private fun applyWeTypeWindowCorner(inputMethodService: Any) {
        runCatching {
            val softInputWindow = inputMethodService.invokeMethodAs<Any>("getWindow") ?: return
            val window = softInputWindow.invokeMethodAs<Window>("getWindow")
                ?: return
            val decorView = window.decorView ?: return
            val radius = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                30f,
                decorView.resources.displayMetrics
            )
            decorView.clipToOutline = true
            decorView.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    val width = view.width
                    val height = view.height
                    if (width <= 0 || height <= 0) return
                    val path = Path().apply {
                        addRoundRect(
                            0f,
                            0f,
                            width.toFloat(),
                            height.toFloat(),
                            floatArrayOf(
                                radius, radius,
                                radius, radius,
                                0f, 0f,
                                0f, 0f
                            ),
                            Path.Direction.CW
                        )
                    }
                    runCatching {
                        Outline::class.java.getMethod("setPath", Path::class.java)
                            .invoke(outline, path)
                    }.onFailure {
                        outline.setRoundRect(0, 0, width, height, radius)
                    }
                }
            }
            decorView.invalidateOutline()
        }
    }

    private fun getWeTypeWindowState(inputMethodService: Any): WeTypeWindowState =
        synchronized(weTypeWindowStates) {
            weTypeWindowStates.getOrPut(inputMethodService) { WeTypeWindowState() }
        }

    private fun collectWeTypeWindowSnapshot(inputMethodService: Any): WeTypeWindowSnapshot? {
        val softInputWindow = inputMethodService.invokeMethodAs<Any>("getWindow") ?: return null
        val window = softInputWindow.invokeMethodAs<Window>("getWindow") ?: return null
        return WeTypeWindowSnapshot(
            decorView = window.decorView?.toWeTypeViewSnapshot("decorView"),
            candidatesFrame = readWeTypeViewField(inputMethodService, "mCandidatesFrame")
                ?.toWeTypeViewSnapshot("mCandidatesFrame"),
            inputFrame = readWeTypeViewField(inputMethodService, "mInputFrame")
                ?.toWeTypeViewSnapshot("mInputFrame"),
            inputView = runCatching { inputMethodService.invokeMethodAs<View>("getInputView") }
                .getOrNull()
                ?.toWeTypeViewSnapshot("getInputView"),
            windowWidth = window.attributes.width,
            windowHeight = window.attributes.height,
            windowGravity = window.attributes.gravity
        )
    }

    private fun readWeTypeViewField(inputMethodService: Any, fieldName: String): View? =
        runCatching { inputMethodService.getObjectAs<View>(fieldName) }.getOrNull()

    private fun View.toWeTypeViewSnapshot(name: String): WeTypeViewSnapshot {
        val location = IntArray(2)
        runCatching { getLocationInWindow(location) }
        return WeTypeViewSnapshot(
            name = name,
            locationX = location[0],
            locationY = location[1],
            top = top,
            height = height,
            measuredHeight = measuredHeight
        )
    }

    private fun logWeTypeWindowSnapshot(
        stage: String,
        order: Int,
        attempt: Int,
        snapshot: WeTypeWindowSnapshot
    ) {
        Log.i(
            "WeType[first-popup][$stage] order=$order attempt=$attempt ready=${snapshot.isLayoutReady()} " +
                "window{width=${snapshot.windowWidth},height=${snapshot.windowHeight},gravity=${snapshot.windowGravity}} " +
                "backgroundTop=${snapshot.backgroundTop()}"
        )
        Log.i(
            "WeType[first-popup][$stage] " +
                describeWeTypeViewSnapshot("decorView", snapshot.decorView) + " " +
                describeWeTypeViewSnapshot("mCandidatesFrame", snapshot.candidatesFrame) + " " +
                describeWeTypeViewSnapshot("mInputFrame", snapshot.inputFrame) + " " +
                describeWeTypeViewSnapshot("getInputView", snapshot.inputView)
        )
    }

    private fun describeWeTypeViewSnapshot(
        name: String,
        snapshot: WeTypeViewSnapshot?
    ): String {
        if (snapshot == null) return "$name{null}"
        return "$name{locationInWindow=(${snapshot.locationX},${snapshot.locationY}),top=${snapshot.top}," +
            "height=${snapshot.height},measuredHeight=${snapshot.measuredHeight}}"
    }

    private fun applyWeTypeBackgroundCarrier(
        window: Window,
        decorView: View,
        context: Context,
        state: WeTypeWindowState,
        snapshot: WeTypeWindowSnapshot
    ) {
        window.setBackgroundBlurRadius(0)
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val decorGroup = decorView as? ViewGroup ?: return
        val decorHeight = snapshot.decorView?.height ?: decorGroup.height
        val backgroundTop = snapshot.backgroundTop().coerceIn(0, decorHeight)
        val backgroundHeight = (decorHeight - backgroundTop).coerceAtLeast(0)
        val carrier = ensureWeTypeBackgroundCarrier(context, decorGroup, state)
        val layoutParams = (carrier.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                backgroundHeight
            )
        layoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT
        layoutParams.height = backgroundHeight
        layoutParams.topMargin = backgroundTop
        carrier.layoutParams = layoutParams
        carrier.visibility = if (backgroundHeight > 0) View.VISIBLE else View.GONE
        carrier.background = createWeTypeBackgroundDrawable(carrier, context)
    }

    private fun ensureWeTypeBackgroundCarrier(
        context: Context,
        decorGroup: ViewGroup,
        state: WeTypeWindowState
    ): View {
        val existing = state.backgroundCarrier?.takeIf { it.parent === decorGroup }
        if (existing != null) return existing

        val carrier = View(context).apply {
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        decorGroup.addView(
            carrier,
            0,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0
            )
        )
        state.backgroundCarrier = carrier
        return carrier
    }

    private fun createWeTypeBackgroundDrawable(
        targetView: View,
        context: Context
    ): Drawable {
        val radius = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            30f,
            context.resources.displayMetrics
        )
        val color = WeTypeSettings.getCurrentBackgroundColorXposed(context)
        val blurRadius = WeTypeSettings.getBlurRadiusXposed()
        val tintDrawable = createWeTypeTintDrawable(color, radius)
        val blurDrawable = createInternalBackgroundBlurDrawable(targetView, blurRadius, radius)
        if (blurDrawable != null) {
            return LayerDrawable(arrayOf(blurDrawable, tintDrawable))
        }

        return tintDrawable
    }

    private fun createInternalBackgroundBlurDrawable(
        targetView: View,
        blurRadius: Int,
        cornerRadius: Float
    ): Drawable? {
        val viewRootImpl = runCatching { targetView.invokeMethodAs<Any>("getViewRootImpl") }.getOrNull()
            ?: return null
        val blurDrawable = runCatching {
            viewRootImpl.invokeMethodAs<Drawable>("createBackgroundBlurDrawable")
        }.getOrNull() ?: return null
        runCatching {
            blurDrawable.javaClass.getMethod(
                "setBlurRadius",
                Int::class.javaPrimitiveType
            ).invoke(blurDrawable, blurRadius)
        }
        runCatching {
            blurDrawable.javaClass.getMethod(
                "setColor",
                Int::class.javaPrimitiveType
            ).invoke(blurDrawable, Color.TRANSPARENT)
        }
        runCatching {
            blurDrawable.javaClass.getMethod(
                "setCornerRadius",
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType
            ).invoke(blurDrawable, cornerRadius, cornerRadius, 0f, 0f)
        }.recoverCatching {
            blurDrawable.javaClass.getMethod(
                "setCornerRadius",
                Float::class.javaPrimitiveType
            ).invoke(blurDrawable, cornerRadius)
        }
        return blurDrawable
    }

    private fun createWeTypeTintDrawable(
        color: Int,
        radius: Float
    ): Drawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadii = floatArrayOf(
            radius, radius,
            radius, radius,
            0f, 0f,
            0f, 0f
        )
        setColor(color)
    }

    private fun replaceWeTypeColor(color: Int): Int = WETYPE_COLOR_REPLACEMENTS[color] ?: color

    private fun replaceWeTypeColorStateList(colorStateList: ColorStateList): ColorStateList {
        val replacedColor = replaceWeTypeColor(colorStateList.defaultColor)
        if (replacedColor == colorStateList.defaultColor) return colorStateList
        return ColorStateList.valueOf(replacedColor)
    }

    /**
     * 跳过包名检查，直接开启输入法优化
     *
     * @param clazz 声明或继承字段的类
     */
    private fun hookSIsImeSupport(clazz: Class<*>) {
        kotlin.runCatching {
            clazz.putStaticObject("sIsImeSupport", 1)
            Log.i("Success:Hook field sIsImeSupport")
        }.onFailure {
            Log.i("Failed:Hook field sIsImeSupport")
            Log.i(it)
        }
    }

    /**
     * 小爱语音输入按钮失效修复
     *
     * @param clazz 声明或继承方法的类
     */
    private fun hookIsXiaoAiEnable(clazz: Class<*>) {
        kotlin.runCatching {
            clazz.getMethod("isXiaoAiEnable").hookReturnConstant(false)
        }.onFailure {
            Log.i("Failed:Hook method isXiaoAiEnable")
            Log.i(it)
        }
    }

    /**
     * 在适当的时机修改抬高区域背景颜色
     *
     * @param clazz 声明或继承字段的类
     */
    private fun setPhraseBgColor(clazz: Class<*>, forceTransparent: Boolean) {
        kotlin.runCatching {
            // 导航栏颜色被设置后, 将颜色存储起来并传递给常用语
            val setNavigationBarColorMethod = findMethod("com.android.internal.policy.PhoneWindow") {
                name == "setNavigationBarColor" && parameterTypes.sameAs(Int::class.java)
            }
            setNavigationBarColorMethod.hookBefore { param ->
                if (forceTransparent) {
                    bottomViewSourceColor = param.args[0] as? Int
                    param.args[0] = Color.TRANSPARENT
                }
            }
            setNavigationBarColorMethod.hookAfter { param ->
                if (forceTransparent) {
                    navBarColor = Color.TRANSPARENT
                    customizeBottomViewColor(clazz, true)
                    return@hookAfter
                }
                if (param.args[0] == 0) return@hookAfter

                navBarColor = param.args[0] as Int
                customizeBottomViewColor(clazz, false)
            }

            clazz.findMethod { name == "customizeBottomViewColor" }.hookBefore { param ->
                if (!forceTransparent) return@hookBefore
                if (param.args.size > 1 && param.args[1] is Int) {
                    param.args[1] = Color.TRANSPARENT
                }
            }

            // 当常用语被创建后, 将背景颜色设置为存储的导航栏颜色
            clazz.findMethod { name == "addMiuiBottomView" }.hookAfter {
                customizeBottomViewColor(clazz, forceTransparent)
            }
        }.onFailure {
            Log.i("Failed to set the color of the MiuiBottomView")
            Log.i(it)
        }
    }

    /**
     * 将导航栏颜色赋值给输入法优化的底图
     *
     * @param clazz 声明或继承字段的类
     */
    private fun customizeBottomViewColor(clazz: Class<*>, forceTransparent: Boolean) {
        if (forceTransparent) {
            val sourceColor = bottomViewSourceColor ?: navBarColor ?: Color.BLACK
            val contentColor = -0x1 - sourceColor
            clazz.invokeStaticMethodAuto(
                "customizeBottomViewColor",
                true,
                Color.TRANSPARENT,
                contentColor or -0x1000000,
                contentColor or 0x66000000
            )
            return
        }

        navBarColor?.let {
            val color = -0x1 - it
            clazz.invokeStaticMethodAuto(
                "customizeBottomViewColor",
                true, navBarColor, color or -0x1000000, color or 0x66000000
            )
        }
    }

    /**
     * 针对A10的修复切换输入法列表
     *
     * @param className 声明或继承方法的类的名称
     */
    private fun hookDeleteNotSupportIme(className: String, classLoader: ClassLoader) {
        kotlin.runCatching {
            findMethod(className, classLoader) { name == "deleteNotSupportIme" }
                .hookReturnConstant(null)
        }.onFailure {
            Log.i("Failed:Hook method deleteNotSupportIme")
            Log.i(it)
        }
    }

    /**
     * Hook 获取应用列表权限，为所有输入法强制提供获取输入法列表的权限。
     * 用于修复部分输入法（搜狗输入法小米版等）缺少获取输入法列表权限，导致切换输入法功能不能显示其他输入法的问题。
     * 理论等效于在输入法的AndroidManifest.xml中添加:
     * ```xml
     * <manifest>
     *     <queries>
     *         <intent>
     *             <action android:name="android.view.InputMethod" />
     *         </intent>
     *     </queries>
     * </manifest>
     * ```
     * 当前实现可能影响开机速度，如需此修复需手动设置系统框架作用域。
     */
    private fun startPermissionHook() {
        runCatching {
            findMethod("com.android.server.pm.AppsFilterUtils") {
                name == "canQueryViaComponents"
            }.hookAfter { param ->
                if (param.result == true) return@hookAfter
                val querying = param.args[0]
                val potentialTarget = param.args[1]
                if (!isIme(querying)) return@hookAfter
                if (!isIme(potentialTarget)) return@hookAfter
                param.result = true
            }
        }.onFailure {
            Log.i("Failed: Hook method canQueryViaComponents")
            Log.i(it)
        }
    }

    private fun isIme(androidPackage: Any): Boolean {
        val services = androidPackage.invokeMethodAs<List<Any>>("getServices")
        services?.forEach { service ->
            if (!service.invokeMethodAs<Boolean>("isExported")!!) return@forEach
            val intents = service.invokeMethodAs<List<Any>>("getIntents")
            intents?.forEach { intent ->
                val intentFilter = intent.invokeMethodAs<IntentFilter>("getIntentFilter")
                if (intentFilter?.matchAction("android.view.InputMethod") == true) {
                    return true
                }
            }
        }
        return false
    }
}
