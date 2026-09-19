package com.xposed.wetypehook.wetype.graphics

import android.content.Context
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.os.Process
import android.view.View
import com.xposed.wetypehook.BuildConfig
import com.xposed.wetypehook.wetype.settings.WeTypeSettings
import com.xposed.wetypehook.xposed.Log
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * S4 最小可撤销探针：在真实 IME carrier 上直接调用 ColorOS native 材质 API。
 *
 * ## 默认关闭
 * 仅当显式开关打开时才执行；关闭时模块行为与现状完全一致（仍走自绘
 * [WeTypeBloomStrokeDrawable]）。开关读取见 [WeTypeSettings.isNativeMaterialProbeEnabledXposed]：
 * 模块偏好键 `native_material_probe_enabled`（默认 false），另支持 ADB 覆盖的系统属性
 * `debug.wetype.native_material_probe`。
 *
 * ## 只做“写入 + 记录”
 * `com.oplus.view.material.OplusMaterialUtil` 只有 setter，没有 getter，因此这里只记录
 * setter 入参与其返回值，**不称回读**；返回值只代表 RenderNode 写入是否被接受。
 *
 * ## 证据落盘
 * WeType 的 `:hld` 进程有 logcat 配额（超限会 DROPPED），因此除 logcat 外还把每次
 * 写入结果追加到宿主进程私有目录 `files/native_material_probe.log`，便于 ADB 取回核对。
 *
 * ## 几何解锁子开关
 * 焦散绘制在节点轮廓之外，carrier 贴屏时会被窗口裁掉。开启
 * `native_material_probe_geometry_unlock` 后，[geometryInsetPx] 会返回一个内缩量，
 * 由窗口层在 layout 时把 carrier 从屏幕边缘内缩，给外侧焦散留出可绘制区域。
 */
internal object WeTypeNativeMaterialProbe {

    const val KEY_ENABLED = WeTypeSettings.KEY_NATIVE_MATERIAL_PROBE
    const val KEY_GEOMETRY_UNLOCK = WeTypeSettings.KEY_NATIVE_MATERIAL_PROBE_GEOMETRY

    private const val TAG = "NativeMaterialProbe"
    private const val EVIDENCE_FILE = "native_material_probe.log"

    // 与 SystemUI 胶囊预设对齐（暗色）：strokeType=1(矩形), edgeAlpha≈0.6,
    // edgeWidthDp≈2.2, shadowFadeIn≈0.01, shadowFadeOut=1.0, shadowFadeScale=1.0。
    private const val EDGE_TYPE_RECTANGLE = 1
    private const val EDGE_ALPHA = 0.6f
    private const val EDGE_WIDTH_DP = 2.2f
    private const val EDGE_ANGLE = 135f
    private const val SHADOW_FADE_IN = 0.01f
    private const val SHADOW_FADE_OUT = 1.0f
    private const val SHADOW_FADE_SCALE = 1.0f

    // 焦散预设：setOutlineCausticShadowParams(view, sdfWidth, 0.6, 0.6, 18, 0.45, 0.6)。
    private const val CAUSTIC_SDF_DP = 12f
    private const val CAUSTIC_FACTOR_0 = 0.6f
    private const val CAUSTIC_FACTOR_1 = 0.6f
    private const val CAUSTIC_SPEED = 18f
    private const val CAUSTIC_LUMINANCE_THRESHOLD = 0.45f
    private const val CAUSTIC_LUMINANCE_INTENSITY = 0.6f
    private const val CAUSTIC_COLOR = 0x40FFFFFF

    private const val GEOMETRY_INSET_DP = 12
    private const val MATERIAL_PASS_BLUR_RADIUS = 30f

    private val loggedThisProcess = AtomicBoolean(false)
    private val evidenceInitialized = AtomicBoolean(false)

    private val utilClass: Class<*>? by lazy { load("com.oplus.view.material.OplusMaterialUtil") }
    private val edgeParamsClass: Class<*>? by lazy { load("com.oplus.view.material.OplusMaterialEdgeParams") }
    private val shadowParamsClass: Class<*>? by lazy { load("com.oplus.view.material.OplusMaterialShadowParams") }

    fun isEnabled(context: Context): Boolean =
        runCatching { WeTypeSettings.isNativeMaterialProbeEnabledXposed(context) }
            .onFailure { Log.e(it) }
            .getOrDefault(false)

    fun isGeometryUnlockEnabled(context: Context): Boolean =
        runCatching { WeTypeSettings.isNativeMaterialProbeGeometryXposed(context) }
            .onFailure { Log.e(it) }
            .getOrDefault(false)

    /** 几何解锁开启时返回 carrier 应从屏幕边缘内缩的像素值，否则 0。 */
    fun geometryInsetPx(view: View, context: Context): Int =
        if (isGeometryUnlockEnabled(context)) {
            (GEOMETRY_INSET_DP * view.resources.displayMetrics.density).toInt()
        } else {
            0
        }

    /**
     * 在 carrier 上写入 native 描边/阴影/焦散参数。返回是否至少有一个 setter 成功。
     * 关闭时不做任何事（返回 false）。
     */
    fun applyIfEnabled(carrier: View, context: Context, isDark: Boolean): Boolean {
        if (!isEnabled(context)) return false
        val util = utilClass ?: return false
        logLoadOnce(carrier, context, isDark)
        val records = mutableListOf<String>()
        records += loadRecord(carrier, context, isDark)
        records += "apply carrierId=${System.identityHashCode(carrier)} " +
            "bounds=[${carrier.left},${carrier.top},${carrier.right},${carrier.bottom}] " +
            "attached=${carrier.isAttachedToWindow}"
        val density = carrier.resources.displayMetrics.density
        // 假设：native 描边/焦散由 hwui 的 material pass 消费，而该 pass 只在 RenderNode 带
        // background render effect 时执行。先挂一个 backdrop blur 激活 material pass，再看描边是否出现。
        val materialPass = enableMaterialPass(carrier, records)
        val edge = setEdge(util, carrier, density, records)
        val shadow = setShadow(util, carrier, density, records)
        val caustic = setCaustic(util, carrier, density, records)
        if (!edge && !shadow && !caustic) {
            records += "REJECTED: all native material setters returned false"
        }
        val any = edge || shadow || caustic
        records += "materialPass=$materialPass"
        records += "readBack getOutlineCausticShadowColor=${readBackCausticColor(util, carrier)} " +
            "(expected=$CAUSTIC_COLOR)"
        writeEvidence(context, records)
        return any
    }

    /** 唯一可回读的 native 材质字段：确认写入确实落在 RenderNode 上。 */
    private fun readBackCausticColor(util: Class<*>, carrier: View): Int = runCatching {
        val method = util.methods.firstOrNull {
            it.name == "getOutlineCausticShadowColor" && it.parameterCount == 1 &&
                View::class.java.isAssignableFrom(it.parameterTypes[0])
        } ?: throw NoSuchMethodException("getOutlineCausticShadowColor/1")
        (method.invoke(null, carrier) as? Int) ?: Int.MIN_VALUE
    }.onFailure { Log.e(it) }.getOrDefault(Int.MIN_VALUE)

    /** 给 carrier 的 RenderNode 挂 backdrop blur，用于激活 hwui material pass。 */
    private fun enableMaterialPass(carrier: View, records: MutableList<String>): Boolean = runCatching {
        val cls = Class.forName("com.oplus.view.OplusViewBackgroundRenderEffect")
        val method = cls.getMethod("setBackgroundRenderEffect", RenderEffect::class.java, View::class.java)
        val effect = RenderEffect.createBlurEffect(
            MATERIAL_PASS_BLUR_RADIUS, MATERIAL_PASS_BLUR_RADIUS, Shader.TileMode.CLAMP
        )
        val result = method.invoke(null, effect, carrier) as? Boolean ?: false
        records += "setBackgroundRenderEffect(blur=${MATERIAL_PASS_BLUR_RADIUS}) -> $result"
        result
    }.onFailure { Log.e(it) }.getOrDefault(false)

    /** 探针关闭后清理：把 edge/shadow/caustic 复位为“无”。 */
    fun clear(carrier: View) {
        val util = utilClass ?: return
        runCatching {
            val edgeType = edgeParamsClass?.getField("EDGE_TYPE_NONE")?.getInt(null) ?: 0
            val shadowType = shadowParamsClass?.getField("SHADOW_TYPE_NONE")?.getInt(null) ?: 0
            invokeSetEdge(util, carrier, edgeType, 0f, 0f, EDGE_ANGLE)
            invokeSetShadow(util, carrier, shadowType, 0f, 0f, 0f)
            invokeBoolean(util, "setOutlineCausticShadowColor", carrier, 0)
            runCatching {
                val cls = Class.forName("com.oplus.view.OplusViewBackgroundRenderEffect")
                val method = cls.getMethod("setBackgroundRenderEffect", RenderEffect::class.java, View::class.java)
                method.invoke(null, null, carrier)
            }.onFailure { Log.e(it) }
        }.onFailure { Log.e(it) }
    }

    private fun setEdge(util: Class<*>, carrier: View, density: Float, records: MutableList<String>): Boolean {
        val widthPx = EDGE_WIDTH_DP * density
        val result = invokeSetEdge(util, carrier, EDGE_TYPE_RECTANGLE, widthPx, EDGE_ALPHA, EDGE_ANGLE)
        records += "setEdgeParams(type=$EDGE_TYPE_RECTANGLE, widthPx=$widthPx, alpha=$EDGE_ALPHA, " +
            "angle=$EDGE_ANGLE) -> $result"
        return result
    }

    private fun setShadow(util: Class<*>, carrier: View, density: Float, records: MutableList<String>): Boolean {
        val fadeScale = SHADOW_FADE_SCALE * density
        val result = invokeSetShadow(util, carrier, EDGE_TYPE_RECTANGLE, fadeScale, SHADOW_FADE_IN, SHADOW_FADE_OUT)
        records += "setShadowParams(type=$EDGE_TYPE_RECTANGLE, fadeScale=$fadeScale, in=$SHADOW_FADE_IN, " +
            "out=$SHADOW_FADE_OUT) -> $result"
        return result
    }

    private fun setCaustic(util: Class<*>, carrier: View, density: Float, records: MutableList<String>): Boolean {
        val sdf = CAUSTIC_SDF_DP * density
        val color = invokeBoolean(util, "setOutlineCausticShadowColor", carrier, CAUSTIC_COLOR)
        val layout = invokeBoolean(util, "setOutlineCausticShadowLayout", carrier, 0, 0, 0, 0)
        val params = invokeBoolean(
            util, "setOutlineCausticShadowParams", carrier,
            sdf, CAUSTIC_FACTOR_0, CAUSTIC_FACTOR_1, CAUSTIC_SPEED,
            CAUSTIC_LUMINANCE_THRESHOLD, CAUSTIC_LUMINANCE_INTENSITY
        )
        records += "caustic color=$color layout(0,0,0,0)=$layout " +
            "params($sdf,$CAUSTIC_FACTOR_0,$CAUSTIC_FACTOR_1,$CAUSTIC_SPEED," +
            "$CAUSTIC_LUMINANCE_THRESHOLD,$CAUSTIC_LUMINANCE_INTENSITY)=$params"
        return color || layout || params
    }

    private fun invokeSetEdge(
        util: Class<*>, carrier: View, type: Int, width: Float, alpha: Float, angle: Float
    ): Boolean = runCatching {
        val paramsClass = edgeParamsClass ?: return false
        val params = paramsClass
            .getConstructor(Int::class.javaPrimitiveType, Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType, Float::class.javaPrimitiveType)
            .newInstance(type, width, alpha, angle)
        val method = util.getMethod("setEdgeParams", View::class.java, paramsClass)
        method.invoke(null, carrier, params) as? Boolean ?: false
    }.onFailure { Log.e(it) }.getOrDefault(false)

    private fun invokeSetShadow(
        util: Class<*>, carrier: View, type: Int, fadeScale: Float, fadeIn: Float, fadeOut: Float
    ): Boolean = runCatching {
        val paramsClass = shadowParamsClass ?: return false
        val params = paramsClass
            .getConstructor(Int::class.javaPrimitiveType, Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType, Float::class.javaPrimitiveType)
            .newInstance(type, fadeScale, fadeIn, fadeOut)
        val method = util.getMethod("setShadowParams", View::class.java, paramsClass)
        method.invoke(null, carrier, params) as? Boolean ?: false
    }.onFailure { Log.e(it) }.getOrDefault(false)

    private fun invokeBoolean(util: Class<*>, name: String, carrier: View, vararg args: Any): Boolean =
        runCatching {
            // 按“名称 + 参数个数 + 首参 View”定位，再按形参类型收敛实参，
            // 避免 Integer/int 这类装箱类型不匹配导致 getMethod 失败。
            val method = util.methods.firstOrNull {
                it.name == name && it.parameterCount == args.size + 1 &&
                    View::class.java.isAssignableFrom(it.parameterTypes[0])
            } ?: throw NoSuchMethodException("$name/${args.size + 1}")
            val callArgs = arrayOfNulls<Any>(args.size + 1)
            callArgs[0] = carrier
            args.forEachIndexed { index, arg -> callArgs[index + 1] = coerce(method.parameterTypes[index + 1], arg) }
            method.invoke(null, *callArgs) as? Boolean ?: false
        }.onFailure { Log.e(it) }.getOrDefault(false)

    private fun coerce(type: Class<*>, value: Any): Any = when (type) {
        Int::class.javaPrimitiveType -> (value as Number).toInt()
        Float::class.javaPrimitiveType -> (value as Number).toFloat()
        Long::class.javaPrimitiveType -> (value as Number).toLong()
        Double::class.javaPrimitiveType -> (value as Number).toDouble()
        Boolean::class.javaPrimitiveType -> value as Boolean
        else -> value
    }

    private fun loadRecord(carrier: View, context: Context, isDark: Boolean): String {
        val token = runCatching { carrier.windowToken }.getOrNull()
        val inset = geometryInsetPx(carrier, context)
        return "loaded pid=${Process.myPid()} procStartTicks=${processStartTicks()} " +
            "module=${BuildConfig.VERSION_NAME}/${BuildConfig.VERSION_CODE} sdk=${Build.VERSION.SDK_INT} " +
            "dark=$isDark geometryInsetPx=$inset token=$token carrier=${carrier.javaClass.name} " +
            "probeEnabled=${isEnabled(context)} geometryUnlock=${isGeometryUnlockEnabled(context)}"
    }

    /** 每进程首帧把加载标识写进 logcat；证据文件按进程截断后重写。 */
    private fun logLoadOnce(carrier: View, context: Context, isDark: Boolean) {
        if (!loggedThisProcess.compareAndSet(false, true)) return
        Log.i("$TAG ${loadRecord(carrier, context, isDark)}")
    }

    private fun writeEvidence(context: Context, records: List<String>) {
        if (records.isEmpty()) return
        val header = if (evidenceInitialized.compareAndSet(false, true)) {
            "=== WeTypeNativeMaterialProbe evidence (pid=${Process.myPid()}) ===\n"
        } else {
            ""
        }
        runCatching {
            val file = File(context.filesDir, EVIDENCE_FILE)
            if (header.isNotEmpty()) file.writeText(header)
            file.appendText(records.joinToString(separator = "\n", postfix = "\n"))
        }.onFailure { Log.e(it) }
    }

    private fun processStartTicks(): Long = runCatching {
        // /proc/self/stat 第 22 项 = 进程启动的时钟节拍；仅作进程标识。
        val stat = File("/proc/self/stat").readText()
        val fields = stat.substringAfter(')').trim().split(' ')
        fields.getOrNull(19)?.toLongOrNull() ?: -1L
    }.getOrDefault(-1L)

    private fun load(className: String): Class<*>? =
        runCatching { Class.forName(className) }.onFailure { Log.e(it) }.getOrNull()
}
