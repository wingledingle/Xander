package com.xander.files.data.net.smb

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileAllInformation
import com.hierynomus.msfscc.fileinformation.FileBasicInformation
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation
import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2Dialect
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.msdtyp.FileTime
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.xander.files.data.net.ClosingInputStream
import com.xander.files.data.net.Connection
import com.xander.files.data.net.ConnectionRepository
import com.xander.files.domain.fs.Capability
import com.xander.files.domain.fs.FileEntry
import com.xander.files.domain.fs.FileSystemProvider
import com.xander.files.domain.fs.FsException
import com.xander.files.domain.fs.FsPath
import com.xander.files.domain.fs.SpaceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.util.EnumSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SMB 2.0.2 – 3.1.1 (Windows, Samba, NAS, routers) via smbj. Paths: smb://<connId>/<share>/<dir>/...
 * The connection root lists the server's disk shares. Sessions are pooled and transparently re-created.
 */
@Singleton
class SmbFileSystemProvider @Inject constructor(private val connections: ConnectionRepository) : FileSystemProvider {

    override val scheme = FsPath.SCHEME_SMB
    override fun capabilities(path: FsPath) =
        if (path.isRoot) emptySet() else setOf(Capability.WRITE, Capability.RENAME, Capability.SERVER_SIDE_MOVE, Capability.SPACE_INFO)

    private class Pooled(val conn: Connection, val client: SMBClient, val session: Session) {
        val shares = ConcurrentHashMap<String, DiskShare>()
    }

    private val pool = ConcurrentHashMap<String, Pooled>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    init { connections.listeners += { id -> pool.remove(id)?.let { runCatching { it.client.close() } } } }

    private fun config(c: Connection) = SmbConfig.builder()
        .withDialects(SMB2Dialect.SMB_3_1_1, SMB2Dialect.SMB_3_0_2, SMB2Dialect.SMB_3_0, SMB2Dialect.SMB_2_1, SMB2Dialect.SMB_2_0_2)
        .withTimeout(c.options.timeoutSec.toLong(), TimeUnit.SECONDS)
        .withSoTimeout(c.options.timeoutSec.toLong() * 3, TimeUnit.SECONDS)
        .withReadBufferSize(1 shl 20).withWriteBufferSize(1 shl 20).withTransactBufferSize(1 shl 20)
        .withMultiProtocolNegotiate(true)
        .withSigningRequired(false)
        .withEncryptData(false)
        .build()

    private suspend fun pooled(id: String): Pooled = locks.getOrPut(id) { Mutex() }.withLock {
        pool[id]?.let { p -> if (p.session.connection.isConnected) return p; runCatching { p.client.close() }; pool.remove(id) }
        val c = connections.get(id)
        val client = SMBClient(config(c))
        try {
            val conn = client.connect(c.host, c.port)
            val auth = if (c.options.anonymous || c.username.isBlank()) AuthenticationContext.guest()
                else AuthenticationContext(c.username, c.password.toCharArray(), c.domain.ifBlank { null })
            val session = try { conn.authenticate(auth) } catch (e: SMBApiException) {
                if (c.options.anonymous || c.username.isBlank()) conn.authenticate(AuthenticationContext.anonymous()) else throw e
            }
            Pooled(c, client, session).also { pool[id] = it }
        } catch (e: Exception) {
            runCatching { client.close() }
            throw map(e, null, id)
        }
    }

    private suspend fun share(path: FsPath): Pair<DiskShare, String> {
        val p = pooled(path.authority)
        val segs = path.path.removePrefix("/").split('/')
        val shareName = segs.first()
        val share = p.shares[shareName]?.takeIf { it.isConnected } ?: (p.session.connectShare(shareName) as? DiskShare
            ?: throw FsException.Generic("$shareName is not a disk share")).also { p.shares[shareName] = it }
        return share to segs.drop(1).joinToString("\\")
    }

    private fun map(e: Throwable, path: FsPath?, authority: String? = path?.authority): FsException {
        if (e is FsException) return e
        val api = e as? SMBApiException ?: e.cause as? SMBApiException
        if (api != null) return when (api.status) {
            NtStatus.STATUS_OBJECT_NAME_NOT_FOUND, NtStatus.STATUS_OBJECT_PATH_NOT_FOUND, NtStatus.STATUS_NO_SUCH_FILE, NtStatus.STATUS_BAD_NETWORK_NAME ->
                path?.let { FsException.NotFound(it) } ?: FsException.Generic(api.message ?: "Not found")
            NtStatus.STATUS_ACCESS_DENIED -> FsException.PermissionDenied(path, e)
            NtStatus.STATUS_LOGON_FAILURE, NtStatus.STATUS_ACCOUNT_DISABLED, NtStatus.STATUS_PASSWORD_EXPIRED ->
                FsException.AuthRequired(authority ?: "", e)
            NtStatus.STATUS_OBJECT_NAME_COLLISION -> path?.let { FsException.AlreadyExists(it) } ?: FsException.Generic("Exists")
            NtStatus.STATUS_DISK_FULL -> FsException.OutOfSpace(e)
            NtStatus.STATUS_DIRECTORY_NOT_EMPTY -> FsException.Generic("Folder not empty")
            else -> FsException.Generic("SMB: ${api.status}", e)
        }
        if (e is com.hierynomus.protocol.transport.TransportException || e is java.io.IOException && e.cause is java.net.SocketException) {
            authority?.let { a -> pool.remove(a)?.let { runCatching { it.client.close() } } }
            return FsException.NetworkLost(e)
        }
        return FsException.wrap(e, path)
    }

    private suspend fun <T> io(path: FsPath, retry: Boolean = true, block: suspend () -> T): T = withContext(Dispatchers.IO) {
        try { block() } catch (e: Exception) {
            val m = map(e, path)
            if (retry && m is FsException.NetworkLost) {
                // One transparent reconnect for dropped sessions (NAS sleep, Wi-Fi roaming).
                try { block() } catch (e2: Exception) { throw map(e2, path) }
            } else throw m
        }
    }

    private fun info(i: FileIdBothDirectoryInformation, path: FsPath): FileEntry {
        val dir = i.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L
        return FileEntry(path, dir, if (dir) 0 else i.endOfFile, i.lastWriteTime.toEpochMillis(), created = i.creationTime.toEpochMillis(),
            canWrite = i.fileAttributes and FileAttributes.FILE_ATTRIBUTE_READONLY.value == 0L)
    }

    override suspend fun list(dir: FsPath): List<FileEntry> = io(dir) {
        if (dir.isRoot) {
            val p = pooled(dir.authority)
            val shares = runCatching { ShareEnumerator.list(p.session, p.conn.host) }.getOrElse {
                // Some servers block SRVSVC for guests; fall back to the share in the connection's remote path.
                val s = p.conn.remotePath.trim('/').substringBefore('/')
                if (s.isEmpty()) throw FsException.Generic("Could not list shares. Enter the share name in the connection settings.") else listOf(ShareEnumerator.Share(s, "", 0))
            }
            shares.map { FileEntry(dir.child(it.name), true, canWrite = false) }
        } else {
            val (share, rel) = share(dir)
            share.list(rel).filter { it.fileName != "." && it.fileName != ".." }.map { info(it, dir.child(it.fileName)) }
        }
    }

    override suspend fun stat(path: FsPath): FileEntry = io(path) {
        if (path.isRoot) return@io FileEntry(path, true, canWrite = false)
        val (share, rel) = share(path)
        if (rel.isEmpty()) return@io FileEntry(path, true)
        val i: FileAllInformation = share.getFileInformation(rel)
        val dir = i.standardInformation.isDirectory
        FileEntry(path, dir, if (dir) 0 else i.standardInformation.endOfFile, i.basicInformation.lastWriteTime.toEpochMillis(),
            created = i.basicInformation.creationTime.toEpochMillis())
    }

    override suspend fun exists(path: FsPath): Boolean = io(path) {
        if (path.isRoot) return@io true
        val (share, rel) = share(path)
        rel.isEmpty() || share.fileExists(rel) || share.folderExists(rel)
    }

    override suspend fun openInput(path: FsPath, offset: Long): InputStream = io(path) {
        val (share, rel) = share(path)
        val f = share.openFile(rel, EnumSet.of(AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null)
        var pos = offset
        val raw = object : InputStream() {
            private val one = ByteArray(1)
            override fun read(): Int = if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xFF
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val n = f.read(b, pos, off, len)
                if (n <= 0) return -1
                pos += n; return n
            }
            override fun skip(n: Long): Long { pos += n; return n }
        }
        ClosingInputStream(java.io.BufferedInputStream(raw, 1 shl 20)) { runCatching { f.close() } }
    }

    override suspend fun openOutput(path: FsPath, size: Long): OutputStream = io(path) {
        val (share, rel) = share(path)
        val f = share.openFile(rel, EnumSet.of(AccessMask.GENERIC_WRITE), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OVERWRITE_IF, null)
        val out = f.outputStream
        object : OutputStream() {
            override fun write(b: Int) = out.write(b)
            override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
            override fun flush() = out.flush()
            override fun close() { try { out.close() } finally { runCatching { f.close() } } }
        }
    }

    override suspend fun mkdir(parent: FsPath, name: String): FsPath = io(parent) {
        val p = parent.child(name)
        if (parent.isRoot) throw FsException.Unsupported("creating shares")
        val (share, rel) = share(p)
        if (share.folderExists(rel) || share.fileExists(rel)) throw FsException.AlreadyExists(p)
        share.mkdir(rel); p
    }

    override suspend fun rename(path: FsPath, newName: String): FsPath {
        val np = path.parent!!.child(newName)
        return moveInternal(path, path.parent!!, newName) ?: np
    }

    override suspend fun delete(path: FsPath) = io(path) {
        val (share, rel) = share(path)
        if (share.folderExists(rel)) share.rmdir(rel, true) else share.rm(rel)
    }

    override suspend fun moveInternal(src: FsPath, dstParent: FsPath, name: String): FsPath? {
        if (dstParent.scheme != scheme || dstParent.authority != src.authority || src.path.split('/')[1] != dstParent.child(name).path.split('/')[1]) return null
        return io(src) {
            val (share, rel) = share(src)
            val (_, dstRel) = share(dstParent.child(name))
            val isDir = share.folderExists(rel)
            val entry = share.open(rel, EnumSet.of(AccessMask.DELETE, AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN,
                if (isDir) EnumSet.of(SMB2CreateOptions.FILE_DIRECTORY_FILE) else EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE))
            entry.use { it.rename(dstRel, false) }
            dstParent.child(name)
        }
    }

    override suspend fun setLastModified(path: FsPath, time: Long): Boolean = runCatching {
        io(path) {
            val (share, rel) = share(path)
            val cur = share.getFileInformation(rel).basicInformation
            share.setFileInformation(rel, FileBasicInformation(cur.creationTime, FileTime.ofEpochMillis(time), FileTime.ofEpochMillis(time), cur.changeTime, cur.fileAttributes))
            true
        }
    }.getOrDefault(false)

    override suspend fun spaceInfo(path: FsPath): SpaceInfo? = runCatching {
        io(path) {
            if (path.isRoot) return@io null
            val (share, _) = share(path)
            val i = share.shareInformation
            SpaceInfo(i.totalSpace, i.freeSpace)
        }
    }.getOrNull()

    override suspend fun rootTitle(authority: String) = runCatching { connections.get(authority).name }.getOrDefault(authority)
}
