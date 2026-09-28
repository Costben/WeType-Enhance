package com.xposed.wetypehook.wetype.host

import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Glide 的形状判据。
 *
 * Glide 自带混淆（入口类 `com.bumptech.glide.c`、RequestListener 接口 `q1.h`），类名随库版本变，
 * 但形状不变，所以入口类与监听接口都按形状认：
 *
 * - RequestListener：接口，恰好两条返回 boolean 的方法，参数个数 5 / 4；
 * - RequestBuilder：能 `load(String)`，也能接收上面那种接口，两条都返回自身类型族；
 * - RequestManager：存在无参方法返回 RequestBuilder；
 * - 入口类：有指向自身的静态字段（单例），且有一个静态 `(Context) -> RequestManager` 入口。
 *
 * 放在 host 层是因为契约层要用它定位入口类，而 hook 层的下载链要用同一份判据 —— 两边共用
 * 一份实现，避免「契约认一个类、链上认另一个类」。
 */
internal object GlideShape {

    fun hierarchy(cls: Class<*>): List<Class<*>> {
        val out = ArrayList<Class<*>>(8)
        var current: Class<*>? = cls
        while (current != null && current != Any::class.java) {
            out.add(current)
            current = current.superclass
        }
        return out
    }

    fun declaredInHierarchy(cls: Class<*>): List<Method> =
        hierarchy(cls).flatMap { it.declaredMethods.asIterable() }

    /** 返回值与自身同类族（自身、子类、或覆写前的父类型）都算相关。 */
    fun relatedTo(cls: Class<*>, returnType: Class<*>): Boolean =
        cls.isAssignableFrom(returnType) || returnType.isAssignableFrom(cls)

    fun looksLikeRequestListener(iface: Class<*>): Boolean {
        if (!iface.isInterface) return false
        val methods = iface.declaredMethods
        if (methods.size != 2) return false
        if (methods.any { it.returnType != Boolean::class.javaPrimitiveType && it.returnType != Boolean::class.java }) {
            return false
        }
        return methods.count { it.parameterTypes.size == 5 } == 1 &&
            methods.count { it.parameterTypes.size == 4 } == 1
    }

    fun looksLikeRequestBuilder(cls: Class<*>): Boolean {
        val methods = declaredInHierarchy(cls)
        val loads = methods.any {
            !Modifier.isStatic(it.modifiers) && it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == String::class.java &&
                it.returnType != Any::class.java && relatedTo(cls, it.returnType)
        }
        if (!loads) return false
        return methods.any {
            !Modifier.isStatic(it.modifiers) && it.parameterTypes.size == 1 &&
                it.parameterTypes[0].isInterface && looksLikeRequestListener(it.parameterTypes[0]) &&
                it.returnType != Any::class.java && relatedTo(cls, it.returnType)
        }
    }

    fun looksLikeRequestManager(cls: Class<*>): Boolean =
        declaredInHierarchy(cls).any {
            !Modifier.isStatic(it.modifiers) && it.parameterTypes.isEmpty() &&
                it.returnType != Void.TYPE && looksLikeRequestBuilder(it.returnType)
        }

    fun looksLikeGlideEntry(cls: Class<*>, contextType: Class<*>): Boolean =
        hasStaticSelfField(cls) &&
            cls.declaredMethods.any {
                Modifier.isStatic(it.modifiers) && it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == contextType && looksLikeRequestManager(it.returnType)
            }
}
