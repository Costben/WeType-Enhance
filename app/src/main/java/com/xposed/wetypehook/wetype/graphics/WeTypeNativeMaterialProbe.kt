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
    private const val TEST_CHILD_TAG = "WeTypeNativeMaterialProbe_TestChild"

    // 与 SystemUI / Settings 搜索框预设对齐
    private const val EDGE_TYPE_RECTANGLE = 1
    private const val EDGE_ALPHA = 0.6f
    private const val EDGE_WIDTH_DP = 2.2f
    private const val EDGE_ANGLE = 135f
    private const val SHADOW_FADE_IN = 0.01f
    private const val SHADOW_FADE_OUT = 1.0f
    private const val SHADOW_FADE_SCALE = 1.0f

    // 焦散预设：setOutlineCausticShadowParams(view, sdfWidth, 0.6, 0.6, 18, 0.45, 0.6)
    private const val CAUSTIC_SDF_DP = 12f
    private const val CAUSTIC_FACTOR_0 = 0.6f
    private const val CAUSTIC_FACTOR_1 = 0.6f
    private const val CAUSTIC_SPEED = 18f
    private const val CAUSTIC_LUMINANCE_THRESHOLD = 0.45f
    private const val CAUSTIC_LUMINANCE_INTENSITY = 0.6f
    private const val CAUSTIC_COLOR = 0x40FFFFFF

    private const val GEOMETRY_INSET_DP = 12
    private const val MATERIAL_PASS_BLUR_RADIUS = 30f

    // Corner 与 Base 差集补齐预设
    private const val CORNER_RADIUS_DP = 28f
    private const val CORNER_WEIGHT = 1.0f

    // 小尺寸测试子 View 尺寸（模拟 Settings 搜索框尺寸 300dp x 50dp）
    private const val TEST_CHILD_WIDTH_DP = 300
    private const val TEST_CHILD_HEIGHT_DP = 50

    private val loggedThisProcess = AtomicBoolean(false)
    private val evidenceInitialized = AtomicBoolean(false)

    private val utilClass: Class<*>? by lazy { load("com.oplus.view.material.OplusMaterialUtil") }
    private val edgeParamsClass: Class<*>? by lazy { load("com.oplus.view.material.OplusMaterialEdgeParams") }
    private val shadowParamsClass: Class<*>? by lazy { load("com.oplus.view.material.OplusMaterialShadowParams") }
    private val cornerParamsClass: Class<*>? by lazy { load("com.oplus.view.material.OplusMaterialCornerParams") }
    private val baseParamsClass: Class<*>? by lazy { load("com.oplus.view.material.OplusMaterialBaseParams") }

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
     * 在 carrier 及对照小尺寸子 View 上写入完整的 native 材质参数序列。
     * 补齐 S4 遗漏的 CornerParams, BaseParams, ShadowClipping, RenderEffect 及测试子节点。
     */
    fun applyIfEnabled(carrier: View, context: Context, isDark: Boolean): Boolean {
        if (!isEnabled(context)) return false
        val util = utilClass ?: return false
        logLoadOnce(carrier, context, isDark)
        val records = mutableListOf<String>()
        records += loadRecord(carrier, context, isDark)
        records += "apply carrierId=${System.identityHashCode(carrier)} " +
            "bounds=[${carrier.left},${carrier.top},${carrier.right},${carrier.bottom}] " +
            "size=${carrier.width}x${carrier.height} attached=${carrier.isAttachedToWindow}"
        val density = carrier.resources.displayMetrics.density

        // 1. 补齐差集项：setCornerParams
        val corner = setCorner(util, carrier, density, records)
        // 2. 补齐差集项：setBaseParams
        val base = setBase(util, carrier, records)
        // 3. 补齐差集项：setShadowClippingEnabled
        val shadowClip = setShadowClipping(carrier, true, records)
        // 4. 激活 material pass (RenderEffect)
        val materialPass = enableMaterialPass(carrier, records)
        // 5. 描边与阴影
        val edge = setEdge(util, carrier, density, records)
        val shadow = setShadow(util, carrier, density, records)
        // 6. 焦散
        val caustic = setCaustic(util, carrier, density, records)

        if (!corner && !base && !edge && !shadow && !caustic) {
            records += "REJECTED: all native material setters returned false on carrier"
        }
        val any = corner || base || edge || shadow || caustic
        records += "materialPass=$materialPass shadowClip=$shadowClip"
        records += "readBack getOutlineCausticShadowColor=${readBackCausticColor(util, carrier)} " +
            "(expected=$CAUSTIC_COLOR)"

        // 7. 测试小尺寸子 View 对照已被证实同样受限，移除干扰，专注真实 carrier 验证
        // applyTestChildProbe(util, carrier, density, records)

        writeEvidence(context, records)
        return any
    }

    /**
     * 在 carrier 内部添加一个与 Settings 搜索框尺寸类似的小 View，用于验证是否因大尺寸被裁剪/跳过。
     */
    private fun applyTestChildProbe(
        util: Class<*>,
        carrier: View,
        density: Float,
        records: MutableList<String>
    ) {
        if (carrier !is android.view.ViewGroup) return
        val existing = carrier.findViewWithTag<View>(TEST_CHILD_TAG)
        val child = existing ?: View(carrier.context).apply {
            tag = TEST_CHILD_TAG
            val w = (TEST_CHILD_WIDTH_DP * density).toInt()
            val h = (TEST_CHILD_HEIGHT_DP * density).toInt()
            val lp = android.widget.FrameLayout.LayoutParams(w, h).apply {
                gravity = android.view.Gravity.CENTER_HORIZONTAL or android.view.Gravity.TOP
                topMargin = (20 * density).toInt()
            }
            layoutParams = lp
            setBackgroundColor(0xFF2A2A2A.toInt())
            carrier.addView(this)
        }

        records += "testChild tag=$TEST_CHILD_TAG id=${System.identityHashCode(child)} " +
            "w=${child.layoutParams.width} h=${child.layoutParams.height}"
        val childCorner = setCorner(util, child, density, records, prefix = "child_")
        val childBase = setBase(util, child, records, prefix = "child_")
        val childShadowClip = setShadowClipping(child, true, records, prefix = "child_")
        val childMaterialPass = enableMaterialPass(child, records, prefix = "child_")
        val childEdge = setEdge(util, child, density, records, prefix = "child_")
        val childShadow = setShadow(util, child, density, records, prefix = "child_")
        val childCaustic = setCaustic(util, child, density, records, prefix = "child_")
        records += "child results: corner=$childCorner base=$childBase edge=$childEdge " +
            "shadow=$childShadow caustic=$childCaustic materialPass=$childMaterialPass shadowClip=$childShadowClip"
    }

    /** 唯一可回读的 native 材质字段：确认写入确实落在 RenderNode 上。 */
    private fun readBackCausticColor(util: Class<*>, view: View): Int = runCatching {
        val method = util.methods.firstOrNull {
            it.name == "getOutlineCausticShadowColor" && it.parameterCount == 1 &&
                View::class.java.isAssignableFrom(it.parameterTypes[0])
        } ?: throw NoSuchMethodException("getOutlineCausticShadowColor/1")
        (method.invoke(null, view) as? Int) ?: Int.MIN_VALUE
    }.onFailure { Log.e(it) }.getOrDefault(Int.MIN_VALUE)

    /** 给目标 View 的 RenderNode 挂 backdrop blur，用于激活 hwui material pass。 */
    private fun enableMaterialPass(
        view: View,
        records: MutableList<String>,
        prefix: String = ""
    ): Boolean = runCatching {
        val cls = Class.forName("com.oplus.view.OplusViewBackgroundRenderEffect")
        val method = cls.getMethod("setBackgroundRenderEffect", RenderEffect::class.java, View::class.java)
        val effect = RenderEffect.createBlurEffect(
            MATERIAL_PASS_BLUR_RADIUS, MATERIAL_PASS_BLUR_RADIUS, Shader.TileMode.CLAMP
        )
        method.invoke(null, effect, view)
        records += "${prefix}setBackgroundRenderEffect(blur=${MATERIAL_PASS_BLUR_RADIUS}) -> invoked"
        true
    }.onFailure {
        Log.e(it)
        records += "${prefix}setBackgroundRenderEffect failed: ${it.message}"
    }.getOrDefault(false)

    /** 探针关闭后清理：把 corner/base/edge/shadow/caustic 复位为“无”，并移除测试子 View。 */
    fun clear(carrier: View) {
        val util = utilClass ?: return
        runCatching {
            // 清理测试子 View
            if (carrier is android.view.ViewGroup) {
                carrier.findViewWithTag<View>(TEST_CHILD_TAG)?.let { child ->
                    clearViewMaterial(util, child)
                    carrier.removeView(child)
                }
            }
            clearViewMaterial(util, carrier)
        }.onFailure { Log.e(it) }
    }

    private fun clearViewMaterial(util: Class<*>, view: View) {
        runCatching {
            // 清理 Corner
            invokeMethodNullable(util, "setCornerParams", view, cornerParamsClass, null)
            // 清理 Base
            invokeMethodNullable(util, "setBaseParams", view, baseParamsClass, null)
            // 清理 Edge & Shadow
            val edgeType = edgeParamsClass?.getField("EDGE_TYPE_NONE")?.getInt(null) ?: 0
            val shadowType = shadowParamsClass?.getField("SHADOW_TYPE_NONE")?.getInt(null) ?: 0
            invokeSetEdge(util, view, edgeType, 0f, 0f, EDGE_ANGLE)
            invokeSetShadow(util, view, shadowType, 0f, 0f, 0f)
            // 清理 Caustic
            invokeBoolean(util, "setOutlineCausticShadowColor", view, 0)
            // 清理 RenderEffect
            runCatching {
                val cls = Class.forName("com.oplus.view.OplusViewBackgroundRenderEffect")
                val method = cls.getMethod("setBackgroundRenderEffect", RenderEffect::class.java, View::class.java)
                method.invoke(null, null, view)
            }.onFailure { Log.e(it) }
            // 恢复 ShadowClipping
            setShadowClipping(view, false, null)
        }.onFailure { Log.e(it) }
    }

    private fun setCorner(
        util: Class<*>,
        view: View,
        density: Float,
        records: MutableList<String>,
        prefix: String = ""
    ): Boolean = runCatching {
        val cls = cornerParamsClass ?: return false
        val radiusPx = CORNER_RADIUS_DP * density
        val constructor = cls.getConstructor(Float::class.javaPrimitiveType, Float::class.javaPrimitiveType)
        val params = constructor.newInstance(radiusPx, CORNER_WEIGHT)
        val method = util.getMethod("setCornerParams", View::class.java, cls)
        val result = method.invoke(null, view, params) as? Boolean ?: false
        records += "${prefix}setCornerParams(radius=$radiusPx, weight=$CORNER_WEIGHT) -> $result"
        result
    }.onFailure {
        Log.e(it)
        records += "${prefix}setCornerParams failed: ${it.message}"
    }.getOrDefault(false)

    private fun setBase(
        util: Class<*>,
        view: View,
        records: MutableList<String>,
        prefix: String = ""
    ): Boolean = runCatching {
        val cls = baseParamsClass ?: return false
        val w = if (view.width > 0) view.width else 1080
        val h = if (view.height > 0) view.height else 400
        val innerBounds = android.graphics.Rect(0, 0, w, h)
        val constructor = cls.getConstructor(Int::class.javaPrimitiveType, android.graphics.Rect::class.java)
        val params = constructor.newInstance(0, innerBounds)
        val method = util.getMethod("setBaseParams", View::class.java, cls)
        val result = method.invoke(null, view, params) as? Boolean ?: false
        records += "${prefix}setBaseParams(color=0, bounds=$innerBounds) -> $result"
        result
    }.onFailure {
        Log.e(it)
        records += "${prefix}setBaseParams failed: ${it.message}"
    }.getOrDefault(false)

    private fun setShadowClipping(
        view: View,
        enabled: Boolean,
        records: MutableList<String>?,
        prefix: String = ""
    ): Boolean = runCatching {
        val wrapper = view.javaClass.getMethod("getViewWrapper").invoke(view) ?: return false
        val renderNode = wrapper.javaClass.getMethod("getRenderNode").invoke(wrapper) ?: return false
        val method = renderNode.javaClass.getMethod("setShadowClippingEnabled", Boolean::class.javaPrimitiveType)
        val result = method.invoke(renderNode, enabled) as? Boolean ?: false
        records?.add("${prefix}setShadowClippingEnabled($enabled) -> $result")
        result
    }.onFailure {
        Log.e(it)
        records?.add("${prefix}setShadowClippingEnabled failed: ${it.message}")
    }.getOrDefault(false)

    private fun setEdge(
        util: Class<*>,
        view: View,
        density: Float,
        records: MutableList<String>,
        prefix: String = ""
    ): Boolean {
        val widthPx = EDGE_WIDTH_DP * density
        val result = invokeSetEdge(util, view, EDGE_TYPE_RECTANGLE, widthPx, EDGE_ALPHA, EDGE_ANGLE)
        records += "${prefix}setEdgeParams(type=$EDGE_TYPE_RECTANGLE, widthPx=$widthPx, alpha=$EDGE_ALPHA, " +
            "angle=$EDGE_ANGLE) -> $result"
        return result
    }

    private fun setShadow(
        util: Class<*>,
        view: View,
        density: Float,
        records: MutableList<String>,
        prefix: String = ""
    ): Boolean {
        val fadeScale = SHADOW_FADE_SCALE * density
        val result = invokeSetShadow(util, view, EDGE_TYPE_RECTANGLE, fadeScale, SHADOW_FADE_IN, SHADOW_FADE_OUT)
        records += "${prefix}setShadowParams(type=$EDGE_TYPE_RECTANGLE, fadeScale=$fadeScale, in=$SHADOW_FADE_IN, " +
            "out=$SHADOW_FADE_OUT) -> $result"
        return result
    }

    private fun setCaustic(
        util: Class<*>,
        view: View,
        density: Float,
        records: MutableList<String>,
        prefix: String = ""
    ): Boolean {
        val sdf = CAUSTIC_SDF_DP * density
        val color = invokeBoolean(util, "setOutlineCausticShadowColor", view, CAUSTIC_COLOR)
        val layout = invokeBoolean(util, "setOutlineCausticShadowLayout", view, 0, 0, 0, 0)
        val params = invokeBoolean(
            util, "setOutlineCausticShadowParams", view,
            sdf, CAUSTIC_FACTOR_0, CAUSTIC_FACTOR_1, CAUSTIC_SPEED,
            CAUSTIC_LUMINANCE_THRESHOLD, CAUSTIC_LUMINANCE_INTENSITY
        )
        records += "${prefix}caustic color=$color layout(0,0,0,0)=$layout " +
            "params($sdf,$CAUSTIC_FACTOR_0,$CAUSTIC_FACTOR_1,$CAUSTIC_SPEED," +
            "$CAUSTIC_LUMINANCE_THRESHOLD,$CAUSTIC_LUMINANCE_INTENSITY)=$params"
        return color || layout || params
    }

    private fun invokeMethodNullable(
        util: Class<*>,
        name: String,
        view: View,
        paramClass: Class<*>?,
        arg: Any?
    ): Boolean = runCatching {
        val pClass = paramClass ?: return false
        val method = util.getMethod(name, View::class.java, pClass)
        method.invoke(null, view, arg) as? Boolean ?: false
    }.onFailure { Log.e(it) }.getOrDefault(false)

    private fun invokeSetEdge(
        util: Class<*>, view: View, type: Int, width: Float, alpha: Float, angle: Float
    ): Boolean = runCatching {
        val paramsClass = edgeParamsClass ?: return false
        val params = paramsClass
            .getConstructor(Int::class.javaPrimitiveType, Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType, Float::class.javaPrimitiveType)
            .newInstance(type, width, alpha, angle)
        val method = util.getMethod("setEdgeParams", View::class.java, paramsClass)
        method.invoke(null, view, params) as? Boolean ?: false
    }.onFailure { Log.e(it) }.getOrDefault(false)

    private fun invokeSetShadow(
        util: Class<*>, view: View, type: Int, fadeScale: Float, fadeIn: Float, fadeOut: Float
    ): Boolean = runCatching {
        val paramsClass = shadowParamsClass ?: return false
        val params = paramsClass
            .getConstructor(Int::class.javaPrimitiveType, Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType, Float::class.javaPrimitiveType)
            .newInstance(type, fadeScale, fadeIn, fadeOut)
        val method = util.getMethod("setShadowParams", View::class.java, paramsClass)
        method.invoke(null, view, params) as? Boolean ?: false
    }.onFailure { Log.e(it) }.getOrDefault(false)

    private fun invokeBoolean(util: Class<*>, name: String, view: View, vararg args: Any): Boolean =
        runCatching {
            val method = util.methods.firstOrNull {
                it.name == name && it.parameterCount == args.size + 1 &&
                    View::class.java.isAssignableFrom(it.parameterTypes[0])
            } ?: throw NoSuchMethodException("$name/${args.size + 1}")
            val callArgs = arrayOfNulls<Any>(args.size + 1)
            callArgs[0] = view
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
