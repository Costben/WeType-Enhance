package com.xposed.wetypehook.xposed

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

private data class ClassCacheKey(
    val name: String,
    val classLoader: ClassLoader?
)

private data class FieldCacheKey(
    val owner: Class<*>,
    val name: String
)

private data class InvokeMethodCacheKey(
    val owner: Class<*>,
    val name: String,
    val isStatic: Boolean,
    val argumentSignature: List<String?>
)

object HookEnvironment {
    @Volatile
    private var currentClassLoader: ClassLoader? = null

    @Volatile
    private var currentLogTag: String = "xposed"

    fun init(classLoader: ClassLoader?, logTag: String) {
        currentClassLoader = classLoader
        currentLogTag = logTag
    }

    internal fun resolveClassLoader(explicitClassLoader: ClassLoader?): ClassLoader? =
        explicitClassLoader ?: currentClassLoader

    internal fun logTag(): String = currentLogTag
}

object Log {
    fun i(message: Any?) {
        log("I", message)
    }

    fun e(message: Any?) {
        log("E", message)
    }

    private fun log(level: String, message: Any?) {
        if (message is Throwable) {
            XposedBridge.log("[${HookEnvironment.logTag()}][$level] ${message.message ?: message.javaClass.name}")
            XposedBridge.log(message)
            return
        }
        XposedBridge.log("[${HookEnvironment.logTag()}][$level] ${message ?: "null"}")
    }
}

private val primitiveToWrapper = mapOf(
    Boolean::class.javaPrimitiveType to Boolean::class.javaObjectType,
    Byte::class.javaPrimitiveType to Byte::class.javaObjectType,
    Char::class.javaPrimitiveType to Char::class.javaObjectType,
    Double::class.javaPrimitiveType to Double::class.javaObjectType,
    Float::class.javaPrimitiveType to Float::class.javaObjectType,
    Int::class.javaPrimitiveType to Int::class.javaObjectType,
    Long::class.javaPrimitiveType to Long::class.javaObjectType,
    Short::class.javaPrimitiveType to Short::class.javaObjectType,
    Void.TYPE to Void::class.java
)

private fun boxed(clazz: Class<*>): Class<*> = primitiveToWrapper[clazz] ?: clazz

private val classCache = ConcurrentHashMap<ClassCacheKey, Class<*>>()
private val fieldCache = ConcurrentHashMap<FieldCacheKey, Field>()
private val invokeMethodCache = ConcurrentHashMap<InvokeMethodCacheKey, Method>()

fun loadClassOrNull(className: String, classLoader: ClassLoader? = null): Class<*>? {
    val resolvedClassLoader = HookEnvironment.resolveClassLoader(classLoader)
    val key = ClassCacheKey(className, resolvedClassLoader)
    classCache[key]?.let { return it }

    val loadedClass = runCatching {
        Class.forName(className, false, resolvedClassLoader)
    }.getOrNull()
    if (loadedClass != null) {
        classCache[key] = loadedClass
    }
    return loadedClass
}

fun findMethod(
    className: String,
    classLoader: ClassLoader? = null,
    predicate: Method.() -> Boolean
): Method {
    val owner = loadClassOrNull(className, classLoader)
        ?: throw ClassNotFoundException("Class not found: $className")
    return owner.findMethod(predicate)
}

fun Class<*>.findMethod(predicate: Method.() -> Boolean): Method {
    declaredMethods.firstOrNull(predicate)?.let { method ->
        method.isAccessible = true
        return method
    }
    throw NoSuchMethodException("No method matched in ${name}")
}

fun Class<*>.findMethodInHierarchy(predicate: Method.() -> Boolean): Method {
    var searchClass: Class<*>? = this
    while (searchClass != null) {
        searchClass.declaredMethods.firstOrNull(predicate)?.let { method ->
            method.isAccessible = true
            return method
        }
        searchClass = searchClass.superclass
    }
    throw NoSuchMethodException("No method matched in hierarchy of ${name}")
}

fun Array<Class<*>>.sameAs(vararg types: Class<*>): Boolean {
    if (size != types.size) return false
    return indices.all { index ->
        boxed(this[index]) == boxed(types[index])
    }
}

fun Method.hookBefore(callback: (XC_MethodHook.MethodHookParam) -> Unit) {
    XposedBridge.hookMethod(
        this,
        object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                callback(param)
            }
        }
    )
}

fun Method.hookAfter(callback: (XC_MethodHook.MethodHookParam) -> Unit) {
    XposedBridge.hookMethod(
        this,
        object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                callback(param)
            }
        }
    )
}

fun Method.hookReplace(callback: (XC_MethodHook.MethodHookParam) -> Any?) {
    XposedBridge.hookMethod(
        this,
        object : XC_MethodReplacement() {
            override fun replaceHookedMethod(param: MethodHookParam): Any? = callback(param)
        }
    )
}

fun Method.hookReturnConstant(result: Any?) {
    XposedBridge.hookMethod(this, XC_MethodReplacement.returnConstant(result))
}

@Suppress("UNCHECKED_CAST")
fun <T> Any.getObjectAs(fieldName: String): T? = findField(javaClass, fieldName).get(this) as? T

fun Class<*>.getStaticObject(fieldName: String): Any? = findField(this, fieldName).get(null)

fun Class<*>.putStaticObject(fieldName: String, value: Any?) {
    findField(this, fieldName).set(null, value)
}

@Suppress("UNCHECKED_CAST")
fun <T> Any.invokeMethodAs(methodName: String, vararg args: Any?): T? =
    findCompatibleMethod(javaClass, methodName, isStatic = false, args = args).invoke(this, *args) as? T

fun Class<*>.invokeStaticMethodAuto(methodName: String, vararg args: Any?): Any? =
    findCompatibleMethod(this, methodName, isStatic = true, args = args).invoke(null, *args)

private fun findField(owner: Class<*>, fieldName: String): Field {
    val key = FieldCacheKey(owner, fieldName)
    return fieldCache.getOrPut(key) {
        var searchClass: Class<*>? = owner
        while (searchClass != null) {
            runCatching {
                searchClass.getDeclaredField(fieldName)
            }.getOrNull()?.let { field ->
                field.isAccessible = true
                return@getOrPut field
            }
            searchClass = searchClass.superclass
        }
        throw NoSuchFieldException("${owner.name}#$fieldName")
    }
}

private fun findCompatibleMethod(
    owner: Class<*>,
    methodName: String,
    isStatic: Boolean,
    args: Array<out Any?>
): Method {
    val key = InvokeMethodCacheKey(
        owner = owner,
        name = methodName,
        isStatic = isStatic,
        argumentSignature = args.map { argument -> argument?.javaClass?.name }
    )
    return invokeMethodCache.getOrPut(key) {
        resolveCompatibleMethod(owner, methodName, isStatic, args)
    }
}

private fun resolveCompatibleMethod(
    owner: Class<*>,
    methodName: String,
    isStatic: Boolean,
    args: Array<out Any?>
): Method {
    var searchClass: Class<*>? = owner
    while (searchClass != null) {
        searchClass.declaredMethods.firstOrNull { method ->
            method.name == methodName &&
                Modifier.isStatic(method.modifiers) == isStatic &&
                method.parameterTypes.size == args.size &&
                method.parameterTypes.indices.all { index ->
                    isCompatibleArgument(method.parameterTypes[index], args[index])
                }
        }?.let { method ->
            method.isAccessible = true
            return method
        }
        searchClass = searchClass.superclass
    }

    owner.methods.firstOrNull { method ->
        method.name == methodName &&
            Modifier.isStatic(method.modifiers) == isStatic &&
            method.parameterTypes.size == args.size &&
            method.parameterTypes.indices.all { index ->
                isCompatibleArgument(method.parameterTypes[index], args[index])
            }
    }?.let { method ->
        method.isAccessible = true
        return method
    }

    throw NoSuchMethodException("${owner.name}#$methodName(${args.joinToString { it?.javaClass?.name ?: "null" }})")
}

private fun isCompatibleArgument(parameterType: Class<*>, argument: Any?): Boolean {
    if (argument == null) return !parameterType.isPrimitive
    return boxed(parameterType).isAssignableFrom(boxed(argument.javaClass))
}
