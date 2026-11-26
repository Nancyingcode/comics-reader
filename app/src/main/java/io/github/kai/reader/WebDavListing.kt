package io.github.kai.reader

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import javax.xml.parsers.ParserConfigurationException
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.ext.DefaultHandler2

internal fun parseDavListing(input: InputStream, folder: RemoteLocation, account: RemoteAccount): List<RemoteDavEntry> {
    val handler = DavMultistatusHandler()
    try {
        val factory = SAXParserFactory.newInstance().apply { isNamespaceAware = true }
        val parser = factory.newSAXParser().xmlReader
        // 拒绝 DTD 能防止外部实体以及内部实体展开。网络目录描述不需要 DTD。
        parser.setProperty("http://xml.org/sax/properties/lexical-handler", handler)
        parser.contentHandler = handler
        parser.entityResolver = handler
        parser.errorHandler = handler
        parser.parse(InputSource(DavListingInput(input)))
    } catch (failure: SAXException) {
        throw IOException("服务器返回的 WebDAV 目录格式无效。", failure)
    } catch (failure: ParserConfigurationException) {
        throw IOException("无法读取 WebDAV 目录。", failure)
    }
    if (!handler.isMultistatus) throw IOException("服务器未返回 WebDAV 目录。")

    val parent = folder.url.asRemoteFolder()
    val parentSegments = parent.folderSegments()
    return handler.entries.mapNotNull { entry ->
        val href = entry.href ?: return@mapNotNull null
        val url = parent.resolve(href)?.takeIf(account::permits) ?: return@mapNotNull null
        val segments = url.folderSegments()
        val isChild = segments.size == parentSegments.size + 1 && segments.startsWithSegments(parentSegments)
        if (!isChild) return@mapNotNull null

        val pathName = segments.last()
        val name = entry.displayName?.takeIf { it.isNotBlank() && it.none(Char::isISOControl) } ?: pathName
        if (isIgnoredSourceName(pathName) || isIgnoredSourceName(name)) return@mapNotNull null
        val isDirectory = entry.isCollection || url.encodedPath.endsWith('/')
        val location = folder.copy(url = if (isDirectory) url.asRemoteFolder() else url)
        val version = RemoteVersion(entry.etag.asEntityTag(), entry.modified.asModifiedDate(), entry.length)
        RemoteDavEntry(location, name, isDirectory, entry.mime, version.takeIf { it.hasValidator || it.length != null })
    }.distinctBy { it.location.url.folderSegments() }
        .sortedWith { left, right ->
            val nameOrder = compareImageNames(left.name, right.name)
            if (nameOrder != 0) nameOrder else left.location.id.compareTo(right.location.id)
        }
}

private class DavMultistatusHandler : DefaultHandler2() {
    val entries = mutableListOf<DavXmlEntry>()
    var isMultistatus = false
        private set

    private val path = mutableListOf<String>()
    private val text = StringBuilder()
    private var response: DavXmlEntry? = null
    private var properties: DavXmlEntry? = null

    override fun startDTD(name: String?, publicId: String?, systemId: String?) {
        throw SAXException("A WebDAV directory cannot contain a DTD")
    }

    override fun resolveEntity(publicId: String?, systemId: String?): InputSource {
        throw SAXException("A WebDAV directory cannot resolve external entities")
    }

    override fun resolveEntity(name: String?, publicId: String?, baseURI: String?, systemId: String?): InputSource {
        throw SAXException("A WebDAV directory cannot resolve external entities")
    }

    override fun startElement(uri: String, localName: String, qName: String, attributes: org.xml.sax.Attributes) {
        if (path.size >= 32) throw SAXException("Too many XML nesting levels")
        path.add(if (uri == "DAV:") localName else "")
        when {
            path == multistatus -> isMultistatus = true
            path == responsePath -> response = DavXmlEntry()
            path == propstat -> properties = DavXmlEntry()
            path == collection -> properties?.isCollection = true
        }
        text.clear()
    }

    override fun characters(ch: CharArray, start: Int, length: Int) {
        if (path.size in 3..5) {
            if (text.length + length > 16_384) throw SAXException("WebDAV property is too long")
            text.append(ch, start, length)
        }
    }

    override fun endElement(uri: String, localName: String, qName: String) {
        val value = text.toString().trim()
        when {
            path == href -> response?.href = value.ifEmpty { null }
            path == responseStatus -> response?.status = statusCode(value)
            path == propstatStatus -> properties?.status = statusCode(value)
            path == propstat -> {
                properties?.takeIf { it.status == 200 }?.let { response?.merge(it) }
                properties = null
            }
            path == responsePath -> {
                response?.takeIf { it.status == null || it.status == 200 }?.let { entries.add(it) }
                response = null
            }
            path.size == 5 && path.subList(0, 4) == prop -> {
                properties?.apply {
                    when (path.last()) {
                        "displayname" -> displayName = value.ifEmpty { null }
                        "getcontenttype" -> mime = value.substringBefore(';').trim().lowercase().ifEmpty { null }
                        "getcontentlength" -> length = value.toLongOrNull()?.takeIf { it >= 0 }
                        "getetag" -> etag = value.ifEmpty { null }
                        "getlastmodified" -> modified = value.ifEmpty { null }
                    }
                }
            }
        }
        path.removeAt(path.lastIndex)
        text.clear()
    }

    private companion object {
        val multistatus = listOf("multistatus")
        val responsePath = multistatus + "response"
        val href = responsePath + "href"
        val responseStatus = responsePath + "status"
        val propstat = responsePath + "propstat"
        val propstatStatus = propstat + "status"
        val prop = propstat + "prop"
        val collection = prop + listOf("resourcetype", "collection")
        val statusPattern = Regex("HTTP/\\d+(?:\\.\\d+)?\\s+(\\d{3})(?:\\s+.*)?")

        fun statusCode(value: String): Int? = statusPattern.matchEntire(value)?.groupValues?.get(1)?.toIntOrNull()
    }
}

private class DavXmlEntry {
    var href: String? = null
    var status: Int? = null
    var displayName: String? = null
    var mime: String? = null
    var isCollection = false
    var length: Long? = null
    var etag: String? = null
    var modified: String? = null

    fun merge(incoming: DavXmlEntry) {
        displayName = incoming.displayName ?: displayName
        mime = incoming.mime ?: mime
        isCollection = isCollection || incoming.isCollection
        length = incoming.length ?: length
        etag = incoming.etag ?: etag
        modified = incoming.modified ?: modified
    }
}

private class DavListingInput(input: InputStream) : FilterInputStream(input) {
    private var bytes = 0L

    override fun read(): Int = super.read().also { value ->
        if (value >= 0) count(1)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = super.read(buffer, offset, length).also { read ->
        if (read > 0) count(read)
    }

    private fun count(read: Int) {
        bytes += read
        if (bytes > 16 * 1024 * 1024) throw IOException("WebDAV 目录过大，请先打开其中的子目录。")
    }
}
