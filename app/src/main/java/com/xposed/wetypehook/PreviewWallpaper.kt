package com.xposed.wetypehook

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/**
 * 预览里那层「手机壁纸」。
 *
 * 真机键盘是两层：底下是屏幕上的东西（壁纸、应用界面），上面才是半透明的键盘面板。
 * 只看面板本身看不出透明度到底生效没有，所以预览必须把底图一起画出来 —— 这层底图跟真机一样
 * 由用户自己挑，换成什么颜色、什么花纹都行。
 *
 * 图片按 [MAX_EDGE_PX] 缩放后压成 JPEG 存进自己那份 SharedPreferences：只有设置进程要读它，
 * 不占宿主共享的那份设置，也免去跨进程的 URI 授权问题。
 */
internal object PreviewWallpaper {

    private const val PREFS_NAME = "wetype_preview_wallpaper"
    private const val KEY_BASE64 = "wallpaper_jpeg_base64"
    private const val KEY_NAME = "wallpaper_name"

    /** 存图最长边上限：只是预览底图，再大就白占内存。 */
    private const val MAX_EDGE_PX = 1080
    private const val JPEG_QUALITY = 85
    private const val MAX_NAME_LENGTH = 64

    /** 提供者没给文件名时的兜底显示名。 */
    private const val DEFAULT_NAME = "预览背景"

    @Volatile
    private var cached: Bitmap? = null

    @Volatile
    private var cachedEncoded: String? = null

    /** 当前底图的文件名；空串表示没设置过。 */
    fun displayName(context: Context): String =
        prefs(context).getString(KEY_NAME, "").orEmpty()

    fun hasImage(context: Context): Boolean = displayName(context).isNotEmpty()

    /** 读底图；没设置过或解不开都返回 null，预览就退回面板自己的底色。 */
    fun load(context: Context): Bitmap? {
        val encoded = prefs(context).getString(KEY_BASE64, "").orEmpty()
        if (encoded.isEmpty()) return null
        if (encoded == cachedEncoded) return cached
        val bitmap = runCatching {
            val bytes = Base64.decode(encoded, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
        cached = bitmap
        cachedEncoded = encoded
        return bitmap
    }

    /** 导入一张新底图并按当前缩放比落库；成功时回传落库的文件名，失败时调用方拿错误文案提示用户。 */
    fun import(context: Context, uri: Uri): Result<String> = runCatching {
        val decoded = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it)
        } ?: error("读不到图片")
        val name = resolveDisplayName(context, uri)
        val scaled = scaleToFit(decoded, MAX_EDGE_PX)
        val bytes = ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            out.toByteArray()
        }
        prefs(context).edit()
            .putString(KEY_BASE64, Base64.encodeToString(bytes, Base64.NO_WRAP))
            .putString(KEY_NAME, name)
            .apply()
        cached = null
        cachedEncoded = null
        name
    }

    /**
     * 问文档提供者要文件名。
     *
     * 不能读 `uri.lastPathSegment`：MediaStore 的文档 id 长这样 `image:33934`，
     * 直接当名字用的话设置页会显示一串编号。
     */
    internal fun resolveDisplayName(context: Context, uri: Uri): String {
        val queried = runCatching {
            context.contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull()
        val candidate = listOfNotNull(
            queried?.takeIf { it.isNotBlank() },
            uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        ).firstOrNull { !it.contains(':') } ?: DEFAULT_NAME
        return candidate.take(MAX_NAME_LENGTH)
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_BASE64).remove(KEY_NAME).apply()
        cached = null
        cachedEncoded = null
    }

    private fun scaleToFit(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxEdge) return bitmap
        val ratio = maxEdge.toFloat() / longest
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * ratio).roundToInt().coerceAtLeast(1),
            (bitmap.height * ratio).roundToInt().coerceAtLeast(1),
            true
        )
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
