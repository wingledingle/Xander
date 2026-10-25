package com.xander.files.data.fs.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.xander.files.BuildConfig
import com.xander.files.data.fs.local.PosixInfo
import com.xander.files.data.shizuku.FileService
import com.xander.files.data.shizuku.IFileService
import com.xander.files.domain.fs.Capability
import com.xander.files.domain.fs.FileEntry
import com.xander.files.domain.fs.FileSystemProvider
import com.xander.files.domain.fs.FsException
import com.xander.files.domain.fs.FsPath
import com.xander.files.domain.fs.SearchQuery
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

enum class ShizukuState { NOT_INSTALLED, NOT_RUNNING, PERMISSION_NEEDED, READY, UNSUPPORTED }

/** Non-root privileged access to Android/data and Android/obb through Shizuku (shell uid). */
@Singleton
class ShizukuFileSystemProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) : FileSystemProvider {

    override val scheme = FsPath.SCHEME_SHIZUKU
    override fun capabilities(path: FsPath) = setOf(Capability.WRITE, Capability.RENAME, Capability.SERVER_SIDE_MOVE, Capability.SEARCH)

    private val _state = MutableStateFlow(ShizukuState.NOT_RUNNING)
    val state: StateFlow<ShizukuState> = _state

    @Volatile private var service: IFileService? = null
    private var pending: CompletableDeferred<IFileService>? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder != null && binder.pingBinder()) {
                val s = IFileService.Stub.asInterface(binder)
                service = s; pending?.complete(s)
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) { service = null }
    }

    private val args by lazy {
        Shizuku.UserServiceArgs(ComponentName(context.packageName, FileService::class.java.name))
            .daemon(false).processNameSuffix("files").debuggable(BuildConfig.DEBUG).version(1)
    }

    init {
        if (Build.VERSION.SDK_INT >= 24) {
            runCatching {
                Shizuku.addBinderReceivedListenerSticky { refresh() }
                Shizuku.addBinderDeadListener { service = null; refresh() }
                Shizuku.addRequestPermissionResultListener { _, _ -> refresh() }
            }
        }
        refresh()
    }

    fun refresh() {
        _state.value = when {
            Build.VERSION.SDK_INT < 24 -> ShizukuState.UNSUPPORTED
            !isInstalled() -> ShizukuState.NOT_INSTALLED
            !runCatching { Shizuku.pingBinder() }.getOrDefault(false) -> ShizukuState.NOT_RUNNING
            runCatching { Shizuku.isPreV11() }.getOrDefault(true) -> ShizukuState.UNSUPPORTED
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> ShizukuState.PERMISSION_NEEDED
            else -> ShizukuState.READY
        }
    }

    private fun isInstalled() = runCatching { context.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0); true }.getOrDefault(false)

    fun isAvailable(): Boolean { refresh(); return _state.value == ShizukuState.READY }

    suspend fun requestPermission(): Boolean {
        refresh()
        if (_state.value == ShizukuState.READY) return true
        if (_state.value != ShizukuState.PERMISSION_NEEDED) return false
        return suspendCancellableCoroutine { cont ->
            val l = object : Shizuku.OnRequestPermissionResultListener {
                override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                    Shizuku.removeRequestPermissionResultListener(this)
                    refresh()
                    if (cont.isActive) cont.resume(grantResult == PackageManager.PERMISSION_GRANTED)
                }
            }
            Shizuku.addRequestPermissionResultListener(l)
            Shizuku.requestPermission(REQUEST_CODE)
        }
    }

    /** Runs [command] with shell (ADB) privileges, e.g. `sm unmount`. */
    suspend fun exec(command: String): String = withContext(Dispatchers.IO) { svc().exec(command) }

    private suspend fun svc(): IFileService {
        service?.let { return it }
        if (!isAvailable()) throw FsException.PermissionDenied(null, IllegalStateException("Shizuku is not running or not authorized"))
        val d = CompletableDeferred<IFileService>()
        pending = d
        withContext(Dispatchers.Main) { Shizuku.bindUserService(args, connection) }
        return withTimeout(10_000) { d.await() }
    }

    private fun parse(line: String, parent: FsPath): FileEntry? {
        val p = line.split('|', limit = 5)
        if (p.size < 5) return null
        val mode = p[3].toIntOrNull() ?: 0
        return FileEntry(
            path = parent.child(p[4]), isDirectory = p[0] == "1", size = p[1].toLongOrNull() ?: 0,
            lastModified = p[2].toLongOrNull() ?: 0, permissions = if (mode != 0) PosixInfo.permissionString(mode) else null,
        )
    }

    override suspend fun list(dir: FsPath): List<FileEntry> = io(dir) {
        val pfd = svc().list(dir.path)
        ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().useLines { lines -> lines.mapNotNull { parse(it, dir) }.toList() }
    }

    override suspend fun stat(path: FsPath): FileEntry = io(path) {
        val line = svc().stat(path.path) ?: throw FsException.NotFound(path)
        parse(line, path.parent ?: path)?.copy(path = path) ?: throw FsException.NotFound(path)
    }

    override suspend fun exists(path: FsPath) = io(path) { svc().exists(path.path) }

    override suspend fun openInput(path: FsPath, offset: Long): InputStream = io(path) {
        val s = ParcelFileDescriptor.AutoCloseInputStream(svc().openRead(path.path))
        if (offset > 0) s.channel.position(offset)
        s
    }

    override suspend fun openOutput(path: FsPath, size: Long): OutputStream = io(path) {
        ParcelFileDescriptor.AutoCloseOutputStream(svc().openWrite(path.path))
    }

    override suspend fun mkdir(parent: FsPath, name: String): FsPath = io(parent) {
        val p = parent.child(name)
        if (svc().exists(p.path)) throw FsException.AlreadyExists(p)
        if (!svc().mkdir(p.path)) throw FsException.PermissionDenied(p)
        p
    }

    override suspend fun createFile(parent: FsPath, name: String): FsPath = io(parent) {
        val p = parent.child(name)
        if (svc().exists(p.path)) throw FsException.AlreadyExists(p)
        if (!svc().createFile(p.path)) throw FsException.PermissionDenied(p)
        p
    }

    override suspend fun rename(path: FsPath, newName: String): FsPath = io(path) {
        val np = path.parent!!.child(newName)
        if (svc().exists(np.path)) throw FsException.AlreadyExists(np)
        if (!svc().rename(path.path, np.path)) throw FsException.PermissionDenied(path)
        np
    }

    override suspend fun delete(path: FsPath) = io(path) { if (!svc().delete(path.path)) throw FsException.PermissionDenied(path) }

    override suspend fun moveInternal(src: FsPath, dstParent: FsPath, name: String): FsPath? {
        if (dstParent.scheme != scheme) return null
        return io(src) {
            val dst = dstParent.child(name)
            if (svc().exists(dst.path)) throw FsException.AlreadyExists(dst)
            if (svc().rename(src.path, dst.path)) dst else null
        }
    }

    override fun search(root: FsPath, query: SearchQuery): Flow<FileEntry> = channelFlow {
        val needle = if (query.text.contains('*') || query.text.contains('?')) "" else query.text.trim()
        val pfd = svc().find(root.path, needle, query.includeHidden)
        ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().useLines { lines ->
            for (l in lines) {
                val slash = l.indexOf("/|").takeIf { it >= 0 } ?: continue
                val parent = FsPath(scheme, "", FsPath.normalize(l.substring(0, slash)))
                val e = parse(l.substring(slash + 2), parent) ?: continue
                if (query.matches(e)) send(e)
            }
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun <T> io(path: FsPath, block: suspend () -> T): T = withContext(Dispatchers.IO) {
        try { block() } catch (e: Exception) { throw FsException.wrap(e, path) }
    }

    companion object { const val REQUEST_CODE = 4711 }
}
