package io.github.kai.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteLibraryIndexTest {
    @Test
    fun `a remote chapter keeps its counted pages only for the discovered version`() {
        val chapter = LibraryChapter("book", "chapter", "chapter", "Chapter", 0, 42, "edition-a")
        val unopened = LocalChapter("chapter", "Chapter", emptyList(), "edition-a")

        assertTrue(canReuseUnopenedPages(unopened, chapter))
        assertFalse(canReuseUnopenedPages(unopened.copy(sourceVersion = "edition-b"), chapter))
        assertFalse(canReuseUnopenedPages(unopened.copy(sourceVersion = null), chapter))
        assertFalse(canReuseUnopenedPages(unopened.copy(sourceVersion = null), chapter.copy(sourceVersion = null)))
    }

    @Test
    fun `a DAV listing can match the downloaded revision with only its etag`() {
        val listed = RemoteVersion(etag = "\"ed-1\"")
        val downloaded = listed.copy(length = 431L, sha256 = "a".repeat(64))
        val chapter = LibraryChapter("book", "chapter", "chapter", "Chapter", 0, 42, downloaded.encode())
        val unopened = LocalChapter("chapter", "Chapter", emptyList(), listed.encode())

        assertTrue(canReuseUnopenedPages(unopened, chapter))
        assertTrue(canUpdateFromOffline(listed.encode(), downloaded.encode()))
    }

    @Test
    fun `opening a previous offline edition cannot replace a newer indexed chapter`() {
        assertFalse(canUpdateFromOffline("edition-b", "edition-a"))
        assertTrue(canUpdateFromOffline("edition-a", "edition-a"))
        assertTrue(canUpdateFromOffline(null, "edition-a"))
    }

    @Test
    fun `image index preserves the validator needed to read the selected edition`() {
        val page = ReaderPage("page", "002.jpg", 0, 0, remoteVersion = "edition-a")

        assertEquals(page, LibraryImage(page).readerPage())
    }
}
