package com.xander.files.data.cloud

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.xander.files.data.net.ClosingInputStream
import com.xander.files.data.net.StreamingUpload
import com.xander.files.domain.fs.FileEntry
import com.xander.files.domain.fs.FsException
import com.xander.files.domain.fs.FsPath
import com.xander.files.domain.fs.SpaceInfo
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

/** Dropbox API v2 (path based). */
class Dropbox(http: OkHttpClient, tokens: TokenSource) : HttpCloudDrive(http, tokens) {
    private val api = "https://api.dropboxapi.com/2"
    private val content = "https://content.dropboxapi.com/2"

    private fun dbx(path: FsPath) = if (path.isRoot) "" else path.path

    /** Dropbox-API-Arg must be ASCII; escape everything else. */
    private fun arg(o: JsonObject) = o.toString().map { if (it.code > 127) "\\u%04x".format(it.code) else it.toString() }.joinToString("")

    private suspend fun rpc(endpoint: String, body: JsonObject, path: FsPath?): JsonObject =
        call({ url("$api/$endpoint"); post(jsonBody(body.toString())) }, path).json()

    override fun mapError(code: Int, body: String, path: FsPath?): FsException = when {
        code == 409 && body.contains("not_found") -> path?.let { FsException.NotFound(it) } ?: FsException.Generic("Not found")
        code == 409 && body.contains("conflict") -> path?.let { FsException.AlreadyExists(it) } ?: FsException.Generic("Exists")
        code == 409 && body.contains("insufficient_space") -> FsException.OutOfSpace()
        else -> super.mapError(code, body, path)
    }

    private fun entry(parent: FsPath, m: JsonObject): FileEntry {
        val dir = m.str(".tag") == "folder"
        return FileEntry(parent.child(m.str("name")!!), dir, m.long("size") ?: 0, isoTime(m.str("server_modified")), backendId = m.str("id"))
    }

    override suspend fun list(path: FsPath): List<FileEntry> {
        val out = ArrayList<FileEntry>()
        var j = rpc("files/list_folder", buildJsonObject { put("path", dbx(path)); put("limit", 2000) }, path)
        while (true) {
            (j["entries"] as? JsonArray)?.forEach { out += entry(path, it.jsonObject) }
            if (j["has_more"]?.toString() != "true") break
            j = rpc("files/list_folder/continue", buildJsonObject { put("cursor", j.str("cursor")) }, path)
        }
        return out
    }

    override suspend fun stat(path: FsPath): FileEntry {
        if (path.isRoot) return FileEntry(path, true)
        val m = rpc("files/get_metadata", buildJsonObject { put("path", path.path) }, path)
        return entry(path.parent!!, m)
    }

    override suspend fun openInput(path: FsPath, offset: Long): InputStream {
        val r = call({
            url("$content/files/download"); post(ByteArray(0).toRequestBody(null))
            header("Dropbox-API-Arg", arg(buildJsonObject { put("path", path.path) }))
            if (offset > 0) header("Range", "bytes=$offset-")
        }, path)
        val s = r.body!!.byteStream()
        if (offset > 0 && r.code == 200) s.skip(offset)
        return ClosingInputStream(s) { r.close() }
    }

    /** Upload sessions in 8 MiB chunks (handles any size; small files finish in one call). */
    override suspend fun openOutput(path: FsPath, size: Long): OutputStream = StreamingUpload(path.name) { input ->
        runBlocking {
            val chunk = 8 * 1024 * 1024
            val buf = ByteArray(chunk)
            var offset = 0L
            var session: String? = null
            while (true) {
                var n = 0
                while (n < chunk) { val r = input.read(buf, n, chunk - n); if (r < 0) break; n += r }
                val last = n < chunk
                val body = buf.copyOf(n).toRequestBody("application/octet-stream".toMediaType())
                if (session == null && last) {
                    call({
                        url("$content/files/upload"); post(body)
                        header("Dropbox-API-Arg", arg(buildJsonObject { put("path", path.path); put("mode", "overwrite"); put("mute", true) }))
                    }, path).close()
                    break
                }
                if (session == null) {
                    session = call({ url("$content/files/upload_session/start"); post(body); header("Dropbox-API-Arg", "{\"close\":false}") }, path).json().str("session_id")
                } else if (!last) {
                    call({
                        url("$content/files/upload_session/append_v2"); post(body)
                        header("Dropbox-API-Arg", "{\"cursor\":{\"session_id\":\"$session\",\"offset\":$offset},\"close\":false}")
                    }, path).close()
                } else {
                    call({
                        url("$content/files/upload_session/finish"); post(body)
                        header("Dropbox-API-Arg", arg(buildJsonObject {
                            put("cursor", buildJsonObject { put("session_id", session); put("offset", offset) })
                            put("commit", buildJsonObject { put("path", path.path); put("mode", "overwrite"); put("mute", true) })
                        }))
                    }, path).close()
                    break
                }
                offset += n
            }
        }
    }

    override suspend fun mkdir(parent: FsPath, name: String): FsPath {
        val p = parent.child(name)
        rpc("files/create_folder_v2", buildJsonObject { put("path", p.path) }, p); return p
    }

    override suspend fun rename(path: FsPath, newName: String): FsPath =
        move(path, path.parent!!, newName)!!

    override suspend fun delete(path: FsPath) { rpc("files/delete_v2", buildJsonObject { put("path", path.path) }, path) }

    override suspend fun move(src: FsPath, dstParent: FsPath, name: String): FsPath? {
        val d = dstParent.child(name)
        rpc("files/move_v2", buildJsonObject { put("from_path", src.path); put("to_path", d.path) }, src); return d
    }

    override suspend fun copy(src: FsPath, dstParent: FsPath, name: String): FsPath? {
        val d = dstParent.child(name)
        rpc("files/copy_v2", buildJsonObject { put("from_path", src.path); put("to_path", d.path) }, src); return d
    }

    override suspend fun thumbnail(entry: FileEntry, sizePx: Int): Bitmap? = runCatching {
        val size = when { sizePx <= 64 -> "w64h64"; sizePx <= 128 -> "w128h128"; sizePx <= 256 -> "w256h256"; sizePx <= 480 -> "w480h320"; else -> "w640h480" }
        call({
            url("$content/files/get_thumbnail_v2"); post(ByteArray(0).toRequestBody(null))
            header("Dropbox-API-Arg", arg(buildJsonObject { put("resource", buildJsonObject { put(".tag", "path"); put("path", entry.path.path) }); put("size", size) }))
        }, entry.path).use { BitmapFactory.decodeStream(it.body!!.byteStream()) }
    }.getOrNull()

    override suspend fun shareLink(path: FsPath): String? {
        val existing = runCatching {
            (rpc("sharing/list_shared_links", buildJsonObject { put("path", path.path); put("direct_only", true) }, path)["links"] as? JsonArray)
                ?.firstOrNull()?.jsonObject?.str("url")
        }.getOrNull()
        return existing ?: rpc("sharing/create_shared_link_with_settings", buildJsonObject { put("path", path.path) }, path).str("url")
    }

    override suspend fun quota(): SpaceInfo? = runCatching {
        val j = call({ url("$api/users/get_space_usage"); post(ByteArray(0).toRequestBody(null)) }).json()
        val total = j.obj("allocation")?.long("allocated") ?: return null
        SpaceInfo(total, total - (j.long("used") ?: 0))
    }.getOrNull()

    companion object {
        suspend fun userInfo(http: OkHttpClient, token: String): Pair<String, String> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            http.newCall(Request.Builder().url("https://api.dropboxapi.com/2/users/get_current_account").header("Authorization", "Bearer $token")
                .post(ByteArray(0).toRequestBody(null)).build()).execute().use { r ->
                val j = cloudJson.parseToJsonElement(r.body!!.string()).jsonObject
                (j.obj("name")?.str("display_name") ?: "Dropbox") to (j.str("email") ?: "")
            }
        }
    }
}
