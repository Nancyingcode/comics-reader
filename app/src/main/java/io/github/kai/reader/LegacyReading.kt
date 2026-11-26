package io.github.kai.reader

import android.net.Uri
import android.provider.DocumentsContract

internal class LegacyReading(history: Map<String, *>) {
    val lastTree = (history["lastTree"] as? String)?.takeIf(String::isNotBlank)
    val lastChapterKey = lastTree?.let(::documentKey)
    val trees: List<String>
    private val anchors: Map<String, ReadingAnchor>

    init {
        val documents = history.keys.mapNotNull { key ->
            when {
                key.startsWith("page/") -> key.removePrefix("page/")
                key.startsWith("offset/") -> key.removePrefix("offset/")
                else -> null
            }
        }.distinct().sorted()
        trees = buildList {
            lastTree?.let(::add)
            documents.mapNotNullTo(this, ::treeForLegacyChapter)
        }.distinctBy(::documentKey)
        anchors = buildMap {
            for (document in documents) {
                val pageId = (history["page/$document"] as? String)?.takeIf(String::isNotBlank) ?: continue
                val fraction = (history["offset/$document"] as? Float)?.toDouble() ?: 0.0
                put(documentKey(document), ReadingAnchor(pageSourceKey(pageId), fraction.readingFraction()))
            }
        }
    }

    fun progress(bookId: String, chapterKey: String): LibraryProgress? {
        val anchor = anchors[chapterKey] ?: return null
        val readAt = if (chapterKey == lastChapterKey) LEGACY_LAST_READ_AT else 0L
        return LibraryProgress(bookId, chapterKey, anchor.pageId, null, anchor.fraction, readAt)
    }
}

internal const val LEGACY_LAST_READ_AT = 1L

private fun treeForLegacyChapter(document: String): String? {
    val uri = Uri.parse(document)
    val authority = uri.authority ?: return null
    val segments = uri.pathSegments
    val isSelectedRootDocument = segments.size == 4 && segments[0] == "tree" && segments[2] == "document"
    if (!isSelectedRootDocument || segments[1] != segments[3]) return null
    // Phase 1 saved the document URI of the selected root. The grant belongs to its tree URI.
    return DocumentsContract.buildTreeDocumentUri(authority, segments[1]).toString()
}

internal fun documentKey(source: String): String {
    val uri = Uri.parse(source)
    val authority = uri.authority ?: return source
    if (uri.scheme != "content") return source
    val segments = uri.pathSegments
    val documentId = when {
        segments.size == 2 && segments[0] == "document" -> segments[1]
        segments.size == 2 && segments[0] == "tree" -> segments[1]
        segments.size == 4 && segments[0] == "tree" && segments[2] == "document" -> segments[3]
        else -> return source
    }
    // Android grants access through a tree prefix, but the provider's document ID identifies the file.
    return DocumentsContract.buildDocumentUri(authority, documentId).toString()
}

internal fun chapterSourceKey(id: String): String =
    ArchiveLocation.fromChapterId(id)?.sourceKey("kai-chapter") ?: documentKey(id)

internal fun pageSourceKey(id: String): String =
    ArchiveLocation.fromPageId(id)?.sourceKey("kai-page") ?: documentKey(id)

private fun ArchiveLocation.sourceKey(scheme: String): String = Uri.Builder()
    .scheme(scheme)
    .appendPath(documentKey(sourceUri))
    .appendPath(path)
    .build().toString()

internal fun Double.readingFraction(): Double = if (isFinite()) coerceIn(0.0, 1.0) else 0.0
