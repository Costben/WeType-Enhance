package com.xposed.wetypehook.wetype.hook

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import com.xposed.wetypehook.wetype.aiwriter.WeTypeAiWriterLauncher
import com.xposed.wetypehook.wetype.host.pickHostField
import com.xposed.wetypehook.wetype.host.pickHostMethod
import com.xposed.wetypehook.wetype.settings.WeTypeSettings
import com.xposed.wetypehook.xposed.Log
import com.xposed.wetypehook.xposed.hookAfter
import com.xposed.wetypehook.xposed.hookBefore
import com.xposed.wetypehook.xposed.loadClassOrNull
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Modifier

/**
 * 在微信输入法候选栏/工具栏中注入「AI写作」入口，复用 ColorOS 小布输入法/AI写作原生矢量图标
 * 并自动跟随宿主皮肤着色，且与「开启 ColorOS AI写」总开关保持联动。
 *
 * 结构化持久设计：
 * 1. 类与成员按形状、继承关系及类型特征动态解析（如 Adapter 的 superclass、ViewHolder 层次、
 *    Permanent 枚举来源、静态图标解析器），混淆名仅作兜底；
 * 2. 播种与开关联动：
 *    - 当总开关开启时：以自定义 valueBit (-9527) 在列表构造点首次播种至工具栏，支持拖拽排序；
 *    - 当总开关关闭时：动态从工具栏列表中剔除该项，并清除已播种标志 (toolbar_aiwriter_seeded)，
 *      使下次开关重新开启时能再次自动播种出现在工具栏。
 */
internal object WeTypeToolbarAiWriterHooks {

    const val AI_WRITER_VALUE_BIT = -9527

    private const val SEED_PREFS_NAME = "wetype_enhance_aiwriter"
    private const val SEED_PREFS_KEY = "toolbar_aiwriter_seeded"

    private const val HOST_AI_WRITER_VALUE_BIT = 1048576

    private const val TOOLBAR_PACKAGE = "com.tencent.wetype.plugin.hld.toolbar"
    private const val FALLBACK_CUSTOM_TOOLBAR_ADAPTER_CLASS = "com.tencent.wetype.plugin.hld.toolbar.a"
    private const val FALLBACK_TOOLBAR_ROUND_VIEW_HOLDER_CLASS = "com.tencent.wetype.plugin.hld.toolbar.B"
    private const val FALLBACK_CUSTOM_TOOLBAR_VIEW_HOLDER_CLASS = "com.tencent.wetype.plugin.hld.toolbar.m"
    private const val FALLBACK_TOOLBAR_ICON_RESOLVER_CLASS = "com.tencent.wetype.plugin.hld.toolbar.c"
    private const val FALLBACK_TOOLBAR_ITEM_SOURCE_CLASS = "com.tencent.wetype.plugin.hld.toolbar.j"

    private const val TOOLBAR_SOURCE_PERMANENT = "Permanent"

    private val mainHandler = Handler(Looper.getMainLooper())

    private fun insertAiWriter(valueBit: Int, target: MutableList<Any?>) {
        if (target.any { (it as? Number)?.toInt() == valueBit }) return
        val sentinelIdx = target.indexOfFirst { (it as? Number)?.toInt() == Int.MIN_VALUE }
        target.add(if (sentinelIdx >= 0) sentinelIdx else target.size, valueBit)
    }

    fun install(sourceDir: String?, classLoader: ClassLoader) {
        val customAdapterClass = resolveCustomAdapterClass(sourceDir, classLoader) ?: run {
            Log.e("WeTypeToolbarAiWriter: CustomToolbarAdapter class not found")
            return
        }
        val toolbarAdapterClass = customAdapterClass.superclass ?: run {
            Log.e("WeTypeToolbarAiWriter: ToolbarAdapter superclass not found")
            return
        }
        val customViewHolderClass = resolveCustomViewHolderClass(sourceDir, classLoader) ?: run {
            Log.e("WeTypeToolbarAiWriter: CustomToolbarViewHolder class not found")
            return
        }
        val roundViewHolderClass = customViewHolderClass.superclass ?: run {
            Log.e("WeTypeToolbarAiWriter: ToolbarRoundViewHolder class not found")
            return
        }

        val toolsField = pickHostField(toolbarAdapterClass, "tools", "b") {
            List::class.java.isAssignableFrom(it.type)
        }
        val viewHoldersField = pickHostField(toolbarAdapterClass, "viewHolders", "d") {
            Map::class.java.isAssignableFrom(it.type)
        }
        val valueBitField = pickHostField(roundViewHolderClass, "valueBit", "g") {
            it.type == Int::class.javaPrimitiveType
        }

        val imageViewField = roundViewHolderClass.declaredFields
            .filter { it.type == ImageView::class.java }
            .onEach { it.isAccessible = true }
            .firstOrNull()

        fun applyAiWriterIconIfNeeded(holder: Any?) {
            if (holder == null) return
            if (!WeTypeSettings.isColorosAiWriterEnabledXposed()) return
            val valueBit = runCatching { valueBitField?.getInt(holder) }.getOrNull() ?: return
            if (valueBit != AI_WRITER_VALUE_BIT) return
            val imageView = runCatching { imageViewField?.get(holder) as? ImageView }.getOrNull() ?: return
            val current = imageView.drawable
            if (current !is WeTypeAiWriterLauncher.AiWriterVectorDrawable) {
                val icon = WeTypeAiWriterLauncher.loadSystemAiWriterIcon(imageView.context)
                val existingFilter = imageView.colorFilter ?: current?.colorFilter
                if (existingFilter != null) {
                    icon.colorFilter = existingFilter
                }
                imageView.setImageDrawable(icon)
                imageView.contentDescription = "AI写作"
            }
            imageView.visibility = View.VISIBLE
            imageView.alpha = 1.0f
        }

        fun applyFromViewHolderCache(adapter: Any?) {
            val holders = runCatching { viewHoldersField?.get(adapter) as? Map<*, *> }.getOrNull() ?: return
            applyAiWriterIconIfNeeded(holders[AI_WRITER_VALUE_BIT])
        }

        // 0. 放行白名单门禁
        val valueBitGates = customViewHolderClass.declaredClasses
            .flatMap { it.declaredMethods.asSequence() }
            .filter { method ->
                method.returnType == Boolean::class.javaPrimitiveType &&
                    method.parameterCount == 1 &&
                    method.parameterTypes[0] == Int::class.javaPrimitiveType
            }
            .onEach { it.isAccessible = true }
            .toList()

        valueBitGates.forEach { gate ->
            gate.hookBefore { param ->
                if ((param.args.getOrNull(0) as? Int) == AI_WRITER_VALUE_BIT) {
                    param.result = WeTypeSettings.isColorosAiWriterEnabledXposed()
                }
            }
        }

        // 1. 列表构造点：Companion.c(boolean, boolean) -> Pair<List<Integer>, Map<Integer, 来源枚举>>
        val permanentSource = resolvePermanentSource(classLoader)
        val buildToolsMethod = customAdapterClass.declaredClasses
            .flatMap { it.declaredMethods.asSequence() }
            .firstOrNull { method ->
                method.parameterCount == 2 &&
                    method.parameterTypes[0] == Boolean::class.javaPrimitiveType &&
                    method.parameterTypes[1] == Boolean::class.javaPrimitiveType &&
                    !method.returnType.isPrimitive &&
                    method.returnType != Void.TYPE
            }?.apply { isAccessible = true }

        buildToolsMethod?.hookAfter { param ->
            val pair = param.result ?: return@hookAfter
            val list = readTupleSlot(pair, "first", 0) as? MutableList<Any?> ?: return@hookAfter
            val preferences = seedPreferences()
            val isEnabled = WeTypeSettings.isColorosAiWriterEnabledXposed()

            if (!isEnabled) {
                // 总开关关闭：清理条目与播种位
                list.removeAll { (it as? Number)?.toInt() == AI_WRITER_VALUE_BIT }
                @Suppress("UNCHECKED_CAST")
                (readTupleSlot(pair, "second", 1) as? MutableMap<Int, Any?>)?.remove(AI_WRITER_VALUE_BIT)
                clearSeeded(preferences)
                return@hookAfter
            }

            // 总开关开启：检查播种
            val seeded = preferences?.getBoolean(SEED_PREFS_KEY, false) ?: false
            val present = list.any { (it as? Number)?.toInt() == AI_WRITER_VALUE_BIT }
            if (present) {
                if (!seeded) markSeeded(preferences)
                return@hookAfter
            }
            if (seeded) return@hookAfter
            insertAiWriter(AI_WRITER_VALUE_BIT, list)
            if (permanentSource != null) {
                @Suppress("UNCHECKED_CAST")
                (readTupleSlot(pair, "second", 1) as? MutableMap<Int, Any?>)
                    ?.put(AI_WRITER_VALUE_BIT, permanentSource)
            }
            markSeeded(preferences)
        }

        // 2. updateData 方法
        val updateDataMethod = pickHostMethod(customAdapterClass, "I", "updateData") { method ->
            method.returnType == Void.TYPE &&
                method.parameterCount == 2 &&
                method.parameterTypes[0] == Boolean::class.javaPrimitiveType &&
                method.parameterTypes[1] == Boolean::class.javaPrimitiveType
        }

        updateDataMethod?.hookAfter { param ->
            val adapter = param.thisObject
            if (toolsField?.get(adapter) as? List<*> == null) return@hookAfter
            applyFromViewHolderCache(adapter)
            mainHandler.postDelayed({ applyFromViewHolderCache(adapter) }, BIND_RETRY_DELAY_MS)
        }

        // 3. 绑定与刷新图标
        val updateIconMethod = pickHostMethod(roundViewHolderClass, "D", "updateIcon") { method ->
            method.parameterCount == 0 &&
                method.returnType == Void.TYPE &&
                !Modifier.isStatic(method.modifiers)
        }

        updateIconMethod?.hookAfter { param -> applyAiWriterIconIfNeeded(param.thisObject) }

        val bindMethod = pickHostMethod(roundViewHolderClass, "b", "bind") { method ->
            method.parameterCount == 5 &&
                method.parameterTypes[0] == Int::class.javaPrimitiveType &&
                method.returnType == Void.TYPE
        }

        bindMethod?.hookAfter { param -> applyAiWriterIconIfNeeded(param.thisObject) }

        val onBindMethod = pickHostMethod(toolbarAdapterClass, "onBindViewHolder") { method ->
            method.parameterCount == 2 &&
                method.returnType == Void.TYPE &&
                !method.parameterTypes[0].isPrimitive
        }

        onBindMethod?.hookAfter { param -> applyAiWriterIconIfNeeded(param.args.getOrNull(0)) }

        // 4. 图标资源解析
        val iconResolverClass = resolveIconResolverClass(sourceDir, classLoader)
        val iconResMethod = iconResolverClass?.let { owner ->
            pickHostMethod(owner, "n") { method ->
                Modifier.isStatic(method.modifiers) &&
                    method.returnType == Int::class.javaPrimitiveType &&
                    method.parameterCount == 1 &&
                    method.parameterTypes[0] == Integer::class.java
            }
        }
        var hostAiWriterIconRes = 0

        iconResMethod?.hookBefore { param ->
            if ((param.args.getOrNull(0) as? Int) != AI_WRITER_VALUE_BIT) return@hookBefore
            if (hostAiWriterIconRes <= 0) {
                hostAiWriterIconRes = runCatching {
                    iconResMethod.invoke(null, HOST_AI_WRITER_VALUE_BIT) as? Int ?: 0
                }.getOrDefault(0)
            }
            if (hostAiWriterIconRes > 0) param.result = hostAiWriterIconRes
        }

        // 5. 点击响应
        val onClickMethod = pickHostMethod(customViewHolderClass, "g", "onClick") { method ->
            method.parameterCount == 1 &&
                method.parameterTypes[0] == View::class.java &&
                method.returnType == Void.TYPE &&
                !Modifier.isStatic(method.modifiers)
        }

        onClickMethod?.hookBefore { param ->
            val holder = param.thisObject
            val valueBit = valueBitField?.getInt(holder) ?: return@hookBefore
            if (valueBit == AI_WRITER_VALUE_BIT) {
                if (!WeTypeSettings.isColorosAiWriterEnabledXposed()) return@hookBefore
                val clickedView = param.args.getOrNull(0) as? View ?: return@hookBefore
                Log.i("WeTypeToolbarAiWriter: toolbar AI Writer button clicked")
                WeTypeAiWriterLauncher.launch(clickedView)
                param.result = null
            }
        }

        Log.i(
            "WeTypeToolbarAiWriter: installed (${customAdapterClass.name}, updateData=${updateDataMethod?.name}, " +
                "buildTools=${buildToolsMethod?.name}, updateIcon=${updateIconMethod?.name}, " +
                "bind=${bindMethod?.name}, onClick=${onClickMethod?.name}, " +
                "valueBitGates=${valueBitGates.size}, iconRes=${iconResMethod?.name})"
        )
    }

    private const val BIND_RETRY_DELAY_MS = 120L

    private fun resolveCustomAdapterClass(sourceDir: String?, classLoader: ClassLoader): Class<*>? {
        if (!sourceDir.isNullOrEmpty()) {
            runCatching {
                DexKitBridge.create(sourceDir).use { bridge ->
                    val data = bridge.findClass {
                        searchPackages(TOOLBAR_PACKAGE)
                        matcher {
                            usingStrings("addToolbar valueBit:", "addRecentUseForToolbar")
                        }
                    }.firstOrNull()
                    if (data != null) {
                        return loadClassOrNull(data.name, classLoader)
                    }
                }
            }
        }
        return loadClassOrNull(FALLBACK_CUSTOM_TOOLBAR_ADAPTER_CLASS, classLoader)
    }

    private fun resolveCustomViewHolderClass(sourceDir: String?, classLoader: ClassLoader): Class<*>? {
        return loadClassOrNull(FALLBACK_CUSTOM_TOOLBAR_VIEW_HOLDER_CLASS, classLoader)
    }

    private fun resolvePermanentSource(classLoader: ClassLoader): Any? {
        val clazz = loadClassOrNull(FALLBACK_TOOLBAR_ITEM_SOURCE_CLASS, classLoader)
        return clazz?.enumConstants?.firstOrNull { it is Enum<*> && it.name == TOOLBAR_SOURCE_PERMANENT }
    }

    private fun resolveIconResolverClass(sourceDir: String?, classLoader: ClassLoader): Class<*>? {
        if (!sourceDir.isNullOrEmpty()) {
            runCatching {
                DexKitBridge.create(sourceDir).use { bridge ->
                    val data = bridge.findClass {
                        searchPackages(TOOLBAR_PACKAGE)
                        matcher {
                            usingStrings(" not resId!!!!!")
                        }
                    }.firstOrNull()
                    if (data != null) {
                        return loadClassOrNull(data.name, classLoader)
                    }
                }
            }
        }
        return loadClassOrNull(FALLBACK_TOOLBAR_ICON_RESOLVER_CLASS, classLoader)
    }

    private fun seedPreferences(): SharedPreferences? = runCatching {
        Class.forName("android.app.ActivityThread")
            .getMethod("currentApplication")
            .invoke(null) as? Context
    }.getOrNull()?.let { context ->
        runCatching {
            (context.applicationContext ?: context)
                .getSharedPreferences(SEED_PREFS_NAME, Context.MODE_PRIVATE)
        }.getOrNull()
    }

    private fun markSeeded(preferences: SharedPreferences?) {
        preferences?.edit()?.putBoolean(SEED_PREFS_KEY, true)?.apply()
    }

    private fun clearSeeded(preferences: SharedPreferences?) {
        preferences?.edit()?.remove(SEED_PREFS_KEY)?.apply()
    }

    private fun readTupleSlot(pair: Any, preferred: String, fallbackIndex: Int): Any? = runCatching {
        val fields = pair.javaClass.declaredFields.filter { !Modifier.isStatic(it.modifiers) }
        val field = fields.firstOrNull { it.name == preferred } ?: fields.getOrNull(fallbackIndex) ?: return null
        field.isAccessible = true
        field.get(pair)
    }.getOrNull()
}
