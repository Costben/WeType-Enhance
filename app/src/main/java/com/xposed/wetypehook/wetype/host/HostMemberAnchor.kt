package com.xposed.wetypehook.wetype.host

import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * 宿主成员的**形状优先**解析：先按形状在 [owner] 上找唯一命中，找不到才退回写死的混淆名。
 *
 * 宿主每次发版都会重排混淆名，但方法签名是源码形状、跨版本稳定。历史代码里大量
 * `declaredMethods.firstOrNull { it.name == "t0" && it.parameterTypes.isEmpty() }`
 * 的写法都收敛到这两个原语上：
 *
 * - 形状在 [owner] 上唯一 —— 名字完全不参与判断，宿主改名也不影响；
 * - 形状不唯一或没命中 —— 按 [names] 顺序退回，行为与改动前逐字一致。
 *
 * 所以「判据算错」的代价只是自检里少一条结构命中，不会造成断链。
 */
internal fun pickHostMethod(
    owner: Class<*>,
    vararg names: String,
    shape: (Method) -> Boolean
): Method? {
    owner.declaredMethods.filter(shape).singleOrNull()?.let {
        it.isAccessible = true
        return it
    }
    for (name in names) {
        owner.declaredMethods.firstOrNull { it.name == name && shape(it) }?.let {
            it.isAccessible = true
            return it
        }
    }
    return null
}

/** [pickHostMethod] 的字段版：形状唯一就不看名字。 */
internal fun pickHostField(
    owner: Class<*>,
    vararg names: String,
    shape: (Field) -> Boolean = { true }
): Field? {
    owner.declaredFields.filter(shape).singleOrNull()?.let {
        it.isAccessible = true
        return it
    }
    for (name in names) {
        owner.declaredFields.firstOrNull { it.name == name && shape(it) }?.let {
            it.isAccessible = true
            return it
        }
    }
    return null
}
