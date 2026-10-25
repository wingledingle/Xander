package com.xander.files.data.net.nfs

import com.emc.ecs.nfsclient.nfs.io.Nfs3File
import com.emc.ecs.nfsclient.nfs.io.NfsFileInputStream
import com.emc.ecs.nfsclient.nfs.io.NfsFileOutputStream
import com.emc.ecs.nfsclient.nfs.nfs3.Nfs3
import com.emc.ecs.nfsclient.rpc.CredentialUnix
import com.xander.files.data.net.ConnectionRepository
import com.xander.files.domain.fs.Capability
import com.xander.files.domain.fs.FileEntry
import com.xander.files.domain.fs.FileSystemProvider
import com.xander.files.domain.fs.FsException
import com.xander.files.domain.fs.FsPath
import com.xander.files.domain.fs.SpaceInfo
import com.xander.files.data.fs.local.PosixInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** NFSv3 (AUTH_SYS with configurable uid/gid). Connection remote path = export, e.g. /volume1/media. */
@Singleton
class NfsFileSystemProvider @Inject constructor(private val connections: ConnectionRepository) : FileSystemProvider {
    override val scheme = FsPath.SCHEME_NFS
    override fun capabilities(path: FsPath) = setOf(Capability.WRITE, Capability.RENAME, Capability.SERVER_SIDE_MOVE, Capability.SPACE_INFO)

    private val clients = ConcurrentHashMap<String, Nfs3>()
    init { connections.listeners += { clients.remove(it) } }

    private suspend fun nfs(id: String): Nfs3 = clients[id] ?: run {
        val c = connections.get(id)
        val export = c.remotePath.ifBlank { "/" }
        Nfs3(c.host, export, CredentialUnix(c.options.nfsUid, c.options.nfsGid, null), 3).also { clients[id] = it }
    }

    // Paths inside the provider are relative to the export root.
    private suspend fun file(path: FsPath) = Nfs3File(nfs(path.authority), path.path)

    private suspend fun <T> io(path: FsPath, block: suspend () -> T): T = withContext(Dispatchers.IO) {
        try { block() } catch (e: FsException) { throw e } catch (e: Exception) {
            val m = e.message.orEmpty()
            throw when {
                m.contains("NFS3ERR_NOENT") -> FsException.NotFound(path)
                m.contains("NFS3ERR_ACCES") || m.contains("NFS3ERR_PERM") -> FsException.PermissionDenied(path, e)
                m.contains("NFS3ERR_EXIST") -> FsException.AlreadyExists(path)
                m.contains("NFS3ERR_NOSPC") || m.contains("NFS3ERR_DQUOT") -> FsException.OutOfSpace(e)
                else -> FsException.wrap(e, path)
            }
        }
    }

    private fun Nfs3File.toEntry(p: FsPath): FileEntry {
        val attrs = attributes
        val dir = isDirectory
        return FileEntry(p, dir, if (dir) 0 else attrs.size, attrs.mtime.timeInMillis,
            permissions = PosixInfo.permissionString(attrs.mode.toInt() or if (dir) 0x4000 else 0x8000),
            owner = attrs.uid.toString(), group = attrs.gid.toString())
    }

    override suspend fun list(dir: FsPath): List<FileEntry> = io(dir) {
        val f = file(dir)
        if (!f.exists()) throw FsException.NotFound(dir)
        f.listFiles().map { it.toEntry(dir.child(it.name)) }
    }

    override suspend fun stat(path: FsPath): FileEntry = io(path) { val f = file(path); if (!f.exists()) throw FsException.NotFound(path); f.toEntry(path) }
    override suspend fun exists(path: FsPath) = io(path) { file(path).exists() }
    override suspend fun openInput(path: FsPath, offset: Long): InputStream = io(path) { NfsFileInputStream(file(path), offset, 1 shl 16) }
    override suspend fun openOutput(path: FsPath, size: Long): OutputStream = io(path) {
        val f = file(path)
        if (f.exists()) f.delete()
        f.createNewFile()
        NfsFileOutputStream(f)
    }
    override suspend fun mkdir(parent: FsPath, name: String): FsPath = io(parent) { val p = parent.child(name); val f = file(p); if (f.exists()) throw FsException.AlreadyExists(p); f.mkdir(); p }
    override suspend fun rename(path: FsPath, newName: String): FsPath = io(path) {
        val np = path.parent!!.child(newName)
        if (!file(path).renameTo(file(np))) throw FsException.PermissionDenied(path)
        np
    }
    override suspend fun delete(path: FsPath) = io(path) { deleteRec(file(path)) }
    private fun deleteRec(f: Nfs3File) { if (f.isDirectory) f.listFiles().forEach { deleteRec(it) }; f.delete() }
    override suspend fun moveInternal(src: FsPath, dstParent: FsPath, name: String): FsPath? =
        if (dstParent.scheme != scheme || dstParent.authority != src.authority) null
        else io(src) { val d = dstParent.child(name); if (file(src).renameTo(file(d))) d else null }
    override suspend fun setLastModified(path: FsPath, time: Long) = runCatching { io(path) { file(path).setLastModified(time) }; true }.getOrDefault(false)
    override suspend fun spaceInfo(path: FsPath): SpaceInfo? = runCatching { io(path) { val f = file(path); SpaceInfo(f.totalSpace, f.usableSpace) } }.getOrNull()
    override suspend fun rootTitle(authority: String) = runCatching { connections.get(authority).name }.getOrDefault(authority)
}
