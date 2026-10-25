package com.xander.files.data.tv

import com.xander.files.data.net.ClosingInputStream
import com.xander.files.data.net.StreamingUpload
import com.xander.files.data.tv.TvProtocol.map
import com.xander.files.domain.fs.FileEntry
import com.xander.files.domain.fs.FsException
import com.xander.files.domain.fs.FsPath
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/** Blocking HTTP client for one paired TV (callers run it on Dispatchers.IO). */
class TvClient(val host: String, val port: Int, private val token: String?, private val http: OkHttpClient = DEFAULT) {
    private val base: HttpUrl = "http://$host:$port/".toHttpUrl()

    private fun url(path: String, vararg q: Pair<String, String>) = base.newBuilder().addPathSegments(path.trimStart('/')).apply { q.forEach { addQueryParameter(it.first, it.second) } }.build()

    private fun req(path: String, vararg q: Pair<String, String>) = Request.Builder().url(url(path, *q)).apply { token?.let { header(TvProtocol.TOKEN_HEADER, it) } }

    private fun exec(r: Request, tvPath: FsPath? = null): Response {
        val resp = try { http.newCall(r).execute() } catch (e: java.io.IOException) { throw FsException.NetworkLost(e) }
        if (!resp.isSuccessful) {
            val msg = resp.body?.string().orEmpty().take(300); resp.close()
            throw when (resp.code) {
                401 -> FsException.AuthRequired("$host", null)
                404 -> if (tvPath != null) FsException.NotFound(tvPath) else FsException.Generic(msg.ifEmpty { "Not found" })
                else -> FsException.Generic(msg.ifEmpty { "TV error ${resp.code}" })
            }
        }
        return resp
    }

    private fun text(r: Request, p: FsPath? = null) = exec(r, p).use { it.body!!.string() }
    private fun post(path: String, vararg q: Pair<String, String>) { text(req(path, *q).post(ByteArray(0).toRequestBody()).build()) }

    fun hello(): JSONObject = JSONObject(text(req("hello").build()))
    fun pair(code: String, client: String): Pair<String, String> =
        JSONObject(text(req("pair", "code" to code, "client" to client).post(ByteArray(0).toRequestBody()).build())).let { it.getString("token") to it.optString("name") }

    fun info(): TvInfo = JSONObject(text(req("info").build())).let { TvInfo(it.optString("name"), it.optString("model"), it.optString("android"), it.optString("root"), it.optLong("total"), it.optLong("free"), it.optBoolean("remote")) }

    fun list(dir: FsPath): List<FileEntry> = JSONArray(text(req("fs/list", "path" to dir.path).build(), dir)).map { TvProtocol.entryFrom(dir, it) }
    fun stat(p: FsPath): FileEntry = JSONObject(text(req("fs/stat", "path" to p.path).build(), p)).let { TvProtocol.entryFrom(p.parent ?: p, it).copy(path = p) }

    fun read(p: FsPath, offset: Long): InputStream {
        val r = exec(req("fs/read", "path" to p.path, "offset" to offset.toString()).build(), p)
        return ClosingInputStream(r.body!!.byteStream()) { r.close() }
    }

    fun write(p: FsPath, size: Long): OutputStream = StreamingUpload("tv") { input ->
        val body = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength() = size
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) { sink.writeAll(input.source()) }
        }
        exec(req("fs/write", "path" to p.path).put(body).build(), p).close()
    }

    fun mkdir(parent: FsPath, name: String) = post("fs/mkdir", "path" to parent.path, "name" to name)
    fun rename(p: FsPath, name: String) = post("fs/rename", "path" to p.path, "name" to name)
    fun delete(p: FsPath) = post("fs/delete", "path" to p.path)
    fun move(p: FsPath, toDir: FsPath, name: String) = post("fs/move", "path" to p.path, "to" to toDir.path, "name" to name)

    fun apps(): List<TvApp> = JSONArray(text(req("apps").build())).map(TvProtocol::appFrom)
    fun iconUrl(pkg: String): String = url("apps/icon", "pkg" to pkg).toString()
    fun iconRequest(pkg: String): Request = req("apps/icon", "pkg" to pkg).build()
    fun launch(pkg: String) = post("apps/launch", "pkg" to pkg)
    fun uninstall(pkg: String) = post("apps/uninstall", "pkg" to pkg)
    fun downloadApk(pkg: String): InputStream { val r = exec(req("apps/apk", "pkg" to pkg).build()); return ClosingInputStream(r.body!!.byteStream()) { r.close() } }
    fun install(apk: InputStream, size: Long) {
        val body = object : RequestBody() {
            override fun contentType() = "application/vnd.android.package-archive".toMediaType()
            override fun contentLength() = size
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) { apk.use { sink.writeAll(it.source()) } }
        }
        exec(req("apps/install").put(body).build()).close()
    }

    fun key(k: TvKey) = post("input/key", "key" to k.name)
    fun text(t: String) = post("input/text", "text" to t)

    companion object {
        val DEFAULT: OkHttpClient = OkHttpClient.Builder().connectTimeout(6, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).writeTimeout(0, TimeUnit.SECONDS).build()
    }
}
