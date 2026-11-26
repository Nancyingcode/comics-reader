package io.github.kai.reader

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

internal data class LibraryOperation(val label: String, val progress: RemoteDownloadProgress? = null)

internal data class WebDavBrowser(
    val path: String,
    val entries: List<RemoteDavEntry> = emptyList(),
    val canGoBack: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
)

class ReaderViewModel(application: Application) : AndroidViewModel(application) {
    private val library = LibraryRepository.get(application)
    val images = library.images

    private var navigationJob: Job? = null
    private var navigationAttempt: NavigationAttempt? = null
    private var retryRequest by mutableStateOf<NavigationRequest?>(null)
    private var webDavJob: Job? = null
    private var webDavTrail = emptyList<RemoteLocation>()
    private var retryWebDavRequest: (suspend () -> List<RemoteLocation>)? = null
    private var saveJob: Job? = null
    private var pendingAnchor: ReadingAnchor? = null
    private var nextSessionId = 0L
    private var session by mutableStateOf<ReaderSession?>(null)
    private var waitingForBooks by mutableStateOf(true)
    private var migratingHistory by mutableStateOf(true)

    val activeChapter: LocalChapter? get() = session?.opened?.chapter
    val sessionId: Long get() = session?.id ?: 0L
    val libraryLoading: Boolean get() = waitingForBooks || migratingHistory
    val loading: Boolean get() = operation != null
    internal val canRetry: Boolean get() = retryRequest != null
    var currentAnchor: ReadingAnchor? = null
        private set
    var books by mutableStateOf<List<BookSummary>>(emptyList())
        private set
    var selectedBook by mutableStateOf<BookDetails?>(null)
        private set
    internal var operation by mutableStateOf<LibraryOperation?>(null)
        private set
    internal var webDavBrowser by mutableStateOf<WebDavBrowser?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    init {
        viewModelScope.launch {
            try {
                library.books.collect { summaries ->
                    books = summaries
                    waitingForBooks = false
                    val selection = selectedBook ?: return@collect
                    val refreshed = library.bookDetails(selection.book.id)
                    if (selectedBook === selection) selectedBook = refreshed
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                logLibraryError(failure)
                showError("无法读取本地书库。")
            } finally {
                waitingForBooks = false
            }
        }
        viewModelScope.launch {
            try {
                val unavailable = library.restoreLegacyLibrary()
                if (unavailable > 0 && error == null) {
                    showError("有 $unavailable 个旧版目录暂时无法访问。重新选择来源后会恢复仍然存在的页面记录。")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                logLibraryError(failure)
                if (error == null) showError("暂时无法导入旧版阅读记录。")
            } finally {
                migratingHistory = false
            }
        }
    }

    fun importSource(source: Uri, isArchive: Boolean) {
        navigate { library.importSource(source, isArchive) }
    }

    internal fun connectWebDav(server: String, rootPath: String, username: String, password: String) {
        webDavTrail = emptyList()
        loadWebDav(WebDavBrowser(rootPath.ifBlank { "/" })) {
            listOf(library.remote.connectWebDav(server, rootPath, username, password))
        }
    }

    internal fun browseWebDav(entry: RemoteDavEntry) {
        val browser = webDavBrowser ?: return
        if (!entry.isDirectory || browser.loading) return
        val trail = webDavTrail + entry.location
        loadWebDav(browser) { trail }
    }

    internal fun webDavBack() {
        val browser = webDavBrowser ?: return
        if (!browser.canGoBack || browser.loading) return
        val parentTrail = webDavTrail.dropLast(1)
        loadWebDav(browser) { parentTrail }
    }

    private fun loadWebDav(previous: WebDavBrowser, getTrail: suspend () -> List<RemoteLocation>) {
        webDavJob?.cancel()
        retryWebDavRequest = null
        webDavBrowser = previous.copy(loading = true, error = null)
        webDavJob = viewModelScope.launch {
            try {
                val trail = getTrail()
                val folder = trail.last()
                val entries = library.remote.listWebDav(folder).filter { it.isDirectory || it.isArchive }
                ensureActive()
                webDavTrail = trail
                webDavBrowser = WebDavBrowser(folder.displayPath, entries, canGoBack = trail.size > 1)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                ensureActive()
                retryWebDavRequest = getTrail
                webDavBrowser = previous.copy(loading = false, error = libraryErrorMessage(failure))
            }
        }
    }

    internal fun closeWebDav() {
        webDavJob?.cancel()
        webDavJob = null
        webDavTrail = emptyList()
        retryWebDavRequest = null
        webDavBrowser = null
    }

    internal fun importWebDav(entry: RemoteDavEntry? = null) {
        val folder = webDavTrail.lastOrNull() ?: return
        closeWebDav()
        navigate("正在导入 WebDAV 漫画…", retriable = true) { progress ->
            val bookId = if (entry == null) {
                library.importWebDavFolder(folder, progress)
            } else {
                library.importWebDav(entry, progress)
            }
            selectedBook = requireBookDetails(bookId)
        }
    }

    internal fun importHttpsArchive(url: String) {
        navigate("正在导入远程压缩包…", retriable = true) { progress ->
            val bookId = library.importHttpsArchive(url, progress)
            selectedBook = requireBookDetails(bookId)
        }
    }

    fun selectBook(bookId: String) {
        navigate { selectedBook = requireBookDetails(bookId) }
    }

    internal fun refreshBook(bookId: String) {
        navigate("正在刷新远程漫画…", retriable = true) { progress ->
            library.refreshBook(bookId, progress)
            reloadSelectedBook(bookId)
        }
    }

    internal fun saveOffline(chapterId: String) {
        val bookId = selectedBook?.book?.id ?: return
        navigate("正在保存离线章节…", retriable = true) { progress ->
            library.saveOffline(bookId, chapterId, progress)
            reloadSelectedBook(bookId)
        }
    }

    internal fun removeOffline(chapterId: String) {
        val bookId = selectedBook?.book?.id ?: return
        navigate("正在删除离线章节…", retriable = true) {
            library.removeOffline(bookId, chapterId)
            reloadSelectedBook(bookId)
        }
    }

    internal fun clearOrdinaryCache() {
        navigate("正在清理普通缓存…", retriable = true) {
            library.clearOrdinaryCache()
        }
    }

    private suspend fun requireBookDetails(bookId: String): BookDetails =
        library.bookDetails(bookId) ?: throw IOException("这部漫画已不在书架中。")

    private suspend fun reloadSelectedBook(bookId: String) {
        if (selectedBook?.book?.id != bookId) return
        val details = requireBookDetails(bookId)
        if (selectedBook?.book?.id == bookId) selectedBook = details
    }

    fun openChapter(chapterId: String, fromStart: Boolean = false) {
        val bookId = selectedBook?.book?.id ?: return
        openReading(bookId, chapterId, fromStart)
    }

    fun resumeBook(bookId: String) {
        openReading(bookId, chapterId = null, fromStart = false)
    }

    internal fun reopenChapter(chapterId: String) {
        val bookId = selectedBook?.book?.id ?: return
        openReading(bookId, chapterId, fromStart = false, forceOnline = true)
    }

    private fun openReading(bookId: String, chapterId: String?, fromStart: Boolean, forceOnline: Boolean = false) {
        val book = selectedBook?.book?.takeIf { it.id == bookId } ?: books.firstOrNull { it.id == bookId }
        val isRemote = book?.isRemote == true
        navigate(label = "正在打开章节…", retriable = isRemote) { progress ->
            val opened = library.openChapter(bookId, chapterId, fromStart, forceOnline, progress)
            try {
                currentCoroutineContext().ensureActive()
            } catch (cancelled: CancellationException) {
                opened.close()
                throw cancelled
            }
            selectedBook = opened.details
            currentAnchor = opened.anchor
            session = ReaderSession(++nextSessionId, opened)
            library.recordOpened(opened)
        }
    }

    private fun navigate(
        label: String = "正在读取漫画…",
        retriable: Boolean = false,
        action: suspend ((RemoteDownloadProgress) -> Unit) -> Unit,
    ) {
        runNavigation(NavigationRequest(label, retriable, action))
    }

    private fun runNavigation(request: NavigationRequest) {
        navigationJob?.cancel()
        finishReading()
        val attempt = NavigationAttempt(request)
        navigationAttempt = attempt
        retryRequest = null
        operation = LibraryOperation(request.label)
        error = null
        navigationJob = viewModelScope.launch {
            val progress = MutableStateFlow<RemoteDownloadProgress?>(null)
            val observer = launch {
                progress.collect { value ->
                    if (navigationAttempt === attempt) operation = LibraryOperation(request.label, value)
                }
            }
            try {
                request.action { progress.value = it }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                ensureActive()
                offerRetry(request)
                showError(libraryErrorMessage(failure))
            } finally {
                observer.cancel()
                // A cancelled request may finish after a newer navigation request has started.
                if (navigationAttempt === attempt) {
                    navigationAttempt = null
                    operation = null
                }
            }
        }
    }

    private fun offerRetry(request: NavigationRequest) {
        if (!request.retriable) return
        retryRequest = request
    }

    internal fun retryOperation() {
        val browser = webDavBrowser
        val browseAgain = retryWebDavRequest
        if (browser?.error != null && browseAgain != null) {
            loadWebDav(browser, browseAgain)
            return
        }
        retryRequest?.let(::runNavigation)
    }

    fun reportAnchor(sessionId: Long, anchor: ReadingAnchor) {
        if (session?.id != sessionId || currentAnchor == anchor) return
        currentAnchor = anchor
        pendingAnchor = anchor
        if (saveJob?.isActive == true) return
        saveJob = viewModelScope.launch {
            delay(350)
            flushAnchor()
        }
    }

    fun flushAnchor() {
        saveJob?.cancel()
        saveJob = null
        val opened = session?.opened ?: return
        val anchor = pendingAnchor ?: return
        pendingAnchor = null
        library.saveAnchor(opened, anchor)
    }

    fun closeReader() {
        cancelLoading()
        finishReading()
    }

    fun backToShelf() {
        closeReader()
        selectedBook = null
    }

    fun cancelLoading() {
        val active = navigationAttempt
        navigationAttempt = null
        navigationJob?.cancel()
        navigationJob = null
        operation = null
        if (active?.request?.retriable == true) {
            offerRetry(active.request)
            showError("操作已取消。")
        }
    }

    fun showError(message: String?) {
        error = message
    }

    override fun onCleared() {
        finishReading()
        super.onCleared()
    }

    private fun finishReading() {
        flushAnchor()
        val previous = session
        session = null
        currentAnchor = null
        previous?.opened?.close()
    }

    private fun libraryErrorMessage(failure: Exception): String = when (failure) {
        is SecurityException -> "文件访问权限已失效，请重新选择这个来源。"
        is IOException -> failure.message ?: "无法读取这个来源，请检查文件是否仍可访问。"
        is IllegalArgumentException -> "来源地址或文件无效，请检查后重试。"
        else -> {
            logLibraryError(failure)
            "暂时无法打开这部漫画。"
        }
    }

    private fun logLibraryError(failure: Exception) {
        Log.e("KaiLibrary", "Unable to access the library", failure)
    }

    private data class NavigationRequest(
        val label: String,
        val retriable: Boolean,
        val action: suspend ((RemoteDownloadProgress) -> Unit) -> Unit,
    )

    private class NavigationAttempt(val request: NavigationRequest)

    private data class ReaderSession(val id: Long, val opened: OpenedChapter)
}
