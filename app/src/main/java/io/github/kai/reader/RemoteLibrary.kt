package io.github.kai.reader

import android.content.Context
import android.os.CancellationSignal
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class RemoteOpenedChapter(
    val chapter: LocalChapter,
    val isOffline: Boolean,
    private val release: Closeable? = null,
) : Closeable {
    override fun close() {
        release?.close()
    }
}

internal class RemoteLibrary(context: Context, private val archives: LocalArchives) {
    private val client = RemoteTransport()
    private val rangeArchives = RemoteRangeArchives(client)
    private val accounts = RemoteAccounts(File(context.noBackupFilesDir, "reader-webdav"))
    private val content = RemoteContentStore(File(context.cacheDir, "reader-remote"), File(context.filesDir, "reader-offline"))
    private val fileLocks = Array(64) { Mutex() }
    private val offlineLocks = Array(32) { Mutex() }
    private val archivePageLocks = Array(32) { Mutex() }

    suspend fun connectWebDav(server: String, rootPath: String, username: String, password: String): RemoteLocation {
        val root = webDavRoot(server, rootPath)
        val name = username.trim()
        if (name.contains(':') || name.any(Char::isISOControl) || name.length > 1024 || password.length > 4096) {
            throw IOException("WebDAV 用户名或密码格式无效。")
        }
        val account = RemoteAccount(root, name, password)
        client.list(RemoteLocation(root), account)
        return readOn(Dispatchers.IO) { signal ->
            signal.throwIfCanceled()
            accounts.put(account)
        }
    }

    suspend fun listWebDav(folder: RemoteLocation): List<RemoteDavEntry> {
        val account = accountFor(folder) ?: throw IOException("请先连接 WebDAV 来源。")
        return client.list(folder, account)
    }

    suspend fun indexWebDav(entry: RemoteDavEntry, onProgress: (RemoteDownloadProgress) -> Unit = {}): IndexedBook {
        if (entry.isDirectory) return indexDavFolder(entry.location, entry.name)
        if (!entry.isArchive) throw IOException("请选择漫画目录或 CBZ / ZIP 压缩包。")
        return indexArchive(entry.location, entry.name.archiveTitle(), entry.version, onProgress)
    }

    suspend fun indexWebDavFolder(folder: RemoteLocation, onProgress: (RemoteDownloadProgress) -> Unit = {}): IndexedBook =
        indexDavFolder(folder, folder.displayName)

    suspend fun importDirect(url: String, onProgress: (RemoteDownloadProgress) -> Unit = {}): IndexedBook {
        val location = RemoteLocation(parseRemoteUrl(url))
        return indexArchive(location, location.displayName.archiveTitle(), expected = null, onProgress)
    }

    suspend fun refreshIndex(sourceUri: String, isArchive: Boolean, onProgress: (RemoteDownloadProgress) -> Unit = {}): IndexedBook {
        val location = RemoteLocation.fromId(sourceUri) ?: throw IOException("远程来源地址已失效，请重新添加。")
        if (!isArchive) return indexDavFolder(location, location.displayName)
        val entry = if (location.isWebDav) archiveEntry(location) else null
        val title = (entry?.name ?: location.displayName).archiveTitle()
        return indexArchive(location, title, entry?.version, onProgress)
    }

    suspend fun openChapter(
        chapter: LocalChapter,
        onProgress: (RemoteDownloadProgress) -> Unit = {},
        forceOnline: Boolean = false,
    ): RemoteOpenedChapter {
        val saved = readOn(Dispatchers.IO, { it?.close() }) { content.offlineChapter(chapter.id) }
        if (!forceOnline && saved != null && chapter.sourceVersion == saved.chapter.sourceVersion) {
            return RemoteOpenedChapter(saved.chapter, true, saved)
        }
        try {
            val opened = openOnline(chapter, onProgress, allowCachedArchive = !forceOnline)
            saved?.close()
            return opened
        } catch (failure: Throwable) {
            val canUseSavedVersion = failure is RemoteUnavailable || failure is RemoteMissing
            if (!forceOnline && canUseSavedVersion && saved != null) {
                return RemoteOpenedChapter(saved.chapter, true, saved)
            }
            saved?.close()
            throw failure
        }
    }

    suspend fun saveOffline(chapter: LocalChapter, onProgress: (RemoteDownloadProgress) -> Unit = {}): LocalChapter =
        offlineLocks.forId(chapter.id).withLock {
            val part = readOn(Dispatchers.IO, File::deleteRecursively) { content.createOfflinePart() }
            try {
                val archive = ArchiveLocation.fromChapterId(chapter.id)
                if (archive != null) {
                    saveArchiveOffline(chapter, archive, part, onProgress)
                } else {
                    saveImagesOffline(chapter, part, onProgress)
                }
            } finally {
                part.deleteRecursively()
            }
        }

    suspend fun hasOffline(chapterId: String): Boolean = readOn(Dispatchers.IO) { content.hasOffline(chapterId) }

    suspend fun deleteOffline(chapterId: String) = offlineLocks.forId(chapterId).withLock {
        readOn(Dispatchers.IO) { content.deleteOffline(chapterId) }
    }

    suspend fun clearCache() = readOn(Dispatchers.IO) { signal ->
        content.clearCache()
        rangeArchives.clearIndexes()
        archives.clearImageCache(signal)
    }

    suspend fun resolvePage(page: ReaderPage, onProgress: (RemoteDownloadProgress) -> Unit): ReaderPage {
        val revision = page.rangeRevision()
        if (revision != null) {
            return withArchiveImageFile(page, revision, onProgress) { backed ->
                val sized = readOn(Dispatchers.IO) { signal ->
                    if (backed.isArchiveEntryFile) readImageFileHeader(checkNotNull(backed.remoteFile), backed, signal)
                    else archives.readHeader(checkNotNull(ArchiveLocation.fromPageId(backed.id)), backed, signal)
                } ?: throw IOException("无法读取远程图片“${page.name}”。")
                sized.copy(remoteFile = null, isArchiveEntryFile = false)
            }
        }
        val location = RemoteLocation.fromId(page.id) ?: return page
        val expected = RemoteVersion.decode(page.remoteVersion)
        return fetch(RemoteFileKind.IMAGE, location, expected, onProgress).use { file ->
            prepareImage(page, file)
        }
    }

    suspend fun <T> withImageFile(page: ReaderPage, read: suspend (ReaderPage) -> T): T {
        if (page.remoteFile != null) return read(page)
        val revision = page.rangeRevision()
        if (revision != null) return withArchiveImageFile(page, revision, {}, read)
        val location = RemoteLocation.fromId(page.id) ?: return read(page)
        return fetch(RemoteFileKind.IMAGE, location, RemoteVersion.decode(page.remoteVersion), {}).use { file ->
            read(page.copy(remoteFile = file.file))
        }
    }

    suspend fun <T> withCoverFile(page: ReaderPage, read: suspend (ReaderPage) -> T): T {
        if (isRemoteCoverPlaceholder(page.id)) throw IOException("此漫画尚未加载封面。")
        if (page.remoteFile != null) return read(page)
        val archive = ArchiveLocation.fromPageId(page.id)?.let { RemoteLocation.fromId(it.sourceUri) }
        val image = RemoteLocation.fromId(page.id)
        if (archive == null && image == null) return read(page)

        val expected = RemoteVersion.decode(page.remoteVersion)
        val cached = readOn(Dispatchers.IO, { it?.close() }) {
            if (archive != null) content.cached(RemoteFileKind.ARCHIVE, archive, expected)
            else content.cached(RemoteFileKind.IMAGE, checkNotNull(image), expected)
        }
        cached?.use { return read(page.copy(remoteFile = it.file)) }

        val saved = readOn(Dispatchers.IO, { it?.close() }) { content.offlineCover(page) }
        saved?.use { snapshot ->
            val file = if (archive != null) File(snapshot.directory, RemoteContentStore.OFFLINE_ARCHIVE)
            else snapshot.chapter.pages.firstOrNull { it.id == page.id }?.remoteFile
            if (file != null) return read(page.copy(remoteFile = file))
        }
        if (image != null) return withImageFile(page, read)
        val revision = page.rangeRevision()
        if (revision != null) return withExtractedArchivePage(page, revision, {}, read)
        throw IOException("压缩包尚未下载，封面暂不可用。")
    }

    private suspend fun <T> withArchiveImageFile(
        page: ReaderPage,
        version: RemoteVersion,
        onProgress: (RemoteDownloadProgress) -> Unit,
        read: suspend (ReaderPage) -> T,
    ): T {
        val entry = checkNotNull(ArchiveLocation.fromPageId(page.id))
        val location = checkNotNull(RemoteLocation.fromId(entry.sourceUri))
        val whole = readOn(Dispatchers.IO, { it?.close() }) { content.cached(RemoteFileKind.ARCHIVE, location, version) }
        whole?.use { return read(page.copy(remoteFile = it.file)) }
        return withExtractedArchivePage(page, version, onProgress, read)
    }

    private suspend fun <T> withExtractedArchivePage(
        page: ReaderPage,
        version: RemoteVersion,
        onProgress: (RemoteDownloadProgress) -> Unit,
        read: suspend (ReaderPage) -> T,
    ): T {
        val entry = checkNotNull(ArchiveLocation.fromPageId(page.id))
        val location = checkNotNull(RemoteLocation.fromId(entry.sourceUri))
        val key = "${page.id}\n${version.encode()}"
        val lease = archivePageLocks.forId(key).withLock {
            val cached = readOn(Dispatchers.IO, { it?.close() }) { content.cachedArchivePage(key) }
            cached ?: extractArchivePage(key, location, entry, page.archiveVersion, version, onProgress)
        }
        return lease.use { read(page.copy(remoteFile = it.file, isArchiveEntryFile = true)) }
    }

    private suspend fun extractArchivePage(
        key: String,
        location: RemoteLocation,
        entry: ArchiveLocation,
        entryVersion: String?,
        version: RemoteVersion,
        onProgress: (RemoteDownloadProgress) -> Unit,
    ): RemoteFileLease {
        val generation = content.archivePageGeneration()
        val account = accountFor(location)
        val part = readOn(Dispatchers.IO, File::delete) { content.createPart() }
        var keepPart = false
        try {
            val info = rangeArchives.extract(location, account, version, entry.path, entryVersion, part, onProgress)
            val lease = readOn(Dispatchers.IO, RemoteFileLease::close) { signal ->
                validateDownloadedImage(part, signal)
                signal.throwIfCanceled()
                // 若用户在读取期间清掉缓存，正在使用的图片读完即删。
                content.cacheArchivePage(key, part, info, generation) ?: RemoteFileLease(part, info, Closeable { part.delete() })
            }
            keepPart = lease.file == part
            return lease
        } finally {
            if (!keepPart) part.delete()
        }
    }

    private suspend fun indexDavFolder(location: RemoteLocation, title: String): IndexedBook {
        val entries = listWebDav(location)
        val direct = scatterChapter(location, title, entries)
        val chapters = if (direct.pages.isNotEmpty()) listOf(direct) else buildList {
            for (entry in entries) {
                if (entry.isDirectory) {
                    add(LocalChapter(entry.location.id, entry.name, emptyList()))
                } else if (entry.isArchive) {
                    val id = ArchiveLocation(entry.location.id, "").chapterId()
                    val version = entry.version?.takeIf(RemoteVersion::hasValidator)?.encode()
                    add(LocalChapter(id, entry.name.archiveTitle(), emptyList(), version))
                }
            }
        }
        if (chapters.isEmpty()) throw IOException("此目录没有可阅读的漫画图片或压缩包。")
        val cover = entries.firstOrNull { it.isImage && isCoverImage(it.location.displayName) }?.readerPage()
            ?: chapters.firstNotNullOfOrNull { it.pages.firstOrNull() }
            ?: ReaderPage("$REMOTE_COVER_PREFIX${location.id}", title, 0, 0)
        return IndexedBook(location.id, title, location.id, false, cover, chapters)
    }

    private fun scatterChapter(location: RemoteLocation, title: String, entries: List<RemoteDavEntry>): LocalChapter {
        val pages = entries.filter { it.isImage && !isCoverImage(it.location.displayName) }.map { it.readerPage() }
        val version = remoteDigest(pages.joinToString("\n") { "${it.id}\t${it.remoteVersion.orEmpty()}" })
        return LocalChapter(location.id, title, pages, version)
    }

    private suspend fun indexArchive(
        location: RemoteLocation,
        title: String,
        expected: RemoteVersion?,
        onProgress: (RemoteDownloadProgress) -> Unit,
    ): IndexedBook {
        val ranged = rangeCatalog(location, title, split = true)
        if (ranged != null) return ranged.toRemoteBook(location, title)
        return fetch(RemoteFileKind.ARCHIVE, location, expected, onProgress).use { archive ->
            val catalog = archiveCatalog(archive, location, title, split = true)
            catalog.copy(
                cover = catalog.cover?.copy(remoteVersion = archive.info.version.encode()),
                chapters = catalog.chapters.map { it.withArchive(archive, location, expected, attachFile = false) },
            ).toRemoteBook(location, title)
        }
    }

    private fun ArchiveCatalog.toRemoteBook(location: RemoteLocation, title: String): IndexedBook {
        val firstPage = chapters.firstOrNull()?.pages?.firstOrNull()
            ?: throw IOException("压缩包中没有可读的 JPG、PNG 或 WebP 图片。")
        return IndexedBook(location.id, title, location.id, true, cover ?: firstPage, chapters)
    }

    private suspend fun openOnline(
        chapter: LocalChapter,
        onProgress: (RemoteDownloadProgress) -> Unit,
        allowCachedArchive: Boolean,
    ): RemoteOpenedChapter {
        val archived = ArchiveLocation.fromChapterId(chapter.id)
        if (archived == null) return RemoteOpenedChapter(openScatter(chapter), false)
        val location = RemoteLocation.fromId(archived.sourceUri) ?: throw IOException("找不到远程压缩包来源。")
        val ranged = try {
            rangeCatalog(location, chapter.name, split = archived.path.isNotEmpty())
        } catch (failure: RemoteUnavailable) {
            val opened = openArchiveCopy(chapter, archived, location, onProgress, allowCachedArchive, failure)
            return RemoteOpenedChapter(opened.chapter, false, opened.file)
        }
        if (ranged != null) return RemoteOpenedChapter(ranged.chapterFor(chapter).reuseRangeDimensions(chapter), false)
        val opened = openArchiveCopy(chapter, archived, location, onProgress, allowCachedArchive)
        return RemoteOpenedChapter(opened.chapter, false, opened.file)
    }

    private suspend fun openScatter(chapter: LocalChapter): LocalChapter {
        val location = RemoteLocation.fromId(chapter.id) ?: throw IOException("找不到远程漫画章节。")
        val index = scatterChapter(location, chapter.name, listWebDav(location))
        if (index.pages.isEmpty()) throw RemoteMissing("此章节没有可读的 JPG、PNG 或 WebP 图片。")
        val previous = chapter.pages.associateBy(ReaderPage::id)
        val pages = index.pages.map { page ->
            val old = previous[page.id]
            val currentVersion = RemoteVersion.decode(page.remoteVersion)
            val previousVersion = RemoteVersion.decode(old?.remoteVersion)
            val canReuseBounds = old != null && old.width > 0 && old.height > 0 && previousVersion != null &&
                currentVersion?.matches(previousVersion) == true
            if (canReuseBounds) checkNotNull(old) else page
        }
        return index.copy(pages = pages)
    }

    private suspend fun openArchiveCopy(
        chapter: LocalChapter,
        archived: ArchiveLocation,
        location: RemoteLocation,
        onProgress: (RemoteDownloadProgress) -> Unit,
        allowCachedArchive: Boolean,
        unavailable: RemoteUnavailable? = null,
    ): OpenArchive {
        var expected: RemoteVersion? = null
        val file = try {
            if (unavailable != null) throw unavailable
            expected = if (location.isWebDav) archiveEntry(location).version else null
            fetch(RemoteFileKind.ARCHIVE, location, expected, onProgress)
        } catch (failure: RemoteUnavailable) {
            if (!allowCachedArchive) throw failure
            val requested = expected ?: RemoteVersion.decode(chapter.sourceVersion)
            readOn(Dispatchers.IO, { it?.close() }) {
                content.cached(RemoteFileKind.ARCHIVE, location, requested)
            } ?: throw failure
        }
        try {
            val catalog = archiveCatalog(file, location, chapter.name, split = archived.path.isNotEmpty())
            val current = catalog.chapterFor(chapter)
            val versioned = current.withArchive(file, location, expected, attachFile = true)
            val pages = readOn(Dispatchers.IO) { signal -> archives.prepareFile(file.file, versioned.pages, signal) }
            if (pages.isEmpty()) throw IOException("此章节没有可读的 JPG、PNG 或 WebP 图片。")
            return OpenArchive(versioned.copy(pages = pages), file)
        } catch (failure: Throwable) {
            file.close()
            throw failure
        }
    }

    private suspend fun archiveEntry(location: RemoteLocation): RemoteDavEntry {
        val parent = location.url.resolve(".") ?: throw IOException("压缩包的 WebDAV 路径无效。")
        return listWebDav(location.copy(url = parent)).firstOrNull { entry ->
            entry.isArchive && entry.location.url.folderSegments() == location.url.folderSegments()
        } ?: throw RemoteMissing("远程压缩包不存在或已移动，请刷新来源。")
    }

    private suspend fun archiveCatalog(file: RemoteFileLease, location: RemoteLocation, title: String, split: Boolean): ArchiveCatalog =
        readOn(Dispatchers.IO) { signal ->
            archives.catalogFile(file.file, location.id, title, split, signal).withRootChapterId(location, split)
        }

    private suspend fun rangeCatalog(location: RemoteLocation, title: String, split: Boolean): ArchiveCatalog? {
        val index = rangeArchives.open(location, accountFor(location)) ?: return null
        return withContext(Dispatchers.Default) {
            index.catalog(location.id, title, split).withRootChapterId(location, split)
        }
    }

    private suspend fun saveArchiveOffline(
        chapter: LocalChapter,
        archived: ArchiveLocation,
        part: File,
        onProgress: (RemoteDownloadProgress) -> Unit,
    ): LocalChapter {
        val location = RemoteLocation.fromId(archived.sourceUri) ?: throw IOException("找不到远程压缩包来源。")
        val opened = openArchiveCopy(chapter, archived, location, onProgress, allowCachedArchive = true)
        opened.file.use { file ->
            val snapshot = opened.chapter.withoutBackingFiles()
            return readOn(Dispatchers.IO) { signal ->
                copyVerified(file, File(part, RemoteContentStore.OFFLINE_ARCHIVE), signal)
                signal.throwIfCanceled()
                content.publishOffline(part, snapshot, file.info)
                snapshot
            }
        }
    }

    private suspend fun saveImagesOffline(
        chapter: LocalChapter,
        part: File,
        onProgress: (RemoteDownloadProgress) -> Unit,
    ): LocalChapter {
        val index = openScatter(chapter)
        val sizes = index.pages.mapNotNull { RemoteVersion.decode(it.remoteVersion)?.length }
        val expectedTotal = if (sizes.size == index.pages.size) sizes.sum().takeIf { it >= 0 } else null
        var bytes = 0L
        val pages = index.pages.mapIndexed { position, page ->
            val location = checkNotNull(RemoteLocation.fromId(page.id))
            val start = bytes
            fetch(RemoteFileKind.IMAGE, location, RemoteVersion.decode(page.remoteVersion), { progress ->
                onProgress(RemoteDownloadProgress(start + progress.downloadedBytes, expectedTotal))
            }).use { file ->
                val prepared = prepareImage(page, file)
                readOn(Dispatchers.IO) { signal ->
                    val destination = File(part, RemoteContentStore.offlineImageName(position))
                    copyVerified(file, destination, signal)
                    validateOfflineImage(destination, prepared, signal)
                }
                bytes += checkNotNull(file.info.version.length)
                prepared
            }
        }
        onProgress(RemoteDownloadProgress(bytes, expectedTotal ?: bytes))
        val saved = index.copy(pages = pages)
        return readOn(Dispatchers.IO) { signal ->
            signal.throwIfCanceled()
            content.publishOffline(part, saved, archive = null)
            saved
        }
    }

    private suspend fun prepareImage(page: ReaderPage, file: RemoteFileLease): ReaderPage = readOn(Dispatchers.IO) { signal ->
        readImageFileHeader(file.file, page, signal)?.copy(remoteVersion = file.info.version.encode())
            ?: throw IOException("无法读取远程图片“${page.name}”。")
    }

    private suspend fun fetch(
        kind: RemoteFileKind,
        location: RemoteLocation,
        expected: RemoteVersion?,
        onProgress: (RemoteDownloadProgress) -> Unit,
    ): RemoteFileLease = fileLocks.forId(location.id).withLock {
        var cached = readOn(Dispatchers.IO, { it?.close() }) { content.cached(kind, location, expected?.takeIf(RemoteVersion::canMatch)) }
        try {
            val canUseVersionedImage = kind == RemoteFileKind.IMAGE && expected?.canMatch == true && cached != null
            if (canUseVersionedImage) {
                return@withLock checkNotNull(cached).also { file ->
                    val bytes = file.info.version.length
                    onProgress(RemoteDownloadProgress(bytes ?: 0, bytes))
                    cached = null
                }
            }
            val account = accountFor(location)
            val part = readOn(Dispatchers.IO, File::delete) { content.createPart() }
            try {
                val info = client.download(location, account, part, cached?.info, expected, onProgress)
                if (info == null) {
                    return@withLock checkNotNull(cached).also { file ->
                        val bytes = file.info.version.length
                        onProgress(RemoteDownloadProgress(bytes ?: 0, bytes))
                        cached = null
                    }
                }
                readOn(Dispatchers.IO, RemoteFileLease::close) { signal ->
                    if (kind == RemoteFileKind.ARCHIVE) validateDownloadedArchive(part, signal)
                    else validateDownloadedImage(part, signal)
                    signal.throwIfCanceled()
                    content.cache(kind, location, part, info)
                }
            } finally {
                part.delete()
            }
        } finally {
            cached?.close()
        }
    }

    private suspend fun accountFor(location: RemoteLocation): RemoteAccount? = readOn(Dispatchers.IO) {
        accounts.forLocation(location)
    }

    private data class OpenArchive(val chapter: LocalChapter, val file: RemoteFileLease)
}

private const val REMOTE_COVER_PREFIX = "kai-remote-cover:"

internal fun isRemoteCoverPlaceholder(pageId: String): Boolean = pageId.startsWith(REMOTE_COVER_PREFIX)

private fun RemoteDavEntry.readerPage(): ReaderPage = ReaderPage(location.id, name, 0, 0, remoteVersion = version?.encode())

private fun Array<Mutex>.forId(id: String): Mutex = this[Math.floorMod(id.hashCode(), size)]

private fun ReaderPage.rangeRevision(): RemoteVersion? = RemoteVersion.decode(remoteVersion)?.takeIf { version ->
    version.rangeUrlKey != null && version.etag.asStrongEntityTag() != null && (version.length ?: 0) > 0
}

private fun ArchiveCatalog.chapterFor(chapter: LocalChapter): LocalChapter = chapters.firstOrNull { it.id == chapter.id }
    ?: throw RemoteMissing("远程压缩包内的章节已移动，请刷新这部漫画。")

private fun ArchiveCatalog.withRootChapterId(location: RemoteLocation, split: Boolean): ArchiveCatalog {
    if (!split) return this
    val wholePackage = ArchiveLocation(location.id, "").chapterId()
    val rootChapter = ArchiveLocation(location.id, "\u0000").chapterId()
    // 单包书的根章可以与子目录并存，必须和作品目录中的整包一章使用不同的标识。
    return copy(chapters = chapters.map { if (it.id == wholePackage) it.copy(id = rootChapter) else it })
}

private fun LocalChapter.reuseRangeDimensions(previous: LocalChapter): LocalChapter {
    if (sourceVersion != previous.sourceVersion) return this
    val measured = previous.pages.associateBy(ReaderPage::id)
    return copy(pages = pages.map { page ->
        val old = measured[page.id]
        if (old == null || old.remoteVersion != page.remoteVersion) page else page.copy(
            width = old.width, height = old.height, isBaselineJpeg = old.isBaselineJpeg,
        )
    })
}

private fun LocalChapter.withArchive(
    file: RemoteFileLease,
    location: RemoteLocation,
    expected: RemoteVersion?,
    attachFile: Boolean,
): LocalChapter {
    val actual = file.info.version
    val chapterVersion = when {
        !location.isWebDav -> RemoteVersion(length = actual.length, sha256 = actual.sha256)
        expected?.hasValidator == true && expected.matches(actual) -> expected
        actual.hasValidator -> actual.copy(sha256 = null)
        else -> actual
    }
    return copy(
        pages = pages.map { page ->
            page.copy(remoteVersion = actual.encode(), remoteFile = if (attachFile) file.file else null)
        },
        sourceVersion = chapterVersion.encode(),
    )
}

private fun LocalChapter.withoutBackingFiles(): LocalChapter = copy(pages = pages.map { it.copy(remoteFile = null) })

private fun validateDownloadedImage(file: File, signal: CancellationSignal) {
    signal.throwIfCanceled()
    val bounds = file.inputStream().buffered().use(::readImageBounds)
    if (bounds == null) throw IOException("下载的文件不是可读的 JPG、PNG 或 WebP 图片。")
}

private fun copyVerified(source: RemoteFileLease, destination: File, signal: CancellationSignal) {
    val expected = checkNotNull(source.info.version.length)
    val checkSpace = checkNotNull(destination.parentFile)
    if (expected > checkSpace.usableSpace) throw IOException("可用空间不足，无法保存离线章节。")
    val sha = MessageDigest.getInstance("SHA-256")
    var copied = 0L
    try {
        source.file.inputStream().buffered().use { input ->
            destination.outputStream().use { stream ->
                val output = stream.buffered()
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    signal.throwIfCanceled()
                    val size = input.read(buffer)
                    if (size < 0) break
                    if (copied + size > expected) throw IOException("远程缓存文件已损坏，请重新下载。")
                    output.write(buffer, 0, size)
                    sha.update(buffer, 0, size)
                    copied += size
                }
                output.flush()
                stream.fd.sync()
            }
        }
    } catch (failure: IOException) {
        if (failure.isRemoteDiskFull(checkSpace)) throw IOException("可用空间不足，无法保存离线章节。", failure)
        throw failure
    }
    if (copied != expected || sha.digest().remoteHex() != source.info.version.sha256) {
        throw IOException("远程缓存文件已损坏，请重新下载。")
    }
}
