package com.xposed.wetypehook.wetype.hook

import com.xposed.wetypehook.wetype.settings.WeTypeSettings
import com.xposed.wetypehook.xposed.Log
import com.xposed.wetypehook.xposed.ProceedWithOriginal
import com.xposed.wetypehook.xposed.hookAfter
import com.xposed.wetypehook.xposed.hookBefore
import com.xposed.wetypehook.xposed.hookReplace
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 微信输入法剪贴板增强 Hook 组
 * 1. 跨设备同步条目可见化持久存储 (远程条目改写为本地可见)
 * 2. 系统粘贴路径改写
 * 3. 解除剪贴板保存条数上限 (100,000 条)
 * 4. 解除剪贴板留存时长上限 (8.64e12 纳秒 / 远超 3 个月)
 * 5. 解除单条剪贴板文本长度上限 (100,000,000 字符)
 * 6. 抑制剪贴板超限提示弹窗
 */
internal object WeTypeClipboardHooks {

    private const val TAG = "WeTypeClipboard"
    private const val UNLIMITED_RETENTION_COUNT = 100000L
    private const val UNLIMITED_TEXT_SIZE = 100000000
    private const val UNLIMITED_RETENTION_DURATION_NS = 8640000000000L

    private var durationField: Field? = null
    private var durationInstance: Any? = null
    private var defaultDuration: Long? = null

    fun install(sourceDir: String?, classLoader: ClassLoader) {
        if (sourceDir.isNullOrEmpty()) {
            Log.e("Failed: Cannot install clipboard hooks without sourceDir")
            return
        }

        runCatching {
            System.loadLibrary("dexkit")
            DexKitBridge.create(sourceDir).use { bridge ->
                hookCrossDevicePersist(bridge, classLoader)
                hookSystemPaste(bridge, classLoader)
                hookRetentionCount(bridge, classLoader)
                hookRetentionDuration(bridge, classLoader)
                hookTextLimit(bridge, classLoader)
                hookExceedTip(bridge, classLoader)
            }
            Log.i("Success: WeType clipboard hooks installation completed")
        }.onFailure {
            Log.e("Failed: Installing WeType clipboard hooks: ${it.message}")
            Log.i(it)
        }
        // ADR-0002：剪贴板图片条目注入 + 缩略图 + 大图预览链路（独立 try/catch，不影响配额 Hook）。
        runCatching {
            if (WeTypeClipboardImageHost.install(classLoader)) {
                WeTypeClipboardImageLoader.install(classLoader)
                WeTypeClipboardImageEntries.install(classLoader)
                WeTypeClipboardImageList.install(classLoader)
            }
        }.onFailure {
            Log.e("Failed: Installing clipboard image entries: ${it.message}")
            Log.i(it)
        }
        // Slice 4:剪贴板搜索框 UI 挂载（只挂载不做过滤；独立 try/catch，不影响配额 Hook）。
        runCatching {
            WeTypeClipboardSearchUi.install(classLoader)
        }.onFailure {
            Log.e("Failed: Installing clipboard search UI: ${it.message}")
            Log.i(it)
        }
        // Slice 5:剪贴板搜索过滤渲染（防抖+后台搜+高亮；独立 try/catch，不影响配额 Hook）。
        runCatching {
            WeTypeClipboardSearchFilter.install(classLoader)
        }.onFailure {
            Log.e("Failed: Installing clipboard search filter: ${it.message}")
            Log.i(it)
        }
    }

    /**
     * 1. 跨设备剪贴板可见化持久存储
     * 包 com.tencent.wetype.plugin.hld.clipboard 下返回 Object，参数形如 (long, int, Continuation) 的方法
     */
    private fun hookCrossDevicePersist(bridge: DexKitBridge, classLoader: ClassLoader) {
        runCatching {
            // 注意：DexKit的declaredClass只做精确类名匹配，不支持通配符；按包检索用searchPackages。
            val methods = bridge.findMethod {
                searchPackages("com.tencent.wetype.plugin.hld.clipboard")
                matcher {
                    returnType = "java.lang.Object"
                    paramCount = 4
                }
            }.filter { data ->
                val params = data.paramTypes
                params.size == 4 && params[1].name == "long" && params[2].name == "int"
            }
            val target = methods.firstOrNull()?.getMethodInstance(classLoader)
                ?: bridge.findMethod {
                    searchPackages("com.tencent.wetype.plugin.hld.clipboard")
                    matcher {
                        returnType = "java.lang.Object"
                    }
                }.firstOrNull { it.paramTypes.any { param -> param.name.contains("Continuation") } }
                    ?.getMethodInstance(classLoader)

            if (target == null) {
                Log.e("[$TAG] Failed to locate crossDevicePersist method")
                return
            }

            target.hookBefore { param ->
                if (!WeTypeSettings.isShowCrossDeviceClipboardXposed()) return@hookBefore
                val args = param.args
                // 当 source == 1 (远程条目) 时，强制重写为 0 (本地可见条目)
                if (args.size > 2 && args[2] == 1) {
                    args[2] = 0
                    Log.i("[$TAG] Stored remote clipboard item as visible (source=0)")
                }
            }
            // 远程条目（含图片）落库后立刻刷新图片采集缓存，不等剪贴板面板 setList。
            target.hookAfter { WeTypeClipboardImageList.primeAsync() }
            Log.i("[$TAG] Hooked crossDevicePersist: ${target.declaringClass.name}#${target.name}")
        }.onFailure {
            Log.e("[$TAG] Failed to hook crossDevicePersist: ${it.message}")
        }
    }

    /**
     * 2. 系统粘贴路径同步改写
     * 包含字符串 "commitTextPreferSystemPaste itemId=" 且末位参数为 boolean
     */
    private fun hookSystemPaste(bridge: DexKitBridge, classLoader: ClassLoader) {
        runCatching {
            val method = bridge.findMethod {
                searchPackages("com.tencent.wetype.plugin.hld.clipboard")
                matcher {
                    usingStrings("commitTextPreferSystemPaste itemId=")
                }
            }.firstOrNull()?.getMethodInstance(classLoader)

            if (method == null) {
                Log.e("[$TAG] Failed to locate systemPaste method")
                return
            }

            val paramTypes = method.parameterTypes
            if (paramTypes.isNotEmpty() && paramTypes.last() == java.lang.Boolean.TYPE) {
                method.hookBefore { param ->
                    if (!WeTypeSettings.isShowCrossDeviceClipboardXposed()) return@hookBefore
                    param.args[param.args.size - 1] = false
                }
                Log.i("[$TAG] Hooked systemPaste: ${method.declaringClass.name}#${method.name}")
            }
        }.onFailure {
            Log.e("[$TAG] Failed to hook systemPaste: ${it.message}")
        }
    }

    /**
     * 3. 剪贴板条数上限解除 (返回 100000)
     */
    private fun hookRetentionCount(bridge: DexKitBridge, classLoader: ClassLoader) {
        runCatching {
            val method = bridge.findMethod {
                searchPackages("com.tencent.wetype.plugin.hld.clipboard")
                matcher {
                    returnType = "long"
                }
            }.firstOrNull()?.getMethodInstance(classLoader)
                ?: bridge.findMethod {
                    searchPackages("com.tencent.wetype.plugin.hld.clipboard")
                    matcher {
                        returnType = "int"
                    }
                }.firstOrNull()?.getMethodInstance(classLoader)

            if (method == null) {
                Log.e("[$TAG] Failed to locate retention limit method")
                return
            }

            method.hookReplace {
                if (!WeTypeSettings.isRemoveClipboardRetentionLimitXposed()) {
                    return@hookReplace ProceedWithOriginal
                }
                if (method.returnType == java.lang.Long.TYPE) {
                    UNLIMITED_RETENTION_COUNT
                } else {
                    UNLIMITED_RETENTION_COUNT.toInt()
                }
            }
            Log.i("[$TAG] Hooked retentionCount: ${method.declaringClass.name}#${method.name}")
        }.onFailure {
            Log.e("[$TAG] Failed to hook retentionCount: ${it.message}")
        }
    }

    /**
     * 4. 剪贴板留存时长上限解除
     * 包含 "THREE_MONTHS" 字符串的类 -> 静态单例 g -> long 字段
     */
    private fun hookRetentionDuration(bridge: DexKitBridge, classLoader: ClassLoader) {
        runCatching {
            val classData = bridge.findClass {
                matcher {
                    usingStrings("THREE_MONTHS")
                }
            }.firstOrNull() ?: return

            val targetClass = classLoader.loadClass(classData.name)
            val staticFields = targetClass.declaredFields.filter { Modifier.isStatic(it.modifiers) }
            val instanceField = staticFields.firstOrNull { it.name == "g" }
                ?: staticFields.firstOrNull { field ->
                    field.isAccessible = true
                    val obj: Any = runCatching { field.get(null) }.getOrNull() ?: return@firstOrNull false
                    obj.javaClass.declaredFields.any { it.type == java.lang.Long.TYPE && !Modifier.isStatic(it.modifiers) }
                }

            if (instanceField == null) {
                Log.e("[$TAG] Failed to locate retention instance field")
                return
            }

            instanceField.isAccessible = true
            val instance = instanceField.get(null) ?: return
            val durationF = instance.javaClass.declaredFields.firstOrNull {
                it.type == java.lang.Long.TYPE && !Modifier.isStatic(it.modifiers)
            } ?: return

            durationF.isAccessible = true
            durationField = durationF
            durationInstance = instance
            defaultDuration = durationF.getLong(instance)

            applyRetentionDurationOverride()
            Log.i("[$TAG] Hooked retentionDuration field: ${durationF.declaringClass.name}#${durationF.name}")
        }.onFailure {
            Log.e("[$TAG] Failed to hook retentionDuration: ${it.message}")
        }
    }

    fun applyRetentionDurationOverride() {
        val field = durationField ?: return
        val instance = durationInstance ?: return
        runCatching {
            if (WeTypeSettings.isRemoveClipboardRetentionLimitXposed()) {
                field.setLong(instance, UNLIMITED_RETENTION_DURATION_NS)
                Log.i("[$TAG] Applied unlimited retention duration")
            } else {
                defaultDuration?.let { field.setLong(instance, it) }
            }
        }
    }

    /**
     * 5. 单条剪贴板文本长度上限解除 (返回 100,000,000)
     * 包 com.tencent.wetype.plugin.hld.feature 下使用 "clipboard_text_max_size_new" 且返回 int
     */
    private fun hookTextLimit(bridge: DexKitBridge, classLoader: ClassLoader) {
        runCatching {
            val method = bridge.findMethod {
                searchPackages("com.tencent.wetype.plugin.hld.feature")
                matcher {
                    usingStrings("clipboard_text_max_size_new")
                    returnType = "int"
                }
            }.firstOrNull()?.getMethodInstance(classLoader)

            if (method == null) {
                Log.e("[$TAG] Failed to locate textLimit method")
                return
            }

            method.hookReplace {
                if (WeTypeSettings.isRemoveClipboardTextLimitXposed()) {
                    UNLIMITED_TEXT_SIZE
                } else {
                    ProceedWithOriginal
                }
            }
            Log.i("[$TAG] Hooked textLimit: ${method.declaringClass.name}#${method.name}")
        }.onFailure {
            Log.e("[$TAG] Failed to hook textLimit: ${it.message}")
        }
    }

    /**
     * 6. 抑制剪贴板超限提示弹窗
     * 包含字符串 "renderClipboardContent show clipboard exceed size tip!!" 的 void 方法
     */
    private fun hookExceedTip(bridge: DexKitBridge, classLoader: ClassLoader) {
        runCatching {
            val method = bridge.findMethod {
                searchPackages("com.tencent.wetype.plugin.hld.candidate")
                matcher {
                    usingStrings("renderClipboardContent show clipboard exceed size tip!!")
                    returnType = "void"
                }
            }.firstOrNull()?.getMethodInstance(classLoader)

            if (method == null) {
                Log.e("[$TAG] Failed to locate exceedTip method")
                return
            }

            method.hookReplace {
                if (WeTypeSettings.isRemoveClipboardTextLimitXposed()) {
                    // 直接跳过原方法，不显示超限提示
                    null
                } else {
                    ProceedWithOriginal
                }
            }
            Log.i("[$TAG] Hooked exceedTip: ${method.declaringClass.name}#${method.name}")
        }.onFailure {
            Log.e("[$TAG] Failed to hook exceedTip: ${it.message}")
        }
    }
}
