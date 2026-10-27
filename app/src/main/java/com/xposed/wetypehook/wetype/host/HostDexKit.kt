package com.xposed.wetypehook.wetype.host

import com.xposed.wetypehook.xposed.Log
import org.luckypray.dexkit.DexKitBridge

/**
 * 进程内共享的 DexKit bridge。
 *
 * 微信输入法 4.0.0 的 APK 约 216MB，每次 [DexKitBridge.create] 都要把整包 dex 解析进
 * native 缓存。各 hook 组原本各自 create/close，输入法进程启动时会重复解析七八次；
 * 实测 Android 16 开机内存紧张时 `:hld` 会在 libdexkit 里 abort，连续崩溃重启 5 次。
 * 这里按「sourceDir + ClassLoader」复用同一份 bridge，把启动期解析次数降到 1 次。
 *
 * **必须按 ClassLoader 建桥，不能只给 sourceDir。** 微信输入法走 Tinker/RFix 热修：
 * 补丁落地后进程里加载的是 `dataDir/tinker/patch-<id>/dex/tinker_classN.apk` 那一套 dex，
 * 宿主重新混淆过，类名/方法名与 base.apk 里的不是同一套（实测 4.0.0 / 58201 热修后
 * `selfdraw.b#d3` 变成 `selfdraw.b#f3`、`selfdraw.c#g3` 变成 `selfdraw.c#i3`）。
 * 只索引 base.apk 时 DexKit 能查到指纹所在的方法，但 `getMethodInstance` 在运行时
 * ClassLoader 上找不到同名方法 —— 表现为「dex 里明明有这条串，hook 却一个都没装上」，
 * 手势就是这样在热修后静默失效的。ClassLoader 建桥会把 base + patch 全部 dex 一起索引，
 * 旧名字的条目在解析阶段自然被丢掉，新名字的条目能命中。
 *
 * 没有热修时 ClassLoader 的 dex 集合与 base.apk 完全一致，内存开销不变。
 */
internal object HostDexKit {
    private data class CacheKey(val sourceDir: String, val classLoader: ClassLoader?)

    private var cacheKey: CacheKey? = null
    private var bridge: DexKitBridge? = null
    private var failedKey: CacheKey? = null

    @Synchronized
    fun get(sourceDir: String?, classLoader: ClassLoader? = null): DexKitBridge? {
        if (sourceDir.isNullOrEmpty()) return null
        val key = CacheKey(sourceDir, classLoader)
        bridge?.let { existing ->
            if (cacheKey == key) return existing
            closeLocked()
        }
        if (failedKey == key) return null
        val created = create(key)
        if (created == null) {
            failedKey = key
            return null
        }
        cacheKey = key
        bridge = created
        return created
    }

    private fun create(key: CacheKey): DexKitBridge? {
        val nativeLoaded = runCatching { System.loadLibrary("dexkit") }
            .onFailure {
                Log.e("Failed: load dexkit native library: ${it.message}")
                Log.i(it)
            }
            .isSuccess
        if (!nativeLoaded) return null

        key.classLoader?.let { loader ->
            val viaClassLoader = runCatching { DexKitBridge.create(loader, false) }
                .onFailure {
                    Log.e("HostDexKit: classloader bridge failed, falling back to sourceDir: ${it.message}")
                }
                .getOrNull()
            if (viaClassLoader != null) {
                Log.i("HostDexKit: bridge created via classloader for ${key.sourceDir} (dex=${viaClassLoader.getDexNum()})")
                return viaClassLoader
            }
        }

        return runCatching { DexKitBridge.create(key.sourceDir) }
            .onFailure {
                Log.e("Failed: create DexKit bridge: ${it.message}")
                Log.i(it)
            }
            .getOrNull()
            ?.also { Log.i("HostDexKit: bridge created for ${key.sourceDir} (dex=${it.getDexNum()})") }
    }

    @Synchronized
    fun close() {
        closeLocked()
        failedKey = null
    }

    private fun closeLocked() {
        runCatching { bridge?.close() }
        bridge = null
        cacheKey = null
    }
}
