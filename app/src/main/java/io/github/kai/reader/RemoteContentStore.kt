package io.github.kai.reader

import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

internal enum class RemoteFileKind(val directoryName: String) {
    ARCHIVE("archives"),
    IMAGE("images"),
}

internal class RemoteFileLease(val file: File, val info: RemoteFileInfo, private val release: Closeable) : Closeable {
    override fun close() = release.close()
}

internal class RemoteOfflineLease(
    val directory: File,
    val chapter: LocalChapter,
    val archive: RemoteFileInfo?,
    private val release: Closeable,
) : Closeable {
    override fun close() = release.close()
}

internal class RemoteContentStore(
    private val ordinary: File,
    private val offline: File,
    private val ordinaryLimit: Long = 1024L * 1024 * 1024,
) {
    private val guard = Any()
    private val parts = File(ordinary, "incoming")
    private val archiveParts = File(offline, "incoming")
    private val pins = mutableMapOf<File, Int>()
    private val evictAfterClose = mutableSetOf<File>()
    private val cachedSizes = mutableMapOf<RemoteFileKind, Long>()
    private val recentFiles = mutableMapOf<RemoteFileKind, File>()
    private var archivePageGeneration = 0L

    init {
        // Incomplete downloads have no valid index after process death and cannot be resumed.
        parts.listFiles()?.forEach(File::delete)
        archiveParts.listFiles()?.forEach(File::deleteRecursively)
        offline.listFiles().orEmpty().filter { it.name.isRemoteDigest() }.forEach { directory ->
            removeOfflineVersions(directory, keep = currentOfflineDirectory(directory))
        }
    }

    fun createPart(): File = Files.createTempFile(parts.requireDirectory().toPath(), "download-", ".part").toFile()

    fun cached(kind: RemoteFileKind, location: RemoteLocation, expected: RemoteVersion? = null): RemoteFileLease? =
        cached(kind, location.id, expected) { remoteDigest(location.url.toString()) }

    fun archivePageGeneration(): Long = synchronized(guard) { archivePageGeneration }

    fun cachedArchivePage(key: String): RemoteFileLease? = cached(RemoteFileKind.IMAGE, key)

    fun cacheArchivePage(key: String, part: File, info: RemoteFileInfo, generation: Long): RemoteFileLease? =
        synchronized(guard) {
            if (generation != archivePageGeneration) return@synchronized null
            cache(RemoteFileKind.IMAGE, key, part, info)
        }

    private fun cached(
        kind: RemoteFileKind,
        key: String,
        expected: RemoteVersion? = null,
        fallbackUrlKey: (() -> String)? = null,
    ): RemoteFileLease? =
        synchronized(guard) {
            val directory = cacheDirectory(kind, key)
            val latest = readRecord(File(directory, CURRENT), DataInputStream::readRemoteInfo)
            val latestMatches = latest != null && (expected == null || latest.matches(expected))
            val info = if (latestMatches) latest else expected?.takeIf { it.sha256 != null }?.let {
                val urlKey = it.rangeUrlKey ?: fallbackUrlKey?.invoke() ?: return@let null
                RemoteFileInfo(it, urlKey)
            }
            if (info == null) return@synchronized null
            val hash = info.version.sha256?.takeIf(String::isRemoteDigest) ?: return@synchronized null
            val file = File(directory, "$hash.data")
            if (file in evictAfterClose || !file.hasSize(info.version.length)) return@synchronized null
            file.setLastModified(System.currentTimeMillis())
            recentFiles[kind] = file
            RemoteFileLease(file, info, pin(file))
        }

    fun cache(kind: RemoteFileKind, location: RemoteLocation, part: File, info: RemoteFileInfo): RemoteFileLease =
        cache(kind, location.id, part, info)

    private fun cache(kind: RemoteFileKind, key: String, part: File, info: RemoteFileInfo): RemoteFileLease =
        synchronized(guard) {
            val hash = info.version.sha256?.takeIf(String::isRemoteDigest) ?: error("Remote file hash missing")
            val directory = cacheDirectory(kind, key).requireDirectory()
            val file = File(directory, "$hash.data")
            if (!file.hasSize(info.version.length)) {
                val previousSize = file.length()
                val usedBytes = storedBytes(kind)
                moveRemoteAtomic(part, file)
                cachedSizes[kind] = (usedBytes - previousSize + file.length()).coerceAtLeast(0L)
            }
            writeRemoteAtomic(File(directory, CURRENT)) { output -> output.writeRemoteInfo(info) }
            evictAfterClose.remove(file)
            file.setLastModified(System.currentTimeMillis())
            recentFiles[kind] = file
            val lease = RemoteFileLease(file, info, pin(file))
            evictOrdinary(kind)
            lease
        }

    fun clearCache() = synchronized(guard) {
        archivePageGeneration++
        for (kind in RemoteFileKind.entries) {
            for (directory in cacheDirectories(kind)) {
                File(directory, CURRENT).delete()
                directory.listFiles().orEmpty().filter { it.name.endsWith(".data") }.forEach(::evict)
                directory.delete()
            }
        }
        cachedSizes.clear()
        recentFiles.clear()
    }

    fun createOfflinePart(): File = Files.createTempDirectory(archiveParts.requireDirectory().toPath(), "save-").toFile()

    fun publishOffline(part: File, chapter: LocalChapter, archive: RemoteFileInfo?) = synchronized(guard) {
        writeRemoteAtomic(File(part, OFFLINE_INFO)) { output ->
            output.writeUTF(chapter.id)
            output.writeUTF(chapter.name)
            output.writeUTF(chapter.sourceVersion.orEmpty())
            output.writeBoolean(archive != null)
            if (archive != null) output.writeRemoteInfo(archive)
            output.writeInt(chapter.pages.size)
            for (page in chapter.pages) {
                output.writeUTF(page.id)
                output.writeUTF(page.name)
                output.writeInt(page.width)
                output.writeInt(page.height)
                output.writeBoolean(page.isBaselineJpeg)
                output.writeUTF(page.archiveVersion.orEmpty())
                output.writeUTF(page.remoteVersion.orEmpty())
            }
        }
        if (checkedOffline(part) == null) throw IOException("离线章节保存不完整，请重试。")

        val chapterDirectory = offlineDirectory(chapter.id).requireDirectory()
        val renamed = File(chapterDirectory, "snapshot-${UUID.randomUUID()}")
        moveRemoteAtomic(part, renamed)
        try {
            writeRemoteAtomic(File(chapterDirectory, CURRENT)) { output -> output.writeUTF(renamed.name) }
        } catch (failure: Throwable) {
            renamed.deleteRecursively()
            throw failure
        }
        removeOfflineVersions(chapterDirectory, keep = renamed)
    }

    fun offlineChapter(chapterId: String): RemoteOfflineLease? = synchronized(guard) {
        val candidate = currentOffline(offlineDirectory(chapterId)) ?: return@synchronized null
        if (candidate.chapter.id != chapterId) return@synchronized null
        RemoteOfflineLease(candidate.directory, candidate.chapter, candidate.archive, pin(candidate.directory))
    }

    fun hasOffline(chapterId: String): Boolean = synchronized(guard) {
        currentOffline(offlineDirectory(chapterId))?.chapter?.id == chapterId
    }

    fun offlineCover(page: ReaderPage): RemoteOfflineLease? = synchronized(guard) {
        val requested = RemoteVersion.decode(page.remoteVersion)
        val archive = ArchiveLocation.fromPageId(page.id)
        offline.listFiles().orEmpty().asSequence().filter { it.name.isRemoteDigest() }
            .mapNotNull(::currentOffline)
            .firstOrNull { candidate ->
                if (archive != null && candidate.archive != null) {
                    val savedArchive = ArchiveLocation.fromChapterId(candidate.chapter.id)
                    return@firstOrNull savedArchive?.sourceUri == archive.sourceUri &&
                        (requested == null || candidate.archive.matches(requested))
                }
                val entry = candidate.chapter.pages.find { it.id == page.id } ?: return@firstOrNull false
                val actual = RemoteVersion.decode(entry.remoteVersion)
                requested == null || actual != null && requested.matches(actual)
            }?.let { candidate ->
                RemoteOfflineLease(candidate.directory, candidate.chapter, candidate.archive, pin(candidate.directory))
            }
    }

    fun deleteOffline(chapterId: String) = synchronized(guard) {
        val directory = offlineDirectory(chapterId)
        Files.deleteIfExists(File(directory, CURRENT).toPath())
        removeOfflineVersions(directory)
        directory.delete()
        Unit
    }

    private fun currentOffline(chapterDirectory: File): OfflineRecord? {
        val directory = currentOfflineDirectory(chapterDirectory) ?: return null
        return checkedOffline(directory)
    }

    private fun currentOfflineDirectory(chapterDirectory: File): File? {
        val pointer = readRecord(File(chapterDirectory, CURRENT), DataInputStream::readUTF)
        if (pointer == null || !snapshotName.matches(pointer)) return null
        return File(chapterDirectory, pointer)
    }

    private fun checkedOffline(directory: File): OfflineRecord? = readRecord(File(directory, OFFLINE_INFO)) { input ->
        val id = input.readUTF()
        val name = input.readUTF()
        val sourceVersion = input.readUTF().ifEmpty { null }
        val archive = if (input.readBoolean()) input.readRemoteInfo() else null
        val pageCount = input.readInt()
        if (pageCount !in 1..100_000) return@readRecord null
        val archiveFile = if (archive != null) File(directory, OFFLINE_ARCHIVE) else null
        if (archiveFile != null && !archiveFile.hasSize(archive?.version?.length)) return@readRecord null
        val pages = buildList {
            repeat(pageCount) { index ->
                val page = ReaderPage(
                    id = input.readUTF(),
                    name = input.readUTF(),
                    width = input.readInt(),
                    height = input.readInt(),
                    isBaselineJpeg = input.readBoolean(),
                    archiveVersion = input.readUTF().ifEmpty { null },
                    remoteVersion = input.readUTF().ifEmpty { null },
                    remoteFile = archiveFile ?: File(directory, offlineImageName(index)),
                )
                val file = checkNotNull(page.remoteFile)
                if (archiveFile == null && !file.hasSize(RemoteVersion.decode(page.remoteVersion)?.length)) {
                    return@readRecord null
                }
                if (page.width <= 0 || page.height <= 0) return@readRecord null
                add(page)
            }
        }
        OfflineRecord(directory, LocalChapter(id, name, pages, sourceVersion), archive)
    }

    private fun pin(file: File): Closeable {
        pins[file] = (pins[file] ?: 0) + 1
        var released = false
        return Closeable {
            synchronized(guard) {
                if (released) return@synchronized
                released = true
                val readers = checkNotNull(pins[file])
                if (readers > 1) {
                    pins[file] = readers - 1
                } else {
                    pins.remove(file)
                    if (evictAfterClose.remove(file)) evict(file)
                    cacheKind(file)?.let(::evictOrdinary)
                }
            }
        }
    }

    private fun evict(file: File) {
        if (file in pins) {
            evictAfterClose.add(file)
        } else if (file.isDirectory) {
            file.deleteRecursively()
        } else {
            val kind = cacheKind(file)
            if (kind != null) removeCacheFile(kind, file) else file.delete()
        }
    }

    private fun evictOrdinary(kind: RemoteFileKind) {
        val limit = if (kind == RemoteFileKind.ARCHIVE) ordinaryLimit else ordinaryLimit / 8
        if (storedBytes(kind) <= limit) return
        val files = cacheFiles(kind)
        cachedSizes[kind] = files.sumOf(File::length)
        val newest = recentFiles[kind]?.takeIf(File::isFile) ?: files.maxByOrNull(File::lastModified)
        // Keep one recent oversized download. Opening a newly indexed archive must not download it again.
        val targetBytes = maxOf(limit, newest?.length() ?: 0L)
        for (file in files.sortedBy(File::lastModified)) {
            if (storedBytes(kind) <= targetBytes) break
            if (file != newest && file !in pins) removeCacheFile(kind, file)
        }
    }

    private fun removeCacheFile(kind: RemoteFileKind, file: File) {
        val used = storedBytes(kind)
        val size = file.length()
        if (!file.delete()) return
        cachedSizes[kind] = (used - size).coerceAtLeast(0L)
        val parent = checkNotNull(file.parentFile)
        if (parent.listFiles().orEmpty().none { it.name.endsWith(".data") }) {
            File(parent, CURRENT).delete()
            parent.delete()
        }
    }

    private fun storageFile(file: File): Boolean = file.isFile && file.name.endsWith(".data")

    private fun cacheKind(file: File): RemoteFileKind? {
        val root = file.parentFile?.parentFile ?: return null
        if (root.parentFile != ordinary) return null
        return RemoteFileKind.entries.firstOrNull { it.directoryName == root.name }
    }

    private fun storedBytes(kind: RemoteFileKind): Long = cachedSizes.getOrPut(kind) { cacheFiles(kind).sumOf(File::length) }

    private fun cacheDirectory(kind: RemoteFileKind, key: String): File =
        File(File(ordinary, kind.directoryName), remoteDigest(key))

    private fun cacheDirectories(kind: RemoteFileKind): List<File> =
        File(ordinary, kind.directoryName).listFiles().orEmpty().filter(File::isDirectory)

    private fun cacheFiles(kind: RemoteFileKind): List<File> = cacheDirectories(kind).flatMap { directory ->
        directory.listFiles().orEmpty().filter(::storageFile)
    }

    private fun offlineDirectory(id: String): File = File(offline, remoteDigest(id))

    private fun removeOfflineVersions(directory: File, keep: File? = null) {
        directory.listFiles().orEmpty().filter { it != keep && snapshotName.matches(it.name) }.forEach(::evict)
    }

    private data class OfflineRecord(val directory: File, val chapter: LocalChapter, val archive: RemoteFileInfo?)

    companion object {
        private const val CURRENT = "latest"
        private const val OFFLINE_INFO = "chapter"
        const val OFFLINE_ARCHIVE = "archive.zip"
        private val snapshotName = Regex("snapshot-[0-9a-f-]{36}")

        fun offlineImageName(index: Int): String = "page-$index.image"
    }
}

private fun DataOutputStream.writeRemoteInfo(info: RemoteFileInfo) {
    writeUTF(info.version.encode())
    writeUTF(info.effectiveUrlKey)
}

private fun DataInputStream.readRemoteInfo(): RemoteFileInfo? {
    val version = RemoteVersion.decode(readUTF()) ?: return null
    val redirectKey = readUTF()
    if (version.sha256?.isRemoteDigest() != true || version.length == null || !redirectKey.isRemoteDigest()) return null
    return RemoteFileInfo(version, redirectKey)
}

private inline fun <T> readRecord(file: File, read: (DataInputStream) -> T?): T? {
    if (!file.isFile) return null
    return try {
        DataInputStream(file.inputStream().buffered()).use(read)
    } catch (_: IOException) {
        null
    }
}

private val remoteHash = Regex("[0-9a-f]{64}")

internal fun String.isRemoteDigest(): Boolean = remoteHash.matches(this)

private fun File.hasSize(size: Long?): Boolean = size != null && size > 0 && isFile && length() == size

private fun File.requireDirectory(): File {
    if (!isDirectory && !mkdirs() && !isDirectory) throw IOException("无法创建远程内容的存储目录。")
    return this
}

internal fun writeRemoteAtomic(destination: File, write: (DataOutputStream) -> Unit) {
    val temporary = Files.createTempFile(checkNotNull(destination.parentFile).requireDirectory().toPath(), "record-", ".part")
        .toFile()
    try {
        temporary.outputStream().use { stream ->
            DataOutputStream(stream.buffered()).use { output ->
                write(output)
                output.flush()
                stream.fd.sync()
            }
        }
        moveRemoteAtomic(temporary, destination)
    } finally {
        temporary.delete()
    }
}

private fun moveRemoteAtomic(from: File, to: File) {
    Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
}
