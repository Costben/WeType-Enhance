package com.xposed.wetypehook.wetype.hook

import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.graphics.BlendMode
import android.graphics.BlendModeColorFilter
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import com.xposed.wetypehook.BuildConfig
import com.xposed.wetypehook.PropertyUtils
import com.xposed.wetypehook.xposed.HookEnvironment
import com.xposed.wetypehook.xposed.Log
import java.io.File
import java.lang.ref.WeakReference
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ColorOS 原生材质胶囊：阶段三方案（`STAGE3_INTEGRATION_PLAN.md`）的落地实现。
 *
 * ## 默认关闭，完全可撤销
 * 仅由系统属性 `debug.wetype.coloros.capsule=1` 驱动；关闭时本组件不创建任何 View、不触碰任何
 * RenderNode，模块行为与现状完全一致。开启后仍需通过四重门禁（ROM 指纹 / OPlus API 版本 /
 * 系统材质开关 / 反射可达性），任一不过即 `FALLBACK_INACTIVE`，退回既有 `WeTypeColorOsMaterial`
 * 背板，绝不向上抛出异常。
 *
 * ## 零崩溃契约
 * 所有 OEM 类通过 `Class.forName` 动态加载，所有 `getMethod`/`invoke` 都被 `runCatching` 或
 * try/catch 包住，捕获 `Throwable`。任何单步失败只记录证据并局部降级。
 *
 * ## 证据等级
 * 每次反射调用记录四态之一：`OK`（返回 true/void）、`RETURNED_FALSE`（返回 false）、
 * `THREW`（异常类型+消息）、`ABSENT`（类/方法不存在）。参数一律来自实际 layout 与密度换算，
 * 不在尺寸确定前下发（F4）。
 */
internal class WeTypeColorOsCapsuleManager(
    private val decorView: ViewGroup,
    private val context: Context
) : ComponentCallbacks {

    enum class State {
        UNINITIALIZED,
        ATTACHED_PENDING_LAYOUT,
        CONFIGURED_ACTIVE,
        THEME_DIRTY,
        GEOMETRY_DIRTY,
        INACTIVE_HIDDEN,
        FALLBACK_INACTIVE,
        CLEARED_DETACHED
    }

    private var carrier: FrameLayout? = null
    private var capsule: View? = null
    private var bridge: WeTypeColorOsNativeBridge? = null
    private var anchorRef: WeakReference<View>? = null
    private var anchorLayoutListener: View.OnLayoutChangeListener? = null
    private var state = State.UNINITIALIZED
    private var configured = false
    private var appliedDark: Boolean? = null
    private var lastRadius = -1f
    private var callbacksRegistered = false
    private var retriesPosted = false
    private var pendingLogged = false

    private val anchorLocation = IntArray(2)
    private val decorLocation = IntArray(2)

    val currentState: State get() = state

    /** FSM：挂载载体并同步一次几何/配方。幂等；门禁不过则保持 FALLBACK_INACTIVE。 */
    fun sync(anchor: View, isDark: Boolean) {
        runCatching {
            if (carrier == null && !attach()) return
            anchorRef = WeakReference(anchor)
            ensureAnchorListener(anchor)
            scheduleRetries()
            anchor.getLocationInWindow(anchorLocation)
            decorView.getLocationInWindow(decorLocation)
            val left = anchorLocation[0] - decorLocation[0]
            val top = anchorLocation[1] - decorLocation[1]
            val width = anchor.width
            val height = anchor.height
            if (width <= 0 || height <= 0 || !anchor.isShown) {
                state = State.ATTACHED_PENDING_LAYOUT
                if (!pendingLogged) {
                    pendingLogged = true
                    evidence(
                        "pending",
                        "anchor=${anchor.javaClass.name} size=${width}x$height " +
                            "shown=${anchor.isShown} attached=${anchor.isAttachedToWindow} " +
                            "loc=($left,$top)"
                    )
                }
                return
            }
            val density = context.resources.displayMetrics.density
            val marginH = (WeTypeColorOsCapsuleRecipe.CAPSULE_MARGIN_DP * density + 0.5f).toInt()
            val capsuleWidth = (width - marginH * 2).coerceAtLeast(1)
            applyLayout(left + marginH, top, capsuleWidth, height)

            if (!configured) {
                configure(anchor, height, isDark)
                return
            }
            if (appliedDark != isDark) {
                state = State.THEME_DIRTY
                applyTheme(isDark, density)
            }
            val radius = height / 2f
            if (radius != lastRadius) {
                state = State.GEOMETRY_DIRTY
                bridge?.applyCorner(radius)
                lastRadius = radius
                state = State.CONFIGURED_ACTIVE
            }
            carrier?.visibility = View.VISIBLE
            capsule?.visibility = View.VISIBLE
        }.onFailure {
            Log.e("Failed: Sync ColorOS native material capsule")
            Log.e(it)
            state = State.FALLBACK_INACTIVE
        }
    }

    /** FSM：键盘收起时仅隐藏，保留 Bridge 实例避免重复反射构造。 */
    fun hide() {
        if (carrier == null) return
        state = State.INACTIVE_HIDDEN
        runCatching {
            carrier?.visibility = View.INVISIBLE
            capsule?.visibility = View.INVISIBLE
        }
        evidence("lifecycle", "INACTIVE_HIDDEN")
    }

    /** FSM：显式可逆清理 + 移除自建 View，切断监听器与引用。幂等。 */
    fun clear() {
        if (state == State.CLEARED_DETACHED && carrier == null) return
        if (callbacksRegistered) {
            runCatching { context.unregisterComponentCallbacks(this) }
            callbacksRegistered = false
        }
        bridge?.let { nativeBridge ->
            nativeBridge.clear()
            evidence("clear", WeTypeColorOsNativeBridge.describe(nativeBridge.recorded))
        }
        bridge = null
        anchorLayoutListener?.let { listener ->
            anchorRef?.get()?.removeOnLayoutChangeListener(listener)
        }
        anchorLayoutListener = null
        capsule?.let { view -> (view.parent as? ViewGroup)?.removeView(view) }
        carrier?.let { group -> (group.parent as? ViewGroup)?.removeView(group) }
        capsule = null
        carrier = null
        anchorRef = null
        configured = false
        appliedDark = null
        lastRadius = -1f
        retriesPosted = false
        pendingLogged = false
        state = State.CLEARED_DETACHED
        evidence("lifecycle", "CLEARED_DETACHED")
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        if (anchorRef?.get() == null) return
        if (!configured) return
        val isDark = newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        if (isDark == appliedDark) return
        runCatching {
            state = State.THEME_DIRTY
            applyTheme(isDark, context.resources.displayMetrics.density)
        }.onFailure { Log.e(it) }
    }

    override fun onLowMemory() = Unit

    private fun applyTheme(isDark: Boolean, density: Float) {
        val b = bridge ?: return
        val start = b.recorded.size
        b.applyStroke(WeTypeColorOsCapsuleRecipe.capsule3(isDark), density)
        b.applyBlur(isDark)
        b.refreshMaterialEffect()
        appliedDark = isDark
        state = State.CONFIGURED_ACTIVE
        evidence(
            "theme",
            "day/night switched dark=$isDark\n" +
                WeTypeColorOsNativeBridge.describe(b.recorded.drop(start))
        )
    }

    private fun applyLayout(left: Int, top: Int, width: Int, height: Int) {
        val view = capsule ?: return
        val current = view.layoutParams as? FrameLayout.LayoutParams
        if (current != null && current.width == width && current.height == height &&
            current.leftMargin == left && current.topMargin == top
        ) {
            return
        }
        view.layoutParams = FrameLayout.LayoutParams(width, height).apply {
            leftMargin = left
            topMargin = top
        }
        view.requestLayout()
    }

    private fun attach(): Boolean {
        val gate = WeTypeColorOsCapsuleGate.evaluate(context)
        evidence("gate", gate.describe())
        if (!gate.passed) {
            state = State.FALLBACK_INACTIVE
            return false
        }

        val newCarrier = FrameLayout(context).apply {
            visibility = View.INVISIBLE
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val newCapsule = FrameLayout(context).apply {
            setWillNotDraw(false)
            clipChildren = false
            clipToPadding = false
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            background = ColorDrawable(Color.TRANSPARENT)
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    val radius = view.height / 2f
                    if (view.width > 0 && view.height > 0 && radius > 0f) {
                        outline.setRoundRect(0, 0, view.width, view.height, radius)
                    } else {
                        outline.setRect(0, 0, view.width, view.height)
                    }
                }
            }
            // 原生路径把圆角交给 OEM material util，自身不裁剪。
            clipToOutline = false
        }
        newCarrier.addView(
            newCapsule,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        val index = minOf(1, decorView.childCount)
        decorView.addView(
            newCarrier,
            index,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        carrier = newCarrier
        capsule = newCapsule
        bridge = WeTypeColorOsNativeBridge(newCapsule)
        state = State.ATTACHED_PENDING_LAYOUT
        if (!callbacksRegistered) {
            runCatching { context.registerComponentCallbacks(this) }
            callbacksRegistered = true
        }
        evidence("lifecycle", "ATTACHED_PENDING_LAYOUT index=$index")
        return true
    }

    private fun configure(anchor: View, anchorHeight: Int, isDark: Boolean) {
        val b = bridge ?: return
        val density = context.resources.displayMetrics.density
        val ok = b.applyOuterShadow(density)
        val strokeOk = b.applyStroke(WeTypeColorOsCapsuleRecipe.capsule3(isDark), density)
        val blurOk = b.applyBlur(isDark)
        val radius = anchorHeight / 2f
        val cornerOk = b.applyCorner(radius)
        b.refreshMaterialEffect()
        configured = true
        appliedDark = isDark
        lastRadius = radius
        state = if (ok || strokeOk || blurOk || cornerOk) {
            State.CONFIGURED_ACTIVE
        } else {
            State.FALLBACK_INACTIVE
        }
        evidence(
            "apply",
            "CONFIGURED_ACTIVE dark=$isDark radius=$radius density=$density " +
                "anchor=${anchor.javaClass.name} anchorSize=${anchor.width}x${anchor.height} " +
                "anchorLoc=(${anchorLocation[0]},${anchorLocation[1]})\n" +
                WeTypeColorOsNativeBridge.describe(b.recorded)
        )
        carrier?.visibility = View.VISIBLE
        capsule?.visibility = View.VISIBLE
        capsule?.post {
            val location = IntArray(2)
            capsule?.getLocationOnScreen(location)
            evidence(
                "geometry",
                "capsuleScreenLoc=(${location[0]},${location[1]}) " +
                    "size=${capsule?.width}x${capsule?.height} shown=${capsule?.isShown} " +
                    "capsuleVis=${capsule?.visibility} carrierVis=${carrier?.visibility} " +
                    "carrierSize=${carrier?.width}x${carrier?.height}"
            )
        }
    }

    private fun ensureAnchorListener(anchor: View) {
        if (anchorLayoutListener != null) return
        val listener = View.OnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            runCatching {
                val isDark = context.resources.configuration.uiMode and
                    Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
                sync(view, isDark)
            }.onFailure { Log.e(it) }
        }
        anchor.addOnLayoutChangeListener(listener)
        anchorLayoutListener = listener
    }

    private fun scheduleRetries() {
        if (retriesPosted) return
        retriesPosted = true
        val retry = {
            val anchor = anchorRef?.get()
            if (anchor != null) {
                runCatching {
                    val isDark = context.resources.configuration.uiMode and
                        Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
                    sync(anchor, isDark)
                }.onFailure { Log.e(it) }
            }
            Unit
        }
        HookEnvironment.postTracked(decorView, 48L, retry)
        HookEnvironment.postTracked(decorView, 144L, retry)
        HookEnvironment.postTracked(decorView, 400L, retry)
        HookEnvironment.postTracked(decorView, 900L, retry)
    }

    private fun evidence(stage: String, detail: String) {
        runCatching {
            val file = File(context.filesDir, EVIDENCE_FILE)
            val header = if (evidenceStarted.compareAndSet(false, true)) {
                "=== WeTypeColorOsCapsule evidence pid=${Process.myPid()} " +
                    "module=${BuildConfig.VERSION_NAME}/${BuildConfig.VERSION_CODE} ===\n"
            } else {
                ""
            }
            if (header.isNotEmpty()) file.writeText(header)
            file.appendText("[$stage] $detail\n")
        }.onFailure { Log.e(it) }
        Log.i("WeTypeCapsule[$stage] $detail")
    }

    companion object {
        const val EVIDENCE_FILE = "coloros_capsule_stage4.log"
        private val evidenceStarted = AtomicBoolean(false)
    }
}

/** 阶段二验证通过的配方参数；与相册 `Recipe.kt` 一一对应。 */
internal object WeTypeColorOsCapsuleRecipe {

    const val PROP_MASTER = "debug.wetype.coloros.capsule"

    const val BLUR_RADIUS_PX = 25.0f
    const val BLUR_LAYER1_LIGHT = 0xB3666666.toInt()
    const val BLUR_LAYER2_LIGHT = 0x99F5F5F5.toInt()
    const val BLUR_LAYER1_DARK = 0x80666666.toInt()
    const val BLUR_LAYER2_DARK = 0xB3262626.toInt()

    const val EDGE_TYPE_RECTANGLE = 1

    const val ELEVATION_DP = 24.0f
    const val LIGHT_X = -1.0f
    const val LIGHT_Y_DP = -320.0f
    const val LIGHT_Z_DP = 0.0f
    const val LIGHT_R_DP = 1600.0f
    const val LIGHT_BLUR_DP = 0.0f
    const val SHADOW_ALPHA = 45
    const val CAUSTIC_COLOR = 0x42FFFFFF.toInt()
    const val CAUSTIC_LAYOUT_B_DP = 4.9f
    const val CAUSTIC_SDF_DP = 10.4f
    const val CAUSTIC_FACTOR0 = 0.6f
    const val CAUSTIC_FACTOR1 = 0.6f
    const val CAUSTIC_SPEED = 18.0f
    const val CAUSTIC_LUMINANCE_THRESHOLD = 0.45f
    const val CAUSTIC_LUMINANCE_INTENSITY = 0.6f

    const val CORNER_WEIGHT = 1.0f

    const val CAPSULE_MARGIN_DP = 12f

    data class StrokeParams(
        val strokeType: Int,
        val edgeAlpha: Float,
        val edgeWidthDp: Float,
        val edgeAngle: Float,
        val shadowFadeIn: Float,
        val shadowFadeOut: Float,
        val shadowFadeScale: Float
    )

    fun capsule3(isDark: Boolean): StrokeParams =
        if (isDark) {
            StrokeParams(1, 0.80f, 2.5f, 0.0f, 0.03f, 1.0f, 1.0f)
        } else {
            StrokeParams(1, 0.25f, 2.5f, 0.0f, 0.10f, 1.0f, 1.0f)
        }

    fun sizePx(dp: Float, density: Float): Int {
        val value = dp * density
        return if (value >= 0f) (value + 0.5f).toInt() else -((-value + 0.5f).toInt())
    }

    fun offsetPx(dp: Float, density: Float): Int = (dp * density).toInt()

    fun lightGeometryArgs(density: Float): FloatArray = floatArrayOf(
        LIGHT_X,
        sizePx(LIGHT_Y_DP, density).toFloat(),
        sizePx(LIGHT_Z_DP, density).toFloat(),
        sizePx(LIGHT_R_DP, density).toFloat(),
        sizePx(LIGHT_BLUR_DP, density).toFloat()
    )

    fun causticLayoutArgs(density: Float): IntArray = intArrayOf(
        0,
        0,
        0,
        offsetPx(CAUSTIC_LAYOUT_B_DP, density)
    )

    fun edgeArgs(p: StrokeParams, density: Float): FloatArray =
        floatArrayOf(p.edgeWidthDp * density, p.edgeAlpha, p.edgeAngle)

    fun shadowArgs(p: StrokeParams): FloatArray =
        floatArrayOf(p.shadowFadeScale, p.shadowFadeIn, p.shadowFadeOut)

    fun blurBlendMode(kind: Int): BlendMode = when (kind) {
        BLEND_COLOR_DODGE -> BlendMode.COLOR_DODGE
        BLEND_OVERLAY -> BlendMode.OVERLAY
        else -> BlendMode.LUMINOSITY
    }

    const val BLEND_COLOR_DODGE = 1
    const val BLEND_OVERLAY = 2
    const val BLEND_LUMINOSITY = 3
}

/** 四重准入门禁。 */
internal object WeTypeColorOsCapsuleGate {

    data class Result(
        val requested: Boolean,
        val rom: Boolean,
        val api: Boolean,
        val switches: Boolean,
        val reflection: Boolean,
        val detail: String
    ) {
        val passed: Boolean get() = requested && rom && api && switches && reflection

        fun describe(): String =
            "requested=$requested rom=$rom api=$api switches=$switches reflection=$reflection " +
                "passed=$passed | $detail"
    }

    fun isRequested(): Boolean =
        runCatching { PropertyUtils[WeTypeColorOsCapsuleRecipe.PROP_MASTER, null] }.getOrNull() == "1"

    fun evaluate(context: Context): Result {
        val oplusRom = prop("ro.build.version.oplusrom")
        val miRom = prop("ro.mi.os.version.code")
        val oplusApi = prop("ro.build.version.oplus.api")?.toIntOrNull()
        val oplusSubApi = prop("ro.build.version.oplus.sub_api")?.toIntOrNull()
        val animLevel = prop("persist.sys.oplus.anim_level")?.toIntOrNull()

        val romOk = !oplusRom.isNullOrEmpty() && miRom.isNullOrEmpty()
        val apiOk = checkOPlusViewSubSDK(oplusApi, oplusSubApi, 40, 12)

        val resolver = context.contentResolver
        val blurEnabled = setting(Settings.System::class.java, resolver, "system_material_blur_enable")
        val strokeEnabled = setting(Settings.System::class.java, resolver, "system_material_stroke_enable")
        val lowPower = setting(Settings.Global::class.java, resolver, "low_power")
        val animOk = animLevel != null && animLevel in 1..2
        val switchOk = blurEnabled == 1 && strokeEnabled == 1 && lowPower == 0 && animOk

        val reflectionOk = reflectionReachable()

        val detail = "oplusrom=$oplusRom mirom=$miRom oplus_api=$oplusApi sub_api=$oplusSubApi " +
            "blur=$blurEnabled stroke=$strokeEnabled low_power=$lowPower anim=$animLevel sdk=${Build.VERSION.SDK_INT}"
        return Result(
            requested = isRequested(),
            rom = romOk,
            api = apiOk,
            switches = switchOk,
            reflection = reflectionOk,
            detail = detail
        )
    }

    /** 复刻 `COUIEffectVersionUtil.checkOPlusViewSubSDK`。 */
    fun checkOPlusViewSubSDK(os: Int?, sub: Int?, major: Int, requiredSub: Int): Boolean {
        if (os == null) return false
        return if (os > major) true else os == major && (sub ?: 0) >= requiredSub
    }

    fun reflectionReachable(): Boolean {
        val util = load("com.oplus.view.material.OplusMaterialUtil")
        val edge = load("com.oplus.view.material.OplusMaterialEdgeParams")
        val shadow = load("com.oplus.view.material.OplusMaterialShadowParams")
        val corner = load("com.oplus.view.material.OplusMaterialCornerParams")
        val background = load("com.oplus.view.OplusViewBackgroundRenderEffect")
        val oplusView = load("com.oplus.view.OplusView")
        if (util == null || edge == null || shadow == null || corner == null ||
            background == null || oplusView == null
        ) {
            return false
        }
        return runCatching {
            util.getMethod("setEdgeParams", View::class.java, edge)
            util.getMethod("setShadowParams", View::class.java, shadow)
            util.getMethod("setCornerParams", View::class.java, corner)
            util.getMethod("setOutlineCausticShadowColor", View::class.java, Int::class.java)
            util.getMethod(
                "setOutlineCausticShadowLayout",
                View::class.java, Int::class.java, Int::class.java, Int::class.java, Int::class.java
            )
            util.getMethod(
                "setOutlineCausticShadowParams",
                View::class.java, Float::class.java, Float::class.java, Float::class.java,
                Float::class.java, Float::class.java, Float::class.java
            )
            background.getMethod("setBackgroundRenderEffect", RenderEffect::class.java, View::class.java)
            oplusView.getMethod("setShadowClippingEnabled", View::class.java, Boolean::class.java)
            oplusView.getMethod(
                "setOverrideLightSourceGeometry",
                Float::class.java, Float::class.java, Float::class.java,
                Float::class.java, Float::class.java
            )
            true
        }.getOrDefault(false)
    }

    private fun setting(
        holder: Class<*>,
        resolver: android.content.ContentResolver,
        key: String
    ): Int? = runCatching {
        if (holder == Settings.Global::class.java) {
            Settings.Global.getInt(resolver, key, -1)
        } else {
            Settings.System.getInt(resolver, key, -1)
        }
    }.getOrNull()?.takeIf { it >= 0 }

    private fun prop(name: String): String? =
        runCatching { PropertyUtils[name, ""] }.getOrNull()?.takeIf { it.isNotEmpty() }

    private fun load(name: String): Class<*>? = runCatching { Class.forName(name) }.getOrNull()
}

/**
 * 有限反射桥：只解析具名类与具名方法，逐步记录四态结果。
 * 反射自阶段二 `NativeMaterialBridge.kt`，接口顺序与相册原生路径一致。
 */
internal class WeTypeColorOsNativeBridge(private val view: View) {

    enum class Status { OK, RETURNED_FALSE, ABSENT, THREW }

    data class CallResult(
        val step: String,
        val signature: String,
        val status: Status,
        val detail: String
    )

    private val calls = mutableListOf<CallResult>()

    val recorded: List<CallResult> get() = calls.toList()

    private val materialUtil: Class<*>? = load("com.oplus.view.material.OplusMaterialUtil")
    private val edgeParamsClass: Class<*>? = load("com.oplus.view.material.OplusMaterialEdgeParams")
    private val baseParamsClass: Class<*>? = load("com.oplus.view.material.OplusMaterialBaseParams")
    private val shadowParamsClass: Class<*>? = load("com.oplus.view.material.OplusMaterialShadowParams")
    private val cornerParamsClass: Class<*>? = load("com.oplus.view.material.OplusMaterialCornerParams")
    private val backgroundEffectClass: Class<*>? = load("com.oplus.view.OplusViewBackgroundRenderEffect")
    private val oplusViewClass: Class<*>? = load("com.oplus.view.OplusView")

    fun applyOuterShadow(density: Float): Boolean {
        val alpha = WeTypeColorOsCapsuleRecipe.SHADOW_ALPHA
        val shadowColor = Color.argb(alpha, 0, 0, 0)
        view.setOutlineAmbientShadowColor(shadowColor)
        view.setOutlineSpotShadowColor(shadowColor)
        view.elevation =
            WeTypeColorOsCapsuleRecipe.sizePx(WeTypeColorOsCapsuleRecipe.ELEVATION_DP, density).toFloat()
        calls += CallResult(
            "outerShadow",
            "setOutlineAmbientShadowColor/SpotShadowColor(argb($alpha,0,0,0)); setElevation(${view.elevation})",
            Status.OK,
            "applied"
        )
        var ok = applyLightSourceGeometry(density)
        ok = setCausticColor(WeTypeColorOsCapsuleRecipe.CAUSTIC_COLOR) && ok
        ok = setCausticLayout(density) && ok
        ok = setCausticParams(density) && ok
        return ok
    }

    fun applyStroke(p: WeTypeColorOsCapsuleRecipe.StrokeParams, density: Float): Boolean {
        var ok = setEdgeParams(p, density)
        ok = setShadowParams(p) && ok
        return ok
    }

    fun applyBlur(dark: Boolean): Boolean {
        var ok = setShadowClippingEnabled(true)
        ok = applyBackgroundRenderEffect(dark) && ok
        return ok
    }

    fun applyCorner(radiusPx: Float): Boolean {
        val clazz = cornerParamsClass ?: return absent("corner", "OplusMaterialCornerParams.<init>(F,F)")
        return try {
            val ctor = clazz.getConstructor(Float::class.java, Float::class.java)
            val params = ctor.newInstance(radiusPx, WeTypeColorOsCapsuleRecipe.CORNER_WEIGHT)
            staticBoolCall(
                "corner",
                "setCornerParams(View,CornerParams radius=$radiusPx)",
                arrayOf(View::class.java, clazz),
                arrayOf(view, params)
            )
        } catch (t: Throwable) {
            threw("corner", "OplusMaterialCornerParams.<init>(F,F)", t)
            false
        }
    }

    /** 单独下发边线（edge）参数：四态测试需要 edge 与 shadow 各自独立的 type。 */
    fun applyEdge(type: Int, alpha: Float, widthDp: Float, angle: Float, density: Float): Boolean {
        val clazz = edgeParamsClass
            ?: return absent("edge", "OplusMaterialEdgeParams.<init>(I,F,F,F)")
        val widthPx = widthDp * density
        return try {
            val ctor = clazz.getConstructor(
                Int::class.java, Float::class.java, Float::class.java, Float::class.java
            )
            val params = ctor.newInstance(type, widthPx, alpha, angle)
            staticBoolCall(
                "edge",
                "setEdgeParams(View,EdgeParams type=$type widthPx=$widthPx alpha=$alpha angle=$angle)",
                arrayOf(View::class.java, clazz),
                arrayOf(view, params)
            )
        } catch (t: Throwable) {
            threw("edge", "OplusMaterialEdgeParams.<init>(I,F,F,F)", t)
            false
        }
    }

    /** 单独下发内阴影（shadow）参数：`fadeIn` 为内发光、`fadeOut` 为外扩散光。 */
    fun applyShadow(type: Int, scale: Float, fadeIn: Float, fadeOut: Float): Boolean {
        val clazz = shadowParamsClass
            ?: return absent("shadow", "OplusMaterialShadowParams.<init>(I,F,F,F)")
        return try {
            val ctor = clazz.getConstructor(
                Int::class.java, Float::class.java, Float::class.java, Float::class.java
            )
            val params = ctor.newInstance(type, scale, fadeIn, fadeOut)
            staticBoolCall(
                "shadow",
                "setShadowParams(View,ShadowParams type=$type scale=$scale fadeIn=$fadeIn fadeOut=$fadeOut)",
                arrayOf(View::class.java, clazz),
                arrayOf(view, params)
            )
        } catch (t: Throwable) {
            threw("shadow", "OplusMaterialShadowParams.<init>(I,F,F,F)", t)
            false
        }
    }

    /**
     * 下发 base（maskColor + innerBounds）。COUI 的描边 effect 依赖 mask 提供材质源，
     * 单独只给 edge/shadow 可能没有任何可绘制内容。
     */
    fun applyBase(maskColor: Int, width: Int, height: Int): Boolean {
        val clazz = baseParamsClass
            ?: return absent("base", "OplusMaterialBaseParams.<init>(I,Rect)")
        return try {
            val ctor = clazz.getConstructor(Int::class.java, Rect::class.java)
            val params = ctor.newInstance(maskColor, Rect(0, 0, width, height))
            staticBoolCall(
                "base",
                "setBaseParams(View,BaseParams mask=0x${Integer.toHexString(maskColor)} bounds=${width}x${height})",
                arrayOf(View::class.java, clazz),
                arrayOf(view, params)
            )
        } catch (t: Throwable) {
            threw("base", "OplusMaterialBaseParams.<init>(I,Rect)", t)
            false
        }
    }

    /** 清空 base mask。否则 base 会跨状态泄漏，污染后续对照。 */
    fun clearBase(): Boolean {
        val clazz = baseParamsClass ?: return absent("base=null", "setBaseParams(View,null)")
        return staticBoolCall(
            "base=null",
            "setBaseParams(View,null)",
            arrayOf(View::class.java, clazz),
            arrayOf(view, null)
        )
    }

    /** 焦散开关：开启走系统 recipe 的 color/layout/params，关闭时全部归零。 */
    fun applyCaustic(enabled: Boolean, density: Float): Boolean {
        return if (enabled) {
            var ok = setCausticColor(WeTypeColorOsCapsuleRecipe.CAUSTIC_COLOR)
            ok = setCausticLayout(density) && ok
            ok = setCausticParams(density) && ok
            ok
        } else {
            var ok = setCausticColor(0)
            ok = setCausticLayoutValues(0, 0, 0, 0) && ok
            ok = staticBoolCall(
                "caustic.params=0",
                "setOutlineCausticShadowParams(View,0,0,0,0,0,0)",
                arrayOf(
                    View::class.java,
                    Float::class.java, Float::class.java, Float::class.java,
                    Float::class.java, Float::class.java, Float::class.java
                ),
                arrayOf(view, 0f, 0f, 0f, 0f, 0f, 0f)
            ) && ok
            ok
        }
    }

    /** 与 [applyBlur] 相配的清空：撤销节点级 background RenderEffect。 */
    fun clearBackdrop(): Boolean {
        val clazz = backgroundEffectClass
            ?: return absent("backdrop", "setBackgroundRenderEffect(null,View)")
        return try {
            val method = clazz.getMethod("setBackgroundRenderEffect", RenderEffect::class.java, View::class.java)
            method.invoke(null, null, view)
            calls += CallResult("backdrop", "setBackgroundRenderEffect(null,View)", Status.OK, "void")
            true
        } catch (t: Throwable) {
            threw("backdrop", "setBackgroundRenderEffect(null,View)", t)
            false
        }
    }

    private fun getRenderNode(v: View): Any? {
        runCatching {
            val wrapper = View::class.java.getMethod("getViewWrapper").invoke(v)
            val node = wrapper?.javaClass?.getMethod("getRenderNode")?.invoke(wrapper)
            if (node != null) return node
        }
        runCatching {
            val field = View::class.java.getDeclaredField("mRenderNode").apply { isAccessible = true }
            val node = field.get(v)
            if (node != null) return node
        }
        return null
    }

    /**
     * 打开/关闭 View 的 RenderNode 材质总开关（`RenderNode.setOplusMaterialEffect`）。
     * 底层 HWUI 会在首次设为 true 时创建 Material Filter 并缓存；若 Filter 已存在则不重建。
     * 因此更新参数时必须先设为 false 销毁缓存，再设为 true 触发重建。
     */
    fun setMaterialEffect(enable: Boolean): Boolean = try {
        val renderNode = getRenderNode(view)
        if (renderNode == null) {
            absent("materialEffect", "renderNode not resolvable")
            false
        } else {
            val method = Class.forName("android.graphics.RenderNode")
                .getMethod("setOplusMaterialEffect", Boolean::class.javaPrimitiveType)
            val res = method.invoke(renderNode, enable) as? Boolean ?: false
            recordBoolean("materialEffect", "setOplusMaterialEffect($enable)", res)
            res
        }
    } catch (t: Throwable) {
        threw("materialEffect", "setOplusMaterialEffect($enable)", t)
        false
    }

    fun refreshMaterialEffect(): Boolean {
        setMaterialEffect(false)
        return setMaterialEffect(true)
    }

    fun clear() {
        setMaterialEffect(false)
        setCausticColor(0)
        setCausticLayoutValues(0, 0, 0, 0)
        staticBoolCall(
            "clear.caustic.params",
            "setOutlineCausticShadowParams(View,0,0,0,0,0,0)",
            arrayOf(
                View::class.java,
                Float::class.java, Float::class.java, Float::class.java,
                Float::class.java, Float::class.java, Float::class.java
            ),
            arrayOf(view, 0f, 0f, 0f, 0f, 0f, 0f)
        )
        val none = WeTypeColorOsCapsuleRecipe.StrokeParams(0, 0f, 0f, 0f, 0f, 0f, 0f)
        setEdgeParams(none, 1f)
        setShadowParams(none)
        backgroundEffectClass?.let { clazz ->
            try {
                val method = clazz.getMethod("setBackgroundRenderEffect", RenderEffect::class.java, View::class.java)
                method.invoke(null, null, view)
                calls += CallResult("clear.blur", "setBackgroundRenderEffect(null,View)", Status.OK, "void")
            } catch (t: Throwable) {
                threw("clear.blur", "setBackgroundRenderEffect(null,View)", t)
            }
        }
        val corner = cornerParamsClass
        if (corner != null && materialUtil != null) {
            try {
                val method = materialUtil.getMethod("setCornerParams", View::class.java, corner)
                method.invoke(null, view, null)
                calls += CallResult("clear.corner", "setCornerParams(View,null)", Status.OK, "void")
            } catch (t: Throwable) {
                threw("clear.corner", "setCornerParams(View,null)", t)
            }
        }
        view.elevation = 0f
        view.background = null
    }

    private fun applyLightSourceGeometry(density: Float): Boolean {
        val clazz = oplusViewClass
            ?: return absent("outerShadow.light", "OplusView.<init>(View); setOverrideLightSourceGeometry")
        val args = WeTypeColorOsCapsuleRecipe.lightGeometryArgs(density)
        return try {
            val ctor: Constructor<*> = clazz.getConstructor(View::class.java)
            val instance = ctor.newInstance(view)
            val method: Method = clazz.getMethod(
                "setOverrideLightSourceGeometry",
                Float::class.java, Float::class.java, Float::class.java,
                Float::class.java, Float::class.java
            )
            method.invoke(instance, args[0], args[1], args[2], args[3], args[4])
            calls += CallResult(
                "outerShadow.light",
                "setOverrideLightSourceGeometry(F,F,F,F,F)=(${args.joinToString(",")})",
                Status.OK,
                "void"
            )
            true
        } catch (t: Throwable) {
            threw("outerShadow.light", "setOverrideLightSourceGeometry(F,F,F,F,F)", t)
            false
        }
    }

    private fun setCausticColor(color: Int): Boolean = staticBoolCall(
        "outerShadow.caustic.color",
        "setOutlineCausticShadowColor(View,I)",
        arrayOf(View::class.java, Int::class.java),
        arrayOf(view, color)
    )

    private fun setCausticLayout(density: Float): Boolean {
        val a = WeTypeColorOsCapsuleRecipe.causticLayoutArgs(density)
        return setCausticLayoutValues(a[0], a[1], a[2], a[3])
    }

    private fun setCausticLayoutValues(l: Int, t: Int, r: Int, b: Int): Boolean = staticBoolCall(
        "outerShadow.caustic.layout",
        "setOutlineCausticShadowLayout(View,$l,$t,$r,$b)",
        arrayOf(View::class.java, Int::class.java, Int::class.java, Int::class.java, Int::class.java),
        arrayOf(view, l, t, r, b)
    )

    private fun setCausticParams(density: Float): Boolean {
        val sdf = WeTypeColorOsCapsuleRecipe.offsetPx(WeTypeColorOsCapsuleRecipe.CAUSTIC_SDF_DP, density).toFloat()
        return staticBoolCall(
            "outerShadow.caustic.params",
            "setOutlineCausticShadowParams(View,F,F,F,F,F,F)",
            arrayOf(
                View::class.java,
                Float::class.java, Float::class.java, Float::class.java,
                Float::class.java, Float::class.java, Float::class.java
            ),
            arrayOf(
                view, sdf,
                WeTypeColorOsCapsuleRecipe.CAUSTIC_FACTOR0,
                WeTypeColorOsCapsuleRecipe.CAUSTIC_FACTOR1,
                WeTypeColorOsCapsuleRecipe.CAUSTIC_SPEED,
                WeTypeColorOsCapsuleRecipe.CAUSTIC_LUMINANCE_THRESHOLD,
                WeTypeColorOsCapsuleRecipe.CAUSTIC_LUMINANCE_INTENSITY
            )
        )
    }

    private fun setEdgeParams(p: WeTypeColorOsCapsuleRecipe.StrokeParams, density: Float): Boolean {
        val clazz = edgeParamsClass ?: return absent("stroke.edge", "OplusMaterialEdgeParams.<init>(I,F,F,F)")
        val args = WeTypeColorOsCapsuleRecipe.edgeArgs(p, density)
        return try {
            val ctor = clazz.getConstructor(
                Int::class.java, Float::class.java, Float::class.java, Float::class.java
            )
            val params = ctor.newInstance(p.strokeType, args[0], args[1], args[2])
            staticBoolCall(
                "stroke.edge",
                "setEdgeParams(View,EdgeParams type=${p.strokeType} width=${args[0]} alpha=${args[1]} angle=${args[2]})",
                arrayOf(View::class.java, clazz),
                arrayOf(view, params)
            )
        } catch (t: Throwable) {
            threw("stroke.edge", "OplusMaterialEdgeParams.<init>(I,F,F,F)", t)
            false
        }
    }

    private fun setShadowParams(p: WeTypeColorOsCapsuleRecipe.StrokeParams): Boolean {
        val clazz = shadowParamsClass ?: return absent("stroke.shadow", "OplusMaterialShadowParams.<init>(I,F,F,F)")
        val args = WeTypeColorOsCapsuleRecipe.shadowArgs(p)
        return try {
            val ctor = clazz.getConstructor(
                Int::class.java, Float::class.java, Float::class.java, Float::class.java
            )
            val params = ctor.newInstance(p.strokeType, args[0], args[1], args[2])
            staticBoolCall(
                "stroke.shadow",
                "setShadowParams(View,ShadowParams type=${p.strokeType} scale=${args[0]} fadeIn=${args[1]} fadeOut=${args[2]})",
                arrayOf(View::class.java, clazz),
                arrayOf(view, params)
            )
        } catch (t: Throwable) {
            threw("stroke.shadow", "OplusMaterialShadowParams.<init>(I,F,F,F)", t)
            false
        }
    }

    private fun setShadowClippingEnabled(enabled: Boolean): Boolean {
        val clazz = oplusViewClass
            ?: return absent("blur.shadowClipping", "OplusView.setShadowClippingEnabled(View,Z)")
        return try {
            val method = clazz.getMethod("setShadowClippingEnabled", View::class.java, Boolean::class.java)
            recordBoolean("blur.shadowClipping", "setShadowClippingEnabled(View,Z)=$enabled", method.invoke(null, view, enabled))
        } catch (t: Throwable) {
            threw("blur.shadowClipping", "OplusView.setShadowClippingEnabled(View,Z)", t)
            false
        }
    }

    private fun applyBackgroundRenderEffect(dark: Boolean): Boolean {
        val clazz = backgroundEffectClass
            ?: return absent("blur.background", "OplusViewBackgroundRenderEffect.setBackgroundRenderEffect(RenderEffect,View)")
        val effect = buildBlurRenderEffect(dark)
        return try {
            val method = clazz.getMethod("setBackgroundRenderEffect", RenderEffect::class.java, View::class.java)
            method.invoke(null, effect, view)
            calls += CallResult(
                "blur.background",
                "setBackgroundRenderEffect(RenderEffect,View) blur=${WeTypeColorOsCapsuleRecipe.BLUR_RADIUS_PX}px",
                Status.OK,
                "void"
            )
            true
        } catch (t: Throwable) {
            threw("blur.background", "setBackgroundRenderEffect(RenderEffect,View)", t)
            false
        }
    }

    private fun buildBlurRenderEffect(dark: Boolean): RenderEffect {
        val c1 = if (dark) WeTypeColorOsCapsuleRecipe.BLUR_LAYER1_DARK else WeTypeColorOsCapsuleRecipe.BLUR_LAYER1_LIGHT
        val c2 = if (dark) WeTypeColorOsCapsuleRecipe.BLUR_LAYER2_DARK else WeTypeColorOsCapsuleRecipe.BLUR_LAYER2_LIGHT
        val bm1 = if (dark) {
            WeTypeColorOsCapsuleRecipe.blurBlendMode(WeTypeColorOsCapsuleRecipe.BLEND_OVERLAY)
        } else {
            WeTypeColorOsCapsuleRecipe.blurBlendMode(WeTypeColorOsCapsuleRecipe.BLEND_COLOR_DODGE)
        }
        val bm2 = WeTypeColorOsCapsuleRecipe.blurBlendMode(WeTypeColorOsCapsuleRecipe.BLEND_LUMINOSITY)
        val blur = RenderEffect.createBlurEffect(
            WeTypeColorOsCapsuleRecipe.BLUR_RADIUS_PX,
            WeTypeColorOsCapsuleRecipe.BLUR_RADIUS_PX,
            Shader.TileMode.MIRROR
        )
        val f1 = RenderEffect.createColorFilterEffect(BlendModeColorFilter(c1, bm1), blur)
        return RenderEffect.createColorFilterEffect(BlendModeColorFilter(c2, bm2), f1)
    }

    private fun staticBoolCall(
        step: String,
        signature: String,
        paramTypes: Array<Class<*>>,
        args: Array<Any?>
    ): Boolean {
        val clazz = materialUtil ?: return absent(step, signature)
        return try {
            val method = clazz.getMethod(signature.substringBefore('('), *paramTypes)
            recordBoolean(step, signature, method.invoke(null, *args))
        } catch (t: Throwable) {
            threw(step, signature, t)
            false
        }
    }

    private fun recordBoolean(step: String, signature: String, result: Any?): Boolean {
        val status = when (result) {
            is Boolean -> if (result) Status.OK else Status.RETURNED_FALSE
            else -> Status.OK
        }
        calls += CallResult(step, signature, status, "return=$result")
        return status == Status.OK
    }

    private fun absent(step: String, signature: String): Boolean {
        calls += CallResult(step, signature, Status.ABSENT, "class/method not resolvable")
        return false
    }

    private fun threw(step: String, signature: String, t: Throwable) {
        val cause = t.cause ?: t
        calls += CallResult(step, signature, Status.THREW, "${cause.javaClass.name}: ${cause.message}")
    }

    private fun load(name: String): Class<*>? = runCatching { Class.forName(name) }.getOrNull()

    companion object {
        fun describe(recorded: List<CallResult>): String =
            recorded.joinToString("\n") { "${it.status}  ${it.step}  ${it.signature}  ${it.detail}" }

        fun reachabilityFailures(recorded: List<CallResult>): List<CallResult> =
            recorded.filter { it.status == Status.ABSENT || it.status == Status.THREW }
    }
}
