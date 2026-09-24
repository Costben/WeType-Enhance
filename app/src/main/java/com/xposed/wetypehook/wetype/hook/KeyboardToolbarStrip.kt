package com.xposed.wetypehook.wetype.hook

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/**
 * 真机键盘工具栏条带的落盘位置与读写。
 *
 * 写入端是输入法进程 `com.tencent.wetype:hld`（见 [KeyboardToolbarStripCapture]），读取端是
 * 模块设置页所在的宿主主进程 `com.tencent.wetype`。两边同包同 uid，共用同一份 `files/`。
 *
 * 刻意不引用任何 Xposed API：设置页进程也要加载这个类。
 */
internal object KeyboardToolbarStrip {

    private const val FILE_NAME = "keyboard_toolbar_strip.png"

    fun file(context: Context): File =
        File(context.applicationContext.filesDir, FILE_NAME)

    /**
     * 文件指纹（时间戳 + 长度）。设置页拿它当缓存键：宿主每重画一次工具栏就换一张，
     * 指纹没变就不重新解码。
     */
    fun stamp(context: Context): String? = runCatching {
        val file = file(context)
        if (!file.exists()) return@runCatching null
        "${file.lastModified()}:${file.length()}"
    }.getOrNull()

    fun read(context: Context): Bitmap? = runCatching {
        val file = file(context)
        if (!file.exists()) return@runCatching null
        BitmapFactory.decodeFile(file.path)
    }.getOrNull()
}
