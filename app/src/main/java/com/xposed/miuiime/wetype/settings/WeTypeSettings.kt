package com.xposed.miuiime.wetype.settings

import android.content.Context
import android.content.res.Configuration
import android.net.Uri
import android.os.BaseBundle
import android.os.Bundle
import java.io.File

object WeTypeSettings {
    private const val MODULE_PACKAGE_NAME = "com.xposed.miuiime"
    private const val PREF_NAME = "wetype_settings"
    private const val KEY_LIGHT_COLOR = "light_color"
    private const val KEY_DARK_COLOR = "dark_color"
    private const val KEY_BLUR_RADIUS = "blur_radius"
    private const val KEY_CORNER_RADIUS = "corner_radius"
    private const val KEY_EDGE_HIGHLIGHT_ENABLED = "edge_highlight_enabled"
    private const val KEY_EDGE_HIGHLIGHT_INTENSITY = "edge_highlight_intensity"
    private const val KEY_KEY_OPACITY = "key_opacity"
    private const val METHOD_GET_SETTINGS = "get_settings"

    const val DEFAULT_LIGHT_COLOR = 0xA0D1D3D8.toInt()
    const val DEFAULT_DARK_COLOR = 0x90202020.toInt()
    const val DEFAULT_BLUR_RADIUS = 60
    const val DEFAULT_CORNER_RADIUS = 28
    const val DEFAULT_EDGE_HIGHLIGHT_ENABLED = true
    const val DEFAULT_EDGE_HIGHLIGHT_INTENSITY = 50
    const val DEFAULT_KEY_OPACITY = 160
    const val PROVIDER_AUTHORITY = "$MODULE_PACKAGE_NAME.settings"

    data class Snapshot(
        val lightColor: Int,
        val darkColor: Int,
        val blurRadius: Int,
        val cornerRadius: Int,
        val edgeHighlightEnabled: Boolean,
        val edgeHighlightIntensity: Int,
        val keyOpacity: Int
    )

    fun getLightColor(context: Context): Int = readSnapshot(context).lightColor

    fun getDarkColor(context: Context): Int = readSnapshot(context).darkColor

    fun getBlurRadius(context: Context): Int = readSnapshot(context).blurRadius

    fun getCornerRadius(context: Context): Int = readSnapshot(context).cornerRadius

    fun isEdgeHighlightEnabled(context: Context): Boolean = readSnapshot(context).edgeHighlightEnabled

    fun getEdgeHighlightIntensity(context: Context): Int = readSnapshot(context).edgeHighlightIntensity

    fun getKeyOpacity(context: Context): Int = readSnapshot(context).keyOpacity

    fun save(
        context: Context,
        lightColor: Int,
        darkColor: Int,
        blurRadius: Int,
        cornerRadius: Int,
        edgeHighlightEnabled: Boolean,
        edgeHighlightIntensity: Int,
        keyOpacity: Int
    ) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_LIGHT_COLOR, lightColor)
            .putInt(KEY_DARK_COLOR, darkColor)
            .putInt(KEY_BLUR_RADIUS, blurRadius.coerceIn(0, 100))
            .putInt(KEY_CORNER_RADIUS, cornerRadius.coerceIn(0, 100))
            .putBoolean(KEY_EDGE_HIGHLIGHT_ENABLED, edgeHighlightEnabled)
            .putInt(KEY_EDGE_HIGHLIGHT_INTENSITY, edgeHighlightIntensity.coerceIn(0, 200))
            .putInt(KEY_KEY_OPACITY, keyOpacity.coerceIn(0, 255))
            .commit()
        val sharedPrefsDir = File(context.dataDir, "shared_prefs").apply {
            setReadable(true, false)
            setExecutable(true, false)
        }
        File(sharedPrefsDir, "$PREF_NAME.xml").setReadable(true, false)
    }

    fun getCurrentBackgroundColorXposed(context: Context): Int {
        val snapshot = readSnapshotXposed(context)
        val isDarkMode =
            context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
        return if (isDarkMode) snapshot.darkColor else snapshot.lightColor
    }

    fun getBlurRadiusXposed(context: Context): Int = readSnapshotXposed(context).blurRadius

    fun getCornerRadiusXposed(context: Context): Int = readSnapshotXposed(context).cornerRadius

    fun isEdgeHighlightEnabledXposed(context: Context): Boolean =
        readSnapshotXposed(context).edgeHighlightEnabled

    fun getEdgeHighlightIntensityXposed(context: Context): Int =
        readSnapshotXposed(context).edgeHighlightIntensity

    fun getKeyOpacityXposed(context: Context): Int = readSnapshotXposed(context).keyOpacity

    fun readSnapshot(context: Context): Snapshot {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return Snapshot(
            lightColor = prefs.getInt(KEY_LIGHT_COLOR, DEFAULT_LIGHT_COLOR),
            darkColor = prefs.getInt(KEY_DARK_COLOR, DEFAULT_DARK_COLOR),
            blurRadius = prefs.getInt(KEY_BLUR_RADIUS, DEFAULT_BLUR_RADIUS),
            cornerRadius = prefs.getInt(KEY_CORNER_RADIUS, DEFAULT_CORNER_RADIUS),
            edgeHighlightEnabled = prefs.getBoolean(
                KEY_EDGE_HIGHLIGHT_ENABLED,
                DEFAULT_EDGE_HIGHLIGHT_ENABLED
            ),
            edgeHighlightIntensity = prefs.getInt(
                KEY_EDGE_HIGHLIGHT_INTENSITY,
                DEFAULT_EDGE_HIGHLIGHT_INTENSITY
            ),
            keyOpacity = prefs.getInt(KEY_KEY_OPACITY, DEFAULT_KEY_OPACITY)
        )
    }

    private fun readSnapshotXposed(context: Context): Snapshot {
        val bundle = runCatching {
            context.contentResolver.call(
                Uri.parse("content://$PROVIDER_AUTHORITY"),
                METHOD_GET_SETTINGS,
                null,
                null
            )
        }.getOrNull()
        return bundle.toSnapshotOrDefault()
    }

    private fun Bundle?.toSnapshotOrDefault(): Snapshot {
        val bundle = this ?: return defaultSnapshot()
        return Snapshot(
            lightColor = bundle.getInt(KEY_LIGHT_COLOR, DEFAULT_LIGHT_COLOR),
            darkColor = bundle.getInt(KEY_DARK_COLOR, DEFAULT_DARK_COLOR),
            blurRadius = bundle.getInt(KEY_BLUR_RADIUS, DEFAULT_BLUR_RADIUS),
            cornerRadius = bundle.getInt(KEY_CORNER_RADIUS, DEFAULT_CORNER_RADIUS),
            edgeHighlightEnabled = bundle.getBoolean(
                KEY_EDGE_HIGHLIGHT_ENABLED,
                DEFAULT_EDGE_HIGHLIGHT_ENABLED
            ),
            edgeHighlightIntensity = bundle.getInt(
                KEY_EDGE_HIGHLIGHT_INTENSITY,
                DEFAULT_EDGE_HIGHLIGHT_INTENSITY
            ),
            keyOpacity = bundle.getInt(KEY_KEY_OPACITY, DEFAULT_KEY_OPACITY)
        )
    }

    fun toBundle(snapshot: Snapshot): Bundle = Bundle().apply {
        putInt(KEY_LIGHT_COLOR, snapshot.lightColor)
        putInt(KEY_DARK_COLOR, snapshot.darkColor)
        putInt(KEY_BLUR_RADIUS, snapshot.blurRadius)
        putInt(KEY_CORNER_RADIUS, snapshot.cornerRadius)
        putBoolean(KEY_EDGE_HIGHLIGHT_ENABLED, snapshot.edgeHighlightEnabled)
        putInt(KEY_EDGE_HIGHLIGHT_INTENSITY, snapshot.edgeHighlightIntensity)
        putInt(KEY_KEY_OPACITY, snapshot.keyOpacity)
    }

    private fun defaultSnapshot(): Snapshot = Snapshot(
        lightColor = DEFAULT_LIGHT_COLOR,
        darkColor = DEFAULT_DARK_COLOR,
        blurRadius = DEFAULT_BLUR_RADIUS,
        cornerRadius = DEFAULT_CORNER_RADIUS,
        edgeHighlightEnabled = DEFAULT_EDGE_HIGHLIGHT_ENABLED,
        edgeHighlightIntensity = DEFAULT_EDGE_HIGHLIGHT_INTENSITY,
        keyOpacity = DEFAULT_KEY_OPACITY
    )
}
