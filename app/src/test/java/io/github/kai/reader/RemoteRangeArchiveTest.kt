package io.github.kai.reader

import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import kotlinx.coroutines.runBlocking
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.apache.commons.compress.archivers.zip.Zip64Mode
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RemoteRangeArchiveTest {
    @get:Rule
    val directory = TemporaryFolder()

    @Test
    fun zip32AndZip64ReadOnlyTargetEntriesThroughPinnedRanges() = runBlocking {
        val largePage = ByteArray(512 * 1024) { (it * 11).toByte() }
        val finalPage = ByteArray(110_000) { (it * 37).toByte() }
        val firstPage = "first page".toByteArray()
        for (forceZip64 in listOf(false, true)) {
            val archive = zip(listOf(
                Image("书/第2话/2.jpg", firstPage, ZipEntry.STORED),
                Image("书/第10话/99.png", finalPage),
                Image("书/第10话/2.png", largePage, ZipEntry.STORED),
                Image("书/cover.jpg", "cover".toByteArray()),
            ), forceZip64)
            val endpoint = RangeEndpoint(Asset(archive))
            val reader = RemoteRangeArchives(endpoint.transport)
            val index = checkNotNull(reader.open(source, null))
            val catalog = index.catalog(source.id, "漫画", splitByFolder = true)

            assertEquals("cover.jpg", catalog.cover?.name)
            assertEquals(listOf("第2话", "第10话"), catalog.chapters.map(LocalChapter::name))
            val pages = catalog.chapters.last().pages
            assertEquals(listOf("2.png", "99.png"), pages.map(ReaderPage::name))
            assertEquals(0, pages.last().width)
            assertEquals("bytes=-65633", endpoint.requests.single().request.header("Range"))
            assertNull(endpoint.requests.single().request.header("If-Range"))

            val last = pages.last()
            val target = directory.newFile()
            val path = checkNotNull(ArchiveLocation.fromPageId(last.id)).path
            val extracted = reader.extract(source, null, index.version, path, last.archiveVersion, target) {}
            assertArrayEquals(finalPage, target.readBytes())
            assertEquals(finalPage.size.toLong(), extracted.version.length)
            assertEquals(3, endpoint.requests.size)
            assertTrue(endpoint.requests.sumOf(RecordedRange::bytes) < archive.size / 2)
            assertTrue(endpoint.requests.all { it.request.method == "GET" && it.request.header("Accept-Encoding") == "identity" })
            assertTrue(endpoint.requests.drop(1).all { it.request.header("If-Range") == index.version.etag })

            reader.extract(source, null, index.version, "书/第2话/2.jpg", null, target) {}
            assertArrayEquals(firstPage, target.readBytes())
        }
    }

    @Test
    fun webDavReadsTheCentralDirectoryOutsideTheSuffixWithCredentialsAndIfRange() = runBlocking {
        val files = List(1600) { index -> Image("chapter-with-a-long-folder-name/entry-${index + 1}.jpg", byteArrayOf(1)) }
        val archive = zip(files)
        val endpoint = RangeEndpoint(Asset(archive))
        val root = webDavRoot("http://example.test/", "comics")
        val davSource = RemoteLocation(parseRemoteUrl("http://example.test/comics/book.cbz", allowHttp = true), "account")
        val account = RemoteAccount(root, "reader", "test-password")
        val chapter = checkNotNull(RemoteRangeArchives(endpoint.transport).open(davSource, account))
            .catalog(davSource.id, "漫画", splitByFolder = false).chapters.single()

        assertEquals(files.size, chapter.pages.size)
        assertEquals("entry-1600.jpg", chapter.pages.last().name)
        assertEquals(2, endpoint.requests.size)
        assertTrue(endpoint.requests[1].request.header("Range")?.startsWith("bytes=-") == false)
        assertEquals(endpoint.asset.etag, endpoint.requests[1].request.header("If-Range"))
        assertTrue(endpoint.requests.all {
            it.request.header("Authorization") == Credentials.basic("reader", "test-password", Charsets.UTF_8)
        })
    }

    @Test
    fun ignoredRangeAndWeakValidatorsFallBackToFullDownload() = runBlocking {
        val asset = Asset(zip(listOf(Image("1.jpg", byteArrayOf(3, 2, 1)))))
        for (endpoint in listOf(RangeEndpoint(asset, supportsRange = false), RangeEndpoint(asset.copy(etag = "W/\"weak\"")))) {
            assertNull(RemoteRangeArchives(endpoint.transport).open(source, null))
            val target = directory.newFile()
            val file = endpoint.transport.download(source, null, target, null, null) {}
            assertNotNull(file)
            assertArrayEquals(asset.bytes, target.readBytes())
            assertEquals(2, endpoint.requests.size)
            assertNotNull(endpoint.requests.first().request.header("Range"))
            assertNull(endpoint.requests.last().request.header("Range"))
        }
    }

    @Test
    fun rejectsPartialMessagesWithWrongBoundsEncodingOrActualLength() = runBlocking {
        val payload = byteArrayOf(1, 2, 3)
        val cases = listOf(
            Reply("bytes 3-5/*", payload),
            Reply("bytes 2-4/6", payload),
            Reply("bytes 3-6/6", payload),
            Reply("bytes 3-5/6", payload, encoding = "gzip"),
            Reply("bytes 3-5/6", payload + 4.toByte(), unknownLength = true),
            Reply("bytes 3-5/6", payload.copyOf(2), unknownLength = true),
            Reply("bytes 3-5/6", payload.copyOf(2)),
        )
        for (reply in cases) {
            val transport = RemoteTransport(OkHttpClient.Builder().addInterceptor { chain ->
                response(chain.request(), 206, reply.payload, "\"v1\"", reply.range, reply.encoding, reply.unknownLength)
            }.build())
            assertThrows(RemoteRangeUnsupported::class.java) {
                runBlocking { transport.archiveTail(source, null, 3) }
            }
        }
        Unit
    }

    @Test
    fun reopeningAfterReplacementBuildsNewIndexWithoutRereadingOldPayload() = runBlocking {
        val firstVersion = "old bytes".toByteArray()
        val newVersion = "new bytes".toByteArray()
        val endpoint = RangeEndpoint(Asset(zip(listOf(Image("1.jpg", firstVersion)))))
        val reader = RemoteRangeArchives(endpoint.transport)
        val old = checkNotNull(reader.open(source, null))
        val oldPage = old.catalog(source.id, "漫画", false).chapters.single().pages.single()

        endpoint.afterRequest = { request ->
            if (request.header("If-Range") != null) {
                endpoint.asset = Asset(zip(listOf(Image("1.jpg", newVersion))), "\"v2\"")
            }
        }
        val target = directory.newFile()
        assertThrows(RemoteContentChanged::class.java) {
            runBlocking { reader.extract(source, null, old.version, "1.jpg", oldPage.archiveVersion, target) {} }
        }
        assertEquals(0L, target.length())
        assertEquals(listOf(206, 206, 200), endpoint.requests.map(RecordedRange::status))

        val fresh = checkNotNull(reader.open(source, null))
        val freshPage = fresh.catalog(source.id, "漫画", false).chapters.single().pages.single()
        assertEquals(oldPage.id, freshPage.id)
        assertTrue(oldPage.archiveVersion != freshPage.archiveVersion)
        reader.extract(source, null, fresh.version, "1.jpg", freshPage.archiveVersion, target) {}
        assertArrayEquals(newVersion, target.readBytes())
    }

    @Test
    fun doesNotTrustReusedEtagAcrossRedirectTargets() = runBlocking {
        val active = java.util.concurrent.atomic.AtomicBoolean(false)
        val payload = byteArrayOf(7)
        val transport = RemoteTransport(OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            when {
                request.url == source.url -> response(request, 302, byteArrayOf(), headers = mapOf("Location" to "/one"))
                request.url.encodedPath == "/one" && active.get() ->
                    response(request, 302, byteArrayOf(), headers = mapOf("Location" to "/two"))
                else -> response(request, 206, payload, "\"same-opaque-etag\"", "bytes 0-0/1")
            }
        }.build())
        val tail = transport.archiveTail(source, null, 1)
        assertEquals("/one", tail.location.url.encodedPath)
        assertEquals(tail.version, RemoteVersion.decode(tail.version.encode()))
        val previous = RemoteVersion(etag = "\"v0\"", length = 80)
        assertEquals(previous, RemoteVersion.decode(previous.encode()))
        active.set(true)
        assertThrows(RemoteContentChanged::class.java) {
            runBlocking { transport.archiveRange(tail.location, null, 0, 1, tail.version) { it.read() } }
        }
        Unit
    }

    @Test
    fun clearingCacheRejectsInFlightPublicationAndDefersPinnedDeletion() {
        val cache = RemoteContentStore(directory.newFolder(), directory.newFolder())
        val revision = RemoteVersion(etag = "\"v1\"", length = 40, rangeUrlKey = remoteDigest(source.url.toString()))
        val key = "${source.id}\n${revision.encode()}"
        val bytes = byteArrayOf(1, 2, 3)
        val info = RemoteFileInfo(RemoteVersion(length = 3, sha256 = remoteDigest("test bytes")), checkNotNull(revision.rangeUrlKey))
        val stale = cache.archivePageGeneration()
        val part = cache.createPart().apply { writeBytes(bytes) }

        cache.clearCache()
        assertNull(cache.cacheArchivePage(key, part, info, stale))
        assertTrue(part.isFile)
        assertNull(cache.cachedArchivePage(key))

        val current = cache.archivePageGeneration()
        val lease = checkNotNull(cache.cacheArchivePage(key, part, info, current))
        cache.clearCache()
        assertNull(cache.cachedArchivePage(key))
        assertArrayEquals(bytes, lease.file.readBytes())
        lease.close()
        assertFalse(lease.file.exists())
    }

    private data class Asset(val bytes: ByteArray, val etag: String = "\"v1\"")

    private data class Image(val name: String, val bytes: ByteArray, val method: Int = ZipEntry.DEFLATED)

    private data class RecordedRange(val request: Request, val bytes: Int, val status: Int)

    private data class Reply(val range: String, val payload: ByteArray, val encoding: String? = null, val unknownLength: Boolean = false)

    private class RangeEndpoint(@Volatile var asset: Asset, private val supportsRange: Boolean = true) {
        val requests = CopyOnWriteArrayList<RecordedRange>()
        @Volatile var afterRequest: (Request) -> Unit = {}

        val transport = RemoteTransport(OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val current = asset
            val range = request.header("Range")?.removePrefix("bytes=")
            val ifRange = request.header("If-Range")
            val partial = supportsRange && range != null && (ifRange == null || ifRange == current.etag)
            val result = if (partial) {
                val fields = checkNotNull(range).split('-')
                val end = fields[1].toInt()
                val start = if (fields[0].isEmpty()) (current.bytes.size - end).coerceAtLeast(0) else fields[0].toInt()
                val last = if (fields[0].isEmpty()) current.bytes.lastIndex else end
                val body = current.bytes.copyOfRange(start, last + 1)
                requests.add(RecordedRange(request, body.size, 206))
                response(request, 206, body, current.etag, "bytes $start-$last/${current.bytes.size}")
            } else {
                requests.add(RecordedRange(request, current.bytes.size, 200))
                response(request, 200, current.bytes, current.etag)
            }
            afterRequest(request)
            result
        }.build())
    }

    private companion object {
        val source = RemoteLocation(parseRemoteUrl("https://example.test/comic.cbz?source=reader"))

        fun zip(images: List<Image>, forceZip64: Boolean = false): ByteArray =
            ByteArrayOutputStream().also { bytes ->
                ZipArchiveOutputStream(bytes).use { zip ->
                    if (forceZip64) zip.setUseZip64(Zip64Mode.Always)
                    for (image in images) {
                        val entry = ZipArchiveEntry(image.name).apply {
                            method = image.method
                            if (method == ZipEntry.STORED) {
                                size = image.bytes.size.toLong()
                                compressedSize = size
                                crc = CRC32().apply { update(image.bytes) }.value
                            }
                        }
                        zip.putArchiveEntry(entry)
                        zip.write(image.bytes)
                        zip.closeArchiveEntry()
                    }
                }
            }.toByteArray()

        fun response(
            request: Request,
            code: Int,
            bytes: ByteArray,
            etag: String? = null,
            range: String? = null,
            encoding: String? = null,
            unknownLength: Boolean = false,
            headers: Map<String, String> = emptyMap(),
        ): Response {
            val body = if (!unknownLength) bytes.toResponseBody() else object : ResponseBody() {
                private val buffer = Buffer().write(bytes)
                override fun contentLength() = -1L
                override fun contentType() = null
                override fun source() = buffer
            }
            return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
                .body(body).apply {
                    if (etag != null) header("ETag", etag)
                    if (range != null) header("Content-Range", range)
                    if (encoding != null) header("Content-Encoding", encoding)
                    headers.forEach { (name, value) -> header(name, value) }
                }.build()
        }
    }
}
