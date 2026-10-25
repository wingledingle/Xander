package com.xander.files.data.net.ftp

import com.xander.files.data.net.ClosingInputStream
import com.xander.files.data.net.ConnType
import com.xander.files.data.net.Connection
import com.xander.files.data.net.ConnectionRepository
import com.xander.files.domain.fs.Capability
import com.xander.files.domain.fs.FileEntry
import com.xander.files.domain.fs.FileSystemProvider
import com.xander.files.domain.fs.FsException
import com.xander.files.domain.fs.FsPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile
import org.apache.commons.net.ftp.FTPReply
import org.apache.commons.net.ftp.FTPSClient
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.time.Duration
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import javax.inject.Inject
import javax.inject.Singleton

/**
 * FTP and FTPS (explicit AUTH TLS or implicit) via Apache Commons Net. FTP cannot multiplex, so each
 * transfer borrows its own control connection from a small per-server pool.
 */
@Singleton
class FtpFileSystemProvider @Inject constructor(private val connections: ConnectionRepository) : FileSystemProvider {

    override val scheme = FsPath.SCHEME_FTP
    override fun capabilities(path: FsPath) = setOf(Capability.WRITE, Capability.RENAME, Capability.SERVER_SIDE_MOVE)

    private val idle = ConcurrentHashMap<String, ConcurrentLinkedDeque<FTPClient>>()
    private val mlsd = ConcurrentHashMap<String, Boolean>()

    init { connections.listeners += { id -> idle.remove(id)?.forEach { runCatching { it.disconnect() } } } }

    private fun create(c: Connection): FTPClient {
        val client = if (c.type == ConnType.FTPS) FTPSClient("TLS", c.options.implicitTls).apply {
            if (c.options.trustSelfSigned) trustManager = com.xander.files.data.net.HttpClients.trustAll
        } else FTPClient()
        client.controlEncoding = c.options.encoding.ifBlank { "UTF-8" }
        client.setConnectTimeout(c.options.timeoutSec * 1000)
        client.setDefaultTimeout(c.options.timeoutSec * 1000)
        client.setDataTimeout(Duration.ofSeconds(c.options.timeoutSec * 3L))
        client.setControlKeepAliveTimeout(Duration.ofSeconds(30))
        client.connect(c.host, c.port)
        if (!FTPReply.isPositiveCompletion(client.replyCode)) { client.disconnect(); throw IOException("Server refused connection: ${client.replyString}") }
        val user = if (c.options.anonymous || c.username.isBlank()) "anonymous" else c.username
        val pass = if (c.options.anonymous || c.username.isBlank()) "guest@" else c.password
        if (!client.login(user, pass)) { val r = client.replyString; client.disconnect(); throw FsException.AuthRequired(c.id, IOException(r)) }
        if (client is FTPSClient) { client.execPBSZ(0); client.execPROT("P") }
        if (c.options.passive) client.enterLocalPassiveMode() else client.enterLocalActiveMode()
        client.setFileType(FTP.BINARY_FILE_TYPE)
        // UTF8 is required for non-ASCII names on most servers.
        if (c.options.encoding.equals("UTF-8", true)) runCatching { client.sendCommand("OPTS UTF8 ON") }
        if (mlsd[c.id] == null) mlsd[c.id] = runCatching { client.hasFeature("MLSD") }.getOrDefault(false)
        return client
    }

    private suspend fun borrow(id: String): FTPClient {
        val q = idle.getOrPut(id) { ConcurrentLinkedDeque() }
        while (true) {
            val c = q.pollFirst() ?: break
            if (c.isConnected && runCatching { c.sendNoOp() }.getOrDefault(false)) return c
            runCatching { c.disconnect() }
        }
        return create(connections.get(id))
    }

    private fun release(id: String, c: FTPClient) {
        if (!c.isConnected) return
        val q = idle.getOrPut(id) { ConcurrentLinkedDeque() }
        if (q.size < 3) q.addFirst(c) else runCatching { c.logout(); c.disconnect() }
    }

    private suspend fun <T> withClient(path: FsPath, block: (FTPClient) -> T): T = withContext(Dispatchers.IO) {
        var attempt = 0
        while (true) {
            val c = try { borrow(path.authority) } catch (e: Exception) { throw FsException.wrap(e, path) }
            try {
                val r = block(c)
                release(path.authority, c)
                return@withContext r
            } catch (e: FsException) {
                release(path.authority, c); throw e
            } catch (e: Exception) {
                runCatching { c.disconnect() }
                val fe = FsException.wrap(e, path)
                if (fe is FsException.NetworkLost && attempt++ == 0) continue
                throw fe
            }
        }
        @Suppress("UNREACHABLE_CODE") throw IllegalStateException()
    }

    private fun FTPClient.check(ok: Boolean, path: FsPath) {
        if (ok) return
        val code = replyCode
        throw when (code) {
            550 -> if (replyString.contains("exist", true) && !replyString.contains("not", true)) FsException.AlreadyExists(path) else FsException.NotFound(path)
            530 -> FsException.AuthRequired(path.authority)
            552 -> FsException.OutOfSpace()
            553 -> FsException.InvalidName(path.name)
            else -> FsException.Generic("FTP ${replyString.trim()}")
        }
    }

    private fun FTPFile.toEntry(path: FsPath) = FileEntry(
        path = path, isDirectory = isDirectory, size = if (isDirectory) 0 else size, lastModified = timestamp?.timeInMillis ?: 0,
        permissions = rawListing?.takeIf { it.length >= 10 && it[1] in "r-" }?.substring(0, 10),
        owner = user, group = group, isSymlink = isSymbolicLink, linkTarget = link,
    )

    private fun listRaw(c: FTPClient, dir: FsPath): Array<FTPFile> =
        if (mlsd[dir.authority] == true) c.mlistDir(dir.path) else c.listFiles(dir.path)

    override suspend fun list(dir: FsPath): List<FileEntry> = withClient(dir) { c ->
        val files = listRaw(c, dir)
        if (files.isEmpty() && !c.changeWorkingDirectory(dir.path)) throw FsException.NotFound(dir)
        files.filter { it.name != "." && it.name != ".." && it.name.isNotEmpty() }.map { it.toEntry(dir.child(it.name.substringAfterLast('/'))) }
    }

    override suspend fun stat(path: FsPath): FileEntry = withClient(path) { c ->
        if (path.isRoot) return@withClient FileEntry(path, true)
        if (mlsd[path.authority] == true) c.mlistFile(path.path)?.let { return@withClient it.toEntry(path) }
        if (c.changeWorkingDirectory(path.path)) return@withClient FileEntry(path, true)
        c.listFiles(path.parent!!.path).firstOrNull { it.name == path.name }?.toEntry(path) ?: throw FsException.NotFound(path)
    }

    override suspend fun openInput(path: FsPath, offset: Long): InputStream = withContext(Dispatchers.IO) {
        val c = try { borrow(path.authority) } catch (e: Exception) { throw FsException.wrap(e, path) }
        try {
            if (offset > 0) c.restartOffset = offset
            val s = c.retrieveFileStream(path.path) ?: run { c.check(false, path); throw FsException.NotFound(path) }
            ClosingInputStream(s) {
                val ok = runCatching { c.completePendingCommand() }.getOrDefault(false)
                if (ok) release(path.authority, c) else runCatching { c.disconnect() }
            }
        } catch (e: Exception) { runCatching { c.disconnect() }; throw FsException.wrap(e, path) }
    }

    override suspend fun openOutput(path: FsPath, size: Long): OutputStream = withContext(Dispatchers.IO) {
        val c = try { borrow(path.authority) } catch (e: Exception) { throw FsException.wrap(e, path) }
        try {
            val s = c.storeFileStream(path.path) ?: run { c.check(false, path); throw FsException.PermissionDenied(path) }
            object : OutputStream() {
                override fun write(b: Int) = s.write(b)
                override fun write(b: ByteArray, off: Int, len: Int) = s.write(b, off, len)
                override fun flush() = s.flush()
                override fun close() {
                    s.close()
                    val ok = c.completePendingCommand()
                    if (ok) release(path.authority, c) else { runCatching { c.disconnect() }; throw IOException("Upload failed: ${c.replyString}") }
                }
            }
        } catch (e: Exception) { runCatching { c.disconnect() }; throw FsException.wrap(e, path) }
    }

    override suspend fun mkdir(parent: FsPath, name: String): FsPath = withClient(parent) { c ->
        val p = parent.child(name)
        c.check(c.makeDirectory(p.path), p); p
    }

    override suspend fun rename(path: FsPath, newName: String): FsPath = withClient(path) { c ->
        val np = path.parent!!.child(newName)
        c.check(c.rename(path.path, np.path), path); np
    }

    override suspend fun delete(path: FsPath) = withClient(path) { c -> deleteRecursive(c, path) }

    private fun deleteRecursive(c: FTPClient, path: FsPath) {
        if (c.deleteFile(path.path)) return
        val children = listRaw(c, path)
        for (f in children) {
            if (f.name == "." || f.name == "..") continue
            val p = path.child(f.name.substringAfterLast('/'))
            if (f.isDirectory) deleteRecursive(c, p) else c.check(c.deleteFile(p.path), p)
        }
        c.check(c.removeDirectory(path.path), path)
    }

    override suspend fun moveInternal(src: FsPath, dstParent: FsPath, name: String): FsPath? {
        if (dstParent.scheme != scheme || dstParent.authority != src.authority) return null
        return withClient(src) { c -> val d = dstParent.child(name); if (c.rename(src.path, d.path)) d else null }
    }

    override suspend fun setLastModified(path: FsPath, time: Long): Boolean = runCatching {
        withClient(path) { c ->
            val fmt = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            c.setModificationTime(path.path, fmt.format(Date(time)))
        }
    }.getOrDefault(false)

    override suspend fun rootTitle(authority: String) = runCatching { connections.get(authority).name }.getOrDefault(authority)
}
