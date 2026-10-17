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
import java.lang.reflect.Method
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
    private const val TOOLBAR_CONFIG_PACKAGE = "com.tencent.wetype.plugin.hld.utils"
    private const val TOOLBAR_CONFIG_KEY = "ime_custom_toolbar_key"
    private const val FALLBACK_TOOLBAR_CONFIG_CLASS = "com.tencent.wetype.plugin.hld.utils.j1"
    private const val FALLBACK_CUSTOM_TOOLBAR_ADAPTER_CLASS = "com.tencent.wetype.plugin.hld.toolbar.a"
    private const val FALLBACK_TOOLBAR_ROUND_VIEW_HOLDER_CLASS = "com.tencent.wetype.plugin.hld.toolbar.B"
    private const val FALLBACK_CUSTOM_TOOLBAR_VIEW_HOLDER_CLASS = "com.tencent.wetype.plugin.hld.toolbar.m"
    private const val FALLBACK_TOOLBAR_ICON_RESOLVER_CLASS = "com.tencent.wetype.plugin.hld.toolbar.c"
    private const val FALLBACK_TOOLBAR_ITEM_SOURCE_CLASS = "com.tencent.wetype.plugin.hld.toolbar.j"

    private const val TOOLBAR_SOURCE_PERMANENT = "Permanent"

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 宿主工具栏的 RecyclerView 方向（3.5.5 起列表首项排在最右）。
     * 由已绑定条目的实测 x 判断，判断不出来时按未反向处理。
     */
    @Volatile
    private var toolbarReversed: Boolean? = null

    private fun insertAiWriter(
        valueBit: Int,
        target: MutableList<Any?>,
        preferredIndex: Int?,
        reversed: Boolean?,
    ) {
        if (target.any { (it as? Number)?.toInt() == valueBit }) return
        val index = when {
            target.isEmpty() -> 0
            preferredIndex != null -> preferredIndex.coerceIn(0, target.size)
            // 反向布局：列表末尾排在最左，紧挨宿主固定的品牌 logo。
            reversed == true -> target.size
            else -> 1.coerceAtMost(target.size)
        }
        target.add(index, valueBit)
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
        val valueBitField = pickHostField(roundViewHolderClass, "valueBit", "g", "f") {
            it.type == Int::class.javaPrimitiveType
        }
        val notifyMethod = runCatching { customAdapterClass.getMethod("notifyDataSetChanged") }.getOrNull()
        val configReader = resolveToolbarConfigReader(sourceDir, classLoader)

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

        /**
         * 宿主工具栏可能把列表首项排在最右（3.5.5 实测如此）。用已绑定条目的实测 x 判断方向，
         * 判断不出来返回 null，调用方按旧行为处理。
         */
        fun detectToolbarReversed(adapter: Any?): Boolean? {
            toolbarReversed?.let { return it }
            val holders = runCatching { viewHoldersField?.get(adapter) as? Map<*, *> }.getOrNull() ?: return null
            val tools = runCatching { toolsField?.get(adapter) as? List<*> }.getOrNull() ?: return null
            val firstBit = (tools.firstOrNull() as? Number)?.toInt() ?: return null
            val lastBit = (tools.lastOrNull() as? Number)?.toInt() ?: return null
            if (firstBit == lastBit) return null
            fun xOf(bit: Int): Float? {
                val holder = holders[bit] ?: return null
                val itemView = runCatching {
                    holder.javaClass.getField("itemView").get(holder) as? View
                }.getOrNull() ?: return null
                return itemView.x
            }
            val firstX = xOf(firstBit) ?: return null
            val lastX = xOf(lastBit) ?: return null
            if (firstX == lastX) return null
            return (firstX > lastX).also { toolbarReversed = it }
        }

        /** 宿主持久化配置里该条目的位置；用于保留用户拖拽后的排序。 */
        fun persistedIndexInHostConfig(): Int? = runCatching {
            val (instance, reader) = configReader ?: return null
            val config = reader.invoke(instance) ?: return null
            val list = config.javaClass.getMethod("getToolsList").invoke(config) as? List<*> ?: return null
            list.indexOfFirst { (it as? Number)?.toInt() == AI_WRITER_VALUE_BIT }.takeIf { it >= 0 }
        }.getOrNull()

        /** 布局出来之后把条目校正到左起第 2 位（只在首次播种、方向未知时调用）。 */
        fun ensureSecondFromLeft(adapter: Any?) {
            val tools = runCatching { toolsField?.get(adapter) as? MutableList<Any?> }.getOrNull() ?: return
            val aiIndex = tools.indexOfFirst { (it as? Number)?.toInt() == AI_WRITER_VALUE_BIT }
            if (aiIndex < 0) return
            val reversed = detectToolbarReversed(adapter) ?: return
            val desired = if (reversed) tools.size - 1 else 1
            if (aiIndex == desired) return
            val item = tools.removeAt(aiIndex)
            tools.add(desired.coerceIn(0, tools.size), item)
            runCatching { notifyMethod?.invoke(adapter) }
            Log.i("WeTypeToolbarAiWriter: reordered toolbar item (reversed=$reversed, index=$desired)")
        }

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

        // 1. 来源枚举与宿主持久化配置读取（用于判断条目是否已被宿主写入配置）
        val permanentSource = resolvePermanentSource(classLoader)
        val sourceMapMethod = pickHostMethod(toolbarAdapterClass, "v") { method ->
            method.parameterCount == 0 && HashMap::class.java.isAssignableFrom(method.returnType)
        }

        fun isPersistedInHostConfig(): Boolean = runCatching {
            val (instance, reader) = configReader ?: return false
            val config = reader.invoke(instance) ?: return false
            val list = config.javaClass.getMethod("getToolsList").invoke(config) as? List<*> ?: return false
            list.any { (it as? Number)?.toInt() == AI_WRITER_VALUE_BIT }
        }.getOrDefault(false)

        // 2. updateData 方法 (4.0.0: I, 3.5.4: F)
        val updateDataMethod = pickHostMethod(customAdapterClass, "I", "F", "updateData") { method ->
            method.returnType == Void.TYPE &&
                method.parameterCount == 2 &&
                method.parameterTypes[0] == Boolean::class.javaPrimitiveType &&
                method.parameterTypes[1] == Boolean::class.javaPrimitiveType
        }

        updateDataMethod?.hookAfter { param ->
            val adapter = param.thisObject
            @Suppress("UNCHECKED_CAST")
            val tools = toolsField?.get(adapter) as? MutableList<Any?> ?: return@hookAfter
            val preferences = seedPreferences()
            val enabled = WeTypeSettings.isColorosAiWriterEnabledXposed()
            val present = tools.any { (it as? Number)?.toInt() == AI_WRITER_VALUE_BIT }

            if (!enabled) {
                if (present) {
                    tools.removeAll { (it as? Number)?.toInt() == AI_WRITER_VALUE_BIT }
                    runCatching { notifyMethod?.invoke(adapter) }
                }
                clearSeeded(preferences)
                return@hookAfter
            }

            val persisted = isPersistedInHostConfig()
            if (present) {
                if (persisted) markSeeded(preferences)
                applyFromViewHolderCache(adapter)
                mainHandler.postDelayed({ applyFromViewHolderCache(adapter) }, BIND_RETRY_DELAY_MS)
                return@hookAfter
            }

            // 宿主 3.5.5 在 updateData 里用 m$b.b(List) 把未知 valueBit 过滤掉，
            // 该过滤方法被 ART 内联后 hook 不到，所以在 updateData 结束后补回。
            // 只有宿主配置里从未写入过该条目、且未播种过时才补回；用户删除后不再补回。
            val seeded = preferences?.getBoolean(SEED_PREFS_KEY, false) ?: false
            if (seeded && !persisted) return@hookAfter

            val reversed = detectToolbarReversed(adapter)
            val persistedIndex = if (persisted) persistedIndexInHostConfig() else null
            insertAiWriter(AI_WRITER_VALUE_BIT, tools, persistedIndex, reversed)
            if (permanentSource != null) {
                @Suppress("UNCHECKED_CAST")
                (runCatching { sourceMapMethod?.invoke(adapter) }.getOrNull() as? MutableMap<Int, Any?>)
                    ?.put(AI_WRITER_VALUE_BIT, permanentSource)
            }
            runCatching { notifyMethod?.invoke(adapter) }
            // 读不到宿主配置时退回旧语义：播种一次即标记，用户删除后不再补回。
            if (persisted || configReader == null) markSeeded(preferences)
            Log.i(
                "WeTypeToolbarAiWriter: seeded toolbar item (persisted=$persisted, " +
                    "reversed=$reversed, index=${tools.indexOf(AI_WRITER_VALUE_BIT)})"
            )
            applyFromViewHolderCache(adapter)
            mainHandler.postDelayed({ applyFromViewHolderCache(adapter) }, BIND_RETRY_DELAY_MS)
            if (reversed == null && persistedIndex == null) {
                // 首次播种时工具栏还没排版，方向未知：等布局出来后再校正到左起第 2 位。
                mainHandler.postDelayed({ ensureSecondFromLeft(adapter) }, LAYOUT_SETTLE_DELAY_MS)
            }
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

        // 5. 点击响应 (4.0.0: g, 3.5.4: h; 若混淆名变更，由于基类抽象方法声明顺序，onClick 总按字母序排在 onTouchDown 之前)
        val onClickMethod = pickHostMethod(customViewHolderClass, "g", "h", "onClick") { method ->
            method.parameterCount == 1 &&
                method.parameterTypes[0] == View::class.java &&
                method.returnType == Void.TYPE &&
                !Modifier.isStatic(method.modifiers)
        } ?: customViewHolderClass.declaredMethods
            .filter {
                it.parameterCount == 1 &&
                    it.parameterTypes[0] == View::class.java &&
                    it.returnType == Void.TYPE &&
                    !Modifier.isStatic(it.modifiers)
            }
            .minByOrNull { it.name }
            ?.apply { isAccessible = true }

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
                "updateIcon=${updateIconMethod?.name}, " +
                "bind=${bindMethod?.name}, onClick=${onClickMethod?.name}, " +
                "valueBitGates=${valueBitGates.size}, iconRes=${iconResMethod?.name}, " +
                "configReader=${configReader != null})"
        )
    }

    private const val BIND_RETRY_DELAY_MS = 120L
    private const val LAYOUT_SETTLE_DELAY_MS = 400L

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

    /**
     * 宿主的工具栏配置（MMKV `ime_custom_toolbar_key`）读取器：返回单例与无参读取方法。
     * 用来判断 `-9527` 是否已经被宿主写进持久化配置，从而区分「从未持久化」与「用户已删除」。
     */
    private fun resolveToolbarConfigReader(sourceDir: String?, classLoader: ClassLoader): Pair<Any, Method>? {
        val clazz = runCatching {
            if (!sourceDir.isNullOrEmpty()) {
                DexKitBridge.create(sourceDir).use { bridge ->
                    bridge.findClass {
                        searchPackages(TOOLBAR_CONFIG_PACKAGE)
                        matcher { usingStrings(TOOLBAR_CONFIG_KEY) }
                    }.firstOrNull()?.let { loadClassOrNull(it.name, classLoader) }
                }
            } else {
                null
            }
        }.getOrNull() ?: loadClassOrNull(FALLBACK_TOOLBAR_CONFIG_CLASS, classLoader) ?: return null

        val instance = runCatching {
            clazz.declaredFields
                .firstOrNull { Modifier.isStatic(it.modifiers) && it.type == clazz }
                ?.apply { isAccessible = true }
                ?.get(null)
        }.getOrNull() ?: return null

        val reader = runCatching {
            clazz.declaredMethods.firstOrNull { method ->
                method.name == "N" &&
                    method.parameterCount == 0 &&
                    method.returnType.name.endsWith("ImeLocalCustomToolbarConfig")
            }?.apply { isAccessible = true }
        }.getOrNull() ?: return null

        return instance to reader
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
}
