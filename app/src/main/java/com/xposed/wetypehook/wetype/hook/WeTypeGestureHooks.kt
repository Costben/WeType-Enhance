package com.xposed.wetypehook.wetype.hook

import android.view.MotionEvent
import android.view.View
import com.xposed.wetypehook.wetype.gesture.KeyGestureResolver
import com.xposed.wetypehook.xposed.Log
import com.xposed.wetypehook.xposed.hookBefore
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Locale

/**
 * 微信输入法按键下滑手势 Hook 组
 * 拦截 QWERTY 与 T9 的原生触摸分发，对接 KeyGestureResolver 状态机
 */
internal object WeTypeGestureHooks {

    private const val TAG = "WeTypeGesture"
    private const val KEYBOARD_PACKAGE = "com.tencent.wetype.plugin.hld.keyboard"
    private const val SELF_DRAW_PACKAGE = "com.tencent.wetype.plugin.hld.keyboard.selfdraw."
    private const val MOTION_EVENT_CLASS = "android.view.MotionEvent"
    private var resolver: KeyGestureResolver? = null
    @Volatile
    private var isDispatchingCancel = false

    fun install(sourceDir: String?, classLoader: ClassLoader) {
        if (sourceDir.isNullOrEmpty()) {
            Log.e("Failed: Cannot install gesture hooks without sourceDir")
            return
        }

        runCatching {
            System.loadLibrary("dexkit")
            DexKitBridge.create(sourceDir).use { bridge ->
                val keyDataMethod = resolveKeyDataMethod(bridge, classLoader)
                val keyIdMethod = resolveKeyIdMethod(bridge, classLoader)
                val newResolver = KeyGestureResolver(keyDataMethod, keyIdMethod)
                resolver = newResolver

                installTouchHooks(bridge, classLoader, newResolver)
            }
            Log.i("Success: WeType gesture hooks installation completed")
        }.onFailure {
            Log.e("Failed: Installing WeType gesture hooks: ${it.message}")
            Log.i(it)
        }
    }

    private fun resolveKeyDataMethod(bridge: DexKitBridge, classLoader: ClassLoader): Method? {
        return runCatching {
            bridge.findMethod {
                searchPackages(KEYBOARD_PACKAGE)
                matcher {
                    name = "getMainText"
                    returnType = "java.lang.String"
                }
            }.firstOrNull()?.getMethodInstance(classLoader)
        }.getOrNull()
    }

    private fun resolveKeyIdMethod(bridge: DexKitBridge, classLoader: ClassLoader): Method? {
        return runCatching {
            bridge.findMethod {
                searchPackages(KEYBOARD_PACKAGE)
                matcher {
                    name = "getId"
                }
            }.firstOrNull { it.paramTypes.isEmpty() && it.returnType?.name != "void" }
                ?.getMethodInstance(classLoader)
        }.getOrNull()
    }

    /**
     * 安装触摸 hook。
     *
     * 宿主把触摸分发拆成两条**互不调用**的路径，靠基类 `onTouch` 里的
     * `invoke-virtual <基类>.<派发方法>` 做多态派发（下两行的类名与方法名取自 3.5.3，
     * 每个版本都会重排，只当机制说明看）：
     *
     * - QWERTY：`b.l2` → `b.Y2(j, ev, p)`（`b` 是 26 键基类，含 "onTouch move2 …" 串）
     * - 九宫格：`c.l2` → `c.b3(j, ev, p)`（`c` 是 T9 基类，方法体里**一个字符串常量都没有**，
     *   只有一句 `"toLowerCase(...)"` 的 Kotlin 空检查）
     *
     * 所以按字符串指纹只能捞到 `b.Y2`，九宫格整条路径永远收不到 hook——历史上模块日志里
     * 每一条 `Gesture triggered:` 都是 q/w/z 这类 26 键绑定，T9 一条都没有。
     *
     * 两个指纹串其实**都在 `b.Y2` 里面**（"move2" 与 "isUpperSlidedCancelState true"），
     * 这正是当年"两个指纹命中同一方法"那条日志的真相，不是宿主合并了实现。
     *
     * 兜底判据改成结构化定位，与宿主命名无关：在 `…keyboard.selfdraw` 包里找
     * 「3 参、返回 boolean、首尾参数都在 selfdraw 包、中间是 MotionEvent」的分发签名
     * （见 [findTouchDispatchers]）。宿主每个版本恰好只有三处：基类里的空格键专用处理，
     * 以及 26 键 / 九宫格两个子类各自的实现。除指纹命中的那个（及它所在基类的空格处理）
     * 之外剩下的就是九宫格入口。
     */
    private fun installTouchHooks(bridge: DexKitBridge, classLoader: ClassLoader, resolver: KeyGestureResolver) {
        val qwertyMethod = findTouchMethod(
            bridge, classLoader,
            usingString = "onTouch move2 lastKeyOperation is null",
            filterReturnBoolean = true
        )
        if (qwertyMethod == null) Log.e("[$TAG] Failed to locate QWERTY touch method")

        val targets = linkedSetOf<Method>()
        qwertyMethod?.let { targets += it }
        if (qwertyMethod == null) {
            Log.e("[$TAG] QWERTY touch method is required before T9 can be resolved")
            return
        }

        // `n` 基类里的空格键专用处理：签名与 `b.Y2` 相同，靠它调用了 getActionButton 区分。
        var skippedBaseSpaceHandler = false
        for (method in findTouchDispatchers(bridge, classLoader)) {
            if (method == qwertyMethod) continue
            if (method.declaringClass == qwertyMethod.declaringClass.superclass) {
                skippedBaseSpaceHandler = true
                continue
            }
            targets += method
        }
        if (!skippedBaseSpaceHandler) {
            Log.i("[$TAG] No base-class space handler found; skipping that filter")
        }
        if (targets.size == 1) {
            Log.e("[$TAG] Failed to locate the T9 touch method; T9 gestures will not fire")
        }

        targets.forEach { method ->
            runCatching {
                Log.i(
                    "[$TAG] Target ${method.declaringClass.name}#${method.name} " +
                        "static=${Modifier.isStatic(method.modifiers)} " +
                        "params=${method.parameterTypes.joinToString { it.name }}"
                )
                method.hookBefore { param ->
                    if (isDispatchingCancel) return@hookBefore
                    val view = param.thisObject as? View ?: return@hookBefore
                    val motionEvent = param.args.firstOrNull { it is MotionEvent } as? MotionEvent ?: return@hookBefore
                    val keyContext = param.args.firstOrNull { it !== motionEvent }

                    val consumed = resolver.onInterceptTouch(
                        view = view,
                        keyContext = keyContext,
                        event = motionEvent,
                        isT9 = isT9View(view)
                    ) {
                        runCatching {
                            isDispatchingCancel = true
                            try {
                                val cancelEvent = MotionEvent.obtain(motionEvent).apply {
                                    setAction(MotionEvent.ACTION_CANCEL)
                                }
                                val cancelArgs = param.args.toMutableList()
                                val evIndex = cancelArgs.indexOfFirst { it is MotionEvent }
                                if (evIndex >= 0) cancelArgs[evIndex] = cancelEvent
                                method.invoke(param.thisObject, *cancelArgs.toTypedArray())
                                cancelEvent.recycle()
                            } finally {
                                isDispatchingCancel = false
                            }
                        }
                    }
                    if (consumed) {
                        param.result = true
                    }
                }
                Log.i("[$TAG] Hooked touch: ${method.declaringClass.name}#${method.name}")
            }.onFailure {
                Log.e("[$TAG] Failed to hook touch ${method.declaringClass.name}#${method.name}: ${it.message}")
            }
        }
        // 保留历史日志关键字，便于旧诊断脚本比对
        if (qwertyMethod != null) Log.i("[$TAG] Hooked QWERTY touch: ${qwertyMethod.declaringClass.name}#${qwertyMethod.name}")
        targets.firstOrNull { it != qwertyMethod }?.let {
            Log.i("[$TAG] Hooked T9 touch: ${it.declaringClass.name}#${it.name}")
        }
    }

    private fun findTouchMethod(
        bridge: DexKitBridge,
        classLoader: ClassLoader,
        usingString: String,
        filterReturnBoolean: Boolean
    ): Method? {
        return runCatching {
            bridge.findMethod {
                searchPackages(KEYBOARD_PACKAGE)
                matcher {
                    usingStrings(usingString)
                    if (filterReturnBoolean) returnType = "boolean"
                }
            }.firstOrNull { data ->
                data.paramTypes.any { it.name == "android.view.MotionEvent" }
            }?.getMethodInstance(classLoader)
        }.getOrNull()
    }

    /**
     * 靠**签名形状**而不是类名/方法名找触摸分发入口。宿主每次发版都会重命名混淆类，
     * 形状本身跨版本稳定：3 参、返回 boolean、中间是 MotionEvent、首尾两个参数都在
     * `…keyboard.selfdraw` 包里。
     *
     * 首尾两个参数类**不能写死**。早期版本把发出事件的上下文写成 `selfdraw.j`、把事件附加
     * 数据写成 `selfdraw.p`，4.0.0 把附加数据换成了 `selfdraw.q`；写死 `p` 会让这一版整包
     * 一个都匹配不到，T9 入口随之静默丢失。包级判定与命名无关，跨版本成立。
     */
    private fun findTouchDispatchers(bridge: DexKitBridge, classLoader: ClassLoader): List<Method> {
        return runCatching {
            bridge.findMethod {
                searchPackages(KEYBOARD_PACKAGE)
                matcher {
                    returnType = "boolean"
                    paramCount = 3
                }
            }.mapNotNull { data ->
                if (data.paramTypes.size != 3) return@mapNotNull null
                if (data.paramTypes[1].name != MOTION_EVENT_CLASS) return@mapNotNull null
                if (!data.paramTypes[0].name.startsWith(SELF_DRAW_PACKAGE)) return@mapNotNull null
                if (!data.paramTypes[2].name.startsWith(SELF_DRAW_PACKAGE)) return@mapNotNull null
                runCatching { data.getMethodInstance(classLoader) }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }

    private fun isT9View(view: View): Boolean {
        if (isT9Class(view.javaClass)) return true
        var parent = view.parent
        repeat(8) {
            val parentView = parent as? View ?: return false
            if (isT9Class(parentView.javaClass)) return true
            parent = parentView.parent
        }
        return false
    }

    private fun isT9Class(clazz: Class<*>): Boolean {
        val name = clazz.name.lowercase(Locale.ROOT)
        return name.contains("t9") || name.contains("nine")
    }
}
