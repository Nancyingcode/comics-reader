package io.github.kai.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryAnchorTest {
    @Test
    fun `reordered page under a different tree grant keeps its fraction`() {
        val pages = listOf(page("different", "different", 0), page("new-grant", "same-file", 1))
        val saved = LibraryProgress("book", "chapter", "same-file", 0, 0.63, 1)

        assertEquals(ReadingAnchor("new-grant", 0.63), resolveAnchor(pages, saved))
    }

    @Test
    fun `missing page falls back to the closest available page start`() {
        val pages = listOf(page("first", "first-file", 0), page("last", "last-file", 1))
        val saved = LibraryProgress("book", "chapter", "removed-file", 12, 0.63, 1)

        assertEquals(ReadingAnchor("last", 0.0), resolveAnchor(pages, saved))
    }

    @Test
    fun `missing legacy page without an ordinal starts at the first page`() {
        val pages = listOf(page("first", "first-file", 0), page("second", "second-file", 1))
        val saved = LibraryProgress("book", "chapter", "removed-file", null, 0.63, 1)

        assertEquals(ReadingAnchor("first", 0.0), resolveAnchor(pages, saved))
    }

    private fun page(id: String, sourceKey: String, position: Int) =
        LibraryPage("book", "chapter", sourceKey, position, LibraryImage(id, id, 100, 100, false))
}
