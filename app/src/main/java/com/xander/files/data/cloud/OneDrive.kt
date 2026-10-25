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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.InputStream
import java.io.OutputStream
import java.net.URLEncoder

/** OneDrive (personal and business) via Microsoft Graph, using path-based addressing. */
class OneDrive(http: OkHttpClient, tokens: TokenSource) : HttpCloudDrive(http, tokens) {
    private val api = "https://graph.microsoft.com/v1.0/me/drive"

    private fun enc(p: String) = p.split('/').filter { it.isNotEmpty() }.joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
    private fun item(path: FsPath) = if (path.isRoot) "$api/root" else "$api/root:/${enc(path.path)}:"

    override fun mapError(code: Int, body: String, path: FsPath?): FsException = when {
        body.contains("quotaLimitReached") || body.contains("insufficientStorage") -> FsException.OutOfSpace()
        code == 409 || body.contains("nameAlreadyExists") -> path?.let { FsException.AlreadyExists(it) } ?: FsException.Generic("Exists")
        else -> super.mapError(code, body, path)
    }

    private fun entry(p: FsPath, j: JsonObject) = FileEntry(p, j.obj("folder") != null, j.long("size") ?: 0, isoTime(j.str("lastModifiedDateTime")),
        created = isoTime(j.str("createdDateTime")), backendId = j.str("id"),
        thumbnailUrl = (j["thumbnails"] as? JsonArray)?.firstOrNull()?.jsonObject?.obj("medium")?.str("url"))

    override suspend fun list(path: FsPath): List<FileEntry> {
        val out = ArrayList<FileEntry>()
        var url: String? = "${if (path.isRoot) "$api/root" else item(path)}/children?\$top=999&\$expand=thumbnails"
        while (url != null) {
            val j = call({ url(url!!) }, path).json()
            (j["value"] as? JsonArray)?.forEach { v -> val o = v.jsonObject; out += entry(path.child(o.str("name")!!), o) }
            url = j.str("@odata.nextLink")
        }
        return out
    }

    override suspend fun stat(path: FsPath): FileEntry = entry(path, call({ url(item(path)) }, path).json())

    override suspend fun openInput(path: FsPath, offset: Long): InputStream {
        val r = call({ url("${item(path)}/content"); if (offset > 0) header("Range", "bytes=$offset-") }, path)
        val s = r.body!!.byteStream()
        if (offset > 0 && r.code == 200) s.skip(offset)
        return ClosingInputStream(s) { r.close() }
    }

    override suspend fun openOutput(path: FsPath, size: Long): OutputStream = StreamingUpload(path.name) { input ->
        runBlocking {
            if (size in 0..(4L shl 20)) {
                val bytes = input.readBytes()
                call({ url("${item(path)}/content"); put(bytes.toRequestBody("application/octet-stream".toMediaType())) }, path).close()
                return@runBlocking
            }
            val session = call({
                url("${item(path)}/createUploadSession")
                post(jsonBody("{\"item\":{\"@microsoft.graph.conflictBehavior\":\"replace\"}}"))
            }, path).json().str("uploadUrl")!!
            val chunk = 320 * 1024 * 30 // 9.4 MiB, multiple of 320 KiB as required
            val buf = ByteArray(chunk)
            var offset = 0L
            // Unknown size: buffer one chunk ahead to know when we are at the end.
            var n = readFull(input, buf)
            while (true) {
                val next = ByteArray(chunk)
                val m = if (n == chunk) readFull(input, next) else 0
                val last = m == 0
                val total = if (size >= 0) size else if (last) offset + n else -1
                val range = "bytes $offset-${offset + n - 1}/${if (total >= 0) total.toString() else "*"}"
                http.newCall(Request.Builder().url(session).put(buf.copyOf(n).toRequestBody("application/octet-stream".toMediaType()))
                    .header("Content-Range", range).build()).execute().use { r ->
                    if (!r.isSuccessful) throw FsException.Generic("Upload failed (${r.code})")
                }
                offset += n
                if (last) break
                System.arraycopy(next, 0, buf, 0, m); n = m
            }
        }
    }

    private fun readFull(input: InputStream, buf: ByteArray): Int { var n = 0; while (n < buf.size) { val r = input.read(buf, n, buf.size - n); if (r < 0) break; n += r }; return n }

    override suspend fun mkdir(parent: FsPath, name: String): FsPath {
        val p = parent.child(name)
        call({
            url("${if (parent.isRoot) "$api/root" else item(parent)}/children")
            post(jsonBody(buildJsonObject { put("name", name); put("folder", buildJsonObject {}); put("@microsoft.graph.conflictBehavior", "fail") }.toString()))
        }, p).close()
        return p
    }

    override suspend fun rename(path: FsPath, newName: String): FsPath {
        call({ url(item(path)); patch(jsonBody(buildJsonObject { put("name", newName) }.toString())) }, path).close()
        return path.parent!!.child(newName)
    }

    override suspend fun delete(path: FsPath) = call({ url(item(path)); delete() }, path).close()

    override suspend fun move(src: FsPath, dstParent: FsPath, name: String): FsPath? {
        val parentRef = if (dstParent.isRoot) "/drive/root:" else "/drive/root:/${dstParent.path.trim('/')}"
        call({ url(item(src)); patch(jsonBody(buildJsonObject { put("name", name); put("parentReference", buildJsonObject { put("path", parentRef) }) }.toString())) }, src).close()
        return dstParent.child(name)
    }

    override suspend fun copy(src: FsPath, dstParent: FsPath, name: String): FsPath? {
        val parentId = call({ url(if (dstParent.isRoot) "$api/root" else item(dstParent)) }, dstParent).json().str("id")
        val r = call({ url("${item(src)}/copy"); post(jsonBody(buildJsonObject { put("name", name); put("parentReference", buildJsonObject { put("id", parentId) }) }.toString())) }, src)
        val monitor = r.use { it.header("Location") } ?: return dstParent.child(name)
        // Copy is asynchronous; poll the monitor (no auth header) until done.
        repeat(120) {
            val st = http.newCall(Request.Builder().url(monitor).build()).execute().use { cloudJson.parseToJsonElement(it.body!!.string()).jsonObject.str("status") }
            if (st == "completed") return dstParent.child(name)
            if (st == "failed") throw FsException.Generic("Copy failed")
            delay(1000)
        }
        return dstParent.child(name)
    }

    override suspend fun thumbnail(entry: FileEntry, sizePx: Int): Bitmap? = runCatching {
        val r = call({ url("${item(entry.path)}/thumbnails/0/${if (sizePx > 200) "large" else "medium"}/content") }, entry.path)
        r.use { BitmapFactory.decodeStream(it.body!!.byteStream()) }
    }.getOrNull()

    override suspend fun shareLink(path: FsPath): String? =
        call({ url("${item(path)}/createLink"); post(jsonBody("{\"type\":\"view\",\"scope\":\"anonymous\"}")) }, path).json().obj("link")?.str("webUrl")

    override suspend fun quota(): SpaceInfo? = runCatching {
        val q = call({ url(api) }).json().obj("quota")!!
        SpaceInfo(q.long("total")!!, q.long("remaining")!!)
    }.getOrNull()

    companion object {
        suspend fun userInfo(http: OkHttpClient, token: String): Pair<String, String> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            http.newCall(Request.Builder().url("https://graph.microsoft.com/v1.0/me").header("Authorization", "Bearer $token").build()).execute().use { r ->
                val j = cloudJson.parseToJsonElement(r.body!!.string()).jsonObject
                (j.str("displayName") ?: "OneDrive") to (j.str("mail") ?: j.str("userPrincipalName") ?: "")
            }
        }
    }
}
