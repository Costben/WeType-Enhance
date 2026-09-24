package com.xposed.wetypehook

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.ViewCompat
import com.kyant.capsule.ContinuousRoundedRectangle
import com.xposed.wetypehook.wetype.gesture.GestureAction
import com.xposed.wetypehook.wetype.graphics.WeTypeBloomStrokeDrawable
import com.xposed.wetypehook.wetype.graphics.WeTypeCornerRadii
import com.xposed.wetypehook.wetype.graphics.WeTypeNativeMaterialProbe
import com.xposed.wetypehook.wetype.graphics.WeTypeSystemMaterials
import com.xposed.wetypehook.wetype.hook.KeyboardToolbarStrip
import com.xposed.wetypehook.wetype.settings.GlassMaterialOverrides
import com.xposed.wetypehook.wetype.settings.GlassOverrideField
import com.xposed.wetypehook.wetype.settings.GlassSliderParameter
import com.xposed.wetypehook.wetype.graphics.WeTypeSmoothRoundedShape
import com.xposed.wetypehook.wetype.graphics.createWeTypeSmoothRoundedPath
import com.xposed.wetypehook.wetype.logo.LogoImageStore
import com.xposed.wetypehook.wetype.settings.DARK_KEY_COLOR_GROUP_ID
import com.xposed.wetypehook.wetype.settings.LIGHT_KEY_COLOR_GROUP_ID
import com.xposed.wetypehook.wetype.settings.WeTypeAppearanceColorGroup
import com.xposed.wetypehook.wetype.settings.WeTypeAppearanceColorGroups
import com.xposed.wetypehook.wetype.settings.WeTypeGestureSettings
import com.xposed.wetypehook.wetype.settings.WeTypeProcessRestarter
import com.xposed.wetypehook.wetype.settings.WeTypeSettings
import top.yukonga.miuix.kmp.basic.Badge
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.TabRowWithContour
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.GridView
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.icon.extended.Pin
import top.yukonga.miuix.kmp.icon.extended.Theme
import top.yukonga.miuix.kmp.icon.extended.Unpin
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavDisplayEffects
import top.yukonga.miuix.kmp.nav.core.NavKey
import top.yukonga.miuix.kmp.nav.core.rememberNavBackStack
import top.yukonga.miuix.kmp.nav.core.rememberNavSystemCornerRadius
import top.yukonga.miuix.kmp.nav.transition.NavTransitions
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.window.WindowBottomSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

const val EXTRA_OPEN_WETYPE_EMBEDDED_SETTINGS = "com.xposed.wetypehook.extra.OPEN_WETYPE_EMBEDDED_SETTINGS"
const val EXTRA_OPEN_WETYPE_BACKUP_PAGE = "com.xposed.wetypehook.extra.OPEN_WETYPE_BACKUP_PAGE"
const val EXTRA_OPEN_WETYPE_COLOROS_LIGHT_PAGE = "com.xposed.wetypehook.extra.OPEN_WETYPE_COLOROS_LIGHT_PAGE"
private const val ACTIVATION_HEARTBEAT_WINDOW_MS = 4_000L
private const val ACTIVATION_KEYBOARD_RETRY_COUNT = 3
private const val ACTIVATION_KEYBOARD_RETRY_DELAY_MS = 450L

private fun ModuleActivationTracker.ActivationStatus.hasFreshHeartbeat(
    now: Long = System.currentTimeMillis()
): Boolean {
    if (!isActive || lastActivatedAt <= 0L) return false
    return now - lastActivatedAt <= ACTIVATION_HEARTBEAT_WINDOW_MS
}

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

@Composable
private fun ActivationEntryApp(
    isActive: Boolean,
    onOpenEmbeddedSettings: () -> Unit
) {
    val darkMode = isSystemInDarkTheme()
    MiuixTheme(colors = if (darkMode) darkColorScheme() else lightColorScheme()) {
        SyncSystemBars(darkMode = darkMode)
        ActivationEntryScreen(
            isActive = isActive,
            onOpenEmbeddedSettings = onOpenEmbeddedSettings
        )
    }
}

@Composable
private fun ActivationEntryScreen(
    isActive: Boolean,
    onOpenEmbeddedSettings: () -> Unit
) {
    var probeText by rememberSaveable { mutableStateOf("") }
    var isCheckingHeartbeat by rememberSaveable { mutableStateOf(!isActive) }
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(isActive) {
        if (isActive) {
            isCheckingHeartbeat = false
            return@LaunchedEffect
        }

        isCheckingHeartbeat = true
        repeat(ACTIVATION_KEYBOARD_RETRY_COUNT) {
            delay(ACTIVATION_KEYBOARD_RETRY_DELAY_MS)
            focusRequester.requestFocus()
            keyboardController?.show()
        }
        isCheckingHeartbeat = false
    }

    val backgroundColor = if (isSystemInDarkTheme()) {
        ComposeColor.Black
    } else {
        ComposeColor(0xFFF7F7F7)
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            insideMargin = PaddingValues(0.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = MiuixIcons.Info,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.primary,
                    modifier = Modifier
                        .width(72.dp)
                        .height(72.dp)
                )
                Spacer(modifier = Modifier.height(18.dp))
                Text(
                    text = stringResource(
                        if (isActive) {
                            R.string.activation_active_title
                        } else {
                            R.string.activation_required_title
                        }
                    ),
                    style = MiuixTheme.textStyles.headline1,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = stringResource(
                        if (isActive) {
                            R.string.activation_active_summary
                        } else {
                            R.string.activation_required_summary
                        }
                    ),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.main,
                    textAlign = TextAlign.Center
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            insideMargin = PaddingValues(0.dp)
        ) {
            if (isActive) {
                ArrowPreference(
                    title = stringResource(R.string.activation_open_embedded_settings),
                    titleColor = BasicComponentDefaults.titleColor(
                        color = MiuixTheme.colorScheme.primary
                    ),
                    onClick = onOpenEmbeddedSettings
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 20.dp)
                ) {
                    Text(
                        text = stringResource(
                            if (isCheckingHeartbeat) {
                                R.string.activation_detecting_summary
                            } else {
                                R.string.activation_probe_summary
                            }
                        ),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        style = MiuixTheme.textStyles.body2
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    TextField(
                        value = probeText,
                        onValueChange = { probeText = it },
                        label = stringResource(R.string.activation_probe_label),
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                    )
                }
            }
        }


    }
}

@Composable
internal fun WeTypeSettingsApp(
    settingsContext: Context
) {
    val darkMode = isSystemInDarkTheme()
    MiuixTheme(colors = if (darkMode) darkColorScheme() else lightColorScheme()) {
        SyncSystemBars(darkMode = darkMode)
        WeTypeSettingsScreen(
            settingsContext = settingsContext
        )
    }
}

@Composable
internal fun SyncSystemBars(darkMode: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return

    SideEffect {
        ViewCompat.getWindowInsetsController(view)?.apply {
            isAppearanceLightStatusBars = !darkMode
            isAppearanceLightNavigationBars = !darkMode
        }
        val window = view.context.findHostActivity()?.window
            ?.takeIf { it.decorView === view.rootView }
            ?: return@SideEffect
        val systemBarColor = if (darkMode) Color.BLACK else Color.parseColor("#F7F7F7")
        window.statusBarColor = systemBarColor
        window.navigationBarColor = systemBarColor
    }
}

private tailrec fun Context.findHostActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findHostActivity()
    else -> null
}

/**
 * 设置界面的导航路由。一级页是返回栈的根，二级页压在其上。
 *
 * 交给 `miuix-nav` 的 `rememberNavBackStack` 托管，所以整条层级必须可序列化，
 * 配置变更与进程重建后由库自行恢复返回栈。
 */
@Serializable
internal sealed interface SettingsRoute : NavKey {
    @Serializable
    data object Home : SettingsRoute

    @Serializable
    data class SubPage(val page: SettingsSubPage) : SettingsRoute
}

/** 一级页里的入口所指向的二级设置页，标题同时用作二级页标题栏文案。 */
@Serializable
internal enum class SettingsSubPage(val title: String) {
    COLORS("颜色"),
    CORNER_BLUR("圆角与模糊"),
    CANDIDATE_TOOLBAR("候选词与工具栏"),
    MATERIAL("高级材质"),
    CLIPBOARD("剪贴板"),
    KEYBOARD_LOGO("键盘 Logo")
}

/** 「界面美化」下暴露实时预览的二级页；剪贴板属于「功能增强」，不涉及外观。 */
private val APPEARANCE_PREVIEW_SUB_PAGES = setOf(
    SettingsSubPage.COLORS,
    SettingsSubPage.CORNER_BLUR,
    SettingsSubPage.CANDIDATE_TOOLBAR,
    SettingsSubPage.MATERIAL,
    SettingsSubPage.KEYBOARD_LOGO
)

@Composable
private fun WeTypeSettingsScreen(
    settingsContext: Context
) {
    val context = LocalContext.current
    val preferencesContext = remember(settingsContext) { settingsContext }
    val isEmbeddedHost = remember(settingsContext) {
        (context.applicationContext ?: context).packageName != "com.xposed.wetypehook"
    }
    val snapshot = remember(preferencesContext) { WeTypeSettings.readSnapshot(preferencesContext) }
    var activationStatus by remember(preferencesContext) {
        mutableStateOf(ModuleActivationTracker.resolveStatusForUi(preferencesContext))
    }
    val systemDarkMode = isSystemInDarkTheme()
    val appearanceGroups = remember { WeTypeAppearanceColorGroups.groups }
    val appearanceSectionGroups = remember(appearanceGroups) {
        appearanceGroups.filterNot { it.isKeyColorGroup }
    }

    var lightColor by rememberSaveable { mutableIntStateOf(snapshot.lightColor) }
    var darkColor by rememberSaveable { mutableIntStateOf(snapshot.darkColor) }
    var blurRadius by rememberSaveable { mutableIntStateOf(snapshot.blurRadius) }
    var cornerRadius by rememberSaveable { mutableIntStateOf(snapshot.cornerRadius) }
    var bottomCornerRadius by rememberSaveable { mutableIntStateOf(snapshot.bottomCornerRadius) }
    var keyCornerRadius by rememberSaveable { mutableIntStateOf(snapshot.keyCornerRadius) }
    var edgeHighlightEnabled by rememberSaveable { mutableStateOf(snapshot.edgeHighlightEnabled) }
    var edgeHighlightIntensity by rememberSaveable { mutableIntStateOf(snapshot.edgeHighlightIntensity) }
    var candidateBackgroundAlpha by rememberSaveable {
        mutableIntStateOf(snapshot.candidateBackgroundAlpha)
    }
    var candidateBackgroundCorner by rememberSaveable {
        mutableIntStateOf(snapshot.candidateBackgroundCorner.roundToInt())
    }
    var candidateBackgroundLeftMarginDp by rememberSaveable {
        mutableStateOf(snapshot.candidateBackgroundLeftMarginDp.toString())
    }
    var candidatePinyinLeftMarginDp by rememberSaveable {
        mutableStateOf(snapshot.candidatePinyinLeftMarginDp.toString())
    }
    var toolbarIconBgOpacity by rememberSaveable {
        mutableIntStateOf(snapshot.toolbarIconBgOpacity)
    }
    var iconEdgeLightEnabled by rememberSaveable {
        mutableStateOf(snapshot.iconEdgeLightEnabled)
    }
    var disableHotUpdate by rememberSaveable {
        mutableStateOf(snapshot.disableHotUpdate)
    }
    var showCrossDeviceClipboard by rememberSaveable {
        mutableStateOf(snapshot.showCrossDeviceClipboard)
    }
    var removeClipboardRetentionLimit by rememberSaveable {
        mutableStateOf(snapshot.removeClipboardRetentionLimit)
    }
    var removeClipboardTextLimit by rememberSaveable {
        mutableStateOf(snapshot.removeClipboardTextLimit)
    }
    var clipboardSearchEnabled by rememberSaveable {
        mutableStateOf(snapshot.clipboardSearchEnabled)
    }
    var clipboardSearchClearOnBack by rememberSaveable {
        mutableStateOf(snapshot.clipboardSearchClearOnBack)
    }
    var clipboardImageAdjustRatio by rememberSaveable {
        mutableStateOf(snapshot.clipboardImageAdjustRatio)
    }
    var clipboardImageCrop by rememberSaveable {
        mutableStateOf(snapshot.clipboardImageCrop)
    }
    var clipboardImageUniformRowHeight by rememberSaveable {
        mutableStateOf(snapshot.clipboardImageUniformRowHeight)
    }
    var clipboardImageMaxCount by rememberSaveable {
        mutableIntStateOf(snapshot.clipboardImageMaxCount)
    }
    var clipboardImageMaxSizeMb by rememberSaveable {
        mutableIntStateOf(snapshot.clipboardImageMaxSizeMb)
    }
    var qwertyGestureEnabled by rememberSaveable {
        mutableStateOf(snapshot.qwertyGestureEnabled)
    }
    var t9GestureEnabled by rememberSaveable {
        mutableStateOf(snapshot.t9GestureEnabled)
    }
    var gestureThreshold by rememberSaveable {
        mutableIntStateOf(snapshot.gestureThreshold)
    }
    var t9GestureThreshold by rememberSaveable {
        mutableIntStateOf(snapshot.t9GestureThreshold)
    }
    var gestureVibration by rememberSaveable {
        mutableStateOf(snapshot.gestureVibration)
    }
    var t9GestureVibration by rememberSaveable {
        mutableStateOf(snapshot.t9GestureVibration)
    }
    var gestureBindingsJson by rememberSaveable {
        mutableStateOf(snapshot.gestureBindingsJson)
    }
    var showGestureKeyLabels by rememberSaveable {
        mutableStateOf(snapshot.showGestureKeyLabels)
    }
    var gestureLabelTextSizeSp by rememberSaveable {
        mutableIntStateOf(snapshot.gestureLabelTextSizeSp)
    }
    var gestureLabelAlpha by rememberSaveable {
        mutableIntStateOf(snapshot.gestureLabelAlpha)
    }
    var gestureLabelPosition by rememberSaveable {
        mutableIntStateOf(snapshot.gestureLabelPosition)
    }
    var gestureLabelMarginTopDp by rememberSaveable {
        mutableIntStateOf(snapshot.gestureLabelMarginTopDp)
    }
    var gestureLabelMarginBottomDp by rememberSaveable {
        mutableIntStateOf(snapshot.gestureLabelMarginBottomDp)
    }
    var gestureLabelMarginLeftDp by rememberSaveable {
        mutableIntStateOf(snapshot.gestureLabelMarginLeftDp)
    }
    var gestureLabelMarginRightDp by rememberSaveable {
        mutableIntStateOf(snapshot.gestureLabelMarginRightDp)
    }
    var logoEnabled by rememberSaveable {
        mutableStateOf(snapshot.logoEnabled)
    }
    var logoShowEnabled by rememberSaveable {
        mutableStateOf(snapshot.logoShowEnabled)
    }
    var logoColorMode by rememberSaveable {
        mutableStateOf(WeTypeSettings.normalizeLogoColorMode(snapshot.logoColorMode))
    }
    var logoCustomColorInput by rememberSaveable {
        mutableStateOf(formatRgb(snapshot.logoCustomColor))
    }
    // 自定义图片 Logo 由「键盘 Logo」二级页编辑；主页面只透传保存，用普通 remember 避免大字符串进 savedState。
    var logoImageEnabled by rememberSaveable { mutableStateOf(snapshot.logoImageEnabled) }
    var logoImageType by rememberSaveable {
        mutableStateOf(WeTypeSettings.normalizeLogoImageType(snapshot.logoImageType))
    }
    var logoSvgRecolorEnabled by rememberSaveable { mutableStateOf(snapshot.logoSvgRecolorEnabled) }
    var logoImagePngBase64 by remember { mutableStateOf(snapshot.logoImagePngBase64) }
    var logoImageSvgText by remember { mutableStateOf(snapshot.logoImageSvgText) }
    var logoImageName by rememberSaveable { mutableStateOf(snapshot.logoImageName) }
    var logoImageUpdatedAt by rememberSaveable { mutableStateOf(snapshot.logoImageUpdatedAt) }
    var logoImageMessage by remember { mutableStateOf("") }
    var logoImageImporting by remember { mutableStateOf(false) }
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
    var fontMode by rememberSaveable {
        mutableIntStateOf(snapshot.fontMode)
    }
    val glassInput = rememberSaveable(
        saver = listSaver(
            save = { it.toList() },
            restore = { restored -> mutableStateListOf(*restored.toTypedArray()) }
        )
    ) {
        mutableStateListOf(
            *GlassOverrideField.entries.map { snapshot.glassOverrides.text(it) }.toTypedArray()
        )
    }
    val glassSupported = remember { WeTypeSystemMaterials.areGlassOverridesAvailable() }
    val parsedGlassOverrides = runCatching {
        GlassMaterialOverrides.parse(GlassOverrideField.entries.associateWith { glassInput[it.ordinal] })
    }.getOrNull()
    var previewGlassOverrides by remember { mutableStateOf(snapshot.glassOverrides) }
    LaunchedEffect(parsedGlassOverrides) {
        // Recreating a native material during every slider tick would flash its fallback tint.
        delay(120)
        parsedGlassOverrides?.let { previewGlassOverrides = it }
    }
    var hyperMaterialEnabled by rememberSaveable { mutableStateOf(snapshot.hyperMaterialEnabled) }
    var hyperMaterialAvailable by remember(preferencesContext) {
        mutableStateOf(WeTypeSystemMaterials.isAvailable(preferencesContext))
    }
    DisposableEffect(preferencesContext) {
        val stopObserving = WeTypeSystemMaterials.observeAvailability(preferencesContext) {
            hyperMaterialAvailable = WeTypeSystemMaterials.isAvailable(preferencesContext)
        }
        onDispose { stopObserving() }
    }
    val appearanceGroupColors = rememberSaveable(
        saver = listSaver(
            save = { it.toList() },
            restore = { restored -> mutableStateListOf(*restored.toTypedArray()) }
        )
    ) {
        mutableStateListOf(
            *appearanceGroups.map { group ->
                snapshot.appearanceColors[group.id] ?: group.defaultColor
            }.toTypedArray()
        )
    }
    var currentModeIsDark by rememberSaveable { mutableStateOf(systemDarkMode) }
    // 一级页是返回栈的根，二级页逐个压栈。返回栈交给 miuix-nav 托管：进出场动画、
    // 预测性返回、边缘圆角裁剪与遮罩统一由 NavDisplay 处理。
    val navBackStack = rememberNavBackStack<SettingsRoute>(SettingsRoute.Home)
    val categoryTabs = remember { listOf("界面美化", "按键手势", "功能增强") }
    val categoryPagerState = rememberPagerState(pageCount = { categoryTabs.size })
    val coroutineScope = rememberCoroutineScope()

    val openSubPage: (SettingsSubPage) -> Unit = { targetSubPage ->
        // 现有二级页只有一个返回按钮，深度恒为 1。
        if (navBackStack.size == 1) {
            navBackStack.add(SettingsRoute.SubPage(targetSubPage))
        }
    }

    // 实时预览固定在标题栏下方，跨二级页共享；只在界面美化的二级页里生效。
    var appearancePreviewPinned by rememberSaveable { mutableStateOf(false) }
    // 真机键盘预览：设置页展示偏好，独立落库、不进 Snapshot。
    var keyboardPreviewEnabled by rememberSaveable {
        mutableStateOf(WeTypeSettings.isAppearanceStagePreviewEnabled(context))
    }

    fun setAppearanceStagePreview(enabled: Boolean) {
        keyboardPreviewEnabled = enabled
        WeTypeSettings.setAppearanceStagePreviewEnabled(context, enabled)
    }
    // 圆角对齐参考线默认关闭：只调效果时那两条弧是纯噪声。
    var appearanceStageGuidesEnabled by rememberSaveable {
        mutableStateOf(WeTypeSettings.isAppearanceStageGuidesEnabled(context))
    }

    // 预览底图（那层「手机壁纸」）：只是设置页的展示偏好，独立落库、不进 Snapshot。
    var previewWallpaperName by rememberSaveable {
        mutableStateOf(PreviewWallpaper.displayName(context))
    }

    fun setAppearanceStageGuides(enabled: Boolean) {
        appearanceStageGuidesEnabled = enabled
        WeTypeSettings.setAppearanceStageGuidesEnabled(context, enabled)
    }
    var colorInput by rememberSaveable {
        mutableStateOf(formatRgb(if (currentModeIsDark) darkColor else lightColor))
    }
    var alphaValue by rememberSaveable {
        mutableIntStateOf(Color.alpha(if (currentModeIsDark) darkColor else lightColor))
    }

    fun currentColor(): Int = if (currentModeIsDark) darkColor else lightColor

    fun syncEditorFromState() {
        alphaValue = Color.alpha(currentColor())
        colorInput = formatRgb(currentColor())
    }

    fun updateColorFromArgb(argb: Int) {
        if (currentModeIsDark) darkColor = argb else lightColor = argb
    }

    /**
     * 预览右上角那个浅色/深色按钮。
     *
     * 切的是「预览按哪一档配色渲染」，与颜色页的「浅色/深色」Tab 共用同一份状态；切完把颜色
     * 编辑框同步过去，免得颜色页里编辑的档位和预览显示的不是一回事。它不写任何设置，也不改
     * 宿主键盘的实际深浅色 —— 那个由宿主按运行时 uiMode 自己决定。
     */
    fun setPreviewDarkMode(dark: Boolean) {
        if (dark == currentModeIsDark) return
        currentModeIsDark = dark
        syncEditorFromState()
    }

    fun currentAppearanceColors(): Map<String, Int> = appearanceGroups.mapIndexed { index, group ->
        group.id to appearanceGroupColors[index]
    }.toMap()

    fun groupIndex(groupId: String): Int =
        appearanceGroups.indexOfFirst { it.id == groupId }

    fun keyColorGroup(isDark: Boolean) = appearanceGroups.first {
        it.id == if (isDark) {
            DARK_KEY_COLOR_GROUP_ID
        } else {
            LIGHT_KEY_COLOR_GROUP_ID
        }
    }

    fun keyColorValue(isDark: Boolean): Int {
        val group = keyColorGroup(isDark)
        return appearanceGroupColors[groupIndex(group.id)]
    }

    /**
     * 唤起文件选择器。嵌入设置跑在 `ComponentDialog` 里，没有 `ActivityResultRegistryOwner`，
     * 只能走 [WeTypeHostActivityResultBridge]；而该桥在派发时会消费掉回调，
     * 所以必须每次唤起前重新注册，否则第二次选文件不会有任何反应。
     */
    fun launchLogoImagePicker(requestCode: Int, intent: Intent, onUri: (Uri) -> Unit) {
        val hostActivity = settingsContext as? Activity
        if (hostActivity == null) {
            logoImageMessage = "无法获取宿主窗口，请重试"
            return
        }
        WeTypeHostActivityResultBridge.register(requestCode) { resultCode, data ->
            val uri = if (resultCode == Activity.RESULT_OK) data?.data else null
            if (uri != null) onUri(uri)
        }
        runCatching {
            hostActivity.startActivityForResult(intent, requestCode)
        }.onFailure {
            WeTypeHostActivityResultBridge.unregister(requestCode)
            logoImageMessage = "无法打开文件选择器"
        }
    }

    fun pickLogoPng() {
        launchLogoImagePicker(
            requestCode = WeTypeHostActivityResultBridge.REQUEST_PICK_LOGO_PNG,
            intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/png"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        ) { uri ->
            if (logoImageImporting) return@launchLogoImagePicker
            logoImageImporting = true
            logoImageMessage = "正在导入 PNG…"
            coroutineScope.launch {
                val result = withContext(Dispatchers.IO) {
                    LogoImageStore.importPng(settingsContext.contentResolver, uri)
                }
                result.onSuccess { png ->
                    // 最后上传者胜：导入即成为待生效类型。
                    logoImagePngBase64 = png.base64
                    logoImageName = png.name
                    logoImageType = WeTypeSettings.LOGO_IMAGE_TYPE_PNG
                    logoImageUpdatedAt = System.currentTimeMillis()
                    logoImageMessage = "PNG 已就绪：${png.name}，点「保存」后生效"
                }.onFailure { error ->
                    logoImageMessage = "导入失败：${error.message ?: "未知错误"}"
                }
                logoImageImporting = false
            }
        }
    }

    fun pickLogoSvg() {
        launchLogoImagePicker(
            requestCode = WeTypeHostActivityResultBridge.REQUEST_PICK_LOGO_SVG,
            intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/svg+xml"
                putExtra(
                    Intent.EXTRA_MIME_TYPES,
                    arrayOf("image/svg+xml", "image/svg", "text/xml")
                )
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        ) { uri ->
            if (logoImageImporting) return@launchLogoImagePicker
            logoImageImporting = true
            logoImageMessage = "正在导入 SVG…"
            coroutineScope.launch {
                val result = withContext(Dispatchers.IO) {
                    LogoImageStore.importSvg(settingsContext.contentResolver, uri)
                }
                result.onSuccess { svg ->
                    logoImageSvgText = svg.text
                    logoImageName = svg.name
                    logoImageType = WeTypeSettings.LOGO_IMAGE_TYPE_SVG
                    logoImageUpdatedAt = System.currentTimeMillis()
                    logoImageMessage = "SVG 已就绪：${svg.name}，点「保存」后生效"
                }.onFailure { error ->
                    logoImageMessage = "导入失败：${error.message ?: "未知错误"}"
                }
                logoImageImporting = false
            }
        }
    }

    fun clearLogoImages() {
        logoImagePngBase64 = ""
        logoImageSvgText = ""
        logoImageName = ""
        logoImageUpdatedAt = 0L
        logoImageMessage = "已清除自定义图片，点「保存」后恢复矢量 Logo"
    }

    /**
     * 换预览里那层「手机壁纸」。
     *
     * 跟 logo 选图同一条桥（嵌入设置没有 `ActivityResultRegistryOwner`），但落库方式不同：
     * 图片缩放后直接存进模块自己的 prefs，不记 URI —— 预览只在设置进程里画，不需要跨进程授权。
     */
    fun pickPreviewWallpaper() {
        val hostActivity = settingsContext as? Activity
        if (hostActivity == null) {
            Toast.makeText(context, "无法获取宿主窗口，请重试", Toast.LENGTH_SHORT).show()
            return
        }
        val requestCode = WeTypeHostActivityResultBridge.REQUEST_PICK_PREVIEW_WALLPAPER
        WeTypeHostActivityResultBridge.register(requestCode) { resultCode, data ->
            val uri = if (resultCode == Activity.RESULT_OK) data?.data else null
            if (uri != null) {
                coroutineScope.launch {
                    val result = withContext(Dispatchers.IO) {
                        PreviewWallpaper.import(settingsContext, uri)
                    }
                    result.onSuccess { name ->
                        previewWallpaperName = name
                    }.onFailure { error ->
                        Toast.makeText(
                            context,
                            "导入失败：${error.message ?: "未知错误"}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }
        runCatching {
            hostActivity.startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "image/*"
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                requestCode
            )
        }.onFailure {
            WeTypeHostActivityResultBridge.unregister(requestCode)
            Toast.makeText(context, "无法打开文件选择器", Toast.LENGTH_SHORT).show()
        }
    }

    fun clearPreviewWallpaper() {
        PreviewWallpaper.clear(settingsContext)
        previewWallpaperName = ""
    }

    fun saveSettings(
        successMessage: Int = R.string.settings_saved,
        glassOverridesToSave: GlassMaterialOverrides? = parsedGlassOverrides,
        restartIme: Boolean = false
    ): Boolean {
        if (glassOverridesToSave == null) {
            Toast.makeText(context, R.string.settings_glass_invalid, Toast.LENGTH_SHORT).show()
            return false
        }
        // 光感相关字段已移到「ColorOS 光感设置」二级页，本页不再编辑它们。
        // 保存时以最新快照为准，避免用过期本地状态覆盖二级页刚写入的修改。
        val latest = WeTypeSettings.readLocalSnapshot(preferencesContext)
        return WeTypeSettings.save(
            context = preferencesContext,
            lightColor = lightColor,
            darkColor = darkColor,
            blurRadius = blurRadius,
            cornerRadius = cornerRadius,
            bottomCornerRadius = bottomCornerRadius,
            keyCornerRadius = keyCornerRadius,
            edgeHighlightEnabled = latest.edgeHighlightEnabled,
            edgeHighlightIntensity = latest.edgeHighlightIntensity,
            colorOsLightAngle = latest.colorOsLightAngle,
            nativeEdgeLightEnabled = latest.nativeEdgeLightEnabled,
            edgeLightWidth = latest.edgeLightWidth,
            nativeEdgeLightWidth = latest.nativeEdgeLightWidth,
            edgeLightAngle = latest.edgeLightAngle,
            candidateBackgroundAlpha = candidateBackgroundAlpha,
            candidateBackgroundCorner = candidateBackgroundCorner.toFloat(),
            candidateBackgroundLeftMarginDp = candidateBackgroundLeftMarginDp.toIntOrNull()
                ?: WeTypeSettings.DEFAULT_CANDIDATE_BACKGROUND_LEFT_MARGIN_DP,
            candidatePinyinLeftMarginDp = candidatePinyinLeftMarginDp.toIntOrNull()
                ?: WeTypeSettings.DEFAULT_CANDIDATE_PINYIN_LEFT_MARGIN_DP,
            toolbarIconBgOpacity = toolbarIconBgOpacity,
            iconEdgeLightEnabled = latest.iconEdgeLightEnabled,
            appearanceColors = currentAppearanceColors(),
            disableHotUpdate = disableHotUpdate,
            showCrossDeviceClipboard = showCrossDeviceClipboard,
            removeClipboardRetentionLimit = removeClipboardRetentionLimit,
            removeClipboardTextLimit = removeClipboardTextLimit,
            clipboardSearchEnabled = clipboardSearchEnabled,
            clipboardSearchClearOnBack = clipboardSearchClearOnBack,
            clipboardImageAdjustRatio = clipboardImageAdjustRatio,
            clipboardImageCrop = clipboardImageCrop,
            clipboardImageUniformRowHeight = clipboardImageUniformRowHeight,
            clipboardImageMaxCount = clipboardImageMaxCount,
            clipboardImageMaxSizeMb = clipboardImageMaxSizeMb,
            qwertyGestureEnabled = qwertyGestureEnabled,
            t9GestureEnabled = t9GestureEnabled,
            gestureThreshold = gestureThreshold,
            t9GestureThreshold = t9GestureThreshold,
            gestureVibration = gestureVibration,
            t9GestureVibration = t9GestureVibration,
            gestureBindingsJson = gestureBindingsJson,
            showGestureKeyLabels = showGestureKeyLabels,
            gestureLabelTextSizeSp = gestureLabelTextSizeSp,
            gestureLabelAlpha = gestureLabelAlpha,
            gestureLabelPosition = gestureLabelPosition,
            gestureLabelMarginTopDp = gestureLabelMarginTopDp,
            gestureLabelMarginBottomDp = gestureLabelMarginBottomDp,
            gestureLabelMarginLeftDp = gestureLabelMarginLeftDp,
            gestureLabelMarginRightDp = gestureLabelMarginRightDp,
            logoEnabled = logoEnabled,
            logoShowEnabled = logoShowEnabled,
            logoColorMode = logoColorMode,
            logoCustomColor = parseLogoCustomColor(logoCustomColorInput),
            logoImageEnabled = logoImageEnabled,
            logoImageType = logoImageType,
            logoSvgRecolorEnabled = logoSvgRecolorEnabled,
            logoImagePngBase64 = logoImagePngBase64,
            logoImageSvgText = logoImageSvgText,
            logoImageName = logoImageName,
            logoImageUpdatedAt = logoImageUpdatedAt,
            fontMode = fontMode,
            hyperMaterialEnabled = hyperMaterialEnabled,
            glassOverrides = glassOverridesToSave,
            onPersisted = { saved ->
                val restarted = saved && restartIme &&
                    WeTypeProcessRestarter.restartImeProcess(preferencesContext)
                val message = when {
                    restarted -> R.string.settings_saved_restarted
                    saved -> successMessage
                    else -> R.string.settings_save_failed
                }
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        )
    }

    fun restoreDefaults() {
        lightColor = WeTypeSettings.DEFAULT_LIGHT_COLOR
        darkColor = WeTypeSettings.DEFAULT_DARK_COLOR
        blurRadius = WeTypeSettings.DEFAULT_BLUR_RADIUS
        cornerRadius = WeTypeSettings.DEFAULT_CORNER_RADIUS
        bottomCornerRadius = WeTypeSettings.DEFAULT_BOTTOM_CORNER_RADIUS
        keyCornerRadius = WeTypeSettings.DEFAULT_KEY_CORNER_RADIUS
        edgeHighlightEnabled = WeTypeSettings.DEFAULT_EDGE_HIGHLIGHT_ENABLED
        edgeHighlightIntensity = WeTypeSettings.DEFAULT_EDGE_HIGHLIGHT_INTENSITY
        candidateBackgroundAlpha = WeTypeSettings.DEFAULT_CANDIDATE_BACKGROUND_ALPHA
        candidateBackgroundCorner = WeTypeSettings.DEFAULT_CANDIDATE_BACKGROUND_CORNER.roundToInt()
        candidateBackgroundLeftMarginDp =
            WeTypeSettings.DEFAULT_CANDIDATE_BACKGROUND_LEFT_MARGIN_DP.toString()
        candidatePinyinLeftMarginDp = WeTypeSettings.DEFAULT_CANDIDATE_PINYIN_LEFT_MARGIN_DP.toString()
        toolbarIconBgOpacity = WeTypeSettings.DEFAULT_TOOLBAR_ICON_BG_OPACITY
        iconEdgeLightEnabled = WeTypeSettings.DEFAULT_ICON_EDGE_LIGHT_ENABLED
        // 光感字段已移到二级页，本页只保留预览状态；重置必须直接写回快照，
        // 因为 saveSettings 会从最新快照读取这些字段。
        WeTypeSettings.saveColorOsLight(
            context = preferencesContext,
            edgeHighlightEnabled = WeTypeSettings.DEFAULT_EDGE_HIGHLIGHT_ENABLED,
            edgeHighlightIntensity = WeTypeSettings.DEFAULT_EDGE_HIGHLIGHT_INTENSITY,
            colorOsLightAngle = WeTypeSettings.DEFAULT_COLOROS_LIGHT_ANGLE,
            iconEdgeLightEnabled = WeTypeSettings.DEFAULT_ICON_EDGE_LIGHT_ENABLED,
            nativeEdgeLightEnabled = WeTypeSettings.DEFAULT_NATIVE_EDGE_LIGHT_ENABLED,
            edgeLightWidth = WeTypeSettings.DEFAULT_EDGE_LIGHT_WIDTH,
            nativeEdgeLightWidth = WeTypeSettings.DEFAULT_NATIVE_EDGE_LIGHT_WIDTH,
            edgeLightAngle = WeTypeSettings.DEFAULT_EDGE_LIGHT_ANGLE
        )
        disableHotUpdate = WeTypeSettings.DEFAULT_DISABLE_HOT_UPDATE
        showCrossDeviceClipboard = WeTypeSettings.DEFAULT_SHOW_CROSS_DEVICE_CLIPBOARD
        removeClipboardRetentionLimit = WeTypeSettings.DEFAULT_REMOVE_CLIPBOARD_RETENTION_LIMIT
        removeClipboardTextLimit = WeTypeSettings.DEFAULT_REMOVE_CLIPBOARD_TEXT_LIMIT
        clipboardSearchEnabled = WeTypeSettings.DEFAULT_CLIPBOARD_SEARCH_ENABLED
        clipboardSearchClearOnBack = WeTypeSettings.DEFAULT_CLIPBOARD_SEARCH_CLEAR_ON_BACK
        clipboardImageAdjustRatio = WeTypeSettings.DEFAULT_CLIPBOARD_IMAGE_ADJUST_RATIO
        clipboardImageCrop = WeTypeSettings.DEFAULT_CLIPBOARD_IMAGE_CROP
        clipboardImageUniformRowHeight = WeTypeSettings.DEFAULT_CLIPBOARD_IMAGE_UNIFORM_ROW_HEIGHT
        clipboardImageMaxCount = WeTypeSettings.DEFAULT_CLIPBOARD_IMAGE_MAX_COUNT
        clipboardImageMaxSizeMb = WeTypeSettings.DEFAULT_CLIPBOARD_IMAGE_MAX_SIZE_MB
        qwertyGestureEnabled = WeTypeSettings.DEFAULT_QWERTY_GESTURE_ENABLED
        t9GestureEnabled = WeTypeSettings.DEFAULT_T9_GESTURE_ENABLED
        gestureThreshold = WeTypeSettings.DEFAULT_GESTURE_THRESHOLD
        t9GestureThreshold = WeTypeSettings.DEFAULT_T9_GESTURE_THRESHOLD
        gestureVibration = WeTypeSettings.DEFAULT_GESTURE_VIBRATION
        t9GestureVibration = WeTypeSettings.DEFAULT_T9_GESTURE_VIBRATION
        gestureBindingsJson = WeTypeSettings.DEFAULT_GESTURE_BINDINGS_JSON
        showGestureKeyLabels = WeTypeSettings.DEFAULT_SHOW_GESTURE_KEY_LABELS
        gestureLabelTextSizeSp = WeTypeSettings.DEFAULT_GESTURE_LABEL_TEXT_SIZE_SP
        gestureLabelAlpha = WeTypeSettings.DEFAULT_GESTURE_LABEL_ALPHA
        gestureLabelPosition = WeTypeSettings.DEFAULT_GESTURE_LABEL_POSITION
        gestureLabelMarginTopDp = WeTypeSettings.DEFAULT_GESTURE_LABEL_MARGIN_TOP_DP
        gestureLabelMarginBottomDp = WeTypeSettings.DEFAULT_GESTURE_LABEL_MARGIN_BOTTOM_DP
        gestureLabelMarginLeftDp = WeTypeSettings.DEFAULT_GESTURE_LABEL_MARGIN_LEFT_DP
        gestureLabelMarginRightDp = WeTypeSettings.DEFAULT_GESTURE_LABEL_MARGIN_RIGHT_DP
        logoEnabled = WeTypeSettings.DEFAULT_LOGO_ENABLED
        logoShowEnabled = WeTypeSettings.DEFAULT_LOGO_SHOW_ENABLED
        logoColorMode = WeTypeSettings.DEFAULT_LOGO_COLOR_MODE
        logoCustomColorInput = formatRgb(WeTypeSettings.DEFAULT_LOGO_CUSTOM_COLOR)
        // 全局重置时图片开关回到关闭（用回矢量 Logo），已上传文件保留，可在二级页清除。
        logoImageEnabled = WeTypeSettings.DEFAULT_LOGO_IMAGE_ENABLED
        logoImageType = WeTypeSettings.DEFAULT_LOGO_IMAGE_TYPE
        logoSvgRecolorEnabled = WeTypeSettings.DEFAULT_LOGO_SVG_RECOLOR_ENABLED
        fontMode = WeTypeSettings.DEFAULT_FONT_MODE
        glassInput.indices.forEach { glassInput[it] = "" }
        previewGlassOverrides = GlassMaterialOverrides()
        hyperMaterialEnabled = WeTypeSettings.DEFAULT_HYPER_MATERIAL_ENABLED
        appearanceGroups.forEachIndexed { index, group ->
            appearanceGroupColors[index] = group.defaultColor
        }
        syncEditorFromState()
        saveSettings(
            successMessage = R.string.settings_reset_toast,
            glassOverridesToSave = GlassMaterialOverrides()
        )
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

    val scrollBehavior = MiuixScrollBehavior(state = rememberTopAppBarState())

    var updateInfo by remember { mutableStateOf<ModuleUpdateInfo?>(null) }
    var showUpdateSheet by remember { mutableStateOf(false) }
    // 每次进入模块设置时检查一次更新，失败静默忽略。
    LaunchedEffect(Unit) {
        ModuleUpdateChecker.check(BuildConfig.VERSION_NAME)?.let { info ->
            updateInfo = info
            showUpdateSheet = true
        }
    }

    /**
     * 「界面美化」二级页的实时预览。键盘 Logo 页看的是 Logo 本身（与 hook 侧同一份渲染），
     * 其余页面看的是键盘外观。
     */
    @Composable
    fun renderAppearancePreview(
        subPage: SettingsSubPage,
        pinned: Boolean,
        onTogglePin: () -> Unit
    ) {
        if (subPage == SettingsSubPage.KEYBOARD_LOGO) {
            LogoImagePreviewCard(
                lightBackgroundColor = lightColor,
                darkBackgroundColor = darkColor,
                lightKeyColor = keyColorValue(false),
                darkKeyColor = keyColorValue(true),
                logoColorMode = logoColorMode,
                brandColor = appearanceGroupColors.getOrNull(groupIndex("theme_color"))
                    ?: WeTypeSettings.DEFAULT_LOGO_CUSTOM_COLOR,
                logoCustomColor = parseLogoCustomColor(logoCustomColorInput),
                toolbarIconBgOpacity = toolbarIconBgOpacity,
                imageEnabled = logoImageEnabled,
                imageType = logoImageType,
                svgRecolor = logoSvgRecolorEnabled,
                pngBase64 = logoImagePngBase64,
                svgText = logoImageSvgText,
                footerText = if (logoImageEnabled) {
                    "主体颜色跟随本页设置实时预览"
                } else {
                    null
                },
                pinned = pinned,
                onTogglePin = onTogglePin
            )
        } else {
            PreviewSection(
                color = currentColor(),
                blurRadius = blurRadius,
                cornerRadius = cornerRadius,
                bottomCornerRadius = bottomCornerRadius,
                keyCornerRadius = keyCornerRadius,
                edgeHighlightEnabled = edgeHighlightEnabled,
                edgeHighlightIntensity = edgeHighlightIntensity,
                lightKeyColor = keyColorValue(false),
                darkKeyColor = keyColorValue(true),
                isDark = currentModeIsDark,
                hyperMaterialEnabled = hyperMaterialEnabled,
                pinned = pinned,
                onTogglePin = onTogglePin,
                keyboardPreviewEnabled = keyboardPreviewEnabled,
                onToggleKeyboardPreview = {
                    setAppearanceStagePreview(!keyboardPreviewEnabled)
                },
                onToggleDarkMode = { setPreviewDarkMode(!currentModeIsDark) }
            )
        }
    }

    fun renderSubPageContent(subPage: SettingsSubPage, listScope: LazyListScope) {
        with(listScope) {
            // 实时预览常驻每个「界面美化」二级页的首位；固定时改由标题栏承担。
            if (subPage in APPEARANCE_PREVIEW_SUB_PAGES && !appearancePreviewPinned) {
                item(key = "appearance_preview") {
                    renderAppearancePreview(
                        subPage = subPage,
                        pinned = false,
                        onTogglePin = { appearancePreviewPinned = true }
                    )
                }
            }
            when (subPage) {
                SettingsSubPage.COLORS -> ColorsSubPageContent(
                    currentModeIsDark = currentModeIsDark,
                    onModeChange = ::setPreviewDarkMode,
                    currentColor = currentColor(),
                    alphaValue = alphaValue,
                    onAlphaChange = {
                        alphaValue = it
                        val rgb = currentColor() and 0xFFFFFF
                        updateColorFromArgb((alphaValue shl 24) or rgb)
                        colorInput = formatRgb(currentColor())
                    },
                    colorInput = colorInput,
                    onColorInputChange = { input ->
                        val trimmed = input.trim()
                        val hasPrefix = trimmed.startsWith("#")
                        val body = trimmed.removePrefix("#")
                        if (body.length <= 6 && body.matches(Regex("^[0-9a-fA-F]*$"))) {
                            colorInput = if (hasPrefix || body.isNotEmpty()) "#$body" else ""

                            if (body.length == 6) {
                                runCatching {
                                    val opaque = Color.parseColor("#$body")
                                    val argb = Color.argb(
                                        alphaValue.coerceIn(0, 255),
                                        Color.red(opaque),
                                        Color.green(opaque),
                                        Color.blue(opaque)
                                    )
                                    updateColorFromArgb(argb)
                                }
                            }
                        }
                    },
                    onColorSelect = { presetRgb ->
                        val rgb = presetRgb and 0xFFFFFF
                        val argb = (alphaValue.coerceIn(0, 255) shl 24) or rgb
                        updateColorFromArgb(argb)
                        colorInput = formatRgb(rgb)
                    },
                    appearanceSectionGroups = appearanceSectionGroups,
                    appearanceGroupColors = appearanceGroupColors,
                    groupIndex = ::groupIndex,
                    currentKeyGroup = keyColorGroup(currentModeIsDark),
                    currentKeyGroupIndex = groupIndex(keyColorGroup(currentModeIsDark).id),
                    onKeyColorChange = {
                        appearanceGroupColors[groupIndex(keyColorGroup(currentModeIsDark).id)] = it
                    }
                )

                SettingsSubPage.CORNER_BLUR -> CornerBlurSubPageContent(
                    hyperMaterialEnabled = hyperMaterialEnabled,
                    blurRadius = blurRadius,
                    onBlurRadiusChange = { blurRadius = it },
                    cornerRadius = cornerRadius,
                    onCornerRadiusChange = { cornerRadius = it },
                    bottomCornerRadius = bottomCornerRadius,
                    onBottomCornerRadiusChange = { bottomCornerRadius = it },
                    keyCornerRadius = keyCornerRadius,
                    onKeyCornerRadiusChange = { keyCornerRadius = it },
                    candidateBackgroundCorner = candidateBackgroundCorner,
                    onCandidateBackgroundCornerChange = { candidateBackgroundCorner = it }
                )

                SettingsSubPage.CANDIDATE_TOOLBAR -> CandidateToolbarSubPageContent(
                    candidateBackgroundAlpha = candidateBackgroundAlpha,
                    onCandidateBackgroundAlphaChange = { candidateBackgroundAlpha = it },
                    candidateBackgroundLeftMarginDp = candidateBackgroundLeftMarginDp,
                    onCandidateBackgroundLeftMarginDpChange = { candidateBackgroundLeftMarginDp = it },
                    candidatePinyinLeftMarginDp = candidatePinyinLeftMarginDp,
                    onCandidatePinyinLeftMarginDpChange = { candidatePinyinLeftMarginDp = it },
                    toolbarIconBgOpacity = toolbarIconBgOpacity,
                    onToolbarIconBgOpacityChange = { toolbarIconBgOpacity = it }
                )

                SettingsSubPage.MATERIAL -> MaterialSubPageContent(
                    hyperMaterialEnabled = hyperMaterialEnabled,
                    onHyperMaterialEnabledChange = { hyperMaterialEnabled = it },
                    hyperMaterialAvailable = hyperMaterialAvailable,
                    glassSupported = glassSupported,
                    glassInput = glassInput,
                    onGlassInputChange = { index, value -> glassInput[index] = value },
                    onGlassReset = {
                        val defaults = GlassMaterialOverrides().withGlassEnabled(true)
                        GlassOverrideField.entries.forEach { glassInput[it.ordinal] = defaults.text(it) }
                    },
                    onOpenColorOsLight = {
                        WeTypeHostLauncher.launchColorOsLightPage(settingsContext as? Activity)
                    }
                )

                SettingsSubPage.CLIPBOARD -> ClipboardSubPageContent(
                    showCrossDeviceClipboard = showCrossDeviceClipboard,
                    onShowCrossDeviceClipboardChange = { showCrossDeviceClipboard = it },
                    removeClipboardRetentionLimit = removeClipboardRetentionLimit,
                    onRemoveClipboardRetentionLimitChange = { removeClipboardRetentionLimit = it },
                    removeClipboardTextLimit = removeClipboardTextLimit,
                    onRemoveClipboardTextLimitChange = { removeClipboardTextLimit = it },
                    clipboardSearchEnabled = clipboardSearchEnabled,
                    onClipboardSearchEnabledChange = { clipboardSearchEnabled = it },
                    clipboardSearchClearOnBack = clipboardSearchClearOnBack,
                    onClipboardSearchClearOnBackChange = { clipboardSearchClearOnBack = it },
                    clipboardImageAdjustRatio = clipboardImageAdjustRatio,
                    onClipboardImageAdjustRatioChange = { clipboardImageAdjustRatio = it },
                    clipboardImageCrop = clipboardImageCrop,
                    onClipboardImageCropChange = { clipboardImageCrop = it },
                    clipboardImageUniformRowHeight = clipboardImageUniformRowHeight,
                    onClipboardImageUniformRowHeightChange = { clipboardImageUniformRowHeight = it },
                    clipboardImageMaxCount = clipboardImageMaxCount,
                    onClipboardImageMaxCountChange = { clipboardImageMaxCount = it },
                    clipboardImageMaxSizeMb = clipboardImageMaxSizeMb,
                    onClipboardImageMaxSizeMbChange = { clipboardImageMaxSizeMb = it },
                    onOpenClipboardBackup = {
                        WeTypeHostLauncher.launchBackupPage(settingsContext as? Activity)
                    }
                )

                SettingsSubPage.KEYBOARD_LOGO -> KeyboardLogoSubPageContent(
                    logoEnabled = logoEnabled,
                    onLogoEnabledChange = { logoEnabled = it },
                    logoShowEnabled = logoShowEnabled,
                    onLogoShowEnabledChange = { logoShowEnabled = it },
                    logoColorMode = logoColorMode,
                    onLogoColorModeChange = { logoColorMode = it },
                    logoCustomColorInput = logoCustomColorInput,
                    onLogoCustomColorInputChange = { logoCustomColorInput = it },
                    logoImageEnabled = logoImageEnabled,
                    onLogoImageEnabledChange = { logoImageEnabled = it },
                    logoImageType = logoImageType,
                    logoSvgRecolorEnabled = logoSvgRecolorEnabled,
                    onLogoSvgRecolorEnabledChange = { logoSvgRecolorEnabled = it },
                    logoImagePngBase64 = logoImagePngBase64,
                    logoImageSvgText = logoImageSvgText,
                    logoImageName = logoImageName,
                    logoImageMessage = logoImageMessage,
                    logoImageImporting = logoImageImporting,
                    onPickLogoPng = { pickLogoPng() },
                    onPickLogoSvg = { pickLogoSvg() },
                    onActivateLogoImageType = { logoImageType = it },
                    onClearLogoImages = { clearLogoImages() },
                    onResetLogo = {
                        logoEnabled = WeTypeSettings.DEFAULT_LOGO_ENABLED
                        logoShowEnabled = WeTypeSettings.DEFAULT_LOGO_SHOW_ENABLED
                        logoColorMode = WeTypeSettings.DEFAULT_LOGO_COLOR_MODE
                        logoCustomColorInput = formatRgb(WeTypeSettings.DEFAULT_LOGO_CUSTOM_COLOR)
                        logoImageEnabled = WeTypeSettings.DEFAULT_LOGO_IMAGE_ENABLED
                        logoImageType = WeTypeSettings.DEFAULT_LOGO_IMAGE_TYPE
                        logoSvgRecolorEnabled = WeTypeSettings.DEFAULT_LOGO_SVG_RECOLOR_ENABLED
                        logoImageMessage = ""
                    }
                )
            }
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
            blockInputDuringTransition = true
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
                                    currentModeIsDark = currentModeIsDark,
                                    onModeChange = ::setPreviewDarkMode,
                                    currentColor = currentColor(),
                                    blurRadius = blurRadius,
                                    cornerRadius = cornerRadius,
                                    bottomCornerRadius = bottomCornerRadius,
                                    keyCornerRadius = keyCornerRadius,
                                    edgeHighlightEnabled = edgeHighlightEnabled,
                                    edgeHighlightIntensity = edgeHighlightIntensity,
                                    hyperMaterialEnabled = hyperMaterialEnabled,
                                    keyboardPreviewEnabled = keyboardPreviewEnabled,
                                    onKeyboardPreviewChange = ::setAppearanceStagePreview,
                                    stageGuidesEnabled = appearanceStageGuidesEnabled,
                                    onStageGuidesChange = ::setAppearanceStageGuides,
                                    wallpaperName = previewWallpaperName,
                                    onPickWallpaper = ::pickPreviewWallpaper,
                                    onClearWallpaper = ::clearPreviewWallpaper,
                                    onOpenSubPage = openSubPage,
                                    lightKeyColor = keyColorValue(false),
                                    darkKeyColor = keyColorValue(true),
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
                // 条带是真机工具栏那一行的截图，进页读一次指纹即可，不必每帧比对。
                val stripStamp = remember(subPage, screenHeightPx) {
                    KeyboardToolbarStrip.stamp(settingsContext)
                }
                val toolbarStrip = remember(stripStamp) {
                    stripStamp?.let { KeyboardToolbarStrip.read(settingsContext) }
                }
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
                            candidateBackgroundLeftMarginDp = candidateBackgroundLeftMarginDp
                                .toIntOrNull()
                                ?: WeTypeSettings.DEFAULT_CANDIDATE_BACKGROUND_LEFT_MARGIN_DP,
                            edgeHighlightEnabled = edgeHighlightEnabled,
                            edgeHighlightIntensity = edgeHighlightIntensity,
                            keyColor = keyColorValue(currentModeIsDark),
                            isDark = currentModeIsDark,
                            hyperMaterialEnabled = hyperMaterialEnabled,
                            showCornerGuide = appearanceStageGuidesEnabled,
                            accentColor = appearanceGroupColors.getOrNull(groupIndex("theme_color"))
                                ?: WeTypeSettings.DEFAULT_LOGO_CUSTOM_COLOR,
                            toolbarStrip = toolbarStrip,
                            toolbarLogo = toolbarLogo,
                            nativeLogo = nativeLogo,
                            logoMode = logoMode,
                            wallpaper = previewWallpaper
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

/**
 * 二级设置页的通用外壳：返回按钮回到一级页，右上角「刷新」让输入法进程重读配置。
 *
 * 标题栏固定不随列表滚动（`SmallTopAppBar` 一旦挂上 `MiuixScrollBehavior`，滚动时整条会被推出屏幕），
 * [pinnedContent] 因此直接排进 `topBar` 槽位，成为标题栏的一部分常驻其正下方。
 * 列表内容会从它下方穿过，所以 [pinnedContent] 需要自带不透明背景。
 *
 * [stageContent] 贴窗口底边、排在列表之外，滚动不带走 —— 键盘复刻件靠它常驻屏幕下半部。
 * 列表底部要按 [stageBottomInset] 预留同等高度，否则最后几项会被压在键盘下面。
 */
@Composable
private fun SettingsSubPageScaffold(
    title: String,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    pinnedContent: (@Composable () -> Unit)? = null,
    stageContent: (@Composable () -> Unit)? = null,
    stageBottomInset: Dp = 0.dp,
    content: LazyListScope.() -> Unit
) {
    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.background),
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MiuixTheme.colorScheme.background)
            ) {
                SmallTopAppBar(
                    title = title,
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = MiuixIcons.Back,
                                contentDescription = "返回"
                            )
                        }
                    },
                    actions = {
                        SettingsRefreshButton(onClick = onRefresh)
                    }
                )
                if (pinnedContent != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp)
                    ) {
                        pinnedContent()
                    }
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                // 宿主输入法自己升起时（键盘栏上的设置入口），靠这条把列表压到键盘之上。
                .imePadding()
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .overScrollVertical(),
                contentPadding = PaddingValues(
                    top = paddingValues.calculateTopPadding(),
                    bottom = 40.dp + stageBottomInset
                ),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                content()
            }
            if (stageContent != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                ) {
                    stageContent()
                }
            }
        }
    }
}

private fun openModuleUpdatePage(context: Context, info: ModuleUpdateInfo) {
    val url = info.targetUrl
    if (url.isBlank()) return
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.onFailure {
        Toast.makeText(context, url, Toast.LENGTH_LONG).show()
    }
}

@Composable
private fun ModuleActivationTag(
    status: ModuleActivationTracker.ActivationStatus,
    modifier: Modifier = Modifier
) {
    val backgroundColor = if (status.isActive) {
        ComposeColor(0xFF4F9A71)
    } else {
        ComposeColor(0xFFC86F67)
    }
    val label = if (status.isActive) {
        stringResource(R.string.settings_module_active_tag)
    } else {
        stringResource(R.string.settings_module_inactive_tag)
    }
    Box(
        modifier = modifier
            .clip(ContinuousRoundedRectangle(999.dp))
            .background(backgroundColor)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = ComposeColor.White,
            style = MiuixTheme.textStyles.body2
        )
    }
}

private data class PresetColorItem(
    val name: String,
    val color: Int
)

private val lightColorPresets = listOf(
    PresetColorItem("默认灰", 0xD4D4D4),
    PresetColorItem("纯白", 0xFFFFFF),
    PresetColorItem("冰川蓝", 0xD0E4F5),
    PresetColorItem("薄荷绿", 0xD2EBD9),
    PresetColorItem("樱花粉", 0xFCE4EC),
    PresetColorItem("薰衣草", 0xEDE7F6),
    PresetColorItem("暖阳米", 0xFFF3E0),
    PresetColorItem("曜石灰", 0x3C3F41)
)

private val darkColorPresets = listOf(
    PresetColorItem("默认黑", 0x000000),
    PresetColorItem("深空灰", 0x1E1E1E),
    PresetColorItem("极夜黑", 0x121212),
    PresetColorItem("夜空蓝", 0x1A2238),
    PresetColorItem("青墨绿", 0x1B262C),
    PresetColorItem("暗夜紫", 0x261C2C),
    PresetColorItem("葡萄酒", 0x2D1B22),
    PresetColorItem("炭石灰", 0x2B2D30)
)

@Composable
private fun ColorPresetPalette(
    isDark: Boolean,
    currentColorRgb: Int,
    onSelectColor: (Int) -> Unit
) {
    val presets = remember(isDark) {
        if (isDark) darkColorPresets else lightColorPresets
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            text = "预设色卡",
            style = MiuixTheme.textStyles.main
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = "点击快速应用推荐底色",
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            style = MiuixTheme.textStyles.body2
        )
        Spacer(modifier = Modifier.height(10.dp))

        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(presets.take(4), presets.drop(4)).forEach { rowPresets ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    rowPresets.forEach { item ->
                        val isSelected = (currentColorRgb and 0xFFFFFF) == (item.color and 0xFFFFFF)
                        val itemComposeColor = ComposeColor(item.color or 0xFF000000.toInt())
                        val isLight = isLightColor(item.color or 0xFF000000.toInt())

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                                .border(
                                    width = if (isSelected) 2.dp else 1.dp,
                                    color = if (isSelected) MiuixTheme.colorScheme.primary
                                    else MiuixTheme.colorScheme.outline.copy(alpha = 0.25f),
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .clickable { onSelectColor(item.color) }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .clip(ContinuousRoundedRectangle(999.dp))
                                        .background(itemComposeColor)
                                        .border(
                                            width = 1.dp,
                                            color = if (isLight) ComposeColor.Black.copy(alpha = 0.15f)
                                            else ComposeColor.White.copy(alpha = 0.2f),
                                            shape = ContinuousRoundedRectangle(999.dp)
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isSelected) {
                                        Icon(
                                            imageVector = MiuixIcons.Ok,
                                            contentDescription = null,
                                            tint = if (isLight) ComposeColor.Black else ComposeColor.White,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = item.name,
                                    style = MiuixTheme.textStyles.body2,
                                    fontSize = 11.sp,
                                    color = if (isSelected) MiuixTheme.colorScheme.primary
                                    else MiuixTheme.colorScheme.onSurface,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 下拉项：主文案只放短标签，解释下沉为 [DropdownItem.summary] 的 14sp 小字。
 *
 * Miuix 把下拉项的文本列写死在 216dp（`DropdownDefaults.MaxItemTextWidth`，外部改不了），16sp
 * 下一行只放得下约 13 个汉字；长标签折行后末行可能只剩一个字，孤字由此而来。
 */
private fun labeledDropdownItems(
    options: List<Pair<String, String>>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit
): List<DropdownItem> = options.mapIndexed { index, (text, summary) ->
    DropdownItem(
        text = text,
        summary = summary,
        selected = index == selectedIndex,
        onClick = { onSelect(index) }
    )
}

private fun LazyListScope.AppearanceTabContent(
    currentModeIsDark: Boolean,
    onModeChange: (Boolean) -> Unit,
    currentColor: Int,
    blurRadius: Int,
    cornerRadius: Int,
    bottomCornerRadius: Int,
    keyCornerRadius: Int,
    edgeHighlightEnabled: Boolean,
    edgeHighlightIntensity: Int,
    hyperMaterialEnabled: Boolean,
    keyboardPreviewEnabled: Boolean,
    onKeyboardPreviewChange: (Boolean) -> Unit,
    stageGuidesEnabled: Boolean,
    onStageGuidesChange: (Boolean) -> Unit,
    wallpaperName: String,
    onPickWallpaper: () -> Unit,
    onClearWallpaper: () -> Unit,
    onOpenSubPage: (SettingsSubPage) -> Unit,
    lightKeyColor: Int,
    darkKeyColor: Int,
    fontMode: Int,
    onFontModeChange: (Int) -> Unit,
    onResetFont: () -> Unit
) {
    // 1. 效果预览折叠卡片（默认收起，平滑展开）
    item {
        var isPreviewExpanded by rememberSaveable { mutableStateOf(false) }
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            Card(
                modifier = Modifier.padding(horizontal = 16.dp),
                insideMargin = PaddingValues(0.dp)
            ) {
                SwitchPreference(
                    title = "实时效果预览",
                    summary = "展开查看键盘背景与毛玻璃渲染效果",
                    checked = isPreviewExpanded,
                    onCheckedChange = { isPreviewExpanded = it }
                )
                HorizontalDivider()
                SwitchPreference(
                    title = "圆角对齐参考线",
                    summary = "在真机键盘底部两角叠上屏幕圆角与当前配置圆角的参考弧，需先打开真机键盘预览",
                    checked = stageGuidesEnabled,
                    onCheckedChange = onStageGuidesChange
                )
                // 面板是半透明的，底下不铺一层图就看不出透明度生效没有；这层图跟真机一样由用户挑。
                if (keyboardPreviewEnabled) {
                    HorizontalDivider()
                    BasicComponent(
                        title = "预览背景",
                        summary = wallpaperName.takeIf { it.isNotEmpty() }
                            ?.let { "已设置：$it，点击可换一张" }
                            ?: "未设置，键盘面板下面只有设置页底色，看不出透明度",
                        onClick = onPickWallpaper
                    )
                    if (wallpaperName.isNotEmpty()) {
                        BasicComponent(
                            title = "清除预览背景",
                            summary = "去掉这层壁纸，预览恢复成纯色底",
                            onClick = onClearWallpaper
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = isPreviewExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                ) {
                    PreviewSection(
                        color = currentColor,
                        blurRadius = blurRadius,
                        cornerRadius = cornerRadius,
                        bottomCornerRadius = bottomCornerRadius,
                        keyCornerRadius = keyCornerRadius,
                        edgeHighlightEnabled = edgeHighlightEnabled,
                        edgeHighlightIntensity = edgeHighlightIntensity,
                        lightKeyColor = lightKeyColor,
                        darkKeyColor = darkKeyColor,
                        isDark = currentModeIsDark,
                        hyperMaterialEnabled = hyperMaterialEnabled,
                        keyboardPreviewEnabled = keyboardPreviewEnabled,
                        onToggleKeyboardPreview = { onKeyboardPreviewChange(!keyboardPreviewEnabled) },
                        onToggleDarkMode = { onModeChange(!currentModeIsDark) }
                    )
                }
            }
        }
    }

    // 2. 外观设置入口
    item {
        SmallTitle(
            text = stringResource(R.string.settings_group_appearance)
        )
        Card(
            modifier = Modifier.padding(horizontal = 16.dp),
            insideMargin = PaddingValues(0.dp)
        ) {
            Column {
                ArrowPreference(
                    title = SettingsSubPage.COLORS.title,
                    summary = "背景颜色与不透明度、按键颜色、品牌强调色",
                    onClick = { onOpenSubPage(SettingsSubPage.COLORS) }
                )
                HorizontalDivider()
                ArrowPreference(
                    title = SettingsSubPage.KEYBOARD_LOGO.title,
                    summary = "替换 Logo 显示、主体颜色与自定义图片",
                    onClick = { onOpenSubPage(SettingsSubPage.KEYBOARD_LOGO) }
                )
                HorizontalDivider()
                ArrowPreference(
                    title = SettingsSubPage.MATERIAL.title,
                    summary = "系统材质开关、毛玻璃参数与 ColorOS 光感",
                    onClick = { onOpenSubPage(SettingsSubPage.MATERIAL) }
                )
                HorizontalDivider()
                ArrowPreference(
                    title = SettingsSubPage.CORNER_BLUR.title,
                    summary = "键盘模糊强度与顶部、底部、按键圆角",
                    onClick = { onOpenSubPage(SettingsSubPage.CORNER_BLUR) }
                )
                HorizontalDivider()
                ArrowPreference(
                    title = SettingsSubPage.CANDIDATE_TOOLBAR.title,
                    summary = "候选词背景、候选栏边距与工具栏图标",
                    onClick = { onOpenSubPage(SettingsSubPage.CANDIDATE_TOOLBAR) }
                )
            }
        }
    }

    // 3. 字体替换卡片
    item {
        val context = LocalContext.current
        val fontModeOptions = listOf(
            WeTypeSettings.FONT_MODE_OFFICIAL,
            WeTypeSettings.FONT_MODE_MODULE,
            WeTypeSettings.FONT_MODE_SYSTEM
        )
        SmallTitle(text = "字体替换")
        Card(
            modifier = Modifier.padding(horizontal = 16.dp),
            insideMargin = PaddingValues(0.dp)
        ) {
            Column {
                OverlayDropdownPreference(
                    title = "字体来源",
                    entry = DropdownEntry(
                        items = labeledDropdownItems(
                            options = listOf(
                                "微信官方" to "放行宿主原生字体",
                                "模块内置" to "WE-Regular 优化字体",
                                "跟随系统" to "系统默认字体"
                            ),
                            selectedIndex = fontModeOptions.indexOf(fontMode).coerceAtLeast(0),
                            onSelect = { index -> onFontModeChange(fontModeOptions[index]) }
                        )
                    )
                )
                HorizontalDivider()
                ArrowPreference(
                    title = "重置字体设置",
                    summary = "恢复跟随系统默认字体",
                    onClick = {
                        onResetFont()
                        Toast.makeText(context, "字体设置已重置", Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }
    }
}

private fun LazyListScope.ColorsSubPageContent(
    currentModeIsDark: Boolean,
    onModeChange: (Boolean) -> Unit,
    currentColor: Int,
    alphaValue: Int,
    onAlphaChange: (Int) -> Unit,
    colorInput: String,
    onColorInputChange: (String) -> Unit,
    onColorSelect: (Int) -> Unit,
    appearanceSectionGroups: List<WeTypeAppearanceColorGroup>,
    appearanceGroupColors: MutableList<Int>,
    groupIndex: (String) -> Int,
    currentKeyGroup: WeTypeAppearanceColorGroup,
    currentKeyGroupIndex: Int,
    onKeyColorChange: (Int) -> Unit
) {
    item {
        SmallTitle(text = stringResource(R.string.settings_section_mode))
        Card(
            modifier = Modifier.padding(horizontal = 16.dp),
            insideMargin = PaddingValues(16.dp)
        ) {
            val tabs = listOf(
                stringResource(R.string.settings_light_mode),
                stringResource(R.string.settings_dark_mode)
            )
            TabRowWithContour(
                tabs = tabs,
                selectedTabIndex = if (currentModeIsDark) 1 else 0,
                onTabSelected = { index ->
                    onModeChange(index == 1)
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    item {
        SmallTitle(text = stringResource(R.string.settings_group_color))
        Card(
            modifier = Modifier.padding(horizontal = 16.dp),
            insideMargin = PaddingValues(0.dp)
        ) {
            Column {
                // 预设色卡快速选择器
                ColorPresetPalette(
                    isDark = currentModeIsDark,
                    currentColorRgb = currentColor and 0xFFFFFF,
                    onSelectColor = onColorSelect
                )

                HorizontalDivider()

                // 自定义 HEX 颜色输入
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = stringResource(R.string.settings_custom_color),
                        style = MiuixTheme.textStyles.main
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.settings_color_helper),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        style = MiuixTheme.textStyles.body2
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(ContinuousRoundedRectangle(999.dp))
                                .background(ComposeColor(currentColor))
                                .border(
                                    1.dp,
                                    MiuixTheme.colorScheme.outline,
                                    ContinuousRoundedRectangle(999.dp)
                                )
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        TextField(
                            value = colorInput,
                            onValueChange = onColorInputChange,
                            label = stringResource(R.string.settings_color_label),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                HorizontalDivider()

                SliderPreferenceItem(
                    title = stringResource(R.string.settings_alpha_title),
                    value = alphaValue,
                    max = 255,
                    onValueChange = onAlphaChange
                )
            }
        }
    }

    item {
        SmallTitle(text = "按键颜色")
        Card(
            modifier = Modifier.padding(horizontal = 16.dp),
            insideMargin = PaddingValues(0.dp)
        ) {
            KeyColorEditor(
                title = if (currentModeIsDark) {
                    stringResource(R.string.settings_dark_key_color_title)
                } else {
                    stringResource(R.string.settings_light_key_color_title)
                },
                summary = stringResource(
                    R.string.settings_key_color_group_summary,
                    Color.alpha(appearanceGroupColors[currentKeyGroupIndex]),
                    currentKeyGroup.entryCount
                ),
                color = appearanceGroupColors[currentKeyGroupIndex],
                onColorChange = onKeyColorChange
            )
        }
    }

    item {
        SmallTitle(text = "品牌强调色")
        Card(
            modifier = Modifier.padding(horizontal = 16.dp),
            insideMargin = PaddingValues(0.dp)
        ) {
            Column {
                appearanceSectionGroups.forEach { group ->
                    val index = groupIndex(group.id)
                    AppearanceColorGroupEditor(
                        title = group.displayName,
                        summary = stringResource(
                            R.string.settings_appearance_color_group_summary,
                            group.entryCount,
                            formatArgb(group.defaultColor)
                        ),
                        color = appearanceGroupColors[index],
                        onColorChange = { appearanceGroupColors[index] = it }
                    )
                }
            }
        }
    }
}

private fun LazyListScope.CornerBlurSubPageContent(
    hyperMaterialEnabled: Boolean,
    blurRadius: Int,
    onBlurRadiusChange: (Int) -> Unit,
    cornerRadius: Int,
    onCornerRadiusChange: (Int) -> Unit,
    bottomCornerRadius: Int,
    onBottomCornerRadiusChange: (Int) -> Unit,
    keyCornerRadius: Int,
    onKeyCornerRadiusChange: (Int) -> Unit,
    candidateBackgroundCorner: Int,
    onCandidateBackgroundCornerChange: (Int) -> Unit
) {
    item {
        SmallTitle(text = "模糊")
        Card(
            modifier = Modifier.padding(horizontal = 16.dp),
            insideMargin = PaddingValues(0.dp)
        ) {
            SliderPreferenceItem(
                title = stringResource(R.string.settings_blur_title),
                value = blurRadius,
                max = 100,
                enabled = !hyperMaterialEnabled,
                onValueChange = onBlurRadiusChange
            )
        }
    }

    item {
        SmallTitle(text = "圆角")
        Card(
            modifier = Modifier.padding(horizontal = 16.dp),
            insideMargin = PaddingValues(0.dp)
        ) {
            SliderPreferenceItem(
                title = stringResource(R.string.settings_corner_title),
                value = cornerRadius,
                max = WeTypeSettings.MAX_CORNER_RADIUS,
                onValueChange = onCornerRadiusChange
            )
            SliderPreferenceItem(
                title = stringResource(R.string.settings_bottom_corner_title),
                value = bottomCornerRadius,
                max = WeTypeSettings.MAX_BOTTOM_CORNER_RADIUS,
                onValueChange = onBottomCornerRadiusChange
            )
            SliderPreferenceItem(
                title = stringResource(R.string.settings_key_corner_title),
                value = keyCornerRadius,
                max = WeTypeSettings.MAX_KEY_CORNER_RADIUS,
                onValueChange = onKeyCornerRadiusChange
            )
            SliderPreferenceItem(
                title = stringResource(R.string.settings_candidate_corner_title),
                value = candidateBackgroundCorner,
                max = WeTypeSettings.MAX_CANDIDATE_BACKGROUND_CORNER,
                onValueChange = onCandidateBackgroundCornerChange
            )
        }
    }
}

private fun LazyListScope.CandidateToolbarSubPageContent(
    candidateBackgroundAlpha: Int,
    onCandidateBackgroundAlphaChange: (Int) -> Unit,
    candidateBackgroundLeftMarginDp: String,
    onCandidateBackgroundLeftMarginDpChange: (String) -> Unit,
    candidatePinyinLeftMarginDp: String,
    onCandidatePinyinLeftMarginDpChange: (String) -> Unit,
    toolbarIconBgOpacity: Int,
    onToolbarIconBgOpacityChange: (Int) -> Unit
) {
    item {
        SmallTitle(text = "候选词")
        Card(
            modifier = Modifier.padding(horizontal = 16.dp),
            insideMargin = PaddingValues(0.dp)
        ) {
            SliderPreferenceItem(
                title = stringResource(R.string.settings_key_color_hook_alpha_title),
                value = candidateBackgroundAlpha,
                max = 255,
                onValueChange = onCandidateBackgroundAlphaChange
            )
            NumericTextSettingItem(
                title = stringResource(R.string.settings_candidate_background_left_margin_title),
                summary = stringResource(R.string.settings_candidate_background_left_margin_desc),
                value = candidateBackgroundLeftMarginDp,
                onValueChange = { input ->
                    if (sanitizeIntegerInput(input, maxLength = 2) != null) {
                        onCandidateBackgroundLeftMarginDpChange(input)
                    }
                }
            )
            NumericTextSettingItem(
                title = stringResource(R.string.settings_candidate_pinyin_margin_title),
                summary = stringResource(R.string.settings_candidate_pinyin_margin_desc),
                value = candidatePinyinLeftMarginDp,
                onValueChange = { input ->
                    if (sanitizeIntegerInput(input, maxLength = 2) != null) {
                        onCandidatePinyinLeftMarginDpChange(input)
                    }
                }
            )
        }
    }

    item {
        SmallTitle(text = "工具栏")
        Card(
            modifier = Modifier.padding(horizontal = 16.dp),
            insideMargin = PaddingValues(0.dp)
        ) {
            SliderPreferenceItem(
                title = stringResource(R.string.settings_toolbar_icon_bg_opacity_title),
                value = toolbarIconBgOpacity,
                max = 255,
                onValueChange = onToolbarIconBgOpacityChange
            )
        }
    }
}

private fun LazyListScope.MaterialSubPageContent(
    hyperMaterialEnabled: Boolean,
    onHyperMaterialEnabledChange: (Boolean) -> Unit,
    hyperMaterialAvailable: Boolean,
    glassSupported: Boolean,
    glassInput: List<String>,
    onGlassInputChange: (Int, String) -> Unit,
    onGlassReset: () -> Unit,
    onOpenColorOsLight: () -> Unit
) {
    item {
        SmallTitle(text = "材质")
        Card(
            modifier = Modifier.padding(horizontal = 16.dp),
            insideMargin = PaddingValues(0.dp)
        ) {
            SwitchPreference(
                title = stringResource(R.string.settings_hyper_material_title),
                summary = stringResource(
                    if (hyperMaterialAvailable) R.string.settings_hyper_material_desc
                    else R.string.settings_hyper_material_unavailable
                ),
                checked = hyperMaterialEnabled,
                enabled = hyperMaterialAvailable,
                onCheckedChange = onHyperMaterialEnabledChange
            )
            if (hyperMaterialEnabled) {
                HorizontalDivider()
                GlassOverrideEditor(
                    values = glassInput,
                    enabled = hyperMaterialAvailable && glassSupported,
                    onValueChange = onGlassInputChange,
                    onReset = onGlassReset
                )
            }
        }
    }

    item {
        SmallTitle(text = "ColorOS 光感")
        Card(
            modifier = Modifier.padding(horizontal = 16.dp),
            insideMargin = PaddingValues(0.dp)
        ) {
            ArrowPreference(
                title = stringResource(R.string.settings_coloros_light_title),
                summary = stringResource(R.string.settings_coloros_light_desc),
                enabled = hyperMaterialEnabled,
                onClick = onOpenColorOsLight
            )
        }
    }
}

/** 复刻件 ⇧ 的归一化顶点（顶点 → 左肩 → 左竖杠 → 底边 → 右竖杠 → 右肩），真机键帽实测。 */
private val REPLICA_SHIFT_OUTLINE = listOf(
    0.5f to 0f,
    0f to 0.584f,
    0.278f to 0.584f,
    0.278f to 0.987f,
    0.722f to 0.987f,
    0.722f to 0.584f,
    1f to 0.584f
)

/** 复刻件 ⌫ 的归一化五边形（左尖 → 右上 → 右下），真机键帽实测。 */
private val REPLICA_BACKSPACE_OUTLINE = listOf(
    0f to 0.54f,
    0.3f to 0f,
    1f to 0f,
    1f to 1f,
    0.3f to 1f
)

/** ⌫ 内部那个 ×：两笔分开画，张开不闭合。 */
private val REPLICA_BACKSPACE_CROSS = listOf(
    listOf(0.476f to 0.324f, 0.728f to 0.703f),
    listOf(0.728f to 0.324f, 0.476f to 0.703f)
)

/** 把归一化顶点映射进 [left]/[top]/[width]/[height] 框出的矩形。 */
private fun replicaGlyphPath(
    points: List<Pair<Float, Float>>,
    left: Float,
    top: Float,
    width: Float,
    height: Float,
    close: Boolean = true
): Path = Path().apply {
    points.forEachIndexed { index, (nx, ny) ->
        val x = left + nx * width
        val y = top + ny * height
        if (index == 0) moveTo(x, y) else lineTo(x, y)
    }
    if (close) close()
}

/**
 * 描边空心画一个归一化顶点集，尺寸与笔画都取自 [ReplicaGeometry] 里的真机实测值。
 *
 * 接头固定用 [StrokeJoin.Round]：⇧ 两肩是尖角，斜接会把外沿顶出去，字形就比真机胖。
 */
@Composable
private fun ReplicaGlyph(
    points: List<Pair<Float, Float>>,
    color: ComposeColor,
    pathWidthPx: Float,
    pathHeightPx: Float,
    strokePx: Float
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val left = (size.width - pathWidthPx) / 2f
        val top = (size.height - pathHeightPx) / 2f
        drawPath(
            path = replicaGlyphPath(points, left, top, pathWidthPx, pathHeightPx),
            color = color,
            style = Stroke(width = strokePx, join = StrokeJoin.Round)
        )
    }
}

/** 候选条末尾的展开箭头 ⌄：真机墨迹框 44×26、笔画 6、圆角接头。 */
@Composable
private fun ReplicaMoreGlyph(color: ComposeColor, geometry: ReplicaGeometry) {
    val density = LocalDensity.current
    Canvas(
        modifier = Modifier.size(
            width = with(density) { geometry.candidateMoreWidthPx.toDp() },
            height = with(density) { geometry.candidateMoreHeightPx.toDp() }
        )
    ) {
        val stroke = geometry.candidateMoreStrokePx
        val half = stroke / 2f
        // 路径盒就是墨迹框每边内缩半个描边，外沿才正好落在 44×26 上。
        val path = Path().apply {
            moveTo(half, half)
            lineTo(size.width / 2f, size.height - half)
            lineTo(size.width - half, half)
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}

/** 真机 ⇧ 键帽字形。 */
@Composable
private fun ReplicaShiftGlyph(color: ComposeColor, geometry: ReplicaGeometry) {
    ReplicaGlyph(
        points = REPLICA_SHIFT_OUTLINE,
        color = color,
        pathWidthPx = geometry.shiftPathWidthPx,
        pathHeightPx = geometry.shiftPathHeightPx,
        strokePx = geometry.shiftStrokePx
    )
}

/** 真机 ⌫ 键帽字形：左侧五边形外框加内部一个 ×。 */
@Composable
private fun ReplicaBackspaceGlyph(color: ComposeColor, geometry: ReplicaGeometry) {
    ReplicaGlyph(
        points = REPLICA_BACKSPACE_OUTLINE,
        color = color,
        pathWidthPx = geometry.backspacePathWidthPx,
        pathHeightPx = geometry.backspacePathHeightPx,
        strokePx = geometry.backspaceStrokePx
    )
    Canvas(modifier = Modifier.fillMaxSize()) {
        val left = (size.width - geometry.backspacePathWidthPx) / 2f
        val top = (size.height - geometry.backspacePathHeightPx) / 2f
        val stroke = Stroke(width = geometry.backspaceCrossStrokePx)
        REPLICA_BACKSPACE_CROSS.forEach { crossLine ->
            drawPath(
                path = replicaGlyphPath(
                    crossLine,
                    left,
                    top,
                    geometry.backspacePathWidthPx,
                    geometry.backspacePathHeightPx,
                    close = false
                ),
                color = color,
                style = stroke
            )
        }
    }
}

/**
 * 二级页底部那块手绘键盘复刻件。
 *
 * 用途只有一个：对着这台机器的真实屏幕圆角比 [cornerRadius] / [bottomCornerRadius]。所以它跟
 * 真机同尺寸、同材质、同圆角绘制路径 —— 面板走 [weTypePreviewBloom] 与 [WeTypeSmoothRoundedShape]，
 * 材质与按键色的取法与 [PreviewCard] 完全同源，改「键盘外观」这里每个像素都跟着变。
 *
 * 竖直按 [geometry] 缩放，横向按窗口宽度缩放。面板铺满整个 IME 窗口高（含底部那条系统抬高），
 * 与真机一致：真机背板也是从键盘内容顶端一直铺到窗口底边（见 `resolveWeTypeBackgroundBounds`）；
 * 按键只排在键盘本体区域内，系统抬高那条不放任何键。
 *
 * 分两层画，跟真机一样：底下是 [wallpaper]（用户自己挑的「手机壁纸」，用来验面板透明度），
 * 上面才是半透明的键盘面板。
 *
 * 工具栏那一行跟真机一样是**二选一**的：没组词时画 [toolbarStrip]（真机工具栏那一行的原样截图，
 * 见 `KeyboardToolbarStrip`）加 [toolbarLogo]；一进组词整行换成候选词，logo 不画 —— 真机上这两个
 * 状态共用同一个宿主视图 `ImeCandidateView`，第一个候选的选中底正好压在 logo 那一格上。
 * 条带是快照，换 logo、改深浅色都得等下次弹键盘才刷新。
 *
 * 点任意键会往前推一格 [demoClickCount]，在两组候选词之间来回切，模拟真机候选条的排版与选中高亮；
 * 点 ⌫ 则是「退出组词」，直接退回工具栏条带那一态。
 * 第四行键帽画得出来但键面留空：真机上那一行没能可靠辨认，宁可留空也不画错。
 */
@Composable
private fun KeyboardReplica(
    geometry: ReplicaGeometry,
    color: Int,
    blurRadius: Int,
    cornerRadius: Int,
    bottomCornerRadius: Int,
    keyCornerRadius: Int,
    candidateBackgroundCorner: Float,
    candidateBackgroundAlpha: Int,
    candidateBackgroundLeftMarginDp: Int,
    edgeHighlightEnabled: Boolean,
    edgeHighlightIntensity: Int,
    keyColor: Int,
    isDark: Boolean,
    hyperMaterialEnabled: Boolean,
    showCornerGuide: Boolean,
    accentColor: Int,
    toolbarStrip: Bitmap?,
    toolbarLogo: Bitmap?,
    nativeLogo: Bitmap?,
    logoMode: ReplicaLogoMode,
    wallpaper: Bitmap?
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    // 系统材质开时预览显示 fallback 底色，与 PreviewCard 同口径。
    val displayColor = if (hyperMaterialEnabled) WeTypeSystemMaterials.fallbackColor(isDark) else color
    val topCornerDp = cornerRadius.coerceIn(0, WeTypeSettings.MAX_CORNER_RADIUS).dp
    val bottomCornerDp = bottomCornerRadius.coerceIn(0, WeTypeSettings.MAX_BOTTOM_CORNER_RADIUS).dp
    val topCornerPx = with(density) { topCornerDp.toPx() }
    val bottomCornerPx = with(density) { bottomCornerDp.toPx() }
    val panelShape = WeTypeSmoothRoundedShape(
        WeTypeCornerRadii(
            topLeft = topCornerPx,
            topRight = topCornerPx,
            bottomRight = bottomCornerPx,
            bottomLeft = bottomCornerPx
        )
    )
    val weTypeFontFamily = remember(context) {
        FontFamily(
            Font(
                path = "WE-Regular.ttf",
                assetManager = context.assets
            )
        )
    }
    val keyLabelSize = with(density) { geometry.keyLabelPx.roundToInt().toSp() }
    // 键面字形压在键帽上，所以按键帽色取可读色，而不是按面板色。
    val keyTextColor = previewTextColor(keyColor)
    // 预览里真正画哪颗 Logo。总开关关 → 宿主那颗原生的（颜色就是品牌强调色）；
    // 显示关 → 一颗都不画。
    val displayedLogo = when (logoMode) {
        ReplicaLogoMode.Custom -> toolbarLogo
        ReplicaLogoMode.Native -> nativeLogo
        ReplicaLogoMode.Hidden -> null
    }
    // 裁掉条带里 Logo 那一格的路径：四周各外扩 2px，免得快照里那颗 Logo 的抗锯齿边剩一圈毛边。
    // 三档都要裁：条带是快照，里面那颗是上一轮弹键盘时宿主/模块画上去的，不裁掉就会从叠加图
    // 的透明边（矢量回退的字面比 90px 的格子小）或者空白底下透出来。实测裁 94px 见方能盖住它。
    val stripLogoHole = remember(geometry) {
        val left = (geometry.toolbarLogoLeftPx - 2).toFloat()
        val top = (geometry.toolbarLogoTopPx - geometry.toolbarTopPx - 2).toFloat()
        Path().apply {
            addRect(
                Rect(
                    left = left,
                    top = top,
                    right = left + geometry.toolbarLogoSizePx + 4f,
                    bottom = top + geometry.toolbarLogoSizePx + 4f
                )
            )
        }
    }
    // 点一下键往前推一格：0 是还没点过（照常显示真机工具栏条带），之后在两组候选词之间来回切。
    var demoClickCount by remember { mutableIntStateOf(0) }
    val candidateLabels = replicaCandidateLabels(demoClickCount)
    val candidateLabelSize = with(density) { geometry.candidateLabelPx.roundToInt().toSp() }
    // 选中态：底用键帽色（实测 240，与键帽 242 同源），字直接用品牌强调色本身
    // —— 真机实测那条「吃」就是 (47,128,237)，不是按底算出来的可读色。
    val candidateHighlightTextColor = ComposeColor(accentColor)
    val candidateIdleTextColor = previewTextColor(displayColor)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(with(density) { geometry.totalHeightPx.toDp() })
            .clearAndSetSemantics {}
    ) {
        // 第一层：手机壁纸。真机键盘面板是半透明的，底下不铺点东西就完全看不出透明度生效没有。
        if (wallpaper != null) {
            Image(
                bitmap = wallpaper.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize()
            )
        }
        // 第二层：键盘面板本体，铺满整个 IME 窗口高（含底部那条系统抬高）。
        Box(
            modifier = Modifier
                .matchParentSize()
                .weTypePreviewBloom(
                    color = displayColor,
                    topCornerRadius = topCornerDp,
                    bottomCornerRadius = bottomCornerDp,
                    edgeHighlightEnabled = edgeHighlightEnabled &&
                        (!hyperMaterialEnabled || WeTypeSystemMaterials.isColorOsBackend()),
                    edgeHighlightIntensity = edgeHighlightIntensity,
                    isDark = isDark
                )
                .clip(panelShape)
        ) {
            if (hyperMaterialEnabled) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(ComposeColor(displayColor))
                )
            } else {
                // 磨砂背板。真机的毛玻璃模糊的是键盘底下那层东西，所以有壁纸时模糊的就是壁纸本身；
                // 没设壁纸才退回内置纹理。纹理是不透明 JPG，拿它当背板会把壁纸整片盖死，
                // 底下的不透明度滑块也就白调了。
                val backdrop: Painter = wallpaper
                    ?.let { BitmapPainter(it.asImageBitmap()) }
                    ?: painterResource(R.drawable.natural_texture_004)
                Image(
                    painter = backdrop,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .matchParentSize()
                        .blur((blurRadius / 3f).coerceAtLeast(0f).dp)
                )
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(ComposeColor(color))
                )
            }
            val toolbarTopDp = with(density) { geometry.toolbarTopPx.toDp() }
            val toolbarHeightDp = with(density) { geometry.toolbarHeightPx.toDp() }
            // 开始点键模拟候选词之后就把真机条带撤掉，免得它那排图标跟候选词叠在一起。
            if (toolbarStrip != null && candidateLabels.isEmpty()) {
                Image(
                    bitmap = toolbarStrip.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier
                        .offset(y = toolbarTopDp)
                        .fillMaxWidth()
                        .height(toolbarHeightDp)
                        .drawWithContent {
                            val hole = stripLogoHole
                            if (hole == null) {
                                this@drawWithContent.drawContent()
                            } else {
                                clipPath(hole, clipOp = ClipOp.Difference) {
                                    this@drawWithContent.drawContent()
                                }
                            }
                        }
                )
            }
            // logo 只在没组词时画在真机 logo 位上：真机一进组词，候选条整行接管，
            // 第一个候选的选中底正好压在 logo 那一格上（条带是快照，换 logo、改深浅色要等下次弹键盘才刷新）。
            if (displayedLogo != null && candidateLabels.isEmpty()) {
                Image(
                    bitmap = displayedLogo.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .offset(
                            x = with(density) { geometry.toolbarLogoLeftPx.toDp() },
                            y = with(density) { geometry.toolbarLogoTopPx.toDp() }
                        )
                        .size(with(density) { geometry.toolbarLogoSizePx.toDp() })
                )
            }
            if (candidateLabels.isNotEmpty()) {
                val itemPaddingDp = with(density) { geometry.candidateItemPaddingPx.toDp() }
                val highlightHeightDp = with(density) { geometry.candidateHighlightHeightPx.toDp() }
                // 第一项左沿：行基础左沿 + 「候选背景左边距」× density（宿主就是 applyDimension(DIP, …)）。
                val candidateRowLeftDp = with(density) {
                    replicaCandidateRowLeftPx(
                        baseLeftPx = geometry.candidateRowLeftPx,
                        marginDp = candidateBackgroundLeftMarginDp,
                        density = this.density
                    ).toDp()
                }
                // 选中底：正圆弧角（宿主自己 drawRoundRect），半径口径见 replicaCandidateCornerPx。
                val highlightShape = RoundedCornerShape(
                    with(density) {
                        replicaCandidateCornerPx(
                            cornerSetting = candidateBackgroundCorner,
                            highlightHeightPx = geometry.candidateHighlightHeightPx
                        ).toDp()
                    }
                )
                // 基色是宿主自己的候选底颜色（模块只压 alpha），不是键帽色。
                val highlightFill = ComposeColor(replicaCandidateBaseColor(isDark)).copy(
                    alpha = replicaCandidateFillAlpha(candidateBackgroundAlpha)
                )
                // 候选词自己摆基线画，不交给 Text 居中 —— CJK 回退字体的行盒上下不对称，
                // 居中会把 CJK 墨迹压到真机位置下方 0.17em。先量好排版盒，才知道选中底要拉多宽。
                val candidateTextStyle = remember(candidateLabelSize, weTypeFontFamily) {
                    TextStyle(fontSize = candidateLabelSize, fontFamily = weTypeFontFamily)
                }
                val candidateMeasurer = rememberTextMeasurer()
                val candidateLayouts = remember(candidateLabels, candidateTextStyle) {
                    candidateLabels.map {
                        candidateMeasurer.measure(AnnotatedString(it), candidateTextStyle)
                    }
                }
                val candidateBaselinePx = with(density) { geometry.candidateBaselineOffsetPx }
                Row(
                    modifier = Modifier
                        .offset(
                            x = candidateRowLeftDp,
                            y = toolbarTopDp + with(density) { geometry.candidateContentDropPx.toDp() }
                        )
                        .height(with(density) { geometry.candidateRowHeightPx.toDp() }),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(
                            with(density) { geometry.candidateItemGapPx.toDp() }
                        )
                    ) {
                        candidateLayouts.forEachIndexed { index, layout ->
                            // 真机候选条：只有第一个候选坐在一块方底上、字用强调色；其余是纯文字。
                            val highlighted = index == 0
                            Box(
                                modifier = Modifier
                                    .height(highlightHeightDp)
                                    .then(
                                        if (highlighted) {
                                            Modifier
                                                .clip(highlightShape)
                                                .background(highlightFill)
                                        } else {
                                            Modifier
                                        }
                                    )
                                    .padding(horizontal = itemPaddingDp),
                                contentAlignment = Alignment.Center
                            ) {
                                Canvas(
                                    modifier = Modifier.size(
                                        width = with(density) { layout.size.width.toDp() },
                                        height = with(density) { layout.size.height.toDp() }
                                    )
                                ) {
                                    // 画布竖直居中，所以基线 = 画布中线再往下 candidateBaselinePx。
                                    val baselineY = size.height / 2f + candidateBaselinePx
                                    drawText(
                                        textLayoutResult = layout,
                                        color = if (highlighted) {
                                            candidateHighlightTextColor
                                        } else {
                                            candidateIdleTextColor
                                        },
                                        topLeft = Offset(0f, baselineY - layout.firstBaseline)
                                    )
                                }
                            }
                        }
                    }
                    Spacer(
                        Modifier.width(with(density) { geometry.candidateDividerGapPx.toDp() })
                    )
                    Box(
                        modifier = Modifier
                            .width(with(density) { geometry.candidateDividerWidthPx.toDp() })
                            .height(with(density) { geometry.candidateDividerHeightPx.toDp() })
                            .background(candidateIdleTextColor.copy(alpha = 0.2f))
                    )
                    Spacer(Modifier.width(with(density) { geometry.candidateMoreGapPx.toDp() }))
                    ReplicaMoreGlyph(
                        color = candidateIdleTextColor.copy(alpha = 0.6f),
                        geometry = geometry
                    )
                }
            }
            geometry.rows.forEachIndexed { rowIndex, row ->
                val faces = REPLICA_KEY_FACES.getOrNull(rowIndex).orEmpty()
                val rowTopDp = with(density) { row.topPx.toDp() }
                val keyHeightDp = with(density) { row.heightPx.toDp() }
                row.keys.forEachIndexed { keyIndex, key ->
                    val face = faces.getOrNull(keyIndex) ?: ReplicaKeyFace.Blank
                    // 真机键帽是正圆弧角矩形（宿主自己 drawRoundRect 画的），不是连续圆角，
                    // 半径口径见 replicaKeyCornerPx：像素值 = min(设置 + 10, 键宽 / 2)。
                    val keyShape = RoundedCornerShape(
                        with(density) { replicaKeyCornerPx(keyCornerRadius, key.widthPx).toDp() }
                    )
                    Box(
                        modifier = Modifier
                            .offset(
                                x = with(density) { key.leftPx.toDp() },
                                y = rowTopDp
                            )
                            .size(
                                width = with(density) { key.widthPx.toDp() },
                                height = keyHeightDp
                            )
                            .clip(keyShape)
                            .background(ComposeColor(keyColor))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = {
                                    demoClickCount = replicaClickCountAfter(face, demoClickCount)
                                }
                            )
                    ) {
                        when (face) {
                            is ReplicaKeyFace.Letter -> Text(
                                text = face.text,
                                color = keyTextColor,
                                fontSize = keyLabelSize,
                                fontFamily = weTypeFontFamily,
                                modifier = Modifier.align(Alignment.Center)
                            )
                            ReplicaKeyFace.Shift -> ReplicaShiftGlyph(keyTextColor, geometry)
                            ReplicaKeyFace.Backspace -> ReplicaBackspaceGlyph(keyTextColor, geometry)
                            ReplicaKeyFace.Blank -> Unit
                        }
                    }
                }
            }
            if (showCornerGuide) {
                KeyboardCornerGuide(
                    screenCornerRadiusPx = resolveScreenCornerRadiusPx(LocalView.current),
                    panelBottomCornerPx = bottomCornerPx
                )
            }
        }
    }
}

/**
 * 底部两角的参考弧：蓝线是这台机器屏幕自己的圆角，红线是当前配置的
 * [bottomCornerRadius]。两条贴合即说明配置跟屏幕对了。
 */
@Composable
private fun KeyboardCornerGuide(screenCornerRadiusPx: Float?, panelBottomCornerPx: Float) {
    val strokeWidth = with(LocalDensity.current) { 2.dp.toPx() }
    Canvas(modifier = Modifier.fillMaxSize()) {
        val bottom = size.height
        fun drawCornerPair(radius: Float, color: ComposeColor) {
            if (radius <= 0f || radius > size.width / 2f || radius > bottom) return
            drawArc(
                color = color,
                startAngle = 90f,
                sweepAngle = 90f,
                useCenter = false,
                topLeft = Offset(0f, bottom - radius),
                size = Size(radius, radius),
                style = Stroke(width = strokeWidth)
            )
            drawArc(
                color = color,
                startAngle = 0f,
                sweepAngle = 90f,
                useCenter = false,
                topLeft = Offset(size.width - radius, bottom - radius),
                size = Size(radius, radius),
                style = Stroke(width = strokeWidth)
            )
        }
        screenCornerRadiusPx?.let { drawCornerPair(it, ComposeColor(0xFF2196F3)) }
        drawCornerPair(panelBottomCornerPx, ComposeColor(0xFFE53935))
    }
}

@Composable
private fun PreviewSection(
    color: Int,
    blurRadius: Int,
    cornerRadius: Int,
    bottomCornerRadius: Int,
    keyCornerRadius: Int,
    edgeHighlightEnabled: Boolean,
    edgeHighlightIntensity: Int,
    lightKeyColor: Int,
    darkKeyColor: Int,
    isDark: Boolean,
    hyperMaterialEnabled: Boolean = false,
    pinned: Boolean = false,
    onTogglePin: (() -> Unit)? = null,
    keyboardPreviewEnabled: Boolean = false,
    onToggleKeyboardPreview: (() -> Unit)? = null,
    onToggleDarkMode: (() -> Unit)? = null
) {
    val pinToggleModifier = if (onTogglePin != null) {
        Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onTogglePin
        )
    } else {
        Modifier
    }
    Card(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .then(pinToggleModifier),
        insideMargin = PaddingValues(0.dp)
    ) {
        PreviewCard(
            color = color,
            blurRadius = blurRadius,
            cornerRadius = cornerRadius,
            bottomCornerRadius = bottomCornerRadius,
            keyCornerRadius = keyCornerRadius,
            edgeHighlightEnabled = edgeHighlightEnabled,
            edgeHighlightIntensity = edgeHighlightIntensity,
            lightKeyColor = lightKeyColor,
            darkKeyColor = darkKeyColor,
            isDark = isDark,
            hyperMaterialEnabled = hyperMaterialEnabled,
            showPinToggle = onTogglePin != null,
            pinned = pinned,
            keyboardPreviewEnabled = keyboardPreviewEnabled,
            onToggleKeyboardPreview = onToggleKeyboardPreview,
            onToggleDarkMode = onToggleDarkMode
        )
    }
}

@Composable
private fun PreviewCard(
    color: Int,
    blurRadius: Int,
    cornerRadius: Int,
    bottomCornerRadius: Int,
    keyCornerRadius: Int,
    edgeHighlightEnabled: Boolean,
    edgeHighlightIntensity: Int,
    lightKeyColor: Int,
    darkKeyColor: Int,
    isDark: Boolean,
    hyperMaterialEnabled: Boolean = false,
    showPinToggle: Boolean = false,
    pinned: Boolean = false,
    keyboardPreviewEnabled: Boolean = false,
    onToggleKeyboardPreview: (() -> Unit)? = null,
    onToggleDarkMode: (() -> Unit)? = null
) {
    val context = LocalContext.current
    // 系统材质开时预览显示 fallback 底色（真机材质在受支持的 ROM 上生效）。
    val displayColor = if (hyperMaterialEnabled) WeTypeSystemMaterials.fallbackColor(isDark) else color
    val weTypeFontFamily = remember(context) {
        FontFamily(
            Font(
                path = "WE-Regular.ttf",
                assetManager = context.assets
            )
        )
    }
    val previewCornerValue = cornerRadius.coerceIn(0, WeTypeSettings.MAX_CORNER_RADIUS)
    val previewBottomCornerValue = bottomCornerRadius.coerceIn(0, WeTypeSettings.MAX_BOTTOM_CORNER_RADIUS)
    val previewCorner = previewCornerValue.dp
    val previewBottomCorner = previewBottomCornerValue.dp
    val previewMinHeight = maxOf(88.dp, (previewCornerValue + previewBottomCornerValue).dp)
    val previewRadiusPx = with(LocalDensity.current) { previewCorner.toPx() }
    val previewBottomRadiusPx = with(LocalDensity.current) { previewBottomCorner.toPx() }
    val previewShape = WeTypeSmoothRoundedShape(
        WeTypeCornerRadii(
            topLeft = previewRadiusPx,
            topRight = previewRadiusPx,
            bottomRight = previewBottomRadiusPx,
            bottomLeft = previewBottomRadiusPx
        )
    )
    val previewKeyColor = if (isDark) darkKeyColor else lightKeyColor
    val previewKeyShape = ContinuousRoundedRectangle(keyCornerRadius.dp)
    // 真机键盘预览的读数：屏幕圆角与宿主实测键盘高度，都是「配置跟这台机器贴不贴」的依据。
    val previewView = LocalView.current
    val previewDensity = LocalDensity.current
    var screenCornerRadiusPx by remember(previewView) {
        mutableStateOf(resolveScreenCornerRadiusPx(previewView))
    }
    LaunchedEffect(previewView) {
        // 首次组合时窗口 insets 可能还没下发，等一帧补读一次。
        if (screenCornerRadiusPx == null) {
            delay(120L)
            screenCornerRadiusPx = resolveScreenCornerRadiusPx(previewView)
        }
    }
    val screenCornerRadiusDp = screenCornerRadiusPx?.let { with(previewDensity) { it.toDp().value } }
    val hostKeyboardHeightPx = if (keyboardPreviewEnabled) {
        val windowHeightPx = with(previewDensity) {
            LocalConfiguration.current.screenHeightDp.dp.roundToPx()
        }
        WeTypeKeyboardMetrics.keyboardHeightForWindow(context, windowHeightPx)
    } else {
        null
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 20.dp, end = 16.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = previewMinHeight)
                    .weTypePreviewBloom(
                        color = displayColor,
                        topCornerRadius = previewCorner,
                        bottomCornerRadius = previewBottomCorner,
                        edgeHighlightEnabled = edgeHighlightEnabled &&
                            (!hyperMaterialEnabled || WeTypeSystemMaterials.isColorOsBackend()),
                        edgeHighlightIntensity = edgeHighlightIntensity,
                        isDark = isDark
                    )
                    .clip(previewShape)
            ) {
                if (hyperMaterialEnabled) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(ComposeColor(displayColor))
                    )
                } else {
                    Image(
                        painter = painterResource(R.drawable.natural_texture_004),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .matchParentSize()
                            .blur((blurRadius / 3f).coerceAtLeast(0f).dp)
                    )
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(ComposeColor(color))
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isDark) stringResource(R.string.settings_preview_mode_dark) else stringResource(R.string.settings_preview_mode_light),
                            color = previewTextColor(displayColor).copy(alpha = 0.7f),
                            style = MiuixTheme.textStyles.body2
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .clip(previewKeyShape)
                                    .background(ComposeColor(previewKeyColor))
                                    .padding(horizontal = 14.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = if (hyperMaterialEnabled) stringResource(R.string.settings_hyper_material_preview) else formatArgb(color),
                                    color = previewTextColor(displayColor),
                                    style = MiuixTheme.textStyles.headline1,
                                    fontFamily = weTypeFontFamily
                                )
                            }

                            for (i in 'A'..'C') {
                                Box(
                                    modifier = Modifier
                                        .clip(previewKeyShape)
                                        .background(ComposeColor(previewKeyColor))
                                        .padding(horizontal = 14.dp, vertical = 8.dp)
                                ) {
                                Text(
                                    text = i.toString(),
                                    color = previewTextColor(displayColor),
                                    style = MiuixTheme.textStyles.headline1,
                                    fontFamily = weTypeFontFamily
                                )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (hyperMaterialEnabled) {
                                "${stringResource(R.string.settings_corner_label)} $cornerRadius / $bottomCornerRadius"
                            } else {
                                "${stringResource(R.string.settings_blur_label)} $blurRadius · ${stringResource(R.string.settings_corner_label)} $cornerRadius / $bottomCornerRadius"
                            },
                            color = previewTextColor(displayColor).copy(alpha = 0.7f),
                            style = MiuixTheme.textStyles.body2
                        )
                        if (keyboardPreviewEnabled) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = buildString {
                                    append(
                                        hostKeyboardHeightPx?.let { "键盘高度 ${it}px" }
                                            ?: "键盘高度 未测到"
                                    )
                                    append(" · ")
                                    append(
                                        screenCornerRadiusDp?.let { "屏幕圆角 ${it.roundToInt()}dp" }
                                            ?: "屏幕圆角 未知"
                                    )
                                },
                                color = previewTextColor(displayColor).copy(alpha = 0.7f),
                                style = MiuixTheme.textStyles.body2
                            )
                        }
                    }
                    // 三个图标顶到键盘背景右上角，与模式标签同一行：切浅色/深色、召唤真机键盘、固定预览。
                    Row(
                        modifier = Modifier.padding(start = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (onToggleDarkMode != null) {
                            Icon(
                                imageVector = MiuixIcons.Theme,
                                contentDescription = if (isDark) {
                                    "切换到浅色预览"
                                } else {
                                    "切换到深色预览"
                                },
                                tint = previewTextColor(displayColor).copy(alpha = 0.85f),
                                modifier = Modifier
                                    .size(20.dp)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = onToggleDarkMode
                                    )
                            )
                        }
                        if (onToggleKeyboardPreview != null) {
                            Icon(
                                imageVector = MiuixIcons.GridView,
                                contentDescription = if (keyboardPreviewEnabled) {
                                    "收起真机键盘预览"
                                } else {
                                    "召唤真机键盘预览"
                                },
                                tint = previewTextColor(displayColor).copy(
                                    alpha = if (keyboardPreviewEnabled) 0.9f else 0.45f
                                ),
                                modifier = Modifier
                                    .size(20.dp)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = onToggleKeyboardPreview
                                    )
                            )
                        }
                        if (showPinToggle) {
                            Icon(
                                imageVector = if (pinned) MiuixIcons.Unpin else MiuixIcons.Pin,
                                contentDescription = if (pinned) {
                                    "取消固定预览"
                                } else {
                                    "把预览固定在标题栏下方"
                                },
                                tint = previewTextColor(displayColor).copy(alpha = 0.85f),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}


@Composable
private fun Modifier.weTypePreviewBloom(
    color: Int,
    topCornerRadius: androidx.compose.ui.unit.Dp,
    bottomCornerRadius: androidx.compose.ui.unit.Dp,
    edgeHighlightEnabled: Boolean,
    edgeHighlightIntensity: Int,
    isDark: Boolean
): Modifier {
    val context = LocalContext.current
    val density = LocalDensity.current
    val previewContext = remember(context, isDark) {
        createPreviewContext(context, isDark)
    }
    val topRadiusPx = with(density) { topCornerRadius.toPx() }
    val bottomRadiusPx = with(density) { bottomCornerRadius.toPx() }
    return this.drawWithCache {
        val previewCornerRadii = WeTypeCornerRadii(
            topLeft = topRadiusPx,
            topRight = topRadiusPx,
            bottomRight = bottomRadiusPx,
            bottomLeft = bottomRadiusPx
        )
        val widthPx = size.width.roundToInt()
        val heightPx = size.height.roundToInt()
        // The bloom overlay relies on clipPath + BlurMaskFilter + Path.op, which are not reliably
        // supported on Compose's hardware-accelerated recording canvas and crash the preview. Render
        // it once into an offscreen software bitmap (which supports every operation) and blit the
        // result, keeping the preview pixel-accurate.
        val overlayBitmap = if (edgeHighlightEnabled && widthPx > 0 && heightPx > 0) {
            runCatching {
                val bloomDrawable = WeTypeBloomStrokeDrawable(
                    context = previewContext,
                    cornerRadii = previewCornerRadii,
                    surfaceColor = color,
                    intensityScale = edgeHighlightIntensity / 100f
                )
                bloomDrawable.setBounds(0, 0, widthPx, heightPx)
                val clipPath = createWeTypeSmoothRoundedPath(
                    width = widthPx.toFloat(),
                    height = heightPx.toFloat(),
                    cornerRadii = previewCornerRadii
                )
                Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888).also { bitmap ->
                    val bitmapCanvas = Canvas(bitmap)
                    bitmapCanvas.clipPath(clipPath)
                    bloomDrawable.draw(bitmapCanvas)
                }
            }.getOrNull()
        } else {
            null
        }

        onDrawWithContent {
            drawContent()
            val bitmap = overlayBitmap ?: return@onDrawWithContent
            drawIntoCanvas { canvas ->
                canvas.nativeCanvas.drawBitmap(bitmap, 0f, 0f, null)
            }
        }
    }
}

private fun createPreviewContext(baseContext: Context, isDark: Boolean): Context {
    val configuration = Configuration(baseContext.resources.configuration).apply {
        uiMode =
            (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (isDark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
    }
    return baseContext.createConfigurationContext(configuration)
}

@Composable
private fun AppearanceColorGroupEditor(
    title: String,
    summary: String,
    color: Int,
    onColorChange: (Int) -> Unit
) {
    var input by rememberSaveable(title) { mutableStateOf(formatArgb(color)) }

    LaunchedEffect(color) {
        val formatted = formatArgb(color)
        if (!input.equals(formatted, ignoreCase = true) && parseHexColor(input) != color) {
            input = formatted
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(32.dp)
                    .height(32.dp)
                    .clip(ContinuousRoundedRectangle(999.dp))
                    .background(ComposeColor(color))
                    .border(1.dp, MiuixTheme.colorScheme.outline, ContinuousRoundedRectangle(999.dp))
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = title,
                    style = MiuixTheme.textStyles.main
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = summary,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.body2
                )
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        TextField(
            value = input,
            onValueChange = { raw ->
                val sanitized = sanitizeHexColorInput(raw) ?: return@TextField
                input = sanitized
                parseHexColor(sanitized)?.let(onColorChange)
            },
            label = stringResource(R.string.settings_appearance_color_input_label),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun KeyColorEditor(
    title: String,
    summary: String,
    color: Int,
    onColorChange: (Int) -> Unit
) {
    var input by rememberSaveable(title) { mutableStateOf(formatRgb(color)) }

    LaunchedEffect(color) {
        val formatted = formatRgb(color)
        val normalizedColor = Color.rgb(Color.red(color), Color.green(color), Color.blue(color))
        if (!input.equals(formatted, ignoreCase = true) && parseRgbColor(input) != normalizedColor) {
            input = formatted
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 16.dp)
        ) {
            Text(
                text = title,
                style = MiuixTheme.textStyles.main
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = summary,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.body2
            )
        }
        // Per-mode key opacity, baked directly into the key color's alpha channel (same logic as
        // the background opacity slider above).
        SliderPreferenceItem(
            title = stringResource(R.string.settings_key_opacity_title),
            value = Color.alpha(color),
            max = 255,
            onValueChange = { alpha ->
                onColorChange((alpha shl 24) or (color and 0xFFFFFF))
            }
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
        ) {
            TextField(
                value = input,
                onValueChange = { raw ->
                    val sanitized = sanitizeRgbColorInput(raw) ?: return@TextField
                    input = sanitized
                    parseRgbColor(sanitized)?.let { rgb ->
                        onColorChange((color and 0xFF000000.toInt()) or (rgb and 0xFFFFFF))
                    }
                },
                label = stringResource(R.string.settings_key_color_input_label),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun NumericTextSettingItem(
    title: String,
    summary: String,
    value: String,
    onValueChange: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Text(
            text = title,
            style = MiuixTheme.textStyles.main
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = summary,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            style = MiuixTheme.textStyles.body2
        )
        Spacer(modifier = Modifier.height(12.dp))
        TextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            label = "DP"
        )
    }
}

@Composable
private fun SliderPreferenceItem(
    title: String,
    value: Int,
    max: Int,
    enabled: Boolean = true,
    onValueChange: (Int) -> Unit
) = SliderPreference(
    value = value.toFloat(),
    onValueChange = { onValueChange(it.roundToInt()) },
    title = title,
    valueText = value.toString(),
    enabled = enabled,
    valueRange = 0f..max.toFloat(),
    steps = (max - 1).coerceAtLeast(0)
)

@Composable
private fun SliderPreferenceItem(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    enabled: Boolean = true,
    format: (Float) -> String,
    onValueChange: (Float) -> Unit
) = SliderPreference(
    value = value.coerceIn(range),
    onValueChange = { onValueChange(((it / step).roundToInt() * step).coerceIn(range)) },
    title = title,
    valueText = format(value),
    enabled = enabled,
    valueRange = range,
    steps = (((range.endInclusive - range.start) / step).roundToInt() - 1).coerceAtLeast(0)
)

@Composable
private fun GlassOverrideEditor(
    values: List<String>,
    enabled: Boolean,
    onValueChange: (Int, String) -> Unit,
    onReset: () -> Unit
) {
    fun read(field: GlassOverrideField) = runCatching {
        GlassMaterialOverrides.parse(mapOf(field to values[field.ordinal]))
    }.getOrNull()
    val glass = read(GlassOverrideField.GLASS)?.glass
    val radii = read(GlassOverrideField.BLUR_RADII)?.blurRadii ?: GlassSliderParameter.startingBlurRadii()
    val bloom = read(GlassOverrideField.BLOOM)?.bloom ?: GlassSliderParameter.startingBloom()
    val valid = GlassOverrideField.entries.all { read(it) != null }
    SwitchPreference(
        title = stringResource(R.string.settings_glass_custom),
        summary = stringResource(
            if (enabled) R.string.settings_glass_custom_desc else R.string.settings_glass_unavailable
        ),
        checked = glass != null,
        enabled = enabled && valid,
        onCheckedChange = { checked ->
            val current = GlassMaterialOverrides(glass, radii, bloom)
            val updated = current.withGlassEnabled(checked)
            GlassOverrideField.entries.forEach { onValueChange(it.ordinal, updated.text(it)) }
        }
    )
    if (glass == null && valid) return
    HorizontalDivider()
    if (glass != null) {
        val labels = mapOf(
            GlassSliderParameter.LIGHT_ANGLE to R.string.settings_glass_light_angle,
            GlassSliderParameter.LIGHT_INTENSITY to R.string.settings_glass_light_intensity,
            GlassSliderParameter.REFRACTION to R.string.settings_glass_refraction,
            GlassSliderParameter.DEPTH to R.string.settings_glass_depth,
            GlassSliderParameter.SPLAY to R.string.settings_glass_splay,
            GlassSliderParameter.THICKNESS to R.string.settings_glass_thickness,
            GlassSliderParameter.EDGE_CURVE to R.string.settings_glass_edge_curve
        )
        @Composable
        fun ParameterSlider(parameter: GlassSliderParameter, bloomMode: Boolean = false) {
            val label = stringResource(
                if (!bloomMode && parameter == GlassSliderParameter.LIGHT_INTENSITY) R.string.settings_glass_edge_lighten
                else if (!bloomMode && parameter == GlassSliderParameter.SPLAY) R.string.settings_glass_lighten_angle
                else labels.getValue(parameter)
            )
            SliderPreferenceItem(
                title = if (bloomMode) stringResource(R.string.settings_glass_highlight_parameter, label) else label,
                value = if (bloomMode) parameter.readBloom(bloom) else parameter.read(glass),
                range = parameter.range,
                step = parameter.step,
                enabled = enabled && valid,
                format = { value ->
                    when (parameter) {
                        GlassSliderParameter.LIGHT_ANGLE, GlassSliderParameter.SPLAY -> "${value.roundToInt()}°"
                        GlassSliderParameter.LIGHT_INTENSITY -> "${value.roundToInt()}%"
                        GlassSliderParameter.DEPTH, GlassSliderParameter.THICKNESS -> "${value.roundToInt()} px"
                        else -> String.format(java.util.Locale.ROOT, "%.2f", value)
                    }
                },
                onValueChange = {
                    val field = if (bloomMode) GlassOverrideField.BLOOM else GlassOverrideField.GLASS
                    val updated = if (bloomMode) parameter.writeBloom(bloom, it) else parameter.write(glass, it)
                    onValueChange(field.ordinal, updated.joinToString(", "))
                }
            )
        }
        GlassSliderParameter.entries.forEach { ParameterSlider(it) }
        val unsupported = stringResource(R.string.settings_glass_unsupported)
        SliderPreferenceItem(
            title = stringResource(R.string.settings_glass_dispersion),
            value = 0f, range = 0f..100f, step = 1f,
            enabled = false, format = { unsupported }, onValueChange = {}
        )
        listOf(R.string.settings_glass_frost_small, R.string.settings_glass_frost_large).forEachIndexed { index, title ->
            SliderPreferenceItem(
                title = stringResource(title), value = radii[index].toFloat(),
                range = 0f..400f, step = 1f, enabled = enabled && valid,
                format = { "${it.roundToInt()} px" },
                onValueChange = { value ->
                    val updated = radii.toMutableList().apply { this[index] = value.roundToInt() }
                    onValueChange(GlassOverrideField.BLUR_RADII.ordinal, updated.joinToString(", "))
                }
            )
        }
        GlassSliderParameter.entries.filter { it.bloomIndex != null }.forEach {
            ParameterSlider(it, bloomMode = true)
        }
    }
    HorizontalDivider()
    var rawExpanded by rememberSaveable { mutableStateOf(false) }
    val showRaw = rawExpanded || !valid
    val arrowRotation by animateFloatAsState(
        targetValue = if (showRaw) 90f else 0f,
        animationSpec = tween(durationMillis = 200),
        label = "GlassRawArrowRotation"
    )
    BasicComponent(
        title = stringResource(R.string.settings_glass_raw),
        summary = stringResource(if (showRaw) R.string.settings_glass_collapse else R.string.settings_glass_expand),
        onClick = { rawExpanded = !rawExpanded },
        endActions = {
            Icon(
                imageVector = MiuixIcons.Basic.ArrowRight,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                modifier = Modifier.size(width = 10.dp, height = 16.dp).rotate(arrowRotation)
            )
        }
    )
    if (showRaw) GlassRawOverrideEditor(values, enabled, onValueChange)
    ArrowPreference(
        title = stringResource(R.string.settings_glass_reset),
        summary = stringResource(R.string.settings_glass_reset_desc),
        onClick = onReset
    )
}

@Composable
private fun GlassRawOverrideEditor(
    values: List<String>,
    enabled: Boolean,
    onValueChange: (Int, String) -> Unit
) {
    val titles = listOf(
        R.string.settings_glass_params, R.string.settings_glass_blur,
        R.string.settings_glass_bloom, R.string.settings_glass_type
    )
    val descriptions = listOf(
        R.string.settings_glass_params_desc, R.string.settings_glass_blur_desc,
        R.string.settings_glass_bloom_desc, R.string.settings_glass_type_desc
    )
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text(
            text = stringResource(R.string.settings_glass_raw_summary),
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
        )
        GlassOverrideField.entries.filter { it != GlassOverrideField.MATERIAL_TYPE }.forEach { field ->
            val index = field.ordinal
            val valid = GlassMaterialOverrides.isValid(field, values[index])
            Column(Modifier.fillMaxWidth().padding(top = 16.dp).alpha(if (enabled) 1f else 0.38f)) {
                Text(stringResource(titles[index]), style = MiuixTheme.textStyles.main)
                Text(
                    stringResource(descriptions[index]), style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
                Spacer(Modifier.height(8.dp))
                TextField(
                    value = values[index],
                    enabled = enabled,
                    onValueChange = { if (it.length <= 4096) onValueChange(index, it) },
                    label = stringResource(R.string.settings_glass_default),
                    singleLine = field == GlassOverrideField.MATERIAL_TYPE || field == GlassOverrideField.BLUR_RADII,
                    modifier = Modifier.fillMaxWidth()
                )
                if (!valid) {
                    Text(
                        stringResource(R.string.settings_glass_invalid),
                        color = MiuixTheme.colorScheme.error,
                        style = MiuixTheme.textStyles.body2
                    )
                }
            }
        }
    }
}

private fun previewTextColor(color: Int): ComposeColor =
    if (isLightColor(color)) ComposeColor.Black else ComposeColor.White

private fun parseLogoCustomColor(input: String): Int {
    return parseRgbColor(input) ?: WeTypeSettings.DEFAULT_LOGO_CUSTOM_COLOR
}

private fun formatRgb(color: Int): String = String.format("#%06X", color and 0xFFFFFF)

private fun formatArgb(color: Int): String = String.format("#%08X", color)

private fun sanitizeHexColorInput(input: String): String? {
    val trimmed = input.trim()
    val hasPrefix = trimmed.startsWith("#")
    val body = trimmed.removePrefix("#")
    if (body.length > 8 || !body.matches(Regex("^[0-9a-fA-F]*$"))) {
        return null
    }
    return if (hasPrefix || body.isNotEmpty()) "#$body" else ""
}

private fun sanitizeRgbColorInput(input: String): String? {
    val trimmed = input.trim()
    val hasPrefix = trimmed.startsWith("#")
    val body = trimmed.removePrefix("#")
    if (body.length > 6 || !body.matches(Regex("^[0-9a-fA-F]*$"))) {
        return null
    }
    return if (hasPrefix || body.isNotEmpty()) "#$body" else ""
}

private fun parseHexColor(input: String): Int? {
    val body = input.trim().removePrefix("#")
    return when (body.length) {
        6, 8 -> runCatching { Color.parseColor("#$body") }.getOrNull()
        else -> null
    }
}

private fun parseRgbColor(input: String): Int? {
    val body = input.trim().removePrefix("#")
    if (body.length != 6) return null
    return runCatching { Color.parseColor("#$body") }.getOrNull()
}

private fun sanitizeIntegerInput(input: String, maxLength: Int): String? {
    val trimmed = input.trim()
    if (trimmed.length > maxLength) return null
    if (trimmed.isNotEmpty() && !trimmed.matches(Regex("^\\d*$"))) return null
    return trimmed
}

private fun isLightColor(color: Int): Boolean {
    val luminance =
        (Color.red(color) * 0.299 + Color.green(color) * 0.587 + Color.blue(color) * 0.114) / 255
    return luminance > 0.5
}

private fun LazyListScope.GestureTabContent(
    qwertyGestureEnabled: Boolean,
    onQwertyGestureEnabledChange: (Boolean) -> Unit,
    t9GestureEnabled: Boolean,
    onT9GestureEnabledChange: (Boolean) -> Unit,
    gestureVibration: Boolean,
    onGestureVibrationChange: (Boolean) -> Unit,
    t9GestureVibration: Boolean,
    onT9GestureVibrationChange: (Boolean) -> Unit,
    gestureThreshold: Int,
    onGestureThresholdChange: (Int) -> Unit,
    t9GestureThreshold: Int,
    onT9GestureThresholdChange: (Int) -> Unit,
    showGestureKeyLabels: Boolean,
    onShowGestureKeyLabelsChange: (Boolean) -> Unit,
    gestureLabelTextSizeSp: Int,
    onGestureLabelTextSizeSpChange: (Int) -> Unit,
    gestureLabelAlpha: Int,
    onGestureLabelAlphaChange: (Int) -> Unit,
    gestureLabelPosition: Int,
    onGestureLabelPositionChange: (Int) -> Unit,
    gestureLabelMarginTopDp: Int,
    onGestureLabelMarginTopDpChange: (Int) -> Unit,
    gestureLabelMarginBottomDp: Int,
    onGestureLabelMarginBottomDpChange: (Int) -> Unit,
    gestureLabelMarginLeftDp: Int,
    onGestureLabelMarginLeftDpChange: (Int) -> Unit,
    gestureLabelMarginRightDp: Int,
    onGestureLabelMarginRightDpChange: (Int) -> Unit,
    gestureBindingsJson: String,
    onGestureBindingsJsonChange: (String) -> Unit
) {
    // 1. 手势总控卡片
    item {
        SmallTitle(text = "手势总控")
        Card(
            modifier = Modifier.padding(horizontal = 16.dp),
            insideMargin = PaddingValues(0.dp)
        ) {
            Column {
                SwitchPreference(
                    title = "启用 26 键 QWERTY 下滑手势",
                    summary = "全键盘按键向下滑动触发绑定动作 (默认 Z/X/C/V)",
                    checked = qwertyGestureEnabled,
                    onCheckedChange = onQwertyGestureEnabledChange
                )
                SwitchPreference(
                    title = "启用九宫格 T9 下滑手势",
                    summary = "支持 1~9 号键位向下滑动触发绑定动作",
                    checked = t9GestureEnabled,
                    onCheckedChange = onT9GestureEnabledChange
                )
                SwitchPreference(
                    title = "QWERTY 手势触觉反馈",
                    summary = "26 键手势触发时调用键盘触觉振动",
                    checked = gestureVibration,
                    onCheckedChange = onGestureVibrationChange
                )
                SwitchPreference(
                    title = "T9 九宫格手势触觉反馈",
                    summary = "九宫格手势触发时调用键盘触觉振动",
                    checked = t9GestureVibration,
                    onCheckedChange = onT9GestureVibrationChange
                )
            }
        }
    }

    // 2. 灵敏度阈值卡片
    item {
        SmallTitle(text = "灵敏度阈值")
        Card(
            modifier = Modifier.padding(horizontal = 16.dp),
            insideMargin = PaddingValues(0.dp)
        ) {
            Column {
                SliderPreferenceItem(
                    title = "QWERTY 触发滑动阈值: ${gestureThreshold} dp",
                    value = gestureThreshold,
                    max = 48,
                    onValueChange = { onGestureThresholdChange(it.coerceIn(10, 48)) }
                )
                SliderPreferenceItem(
                    title = "T9 触发滑动阈值: ${t9GestureThreshold} dp",
                    value = t9GestureThreshold,
                    max = 48,
                    onValueChange = { onT9GestureThresholdChange(it.coerceIn(10, 48)) }
                )
            }
        }
    }

    // 3. 标签样式卡片
    item {
        SmallTitle(text = "标签样式")
        Card(
            modifier = Modifier.padding(horizontal = 16.dp),
            insideMargin = PaddingValues(0.dp)
        ) {
            Column {
                SwitchPreference(
                    title = "显示按键手势标签",
                    summary = "在已绑定手势的按键上显示动作名称角标",
                    checked = showGestureKeyLabels,
                    onCheckedChange = onShowGestureKeyLabelsChange
                )
                SliderPreferenceItem(
                    title = "标签文字大小: ${gestureLabelTextSizeSp} sp",
                    value = gestureLabelTextSizeSp,
                    max = 16,
                    onValueChange = { onGestureLabelTextSizeSpChange(it.coerceIn(6, 16)) }
                )
                SliderPreferenceItem(
                    title = "标签不透明度: ${gestureLabelAlpha}",
                    value = gestureLabelAlpha,
                    max = 255,
                    onValueChange = { onGestureLabelAlphaChange(it.coerceIn(0, 255)) }
                )
                OverlayDropdownPreference(
                    title = "标签位置",
                    items = listOf("底部", "顶部"),
                    selectedIndex = when (gestureLabelPosition) {
                        WeTypeSettings.GESTURE_LABEL_POSITION_TOP -> 1
                        else -> 0
                    },
                    onSelectedIndexChange = { index ->
                        onGestureLabelPositionChange(
                            if (index == 1) {
                                WeTypeSettings.GESTURE_LABEL_POSITION_TOP
                            } else {
                                WeTypeSettings.GESTURE_LABEL_POSITION_BOTTOM
                            }
                        )
                    }
                )
                SliderPreferenceItem(
                    title = "标签上边距: ${gestureLabelMarginTopDp} dp",
                    value = gestureLabelMarginTopDp,
                    max = WeTypeSettings.GESTURE_LABEL_MARGIN_MAX_DP,
                    onValueChange = {
                        onGestureLabelMarginTopDpChange(
                            it.coerceIn(
                                WeTypeSettings.GESTURE_LABEL_MARGIN_MIN_DP,
                                WeTypeSettings.GESTURE_LABEL_MARGIN_MAX_DP
                            )
                        )
                    }
                )
                SliderPreferenceItem(
                    title = "标签下边距: ${gestureLabelMarginBottomDp} dp",
                    value = gestureLabelMarginBottomDp,
                    max = WeTypeSettings.GESTURE_LABEL_MARGIN_MAX_DP,
                    onValueChange = {
                        onGestureLabelMarginBottomDpChange(
                            it.coerceIn(
                                WeTypeSettings.GESTURE_LABEL_MARGIN_MIN_DP,
                                WeTypeSettings.GESTURE_LABEL_MARGIN_MAX_DP
                            )
                        )
                    }
                )
                SliderPreferenceItem(
                    title = "标签左边距: ${gestureLabelMarginLeftDp} dp",
                    value = gestureLabelMarginLeftDp,
                    max = WeTypeSettings.GESTURE_LABEL_MARGIN_MAX_DP,
                    onValueChange = {
                        onGestureLabelMarginLeftDpChange(
                            it.coerceIn(
                                WeTypeSettings.GESTURE_LABEL_MARGIN_MIN_DP,
                                WeTypeSettings.GESTURE_LABEL_MARGIN_MAX_DP
                            )
                        )
                    }
                )
                SliderPreferenceItem(
                    title = "标签右边距: ${gestureLabelMarginRightDp} dp",
                    value = gestureLabelMarginRightDp,
                    max = WeTypeSettings.GESTURE_LABEL_MARGIN_MAX_DP,
                    onValueChange = {
                        onGestureLabelMarginRightDpChange(
                            it.coerceIn(
                                WeTypeSettings.GESTURE_LABEL_MARGIN_MIN_DP,
                                WeTypeSettings.GESTURE_LABEL_MARGIN_MAX_DP
                            )
                        )
                    }
                )
            }
        }
    }

    // 4. 可视化键位动作映射编辑器卡片
    item {
        SmallTitle(text = "按键手势映射")
        Card(
            modifier = Modifier.padding(horizontal = 16.dp),
            insideMargin = PaddingValues(0.dp)
        ) {
            GestureKeyBindingEditor(
                bindingsJson = gestureBindingsJson,
                onBindingsChange = onGestureBindingsJsonChange
            )
        }
    }
}

@Composable
private fun GestureKeyBindingEditor(
    bindingsJson: String,
    onBindingsChange: (String) -> Unit
) {
    var selectedKeyboardTab by rememberSaveable { mutableIntStateOf(0) }
    var editingKey by remember { mutableStateOf<Char?>(null) }
    val bindings = remember(bindingsJson) { WeTypeGestureSettings.parseBindings(bindingsJson) }
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        // Tab 切换：26键 (QWERTY) 与 九宫格 (T9)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (selectedKeyboardTab == 0) MiuixTheme.colorScheme.primary.copy(alpha = 0.15f)
                        else MiuixTheme.colorScheme.surfaceContainerHigh
                    )
                    .clickable { selectedKeyboardTab = 0 }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "QWERTY",
                    style = MiuixTheme.textStyles.main,
                    color = if (selectedKeyboardTab == 0) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface,
                    fontWeight = if (selectedKeyboardTab == 0) FontWeight.Bold else FontWeight.Normal
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (selectedKeyboardTab == 1) MiuixTheme.colorScheme.primary.copy(alpha = 0.15f)
                        else MiuixTheme.colorScheme.surfaceContainerHigh
                    )
                    .clickable { selectedKeyboardTab = 1 }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "T9",
                    style = MiuixTheme.textStyles.main,
                    color = if (selectedKeyboardTab == 1) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface,
                    fontWeight = if (selectedKeyboardTab == 1) FontWeight.Bold else FontWeight.Normal
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        if (selectedKeyboardTab == 0) {
            val row1 = listOf('q', 'w', 'e', 'r', 't', 'y', 'u', 'i', 'o', 'p')
            val row2 = listOf('a', 's', 'd', 'f', 'g', 'h', 'j', 'k', 'l')
            val row3 = listOf('z', 'x', 'c', 'v', 'b', 'n', 'm')

            // Row 1
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                row1.forEach { char ->
                    Box(modifier = Modifier.weight(1f)) {
                        GestureKeyButton(
                            keyLabel = char.uppercaseChar().toString(),
                            action = bindings[char] ?: GestureAction.None,
                            onClick = { editingKey = char }
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(6.dp))

            // Row 2
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                row2.forEach { char ->
                    Box(modifier = Modifier.weight(1f)) {
                        GestureKeyButton(
                            keyLabel = char.uppercaseChar().toString(),
                            action = bindings[char] ?: GestureAction.None,
                            onClick = { editingKey = char }
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(6.dp))

            // Row 3
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                row3.forEach { char ->
                    Box(modifier = Modifier.weight(1f)) {
                        GestureKeyButton(
                            keyLabel = char.uppercaseChar().toString(),
                            action = bindings[char] ?: GestureAction.None,
                            onClick = { editingKey = char }
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(6.dp))

            // Row 4: Space
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 40.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    GestureKeyButton(
                        keyLabel = "空格 (Space)",
                        action = bindings[' '] ?: GestureAction.None,
                        onClick = { editingKey = ' ' }
                    )
                }
            }
        } else {
            val t9Rows = listOf(
                listOf('1', '2', '3'),
                listOf('4', '5', '6'),
                listOf('7', '8', '9')
            )
            t9Rows.forEachIndexed { index, row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    row.forEach { char ->
                        Box(modifier = Modifier.weight(1f)) {
                            GestureKeyButton(
                                keyLabel = char.toString(),
                                action = bindings[char] ?: GestureAction.None,
                                onClick = { editingKey = char }
                            )
                        }
                    }
                }
                if (index < t9Rows.size - 1) {
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // 快捷操作按钮：恢复默认 / 清空全部
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                    .clickable {
                        onBindingsChange(WeTypeSettings.DEFAULT_GESTURE_BINDINGS_JSON)
                        Toast.makeText(context, "已恢复默认预设 (Z=全选 / X=剪切 / C=复制 / V=粘贴)", Toast.LENGTH_SHORT).show()
                    }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "恢复默认预设",
                    style = MiuixTheme.textStyles.main,
                    color = MiuixTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                    .clickable {
                        onBindingsChange(WeTypeGestureSettings.serializeBindings(emptyMap()))
                        Toast.makeText(context, "已清空所有按键手势绑定", Toast.LENGTH_SHORT).show()
                    }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "清空全部按键",
                    style = MiuixTheme.textStyles.main,
                    color = ComposeColor(0xFFE53935),
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }

    // 动作选择对话框
    editingKey?.let { targetChar ->
        val currentAction = bindings[targetChar] ?: GestureAction.None
        val keyName = if (targetChar == ' ') "空格 (Space)" else targetChar.uppercaseChar().toString()
        val scrollState = rememberScrollState()

        OverlayDialog(
            show = true,
            title = "设置按键 [$keyName] 下滑动作",
            summary = "选择下滑此按键时触发的操作 (共 25 种动作)",
            onDismissRequest = { editingKey = null }
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .verticalScroll(scrollState)
                ) {
                    GestureAction.entries.forEach { action ->
                        val isSelected = action == currentAction
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (isSelected) MiuixTheme.colorScheme.primary.copy(alpha = 0.12f)
                                    else ComposeColor.Transparent
                                )
                                .clickable {
                                    val newMap = bindings.toMutableMap()
                                    if (action == GestureAction.None) {
                                        newMap.remove(targetChar)
                                    } else {
                                        newMap[targetChar] = action
                                    }
                                    onBindingsChange(WeTypeGestureSettings.serializeBindings(newMap))
                                    editingKey = null
                                    Toast.makeText(context, "[$keyName] 已绑定: ${action.title}", Toast.LENGTH_SHORT).show()
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "${action.id}. ${action.title}",
                                    style = MiuixTheme.textStyles.main,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface
                                )
                                if (action.shortTitle.isNotEmpty() && action.shortTitle != "\\") {
                                    Text(
                                        text = "按键标签: ${action.shortTitle}",
                                        style = MiuixTheme.textStyles.body2,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                    )
                                }
                            }
                            if (isSelected) {
                                Text(
                                    text = "✓",
                                    style = MiuixTheme.textStyles.title4,
                                    color = MiuixTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        text = "取消",
                        onClick = { editingKey = null }
                    )
                }
            }
        }
    }
}

@Composable
private fun GestureKeyButton(
    keyLabel: String,
    action: GestureAction,
    onClick: () -> Unit
) {
    val isBound = action != GestureAction.None && action != GestureAction.Disable
    val isDisable = action == GestureAction.Disable

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .border(
                width = if (isBound) 1.5.dp else 0.5.dp,
                color = when {
                    isBound -> MiuixTheme.colorScheme.primary
                    isDisable -> ComposeColor(0xFFE53935)
                    else -> MiuixTheme.colorScheme.outline.copy(alpha = 0.35f)
                },
                shape = RoundedCornerShape(6.dp)
            )
            .background(
                when {
                    isBound -> MiuixTheme.colorScheme.primary.copy(alpha = 0.12f)
                    isDisable -> ComposeColor(0xFFE53935).copy(alpha = 0.08f)
                    else -> MiuixTheme.colorScheme.surfaceContainerHigh
                }
            )
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = keyLabel,
                style = MiuixTheme.textStyles.body2,
                fontWeight = FontWeight.Bold,
                color = when {
                    isBound -> MiuixTheme.colorScheme.primary
                    isDisable -> ComposeColor(0xFFE53935)
                    else -> MiuixTheme.colorScheme.onSurface
                },
                maxLines = 1
            )
            Text(
                text = when {
                    isBound -> action.shortTitle
                    isDisable -> "禁用"
                    else -> "-"
                },
                fontSize = 9.sp,
                fontWeight = if (isBound) FontWeight.SemiBold else FontWeight.Normal,
                color = when {
                    isBound -> MiuixTheme.colorScheme.primary
                    isDisable -> ComposeColor(0xFFE53935)
                    else -> MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.6f)
                },
                maxLines = 1
            )
        }
    }
}

private fun LazyListScope.FeatureTabContent(
    onOpenSubPage: (SettingsSubPage) -> Unit,
    disableHotUpdate: Boolean,
    onDisableHotUpdateChange: (Boolean) -> Unit,
    activationStatus: ModuleActivationTracker.ActivationStatus,
    onRestoreDefaults: () -> Unit
) {
    // 1. 剪贴板增强卡片
    item {
        Card(
            modifier = Modifier.padding(horizontal = 16.dp),
            insideMargin = PaddingValues(0.dp)
        ) {
            ArrowPreference(
                title = "剪贴板增强",
                summary = "条目保留、搜索与图片缩略图，以及备份与恢复",
                onClick = { onOpenSubPage(SettingsSubPage.CLIPBOARD) }
            )
        }
    }

    // 2. 进阶系统防护卡片
    item {
        SmallTitle(text = "进阶系统防护")
        Card(
            modifier = Modifier.padding(horizontal = 16.dp),
            insideMargin = PaddingValues(0.dp)
        ) {
            Column {
                SwitchPreference(
                    title = stringResource(R.string.settings_disable_hot_update_title),
                    summary = stringResource(R.string.settings_disable_hot_update_desc),
                    checked = disableHotUpdate,
                    onCheckedChange = onDisableHotUpdateChange
                )
            }
        }
    }

    // 5. 关于与重置卡片
    item {
        val context = LocalContext.current
        SmallTitle(text = "关于与重置")
        Card(
            modifier = Modifier.padding(horizontal = 16.dp),
            insideMargin = PaddingValues(0.dp)
        ) {
            Column {
                BasicComponent(
                    title = "模块版本",
                    summary = "v${BuildConfig.VERSION_NAME} (Code ${BuildConfig.VERSION_CODE})",
                    endActions = {
                        Badge(
                            containerColor = if (activationStatus.isActive) ComposeColor(0xFF4F9A71) else ComposeColor(0xFFC86F67),
                            contentColor = ComposeColor.White
                        ) {
                            Text(text = if (activationStatus.isActive) "已激活" else "未激活")
                        }
                    }
                )
                ArrowPreference(
                    title = stringResource(R.string.settings_visit_github_title),
                    summary = "https://github.com/Costben/WeType-Enhance",
                    titleColor = BasicComponentDefaults.titleColor(
                        color = MiuixTheme.colorScheme.primary
                    ),
                    onClick = {
                        val intent = Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://github.com/Costben/WeType-Enhance")
                        )
                        context.startActivity(intent)
                    }
                )
                ArrowPreference(
                    title = stringResource(R.string.settings_reset_title),
                    summary = stringResource(R.string.settings_reset_desc),
                    onClick = onRestoreDefaults
                )
            }
        }
    }
}

private fun LazyListScope.ClipboardSubPageContent(
    showCrossDeviceClipboard: Boolean,
    onShowCrossDeviceClipboardChange: (Boolean) -> Unit,
    removeClipboardRetentionLimit: Boolean,
    onRemoveClipboardRetentionLimitChange: (Boolean) -> Unit,
    removeClipboardTextLimit: Boolean,
    onRemoveClipboardTextLimitChange: (Boolean) -> Unit,
    clipboardSearchEnabled: Boolean,
    onClipboardSearchEnabledChange: (Boolean) -> Unit,
    clipboardSearchClearOnBack: Boolean,
    onClipboardSearchClearOnBackChange: (Boolean) -> Unit,
    clipboardImageAdjustRatio: Boolean,
    onClipboardImageAdjustRatioChange: (Boolean) -> Unit,
    clipboardImageCrop: Boolean,
    onClipboardImageCropChange: (Boolean) -> Unit,
    clipboardImageUniformRowHeight: Boolean,
    onClipboardImageUniformRowHeightChange: (Boolean) -> Unit,
    clipboardImageMaxCount: Int,
    onClipboardImageMaxCountChange: (Int) -> Unit,
    clipboardImageMaxSizeMb: Int,
    onClipboardImageMaxSizeMbChange: (Int) -> Unit,
    onOpenClipboardBackup: () -> Unit
) {
    item {
        SmallTitle(text = "基础增强")
        Card(
            modifier = Modifier.padding(horizontal = 16.dp),
            insideMargin = PaddingValues(0.dp)
        ) {
            SwitchPreference(
                title = "跨设备条目可见化持久保存",
                summary = "自动将多端同步的剪贴板远程条目转换为本地可见条目保存",
                checked = showCrossDeviceClipboard,
                onCheckedChange = onShowCrossDeviceClipboardChange
            )
            SwitchPreference(
                title = "解除保留上限与时长限制",
                summary = "剪贴板保存条数上限提升至 100,000 条，留存时长永久",
                checked = removeClipboardRetentionLimit,
                onCheckedChange = onRemoveClipboardRetentionLimitChange
            )
            SwitchPreference(
                title = "解除单条文本长度限制",
                summary = "剪贴板文本长度上限提升至 1 亿字符，抑制超限提示",
                checked = removeClipboardTextLimit,
                onCheckedChange = onRemoveClipboardTextLimitChange
            )
        }
    }

    item {
        SmallTitle(text = "搜索")
        Card(
            modifier = Modifier.padding(horizontal = 16.dp),
            insideMargin = PaddingValues(0.dp)
        ) {
            SwitchPreference(
                title = "剪贴板搜索",
                summary = "在剪贴板页面显示搜索框，按文本拼音分词过滤",
                checked = clipboardSearchEnabled,
                onCheckedChange = onClipboardSearchEnabledChange
            )
            SwitchPreference(
                title = "返回时清理搜索关键词",
                summary = "在剪贴板页面点击返回键时清理搜索关键词，恢复完整列表；关闭则保留上次搜索结果",
                checked = clipboardSearchClearOnBack,
                onCheckedChange = onClipboardSearchClearOnBackChange
            )
        }
    }

    item {
        SmallTitle(text = "图片缩略图")
        Card(
            modifier = Modifier.padding(horizontal = 16.dp),
            insideMargin = PaddingValues(0.dp)
        ) {
            SwitchPreference(
                title = "图片缩略图保持原比例",
                summary = "宽度按原图比例缩放，最长不超过行宽；关闭后缩略图统一为正方形",
                checked = clipboardImageAdjustRatio,
                onCheckedChange = onClipboardImageAdjustRatioChange
            )
            SwitchPreference(
                title = "图片缩略图裁剪填满",
                summary = "在缩略图框内居中裁剪填满，可能裁掉图片边缘；关闭则完整显示",
                checked = clipboardImageCrop,
                onCheckedChange = onClipboardImageCropChange
            )
            SwitchPreference(
                title = "图片缩略图统一行高",
                summary = "所有图片条目统一为两行高度；关闭后图片按单行高度显示",
                checked = clipboardImageUniformRowHeight,
                onCheckedChange = onClipboardImageUniformRowHeightChange
            )
            val imageCountOptions = listOf(0, 20, 50, 100, 200, 500)
            val imageSizeOptions = listOf(0, 128, 256, 512, 1024, 2048)
            OverlayDropdownPreference(
                title = "图片数量上限",
                items = imageCountOptions.map { if (it == 0) "无上限" else "$it 张" },
                selectedIndex = imageCountOptions.indexOf(clipboardImageMaxCount).coerceAtLeast(0),
                onSelectedIndexChange = { index ->
                    onClipboardImageMaxCountChange(imageCountOptions[index])
                }
            )
            OverlayDropdownPreference(
                title = "图片容量上限",
                items = imageSizeOptions.map {
                    when {
                        it == 0 -> "无上限"
                        it >= 1024 -> "${it / 1024} GB"
                        else -> "$it MB"
                    }
                },
                selectedIndex = imageSizeOptions.indexOf(clipboardImageMaxSizeMb).coerceAtLeast(0),
                onSelectedIndexChange = { index ->
                    onClipboardImageMaxSizeMbChange(imageSizeOptions[index])
                }
            )
        }
    }

    item {
        SmallTitle(text = "备份与恢复")
        Card(
            modifier = Modifier.padding(horizontal = 16.dp),
            insideMargin = PaddingValues(0.dp)
        ) {
            ArrowPreference(
                title = "剪贴板备份与恢复",
                summary = "导出 zip / WebDAV 备份 / 导入还原",
                onClick = onOpenClipboardBackup
            )
        }
    }
}

private fun LazyListScope.KeyboardLogoSubPageContent(
    logoEnabled: Boolean,
    onLogoEnabledChange: (Boolean) -> Unit,
    logoShowEnabled: Boolean,
    onLogoShowEnabledChange: (Boolean) -> Unit,
    logoColorMode: String,
    onLogoColorModeChange: (String) -> Unit,
    logoCustomColorInput: String,
    onLogoCustomColorInputChange: (String) -> Unit,
    logoImageEnabled: Boolean,
    onLogoImageEnabledChange: (Boolean) -> Unit,
    logoImageType: String,
    logoSvgRecolorEnabled: Boolean,
    onLogoSvgRecolorEnabledChange: (Boolean) -> Unit,
    logoImagePngBase64: String,
    logoImageSvgText: String,
    logoImageName: String,
    logoImageMessage: String,
    logoImageImporting: Boolean,
    onPickLogoPng: () -> Unit,
    onPickLogoSvg: () -> Unit,
    onActivateLogoImageType: (String) -> Unit,
    onClearLogoImages: () -> Unit,
    onResetLogo: () -> Unit
) {
    item {
        val context = LocalContext.current
        val isPng = logoImageType == WeTypeSettings.LOGO_IMAGE_TYPE_PNG
        val nameSuffix = logoImageName.takeIf { it.isNotEmpty() }?.let { "：$it" }.orEmpty()
        val pngSummary = when {
            logoImagePngBase64.isEmpty() -> "未上传，点击选择 PNG 图片（原色显示）"
            isPng -> "生效中$nameSuffix，点击重新选择"
            else -> "已上传，点击切换为 PNG 生效"
        }
        val svgSummary = when {
            logoImageSvgText.isEmpty() -> "未上传，点击选择 SVG 图片"
            !isPng -> "生效中$nameSuffix，点击重新选择"
            else -> "已上传，点击切换为 SVG 生效"
        }
        val logoColorModeOptions = listOf(
            WeTypeSettings.LOGO_COLOR_MODE_BRAND,
            WeTypeSettings.LOGO_COLOR_MODE_SYSTEM,
            WeTypeSettings.LOGO_COLOR_MODE_CUSTOM
        )
        val cardModifier = Modifier.padding(horizontal = 16.dp)
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // 1. 总开关
            Card(modifier = cardModifier, insideMargin = PaddingValues(0.dp)) {
                SwitchPreference(
                    title = "启用 Logo 替换",
                    summary = "关闭则显示输入法的原生 Logo",
                    checked = logoEnabled,
                    onCheckedChange = onLogoEnabledChange
                )
            }
            AnimatedVisibility(
                visible = logoEnabled,
                enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
                exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    // 2. 显示与主体颜色
                    Card(modifier = cardModifier, insideMargin = PaddingValues(0.dp)) {
                        SwitchPreference(
                            title = "显示 Logo",
                            summary = "关闭则隐藏键盘上的 Logo",
                            checked = logoShowEnabled,
                            onCheckedChange = onLogoShowEnabledChange
                        )
                        OverlayDropdownPreference(
                            title = "Logo 主体颜色",
                            entry = DropdownEntry(
                                items = labeledDropdownItems(
                                    options = listOf(
                                        "跟随品牌色" to "官方彩色",
                                        "跟随系统" to "自适应黑白",
                                        "自定义颜色" to "手动指定颜色"
                                    ),
                                    selectedIndex = logoColorModeOptions.indexOf(logoColorMode)
                                        .coerceAtLeast(0),
                                    onSelect = { index ->
                                        onLogoColorModeChange(logoColorModeOptions[index])
                                    }
                                )
                            )
                        )
                        if (logoColorMode == WeTypeSettings.LOGO_COLOR_MODE_CUSTOM) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp)
                            ) {
                                Text(
                                    text = "自定义颜色",
                                    style = MiuixTheme.textStyles.main
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "输入 #RRGGBB，例如 #23C891",
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    style = MiuixTheme.textStyles.body2
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                TextField(
                                    value = logoCustomColorInput,
                                    onValueChange = { input ->
                                        val trimmed = input.trim()
                                        val hasPrefix = trimmed.startsWith("#")
                                        val body = trimmed.removePrefix("#")
                                        if (body.length <= 6 && body.matches(Regex("^[0-9a-fA-F]*$"))) {
                                            onLogoCustomColorInputChange(if (hasPrefix || body.isNotEmpty()) "#$body" else "")
                                        }
                                    },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                    label = "#RRGGBB"
                                )
                            }
                        }
                    }
                    // 3. 图片替换：开启后才展开后面的上传与生效选项
                    Card(modifier = cardModifier, insideMargin = PaddingValues(0.dp)) {
                        SwitchPreference(
                            title = "替换为图片",
                            summary = "关闭则用回矢量 Logo，已上传文件保留",
                            checked = logoImageEnabled,
                            onCheckedChange = onLogoImageEnabledChange
                        )
                        AnimatedVisibility(
                            visible = logoImageEnabled,
                            enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
                            exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top)
                        ) {
                            Column {
                                ArrowPreference(
                                    title = "替换为 PNG",
                                    summary = pngSummary,
                                    onClick = {
                                        // 已上传但未生效时点一下就直接切过去，否则重新选文件。
                                        if (logoImagePngBase64.isNotEmpty() && !isPng) {
                                            onActivateLogoImageType(WeTypeSettings.LOGO_IMAGE_TYPE_PNG)
                                        } else {
                                            onPickLogoPng()
                                        }
                                    }
                                )
                                ArrowPreference(
                                    title = "替换为 SVG",
                                    summary = svgSummary,
                                    onClick = {
                                        if (logoImageSvgText.isNotEmpty() && isPng) {
                                            onActivateLogoImageType(WeTypeSettings.LOGO_IMAGE_TYPE_SVG)
                                        } else {
                                            onPickLogoSvg()
                                        }
                                    }
                                )
                                SwitchPreference(
                                    title = "替换 SVG 颜色",
                                    summary = "仅对 SVG 生效，染色为 Logo 主体颜色；PNG 始终原色",
                                    checked = logoSvgRecolorEnabled,
                                    onCheckedChange = onLogoSvgRecolorEnabledChange
                                )
                                ArrowPreference(
                                    title = "清除已上传图片",
                                    summary = "删除 PNG 与 SVG，恢复矢量 Logo",
                                    onClick = onClearLogoImages
                                )
                                if (logoImageImporting || logoImageMessage.isNotEmpty()) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp)
                                    ) {
                                        Text(
                                            text = logoImageMessage,
                                            style = MiuixTheme.textStyles.body2,
                                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                        )
                                    }
                                }
                            }
                        }
                    }
                    // 4. 重置
                    Card(modifier = cardModifier, insideMargin = PaddingValues(0.dp)) {
                        ArrowPreference(
                            title = "重置 Logo 设置",
                            summary = "恢复 Logo 默认开启、品牌色状态",
                            onClick = {
                                onResetLogo()
                                Toast.makeText(context, "Logo 设置已重置", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                }
            }
        }
    }
}
