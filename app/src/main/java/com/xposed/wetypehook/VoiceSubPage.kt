package com.xposed.wetypehook

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.xposed.wetypehook.wetype.settings.WeTypeSettings
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 「Asr2api」二级页。
 *
 * 把微信输入法的语音识别能力，以**系统标准接口**的形式对外提供：模块自带一个标准的
 * [android.speech.RecognitionService]，并把 `Settings.Secure` 的 `voice_recognition_service`
 * 指向它。生效后所有走系统语音识别的应用都会落到微信输入法的识别引擎上。
 *
 * 页面上不放说明段落 —— 状态本身由开关和摘要行表达，长篇解释只会把真正要点的两三个开关
 * 埋掉。
 */
internal fun LazyListScope.VoiceSubPageContent(
    bridgeEnabled: Boolean,
    onBridgeEnabledChange: (Boolean) -> Unit,
    aiPolishEnabled: Boolean,
    onAiPolishChange: (Boolean) -> Unit,
    silenceFinishMs: Int,
    onSilenceFinishChange: (Int) -> Unit,
    noSpeechFinishMs: Int,
    onNoSpeechFinishChange: (Int) -> Unit,
    silencePeak: Int,
    onSilencePeakChange: (Int) -> Unit,
    eosQuietMs: Int,
    onEosQuietChange: (Int) -> Unit,
    eosMaxWaitMs: Int,
    onEosMaxWaitChange: (Int) -> Unit,
    systemServiceEnabled: Boolean,
    systemServiceBusy: Boolean,
    systemServiceMasterEnabled: Boolean,
    onSystemServiceMasterChange: (Boolean) -> Unit,
    onSystemServiceChange: (Boolean) -> Unit,
    shizukuInstalled: Boolean,
    onRequestShizukuAuthorization: () -> Unit,
    shellEnabled: Boolean,
    onShellEnabledChange: (Boolean) -> Unit,
    shellPort: Int,
    onShellPortChange: (Int) -> Unit,
    shellAllowLan: Boolean,
    onShellAllowLanChange: (Boolean) -> Unit
) {
    item {
        SmallTitle(text = "识别能力")
        Card(
            modifier = Modifier.padding(horizontal = 16.dp),
            insideMargin = PaddingValues(0.dp)
        ) {
            Column {
                SwitchPreference(
                    title = "启用识别能力",
                    summary = "关闭后模块不再启动桥接，系统识别服务一并失效",
                    checked = bridgeEnabled,
                    onCheckedChange = onBridgeEnabledChange
                )
                SwitchPreference(
                    title = "AI 润色",
                    summary = "识别结果先经微信输入法的文字润色再回传",
                    checked = aiPolishEnabled,
                    onCheckedChange = onAiPolishChange
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }

    item {
        SmallTitle(text = "系统识别服务")
        Card(
            modifier = Modifier.padding(horizontal = 16.dp),
            insideMargin = PaddingValues(0.dp)
        ) {
            Column {
                SwitchPreference(
                    title = "启用系统识别服务",
                    summary = when {
                        systemServiceBusy -> "正在处理…"

                        systemServiceMasterEnabled -> "已开启，下方可把系统识别服务指向本模块"

                        else -> "已关闭，本模块不参与系统语音识别"
                    },
                    checked = systemServiceMasterEnabled,
                    onCheckedChange = onSystemServiceMasterChange
                )
                SettingExpandGroup(visible = systemServiceMasterEnabled) {
                    SystemServiceSwitch(
                        enabled = systemServiceEnabled,
                        busy = systemServiceBusy,
                        onChange = onSystemServiceChange
                    )
                    BasicComponent(
                        title = "Shizuku 授权",
                        summary = if (shizukuInstalled) {
                            "点击后在 Shizuku 弹窗中允许本模块写入"
                        } else {
                            "未检测到 Shizuku，将改用 root 写入"
                        },
                        onClick = onRequestShizukuAuthorization
                    )
                    SystemServiceCommandCard()
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }

    item {
        SmallTitle(text = "千问 / 豆包服务")
        Card(
            modifier = Modifier.padding(horizontal = 16.dp),
            insideMargin = PaddingValues(0.dp)
        ) {
            Column {
                SwitchPreference(
                    title = "启用本地监听",
                    summary = when {
                        shellEnabled && shellAllowLan -> "监听中，同网段设备可访问"

                        shellEnabled -> "监听中，仅本机可访问"

                        else -> "未启用，给 Eta 用的本地识别服务"
                    },
                    checked = shellEnabled,
                    onCheckedChange = onShellEnabledChange
                )
                SettingExpandGroup(visible = shellEnabled) {
                    ShellPortField(port = shellPort, onPortChange = onShellPortChange)
                    SwitchPreference(
                        title = "允许局域网访问",
                        summary = "同网段的设备才能连进来；只在手机上用请保持关闭",
                        checked = shellAllowLan,
                        onCheckedChange = onShellAllowLanChange
                    )
                    ShellAddressCopyRow(port = shellPort, allowLan = shellAllowLan)
                }
                if (Build.VERSION.SDK_INT >= LOCAL_NETWORK_PROTECTION_MIN_SDK) {
                    LocalNetworkPermissionHint()
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }

    item {
        SmallTitle(text = "端点参数")
        Card(
            modifier = Modifier.padding(horizontal = 16.dp),
            insideMargin = PaddingValues(0.dp)
        ) {
            Column {
                SliderPreferenceItem(
                    title = "静音判停",
                    summary = "开口后连续静音多久算说完",
                    value = silenceFinishMs.toFloat(),
                    range = 300f..10000f,
                    step = 100f,
                    format = { "${it.roundToInt()} ms" },
                    onValueChange = { onSilenceFinishChange(it.roundToInt()) }
                )
                SliderPreferenceItem(
                    title = "无语音超时",
                    summary = "一直没开口，多久判本轮无语音",
                    value = noSpeechFinishMs.toFloat(),
                    range = 1000f..30000f,
                    step = 500f,
                    format = { "${it.roundToInt()} ms" },
                    onValueChange = { onNoSpeechFinishChange(it.roundToInt()) }
                )
                SliderPreferenceItem(
                    title = "静音门限",
                    summary = "低于这个峰值算静音，越小越不容易被当停顿",
                    value = silencePeak.toFloat(),
                    range = 50f..5000f,
                    step = 50f,
                    format = { "${it.roundToInt()}" },
                    onValueChange = { onSilencePeakChange(it.roundToInt()) }
                )
                SliderPreferenceItem(
                    title = "收尾静默",
                    summary = "收尾后连续多久没有新转录就结束",
                    value = eosQuietMs.toFloat(),
                    range = 200f..3000f,
                    step = 100f,
                    format = { "${it.roundToInt()} ms" },
                    onValueChange = { onEosQuietChange(it.roundToInt()) }
                )
                SliderPreferenceItem(
                    title = "收尾上限",
                    summary = "收尾等待的绝对上限",
                    value = eosMaxWaitMs.toFloat(),
                    range = 1000f..15000f,
                    step = 500f,
                    format = { "${it.roundToInt()} ms" },
                    onValueChange = { onEosMaxWaitChange(it.roundToInt()) }
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

/**
 * 「系统识别服务」开关。
 *
 * 开关直接读写 `Settings.Secure`，所以显示的是**事实**而非意图：在别处改过之后回到本页，
 * 开关会跟着变。写入顺序（Shizuku → root → 提示）见 [WeTypeSettingsState.setVoiceSystemService]。
 *
 * 没有可用通道时**不隐藏也不置灰**：写失败后回读会发现值没变，开关自己弹回原位，同时给出
 * 下方那条可复制的命令。置灰会让用户以为是模块坏了，而实际只是少一次授权。
 */
@Composable
private fun SystemServiceSwitch(
    enabled: Boolean,
    busy: Boolean,
    onChange: (Boolean) -> Unit
) {
    SwitchPreference(
        title = "将系统识别服务指向本模块",
        summary = when {
            busy -> "正在写入…"
            enabled -> "已生效，系统语音识别请求由本模块处理"
            else -> "未生效，系统仍在用原来的识别服务"
        },
        checked = enabled,
        enabled = !busy,
        onCheckedChange = onChange
    )
}

/**
 * 可复制的 `settings put` / `settings delete` 命令。
 *
 * 两条都给，是因为「还原」有两种语义：用户原来配过别的识别服务（用 `settings put` 写回原值），
 * 或者原来是空的、走系统默认（用 `settings delete`）。模块分不清是哪种 —— 它读不到
 * 「打开开关之前」的值，也不该猜。所以把两条都摆出来，由用户按自己的情况选。
 *
 * 这是 Shizuku 与 root 都不可用时的兜底：命令由用户自己在 adb 或 root 终端里跑一次。
 */
@Composable
private fun SystemServiceCommandCard() {
    val context = LocalContext.current
    val component = WeTypeSettings.VOICE_RECOGNITION_COMPONENT
    BasicComponent(
        title = "复制启用命令",
        summary = "adb shell settings put secure ${WeTypeSettings.VOICE_RECOGNITION_SERVICE_KEY} $component",
        onClick = {
            copyToClipboard(
                context,
                "voice_recognition_service",
                "settings put secure ${WeTypeSettings.VOICE_RECOGNITION_SERVICE_KEY} $component"
            )
        }
    )
    BasicComponent(
        title = "复制还原命令",
        summary = "adb shell settings delete secure ${WeTypeSettings.VOICE_RECOGNITION_SERVICE_KEY}",
        onClick = {
            copyToClipboard(
                context,
                "voice_recognition_service",
                "settings delete secure ${WeTypeSettings.VOICE_RECOGNITION_SERVICE_KEY}"
            )
        }
    )
}

/** 复制并提示。剪贴板失败不抛给用户 —— 复制不到比崩一下更不打扰。 */
internal fun copyToClipboard(context: Context, label: String, text: String) {
    val copied = runCatching {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        manager?.setPrimaryClip(ClipData.newPlainText(label, text))
        true
    }.getOrDefault(false)
    Toast.makeText(
        context,
        if (copied) "已复制" else "复制失败",
        Toast.LENGTH_SHORT
    ).show()
}

/**
 * 壳服务的监听端口输入框。
 *
 * 只在输入落成一个**合法端口**时才写回状态：逐字输入时的中间态（空串、`18`）不写，
 * 否则每敲一个数字都要重启一次监听。非法值只提示不纠正，用户改完自然落到合法区间。
 */
@Composable
private fun ShellPortField(port: Int, onPortChange: (Int) -> Unit) {
    var draft by remember(port) { mutableStateOf(port.toString()) }
    val parsed = draft.toIntOrNull()
    val inRange = parsed != null &&
        parsed in WeTypeSettings.VOICE_SHELL_PORT_MIN..WeTypeSettings.VOICE_SHELL_PORT_MAX
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(text = "监听端口", style = MiuixTheme.textStyles.main)
        Text(
            text = "端口范围 ${WeTypeSettings.VOICE_SHELL_PORT_MIN}–${WeTypeSettings.VOICE_SHELL_PORT_MAX}",
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
        )
        Spacer(modifier = Modifier.height(8.dp))
        TextField(
            value = draft,
            onValueChange = { raw ->
                val next = raw.filter { it.isDigit() }.take(5)
                draft = next
                next.toIntOrNull()?.let { value ->
                    if (value in WeTypeSettings.VOICE_SHELL_PORT_MIN..WeTypeSettings.VOICE_SHELL_PORT_MAX) {
                        onPortChange(value)
                    }
                }
            },
            label = "端口",
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        if (draft.isNotEmpty() && !inRange) {
            Text(
                text = "端口需在 ${WeTypeSettings.VOICE_SHELL_PORT_MIN}–${WeTypeSettings.VOICE_SHELL_PORT_MAX} 之间",
                color = MiuixTheme.colorScheme.error,
                style = MiuixTheme.textStyles.body2
            )
        }
    }
}

/**
 * 一键把 Eta 该填的服务地址复制走。
 *
 * 地址跟着「允许局域网访问」变：关着只有本机能连，给回环地址；开着才给局域网 IP ——
 * 复制一个连不上的地址比不给更误导。
 */
@Composable
private fun ShellAddressCopyRow(port: Int, allowLan: Boolean) {
    val context = LocalContext.current
    val address = shellServiceAddress(port = port, allowLan = allowLan)
    BasicComponent(
        title = "复制 base URL",
        summary = address,
        onClick = { copyToClipboard(context, "asr_shell_address", address) }
    )
}

/**
 * Android 16 起，访问局域网地址需要 `ACCESS_LOCAL_NETWORK` 运行时权限；缺这条权限时系统
 * 会**静默丢弃**该 uid 发往局域网的全部报文 —— 表现是连接超时与域名解析失败，且没有任何
 * 报错可查。Eta 从 3.2.0 起把 targetSdk 提到 37，升级后这条权限默认拒绝，于是它连不上
 * 自建的局域网网关，模型请求会全线超时。
 *
 * 运行时权限只能由系统或 shell 授予，模块没有代授的通道，所以这里只给一条可复制的命令。
 */
@Composable
private fun LocalNetworkPermissionHint() {
    val context = LocalContext.current
    BasicComponent(
        title = "Eta 的本地网络访问权限",
        summary = "Android 16 起未授权时 Eta 连不上局域网地址；点按复制授权命令",
        onClick = {
            copyToClipboard(
                context,
                "eta_local_network",
                "adb shell pm grant ${ModuleBridgeContract.ETA_PACKAGE_NAME} $ACCESS_LOCAL_NETWORK_PERMISSION"
            )
        }
    )
}

/** 本地网络保护（Local Network Protection）从 Android 16（API 36）开始生效。 */
private const val LOCAL_NETWORK_PROTECTION_MIN_SDK = 36

private const val ACCESS_LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

/**
 * 壳对外的根地址。
 *
 * 只走 `http://` 明文，不需要任何证书，新用户开箱即用。Eta 的 `speechBaseUrl` 只认
 * https，模块在 Eta 进程里把回环地址放行成 http（见 `EtaSpeechHooks`），所以这一栏
 * 填 http 就能过它的校验。
 */
internal fun shellServiceAddress(port: Int, allowLan: Boolean): String {
    val host = if (allowLan) localIpv4Address() ?: LOOPBACK_ADDRESS else LOOPBACK_ADDRESS
    return "http://$host:$port"
}

private const val LOOPBACK_ADDRESS = "127.0.0.1"

/** 本机在局域网里的 IPv4 地址。没有可用的（没连 Wi-Fi、只有蜂窝）就返回 null。 */
private fun localIpv4Address(): String? = runCatching {
    java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())
        .asSequence()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { java.util.Collections.list(it.inetAddresses).asSequence() }
        .filterIsInstance<java.net.Inet4Address>()
        .firstOrNull { it.isSiteLocalAddress }
        ?.hostAddress
}.getOrNull()
