package com.xander.files.data.cloud

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.xander.files.data.net.ClosingInputStream
import com.xander.files.data.net.StreamingUpload
import com.xander.files.domain.fs.FileEntry
import com.xander.files.domain.fs.FsException
import com.xander.files.domain.fs.FsPath
import com.xander.files.domain.fs.SpaceInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import java.io.InputStream
import java.io.OutputStream

/** Yandex Disk REST API (path based, "disk:/..."). */
class YandexDisk(http: OkHttpClient, tokens: TokenSource) : HttpCloudDrive(http, tokens) {
    private val api = "https://cloud-api.yandex.net/v1/disk"
    override fun authHeader(token: String) = "OAuth $token"

    private fun disk(p: FsPath) = "disk:" + p.path
    private fun url(endpoint: String, vararg q: Pair<String, String>): HttpUrl =
        "$api$endpoint".toHttpUrl().newBuilder().apply { q.forEach { addQueryParameter(it.first, it.second) } }.build()

    override fun mapError(code: Int, body: String, path: FsPath?): FsException = when {
        body.contains("DiskResourceAlreadyExistsError") -> path?.let { FsException.AlreadyExists(it) } ?: FsException.Generic("Exists")
        code == 507 || body.contains("DiskStorageQuotaExhaustedError") -> FsException.OutOfSpace()
        else -> super.mapError(code, body, path)
    }

    private fun entry(p: FsPath, j: JsonObject) = FileEntry(p, j.str("type") == "dir", j.long("size") ?: 0, isoTime(j.str("modified")),
        created = isoTime(j.str("created")), mimeType = j.str("mime_type"), thumbnailUrl = j.str("preview"), backendId = j.str("resource_id"))

    override suspend fun list(path: FsPath): List<FileEntry> {
        val out = ArrayList<FileEntry>()
        var offset = 0
        while (true) {
            val j = call({ url(url("/resources", "path" to disk(path), "limit" to "1000", "offset" to "$offset", "preview_size" to "M")) }, path).json()
            val items = (j.obj("_embedded")?.get("items") as? JsonArray).orEmpty().map { it.jsonObject }
            items.forEach { out += entry(path.child(it.str("name")!!), it) }
            offset += items.size
            if (items.isEmpty() || offset >= (j.obj("_embedded")?.long("total") ?: 0)) break
        }
        return out
    }

    override suspend fun stat(path: FsPath) = entry(path, call({ url(url("/resources", "path" to disk(path), "limit" to "0")) }, path).json())

    override suspend fun openInput(path: FsPath, offset: Long): InputStream {
        val href = call({ url(url("/resources/download", "path" to disk(path))) }, path).json().str("href")!!
        val r = http.newCall(Request.Builder().url(href).apply { if (offset > 0) header("Range", "bytes=$offset-") }.build()).execute()
        if (!r.isSuccessful) { r.close(); throw FsException.Generic("Download failed (${r.code})") }
        val s = r.body!!.byteStream()
        if (offset > 0 && r.code == 200) s.skip(offset)
        return ClosingInputStream(s) { r.close() }
    }

    override suspend fun openOutput(path: FsPath, size: Long): OutputStream {
        val href = call({ url(url("/resources/upload", "path" to disk(path), "overwrite" to "true")) }, path).json().str("href")!!
        return StreamingUpload(path.name) { input ->
            val body = object : RequestBody() {
                override fun contentType() = "application/octet-stream".toMediaType()
                override fun contentLength() = size
                override fun isOneShot() = true
                override fun writeTo(sink: BufferedSink) { sink.writeAll(input.source()) }
            }
            http.newCall(Request.Builder().url(href).put(body).build()).execute().use { r ->
                if (!r.isSuccessful) throw FsException.Generic("Upload failed (${r.code})")
            }
        }
    }

    /** Long operations answer 202 with a status link; wait for completion. */
    private suspend fun waitOperation(j: JsonObject) {
        val href = j.str("href") ?: return
        if (!href.contains("/operations/")) return
        repeat(300) {
            val st = call({ url(href) }).json().str("status")
            if (st == "success") return
            if (st == "failed") throw FsException.Generic("Operation failed")
            delay(1000)
        }
    }

    override suspend fun mkdir(parent: FsPath, name: String): FsPath {
        val p = parent.child(name)
        call({ url(url("/resources", "path" to disk(p))); put(ByteArray(0).toRequestBody(null)) }, p).close(); return p
    }

    override suspend fun rename(path: FsPath, newName: String) = move(path, path.parent!!, newName)!!

    override suspend fun delete(path: FsPath) {
        val r = call({ url(url("/resources", "path" to disk(path), "permanently" to "false")); delete() }, path)
        if (r.code == 202) waitOperation(r.json()) else r.close()
    }

    override suspend fun move(src: FsPath, dstParent: FsPath, name: String): FsPath? {
        val d = dstParent.child(name)
        val r = call({ url(url("/resources/move", "from" to disk(src), "path" to disk(d), "overwrite" to "false")); post(ByteArray(0).toRequestBody(null)) }, src)
        if (r.code == 202) waitOperation(r.json()) else r.close()
        return d
    }

    override suspend fun copy(src: FsPath, dstParent: FsPath, name: String): FsPath? {
        val d = dstParent.child(name)
        val r = call({ url(url("/resources/copy", "from" to disk(src), "path" to disk(d), "overwrite" to "false")); post(ByteArray(0).toRequestBody(null)) }, src)
        if (r.code == 202) waitOperation(r.json()) else r.close()
        return d
    }

    override suspend fun thumbnail(entry: FileEntry, sizePx: Int): Bitmap? = runCatching {
        val u = entry.thumbnailUrl ?: return null
        http.newCall(Request.Builder().url(u).header("Authorization", authHeader(tokens.token())).build()).execute().use { r ->
            if (r.isSuccessful) BitmapFactory.decodeStream(r.body!!.byteStream()) else null
        }
    }.getOrNull()

    override suspend fun shareLink(path: FsPath): String? {
        call({ url(url("/resources/publish", "path" to disk(path))); put(ByteArray(0).toRequestBody(null)) }, path).close()
        return call({ url(url("/resources", "path" to disk(path), "fields" to "public_url")) }, path).json().str("public_url")
    }

    override suspend fun quota(): SpaceInfo? = runCatching {
        val j = call({ url(api) }).json()
        val total = j.long("total_space")!!
        SpaceInfo(total, total - j.long("used_space")!!)
    }.getOrNull()

    companion object {
        suspend fun userInfo(http: OkHttpClient, token: String): Pair<String, String> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            http.newCall(Request.Builder().url("https://login.yandex.ru/info?format=json").header("Authorization", "OAuth $token").build()).execute().use { r ->
                val j = cloudJson.parseToJsonElement(r.body!!.string()).jsonObject
                (j.str("real_name") ?: j.str("display_name") ?: "Yandex Disk") to (j.str("default_email") ?: j.str("login") ?: "")
            }
        }
    }
}
