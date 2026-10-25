package com.xander.files.data.cloud

import android.graphics.Bitmap
import com.xander.files.domain.fs.FileEntry
import com.xander.files.domain.fs.FsException
import com.xander.files.domain.fs.FsPath
import com.xander.files.domain.fs.SpaceInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** One signed-in cloud account. Paths are display paths ("/Photos/2024"); drives map them to ids. */
interface CloudDrive {
    suspend fun list(path: FsPath): List<FileEntry>
    suspend fun stat(path: FsPath): FileEntry
    suspend fun openInput(path: FsPath, offset: Long): InputStream
    suspend fun openOutput(path: FsPath, size: Long): OutputStream
    suspend fun mkdir(parent: FsPath, name: String): FsPath
    suspend fun rename(path: FsPath, newName: String): FsPath
    suspend fun delete(path: FsPath)
    suspend fun move(src: FsPath, dstParent: FsPath, name: String): FsPath?
    suspend fun copy(src: FsPath, dstParent: FsPath, name: String): FsPath? = null
    suspend fun thumbnail(entry: FileEntry, sizePx: Int): Bitmap? = null
    suspend fun shareLink(path: FsPath): String? = null
    suspend fun quota(): SpaceInfo? = null
    fun invalidate() {}
}

val cloudJson = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }
val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull
fun JsonObject.long(k: String): Long? = (this[k] as? JsonPrimitive)?.longOrNull ?: str(k)?.toLongOrNull()
fun JsonObject.obj(k: String): JsonObject? = this[k] as? JsonObject
fun JsonElement.o(): JsonObject = jsonObject
fun isoTime(s: String?): Long = s?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0

fun jsonBody(s: String) = s.toRequestBody(JSON_MEDIA)

/** Shared HTTP plumbing with bearer tokens, error mapping and a path->id cache. */
abstract class HttpCloudDrive(protected val http: OkHttpClient, protected val tokens: TokenSource) : CloudDrive {
    protected val ids = ConcurrentHashMap<String, String>()

    override fun invalidate() = ids.clear()

    protected open fun authHeader(token: String) = "Bearer $token"

    protected suspend fun call(build: Request.Builder.() -> Unit, path: FsPath? = null, allow404: Boolean = false): Response {
        var token = tokens.token()
        repeat(2) { attempt ->
            val req = Request.Builder().apply(build).header("Authorization", authHeader(token)).build()
            val r = try { http.newCall(req).execute() } catch (e: Exception) { throw FsException.wrap(e, path) }
            if (r.code == 401 && attempt == 0) { r.close(); token = tokens.token(forceRefresh = true); return@repeat }
            if (r.isSuccessful || (allow404 && r.code == 404)) return r
            val body = runCatching { r.body?.string() }.getOrNull().orEmpty()
            r.close()
            throw mapError(r.code, body, path)
        }
        throw FsException.AuthRequired(tokens.accountId)
    }

    protected open fun mapError(code: Int, body: String, path: FsPath?): FsException = when (code) {
        401 -> FsException.AuthRequired(tokens.accountId)
        403 -> if (body.contains("quota", true) || body.contains("storage", true) && body.contains("full", true)) FsException.OutOfSpace() else FsException.PermissionDenied(path)
        404 -> path?.let { FsException.NotFound(it) } ?: FsException.Generic("Not found")
        409 -> path?.let { FsException.AlreadyExists(it) } ?: FsException.Generic("Conflict")
        413, 507 -> FsException.OutOfSpace()
        429, 503 -> FsException.NetworkLost(java.io.IOException("Service busy, try again"))
        else -> FsException.Generic("Cloud error $code: ${body.take(200)}")
    }

    protected fun Response.json(): JsonObject = use { cloudJson.parseToJsonElement(it.body!!.string()).jsonObject }
}

/** Supplies a valid access token, refreshing when expired. */
interface TokenSource {
    val accountId: String
    suspend fun token(forceRefresh: Boolean = false): String
}
