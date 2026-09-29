package com.xposed.wetypehook.wetype.hook

import android.view.View
import android.view.ViewGroup
import com.xposed.wetypehook.wetype.settings.WeTypeSettings
import com.xposed.wetypehook.xposed.Log
import com.xposed.wetypehook.xposed.hookAfter
import com.xposed.wetypehook.xposed.hookBefore
import com.xposed.wetypehook.xposed.loadClassOrNull
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 让「AI 写作」出现在微信输入法 **设置 → 定制工具栏** 页，并与「开启 ColorOS AI写」总开关保持联动。
 *
 * 结构化无混淆依赖设计：
 * 1. 宿主类 [CUSTOM_TOOLBAR_VIEW_CLASS] 是未混淆的公开命名；
 * 2. 免除对 a 类名及 r/w 资源类的依赖：在宿主构造完成时，直接从私有 Map (ITEMSS_CONFIG_MAP) 中
 *    提取宿主已植入的自带 AI 项 (valueBit = 1048576) 作为原型，通过结构分析动态克隆出 valueBit = -9527
 *    的新条目配置对象；
 * 3. 动态识别条目 View (c) 及其文案下发方法 (J)：通过拦截 `d(config): ViewGroup` 的返回值反查其真实类，
 *    并在该类上查找 `(int, CharSequence, boolean, boolean) -> void` 挂载文本替换钩子；
 * 4. 全链路开关联动：当 [WeTypeSettings.isColorosAiWriterEnabledXposed] 为 true 时注入条目，
 *    为 false 时立即从 Map、展示顺序 List 以及展示列表中剔除，保证开关关闭后在定制页彻底消失。
 */
internal object WeTypeSettingsToolbarHooks {

    private val CUSTOM_TOOLBAR_VIEW_CLASSES = listOf(
        "com.tencent.wetype.plugin.hld.view.settingkeyboard.S10SettingCustomToolbarView",
        "com.tencent.mm.ui.widget.imageview.settingkeyboard.S10SettingCustomToolbarView"
    )
    private val FALLBACK_ITEM_CONFIG_CLASSES = listOf(
        "com.tencent.wetype.plugin.hld.view.settingkeyboard.a",
        "com.tencent.mm.ui.widget.imageview.settingkeyboard.a"
    )
    private val FALLBACK_ITEM_VIEW_CLASSES = listOf(
        "com.tencent.wetype.plugin.hld.view.settingkeyboard.c",
        "com.tencent.mm.ui.widget.imageview.settingkeyboard.c"
    )
    private const val FALLBACK_ICON_RES_CLASS = "com.tencent.wetype.plugin.hld.r"
    private const val FALLBACK_TITLE_RES_CLASS = "com.tencent.wetype.plugin.hld.w"

    private const val HOST_AI_WRITER_VALUE_BIT = 1048576
    private const val AI_WRITER_LABEL = "AI 写作"

    @Volatile
    private var cachedConfig: Any? = null

    @Volatile
    private var cachedValueBitGetter: Method? = null

    @Volatile
    private var itemViewHookInstalled = false

    /** 改写文案要重新走一次原方法，用线程局部标志挡住重入。 */
    private val labelOverrideGuard: ThreadLocal<Boolean> = ThreadLocal.withInitial { false }

    fun install(classLoader: ClassLoader) {
        val viewClass = CUSTOM_TOOLBAR_VIEW_CLASSES.firstNotNullOfOrNull {
            loadClassOrNull(it, classLoader)
        } ?: run {
            Log.e("WeTypeSettingsToolbar: S10SettingCustomToolbarView not found")
            return
        }

        // 两个私有 final 字段：Map 只有一个，List 只有一个 (同类型的 currentItemsConfig 是 var)
        val configMapField = viewClass.declaredFields.firstOrNull {
            Modifier.isFinal(it.modifiers) && Map::class.java.isAssignableFrom(it.type)
        }?.apply { isAccessible = true }

        val orderListField = viewClass.declaredFields.firstOrNull {
            Modifier.isFinal(it.modifiers) && List::class.java.isAssignableFrom(it.type)
        }?.apply { isAccessible = true }

        // 动态识别或静态兜底 itemView 的 applyText 方法
        val fallbackItemViewClass = FALLBACK_ITEM_VIEW_CLASSES.firstNotNullOfOrNull {
            loadClassOrNull(it, classLoader)
        }
        if (fallbackItemViewClass != null) {
            installItemViewHook(fallbackItemViewClass)
        }

        // 查找创建条目 View 的方法 (ITEM_CONFIG) -> ViewGroup
        val createItemViewMethod = viewClass.declaredMethods.firstOrNull { method ->
            ViewGroup::class.java.isAssignableFrom(method.returnType) &&
                method.parameterCount == 1 &&
                !method.parameterTypes[0].isPrimitive
        }?.apply { isAccessible = true }

        createItemViewMethod?.hookAfter { param ->
            val itemView = param.result as? ViewGroup ?: return@hookAfter
            if (!itemViewHookInstalled) {
                installItemViewHook(itemView.javaClass)
            }
        }

        viewClass.declaredConstructors.forEach { constructor ->
            constructor.isAccessible = true
            constructor.hookAfter { param ->
                val view = param.thisObject
                val isAiWriterEnabled = WeTypeSettings.isColorosAiWriterEnabledXposed()
                runCatching {
                    @Suppress("UNCHECKED_CAST")
                    val map = configMapField?.get(view) as? MutableMap<Any, Any> ?: return@hookAfter
                    if (isAiWriterEnabled) {
                        var config = cachedConfig
                        if (config == null) {
                            val hostTemplate = map[HOST_AI_WRITER_VALUE_BIT]
                            config = createOrCloneConfig(hostTemplate, classLoader)
                            cachedConfig = config
                        }
                        if (config != null) {
                            map.putIfAbsent(WeTypeToolbarAiWriterHooks.AI_WRITER_VALUE_BIT, config)
                            appendToOrderList(orderListField, view)
                        }
                    } else {
                        map.remove(WeTypeToolbarAiWriterHooks.AI_WRITER_VALUE_BIT)
                        removeFromOrderList(orderListField, view)
                    }
                }.onFailure { Log.e(it) }
            }
        }

        // 兜底：宿主拼展示列表的私有方法 (无参、返回 List)
        val listBuilder = viewClass.declaredMethods.firstOrNull { method ->
            Modifier.isPrivate(method.modifiers) &&
                method.parameterCount == 0 &&
                method.returnType == List::class.java
        }?.apply { isAccessible = true }

        listBuilder?.hookAfter { param ->
            val items = param.result as? MutableList<Any> ?: return@hookAfter
            val isAiWriterEnabled = WeTypeSettings.isColorosAiWriterEnabledXposed()
            if (isAiWriterEnabled) {
                if (items.any { extractValueBit(it) == WeTypeToolbarAiWriterHooks.AI_WRITER_VALUE_BIT }) {
                    return@hookAfter
                }
                cachedConfig?.let { items.add(it) }
            } else {
                items.removeAll { extractValueBit(it) == WeTypeToolbarAiWriterHooks.AI_WRITER_VALUE_BIT }
            }
        }

        Log.i(
            "WeTypeSettingsToolbar: installed (${viewClass.name}, constructors=" +
                "${viewClass.declaredConstructors.size}, mapField=${configMapField?.name}, " +
                "orderField=${orderListField?.name}, listBuilder=${listBuilder?.name}, " +
                "createItemView=${createItemViewMethod?.name})"
        )
    }

    private fun installItemViewHook(itemViewClass: Class<*>) {
        if (itemViewHookInstalled) return
        val applyTextMethod = itemViewClass.declaredMethods.firstOrNull { method ->
            method.parameterCount == 4 &&
                method.returnType == Void.TYPE &&
                method.parameterTypes[0] == Int::class.javaPrimitiveType &&
                method.parameterTypes[1] == CharSequence::class.java &&
                method.parameterTypes[2] == Boolean::class.javaPrimitiveType &&
                method.parameterTypes[3] == Boolean::class.javaPrimitiveType
        }?.apply { isAccessible = true } ?: return

        applyTextMethod.hookBefore { param ->
            if (labelOverrideGuard.get() == true) return@hookBefore
            if (!WeTypeSettings.isColorosAiWriterEnabledXposed()) return@hookBefore
            val view = param.thisObject as? View ?: return@hookBefore
            val tagged = runCatching { view.tag }.getOrNull() ?: return@hookBefore
            val valueBit = extractValueBit(tagged)
            if (valueBit != WeTypeToolbarAiWriterHooks.AI_WRITER_VALUE_BIT) return@hookBefore

            val iconRes = param.args.getOrNull(0) as? Int ?: return@hookBefore
            val selected = param.args.getOrNull(2) as? Boolean ?: false
            val enabled = param.args.getOrNull(3) as? Boolean ?: true
            labelOverrideGuard.set(true)
            try {
                applyTextMethod.invoke(view, iconRes, AI_WRITER_LABEL, selected, enabled)
            } catch (throwable: Throwable) {
                Log.e(throwable)
            } finally {
                labelOverrideGuard.set(false)
            }
            param.result = null
        }
        itemViewHookInstalled = true
        Log.i("WeTypeSettingsToolbar: hooked itemView applyText method on ${itemViewClass.name}#${applyTextMethod.name}")
    }

    private fun extractValueBit(target: Any?): Int? {
        if (target == null) return null
        if (target is Number) return target.toInt()
        val getter = cachedValueBitGetter
        if (getter != null && getter.declaringClass.isInstance(target)) {
            return runCatching { getter.invoke(target) as? Int }.getOrNull()
        }
        val targetClass = target.javaClass
        val resolvedGetter = targetClass.declaredMethods.firstOrNull { method ->
            method.parameterCount == 0 &&
                method.returnType == Int::class.javaPrimitiveType &&
                method.name != "hashCode" &&
                runCatching {
                    method.isAccessible = true
                    method.invoke(target)
                }.getOrNull() == WeTypeToolbarAiWriterHooks.AI_WRITER_VALUE_BIT
        }?.apply { isAccessible = true }

        if (resolvedGetter != null) {
            cachedValueBitGetter = resolvedGetter
            return WeTypeToolbarAiWriterHooks.AI_WRITER_VALUE_BIT
        }
        return null
    }

    private fun createOrCloneConfig(hostTemplate: Any?, classLoader: ClassLoader): Any? {
        if (hostTemplate != null) {
            val templateClass = hostTemplate.javaClass
            val constructor = templateClass.declaredConstructors.firstOrNull { c ->
                c.parameterCount == 3 && c.parameterTypes.all { it == Int::class.javaPrimitiveType }
            }?.apply { isAccessible = true }

            val intFields = templateClass.declaredFields.filter {
                it.type == Int::class.javaPrimitiveType && !Modifier.isStatic(it.modifiers)
            }.onEach { it.isAccessible = true }

            if (constructor != null && intFields.size >= 3) {
                val valueBitField = intFields.firstOrNull { runCatching { it.getInt(hostTemplate) }.getOrNull() == HOST_AI_WRITER_VALUE_BIT }
                val otherInts = intFields.filter { it !== valueBitField }
                val arg1 = runCatching { otherInts.getOrNull(0)?.getInt(hostTemplate) }.getOrNull() ?: 0
                val arg2 = runCatching { otherInts.getOrNull(1)?.getInt(hostTemplate) }.getOrNull() ?: 0

                val newInstance = runCatching {
                    constructor.newInstance(WeTypeToolbarAiWriterHooks.AI_WRITER_VALUE_BIT, arg1, arg2)
                }.getOrNull() ?: runCatching {
                    val cloned = constructor.newInstance(HOST_AI_WRITER_VALUE_BIT, arg1, arg2)
                    valueBitField?.setInt(cloned, WeTypeToolbarAiWriterHooks.AI_WRITER_VALUE_BIT)
                    cloned
                }.getOrNull()

                if (newInstance != null) {
                    valueBitField?.setInt(newInstance, WeTypeToolbarAiWriterHooks.AI_WRITER_VALUE_BIT)
                    resolveValueBitGetter(templateClass, newInstance)
                    Log.i("WeTypeSettingsToolbar: dynamically cloned config from host template (${templateClass.name})")
                    return newInstance
                }
            }
        }

        // 静态兜底
        val configClass = FALLBACK_ITEM_CONFIG_CLASSES.firstNotNullOfOrNull {
            loadClassOrNull(it, classLoader)
        } ?: return null
        val iconResId = staticIntField(loadClassOrNull(FALLBACK_ICON_RES_CLASS, classLoader), "icon_settings_item_ai") ?: return null
        val titleResId = staticIntField(loadClassOrNull(FALLBACK_TITLE_RES_CLASS, classLoader), "ime_s10_ai") ?: return null
        val configConstructor = configClass.declaredConstructors.firstOrNull { constructor ->
            constructor.parameterCount == 3 && constructor.parameterTypes.all { it == Int::class.javaPrimitiveType }
        }?.apply { isAccessible = true } ?: return null

        val fallbackInstance = runCatching {
            configConstructor.newInstance(WeTypeToolbarAiWriterHooks.AI_WRITER_VALUE_BIT, iconResId, titleResId)
        }.getOrNull() ?: return null

        resolveValueBitGetter(configClass, fallbackInstance)
        return fallbackInstance
    }

    private fun resolveValueBitGetter(configClass: Class<*>, instance: Any) {
        if (cachedValueBitGetter != null) return
        val getter = configClass.declaredMethods.firstOrNull { method ->
            method.parameterCount == 0 &&
                method.returnType == Int::class.javaPrimitiveType &&
                method.name != "hashCode" &&
                runCatching {
                    method.isAccessible = true
                    method.invoke(instance)
                }.getOrNull() == WeTypeToolbarAiWriterHooks.AI_WRITER_VALUE_BIT
        }?.apply { isAccessible = true }
        cachedValueBitGetter = getter
    }

    private fun appendToOrderList(orderListField: Field?, view: Any) {
        val order = orderListField?.get(view) as? List<*> ?: return
        if (order.any { (it as? Number)?.toInt() == WeTypeToolbarAiWriterHooks.AI_WRITER_VALUE_BIT }) return
        val mutable = ArrayList<Any?>(order.size + 1)
        mutable.addAll(order)
        mutable.add(WeTypeToolbarAiWriterHooks.AI_WRITER_VALUE_BIT)
        orderListField.set(view, mutable)
    }

    private fun removeFromOrderList(orderListField: Field?, view: Any) {
        val order = orderListField?.get(view) as? List<*> ?: return
        if (order.none { (it as? Number)?.toInt() == WeTypeToolbarAiWriterHooks.AI_WRITER_VALUE_BIT }) return
        val mutable = ArrayList<Any?>(order.size)
        order.forEach { item ->
            if ((item as? Number)?.toInt() != WeTypeToolbarAiWriterHooks.AI_WRITER_VALUE_BIT) {
                mutable.add(item)
            }
        }
        orderListField.set(view, mutable)
    }

    private fun staticIntField(owner: Class<*>?, name: String): Int? {
        val field = owner?.declaredFields?.firstOrNull { it.name == name } ?: return null
        field.isAccessible = true
        return runCatching { field.getInt(null) }.getOrNull()
    }
}
