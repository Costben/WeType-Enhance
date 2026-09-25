package com.xposed.wetypehook

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.xposed.wetypehook.wetype.backup.WebDavCancelledException
import com.xposed.wetypehook.wetype.backup.WebDavClient
import com.xposed.wetypehook.wetype.backup.WebDavHttpException
import com.xposed.wetypehook.wetype.backup.WebDavPaths
import com.xposed.wetypehook.wetype.backup.WebDavRemoteEntry
import com.xposed.wetypehook.wetype.backup.WebDavRetention
import com.xposed.wetypehook.wetype.clipboard.ClipboardBackupArchive
import com.xposed.wetypehook.wetype.hook.WeTypeClipboardBackupHost
import com.xposed.wetypehook.wetype.settings.ClipboardBackupSettings
import com.xposed.wetypehook.wetype.settings.WeTypeSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLHandshakeException

private val KEEP_COUNT_OPTIONS = listOf(0, 1, 3, 5, 10, 20, 50)
private val BACKUP_TIME_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

@Composable
internal fun ClipboardBackupApp(
    settingsContext: Context,
    busyState: MutableState<Boolean>,
    onClose: () -> Unit
) {
    val darkMode = isSystemInDarkTheme()
    MiuixTheme(colors = if (darkMode) darkColorScheme() else lightColorScheme()) {
        SyncSystemBars(darkMode = darkMode)
        ClipboardBackupPage(
            settingsContext = settingsContext,
            busyState = busyState,
            onClose = onClose
        )
    }
}

@Composable
internal fun ClipboardBackupPage(
    settingsContext: Context,
    busyState: MutableState<Boolean>,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val resolver = settingsContext.contentResolver
    val isEmbeddedHost = remember(settingsContext) {
        (context.applicationContext ?: context).packageName != "com.xposed.wetypehook"
    }
    var settings by remember { mutableStateOf(WeTypeSettings.readClipboardBackupSettings(settingsContext)) }
    var busy by remember { mutableStateOf(false) }
    var progressPhase by remember { mutableStateOf("") }
    var progressDone by remember { mutableIntStateOf(0) }
    var progressTotal by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf("") }
    var remoteEntries by remember { mutableStateOf<List<WebDavRemoteEntry>?>(null) }
    val cancelFlag = remember { AtomicBoolean(false) }
    val scope = rememberCoroutineScope()
    SideEffect { busyState.value = busy }

    fun persist() {
        WeTypeSettings.saveClipboardBackupSettings(settingsContext, settings)
    }

    fun onProgressUpdate(phase: String, done: Int, total: Int) {
        progressPhase = phase
        progressDone = done
        progressTotal = total
    }

    fun startTask(block: suspend () -> String) {
        if (busy) return
        busy = true
        message = ""
        cancelFlag.set(false)
        onProgressUpdate("准备中", 0, 0)
        scope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { block() } }
            busy = false
            message = result.getOrElse { error ->
                if (error is WeTypeClipboardBackupHost.BackupCancelledException ||
                    error is WebDavCancelledException
                ) {
                    "已取消"
                } else {
                    "失败：${describeError(error)}"
                }
            }
        }
    }

    fun performLocalExport(treeUri: Uri) {
        startTask {
            val fileName = ClipboardBackupArchive.buildFileName(System.currentTimeMillis())
            // SAF 返回的是 tree URI，创建文件必须用 tree 下的 document URI。
            val parentUri = DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri)
            )
            val documentUri = DocumentsContract.createDocument(
                resolver,
                parentUri,
                ClipboardBackupArchive.MIME_ZIP,
                fileName
            ) ?: throw IllegalStateException("无法在所选文件夹创建文件")
            try {
                val output = resolver.openOutputStream(documentUri, "wt")
                    ?: throw IllegalStateException("无法写入所选文件夹")
                val summary = output.use { stream ->
                    WeTypeClipboardBackupHost.exportToStream(
                        context = settingsContext,
                        output = stream,
                        preDownloadRemoteImages = settings.preExportDownload,
                        onProgress = ::onProgressUpdate,
                        isCancelled = cancelFlag::get
                    ).getOrThrow()
                }
                if (summary.cancelled) {
                    DocumentsContract.deleteDocument(resolver, documentUri)
                    "已取消导出，未完成的文件已删除"
                } else {
                    buildString {
                        append("导出完成：").append(fileName)
                        append("\n文本 ").append(summary.textCount).append(" 条")
                        append("，图片 ").append(summary.imageCount).append(" 张")
                        if (summary.skippedRemoteImages > 0) {
                            append("\n跳过未下载的跨设备图片 ").append(summary.skippedRemoteImages).append(" 张")
                        }
                        if (summary.skippedMissingImages > 0) {
                            append("\n跳过文件缺失的图片 ").append(summary.skippedMissingImages).append(" 张")
                        }
                    }
                }
            } catch (t: Throwable) {
                runCatching { DocumentsContract.deleteDocument(resolver, documentUri) }
                throw t
            }
        }
    }

    fun performLocalImport(uri: Uri) {
        startTask {
            val input = resolver.openInputStream(uri)
                ?: throw IllegalStateException("无法读取所选文件")
            val summary = input.use { stream ->
                WeTypeClipboardBackupHost.importFromStream(
                    context = settingsContext,
                    input = stream,
                    onProgress = ::onProgressUpdate,
                    isCancelled = cancelFlag::get
                ).getOrThrow()
            }
            if (summary.cancelled) "已取消导入" else importMessage(summary)
        }
    }

    fun buildClient(): WebDavClient = WebDavClient(
        rawBaseUrl = settings.webDavUrl,
        username = settings.webDavUsername,
        password = settings.webDavPassword,
        allowInsecureTls = settings.webDavAllowSelfSigned
    )

    fun testWebDav() {
        persist()
        if (settings.webDavUrl.isBlank()) {
            message = "请先填写 WebDAV 地址"
            return
        }
        startTask {
            buildClient().testConnection().getOrThrow()
            "WebDAV 连接成功"
        }
    }

    fun performWebDavBackup() {
        persist()
        if (settings.webDavUrl.isBlank()) {
            message = "请先配置 WebDAV 地址"
            return
        }
        startTask {
            val fileName = ClipboardBackupArchive.buildFileName(System.currentTimeMillis())
            val temp = File(settingsContext.cacheDir, fileName)
            try {
                FileOutputStream(temp).use { output ->
                    val summary = WeTypeClipboardBackupHost.exportToStream(
                        context = settingsContext,
                        output = output,
                        preDownloadRemoteImages = settings.preExportDownload,
                        onProgress = ::onProgressUpdate,
                        isCancelled = cancelFlag::get
                    ).getOrThrow()
                    if (summary.cancelled) return@startTask "已取消备份"
                }
                val client = buildClient()
                onProgressUpdate("创建远程目录", 0, 1)
                client.ensureDirectory(settings.webDavRemoteDir).getOrThrow()
                try {
                    client.upload(
                        remoteDir = settings.webDavRemoteDir,
                        file = temp,
                        fileName = fileName,
                        isCancelled = cancelFlag::get
                    ) { written, total ->
                        onProgressUpdate(
                            "上传备份包",
                            (written / 1024).toInt(),
                            (total / 1024).toInt()
                        )
                    }.getOrThrow()
                } catch (t: Throwable) {
                    // 取消/失败后清理可能残留的半成品远程文件。
                    runCatching {
                        val url = WebDavPaths.join(
                            client.baseUrl,
                            WebDavPaths.splitSegments(settings.webDavRemoteDir) + fileName
                        )
                        client.delete(WebDavRemoteEntry(fileName, url, -1L, -1L))
                    }
                    throw t
                }
                onProgressUpdate("清理旧备份", 0, 1)
                val entries = client.list(settings.webDavRemoteDir).getOrThrow()
                val deletions = WebDavRetention.selectForDeletion(entries, settings.webDavKeepCount)
                var deleted = 0
                for (entry in deletions) {
                    runCatching { client.delete(entry) }.onSuccess { deleted++ }
                }
                "WebDAV 备份完成：$fileName" +
                    (if (deleted > 0) "\n已按保留策略清理 $deleted 份旧备份" else "")
            } finally {
                temp.delete()
            }
        }
    }

    fun loadRemoteList() {
        persist()
        if (settings.webDavUrl.isBlank()) {
            message = "请先配置 WebDAV 地址"
            return
        }
        startTask {
            val entries = buildClient().list(settings.webDavRemoteDir).getOrThrow()
            remoteEntries = entries
            if (entries.isEmpty()) "远程目录暂无备份包" else "共 ${entries.size} 份备份，点击选择要恢复的版本"
        }
    }

    fun performWebDavRestore(entry: WebDavRemoteEntry) {
        startTask {
            val temp = File(settingsContext.cacheDir, "restore_${System.currentTimeMillis()}.zip")
            try {
                buildClient().download(entry, temp, isCancelled = cancelFlag::get) { read, total ->
                    onProgressUpdate(
                        "下载备份包",
                        (read / 1024).toInt(),
                        (total / 1024).toInt()
                    )
                }.getOrThrow()
                val summary = FileInputStream(temp).use { stream ->
                    WeTypeClipboardBackupHost.importFromStream(
                        context = settingsContext,
                        input = stream,
                        onProgress = ::onProgressUpdate,
                        isCancelled = cancelFlag::get
                    ).getOrThrow()
                }
                if (summary.cancelled) "已取消恢复" else importMessage(summary)
            } finally {
                temp.delete()
            }
        }
    }

    val hostActivity = settingsContext as? Activity

    fun closePage() {
        if (busy) {
            Toast.makeText(settingsContext, "任务进行中，请稍候", Toast.LENGTH_SHORT).show()
            return
        }
        persist()
        onClose()
    }

    fun launchPickTree() {
        if (hostActivity == null) {
            message = "无法获取宿主窗口，请重试"
            return
        }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        )
        runCatching {
            hostActivity.startActivityForResult(intent, WeTypeHostActivityResultBridge.REQUEST_PICK_TREE)
        }.onFailure { message = "无法打开文件夹选择器：${describeError(it)}" }
    }

    fun launchPickFile() {
        if (hostActivity == null) {
            message = "无法获取宿主窗口，请重试"
            return
        }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf(
                    "application/zip",
                    "application/octet-stream",
                    "application/x-zip-compressed"
                )
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            hostActivity.startActivityForResult(intent, WeTypeHostActivityResultBridge.REQUEST_PICK_FILE)
        }.onFailure { message = "无法打开文件选择器：${describeError(it)}" }
    }

    DisposableEffect(hostActivity) {
        WeTypeHostActivityResultBridge.register(
            WeTypeHostActivityResultBridge.REQUEST_PICK_TREE
        ) { resultCode, data ->
            val uri = if (resultCode == Activity.RESULT_OK) data?.data else null
            if (uri != null) {
                runCatching {
                    resolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                }
                settings = settings.copy(localFolderUri = uri.toString())
                persist()
                performLocalExport(uri)
            }
        }
        WeTypeHostActivityResultBridge.register(
            WeTypeHostActivityResultBridge.REQUEST_PICK_FILE
        ) { resultCode, data ->
            val uri = if (resultCode == Activity.RESULT_OK) data?.data else null
            if (uri != null) performLocalImport(uri)
        }
        onDispose {
            WeTypeHostActivityResultBridge.unregister(WeTypeHostActivityResultBridge.REQUEST_PICK_TREE)
            WeTypeHostActivityResultBridge.unregister(WeTypeHostActivityResultBridge.REQUEST_PICK_FILE)
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            // 部分 ROM 在 IME 弹出时会把 Compose 的 statusBars inset 放大到键盘上沿，
            // Miuix TopAppBar 会无条件吃 systemBars.top，导致整条标题栏下移；
            // 顶部 inset 由宿主页面在 View 层统一处理，这里消费掉避免二次偏移。
            .consumeWindowInsets(WindowInsets.systemBars.only(WindowInsetsSides.Top))
            .background(MiuixTheme.colorScheme.background),
        topBar = {
            SmallTopAppBar(
                title = "剪贴板备份与恢复",
                navigationIcon = {
                    IconButton(onClick = { closePage() }) {
                        Icon(
                            imageVector = MiuixIcons.Back,
                            contentDescription = "返回"
                        )
                    }
                },
                actions = {
                    SettingsKeyboardButton()
                    SettingsRefreshButton {
                        applySettingsToImeProcess(context)
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .overScrollVertical()
                .imePadding(),
            contentPadding = PaddingValues(
                top = paddingValues.calculateTopPadding(),
                bottom = 40.dp
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Card(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    insideMargin = PaddingValues(16.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "备份包包含全部文本与已落盘的图片；恢复为合并导入，重复内容自动跳过。",
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                        if (!isEmbeddedHost) {
                            Text(
                                text = "当前不在微信输入法进程内，剪贴板数据不可访问；请从微信输入法内打开设置后使用。",
                                style = MiuixTheme.textStyles.body2,
                                color = ComposeColor(0xFFE53935)
                            )
                        }
                    }
                }
            }

            if (isEmbeddedHost) {
                item {
                    SmallTitle(text = "本地备份")
                }
                item {
                    Card(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        insideMargin = PaddingValues(0.dp)
                    ) {
                        Column {
                            BasicComponent(
                                title = "本地导出",
                                summary = folderSummary(settings.localFolderUri),
                                onClick = {
                                    val saved = settings.localFolderUri.takeIf { it.isNotEmpty() }
                                        ?.let { Uri.parse(it) }
                                    val hasPermission = saved != null && resolver.persistedUriPermissions.any {
                                        it.uri == saved && it.isWritePermission
                                    }
                                    if (hasPermission) {
                                        performLocalExport(saved!!)
                                    } else {
                                        launchPickTree()
                                    }
                                }
                            )
                            BasicComponent(
                                title = "选择导出文件夹",
                                summary = "授予文件夹读写权限并记住，导出时可直接使用",
                                onClick = { launchPickTree() }
                            )
                            BasicComponent(
                                title = "本地导入",
                                summary = "选择备份包（.zip）并合并恢复到剪贴板",
                                onClick = { launchPickFile() }
                            )
                            SwitchPreference(
                                checked = settings.preExportDownload,
                                onCheckedChange = {
                                    settings = settings.copy(preExportDownload = it)
                                    persist()
                                },
                                title = "导出前补下载跨设备图片",
                                summary = "导出时先尝试下载未落盘的跨设备图片，耗时更长"
                            )
                        }
                    }
                }

                item {
                    SmallTitle(text = "WebDAV 设置")
                }
                item {
                    Card(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        insideMargin = PaddingValues(16.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            TextField(
                                value = settings.webDavUrl,
                                onValueChange = { settings = settings.copy(webDavUrl = it) },
                                label = "服务器地址（https://dav.example.com/remote.php/dav）",
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            TextField(
                                value = settings.webDavUsername,
                                onValueChange = { settings = settings.copy(webDavUsername = it) },
                                label = "用户名",
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            TextField(
                                value = settings.webDavPassword,
                                onValueChange = { settings = settings.copy(webDavPassword = it) },
                                label = "密码 / 应用密码",
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth()
                            )
                            TextField(
                                value = settings.webDavRemoteDir,
                                onValueChange = { settings = settings.copy(webDavRemoteDir = it) },
                                label = "远程目录",
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            BasicComponent(
                                title = "远程保留份数",
                                summary = keepCountSummary(settings.webDavKeepCount),
                                onClick = {
                                    val currentIndex = KEEP_COUNT_OPTIONS.indexOf(settings.webDavKeepCount)
                                    val next = KEEP_COUNT_OPTIONS[(currentIndex + 1) % KEEP_COUNT_OPTIONS.size]
                                    settings = settings.copy(webDavKeepCount = next)
                                    persist()
                                }
                            )
                            SwitchPreference(
                                checked = settings.webDavAllowSelfSigned,
                                onCheckedChange = {
                                    settings = settings.copy(webDavAllowSelfSigned = it)
                                    persist()
                                },
                                title = "允许自签名证书",
                                summary = "仅对自建服务器开启；开启后无法防御中间人攻击"
                            )
                            if (settings.webDavUrl.startsWith("http://")) {
                                Text(
                                    text = "警告：当前使用明文 HTTP 传输，备份内容可被网络中间人读取。",
                                    style = MiuixTheme.textStyles.body2,
                                    color = ComposeColor(0xFFE53935)
                                )
                            }
                            BasicComponent(
                                title = "测试连接",
                                summary = "校验地址与账号权限",
                                onClick = { testWebDav() }
                            )
                            HorizontalDivider()
                            BasicComponent(
                                title = "立即备份到 WebDAV",
                                onClick = { performWebDavBackup() }
                            )
                            BasicComponent(
                                title = "从 WebDAV 恢复",
                                summary = "拉取远程备份列表并选择版本",
                                onClick = { loadRemoteList() }
                            )
                        }
                    }
                }

                if (!remoteEntries.isNullOrEmpty()) {
                    item {
                        SmallTitle(text = "远程备份版本")
                    }
                    item {
                        Card(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            insideMargin = PaddingValues(0.dp)
                        ) {
                            Column {
                                remoteEntries.orEmpty().forEach { entry ->
                                    BasicComponent(
                                        title = entry.name,
                                        summary = remoteEntrySummary(entry),
                                        titleColor = top.yukonga.miuix.kmp.basic.BasicComponentDefaults.titleColor(
                                            color = MiuixTheme.colorScheme.primary
                                        ),
                                        onClick = { performWebDavRestore(entry) }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (busy || message.isNotEmpty()) {
                item {
                    SmallTitle(text = "任务状态")
                }
                item {
                    Card(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        insideMargin = PaddingValues(16.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (busy) {
                                Text(
                                    text = if (progressTotal > 0) {
                                        "$progressPhase  $progressDone/$progressTotal"
                                    } else {
                                        progressPhase
                                    },
                                    style = MiuixTheme.textStyles.body2
                                )
                                BasicComponent(
                                    title = "取消任务",
                                    titleColor = top.yukonga.miuix.kmp.basic.BasicComponentDefaults.titleColor(
                                        color = ComposeColor(0xFFE53935)
                                    ),
                                    onClick = { cancelFlag.set(true) }
                                )
                            }
                            if (message.isNotEmpty()) {
                                Text(
                                    text = message,
                                    style = MiuixTheme.textStyles.body2,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun importMessage(summary: WeTypeClipboardBackupHost.ImportSummary): String = buildString {
    append("导入完成：文本 ").append(summary.importedTexts).append(" 条")
    append("，图片 ").append(summary.importedImages).append(" 张")
    val duplicates = summary.skippedDuplicateTexts + summary.skippedDuplicateImages
    if (duplicates > 0) append("\n跳过重复 ").append(duplicates).append(" 条")
    if (summary.skippedBadEntries > 0) {
        append("\n跳过损坏条目 ").append(summary.skippedBadEntries).append(" 条")
    }
    if (summary.failedImages > 0) {
        append("\n图片恢复失败 ").append(summary.failedImages).append(" 张")
    }
}

private fun folderSummary(uri: String): String {
    if (uri.isEmpty()) return "尚未选择文件夹"
    val name = Uri.parse(uri).lastPathSegment?.substringAfterLast(':').orEmpty()
    return if (name.isEmpty()) "已记住文件夹：$uri" else "当前文件夹：$name"
}

private fun keepCountSummary(count: Int): String =
    if (count <= ClipboardBackupSettings.KEEP_COUNT_UNLIMITED) "不限制" else "$count 份"

private fun remoteEntrySummary(entry: WebDavRemoteEntry): String {
    val time = if (entry.lastModified > 0) {
        BACKUP_TIME_FORMAT.format(Date(entry.lastModified))
    } else {
        "时间未知"
    }
    val size = if (entry.sizeBytes >= 0) {
        when {
            entry.sizeBytes >= 1024 * 1024 -> "${entry.sizeBytes / (1024 * 1024)} MB"
            entry.sizeBytes >= 1024 -> "${entry.sizeBytes / 1024} KB"
            else -> "${entry.sizeBytes} B"
        }
    } else {
        "大小未知"
    }
    return "$time · $size · 点击恢复"
}

private fun describeError(error: Throwable): String = when {
    error is WebDavHttpException && error.statusCode == 401 ->
        "认证失败 (HTTP 401)，请检查用户名或应用密码"

    error is WebDavHttpException && error.statusCode == 403 -> "无权限 (HTTP 403)"
    error is WebDavHttpException && error.statusCode == 404 -> "地址或目录不存在 (HTTP 404)"
    error is WebDavHttpException -> "服务器返回 HTTP ${error.statusCode}"
    error is UnknownHostException -> "无法解析服务器地址"
    error is SocketTimeoutException -> "连接超时"

    error is SSLHandshakeException -> "TLS 握手失败，自签名证书需开启对应开关"
    else -> error.message ?: error.javaClass.simpleName
}
