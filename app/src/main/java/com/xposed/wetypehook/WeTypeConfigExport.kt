package com.xposed.wetypehook

import android.content.Context
import android.net.Uri
import com.xposed.wetypehook.wetype.settings.WeTypeSettings
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 模块配置导出。
 *
 * 导出的不是 [WeTypeSettings.Snapshot] 的字段快照，而是 `wetype_settings` 偏好文件的全部
 * 键值：设置项有一部分散落在 Snapshot 之外（备份账号、自定义光感预设、Logo 图片、页级
 * 偏好），按偏好文件导出才能一份带走全部配置。
 *
 * 值按 SharedPreferences 的实际类型打标签，恢复时才不会把 int 写成 long、让读取方抛
 * `ClassCastException`。
 *
 * 同步簿记与迁移标记不导出：它们描述的是本机两份偏好之间的同步进度，带到另一台设备上
 * 只会让首次读取误判成「已经同步过」。
 */
internal object WeTypeConfigExport {
    const val MIME_TYPE = "application/json"
    const val SCHEMA = "wetype-enhance-config"
    const val SCHEMA_VERSION = 1

    /** 偏好文件里的内部键：同步进度与一次性迁移标记。 */
    private val INTERNAL_KEYS = setOf(
        "remote_sync_pending",
        "host_sync_pending",
        "host_sync_revision",
        "last_imported_revision",
        "key_opacity_migrated",
        "gesture_label_midline_migrated"
    )

    fun buildFileName(
        versionName: String = BuildConfig.VERSION_NAME,
        now: Long = System.currentTimeMillis()
    ): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(now))
        return "WeType_Enhance-config-$versionName-$stamp.json"
    }

    /** 写入 SAF 返回的 document URI，返回导出的配置项数量。 */
    fun export(context: Context, uri: Uri): Int {
        val json = buildJson(context)
        val count = JSONObject(json).getJSONObject("entries").length()
        val output = context.contentResolver.openOutputStream(uri, "wt")
            ?: error("无法打开目标文件")
        output.use { stream ->
            stream.write(json.toByteArray(Charsets.UTF_8))
            stream.flush()
        }
        return count
    }

    fun buildJson(context: Context, now: Long = System.currentTimeMillis()): String {
        val appContext = context.applicationContext ?: context
        val preferences = appContext.getSharedPreferences(
            WeTypeSettings.PREF_GROUP,
            Context.MODE_PRIVATE
        )
        val entries = JSONObject()
        preferences.all.toSortedMap().forEach { (key, value) ->
            if (key in INTERNAL_KEYS) return@forEach
            encode(value)?.let { entries.put(key, it) }
        }
        return JSONObject().apply {
            put("schema", SCHEMA)
            put("schemaVersion", SCHEMA_VERSION)
            put("moduleVersion", BuildConfig.VERSION_NAME)
            put("moduleVersionCode", BuildConfig.VERSION_CODE)
            put("exportedAt", now)
            put("entries", entries)
        }.toString(2)
    }

    private fun encode(value: Any?): JSONObject? = when (value) {
        is Boolean -> entry("boolean", value)
        is Int -> entry("int", value)
        is Long -> entry("long", value)
        is Float -> entry("float", value)
        is String -> entry("string", value)
        is Set<*> -> JSONObject()
            .put("type", "stringSet")
            .put("value", JSONArray(value.mapNotNull { it as? String }.sorted()))
        else -> null
    }

    private fun entry(type: String, value: Any): JSONObject =
        JSONObject().put("type", type).put("value", value)
}
