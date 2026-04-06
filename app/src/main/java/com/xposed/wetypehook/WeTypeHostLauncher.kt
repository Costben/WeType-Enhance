package com.xposed.wetypehook

import android.app.Activity
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.ContextThemeWrapper
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import androidx.activity.ComponentDialog
import androidx.compose.ui.platform.ComposeView
import com.github.kyuubiran.ezxhelper.utils.Log
import java.util.WeakHashMap

private const val MODULE_PACKAGE_NAME = "com.xposed.wetypehook"
private val activeHostDialogs = WeakHashMap<Activity, ComponentDialog>()
private val moduleResourcesCache = HashMap<String, Resources>()

object WeTypeHostLauncher {
    fun show(activity: Activity) {
        activeHostDialogs[activity]?.takeIf { it.isShowing }?.let { return }

        val moduleContext = runCatching {
            activity.createPackageContext(
                MODULE_PACKAGE_NAME,
                Context.CONTEXT_IGNORE_SECURITY or Context.CONTEXT_INCLUDE_CODE
            )
        }.getOrElse {
            Log.e("Failed:Create module package context for WeType host dialog")
            Log.i(it)
            createEmbeddedModuleContext(activity)
                ?: return
        }

        val dialog = ComponentDialog(
            ModuleHostContext(activity, moduleContext),
            R.style.Theme_WeTypeHook_HostDialog
        ).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setCanceledOnTouchOutside(false)
            setOnDismissListener { activeHostDialogs.remove(activity) }
        }
        activeHostDialogs[activity] = dialog
        val windowBackgroundColor = resolveWindowBackgroundColor(dialog.context)

        val composeView = ComposeView(dialog.context).apply {
            setBackgroundColor(windowBackgroundColor)
            setContent {
                WeTypeSettingsApp(
                    settingsContext = activity
                )
            }
        }
        dialog.setContentView(
            composeView,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        dialog.show()
        dialog.window?.apply {
            decorView.setPadding(0, 0, 0, 0)
            setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT
            )
            statusBarColor = windowBackgroundColor
            navigationBarColor = windowBackgroundColor
            setBackgroundDrawable(ColorDrawable(windowBackgroundColor))
        }
    }

    private fun createEmbeddedModuleContext(activity: Activity): Context? {
        val moduleApkPath = ModuleRuntime.resolveModuleApkPath()
        if (moduleApkPath == null) {
            Log.e("Failed:Resolve module apk path for embedded WeType host dialog")
            return null
        }
        val moduleResources = runCatching {
            synchronized(moduleResourcesCache) {
                moduleResourcesCache.getOrPut(moduleApkPath) {
                    val assetManager = AssetManager::class.java.getDeclaredConstructor().newInstance()
                    val addAssetPath = AssetManager::class.java.getMethod(
                        "addAssetPath",
                        String::class.java
                    )
                    check(addAssetPath.invoke(assetManager, moduleApkPath) as Int != 0) {
                        "Failed to add embedded module asset path: $moduleApkPath"
                    }
                    Resources(
                        assetManager,
                        activity.resources.displayMetrics,
                        activity.resources.configuration
                    )
                }
            }
        }.getOrElse {
            Log.e("Failed:Create embedded module resources for WeType host dialog")
            Log.i(it)
            return null
        }
        return EmbeddedModuleContext(activity, moduleResources)
    }
}

private fun resolveWindowBackgroundColor(context: Context): Int {
    val isDarkMode =
        context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    return if (isDarkMode) Color.BLACK else Color.parseColor("#F7F7F7")
}

private class ModuleHostContext(
    baseContext: Context,
    private val moduleContext: Context
) : ContextThemeWrapper(baseContext, 0) {
    private val hostApplicationContext = baseContext.applicationContext ?: baseContext
    private val moduleTheme by lazy(LazyThreadSafetyMode.NONE) {
        moduleContext.resources.newTheme().apply {
            setTo(moduleContext.theme)
        }
    }

    override fun getApplicationContext(): Context {
        return hostApplicationContext
    }

    override fun getAssets(): AssetManager {
        return moduleContext.assets
    }

    override fun getResources(): Resources {
        return moduleContext.resources
    }

    override fun getTheme(): Resources.Theme {
        return moduleTheme
    }

    override fun setTheme(resid: Int) {
        moduleTheme.applyStyle(resid, true)
    }

    override fun getPackageName(): String {
        return moduleContext.packageName
    }

    override fun getApplicationInfo(): ApplicationInfo {
        return moduleContext.applicationInfo
    }

    override fun getClassLoader(): ClassLoader {
        return moduleContext.classLoader
    }
}

private class EmbeddedModuleContext(
    baseContext: Context,
    private val moduleResources: Resources
) : ContextThemeWrapper(baseContext, 0) {
    private val hostBaseContext = baseContext
    private val hostApplicationContext = baseContext.applicationContext ?: baseContext
    private val moduleTheme by lazy(LazyThreadSafetyMode.NONE) {
        moduleResources.newTheme().apply {
            setTo(baseContext.theme)
            applyStyle(android.R.style.Theme_DeviceDefault_NoActionBar, true)
        }
    }

    override fun getApplicationContext(): Context {
        return hostApplicationContext
    }

    override fun getAssets(): AssetManager {
        return moduleResources.assets
    }

    override fun getResources(): Resources {
        return moduleResources
    }

    override fun getTheme(): Resources.Theme {
        return moduleTheme
    }

    override fun setTheme(resid: Int) {
        moduleTheme.applyStyle(resid, true)
    }

    override fun getPackageName(): String {
        return MODULE_PACKAGE_NAME
    }

    override fun getApplicationInfo(): ApplicationInfo {
        return hostBaseContext.applicationInfo
    }

    override fun getClassLoader(): ClassLoader {
        return WeTypeHostLauncher::class.java.classLoader ?: hostBaseContext.classLoader
    }
}
