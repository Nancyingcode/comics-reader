package io.github.kai.reader

import android.content.ContentResolver
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.NonWritableChannelException
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import java.util.zip.CRC32
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile

internal class LocalArchives(private val resolver: ContentResolver, cacheDir: File) {
    private val packages = ArchiveFileCache(File(cacheDir, "reader-archives"), 1024 * MEBIBYTE, 4, packageLock)
    private val images = ArchiveFileCache(File(cacheDir, "reader-archive-images"), 128 * MEBIBYTE, 12, imageLock)

    fun clearImageCache(signal: CancellationSignal) = images.clear(signal)

    fun catalog(uri: Uri, title: String, splitByFolder: Boolean, signal: CancellationSignal): ArchiveCatalog =
        useZip(uri, signal, refreshSource = true) { zip ->
            zip.catalog(uri.toString(), title, splitByFolder, signal)
        }

    fun catalogFile(file: File, source: String, title: String, splitByFolder: Boolean, signal: CancellationSignal): ArchiveCatalog =
        useFileZip(file, signal) { zip -> zip.catalog(source, title, splitByFolder, signal) }

    fun prepare(uri: Uri, pages: List<ReaderPage>, signal: CancellationSignal): List<ReaderPage> =
        useZip(uri, signal) { zip -> preparePages(zip, pages, signal) }

    fun prepareFile(file: File, pages: List<ReaderPage>, signal: CancellationSignal): List<ReaderPage> =
        useFileZip(file, signal) { zip -> preparePages(zip, pages, signal) }

    fun readHeader(location: ArchiveLocation, page: ReaderPage, signal: CancellationSignal): ReaderPage? =
        usePageZip(location, page, signal) { zip -> readHeader(zip, location, page, signal) }

    fun <T> readImage(
        location: ArchiveLocation,
        page: ReaderPage,
        signal: CancellationSignal,
        read: (InputStream) -> T,
    ): T = usePageZip(location, page, signal) { zip ->
        zip.readEntry(zip.pageEntry(location, page), signal, read)
    }

    fun openImage(location: ArchiveLocation, page: ReaderPage, signal: CancellationSignal): ParcelFileDescriptor {
        val version = checkNotNull(page.archiveVersion)
        val localKey = "${page.id}:$version"
        val key = page.remoteVersion?.let { "$localKey:$it" } ?: localKey
        return images.open(key, signal, write = { output ->
            usePageZip(location, page, signal) { zip ->
                val entry = zip.pageEntry(location, page)
                zip.readEntry(entry, signal) { input ->
                    copyComplete(input, output, signal, expectedSize = entry.size, expectedCrc = entry.crc)
                }
            }
        }, read = { file -> ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY) })
    }

    private fun preparePages(zip: ZipFile, pages: List<ReaderPage>, signal: CancellationSignal): List<ReaderPage> =
        pages.mapNotNull { page ->
            val location = checkNotNull(ArchiveLocation.fromPageId(page.id))
            signal.throwIfCanceled()
            readHeader(zip, location, page, signal)
        }

    private fun readHeader(
        zip: ZipFile,
        location: ArchiveLocation,
        page: ReaderPage,
        signal: CancellationSignal,
    ): ReaderPage? {
        val entry = zip.pageEntry(location, page)
        val bounds = zip.readEntry(entry, signal) { input -> readImageBounds(input.buffered()) } ?: return null
        return page.copy(
            width = bounds.width,
            height = bounds.height,
            isBaselineJpeg = bounds.isBaselineJpeg,
            archiveVersion = entry.imageVersion(),
        )
    }

    private fun <T> useZip(
        uri: Uri,
        signal: CancellationSignal,
        refreshSource: Boolean = false,
        read: (ZipFile) -> T,
    ): T {
        val channel = openSource(uri, signal, refreshSource)
        return channel.use { openCentralDirectory(it).use(read) }
    }

    private fun <T> usePageZip(
        location: ArchiveLocation,
        page: ReaderPage,
        signal: CancellationSignal,
        read: (ZipFile) -> T,
    ): T = page.remoteFile?.let { useFileZip(it, signal, read) }
        ?: useZip(Uri.parse(location.sourceUri), signal, read = read)

    private fun openSource(uri: Uri, signal: CancellationSignal, refreshSource: Boolean): SeekableByteChannel {
        val revision = sourceRevision(uri, signal)
        val key = "$uri\n${revision?.let { "${it.size}:${it.modified}" } ?: processRevision}"
        val openCachedChannel = { file: File ->
            ArchiveSliceChannel(file.inputStream(), 0L, file.length(), signal::throwIfCanceled)
        }
        if (!refreshSource) {
            // 已暂存的管道无需让提供方为每个条目重新开流。查询版本时仍由 SAF 校验读权限。
            packages.openIfPresent(key, signal, openCachedChannel)?.let { return it }
        }

        val asset = resolver.openAssetFileDescriptor(uri, "r", signal) ?: throw IOException("无法打开压缩包")
        val stream = ParcelFileDescriptor.AutoCloseInputStream(asset.parcelFileDescriptor)
        var ownsStream = true
        try {
            val length = seekableLength(asset, stream)
            if (length != null) {
                ownsStream = false
                return ArchiveSliceChannel(stream, asset.startOffset, length, signal::throwIfCanceled)
            }

            // 缺少可靠版本的来源只在本进程复用。手动重导会重新获取原包，以识别同路径换包。
            return packages.open(key, signal, refreshSource, write = { output ->
                asset.createInputStream().use { input ->
                    input.cancelWith(signal) {
                        val expectedSize = asset.declaredLength.takeIf { it >= 0 } ?: revision?.size
                        copyComplete(input, output, signal, expectedSize = expectedSize)
                    }
                }
            }, read = openCachedChannel)
        } finally {
            if (ownsStream) stream.close()
        }
    }

    private fun sourceRevision(uri: Uri, signal: CancellationSignal): SourceRevision? {
        val columns = arrayOf(Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED)
        return try {
            resolver.query(uri, columns, null, null, null, signal)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val sizeIndex = cursor.getColumnIndex(Document.COLUMN_SIZE)
                val modifiedIndex = cursor.getColumnIndex(Document.COLUMN_LAST_MODIFIED)
                val hasVersion = sizeIndex >= 0 && modifiedIndex >= 0
                if (!hasVersion) return@use null

                val size = cursor.getLong(sizeIndex)
                val modified = cursor.getLong(modifiedIndex)
                if (size > 0 && modified > 0) SourceRevision(size, modified) else null
            }
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: UnsupportedOperationException) {
            null
        }
    }

    private fun seekableLength(asset: AssetFileDescriptor, stream: FileInputStream): Long? {
        val channel = stream.channel
        return try {
            // 存储提供方可能返回管道或容器中的文件切片，statSize 不能单独证明可随机读取。
            val length = asset.declaredLength.takeIf { it >= 0 } ?: (channel.size() - asset.startOffset)
            if (length <= 0 || length > Long.MAX_VALUE - asset.startOffset) return null
            channel.position(asset.startOffset)
            length
        } catch (_: IOException) {
            null
        } catch (_: UnsupportedOperationException) {
            null
        }
    }

    private data class SourceRevision(val size: Long, val modified: Long)

    private companion object {
        const val MEBIBYTE = 1024L * 1024
        val processRevision: String = UUID.randomUUID().toString()
        val packageLock = ReentrantLock()
        val imageLock = ReentrantLock()
    }
}

private fun <T> useFileZip(file: File, signal: CancellationSignal, read: (ZipFile) -> T): T {
    signal.throwIfCanceled()
    return file.inputStream().use { stream ->
        ArchiveSliceChannel(stream, 0L, stream.channel.size(), signal::throwIfCanceled).use { channel ->
            openCentralDirectory(channel).use(read)
        }
    }
}

private fun ZipFile.catalog(source: String, title: String, splitByFolder: Boolean, signal: CancellationSignal): ArchiveCatalog {
    val pictureEntries = buildList {
        for (entry in entries.asSequence()) {
            signal.throwIfCanceled()
            if (isReadableFile(entry)) add(entry.name)
        }
    }
    return catalogArchive(source, title, pictureEntries, splitByFolder)
}

internal fun validateDownloadedArchive(file: File, signal: CancellationSignal) = useFileZip(file, signal) { zip ->
    for (entry in zip.entries.asSequence()) {
        signal.throwIfCanceled()
        if (!zip.isReadableFile(entry)) continue
        if (entry.size < 0 || entry.crc < 0) throw IOException("压缩包条目没有有效的校验信息。")
        zip.readEntry(entry, signal) { input ->
            copyComplete(input, null, signal, expectedSize = entry.size, expectedCrc = entry.crc)
        }
    }
}

internal fun openCentralDirectory(channel: SeekableByteChannel): ZipFile = ZipFile.builder()
    .setSeekableByteChannel(channel)
    .setIgnoreLocalFileHeader(true)
    .get()

private fun ZipFile.isReadableFile(entry: ZipArchiveEntry): Boolean =
    !entry.isDirectory && !entry.isUnixSymlink && canReadEntryData(entry)

private fun ZipFile.pageEntry(location: ArchiveLocation, page: ReaderPage): ZipArchiveEntry {
    val entry = getEntries(location.path).firstOrNull(::isReadableFile)
        ?: throw IOException("压缩包中的“${page.name}”已移动或不可读取，请重新导入")
    val expectedVersion = page.archiveVersion
    if (expectedVersion != null && entry.imageVersion() != expectedVersion) {
        throw IOException("压缩包中的“${page.name}”已更改，请重新打开章节")
    }
    return entry
}

private fun ZipArchiveEntry.imageVersion(): String = "$size:$crc"

private fun <T> ZipFile.readEntry(entry: ZipArchiveEntry, signal: CancellationSignal, read: (InputStream) -> T): T {
    signal.throwIfCanceled()
    val input = getInputStream(entry) ?: throw IOException("无法打开压缩包中的“${entry.name}”")
    return input.use(read)
}

private fun <T> InputStream.cancelWith(signal: CancellationSignal, read: () -> T): T {
    signal.setOnCancelListener { runCatching { close() } }
    try {
        signal.throwIfCanceled()
        return read()
    } finally {
        signal.setOnCancelListener(null)
    }
}

private fun copyComplete(
    input: InputStream,
    output: OutputStream?,
    signal: CancellationSignal,
    expectedSize: Long? = null,
    expectedCrc: Long? = null,
) {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    val crc = expectedCrc?.let { CRC32() }
    var bytes = 0L
    while (true) {
        signal.throwIfCanceled()
        val read = input.read(buffer)
        if (read < 0) break
        bytes += read
        if (expectedSize != null && bytes > expectedSize) throw IOException("暂存的数据长度超出预期")
        crc?.update(buffer, 0, read)
        output?.write(buffer, 0, read)
    }
    val sizeIsWrong = expectedSize != null && bytes != expectedSize
    val crcIsWrong = expectedCrc != null && crc?.value != expectedCrc
    if (sizeIsWrong || crcIsWrong) throw IOException("暂存的数据不完整")
}

private class ArchiveFileCache(
    private val directory: File,
    private val budgetBytes: Long,
    private val maxFiles: Int,
    private val lock: ReentrantLock,
) {
    fun clear(signal: CancellationSignal) = withLock(signal) {
        directory.listFiles()?.forEach(File::delete)
        Unit
    }

    fun <T : Closeable> openIfPresent(key: String, signal: CancellationSignal, read: (File) -> T): T? =
        withLock(signal) {
            val file = cacheFile(key)
            if (file.isFile) useCached(file, read) else null
        }

    fun <T : Closeable> open(
        key: String,
        signal: CancellationSignal,
        refresh: Boolean = false,
        write: (OutputStream) -> Unit,
        read: (File) -> T,
    ): T = withLock(signal) {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("无法创建压缩包缓存目录")
        val file = cacheFile(key)
        if (refresh || !file.isFile) {
            // 新文件完整后才替换缓存，取消重导时仍能使用之前完整的副本。
            directory.listFiles { child -> child.extension == "part" }?.forEach(File::delete)
            val temporary = File.createTempFile("reader-", ".part", directory)
            try {
                temporary.outputStream().use(write)
                signal.throwIfCanceled()
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } finally {
                temporary.delete()
            }
        }
        useCached(file, read)
    }

    private inline fun <T> withLock(signal: CancellationSignal, action: () -> T): T {
        while (!lock.tryLock(50, TimeUnit.MILLISECONDS)) signal.throwIfCanceled()
        try {
            signal.throwIfCanceled()
            return action()
        } finally {
            lock.unlock()
        }
    }

    private fun cacheFile(key: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        val filename = Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
        return File(directory, "$filename.cache")
    }

    private fun <T> useCached(file: File, read: (File) -> T): T {
        file.setLastModified(System.currentTimeMillis())
        prune(file)
        // 在其他并发操作淘汰文件前取得描述符，已打开的文件在 Android 上仍可正常读完。
        return read(file)
    }

    private fun prune(current: File) {
        var available = (budgetBytes - current.length()).coerceAtLeast(0)
        val olderFiles = directory.listFiles { child -> child != current && child.extension == "cache" }
            ?.sortedByDescending(File::lastModified) ?: return
        // 超大文件只能暂存当前一个，避免长图的下一条带或下一页再次复制相同来源。
        olderFiles.forEachIndexed { index, file ->
            val size = file.length()
            if (index + 1 >= maxFiles || size > available) file.delete() else available -= size
        }
    }
}

internal class ArchiveSliceChannel(
    private val stream: FileInputStream,
    private val start: Long,
    private val length: Long,
    private val checkCancellation: () -> Unit = {},
) : SeekableByteChannel {
    private var offset = 0L

    override fun read(destination: ByteBuffer): Int {
        checkCancellation()
        if (!isOpen) throw ClosedChannelException()
        if (!destination.hasRemaining()) return 0
        val remaining = length - offset
        if (remaining <= 0L) return -1

        val originalLimit = destination.limit()
        try {
            if (remaining < destination.remaining()) destination.limit(destination.position() + remaining.toInt())
            val count = stream.channel.read(destination, start + offset)
            if (count > 0) offset += count
            return count
        } finally {
            destination.limit(originalLimit)
        }
    }

    override fun position(): Long {
        if (!isOpen) throw ClosedChannelException()
        return offset
    }

    override fun position(newPosition: Long): SeekableByteChannel {
        checkCancellation()
        if (!isOpen) throw ClosedChannelException()
        require(newPosition >= 0)
        offset = newPosition
        return this
    }

    override fun size(): Long {
        if (!isOpen) throw ClosedChannelException()
        return length
    }

    override fun write(source: ByteBuffer): Int = throw NonWritableChannelException()
    override fun truncate(size: Long): SeekableByteChannel = throw NonWritableChannelException()
    override fun isOpen(): Boolean = stream.channel.isOpen
    override fun close() = stream.close()
}
