package com.xander.files.data.cast

import com.xander.files.data.server.HttpRequest
import com.xander.files.data.server.HttpResponse
import com.xander.files.data.server.LocalAddresses
import com.xander.files.data.server.MiniHttpServer
import com.xander.files.domain.fs.FileSystemRegistry
import com.xander.files.domain.fs.FsPath
import com.xander.files.domain.fs.MimeTypes
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Serves individual files from any backend over HTTP on the LAN so a Cast device can fetch them.
 * Each file gets an unguessable token; nothing else is reachable. SRT subtitles are converted to WebVTT.
 */
@Singleton
class MediaStreamServer @Inject constructor(private val registry: FileSystemRegistry) {
    private var server: MiniHttpServer? = null
    private val tokens = ConcurrentHashMap<String, FsPath>()
    private val random = SecureRandom()

    @Synchronized private fun ensure(): MiniHttpServer =
        server?.takeIf { it.running } ?: MiniHttpServer(0, ::handle).also { it.start(); server = it }

    /** Public URL for [path], or null when the phone has no LAN address. */
    fun urlFor(path: FsPath, vtt: Boolean = false): String? {
        val ip = LocalAddresses.ipv4().firstOrNull()?.hostAddress ?: return null
        val s = ensure()
        val token = tokens.entries.firstOrNull { it.value == path }?.key
            ?: ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }.also { tokens[it] = path }
        val name = URLEncoder.encode(if (vtt) path.name.substringBeforeLast('.') + ".vtt" else path.name, "UTF-8").replace("+", "%20")
        return "http://$ip:${s.localPort}/$token/$name" + if (vtt) "?vtt" else ""
    }

    fun stop() { server?.stop(); server = null; tokens.clear() }

    private suspend fun handle(r: HttpRequest): HttpResponse {
        val cors = mapOf("Access-Control-Allow-Origin" to "*", "Access-Control-Allow-Headers" to "Range, Content-Type", "Access-Control-Expose-Headers" to "Content-Range, Content-Length, Accept-Ranges")
        if (r.method == "OPTIONS") return HttpResponse(204, LinkedHashMap(cors), 0)
        val token = r.path.trimStart('/').substringBefore('/')
        val path = tokens[token] ?: return HttpResponse.text(404, "Not found")
        val fs = registry.provider(path)
        val e = fs.stat(path)
        if (r.query.containsKey("vtt")) {
            val src = fs.openInput(path).use { String(it.readBytes(), Charsets.UTF_8) }
            return HttpResponse.text(200, srtToVtt(src), "text/vtt; charset=utf-8").also { it.headers.putAll(cors) }
        }
        val size = e.size
        val range = r.header("range")?.removePrefix("bytes=")?.substringBefore(',')
        var start = 0L; var end = size - 1; var status = 200
        if (range != null && size > 0) {
            val a = range.substringBefore('-'); val b = range.substringAfter('-')
            if (a.isEmpty()) start = (size - (b.toLongOrNull() ?: 0)).coerceAtLeast(0) else { start = a.toLongOrNull() ?: 0; if (b.isNotEmpty()) end = minOf(b.toLong(), size - 1) }
            if (start >= size) return HttpResponse(416, linkedMapOf("Content-Range" to "bytes */$size"), 0)
            status = 206
        }
        val len = (end - start + 1).coerceAtLeast(0)
        val headers = LinkedHashMap<String, String>(cors)
        headers["Content-Type"] = MimeTypes.fromName(e.name); headers["Accept-Ranges"] = "bytes"
        if (status == 206) headers["Content-Range"] = "bytes $start-$end/$size"
        if (r.method == "HEAD") return HttpResponse(status, headers, len)
        val input = fs.openInput(path, start)
        return HttpResponse(status, headers, len) { out ->
            input.use { i -> val buf = ByteArray(64 * 1024); var left = len
                while (left > 0) { val n = i.read(buf, 0, minOf(buf.size.toLong(), left).toInt()); if (n < 0) break; out.write(buf, 0, n); left -= n } }
        }
    }

    companion object {
        private val timing = Regex("(\\d{1,2}:\\d{2}:\\d{2}),(\\d{3})")
        fun srtToVtt(srt: String): String {
            val body = srt.removePrefix("﻿").replace("\r\n", "\n")
            if (body.trimStart().startsWith("WEBVTT")) return body
            return "WEBVTT\n\n" + body.replace(timing, "$1.$2")
        }
    }
}
