package com.xposed.wetypehook

import java.io.File

object ModuleRuntime {
    @Volatile
    private var configuredModuleApkPath: String? = null

    fun updateModuleApkPath(path: String?) {
        if (path.isNullOrBlank()) return
        configuredModuleApkPath = path
    }

    fun resolveModuleApkPath(anchorClass: Class<*> = MainHook::class.java): String? {
        configuredModuleApkPath?.takeIf(::isUsableApkPath)?.let { return it }

        val resolvedFromCodeSource = runCatching {
            anchorClass.protectionDomain?.codeSource?.location?.toURI()?.let { File(it).absolutePath }
        }.getOrNull()
        if (isUsableApkPath(resolvedFromCodeSource)) {
            configuredModuleApkPath = resolvedFromCodeSource
            return resolvedFromCodeSource
        }

        val resolvedFromResource = runCatching {
            anchorClass.getResource("${anchorClass.simpleName}.class")
                ?.toString()
                ?.substringAfter("file:")
                ?.substringBefore("!/")
                ?.takeIf(::isUsableApkPath)
        }.getOrNull()
        if (isUsableApkPath(resolvedFromResource)) {
            configuredModuleApkPath = resolvedFromResource
            return resolvedFromResource
        }

        return null
    }

    private fun isUsableApkPath(path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        return runCatching { File(path).exists() && File(path).isFile }.getOrDefault(false)
    }
}
