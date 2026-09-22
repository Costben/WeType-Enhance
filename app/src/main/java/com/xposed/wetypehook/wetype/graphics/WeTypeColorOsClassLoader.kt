package com.xposed.wetypehook.wetype.graphics

import android.content.Context
import com.xposed.wetypehook.PropertyUtils
import com.xposed.wetypehook.xposed.Log
import dalvik.system.PathClassLoader
import java.io.File

/**
 * ColorOS SystemUI ClassLoader 缓存。
 *
 * `COUIShadowEdgeDrawable` 随 `com.android.systemui` 分发。在微信输入法进程中，
 * 通过 [PathClassLoader] 解析 SystemUI 的 APK 路径加载 COUI 硬件流光组件。
 * 进程内共享同一个 ClassLoader 实例，避免重复反射与内存浪费。
 */
internal object WeTypeColorOsClassLoader {
    @Volatile
    private var cachedLoader: ClassLoader? = null

    fun isPlatform(): Boolean = !PropertyUtils["ro.build.version.oplusrom", ""].isNullOrEmpty()

    fun get(context: Context): ClassLoader? {
        if (!isPlatform()) return null
        cachedLoader?.let { return it }
        return synchronized(this) {
            cachedLoader?.let { return it }
            runCatching {
                val info = context.packageManager.getApplicationInfo("com.android.systemui", 0)
                var paths = info.sourceDir
                info.splitSourceDirs?.forEach { paths += File.pathSeparator + it }
                PathClassLoader(paths, context.classLoader).also { cachedLoader = it }
            }.onFailure {
                Log.e("Failed: Get ColorOS SystemUI ClassLoader")
                Log.e(it)
            }.getOrNull()
        }
    }
}
