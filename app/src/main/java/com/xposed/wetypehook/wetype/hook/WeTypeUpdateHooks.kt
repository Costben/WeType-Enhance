package com.xposed.wetypehook.wetype.hook

import com.xposed.wetypehook.wetype.settings.WeTypeSettings
import com.xposed.wetypehook.xposed.Log
import com.xposed.wetypehook.xposed.hookBefore
import com.xposed.wetypehook.xposed.loadClassOrNull

private const val RFIX_CLASS = "com.tencent.rfix.lib.RFix"
private const val TINKER_PATCH_SERVICE_CLASS = "com.tencent.tinker.lib.service.TinkerPatchService"

internal object WeTypeUpdateHooks {

    fun hookDisableHotUpdate() {
        hookRFixRequestConfig()
        hookTinkerPatchService()
    }

    private fun hookRFixRequestConfig() {
        runCatching {
            val rfixClass = loadClassOrNull(RFIX_CLASS)
                ?: error("RFix class not found")

            val requestConfigMethod = rfixClass.getMethod("requestConfig")
            requestConfigMethod.hookBefore { param ->
                if (!WeTypeSettings.isDisableHotUpdateXposed()) return@hookBefore
                param.result = null
                Log.i("Success: Blocked RFix.requestConfig")
            }
            Log.i("Success: Hook RFix.requestConfig")
        }.onFailure {
            Log.i("Failed: Hook RFix.requestConfig")
            Log.i(it)
        }
    }

    private fun hookTinkerPatchService() {
        runCatching {
            val serviceClass = loadClassOrNull(TINKER_PATCH_SERVICE_CLASS)
                ?: error("TinkerPatchService class not found")

            serviceClass.declaredMethods
                .filter { it.name == "runPatchService" }
                .forEach { method ->
                    method.hookBefore { param ->
                        if (!WeTypeSettings.isDisableHotUpdateXposed()) return@hookBefore
                        param.result = null
                        Log.i("Success: Blocked TinkerPatchService.runPatchService")
                    }
                }
            Log.i("Success: Hook TinkerPatchService.runPatchService")
        }.onFailure {
            Log.i("Failed: Hook TinkerPatchService.runPatchService")
            Log.i(it)
        }
    }
}
