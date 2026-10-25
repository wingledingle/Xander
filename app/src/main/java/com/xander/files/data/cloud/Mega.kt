package com.xander.files.data.cloud

import com.xander.files.data.cloud.MegaCrypto.b64
import com.xander.files.data.cloud.MegaCrypto.fromA32
import com.xander.files.data.cloud.MegaCrypto.toA32
import com.xander.files.data.cloud.MegaCrypto.unb64
import com.xander.files.data.net.ClosingInputStream
import com.xander.files.data.net.StreamingUpload
import com.xander.files.domain.fs.FileEntry
import com.xander.files.domain.fs.FsException
import com.xander.files.domain.fs.FsPath
import com.xander.files.domain.fs.SpaceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.CipherInputStream

/**
 * MEGA (mega.nz) client implemented against the public JSON API: email/password login (v1 + v2 accounts),
 * encrypted node tree, AES-CTR streaming download/upload with chunk MACs, folders, rename, move, rubbish bin,
 * public file links and quota.
 */
class Mega(private val http: OkHttpClient, private val accountId: String, private val repo: CloudAccountRepository) : CloudDrive {

    data class Node(val h: String, val parent: String?, val type: Int, var name: String, val size: Long, val ts: Long, val key: IntArray?, val rawKey: ByteArray?)

    private val seq = AtomicLong(System.currentTimeMillis() % 1_000_000)
    private val lock = Mutex()
    private var nodes: MutableMap<String, Node>? = null
    private var rootH: String? = null
    private var rubbishH: String? = null
    private var sid: String = ""
    private var master: ByteArray = ByteArray(0)

    private suspend fun ensureSession() {
        if (sid.isNotEmpty()) return
        val c = repo.get(accountId).credentials
        if (c.accessToken.isNotEmpty() && c.masterKey.isNotEmpty()) { sid = c.accessToken; master = unb64(c.masterKey); return }
        val s = login(http, c.user, c.password)
        sid = s.first; master = s.second
        repo.updateCredentials(accountId, c.copy(accessToken = sid, masterKey = b64(master)))
    }

    private suspend fun api(cmd: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        ensureSession()
        var backoff = 250L
        repeat(6) {
            val res = raw(http, cmd, sid, seq.incrementAndGet())
            val code = (res as? JsonPrimitive)?.intOrNull ?: ((res as? JsonArray)?.firstOrNull() as? JsonPrimitive)?.intOrNull
            when {
                code == -3 -> { delay(backoff); backoff *= 2 }
                code == -15 || code == -16 -> {
                    // Session expired: log in again with the stored credentials once.
                    val c = repo.get(accountId).credentials
                    val s = login(http, c.user, c.password); sid = s.first; master = s.second
                    repo.updateCredentials(accountId, c.copy(accessToken = sid, masterKey = b64(master)))
                }
                code != null && code < 0 -> throw error(code)
                res is JsonArray -> return@withContext res.first()
                else -> return@withContext res
            }
        }
        throw FsException.NetworkLost(java.io.IOException("MEGA is busy, try again"))
    }

    private suspend fun tree(): MutableMap<String, Node> = lock.withLock {
        nodes?.let { return it }
        val res = api(buildJsonObject { put("a", "f"); put("c", 1) }).jsonObject
        val shareKeys = HashMap<String, ByteArray>()
        (res["ok"] as? JsonArray)?.forEach { o -> val j = o.jsonObject; shareKeys[j.str("h")!!] = MegaCrypto.decryptKey(master, unb64(j.str("k")!!)) }
        val me = repo.get(accountId).credentials.server
        val map = HashMap<String, Node>()
        (res["f"] as? JsonArray)?.forEach { el ->
            val j = el.jsonObject
            val h = j.str("h")!!; val t = j.long("t")!!.toInt()
            when (t) {
                2 -> { rootH = h; map[h] = Node(h, null, t, "", 0, 0, null, null); return@forEach }
                3 -> return@forEach
                4 -> { rubbishH = h; map[h] = Node(h, null, t, "Rubbish Bin", 0, 0, null, null); return@forEach }
            }
            val kField = j.str("k") ?: return@forEach
            var raw: ByteArray? = null
            for (part in kField.split('/')) {
                val (owner, enc) = part.split(':', limit = 2).let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: return@let "" to "") }
                if (enc.isEmpty()) continue
                val key = when {
                    owner == me || owner == j.str("u") -> master
                    shareKeys[owner] != null -> shareKeys[owner]
                    else -> null
                } ?: continue
                raw = runCatching { MegaCrypto.decryptKey(key, unb64(enc)) }.getOrNull()
                if (raw != null) break
            }
            if (raw == null) return@forEach
            val k = toA32(raw)
            val aes = if (t == 0) MegaCrypto.fileKey(k) else raw
            val attrs = runCatching { MegaCrypto.decryptAttr(j.str("a")!!, aes) }.getOrDefault(emptyMap())
            val name = (attrs["n"] as? String)?.replace('/', '_') ?: return@forEach
            map[h] = Node(h, j.str("p"), t, name, j.long("s") ?: 0, (j.long("ts") ?: 0) * 1000, k, raw)
        }
        nodes = map
        map
    }

    override fun invalidate() { nodes = null }

    private fun children(map: Map<String, Node>, h: String) = map.values.filter { it.parent == h }

    private suspend fun node(path: FsPath): Node {
        val map = tree()
        var cur = map[rootH ?: throw FsException.NotFound(path)]!!
        for (seg in path.path.removePrefix("/").split('/').filter { it.isNotEmpty() }) {
            cur = if (cur.h == rootH && seg == "Rubbish Bin" && rubbishH != null) map[rubbishH]!!
                else children(map, cur.h).firstOrNull { it.name == seg } ?: throw FsException.NotFound(path)
        }
        return cur
    }

    private fun Node.entry(p: FsPath) = FileEntry(p, type != 0, size, ts, backendId = h)

    override suspend fun list(path: FsPath): List<FileEntry> {
        val n = node(path)
        if (n.type == 0) throw FsException.NotADirectory(path)
        val map = tree()
        val kids = children(map, n.h).map { it.entry(path.child(it.name)) }
        return if (n.h == rootH && rubbishH != null) kids + FileEntry(path.child("Rubbish Bin"), true, canWrite = false) else kids
    }

    override suspend fun stat(path: FsPath): FileEntry = if (path.isRoot) FileEntry(path, true) else node(path).entry(path)

    override suspend fun openInput(path: FsPath, offset: Long): InputStream {
        val n = node(path)
        val k = n.key ?: throw FsException.PermissionDenied(path)
        val res = api(buildJsonObject { put("a", "g"); put("g", 1); put("n", n.h); put("ssl", 2) }).jsonObject
        val url = res.str("g") ?: throw FsException.Generic("MEGA download unavailable")
        val start = offset - offset % 16
        val r = withContext(Dispatchers.IO) { http.newCall(Request.Builder().url(if (start > 0) "$url/$start-" else url).build()).execute() }
        if (!r.isSuccessful) { r.close(); throw if (r.code == 509) FsException.Generic("MEGA transfer quota exceeded") else FsException.Generic("Download failed (${r.code})") }
        val cipher = MegaCrypto.ctr(MegaCrypto.fileKey(k), intArrayOf(k[4], k[5]), start)
        val decrypted = CipherInputStream(r.body!!.byteStream(), cipher)
        var skip = offset - start
        while (skip > 0) { val s = decrypted.skip(skip); if (s <= 0) break; skip -= s }
        return ClosingInputStream(object : FilterInputStream(decrypted) {}) { r.close() }
    }

    override suspend fun openOutput(path: FsPath, size: Long): OutputStream {
        val parent = node(path.parent!!)
        val old = runCatching { node(path) }.getOrNull()?.takeIf { it.type == 0 }
        return StreamingUpload(path.name) { input -> runBlocking { upload(input, size, parent, path.name, old) } }
    }

    private suspend fun upload(input: InputStream, size: Long, parent: Node, name: String, old: Node?) {
        // MEGA needs the size up front: spool unknown-size streams to memory/disk first.
        val (source, total) = if (size >= 0) input to size else {
            val tmp = java.io.File.createTempFile("mega", ".up"); tmp.deleteOnExit()
            tmp.outputStream().use { input.copyTo(it) }
            tmp.inputStream() to tmp.length()
        }
        val url = api(buildJsonObject { put("a", "u"); put("s", total) }).jsonObject.str("p") ?: throw FsException.Generic("MEGA upload unavailable")
        val ul = MegaCrypto.randomInts(6)
        val key = fromA32(ul.copyOfRange(0, 4)); val nonce = ul.copyOfRange(4, 6)
        val cipher = MegaCrypto.ctr(key, nonce, 0)
        var fileMac = ByteArray(16)
        var offset = 0L
        var completion: String? = null
        val sizes = MegaCrypto.chunkSizes().iterator()
        if (total == 0L) {
            completion = withContext(Dispatchers.IO) { http.newCall(Request.Builder().url("$url/0").post(ByteArray(0).toRequestBody(null)).build()).execute().use { it.body!!.string() } }
        }
        while (offset < total) {
            val chunk = minOf(sizes.next().toLong(), total - offset).toInt()
            val buf = ByteArray(chunk)
            var n = 0
            while (n < chunk) { val r = source.read(buf, n, chunk - n); if (r < 0) break; n += r }
            if (n < chunk) throw FsException.Generic("Upload source ended early")
            val mac = MegaCrypto.chunkMac(key, nonce, buf, n)
            for (i in 0 until 16) fileMac[i] = (fileMac[i].toInt() xor mac[i].toInt()).toByte()
            fileMac = MegaCrypto.aesEcb(key, fileMac, true)
            val enc = cipher.update(buf, 0, n)
            val body = withContext(Dispatchers.IO) {
                http.newCall(Request.Builder().url("$url/$offset").post(enc.toRequestBody("application/octet-stream".toMediaType())).build()).execute().use { it.body!!.string() }
            }
            if (body.startsWith("-")) throw error(body.toIntOrNull() ?: -1)
            if (body.isNotEmpty()) completion = body
            offset += n
        }
        source.close()
        val fm = toA32(fileMac)
        val meta = intArrayOf(fm[0] xor fm[1], fm[2] xor fm[3])
        val nodeKey = intArrayOf(ul[0] xor ul[4], ul[1] xor ul[5], ul[2] xor meta[0], ul[3] xor meta[1], ul[4], ul[5], meta[0], meta[1])
        val res = api(buildJsonObject {
            put("a", "p"); put("t", parent.h)
            put("n", buildJsonArray {
                add(buildJsonObject {
                    put("h", completion ?: throw FsException.Generic("MEGA upload did not complete")); put("t", 0)
                    put("a", MegaCrypto.encryptAttr(name, key)); put("k", b64(MegaCrypto.encryptKey(master, fromA32(nodeKey))))
                    if (old != null) put("ov", old.h)
                })
            })
        })
        if (old != null) runCatching { api(buildJsonObject { put("a", "d"); put("n", old.h) }) }
        res.toString()
        invalidate()
    }

    override suspend fun mkdir(parent: FsPath, name: String): FsPath {
        val p = node(parent)
        if (children(tree(), p.h).any { it.name == name }) throw FsException.AlreadyExists(parent.child(name))
        val key = fromA32(MegaCrypto.randomInts(4))
        api(buildJsonObject {
            put("a", "p"); put("t", p.h)
            put("n", buildJsonArray { add(buildJsonObject { put("h", "xxxxxxxx"); put("t", 1); put("a", MegaCrypto.encryptAttr(name, key)); put("k", b64(MegaCrypto.encryptKey(master, key))) }) })
        })
        invalidate()
        return parent.child(name)
    }

    override suspend fun rename(path: FsPath, newName: String): FsPath {
        val n = node(path)
        val raw = n.rawKey ?: throw FsException.PermissionDenied(path)
        val aes = if (n.type == 0) MegaCrypto.fileKey(n.key!!) else raw
        api(buildJsonObject { put("a", "a"); put("n", n.h); put("attr", MegaCrypto.encryptAttr(newName, aes)); put("key", b64(MegaCrypto.encryptKey(master, raw))) })
        n.name = newName
        return path.parent!!.child(newName)
    }

    /** Moves to MEGA's Rubbish Bin (recoverable); deleting inside the Rubbish Bin is permanent. */
    override suspend fun delete(path: FsPath) {
        val n = node(path)
        val map = tree()
        val inRubbish = generateSequence(n) { map[it.parent ?: ""] }.any { it.h == rubbishH }
        if (inRubbish || rubbishH == null) api(buildJsonObject { put("a", "d"); put("n", n.h) })
        else api(buildJsonObject { put("a", "m"); put("n", n.h); put("t", rubbishH) })
        invalidate()
    }

    override suspend fun move(src: FsPath, dstParent: FsPath, name: String): FsPath? {
        val n = node(src); val t = node(dstParent)
        api(buildJsonObject { put("a", "m"); put("n", n.h); put("t", t.h) })
        invalidate()
        if (name != n.name) rename(dstParent.child(n.name), name)
        return dstParent.child(name)
    }

    override suspend fun shareLink(path: FsPath): String? {
        val n = node(path)
        if (n.type != 0) return null
        val ph = (api(buildJsonObject { put("a", "l"); put("n", n.h) }) as JsonPrimitive).content
        return "https://mega.nz/file/$ph#${b64(n.rawKey!!)}"
    }

    override suspend fun quota(): SpaceInfo? = runCatching {
        val j = api(buildJsonObject { put("a", "uq"); put("strg", 1) }).jsonObject
        val max = j.long("mstrg")!!
        SpaceInfo(max, max - (j.long("cstrg") ?: 0))
    }.getOrNull()

    companion object {
        private const val API = "https://g.api.mega.co.nz/cs"

        fun error(code: Int): FsException = when (code) {
            -9 -> FsException.Generic("Not found on MEGA")
            -11 -> FsException.PermissionDenied(null)
            -15, -16 -> FsException.AuthRequired("mega")
            -17 -> FsException.OutOfSpace()
            -18 -> FsException.Generic("MEGA resource temporarily unavailable")
            -26 -> FsException.Generic("MEGA two-factor authentication is not supported here")
            -2 -> FsException.Generic("MEGA rejected the request")
            -14 -> FsException.Generic("Wrong email or password")
            else -> FsException.Generic("MEGA error $code")
        }

        fun raw(http: OkHttpClient, cmd: JsonObject, sid: String, id: Long): JsonElement {
            val url = "$API?id=$id" + if (sid.isNotEmpty()) "&sid=$sid" else ""
            val body = "[$cmd]".toRequestBody("application/json".toMediaType())
            val text = try { http.newCall(Request.Builder().url(url).post(body).build()).execute().use { it.body!!.string() } }
                catch (e: Exception) { throw FsException.wrap(e) }
            return cloudJson.parseToJsonElement(text)
        }

        /** Returns (session id, master key). */
        suspend fun login(http: OkHttpClient, email: String, password: String): Pair<String, ByteArray> = withContext(Dispatchers.IO) {
            val user = email.trim().lowercase()
            val pre = raw(http, buildJsonObject { put("a", "us0"); put("user", user) }, "", 1).let { (it as? JsonArray)?.first() ?: it }
            if (pre is JsonPrimitive) throw error(pre.intOrNull ?: -1)
            val v = pre.jsonObject.long("v") ?: 1
            val (pk, uh) = if (v == 2L) {
                val dk = MegaCrypto.pbkdf2Sha512(password, unb64(pre.jsonObject.str("s")!!), 100_000, 32)
                dk.copyOfRange(0, 16) to b64(dk.copyOfRange(16, 32))
            } else {
                val k = MegaCrypto.prepareKey(password)
                k to MegaCrypto.stringHash(user, k)
            }
            val res = raw(http, buildJsonObject { put("a", "us"); put("user", user); put("uh", uh) }, "", 2).let { (it as? JsonArray)?.first() ?: it }
            if (res is JsonPrimitive) throw error(res.intOrNull ?: -1)
            val j = res.jsonObject
            val master = MegaCrypto.decryptKey(pk, unb64(j.str("k")!!))
            val sid = j.str("tsid") ?: run {
                val privk = MegaCrypto.decryptKey(master, unb64(j.str("privk")!!))
                MegaCrypto.sessionId(privk, unb64(j.str("csid")!!))
            }
            sid to master
        }

        /** Account email + user handle for display and node key ownership. */
        suspend fun userInfo(http: OkHttpClient, sid: String): Pair<String, String> = withContext(Dispatchers.IO) {
            val j = raw(http, buildJsonObject { put("a", "ug") }, sid, 3).let { (it as? JsonArray)?.first() ?: it }.jsonObject
            (j.str("email") ?: "") to (j.str("u") ?: "")
        }
    }
}
