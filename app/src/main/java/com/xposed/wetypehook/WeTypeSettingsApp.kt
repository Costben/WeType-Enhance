package com.xposed.wetypehook

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.core.view.ViewCompat
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme


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

internal tailrec fun Context.findHostActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findHostActivity()
    else -> null
}
