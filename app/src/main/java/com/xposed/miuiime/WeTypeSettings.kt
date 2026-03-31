package com.xposed.miuiime

import android.content.Context
import android.content.res.Configuration
import de.robv.android.xposed.XSharedPreferences
import java.io.File

object WeTypeSettings {
    private const val MODULE_PACKAGE_NAME = "com.xposed.miuiime"
    private const val PREF_NAME = "wetype_settings"
    private const val KEY_LIGHT_COLOR = "light_color"
    private const val KEY_DARK_COLOR = "dark_color"
    private const val KEY_BLUR_RADIUS = "blur_radius"

    const val DEFAULT_LIGHT_COLOR = 0xCCFFFFFF.toInt()
    const val DEFAULT_DARK_COLOR = 0x80202020.toInt()
    const val DEFAULT_BLUR_RADIUS = 60

    fun getLightColor(context: Context): Int =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_LIGHT_COLOR, DEFAULT_LIGHT_COLOR)

    fun getDarkColor(context: Context): Int =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_DARK_COLOR, DEFAULT_DARK_COLOR)

    fun getBlurRadius(context: Context): Int =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_BLUR_RADIUS, DEFAULT_BLUR_RADIUS)

    fun save(context: Context, lightColor: Int, darkColor: Int, blurRadius: Int) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_LIGHT_COLOR, lightColor)
            .putInt(KEY_DARK_COLOR, darkColor)
            .putInt(KEY_BLUR_RADIUS, blurRadius.coerceIn(0, 100))
            .commit()
        File(context.applicationInfo.dataDir, "shared_prefs/$PREF_NAME.xml")
            .setReadable(true, false)
    }

    fun getCurrentBackgroundColorXposed(context: Context): Int {
        val prefs = XSharedPreferences(MODULE_PACKAGE_NAME, PREF_NAME).apply { reload() }
        val isDarkMode =
            context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
        return if (isDarkMode) {
            prefs.getInt(KEY_DARK_COLOR, DEFAULT_DARK_COLOR)
        } else {
            prefs.getInt(KEY_LIGHT_COLOR, DEFAULT_LIGHT_COLOR)
        }
    }

    fun getBlurRadiusXposed(): Int =
        XSharedPreferences(MODULE_PACKAGE_NAME, PREF_NAME)
            .apply { reload() }
            .getInt(KEY_BLUR_RADIUS, DEFAULT_BLUR_RADIUS)
}
