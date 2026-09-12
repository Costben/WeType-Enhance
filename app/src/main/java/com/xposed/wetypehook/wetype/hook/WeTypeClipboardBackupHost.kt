package com.xposed.wetypehook.wetype.hook

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log as AndroidLog
import com.xposed.wetypehook.wetype.clipboard.BackupImageItem
import com.xposed.wetypehook.wetype.clipboard.BackupItem
import com.xposed.wetypehook.wetype.clipboard.BackupManifest
import com.xposed.wetypehook.wetype.clipboard.BackupTextItem
import com.xposed.wetypehook.wetype.clipboard.ClipboardBackupArchive
import com.xposed.wetypehook.wetype.clipboard.ClipboardBackupMergeLogic
import com.xposed.wetypehook.wetype.settings.WeTypeSettings
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 剪贴板备份与恢复的宿主侧适配（ADR-0003：运行在微信输入法进程内）。
 *
 * - 数据面优先直读宿主 Room 数据库文件 `ime_database`（框架 SQLiteDatabase，
 *   避开宿主 R8 混淆后的 Room 句柄名称）；DAO 列表作为兜底；
 * - 导入：合并去重后以原始 SQL 批量写入，图片落盘到宿主缓存目录并回写路径；
 * - 补下载：导出前对跨设备图片触发宿主下载链路并轮询落盘。
 */
internal object WeTypeClipboardBackupHost {

    private const val TAG = "WeTypeClipboardBackup"
    private const val CLIPBOARD_DB_NAME = "ime_database"
    private const val IMAGE_PLACEHOLDER = "[图片]"
    private const val RESTORE_EXPIRE_MS = 90L * 24 * 60 * 60 * 1000
    private const val PRE_DOWNLOAD_TIMEOUT_MS = 90_000L
    private const val PRE_DOWNLOAD_POLL_MS = 500L
    private const val TEXT_INSERT_BATCH = 500
    private const val SQLITE_VARIABLE_LIMIT = 800
    private const val ENTRY_TEXT_LIMIT = 256 * 1024

    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif")

    private const val INSERT_SQL =
        "INSERT INTO clipboard_record (content, keyInformation, createTime, state, " +
            "expireTimestamp, type, path, useTimes, source, pathType, serverVersion, " +
            "serverClipboardType, receiveTimestampFromServer, firstShowTimestamp, traceId, " +
            "contentSizeBytes) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"

    class BackupCancelledException : Exception()

    data class ExportSummary(
        val textCount: Int = 0,
        val imageCount: Int = 0,
        val skippedRemoteImages: Int = 0,
        val skippedMissingImages: Int = 0,
        val cancelled: Boolean = false
    )

    data class ImportSummary(
        val importedTexts: Int = 0,
        val importedImages: Int = 0,
        val skippedDuplicateTexts: Int = 0,
        val skippedDuplicateImages: Int = 0,
        val skippedBadEntries: Int = 0,
        val failedImages: Int = 0,
        val cancelled: Boolean = false
    )

    private data class ClipboardRow(
        val id: Long,
        val content: String,
        val createTime: Long,
        val type: Long,
        val path: String,
        val pathType: Int
    )

    @Volatile
    private var contentAccessor: Method? = null

    fun exportToStream(
        context: Context,
        output: OutputStream,
        preDownloadRemoteImages: Boolean,
        onProgress: (phase: String, done: Int, total: Int) -> Unit,
        isCancelled: () -> Boolean
    ): Result<ExportSummary> = try {
        Result.success(
            exportInternal(context, output, preDownloadRemoteImages, onProgress, isCancelled)
        )
    } catch (_: BackupCancelledException) {
        Result.success(ExportSummary(cancelled = true))
    } catch (t: Throwable) {
        AndroidLog.e(TAG, "export failed: ${t.message}")
        Result.failure(t)
    }

    fun importFromStream(
        context: Context,
        input: InputStream,
        onProgress: (phase: String, done: Int, total: Int) -> Unit,
        isCancelled: () -> Boolean
    ): Result<ImportSummary> = try {
        Result.success(importInternal(context, input, onProgress, isCancelled))
    } catch (_: BackupCancelledException) {
        Result.success(ImportSummary(cancelled = true))
    } catch (t: Throwable) {
        AndroidLog.e(TAG, "import failed: ${t.message}")
        Result.failure(t)
    }

    private fun exportInternal(
        context: Context,
        output: OutputStream,
        preDownloadRemoteImages: Boolean,
        onProgress: (phase: String, done: Int, total: Int) -> Unit,
        isCancelled: () -> Boolean
    ): ExportSummary {
        val dao = WeTypeClipboardImageList.daoOrResolve()
            ?: error("剪贴板数据源未就绪，请稍后重试")
        val database = openClipboardDatabase(context)
        try {
            if (preDownloadRemoteImages) {
                runCatching { preDownloadImages(context, dao, database, onProgress, isCancelled) }
                    .onFailure { AndroidLog.w(TAG, "pre-download failed: ${it.message}") }
            }
            checkCancelled(isCancelled)

            onProgress("读取剪贴板条目", 0, 1)
            val rows = if (database != null) readRowsFromSql(database) else readRowsFromDao(dao)
            val textItems = ArrayList<BackupTextItem>(rows.size)
            val imageCandidates = ArrayList<ClipboardRow>()
            var skippedRemote = 0
            var skippedMissing = 0
            for (row in rows) {
                when (row.type) {
                    0L -> textItems.add(BackupTextItem(row.content, row.createTime))
                    1L -> {
                        if (row.pathType != 0) {
                            skippedRemote++
                            continue
                        }
                        val file = File(row.path)
                        if (!file.isFile || file.length() <= 0L) {
                            skippedMissing++
                            continue
                        }
                        imageCandidates.add(row)
                    }
                }
            }
            checkCancelled(isCancelled)

            val imageItems = ArrayList<Pair<ClipboardRow, BackupImageItem>>(imageCandidates.size)
            onProgress("计算图片指纹", 0, imageCandidates.size)
            for ((index, row) in imageCandidates.withIndex()) {
                checkCancelled(isCancelled)
                val file = File(row.path)
                val md5 = runCatching { md5OfFile(file) }.getOrNull()
                if (md5 == null) {
                    skippedMissing++
                    continue
                }
                val item = BackupImageItem(
                    archivePath = ClipboardBackupArchive.imageEntryPath(
                        md5,
                        sniffImageExtension(file, row.path)
                    ),
                    md5 = md5,
                    createTime = row.createTime,
                    sizeBytes = file.length()
                )
                imageItems.add(row to item)
                if ((index + 1) % 16 == 0 || index == imageCandidates.lastIndex) {
                    onProgress("计算图片指纹", index + 1, imageCandidates.size)
                }
            }

            val manifest = BackupManifest(
                formatVersion = ClipboardBackupArchive.FORMAT_VERSION,
                exportedAt = System.currentTimeMillis(),
                textCount = textItems.size,
                imageCount = imageItems.size,
                skippedRemoteImages = skippedRemote
            )
            val totalItems = textItems.size + imageItems.size
            val writtenEntries = HashSet<String>()
            ZipOutputStream(BufferedOutputStream(output, 64 * 1024), StandardCharsets.UTF_8).use { zip ->
                zip.putNextEntry(ZipEntry(ClipboardBackupArchive.MANIFEST_ENTRY))
                zip.write(
                    ClipboardBackupArchive.manifestJson(manifest).toByteArray(StandardCharsets.UTF_8)
                )
                zip.closeEntry()

                zip.putNextEntry(ZipEntry(ClipboardBackupArchive.ITEMS_ENTRY))
                val writer = OutputStreamWriter(zip, StandardCharsets.UTF_8)
                var done = 0
                for (item in textItems) {
                    checkCancelled(isCancelled)
                    ClipboardBackupArchive.writeTextItem(writer, item)
                    writer.write("\n")
                    done++
                    if (done % 64 == 0 || done == totalItems) {
                        onProgress("打包条目", done, totalItems)
                    }
                }
                for ((_, item) in imageItems) {
                    ClipboardBackupArchive.writeImageItem(writer, item)
                    writer.write("\n")
                    done++
                    if (done % 64 == 0 || done == totalItems) {
                        onProgress("打包条目", done, totalItems)
                    }
                }
                writer.flush()
                zip.closeEntry()

                for ((index, pair) in imageItems.withIndex()) {
                    checkCancelled(isCancelled)
                    val (row, item) = pair
                    if (writtenEntries.add(item.archivePath)) {
                        zip.putNextEntry(ZipEntry(item.archivePath))
                        FileInputStream(File(row.path)).use { input -> input.copyTo(zip, 64 * 1024) }
                        zip.closeEntry()
                    }
                    onProgress("打包图片", index + 1, imageItems.size)
                }
            }
            AndroidLog.i(
                TAG,
                "export done: texts=${textItems.size} images=${imageItems.size} " +
                    "remoteSkipped=$skippedRemote missingSkipped=$skippedMissing"
            )
            return ExportSummary(
                textCount = textItems.size,
                imageCount = imageItems.size,
                skippedRemoteImages = skippedRemote,
                skippedMissingImages = skippedMissing
            )
        } finally {
            database?.close()
        }
    }

    private fun importInternal(
        context: Context,
        input: InputStream,
        onProgress: (phase: String, done: Int, total: Int) -> Unit,
        isCancelled: () -> Boolean
    ): ImportSummary {
        val database = openClipboardDatabase(context)
            ?: error("剪贴板数据库不可用，无法恢复")
        val tempDir = File(context.cacheDir, "clipboard_import_${System.currentTimeMillis()}")
        check(tempDir.mkdirs() || tempDir.isDirectory) { "无法创建导入临时目录" }
        try {
            var manifest: BackupManifest? = null
            val items = ArrayList<BackupItem>(1024)
            val imageEntryFiles = LinkedHashMap<String, File>()
            var badEntries = 0
            onProgress("解析备份包", 0, 1)
            ZipInputStream(BufferedInputStream(input, 64 * 1024), StandardCharsets.UTF_8).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    checkCancelled(isCancelled)
                    when {
                        entry.name == ClipboardBackupArchive.MANIFEST_ENTRY -> {
                            manifest = ClipboardBackupArchive.parseManifest(readEntryText(zip))
                        }

                        entry.name == ClipboardBackupArchive.ITEMS_ENTRY -> {
                            // 不能关闭该 reader：其 close() 会级联关闭 ZipInputStream。
                            val reader = BufferedReader(InputStreamReader(zip, StandardCharsets.UTF_8))
                            while (true) {
                                val line = reader.readLine() ?: break
                                if (line.isBlank()) continue
                                val item = ClipboardBackupArchive.parseItem(line)
                                if (item == null) badEntries++ else items.add(item)
                            }
                        }

                        entry.name.startsWith(ClipboardBackupArchive.IMAGES_DIR) && !entry.isDirectory -> {
                            val file = File(tempDir, "entry_${imageEntryFiles.size}")
                            FileOutputStream(file).use { zip.copyTo(it, 64 * 1024) }
                            imageEntryFiles[entry.name] = file
                        }
                    }
                    zip.closeEntry()
                }
            }
            val parsedManifest = manifest ?: error("备份包缺少 manifest.json，可能不是有效的备份包")
            check(parsedManifest.formatVersion <= ClipboardBackupArchive.FORMAT_VERSION) {
                "备份包版本过新（v${parsedManifest.formatVersion}），请先升级模块"
            }
            val textItems = items.filterIsInstance<BackupTextItem>()
            val imageItems = items.filterIsInstance<BackupImageItem>()
            checkCancelled(isCancelled)

            val existingTexts = HashSet<String>()
            database.queryRows("SELECT content FROM clipboard_record WHERE type = 0") { cursor ->
                val index = cursor.getColumnIndexOrThrow("content")
                while (cursor.moveToNext()) existingTexts.add(cursor.getString(index).orEmpty())
            }
            val textMerge = ClipboardBackupMergeLogic.mergeTexts(textItems, existingTexts)
            val expireTimestamp = importExpireTimestamp()
            var importedTexts = 0
            val batch = ArrayList<Pair<String, Array<Any?>>>(TEXT_INSERT_BATCH)
            onProgress("写入文本", 0, textMerge.kept.size)
            for ((index, item) in textMerge.kept.withIndex()) {
                checkCancelled(isCancelled)
                batch.add(
                    insertStatement(
                        content = item.content,
                        createTime = item.createTime,
                        type = 0L,
                        path = "",
                        sizeBytes = utf8Length(item.content),
                        expireTimestamp = expireTimestamp
                    )
                )
                if (batch.size >= TEXT_INSERT_BATCH) {
                    runBatch(database, batch)
                    importedTexts += batch.size
                    batch.clear()
                }
                if ((index + 1) % 128 == 0 || index == textMerge.kept.lastIndex) {
                    onProgress("写入文本", index + 1, textMerge.kept.size)
                }
            }
            runBatch(database, batch)
            importedTexts += batch.size

            val sizeToExistingPaths = HashMap<Long, MutableList<String>>()
            database.queryRows(
                "SELECT path FROM clipboard_record WHERE type = 1 AND pathType = 0 AND path <> ''"
            ) { cursor ->
                val index = cursor.getColumnIndexOrThrow("path")
                while (cursor.moveToNext()) {
                    val path = cursor.getString(index) ?: continue
                    val length = runCatching { File(path).length() }.getOrDefault(0L)
                    if (length > 0L) sizeToExistingPaths.getOrPut(length) { ArrayList() }.add(path)
                }
            }
            val existingMd5Cache = HashMap<String, String>()
            val imageMerge = ClipboardBackupMergeLogic.mergeImages(imageItems, emptySet())
            val importedMd5 = HashSet<String>()
            var importedImages = 0
            var failedImages = 0
            var duplicateImages = 0
            val restoreStamp = System.currentTimeMillis()
            onProgress("写入图片", 0, imageMerge.kept.size)
            for ((index, item) in imageMerge.kept.withIndex()) {
                checkCancelled(isCancelled)
                val entryFile = imageEntryFiles[item.archivePath]
                if (entryFile == null) {
                    failedImages++
                    continue
                }
                val extension = sniffImageExtension(entryFile, item.archivePath)
                val destination = File(
                    context.cacheDir,
                    "wetype_clip_restore_${restoreStamp}_$index.$extension"
                )
                val md5 = copyAndHash(entryFile, destination)
                if (md5 == null || md5 != item.md5) {
                    destination.delete()
                    failedImages++
                    onProgress("写入图片", index + 1, imageMerge.kept.size)
                    continue
                }
                if (importedMd5.contains(md5) ||
                    isExistingImageMd5(md5, destination.length(), sizeToExistingPaths, existingMd5Cache)
                ) {
                    destination.delete()
                    duplicateImages++
                    onProgress("写入图片", index + 1, imageMerge.kept.size)
                    continue
                }
                val statement = insertStatement(
                    content = IMAGE_PLACEHOLDER,
                    createTime = item.createTime,
                    type = 1L,
                    path = destination.absolutePath,
                    sizeBytes = destination.length(),
                    expireTimestamp = expireTimestamp
                )
                runCatching { database.execSQL(statement.first, statement.second) }
                    .onFailure {
                        destination.delete()
                        failedImages++
                        AndroidLog.w(TAG, "insert restored image failed: ${it.message}")
                    }
                    .onSuccess {
                        importedMd5.add(md5)
                        importedImages++
                    }
                if ((index + 1) % 4 == 0 || index == imageMerge.kept.lastIndex) {
                    onProgress("写入图片", index + 1, imageMerge.kept.size)
                }
            }

            WeTypeClipboardImageList.primeAsync()
            AndroidLog.i(
                TAG,
                "import done: texts=$importedTexts images=$importedImages " +
                    "dupTexts=${textMerge.skippedDuplicates} " +
                    "dupImages=${imageMerge.skippedDuplicates + duplicateImages} " +
                    "bad=$badEntries failedImages=$failedImages"
            )
            return ImportSummary(
                importedTexts = importedTexts,
                importedImages = importedImages,
                skippedDuplicateTexts = textMerge.skippedDuplicates,
                skippedDuplicateImages = imageMerge.skippedDuplicates + duplicateImages,
                skippedBadEntries = badEntries,
                failedImages = failedImages
            )
        } finally {
            runCatching { database.close() }
            runCatching { tempDir.deleteRecursively() }
        }
    }

    /** 导出前补下载跨设备图片：主线程触发宿主下载链路，后台轮询 pathType 落盘。 */
    private fun preDownloadImages(
        context: Context,
        dao: Any,
        database: SQLiteDatabase?,
        onProgress: (phase: String, done: Int, total: Int) -> Unit,
        isCancelled: () -> Boolean
    ) {
        val items = collectDaoItems(dao).filter {
            WeTypeClipboardImageHost.itemType(it) == 1L &&
                WeTypeClipboardImageHost.itemPathType(it) != 0
        }
        val ids = items.mapNotNull { WeTypeClipboardImageHost.itemId(it) }.toSet()
        if (ids.isEmpty()) return
        val handler = Handler(Looper.getMainLooper())
        handler.post {
            for (item in items) {
                runCatching { WeTypeClipboardImageLoader.request(item, context) { } }
                    .onFailure { AndroidLog.w(TAG, "pre-download request failed: ${it.message}") }
            }
        }
        if (database == null) return
        val total = ids.size
        onProgress("补下载跨设备图片", 0, total)
        val deadline = SystemClock.uptimeMillis() + PRE_DOWNLOAD_TIMEOUT_MS
        var lastDone = -1
        while (SystemClock.uptimeMillis() < deadline && !isCancelled()) {
            val pending = countPendingPathTypes(database, ids)
            val done = total - pending
            if (done != lastDone) {
                onProgress("补下载跨设备图片", done, total)
                lastDone = done
            }
            if (pending == 0) return
            Thread.sleep(PRE_DOWNLOAD_POLL_MS)
        }
    }

    private fun countPendingPathTypes(database: SQLiteDatabase, ids: Set<Long>): Int {
        var pending = 0
        for (chunk in ids.chunked(SQLITE_VARIABLE_LIMIT)) {
            val placeholders = chunk.joinToString(",") { "?" }
            val args = Array<String?>(chunk.size) { chunk[it].toString() }
            database.queryRows(
                "SELECT pathType FROM clipboard_record WHERE id IN ($placeholders)",
                args
            ) { cursor ->
                while (cursor.moveToNext()) {
                    if (cursor.getInt(0) != 0) pending++
                }
            }
        }
        return pending
    }

    private fun readRowsFromSql(database: SQLiteDatabase): List<ClipboardRow> {
        val rows = ArrayList<ClipboardRow>()
        database.queryRows(
            "SELECT id, content, createTime, type, path, pathType FROM clipboard_record"
        ) { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow("id")
            val contentIndex = cursor.getColumnIndexOrThrow("content")
            val createTimeIndex = cursor.getColumnIndexOrThrow("createTime")
            val typeIndex = cursor.getColumnIndexOrThrow("type")
            val pathIndex = cursor.getColumnIndexOrThrow("path")
            val pathTypeIndex = cursor.getColumnIndexOrThrow("pathType")
            while (cursor.moveToNext()) {
                rows.add(
                    ClipboardRow(
                        id = cursor.getLong(idIndex),
                        content = cursor.getString(contentIndex).orEmpty(),
                        createTime = cursor.getLong(createTimeIndex),
                        type = cursor.getLong(typeIndex),
                        path = cursor.getString(pathIndex).orEmpty(),
                        pathType = cursor.getInt(pathTypeIndex)
                    )
                )
            }
        }
        AndroidLog.i(TAG, "read ${rows.size} clipboard row(s) via sqlite")
        return rows
    }

    /** DAO 兜底：收集全部零参 List 查询返回的条目并按 id 去重。 */
    private fun readRowsFromDao(dao: Any): List<ClipboardRow> {
        val rows = LinkedHashMap<Long, ClipboardRow>()
        for (item in collectDaoItems(dao)) {
            val id = WeTypeClipboardImageHost.itemId(item) ?: continue
            val type = WeTypeClipboardImageHost.itemType(item)
            rows[id] = ClipboardRow(
                id = id,
                content = if (type == 0L) contentOf(item).orEmpty() else "",
                createTime = WeTypeClipboardImageHost.itemCreateTime(item),
                type = type,
                path = WeTypeClipboardImageHost.itemPath(item).orEmpty(),
                pathType = WeTypeClipboardImageHost.itemPathType(item)
            )
        }
        AndroidLog.i(TAG, "read ${rows.size} clipboard row(s) via dao fallback")
        return rows.values.toList()
    }

    private fun collectDaoItems(dao: Any): List<Any> {
        val items = LinkedHashMap<Long, Any>()
        for (method in dao.javaClass.declaredMethods) {
            if (method.parameterTypes.isNotEmpty() || Modifier.isStatic(method.modifiers)) continue
            if (!List::class.java.isAssignableFrom(method.returnType)) continue
            val list = runCatching {
                method.isAccessible = true
                method.invoke(dao) as? List<*>
            }.getOrNull() ?: continue
            for (item in list) {
                if (item == null) continue
                val id = WeTypeClipboardImageHost.itemId(item) ?: continue
                items.putIfAbsent(id, item)
            }
        }
        return items.values.toList()
    }

    private fun contentOf(item: Any): String? {
        val cached = contentAccessor
        val method = if (cached != null && cached.declaringClass == item.javaClass) {
            cached
        } else {
            item.javaClass.declaredMethods.firstOrNull {
                it.name == "a" && it.parameterTypes.isEmpty() && it.returnType == String::class.java
            }?.apply {
                isAccessible = true
                contentAccessor = this
            }
        }
        return runCatching { method?.invoke(item) as? String }.getOrNull()
    }

    /**
     * 直接打开宿主 Room 数据库文件，避开 R8 混淆后的 Room 句柄名称。
     * 仅在微信输入法进程内可用（数据库路径属于宿主包）。
     */
    private fun openClipboardDatabase(context: Context): SQLiteDatabase? {
        val path = context.getDatabasePath(CLIPBOARD_DB_NAME)
        if (!path.isFile) {
            AndroidLog.w(TAG, "clipboard database missing at ${path.absolutePath}")
            return null
        }
        return runCatching {
            SQLiteDatabase.openDatabase(path.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
        }.onFailure {
            AndroidLog.w(TAG, "open clipboard database failed: ${it.message}")
        }.getOrNull()
    }

    private fun SQLiteDatabase.queryRows(
        sql: String,
        args: Array<String?>? = null,
        read: (Cursor) -> Unit
    ) {
        rawQuery(sql, args).use(read)
    }

    private fun importExpireTimestamp(): Long {
        val now = System.currentTimeMillis()
        return if (WeTypeSettings.isRemoveClipboardRetentionLimitXposed()) {
            now + WeTypeClipboardImageHost.EXPIRY_EXTEND_TARGET_MS
        } else {
            now + RESTORE_EXPIRE_MS
        }
    }

    private fun insertStatement(
        content: String,
        createTime: Long,
        type: Long,
        path: String,
        sizeBytes: Long,
        expireTimestamp: Long
    ): Pair<String, Array<Any?>> = INSERT_SQL to arrayOf(
        content,
        "",
        createTime,
        1,
        expireTimestamp,
        type,
        path,
        0,
        0,
        0,
        0,
        0,
        0L,
        createTime,
        "",
        sizeBytes
    )

    private fun runBatch(database: SQLiteDatabase, statements: List<Pair<String, Array<Any?>>>) {
        if (statements.isEmpty()) return
        var attempt = 0
        while (true) {
            try {
                database.beginTransaction()
                try {
                    for ((sql, args) in statements) database.execSQL(sql, args)
                    database.setTransactionSuccessful()
                } finally {
                    database.endTransaction()
                }
                return
            } catch (locked: android.database.sqlite.SQLiteDatabaseLockedException) {
                // 宿主并发写入时短暂锁库：小退避重试当前批次。
                if (++attempt >= 3) throw locked
                Thread.sleep(200L * attempt)
            }
        }
    }

    private fun readEntryText(input: InputStream): String {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        while (buffer.size() < ENTRY_TEXT_LIMIT) {
            val read = input.read(chunk)
            if (read < 0) break
            buffer.write(chunk, 0, read.coerceAtMost(ENTRY_TEXT_LIMIT - buffer.size()))
        }
        return String(buffer.toByteArray(), StandardCharsets.UTF_8)
    }

    private fun isExistingImageMd5(
        md5: String,
        size: Long,
        sizeToExistingPaths: Map<Long, List<String>>,
        cache: MutableMap<String, String>
    ): Boolean {
        val candidates = sizeToExistingPaths[size] ?: return false
        for (path in candidates) {
            val hash = cache.getOrPut(path) {
                runCatching { md5OfFile(File(path)) }.getOrDefault("")
            }
            if (hash == md5) return true
        }
        return false
    }

    private fun copyAndHash(source: File, destination: File): String? = runCatching {
        val digest = MessageDigest.getInstance("MD5")
        destination.parentFile?.mkdirs()
        FileInputStream(source).use { input ->
            FileOutputStream(destination).use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                    output.write(buffer, 0, read)
                }
            }
        }
        digest.digest().toHex()
    }.getOrNull()

    private fun md5OfFile(file: File): String {
        val digest = MessageDigest.getInstance("MD5")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String {
        val sb = StringBuilder(size * 2)
        for (byte in this) sb.append((byte.toInt() and 0xFF).toString(16).padStart(2, '0'))
        return sb.toString()
    }

    private fun sniffImageExtension(file: File, fallbackPath: String): String {
        val header = ByteArray(16)
        val read = runCatching { FileInputStream(file).use { it.read(header) } }.getOrDefault(-1)
        if (read >= 2) {
            if (header.u(0) == 0xFF && header.u(1) == 0xD8) return "jpg"
            if (read >= 4 && header.u(0) == 0x89 && header.u(1) == 0x50 &&
                header.u(2) == 0x4E && header.u(3) == 0x47
            ) {
                return "png"
            }
            if (read >= 3 && header.u(0) == 0x47 && header.u(1) == 0x49 && header.u(2) == 0x46) {
                return "gif"
            }
            if (read >= 12 && header.u(0) == 0x52 && header.u(1) == 0x49 &&
                header.u(2) == 0x46 && header.u(3) == 0x46 &&
                header.u(8) == 0x57 && header.u(9) == 0x45 &&
                header.u(10) == 0x42 && header.u(11) == 0x50
            ) {
                return "webp"
            }
            if (header.u(0) == 0x42 && header.u(1) == 0x4D) return "bmp"
            if (read >= 12 && header.u(4) == 0x66 && header.u(5) == 0x74 &&
                header.u(6) == 0x79 && header.u(7) == 0x70
            ) {
                return "heic"
            }
        }
        val suffix = fallbackPath.substringAfterLast('.', "").lowercase()
        return if (suffix in IMAGE_EXTENSIONS) suffix else "jpg"
    }

    private fun ByteArray.u(index: Int): Int = this[index].toInt() and 0xFF

    private fun utf8Length(text: String): Long {
        var bytes = 0L
        var i = 0
        while (i < text.length) {
            val c = text[i]
            bytes += when {
                c.code < 0x80 -> 1
                c.code < 0x800 -> 2
                c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate() -> {
                    i++
                    4
                }

                else -> 3
            }
            i++
        }
        return bytes
    }

    private fun checkCancelled(isCancelled: () -> Boolean) {
        if (isCancelled()) throw BackupCancelledException()
    }
}
