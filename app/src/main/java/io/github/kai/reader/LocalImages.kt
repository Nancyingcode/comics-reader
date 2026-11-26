package io.github.kai.reader

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine

data class ReaderPage(
    val id: String,
    val name: String,
    val width: Int,
    val height: Int,
    val isBaselineJpeg: Boolean = false,
    val archiveVersion: String? = null,
    val remoteVersion: String? = null,
    internal val remoteFile: File? = null,
    internal val isArchiveEntryFile: Boolean = false,
)

data class ReadingAnchor(val pageId: String, val fraction: Double)

data class LocalChapter(val id: String, val name: String, val pages: List<ReaderPage>, val sourceVersion: String? = null)

class LocalImages(context: Context) {
    private val resolver = context.applicationContext.contentResolver
    private val cacheDir = context.applicationContext.cacheDir
    private val archives = LocalArchives(resolver, cacheDir)
    internal val remote = RemoteLibrary(context.applicationContext, archives)

    suspend fun open(tree: Uri): LocalChapter = readOn(Dispatchers.IO) { signal ->
        val root = treeRoot(tree, signal)
        val chapter = LocalChapter(root.uri.toString(), root.name, chapterPages(children(tree, root.documentId, signal)))
        prepareChapter(chapter, signal)
    }

    suspend fun index(source: Uri, isArchive: Boolean): IndexedBook = readOn(Dispatchers.IO) { signal ->
        if (isArchive) {
            val name = documentName(source, source.lastPathSegment.orEmpty(), signal).archiveTitle()
            val catalog = archives.catalog(source, name, splitByFolder = true, signal)
            indexedBook(source, name, true, catalog.cover, catalog.chapters)
        } else {
            indexFolder(source, signal)
        }
    }

    suspend fun prepare(chapter: LocalChapter): LocalChapter = readOn(Dispatchers.IO) { signal ->
        prepareChapter(chapter, signal)
    }

    suspend fun resolveRemotePage(page: ReaderPage, onProgress: (RemoteDownloadProgress) -> Unit = {}): ReaderPage {
        if (page.width > 0 && page.height > 0) return page
        return remote.resolvePage(page, onProgress)
    }

    suspend fun decode(page: ReaderPage, top: Int, bottom: Int, targetWidth: Int): Bitmap {
        val hasDimensions = page.width > 0 && page.height > 0
        val regionIsInsidePage = top >= 0 && top < bottom && bottom <= page.height
        require(hasDimensions && targetWidth > 0) { "图片和显示尺寸必须为正数" }
        require(regionIsInsidePage) { "采样区域超出图片范围" }

        return remote.withImageFile(page) { backed ->
            readOn(regionDispatcher, Bitmap::recycle) { signal ->
                decodePage(backed, Rect(0, top, backed.width, bottom), targetWidth, signal)
            }
        }
    }

    suspend fun thumbnail(page: ReaderPage, maxWidth: Int, maxHeight: Int): Bitmap = remote.withCoverFile(page) { backed ->
        thumbnailLocal(backed, maxWidth, maxHeight)
    }

    private suspend fun thumbnailLocal(page: ReaderPage, maxWidth: Int, maxHeight: Int): Bitmap {
        require(maxWidth > 0 && maxHeight > 0)
        return readOn(regionDispatcher, Bitmap::recycle) { signal ->
            val location = ArchiveLocation.fromPageId(page.id)
            val prepared = if (location != null && !page.isArchiveEntryFile) archives.readHeader(location, page, signal)
            else readHeader(page, signal)
            if (prepared == null) throw IOException("无法读取封面“${page.name}”")

            // 长条漫封面取页面顶部；保持可读的缩略比例，同时限制原图解码的区域。
            val previewRows = (prepared.width.toLong() * maxHeight / maxWidth)
                .coerceIn(1L, prepared.height.toLong()).toInt()
            val region = Rect(0, 0, prepared.width, previewRows)
            val bitmap = decodePage(prepared, region, maxWidth, signal)
            val scale = minOf(maxWidth.toDouble() / bitmap.width, maxHeight.toDouble() / bitmap.height, 1.0)
            if (scale == 1.0) bitmap else {
                try {
                    val width = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
                    val height = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
                    Bitmap.createScaledBitmap(bitmap, width, height, true).also { resized ->
                        if (resized !== bitmap) bitmap.recycle()
                    }
                } catch (failure: Throwable) {
                    bitmap.recycle()
                    throw failure
                }
            }
        }
    }

    private fun indexFolder(tree: Uri, signal: CancellationSignal): IndexedBook {
        val root = treeRoot(tree, signal)
        val items = children(tree, root.documentId, signal)
        val cover = items.firstOrNull { it.isImage && isCoverImage(it.name) }?.page()
        val directPages = chapterPages(items)
        val chapters = if (directPages.isNotEmpty()) listOf(LocalChapter(root.uri.toString(), root.name, directPages))
        else buildList {
            for (entry in items) {
                signal.throwIfCanceled()
                if (entry.isDirectory) {
                    val pages = chapterPages(children(tree, entry.documentId, signal))
                    if (pages.isNotEmpty()) add(LocalChapter(entry.uri.toString(), entry.name, pages))
                } else if (entry.name.isComicArchive()) {
                    addAll(archives.catalog(entry.uri, entry.name.archiveTitle(), splitByFolder = false, signal).chapters)
                }
            }
        }.sortedWith { left, right ->
            val nameOrder = compareImageNames(left.name, right.name)
            if (nameOrder != 0) nameOrder else left.id.compareTo(right.id)
        }
        return indexedBook(tree, root.name, false, cover, chapters)
    }

    private fun indexedBook(
        source: Uri,
        name: String,
        isArchive: Boolean,
        cover: ReaderPage?,
        chapters: List<LocalChapter>,
    ): IndexedBook {
        val firstPage = chapters.firstOrNull()?.pages?.firstOrNull()
            ?: throw IOException("没有找到可读的 JPG、PNG 或 WebP 漫画页")
        val sourceUri = source.toString()
        return IndexedBook(sourceUri, name, sourceUri, isArchive, cover ?: firstPage, chapters)
    }

    private fun treeRoot(tree: Uri, signal: CancellationSignal): DocumentEntry {
        val id = DocumentsContract.getTreeDocumentId(tree)
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
        return DocumentEntry(id, uri, documentName(uri, id, signal), Document.MIME_TYPE_DIR)
    }

    private fun documentName(uri: Uri, documentId: String, signal: CancellationSignal): String {
        val columns = arrayOf(Document.COLUMN_DISPLAY_NAME)
        val name = resolver.query(uri, columns, null, null, null, signal)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
        return name?.takeIf(String::isNotBlank) ?: documentId.displayName("本地漫画")
    }

    private fun children(tree: Uri, folderId: String, signal: CancellationSignal): List<DocumentEntry> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, folderId)
        val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE)
        val entries = resolver.query(children, columns, null, null, null, signal)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(Document.COLUMN_DOCUMENT_ID)
            val nameColumn = cursor.getColumnIndexOrThrow(Document.COLUMN_DISPLAY_NAME)
            val mimeColumn = cursor.getColumnIndexOrThrow(Document.COLUMN_MIME_TYPE)
            buildList<DocumentEntry> {
                while (cursor.moveToNext()) {
                    signal.throwIfCanceled()
                    val id = cursor.getString(idColumn) ?: continue
                    val name = cursor.getString(nameColumn)?.takeIf(String::isNotBlank) ?: id.displayName(id)
                    if (isIgnoredSourceName(name)) continue

                    val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                    add(DocumentEntry(id, uri, name, cursor.getString(mimeColumn)))
                }
            }
        } ?: throw IOException("无法读取所选文件夹")

        return entries.distinctBy(DocumentEntry::uri).sortedWith { left, right ->
            val nameOrder = compareImageNames(left.name, right.name)
            if (nameOrder != 0) nameOrder else left.uri.compareTo(right.uri)
        }
    }

    private fun chapterPages(items: List<DocumentEntry>): List<ReaderPage> = items.mapNotNull { entry ->
        if (entry.isImage && !isCoverImage(entry.name)) entry.page() else null
    }

    private fun prepareChapter(chapter: LocalChapter, signal: CancellationSignal): LocalChapter {
        val archive = ArchiveLocation.fromChapterId(chapter.id)
        val pages = if (archive != null) archives.prepare(Uri.parse(archive.sourceUri), chapter.pages, signal)
        else chapter.pages.mapNotNull { page -> readHeader(page, signal) }
        return chapter.copy(pages = pages)
    }

    private fun readHeader(page: ReaderPage, signal: CancellationSignal): ReaderPage? {
        signal.throwIfCanceled()
        page.remoteFile?.let { return readImageFileHeader(it, page, signal) }
        val bounds = openAsset(Uri.parse(page.id), signal).use { asset ->
            asset.createInputStream().buffered().use(::readImageBounds)
        }
        signal.throwIfCanceled()
        return bounds?.let { bound ->
            page.copy(width = bound.width, height = bound.height, isBaselineJpeg = bound.isBaselineJpeg)
        }
    }

    private fun decodePage(page: ReaderPage, region: Rect, targetWidth: Int, signal: CancellationSignal): Bitmap {
        val options = BitmapFactory.Options().apply {
            inSampleSize = boundedImageSampleSize(page.width, region.height(), targetWidth)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val location = ArchiveLocation.fromPageId(page.id)
        if (location != null && !page.isArchiveEntryFile) {
            val isWholePage = region.top == 0 && region.bottom == page.height
            val fitsStreamDecode = page.width.toLong() * page.height <= MAX_STREAM_BITMAP_PIXELS
            if (isWholePage && fitsStreamDecode) {
                return archives.readImage(location, page, signal) { input ->
                    BitmapFactory.decodeStream(input, null, options) ?: throw IOException("无法解码图片“${page.name}”")
                }
            }
            return archives.openImage(location, page, signal).use { descriptor ->
                decodeRegion(descriptor, page, region, options, signal)
            }
        }

        page.remoteFile?.let { file ->
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                decodeRegion(descriptor, page, region, options, signal)
            }
        }

        // 可寻址完整文件可由原生解码器直接访问，避免将编码后的图片复制到托管堆。
        return openAsset(Uri.parse(page.id), signal).use { asset ->
            if (asset.isWholeFile()) {
                decodeRegion(asset.parcelFileDescriptor, page, region, options, signal)
            } else {
                decodeStagedRegion(asset, page, region, options, signal)
            }
        }
    }

    private fun openAsset(uri: Uri, signal: CancellationSignal): AssetFileDescriptor =
        resolver.openAssetFileDescriptor(uri, "r", signal) ?: throw IOException("无法打开图片")

    private fun decodeStagedRegion(
        asset: AssetFileDescriptor,
        page: ReaderPage,
        region: Rect,
        options: BitmapFactory.Options,
        signal: CancellationSignal,
    ): Bitmap {
        // A pipe or file slice needs its own seekable backing file for region decoding.
        val temporaryFile = File.createTempFile("reader-region-", ".image", cacheDir)
        try {
            asset.createInputStream().use { input ->
                temporaryFile.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        signal.throwIfCanceled()
                        val size = input.read(buffer)
                        if (size < 0) break
                        output.write(buffer, 0, size)
                    }
                }
            }
            return ParcelFileDescriptor.open(temporaryFile, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                decodeRegion(descriptor, page, region, options, signal)
            }
        } finally {
            temporaryFile.delete()
        }
    }

    private data class DocumentEntry(val documentId: String, val uri: Uri, val name: String, val mime: String?) {
        val isDirectory: Boolean get() = mime == Document.MIME_TYPE_DIR
        val isImage: Boolean get() = !isDirectory && isImageFile(name, mime)
        fun page(): ReaderPage = ReaderPage(uri.toString(), name, 0, 0)
    }
}

private const val MAX_STREAM_BITMAP_PIXELS = 4L * 1024 * 1024
private val regionDispatcher = Dispatchers.IO.limitedParallelism(2)

internal fun validateOfflineImage(file: File, page: ReaderPage, signal: CancellationSignal) {
    signal.throwIfCanceled()
    val sample = boundedImageSampleSize(page.width, page.height, 128)
    try {
        validateWebpFileLength(file)
        val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetSampleSize(sample)
                decoder.setOnPartialImageListener { false }
            }
        } else {
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                decodeRegion(descriptor, page, Rect(0, 0, page.width, page.height), options, signal)
            }
        }
        bitmap.recycle()
    } catch (failure: IOException) {
        throw IOException("离线图片“${page.name}”不完整或无法解码。", failure)
    }
    signal.throwIfCanceled()
}

private fun decodeRegion(
    descriptor: ParcelFileDescriptor,
    page: ReaderPage,
    region: Rect,
    options: BitmapFactory.Options,
    signal: CancellationSignal,
): Bitmap {
    signal.throwIfCanceled()
    val decoder = newRegionDecoder(descriptor) ?: throw IOException("无法打开图片“${page.name}”")
    try {
        signal.throwIfCanceled()
        val imageWasResized = decoder.width != page.width || decoder.height != page.height
        if (imageWasResized) throw IOException("图片“${page.name}”的尺寸已更改，请重新打开章节")
        return decoder.decodeRegion(region, options) ?: throw IOException("无法解码图片“${page.name}”")
    } finally {
        decoder.recycle()
    }
}

@Suppress("DEPRECATION") // The single-argument decoder overload requires API 31.
private fun newRegionDecoder(descriptor: ParcelFileDescriptor): BitmapRegionDecoder? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        BitmapRegionDecoder.newInstance(descriptor)
    } else {
        BitmapRegionDecoder.newInstance(descriptor.fileDescriptor, false)
    }

private fun String.displayName(fallback: String): String =
    substringAfterLast('/').substringAfterLast(':').ifBlank { fallback }

internal data class ImageBounds(val width: Int, val height: Int, val isBaselineJpeg: Boolean = false)

internal fun readImageFileHeader(file: File, page: ReaderPage, signal: CancellationSignal): ReaderPage? {
    signal.throwIfCanceled()
    val bounds = file.inputStream().buffered().use(::readImageBounds) ?: return null
    signal.throwIfCanceled()
    return page.copy(width = bounds.width, height = bounds.height, isBaselineJpeg = bounds.isBaselineJpeg)
}

internal fun readImageBounds(input: BufferedInputStream): ImageBounds? {
    // Older Android WebP codecs copy the compressed stream even in bounds-only mode.
    val prefix = ByteArray(WEBP_MAX_BOUNDS_HEADER)
    input.mark(prefix.size)
    var prefixSize = 0
    while (prefixSize < prefix.size) {
        val read = input.read(prefix, prefixSize, prefix.size - prefixSize)
        if (read < 0) break
        prefixSize += read
    }
    if (isWebpHeader(prefix, prefixSize)) return readWebpBounds(prefix, prefixSize)

    input.reset()
    val hasJpegSignature = prefixSize >= 2 && prefix[0] == 0xff.toByte() && prefix[1] == 0xd8.toByte()
    val isBaselineJpeg = hasJpegSignature && isSingleScanBaselineJpeg(input)
    val header = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeStream(input, null, header)
    val hasImageBounds = header.outWidth > 0 && header.outHeight > 0
    val isSupported = header.outMimeType in imageMimeTypes
    if (!hasImageBounds || !isSupported) return null

    return ImageBounds(header.outWidth, header.outHeight, isBaselineJpeg && header.outMimeType == "image/jpeg")
}

private fun AssetFileDescriptor.isWholeFile(): Boolean {
    val fileSize = parcelFileDescriptor.statSize
    val coversWholeFile = declaredLength == AssetFileDescriptor.UNKNOWN_LENGTH || declaredLength == fileSize
    return startOffset == 0L && fileSize > 0 && coversWholeFile
}

internal suspend fun <T> readOn(
    dispatcher: CoroutineDispatcher,
    discardResult: (T) -> Unit = {},
    operation: (CancellationSignal) -> T,
): T = suspendCancellableCoroutine { continuation ->
    val signal = CancellationSignal()
    continuation.invokeOnCancellation { signal.cancel() }
    // Keep an active decoder on the limited dispatcher until the native call exits.
    dispatcher.dispatch(continuation.context) {
        if (!continuation.isActive) return@dispatch
        val result = runCatching { operation(signal) }
        result.fold(
            onSuccess = { value -> continuation.resume(value) { _, discarded, _ -> discardResult(discarded) } },
            onFailure = continuation::resumeWithException,
        )
    }
}

internal fun imageSampleSize(sourceWidth: Int, targetWidth: Int): Int =
    Integer.highestOneBit((sourceWidth / targetWidth).coerceAtLeast(1))

internal fun boundedImageSampleSize(width: Int, height: Int, targetWidth: Int): Int {
    var sampleSize = imageSampleSize(width, targetWidth)
    while (true) {
        val sampledWidth = (width - 1) / sampleSize + 1
        val sampledHeight = (height - 1) / sampleSize + 1
        val fitsTexture = maxOf(sampledWidth, sampledHeight) <= 4096
        val fitsBitmapBudget = sampledWidth.toLong() * sampledHeight <= 4L * 1024 * 1024
        if (fitsTexture && fitsBitmapBudget) return sampleSize
        sampleSize *= 2
    }
}

internal fun compareImageNames(left: String, right: String): Int {
    var leftIndex = 0
    var rightIndex = 0
    while (leftIndex < left.length && rightIndex < right.length) {
        val leftChar = left[leftIndex]
        val rightChar = right[rightIndex]
        val bothStartWithNumber = leftChar in '0'..'9' && rightChar in '0'..'9'
        if (bothStartWithNumber) {
            val leftEnd = left.endOfDigits(leftIndex)
            val rightEnd = right.endOfDigits(rightIndex)
            val leftNumber = left.substring(leftIndex, leftEnd).trimStart('0')
            val rightNumber = right.substring(rightIndex, rightEnd).trimStart('0')
            // Digit count and lexical order compare numbers larger than a Long.
            val sizeOrder = leftNumber.length.compareTo(rightNumber.length)
            if (sizeOrder != 0) return sizeOrder
            val numberOrder = leftNumber.compareTo(rightNumber)
            if (numberOrder != 0) return numberOrder
            leftIndex = leftEnd
            rightIndex = rightEnd
        } else {
            val charOrder = leftChar.lowercaseChar().compareTo(rightChar.lowercaseChar())
            if (charOrder != 0) return charOrder
            leftIndex++
            rightIndex++
        }
    }

    val suffixOrder = (left.length - leftIndex).compareTo(right.length - rightIndex)
    return if (suffixOrder != 0) suffixOrder else left.compareTo(right)
}

private fun String.endOfDigits(start: Int): Int {
    var end = start + 1
    while (end < length && this[end] in '0'..'9') end++
    return end
}
