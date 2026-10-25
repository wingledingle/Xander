package com.xander.files.data.net.webdav

import com.xander.files.data.net.ConnectionRepository
import com.xander.files.data.net.HttpClients
import com.xander.files.domain.fs.Capability
import com.xander.files.domain.fs.FileEntry
import com.xander.files.domain.fs.FileSystemProvider
import com.xander.files.domain.fs.FsException
import com.xander.files.domain.fs.FsPath
import com.xander.files.domain.fs.ProgressListener
import com.xander.files.domain.fs.SpaceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** WebDAV over HTTP/HTTPS (NAS, Apache mod_dav, nginx, IIS, Nextcloud/ownCloud servers added as WebDAV). */
@Singleton
class WebDavFileSystemProvider @Inject constructor(private val connections: ConnectionRepository) : FileSystemProvider {
    override val scheme = FsPath.SCHEME_WEBDAV
    override fun capabilities(path: FsPath) = setOf(Capability.WRITE, Capability.RENAME, Capability.SERVER_SIDE_MOVE, Capability.SERVER_SIDE_COPY, Capability.SPACE_INFO)

    private val clients = ConcurrentHashMap<String, WebDavClient>()
    init { connections.listeners += { clients.remove(it) } }

    private suspend fun client(id: String): WebDavClient = clients[id] ?: run {
        val c = connections.get(id)
        val proto = if (c.options.https) "https" else "http"
        val defaultPort = if (c.options.https) 443 else 80
        val url = "$proto://${c.host}${if (c.port != defaultPort && c.port > 0) ":${c.port}" else ""}"
        WebDavClient(HttpClients.forConnection(c.username, c.password, c.options.trustSelfSigned, c.options.timeoutSec), url).also { clients[id] = it }
    }

    private suspend fun <T> io(path: FsPath, block: suspend (WebDavClient) -> T): T = withContext(Dispatchers.IO) {
        try { block(client(path.authority)) } catch (e: Exception) { throw FsException.wrap(e, path) }
    }

    override suspend fun list(dir: FsPath) = io(dir) { it.list(dir) }
    override suspend fun stat(path: FsPath): FileEntry = io(path) { it.stat(path) }
    override suspend fun openInput(path: FsPath, offset: Long): InputStream = io(path) { it.get(path, offset) }
    override suspend fun openOutput(path: FsPath, size: Long): OutputStream = io(path) { it.put(path, size) }
    override suspend fun mkdir(parent: FsPath, name: String): FsPath = io(parent) { val p = parent.child(name); it.mkcol(p); p }
    override suspend fun rename(path: FsPath, newName: String): FsPath = io(path) {
        val np = path.parent!!.child(newName); it.move(path, np, dir = it.stat(path).isDirectory); np
    }
    override suspend fun delete(path: FsPath) = io(path) { it.delete(path) }
    override suspend fun moveInternal(src: FsPath, dstParent: FsPath, name: String): FsPath? =
        if (dstParent.scheme != scheme || dstParent.authority != src.authority) null
        else io(src) { val d = dstParent.child(name); it.move(src, d, dir = it.stat(src).isDirectory); d }
    override suspend fun copyInternal(src: FsPath, dstParent: FsPath, name: String, progress: ProgressListener?): FsPath? =
        if (dstParent.scheme != scheme || dstParent.authority != src.authority) null
        else io(src) { val st = it.stat(src); val d = dstParent.child(name); it.move(src, d, copy = true, dir = st.isDirectory); progress?.onBytes(st.size); d }
    override suspend fun setLastModified(path: FsPath, time: Long): Boolean = runCatching { io(path) { it.setModified(path, time) }; true }.getOrDefault(false)
    override suspend fun spaceInfo(path: FsPath): SpaceInfo? = runCatching { io(path) { it.quota(path) } }.getOrNull()
    override suspend fun rootTitle(authority: String) = runCatching { connections.get(authority).name }.getOrDefault(authority)
}
