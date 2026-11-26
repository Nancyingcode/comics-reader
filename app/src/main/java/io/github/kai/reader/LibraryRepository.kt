package io.github.kai.reader

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.room.Room
import androidx.room.withTransaction
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class OpenedChapter(
    val details: BookDetails,
    val chapter: LocalChapter,
    val anchor: ReadingAnchor,
    val fromStart: Boolean,
    pages: List<LibraryPage>,
    private val remote: RemoteOpenedChapter?,
) {
    private val pagesById = pages.associateBy { it.image.id }

    fun page(pageId: String): LibraryPage? = pagesById[pageId]

    fun close() = remote?.close()
}

internal class LibraryRepository private constructor(context: Context) {
    val images = LocalImages(context)
    val remote = images.remote
    private val database = Room.databaseBuilder(context, LibraryDatabase::class.java, "local-library.db")
        .addMigrations(LIBRARY_MIGRATION_1_2)
        .build()
    private val dao = database.library()
    private val legacy by lazy { LegacyReading(context.getSharedPreferences("local-reading", 0).all) }

    private val writes = Mutex()
    private val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val books = dao.observeBooks().map { rows -> rows.map(BookSummaryRow::summary) }

    suspend fun importSource(source: Uri, isArchive: Boolean) = withContext(Dispatchers.IO) {
        val indexed = images.index(source, isArchive)
        saveIndex(indexed)
    }

    suspend fun importWebDav(entry: RemoteDavEntry, onProgress: (RemoteDownloadProgress) -> Unit): String =
        saveIndex(remote.indexWebDav(entry, onProgress))

    suspend fun importWebDavFolder(folder: RemoteLocation, onProgress: (RemoteDownloadProgress) -> Unit): String =
        saveIndex(remote.indexWebDavFolder(folder, onProgress))

    suspend fun importHttpsArchive(url: String, onProgress: (RemoteDownloadProgress) -> Unit): String =
        saveIndex(remote.importDirect(url, onProgress))

    suspend fun refreshBook(bookId: String, onProgress: (RemoteDownloadProgress) -> Unit) {
        val previous = dao.book(bookId) ?: throw IOException("这部漫画已不在书架中。")
        val index = remote.refreshIndex(previous.sourceUri, previous.isArchive, onProgress)
        saveIndex(index, expectedIndexedAt = previous.indexedAt)
    }

    suspend fun restoreLegacyLibrary(): Int = withContext(Dispatchers.IO) {
        var unavailable = 0
        for (tree in legacy.trees) {
            if (dao.bookForSource(documentKey(tree), isArchive = false) != null) continue
            val indexed = try {
                images.index(Uri.parse(tree), isArchive = false)
            } catch (failure: IOException) {
                unavailable++
                continue
            } catch (failure: SecurityException) {
                unavailable++
                continue
            } catch (failure: IllegalArgumentException) {
                unavailable++
                continue
            }
            // Keep the old preferences so an unavailable directory can recover on a later import.
            saveIndex(indexed, onlyIfMissing = true)
        }
        unavailable
    }

    suspend fun bookDetails(bookId: String): BookDetails? {
        val index = database.withTransaction { readDetailsIndex(bookId) }
        return index?.details()
    }

    private suspend fun chapterIndex(bookId: String, chapterId: String?): ChapterIndex = writes.withLock {
        database.withTransaction {
            val book = dao.book(bookId) ?: throw IOException("这部漫画已不在书架中。")
            val isRemote = RemoteLocation.fromId(book.sourceUri) != null
            val chapter = if (chapterId == null) {
                dao.chapterToResume(bookId, book.lastChapterKey, canOpenUnindexed = isRemote)
            } else {
                dao.chapter(bookId, chapterId)
            } ?: throw IOException("找不到可阅读的章节，请重新导入漫画。")
            val progress = dao.chapterProgress(bookId, chapter.sourceKey)
            ChapterIndex(book, chapter, dao.pages(bookId, chapter.id), progress, isRemote)
        }
    }

    suspend fun openChapter(
        bookId: String,
        chapterId: String?,
        fromStart: Boolean,
        forceOnline: Boolean = false,
        onProgress: (RemoteDownloadProgress) -> Unit = {},
    ): OpenedChapter {
        val indexed = chapterIndex(bookId, chapterId)
        val source = indexed.readerChapter()
        val remoteChapter = if (indexed.isRemote) remote.openChapter(source, onProgress, forceOnline) else null
        try {
            val chapter = remoteChapter?.chapter ?: images.prepare(source)
            val pages = indexedPages(indexed.chapter, chapter.pages)
            val progress = if (fromStart) null else indexed.progress
            val anchor = pages.takeIf { it.isNotEmpty() }?.let { resolveAnchor(it, progress) }
            val prepared = if (indexed.isRemote && anchor != null) {
                resolveOpeningPage(chapter, anchor, onProgress)
            } else {
                chapter
            }
            val preparedPages = if (prepared === chapter) pages else indexedPages(indexed.chapter, prepared.pages)
            val details = writes.withLock {
                database.withTransaction {
                    val currentBook = dao.book(bookId)
                    val currentChapter = dao.chapter(bookId, source.id)
                    if (currentBook?.indexedAt != indexed.book.indexedAt || currentChapter == null) {
                        throw IOException("漫画索引已更新，请重新打开章节。")
                    }
                    val canUsePreparedIndex = remoteChapter?.isOffline != true ||
                        canUpdateFromOffline(indexed.chapter.sourceVersion, prepared.sourceVersion)
                    if (canUsePreparedIndex && currentChapter.canUpdateFrom(indexed.chapter, prepared)) {
                        updatePreparedPages(currentBook, currentChapter, preparedPages, prepared.sourceVersion)
                    }
                    checkNotNull(readDetailsIndex(bookId))
                }
            }
            if (anchor == null) throw IOException("本章没有可读的 JPG、PNG 或 WebP 图片。")
            return OpenedChapter(
                details.details(), prepared, anchor, fromStart, preparedPages, remoteChapter,
            ).also { currentCoroutineContext().ensureActive() }
        } catch (failure: Throwable) {
            remoteChapter?.close()
            throw failure
        }
    }

    private suspend fun resolveOpeningPage(
        chapter: LocalChapter,
        anchor: ReadingAnchor,
        onProgress: (RemoteDownloadProgress) -> Unit,
    ): LocalChapter {
        val position = chapter.pages.indexOfFirst { it.id == anchor.pageId }
        val page = chapter.pages[position]
        val hasDimensions = page.width > 0 && page.height > 0
        if (hasDimensions) return chapter

        // Restore into the target page's real height before the first layout, including single-image chapters.
        val sized = images.resolveRemotePage(page, onProgress)
        return chapter.copy(pages = chapter.pages.toMutableList().apply { this[position] = sized })
    }

    suspend fun saveOffline(bookId: String, chapterId: String, onProgress: (RemoteDownloadProgress) -> Unit) {
        val indexed = chapterIndex(bookId, chapterId)
        if (!indexed.isRemote) return
        val saved = remote.saveOffline(indexed.readerChapter(), onProgress)
        val pages = indexedPages(indexed.chapter, saved.pages)
        writes.withLock {
            database.withTransaction {
                val currentBook = dao.book(bookId) ?: return@withTransaction
                val currentChapter = dao.chapter(bookId, chapterId) ?: return@withTransaction
                if (currentBook.indexedAt != indexed.book.indexedAt) return@withTransaction
                if (!currentChapter.canUpdateFrom(indexed.chapter, saved)) return@withTransaction
                updatePreparedPages(currentBook, currentChapter, pages, saved.sourceVersion)
            }
        }
    }

    suspend fun removeOffline(bookId: String, chapterId: String) {
        val indexed = chapterIndex(bookId, chapterId)
        if (indexed.isRemote) remote.deleteOffline(indexed.chapter.id)
    }

    suspend fun clearOrdinaryCache() {
        remote.clearCache()
    }

    fun recordOpened(opened: OpenedChapter) {
        enqueueWrite {
            val book = bookFor(opened) ?: return@enqueueWrite
            val chapter = dao.chapter(book.id, opened.chapter.id) ?: return@enqueueWrite
            val readAt = maxOf(System.currentTimeMillis(), (dao.latestReadAt() ?: 0) + 1)
            val saved = dao.chapterProgress(book.id, chapter.sourceKey)
            if (saved != null && !opened.fromStart) {
                dao.putProgress(saved.copy(updatedAt = readAt))
            } else {
                writeProgress(opened, chapter, opened.anchor, readAt)
            }
            dao.markRead(book.id, chapter.sourceKey, readAt)
        }
    }

    fun saveAnchor(opened: OpenedChapter, anchor: ReadingAnchor) {
        enqueueWrite {
            val book = bookFor(opened) ?: return@enqueueWrite
            val chapter = dao.chapter(book.id, opened.chapter.id) ?: return@enqueueWrite
            val previous = dao.chapterProgress(book.id, chapter.sourceKey)
            val readAt = maxOf(System.currentTimeMillis(), (previous?.updatedAt ?: 0) + 1)
            writeProgress(opened, chapter, anchor, readAt)
        }
    }

    private fun enqueueWrite(write: suspend () -> Unit) {
        // Acquire the lock in event order. Writes must finish even after the ViewModel is cleared.
        writeScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                writes.withLock { database.withTransaction { write() } }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.e("KaiLibrary", "Unable to save reading position", failure)
            }
        }
    }

    private suspend fun bookFor(opened: OpenedChapter): LibraryBook? {
        val book = opened.details.book
        return dao.book(book.id)?.takeIf { it.indexedAt == book.indexedAt }
    }

    private suspend fun writeProgress(opened: OpenedChapter, chapter: LibraryChapter, anchor: ReadingAnchor, readAt: Long) {
        val page = opened.page(anchor.pageId) ?: return
        dao.putProgress(LibraryProgress(
            chapter.bookId, chapter.sourceKey, page.sourceKey, page.position, anchor.fraction.readingFraction(), readAt,
        ))
    }

    private suspend fun saveIndex(
        index: IndexedBook,
        onlyIfMissing: Boolean = false,
        expectedIndexedAt: Long? = null,
    ): String {
        if (index.chapters.isEmpty()) throw IOException("这个来源没有可阅读的章节。")
        val sourceKey = documentKey(index.sourceUri)
        return writes.withLock {
            database.withTransaction {
                val previous = dao.bookForSource(sourceKey, index.isArchive)
                if (onlyIfMissing && previous != null) return@withTransaction previous.id
                if (expectedIndexedAt != null && previous?.indexedAt != expectedIndexedAt) {
                    throw IOException("漫画索引已更新，请重试刷新。")
                }
                replaceIndex(index, sourceKey, previous)
            }
        }
    }

    private suspend fun replaceIndex(index: IndexedBook, sourceKey: String, previous: LibraryBook?): String {
        val bookId = previous?.id ?: index.id
        val content = rebuildChapters(index, bookId, previous)
        val existingProgress = dao.bookProgress(bookId).associateBy(LibraryProgress::chapterKey)
        val progress = content.mapNotNull { (chapter, pages) ->
            val saved = existingProgress[chapter.sourceKey]
                ?: dao.latestProgressForSource(chapter.sourceKey)
                ?: legacy.progress(bookId, chapter.sourceKey)
                ?: return@mapNotNull null
            val pagePosition = pages.indexOfFirst { it.sourceKey == saved.pageKey }
            saved.copy(bookId = bookId, pagePosition = pagePosition.takeIf { it >= 0 } ?: saved.pagePosition)
        }
        val chapters = content.map(IndexedChapter::chapter)
        val newestProgress = progress.maxByOrNull(LibraryProgress::updatedAt)
        val openedInLegacy = sourceKey == legacy.lastChapterKey &&
            (newestProgress?.updatedAt ?: 0) <= LEGACY_LAST_READ_AT
        val legacyChapter = if (openedInLegacy) {
            chapters.firstOrNull { it.sourceKey == sourceKey } ?: chapters.first()
        } else {
            null
        }
        val lastChapterKey = previous?.lastChapterKey ?: legacyChapter?.sourceKey ?: newestProgress?.chapterKey
        val lastReadAt = previous?.lastReadAt ?: if (openedInLegacy) LEGACY_LAST_READ_AT else newestProgress?.updatedAt
        val cover = if (needsCoverPage(index.cover.id)) {
            content.firstNotNullOfOrNull { it.pages.firstOrNull()?.image } ?: LibraryImage(index.cover)
        } else {
            LibraryImage(index.cover)
        }
        val book = LibraryBook(
            id = bookId,
            sourceKey = sourceKey,
            sourceUri = index.sourceUri,
            isArchive = index.isArchive,
            name = index.name,
            cover = cover,
            chapterCount = chapters.size,
            pageCount = chapters.sumOf(LibraryChapter::pageCount),
            indexedAt = maxOf(System.currentTimeMillis(), (previous?.indexedAt ?: 0) + 1),
            lastChapterKey = lastChapterKey,
            lastReadAt = lastReadAt,
        )
        dao.putBook(book)
        dao.deleteChapters(bookId)
        dao.putChapters(chapters)
        for ((_, pages) in content) dao.putPages(pages)
        dao.putProgress(progress)
        return bookId
    }

    private suspend fun rebuildChapters(index: IndexedBook, bookId: String, previous: LibraryBook?): List<IndexedChapter> {
        val canReuseRemoteIndex = previous != null && RemoteLocation.fromId(index.sourceUri) != null
        val previousChapters = if (canReuseRemoteIndex) dao.chapters(bookId).associateBy(LibraryChapter::id) else emptyMap()
        val currentChapters = index.chapters.mapIndexed { position, source ->
            val chapter = LibraryChapter(
                bookId, source.id, chapterSourceKey(source.id), source.name, position, source.pages.size, source.sourceVersion,
            )
            val priorChapter = previousChapters[chapter.id]
            if (priorChapter != null && canReuseUnopenedPages(source, priorChapter)) {
                val carriedIndex = chapter.copy(pageCount = priorChapter.pageCount, sourceVersion = priorChapter.sourceVersion)
                IndexedChapter(carriedIndex, dao.pages(bookId, chapter.id))
            } else {
                IndexedChapter(chapter, indexedPages(chapter, source.pages))
            }
        }
        if (previousChapters.isEmpty()) return currentChapters

        val currentIds = currentChapters.mapTo(HashSet()) { it.chapter.id }
        return buildList {
            addAll(currentChapters)
            for (chapter in previousChapters.values) {
                // A server-side deletion must leave a chapter the reader explicitly saved accessible.
                if (chapter.id in currentIds || !remote.hasOffline(chapter.id)) continue
                add(IndexedChapter(chapter.copy(position = size), dao.pages(bookId, chapter.id)))
            }
        }
    }

    private suspend fun updatePreparedPages(
        book: LibraryBook,
        chapter: LibraryChapter,
        pages: List<LibraryPage>,
        sourceVersion: String?,
    ) {
        val hasSameIndex = chapter.sourceVersion == sourceVersion && chapter.pageCount == pages.size
        if (hasSameIndex && dao.pages(book.id, chapter.id) == pages) return

        val useFirstPageAsCover = needsCoverPage(book.cover.id) || dao.hasPage(book.id, book.cover.id)
        dao.deletePages(book.id, chapter.id)
        dao.putPages(pages)
        dao.updateChapterIndex(book.id, chapter.id, pages.size, sourceVersion)
        val cover = if (useFirstPageAsCover) dao.firstPage(book.id)?.image ?: book.cover else book.cover
        dao.putBook(book.copy(cover = cover, pageCount = book.pageCount - chapter.pageCount + pages.size))

        val progress = dao.chapterProgress(book.id, chapter.sourceKey) ?: return
        val page = pages.firstOrNull { it.sourceKey == progress.pageKey } ?: return
        if (progress.pagePosition != page.position) dao.putProgress(progress.copy(pagePosition = page.position))
    }

    private suspend fun readDetailsIndex(bookId: String): BookDetailsIndex? {
        val summary = dao.bookSummary(bookId)?.summary() ?: return null
        return BookDetailsIndex(summary, dao.chapterSummaries(bookId))
    }

    private suspend fun BookDetailsIndex.details(): BookDetails {
        val summaries = chapters.map { row ->
            row.summary(book.isRemote, isOffline = book.isRemote && remote.hasOffline(row.id))
        }
        return BookDetails(book, summaries)
    }

    companion object {
        @Volatile private var instance: LibraryRepository? = null

        fun get(context: Context): LibraryRepository = instance ?: synchronized(this) {
            instance ?: LibraryRepository(context.applicationContext).also { instance = it }
        }
    }
}

private data class BookDetailsIndex(val book: BookSummary, val chapters: List<ChapterSummaryRow>)

private data class IndexedChapter(val chapter: LibraryChapter, val pages: List<LibraryPage>)

private data class ChapterIndex(
    val book: LibraryBook,
    val chapter: LibraryChapter,
    val pages: List<LibraryPage>,
    val progress: LibraryProgress?,
    val isRemote: Boolean,
) {
    fun readerChapter(): LocalChapter =
        LocalChapter(chapter.id, chapter.name, pages.map { it.image.readerPage() }, chapter.sourceVersion)
}

private fun LibraryChapter.canUpdateFrom(indexed: LibraryChapter, prepared: LocalChapter): Boolean =
    sourceVersion == indexed.sourceVersion || sourceVersion == prepared.sourceVersion

internal fun canReuseUnopenedPages(discovered: LocalChapter, previous: LibraryChapter): Boolean =
    discovered.pages.isEmpty() && previous.pageCount > 0 && matchesSourceVersion(discovered.sourceVersion, previous.sourceVersion)

internal fun canUpdateFromOffline(indexedVersion: String?, offlineVersion: String?): Boolean =
    indexedVersion == null || matchesSourceVersion(indexedVersion, offlineVersion)

private fun matchesSourceVersion(expected: String?, actual: String?): Boolean {
    if (expected == null || actual == null) return false
    if (expected == actual) return true
    val requested = RemoteVersion.decode(expected) ?: return false
    val available = RemoteVersion.decode(actual) ?: return false
    return requested.matches(available)
}

private fun needsCoverPage(id: String): Boolean = id.isBlank() || isRemoteCoverPlaceholder(id)

private fun indexedPages(chapter: LibraryChapter, pages: List<ReaderPage>): List<LibraryPage> =
    pages.mapIndexed { position, page ->
        LibraryPage(chapter.bookId, chapter.id, pageSourceKey(page.id), position, LibraryImage(page))
    }

internal fun resolveAnchor(pages: List<LibraryPage>, progress: LibraryProgress?): ReadingAnchor {
    if (progress != null) {
        val page = pages.firstOrNull { it.sourceKey == progress.pageKey }
        if (page != null) return ReadingAnchor(page.image.id, progress.pageFraction.readingFraction())
    }
    val fallback = (progress?.pagePosition ?: 0).coerceIn(0, pages.lastIndex)
    return ReadingAnchor(pages[fallback].image.id, 0.0)
}
