package io.github.kai.reader

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.ClosedChannelException
import java.nio.channels.NonWritableChannelException
import java.nio.channels.SeekableByteChannel
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Inflater
import java.util.zip.ZipEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class RemoteRangeArchives(private val transport: RemoteTransport) {
    private val guard = Any()
    private val indexes = LinkedHashMap<IndexKey, RemoteRangeIndex>(8, 0.75f, true)
    private val unsupported = LinkedHashSet<IndexKey>()
    private val indexLocks = Array(16) { Mutex() }
    private var cacheGeneration = 0L

    fun clearIndexes() = synchronized(guard) {
        cacheGeneration++
        indexes.clear()
        unsupported.clear()
    }

    suspend fun open(location: RemoteLocation, account: RemoteAccount?): RemoteRangeIndex? =
        try {
            index(location, account, expected = null)
        } catch (_: RemoteRangeUnsupported) {
            null
        }

    suspend fun extract(
        location: RemoteLocation,
        account: RemoteAccount?,
        expected: RemoteVersion,
        path: String,
        entryVersion: String?,
        target: File,
        onProgress: (RemoteDownloadProgress) -> Unit,
    ): RemoteFileInfo {
        try {
            val index = index(location, account, expected)
            val entry = index.entries[path] ?: throw RemoteContentChanged()
            if (entryVersion != null && entryVersion != entry.version) throw RemoteContentChanged()
            return extractEntry(index, account, entry, target, onProgress)
        } catch (_: RemoteRangeUnsupported) {
            // 有些服务只支持后缀请求。当前页表已固定，下一次打开时才走整包读取。
            rememberUnsupported(IndexKey(location.id, expected))
            throw RemoteContentChanged()
        }
    }

    private suspend fun index(
        location: RemoteLocation,
        account: RemoteAccount?,
        expected: RemoteVersion?,
    ): RemoteRangeIndex = indexLocks[Math.floorMod(location.id.hashCode(), indexLocks.size)].withLock {
        val generation = synchronized(guard) { cacheGeneration }
        if (expected != null) cachedIndex(IndexKey(location.id, expected))?.let { return@withLock it }

        // 每次打开重新探测当前版本；已有页只能按其版本重建被淘汰的索引。
        val tail = transport.archiveTail(location, account, ZIP_TAIL_LIMIT, expected)
        val key = IndexKey(location.id, tail.version)
        cachedIndex(key)?.let { return@withLock it }

        val directory = readDirectoryLayout(tail, account)
        val missing = (tail.start - directory.start).coerceAtLeast(0L).toInt()
        val prefix = if (missing == 0) ByteArray(0) else {
            transport.archiveRange(tail.location, account, directory.start, missing, tail.version) {
                it.readRangeBytes(missing)
            }
        }
        val entries = withContext(Dispatchers.Default) {
            val context = currentCoroutineContext()
            DirectoryChannel(directory, tail, prefix).use { channel ->
                channel.validateEntries(directory, context::ensureActive)
                parseCentralDirectory(channel, directory, context::ensureActive)
            }
        }
        val built = RemoteRangeIndex(tail.location, tail.version, directory.start, entries)
        synchronized(guard) {
            if (generation == cacheGeneration) {
                indexes[key] = built
                if (indexes.size > 4) indexes.remove(indexes.keys.first())
            }
        }
        built
    }

    private fun cachedIndex(key: IndexKey): RemoteRangeIndex? = synchronized(guard) {
        if (key in unsupported) throw RemoteRangeUnsupported()
        indexes[key]
    }

    private fun rememberUnsupported(key: IndexKey) = synchronized(guard) {
        indexes.remove(key)
        unsupported.add(key)
        if (unsupported.size > 16) unsupported.remove(unsupported.first())
    }

    private suspend fun readDirectoryLayout(tail: RemoteArchiveTail, account: RemoteAccount?): DirectoryLayout {
        val buffer = tail.bytes.zipBuffer()
        val end = findDirectoryEnd(buffer)
        val endOffset = tail.start + end
        val archiveEnd = endOffset + ZIP_END_SIZE
        val layout = if (end >= ZIP64_LOCATOR_SIZE && buffer.getInt(end - ZIP64_LOCATOR_SIZE) == ZIP64_LOCATOR_SIGNATURE) {
            val locator = end - ZIP64_LOCATOR_SIZE
            val zip64Offset = buffer.getLong(locator + 8)
            val locatorOffset = tail.start + locator
            val isSingleDisk = buffer.getInt(locator + 4) == 0 && buffer.getInt(locator + 16) == 1
            if (!isSingleDisk || zip64Offset !in 0..locatorOffset - ZIP64_END_SIZE) throw RemoteRangeUnsupported()
            val header = if (zip64Offset >= tail.start) {
                val localStart = (zip64Offset - tail.start).toInt()
                tail.bytes.copyOfRange(localStart, localStart + ZIP64_END_SIZE)
            } else transport.archiveRange(tail.location, account, zip64Offset, ZIP64_END_SIZE, tail.version) {
                it.readRangeBytes(ZIP64_END_SIZE)
            }
            zip64Directory(header.zipBuffer(), zip64Offset, locatorOffset, archiveEnd)
        } else zip32Directory(buffer, end, endOffset, archiveEnd)

        val length = layout.end - layout.start
        val hasRoomForEntries = layout.entryCount.toLong() * CENTRAL_HEADER_SIZE <= length
        val fitsDirectoryBudget = length in 0..DIRECTORY_LIMIT && archiveEnd - layout.start <= DIRECTORY_LIMIT + ZIP_TAIL_LIMIT
        if (!hasRoomForEntries || !fitsDirectoryBudget) throw RemoteRangeUnsupported()
        return layout
    }

    private suspend fun extractEntry(
        index: RemoteRangeIndex,
        account: RemoteAccount?,
        entry: ZipRangeEntry,
        target: File,
        onProgress: (RemoteDownloadProgress) -> Unit,
    ): RemoteFileInfo {
        val context = currentCoroutineContext()
        val header = transport.archiveRange(index.location, account, entry.offset, LOCAL_HEADER_SIZE, index.version) {
            it.readRangeBytes(LOCAL_HEADER_SIZE).zipBuffer()
        }
        val headerMatches = header.getInt(0) == LOCAL_SIGNATURE && header.u16(6) == entry.flags &&
            header.u16(8) == entry.method && header.u16(26) == entry.name.size
        if (!headerMatches) throw invalidEntry()
        val hasCentralSizes = entry.flags and DATA_DESCRIPTOR_FLAG != 0
        if (!hasCentralSizes) {
            val localCompressed = header.u32(18)
            val localExpanded = header.u32(22)
            val sizesMatch = (localCompressed == ZIP32_LIMIT || localCompressed == entry.compressedSize.toLong()) &&
                (localExpanded == ZIP32_LIMIT || localExpanded == entry.expandedSize)
            if (header.u32(14) != entry.crc || !sizesMatch) throw invalidEntry()
        }

        val extraLength = header.u16(28)
        val dataStart = entry.offset + LOCAL_HEADER_SIZE
        val transferLength = entry.name.size + extraLength + entry.compressedSize
        if (transferLength > index.directoryStart - dataStart) throw invalidEntry()
        val directory = checkNotNull(target.parentFile)
        if (entry.expandedSize > directory.usableSpace) throw IOException("可用空间不足，无法暂存远程漫画页。")

        return try {
            val version = transport.archiveRange(
                index.location, account, dataStart, transferLength, index.version, discard = { target.delete() },
            ) { input ->
                if (!input.readRangeBytes(entry.name.size).contentEquals(entry.name)) throw invalidEntry()
                input.readRangeBytes(extraLength)
                val checks = EntryChecks(entry, onProgress, context::ensureActive)
                target.outputStream().buffered().use { output ->
                    checks.start()
                    when (entry.method) {
                        ZipEntry.STORED -> checks.copyStored(input, output)
                        ZipEntry.DEFLATED -> checks.copyDeflated(input, output)
                        else -> error("Unexpected ZIP compression method")
                    }
                }
                checks.complete()
            }
            RemoteFileInfo(version, checkNotNull(index.version.rangeUrlKey))
        } catch (failure: IOException) {
            if (failure.isRemoteDiskFull(directory)) throw IOException("可用空间不足，无法暂存远程漫画页。", failure)
            throw failure
        }
    }

    private data class IndexKey(val locationId: String, val version: RemoteVersion)
}

internal class RemoteRangeIndex(
    val location: RemoteLocation,
    val version: RemoteVersion,
    val directoryStart: Long,
    val entries: Map<String, ZipRangeEntry>,
) {
    fun catalog(sourceId: String, title: String, splitByFolder: Boolean): ArchiveCatalog {
        val raw = catalogArchive(sourceId, title, entries.keys.toList(), splitByFolder)
        val sourceVersion = version.encode()
        fun versioned(page: ReaderPage): ReaderPage {
            val path = checkNotNull(ArchiveLocation.fromPageId(page.id)).path
            return page.copy(archiveVersion = entries.getValue(path).version, remoteVersion = sourceVersion)
        }
        return raw.copy(
            cover = raw.cover?.let(::versioned),
            chapters = raw.chapters.map { chapter ->
                chapter.copy(pages = chapter.pages.map(::versioned), sourceVersion = sourceVersion)
            },
        )
    }
}

internal data class ZipRangeEntry(
    val name: ByteArray,
    val offset: Long,
    val flags: Int,
    val method: Int,
    val compressedSize: Int,
    val expandedSize: Long,
    val crc: Long,
) {
    val version: String get() = "$expandedSize:$crc"
}

private data class DirectoryLayout(val start: Long, val end: Long, val entryCount: Int, val archiveEnd: Long)

private fun findDirectoryEnd(tail: ByteBuffer): Int {
    for (offset in tail.limit() - ZIP_END_SIZE downTo 0) {
        if (tail.getInt(offset) == END_SIGNATURE && offset + ZIP_END_SIZE + tail.u16(offset + 20) == tail.limit()) {
            return offset
        }
    }
    throw RemoteRangeUnsupported()
}

private fun zip32Directory(tail: ByteBuffer, position: Int, absolute: Long, archiveEnd: Long): DirectoryLayout {
    val perDisk = tail.u16(position + 8)
    val entries = tail.u16(position + 10)
    val diskNumber = tail.u16(position + 4)
    val directoryDisk = tail.u16(position + 6)
    val size = tail.u32(position + 12)
    val relativeStart = tail.u32(position + 16)
    val start = absolute - size
    val isSingleDisk = diskNumber == 0 && directoryDisk == 0 && perDisk == entries
    val hasZip64Sentinel = size == ZIP32_LIMIT || relativeStart == ZIP32_LIMIT || entries == 0xffff
    if (!isSingleDisk || hasZip64Sentinel || relativeStart > start) throw RemoteRangeUnsupported()
    // Classic ZIP may be preceded by a launcher. Commons Compress applies the same offset correction.
    return DirectoryLayout(start, absolute, checkedEntryCount(entries.toLong()), archiveEnd)
}

private fun zip64Directory(header: ByteBuffer, position: Long, locator: Long, archiveEnd: Long): DirectoryLayout {
    val size = header.getLong(4)
    val entries = header.getLong(32)
    val directorySize = header.getLong(40)
    val start = header.getLong(48)
    val isSingleDisk = header.getInt(16) == 0 && header.getInt(20) == 0 && header.getLong(24) == entries
    val hasValidSize = size >= ZIP64_END_SIZE - 12 && size == locator - position - 12
    val isContiguous = start >= 0 && directorySize >= 0 && start == position - directorySize
    if (header.getInt(0) != ZIP64_END_SIGNATURE || !isSingleDisk || !hasValidSize || !isContiguous) {
        throw RemoteRangeUnsupported()
    }
    return DirectoryLayout(start, position, checkedEntryCount(entries), archiveEnd)
}

private fun checkedEntryCount(count: Long): Int {
    if (count !in 1..ENTRY_LIMIT) throw RemoteRangeUnsupported()
    return count.toInt()
}

private fun parseCentralDirectory(
    channel: SeekableByteChannel,
    directory: DirectoryLayout,
    checkCancellation: () -> Unit,
): Map<String, ZipRangeEntry> = try {
    openCentralDirectory(channel).use { archive ->
        buildMap {
            for (entry in archive.entries.asSequence()) {
                checkCancellation()
                if (entry.isDirectory || entry.isUnixSymlink || !archive.canReadEntryData(entry) || !isImageFile(entry.name)) continue
                if (containsKey(entry.name)) continue

                val offset = entry.localHeaderOffset
                val compressed = entry.compressedSize
                val expanded = entry.size
                val isStandardCompression = entry.method == ZipEntry.STORED || entry.method == ZipEntry.DEFLATED
                val isValidStoredSize = entry.method != ZipEntry.STORED || compressed == expanded
                val fitsPageBudget = compressed in 1..PAGE_LIMIT && expanded in 1..PAGE_LIMIT
                val fitsBeforeDirectory = offset in 0..directory.start - LOCAL_HEADER_SIZE &&
                    compressed <= directory.start - offset - LOCAL_HEADER_SIZE
                if (entry.diskNumberStart != 0L || !isStandardCompression || !isValidStoredSize ||
                    !fitsPageBudget || !fitsBeforeDirectory
                ) throw RemoteRangeUnsupported()
                put(entry.name, ZipRangeEntry(
                    entry.rawName, offset, entry.rawFlag, entry.method, compressed.toInt(), expanded, entry.crc,
                ))
            }
        }
    }
} catch (_: IOException) {
    throw RemoteRangeUnsupported()
} catch (_: IllegalArgumentException) {
    throw RemoteRangeUnsupported()
}

private class DirectoryChannel(
    private val layout: DirectoryLayout,
    private val tail: RemoteArchiveTail,
    private val prefix: ByteArray,
) : SeekableByteChannel {
    private var open = true
    private var offset = 0L

    fun validateEntries(layout: DirectoryLayout, checkCancellation: () -> Unit) {
        val header = ByteBuffer.allocate(CENTRAL_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        var position = layout.start
        repeat(layout.entryCount) {
            checkCancellation()
            if (layout.end - position < CENTRAL_HEADER_SIZE) throw RemoteRangeUnsupported()
            position(position)
            header.clear()
            while (header.hasRemaining()) {
                if (read(header) < 0) throw RemoteRangeUnsupported()
            }
            if (header.getInt(0) != CENTRAL_SIGNATURE) throw RemoteRangeUnsupported()
            val variableSize = header.u16(28) + header.u16(30) + header.u16(32)
            position += CENTRAL_HEADER_SIZE + variableSize
        }
        if (position != layout.end) throw RemoteRangeUnsupported()
    }

    override fun read(destination: ByteBuffer): Int {
        if (!isOpen) throw ClosedChannelException()
        if (!destination.hasRemaining()) return 0
        if (offset >= layout.archiveEnd || offset < minOf(layout.start, tail.start)) return -1

        val bytes: ByteArray
        val start: Long
        if (offset < tail.start) {
            bytes = prefix
            start = layout.start
        } else {
            bytes = tail.bytes
            start = tail.start
        }
        val index = (offset - start).toInt()
        val available = minOf(layout.archiveEnd - offset, (bytes.size - index).toLong()).toInt()
        val count = minOf(destination.remaining(), available)
        destination.put(bytes, index, count)
        offset += count
        return count
    }

    override fun position(): Long {
        if (!isOpen) throw ClosedChannelException()
        return offset
    }

    override fun position(newPosition: Long): SeekableByteChannel {
        if (!isOpen) throw ClosedChannelException()
        require(newPosition >= 0)
        offset = newPosition
        return this
    }

    override fun size(): Long = layout.archiveEnd
    override fun write(source: ByteBuffer): Int = throw NonWritableChannelException()
    override fun truncate(size: Long): SeekableByteChannel = throw NonWritableChannelException()
    override fun isOpen(): Boolean = open
    override fun close() { open = false }
}

private class EntryChecks(
    private val entry: ZipRangeEntry,
    private val onProgress: (RemoteDownloadProgress) -> Unit,
    private val checkCancellation: () -> Unit,
) {
    private val crc = CRC32()
    private val digest = MessageDigest.getInstance("SHA-256")
    private var expanded = 0L
    private var transferred = 0L
    private var lastReported = 0L

    fun start() = onProgress(RemoteDownloadProgress(0, entry.compressedSize.toLong()))

    fun copyStored(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = readChunk(input, buffer)
            if (count < 0) break
            writeChunk(output, buffer, count)
        }
    }

    fun copyDeflated(input: InputStream, output: OutputStream) {
        val inflater = Inflater(true)
        val compressed = ByteArray(64 * 1024)
        val image = ByteArray(64 * 1024)
        try {
            while (!inflater.finished()) {
                checkCancellation()
                if (inflater.needsInput()) {
                    val count = readChunk(input, compressed)
                    if (count < 0) throw invalidEntry()
                    inflater.setInput(compressed, 0, count)
                }
                val count = inflater.inflate(image)
                if (count > 0) writeChunk(output, image, count)
                else if (!inflater.needsInput() && !inflater.finished()) throw invalidEntry()
            }
            if (inflater.bytesRead != entry.compressedSize.toLong()) throw invalidEntry()
        } catch (failure: DataFormatException) {
            throw invalidEntry(failure)
        } finally {
            inflater.end()
        }
    }

    fun complete(): RemoteVersion {
        if (expanded != entry.expandedSize || crc.value != entry.crc) throw invalidEntry()
        onProgress(RemoteDownloadProgress(transferred, entry.compressedSize.toLong()))
        return RemoteVersion(length = expanded, sha256 = digest.digest().remoteHex())
    }

    private fun readChunk(input: InputStream, buffer: ByteArray): Int {
        val count = input.read(buffer)
        if (count > 0) {
            transferred += count
            if (transferred - lastReported >= 256 * 1024) {
                onProgress(RemoteDownloadProgress(transferred, entry.compressedSize.toLong()))
                lastReported = transferred
            }
        }
        return count
    }

    private fun writeChunk(output: OutputStream, buffer: ByteArray, count: Int) {
        expanded += count
        if (expanded > entry.expandedSize) throw invalidEntry()
        crc.update(buffer, 0, count)
        digest.update(buffer, 0, count)
        output.write(buffer, 0, count)
    }
}

private fun ByteArray.zipBuffer(): ByteBuffer = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN)
private fun ByteBuffer.u16(offset: Int): Int = getShort(offset).toInt() and 0xffff
private fun ByteBuffer.u32(offset: Int): Long = getInt(offset).toLong() and ZIP32_LIMIT
private fun invalidEntry(cause: Throwable? = null) = IOException("压缩包中的图片条目不完整或格式无效。", cause)

private const val ZIP_END_SIZE = 22
private const val ZIP64_LOCATOR_SIZE = 20
private const val ZIP64_END_SIZE = 56
private const val ZIP_TAIL_LIMIT = 65535 + ZIP_END_SIZE + ZIP64_LOCATOR_SIZE + ZIP64_END_SIZE
private const val CENTRAL_HEADER_SIZE = 46
private const val LOCAL_HEADER_SIZE = 30
private const val END_SIGNATURE = 0x06054b50
private const val ZIP64_LOCATOR_SIGNATURE = 0x07064b50
private const val ZIP64_END_SIGNATURE = 0x06064b50
private const val CENTRAL_SIGNATURE = 0x02014b50
private const val LOCAL_SIGNATURE = 0x04034b50
private const val ZIP32_LIMIT = 0xffffffffL
private const val DATA_DESCRIPTOR_FLAG = 8
private const val DIRECTORY_LIMIT = 8L * 1024 * 1024
private const val ENTRY_LIMIT = 20_000L
private const val PAGE_LIMIT = 128L * 1024 * 1024
