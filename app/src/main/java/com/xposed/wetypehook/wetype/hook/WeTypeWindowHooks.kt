package com.xposed.wetypehook.wetype.hook

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Path
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.RoundedCorner
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.view.Window
import android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.graphics.drawable.toDrawable
import com.xposed.wetypehook.xposed.Log
import com.xposed.wetypehook.xposed.HookEnvironment
import com.xposed.wetypehook.xposed.findMethodInHierarchy
import com.xposed.wetypehook.xposed.getObjectAs
import com.xposed.wetypehook.xposed.hookAfter
import com.xposed.wetypehook.xposed.hookBefore
import com.xposed.wetypehook.xposed.invokeMethodAs
import com.xposed.wetypehook.xposed.loadClassOrNull
import com.xposed.wetypehook.wetype.graphics.WeTypeSystemMaterial
import com.xposed.wetypehook.wetype.graphics.WeTypeSystemMaterials
import com.xposed.wetypehook.wetype.graphics.WeTypeBloomStrokeDrawable
import com.xposed.wetypehook.wetype.graphics.WeTypeCornerRadii
import com.xposed.wetypehook.wetype.graphics.createWeTypeSmoothRoundedPath
import com.xposed.wetypehook.wetype.settings.GlassMaterialOverrides
import com.xposed.wetypehook.wetype.settings.WeTypeSettings
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val WETYPE_COLLAPSED_IME_HEIGHT_THRESHOLD_PX = 2
/**
 * 导航栏图标反色的亮度判据。
 *
 * 微信输入法自己不声明导航栏 appearance，而 `DisplayPolicy.chooseNavigationColorWindowLw`
 * 会让铺满全屏的 IME 窗口成为导航栏外观的权威来源，于是它的沉默（appearance=0）被当成
 * "要深色图标"——浅色键盘上那两个系统底栏图标就此消失。这里由模块替它把话说清楚。
 */
private const val NAV_BAR_LUMINANCE_THRESHOLD = 0.5
/**
 * `BackgroundStyle.color` 带透明度时无法直接算亮度：用户给的是叠加色，"实际看起来多亮"
 * 取决于底下透出什么。透明度低于此值就改用系统深浅模式兜底，而不是拿半透明色硬算。
 */
private const val NAV_BAR_OPAQUE_ALPHA_THRESHOLD = 200

private const val WETYPE_HARDWARE_VIEW_CLASS_PREFIX = "com.tencent.wetype.plugin.hld.hardware."
private const val WETYPE_CANDIDATE_VIEW_CLASS_NAME =
    "com.tencent.wetype.plugin.hld.candidate.ImeCandidateView"
private const val WETYPE_SETTINGS_KEYBOARD_CLASS_NAME =
    "com.tencent.wetype.plugin.hld.keyboard.S10SettingsKeyboard"
private const val WETYPE_SETTINGS_RECYCLER_VIEW_CLASS_NAME =
    "com.tencent.wetype.plugin.hld.view.settingkeyboard.S10SettingRecyclerView"
private const val WETYPE_SETTING_VIEW_PACKAGE_PREFIX =
    "com.tencent.wetype.plugin.hld.view.settingkeyboard."

private val WETYPE_TRANSPARENT_OVERLAY_CLASS_NAMES = setOf(
    "com.tencent.wetype.plugin.hld.keyboard.selfdraw.S11EmojiKeyboard",
    "com.tencent.wetype.plugin.hld.keyboard.S15CustomPhraseAndClipboardKeyboard",
    "com.tencent.wetype.plugin.hld.keyboard.S33ImagePreviewKeyboard",
    "com.tencent.wetype.plugin.hld.keyboard.S34ClipboardBombKeyboard",
    "com.tencent.wetype.plugin.hld.keyboard.S35RequestAIKeyboard"
)

private val WETYPE_INTERNAL_SETTING_OVERLAY_CLASS_NAMES = setOf(
    "${WETYPE_SETTING_VIEW_PACKAGE_PREFIX}S10SettingKeyboardTypeView",
    "${WETYPE_SETTING_VIEW_PACKAGE_PREFIX}S10SettingCustomToolbarView"
)

private val WETYPE_HARDWARE_VIEW_ID_NAMES = arrayOf(
    "hardware_keyboard_candidate_container_view",
    "hardware_keyboard_pending_container_view",
    "hardware_keyboard_alternative_container_view",
    "hardware_keyboard_candidate_recyclerview",
    "hardware_keyboard_candidate_right_container"
)

internal object WeTypeWindowHooks {
    private data class WeTypeGlobalLayoutRegistration(
        val observer: WeakReference<ViewTreeObserver>,
        val listener: ViewTreeObserver.OnGlobalLayoutListener
    )

    private data class BackgroundStyle(
        val color: Int,
        val blurRadius: Int,
        val edgeHighlightEnabled: Boolean,
        val edgeHighlightIntensity: Int,
        val cornerRadii: WeTypeCornerRadii,
        val nightMode: Int,
        val density: Float,
        val hyperMaterialEnabled: Boolean,
        val hyperMaterialAvailable: Boolean
    )

    private class ContinuousCornerOutline(val cornerRadii: WeTypeCornerRadii) : ViewOutlineProvider() {
        private var cachedWidth = 0
        private var cachedHeight = 0
        private var cachedPath: Path? = null

        override fun getOutline(target: View, outline: Outline) {
            val width = target.width
            val height = target.height
            if (width <= 0 || height <= 0) return
            if (cachedPath == null || width != cachedWidth || height != cachedHeight) {
                cachedPath = createWeTypeSmoothRoundedPath(width.toFloat(), height.toFloat(), cornerRadii)
                cachedWidth = width
                cachedHeight = height
            }
            runCatching { outline.setPath(checkNotNull(cachedPath)) }.onFailure {
                outline.setRoundRect(0, 0, width, height, cornerRadii.maxRadius())
            }
        }
    }

    private data class WeTypeWindowState(
        var windowVisible: Boolean = false,
        var backgroundCarrier: View? = null,
        var carrierOverrides: GlassMaterialOverrides = GlassMaterialOverrides(),
        var hyperMaterial: WeTypeSystemMaterial? = null,
        var stopMaterialObserver: (() -> Unit)? = null,
        var window: WeakReference<Window>? = null,
        var resourceReconcilePending: Boolean = false,
        var backgroundDecorView: WeakReference<View>? = null,
        var backgroundObserver: WeakReference<ViewTreeObserver>? = null,
        var backgroundLayoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null,
        var backgroundPreDrawListener: ViewTreeObserver.OnPreDrawListener? = null,
        var backgroundUpdatePending: Boolean = false,
        var backgroundStyleDirty: Boolean = true,
        var backgroundStyle: BackgroundStyle? = null,
        var backgroundViewRoot: Any? = null,
        var transparentWindowBackground: Drawable? = null,
        val locationBuffer: IntArray = IntArray(2),
        var computedVisibleImeHeightPx: Int? = null,
        var bottomLeftHardwareCornerRadius: Float? = null,
        var bottomRightHardwareCornerRadius: Float? = null,
        var hardwareViewIds: IntArray? = null,
        var originalWindowStateCaptured: Boolean = false,
        var originalWindowBackground: Drawable? = null,
        var originalWindowBlurRadius: Int? = null,
        var navBarAppearanceCaptured: Boolean = false,
        var originalNavBarAppearance: Int = 0
    )

    private val weTypeWindowStates = WeakHashMap<Any, WeTypeWindowState>()
    private val overlayStateLock = Any()
    private val overlayRootsByContainer =
        WeakHashMap<ViewGroup, MutableMap<View, Boolean>>()
    private val overlayContainerByRoot = WeakHashMap<View, WeakReference<ViewGroup>>()
    private val coveredUnderlayOriginalVisibilities =
        WeakHashMap<ViewGroup, MutableMap<View, Int>>()
    private val internalSettingUnderlayOriginalVisibilities =
        WeakHashMap<ViewGroup, MutableMap<View, Int>>()
    private val internalSettingContainers = WeakHashMap<ViewGroup, Boolean>()
    private val internalSettingContainerByOverlay = WeakHashMap<View, WeakReference<ViewGroup>>()
    private val externalOverlayAttachListeners =
        WeakHashMap<View, View.OnAttachStateChangeListener>()
    private val internalSettingAttachListeners =
        WeakHashMap<View, View.OnAttachStateChangeListener>()
    private val externalOverlayLayoutRegistrations =
        WeakHashMap<ViewGroup, WeTypeGlobalLayoutRegistration>()
    private val internalSettingLayoutRegistrations =
        WeakHashMap<ViewGroup, WeTypeGlobalLayoutRegistration>()
    private val overlaySyncPosted = WeakHashMap<ViewGroup, Boolean>()
    private val internalSettingSyncPosted = WeakHashMap<ViewGroup, Boolean>()
    private var weTypeKeyboardBaseClasses: Set<Class<*>> = emptySet()
    private var weTypeCandidateViewClass: Class<*>? = null
    @Volatile
    private var overlayWindowVisible = false

    fun prepareForHotReload(): Boolean {
        val states = synchronized(weTypeWindowStates) {
            weTypeWindowStates.values.toList()
        }
        val cleaned = runOnMainThreadBlocking {
            overlayWindowVisible = false
            restoreAllCoveredUnderlays(clearTrackedRoots = true)
            states.forEach { state ->
                state.windowVisible = false
                removeBackgroundListeners(state)
                restoreWindowState(state)
                removeBackgroundCarrier(state)
            }
        }
        if (cleaned) {
            synchronized(weTypeWindowStates) {
                weTypeWindowStates.clear()
            }
            synchronized(overlayStateLock) {
                overlayRootsByContainer.clear()
                overlayContainerByRoot.clear()
                coveredUnderlayOriginalVisibilities.clear()
                internalSettingUnderlayOriginalVisibilities.clear()
                internalSettingContainers.clear()
                internalSettingContainerByOverlay.clear()
                externalOverlayAttachListeners.clear()
                internalSettingAttachListeners.clear()
                externalOverlayLayoutRegistrations.clear()
                internalSettingLayoutRegistrations.clear()
                overlaySyncPosted.clear()
                internalSettingSyncPosted.clear()
                weTypeKeyboardBaseClasses = emptySet()
                weTypeCandidateViewClass = null
            }
        }
        return cleaned
    }

    fun hookTransparentOverlayUnderlay() {
        val resolvedExternalOverlays = WETYPE_TRANSPARENT_OVERLAY_CLASS_NAMES.mapNotNull { className ->
            runCatching {
                val overlayClass = loadClassOrNull(className)
                    ?: error("Failed to resolve $className")
                val showMethod = overlayClass.declaredMethods.single { method ->
                    method.returnType == Void.TYPE &&
                        method.parameterTypes.size == 2 &&
                        method.parameterTypes[1] == Bundle::class.java
                }.apply { isAccessible = true }
                overlayClass to showMethod
            }.onFailure { error ->
                Log.i("Failed: Hook transparent overlay for $className")
                Log.i(error)
            }.getOrNull()
        }

        weTypeKeyboardBaseClasses = resolvedExternalOverlays.mapNotNull { (overlayClass, _) ->
            runCatching {
                findKeyboardBaseClass(overlayClass)
            }.onFailure { error ->
                Log.i("Failed: Resolve WeType keyboard base for ${overlayClass.name}")
                Log.i(error)
            }.getOrNull()
        }.toSet()
        weTypeCandidateViewClass = loadClassOrNull(WETYPE_CANDIDATE_VIEW_CLASS_NAME).also { candidateClass ->
            if (candidateClass == null) {
                Log.i("Failed: Resolve WeType candidate view for transparent overlays")
            }
        }

        resolvedExternalOverlays.forEach { (overlayClass, showMethod) ->
            runCatching {
                showMethod.hookAfter { param ->
                    reconcileShownOverlayRoot(param.thisObject as? View)
                }
                Log.i("Success: Hook transparent overlay for ${overlayClass.name}")
            }.onFailure { error ->
                Log.i("Failed: Hook transparent overlay for ${overlayClass.name}")
                Log.i(error)
            }
        }

        hookInternalSettingOverlays()

        val inputMethodService = loadClassOrNull("android.inputmethodservice.InputMethodService")
        runCatching {
            inputMethodService?.getMethod("onWindowShown")?.hookAfter { param ->
                overlayWindowVisible = true
                reconcileCurrentOverlayUnderlays(param.thisObject)
            }
        }.onFailure(Log::i)
        runCatching {
            inputMethodService?.getMethod("onWindowHidden")?.hookAfter {
                overlayWindowVisible = false
                restoreAllCoveredUnderlays(clearTrackedRoots = true)
            }
        }.onFailure(Log::i)
        runCatching {
            inputMethodService?.getMethod("onDestroy")?.hookAfter {
                overlayWindowVisible = false
                restoreAllCoveredUnderlays(clearTrackedRoots = true)
            }
        }.onFailure(Log::i)

        if (resolvedExternalOverlays.isEmpty()) {
            Log.i("Failed: Hook WeType transparent overlay underlay visibility")
        } else {
            Log.i("Success: Hook WeType transparent overlay underlay visibility")
        }
    }

    fun reconcileCurrentOverlayUnderlays(rootViews: List<View>) {
        if (rootViews.isEmpty()) return
        overlayWindowVisible = true
        rootViews.forEach { root ->
            fun visit(view: View) {
                when (view.javaClass.name) {
                    in WETYPE_TRANSPARENT_OVERLAY_CLASS_NAMES ->
                        reconcileShownOverlayRoot(view)
                    in WETYPE_INTERNAL_SETTING_OVERLAY_CLASS_NAMES ->
                        reconcileInternalSettingOverlay(view)
                }
                if (view is ViewGroup) {
                    repeat(view.childCount) { index -> visit(view.getChildAt(index)) }
                }
            }
            visit(root)
        }
        scheduleKnownOverlayContainers()
        scheduleKnownInternalSettingContainers()
    }

    private fun reconcileCurrentOverlayUnderlays(inputMethodService: Any) {
        val decorView = resolveInputMethodDecorView(inputMethodService) ?: return
        reconcileCurrentOverlayUnderlays(listOf(decorView))
        HookEnvironment.postTracked(decorView, 48L) {
            reconcileCurrentOverlayUnderlays(listOf(decorView))
        }
        HookEnvironment.postTracked(decorView, 144L) {
            reconcileCurrentOverlayUnderlays(listOf(decorView))
        }
    }

    private fun reconcileShownOverlayRoot(view: View?) {
        if (!overlayWindowVisible) return
        val overlayRoot = view?.takeIf {
            it.javaClass.name in WETYPE_TRANSPARENT_OVERLAY_CLASS_NAMES
        } ?: return
        ensureExternalOverlayAttachListener(overlayRoot)
        fun registerWhenAttached(retriesRemaining: Int) {
            val container = findOverlayKeyboardContainer(overlayRoot)
            if (container != null) {
                registerOverlayRoot(container, overlayRoot)
                scheduleOverlayUnderlaySync(container)
            } else if (retriesRemaining > 0) {
                HookEnvironment.postTracked(overlayRoot, 32L) {
                    registerWhenAttached(retriesRemaining - 1)
                }
            }
        }
        registerWhenAttached(retriesRemaining = 5)
    }

    private fun registerOverlayRoot(container: ViewGroup, overlayRoot: View) {
        synchronized(overlayStateLock) {
            overlayRootsByContainer
                .getOrPut(container) { WeakHashMap() }[overlayRoot] = true
            overlayContainerByRoot[overlayRoot] = WeakReference(container)
        }
        ensureExternalOverlayLayoutRegistration(container)
    }

    private fun reconcileDetachedOverlayRoot(view: View?) {
        val overlayRoot = view?.takeIf {
            it.javaClass.name in WETYPE_TRANSPARENT_OVERLAY_CLASS_NAMES
        } ?: return
        val container = synchronized(overlayStateLock) {
            overlayContainerByRoot[overlayRoot]?.get()
        } ?: return
        scheduleOverlayUnderlaySync(container)
    }

    private fun ensureExternalOverlayAttachListener(overlayRoot: View) {
        val listener = synchronized(overlayStateLock) {
            if (externalOverlayAttachListeners.containsKey(overlayRoot)) {
                null
            } else {
                object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(view: View) {
                        reconcileShownOverlayRoot(view)
                    }

                    override fun onViewDetachedFromWindow(view: View) {
                        reconcileDetachedOverlayRoot(view)
                    }
                }.also { externalOverlayAttachListeners[overlayRoot] = it }
            }
        } ?: return
        overlayRoot.addOnAttachStateChangeListener(listener)
    }

    private fun scheduleOverlayUnderlaySync(container: ViewGroup) {
        ensureExternalOverlayLayoutRegistration(container)
        val shouldPost = synchronized(overlayStateLock) {
            if (overlaySyncPosted[container] == true) {
                false
            } else {
                overlaySyncPosted[container] = true
                true
            }
        }
        if (!shouldPost) return

        val posted = HookEnvironment.postTracked(container) {
            synchronized(overlayStateLock) { overlaySyncPosted.remove(container) }
            syncOverlayUnderlay(container)
            HookEnvironment.postTracked(container, 48L) { syncOverlayUnderlay(container) }
            HookEnvironment.postTracked(container, 144L) { syncOverlayUnderlay(container) }
        }
        if (!posted) {
            synchronized(overlayStateLock) { overlaySyncPosted.remove(container) }
            syncOverlayUnderlay(container)
        }
    }

    private fun syncOverlayUnderlay(container: ViewGroup) {
        if (!overlayWindowVisible) {
            restoreCoveredUnderlays(container)
            removeExternalOverlayLayoutRegistration(container)
            return
        }
        val visibleOverlayRoots = synchronized(overlayStateLock) {
            overlayRootsByContainer[container]
                ?.keys
                ?.filter { root ->
                    root.isAttachedToWindow &&
                        root.isShown &&
                        isDescendantOf(root, container)
                }
                .orEmpty()
                .toSet()
        }
        if (visibleOverlayRoots.isNotEmpty()) {
            hideCoveredUnderlays(container, visibleOverlayRoots)
        } else {
            restoreCoveredUnderlays(container)
            removeExternalOverlayLayoutRegistration(container)
        }
    }

    private fun ensureExternalOverlayLayoutRegistration(container: ViewGroup) {
        // Host reset/hide methods are intentionally not resolved: framework visibility and
        // reparenting changes are observed only while a transparent overlay is active.
        val observer = container.viewTreeObserver
        if (!observer.isAlive) return
        var previousRegistration: WeTypeGlobalLayoutRegistration? = null
        val registration = synchronized(overlayStateLock) {
            externalOverlayLayoutRegistrations[container]?.let { current ->
                if (current.observer.get() === observer) return@synchronized null
                previousRegistration = current
            }
            val containerReference = WeakReference(container)
            val listener = ViewTreeObserver.OnGlobalLayoutListener {
                containerReference.get()?.let(::syncOverlayUnderlay)
            }
            WeTypeGlobalLayoutRegistration(WeakReference(observer), listener).also {
                externalOverlayLayoutRegistrations[container] = it
            }
        } ?: return
        previousRegistration?.let { previous ->
            previous.observer.get()?.takeIf { observer -> observer.isAlive }
                ?.removeOnGlobalLayoutListener(previous.listener)
        }
        observer.addOnGlobalLayoutListener(registration.listener)
    }

    private fun removeExternalOverlayLayoutRegistration(container: ViewGroup) {
        val registration = synchronized(overlayStateLock) {
            externalOverlayLayoutRegistrations.remove(container)
        } ?: return
        registration.observer.get()?.takeIf { observer -> observer.isAlive }
            ?.removeOnGlobalLayoutListener(registration.listener)
    }

    private fun removeAllExternalOverlayLayoutRegistrations() {
        val containers = synchronized(overlayStateLock) {
            externalOverlayLayoutRegistrations.keys.toList()
        }
        containers.forEach(::removeExternalOverlayLayoutRegistration)
    }

    private fun hideCoveredUnderlays(
        container: ViewGroup,
        visibleOverlayRoots: Set<View>
    ) {
        val coveredUnderlays = findCoveredUnderlays(container)
        val rootsToRestore = mutableListOf<Pair<View, Int>>()
        val rootsToHide = mutableListOf<View>()
        synchronized(overlayStateLock) {
            val originals = coveredUnderlayOriginalVisibilities
                .getOrPut(container) { WeakHashMap() }
            visibleOverlayRoots.forEach { overlayRoot ->
                originals.remove(overlayRoot)?.let { visibility ->
                    rootsToRestore += overlayRoot to visibility
                }
            }
            coveredUnderlays.forEach { underlay ->
                if (
                    underlay !in visibleOverlayRoots &&
                    underlay.visibility == View.VISIBLE &&
                    underlay.isShown
                ) {
                    if (!originals.containsKey(underlay)) {
                        originals[underlay] = underlay.visibility
                    }
                    rootsToHide += underlay
                }
            }
        }
        rootsToRestore.forEach { (view, visibility) ->
            if (view.visibility == View.INVISIBLE) {
                setOverlayManagedVisibility(view, visibility)
            }
        }
        rootsToHide.forEach { underlay ->
            setOverlayManagedVisibility(underlay, View.INVISIBLE)
        }
    }

    private fun restoreCoveredUnderlays(container: ViewGroup) {
        val originals = synchronized(overlayStateLock) {
            coveredUnderlayOriginalVisibilities.remove(container)
                ?.entries
                ?.toList()
                .orEmpty()
        }
        originals.forEach { (view, visibility) ->
            if (view.visibility == View.INVISIBLE) {
                setOverlayManagedVisibility(view, visibility)
            }
        }
    }

    private fun restoreAllCoveredUnderlays(clearTrackedRoots: Boolean) {
        restoreAllInternalSettingUnderlays(clearTrackedOverlays = clearTrackedRoots)
        removeAllExternalOverlayLayoutRegistrations()
        val containers = synchronized(overlayStateLock) {
            coveredUnderlayOriginalVisibilities.keys.toList()
        }
        containers.forEach(::restoreCoveredUnderlays)
        if (clearTrackedRoots) {
            removeAllExternalOverlayAttachListeners()
            synchronized(overlayStateLock) {
                overlayRootsByContainer.clear()
                overlayContainerByRoot.clear()
                overlaySyncPosted.clear()
            }
        }
    }

    private fun scheduleKnownOverlayContainers() {
        val containers = synchronized(overlayStateLock) {
            overlayRootsByContainer.keys.toList()
        }
        containers.forEach(::scheduleOverlayUnderlaySync)
    }

    private fun scheduleKnownInternalSettingContainers() {
        val containers = synchronized(overlayStateLock) {
            internalSettingContainers.keys.toList()
        }
        containers.forEach(::scheduleInternalSettingUnderlaySync)
    }

    private fun removeAllExternalOverlayAttachListeners() {
        val listeners = synchronized(overlayStateLock) {
            externalOverlayAttachListeners.entries.map { (view, listener) -> view to listener }
                .also { externalOverlayAttachListeners.clear() }
        }
        listeners.forEach { (view, listener) ->
            view.removeOnAttachStateChangeListener(listener)
        }
    }

    private fun setOverlayManagedVisibility(view: View, visibility: Int) {
        view.visibility = visibility
    }

    private fun findCoveredUnderlays(root: View): List<View> {
        val result = mutableListOf<View>()
        fun visit(view: View) {
            if (isWeTypeKeyboardRoot(view) || isWeTypeCandidateView(view)) {
                result += view
                return
            }
            if (view is ViewGroup) {
                repeat(view.childCount) { index -> visit(view.getChildAt(index)) }
            }
        }
        visit(root)
        return result
    }

    private fun hookInternalSettingOverlays() {
        val hookedRenderMethods = mutableSetOf<java.lang.reflect.Method>()
        WETYPE_INTERNAL_SETTING_OVERLAY_CLASS_NAMES.forEach { className ->
            runCatching {
                val overlayClass = loadClassOrNull(className)
                    ?: error("Failed to resolve $className")
                val renderMethod = overlayClass.findMethodInHierarchy {
                    returnType == Void.TYPE &&
                        parameterTypes.contentEquals(
                            arrayOf(
                                View::class.java,
                                Bundle::class.java,
                                Boolean::class.javaPrimitiveType
                            )
                        )
                }
                if (hookedRenderMethods.add(renderMethod)) {
                    renderMethod.hookBefore { param ->
                        registerInternalSettingOverlay(param.thisObject as? View)
                    }
                    renderMethod.hookAfter { param ->
                        (param.thisObject as? View)?.let { overlay ->
                            reconcileInternalSettingOverlay(overlay)
                            scheduleInternalSettingOverlayReconcile(overlay)
                        }
                    }
                }
                Log.i("Success: Hook internal setting overlay for $className")
            }.onFailure { error ->
                Log.i("Failed: Hook internal setting overlay for $className")
                Log.i(error)
            }
        }
    }

    private fun registerInternalSettingOverlay(target: View?) {
        if (!overlayWindowVisible) return
        val overlay = target?.takeIf { view ->
            view.javaClass.name in WETYPE_INTERNAL_SETTING_OVERLAY_CLASS_NAMES &&
                hasAncestorClass(view, WETYPE_SETTINGS_KEYBOARD_CLASS_NAME)
        } ?: return
        val container = overlay.parent as? ViewGroup ?: return
        synchronized(overlayStateLock) {
            internalSettingContainerByOverlay[overlay] = WeakReference(container)
            internalSettingContainers[container] = true
        }
        ensureInternalSettingAttachListener(overlay)
        ensureInternalSettingLayoutRegistration(container)
        hideInternalSettingUnderlay(container)
    }

    private fun scheduleInternalSettingOverlayReconcile(overlay: View) {
        val reconcile = {
            reconcileInternalSettingOverlay(overlay)
            Unit
        }
        if (!HookEnvironment.postTracked(overlay, 48L, reconcile)) reconcile()
        HookEnvironment.postTracked(overlay, 144L, reconcile)
    }

    private fun hideInternalSettingUnderlay(container: ViewGroup) {
        val underlays = buildList {
            repeat(container.childCount) { index ->
                val child = container.getChildAt(index)
                if (
                    child is LinearLayout &&
                    child.visibility == View.VISIBLE &&
                    containsDescendantClass(child, WETYPE_SETTINGS_RECYCLER_VIEW_CLASS_NAME)
                ) {
                    add(child)
                }
            }
        }
        val rootsToHide = synchronized(overlayStateLock) {
            val originals = internalSettingUnderlayOriginalVisibilities
                .getOrPut(container) { WeakHashMap() }
            underlays.filter { underlay ->
                if (!originals.containsKey(underlay)) {
                    originals[underlay] = underlay.visibility
                }
                true
            }
        }
        rootsToHide.forEach { underlay ->
            setOverlayManagedVisibility(underlay, View.INVISIBLE)
        }
    }

    private fun reconcileInternalSettingOverlay(view: View?) {
        if (!overlayWindowVisible) return
        val overlay = view?.takeIf { candidate ->
            candidate.javaClass.name in WETYPE_INTERNAL_SETTING_OVERLAY_CLASS_NAMES
        } ?: return
        val container = resolveInternalSettingContainer(overlay) ?: return
        synchronized(overlayStateLock) {
            internalSettingContainers[container] = true
        }
        ensureInternalSettingAttachListener(overlay)
        ensureInternalSettingLayoutRegistration(container)
        syncInternalSettingUnderlay(container)
    }

    private fun scheduleInternalSettingUnderlaySync(container: ViewGroup) {
        ensureInternalSettingLayoutRegistration(container)
        val shouldPost = synchronized(overlayStateLock) {
            if (internalSettingSyncPosted[container] == true) {
                false
            } else {
                internalSettingSyncPosted[container] = true
                true
            }
        }
        if (!shouldPost) return

        val posted = HookEnvironment.postTracked(container) {
            synchronized(overlayStateLock) { internalSettingSyncPosted.remove(container) }
            syncInternalSettingUnderlay(container)
            HookEnvironment.postTracked(container, 48L) { syncInternalSettingUnderlay(container) }
            HookEnvironment.postTracked(container, 144L) { syncInternalSettingUnderlay(container) }
        }
        if (!posted) {
            synchronized(overlayStateLock) { internalSettingSyncPosted.remove(container) }
            syncInternalSettingUnderlay(container)
        }
    }

    private fun syncInternalSettingUnderlay(container: ViewGroup) {
        if (!overlayWindowVisible) {
            restoreInternalSettingUnderlay(container)
            removeInternalSettingLayoutRegistration(container)
            return
        }
        val hasVisibleOverlay = buildList {
            repeat(container.childCount) { index -> add(container.getChildAt(index)) }
        }.any { child ->
            child.javaClass.name in WETYPE_INTERNAL_SETTING_OVERLAY_CLASS_NAMES &&
                child.isAttachedToWindow &&
                child.isShown
        }
        if (hasVisibleOverlay) {
            hideInternalSettingUnderlay(container)
        } else {
            restoreInternalSettingUnderlay(container)
            removeInternalSettingLayoutRegistration(container)
        }
    }

    private fun ensureInternalSettingAttachListener(overlay: View) {
        val listener = synchronized(overlayStateLock) {
            if (internalSettingAttachListeners.containsKey(overlay)) {
                null
            } else {
                object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(view: View) {
                        reconcileInternalSettingOverlay(view)
                    }

                    override fun onViewDetachedFromWindow(view: View) {
                        reconcileInternalSettingOverlay(view)
                    }
                }.also { internalSettingAttachListeners[overlay] = it }
            }
        } ?: return
        overlay.addOnAttachStateChangeListener(listener)
    }

    private fun ensureInternalSettingLayoutRegistration(container: ViewGroup) {
        // Keep this instance-scoped so host method obfuscation cannot affect close detection.
        val observer = container.viewTreeObserver
        if (!observer.isAlive) return
        var previousRegistration: WeTypeGlobalLayoutRegistration? = null
        val registration = synchronized(overlayStateLock) {
            internalSettingLayoutRegistrations[container]?.let { current ->
                if (current.observer.get() === observer) return@synchronized null
                previousRegistration = current
            }
            val containerReference = WeakReference(container)
            val listener = ViewTreeObserver.OnGlobalLayoutListener {
                containerReference.get()?.let(::syncInternalSettingUnderlay)
            }
            WeTypeGlobalLayoutRegistration(WeakReference(observer), listener).also {
                internalSettingLayoutRegistrations[container] = it
            }
        } ?: return
        previousRegistration?.let { previous ->
            previous.observer.get()?.takeIf { observer -> observer.isAlive }
                ?.removeOnGlobalLayoutListener(previous.listener)
        }
        observer.addOnGlobalLayoutListener(registration.listener)
    }

    private fun removeInternalSettingLayoutRegistration(container: ViewGroup) {
        val registration = synchronized(overlayStateLock) {
            internalSettingLayoutRegistrations.remove(container)
        } ?: return
        registration.observer.get()?.takeIf { observer -> observer.isAlive }
            ?.removeOnGlobalLayoutListener(registration.listener)
    }

    private fun removeAllInternalSettingLayoutRegistrations() {
        val containers = synchronized(overlayStateLock) {
            internalSettingLayoutRegistrations.keys.toList()
        }
        containers.forEach(::removeInternalSettingLayoutRegistration)
    }

    private fun restoreInternalSettingUnderlay(container: ViewGroup) {
        val originals = synchronized(overlayStateLock) {
            internalSettingUnderlayOriginalVisibilities.remove(container)
                ?.entries
                ?.toList()
                .orEmpty()
        }
        originals.forEach { (underlay, visibility) ->
            if (underlay.visibility == View.INVISIBLE) {
                setOverlayManagedVisibility(underlay, visibility)
            }
        }
    }

    private fun restoreAllInternalSettingUnderlays(clearTrackedOverlays: Boolean) {
        removeAllInternalSettingLayoutRegistrations()
        val containers = synchronized(overlayStateLock) {
            internalSettingUnderlayOriginalVisibilities.keys.toList()
        }
        containers.forEach(::restoreInternalSettingUnderlay)
        if (clearTrackedOverlays) {
            val listeners = synchronized(overlayStateLock) {
                internalSettingAttachListeners.entries.map { (view, listener) -> view to listener }
                    .also { internalSettingAttachListeners.clear() }
            }
            listeners.forEach { (view, listener) ->
                view.removeOnAttachStateChangeListener(listener)
            }
            synchronized(overlayStateLock) {
                internalSettingContainers.clear()
                internalSettingContainerByOverlay.clear()
                internalSettingSyncPosted.clear()
            }
        }
    }

    private fun resolveInternalSettingContainer(overlay: View): ViewGroup? {
        val attachedContainer = (overlay.parent as? ViewGroup)?.takeIf {
            hasAncestorClass(overlay, WETYPE_SETTINGS_KEYBOARD_CLASS_NAME)
        }
        if (attachedContainer != null) {
            synchronized(overlayStateLock) {
                internalSettingContainerByOverlay[overlay] = WeakReference(attachedContainer)
            }
            return attachedContainer
        }
        return synchronized(overlayStateLock) {
            internalSettingContainerByOverlay[overlay]?.get()
        }
    }

    private fun containsDescendantClass(root: View, className: String): Boolean {
        if (root.javaClass.name == className) return true
        if (root !is ViewGroup) return false
        repeat(root.childCount) { index ->
            if (containsDescendantClass(root.getChildAt(index), className)) return true
        }
        return false
    }

    private fun hasAncestorClass(view: View, className: String): Boolean {
        var current = view.parent as? View
        while (current != null) {
            if (current.javaClass.name == className) return true
            current = current.parent as? View
        }
        return false
    }

    private fun isWeTypeKeyboardRoot(view: View): Boolean =
        weTypeKeyboardBaseClasses.any { keyboardBase -> keyboardBase.isInstance(view) }

    private fun isWeTypeCandidateView(view: View): Boolean =
        weTypeCandidateViewClass?.isInstance(view) == true

    private fun findKeyboardBaseClass(overlayClass: Class<*>): Class<*> {
        var candidate = overlayClass
        while (true) {
            val superclass = candidate.superclass ?: break
            val superclassHasKeyboardType = superclass.methods.any { method ->
                method.name == "getKeyboardType" && method.parameterCount == 0
            }
            if (!View::class.java.isAssignableFrom(superclass) || !superclassHasKeyboardType) break
            candidate = superclass
        }
        check(
            View::class.java.isAssignableFrom(candidate) &&
                candidate != View::class.java &&
                candidate != ViewGroup::class.java &&
                candidate.methods.any { method ->
                    method.name == "getKeyboardType" && method.parameterCount == 0
                }
        ) { "Invalid WeType keyboard base: ${candidate.name}" }
        return candidate
    }

    private fun findOverlayKeyboardContainer(view: View): ViewGroup? {
        val overlayHost = view.parent as? ViewGroup ?: return null
        return overlayHost.parent as? ViewGroup
    }

    private fun isDescendantOf(view: View, ancestor: ViewGroup): Boolean {
        var current: View? = view
        while (current != null) {
            if (current === ancestor) return true
            current = current.parent as? View
        }
        return false
    }

    fun hookWindowBlur() {
        runCatching {
            val inputMethodService = loadClassOrNull("android.inputmethodservice.InputMethodService")
                ?: error("Failed to load InputMethodService")

            inputMethodService.getMethod(
                "onStartInputView",
                EditorInfo::class.java,
                Boolean::class.javaPrimitiveType
            ).hookAfter { param ->
                onWindowStage(param.thisObject, "onStartInputView")
                reconcileCurrentResourceViews(param.thisObject)
            }
            runCatching {
                inputMethodService.getMethod("onWindowShown").hookAfter { param ->
                    onWindowStage(param.thisObject, "onWindowShown")
                    reconcileCurrentResourceViews(param.thisObject)
                }
            }
            runCatching {
                inputMethodService.getMethod("updateFullscreenMode").hookAfter { param ->
                    onWindowStage(param.thisObject, "updateFullscreenMode")
                }
            }
            // Observe the final host result, including its normal/floating/hardware proxy.
            val insetService = loadClassOrNull("com.tencent.wetype.plugin.hld.WxHldService")
                ?: inputMethodService
            insetService.getMethod(
                "onComputeInsets",
                InputMethodService.Insets::class.java
            ).hookAfter { param ->
                onComputeInsets(param.thisObject, param.args.getOrNull(0) as? InputMethodService.Insets)
            }
            runCatching {
                inputMethodService.getMethod("onWindowHidden").hookAfter { param ->
                    onWindowInactive(param.thisObject, removeCarrier = false)
                }
            }
            runCatching {
                inputMethodService.getMethod("hideWindow").hookAfter { param ->
                    onWindowInactive(param.thisObject, removeCarrier = false)
                }
            }
            runCatching {
                inputMethodService.getMethod("onDestroy").hookAfter { param ->
                    onWindowInactive(param.thisObject, removeCarrier = true)
                }
            }
            Log.i("Success: Hook WeType window blur")
        }.onFailure {
            Log.i("Failed: Hook WeType window blur")
            Log.i(it)
        }
    }

    fun reconcileCurrentInputMethodService(inputMethodService: InputMethodService) {
        // Hidden windows are initialized by their next onWindowShown callback.
        if (!inputMethodService.isInputViewShown) return
        val state = getWindowState(inputMethodService)
        state.windowVisible = true
        state.computedVisibleImeHeightPx = null
        scheduleWindowBlur(inputMethodService)
        reconcileCurrentResourceViews(inputMethodService)
    }

    private fun reconcileCurrentResourceViews(inputMethodService: Any) {
        val decorView = resolveInputMethodDecorView(inputMethodService) ?: return
        val state = getWindowState(inputMethodService)
        if (state.resourceReconcilePending) return
        state.resourceReconcilePending = true
        // Both lifecycle callbacks can run in the same turn. Reconcile once after the
        // host has finished installing its views; newly bound logos have their own hooks.
        if (!HookEnvironment.postTracked(decorView) {
                state.resourceReconcilePending = false
                if (state.windowVisible) {
                    WeTypeResourceHooks.reconcileCurrentKeyboardLogos(listOf(decorView))
                }
            }) {
            state.resourceReconcilePending = false
        }
    }

    private fun resolveInputMethodDecorView(inputMethodService: Any): View? = runCatching {
        (inputMethodService as? InputMethodService)?.window?.window?.decorView
    }.getOrNull()

    private fun onComputeInsets(inputMethodService: Any, insets: InputMethodService.Insets?) {
        runCatching {
            val state = getWindowState(inputMethodService)
            val window = (inputMethodService as? InputMethodService)?.window?.window ?: return@runCatching
            val rootHeight = window.decorView.rootView?.height?.takeIf { it > 0 }
                ?: window.decorView.height.takeIf { it > 0 }
                ?: return@runCatching
            val visibleTopInsets = insets?.visibleTopInsets ?: return@runCatching
            val visibleImeHeight = (rootHeight - visibleTopInsets).coerceAtLeast(0)
            val previousVisibleImeHeight = state.computedVisibleImeHeightPx
            state.computedVisibleImeHeightPx = visibleImeHeight

            if (!state.windowVisible) return@runCatching
            if (visibleImeHeight <= WETYPE_COLLAPSED_IME_HEIGHT_THRESHOLD_PX) {
                hideBackgroundCarrier(state)
                return@runCatching
            }
            if (visibleImeHeight == previousVisibleImeHeight) return@runCatching
            scheduleWindowBlur(inputMethodService, refreshStyle = false)
        }.onFailure {
            Log.i("Failed: Track WeType visible IME height")
            Log.i(it)
        }
    }

    private fun onWindowStage(inputMethodService: Any, stage: String) {
        runCatching {
            val state = getWindowState(inputMethodService)
            when (stage) {
                "onStartInputView" -> {
                    state.computedVisibleImeHeightPx = null
                }
                "onWindowShown" -> {
                    state.windowVisible = true
                    state.computedVisibleImeHeightPx = null
                }
                "updateFullscreenMode" -> {
                    if (!state.windowVisible) return@runCatching
                }
            }

            scheduleWindowBlur(inputMethodService)
        }.onFailure {
            Log.i("Failed: Handle WeType window stage")
            Log.i(it)
        }
    }

    private fun scheduleWindowBlur(inputMethodService: Any, refreshStyle: Boolean = true) {
        val state = getWindowState(inputMethodService)
        if (!state.windowVisible) return
        val window = (inputMethodService as? InputMethodService)?.window?.window ?: return
        val decorView = window.decorView
        val observer = decorView.viewTreeObserver
        if (!observer.isAlive) return
        state.window = WeakReference(window)
        if (state.stopMaterialObserver == null) {
            val serviceReference = WeakReference(inputMethodService)
            state.stopMaterialObserver = WeTypeSystemMaterials.observeAvailability(decorView.context) {
                serviceReference.get()?.let { scheduleWindowBlur(it) }
            }
        }
        val updateAlreadyPending = state.backgroundUpdatePending
        state.backgroundUpdatePending = true
        state.backgroundStyleDirty = state.backgroundStyleDirty || refreshStyle
        if (state.backgroundObserver?.get() === observer) {
            if (!updateAlreadyPending && refreshStyle) decorView.invalidate()
            return
        }
        removeBackgroundListeners(state)
        state.backgroundUpdatePending = true

        val layoutListener = ViewTreeObserver.OnGlobalLayoutListener {
            state.backgroundUpdatePending = true
        }
        val serviceReference = WeakReference(inputMethodService)
        val preDrawListener = ViewTreeObserver.OnPreDrawListener {
            if (!state.windowVisible || !state.backgroundUpdatePending) {
                true
            } else {
                // A new layout/inset/lifecycle event will re-arm this work. An invalid
                // snapshot must not turn an unrelated animation into a per-frame retry loop.
                state.backgroundUpdatePending = false
                runCatching {
                    val service = serviceReference.get() as? InputMethodService ?: return@runCatching true
                    val context: Context = service
                    val window = service.window?.window ?: return@runCatching true
                    val latestDecorView = window.decorView
                    if (shouldHideBackground(latestDecorView, state)) {
                        hideBackgroundCarrier(state)
                        return@runCatching true
                    }
                    val bounds = collectBackgroundBounds(service, latestDecorView, state.locationBuffer)
                    if (bounds == null) {
                        // Never display a stale/full-window estimate while the host relayouts.
                        hideBackgroundCarrier(state)
                        return@runCatching true
                    }
                    applyBackgroundCarrier(window, latestDecorView, context, state, bounds)
                    true
                }.getOrElse {
                    Log.i("Failed: Apply WeType background before drawing")
                    Log.i(it)
                    true
                }
            }
        }
        state.backgroundDecorView = WeakReference(decorView)
        state.backgroundObserver = WeakReference(observer)
        state.backgroundLayoutListener = layoutListener
        state.backgroundPreDrawListener = preDrawListener
        observer.addOnGlobalLayoutListener(layoutListener)
        observer.addOnPreDrawListener(preDrawListener)
        decorView.invalidate()
    }

    private fun removeBackgroundListeners(state: WeTypeWindowState) {
        // Attaching a window can merge its floating observer into a new live observer.
        listOfNotNull(
            state.backgroundObserver?.get(),
            state.backgroundDecorView?.get()?.viewTreeObserver
        ).distinct().filter { it.isAlive }.forEach { observer ->
            state.backgroundLayoutListener?.let(observer::removeOnGlobalLayoutListener)
            state.backgroundPreDrawListener?.let(observer::removeOnPreDrawListener)
        }
        state.backgroundDecorView = null
        state.backgroundObserver = null
        state.backgroundLayoutListener = null
        state.backgroundPreDrawListener = null
        state.backgroundUpdatePending = false
    }

    private fun getWindowState(inputMethodService: Any): WeTypeWindowState =
        synchronized(weTypeWindowStates) {
            weTypeWindowStates.getOrPut(inputMethodService) { WeTypeWindowState() }
        }

    private fun onWindowInactive(inputMethodService: Any, removeCarrier: Boolean) {
        runCatching {
            val state = getWindowState(inputMethodService)
            state.windowVisible = false
            state.stopMaterialObserver?.invoke()
            state.stopMaterialObserver = null
            state.computedVisibleImeHeightPx = null
            removeBackgroundListeners(state)
            hideBackgroundCarrier(state)
            if (removeCarrier) {
                removeBackgroundCarrier(state)
                synchronized(weTypeWindowStates) {
                    weTypeWindowStates.remove(inputMethodService)
                }
            }
        }.onFailure {
            Log.i("Failed: Cleanup WeType window background")
            Log.i(it)
        }
    }

    private fun resolveCornerRadii(targetView: View, context: Context, state: WeTypeWindowState, cornerRadiusDp: Int): WeTypeCornerRadii {
        val topRadius = android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_DIP,
            cornerRadiusDp.toFloat(),
            context.resources.displayMetrics
        )
        val insets = targetView.rootWindowInsets
        if (insets != null) {
            state.bottomLeftHardwareCornerRadius = insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT)?.radius?.toFloat()
            state.bottomRightHardwareCornerRadius = insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT)?.radius?.toFloat()
        }
        return WeTypeCornerRadii(
            topLeft = topRadius,
            topRight = topRadius,
            bottomRight = state.bottomRightHardwareCornerRadius ?: topRadius,
            bottomLeft = state.bottomLeftHardwareCornerRadius ?: topRadius
        )
    }

    private fun collectBackgroundBounds(
        inputMethodService: Any,
        decorView: View,
        location: IntArray
    ): WeTypeBackgroundBounds? {
        val contentViews = listOfNotNull(
            readViewField(inputMethodService, "mCandidatesFrame"),
            readViewField(inputMethodService, "mInputFrame"),
            runCatching { inputMethodService.invokeMethodAs<View>("getInputView") }.getOrNull()
        )
        return resolveWeTypeBackgroundBounds(
            decorView.toBackgroundLayout(location),
            contentViews.map { it.toBackgroundLayout(location) }
        )
    }

    private fun readViewField(inputMethodService: Any, fieldName: String): View? =
        runCatching { inputMethodService.getObjectAs<View>(fieldName) }.getOrNull()

    private fun View.toBackgroundLayout(location: IntArray): WeTypeBackgroundLayout {
        getLocationInWindow(location)
        return WeTypeBackgroundLayout(
            windowTop = location[1],
            height = height,
            isShown = isShown,
            isLaidOut = isAttachedToWindow && isLaidOut,
            isLayoutRequested = isLayoutRequested
        )
    }

    private fun applyBackgroundCarrier(
        window: Window,
        decorView: View,
        context: Context,
        state: WeTypeWindowState,
        bounds: WeTypeBackgroundBounds
    ) {
        val decorGroup = decorView as? ViewGroup ?: return
        val backgroundHeight = bounds.height
        val settings = WeTypeSettings.readSnapshotXposed()
        val cornerRadii = resolveCornerRadii(decorView, context, state, settings.cornerRadius)
        if (backgroundHeight < cornerRadii.maxRadius()) {
            hideBackgroundCarrier(state)
            return
        }
        if (!state.originalWindowStateCaptured) {
            state.originalWindowBackground = decorView.background
            state.originalWindowBlurRadius = runCatching {
                Window::class.java.getMethod("getBackgroundBlurRadius").invoke(window) as? Int
            }.getOrNull()
            state.originalWindowStateCaptured = true
        }
        if (state.backgroundStyleDirty) {
            val transparent = state.transparentWindowBackground
                ?: Color.TRANSPARENT.toDrawable().also { state.transparentWindowBackground = it }
            if (decorView.background !== transparent) {
                window.setBackgroundBlurRadius(0)
                window.setBackgroundDrawable(transparent)
            }
        }

        val overrides = if (settings.hyperMaterialEnabled && WeTypeSystemMaterials.areGlassOverridesAvailable()) {
            settings.glassOverrides
        } else GlassMaterialOverrides()
        val materialEnabled = settings.hyperMaterialEnabled
        val carrier = ensureBackgroundCarrier(context, decorGroup, state, overrides)
        carrier.visibility = View.VISIBLE
        val style = BackgroundStyle(
            color = if (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES) settings.darkColor else settings.lightColor,
            blurRadius = settings.blurRadius,
            edgeHighlightEnabled = settings.edgeHighlightEnabled,
            edgeHighlightIntensity = settings.edgeHighlightIntensity,
            cornerRadii = cornerRadii,
            nightMode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK,
            density = context.resources.displayMetrics.density,
            hyperMaterialEnabled = materialEnabled,
            hyperMaterialAvailable = WeTypeSystemMaterials.isAvailable(context)
        )
        val viewRoot = if (state.backgroundStyleDirty || carrier.background == null) {
            runCatching { carrier.invokeMethodAs<Any>("getViewRootImpl") }.getOrNull()
        } else {
            state.backgroundViewRoot
        }
        if (carrier.background == null || state.backgroundStyle != style || state.backgroundViewRoot !== viewRoot) {
            applyContinuousCornerOutline(carrier, cornerRadii)
            val material = checkNotNull(state.hyperMaterial)
            if (style.hyperMaterialEnabled) {
                // Remove the old blur/bloom drawable before enabling the system material.
                carrier.background = Color.TRANSPARENT.toDrawable()
                if (!material.apply(style.nightMode == Configuration.UI_MODE_NIGHT_YES, style.color)) {
                    // An invocation failure keeps the keyboard legible without custom effects.
                    carrier.background = createTintDrawable(WeTypeSystemMaterials.fallbackColor(style.nightMode == Configuration.UI_MODE_NIGHT_YES), cornerRadii)
                    carrier.foreground = null
                } else {
                    // ColorOS 的原生模糊只画背景，不像 HyperOS 材质自带面板光影；
                    // 这里把模块既有的边缘高光叠到模糊之上，还原小布面板的描边观感。
                    carrier.foreground = if (style.edgeHighlightEnabled && WeTypeSystemMaterials.isColorOsBackend()) {
                        WeTypeBloomStrokeDrawable(
                            context = context,
                            cornerRadii = cornerRadii,
                            surfaceColor = style.color,
                            intensityScale = style.edgeHighlightIntensity / 100f
                        )
                    } else null
                }
            } else {
                material.clear()
                carrier.foreground = null
                carrier.background = createBackgroundDrawable(carrier, context, style)
            }
            state.backgroundStyle = style
            state.backgroundViewRoot = viewRoot
        }
        state.backgroundStyleDirty = false
        // This decorative child keeps a zero-height layout spec. Its rendered bounds must
        // not feed back into the IME's measurement or the app-facing inset calculation.
        if (carrier.width != decorView.width || carrier.height != backgroundHeight || carrier.top != bounds.top) {
            carrier.measure(
                View.MeasureSpec.makeMeasureSpec(decorView.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(backgroundHeight, View.MeasureSpec.EXACTLY)
            )
            carrier.layout(0, bounds.top, decorView.width, bounds.top + backgroundHeight)
            carrier.invalidateOutline()
        }
        if (style.hyperMaterialEnabled) state.hyperMaterial?.updateGeometry(cornerRadii)
        applyNavigationBarAppearance(window, state, style)
    }

    /**
     * 让系统底栏那两个图标（收起 / 地球）跟着输入法背景反色。
     *
     * ## 为什么必须由模块来声明
     *
     * IME 窗口铺满全屏、带 `FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS`，在
     * `DisplayPolicy.chooseNavigationColorWindowLw` 里压过底下的宿主 App，成为导航栏外观的
     * 权威来源。微信输入法从不写 `insetsFlags.appearance`，于是框架读到 0、把
     * `APPEARANCE_LIGHT_NAVIGATION_BARS` 清掉，浅色键盘上图标仍是白色——看起来就是"没做
     * 反色"。模块替它把这个位写对，不改宿主 APK，也不碰系统侧。
     *
     * ## 判据
     *
     * 优先按真实生效的背景色算亮度；系统材质（hyper material）的观感来自合成器采样，推不出
     * 亮度，半透明色同理，这两种情况退回系统深浅模式。
     */
    private fun applyNavigationBarAppearance(
        window: Window,
        state: WeTypeWindowState,
        style: BackgroundStyle
    ) {
        // 带透明度的色值是"叠加色"，实际观感取决于底下透出什么；系统材质的观感来自合成器
        // 采样。两者都推不出亮度，退回系统深浅模式，而不是拿半透明色硬算。
        val colorIsUsable = !style.hyperMaterialEnabled &&
            Color.alpha(style.color) >= NAV_BAR_OPAQUE_ALPHA_THRESHOLD
        // 该位表达的是"底栏是浅色的，请画深色图标"，不是一个"图标要浅色"的开关。
        val lightNavBar = if (colorIsUsable) {
            relativeLuminance(style.color) >= NAV_BAR_LUMINANCE_THRESHOLD
        } else {
            style.nightMode != Configuration.UI_MODE_NIGHT_YES
        }
        val controller = runCatching { window.insetsController }.getOrNull() ?: return
        val appearance = if (lightNavBar) APPEARANCE_LIGHT_NAVIGATION_BARS else 0
        if (!state.navBarAppearanceCaptured) {
            val current = runCatching { controller.systemBarsAppearance }.getOrDefault(0)
            // 已经是目标值就没什么可记账的：契约要求恢复原值，而原值本来就是对的。
            if (current and APPEARANCE_LIGHT_NAVIGATION_BARS == appearance) return
            state.originalNavBarAppearance = current
            state.navBarAppearanceCaptured = true
        } else if (controller.systemBarsAppearance and APPEARANCE_LIGHT_NAVIGATION_BARS == appearance) {
            return
        }
        runCatching {
            controller.setSystemBarsAppearance(appearance, APPEARANCE_LIGHT_NAVIGATION_BARS)
        }
    }

    /**
     * 把导航栏外观位交还宿主。
     *
     * 与 [restoreWindowState] 同一套纪律：自清标记，可重复调用。
     */
    private fun restoreNavigationBarAppearance(state: WeTypeWindowState) {
        if (!state.navBarAppearanceCaptured) return
        val window = state.window?.get() ?: return
        val controller = runCatching { window.insetsController }.getOrNull() ?: return
        runCatching {
            controller.setSystemBarsAppearance(
                state.originalNavBarAppearance and APPEARANCE_LIGHT_NAVIGATION_BARS,
                APPEARANCE_LIGHT_NAVIGATION_BARS
            )
        }
        state.navBarAppearanceCaptured = false
        state.originalNavBarAppearance = 0
    }

    private fun relativeLuminance(color: Int): Float =
        (Color.red(color) * 0.299f + Color.green(color) * 0.587f + Color.blue(color) * 0.114f) / 255f

    private fun ensureBackgroundCarrier(
        context: Context,
        decorGroup: ViewGroup,
        state: WeTypeWindowState,
        overrides: GlassMaterialOverrides
    ): View {
        val existing = state.backgroundCarrier?.takeIf { it.parent === decorGroup && state.carrierOverrides == overrides }
        if (existing != null) return existing

        state.backgroundCarrier?.let { oldCarrier ->
            state.hyperMaterial?.clear()
            (oldCarrier.parent as? ViewGroup)?.removeView(oldCarrier)
            state.backgroundStyle = null
            state.backgroundViewRoot = null
        }
        val carrier = FrameLayout(context).apply {
            visibility = View.INVISIBLE
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        decorGroup.addView(
            carrier,
            0,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0)
        )
        state.backgroundCarrier = carrier
        // A fresh RenderNode restores actual ROM defaults when an override is cleared.
        state.carrierOverrides = overrides
        state.hyperMaterial = WeTypeSystemMaterials.create(carrier, overrides)
        return carrier
    }

    private fun shouldHideBackground(
        decorView: View,
        state: WeTypeWindowState
    ): Boolean {
        val collapsedByInsets = state.computedVisibleImeHeightPx
            ?.let { it <= WETYPE_COLLAPSED_IME_HEIGHT_THRESHOLD_PX } == true
        if (collapsedByInsets) return true

        // The input view is already a descendant of decor; do not scan it twice.
        return containsWeTypeHardwareView(decorView, state)
    }

    private fun containsWeTypeHardwareView(view: View, state: WeTypeWindowState): Boolean {
        if (view.visibility != View.VISIBLE) return false
        val className = view.javaClass.name
        if (className.startsWith(WETYPE_HARDWARE_VIEW_CLASS_PREFIX)) return true

        val hardwareViewIds = state.hardwareViewIds ?: resolveHardwareViewIds(view.context)
            .also { state.hardwareViewIds = it }
        if (view.id != View.NO_ID && hardwareViewIds.contains(view.id)) return true

        val group = view as? ViewGroup ?: return false
        for (index in 0 until group.childCount) {
            if (containsWeTypeHardwareView(group.getChildAt(index), state)) return true
        }
        return false
    }

    private fun resolveHardwareViewIds(context: Context): IntArray =
        WETYPE_HARDWARE_VIEW_ID_NAMES.mapNotNull { name ->
            context.resources.getIdentifier(name, "id", context.packageName)
                .takeIf { it != 0 }
        }.toIntArray()

    private fun hideBackgroundCarrier(state: WeTypeWindowState) {
        // 键盘收起时 IME 窗口在 WM 眼里仍然 visible，依旧是导航栏外观的权威来源。不在这里
        // 交还，桌面和宿主 App 的底栏图标会被输入法的残留值继续按着。
        restoreNavigationBarAppearance(state)
        val carrier = state.backgroundCarrier ?: return
        carrier.visibility = View.INVISIBLE
        state.hyperMaterial?.clear()
        state.backgroundStyle = null
    }

    private fun removeBackgroundCarrier(state: WeTypeWindowState) {
        state.stopMaterialObserver?.invoke()
        state.stopMaterialObserver = null
        state.hyperMaterial?.clear()
        state.hyperMaterial = null
        // 必须在置空 state.window 之前交还：restoreNavigationBarAppearance 依赖它取窗口。
        restoreNavigationBarAppearance(state)
        val carrier = state.backgroundCarrier ?: return
        (carrier.parent as? ViewGroup)?.removeView(carrier)
        state.backgroundCarrier = null
        state.backgroundStyle = null
        state.backgroundViewRoot = null
        state.window = null
    }

    private fun restoreWindowState(state: WeTypeWindowState) {
        restoreNavigationBarAppearance(state)
        if (!state.originalWindowStateCaptured) return
        val window = state.window?.get() ?: return
        runCatching {
            window.setBackgroundBlurRadius(state.originalWindowBlurRadius ?: 0)
            window.setBackgroundDrawable(state.originalWindowBackground)
        }
        state.originalWindowStateCaptured = false
        state.originalWindowBackground = null
        state.originalWindowBlurRadius = null
    }

    private fun createBackgroundDrawable(targetView: View, context: Context, style: BackgroundStyle): Drawable {
        val (color, blurRadius, edgeHighlightEnabled, edgeHighlightIntensity, cornerRadii) = style
        val tintDrawable = createTintDrawable(color, cornerRadii)
        val blurDrawable = createInternalBackgroundBlurDrawable(targetView, blurRadius, cornerRadii)
        val layers = buildList {
            blurDrawable?.also(::add)
            add(tintDrawable)
            if (edgeHighlightEnabled) {
                add(
                    WeTypeBloomStrokeDrawable(
                        context = context,
                        cornerRadii = cornerRadii,
                        surfaceColor = color,
                        intensityScale = edgeHighlightIntensity / 100f
                    )
                )
            }
        }
        return if (layers.size == 1) layers.first() else android.graphics.drawable.LayerDrawable(layers.toTypedArray())
    }

    private fun createInternalBackgroundBlurDrawable(targetView: View, blurRadius: Int, cornerRadii: WeTypeCornerRadii): Drawable? {
        val viewRootImpl = runCatching { targetView.invokeMethodAs<Any>("getViewRootImpl") }.getOrNull() ?: return null
        val blurDrawable = runCatching { viewRootImpl.invokeMethodAs<Drawable>("createBackgroundBlurDrawable") }.getOrNull() ?: return null
        runCatching { blurDrawable.javaClass.getMethod("setBlurRadius", Int::class.javaPrimitiveType).invoke(blurDrawable, blurRadius) }
        runCatching { blurDrawable.javaClass.getMethod("setColor", Int::class.javaPrimitiveType).invoke(blurDrawable, Color.TRANSPARENT) }
        runCatching {
            blurDrawable.javaClass.getMethod(
                "setCornerRadius",
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType
            ).invoke(
                blurDrawable,
                cornerRadii.topLeft,
                cornerRadii.topRight,
                cornerRadii.bottomRight,
                cornerRadii.bottomLeft
            )
        }.recoverCatching {
            blurDrawable.javaClass.getMethod("setCornerRadius", Float::class.javaPrimitiveType)
                .invoke(blurDrawable, cornerRadii.maxRadius())
        }
        return blurDrawable
    }

    private fun createTintDrawable(color: Int, cornerRadii: WeTypeCornerRadii): Drawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        this.cornerRadii = cornerRadii.toArray()
        setColor(color)
    }

    private fun applyContinuousCornerOutline(
        view: View,
        cornerRadii: WeTypeCornerRadii
    ) {
        val current = view.outlineProvider as? ContinuousCornerOutline
        if (current?.cornerRadii == cornerRadii && view.clipToOutline) return
        view.clipToOutline = true
        view.outlineProvider = ContinuousCornerOutline(cornerRadii)
        view.invalidateOutline()
    }

    private fun runOnMainThreadBlocking(block: () -> Unit): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return runCatching(block).onFailure {
                Log.i("Failed: Cleanup WeType window state for hot reload")
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
            Log.i("Failed: Cleanup WeType window state for hot reload")
            Log.i(it)
        }
        return finished && failure == null
    }
}
