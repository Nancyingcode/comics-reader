package io.github.kai.reader

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalArchivesTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun groupsWrappedChaptersInNaturalOrderAndExcludesCoversAndMetadata() {
        val catalog = catalogArchive(
            sourceUri = source,
            title = "作品",
            entryNames = listOf(
                "作品/第10话/10.jpg", "__MACOSX/作品/第10话/._10.jpg", "作品/第2话/封面.jpeg",
                "作品/第10话/2.WEBP", "作品/第2话/1.PNG", "作品/Cover.jpg", "作品/.thumbnails/icon.jpg",
                "作品/第2话/Thumbs.db", "作品/第10话/ComicInfo.xml", "作品/第2话/thumbnail.jpg",
            ),
            splitByFolder = true,
        )

        assertEquals("Cover.jpg", catalog.cover?.name)
        assertEquals(listOf("第2话", "第10话"), catalog.chapters.map { it.name })
        assertEquals(listOf(listOf("1.PNG"), listOf("2.WEBP", "10.jpg")), catalog.chapters.map { chapter ->
            chapter.pages.map { it.name }
        })
        assertEquals("作品/第2话", ArchiveLocation.fromChapterId(catalog.chapters[0].id)?.path)
        assertEquals("作品/第10话/2.WEBP", ArchiveLocation.fromPageId(catalog.chapters[1].pages[0].id)?.path)
        assertTrue(catalog.chapters.flatMap { it.pages }.all { it.width == 0 && it.height == 0 })
    }

    @Test
    fun flatAndChapterArchivesProduceOneChapterWithStableIds() {
        val flat = catalogArchive(source, "单册", listOf("10.png", "2.png"), splitByFolder = true)
        val perChapter = catalogArchive(
            source, "第2话", listOf("分组10/1.jpg", "分组2/1.jpg", "cover.jpg", "分组2/cover.png"),
            splitByFolder = false,
        )
        val reimport = catalogArchive(source, "新名称", listOf("1.png", "2.png", "10.png"), splitByFolder = true)

        assertNull(flat.cover)
        assertEquals("单册", flat.chapters.single().name)
        assertEquals(listOf("2.png", "10.png"), flat.chapters.single().pages.map { it.name })
        assertEquals(flat.chapters.single().id, reimport.chapters.single().id)
        assertEquals(flat.chapters.single().pages[0].id, reimport.chapters.single().pages[1].id)
        assertEquals("cover.jpg", perChapter.cover?.name)
        assertEquals("第2话", perChapter.chapters.single().name)
        assertEquals(listOf("分组2/1.jpg", "分组10/1.jpg"), perChapter.chapters.single().pages.map { page ->
            ArchiveLocation.fromPageId(page.id)?.path
        })
    }

    @Test
    fun ignoresUnsafePathsAndUsesFirstDuplicatePage() {
        val catalog = catalogArchive(
            source, "单册", listOf("../1.png", "/2.png", "C:\\3.png", "内页\\10.png", "内页/10.png"),
            splitByFolder = true,
        )
        val page = catalog.chapters.single().pages.single()

        assertEquals(ArchiveLocation(source, "内页\\10.png"), ArchiveLocation.fromPageId(page.id))
        assertNull(ArchiveLocation.fromChapterId(page.id))
        assertNull(ArchiveLocation.fromPageId(source))
    }

    @Test
    fun indexesPackageSliceAndReadsLastEntryWithoutReadingEarlierPayload() {
        val tail = byteArrayOf(0x11, 0x22, 0x33)
        val zip = ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { stream ->
                stream.writeStoredEntry("1.jpg", ByteArray(1024 * 1024))
                stream.writeStoredEntry("42.jpg", tail)
            }
        }.toByteArray()
        val prefix = ByteArray(11) { 0x44 }
        val file = temporaryFolder.newFile().apply { writeBytes(prefix + zip + ByteArray(13)) }
        val slice = ArchiveSliceChannel(file.inputStream(), prefix.size.toLong(), zip.size.toLong())
        val channel = CountingArchiveChannel(slice)

        openCentralDirectory(channel).use { archive ->
            assertEquals(listOf("1.jpg", "42.jpg"), archive.entries.asSequence().map { it.name }.toList())
            assertTrue(channel.bytesRead < 1024)

            val bitmapEntry = archive.getEntry("42.jpg")
            assertArrayEquals(tail, archive.getInputStream(bitmapEntry).use { it.readBytes() })
            assertTrue(channel.bytesRead < 1024)
        }
        assertFalse(channel.isOpen)
    }

    private fun ZipOutputStream.writeStoredEntry(name: String, content: ByteArray) {
        val entry = ZipEntry(name).apply {
            method = ZipEntry.STORED
            size = content.size.toLong()
            compressedSize = size
            crc = CRC32().apply { update(content) }.value
        }
        putNextEntry(entry)
        write(content)
        closeEntry()
    }

    private class CountingArchiveChannel(private val base: SeekableByteChannel) : SeekableByteChannel by base {
        var bytesRead = 0L
            private set

        override fun read(destination: ByteBuffer): Int = base.read(destination).also { count ->
            if (count > 0) bytesRead += count
        }

        override fun position(newPosition: Long): SeekableByteChannel {
            base.position(newPosition)
            return this
        }
    }

    private companion object {
        const val source = "content://storage/tree/%2F漫画/document/第2话%3A合集.zip"
    }
}
