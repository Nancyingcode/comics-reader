package io.github.kai.reader

import java.io.File
import java.io.IOException
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RemoteCacheContractTest {
    @get:Rule val temporary = TemporaryFolder()

    private val ordinary get() = File(temporary.root, "ordinary")
    private val offline get() = File(temporary.root, "offline")

    @Test
    fun `clearing ordinary cache keeps the open edition until readers release it`() {
        val store = RemoteContentStore(ordinary, offline)
        val location = RemoteLocation(parseRemoteUrl("https://reader.example/book.cbz"))
        val contents = "downloaded book"
        val version = RemoteVersion(length = contents.length.toLong(), sha256 = remoteDigest(contents))
        val info = RemoteFileInfo(version, remoteDigest(location.url.toString()))
        val part = store.createPart().apply { writeText(contents) }
        val first = store.cache(RemoteFileKind.ARCHIVE, location, part, info)
        val backingFile = first.file

        first.use {
            checkNotNull(store.cached(RemoteFileKind.ARCHIVE, location)).use { second ->
                store.clearCache()

                assertNull(store.cached(RemoteFileKind.ARCHIVE, location))
                assertNull(store.cached(RemoteFileKind.ARCHIVE, location, version))
                assertEquals(contents, second.file.readText())
            }
            assertEquals(contents, backingFile.readText())
        }
        assertFalse(backingFile.exists())
    }

    @Test
    fun `offline deletion removes chapter availability and keeps an open copy usable`() {
        val store = RemoteContentStore(ordinary, offline)
        val (part, chapter) = stageImageChapter(store, "edition-a", "saved image")
        store.publishOffline(part, chapter, archive = null)
        val reader = checkNotNull(store.offlineChapter(chapter.id))
        val backingFile = checkNotNull(reader.chapter.pages.single().remoteFile)

        reader.use {
            store.clearCache()
            checkNotNull(store.offlineChapter(chapter.id)).close()
            assertEquals("saved image", backingFile.readText())

            store.deleteOffline(chapter.id)
            assertNull(store.offlineChapter(chapter.id))
            assertEquals("saved image", backingFile.readText())
        }
        assertFalse(backingFile.exists())
    }

    @Test
    fun `an incomplete replacement keeps the previous offline chapter published`() {
        val store = RemoteContentStore(ordinary, offline)
        val (complete, original) = stageImageChapter(store, "edition-a", "original image")
        store.publishOffline(complete, original, archive = null)

        val (truncated, replacement) = stageImageChapter(store, "edition-b", "short", expectedBytes = 100)
        assertThrows(IOException::class.java) { store.publishOffline(truncated, replacement, archive = null) }

        checkNotNull(store.offlineChapter(original.id)).use { saved ->
            assertEquals("edition-a", saved.chapter.sourceVersion)
            assertEquals("original image", saved.chapter.pages.single().remoteFile?.readText())
        }
    }

    @Test
    fun `a newly saved edition is visible without changing the active offline copy`() {
        val store = RemoteContentStore(ordinary, offline)
        val (complete, original) = stageImageChapter(store, "edition-a", "original image")
        store.publishOffline(complete, original, archive = null)
        val reader = checkNotNull(store.offlineChapter(original.id))
        val backingFile = checkNotNull(reader.chapter.pages.single().remoteFile)

        reader.use {
            val (newPart, replacement) = stageImageChapter(store, "edition-b", "replacement image")
            store.publishOffline(newPart, replacement, archive = null)

            checkNotNull(store.offlineChapter(original.id)).use { newReader ->
                assertEquals("edition-b", newReader.chapter.sourceVersion)
                assertEquals("replacement image", newReader.chapter.pages.single().remoteFile?.readText())
            }
            assertTrue(backingFile.isFile)
            assertEquals("original image", backingFile.readText())
        }
        assertFalse(backingFile.exists())
    }

    @Test
    fun `restarting removes interrupted staging and offline snapshots without changing the published chapter`() {
        val store = RemoteContentStore(ordinary, offline)
        val (complete, original) = stageImageChapter(store, "edition-a", "saved image")
        store.publishOffline(complete, original, archive = null)
        val pendingDownload = store.createPart().apply { writeText("interrupted download") }
        val (pendingOffline, _) = stageImageChapter(store, "edition-b", "interrupted save")
        val abandonedSnapshot = File(offline, "${remoteDigest(original.id)}/snapshot-${UUID.randomUUID()}")
        assertTrue(abandonedSnapshot.mkdir())
        File(abandonedSnapshot, RemoteContentStore.offlineImageName(0)).writeText("unpublished image")

        val reopened = RemoteContentStore(ordinary, offline)

        assertFalse(pendingDownload.exists())
        assertFalse(pendingOffline.exists())
        assertFalse(abandonedSnapshot.exists())
        checkNotNull(reopened.offlineChapter(original.id)).use { saved ->
            assertEquals("edition-a", saved.chapter.sourceVersion)
            assertEquals("saved image", saved.chapter.pages.single().remoteFile?.readText())
        }
    }

    private fun stageImageChapter(
        store: RemoteContentStore,
        edition: String,
        contents: String,
        expectedBytes: Long = contents.length.toLong(),
    ): Pair<File, LocalChapter> {
        val part = store.createOfflinePart()
        File(part, RemoteContentStore.offlineImageName(0)).writeText(contents)
        val version = RemoteVersion(length = expectedBytes, sha256 = remoteDigest(contents)).encode()
        val page = ReaderPage("page", "001.jpg", 960, 1255, remoteVersion = version)
        return part to LocalChapter("chapter", "Chapter", listOf(page), edition)
    }
}
