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
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 「Adr2api」二级页。
 *
 * 把微信输入法的语音识别能力，以**系统标准接口**的形式对外提供：
 *
 * 模块自带一个标准的 [android.speech.RecognitionService]，并把 `Settings.Secure` 的
 * `voice_recognition_service` 指向它。生效后，**所有**走系统语音识别的应用
 * （语音助手、各家 App 的语音输入）都会落到微信输入法的识别引擎上。
 *
 * 能力来自模块在微信输入法进程内借用它的识别引擎：模块进程的服务收到请求后，把麦克风
 * PCM 推到回环端口，由输入法进程内的桥接层喂给宿主识别引擎，转录再原路返回。
 * 所以总开关关闭后，这一路虽然开关仍然可点，但实际识别会失败 ——
 * 它的开关只代表 `Settings.Secure` 有没有指向模块，不代表底层可用。
 */
internal fun LazyListScope.VoiceSubPageContent(
    bridgeEnabled: Boolean,
    onBridgeEnabledChange: (Boolean) -> Unit,
    systemServiceEnabled: Boolean,
    systemServiceBusy: Boolean,
    onSystemServiceChange: (Boolean) -> Unit
) {
    item {
        SmallTitle(text = "识别能力")
        Card(
            modifier = Modifier.padding(horizontal = 16.dp),
            insideMargin = PaddingValues(0.dp)
        ) {
            Column {
                Text(
                    text = "模块在微信输入法进程内借用其语音识别引擎，并通过系统标准接口对外提供。",
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.body2,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                )
                SwitchPreference(
                    title = "启用识别能力",
                    summary = "关闭后模块不再启动桥接，系统识别服务一并失效",
                    checked = bridgeEnabled,
                    onCheckedChange = onBridgeEnabledChange
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
                Text(
                    text = "将系统语音识别服务指向模块。生效后，所有使用系统识别的应用" +
                        "（语音助手、应用内语音输入等）均由微信输入法识别引擎处理。",
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.body2,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                )
                SystemServiceSwitch(
                    enabled = systemServiceEnabled,
                    busy = systemServiceBusy,
                    onChange = onSystemServiceChange
                )
                SystemServiceCommandCard()
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

/**
 * 「系统识别服务」开关。
 *
 * 开关直接读写 `Settings.Secure`，所以显示的是**事实**而非意图：在别处改过之后回到本页，
 * 开关会跟着变。写入走 root，权限模型与踩过的坑见
 * [WeTypeSettings.applyVoiceSystemService]。
 *
 * 无 root 时**不隐藏也不置灰**：写失败后回读会发现值没变，开关自己弹回原位，同时给出
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
