package com.xander.files.data.server

import com.xander.files.domain.fs.FileEntry
import com.xander.files.domain.fs.FsException
import com.xander.files.domain.fs.MimeTypes
import java.net.URLEncoder
import java.text.DateFormat
import java.util.Base64
import java.util.Date

/**
 * "Access from PC" web interface: browse folders, download (with Range for media seeking), upload
 * (multipart, several files), create folders, rename and delete. Optional Basic auth.
 */
class WebFileServer(
    private val root: ServerRoot,
    private val user: String?,
    private val password: String?,
    port: Int,
) {
    private val server = MiniHttpServer(port, ::handle)
    val port get() = server.localPort
    val running get() = server.running

    fun start() = server.start()
    fun stop() = server.stop()

    private fun authorized(r: HttpRequest): Boolean {
        if (user.isNullOrEmpty()) return true
        val h = r.header("authorization") ?: return false
        if (!h.startsWith("Basic ", true)) return false
        val dec = runCatching { String(Base64.getDecoder().decode(h.substring(6).trim()), Charsets.UTF_8) }.getOrNull() ?: return false
        return dec == "$user:${password.orEmpty()}"
    }

    suspend fun handle(r: HttpRequest): HttpResponse {
        if (!authorized(r)) return HttpResponse.text(401, "Sign in required").also { it.headers["WWW-Authenticate"] = "Basic realm=\"Xander Files\", charset=\"UTF-8\"" }
        val v = FsPath0.norm(r.path)
        val p = root.toFs(v)
        val fs = root.provider
        return try {
            when (r.method) {
                "GET", "HEAD" -> {
                    if (r.query.containsKey("delete") || r.query.containsKey("mkdir") || r.query.containsKey("rename")) return HttpResponse.text(405, "Use POST")
                    val e = fs.stat(p)
                    if (e.isDirectory) {
                        if (!r.path.endsWith("/")) HttpResponse(301, linkedMapOf("Location" to href(v) + "/"), 0)
                        else HttpResponse.html(listing(v, fs.list(p)))
                    } else download(r, e)
                }
                "POST" -> {
                    if (root.readOnly) return HttpResponse.text(403, "This server is read-only")
                    val e = fs.stat(p)
                    if (!e.isDirectory) return HttpResponse.text(400, "Not a folder")
                    when {
                        r.query.containsKey("mkdir") -> { fs.mkdir(p, safeName(r.query["mkdir"]!!)) }
                        r.query.containsKey("delete") -> { fs.delete(root.toFs(FsPath0.norm("$v/${safeName(r.query["delete"]!!)}"))) }
                        r.query.containsKey("rename") && r.query.containsKey("to") -> fs.rename(root.toFs(FsPath0.norm("$v/${safeName(r.query["rename"]!!)}")), safeName(r.query["to"]!!))
                        else -> {
                            val b = Multipart.boundary(r.header("content-type")) ?: return HttpResponse.text(400, "Expected multipart upload")
                            Multipart.read(r.body, b) { headers, content ->
                                val name = Multipart.disposition(headers, "filename")?.substringAfterLast('/')?.substringAfterLast('\\')
                                if (!name.isNullOrBlank()) kotlinx.coroutines.runBlocking {
                                    val target = fs.createFileOrReplace(p, safeName(name))
                                    fs.openOutput(target).use { content.copyTo(it, 64 * 1024) }
                                }
                            }
                        }
                    }
                    if (r.header("accept")?.contains("text/html") == true) HttpResponse.redirect(href(v).trimEnd('/') + "/") else HttpResponse.text(200, "OK")
                }
                else -> HttpResponse.text(405, "Method not allowed")
            }
        } catch (e: FsException.NotFound) {
            HttpResponse.text(404, "Not found")
        } catch (e: Exception) {
            HttpResponse.text(500, FsException.wrap(e).message ?: "Error")
        }
    }

    private suspend fun download(r: HttpRequest, e: FileEntry): HttpResponse {
        val size = e.size
        val mime = e.mimeType ?: MimeTypes.fromName(e.name) ?: "application/octet-stream"
        val range = r.header("range")?.takeIf { it.startsWith("bytes=") }?.removePrefix("bytes=")?.substringBefore(',')
        var start = 0L; var end = size - 1; var status = 200
        if (range != null && size > 0) {
            val a = range.substringBefore('-'); val b = range.substringAfter('-')
            if (a.isEmpty()) { start = (size - (b.toLongOrNull() ?: 0)).coerceAtLeast(0) } else { start = a.toLongOrNull() ?: 0; if (b.isNotEmpty()) end = minOf(b.toLong(), size - 1) }
            if (start >= size || start > end) return HttpResponse(416, linkedMapOf("Content-Range" to "bytes */$size"), 0)
            status = 206
        }
        val len = if (size <= 0) 0 else end - start + 1
        val headers = linkedMapOf("Content-Type" to mime, "Accept-Ranges" to "bytes", "Last-Modified" to MiniHttpServer.httpDate(e.lastModified))
        if (status == 206) headers["Content-Range"] = "bytes $start-$end/$size"
        if (r.query.containsKey("dl")) headers["Content-Disposition"] = "attachment; filename*=UTF-8''" + URLEncoder.encode(e.name, "UTF-8").replace("+", "%20")
        if (r.method == "HEAD") return HttpResponse(status, headers, len)
        val input = root.provider.openInput(e.path, start)
        return HttpResponse(status, headers, len) { out ->
            input.use { i ->
                val buf = ByteArray(64 * 1024); var left = len
                while (left > 0) { val n = i.read(buf, 0, minOf(buf.size.toLong(), left).toInt()); if (n < 0) break; out.write(buf, 0, n); left -= n }
            }
        }
    }

    private fun safeName(n: String): String {
        val s = n.replace('/', '_').replace('\\', '_').trim()
        if (s.isEmpty() || s == "." || s == "..") throw FsException.Generic("Invalid name")
        return s
    }

    private fun href(v: String) = v.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }.ifEmpty { "/" }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun listing(v: String, entries: List<FileEntry>): String {
        val df = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        val sorted = entries.sortedWith(compareBy<FileEntry>({ !it.isDirectory }, { it.name.lowercase() }))
        val crumbs = StringBuilder("<a href=\"/\">Home</a>")
        var acc = ""
        v.split('/').filter { it.isNotEmpty() }.forEach { seg -> acc += "/$seg"; crumbs.append(" / <a href=\"${href(acc)}/\">${esc(seg)}</a>") }
        val rows = StringBuilder()
        if (v != "/") rows.append("<tr><td colspan=4><a href=\"../\">⬑ ..</a></td></tr>")
        for (e in sorted) {
            val link = href(FsPath0.norm("$v/${e.name}")) + if (e.isDirectory) "/" else ""
            rows.append("<tr><td>${if (e.isDirectory) "📁" else "📄"} <a href=\"$link\">${esc(e.name)}</a></td>")
                .append("<td class=r>${if (e.isDirectory) "" else human(e.size)}</td><td>${esc(df.format(Date(e.lastModified)))}</td><td class=r>")
            if (!e.isDirectory) rows.append("<a href=\"$link?dl\" title=Download>⬇</a> ")
            if (!root.readOnly) rows.append("<button onclick=\"ren('${esc(js(e.name))}')\" title=Rename>✎</button> <button onclick=\"del('${esc(js(e.name))}')\" title=Delete>🗑</button>")
            rows.append("</td></tr>")
        }
        val tools = if (root.readOnly) "" else """
            <form method=post enctype=multipart/form-data id=up><input type=file name=f multiple onchange="up.submit()"><button type=button onclick="mk()">New folder</button></form>
            <p class=hint>Tip: drag files onto this page to upload them here.</p>"""
        return """<!doctype html><html><head><meta charset=utf-8><meta name=viewport content="width=device-width,initial-scale=1"><title>Xander Files – ${esc(v)}</title>
<style>body{font-family:system-ui,sans-serif;margin:0;background:#f6f7f9;color:#1b1c1e}header{background:#2f5bd3;color:#fff;padding:14px 18px;font-size:18px}
header a{color:#fff}main{padding:12px 18px}table{width:100%;border-collapse:collapse;background:#fff;border-radius:8px}td{padding:8px;border-bottom:1px solid #eee}
.r{text-align:right;white-space:nowrap}a{color:#2f5bd3;text-decoration:none}button{border:0;background:none;cursor:pointer;font-size:15px}
form{margin:10px 0}.hint{color:#777;font-size:13px}@media(prefers-color-scheme:dark){body{background:#121316;color:#e3e3e6}table{background:#1d1e22}td{border-color:#2a2b30}a{color:#9db4ff}}</style></head>
<body><header>Xander Files — $crumbs</header><main>$tools<table>$rows</table></main>
<script>
function post(q){fetch(location.pathname+'?'+q,{method:'POST'}).then(function(r){if(r.ok)location.reload();else r.text().then(alert)})}
function mk(){var n=prompt('Folder name');if(n)post('mkdir='+encodeURIComponent(n))}
function del(n){if(confirm('Delete '+n+'?'))post('delete='+encodeURIComponent(n))}
function ren(n){var t=prompt('New name',n);if(t&&t!=n)post('rename='+encodeURIComponent(n)+'&to='+encodeURIComponent(t))}
document.addEventListener('dragover',function(e){e.preventDefault()});
document.addEventListener('drop',function(e){e.preventDefault();var fd=new FormData();for(var f of e.dataTransfer.files)fd.append('f',f,f.name);
fetch(location.pathname,{method:'POST',body:fd}).then(function(){location.reload()})});
</script></body></html>"""
    }

    private fun js(s: String) = s.replace("\\", "\\\\").replace("'", "\\'")

    private fun human(b: Long): String {
        if (b < 1024) return "$b B"
        val u = arrayOf("KB", "MB", "GB", "TB"); var v = b / 1024.0; var i = 0
        while (v >= 1024 && i < u.size - 1) { v /= 1024; i++ }
        return "%.1f %s".format(v, u[i])
    }
}

internal object FsPath0 { fun norm(s: String) = com.xander.files.domain.fs.FsPath.normalize(s) }

/** Creates [name] in [parent], or returns the existing file so it is overwritten. */
suspend fun com.xander.files.domain.fs.FileSystemProvider.createFileOrReplace(parent: com.xander.files.domain.fs.FsPath, name: String): com.xander.files.domain.fs.FsPath {
    val target = parent.child(name)
    return if (exists(target)) target else createFile(parent, name)
}
