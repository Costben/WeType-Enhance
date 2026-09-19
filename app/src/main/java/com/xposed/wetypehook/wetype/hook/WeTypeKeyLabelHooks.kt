package com.xposed.wetypehook.wetype.hook

import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import com.xposed.wetypehook.wetype.gesture.GestureAction
import com.xposed.wetypehook.wetype.gesture.GestureActionExecutor
import com.xposed.wetypehook.wetype.gesture.KeyGestureResolver
import com.xposed.wetypehook.wetype.graphics.WeTypeColorOsKeyLight
import com.xposed.wetypehook.wetype.settings.WeTypeGestureSettings
import com.xposed.wetypehook.wetype.settings.WeTypeSettings
import com.xposed.wetypehook.xposed.Log
import com.xposed.wetypehook.xposed.MethodHookParam
import com.xposed.wetypehook.xposed.hookAround
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * 按键底部标签（移植自 WeType-Tool 显示底部标签）。
 *
 * 定位：宿主 `com.tencent.wetype.plugin.hld.keyboard.selfdraw.drawmethod` 包下
 * `void (Canvas, selfdraw.*)` 单键绘制方法，绘制返回后用同一 Canvas 把绑定动作短名
 * 画在按键上。View 字段按类型发现，键帽矩形优先按名取 getter、退回几何推导（抗宿主混淆更名）。
 * 取键/密码抑制/手写抑制/按字数缩放等语义与原版一致；定位与颜色走本模块设置。
 */
internal object WeTypeKeyLabelHooks {

    private const val TAG = "WeTypeKeyLabel"
    private const val DRAW_METHOD_PACKAGE = "com.tencent.wetype.plugin.hld.keyboard.selfdraw.drawmethod"
    private const val DRAW_CONTEXT_PACKAGE = "com.tencent.wetype.plugin.hld.keyboard.selfdraw."
    private const val CANVAS_CLASS = "android.graphics.Canvas"

    /**
     * 键帽 getter 的候选名，按可靠性排序。
     *
     * `t()` 是实测（3.5.3 / 3.5.4 双版本核对）返回**键帽**的那个；`getDrawRect` 是
     * 语义名，命中即用。两者都是无参返回 Rect 的实例方法，与下面的
     * [resolveKeyCapRect] 几何推导互为兜底：名字变了走推导，推导解不开走名字。
     */
    private val DRAW_RECT_GETTER_NAMES = listOf("getDrawRect", "t")

    private data class KeyDrawAccess(
        val viewField: Field,
        /** 绘制上下文里可能有多个矩形，运行时再区分格子与内容块。 */
        val rectFields: List<Field>,
        /** 按名解析到的键帽 getter；宿主改字段顺序时它是唯一稳的来源。 */
        val drawRectGetter: Method?
    )

    private data class LabelPaintStyle(
        val textSizePx: Float,
        val color: Int
    )

    private val accessCache = ConcurrentHashMap<Class<*>, KeyDrawAccess?>()
    private val t9ClassCache = ConcurrentHashMap<Class<*>, Boolean>()

    /** ColorOS 逐键光感后端；加载失败后不再重试。 */
    @Volatile
    private var keyLight: WeTypeColorOsKeyLight? = null

    @Volatile
    private var keyLightResolved = false

    /**
     * 宿主按键真实的像素圆角 getter（混淆名，运行时解析）。
     *
     * 由 [resolveBgCornerGetter] 在 DexKit 打开时按调用关系定位一次，`drawCtx` 就是
     * 该 getter 的声明类实例，直接反射调用即得宿主本帧下发的键帽半径。
     */
    @Volatile
    private var bgCornerGetter: Method? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT
    }
    private var cachedPaintStyle: LabelPaintStyle? = null

    @Volatile
    private var cachedBindingsJson: String? = null

    @Volatile
    private var cachedBindings: Map<Char, GestureAction> = emptyMap()

    private var keyDataMethod: Method? = null

    /**
     * 当前线程上嵌套的单键绘制层数，由同一个 around 拦截器的 before/after 配对维护。
     *
     * 宿主画一个键会嵌套调用多个 drawmethod 方法（实测 3.5.3/3.5.4：最外层
     * `c#a` 内部再调 `c#e`/`e#b`/`c#f`/`c#g`），而 hookAfter 是**方法返回后**触发，
     * 于是同一个键在同一帧里被连着报 5 次。标签是半透明的（默认 alpha 153/255≈60%），
     * 叠 5 层后有效不透明度约 99% —— 用户把透明度拉低也看不出变化。
     *
     * 只让最外层那次（最先进入、最后退出）真正落笔，把标签拉回设定的透明度。
     * 5 次调用算出的 x/y 完全相同，所以留下最外层与当前可见**位置**一致，差别只在
     * 谁最后落笔：最外层在子绘制之后才画，标签压在最上面，不会被按键内容盖住。
     */
    private val drawNesting = ThreadLocal.withInitial { 0 }

    /**
     * 嵌套层数的自愈上限。实测深 5 层，留出余量。
     *
     * 宿主方法抛异常时配对的退出逻辑曾会漏掉（旧 compat 层的 after 里 `chain.proceed()`
     * 先于回调，抛了就跳过），计数器会永久泄漏在本线程上，之后所有 `remaining != 0`，
     * 标签在本线程再也画不出来 —— 而且完全静默。超过这个上限必然是泄漏，就地复位。
     */
    private const val MAX_PLAUSIBLE_DRAW_NESTING = 16

    /**
     * 自愈日志的配额。
     *
     * 线程局部变量一旦泄漏，这条复位日志会按"每次按键绘制"的频率爆发：实测一次点击
     * 触发 456 条，而 LSPatch 内嵌模式下 `Log.e` 走 `module.log()` —— 每一次都是
     * 跨进程 IPC。四五百次 IPC 压在键盘动画帧里，把视图线程堵到中位帧 150ms。
     *
     * 复位是一个"已经坏了"的状态位，报一次就足够在 logcat 里看见；之后只静默自愈。
     * 配额按进程生命周期给，热重载时重置。
     */
    private const val NESTING_LEAK_LOG_BUDGET = 3

    private val nestingLeakLogsRemaining = java.util.concurrent.atomic.AtomicInteger(
        NESTING_LEAK_LOG_BUDGET
    )

    /**
     * 宿主绘制方法抛异常的日志配额。与泄漏日志同额定，避免同样的 IPC 洪水。
     */
    private const val HOST_DRAW_FAILURE_LOG_BUDGET = 3

    private val hostDrawFailuresRemaining = java.util.concurrent.atomic.AtomicInteger(
        HOST_DRAW_FAILURE_LOG_BUDGET
    )

    private fun resetNestingLeakLogBudget() {
        nestingLeakLogsRemaining.set(NESTING_LEAK_LOG_BUDGET)
        hostDrawFailuresRemaining.set(HOST_DRAW_FAILURE_LOG_BUDGET)
    }

    fun prepareForHotReload() {
        resetNestingLeakLogBudget()
        keyLight = null
        keyLightResolved = false
        bgCornerGetter = null
    }

    fun install(sourceDir: String?, classLoader: ClassLoader) {
        if (sourceDir.isNullOrEmpty()) {
            Log.e("Failed: Cannot install key label hooks without sourceDir")
            return
        }

        runCatching {
            System.loadLibrary("dexkit")
            DexKitBridge.create(sourceDir).use { bridge ->
                keyDataMethod = resolveKeyDataMethod(bridge, classLoader)
                resolveBgCornerGetter(bridge, classLoader)?.let {
                    bgCornerGetter = it
                }
                val targets = bridge.findMethod {
                    searchPackages(DRAW_METHOD_PACKAGE)
                    matcher {
                        returnType = "void"
                        paramCount = 2
                    }
                }.mapNotNull { data ->
                    if (data.paramTypes.size != 2) return@mapNotNull null
                    if (data.paramTypes[0].name != CANVAS_CLASS) return@mapNotNull null
                    if (!data.paramTypes[1].name.startsWith(DRAW_CONTEXT_PACKAGE)) return@mapNotNull null
                    val method = runCatching { data.getMethodInstance(classLoader) }.getOrNull()
                        ?: return@mapNotNull null
                    if (Modifier.isAbstract(method.modifiers)) return@mapNotNull null
                    method
                }
                if (targets.isEmpty()) {
                    Log.e("[$TAG] Failed to locate single-key draw method")
                    return
                }
                targets.forEach { method ->
                    method.hookAround(
                        before = {
                            val depth = drawNesting.get() + 1
                            if (depth > MAX_PLAUSIBLE_DRAW_NESTING) {
                                // hookAround 保证 before/after 成对，走到这里说明
                                // 还有别的调用路径绕开了配对，只能就地自愈。
                                if (nestingLeakLogsRemaining.getAndUpdate { current ->
                                        if (current > 0) current - 1 else 0
                                    } > 0
                                ) {
                                    Log.e("[$TAG] draw nesting hit $depth; resetting" +
                                        " (thread=${Thread.currentThread().name})")
                                }
                                drawNesting.set(1)
                            } else {
                                drawNesting.set(depth)
                            }
                        },
                        after = { param: MethodHookParam, failure: Throwable? ->
                            val remaining = (drawNesting.get() - 1).coerceAtLeast(0)
                            drawNesting.set(remaining)
                            // 宿主绘制抛异常时这里依然执行（try/finally 保证），
                            // 但那一帧的键帽已经画残，落笔没有意义。
                            if (failure != null && hostDrawFailuresRemaining.getAndUpdate { current ->
                                    if (current > 0) current - 1 else 0
                                } > 0
                            ) {
                                Log.e("[$TAG] host draw threw: ${failure.javaClass.name}: ${failure.message}")
                            }
                            if (remaining == 0 && failure == null) {
                                drawKeyLabel(param.args.getOrNull(0), param.args.getOrNull(1))
                            }
                        }
                    )
                }
                Log.i("[$TAG] Hooked ${targets.size} single-key draw methods")
            }
        }.onFailure {
            Log.e("Failed: Installing WeType key label hooks: ${it.message}")
            Log.i(it)
        }
    }

    private fun resolveKeyDataMethod(bridge: DexKitBridge, classLoader: ClassLoader): Method? {
        return runCatching {
            bridge.findMethod {
                searchPackages("com.tencent.wetype.plugin.hld.keyboard")
                matcher {
                    name = "getMainText"
                    returnType = "java.lang.String"
                }
            }.firstOrNull()?.getMethodInstance(classLoader)
        }.getOrNull()
    }

    private fun drawKeyLabel(canvasArg: Any?, drawCtx: Any?) {
        val canvas = canvasArg as? Canvas ?: return
        if (drawCtx == null) return
        val access = accessFor(drawCtx) ?: return
        val keyView = runCatching { access.viewField.get(drawCtx) as? View }.getOrNull() ?: return
        val rect = resolveKeyCapRect(access, drawCtx) ?: return
        if (rect.isEmpty) return

        // 系统材质开启时给每个按键叠 ColorOS 官方边缘光。它与手势标签互相独立：
        // 即使标签功能关闭也要画，因此放在标签开关判断之前。
        drawSystemKeyLight(canvas, keyView, rect, resolveKeyCornerPx(drawCtx, keyView))

        if (!WeTypeSettings.isShowGestureKeyLabelsXposed()) return

        val isT9 = isT9Key(keyView, drawCtx)
        val enabled = if (isT9) {
            WeTypeSettings.isT9GestureEnabledXposed()
        } else {
            WeTypeSettings.isQwertyGestureEnabledXposed()
        }
        if (!enabled) return
        if (isSuppressedHost(keyView)) return

        val keyChar = KeyGestureResolver.resolveKeyChar(drawCtx, keyDataMethod, isT9)
        if (keyChar == '\u0000') return
        val action = bindings()[keyChar] ?: GestureAction.None
        if (action == GestureAction.None || action == GestureAction.Disable) return
        val text = action.shortTitle
        if (text.isEmpty()) return

        val snapshot = LabelStyleSnapshot.capture(keyView) ?: return
        ensurePaint(snapshot)
        // 原版按字数缩放：≥4 字 0.78×，3 字 0.88×，画完恢复。
        val baseTextSize = paint.textSize
        paint.textSize = when {
            text.length >= 4 -> baseTextSize * 0.78f
            text.length == 3 -> baseTextSize * 0.88f
            else -> baseTextSize
        }
        val metrics = paint.fontMetrics
        // 两个轴都锚在**键帽**（真正画出来的圆角方块）上，跟宿主的字母同一个基准。用键帽
        // 而不是格子，是因为格子含不等宽的左右 padding，会让每个键各自偏一点点。
        val x = (rect.left + rect.right) / 2f +
            (snapshot.marginLeftPx - snapshot.marginRightPx) / 2f
        // 垂直基准是键帽中线：边距为 0 时标签墨迹中心正压在中线上，顶部往上、底部往下
        // 各偏移各自的边距，默认各 15dp 落在字母与键帽下沿之间。
        val midline = (rect.top + rect.bottom) / 2f
        val midlineBaseline = midline - (metrics.ascent + metrics.descent) / 2f
        val y = when (snapshot.verticalPosition) {
            WeTypeSettings.GESTURE_LABEL_POSITION_TOP -> midlineBaseline - snapshot.marginTopPx
            else -> midlineBaseline + snapshot.marginBottomPx
        }
        canvas.drawText(text, x, y, paint)
        paint.textSize = baseTextSize
    }

    /**
     * 取宿主**本帧真正下发的键帽像素圆角半径**。
     *
     * 首选读 `j.getBgCorner()`：宿主就是用这个 int 直接调
     * `canvas.drawRoundRect(rect, radius, radius, paint)` 画键帽的（drawmethod 包），
     * 所以它天然就是像素、天然与键帽一致，不需要任何 `dp * density` 换算。
     * 这也解释了此前"设置 15dp 下发 58px、看着比键帽大一倍"的根因：那条路径把
     * 用户设置值当半径，而宿主真实半径是它的一半出头。
     *
     * 兜底才退回设置值换算，供 getter 改名/缺失的宿主版本使用。
     */
    private fun resolveKeyCornerPx(drawCtx: Any, keyView: View): Float {
        bgCornerGetter?.let { getter ->
            if (getter.declaringClass.isInstance(drawCtx)) {
                val hostRadius = runCatching { getter.invoke(drawCtx) as? Int }.getOrNull()
                if (hostRadius != null && hostRadius >= 0) {
                    return hostRadius.toFloat()
                }
            }
        }
        val density = keyView.resources.displayMetrics.density
        return WeTypeSettings.getKeyCornerRadiusXposed()
            .coerceIn(0, WeTypeSettings.MAX_KEY_CORNER_RADIUS) * density
    }

    /**
     * 系统材质开启时，给单个按键叠 ColorOS 官方边缘光。
     *
     * 只在 ColorOS 且系统材质开关打开时生效；后端懒加载一次，
     * 加载失败即静默关闭，不影响宿主绘制。
     */
    private fun drawSystemKeyLight(
        canvas: Canvas,
        keyView: View,
        rect: Rect,
        keyRadiusPx: Float
    ) {
        if (!WeTypeSettings.isHyperMaterialEnabledXposed()) return
        val light = keyLight ?: run {
            if (keyLightResolved) return
            keyLightResolved = true
            WeTypeColorOsKeyLight.create(keyView.context).also { keyLight = it }
        } ?: return
        val isDark = (keyView.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        light.draw(canvas, rect, keyRadiusPx, isDark)
    }

    private data class LabelStyleSnapshot(
        val textSizePx: Float,
        val color: Int,
        val verticalPosition: Int,
        val marginLeftPx: Float,
        val marginTopPx: Float,
        val marginRightPx: Float,
        val marginBottomPx: Float
    ) {
        companion object {
            fun capture(keyView: View): LabelStyleSnapshot? {
                val metrics = keyView.resources.displayMetrics
                val density = metrics.density
                if (density <= 0) return null
                val fontScale = keyView.resources.configuration.fontScale
                val textSizePx = WeTypeSettings.getGestureLabelTextSizeSpXposed()
                    .coerceIn(6, 16) * density * fontScale
                val alpha = WeTypeSettings.getGestureLabelAlphaXposed().coerceIn(0, 255)
                val isDark = isDarkMode(keyView)
                val base = (keyView as? TextView)?.currentTextColor
                    ?: resolveThemeTextColor(keyView, isDark)
                return LabelStyleSnapshot(
                    textSizePx = textSizePx,
                    color = (base and 0x00FFFFFF) or (alpha shl 24),
                    verticalPosition = WeTypeSettings.getGestureLabelPositionXposed()
                        .coerceIn(
                            WeTypeSettings.GESTURE_LABEL_POSITION_BOTTOM,
                            WeTypeSettings.GESTURE_LABEL_POSITION_TOP
                        ),
                    marginLeftPx = WeTypeSettings.getGestureLabelMarginLeftDpXposed()
                        .coerceIn(
                            WeTypeSettings.GESTURE_LABEL_MARGIN_MIN_DP,
                            WeTypeSettings.GESTURE_LABEL_MARGIN_MAX_DP
                        ) * density,
                    marginTopPx = WeTypeSettings.getGestureLabelMarginTopDpXposed()
                        .coerceIn(
                            WeTypeSettings.GESTURE_LABEL_MARGIN_MIN_DP,
                            WeTypeSettings.GESTURE_LABEL_MARGIN_MAX_DP
                        ) * density,
                    marginRightPx = WeTypeSettings.getGestureLabelMarginRightDpXposed()
                        .coerceIn(
                            WeTypeSettings.GESTURE_LABEL_MARGIN_MIN_DP,
                            WeTypeSettings.GESTURE_LABEL_MARGIN_MAX_DP
                        ) * density,
                    marginBottomPx = WeTypeSettings.getGestureLabelMarginBottomDpXposed()
                        .coerceIn(
                            WeTypeSettings.GESTURE_LABEL_MARGIN_MIN_DP,
                            WeTypeSettings.GESTURE_LABEL_MARGIN_MAX_DP
                        ) * density
                )
            }

            private fun isDarkMode(view: View): Boolean {
                val isNightUi = (view.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
                val context = view.context ?: return isNightUi
                val bgColor: Int? = runCatching { WeTypeSettings.getCurrentBackgroundColorXposed(context) }.getOrNull()
                if (bgColor != null && Color.alpha(bgColor) > 50) {
                    val luminance = (Color.red(bgColor) * 0.299 + Color.green(bgColor) * 0.587 + Color.blue(bgColor) * 0.114) / 255
                    return luminance < 0.5
                }
                return isNightUi
            }

            private fun resolveThemeTextColor(view: View, isDark: Boolean): Int {
                val defaultColor = if (isDark) 0xFFCAC4D0.toInt() else 0xFF5F6368.toInt()
                return runCatching {
                    val typedValue = TypedValue()
                    if (view.context.theme.resolveAttribute(
                            android.R.attr.textColorSecondary,
                            typedValue,
                            true
                        )
                    ) {
                        val color = if (typedValue.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT) {
                            typedValue.data
                        } else {
                            val resId = typedValue.resourceId
                            if (resId != 0) view.context.getColor(resId) else defaultColor
                        }
                        val lum = (Color.red(color) * 0.299 + Color.green(color) * 0.587 + Color.blue(color) * 0.114) / 255
                        if ((isDark && lum > 0.45) || (!isDark && lum <= 0.55)) {
                            return color
                        }
                    }
                    defaultColor
                }.getOrDefault(defaultColor)
            }
        }
    }

    private fun ensurePaint(snapshot: LabelStyleSnapshot) {
        val style = LabelPaintStyle(snapshot.textSizePx, snapshot.color)
        if (cachedPaintStyle != style) {
            paint.reset()
            paint.isAntiAlias = true
            paint.textAlign = Paint.Align.CENTER
            paint.typeface = Typeface.DEFAULT
            paint.textSize = snapshot.textSizePx
            paint.color = snapshot.color
            cachedPaintStyle = style
        }
    }

    private fun bindings(): Map<Char, GestureAction> {
        val json = WeTypeSettings.getGestureBindingsJsonXposed()
        if (json != cachedBindingsJson) {
            cachedBindingsJson = json
            cachedBindings = WeTypeGestureSettings.parseBindings(json)
        }
        return cachedBindings
    }

    private fun accessFor(drawCtx: Any): KeyDrawAccess? {
        val clazz = drawCtx.javaClass
        accessCache[clazz]?.let { return it }
        var viewField: Field? = null
        val rectFields = mutableListOf<Field>()
        var search: Class<*>? = clazz
        while (search != null && search != Any::class.java) {
            for (field in search.declaredFields) {
                if (viewField == null && View::class.java.isAssignableFrom(field.type)) {
                    field.isAccessible = true
                    viewField = field
                } else if (field.type == Rect::class.java && !Modifier.isStatic(field.modifiers)) {
                    field.isAccessible = true
                    rectFields += field
                }
            }
            search = search.superclass
        }
        val drawRectGetter = resolveDrawRectGetter(clazz)
        val access = if (viewField != null && (drawRectGetter != null || rectFields.isNotEmpty())) {
            KeyDrawAccess(viewField, rectFields, drawRectGetter)
        } else {
            null
        }
        accessCache[clazz] = access
        if (access != null) {
            Log.i(
                "[$TAG] Resolved draw context ${clazz.name}: " +
                    "view=${viewField?.name} rects=${rectFields.joinToString { it.name }} " +
                    "drawRect=${drawRectGetter?.name ?: "none"}"
            )
        } else {
            Log.e("[$TAG] Failed to resolve View/Rect fields in ${clazz.name}")
        }
        return access
    }

    /**
     * 按名找键帽 getter。找不到返回 null，由 [resolveKeyCapRect] 的几何推导接手。
     *
     * 不用 `getDeclaredMethod` 而是扫 declaredMethods，是为了不依赖方法名的可见性；
     * 只收无参、返回 Rect、非静态的实例方法，避免碰到 `t(Rect)` 之类的重载。
     */
    private fun resolveDrawRectGetter(clazz: Class<*>): Method? {
        var search: Class<*>? = clazz
        while (search != null && search != Any::class.java) {
            for (name in DRAW_RECT_GETTER_NAMES) {
                val method = search.declaredMethods.firstOrNull { candidate ->
                    candidate.name == name &&
                        candidate.returnType == Rect::class.java &&
                        candidate.parameterCount == 0 &&
                        !Modifier.isStatic(candidate.modifiers) &&
                        !Modifier.isAbstract(candidate.modifiers)
                } ?: continue
                runCatching { method.isAccessible = true }
                return method
            }
            search = search.superclass
        }
        return null
    }

    /**
     * 挑出宿主绘制键帽时真正下发的像素圆角半径 getter。
     *
     * 宿主 `drawmethod` 包用 `canvas.drawRoundRect(rect, button.<radius>(), ...)` 画键帽，
     * 这个返回值**就是**像素半径，与 Canvas 同一坐标空间，不需要任何 density 换算。
     * 光感直接用同一个值即可与键帽圆角完全一致。
     *
     * 难点：类名、字段名、方法名全都是混淆的（实测 3.5.4 上 getter 是 `j.j()`）。
     * 整套定位只依赖宿主自己的调用关系，抗混淆改名：
     *
     *  1. 找 `KeyData.getBgCorner():Float` 的调用者，它在同一个方法里把键帽半径灌进按钮；
     *  2. 该方法的 `invokes` 里那个 `(I)V`、声明类落在 `keyboard.selfdraw` 下的方法
     *     就是写入像素半径的 setter（实测 `j.x0(I)V`），按钮类即取自它的声明类；
     *  3. setter 写入哪个 int 字段，就回读该字段的无参 `()I` getter（实测 `j.j()`）。
     *
     * 其中 `KeyData.getBgCorner` 未混淆（序列化 bean），是最稳的锚点。
     */
    private fun resolveBgCornerGetter(
        bridge: DexKitBridge,
        classLoader: ClassLoader
    ): Method? = runCatching {
        val callers = bridge.findMethod {
            searchPackages("com.tencent.wetype.plugin.hld.keyboard")
            matcher {
                name = "getBgCorner"
                returnType = "java.lang.Float"
            }
        }.flatMap { it.callers }
        if (callers.isEmpty()) return null

        // 按钮类的像素半径 setter：在某个调用者里，紧随 getBgCorner 调用之后、
        // 形如 `(I)V` 且声明在 selfdraw 下的方法（实测 `j.x0(I)V`，中间只隔一个 n1.A1）。
        // 按钮类上有很多同签名的 (I)V setter，所以按“相对 getBgCorner 的先后顺序”取，
        // 而不是按声明类名盲取第一个。
        val setter = callers.asSequence()
            .mapNotNull { caller ->
                val invokes = caller.invokes
                val anchor = invokes.indexOfFirst {
                    it.name == "getBgCorner" && it.returnTypeName == "java.lang.Float"
                }
                if (anchor < 0) return@mapNotNull null
                invokes.drop(anchor + 1).firstOrNull { invoke ->
                    invoke.paramTypeNames.size == 1 &&
                        invoke.paramTypeNames[0] == "int" &&
                        invoke.returnTypeName == "void" &&
                        invoke.declaredClassName.startsWith(DRAW_CONTEXT_PACKAGE)
                }
            }
            .firstOrNull() ?: return null
        val buttonName = setter.declaredClassName
        // setter 写入的 int 字段名。
        val fieldName = setter.usingFields
            .firstOrNull { it.usingType.isWrite() && it.field.declaredClassName == buttonName }
            ?.field?.name
            ?: return null
        // 同一按钮类里读该字段的无参 int getter。
        val getter = bridge.getClassData(buttonName)?.methods?.firstOrNull { m ->
            m.paramTypeNames.isEmpty() &&
                m.returnTypeName == "int" &&
                m.usingFields.any { it.usingType.isRead() && it.field.name == fieldName }
        } ?: return null
        Log.i("[$TAG] Resolved host key radius getter $buttonName.${getter.name} (field=$fieldName)")
        getter.getMethodInstance(classLoader)
    }.getOrElse {
        Log.e("[$TAG] Failed to resolve host key radius getter")
        Log.e(it)
        null
    }

    /**
     * 挑出**键帽**矩形，两级来源。
     *
     * 一级：按名取 getter（见 [DRAW_RECT_GETTER_NAMES]）。名字是宿主自己给的语义，
     * 字段顺序、矩形增删都动不了它 —— 这是抗漂移的主路径。实测 3.5.3/3.5.4 上
     * `t()` 与下面几何推导选中的矩形逐键一致（Z/X/C/Q 都核对过）。
     *
     * 二级：几何推导兜底，供 getter 改名或不存在时用。绘制上下文里最外层的矩形是
     * 宿主分配给这个键的**格子**（含不等宽的左右 padding：Q 左 13 右 8、W 左右各 7、
     * Z 左 18 右 8…），里层被它包住、且更窄的才是真正画出来的**键帽**。
     * 识别不出来时退回第一个矩形，保持只有单个矩形字段的宿主版本行为不变。
     *
     * 曾经直接用第一个矩形，结果每个键按自己的 padding 差值偏移——Z 偏 5px、X 偏 4px、
     * C 偏 3px、Q 偏 2.5px，就是肉眼看到的"全选没对齐"。
     */
    private fun resolveKeyCapRect(access: KeyDrawAccess, drawCtx: Any): Rect? {
        // 一级：getter 取的键帽可能是空矩形（未布局的键），此时不能直接采信，
        // 但也不该退回推导——空就是空，画上去也是错位。交由调用方按 isEmpty 丢弃。
        access.drawRectGetter?.let { getter ->
            val rect = runCatching { getter.invoke(drawCtx) as? Rect }.getOrNull()
            if (rect != null && !rect.isEmpty) return rect
        }
        val rects = access.rectFields.mapNotNull { field ->
            runCatching { field.get(drawCtx) as? Rect }.getOrNull()?.takeIf { !it.isEmpty }
        }
        if (rects.isEmpty()) return null
        if (rects.size == 1) return rects[0]
        return rects.firstOrNull { outer ->
            rects.any { inner -> inner !== outer && outer.contains(inner) && inner.width() < outer.width() }
        }?.let { outer -> rects.filter { outer.contains(it) }.minBy { it.width() } }
            ?: rects.first()
    }

    private fun isT9Key(view: View, drawCtx: Any): Boolean {
        if (isT9Class(view.javaClass) || isT9Class(drawCtx.javaClass)) return true
        var parent = view.parent
        repeat(8) {
            val parentView = parent as? View ?: return false
            if (isT9Class(parentView.javaClass)) return true
            parent = parentView.parent
        }
        return false
    }

    private fun isT9Class(clazz: Class<*>): Boolean {
        return t9ClassCache.getOrPut(clazz) {
            val name = clazz.name.lowercase(Locale.ROOT)
            name.contains("t9") || name.contains("nine")
        }
    }

    /**
     * 抑制场景（与原版一致）：密码输入框、手写键盘不画标签。
     */
    private fun isSuppressedHost(view: View): Boolean {
        var current: View? = view
        repeat(8) {
            val name = current?.javaClass?.name?.lowercase(Locale.ROOT).orEmpty()
            if (name.contains("handwrite") || name.contains("hand_write")) return true
            current = current?.parent as? View ?: return@repeat
        }
        val context = view.context ?: return false
        val ims = GestureActionExecutor.resolveInputMethodService(context) ?: return false
        val editorInfo = runCatching { ims.currentInputEditorInfo }.getOrNull() ?: return false
        val inputClass = editorInfo.inputType and EditorInfo.TYPE_MASK_CLASS
        val variation = editorInfo.inputType and EditorInfo.TYPE_MASK_VARIATION
        if (inputClass == EditorInfo.TYPE_CLASS_TEXT) {
            if (variation == EditorInfo.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == EditorInfo.TYPE_TEXT_VARIATION_WEB_PASSWORD
            ) {
                return true
            }
        } else if (inputClass == EditorInfo.TYPE_CLASS_NUMBER) {
            if (variation == EditorInfo.TYPE_NUMBER_VARIATION_PASSWORD) return true
        }
        return false
    }
}
