package io.github.kai.reader

import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Credentials
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RemoteTransportTest {
    @get:Rule
    val directory = TemporaryFolder()

    @Test
    fun directoryUsesDepthOneAndAcceptsOnlyDirectChildren() = runBlocking {
        val root = webDavRoot("https://dav.example/library/", "漫画")
        val source = RemoteLocation(root, "account")
        val account = RemoteAccount(root, "reader", "local-password")
        lateinit var request: Request
        val transport = fakeTransport { chain ->
            request = chain.request()
            chain.response(207, davResponse(
                davFile("/library/漫画/10.jpeg", "<D:getetag>&quot;ten&quot;</D:getetag>"),
                davFile("/library/漫画/2.jpeg", "<D:getcontentlength>7</D:getcontentlength>"),
                davFile("/library/漫画/三/", "<D:resourcetype><D:collection/></D:resourcetype>"),
                davFile("/library/漫画/", "<D:resourcetype><D:collection/></D:resourcetype>"),
                davFile("/library/漫画/三/11.jpeg"),
                davFile("/library/漫画2/outside.jpeg"),
                davFile("/library/漫画/..%2Foutside.jpeg"),
                davFile("https://other.example/library/漫画/8.jpeg"),
                davFile("/library/漫画/.ignored/"),
            ).toResponseBody())
        }

        val children = transport.list(source, account)

        assertEquals("PROPFIND", request.method)
        assertEquals("1", request.header("Depth"))
        assertEquals(Credentials.basic("reader", "local-password"), request.header("Authorization"))
        assertEquals(listOf("2.jpeg", "10.jpeg", "三"), children.map(RemoteDavEntry::name))
        assertEquals(7L, children.first().version?.length)
        assertEquals("\"ten\"", children[1].version?.etag)
        assertTrue(children.last().isDirectory)
        assertEquals(children.last().location, RemoteLocation.fromId(children.last().location.id))
    }

    @Test
    fun davRejectsDocumentsWithDoctype() {
        val root = webDavRoot("https://dav.example/", "")
        val account = RemoteAccount(root, "", "")
        val xml = "<!DOCTYPE multistatus><D:multistatus xmlns:D=\"DAV:\"/>"
        assertThrows(IOException::class.java) {
            parseDavListing(xml.byteInputStream(), RemoteLocation(root, "account"), account)
        }
    }

    @Test
    fun allowsExplicitHttpDavButRejectsItForDirectArchives() = runBlocking {
        val root = webDavRoot("http://dav.example/", "comics")
        val account = RemoteAccount(root, "", "")
        val source = RemoteLocation(root, "account")
        val transport = fakeTransport { chain ->
            assertEquals("http", chain.request().url.scheme)
            chain.response(207, davResponse().toResponseBody())
        }
        assertTrue(transport.list(source, account).isEmpty())
        assertEquals(source, RemoteLocation.fromId(source.id))
        assertThrows(IOException::class.java) { parseRemoteUrl("http://dav.example/archive.cbz") }
        assertEquals("t", parseRemoteUrl("https://dav.example/archive.cbz?token=t").queryParameter("token"))
    }

    @Test
    fun davNeverSendsCredentialsToAnotherOriginOrDropsToHttp() {
        val root = webDavRoot("https://dav.example/", "comics")
        val account = RemoteAccount(root, "reader", "local-password")
        val source = RemoteLocation(root, "account")
        val redirects = listOf(
            "http://dav.example/comics/new/",
            "https://other.example/comics/new/",
            "https://dav.example/other/comics/new/",
        )
        for (destination in redirects) {
            val requests = CopyOnWriteArrayList<Request>()
            val transport = fakeTransport { chain ->
                requests.add(chain.request())
                chain.response(302, headers = mapOf("Location" to destination))
            }
            assertThrows(IOException::class.java) { runBlocking { transport.list(source, account) } }
            assertEquals(listOf(root), requests.map { it.url })
        }
    }

    @Test
    fun conditionalGetCanReuseCompleteFileWithSameVersion() = runBlocking {
        val source = RemoteLocation(parseRemoteUrl("https://dav.example/volume.cbz?token=opaque"))
        val contents = "package bytes"
        val tag = "\"version-1\""
        val progress = CopyOnWriteArrayList<RemoteDownloadProgress>()
        val transport = fakeTransport { chain ->
            assertNull(chain.request().header("Authorization"))
            val conditional = chain.request().header("If-None-Match")
            if (conditional == null) {
                chain.response(200, contents.toResponseBody(), headers = mapOf("ETag" to tag))
            } else {
                assertEquals(tag, conditional)
                chain.response(304)
            }
        }
        val first = directory.newFile()
        val file = transport.download(source, null, first, null, null, progress::add)
        assertEquals(contents, first.readText())
        assertEquals(tag, file?.version?.etag)
        assertEquals(remoteDigest(contents), file?.version?.sha256)
        assertEquals(RemoteDownloadProgress(contents.length.toLong(), contents.length.toLong()), progress.last())

        val second = directory.newFile()
        assertNull(transport.download(source, null, second, file, null) {})
        assertEquals(0L, second.length())
    }

    @Test
    fun conflictingDavLengthDisablesConditionalRequestAndRejectsAnUnexpected304() {
        val account = RemoteAccount(webDavRoot("https://dav.example/", "comics"), "", "")
        val source = RemoteLocation(parseRemoteUrl("https://dav.example/comics/volume.cbz"), "account")
        val contents = "old archive"
        val previous = RemoteFileInfo(
            RemoteVersion(etag = "\"old\"", length = contents.length.toLong(), sha256 = remoteDigest(contents)),
            remoteDigest(source.url.toString()),
        )
        val expected = RemoteVersion(length = contents.length.toLong() + 1)
        lateinit var request: Request
        val transport = fakeTransport { chain ->
            request = chain.request()
            chain.response(304)
        }
        val target = directory.newFile()

        assertThrows(IOException::class.java) {
            runBlocking { transport.download(source, account, target, previous, expected) {} }
        }
        assertNull(request.header("If-None-Match"))
        assertNull(request.header("If-Modified-Since"))
        assertFalse(target.exists())
    }

    @Test
    fun changedPageCannotReplaceThePinnedVersion() {
        val source = RemoteLocation(parseRemoteUrl("https://dav.example/pictures/42.png"))
        val expected = RemoteVersion(etag = "\"old\"")
        val transport = fakeTransport { chain ->
            assertEquals(expected.etag, chain.request().header("If-Match"))
            chain.response(200, "new image bytes".toResponseBody(), headers = mapOf("ETag" to "\"new\""))
        }
        val target = directory.newFile()
        assertThrows(RemoteContentChanged::class.java) {
            runBlocking { transport.download(source, null, target, null, expected) {} }
        }
        assertFalse(target.exists())
    }

    @Test
    fun cancellingDownloadCancelsCallAndRemovesTemporaryFile() = runBlocking {
        val source = RemoteLocation(parseRemoteUrl("https://dav.example/volume.zip"))
        val firstRead = CompletableDeferred<Unit>()
        val sourceClosed = CompletableDeferred<Unit>()
        val observedCancellation = CompletableDeferred<Unit>()
        val transport = fakeTransport { chain ->
            val cancellableBody = object : ResponseBody() {
                private val data = object : Source {
                    private var sentFirstChunk = false
                    override fun read(sink: Buffer, byteCount: Long): Long {
                        if (!sentFirstChunk) {
                            sentFirstChunk = true
                            firstRead.complete(Unit)
                            val size = byteCount.coerceAtMost(4096).toInt()
                            sink.write(ByteArray(size))
                            return size.toLong()
                        }
                        while (!chain.call().isCanceled()) Thread.sleep(10)
                        observedCancellation.complete(Unit)
                        throw InterruptedIOException("cancelled")
                    }

                    override fun timeout(): Timeout = Timeout.NONE
                    override fun close() { sourceClosed.complete(Unit) }
                }.buffer()

                override fun contentType() = null
                override fun contentLength() = -1L
                override fun source(): BufferedSource = data
            }
            chain.response(200, cancellableBody)
        }
        val target = directory.newFile()
        val job = launch { transport.download(source, null, target, null, null) {} }
        withTimeout(5000) {
            firstRead.await()
            job.cancelAndJoin()
            observedCancellation.await()
            sourceClosed.await()
        }
        assertFalse(target.exists())
    }

    @Test
    fun cancellingQueuedDownloadRemovesTemporaryFileBeforeAnyCallback() = runBlocking {
        val dispatcher = okhttp3.Dispatcher().apply { maxRequests = 1 }
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CountDownLatch(1)
        val transport = RemoteTransport(OkHttpClient.Builder().dispatcher(dispatcher).addInterceptor { chain ->
            if (chain.request().url.encodedPath == "/active.zip") {
                firstStarted.complete(Unit)
                check(releaseFirst.await(10, TimeUnit.SECONDS))
            }
            chain.response(200, "archive".toResponseBody())
        }.build())
        val firstSource = RemoteLocation(parseRemoteUrl("https://dav.example/active.zip"))
        val queuedSource = RemoteLocation(parseRemoteUrl("https://dav.example/queued.zip"))
        val firstTarget = directory.newFile()
        val queuedTarget = directory.newFile()
        val first = launch { transport.download(firstSource, null, firstTarget, null, null) {} }
        try {
            withTimeout(5000) { firstStarted.await() }
            val queued = launch(start = CoroutineStart.UNDISPATCHED) {
                transport.download(queuedSource, null, queuedTarget, null, null) {}
            }
            assertEquals(1, dispatcher.queuedCallsCount())
            withTimeout(5000) { queued.cancelAndJoin() }
            assertFalse(queuedTarget.exists())
        } finally {
            releaseFirst.countDown()
        }
        first.join()
        assertEquals("archive", firstTarget.readText())
    }

    @Test
    fun cancellingAfterBodyBeforeDeliveryDiscardsCompleteTemporaryFile() = runBlocking {
        val source = RemoteLocation(parseRemoteUrl("https://dav.example/volume.zip"))
        val releaseBody = CountDownLatch(1)
        val deliveries = LinkedBlockingQueue<Runnable>()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                deliveries.add(block)
            }
        }
        val transport = fakeTransport { chain ->
            check(releaseBody.await(5, TimeUnit.SECONDS))
            chain.response(200, "complete archive".toResponseBody())
        }
        val target = directory.newFile()
        val job = launch(dispatcher, start = CoroutineStart.UNDISPATCHED) {
            transport.download(source, null, target, null, null) {}
        }
        releaseBody.countDown()
        val deliverResponse = deliveries.poll(5, TimeUnit.SECONDS)
        try {
            assertEquals("complete archive", target.readText())
        } finally {
            job.cancel()
            deliverResponse?.run()
            while (!job.isCompleted) {
                checkNotNull(deliveries.poll(5, TimeUnit.SECONDS)) { "Cancelled download did not finish" }.run()
            }
        }
        assertFalse(target.exists())
    }

    private fun fakeTransport(simulate: (Interceptor.Chain) -> Response): RemoteTransport =
        RemoteTransport(OkHttpClient.Builder().addInterceptor(simulate).build())

    private fun Interceptor.Chain.response(
        status: Int,
        body: ResponseBody = "".toResponseBody(),
        headers: Map<String, String> = emptyMap(),
    ): Response = Response.Builder().request(request()).protocol(Protocol.HTTP_1_1).code(status).message("fixture")
        .body(body).apply { headers.forEach { (name, value) -> header(name, value) } }.build()

    private fun davResponse(vararg entries: String): String =
        "<D:multistatus xmlns:D=\"DAV:\">${entries.joinToString("")}</D:multistatus>"

    private fun davFile(href: String, properties: String = ""): String = """<D:response><D:href>$href</D:href>
        <D:propstat><D:prop>$properties</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>"""
}
