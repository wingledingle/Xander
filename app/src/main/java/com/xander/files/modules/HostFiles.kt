package com.xander.files.modules

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.WebResourceResponse
import com.xander.files.domain.fs.FileSystemRegistry
import com.xander.files.domain.fs.FsPath
import com.xander.files.domain.fs.MimeTypes
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Where a file handed to a web module lives. */
sealed interface HostSource {
    data class Fs(val path: FsPath) : HostSource
    data class Content(val uri: Uri) : HostSource
    data class Local(val file: File) : HostSource
}

data class HostFile(val token: String, val source: HostSource, val name: String, val mime: String, val size: Long) {
    val url: String get() = "${WebModuleHost.ORIGIN}/host/file/$token/${Uri.encode(name)}"
    fun toJson(): JSONObject = JSONObject().put("url", url).put("name", name).put("mime", mime).put("size", size).put("token", token)
        .put("path", (source as? HostSource.Fs)?.path?.toString() ?: JSONObject.NULL)
}

/**
 * Files a web module may read, addressed by unguessable tokens (the page never sees real paths it wasn't given).
 * Serves them to the WebView with HTTP Range support so <video> can seek and long files stream.
 */
class HostFiles(private val context: Context, private val registry: FileSystemRegistry) {
    private val files = ConcurrentHashMap<String, HostFile>()

    fun get(token: String): HostFile? = files[token]

    private fun add(source: HostSource, name: String, mime: String?, size: Long): HostFile {
        val f = HostFile(UUID.randomUUID().toString().replace("-", ""), source, name, mime ?: MimeTypes.fromName(name) ?: "application/octet-stream", size)
        files[f.token] = f
        return f
    }

    suspend fun register(path: FsPath): HostFile {
        val e = registry.provider(path).stat(path)
        return add(HostSource.Fs(path), e.name, e.mimeType, e.size)
    }

    fun register(uri: Uri): HostFile {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        var size = -1L
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.let { name = it }
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        }
        return add(HostSource.Content(uri), name, context.contentResolver.getType(uri), size)
    }

    fun register(file: File, mime: String): HostFile = add(HostSource.Local(file), file.name, mime, file.length())

    /** Opens the file at [offset]. Blocking; callers are on WebView or IO threads. */
    fun open(f: HostFile, offset: Long): InputStream = when (val s = f.source) {
        is HostSource.Local -> FileInputStream(s.file).also { it.channel.position(offset) }
        is HostSource.Fs -> runBlocking { registry.provider(s.path).openInput(s.path, offset) }
        is HostSource.Content -> {
            val afd = context.contentResolver.openAssetFileDescriptor(s.uri, "r") ?: error("cannot open ${f.name}")
            val input = afd.createInputStream()
            val ch = (input as? FileInputStream)?.channel
            if (ch != null) ch.position(afd.startOffset.coerceAtLeast(0) + offset) else input.skipFully(offset)
            input
        }
    }

    /** Response for GET /host/file/<token>[/name] with an optional Range header. */
    fun respond(token: String, range: String?): WebResourceResponse {
        val f = files[token] ?: return WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), "".byteInputStream())
        return try {
            val r = parseRange(range, f.size)
            if (r == null) {
                val headers = mutableMapOf("Accept-Ranges" to "bytes", "Cache-Control" to "no-store")
                if (f.size >= 0) headers["Content-Length"] = f.size.toString()
                WebResourceResponse(f.mime, null, 200, "OK", headers, open(f, 0))
            } else {
                val len = r.last - r.first + 1
                WebResourceResponse(f.mime, null, 206, "Partial Content", mapOf(
                    "Accept-Ranges" to "bytes", "Content-Length" to len.toString(), "Content-Range" to "bytes ${r.first}-${r.last}/${f.size}", "Cache-Control" to "no-store",
                ), LimitedInputStream(open(f, r.first), len))
            }
        } catch (e: Exception) {
            WebResourceResponse("text/plain", "utf-8", 500, "Error", emptyMap(), (e.message ?: "error").byteInputStream())
        }
    }

    companion object {
        /** Parses `bytes=a-b`, `bytes=a-` and `bytes=-n`. Null when absent, unsatisfiable or the size is unknown. */
        fun parseRange(header: String?, size: Long): LongRange? {
            if (header == null || size <= 0) return null
            val m = Regex("""bytes=(\d*)-(\d*)""").find(header.trim()) ?: return null
            val (a, b) = m.destructured
            val (start, end) = when {
                a.isEmpty() && b.isEmpty() -> return null
                a.isEmpty() -> (size - b.toLong()).coerceAtLeast(0) to size - 1
                else -> a.toLong() to (b.toLongOrNull()?.coerceAtMost(size - 1) ?: (size - 1))
            }
            return if (start in 0..end && start < size) start..end else null
        }
    }
}

private fun InputStream.skipFully(n: Long) {
    var left = n
    while (left > 0) {
        val s = skip(left)
        if (s <= 0) { if (read() < 0) return; left-- } else left -= s
    }
}

/** Reads at most [limit] bytes from [input]. */
class LimitedInputStream(input: InputStream, private var limit: Long) : FilterInputStream(input) {
    override fun read(): Int = if (limit <= 0) -1 else super.read().also { if (it >= 0) limit-- }
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (limit <= 0) return -1
        val n = super.read(b, off, minOf(len.toLong(), limit).toInt())
        if (n > 0) limit -= n
        return n
    }
    override fun available(): Int = minOf(super.available().toLong(), limit).toInt()
}
