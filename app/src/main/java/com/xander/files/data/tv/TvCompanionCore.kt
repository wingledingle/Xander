package com.xander.files.data.tv

import com.xander.files.data.server.HttpRequest
import com.xander.files.data.server.HttpResponse
import com.xander.files.data.server.createFileOrReplace
import com.xander.files.domain.fs.FileSystemProvider
import com.xander.files.domain.fs.FsException
import com.xander.files.domain.fs.FsPath
import com.xander.files.domain.fs.MimeTypes
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

interface TvAppsBackend {
    fun list(): List<TvApp>
    fun icon(pkg: String): ByteArray?
    fun launch(pkg: String): Boolean
    /** Opens the system uninstall confirmation on the TV screen. */
    fun uninstall(pkg: String): Boolean
    /** Starts installing [apk]; the TV shows the system confirmation. */
    fun install(apk: File): Boolean
    fun apkFile(pkg: String): File?
}

interface TvInputBackend {
    val available: Boolean
    fun key(key: TvKey): Boolean
    fun text(text: String): Boolean
}

interface TvPairing {
    val deviceName: String
    fun code(): String
    fun failedAttempt()
    fun issueToken(clientName: String): String
    fun valid(token: String?): Boolean
}

/**
 * HTTP API served by the TV build: pairing, file access to TV storage, app management and remote input.
 * Every route except /pair and /hello needs the token handed out at pairing.
 */
class TvCompanionCore(
    private val fs: FileSystemProvider,
    private val scheme: String,
    private val info: () -> TvInfo,
    private val apps: TvAppsBackend,
    private val input: TvInputBackend,
    private val pairing: TvPairing,
    private val cacheDir: File,
) {
    private fun p(path: String?) = FsPath(scheme, "", FsPath.normalize(path ?: "/"))
    private fun json(o: Any) = HttpResponse.text(200, o.toString(), "application/json; charset=utf-8")
    private fun ok() = json(JSONObject().put("ok", true))

    suspend fun handle(r: HttpRequest): HttpResponse {
        val q = r.query
        try {
            when (r.path) {
                "/hello" -> return json(JSONObject().put("name", pairing.deviceName).put("app", "xander-tv").put("v", 1))
                "/pair" -> {
                    if (r.method != "POST") return HttpResponse.text(405, "POST")
                    if (q["code"] != pairing.code()) { pairing.failedAttempt(); return HttpResponse.text(403, "Wrong code") }
                    return json(JSONObject().put("token", pairing.issueToken(q["client"] ?: "phone")).put("name", pairing.deviceName))
                }
            }
            if (!pairing.valid(r.header(TvProtocol.TOKEN_HEADER))) return HttpResponse.text(401, "Not paired")
            return when (r.path) {
                "/info" -> info().let { i -> json(JSONObject().put("name", i.name).put("model", i.model).put("android", i.android).put("root", i.storageRoot).put("total", i.total).put("free", i.free).put("remote", i.remote)) }
                "/fs/list" -> json(JSONArray().apply { fs.list(p(q["path"])).forEach { put(TvProtocol.entryJson(it)) } })
                "/fs/stat" -> json(TvProtocol.entryJson(fs.stat(p(q["path"]))))
                "/fs/read" -> {
                    val path = p(q["path"]); val e = fs.stat(path)
                    val off = q["offset"]?.toLongOrNull() ?: 0
                    val input = fs.openInput(path, off)
                    HttpResponse(200, linkedMapOf("Content-Type" to MimeTypes.fromName(e.name)), (e.size - off).coerceAtLeast(0)) { out -> input.use { it.copyTo(out, 64 * 1024) } }
                }
                "/fs/write" -> {
                    val path = p(q["path"])
                    val target = fs.createFileOrReplace(path.parent ?: p("/"), path.name)
                    fs.openOutput(target, r.header("content-length")?.toLongOrNull() ?: -1).use { r.body.copyTo(it, 64 * 1024) }
                    ok()
                }
                "/fs/mkdir" -> { fs.mkdir(p(q["path"]), q["name"]!!); ok() }
                "/fs/rename" -> { fs.rename(p(q["path"]), q["name"]!!); ok() }
                "/fs/delete" -> { fs.delete(p(q["path"])); ok() }
                "/fs/move" -> { (fs.moveInternal(p(q["path"]), p(q["to"]), q["name"]!!) ?: throw FsException.Unsupported("move")); ok() }
                "/apps" -> json(JSONArray().apply { apps.list().forEach { put(TvProtocol.appJson(it)) } })
                "/apps/icon" -> apps.icon(q["pkg"]!!)?.let { b -> HttpResponse(200, linkedMapOf("Content-Type" to "image/png", "Cache-Control" to "max-age=86400"), b.size.toLong()) { it.write(b) } } ?: HttpResponse.text(404, "No icon")
                "/apps/launch" -> if (apps.launch(q["pkg"]!!)) ok() else HttpResponse.text(404, "Cannot launch")
                "/apps/uninstall" -> if (apps.uninstall(q["pkg"]!!)) ok() else HttpResponse.text(500, "Cannot uninstall")
                "/apps/apk" -> apps.apkFile(q["pkg"]!!)?.let { f -> HttpResponse(200, linkedMapOf("Content-Type" to "application/vnd.android.package-archive"), f.length()) { o -> f.inputStream().use { it.copyTo(o) } } } ?: HttpResponse.text(404, "Not found")
                "/apps/install" -> {
                    val f = File(cacheDir, "tv-install-${System.currentTimeMillis()}.apk")
                    f.outputStream().use { r.body.copyTo(it, 64 * 1024) }
                    if (apps.install(f)) ok() else HttpResponse.text(500, "Install could not start")
                }
                "/input/key" -> {
                    if (!input.available) return HttpResponse.text(409, "Turn on the Xander remote control service in the TV's Accessibility settings")
                    val k = runCatching { TvKey.valueOf(q["key"]!!) }.getOrNull() ?: return HttpResponse.text(400, "Unknown key")
                    if (input.key(k)) ok() else HttpResponse.text(500, "Key not supported on this TV")
                }
                "/input/text" -> if (input.available && input.text(q["text"].orEmpty())) ok() else HttpResponse.text(409, "Focus a text field on the TV first")
                else -> HttpResponse.text(404, "Unknown route")
            }
        } catch (e: FsException.NotFound) {
            return HttpResponse.text(404, e.message ?: "Not found")
        } catch (e: NullPointerException) {
            return HttpResponse.text(400, "Missing parameter")
        } catch (e: Exception) {
            return HttpResponse.text(500, FsException.wrap(e).message ?: "Error")
        }
    }
}
