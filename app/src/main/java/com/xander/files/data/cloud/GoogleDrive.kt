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
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.InputStream
import java.io.OutputStream

/**
 * Google Drive v3. Root shows "My Drive", "Shared drives" and "Shared with me". Google Docs/Sheets/Slides
 * are exported (DOCX/XLSX/PPTX) when read.
 */
class GoogleDrive(http: OkHttpClient, tokens: TokenSource) : HttpCloudDrive(http, tokens) {
    private val api = "https://www.googleapis.com/drive/v3"
    private val fields = "id,name,mimeType,size,modifiedTime,createdTime,thumbnailLink,webViewLink,parents,driveId"

    private object Roots { const val MY = "My Drive"; const val SHARED_DRIVES = "Shared drives"; const val SHARED_WITH_ME = "Shared with me" }

    private val docExports = mapOf(
        "application/vnd.google-apps.document" to ("application/vnd.openxmlformats-officedocument.wordprocessingml.document" to "docx"),
        "application/vnd.google-apps.spreadsheet" to ("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" to "xlsx"),
        "application/vnd.google-apps.presentation" to ("application/vnd.openxmlformats-officedocument.presentationml.presentation" to "pptx"),
        "application/vnd.google-apps.drawing" to ("application/pdf" to "pdf"),
    )

    private val FOLDER = "application/vnd.google-apps.folder"
    private val meta = java.util.concurrent.ConcurrentHashMap<String, JsonObject>()

    private fun displayName(f: JsonObject): String {
        val name = f.str("name").orEmpty().replace('/', '_')
        val export = docExports[f.str("mimeType")]
        return if (export != null && !name.endsWith(".${export.second}")) "$name.${export.second}" else name
    }

    private fun entry(path: FsPath, f: JsonObject): FileEntry {
        val dir = f.str("mimeType") == FOLDER
        return FileEntry(path, dir, f.long("size") ?: 0, isoTime(f.str("modifiedTime")), created = isoTime(f.str("createdTime")),
            backendId = f.str("id"), thumbnailUrl = f.str("thumbnailLink"))
    }

    private suspend fun query(q: String, driveId: String? = null, sharedWithMe: Boolean = false): List<JsonObject> {
        val out = ArrayList<JsonObject>()
        var page: String? = null
        do {
            val u = "$api/files".toHttpUrl().newBuilder()
                .addQueryParameter("q", if (sharedWithMe) "sharedWithMe=true and trashed=false" else q)
                .addQueryParameter("fields", "nextPageToken,files($fields)")
                .addQueryParameter("pageSize", "1000")
                .addQueryParameter("supportsAllDrives", "true").addQueryParameter("includeItemsFromAllDrives", "true")
                .apply { if (driveId != null) { addQueryParameter("corpora", "drive"); addQueryParameter("driveId", driveId) } }
                .apply { if (page != null) addQueryParameter("pageToken", page) }.build()
            val j = call({ url(u) }).json()
            (j["files"] as? JsonArray)?.forEach { out += it.jsonObject }
            page = j.str("nextPageToken")
        } while (page != null)
        return out
    }

    private suspend fun sharedDrives(): List<JsonObject> {
        val out = ArrayList<JsonObject>(); var page: String? = null
        do {
            val u = "$api/drives".toHttpUrl().newBuilder().addQueryParameter("pageSize", "100").apply { if (page != null) addQueryParameter("pageToken", page) }.build()
            val j = call({ url(u) }).json()
            (j["drives"] as? JsonArray)?.forEach { out += it.jsonObject }
            page = j.str("nextPageToken")
        } while (page != null)
        return out
    }

    /** Resolves a display path to a file id (and records metadata). */
    private suspend fun idOf(path: FsPath): String {
        ids[path.path]?.let { return it }
        val segs = path.path.removePrefix("/").split('/').filter { it.isNotEmpty() }
        if (segs.isEmpty()) throw FsException.NotADirectory(path)
        var cur: FsPath
        var id: String
        var driveId: String? = null
        var start: Int
        when (segs[0]) {
            Roots.MY -> { id = "root"; cur = FsPath(path.scheme, path.authority, "/${Roots.MY}"); start = 1 }
            Roots.SHARED_DRIVES -> {
                if (segs.size == 1) throw FsException.NotADirectory(path)
                val d = sharedDrives().firstOrNull { it.str("name") == segs[1] } ?: throw FsException.NotFound(path)
                id = d.str("id")!!; driveId = id; cur = FsPath(path.scheme, path.authority, "/${Roots.SHARED_DRIVES}/${segs[1]}"); start = 2
            }
            Roots.SHARED_WITH_ME -> {
                if (segs.size == 1) throw FsException.NotADirectory(path)
                val f = query("", sharedWithMe = true).firstOrNull { displayName(it) == segs[1] } ?: throw FsException.NotFound(path)
                id = f.str("id")!!; meta[id] = f; cur = FsPath(path.scheme, path.authority, "/${Roots.SHARED_WITH_ME}/${segs[1]}"); start = 2
            }
            else -> throw FsException.NotFound(path)
        }
        ids[cur.path] = id
        for (i in start until segs.size) {
            cur = cur.child(segs[i])
            ids[cur.path]?.let { id = it; continue }
            val kids = query("'$id' in parents and trashed=false", driveId)
            kids.forEach { k -> val p = cur.parent!!.child(displayName(k)); ids[p.path] = k.str("id")!!; meta[k.str("id")!!] = k }
            id = ids[cur.path] ?: throw FsException.NotFound(path)
        }
        return id
    }

    override suspend fun list(path: FsPath): List<FileEntry> {
        val segs = path.path.removePrefix("/").split('/').filter { it.isNotEmpty() }
        return when {
            segs.isEmpty() -> listOf(Roots.MY, Roots.SHARED_DRIVES, Roots.SHARED_WITH_ME).map { FileEntry(path.child(it), true) }
            segs.size == 1 && segs[0] == Roots.SHARED_DRIVES -> sharedDrives().map { FileEntry(path.child(it.str("name")!!.replace('/', '_')), true, backendId = it.str("id")) }
            segs.size == 1 && segs[0] == Roots.SHARED_WITH_ME -> query("", sharedWithMe = true).map { f ->
                val p = path.child(displayName(f)); ids[p.path] = f.str("id")!!; meta[f.str("id")!!] = f; entry(p, f)
            }
            else -> {
                val id = idOf(path)
                val driveId = meta[id]?.str("driveId") ?: if (segs[0] == Roots.SHARED_DRIVES) runCatching { idOf(FsPath(path.scheme, path.authority, "/${segs[0]}/${segs[1]}")) }.getOrNull() else null
                query("'$id' in parents and trashed=false", driveId).map { f ->
                    val p = path.child(displayName(f)); ids[p.path] = f.str("id")!!; meta[f.str("id")!!] = f; entry(p, f)
                }
            }
        }
    }

    private suspend fun file(id: String): JsonObject =
        call({ url("$api/files/$id?fields=$fields&supportsAllDrives=true") }).json().also { meta[id] = it }

    override suspend fun stat(path: FsPath): FileEntry {
        val segs = path.path.removePrefix("/").split('/').filter { it.isNotEmpty() }
        if (segs.size <= 1 || (segs[0] == Roots.SHARED_DRIVES && segs.size == 2)) {
            if (segs.isEmpty() || segs[0] in setOf(Roots.MY, Roots.SHARED_DRIVES, Roots.SHARED_WITH_ME)) return FileEntry(path, true)
        }
        val id = idOf(path)
        return entry(path, meta[id] ?: file(id))
    }

    override suspend fun openInput(path: FsPath, offset: Long): InputStream {
        val id = idOf(path)
        val m = meta[id] ?: file(id)
        val export = docExports[m.str("mimeType")]
        val url = if (export != null) "$api/files/$id/export?mimeType=${export.first}" else "$api/files/$id?alt=media&supportsAllDrives=true"
        val r = call({ url(url); if (offset > 0 && export == null) header("Range", "bytes=$offset-") }, path)
        val s = r.body!!.byteStream()
        if (offset > 0 && (export != null || r.code == 200)) s.skip(offset)
        return ClosingInputStream(s) { r.close() }
    }

    private suspend fun parentIdFor(path: FsPath): Pair<String, String?> {
        val parent = path.parent ?: throw FsException.PermissionDenied(path)
        val segs = parent.path.removePrefix("/").split('/').filter { it.isNotEmpty() }
        if (segs.isEmpty() || (segs[0] != Roots.MY && segs.size < 2) || (segs[0] == Roots.SHARED_DRIVES && segs.size < 2)) throw FsException.PermissionDenied(path)
        val id = if (segs == listOf(Roots.MY)) "root" else idOf(parent)
        return id to meta[id]?.str("driveId")
    }

    override suspend fun openOutput(path: FsPath, size: Long): OutputStream {
        val existing = runCatching { idOf(path) }.getOrNull()
        val (parentId, _) = parentIdFor(path)
        val metadata = buildJsonObject {
            put("name", path.name)
            if (existing == null) put("parents", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(parentId)) })
        }.toString()
        val initUrl = if (existing != null) "https://www.googleapis.com/upload/drive/v3/files/$existing?uploadType=resumable&supportsAllDrives=true"
            else "https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&supportsAllDrives=true"
        val session = call({
            url(initUrl)
            method(if (existing != null) "PATCH" else "POST", jsonBody(metadata))
            if (size >= 0) header("X-Upload-Content-Length", size.toString())
        }, path).use { it.header("Location") } ?: throw FsException.Generic("Drive did not return an upload session")
        return StreamingUpload(path.name) { input -> uploadChunks(session, input, size, path) }
    }

    /** Resumable upload in 8 MiB chunks (multiple of 256 KiB as Drive requires). */
    private fun uploadChunks(session: String, input: InputStream, size: Long, path: FsPath) {
        val chunk = 8 * 1024 * 1024
        val buf = ByteArray(chunk)
        var offset = 0L
        while (true) {
            var n = 0
            while (n < chunk) { val r = input.read(buf, n, chunk - n); if (r < 0) break; n += r }
            val last = n < chunk
            val total = if (last) (offset + n).toString() else if (size >= 0) size.toString() else "*"
            val range = if (n == 0) "bytes */$total" else "bytes $offset-${offset + n - 1}/$total"
            val req = Request.Builder().url(session).put(buf.copyOf(n).toRequestBody("application/octet-stream".toMediaType()))
                .header("Content-Range", range).build()
            http.newCall(req).execute().use { r ->
                if (r.code !in listOf(200, 201, 308)) throw FsException.Generic("Upload failed (${r.code})")
            }
            offset += n
            if (last) break
        }
        ids.remove(path.path)
    }

    override suspend fun mkdir(parent: FsPath, name: String): FsPath {
        val p = parent.child(name)
        if (runCatching { idOf(p) }.isSuccess) throw FsException.AlreadyExists(p)
        val (pid, _) = parentIdFor(p)
        val body = buildJsonObject { put("name", name); put("mimeType", FOLDER); put("parents", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(pid)) }) }
        val f = call({ url("$api/files?fields=$fields&supportsAllDrives=true"); post(jsonBody(body.toString())) }, p).json()
        ids[p.path] = f.str("id")!!; meta[f.str("id")!!] = f
        return p
    }

    override suspend fun rename(path: FsPath, newName: String): FsPath {
        val id = idOf(path)
        call({ url("$api/files/$id?supportsAllDrives=true"); patch(jsonBody(buildJsonObject { put("name", newName) }.toString())) }, path).close()
        invalidate()
        return path.parent!!.child(newName)
    }

    override suspend fun delete(path: FsPath) {
        val id = idOf(path)
        // Moves to Drive's trash (recoverable from drive.google.com), like the Drive app does.
        call({ url("$api/files/$id?supportsAllDrives=true"); patch(jsonBody("{\"trashed\":true}")) }, path).close()
        invalidate()
    }

    override suspend fun move(src: FsPath, dstParent: FsPath, name: String): FsPath? {
        val id = idOf(src)
        val oldParent = meta[id]?.let { (it["parents"] as? JsonArray)?.firstOrNull()?.let { p -> (p as kotlinx.serialization.json.JsonPrimitive).content } } ?: idOf(src.parent!!)
        val newParent = if (dstParent.path == "/${Roots.MY}") "root" else idOf(dstParent)
        call({
            url("$api/files/$id?addParents=$newParent&removeParents=$oldParent&supportsAllDrives=true")
            patch(jsonBody(buildJsonObject { put("name", name) }.toString()))
        }, src).close()
        invalidate()
        return dstParent.child(name)
    }

    override suspend fun copy(src: FsPath, dstParent: FsPath, name: String): FsPath? {
        val id = idOf(src)
        if (meta[id]?.str("mimeType") == FOLDER) return null
        val newParent = if (dstParent.path == "/${Roots.MY}") "root" else idOf(dstParent)
        call({ url("$api/files/$id/copy?supportsAllDrives=true"); post(jsonBody(buildJsonObject { put("name", name); put("parents", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(newParent)) }) }.toString())) }, src).close()
        return dstParent.child(name)
    }

    override suspend fun thumbnail(entry: FileEntry, sizePx: Int): Bitmap? {
        val link = entry.thumbnailUrl ?: return null
        val u = link.replace(Regex("=s\\d+$"), "=s$sizePx")
        return runCatching {
            http.newCall(Request.Builder().url(u).header("Authorization", authHeader(tokens.token())).build()).execute().use { r ->
                if (r.isSuccessful) BitmapFactory.decodeStream(r.body!!.byteStream()) else null
            }
        }.getOrNull()
    }

    override suspend fun shareLink(path: FsPath): String? {
        val id = idOf(path)
        call({ url("$api/files/$id/permissions?supportsAllDrives=true"); post(jsonBody("{\"role\":\"reader\",\"type\":\"anyone\"}")) }, path).close()
        return file(id).str("webViewLink")
    }

    override suspend fun quota(): SpaceInfo? = runCatching {
        val q = call({ url("$api/about?fields=storageQuota") }).json().obj("storageQuota")!!
        val limit = q.long("limit") ?: return null
        SpaceInfo(limit, limit - (q.long("usage") ?: 0))
    }.getOrNull()

    companion object {
        suspend fun userInfo(http: OkHttpClient, token: String): Pair<String, String> {
            val j = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                http.newCall(Request.Builder().url("https://www.googleapis.com/drive/v3/about?fields=user").header("Authorization", "Bearer $token").build()).execute()
                    .use { cloudJson.parseToJsonElement(it.body!!.string()).jsonObject }
            }
            val u = j.obj("user")
            return (u?.str("displayName") ?: "Google Drive") to (u?.str("emailAddress") ?: "")
        }
    }
}
