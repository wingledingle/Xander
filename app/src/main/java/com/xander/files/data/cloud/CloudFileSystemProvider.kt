package com.xander.files.data.cloud

import android.content.Context
import android.graphics.Bitmap
import com.xander.files.data.net.HttpClients
import com.xander.files.domain.fs.Capability
import com.xander.files.domain.fs.FileEntry
import com.xander.files.domain.fs.FileSystemProvider
import com.xander.files.domain.fs.FsException
import com.xander.files.domain.fs.FsPath
import com.xander.files.domain.fs.ProgressListener
import com.xander.files.domain.fs.SpaceInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** All cloud accounts behind one backend: cloud://<accountId>/path. */
@Singleton
class CloudFileSystemProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val accounts: CloudAccountRepository,
) : FileSystemProvider {
    override val scheme = FsPath.SCHEME_CLOUD
    override fun capabilities(path: FsPath) = setOf(
        Capability.WRITE, Capability.RENAME, Capability.SERVER_SIDE_MOVE, Capability.SERVER_SIDE_COPY,
        Capability.THUMBNAILS, Capability.SHARE_LINK, Capability.SPACE_INFO
    )

    private val drives = ConcurrentHashMap<String, CloudDrive>()

    init { accounts.listeners += { drives.remove(it) } }

    suspend fun drive(accountId: String): CloudDrive = drives[accountId] ?: run {
        val a = accounts.get(accountId)
        val http = HttpClients.shared
        val d: CloudDrive = when (a.service) {
            CloudService.GOOGLE_DRIVE -> GoogleDrive(http, GoogleTokenSource(a.id, context, accounts))
            CloudService.DROPBOX -> Dropbox(http, OAuthTokenSource(a.id, accounts, OAuthConfigs.of(a.service)!!))
            CloudService.ONEDRIVE -> OneDrive(http, OAuthTokenSource(a.id, accounts, OAuthConfigs.of(a.service)!!))
            CloudService.BOX -> Box(http, OAuthTokenSource(a.id, accounts, OAuthConfigs.of(a.service)!!))
            CloudService.YANDEX -> YandexDisk(http, OAuthTokenSource(a.id, accounts, OAuthConfigs.of(a.service)!!))
            CloudService.MEGA -> Mega(http, a.id, accounts)
            CloudService.NEXTCLOUD -> Nextcloud(a.credentials.server, a.credentials.user, a.credentials.password)
        }
        drives[accountId] = d
        d
    }

    private suspend fun <T> io(path: FsPath, block: suspend (CloudDrive) -> T): T = withContext(Dispatchers.IO) {
        try { block(drive(path.authority)) } catch (e: FsException.AuthRequired) { throw FsException.AuthRequired(path.authority, e) }
        catch (e: FsException) { throw e } catch (e: Exception) { throw FsException.wrap(e, path) }
    }

    override suspend fun list(dir: FsPath): List<FileEntry> = io(dir) { d -> d.list(dir).map { it.copy(path = FsPath(scheme, dir.authority, it.path.path)) } }
    override suspend fun stat(path: FsPath): FileEntry = io(path) { d -> d.stat(path).let { it.copy(path = path) } }
    override suspend fun openInput(path: FsPath, offset: Long): InputStream = io(path) { it.openInput(path, offset) }
    override suspend fun openOutput(path: FsPath, size: Long): OutputStream = io(path) { it.openOutput(path, size) }
    override suspend fun mkdir(parent: FsPath, name: String): FsPath = io(parent) { it.mkdir(parent, name) }.let { parent.child(name) }
    override suspend fun rename(path: FsPath, newName: String): FsPath = io(path) { it.rename(path, newName) }.let { path.parent!!.child(newName) }
    override suspend fun delete(path: FsPath) = io(path) { it.delete(path) }
    override suspend fun moveInternal(src: FsPath, dstParent: FsPath, name: String): FsPath? =
        if (dstParent.scheme != scheme || dstParent.authority != src.authority) null else io(src) { it.move(src, dstParent, name) }
    override suspend fun copyInternal(src: FsPath, dstParent: FsPath, name: String, progress: ProgressListener?): FsPath? =
        if (dstParent.scheme != scheme || dstParent.authority != src.authority) null else io(src) { it.copy(src, dstParent, name) }
    override suspend fun thumbnail(entry: FileEntry, sizePx: Int): Bitmap? = runCatching { io(entry.path) { it.thumbnail(entry, sizePx) } }.getOrNull()
    override suspend fun shareLink(path: FsPath): String? = io(path) { it.shareLink(path) }
    override suspend fun spaceInfo(path: FsPath): SpaceInfo? = runCatching { io(path) { it.quota() } }.getOrNull()
    override suspend fun rootTitle(authority: String): String = runCatching { accounts.get(authority).let { "${it.service.label} · ${it.email.ifEmpty { it.displayName }}" } }.getOrDefault(authority)

    fun invalidate(accountId: String) { drives[accountId]?.invalidate() }
}
