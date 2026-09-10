package com.xposed.wetypehook.wetype.clipboard

/**
 * 剪贴板图片条目渲染决策（纯逻辑，不引用宿主类，供单测与 Hook 层共用）。
 *
 * 依据 ADR-0002：
 * - 本设备图片（type==1 且 receiveTimestampFromServer==0）单行纯缩略图；
 * - 跨设备图片未落盘时单行来源文案，落盘后双行大缩略图；
 * - 非图片条目一律不渲染。
 */
internal enum class ClipboardImageRowState {
    NOT_IMAGE,
    LOCAL_THUMBNAIL,
    REMOTE_PENDING,
    REMOTE_THUMBNAIL
}

/** 跨设备图片 path 字段内的 JSON 载荷（宿主 S33 同款字段：version/key/md5/path）。 */
internal data class ClipboardRemoteImagePayload(
    val url: String,
    val key: String,
    val md5: String,
    val encrypted: Boolean
)

internal object ClipboardImageEntryLogic {

    const val REMOTE_PENDING_TEXT = "来自关联设备的图片"

    private const val KEY_VERSION = "version"
    private const val KEY_URL = "path"
    private const val KEY_KEY = "key"
    private const val KEY_MD5 = "md5"

    /**
     * 解析跨设备图片 path。宿主渲染约定：
     * - path 含 "version" 时为 JSON：{"version":..,"key":..,"md5":..,"path":"url"}；
     * - 否则 path 本身是可下载地址（无加密）。
     * 解析失败返回 null（不触发下载）。
     */
    fun parseRemotePayload(path: String?): ClipboardRemoteImagePayload? {
        if (path.isNullOrEmpty()) return null
        if (!path.contains(KEY_VERSION)) {
            return ClipboardRemoteImagePayload(url = path, key = "", md5 = "", encrypted = false)
        }
        val url = extractJsonString(path, KEY_URL) ?: return null
        if (url.isEmpty()) return null
        val key = extractJsonString(path, KEY_KEY).orEmpty()
        val md5 = extractJsonString(path, KEY_MD5).orEmpty()
        return ClipboardRemoteImagePayload(
            url = url,
            key = key,
            md5 = md5,
            encrypted = key.isNotEmpty()
        )
    }

    /**
     * 行态决策：
     * @param itemType C.getType()（0 文本 / 1 图片）
     * @param receiveTimestampFromServer >0 表示跨设备同步条目
     * @param pathType C.getPathType()（0 本地 / 1 远程）
     * @param localFileExists 当前 path 是否已落盘（仅 pathType==0 时探测）
     */
    fun classify(
        itemType: Long,
        receiveTimestampFromServer: Long,
        pathType: Int,
        localFileExists: Boolean
    ): ClipboardImageRowState {
        if (itemType != 1L) return ClipboardImageRowState.NOT_IMAGE
        if (receiveTimestampFromServer <= 0L) return ClipboardImageRowState.LOCAL_THUMBNAIL
        if (pathType == 0 && localFileExists) return ClipboardImageRowState.REMOTE_THUMBNAIL
        return ClipboardImageRowState.REMOTE_PENDING
    }

    /**
     * 极简 JSON 字符串取值：只认「字段名 + 冒号 + 字符串字面量」，
     * 支持 \uXXXX 转义（Gson 默认把 base64 的 '=' 写成 \u003d）。
     */
    private fun extractJsonString(json: String, field: String): String? {
        val token = "\"$field\""
        var searchFrom = 0
        while (true) {
            val tokenAt = json.indexOf(token, searchFrom)
            if (tokenAt < 0) return null
            searchFrom = tokenAt + token.length
            var colon = searchFrom
            while (colon < json.length && json[colon].isWhitespace()) colon++
            if (colon >= json.length || json[colon] != ':') continue
            var i = colon + 1
            while (i < json.length && json[i].isWhitespace()) i++
            if (i >= json.length || json[i] != '"') continue
            i++
            val sb = StringBuilder()
            while (i < json.length) {
                val c = json[i]
                if (c == '\\' && i + 1 < json.length) {
                    when (val esc = json[i + 1]) {
                        '"', '\\', '/' -> { sb.append(esc); i += 2 }
                        'b' -> { sb.append('\b'); i += 2 }
                        'f' -> { sb.append('\u000C'); i += 2 }
                        'n' -> { sb.append('\n'); i += 2 }
                        'r' -> { sb.append('\r'); i += 2 }
                        't' -> { sb.append('\t'); i += 2 }
                        'u' -> {
                            val hex = if (i + 6 <= json.length) json.substring(i + 2, i + 6) else null
                            val code = hex?.toIntOrNull(16)
                            if (code != null) {
                                sb.append(code.toChar())
                                i += 6
                            } else {
                                i += 2
                            }
                        }
                        else -> { sb.append(esc); i += 2 }
                    }
                } else if (c == '"') {
                    return sb.toString()
                } else {
                    sb.append(c)
                    i++
                }
            }
            return null
        }
    }
}
