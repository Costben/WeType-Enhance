package com.xposed.wetypehook.wetype.hook

import com.xposed.wetypehook.wetype.host.GlideShape
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Glide 图片下载链的结构化解析。
 *
 * 链上每一步都按方法形状 + 返回值自校验定位，方法名只在多个同形候选之间做优先排序。
 * 入口类与 RequestListener 接口同样按形状认（见 [GlideShape]），
 * 只在结构锚失效时才退回写死的混淆名。宿主 R8 重排名字时（4.0.0 的 `L0/G0/Q0` 与 3.5.4
 * 是同一组形状、不同短名）整条链仍然成立。
 */
internal object WeTypeGlideChain {

    const val MANAGER_NAME_HINT = "u"
    const val BUILDER_NAME_HINT = "r"
    const val LOAD_NAME_HINT = "L0"
    const val LISTENER_NAME_HINT = "G0"
    const val START_NAME_HINT = "Q0"
    const val REQUEST_NAME_HINT = "a"

    private fun prefer(candidates: List<Method>, nameHint: String): Method? =
        candidates.firstOrNull { it.name == nameHint } ?: candidates.minByOrNull { it.name }

    fun hierarchy(cls: Class<*>): List<Class<*>> = GlideShape.hierarchy(cls)

    fun declaredInHierarchy(cls: Class<*>): List<Method> = GlideShape.declaredInHierarchy(cls)

    /** Glide 持有器：静态、单参 Context、返回类型具备 RequestManager 指纹。 */
    fun resolveManager(
        glideClass: Class<*>,
        contextType: Class<*>,
        listenerInterface: Class<*>
    ): Method? {
        val candidates = glideClass.declaredMethods.filter {
            Modifier.isStatic(it.modifiers) && it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == contextType && it.returnType != Void.TYPE &&
                hasBuilderFactory(it.returnType, listenerInterface)
        }
        return prefer(candidates, MANAGER_NAME_HINT)?.apply { isAccessible = true }
    }

    /** RequestManager 指纹：存在无参方法返回一个 RequestBuilder 形状的类型。 */
    fun hasBuilderFactory(cls: Class<*>, listenerInterface: Class<*>): Boolean =
        declaredInHierarchy(cls).any {
            !Modifier.isStatic(it.modifiers) && it.parameterTypes.isEmpty() &&
                it.returnType != Void.TYPE && hasBuilderShape(it.returnType, listenerInterface)
        }

    /**
     * RequestBuilder 指纹：既能按 String 载入、又能接收 RequestListener，且都返回自身类型。
     *
     * 判据必须容忍「返回父类型」：宿主自己的构建器继承 Glide 的 RequestBuilder，基类声明的是
     * 返回基类型的桥方法，只有子类那条协变重载才返回自身。
     */
    fun hasBuilderShape(cls: Class<*>, listenerInterface: Class<*>): Boolean {
        val methods = declaredInHierarchy(cls)
        val loads = methods.any {
            !Modifier.isStatic(it.modifiers) && it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == String::class.java &&
                it.returnType != Any::class.java && relatedTo(cls, it.returnType)
        }
        val listens = methods.any {
            !Modifier.isStatic(it.modifiers) && it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == listenerInterface &&
                it.returnType != Any::class.java && relatedTo(cls, it.returnType)
        }
        return loads && listens
    }

    // ---- 不依赖已知类名的形状：入口类、RequestListener 接口（判据在 [GlideShape]） ----

    /**
     * RequestListener 接口（从 RequestBuilder 反推）：单参、参数是接口、返回自身类型族。
     *
     * 只在 [GlideShape.looksLikeRequestBuilder] 已经确认过构建器时调用，所以这里不必再挑名字。
     */
    fun resolveListenerInterface(builderClass: Class<*>): Class<*>? =
        declaredInHierarchy(builderClass).firstOrNull {
            !Modifier.isStatic(it.modifiers) && it.parameterTypes.size == 1 &&
                it.parameterTypes[0].isInterface && GlideShape.looksLikeRequestListener(it.parameterTypes[0]) &&
                it.returnType != Any::class.java && relatedTo(builderClass, it.returnType)
        }?.parameterTypes?.get(0)

    /** [resolveManager] 的不依赖 RequestListener 版本：按 RequestManager 形状认。 */
    fun resolveManager(glideClass: Class<*>, contextType: Class<*>): Method? {
        val candidates = glideClass.declaredMethods.filter {
            Modifier.isStatic(it.modifiers) && it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == contextType && GlideShape.looksLikeRequestManager(it.returnType)
        }
        return prefer(candidates, MANAGER_NAME_HINT)?.apply { isAccessible = true }
    }

    /**
     * [resolveBuilderFactory] 的不依赖 RequestListener 版本：逐个同形候选真调用一次，
     * 读回构建器自声明的转码类型，只有等于 [fileType] 的才算命中。
     */
    fun resolveBuilderFactory(manager: Any, fileType: Class<*>): Method? {
        val candidates = declaredInHierarchy(manager.javaClass).filter {
            !Modifier.isStatic(it.modifiers) && it.parameterTypes.isEmpty() &&
                it.returnType != Void.TYPE && GlideShape.looksLikeRequestBuilder(it.returnType)
        }.sortedBy { if (it.name == BUILDER_NAME_HINT) 0 else 1 }
        for (method in candidates) {
            method.isAccessible = true
            val builder = runCatching { method.invoke(manager) }.getOrNull() ?: continue
            if (transcodeTypes(builder).any { it == fileType }) return method
        }
        return null
    }

    /**
     * RequestManager -> RequestBuilder：逐个同形候选真调用一次，读回构建器自声明的转码类型，
     * 只有等于 [fileType]（java.io.File）的才算命中，避免误选 asBitmap / asDrawable。
     */
    fun resolveBuilderFactory(
        manager: Any,
        listenerInterface: Class<*>,
        fileType: Class<*>
    ): Method? {
        val candidates = declaredInHierarchy(manager.javaClass).filter {
            !Modifier.isStatic(it.modifiers) && it.parameterTypes.isEmpty() &&
                it.returnType != Void.TYPE && hasBuilderShape(it.returnType, listenerInterface)
        }.sortedBy { if (it.name == BUILDER_NAME_HINT) 0 else 1 }
        for (method in candidates) {
            method.isAccessible = true
            val builder = runCatching { method.invoke(manager) }.getOrNull() ?: continue
            if (transcodeTypes(builder).any { it == fileType }) return method
        }
        return null
    }

    /** 构建器自声明的转码类型（Glide RequestBuilder 的 transcodeClass，字段类型是 java.lang.Class）。 */
    fun transcodeTypes(builder: Any): List<Class<*>> = hierarchy(builder.javaClass).flatMap { cls ->
        cls.declaredFields.mapNotNull { field ->
            if (field.type != Class::class.java) return@mapNotNull null
            runCatching {
                field.isAccessible = true
                field.get(builder) as? Class<*>
            }.getOrNull()
        }
    }

    /**
     * RequestBuilder.load(String)：单参 String，返回自身类型或它的父类型。
     *
     * 宿主自己继承了 RequestBuilder（`utils.t extends com.bumptech.glide.l`）并把
     * `load(String)` 覆写成协变返回，编译器会额外生成一条返回父类型的桥方法 —— 两条都算命中，
     * 名字提示负责在其中挑出 `load` 而不是别的同参方法。
     */
    fun resolveLoad(builderClass: Class<*>): Method? =
        prefer(
            declaredInHierarchy(builderClass).filter {
                !Modifier.isStatic(it.modifiers) && it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == String::class.java &&
                    it.returnType != Any::class.java && relatedTo(builderClass, it.returnType)
            },
            LOAD_NAME_HINT
        )?.apply { isAccessible = true }

    /** RequestBuilder.listener(RequestListener)：单参 RequestListener，返回自身类型或它的父类型。 */
    fun resolveListener(loadedClass: Class<*>, listenerInterface: Class<*>): Method? =
        prefer(
            declaredInHierarchy(loadedClass).filter {
                !Modifier.isStatic(it.modifiers) && it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == listenerInterface &&
                    it.returnType != Any::class.java && relatedTo(loadedClass, it.returnType)
            },
            LISTENER_NAME_HINT
        )?.apply { isAccessible = true }

    /** 返回值与自身同类族（自身、子类、或覆写前的父类型）都算相关。 */
    private fun relatedTo(cls: Class<*>, returnType: Class<*>): Boolean =
        GlideShape.relatedTo(cls, returnType)

    /**
     * 启动请求：无参、返回接口，且同一类族里存在 `(int, int)` 重载返回同一接口。
     *
     * 这条签名对只属于 `submit` / `preload`。**不能按方法名配对**：R8 给每个重载分配各自
     * 独立的短名，`submit()` / `submit(int,int)` 在 4.0.0 是 `Q0` / `R0`，名字并不相同，
     * 按名字配对会一条都命中不了。
     */
    fun resolveStart(builderClass: Class<*>): Method? {
        val all = declaredInHierarchy(builderClass)
        val candidates = all.filter { method ->
            !Modifier.isStatic(method.modifiers) && method.parameterTypes.isEmpty() &&
                method.returnType != Void.TYPE && method.returnType.isInterface &&
                all.any { other ->
                    other !== method && !Modifier.isStatic(other.modifiers) &&
                        other.parameterTypes.size == 2 &&
                        other.parameterTypes[0] == Int::class.javaPrimitiveType &&
                        other.parameterTypes[1] == Int::class.javaPrimitiveType &&
                        other.returnType == method.returnType
                }
        }
        return prefer(candidates, START_NAME_HINT)?.apply { isAccessible = true }
    }

    /** Target.getRequest()：无参、返回接口。持有它可防止 Glide 弱引用表把请求回收。 */
    fun resolveRequest(targetClass: Class<*>): Method? =
        prefer(
            declaredInHierarchy(targetClass).filter {
                !Modifier.isStatic(it.modifiers) && it.parameterTypes.isEmpty() &&
                    it.returnType != Void.TYPE && it.returnType.isInterface
            },
            REQUEST_NAME_HINT
        )?.apply { isAccessible = true }
}
