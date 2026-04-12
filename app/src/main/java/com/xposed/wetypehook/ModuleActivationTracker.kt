package com.xposed.wetypehook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.SystemClock

object ModuleActivationTracker {
    private const val MODULE_PACKAGE_NAME = "com.xposed.wetypehook"
    private const val PREF_NAME = "module_activation_status"
    private const val KEY_LAST_ACTIVATED_AT = "last_activated_at"
    private const val KEY_LAST_SOURCE_PACKAGE = "last_source_package"
    private const val KEY_LAST_SOURCE_PROCESS = "last_source_process"

    const val ACTION_RECORD_ACTIVATION = "$MODULE_PACKAGE_NAME.action.RECORD_ACTIVATION"
    const val EXTRA_SOURCE_PACKAGE = "source_package"
    const val EXTRA_SOURCE_PROCESS = "source_process"

    private val trustedSourcePackages = setOf(
        "android",
        "com.tencent.wetype",
        "com.iflytek.inputmethod.miui",
        "com.sohu.inputmethod.sogou.xiaomi",
        "com.baidu.input_mi",
        "com.miui.catcherpatch"
    )

    data class ActivationStatus(
        val isActive: Boolean,
        val sourcePackage: String?,
        val sourceProcess: String?,
        val lastActivatedAt: Long
    )

    fun resolveStatusForUi(context: Context): ActivationStatus {
        val appContext = context.applicationContext ?: context
        if (appContext.packageName == MODULE_PACKAGE_NAME) {
            return readStatus(appContext)
        }
        return ActivationStatus(
            isActive = true,
            sourcePackage = appContext.packageName,
            sourceProcess = resolveProcessName(appContext),
            lastActivatedAt = System.currentTimeMillis()
        )
    }

    fun syncActivationFromUiContext(context: Context) {
        val appContext = context.applicationContext ?: context
        if (appContext.packageName == MODULE_PACKAGE_NAME) return
        notifyActivationFromHook(
            context = appContext,
            sourcePackage = appContext.packageName,
            sourceProcess = resolveProcessName(appContext)
        )
    }

    fun notifyActivationFromHook(
        context: Context,
        sourcePackage: String,
        sourceProcess: String?
    ) {
        val appContext = context.applicationContext ?: context
        val intent = Intent(ACTION_RECORD_ACTIVATION)
            .setClassName(MODULE_PACKAGE_NAME, ModuleActivationReceiver::class.java.name)
            .putExtra(EXTRA_SOURCE_PACKAGE, sourcePackage)
            .putExtra(EXTRA_SOURCE_PROCESS, sourceProcess)
        runCatching { appContext.sendBroadcast(intent) }
    }

    fun readStatus(context: Context): ActivationStatus {
        val prefs = preferences(context)
        val lastActivatedAt = prefs.getLong(KEY_LAST_ACTIVATED_AT, 0L)
        val bootEpoch = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        return ActivationStatus(
            isActive = lastActivatedAt >= bootEpoch - 5_000L,
            sourcePackage = prefs.getString(KEY_LAST_SOURCE_PACKAGE, null),
            sourceProcess = prefs.getString(KEY_LAST_SOURCE_PROCESS, null),
            lastActivatedAt = lastActivatedAt
        )
    }

    fun registerStatusListener(
        context: Context,
        onStatusChanged: (ActivationStatus) -> Unit
    ): SharedPreferences.OnSharedPreferenceChangeListener {
        val prefs = preferences(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (
                key == KEY_LAST_ACTIVATED_AT ||
                key == KEY_LAST_SOURCE_PACKAGE ||
                key == KEY_LAST_SOURCE_PROCESS
            ) {
                onStatusChanged(readStatus(context))
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        return listener
    }

    fun unregisterStatusListener(
        context: Context,
        listener: SharedPreferences.OnSharedPreferenceChangeListener
    ) {
        preferences(context).unregisterOnSharedPreferenceChangeListener(listener)
    }

    internal fun recordActivation(
        context: Context,
        sourcePackage: String,
        sourceProcess: String?
    ) {
        if (sourcePackage !in trustedSourcePackages) return
        preferences(context)
            .edit()
            .putLong(KEY_LAST_ACTIVATED_AT, System.currentTimeMillis())
            .putString(KEY_LAST_SOURCE_PACKAGE, sourcePackage)
            .putString(KEY_LAST_SOURCE_PROCESS, sourceProcess)
            .apply()
    }

    private fun preferences(context: Context): SharedPreferences {
        val appContext = context.applicationContext ?: context
        return appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    private fun resolveProcessName(context: Context): String? = runCatching {
        context.applicationInfo.processName ?: context.packageName
    }.getOrNull()
}

class ModuleActivationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ModuleActivationTracker.ACTION_RECORD_ACTIVATION) return
        val sourcePackage = intent.getStringExtra(ModuleActivationTracker.EXTRA_SOURCE_PACKAGE)
            ?: return
        ModuleActivationTracker.recordActivation(
            context = context,
            sourcePackage = sourcePackage,
            sourceProcess = intent.getStringExtra(ModuleActivationTracker.EXTRA_SOURCE_PROCESS)
        )
    }
}
