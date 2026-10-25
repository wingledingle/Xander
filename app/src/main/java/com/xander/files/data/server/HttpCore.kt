package com.xander.files.data.server

import kotlinx.coroutines.runBlocking
import java.io.BufferedInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class HttpRequest(
    val method: String,
    val rawTarget: String,
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String>,
    val body: InputStream,
    val remote: InetAddress,
) {
    fun header(name: String) = headers[name.lowercase()]
}

class HttpResponse(
    val status: Int,
    val headers: MutableMap<String, String> = LinkedHashMap(),
    val length: Long = -1,
    val writer: (OutputStream) -> Unit = {},
) {
    companion object {
        fun text(status: Int, text: String, type: String = "text/plain; charset=utf-8"): HttpResponse {
            val b = text.toByteArray(Charsets.UTF_8)
            return HttpResponse(status, linkedMapOf("Content-Type" to type), b.size.toLong()) { it.write(b) }
        }
        fun html(html: String, status: Int = 200) = text(status, html, "text/html; charset=utf-8")
        fun redirect(location: String) = HttpResponse(303, linkedMapOf("Location" to location), 0)
    }
}

/**
 * Minimal HTTP/1.1 server (keep-alive, chunked request bodies, streaming responses). Written in-house so the
 * app carries no third-party server licence. Each connection runs on its own thread.
 */
class MiniHttpServer(
    private val port: Int,
    private val handler: suspend (HttpRequest) -> HttpResponse,
    private val bind: InetAddress? = null,
) {
    private var socket: ServerSocket? = null
    private var pool: ExecutorService? = null
    @Volatile var running = false
        private set
    val localPort get() = socket?.localPort ?: port

    fun start() {
        val s = ServerSocket(port, 50, bind).apply { reuseAddress = true }
        socket = s
        val p = Executors.newCachedThreadPool { r -> Thread(r, "xander-http").apply { isDaemon = true } }
        pool = p
        running = true
        p.execute {
            while (running) {
                val c = try { s.accept() } catch (e: IOException) { break }
                p.execute { serve(c) }
            }
        }
    }

    fun stop() {
        running = false
        runCatching { socket?.close() }
        pool?.shutdownNow()
    }

    private fun serve(c: Socket) = c.use { sock ->
        sock.soTimeout = 120_000
        val input = BufferedInputStream(sock.getInputStream(), 16 * 1024)
        val out = sock.getOutputStream().buffered(64 * 1024)
        try {
            while (true) {
                val line = readLine(input) ?: return
                if (line.isEmpty()) continue
                val parts = line.split(' ')
                if (parts.size < 3) { write(out, HttpResponse.text(400, "Bad request"), false, false); return }
                val headers = HashMap<String, String>()
                while (true) {
                    val h = readLine(input) ?: return
                    if (h.isEmpty()) break
                    val i = h.indexOf(':')
                    if (i > 0) headers[h.substring(0, i).trim().lowercase()] = h.substring(i + 1).trim()
                }
                val method = parts[0].uppercase()
                val target = parts[1]
                val http10 = parts[2] == "HTTP/1.0"
                val keepAlive = if (http10) headers["connection"].equals("keep-alive", true) else !headers["connection"].equals("close", true)
                val body: InputStream = when {
                    headers["transfer-encoding"]?.contains("chunked", true) == true -> ChunkedInput(input)
                    headers["content-length"] != null -> LimitedInput(input, headers["content-length"]!!.toLongOrNull() ?: 0)
                    else -> LimitedInput(input, 0)
                }
                if (headers["expect"].equals("100-continue", true)) { out.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray()); out.flush() }
                val rawPath = target.substringBefore('?')
                val req = HttpRequest(
                    method, target, decode(rawPath), parseQuery(target.substringAfter('?', "")), headers, body,
                    sock.inetAddress,
                )
                val resp = try { runBlocking { handler(req) } } catch (e: Exception) { HttpResponse.text(500, e.message ?: "Server error") }
                // Drain whatever the handler did not consume so the next request on this connection parses cleanly.
                runCatching { body.skip(Long.MAX_VALUE); while (body.read() >= 0) { } }
                val persist = write(out, resp, keepAlive, method == "HEAD")
                if (!persist) return
            }
        } catch (_: SocketException) {
        } catch (_: IOException) {
        }
    }

    /** Returns whether the connection can be reused. */
    private fun write(out: OutputStream, r: HttpResponse, keepAlive: Boolean, head: Boolean): Boolean {
        val sb = StringBuilder("HTTP/1.1 ${r.status} ${reason(r.status)}\r\n")
        val chunked = r.length < 0 && !head
        r.headers.putIfAbsent("Date", httpDate(System.currentTimeMillis()))
        r.headers["Server"] = "Xander"
        if (r.length >= 0) r.headers["Content-Length"] = r.length.toString()
        else if (chunked) r.headers["Transfer-Encoding"] = "chunked"
        r.headers["Connection"] = if (keepAlive) "keep-alive" else "close"
        r.headers.forEach { (k, v) -> sb.append(k).append(": ").append(v).append("\r\n") }
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        if (!head) {
            if (chunked) { val co = ChunkedOutput(out); r.writer(co); co.finish() } else r.writer(out)
        }
        out.flush()
        return keepAlive
    }

    companion object {
        fun readLine(i: InputStream): String? {
            val sb = StringBuilder()
            while (true) {
                val b = i.read()
                if (b < 0) return if (sb.isEmpty()) null else sb.toString()
                if (b == '\n'.code) return sb.toString().trimEnd('\r')
                sb.append(b.toChar())
                if (sb.length > 16 * 1024) throw IOException("Header line too long")
            }
        }

        fun decode(s: String): String = runCatching { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") }.getOrDefault(s)

        fun parseQuery(q: String): Map<String, String> = if (q.isEmpty()) emptyMap() else q.split('&').filter { it.isNotEmpty() }.associate {
            val k = it.substringBefore('='); val v = it.substringAfter('=', "")
            runCatching { URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8") }.getOrDefault(k to v)
        }

        fun httpDate(t: Long): String = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT") }.format(Date(t))

        fun reason(code: Int) = when (code) {
            200 -> "OK"; 201 -> "Created"; 204 -> "No Content"; 206 -> "Partial Content"; 301 -> "Moved Permanently"; 303 -> "See Other"
            304 -> "Not Modified"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"
            405 -> "Method Not Allowed"; 409 -> "Conflict"; 416 -> "Range Not Satisfiable"; 500 -> "Internal Server Error"; else -> "Status"
        }
    }
}

class LimitedInput(i: InputStream, private var left: Long) : FilterInputStream(i) {
    override fun read(): Int { if (left <= 0) return -1; val b = super.read(); if (b >= 0) left--; return b }
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (left <= 0) return -1
        val n = super.read(b, off, minOf(len.toLong(), left).toInt()); if (n > 0) left -= n; return n
    }
    override fun skip(n: Long): Long { var s = 0L; val buf = ByteArray(8192); while (s < n) { val r = read(buf, 0, minOf(buf.size.toLong(), n - s).toInt()); if (r < 0) break; s += r }; return s }
    override fun available() = minOf(super.available().toLong(), left).toInt()
    override fun close() {}
}

class ChunkedInput(private val i: InputStream) : InputStream() {
    private var left = 0L
    private var done = false
    private fun next(): Boolean {
        if (done) return false
        if (left == 0L) {
            val line = MiniHttpServer.readLine(i) ?: run { done = true; return false }
            val l = (if (line.isEmpty()) MiniHttpServer.readLine(i) ?: "" else line).substringBefore(';').trim()
            left = l.toLongOrNull(16) ?: 0
            if (left == 0L) { while (MiniHttpServer.readLine(i)?.isNotEmpty() == true) { }; done = true; return false }
        }
        return true
    }
    override fun read(): Int { if (!next()) return -1; val b = i.read(); if (b >= 0) { left--; if (left == 0L) MiniHttpServer.readLine(i) }; return b }
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (!next()) return -1
        val n = i.read(b, off, minOf(len.toLong(), left).toInt())
        if (n > 0) { left -= n; if (left == 0L) MiniHttpServer.readLine(i) }
        return n
    }
    override fun close() {}
}

class ChunkedOutput(private val o: OutputStream) : OutputStream() {
    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
    override fun write(b: ByteArray, off: Int, len: Int) {
        if (len == 0) return
        o.write("${len.toString(16)}\r\n".toByteArray()); o.write(b, off, len); o.write("\r\n".toByteArray())
    }
    override fun flush() = o.flush()
    fun finish() { o.write("0\r\n\r\n".toByteArray()); o.flush() }
    override fun close() {}
}

/** Streaming multipart/form-data reader: calls [onPart] with each part's headers and a stream of its content. */
object Multipart {
    fun boundary(contentType: String?): String? =
        contentType?.split(';')?.map { it.trim() }?.firstOrNull { it.startsWith("boundary=", true) }?.substringAfter('=')?.trim('"')

    fun read(input: InputStream, boundary: String, onPart: (Map<String, String>, InputStream) -> Unit) {
        val src = BufferedInputStream(input, 64 * 1024)
        // Skip preamble up to and including the first boundary line.
        val first = "--$boundary"
        while (true) { val l = MiniHttpServer.readLine(src) ?: return; if (l == first) break; if (l == "$first--") return }
        val delim = "\r\n--$boundary".toByteArray(Charsets.ISO_8859_1)
        while (true) {
            val headers = HashMap<String, String>()
            while (true) {
                val h = MiniHttpServer.readLine(src) ?: return
                if (h.isEmpty()) break
                val i = h.indexOf(':'); if (i > 0) headers[h.substring(0, i).trim().lowercase()] = h.substring(i + 1).trim()
            }
            val part = PartStream(src, delim)
            onPart(headers, part)
            while (part.read() >= 0) { }
            // After the delimiter: "--" ends the body, CRLF starts the next part.
            val a = src.read(); val b = src.read()
            if (a == '-'.code && b == '-'.code || a < 0) return
        }
    }

    fun disposition(headers: Map<String, String>, key: String): String? =
        headers["content-disposition"]?.split(';')?.map { it.trim() }?.firstOrNull { it.startsWith("$key=", true) }
            ?.substringAfter('=')?.trim('"')

    /** Reads until [delim]; uses mark/reset on the buffered source so nothing past the delimiter is consumed. */
    private class PartStream(private val src: BufferedInputStream, private val delim: ByteArray) : InputStream() {
        private var ended = false
        override fun read(): Int {
            val one = ByteArray(1); val n = read(one, 0, 1); return if (n <= 0) -1 else one[0].toInt() and 0xff
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (ended) return -1
            var n = 0
            while (n < len) {
                src.mark(delim.size + 1)
                val c = src.read()
                if (c < 0) { ended = true; break }
                if (c.toByte() == delim[0]) {
                    var k = 1
                    while (k < delim.size) { val d = src.read(); if (d < 0 || d.toByte() != delim[k]) break; k++ }
                    if (k == delim.size) { ended = true; break }
                    src.reset(); src.read()
                }
                b[off + n++] = c.toByte()
            }
            return if (n == 0 && ended) -1 else n
        }
    }
}
