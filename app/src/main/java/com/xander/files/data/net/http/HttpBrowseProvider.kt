package com.xander.files.data.net.http

import com.xander.files.data.net.ClosingInputStream
import com.xander.files.data.net.ConnectionRepository
import com.xander.files.data.net.HttpClients
import com.xander.files.domain.fs.Capability
import com.xander.files.domain.fs.FileEntry
import com.xander.files.domain.fs.FileSystemProvider
import com.xander.files.domain.fs.FsException
import com.xander.files.domain.fs.FsPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.InputStream
import java.io.OutputStream
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Read-only browsing of HTTP directory listings (Apache/nginx/lighttpd autoindex, python http.server, routers). */
@Singleton
class HttpBrowseProvider @Inject constructor(private val connections: ConnectionRepository) : FileSystemProvider {
    override val scheme = FsPath.SCHEME_HTTP
    override fun capabilities(path: FsPath) = emptySet<Capability>()

    private val clients = ConcurrentHashMap<String, Pair<OkHttpClient, HttpUrl>>()
    init { connections.listeners += { clients.remove(it) } }

    private suspend fun client(id: String) = clients[id] ?: run {
        val c = connections.get(id)
        val proto = if (c.options.https) "https" else "http"
        val base = "$proto://${c.host}:${c.port}/".toHttpUrl()
        (HttpClients.forConnection(c.username, c.password, c.options.trustSelfSigned, c.options.timeoutSec) to base).also { clients[id] = it }
    }

    private fun url(base: HttpUrl, path: String, dir: Boolean) = base.newBuilder().apply {
        path.split('/').filter { it.isNotEmpty() }.forEach { addPathSegment(it) }
        if (dir) addPathSegment("")
    }.build()

    private val linkRe = Regex("""<a\s[^>]*href\s*=\s*["']([^"'#?]+)["'][^>]*>""", RegexOption.IGNORE_CASE)
    private val sizeRe = Regex("""(\d{1,2}-\w{3}-\d{4} \d{2}:\d{2}|\d{4}-\d{2}-\d{2} \d{2}:\d{2})\s+([\d.]+[KMGT]?|-)""")

    override suspend fun list(dir: FsPath): List<FileEntry> = withContext(Dispatchers.IO) {
        val (http, base) = client(dir.authority)
        val u = url(base, dir.path, true)
        val html = try {
            http.newCall(Request.Builder().url(u).build()).execute().use { r ->
                if (r.code == 404) throw FsException.NotFound(dir)
                if (r.code == 401) throw FsException.AuthRequired(dir.authority)
                if (!r.isSuccessful) throw FsException.Generic("HTTP ${r.code}")
                r.body!!.string()
            }
        } catch (e: FsException) { throw e } catch (e: Exception) { throw FsException.wrap(e, dir) }
        val seen = HashSet<String>()
        html.lines().flatMap { line ->
            linkRe.findAll(line).mapNotNull { m ->
                val href = m.groupValues[1]
                if (href.startsWith("..") || href.startsWith("/") && !href.startsWith(u.encodedPath) || href.contains("://") || href == "./") return@mapNotNull null
                val rel = href.removePrefix(u.encodedPath).trimStart('.')
                val isDir = rel.endsWith("/")
                val name = URLDecoder.decode(rel.trimEnd('/').substringAfterLast('/'), "UTF-8")
                if (name.isEmpty() || name == "." || !seen.add(name)) return@mapNotNull null
                val meta = sizeRe.find(line.substring(m.range.last))
                FileEntry(dir.child(name), isDir, size = meta?.groupValues?.get(2)?.let(::parseSize) ?: 0, lastModified = meta?.groupValues?.get(1)?.let(::parseDate) ?: 0, canWrite = false)
            }.toList()
        }
    }

    private fun parseSize(s: String): Long = when {
        s == "-" -> 0
        s.last().isLetter() -> ((s.dropLast(1).toDoubleOrNull() ?: 0.0) * when (s.last()) { 'K' -> 1024.0; 'M' -> 1048576.0; 'G' -> 1073741824.0; else -> 1099511627776.0 }).toLong()
        else -> s.toLongOrNull() ?: 0
    }

    private fun parseDate(s: String): Long = runCatching {
        (if (s[2] == '-') SimpleDateFormat("dd-MMM-yyyy HH:mm", Locale.US) else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)).parse(s)!!.time
    }.getOrDefault(0)

    override suspend fun stat(path: FsPath): FileEntry = withContext(Dispatchers.IO) {
        if (path.isRoot) return@withContext FileEntry(path, true, canWrite = false)
        val (http, base) = client(path.authority)
        try {
            http.newCall(Request.Builder().url(url(base, path.path, false)).head().build()).execute().use { r ->
                if (r.isSuccessful && r.header("Content-Type")?.startsWith("text/html") != true)
                    FileEntry(path, false, r.header("Content-Length")?.toLongOrNull() ?: 0, r.headers.getDate("Last-Modified")?.time ?: 0, canWrite = false)
                else FileEntry(path, true, canWrite = false)
            }
        } catch (e: Exception) { throw FsException.wrap(e, path) }
    }

    override suspend fun openInput(path: FsPath, offset: Long): InputStream = withContext(Dispatchers.IO) {
        val (http, base) = client(path.authority)
        val b = Request.Builder().url(url(base, path.path, false))
        if (offset > 0) b.header("Range", "bytes=$offset-")
        val r = try { http.newCall(b.build()).execute() } catch (e: Exception) { throw FsException.wrap(e, path) }
        if (!r.isSuccessful) { r.close(); throw if (r.code == 404) FsException.NotFound(path) else FsException.Generic("HTTP ${r.code}") }
        val s = r.body!!.byteStream()
        if (offset > 0 && r.code == 200) s.skip(offset)
        ClosingInputStream(s) { r.close() }
    }

    private fun ro(): Nothing = throw FsException.Unsupported("HTTP listings are read-only")
    override suspend fun openOutput(path: FsPath, size: Long): OutputStream = ro()
    override suspend fun mkdir(parent: FsPath, name: String): FsPath = ro()
    override suspend fun rename(path: FsPath, newName: String): FsPath = ro()
    override suspend fun delete(path: FsPath) = ro()
    override suspend fun rootTitle(authority: String) = runCatching { connections.get(authority).name }.getOrDefault(authority)
}
