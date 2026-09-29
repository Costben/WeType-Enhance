package com.xposed.wetypehook.oplus

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import com.xposed.wetypehook.xposed.HookEnvironment
import com.xposed.wetypehook.xposed.Log
import com.xposed.wetypehook.xposed.findMethod
import com.xposed.wetypehook.xposed.hookAfter
import com.xposed.wetypehook.xposed.hookBefore
import com.xposed.wetypehook.xposed.loadClassOrNull
import java.lang.ref.WeakReference

/**
 * ColorOS 系统 AI 写作 (com.oplus.aiwriter) 进程侧 Hook。
 *
 * 核心职责：
 * 1. 动态注册导出广播 [ACTION_OPEN_AIWRITER]，接收来自微信输入法的无权限跨进程调用，
 *    在自身受信任的 UID 下启动 [AIKitEntryService] (携带 action OPLUS_INPUT + kitName KitMainPage)。
 * 2. 兜底处理 [TextSelectorEntrance] 调起场景。
 * 3. 拦截顶层宿主组件名识别 (P.m / AIKitEntryService.y)，在触发后短窗口内确保真实的前台应用
 *    被正确识别并挂载 AI 写作面板 (KitPanelActivity)。
 */
object OplusAiWriterHooks {

    private const val TAG = "OplusAiWriterHooks"

    const val ACTION_OPEN_AIWRITER = "com.xposed.wetypehook.ACTION_OPEN_AIWRITER"
    const val EXTRA_TARGET_PACKAGE = "extra_wetype_target_package"
    const val EXTRA_TEXT = "extra_wetype_text"
    const val EXTRA_FROM_WETYPE = "extra_from_wetype"

    private const val SERVICE_ACTION = "com.oplus.aiwriter.entrance.action.OPLUS_INPUT"
    private const val SERVICE_CLASS = "com.oplus.aiwriter.entrance.AIKitEntryService"
    private const val KIT_NAME = "KitMainPage"

    @Volatile
    private var lastTargetPackage: String? = null

    @Volatile
    private var lastTargetTimestamp: Long = 0L

    private var appContextRef: WeakReference<Context>? = null

    @Volatile
    private var receiverRegistered = false

    fun install(classLoader: ClassLoader) {
        Log.i("[$TAG] Installing OplusAiWriterHooks in com.oplus.aiwriter...")

        // 1. 在 Application.onCreate 中注册广播接收器并缓存 Context
        hookApplication(classLoader)

        // 2. 在 AIKitEntryService.onCreate 中兜底注册广播接收器
        hookEntryService(classLoader)

        // 3. Hook TextSelectorEntrance (冷启动兜底)
        hookTextSelectorEntrance(classLoader)

        // 4. Hook 顶层组件判定逻辑 (P.m 与 AIKitEntryService.y)
        hookTopComponentResolver(classLoader)

        Log.i("[$TAG] OplusAiWriterHooks installed successfully")
    }

    private fun hookApplication(classLoader: ClassLoader) {
        val appClass = Application::class.java
        appClass.declaredMethods.firstOrNull {
            it.name == "onCreate" && it.parameterCount == 0
        }?.apply { isAccessible = true }?.hookAfter { param ->
            val app = param.thisObject as? Application ?: return@hookAfter
            appContextRef = WeakReference(app)
            ensureReceiverRegistered(app)
        }
    }

    private fun hookEntryService(classLoader: ClassLoader) {
        val serviceClass = loadClassOrNull(SERVICE_CLASS, classLoader) ?: return
        serviceClass.declaredMethods.firstOrNull {
            it.name == "onCreate" && it.parameterCount == 0
        }?.apply { isAccessible = true }?.hookAfter { param ->
            val service = param.thisObject as? Context ?: return@hookAfter
            appContextRef = WeakReference(service.applicationContext ?: service)
            ensureReceiverRegistered(service)
        }
    }

    @Synchronized
    private fun ensureReceiverRegistered(context: Context) {
        if (receiverRegistered) return
        val appContext = context.applicationContext ?: context
        val filter = IntentFilter(ACTION_OPEN_AIWRITER)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val targetPkg = intent.getStringExtra(EXTRA_TARGET_PACKAGE)
                val text = intent.getStringExtra(EXTRA_TEXT)
                Log.i("[$TAG] Received ACTION_OPEN_AIWRITER: targetPkg=$targetPkg, textLength=${text?.length ?: 0}")

                if (!targetPkg.isNullOrEmpty()) {
                    lastTargetPackage = targetPkg
                    lastTargetTimestamp = SystemClock.uptimeMillis()
                }

                setResultCode(Activity.RESULT_OK)
                launchAiWriterService(ctx, text)
            }
        }

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                appContext.registerReceiver(receiver, filter)
            }
            receiverRegistered = true
            Log.i("[$TAG] BroadcastReceiver registered for $ACTION_OPEN_AIWRITER")
        }.onFailure {
            Log.e("[$TAG] Failed to register BroadcastReceiver: ${it.message}")
        }
    }

    private fun hookTextSelectorEntrance(classLoader: ClassLoader) {
        val entranceClass = loadClassOrNull("com.oplus.aiwriter.entrance.TextSelectorEntrance", classLoader) ?: return
        entranceClass.declaredMethods.firstOrNull {
            it.name == "onCreate" && it.parameterCount == 1 && it.parameterTypes[0] == Bundle::class.java
        }?.apply { isAccessible = true }?.hookBefore { param ->
            val activity = param.thisObject as? Activity ?: return@hookBefore
            val intent = activity.intent ?: return@hookBefore
            val fromWeType = intent.getBooleanExtra(EXTRA_FROM_WETYPE, false)
            if (!fromWeType) return@hookBefore

            val targetPkg = intent.getStringExtra(EXTRA_TARGET_PACKAGE)
            val text = intent.getStringExtra(EXTRA_TEXT)
                ?: intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()

            Log.i("[$TAG] TextSelectorEntrance invoked from WeType: targetPkg=$targetPkg")
            if (!targetPkg.isNullOrEmpty()) {
                lastTargetPackage = targetPkg
                lastTargetTimestamp = SystemClock.uptimeMillis()
            }

            // 清理 action 避免触发默认的文本选择流程
            intent.action = null

            // 在自身进程启动真正的 AIKitEntryService
            launchAiWriterService(activity, text)

            // 立即关闭无界面的中间 Activity
            activity.finish()
            runCatching {
                activity.overridePendingTransition(0, 0)
            }
            param.result = null
        }
    }

    private fun hookTopComponentResolver(classLoader: ClassLoader) {
        // 1. Hook com.oplus.aiwriter.utils.P 中所有 static () -> ComponentName 方法
        val pClass = loadClassOrNull("com.oplus.aiwriter.utils.P", classLoader)
        val resolveByPkgMethod = pClass?.declaredMethods?.firstOrNull {
            it.parameterCount == 2 &&
                it.parameterTypes[0] == Context::class.java &&
                it.parameterTypes[1] == String::class.java &&
                it.returnType == ComponentName::class.java
        }?.apply { isAccessible = true }

        pClass?.declaredMethods?.filter {
            it.parameterCount == 0 && it.returnType == ComponentName::class.java
        }?.forEach { method ->
            method.isAccessible = true
            method.hookAfter { param ->
                if (!isWithinRecentTriggerWindow()) return@hookAfter
                val targetPkg = lastTargetPackage ?: return@hookAfter
                val current = param.result as? ComponentName
                if (current == null || current.packageName == "com.oplus.aiwriter" || current.packageName == "com.tencent.wetype") {
                    val context = appContextRef?.get()
                    val resolved = if (context != null && resolveByPkgMethod != null) {
                        runCatching {
                            resolveByPkgMethod.invoke(null, context, targetPkg) as? ComponentName
                        }.getOrNull()
                    } else null

                    val fallback = resolved ?: ComponentName(targetPkg, "")
                    Log.i("[$TAG] Substituted ${method.name}() from '$current' to '$fallback' (target=$targetPkg)")
                    param.result = fallback
                }
            }
        }

        // 2. Hook AIKitEntryService 中解析顶层包名的私有无参 () -> String 方法
        val serviceClass = loadClassOrNull(SERVICE_CLASS, classLoader)
        serviceClass?.declaredMethods?.filter {
            it.parameterCount == 0 && it.returnType == String::class.java &&
                (it.name == "y" || java.lang.reflect.Modifier.isPrivate(it.modifiers))
        }?.forEach { method ->
            method.isAccessible = true
            method.hookAfter { param ->
                if (!isWithinRecentTriggerWindow()) return@hookAfter
                val targetPkg = lastTargetPackage ?: return@hookAfter
                val current = param.result as? String
                if (current.isNullOrEmpty() || current == "com.oplus.aiwriter" || current == "com.tencent.wetype") {
                    Log.i("[$TAG] Substituted AIKitEntryService.${method.name}() from '$current' to '$targetPkg'")
                    param.result = targetPkg
                }
            }
        }

        // 3. Hook KitRunningTimeManager 中形如 (Context, String, String, Bundle) 的启动入口
        val runtimeMgrClass = loadClassOrNull("com.oplus.aiwriter.runtime.KitRunningTimeManager", classLoader)
        runtimeMgrClass?.declaredMethods?.filter {
            it.parameterCount == 4 &&
                it.parameterTypes[0] == Context::class.java &&
                it.parameterTypes[1] == String::class.java &&
                it.parameterTypes[2] == String::class.java &&
                it.parameterTypes[3] == Bundle::class.java
        }?.forEach { method ->
            method.isAccessible = true
            method.hookBefore { param ->
                if (!isWithinRecentTriggerWindow()) return@hookBefore
                val targetPkg = lastTargetPackage ?: return@hookBefore
                val callPkg = param.args.getOrNull(1) as? String
                if (callPkg.isNullOrEmpty() || callPkg == "com.oplus.aiwriter" || callPkg == "com.tencent.wetype") {
                    param.args[1] = targetPkg
                    Log.i("[$TAG] Substituted KitRunningTimeManager.${method.name} callPackage from '$callPkg' to '$targetPkg'")
                }
            }
        }
    }

    private fun isWithinRecentTriggerWindow(): Boolean {
        val elapsed = SystemClock.uptimeMillis() - lastTargetTimestamp
        return elapsed in 0..8000L && !lastTargetPackage.isNullOrEmpty()
    }

    private fun launchAiWriterService(context: Context, text: String?) {
        val serviceIntent = Intent(SERVICE_ACTION).apply {
            setClassName("com.oplus.aiwriter", SERVICE_CLASS)
            putExtra("kitName", KIT_NAME)
            putExtra("isDirectToKit", false)
            putExtra("isEditable", true)
            putExtra("from_source", "input_method_entry")
            if (!text.isNullOrEmpty()) {
                putExtra("text", text)
            }
        }

        runCatching {
            context.startService(serviceIntent)
            Log.i("[$TAG] Successfully called startService for AIKitEntryService")
        }.onFailure { e ->
            Log.e("[$TAG] startService failed, trying startForegroundService: ${e.message}")
            runCatching {
                context.startForegroundService(serviceIntent)
                Log.i("[$TAG] startForegroundService called")
            }.onFailure { e2 ->
                Log.e("[$TAG] startForegroundService failed: ${e2.message}")
            }
        }
    }
}
