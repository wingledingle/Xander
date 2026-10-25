package com.xander.files.data.tv

import com.xander.files.domain.fs.Capability
import com.xander.files.domain.fs.FileEntry
import com.xander.files.domain.fs.FileSystemProvider
import com.xander.files.domain.fs.FsPath
import com.xander.files.domain.fs.ProgressListener
import com.xander.files.domain.fs.SpaceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** tv://<deviceId>/<path on the TV> – files on a paired Android TV running Xander. */
@Singleton
class TvFileSystemProvider @Inject constructor(private val repo: TvRepository) : FileSystemProvider {
    override val scheme = FsPath.SCHEME_TV
    override fun capabilities(path: FsPath) = setOf(Capability.WRITE, Capability.RENAME)

    private suspend fun <T> io(p: FsPath, f: (TvClient) -> T): T = withContext(Dispatchers.IO) { f(repo.client(p.authority)) }

    override suspend fun list(dir: FsPath): List<FileEntry> = io(dir) { it.list(dir) }
    override suspend fun stat(path: FsPath): FileEntry = io(path) { it.stat(path) }
    override suspend fun openInput(path: FsPath, offset: Long): InputStream = io(path) { it.read(path, offset) }
    override suspend fun openOutput(path: FsPath, size: Long): OutputStream = io(path) { it.write(path, size) }
    override suspend fun mkdir(parent: FsPath, name: String): FsPath = io(parent) { it.mkdir(parent, name); parent.child(name) }
    override suspend fun createFile(parent: FsPath, name: String): FsPath = io(parent) { c -> c.write(parent.child(name), 0).close(); parent.child(name) }
    override suspend fun rename(path: FsPath, newName: String): FsPath = io(path) { it.rename(path, newName); path.parent!!.child(newName) }
    override suspend fun delete(path: FsPath) = io(path) { it.delete(path) }
    override suspend fun moveInternal(src: FsPath, dstParent: FsPath, name: String): FsPath? =
        if (src.authority != dstParent.authority) null else io(src) { it.move(src, dstParent, name); dstParent.child(name) }
    override suspend fun spaceInfo(path: FsPath): SpaceInfo? = runCatching { io(path) { it.info() }.let { SpaceInfo(it.total, it.free) } }.getOrNull()
    override suspend fun rootTitle(authority: String): String = repo.name(authority)
}
