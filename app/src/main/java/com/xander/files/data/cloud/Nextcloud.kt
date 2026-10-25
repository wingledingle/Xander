package com.xander.files.data.cloud

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.xander.files.data.net.HttpClients
import com.xander.files.data.net.webdav.WebDavClient
import com.xander.files.domain.fs.FileEntry
import com.xander.files.domain.fs.FsException
import com.xander.files.domain.fs.FsPath
import com.xander.files.domain.fs.SpaceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import okhttp3.Credentials
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.InputStream
import java.io.OutputStream

/** Nextcloud / ownCloud via WebDAV (remote.php/dav/files/<user>) + OCS sharing + preview thumbnails. */
class Nextcloud(private val server: String, private val user: String, password: String) : CloudDrive {
    private val http: OkHttpClient = HttpClients.forConnection(user, password, trustSelfSigned = false)
    private val auth = Credentials.basic(user, password, Charsets.UTF_8)
    private val base = server.trimEnd('/')
    private val dav = WebDavClient(http, "$base/remote.php/dav/files/${java.net.URLEncoder.encode(user, "UTF-8").replace("+", "%20")}/")

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { try { block() } catch (e: Exception) { throw FsException.wrap(e) } }

    override suspend fun list(path: FsPath) = io { dav.list(path) }
    override suspend fun stat(path: FsPath) = io { dav.stat(path) }
    override suspend fun openInput(path: FsPath, offset: Long): InputStream = io { dav.get(path, offset) }
    override suspend fun openOutput(path: FsPath, size: Long): OutputStream = io { dav.put(path, size) }
    override suspend fun mkdir(parent: FsPath, name: String): FsPath = io { val p = parent.child(name); dav.mkcol(p); p }
    override suspend fun rename(path: FsPath, newName: String): FsPath = io {
        val np = path.parent!!.child(newName); dav.move(path, np, dir = dav.stat(path).isDirectory); np
    }
    override suspend fun delete(path: FsPath) = io { dav.delete(path) }
    override suspend fun move(src: FsPath, dstParent: FsPath, name: String): FsPath? = io {
        val d = dstParent.child(name); dav.move(src, d, dir = dav.stat(src).isDirectory); d
    }
    override suspend fun copy(src: FsPath, dstParent: FsPath, name: String): FsPath? = io {
        val d = dstParent.child(name); dav.move(src, d, copy = true, dir = dav.stat(src).isDirectory); d
    }

    override suspend fun thumbnail(entry: FileEntry, sizePx: Int): Bitmap? = runCatching {
        io {
            val id = entry.backendId ?: return@io null
            http.newCall(Request.Builder().url("$base/index.php/core/preview?fileId=$id&x=$sizePx&y=$sizePx&a=1").header("Authorization", auth).build()).execute().use { r ->
                if (r.isSuccessful) BitmapFactory.decodeStream(r.body!!.byteStream()) else null
            }
        }
    }.getOrNull()

    override suspend fun shareLink(path: FsPath): String? = io {
        val form = FormBody.Builder().add("path", path.path).add("shareType", "3").add("permissions", "1").build()
        http.newCall(Request.Builder().url("$base/ocs/v2.php/apps/files_sharing/api/v1/shares?format=json").header("Authorization", auth)
            .header("OCS-APIRequest", "true").post(form).build()).execute().use { r ->
            if (!r.isSuccessful) throw FsException.Generic("Sharing failed (${r.code})")
            cloudJson.parseToJsonElement(r.body!!.string()).jsonObject.obj("ocs")?.obj("data")?.str("url")
        }
    }

    override suspend fun quota(): SpaceInfo? = io { dav.quota(FsPath(FsPath.SCHEME_CLOUD, "", "/")) }

    companion object {
        data class LoginFlow(val loginUrl: String, val pollEndpoint: String, val token: String)

        /** Nextcloud Login Flow v2: open [LoginFlow.loginUrl] in the browser, then [poll] until approved. */
        suspend fun startLogin(server: String): LoginFlow = withContext(Dispatchers.IO) {
            val base = normalize(server)
            HttpClients.shared.newCall(Request.Builder().url("$base/index.php/login/v2").post(FormBody.Builder().build()).header("User-Agent", "Xander Files").build()).execute().use { r ->
                if (!r.isSuccessful) throw FsException.Generic("This server does not support Nextcloud login (HTTP ${r.code})")
                val j = cloudJson.parseToJsonElement(r.body!!.string()).jsonObject
                LoginFlow(j.str("login")!!, j.obj("poll")!!.str("endpoint")!!, j.obj("poll")!!.str("token")!!)
            }
        }

        /** Returns (server, loginName, appPassword) once the user approved in the browser. */
        suspend fun poll(flow: LoginFlow, timeoutMs: Long = 10 * 60_000): Triple<String, String, String> = withContext(Dispatchers.IO) {
            val end = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < end) {
                HttpClients.shared.newCall(Request.Builder().url(flow.pollEndpoint).post(FormBody.Builder().add("token", flow.token).build()).build()).execute().use { r ->
                    if (r.code == 200) {
                        val j = cloudJson.parseToJsonElement(r.body!!.string()).jsonObject
                        return@withContext Triple(j.str("server")!!, j.str("loginName")!!, j.str("appPassword")!!)
                    }
                }
                delay(2000)
            }
            throw FsException.Generic("Sign-in timed out")
        }

        fun normalize(server: String): String {
            val s = server.trim().trimEnd('/')
            return if (s.startsWith("http://") || s.startsWith("https://")) s else "https://$s"
        }
    }
}
