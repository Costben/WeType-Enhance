package com.xposed.wetypehook

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.xposed.wetypehook.wetype.settings.WeTypeSettings
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.SwitchPreference

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
    onSystemServiceChange: (Boolean) -> Unit,
    shizukuInstalled: Boolean,
    onRequestShizukuAuthorization: () -> Unit
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
                    summary = "识别结果先经微信输入法的文字润色再回传，关闭则直接回传原始识别文本",
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
        title = "指向模块的识别服务",
        summary = when {
            busy -> "正在写入…"
            enabled -> "已生效，所有系统语音识别请求由本模块处理"
            else -> "未生效，系统仍在使用原识别服务"
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
