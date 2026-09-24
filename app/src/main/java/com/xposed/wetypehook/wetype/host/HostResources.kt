package com.xposed.wetypehook.wetype.host

import com.xposed.wetypehook.xposed.loadClassOrNull
import java.lang.reflect.Modifier

/**
 * 宿主 R 类的取用入口：调用点仍然按历史短名（`com.tencent.wetype.plugin.hld.s`）问，这里先查契约，
 * 查不到再退回名字加载。
 *
 * 为什么保留 `rClass(短名)` 这个形状，而不是让调用点直接传 [HostContractId]：
 * 调用点遍布多个 hook 文件且各自持有不同的短名常量，改签名要动十几处；保留旧入口
 * 就能一次改动覆盖全部，且短名兜底保证契约层出问题时行为与改动前完全一致。
 *
 * **不要改成 `Resources.getIdentifier(字段名, …)`。** 实测宿主 APK 的 `resources.arsc`
 * 名字池里查不到 `logo_iv` / `keyboard_container_rl` / `ime_logo_green_dark` 这些条目名
 * （R8 后资源条目名与源码标识符不再对应），getIdentifier 会静默返回 0。
 * 字段反射走的是 dex 里真实存在的 R 字段名，这条路是通的。
 */
internal object HostResources {

    private val CONTRACTS_BY_LEGACY_NAME = mapOf(
        "com.tencent.wetype.plugin.hld.s" to HostContractId.RESOURCE_CLASS_ID,
        "com.tencent.wetype.plugin.hld.r" to HostContractId.RESOURCE_CLASS_DRAWABLE,
        "com.tencent.wetype.plugin.hld.o" to HostContractId.RESOURCE_CLASS_ATTR,
        "com.tencent.wetype.plugin.hld.p" to HostContractId.RESOURCE_CLASS_COLOR,
        "com.tencent.wetype.plugin.hld.q" to HostContractId.RESOURCE_CLASS_DIMEN
    )

    /**
     * 按历史短名取 R 类：契约命中优先，否则按名字加载。
     *
     * [classLoader] 只在兜底路径用得上 —— 契约解析出来的 Class 来自宿主 ClassLoader，无需再传。
     */
    fun rClass(legacyName: String, classLoader: ClassLoader? = null): Class<*>? {
        CONTRACTS_BY_LEGACY_NAME[legacyName]
            ?.let { WeTypeHostContracts.classOf(it) }
            ?.let { return it }
        return loadClassOrNull(legacyName, classLoader)
    }

    /**
     * 读 R 类的静态 int 字段名（`logo_iv` 这类）。
     *
     * 字段名是源码标识符、能活过 R8，所以这里完全名字无关 —— 唯一会变的是类本身。
     */
    fun intField(rClass: Class<*>?, fieldName: String): Int? {
        val clazz = rClass ?: return null
        val field = clazz.declaredFields.firstOrNull {
            Modifier.isStatic(it.modifiers) && it.name == fieldName &&
                (it.type == Int::class.javaPrimitiveType || it.type == Integer::class.java)
        } ?: return null
        return runCatching { field.isAccessible = true; field.getInt(null) }.getOrNull()
    }
}
