package com.xposed.wetypehook

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.xposed.wetypehook.wetype.graphics.WeTypeNativeMaterialProbe

const val EXTRA_OPEN_WETYPE_EMBEDDED_SETTINGS = "com.xposed.wetypehook.extra.OPEN_WETYPE_EMBEDDED_SETTINGS"
const val EXTRA_OPEN_WETYPE_BACKUP_PAGE = "com.xposed.wetypehook.extra.OPEN_WETYPE_BACKUP_PAGE"

class MainActivity : ComponentActivity() {
    private var hasAttemptedEmbeddedLaunch = false
    private var activationStatusListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var activationStatus by mutableStateOf(
        ModuleActivationTracker.ActivationStatus(
            isActive = false,
            sourcePackage = null,
            sourceProcess = null,
            lastActivatedAt = 0L
        )
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        activationStatus = ModuleActivationTracker.resolveStatusForUi(this)
        activationStatusListener = ModuleActivationTracker.registerStatusListener(this) { status ->
            activationStatus = status
            if (WeTypeNativeMaterialProbe.isEnabled()) return@registerStatusListener
            if (!status.hasFreshHeartbeat()) return@registerStatusListener
            runOnUiThread {
                launchEmbeddedSettingsAndFinish()
            }
        }
        setContent {
            ActivationEntryApp(
                isActive = activationStatus.hasFreshHeartbeat(),
                onOpenEmbeddedSettings = ::launchEmbeddedSettingsAndFinish
            )
        }
        // R1 探针：普通 Activity 对照宿主，默认关闭时完全不介入。
        if (WeTypeNativeMaterialProbe.isEnabled()) {
            (window.decorView as? android.view.ViewGroup)?.let {
                WeTypeNativeMaterialProbe.installActivityHost(it)
            }
        }
        launchEmbeddedSettingsIfActive()
    }

    override fun onResume() {
        super.onResume()
        activationStatus = ModuleActivationTracker.resolveStatusForUi(this)
        launchEmbeddedSettingsIfActive()
    }

    override fun onDestroy() {
        activationStatusListener?.let {
            ModuleActivationTracker.unregisterStatusListener(this, it)
            activationStatusListener = null
        }
        super.onDestroy()
    }

    private fun launchEmbeddedSettingsIfActive(): Boolean {
        // R1 探针开启时留在本 Activity，作为普通窗口对照宿主，不跳转内嵌设置。
        if (WeTypeNativeMaterialProbe.isEnabled()) return false
        if (!hasAttemptedEmbeddedLaunch && activationStatus.hasFreshHeartbeat()) {
            return launchEmbeddedSettingsAndFinish()
        }
        return false
    }

    private fun launchEmbeddedSettingsAndFinish(): Boolean {
        if (hasAttemptedEmbeddedLaunch) return false
        hasAttemptedEmbeddedLaunch = true
        val launched = openEmbeddedWeTypeSettings()
        if (launched) {
            finish()
        } else {
            hasAttemptedEmbeddedLaunch = false
        }
        return launched
    }

    private fun openEmbeddedWeTypeSettings(): Boolean {
        val bridgePendingIntent = runCatching {
            ModuleBridgeContract.createSettingsBridgePendingIntent(this)
        }.getOrNull()
        val launchIntents = listOfNotNull(
            packageManager.getLaunchIntentForPackage("com.tencent.wetype")?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(EXTRA_OPEN_WETYPE_EMBEDDED_SETTINGS, true)
                bridgePendingIntent?.let {
                    putExtra(ModuleBridgeContract.EXTRA_BRIDGE_PENDING_INTENT, it)
                }
            },
            Intent(Intent.ACTION_MAIN).apply {
                setPackage("com.tencent.wetype")
                addCategory(Intent.CATEGORY_LAUNCHER)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(EXTRA_OPEN_WETYPE_EMBEDDED_SETTINGS, true)
                bridgePendingIntent?.let {
                    putExtra(ModuleBridgeContract.EXTRA_BRIDGE_PENDING_INTENT, it)
                }
            },
            Intent().apply {
                component = ComponentName(
                    "com.tencent.wetype",
                    "com.tencent.wetype.plugin.hld.ui.ImeAboutActivity"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(EXTRA_OPEN_WETYPE_EMBEDDED_SETTINGS, true)
                bridgePendingIntent?.let {
                    putExtra(ModuleBridgeContract.EXTRA_BRIDGE_PENDING_INTENT, it)
                }
            }
        )

        for (intent in launchIntents) {
            val launched = runCatching {
                startActivity(intent)
                true
            }.getOrElse { false }
            if (launched) return true
        }

        Toast.makeText(this, "Failed to open WeType", Toast.LENGTH_SHORT).show()
        return false
    }
}
