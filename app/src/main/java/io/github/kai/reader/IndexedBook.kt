package io.github.kai.reader

data class IndexedBook(
    val id: String,
    val name: String,
    val sourceUri: String,
    val isArchive: Boolean,
    val cover: ReaderPage,
    val chapters: List<LocalChapter>,
)
