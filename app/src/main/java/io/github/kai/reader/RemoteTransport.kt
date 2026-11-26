package io.github.kai.reader

import java.io.File
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.ProtocolException
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

data class RemoteDownloadProgress(val downloadedBytes: Long, val totalBytes: Long?)

internal data class RemoteLocation(val url: HttpUrl, val accountId: String? = null) {
    val id: String get() = "$PREFIX${accountId.orEmpty()}:${url.toString().encodeRemoteField()}"
    val displayPath: String get() = url.pathSegments.joinToString("/", prefix = "/")
    val displayName: String get() = url.pathSegments.lastOrNull { it.isNotEmpty() } ?: url.host
    val isWebDav: Boolean get() = accountId != null

    companion object {
        private const val PREFIX = "kai-remote:"

        fun fromId(id: String): RemoteLocation? {
            if (!id.startsWith(PREFIX)) return null
            val parts = id.removePrefix(PREFIX).split(':', limit = 2)
            if (parts.size != 2) return null
            return try {
                val account = parts[0].ifEmpty { null }
                val url = parts[1].decodeRemoteField().toHttpUrlOrNull()?.takeIf { it.canRead(allowHttp = account != null) }
                    ?: return null
                RemoteLocation(url, account)
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }
}

internal data class RemoteDavEntry(
    val location: RemoteLocation,
    val name: String,
    val isDirectory: Boolean,
    val mimeType: String? = null,
    val version: RemoteVersion? = null,
) {
    val isArchive: Boolean get() = !isDirectory && location.displayName.isComicArchive()
    val isImage: Boolean get() = !isDirectory && isImageFile(location.displayName, mimeType)
}

internal data class RemoteAccount(val root: HttpUrl, val username: String, val password: String) {
    fun permits(url: HttpUrl): Boolean = url.canRead(allowHttp = true) && url.isSameOrigin(root) &&
        url.query == null && url.safePath() && url.folderSegments().startsWithSegments(root.folderSegments())
}

internal data class RemoteVersion(
    val etag: String? = null,
    val modified: String? = null,
    val length: Long? = null,
    val sha256: String? = null,
    val rangeUrlKey: String? = null,
) {
    val hasValidator: Boolean get() = etag != null || modified != null
    val canMatch: Boolean get() = hasValidator || sha256 != null

    fun encode(): String = (listOf(etag, modified, length?.toString(), sha256) + listOfNotNull(rangeUrlKey))
        .joinToString(":") { it.orEmpty().encodeRemoteField() }

    fun matches(actual: RemoteVersion): Boolean {
        if (rangeUrlKey != null && rangeUrlKey != actual.rangeUrlKey) return false
        if (sha256 != null) return sha256 == actual.sha256
        if (etag != null) return etag == actual.etag && (length == null || length == actual.length)
        return modified != null && modified == actual.modified && (length == null || length == actual.length)
    }

    fun changed(result: RemoteVersion): Boolean {
        if (rangeUrlKey != null && result.rangeUrlKey != null && rangeUrlKey != result.rangeUrlKey) return true
        if (sha256 != null && result.sha256 != null && sha256 != result.sha256) return true
        if (etag != null && result.etag != null && etag != result.etag) return true
        if (length != null && result.length != null && length != result.length) return true
        return etag == null && modified != null && result.modified != null && modified != result.modified
    }

    companion object {
        fun decode(value: String?): RemoteVersion? {
            if (value == null) return null
            val parts = value.split(':')
            if (parts.size !in 4..5) return null
            return try {
                val fields = parts.map { it.decodeRemoteField().ifEmpty { null } }
                val (etag, date, length, hash) = fields
                val urlKey = fields.getOrNull(4)
                if (parts.size == 5 && urlKey?.isRemoteDigest() != true) return null
                RemoteVersion(etag, date, length?.toLongOrNull(), hash, urlKey)
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }
}

internal data class RemoteFileInfo(val version: RemoteVersion, val effectiveUrlKey: String) {
    fun matches(expected: RemoteVersion): Boolean = expected.matches(version.copy(rangeUrlKey = effectiveUrlKey))
}

internal class RemoteUnavailable(cause: IOException? = null) : IOException("无法连接远程来源，请检查网络后重试。", cause)

internal class RemoteContentChanged : IOException("远程内容已更改，请重新打开章节。")

internal class RemoteMissing(message: String) : IOException(message)

internal class RemoteRangeUnsupported : IOException("服务器未提供可验证的分段响应。")

internal data class RemoteArchiveTail(
    val location: RemoteLocation,
    val version: RemoteVersion,
    val start: Long,
    val bytes: ByteArray,
)

internal class RemoteTransport(client: OkHttpClient = OkHttpClient()) {
    private val client = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()

    suspend fun list(folder: RemoteLocation, account: RemoteAccount): List<RemoteDavEntry> =
        read(folder, account, { url ->
            Request.Builder().url(url)
                .header("Depth", "1")
                .method("PROPFIND", propfind.toRequestBody("application/xml; charset=utf-8".toMediaType()))
        }) { response ->
            if (response.code != 207) {
                response.checkStatus()
                throw IOException("服务器未提供有效的 WebDAV 目录列表。")
            }
            val body = response.body ?: throw IOException("服务器返回空的 WebDAV 目录列表。")
            body.byteStream().use { parseDavListing(it, folder.copy(url = response.request.url), account) }
        }

    suspend fun archiveTail(
        location: RemoteLocation,
        account: RemoteAccount?,
        count: Int,
        expected: RemoteVersion? = null,
    ): RemoteArchiveTail = readRange(location, account, RequestedRange(null, count), expected) { response, range, version, input ->
        RemoteArchiveTail(location.copy(url = response.request.url), version, range.first, input.readRangeBytes(range.count.toInt()))
    }

    suspend fun <T> archiveRange(
        location: RemoteLocation,
        account: RemoteAccount?,
        first: Long,
        count: Int,
        expected: RemoteVersion,
        discard: (() -> Unit)? = null,
        consume: (InputStream) -> T,
    ): T = readRange(location, account, RequestedRange(first, count), expected, discard) { _, _, _, input -> consume(input) }

    private suspend fun <T> readRange(
        location: RemoteLocation,
        account: RemoteAccount?,
        requested: RequestedRange,
        expected: RemoteVersion?,
        discard: (() -> Unit)? = null,
        consume: (Response, AcceptedRange, RemoteVersion, InputStream) -> T,
    ): T {
        val context = currentCoroutineContext()
        return read(location, account, { url ->
            val request = Request.Builder().url(url).header("Accept-Encoding", "identity").header("Range", requested.header)
            if (expected == null) request.header("Cache-Control", "no-cache")
            else if (expected.rangeUrlKey == remoteDigest(url.toString())) {
                request.header("If-Range", checkNotNull(expected.etag))
            }
            request
        }, discard) { response ->
            val effectiveUrlKey = remoteDigest(response.request.url.toString())
            val etag = response.headers.values("ETag").singleOrNull().asStrongEntityTag()
            if (expected != null) {
                if (effectiveUrlKey != expected.rangeUrlKey || response.code in changedRangeStatuses) throw RemoteContentChanged()
                if (response.code == 200 && etag != expected.etag) throw RemoteContentChanged()
            }
            if (response.code in unsupportedRangeStatuses) throw RemoteRangeUnsupported()
            response.checkStatus()
            if (response.code != 206) throw RemoteRangeUnsupported()
            if (expected != null && (etag != expected.etag || response.request.header("If-Range") != expected.etag)) {
                throw RemoteContentChanged()
            }
            if (etag == null) throw RemoteRangeUnsupported()

            val encodings = response.headers.values("Content-Encoding")
            if (encodings.isNotEmpty() && !encodings.singleOrNull().equals("identity", ignoreCase = true)) {
                throw RemoteRangeUnsupported()
            }
            val range = response.headers.values("Content-Range").singleOrNull()?.let(AcceptedRange::parse)
                ?: throw RemoteRangeUnsupported()
            if (expected != null && range.total != expected.length) throw RemoteContentChanged()
            if (!requested.matches(range)) throw RemoteRangeUnsupported()
            val body = response.body ?: throw RemoteRangeUnsupported()
            val declaredBytes = body.contentLength()
            val multipart = body.contentType()?.let { it.type == "multipart" && it.subtype == "byteranges" } == true
            if (multipart || declaredBytes >= 0 && declaredBytes != range.count) throw RemoteRangeUnsupported()

            val version = RemoteVersion(etag = etag, length = range.total, rangeUrlKey = effectiveUrlKey)
            val checked = CheckedRangeInput(body.byteStream(), range.count, context::ensureActive)
            val result = consume(response, range, version, checked)
            checked.verifyEnd()
            result
        }
    }

    suspend fun download(
        location: RemoteLocation,
        account: RemoteAccount?,
        target: File,
        cached: RemoteFileInfo?,
        expected: RemoteVersion?,
        onProgress: (RemoteDownloadProgress) -> Unit,
    ): RemoteFileInfo? {
        val candidate = cached?.takeIf { file ->
            expected == null || !expected.changed(file.version) && (!expected.canMatch || file.matches(expected))
        }
        return read(location, account, { url ->
            val builder = Request.Builder().url(url).header("Accept-Encoding", "identity")
            val sameCachedResource = candidate != null && candidate.effectiveUrlKey == remoteDigest(url.toString())
            if (sameCachedResource) {
                val version = checkNotNull(candidate).version
                if (version.etag != null) builder.header("If-None-Match", version.etag)
                else if (version.modified != null) builder.header("If-Modified-Since", version.modified)
            }
            if (expected?.etag?.startsWith('"') == true) builder.header("If-Match", expected.etag)
            else if (expected?.modified != null) builder.header("If-Unmodified-Since", expected.modified)
            builder
        }, discard = { target.delete() }) { response ->
            val effectiveUrlKey = remoteDigest(response.request.url.toString())
            if (response.code == 304) {
                val hasConditionalHeader = response.request.header("If-None-Match") != null ||
                    response.request.header("If-Modified-Since") != null
                if (candidate?.effectiveUrlKey != effectiveUrlKey || !hasConditionalHeader) {
                    throw IOException("服务器返回了无效的缓存校验结果，请重试。")
                }
                return@read null
            }
            if (response.code == 412) throw RemoteContentChanged()
            response.checkStatus()
            if (response.code != 200) throw IOException("服务器未返回完整的文件。")
            val encoding = response.header("Content-Encoding")
            if (encoding != null && !encoding.equals("identity", ignoreCase = true)) {
                throw IOException("服务器未返回文件的原始字节。")
            }
            val body = response.body ?: throw IOException("服务器返回空文件。")
            val responseVersion = RemoteVersion(
                etag = response.header("ETag").asEntityTag(),
                modified = response.header("Last-Modified").asModifiedDate(),
                length = body.contentLength().takeIf { it >= 0 },
            )
            if (expected?.changed(responseVersion) == true) throw RemoteContentChanged()
            val usedMatch = response.request.header("If-Match") != null
            val usedDate = response.request.header("If-Unmodified-Since") != null
            if (expected?.etag != null && responseVersion.etag == null && !usedMatch && !usedDate) throw RemoteContentChanged()
            val totalBytes = responseVersion.length ?: expected?.length
            val (bytes, hash) = saveBody(body.byteStream(), target, totalBytes, onProgress)
            val actual = RemoteVersion(
                etag = responseVersion.etag ?: expected?.etag?.takeIf { usedMatch },
                modified = responseVersion.modified ?: expected?.modified?.takeIf { usedMatch || usedDate },
                length = bytes,
                sha256 = hash,
            )
            if (expected?.changed(actual) == true) throw RemoteContentChanged()
            RemoteFileInfo(actual, effectiveUrlKey)
        }
    }

    private suspend fun <T> read(
        location: RemoteLocation,
        account: RemoteAccount?,
        request: (HttpUrl) -> Request.Builder,
        discard: (() -> Unit)? = null,
        consume: (Response) -> T,
    ): T {
        var url = location.url
        repeat(6) {
            if (!url.canRead(allowHttp = account != null) || account?.permits(url) == false) {
                throw IOException("远程直链须使用 HTTPS，WebDAV 地址须位于已连接的目录中。")
            }
            val builder = request(url)
            if (account != null && (account.username.isNotEmpty() || account.password.isNotEmpty())) {
                builder.header("Authorization", Credentials.basic(account.username, account.password, Charsets.UTF_8))
            }
            when (val result = awaitResponse(builder.build(), discard) { response ->
                if (response.code in redirectCodes) {
                    val nextUrl = response.header("Location")?.let(url::resolve)
                        ?: throw IOException("服务器返回的跳转地址无效。")
                    RemoteResponse.Redirect(nextUrl)
                } else {
                    RemoteResponse.Final(consume(response))
                }
            }) {
                is RemoteResponse.Redirect -> url = result.url
                is RemoteResponse.Final -> return result.value
            }
        }
        throw IOException("服务器返回过多的跳转。")
    }

    private suspend fun <T> awaitResponse(
        request: Request,
        discard: (() -> Unit)?,
        consume: (Response) -> T,
    ): T {
        val call = client.newCall(request)
        val cleanup = discard?.let(::ResponseFileCleanup)
        try {
            return suspendCancellableCoroutine<T> { continuation ->
                continuation.invokeOnCancellation { call.cancel() }
                if (!continuation.isActive) return@suspendCancellableCoroutine
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        val failure = when (e) {
                            is SSLHandshakeException, is SSLPeerUnverifiedException -> IOException("无法验证 HTTPS 服务器证书。", e)
                            is ProtocolException -> IOException("服务器返回了无效的 HTTP 响应。", e)
                            else -> RemoteUnavailable(e)
                        }
                        continuation.resumeWithException(failure)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        if (!continuation.isActive || cleanup?.claimBody() == false) {
                            response.close()
                            return
                        }
                        val result = try {
                            runCatching {
                                response.use {
                                    try {
                                        consume(it)
                                    } catch (failure: Throwable) {
                                        // 拒绝 Range 探测时中断完整响应，避免丢弃响应前仍传完整包。
                                        call.cancel()
                                        throw failure
                                    }
                                }
                            }
                        } finally {
                            cleanup?.finishBody()
                        }
                        result.fold(continuation::resume, continuation::resumeWithException)
                    }
                })
            }.also { currentCoroutineContext().ensureActive() }
        } catch (failure: Throwable) {
            cleanup?.discardAfterBody()
            throw failure
        }
    }

    private fun Response.checkStatus() {
        if (isSuccessful) return
        when (code) {
            401, 403 -> throw IOException("远程来源拒绝访问，请检查用户名、密码和目录权限。")
            404, 410 -> throw RemoteMissing("远程文件不存在或已移动，请刷新来源。")
            408, 429, in 500..599 -> throw RemoteUnavailable()
            else -> throw IOException("远程服务器返回 HTTP $code。")
        }
    }

    private companion object {
        val redirectCodes = setOf(301, 302, 303, 307, 308)
        val unsupportedRangeStatuses = setOf(400, 405, 416, 501)
        val changedRangeStatuses = setOf(404, 410, 412, 416)
        val propfind = """<?xml version="1.0" encoding="utf-8"?>
            <propfind xmlns="DAV:"><prop><resourcetype/><displayname/><getcontenttype/>
            <getcontentlength/><getetag/><getlastmodified/></prop></propfind>
        """.trimIndent()
    }
}

private class ResponseFileCleanup(private val discard: () -> Unit) {
    private val bodyClaimed = AtomicBoolean(false)
    private val bodyFinished = CompletableDeferred<Unit>()

    fun claimBody(): Boolean = bodyClaimed.compareAndSet(false, true)

    fun finishBody() {
        bodyFinished.complete(Unit)
    }

    suspend fun discardAfterBody() {
        // 取消先取得写入权时，无需等待仍在 OkHttp 队列中的回调。
        // 已开始写入则等回调关闭文件，避免 Windows 因打开的句柄拒绝删除。
        if (!claimBody()) withContext(NonCancellable) { bodyFinished.await() }
        discard()
    }
}

private data class RequestedRange(val first: Long?, val count: Int) {
    init {
        require(count > 0)
        require(first == null || first in 0..Long.MAX_VALUE - count)
    }

    val header: String = if (first == null) "bytes=-$count" else "bytes=$first-${first + count - 1}"

    fun matches(result: AcceptedRange): Boolean {
        val size = if (first == null) minOf(count.toLong(), result.total) else count.toLong()
        val start = first ?: result.total - size
        return result.first == start && result.count == size
    }
}

private data class AcceptedRange(val first: Long, val last: Long, val total: Long) {
    val count: Long get() = last - first + 1

    companion object {
        private val syntax = Regex("bytes ([0-9]{1,19})-([0-9]{1,19})/([0-9]{1,19})", RegexOption.IGNORE_CASE)

        fun parse(header: String): AcceptedRange? {
            val fields = syntax.matchEntire(header.trim())?.groupValues ?: return null
            val first = fields[1].toLongOrNull() ?: return null
            val last = fields[2].toLongOrNull() ?: return null
            val total = fields[3].toLongOrNull() ?: return null
            if (last < first || last >= total) return null
            return AcceptedRange(first, last, total)
        }
    }
}

private class CheckedRangeInput(
    private val input: InputStream,
    private var remaining: Long,
    private val checkCancellation: () -> Unit,
) : InputStream() {
    override fun read(): Int {
        if (remaining == 0L) return -1
        val byte = readBody { input.read() }
        if (byte < 0) throw RemoteRangeUnsupported()
        remaining--
        return byte
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return -1
        val count = readBody { input.read(buffer, offset, minOf(remaining, length.toLong()).toInt()) }
        if (count < 0) throw RemoteRangeUnsupported()
        remaining -= count
        return count
    }

    fun verifyEnd() {
        if (remaining != 0L || readBody { input.read() } != -1) throw RemoteRangeUnsupported()
    }

    private inline fun readBody(read: () -> Int): Int {
        checkCancellation()
        return try {
            read()
        } catch (_: ProtocolException) {
            throw RemoteRangeUnsupported()
        } catch (failure: IOException) {
            throw RemoteUnavailable(failure)
        }
    }
}

internal fun InputStream.readRangeBytes(count: Int): ByteArray {
    val bytes = ByteArray(count)
    var offset = 0
    while (offset < count) {
        val size = read(bytes, offset, count - offset)
        if (size < 0) throw EOFException("远程压缩包的分段数据不完整。")
        offset += size
    }
    return bytes
}

private sealed interface RemoteResponse<out T> {
    data class Redirect(val url: HttpUrl) : RemoteResponse<Nothing>
    data class Final<T>(val value: T) : RemoteResponse<T>
}

internal fun parseRemoteUrl(input: String, allowHttp: Boolean = false): HttpUrl =
    input.trim().toHttpUrlOrNull()?.takeIf { it.canRead(allowHttp) } ?: throw IOException(
        if (allowHttp) "请输入有效的 HTTP 或 HTTPS WebDAV 地址，用户名和密码请单独填写。"
        else "请输入有效的 HTTPS 压缩包链接。"
    )

internal fun HttpUrl.asRemoteFolder(): HttpUrl =
    if (encodedPath.endsWith('/')) this else newBuilder().addPathSegment("").build()

internal fun HttpUrl.safePath(): Boolean = pathSegments.none { segment ->
    segment == "." || segment == ".." || segment.any { it == '/' || it == '\\' || it.isISOControl() }
}

internal fun HttpUrl.isSameOrigin(other: HttpUrl): Boolean =
    scheme == other.scheme && host == other.host && port == other.port

internal fun HttpUrl.folderSegments(): List<String> =
    if (pathSegments.lastOrNull() == "") pathSegments.dropLast(1) else pathSegments

internal fun List<String>.startsWithSegments(prefix: List<String>): Boolean = take(prefix.size) == prefix

internal fun String?.asEntityTag(): String? = this?.trim()?.takeIf { value ->
    val tag = value.removePrefix("W/")
    tag.length >= 2 && tag.startsWith('"') && tag.endsWith('"') &&
        tag.substring(1, tag.lastIndex).all { it.code in 0x21..0x7e && it != '"' }
}

internal fun String?.asStrongEntityTag(): String? = asEntityTag()?.takeIf { it.length <= 1024 && it.startsWith('"') }

internal fun String?.asModifiedDate(): String? = this?.trim()?.takeIf { value ->
    value.length in 16..64 && value.endsWith(" GMT") && value.all { it.code in 0x20..0x7e }
}

internal fun remoteDigest(input: String): String =
    MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8)).remoteHex()

private fun HttpUrl.canRead(allowHttp: Boolean): Boolean =
    (isHttps || allowHttp && scheme == "http") && encodedUsername.isEmpty() && encodedPassword.isEmpty() && fragment == null

private fun String.encodeRemoteField(): String = Base64.getUrlEncoder().withoutPadding()
    .encodeToString(toByteArray(Charsets.UTF_8))

private fun String.decodeRemoteField(): String = Base64.getUrlDecoder().decode(this).toString(Charsets.UTF_8)

internal fun ByteArray.remoteHex(): String = joinToString("") { "%02x".format(it) }

private fun saveBody(
    input: InputStream,
    target: File,
    expectedBytes: Long?,
    onProgress: (RemoteDownloadProgress) -> Unit,
): Pair<Long, String> {
    val disk = checkNotNull(target.parentFile)
    if (expectedBytes != null && expectedBytes > disk.usableSpace) throw IOException("可用空间不足，无法下载远程文件。")
    val sha = MessageDigest.getInstance("SHA-256")
    var transferred = 0L
    var lastProgress = System.nanoTime()
    onProgress(RemoteDownloadProgress(0, expectedBytes))
    try {
        target.outputStream().buffered().use { output ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = try {
                    input.read(buffer)
                } catch (failure: IOException) {
                    throw RemoteUnavailable(failure)
                }
                if (count < 0) break
                transferred += count
                if (expectedBytes != null && transferred > expectedBytes) throw ProtocolException("远程文件的长度与声明不符。")
                sha.update(buffer, 0, count)
                output.write(buffer, 0, count)
                val now = System.nanoTime()
                if (now - lastProgress >= TimeUnit.MILLISECONDS.toNanos(80)) {
                    onProgress(RemoteDownloadProgress(transferred, expectedBytes))
                    lastProgress = now
                }
            }
        }
    } catch (failure: IOException) {
        if (failure is RemoteUnavailable || failure is ProtocolException) throw failure
        if (failure.isRemoteDiskFull(disk)) throw IOException("可用空间不足，无法下载远程文件。", failure)
        throw IOException("无法写入正在下载的远程文件。", failure)
    }
    if (transferred == 0L || expectedBytes != null && transferred != expectedBytes) {
        throw IOException("远程文件未下载完整，请重试。")
    }
    onProgress(RemoteDownloadProgress(transferred, expectedBytes ?: transferred))
    return transferred to sha.digest().remoteHex()
}

internal fun IOException.isRemoteDiskFull(directory: File): Boolean =
    directory.usableSpace == 0L || message?.contains("ENOSPC", ignoreCase = true) == true
