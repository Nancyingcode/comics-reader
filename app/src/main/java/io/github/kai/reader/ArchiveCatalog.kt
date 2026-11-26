package io.github.kai.reader

import java.util.Base64

internal data class ArchiveLocation(val sourceUri: String, val path: String) {
    fun pageId(): String = toId(PAGE_PREFIX)
    fun chapterId(): String = toId(CHAPTER_PREFIX)

    private fun toId(prefix: String): String = "$prefix${sourceUri.encoded()}:${path.encoded()}"

    companion object {
        private const val PAGE_PREFIX = "kai-archive-page:"
        private const val CHAPTER_PREFIX = "kai-archive-chapter:"

        fun fromPageId(id: String): ArchiveLocation? = fromId(id, PAGE_PREFIX)
        fun fromChapterId(id: String): ArchiveLocation? = fromId(id, CHAPTER_PREFIX)

        private fun fromId(id: String, prefix: String): ArchiveLocation? {
            if (!id.startsWith(prefix)) return null
            val fields = id.removePrefix(prefix).split(':', limit = 2)
            require(fields.size == 2) { "压缩包位置无效" }
            val decoder = Base64.getUrlDecoder()
            return ArchiveLocation(
                decoder.decode(fields[0]).toString(Charsets.UTF_8),
                decoder.decode(fields[1]).toString(Charsets.UTF_8),
            )
        }

        private fun String.encoded(): String = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(toByteArray(Charsets.UTF_8))
    }
}

internal data class ArchiveCatalog(val cover: ReaderPage?, val chapters: List<LocalChapter>)

internal fun catalogArchive(
    sourceUri: String,
    title: String,
    entryNames: List<String>,
    splitByFolder: Boolean,
): ArchiveCatalog {
    val pictures = entryNames.mapNotNull(::archiveImage).distinctBy(ArchiveImage::parts)
        .sortedWith { left, right -> comparePaths(left.parts, right.parts) }
    val pages = pictures.filterNot { isCoverImage(it.name) }
    if (pages.isEmpty()) return ArchiveCatalog(null, emptyList())

    val sharedFolder = commonImageFolder(pages)
    // 发行包可能套有外层目录。该层及其上层的封面属于整部作品。
    val covers = pictures.filter { picture ->
        isCoverImage(picture.name) && sharedFolder.take(picture.parent.size) == picture.parent
    }
    val cover = covers.minByOrNull { it.parent.size }
    val chapterPages = if (splitByFolder) {
        pages.groupBy { picture -> picture.parent.take(sharedFolder.size + 1) }
    } else mapOf(emptyList<String>() to pages)

    val chapters = chapterPages.entries.sortedWith { left, right -> comparePaths(left.key, right.key) }
        .map { (folder, images) ->
            val location = ArchiveLocation(sourceUri, folder.joinToString("/"))
            LocalChapter(location.chapterId(), folder.lastOrNull() ?: title, images.map { it.page(sourceUri) })
        }
    return ArchiveCatalog(cover?.page(sourceUri), chapters)
}

private data class ArchiveImage(val entryName: String, val parts: List<String>) {
    val name: String get() = parts.last()
    val parent: List<String> get() = parts.dropLast(1)

    fun page(sourceUri: String) = ReaderPage(ArchiveLocation(sourceUri, entryName).pageId(), name, 0, 0)
}

private fun archiveImage(name: String): ArchiveImage? {
    val parts = name.replace('\\', '/').split('/')
    val isAbsolute = parts.first().let { it.length >= 2 && it[0].isLetter() && it[1] == ':' }
    if (isAbsolute || parts.any { isIgnoredSourceName(it) || '\u0000' in it }) return null

    return if (isImageFile(parts.last())) ArchiveImage(name, parts) else null
}

private fun commonImageFolder(pictures: List<ArchiveImage>): List<String> {
    var shared = pictures.first().parent
    for (picture in pictures.drop(1)) {
        val commonSize = shared.zip(picture.parent).takeWhile { (left, right) -> left == right }.size
        shared = shared.take(commonSize)
        if (shared.isEmpty()) break
    }
    return shared
}

private fun comparePaths(left: List<String>, right: List<String>): Int {
    for (index in 0 until minOf(left.size, right.size)) {
        val order = compareImageNames(left[index], right[index])
        if (order != 0) return order
    }
    return left.size.compareTo(right.size)
}

internal fun isCoverImage(name: String): Boolean = name.substringBeforeLast('.').lowercase() in coverNames

internal fun isIgnoredSourceName(name: String): Boolean =
    name.isBlank() || name.startsWith('.') || name.lowercase() in systemSourceNames

internal fun isImageFile(name: String, mime: String? = null): Boolean =
    mime in imageMimeTypes || name.substringAfterLast('.', "").lowercase() in imageExtensions

internal fun String.isComicArchive(): Boolean = substringAfterLast('.', "").lowercase() in archiveExtensions

internal fun String.archiveTitle(): String = if (isComicArchive()) substringBeforeLast('.') else this

internal val imageMimeTypes = setOf("image/jpeg", "image/png", "image/webp")
private val imageExtensions = setOf("jpg", "jpeg", "png", "webp")
private val archiveExtensions = setOf("cbz", "zip")
private val coverNames = setOf("cover", "封面")
private val systemSourceNames = setOf(
    "__macosx", "@eadir", "thumbnails", "thumbs.db", "desktop.ini", "comicinfo.xml",
    "folder.jpg", "folder.png", "thumb.jpg", "thumbnail.jpg", "thumbnail.png",
)
