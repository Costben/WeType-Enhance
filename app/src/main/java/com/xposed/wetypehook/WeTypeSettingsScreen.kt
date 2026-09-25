package com.xposed.wetypehook

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xposed.wetypehook.wetype.graphics.WeTypeSystemMaterials
import com.xposed.wetypehook.wetype.settings.WeTypeSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TabRowWithContour
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavDisplayEffects
import top.yukonga.miuix.kmp.nav.core.rememberNavSystemCornerRadius
import top.yukonga.miuix.kmp.nav.transition.NavTransitions
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.window.WindowBottomSheet

@Composable
internal fun WeTypeSettingsScreen(settingsContext: Context) {
    rememberWeTypeSettingsState(settingsContext).WeTypeSettingsScaffold()
}

@Composable
private fun WeTypeSettingsState.WeTypeSettingsScaffold() {
    DisposableEffect(preferencesContext) {
        fun refreshFromLocal() {
            val fresh = WeTypeSettings.readLocalSnapshot(preferencesContext)
            logoImageEnabled = fresh.logoImageEnabled
            logoImageType = WeTypeSettings.normalizeLogoImageType(fresh.logoImageType)
            logoSvgRecolorEnabled = fresh.logoSvgRecolorEnabled
            logoImagePngBase64 = fresh.logoImagePngBase64
            logoImageSvgText = fresh.logoImageSvgText
            logoImageName = fresh.logoImageName
            logoImageUpdatedAt = fresh.logoImageUpdatedAt
        }
        // 二级页在同一进程直接写本地偏好，回来时刷新摘要与透传值（主页面不编辑这些字段）。
        refreshFromLocal()
        val stopObserving = WeTypeSettings.observeLocalChanges(
            preferencesContext,
            SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refreshFromLocal() }
        )
        onDispose { stopObserving() }
    }
    LaunchedEffect(parsedGlassOverrides) {
        // Recreating a native material during every slider tick would flash its fallback tint.
        delay(120)
        parsedGlassOverrides?.let { previewGlassOverrides = it }
    }
    DisposableEffect(preferencesContext) {
        val stopObserving = WeTypeSystemMaterials.observeAvailability(preferencesContext) {
            hyperMaterialAvailable = !WeTypeSystemMaterials.isColorOsBackend() &&
                WeTypeSystemMaterials.isAvailable(preferencesContext)
        }
        onDispose { stopObserving() }
    }
    if (isEmbeddedHost) {
        LaunchedEffect(preferencesContext) {
            ModuleActivationTracker.syncActivationFromUiContext(preferencesContext)
        }
    } else {
        DisposableEffect(preferencesContext) {
            val listener = ModuleActivationTracker.registerStatusListener(preferencesContext) {
                activationStatus = it
            }
            onDispose {
                ModuleActivationTracker.unregisterStatusListener(preferencesContext, listener)
            }
        }
    }
    // 每次进入模块设置时检查一次更新，失败静默忽略。
    LaunchedEffect(Unit) {
        ModuleUpdateChecker.check(BuildConfig.VERSION_NAME)?.let { info ->
            updateInfo = info
            showUpdateSheet = true
        }
    }

    NavDisplay(
        backStack = navBackStack,
        modifier = Modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.background),
        transition = NavTransitions.MiuixDefault,
        effects = NavDisplayEffects(
            cornerClipRadius = rememberNavSystemCornerRadius(),
            // 这个开关是按「页面深度是不是整数」来吃触摸的：返回到一半、手指还按着的时候
            // 上下两层深度都是小数，整块屏幕都收不到触摸，松手后的回弹动画期间也一样，
            // 于是既滑不动也抓不回来。跟手返回要的就是随时能接管，所以保持库默认的关闭。
            blockInputDuringTransition = false
        )
    ) {
        entry<SettingsRoute.Home> {
            Scaffold(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MiuixTheme.colorScheme.background),
                topBar = {
                    TopAppBar(
                        title = stringResource(R.string.settings_title),
                        scrollBehavior = scrollBehavior,
                        navigationIcon = {
                            ModuleActivationTag(
                                status = activationStatus,
                            )
                        },
                        actions = {
                            SettingsKeyboardButton()
                            SettingsRefreshButton(
                                onClick = { saveSettings(restartIme = true) }
                            )
                        },
                        bottomContent = {
                            TabRowWithContour(
                                tabs = categoryTabs,
                                selectedTabIndex = categoryPagerState.currentPage,
                                onTabSelected = { index ->
                                    coroutineScope.launch {
                                        categoryPagerState.animateScrollToPage(index)
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                    )
                }
            ) { paddingValues ->
                HorizontalPager(
                    state = categoryPagerState,
                    modifier = Modifier.fillMaxSize()
                ) { page ->
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .overScrollVertical()
                            .nestedScroll(scrollBehavior.nestedScrollConnection),
                        contentPadding = PaddingValues(
                            top = paddingValues.calculateTopPadding(),
                            bottom = 40.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        when (page) {
                            0 -> {
                                AppearanceTabContent(
                                    wallpaperName = previewWallpaperName,
                                    onPickWallpaper = ::pickPreviewWallpaper,
                                    onClearWallpaper = ::clearPreviewWallpaper,
                                    onOpenSubPage = openSubPage,
                                    fontMode = fontMode,
                                    onFontModeChange = { fontMode = it },
                                    onResetFont = {
                                        fontMode = WeTypeSettings.DEFAULT_FONT_MODE
                                    }
                                )
                            }

                            1 -> {
                                GestureTabContent(
                                    qwertyGestureEnabled = qwertyGestureEnabled,
                                    onQwertyGestureEnabledChange = { qwertyGestureEnabled = it },
                                    t9GestureEnabled = t9GestureEnabled,
                                    onT9GestureEnabledChange = { t9GestureEnabled = it },
                                    gestureVibration = gestureVibration,
                                    onGestureVibrationChange = { gestureVibration = it },
                                    t9GestureVibration = t9GestureVibration,
                                    onT9GestureVibrationChange = { t9GestureVibration = it },
                                    gestureThreshold = gestureThreshold,
                                    onGestureThresholdChange = { gestureThreshold = it },
                                    t9GestureThreshold = t9GestureThreshold,
                                    onT9GestureThresholdChange = { t9GestureThreshold = it },
                                    showGestureKeyLabels = showGestureKeyLabels,
                                    onShowGestureKeyLabelsChange = { showGestureKeyLabels = it },
                                    gestureLabelTextSizeSp = gestureLabelTextSizeSp,
                                    onGestureLabelTextSizeSpChange = { gestureLabelTextSizeSp = it },
                                    gestureLabelAlpha = gestureLabelAlpha,
                                    onGestureLabelAlphaChange = { gestureLabelAlpha = it },
                                    gestureLabelPosition = gestureLabelPosition,
                                    onGestureLabelPositionChange = { gestureLabelPosition = it },
                                    gestureLabelMarginTopDp = gestureLabelMarginTopDp,
                                    onGestureLabelMarginTopDpChange = { gestureLabelMarginTopDp = it },
                                    gestureLabelMarginBottomDp = gestureLabelMarginBottomDp,
                                    onGestureLabelMarginBottomDpChange = { gestureLabelMarginBottomDp = it },
                                    gestureLabelMarginLeftDp = gestureLabelMarginLeftDp,
                                    onGestureLabelMarginLeftDpChange = { gestureLabelMarginLeftDp = it },
                                    gestureLabelMarginRightDp = gestureLabelMarginRightDp,
                                    onGestureLabelMarginRightDpChange = { gestureLabelMarginRightDp = it },
                                    gestureBindingsJson = gestureBindingsJson,
                                    onGestureBindingsJsonChange = { gestureBindingsJson = it }
                                )
                            }

                            2 -> {
                                FeatureTabContent(
                                    onOpenSubPage = openSubPage,
                                    disableHotUpdate = disableHotUpdate,
                                    onDisableHotUpdateChange = { disableHotUpdate = it },
                                    activationStatus = activationStatus,
                                    onRestoreDefaults = ::restoreDefaults
                                )
                            }
                        }
                    }
                }
            }
        }

        entry<SettingsRoute.SubPage> { route ->
            val subPage = route.page
            val pinnedPreview: (@Composable () -> Unit)? =
                if (appearancePreviewPinned && subPage in APPEARANCE_PREVIEW_SUB_PAGES) {
                    {
                        renderAppearancePreview(
                            subPage = subPage,
                            pinned = true,
                            onTogglePin = { appearancePreviewPinned = false }
                        )
                    }
                } else {
                    null
                }
            // 底部键盘复刻件：只在「界面美化」二级页、且预览开关打开时挂。
            val replicaStage: (@Composable () -> Unit)?
            val replicaBottomInset: Dp
            if (subPage in APPEARANCE_PREVIEW_SUB_PAGES && keyboardPreviewEnabled) {
                val replicaDensity = LocalDensity.current
                val replicaView = LocalView.current
                val replicaConfiguration = LocalConfiguration.current
                var navBarInsetPx by remember(replicaView) {
                    mutableIntStateOf(resolveNavigationBarInsetPx(replicaView))
                }
                LaunchedEffect(replicaView) {
                    // 首次组合时窗口 insets 可能还没下发，等一帧补读一次。
                    if (navBarInsetPx == 0) {
                        delay(120L)
                        navBarInsetPx = resolveNavigationBarInsetPx(replicaView)
                    }
                }
                val replicaWidthPx = with(replicaDensity) {
                    replicaConfiguration.screenWidthDp.dp.roundToPx()
                }
                val screenHeightPx = with(replicaDensity) {
                    replicaConfiguration.screenHeightDp.dp.roundToPx()
                }
                // 宿主实测过键盘高度就用实测值；没测过按屏幕高的固定比例兜底。
                val replicaTotalHeightPx = remember(screenHeightPx) {
                    WeTypeKeyboardMetrics.keyboardHeightForWindow(settingsContext, screenHeightPx)
                        ?: resolveReplicaFallbackHeightPx(screenHeightPx)
                }
                val replicaGeometry = resolveReplicaGeometry(
                    widthPx = replicaWidthPx,
                    totalHeightPx = replicaTotalHeightPx,
                    systemInsetPx = navBarInsetPx
                )
                // 工具栏那一排由复刻件自己画（圆形底 + 七个图标字形），不再读宿主那一行的快照。
                val toolbarLogo = remember(
                    logoColorMode,
                    logoCustomColorInput,
                    toolbarIconBgOpacity,
                    logoImageEnabled,
                    logoImageType,
                    logoSvgRecolorEnabled,
                    logoImagePngBase64,
                    logoImageSvgText,
                    currentModeIsDark
                ) {
                    renderPreviewBitmap(
                        logoColorMode = logoColorMode,
                        brandColor = appearanceGroupColors.getOrNull(groupIndex("theme_color"))
                            ?: WeTypeSettings.DEFAULT_LOGO_CUSTOM_COLOR,
                        logoCustomColor = parseLogoCustomColor(logoCustomColorInput),
                        toolbarIconBgOpacity = toolbarIconBgOpacity,
                        enabled = logoImageEnabled,
                        imageType = logoImageType,
                        recolor = logoSvgRecolorEnabled,
                        pngBase64 = logoImagePngBase64,
                        svgText = logoImageSvgText,
                        isNight = currentModeIsDark
                    )
                }
                // 总开关关掉时真机显示的是宿主原生 Logo，预览用同一个矢量回退路径以品牌强调色画一遍。
                val nativeLogo = remember(
                    toolbarIconBgOpacity,
                    currentModeIsDark,
                    appearanceGroupColors.getOrNull(groupIndex("theme_color"))
                ) {
                    renderNativePreviewBitmap(
                        brandColor = appearanceGroupColors.getOrNull(groupIndex("theme_color"))
                            ?: WeTypeSettings.DEFAULT_LOGO_CUSTOM_COLOR,
                        toolbarIconBgOpacity = toolbarIconBgOpacity,
                        isNight = currentModeIsDark
                    )
                }
                val logoMode = replicaLogoMode(
                    logoEnabled = logoEnabled,
                    logoShowEnabled = logoShowEnabled
                )
                // 预览底图：换一次图要重读一次，所以拿文件名当键。
                val previewWallpaper = remember(previewWallpaperName) {
                    PreviewWallpaper.load(settingsContext)
                }
                if (replicaGeometry != null) {
                    replicaStage = {
                        KeyboardReplica(
                            geometry = replicaGeometry,
                            color = currentColor(),
                            blurRadius = blurRadius,
                            cornerRadius = cornerRadius,
                            bottomCornerRadius = bottomCornerRadius,
                            keyCornerRadius = keyCornerRadius,
                            candidateBackgroundCorner = candidateBackgroundCorner.toFloat(),
                            candidateBackgroundAlpha = candidateBackgroundAlpha,
                            candidateBackgroundLeftMarginDp = candidateBackgroundLeftMarginDp,
                            edgeHighlightEnabled = edgeHighlightEnabled,
                            edgeHighlightIntensity = edgeHighlightIntensity,
                            // 图标与按键光感各由自己的开关门控，都挂在「光感设置」总开关之下。
                            iconEdgeLight = ReplicaEdgeLight(
                                enabled = edgeHighlightEnabled && iconEdgeLightEnabled,
                                angleDegrees = edgeLightAngle,
                                widthDp = edgeLightWidth,
                                intensity = edgeHighlightIntensity,
                                glow = glowIntensity
                            ),
                            keyEdgeLight = ReplicaEdgeLight(
                                enabled = edgeHighlightEnabled && keyEdgeLightEnabled,
                                angleDegrees = edgeLightAngle,
                                widthDp = edgeLightWidth,
                                intensity = edgeHighlightIntensity,
                                glow = glowIntensity
                            ),
                            keyColor = keyColorValue(currentModeIsDark),
                            isDark = currentModeIsDark,
                            systemMaterialEnabled = systemMaterialEnabled,
                            hyperMaterialEnabled = hyperMaterialEnabled,
                            nativeEdgeLightEnabled = nativeEdgeLightEnabled,
                            showCornerGuide = subPage == SettingsSubPage.CORNER_BLUR,
                            accentColor = appearanceGroupColors.getOrNull(groupIndex("theme_color"))
                                ?: WeTypeSettings.DEFAULT_LOGO_CUSTOM_COLOR,
                            toolbarLogo = toolbarLogo,
                            nativeLogo = nativeLogo,
                            logoMode = logoMode,
                            wallpaper = previewWallpaper,
                            // 复刻件里点「收起键盘」跟真机一个效果：键盘没了。这里是预览，
                            // 收起来就是把底部的复刻件撤掉，开关同步落库，顶部那枚键盘图标跟着变。
                            onCollapsePreview = { setAppearanceStagePreview(false) }
                        )
                    }
                    replicaBottomInset = with(replicaDensity) {
                        replicaGeometry.totalHeightPx.toDp()
                    }
                } else {
                    replicaStage = null
                    replicaBottomInset = 0.dp
                }
            } else {
                replicaStage = null
                replicaBottomInset = 0.dp
            }
            SettingsSubPageScaffold(
                title = subPage.title,
                onBack = { navBackStack.removeLastOrNull() },
                onRefresh = { saveSettings(restartIme = true) },
                pinnedContent = pinnedPreview,
                stageContent = replicaStage,
                stageBottomInset = replicaBottomInset
            ) {
                renderSubPageContent(subPage, this)
            }
        }
    }

    updateInfo?.let { info ->
        WindowBottomSheet(
            show = showUpdateSheet,
            title = stringResource(R.string.update_dialog_title),
            onDismissRequest = { showUpdateSheet = false }
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    insideMargin = PaddingValues(16.dp)
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = stringResource(R.string.update_dialog_version, info.versionName),
                            style = MiuixTheme.textStyles.main,
                            fontWeight = FontWeight.Medium
                        )
                        if (info.changelog.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = stringResource(R.string.update_dialog_changelog),
                                style = MiuixTheme.textStyles.body2,
                                fontWeight = FontWeight.Medium,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = info.changelog,
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 360.dp)
                                    .verticalScroll(rememberScrollState())
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = { showUpdateSheet = false },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors()
                    ) {
                        Text(text = stringResource(R.string.update_dialog_cancel))
                    }
                    Button(
                        onClick = {
                            showUpdateSheet = false
                            openModuleUpdatePage(context, info)
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColorsPrimary()
                    ) {
                        Text(text = stringResource(R.string.update_dialog_confirm))
                    }
                }
            }
        }
    }
}
