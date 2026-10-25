package com.xander.files.data.net.webdav

import com.xander.files.data.net.ClosingInputStream
import com.xander.files.data.net.StreamingUpload
import com.xander.files.domain.fs.FileEntry
import com.xander.files.domain.fs.FsException
import com.xander.files.domain.fs.FsPath
import com.xander.files.domain.fs.SpaceInfo
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.source
import org.w3c.dom.Element
import java.io.InputStream
import java.io.OutputStream
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.xml.parsers.DocumentBuilderFactory

/** Minimal RFC 4918 client: PROPFIND, GET (Range), PUT (streamed), MKCOL, MOVE, COPY, DELETE, PROPPATCH mtime. */
class WebDavClient(private val http: OkHttpClient, baseUrl: String) {
    private val base: HttpUrl = baseUrl.trimEnd('/').plus("/").toHttpUrl()

    fun url(path: String, dir: Boolean = false): HttpUrl {
        val b = base.newBuilder()
        path.split('/').filter { it.isNotEmpty() }.forEach { b.addPathSegment(it) }
        if (dir) b.addPathSegment("")
        return b.build()
    }

    private val propfindBody = """<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns"><d:prop>
<d:resourcetype/><d:getcontentlength/><d:getlastmodified/><d:getcontenttype/><d:creationdate/>
<d:quota-available-bytes/><d:quota-used-bytes/><oc:fileid/>
</d:prop></d:propfind>"""

    data class DavItem(val href: String, val isDir: Boolean, val size: Long, val modified: Long, val contentType: String?, val quotaFree: Long?, val quotaUsed: Long?, val fileId: String?)

    private fun exec(req: Request, path: FsPath): Response {
        val r = try { http.newCall(req).execute() } catch (e: Exception) { throw FsException.wrap(e, path) }
        if (r.isSuccessful || r.code == 207) return r
        r.close()
        throw when (r.code) {
            401 -> FsException.AuthRequired(path.authority)
            403 -> FsException.PermissionDenied(path)
            404 -> FsException.NotFound(path)
            405, 409 -> if (req.method == "MKCOL") FsException.AlreadyExists(path) else FsException.Generic("WebDAV ${r.code}")
            412 -> FsException.AlreadyExists(path)
            507 -> FsException.OutOfSpace()
            else -> FsException.Generic("WebDAV error ${r.code} ${r.message}")
        }
    }

    fun propfind(path: FsPath, depth: Int): List<DavItem> {
        val req = Request.Builder().url(url(path.path, dir = depth > 0 || path.isRoot))
            .method("PROPFIND", propfindBody.toRequestBody("application/xml; charset=utf-8".toMediaType()))
            .header("Depth", depth.toString()).build()
        exec(req, path).use { r -> return parse(r.body!!.byteStream()) }
    }

    private fun parse(xml: InputStream): List<DavItem> {
        val f = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val doc = f.newDocumentBuilder().parse(xml)
        val responses = doc.getElementsByTagNameNS("DAV:", "response")
        val out = ArrayList<DavItem>()
        for (i in 0 until responses.length) {
            val r = responses.item(i) as Element
            val href = r.text("href") ?: continue
            val okProp = (0 until r.getElementsByTagNameNS("DAV:", "propstat").length).map { r.getElementsByTagNameNS("DAV:", "propstat").item(it) as Element }
                .firstOrNull { it.text("status")?.contains(" 200") != false } ?: continue
            val isDir = okProp.getElementsByTagNameNS("DAV:", "collection").length > 0
            out += DavItem(
                href = href, isDir = isDir,
                size = okProp.text("getcontentlength")?.toLongOrNull() ?: 0,
                modified = okProp.text("getlastmodified")?.let(::parseDate) ?: 0,
                contentType = okProp.text("getcontenttype"),
                quotaFree = okProp.text("quota-available-bytes")?.toLongOrNull(),
                quotaUsed = okProp.text("quota-used-bytes")?.toLongOrNull(),
                fileId = okProp.getElementsByTagNameNS("http://owncloud.org/ns", "fileid").item(0)?.textContent,
            )
        }
        return out
    }

    private fun Element.text(local: String): String? = getElementsByTagNameNS("DAV:", local).item(0)?.textContent?.trim()?.takeIf { it.isNotEmpty() }

    private fun parseDate(s: String): Long = runCatching {
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT") }.parse(s)!!.time
    }.getOrDefault(0)

    private fun nameOf(href: String): String = URLDecoder.decode(href.trimEnd('/').substringAfterLast('/'), "UTF-8")

    private fun samePath(href: String, path: FsPath): Boolean {
        val decoded = URLDecoder.decode(href, "UTF-8").trimEnd('/')
        val want = URLDecoder.decode(url(path.path).encodedPath, "UTF-8").trimEnd('/')
        return decoded.endsWith(want) || decoded == want || (path.isRoot && decoded == URLDecoder.decode(base.encodedPath, "UTF-8").trimEnd('/'))
    }

    fun list(dir: FsPath): List<FileEntry> {
        val items = propfind(dir, 1)
        return items.filterNot { samePath(it.href, dir) }.map { it.toEntry(dir.child(nameOf(it.href))) }
    }

    fun stat(path: FsPath): FileEntry = propfind(path, 0).firstOrNull()?.toEntry(path) ?: throw FsException.NotFound(path)

    private fun DavItem.toEntry(p: FsPath) = FileEntry(p, isDir, if (isDir) 0 else size, modified, mimeType = if (isDir) null else contentType, backendId = fileId)

    fun quota(path: FsPath): SpaceInfo? = runCatching {
        val it = propfind(path, 0).first()
        val free = it.quotaFree ?: return null
        val used = it.quotaUsed ?: 0
        if (free < 0) null else SpaceInfo(free + used, free)
    }.getOrNull()

    fun get(path: FsPath, offset: Long): InputStream {
        val b = Request.Builder().url(url(path.path)).get()
        if (offset > 0) b.header("Range", "bytes=$offset-")
        val r = exec(b.build(), path)
        val body = r.body!!
        var s = body.byteStream()
        if (offset > 0 && r.code == 200) { var left = offset; while (left > 0) { val k = s.skip(left); if (k <= 0) break; left -= k } }
        return ClosingInputStream(s) { r.close() }
    }

    fun put(path: FsPath, size: Long): OutputStream = StreamingUpload(path.name) { input ->
        val body = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength() = if (size >= 0) size else -1
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) { sink.writeAll(input.source()) }
        }
        exec(Request.Builder().url(url(path.path)).put(body).build(), path).close()
    }

    fun mkcol(path: FsPath) = exec(Request.Builder().url(url(path.path, dir = true)).method("MKCOL", null).build(), path).close()

    fun delete(path: FsPath) = exec(Request.Builder().url(url(path.path)).delete().build(), path).close()

    fun move(src: FsPath, dst: FsPath, overwrite: Boolean = false, copy: Boolean = false, dir: Boolean = false) =
        exec(Request.Builder().url(url(src.path, dir)).method(if (copy) "COPY" else "MOVE", null)
            .header("Destination", url(dst.path, dir).toString()).header("Overwrite", if (overwrite) "T" else "F")
            .header("Depth", "infinity").build(), src).close()

    fun setModified(path: FsPath, time: Long) {
        // Nextcloud/ownCloud honour X-OC-Mtime on PUT; standard servers accept PROPPATCH of lastmodified (often ignored).
        val fmt = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT") }
        val body = """<?xml version="1.0"?><d:propertyupdate xmlns:d="DAV:"><d:set><d:prop><d:getlastmodified>${fmt.format(time)}</d:getlastmodified></d:prop></d:set></d:propertyupdate>"""
        runCatching { exec(Request.Builder().url(url(path.path)).method("PROPPATCH", body.toRequestBody("application/xml".toMediaType())).build(), path).close() }
    }
}
