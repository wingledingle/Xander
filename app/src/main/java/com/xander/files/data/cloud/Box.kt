package com.xander.files.data.cloud

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.xander.files.data.net.ClosingInputStream
import com.xander.files.data.net.StreamingUpload
import com.xander.files.domain.fs.FileEntry
import com.xander.files.domain.fs.FsException
import com.xander.files.domain.fs.FsPath
import com.xander.files.domain.fs.SpaceInfo
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** Box Content API (id based; root folder id 0). Files over 50 MB use chunked upload sessions. */
class Box(http: OkHttpClient, tokens: TokenSource) : HttpCloudDrive(http, tokens) {
    private val api = "https://api.box.com/2.0"
    private val upload = "https://upload.box.com/api/2.0"
    private val types = java.util.concurrent.ConcurrentHashMap<String, String>()

    init { ids["/"] = "0"; types["0"] = "folder" }
    override fun invalidate() { ids.clear(); ids["/"] = "0" }

    override fun mapError(code: Int, body: String, path: FsPath?): FsException = when {
        body.contains("item_name_in_use") -> path?.let { FsException.AlreadyExists(it) } ?: FsException.Generic("Exists")
        body.contains("storage_limit_exceeded") -> FsException.OutOfSpace()
        else -> super.mapError(code, body, path)
    }

    private suspend fun children(folderId: String, dir: FsPath): List<FileEntry> {
        val out = ArrayList<FileEntry>()
        var offset = 0
        while (true) {
            val j = call({ url("$api/folders/$folderId/items?fields=id,type,name,size,modified_at,created_at&limit=1000&offset=$offset") }, dir).json()
            val entries = (j["entries"] as? JsonArray).orEmpty().map { it.jsonObject }
            entries.forEach { e ->
                val p = dir.child(e.str("name")!!)
                ids[p.path] = e.str("id")!!; types[e.str("id")!!] = e.str("type")!!
                out += FileEntry(p, e.str("type") == "folder", e.long("size") ?: 0, isoTime(e.str("modified_at")), created = isoTime(e.str("created_at")), backendId = e.str("id"))
            }
            offset += entries.size
            if (entries.isEmpty() || offset >= (j.long("total_count") ?: 0)) break
        }
        return out
    }

    private suspend fun idOf(path: FsPath): String {
        ids[path.path]?.let { return it }
        val parent = path.parent ?: return "0"
        children(idOf(parent), parent)
        return ids[path.path] ?: throw FsException.NotFound(path)
    }

    private fun kind(id: String) = types[id] ?: "file"

    override suspend fun list(path: FsPath) = children(idOf(path), path)

    override suspend fun stat(path: FsPath): FileEntry {
        if (path.isRoot) return FileEntry(path, true)
        val id = idOf(path)
        val j = call({ url("$api/${kind(id)}s/$id?fields=id,type,name,size,modified_at,created_at") }, path).json()
        return FileEntry(path, j.str("type") == "folder", j.long("size") ?: 0, isoTime(j.str("modified_at")), created = isoTime(j.str("created_at")), backendId = id)
    }

    override suspend fun openInput(path: FsPath, offset: Long): InputStream {
        val fid = idOf(path)
        val r = call({ url("$api/files/$fid/content"); if (offset > 0) header("Range", "bytes=$offset-") }, path)
        val s = r.body!!.byteStream()
        if (offset > 0 && r.code == 200) s.skip(offset)
        return ClosingInputStream(s) { r.close() }
    }

    override suspend fun openOutput(path: FsPath, size: Long): OutputStream {
        val parentId = idOf(path.parent!!)
        val existing = runCatching { idOf(path) }.getOrNull()?.takeIf { kind(it) == "file" }
        return StreamingUpload(path.name) { input ->
            runBlocking {
                if (size in 0..(50L shl 20) || size < 0 && existing != null) simpleUpload(path, parentId, existing, input)
                else if (size < 0) simpleUpload(path, parentId, existing, input)
                else chunkedUpload(path, parentId, existing, input, size)
                ids.remove(path.path)
            }
        }
    }

    private suspend fun simpleUpload(path: FsPath, parentId: String, existing: String?, input: InputStream) {
        val bytes = input.readBytes()
        val attrs = buildJsonObject { put("name", path.name); put("parent", buildJsonObject { put("id", parentId) }) }.toString()
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("attributes", attrs)
            .addFormDataPart("file", path.name, bytes.toRequestBody("application/octet-stream".toMediaType())).build()
        call({ url(if (existing != null) "$upload/files/$existing/content" else "$upload/files/content"); post(body) }, path).close()
    }

    private suspend fun chunkedUpload(path: FsPath, parentId: String, existing: String?, input: InputStream, size: Long) {
        val session = call({
            url(if (existing != null) "$upload/files/$existing/upload_sessions" else "$upload/files/upload_sessions")
            post(jsonBody(buildJsonObject { if (existing == null) { put("folder_id", parentId); put("file_name", path.name) }; put("file_size", size) }.toString()))
        }, path).json()
        val sid = session.str("id")!!
        val part = session.long("part_size")!!.toInt()
        val whole = MessageDigest.getInstance("SHA-1")
        val parts = ArrayList<JsonObject>()
        val buf = ByteArray(part)
        var offset = 0L
        while (offset < size) {
            var n = 0
            while (n < part && offset + n < size) { val r = input.read(buf, n, (part - n).coerceAtMost((size - offset - n).toInt())); if (r < 0) break; n += r }
            if (n == 0) break
            val chunk = buf.copyOf(n)
            whole.update(chunk)
            val sha = Base64.encodeToString(MessageDigest.getInstance("SHA-1").digest(chunk), Base64.NO_WRAP)
            val j = call({
                url("$upload/files/upload_sessions/$sid"); put(chunk.toRequestBody("application/octet-stream".toMediaType()))
                header("Content-Range", "bytes $offset-${offset + n - 1}/$size"); header("Digest", "sha=$sha")
            }, path).json()
            parts += j.obj("part")!!
            offset += n
        }
        call({
            url("$upload/files/upload_sessions/$sid/commit")
            post(jsonBody(buildJsonObject { put("parts", buildJsonArray { parts.forEach { add(it) } }) }.toString()))
            header("Digest", "sha=" + Base64.encodeToString(whole.digest(), Base64.NO_WRAP))
        }, path).close()
    }

    override suspend fun mkdir(parent: FsPath, name: String): FsPath {
        val p = parent.child(name)
        val pid = idOf(parent)
        val j = call({ url("$api/folders"); post(jsonBody(buildJsonObject { put("name", name); put("parent", buildJsonObject { put("id", pid) }) }.toString())) }, p).json()
        ids[p.path] = j.str("id")!!; types[j.str("id")!!] = "folder"
        return p
    }

    override suspend fun rename(path: FsPath, newName: String): FsPath {
        val id = idOf(path)
        call({ url("$api/${kind(id)}s/$id"); put(jsonBody(buildJsonObject { put("name", newName) }.toString())) }, path).close()
        invalidate(); return path.parent!!.child(newName)
    }

    override suspend fun delete(path: FsPath) {
        val id = idOf(path)
        call({ url("$api/${kind(id)}s/$id${if (kind(id) == "folder") "?recursive=true" else ""}"); delete() }, path).close()
        invalidate()
    }

    override suspend fun move(src: FsPath, dstParent: FsPath, name: String): FsPath? {
        val id = idOf(src)
        val dst = idOf(dstParent)
        call({ url("$api/${kind(id)}s/$id"); put(jsonBody(buildJsonObject { put("name", name); put("parent", buildJsonObject { put("id", dst) }) }.toString())) }, src).close()
        invalidate(); return dstParent.child(name)
    }

    override suspend fun copy(src: FsPath, dstParent: FsPath, name: String): FsPath? {
        val id = idOf(src)
        val dst = idOf(dstParent)
        call({ url("$api/${kind(id)}s/$id/copy"); post(jsonBody(buildJsonObject { put("name", name); put("parent", buildJsonObject { put("id", dst) }) }.toString())) }, src).close()
        return dstParent.child(name)
    }

    override suspend fun thumbnail(entry: FileEntry, sizePx: Int): Bitmap? = runCatching {
        val id = idOf(entry.path)
        val s = sizePx.coerceIn(32, 320)
        call({ url("$api/files/$id/thumbnail.png?min_height=$s&min_width=$s") }, entry.path).use { r ->
            if (r.code == 200) BitmapFactory.decodeStream(r.body!!.byteStream()) else null
        }
    }.getOrNull()

    override suspend fun shareLink(path: FsPath): String? {
        val id = idOf(path)
        return call({ url("$api/${kind(id)}s/$id?fields=shared_link"); put(jsonBody("{\"shared_link\":{\"access\":\"open\"}}")) }, path).json()
            .obj("shared_link")?.str("url")
    }

    override suspend fun quota(): SpaceInfo? = runCatching {
        val j = call({ url("$api/users/me?fields=space_amount,space_used") }).json()
        val total = j.long("space_amount")!!
        SpaceInfo(total, total - j.long("space_used")!!)
    }.getOrNull()

    companion object {
        suspend fun userInfo(http: OkHttpClient, token: String): Pair<String, String> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            http.newCall(Request.Builder().url("https://api.box.com/2.0/users/me").header("Authorization", "Bearer $token").build()).execute().use { r ->
                val j = cloudJson.parseToJsonElement(r.body!!.string()).jsonObject
                (j.str("name") ?: "Box") to (j.str("login") ?: "")
            }
        }
    }
}
