package com.xposed.wetypehook

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import kotlinx.coroutines.delay

internal const val ACTIVATION_HEARTBEAT_WINDOW_MS = 4_000L
internal const val ACTIVATION_KEYBOARD_RETRY_COUNT = 3
internal const val ACTIVATION_KEYBOARD_RETRY_DELAY_MS = 450L

internal fun ModuleActivationTracker.ActivationStatus.hasFreshHeartbeat(
    now: Long = System.currentTimeMillis()
): Boolean {
    if (!isActive || lastActivatedAt <= 0L) return false
    return now - lastActivatedAt <= ACTIVATION_HEARTBEAT_WINDOW_MS
}

@Composable
internal fun ActivationEntryApp(
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
internal fun ActivationEntryScreen(
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
