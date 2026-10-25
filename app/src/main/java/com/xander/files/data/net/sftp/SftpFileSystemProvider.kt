package com.xander.files.data.net.sftp

import com.xander.files.data.net.ClosingInputStream
import com.xander.files.data.net.Connection
import com.xander.files.data.net.ConnectionRepository
import com.xander.files.domain.fs.Capability
import com.xander.files.domain.fs.FileEntry
import com.xander.files.domain.fs.FsException
import com.xander.files.domain.fs.FsPath
import com.xander.files.domain.fs.PosixFileSystemProvider
import com.xander.files.domain.fs.ProgressListener
import com.xander.files.domain.fs.SpaceInfo
import com.xander.files.data.fs.local.PosixInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.SecurityUtils
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.RemoteResourceInfo
import net.schmizz.sshj.sftp.Response
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.SFTPException
import net.schmizz.sshj.transport.TransportException
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.UserAuthException
import net.schmizz.sshj.userauth.password.PasswordUtils
import java.io.InputStream
import java.io.OutputStream
import java.security.PublicKey
import java.util.EnumSet
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Thrown on first connect (or key change) so the UI can show the fingerprint and ask to trust it. */
class HostKeyUnknownException(val connectionId: String, val fingerprint: String, val changed: Boolean) :
    FsException.Generic(if (changed) "Host key changed! Fingerprint: $fingerprint" else "Unknown host key: $fingerprint")

/** SFTP with password or private-key auth via sshj; trust-on-first-use host key pinning. */
@Singleton
class SftpFileSystemProvider @Inject constructor(private val connections: ConnectionRepository) : PosixFileSystemProvider {

    override val scheme = FsPath.SCHEME_SFTP
    override fun capabilities(path: FsPath) = setOf(Capability.WRITE, Capability.RENAME, Capability.SERVER_SIDE_MOVE, Capability.POSIX, Capability.SYMLINKS, Capability.RANDOM_ACCESS)

    private class Pooled(val ssh: SSHClient, val sftp: SFTPClient)
    private val pool = ConcurrentHashMap<String, Pooled>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    init {
        SecurityUtils.setRegisterBouncyCastle(true)
        connections.listeners += { id -> pool.remove(id)?.let { runCatching { it.ssh.disconnect() } } }
    }

    private suspend fun client(id: String): SFTPClient = locks.getOrPut(id) { Mutex() }.withLock {
        pool[id]?.let { if (it.ssh.isConnected && it.ssh.isAuthenticated) return it.sftp; runCatching { it.ssh.disconnect() } }
        val c = connections.get(id)
        val ssh = SSHClient()
        ssh.connectTimeout = c.options.timeoutSec * 1000
        ssh.timeout = c.options.timeoutSec * 3000
        ssh.addHostKeyVerifier(object : HostKeyVerifier {
            override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
                val fp = SecurityUtils.getFingerprint(key)
                if (c.options.hostKeyFingerprint.isEmpty()) throw HostKeyUnknownException(id, fp, false)
                if (c.options.hostKeyFingerprint != fp) throw HostKeyUnknownException(id, fp, true)
                return true
            }
            override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
        })
        try {
            ssh.connect(c.host, c.port)
            ssh.connection.keepAlive.keepAliveInterval = 20
            if (c.options.usePrivateKey && c.privateKey.isNotBlank()) {
                val finder = if (c.keyPassphrase.isNotEmpty()) PasswordUtils.createOneOff(c.keyPassphrase.toCharArray()) else null
                val keys = ssh.loadKeys(c.privateKey, null, finder)
                ssh.authPublickey(c.username, keys)
            } else ssh.authPassword(c.username, c.password)
            val sftp = ssh.newSFTPClient()
            pool[id] = Pooled(ssh, sftp)
            sftp
        } catch (e: Throwable) {
            runCatching { ssh.disconnect() }
            val root = generateSequence(e) { it.cause }.firstOrNull { it is HostKeyUnknownException }
            if (root != null) throw root
            throw if (e is UserAuthException) FsException.AuthRequired(id, e) else FsException.wrap(e)
        }
    }

    /** Called after the user accepted the fingerprint shown by [HostKeyUnknownException]. */
    suspend fun trustHostKey(id: String, fingerprint: String) = connections.updateOptions(id) { it.copy(hostKeyFingerprint = fingerprint) }

    private fun map(e: Throwable, path: FsPath): FsException = when {
        e is FsException -> e
        e is SFTPException -> when (e.statusCode) {
            Response.StatusCode.NO_SUCH_FILE, Response.StatusCode.NO_SUCH_PATH -> FsException.NotFound(path)
            Response.StatusCode.PERMISSION_DENIED -> FsException.PermissionDenied(path, e)
            Response.StatusCode.FILE_ALREADY_EXISTS -> FsException.AlreadyExists(path)
            Response.StatusCode.NO_SPACE_ON_FILESYSTEM, Response.StatusCode.QUOTA_EXCEEDED -> FsException.OutOfSpace(e)
            else -> FsException.Generic(e.message ?: "SFTP error", e)
        }
        e is TransportException || e is net.schmizz.sshj.connection.ConnectionException -> {
            pool.remove(path.authority)?.let { runCatching { it.ssh.disconnect() } }
            FsException.NetworkLost(e)
        }
        else -> FsException.wrap(e, path)
    }

    private suspend fun <T> io(path: FsPath, block: suspend (SFTPClient) -> T): T = withContext(Dispatchers.IO) {
        try { block(client(path.authority)) } catch (e: Exception) {
            val m = map(e, path)
            if (m is FsException.NetworkLost) try { block(client(path.authority)) } catch (e2: Exception) { throw map(e2, path) } else throw m
        }
    }

    private fun attrsToEntry(path: FsPath, a: FileAttributes, linkTarget: String? = null, isLink: Boolean = false): FileEntry {
        val mode = a.mode
        val dir = mode.type == FileMode.Type.DIRECTORY
        return FileEntry(path, dir, if (dir) 0 else a.size, a.mtime * 1000,
            permissions = PosixInfo.permissionString(mode.mask or typeBits(mode.type)), owner = a.uid.toString(), group = a.gid.toString(),
            isSymlink = isLink, linkTarget = linkTarget)
    }

    private fun typeBits(t: FileMode.Type) = when (t) {
        FileMode.Type.DIRECTORY -> 0x4000; FileMode.Type.SYMLINK -> 0xA000; FileMode.Type.REGULAR -> 0x8000; else -> 0
    }

    private fun RemoteResourceInfo.toEntry(dir: FsPath, sftp: SFTPClient): FileEntry {
        val p = dir.child(name)
        if (attributes.mode.type == FileMode.Type.SYMLINK) {
            val target = runCatching { sftp.readlink(path) }.getOrNull()
            val resolved = runCatching { sftp.stat(path) }.getOrNull()
            return attrsToEntry(p, resolved ?: attributes, target, true)
        }
        return attrsToEntry(p, attributes)
    }

    override suspend fun list(dir: FsPath): List<FileEntry> = io(dir) { s -> s.ls(dir.path).map { it.toEntry(dir, s) } }

    override suspend fun stat(path: FsPath): FileEntry = io(path) { s -> attrsToEntry(path, s.stat(path.path)) }

    override suspend fun exists(path: FsPath): Boolean = io(path) { s -> s.statExistence(path.path) != null }

    override suspend fun openInput(path: FsPath, offset: Long): InputStream = io(path) { s ->
        val rf = s.open(path.path, EnumSet.of(OpenMode.READ))
        val stream = rf.ReadAheadRemoteFileInputStream(16, offset)
        ClosingInputStream(stream) { runCatching { rf.close() } }
    }

    override suspend fun openOutput(path: FsPath, size: Long): OutputStream = io(path) { s ->
        val rf = s.open(path.path, EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC))
        // Buffered sequential WRITE requests sized to the SFTP packet limit; each waits for its status.
        object : OutputStream() {
            private val buf = ByteArray(32 * 1024)
            private var n = 0
            private var offset = 0L
            override fun write(b: Int) { buf[n++] = b.toByte(); if (n == buf.size) drain() }
            override fun write(b: ByteArray, off: Int, len: Int) {
                var o = off; var l = len
                while (l > 0) {
                    val k = minOf(l, buf.size - n)
                    System.arraycopy(b, o, buf, n, k); n += k; o += k; l -= k
                    if (n == buf.size) drain()
                }
            }
            private fun drain() { if (n > 0) { rf.write(offset, buf, 0, n); offset += n; n = 0 } }
            override fun flush() = drain()
            override fun close() { try { drain() } finally { rf.close() } }
        }
    }

    override suspend fun mkdir(parent: FsPath, name: String): FsPath = io(parent) { s ->
        val p = parent.child(name)
        if (s.statExistence(p.path) != null) throw FsException.AlreadyExists(p)
        s.mkdir(p.path); p
    }

    override suspend fun rename(path: FsPath, newName: String): FsPath = io(path) { s ->
        val np = path.parent!!.child(newName)
        s.rename(path.path, np.path); np
    }

    override suspend fun delete(path: FsPath) = io(path) { s -> deleteRecursive(s, path.path) }

    private fun deleteRecursive(s: SFTPClient, p: String) {
        val a = s.lstat(p)
        if (a.mode.type == FileMode.Type.DIRECTORY) {
            s.ls(p).forEach { deleteRecursive(s, it.path) }
            s.rmdir(p)
        } else s.rm(p)
    }

    override suspend fun moveInternal(src: FsPath, dstParent: FsPath, name: String): FsPath? {
        if (dstParent.scheme != scheme || dstParent.authority != src.authority) return null
        return io(src) { s -> val d = dstParent.child(name); s.rename(src.path, d.path); d }
    }

    override suspend fun copyInternal(src: FsPath, dstParent: FsPath, name: String, progress: ProgressListener?): FsPath? = null

    override suspend fun setLastModified(path: FsPath, time: Long): Boolean = runCatching {
        io(path) { s -> val a = s.stat(path.path); s.setattr(path.path, FileAttributes.Builder().withAtimeMtime(a.atime, time / 1000).build()); true }
    }.getOrDefault(false)

    override suspend fun chmod(path: FsPath, mode: Int, recursive: Boolean) = io(path) { s ->
        fun apply(p: String) {
            s.chmod(p, mode)
            if (recursive && s.stat(p).mode.type == FileMode.Type.DIRECTORY) s.ls(p).forEach { apply(it.path) }
        }
        apply(path.path)
    }

    override suspend fun chown(path: FsPath, owner: String, group: String, recursive: Boolean) = io(path) { s ->
        val uid = owner.toIntOrNull() ?: throw FsException.Generic("SFTP needs numeric user IDs")
        val gid = group.toIntOrNull() ?: uid
        fun apply(p: String) {
            s.chown(p, uid); s.chgrp(p, gid)
            if (recursive && s.stat(p).mode.type == FileMode.Type.DIRECTORY) s.ls(p).forEach { apply(it.path) }
        }
        apply(path.path)
    }

    override suspend fun symlink(target: String, link: FsPath) = io(link) { s -> s.symlink(link.path, target) }

    override suspend fun spaceInfo(path: FsPath): SpaceInfo? = null

    override suspend fun rootTitle(authority: String) = runCatching { connections.get(authority).name }.getOrDefault(authority)
}
