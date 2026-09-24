package com.xposed.wetypehook

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentDialog
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.xposed.wetypehook.wetype.settings.WeTypeSettings
import com.xposed.wetypehook.xposed.Log
import java.util.WeakHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val MODULE_PACKAGE_NAME = "com.xposed.wetypehook"
private val activeHostDialogs = WeakHashMap<Activity, ComponentDialog>()
private val activeBackupPages = WeakHashMap<Activity, View>()
private val activeColorOsLightPages = WeakHashMap<Activity, View>()
private val backupPageBusy = WeakHashMap<Activity, () -> Boolean>()
private val moduleResourcesCache = HashMap<String, Resources>()
private val BACKUP_PAGE_HOST_ACTIVITIES = listOf(
    "com.tencent.wetype.plugin.hld.reactnative.activity.ImeMainSettingActivity",
    "com.tencent.wetype.plugin.hld.ui.ImeAboutActivity"
)
private const val HOST_PACKAGE_NAME = "com.tencent.wetype"

object WeTypeHostLauncher {
    fun show(activity: Activity) {
        activeHostDialogs[activity]?.takeIf { it.isShowing }?.let { return }
        WeTypeSettings.bindModuleBridgePendingIntent(
            ModuleBridgeContract.settingsBridgePendingIntent(activity.intent)
        )

        val moduleContext = resolveModuleContext(activity) ?: return

        val dialog = ComponentDialog(
            ModuleHostContext(activity, moduleContext),
            R.style.Theme_WeTypeHook_HostDialog
        ).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setCanceledOnTouchOutside(false)
            setOnDismissListener {
                activeHostDialogs.remove(activity)
                WeTypeSettings.bindModuleBridgePendingIntent(null)
            }
        }
        activeHostDialogs[activity] = dialog
        val isDarkMode = resolveWindowIsDarkMode(dialog.context)
        val windowBackgroundColor = if (isDarkMode) {
            Color.BLACK
        } else {
            Color.parseColor("#F7F7F7")
        }

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
            WindowCompat.getInsetsController(this, decorView).apply {
                isAppearanceLightStatusBars = !isDarkMode
                isAppearanceLightNavigationBars = !isDarkMode
            }
        }
    }

    /**
     * 宿主外层 Activity 上再启动一个设置 Activity 实例，并把内容替换为备份与恢复页，
     * 形成真正的二级 Activity 页面（非弹窗），返回键回退到嵌入设置。
     */
    fun launchBackupPage(activity: Activity?): Boolean {
        if (activity == null) return false
        for (className in BACKUP_PAGE_HOST_ACTIVITIES) {
            val intent = Intent().apply {
                component = ComponentName(HOST_PACKAGE_NAME, className)
                putExtra(EXTRA_OPEN_WETYPE_BACKUP_PAGE, true)
            }
            val started = runCatching {
                activity.startActivity(intent)
                true
            }.getOrDefault(false)
            if (started) return true
        }
        return false
    }

    /**
     * ColorOS 光感设置是宿主外层 Activity 上再启动的设置 Activity 实例，
     * 与备份页同模式，返回键回退到嵌入设置。
     */
    fun launchColorOsLightPage(activity: Activity?): Boolean {
        if (activity == null) return false
        for (className in BACKUP_PAGE_HOST_ACTIVITIES) {
            val intent = Intent().apply {
                component = ComponentName(HOST_PACKAGE_NAME, className)
                putExtra(EXTRA_OPEN_WETYPE_COLOROS_LIGHT_PAGE, true)
            }
            val started = runCatching {
                activity.startActivity(intent)
                true
            }.getOrDefault(false)
            if (started) return true
        }
        return false
    }

    fun showColorOsLightPage(activity: Activity) {
        if (activeColorOsLightPages.containsKey(activity)) return
        val moduleContext = resolveModuleContext(activity) ?: return
        val isDarkMode = resolveWindowIsDarkMode(activity)
        val windowBackgroundColor = if (isDarkMode) {
            Color.BLACK
        } else {
            Color.parseColor("#F7F7F7")
        }
        val pageLifecycleOwner = HostPageLifecycleOwner().apply { moveToResumed() }
        val composeView = ComposeView(ModuleHostContext(activity, moduleContext)).apply {
            setBackgroundColor(windowBackgroundColor)
            setViewTreeLifecycleOwner(pageLifecycleOwner)
            setViewTreeViewModelStoreOwner(HostPageViewModelOwner())
            setViewTreeSavedStateRegistryOwner(HostPageSavedStateOwner())
            setContent {
                ColorOsLightApp(
                    settingsContext = activity,
                    onClose = { activity.finish() }
                )
            }
        }
        activity.window?.apply {
            WindowCompat.setDecorFitsSystemWindows(this, false)
            statusBarColor = windowBackgroundColor
            navigationBarColor = windowBackgroundColor
            setBackgroundDrawable(ColorDrawable(windowBackgroundColor))
            WindowCompat.getInsetsController(this, decorView).apply {
                isAppearanceLightStatusBars = !isDarkMode
                isAppearanceLightNavigationBars = !isDarkMode
            }
        }
        activity.setContentView(
            composeView,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        composeView.post {
            val insets = ViewCompat.getRootWindowInsets(composeView)
            val topInset = insets?.getInsets(WindowInsetsCompat.Type.statusBars())?.top ?: 0
            if (topInset > 0 && composeView.paddingTop != topInset) {
                composeView.setPadding(0, topInset, 0, 0)
            }
        }
        activeColorOsLightPages[activity] = composeView
    }

    fun showBackupPage(activity: Activity) {
        if (activeBackupPages.containsKey(activity)) return
        val moduleContext = resolveModuleContext(activity) ?: return
        val isDarkMode = resolveWindowIsDarkMode(activity)
        val windowBackgroundColor = if (isDarkMode) {
            Color.BLACK
        } else {
            Color.parseColor("#F7F7F7")
        }
        val busyState = mutableStateOf(false)
        backupPageBusy[activity] = { busyState.value }
        val pageLifecycleOwner = HostPageLifecycleOwner().apply { moveToResumed() }
        val composeView = ComposeView(ModuleHostContext(activity, moduleContext)).apply {
            setBackgroundColor(windowBackgroundColor)
            setViewTreeLifecycleOwner(pageLifecycleOwner)
            setViewTreeViewModelStoreOwner(HostPageViewModelOwner())
            setViewTreeSavedStateRegistryOwner(HostPageSavedStateOwner())
            setContent {
                ClipboardBackupApp(
                    settingsContext = activity,
                    busyState = busyState,
                    onClose = { activity.finish() }
                )
            }
        }
        activity.window?.apply {
            WindowCompat.setDecorFitsSystemWindows(this, false)
            statusBarColor = windowBackgroundColor
            navigationBarColor = windowBackgroundColor
            setBackgroundDrawable(ColorDrawable(windowBackgroundColor))
            WindowCompat.getInsetsController(this, decorView).apply {
                isAppearanceLightStatusBars = !isDarkMode
                isAppearanceLightNavigationBars = !isDarkMode
            }
        }
        activity.setContentView(
            composeView,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        composeView.post {
            val insets = ViewCompat.getRootWindowInsets(composeView)
            val topInset = insets?.getInsets(WindowInsetsCompat.Type.statusBars())?.top ?: 0
            if (topInset > 0 && composeView.paddingTop != topInset) {
                composeView.setPadding(0, topInset, 0, 0)
            }
        }
        activeBackupPages[activity] = composeView
    }

    fun isBackupPageBusy(activity: Activity): Boolean =
        backupPageBusy[activity]?.invoke() == true

    fun prepareForHotReload(): Boolean {
        val cleaned = runOnMainThreadBlocking {
            val dialogs = synchronized(activeHostDialogs) {
                activeHostDialogs.values.toList().also { activeHostDialogs.clear() }
            }
            dialogs.forEach { dialog ->
                dialog.setOnDismissListener(null)
                if (dialog.isShowing) dialog.dismiss()
            }
            synchronized(activeBackupPages) {
                activeBackupPages.keys.toList().also {
                    activeBackupPages.clear()
                    backupPageBusy.clear()
                }
            }.forEach { page -> page.finish() }
            synchronized(activeColorOsLightPages) {
                activeColorOsLightPages.keys.toList().also {
                    activeColorOsLightPages.clear()
                }
            }.forEach { page -> page.finish() }
        }
        if (cleaned) {
            WeTypeSettings.bindModuleBridgePendingIntent(null)
            synchronized(moduleResourcesCache) {
                moduleResourcesCache.clear()
            }
        }
        return cleaned
    }

    private fun resolveModuleContext(activity: Activity): Context? {
        val moduleContext = runCatching {
            activity.createPackageContext(
                MODULE_PACKAGE_NAME,
                Context.CONTEXT_IGNORE_SECURITY or Context.CONTEXT_INCLUDE_CODE
            )
        }.getOrElse {
            Log.e("Failed:Create module package context for WeType host dialog")
            Log.i(it)
            createEmbeddedModuleContext(activity)
        }
        if (moduleContext == null) {
            Log.e("Failed:Resolve module package context for WeType host page")
        }
        return moduleContext
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

private fun runOnMainThreadBlocking(block: () -> Unit): Boolean {
    if (Looper.myLooper() == Looper.getMainLooper()) {
        return runCatching(block).onFailure {
            Log.e("Failed:Cleanup WeType host dialog for hot reload")
            Log.i(it)
        }.isSuccess
    }

    val completed = CountDownLatch(1)
    var failure: Throwable? = null
    if (!Handler(Looper.getMainLooper()).post {
            try {
                block()
            } catch (error: Throwable) {
                failure = error
            } finally {
                completed.countDown()
            }
        }
    ) {
        return false
    }
    val finished = runCatching { completed.await(2, TimeUnit.SECONDS) }.getOrDefault(false)
    failure?.let {
        Log.e("Failed:Cleanup WeType host dialog for hot reload")
        Log.i(it)
    }
    return finished && failure == null
}

private fun resolveWindowIsDarkMode(context: Context): Boolean =
    context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
        Configuration.UI_MODE_NIGHT_YES

/**
 * 宿主 Activity 未走 ComponentActivity#setContent，直接在装饰视图上挂 ComposeView
 * 会因缺少 ViewTreeLifecycleOwner 崩溃；且宿主实例实现的是宿主 dex 中的
 * LifecycleOwner 接口，与模块打包的接口不是同一运行时类，无法直接转型。
 * 这里为页面自建生命周期所有者，只服务于模块自己的 ComposeView。
 */
private open class HostPageLifecycleOwner : LifecycleOwner {
    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry

    fun moveToResumed() {
        registry.currentState = Lifecycle.State.RESUMED
    }
}

private class HostPageViewModelOwner : ViewModelStoreOwner {
    override val viewModelStore: ViewModelStore = ViewModelStore()
}

private class HostPageSavedStateOwner : HostPageLifecycleOwner(), SavedStateRegistryOwner {
    private val controller = SavedStateRegistryController.create(this)

    init {
        controller.performAttach()
        controller.performRestore(null)
    }

    override val savedStateRegistry: SavedStateRegistry get() = controller.savedStateRegistry
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
