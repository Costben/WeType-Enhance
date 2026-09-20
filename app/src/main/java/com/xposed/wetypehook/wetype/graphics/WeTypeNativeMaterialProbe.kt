package com.xposed.wetypehook.wetype.graphics

import android.content.Context
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.os.Looper
import android.os.Process
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.xposed.wetypehook.BuildConfig
import com.xposed.wetypehook.PropertyUtils
import com.xposed.wetypehook.xposed.Log
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * R2/R1 探针：在真实 IME carrier 与普通 Activity 上共用同一实现、同一几何推导与同一调用序列，
 * 分别验证 ColorOS native 描边（edge）、焦散（caustic）与自身裁剪（clipToOutline）。
 *
 * ## 默认关闭，完全可撤销
 * 仅由系统属性驱动，默认全关；关闭时不触碰任何 RenderNode，模块行为与现状一致：
 * - `debug.wetype.r1probe`        总开关，`1` 才执行
 * - `debug.wetype.r1probe.edge`   `1` 施加 native 描边（corner + base + edge + shadow）
 * - `debug.wetype.r1probe.caustic` `1` 施加 native 焦散（corner + base + caustic）
 * - `debug.wetype.r1probe.clip`   `""` 不碰；`0` 强制 false；`1` 强制 true
 * - `debug.wetype.r1probe.marker` `1` 在该节点内挂一个高对比标记子 View（可撤销）
 *
 * ## 证据等级（F7）
 * 每次反射调用记录四态之一：`ok`（返回 true）、`false`（返回 false）、`threw`（异常类型+消息）、
 * `absent`（方法不存在）。不把“某个 setter 返回 true”外推为“整体生效”。参数一律来自实际
 * layout（`view.width/height/left/top`），attach 且尺寸 > 0 之前不构造任何参数（F4）。
 */
internal object WeTypeNativeMaterialProbe {

    private const val TAG = "WeTypeR1Probe"
    private const val EVIDENCE_FILE = "native_material_probe_r1.log"

    const val PROP_MASTER = "debug.wetype.r1probe"
    const val PROP_EDGE = "debug.wetype.r1probe.edge"
    const val PROP_CAUSTIC = "debug.wetype.r1probe.caustic"
    const val PROP_CLIP = "debug.wetype.r1probe.clip"
    const val PROP_MARKER = "debug.wetype.r1probe.marker"

    private const val EDGE_TYPE_RECTANGLE = 1
    private const val EDGE_ALPHA = 0.6f
    private const val EDGE_WIDTH_DP = 2.2f
    private const val EDGE_ANGLE = 135f
    private const val SHADOW_FADE_IN = 0.01f
    private const val SHADOW_FADE_OUT = 1.0f
    private const val SHADOW_FADE_SCALE = 1.0f
    private const val CORNER_RADIUS_DP = 28f
    private const val CORNER_WEIGHT = 1.0f
    private const val CAUSTIC_SDF_DP = 12f
    private const val CAUSTIC_LAYOUT_DP = 8
    private const val CAUSTIC_COLOR = 0x40FFFFFF
    private const val MARKER_TAG = "WeTypeR1Probe_Marker"
    private const val ACTIVITY_HOST_TAG = "WeTypeR1Probe_ActivityHost"

    private val loggedProcess = AtomicBoolean(false)
    private val evidenceStarted = AtomicBoolean(false)

    // 每个 View 最近一次生效的配置键；用于避免每帧重复下发。key 为 identityHashCode。
    private val appliedKeys = HashMap<Int, String>()

    // 标记绘制控制：记录被临时替换的背景，便于撤销。
    private val markerOriginalBackground = HashMap<Int, android.graphics.drawable.Drawable?>()

    private val utilClass: Class<*>? by lazy { load("com.oplus.view.material.OplusMaterialUtil") }
    private val edgeParamsClass: Class<*>? by lazy { load("com.oplus.view.material.OplusMaterialEdgeParams") }
    private val shadowParamsClass: Class<*>? by lazy { load("com.oplus.view.material.OplusMaterialShadowParams") }
    private val cornerParamsClass: Class<*>? by lazy { load("com.oplus.view.material.OplusMaterialCornerParams") }
    private val baseParamsClass: Class<*>? by lazy { load("com.oplus.view.material.OplusMaterialBaseParams") }

    fun prop(name: String): String? = runCatching { PropertyUtils[name, null] }.getOrNull()
    fun isEnabled(): Boolean = prop(PROP_MASTER) == "1"
    fun edgeOn(): Boolean = prop(PROP_EDGE) == "1"
    fun causticOn(): Boolean = prop(PROP_CAUSTIC) == "1"
    fun markerOn(): Boolean = prop(PROP_MARKER) == "1"
    fun clipMode(): String = prop(PROP_CLIP) ?: ""

    fun describeSwitches(): String =
        "master=${isEnabled()} edge=${edgeOn()} caustic=${causticOn()} clip='${clipMode()}' marker=${markerOn()}"

    /**
     * 在真实 layout 完成后下发材质。必须在目标 View attach 且宽高 > 0 后调用。
     * 关闭时若此前已下发，则清理一次。
     */
    fun applyIfEnabled(view: View, host: String) {
        runCatching {
            if (!isEnabled()) {
                if (appliedKeys.containsKey(System.identityHashCode(view))) clear(view, host)
                return
            }
            if (!view.isAttachedToWindow || view.width <= 0 || view.height <= 0) {
                writeEvidence(
                    view.context,
                    listOf("$host SKIP not-laid-out attached=${view.isAttachedToWindow} " +
                        "size=${view.width}x${view.height} ${describeSwitches()}")
                )
                return
            }
            val key = "${System.identityHashCode(view)}:${view.width}x${view.height}:" +
                "${view.left},${view.top}:${view.isHardwareAccelerated}:${clipMode()}:" +
                "edge=${edgeOn()}:caustic=${causticOn()}:marker=${markerOn()}"
            if (appliedKeys[System.identityHashCode(view)] == key) return
            apply(view, host)
            // 缓存的是“这一组几何+开关已处理过”，不代表每个 setter 都成功；每次调用的四态
            // 结果都逐条写进证据（F7），避免每帧重复下发同一配置。
            appliedKeys[System.identityHashCode(view)] = key
        }.onFailure {
            Log.e(it)
            writeEvidence(view.context, listOf("$host THREW apply: ${it.javaClass.name}: ${it.message}"))
        }
    }

    private fun apply(view: View, host: String): Boolean {
        val context = view.context
        val density = context.resources.displayMetrics.density
        val records = mutableListOf<String>()
        records += "--- APPLY host=$host ${describeSwitches()} pid=${Process.myPid()} ---"
        records += snapshot(view, host)

        val util = utilClass
        if (util == null) {
            records += "OplusMaterialUtil ABSENT"
            writeEvidence(context, records)
            return false
        }

        var any = false

        // 与系统 native 路径一致的共享前置：corner + base（来自实际 layout）。
        val corner = call(records, "corner", util, "setCornerParams", view, cornerParamsClass) {
            val cls = cornerParamsClass ?: return@call null
            val radiusPx = CORNER_RADIUS_DP * density
            val params = cls.getConstructor(Float::class.javaPrimitiveType, Float::class.javaPrimitiveType)
                .newInstance(radiusPx, CORNER_WEIGHT)
            arrayOf<Any?>(view, params)
        }
        val base = call(records, "base", util, "setBaseParams", view, baseParamsClass) {
            val cls = baseParamsClass ?: return@call null
            arrayOf<Any?>(view, cls.getConstructor(Int::class.javaPrimitiveType, Rect::class.java)
                .newInstance(0, Rect(0, 0, view.width, view.height)))
        }

        if (edgeOn()) {
            val edge = call(records, "edge", util, "setEdgeParams", view, edgeParamsClass) {
                val cls = edgeParamsClass ?: return@call null
                arrayOf<Any?>(view, cls.getConstructor(
                    Int::class.javaPrimitiveType, Float::class.javaPrimitiveType,
                    Float::class.javaPrimitiveType, Float::class.javaPrimitiveType)
                    .newInstance(EDGE_TYPE_RECTANGLE, EDGE_WIDTH_DP * density, EDGE_ALPHA, EDGE_ANGLE))
            }
            val shadow = call(records, "shadow", util, "setShadowParams", view, shadowParamsClass) {
                val cls = shadowParamsClass ?: return@call null
                arrayOf<Any?>(view, cls.getConstructor(
                    Int::class.javaPrimitiveType, Float::class.javaPrimitiveType,
                    Float::class.javaPrimitiveType, Float::class.javaPrimitiveType)
                    .newInstance(EDGE_TYPE_RECTANGLE, SHADOW_FADE_SCALE, SHADOW_FADE_IN, SHADOW_FADE_OUT))
            }
            any = any || edge.isPositive || shadow.isPositive
        }

        if (causticOn()) {
            val color = booleanCall(records, "caustic.color", util, "setOutlineCausticShadowColor", view, CAUSTIC_COLOR)
            val off = (CAUSTIC_LAYOUT_DP * density).toInt()
            val layout = booleanCall(records, "caustic.layout", util, "setOutlineCausticShadowLayout", view, off, off, off, off)
            val paramsArgs: Array<Any?> = arrayOf(
                view, CAUSTIC_SDF_DP * density, 0.6f, 0.6f, 18.0f, 0.45f, 0.6f
            )
            val params = booleanCallVararg(records, "caustic.params", util, "setOutlineCausticShadowParams", paramsArgs)
            any = any || color.isPositive || layout.isPositive || params.isPositive
        }

        when (clipMode()) {
            "0" -> {
                view.clipToOutline = false
                records += "clipToOutline set=false actual=${view.clipToOutline}"
            }
            "1" -> {
                view.clipToOutline = true
                records += "clipToOutline set=true actual=${view.clipToOutline}"
            }
            else -> records += "clipToOutline untouched actual=${view.clipToOutline}"
        }

        if (markerOn()) ensureMarker(view, records) else removeMarker(view, records)

        records += "outline after=${describeOutline(view)}"
        records += "readBack causticColor=${readBackCausticColor(util, view)} (expected=$CAUSTIC_COLOR)"
        records += "result anyPositive=$any corner=${corner.state} base=${base.state}"
        writeEvidence(context, records)
        view.invalidateOutline()
        return any
    }

    /** 探针关闭时清理：复位 corner/base/edge/shadow/caustic，并移除标记。 */
    fun clear(view: View, host: String) {
        appliedKeys.remove(System.identityHashCode(view))
        runCatching {
            val context = view.context
            val records = mutableListOf<String>()
            records += "--- CLEAR host=$host pid=${Process.myPid()} ---"
            val util = utilClass
            if (util != null) {
                call(records, "corner=null", util, "setCornerParams", view, cornerParamsClass) {
                    arrayOf<Any?>(view, null)
                }
                call(records, "base=null", util, "setBaseParams", view, baseParamsClass) {
                    arrayOf<Any?>(view, null)
                }
                call(records, "edge=NONE", util, "setEdgeParams", view, edgeParamsClass) {
                    val cls = edgeParamsClass ?: return@call null
                    arrayOf<Any?>(view, cls.getConstructor(
                        Int::class.javaPrimitiveType, Float::class.javaPrimitiveType,
                        Float::class.javaPrimitiveType, Float::class.javaPrimitiveType)
                        .newInstance(0, 0f, 0f, EDGE_ANGLE))
                }
                call(records, "shadow=NONE", util, "setShadowParams", view, shadowParamsClass) {
                    val cls = shadowParamsClass ?: return@call null
                    arrayOf<Any?>(view, cls.getConstructor(
                        Int::class.javaPrimitiveType, Float::class.javaPrimitiveType,
                        Float::class.javaPrimitiveType, Float::class.javaPrimitiveType)
                        .newInstance(0, 0f, 0f, 0f))
                }
                booleanCall(records, "caustic.color=0", util, "setOutlineCausticShadowColor", view, 0)
            } else {
                records += "OplusMaterialUtil ABSENT"
            }
            removeMarker(view, records)
            writeEvidence(context, records)
            view.invalidateOutline()
        }.onFailure { Log.e(it) }
    }

    /** 普通 Activity 对照宿主：把一个真实布局的 FrameLayout 挂到 decorView 上并复用同一 apply。 */
    fun installActivityHost(decor: ViewGroup): View {
        val existing = decor.findViewWithTag<View>(ACTIVITY_HOST_TAG)
        if (existing != null) return existing
        val density = decor.resources.displayMetrics.density
        val host = FrameLayout(decor.context).apply {
            tag = ACTIVITY_HOST_TAG
            background = ColorDrawable(0x66000000)
        }
        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            (96 * density).toInt()
        ).apply {
            leftMargin = (24 * density).toInt()
            rightMargin = (24 * density).toInt()
            topMargin = (200 * density).toInt()
        }
        decor.addView(host, lp)
        host.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> applyIfEnabled(v, "activity") }
        host.post { applyIfEnabled(host, "activity") }
        return host
    }

    // ---- marker -------------------------------------------------------------

    private fun ensureMarker(view: View, records: MutableList<String>) {
        val id = System.identityHashCode(view)
        if (markerOriginalBackground.containsKey(id)) {
            records += "marker already-present"
            return
        }
        markerOriginalBackground[id] = view.background
        view.background = ColorDrawable(0xFFFF00FF.toInt())
        view.invalidate()
        records += "marker set background=magenta (prev=${view.background != null})"
    }

    private fun removeMarker(view: View, records: MutableList<String>) {
        val id = System.identityHashCode(view)
        if (!markerOriginalBackground.containsKey(id)) return
        view.background = markerOriginalBackground.remove(id)
        view.invalidate()
        records += "marker restored background"
    }

    // ---- reflection helpers -------------------------------------------------

    private class CallState(val state: String, val value: Any?) {
        val isPositive: Boolean get() = state == "ok"
    }

    private inline fun call(
        records: MutableList<String>,
        label: String,
        util: Class<*>,
        methodName: String,
        view: View,
        paramClass: Class<*>?,
        argsBuilder: () -> Array<Any?>?
    ): CallState {
        if (paramClass == null) {
            records += "$label ABSENT paramClass"
            return CallState("absent", null)
        }
        return try {
            val method = util.getMethod(methodName, View::class.java, paramClass)
            val args = argsBuilder() ?: run {
                records += "$label SKIP builder-null"
                return CallState("absent", null)
            }
            val value = method.invoke(null, *args)
            val sig = "$methodName(${method.parameterTypes.joinToString { it.simpleName }})"
            when (value) {
                is Boolean -> {
                    records += "$label $sig -> $value"
                    CallState(if (value) "ok" else "false", value)
                }
                else -> {
                    records += "$label $sig -> ${value ?: "null"}"
                    CallState("ok", value)
                }
            }
        } catch (e: NoSuchMethodException) {
            records += "$label ABSENT $methodName: ${e.message}"
            CallState("absent", null)
        } catch (e: Throwable) {
            val cause = e.cause ?: e
            records += "$label THREW $methodName: ${cause.javaClass.name}: ${cause.message}"
            CallState("threw", null)
        }
    }

    private fun booleanCall(
        records: MutableList<String>,
        label: String,
        util: Class<*>,
        methodName: String,
        view: View,
        vararg args: Any
    ): CallState = invokeBool(records, label, util, methodName, arrayOf<Any?>(view, *args))

    private fun booleanCallVararg(
        records: MutableList<String>,
        label: String,
        util: Class<*>,
        methodName: String,
        args: Array<Any?>
    ): CallState = invokeBool(records, label, util, methodName, args)

    private fun invokeBool(
        records: MutableList<String>,
        label: String,
        util: Class<*>,
        methodName: String,
        callArgs: Array<Any?>
    ): CallState {
        return try {
            val method = util.methods.firstOrNull {
                it.name == methodName && it.parameterCount == callArgs.size &&
                    it.parameterTypes[0].isAssignableFrom(View::class.java)
            } ?: run {
                records += "$label ABSENT $methodName/${callArgs.size}"
                return CallState("absent", null)
            }
            val coerced = arrayOfNulls<Any?>(callArgs.size)
            for (i in callArgs.indices) {
                coerced[i] = coerce(method.parameterTypes[i], callArgs[i])
            }
            val value = method.invoke(null, *coerced)
            val sig = "$methodName(${method.parameterTypes.joinToString { it.simpleName }})"
            when (value) {
                is Boolean -> {
                    records += "$label $sig -> $value"
                    CallState(if (value) "ok" else "false", value)
                }
                else -> {
                    records += "$label $sig -> ${value ?: "null"}"
                    CallState("ok", value)
                }
            }
        } catch (e: NoSuchMethodException) {
            records += "$label ABSENT $methodName: ${e.message}"
            CallState("absent", null)
        } catch (e: Throwable) {
            val cause = e.cause ?: e
            records += "$label THREW $methodName: ${cause.javaClass.name}: ${cause.message}"
            CallState("threw", null)
        }
    }

    private fun coerce(type: Class<*>, value: Any?): Any? = when (type) {
        Int::class.javaPrimitiveType, java.lang.Integer::class.java -> (value as? Number)?.toInt() ?: 0
        Float::class.javaPrimitiveType, java.lang.Float::class.java -> (value as? Number)?.toFloat() ?: 0f
        Long::class.javaPrimitiveType, java.lang.Long::class.java -> (value as? Number)?.toLong() ?: 0L
        Double::class.javaPrimitiveType, java.lang.Double::class.java -> (value as? Number)?.toDouble() ?: 0.0
        Boolean::class.javaPrimitiveType, java.lang.Boolean::class.java -> value as? Boolean ?: false
        else -> value
    }

    private fun readBackCausticColor(util: Class<*>, view: View): String = runCatching {
        val method = util.methods.firstOrNull {
            it.name == "getOutlineCausticShadowColor" && it.parameterCount == 1 &&
                it.parameterTypes[0].isAssignableFrom(View::class.java)
        } ?: return "absent"
        val v = method.invoke(null, view)
        "ok($v)"
    }.getOrElse { "threw:${it.cause?.javaClass?.simpleName ?: it.javaClass.simpleName}:${it.cause?.message ?: it.message}" }

    // ---- evidence -----------------------------------------------------------

    private fun snapshot(view: View, host: String): String {
        val token = runCatching { view.windowToken }.getOrElse { null }
        val rect = Rect()
        val visible = runCatching { view.getGlobalVisibleRect(rect) }.getOrDefault(false)
        val visibleRect = if (visible) "[${rect.left},${rect.top},${rect.right},${rect.bottom}]" else "none"
        return "snapshot host=$host view=${view.javaClass.name} id=${System.identityHashCode(view)} " +
            "uiThread=${Looper.myLooper() == Looper.getMainLooper()} hwAccel=${view.isHardwareAccelerated} " +
            "bounds=[${view.left},${view.top},${view.right},${view.bottom}] size=${view.width}x${view.height} " +
            "visible=$visibleRect token=$token renderNode=${renderNodeId(view)} " +
            "outline=${describeOutline(view)} clip=${view.clipToOutline} " +
            "parentClipChildren=${(view.parent as? ViewGroup)?.clipChildren} " +
            "module=${BuildConfig.VERSION_NAME}/${BuildConfig.VERSION_CODE} sdk=${android.os.Build.VERSION.SDK_INT} " +
            "density=${view.resources.displayMetrics.density}"
    }

    private fun describeOutline(view: View): String = runCatching {
        val provider = view.outlineProvider?.javaClass?.name ?: "null"
        val outline = Outline()
        view.outlineProvider?.getOutline(view, outline)
        val r = Rect()
        outline.getRect(r)
        val hasAlpha = runCatching { outline.alpha }.getOrDefault(-1f)
        val radius = runCatching { outline.radius }.getOrDefault(-1f)
        // 路径型 Outline 的 getRect 恒为空；radius<=0 且 rect 为空即提示为路径轮廓。
        "$provider rect=[${r.left},${r.top},${r.right},${r.bottom}] radius=$radius alpha=$hasAlpha"
    }.getOrElse { "threw:${it.javaClass.simpleName}" }

    private fun renderNodeId(view: View): String = runCatching {
        val wrapper = view.javaClass.getMethod("getViewWrapper").invoke(view) ?: return "no-wrapper"
        val node = wrapper.javaClass.getMethod("getRenderNode").invoke(wrapper) ?: return "no-node"
        "ok(${System.identityHashCode(node)})"
    }.getOrElse { "absent:${it.cause?.javaClass?.simpleName ?: it.javaClass.simpleName}" }

    private fun writeEvidence(context: Context, records: List<String>) {
        if (records.isEmpty()) return
        val header = if (evidenceStarted.compareAndSet(false, true)) {
            val marker = "=== WeTypeR1Probe evidence pid=${Process.myPid()} " +
                "module=${BuildConfig.VERSION_NAME}/${BuildConfig.VERSION_CODE} ===\n"
            android.util.Log.i(TAG, marker.trim())
            marker
        } else ""
        runCatching {
            val file = File(context.filesDir, EVIDENCE_FILE)
            if (header.isNotEmpty()) file.writeText(header)
            file.appendText(records.joinToString(separator = "\n", postfix = "\n"))
        }.onFailure { Log.e(it) }
    }

    fun logEvidenceOnly(context: Context, line: String) = writeEvidence(context, listOf(line))

    private fun load(className: String): Class<*>? =
        runCatching { Class.forName(className) }.onFailure { Log.e(it) }.getOrNull()
}
