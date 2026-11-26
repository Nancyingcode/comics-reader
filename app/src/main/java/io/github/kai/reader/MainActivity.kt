package io.github.kai.reader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    private val reader by lazy { ViewModelProvider(this)[ReaderViewModel::class.java] }
    private val display by lazy { ViewModelProvider(this)[AppearanceViewModel::class.java] }
    private val directoryPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) {
        importSelection(it, isArchive = false)
    }
    private val archivePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) {
        importSelection(it, isArchive = true)
    }

    private fun importSelection(uri: Uri?, isArchive: Boolean) {
        if (uri == null) return
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            reader.importSource(uri, isArchive)
        } catch (_: SecurityException) {
            reader.showError("无法保留读取权限，请通过系统选择器重新选择漫画。")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            val covers = remember { coverImageLoader(applicationContext, reader.images) }
            val superResolution = remember { ReaderSuperResolution(applicationContext) }
            val appearance by display.appearance.collectAsStateWithLifecycle(initialValue = ReaderAppearance())
            var sourceDialog by rememberSaveable { mutableStateOf<String?>(null) }
            var clearCacheDialog by rememberSaveable { mutableStateOf(false) }
            DisposableEffect(covers) { onDispose { covers.shutdown() } }
            MaterialTheme(colorScheme = ReaderColors) {
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                        val details = reader.selectedBook
                        val chapter = reader.activeChapter
                        val browser = reader.webDavBrowser
                        when {
                            reader.loading -> {
                                BackHandler { reader.cancelLoading() }
                                OperationScreen(reader.operation, reader::cancelLoading)
                            }
                            chapter != null && details != null -> key(reader.sessionId) {
                                val session = reader.sessionId
                                var showChapters by rememberSaveable { mutableStateOf(false) }
                                val index = details.chapters.indexOfFirst { it.id == chapter.id }
                                val next = if (index >= 0) details.chapters.getOrNull(index + 1) else null
                                ReaderScreen(
                                    chapter = chapter,
                                    images = reader.images,
                                    initialAnchor = reader.currentAnchor,
                                    onAnchor = { reader.reportAnchor(session, it) },
                                    onChapterList = { showChapters = true },
                                    onNextChapter = next?.let { { reader.openChapter(it.id, fromStart = true) } },
                                    onReloadChapter = { reader.reopenChapter(chapter.id) },
                                    onClose = reader::closeReader,
                                    appearance = appearance,
                                    appearanceError = display.error,
                                    onUpdateAppearance = display::update,
                                    superResolution = superResolution,
                                )
                                if (showChapters) {
                                    ChapterSheet(details.chapters, chapter.id, { showChapters = false }) {
                                        showChapters = false
                                        if (it != chapter.id) reader.openChapter(it)
                                    }
                                }
                            }
                            browser != null -> {
                                BackHandler { if (browser.canGoBack) reader.webDavBack() else reader.closeWebDav() }
                                WebDavScreen(browser, reader::webDavBack, reader::closeWebDav,
                                    reader::browseWebDav, reader::importWebDav, reader::retryOperation)
                            }
                            details != null -> {
                                BackHandler(onBack = reader::backToShelf)
                                BookScreen(
                                    details, covers, reader::backToShelf,
                                    onResume = { reader.resumeBook(details.book.id) },
                                    onChapter = reader::openChapter,
                                    onRefresh = { reader.refreshBook(details.book.id) },
                                    onOffline = { item ->
                                        if (item.isOffline) reader.removeOffline(item.id) else reader.saveOffline(item.id)
                                    },
                                )
                            }
                            else -> ShelfScreen(
                                reader.books, reader.libraryLoading, covers,
                                onDirectory = { directoryPicker.launch(null) },
                                onWebDav = { sourceDialog = "dav" },
                                onHttps = { sourceDialog = "link" },
                                onClearCache = { clearCacheDialog = true },
                                // 部分文件提供方将 CBZ 标为通用二进制，由导入器验证 ZIP 格式。
                                onArchive = { archivePicker.launch(arrayOf("*/*")) },
                                onSelect = reader::selectBook,
                                onResume = reader::resumeBook,
                            )
                        }
                        sourceDialog?.let { kind ->
                            RemoteSourceDialog(kind == "dav", onDismiss = { sourceDialog = null },
                                onConnect = { url, path, user, password ->
                                    sourceDialog = null
                                    reader.connectWebDav(url, path, user, password)
                                }, onLink = {
                                    sourceDialog = null
                                    reader.importHttpsArchive(it)
                                })
                        }
                        if (clearCacheDialog) AlertDialog(
                            onDismissRequest = { clearCacheDialog = false }, title = { Text("清理阅读缓存") },
                            text = { Text("清理临时下载的漫画文件。已保存的离线章节和阅读进度会保留。") },
                            confirmButton = { TextButton(onClick = {
                                clearCacheDialog = false
                                reader.clearOrdinaryCache()
                            }) { Text("清理") } },
                            dismissButton = { TextButton(onClick = { clearCacheDialog = false }) { Text("取消") } },
                        )
                        reader.error?.let { message ->
                            AlertDialog(
                                onDismissRequest = { reader.showError(null) },
                                title = { Text("操作提示") }, text = { Text(message) },
                                confirmButton = {
                                    if (reader.canRetry) TextButton(reader::retryOperation) { Text("重试") }
                                    else TextButton({ reader.showError(null) }) { Text("知道了") }
                                },
                                dismissButton = {
                                    if (reader.canRetry) TextButton({ reader.showError(null) }) { Text("关闭") }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        reader.flushAnchor()
    }
}

private val ReaderColors = darkColorScheme(
    primary = Color(0xFFFFC184),
    onPrimary = Color(0xFF422B15),
    surfaceVariant = Color(0xFF302D29),
    onSurfaceVariant = Color(0xFFCDC4BA),
    background = Color(0xFF151514),
    surface = Color(0xFF151514),
    onSurface = Color(0xFFF4EFE7),
)
