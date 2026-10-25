package com.xander.files.data.fs.local

import android.os.Build
import android.os.Environment
import android.os.FileObserver
import android.system.Os
import android.system.OsConstants
import com.xander.files.data.fs.root.RootFileSystemProvider
import com.xander.files.data.fs.saf.SafFileSystemProvider
import com.xander.files.data.fs.saf.SafRootStore
import com.xander.files.data.fs.shizuku.ShizukuFileSystemProvider
import com.xander.files.data.settings.SettingsRepository
import com.xander.files.data.storage.StorageVolumes
import com.xander.files.domain.fs.Capability
import com.xander.files.domain.fs.FileEntry
import com.xander.files.domain.fs.FileSystemProvider
import com.xander.files.domain.fs.FsException
import com.xander.files.domain.fs.FsPath
import com.xander.files.domain.fs.PosixFileSystemProvider
import com.xander.files.domain.fs.ProgressListener
import com.xander.files.domain.fs.SpaceInfo
import com.xander.files.domain.fs.WatchableProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * java.io.File based backend for every directly accessible path. Transparently falls back to:
 *  - a SAF tree grant for removable volumes that are read-only to apps (Android 5-10 SD cards / OTG)
 *  - SAF grant / Shizuku / root for the restricted Android/data and Android/obb folders on Android 11+
 */
@Singleton
open class LocalFileSystemProvider @Inject constructor(
    private val volumes: StorageVolumes,
    private val safRoots: SafRootStore,
    private val saf: Provider<SafFileSystemProvider>,
    private val shizuku: Provider<ShizukuFileSystemProvider>,
    private val root: Provider<RootFileSystemProvider>,
    private val settings: SettingsRepository,
) : PosixFileSystemProvider, WatchableProvider {

    override val scheme = FsPath.SCHEME_FILE

    override fun capabilities(path: FsPath) = setOf(
        Capability.WRITE, Capability.RENAME, Capability.SERVER_SIDE_MOVE, Capability.SERVER_SIDE_COPY,
        Capability.THUMBNAILS, Capability.POSIX, Capability.SYMLINKS, Capability.RANDOM_ACCESS,
        Capability.APPEND, Capability.SPACE_INFO, Capability.WATCH, Capability.SEARCH
    )

    protected fun file(path: FsPath) = File(path.path)

    // ---- delegation for paths the app cannot touch directly ----

    private fun isRestricted(path: String): Boolean {
        if (Build.VERSION.SDK_INT < 30) return false
        val primary = volumes.primaryRoot
        val restricted = listOf("$primary/Android/data", "$primary/Android/obb")
        return restricted.any { path == it || path.startsWith("$it/") }
    }

    /** Returns an equivalent location on a backend that can access [path], or null to use java.io. */
    private suspend fun delegateFor(path: FsPath, write: Boolean): FsPath? {
        val p = path.path
        if (isRestricted(p)) {
            safRoots.findForLocalPath(p)?.let { (r, rel) -> return FsPath(FsPath.SCHEME_SAF, r.id, rel) }
            if (shizuku.get().isAvailable()) return path.copy(scheme = FsPath.SCHEME_SHIZUKU)
            if (settings.settings.first().rootMode && root.get().isAvailable()) return path.copy(scheme = FsPath.SCHEME_ROOT)
            throw RestrictedFolderException(path)
        }
        if (write && volumes.needsSafForWrite(p)) {
            safRoots.findForLocalPath(p)?.let { (r, rel) -> return FsPath(FsPath.SCHEME_SAF, r.id, rel) }
            throw SafGrantRequiredException(path, volumes.volumeFor(p)?.uuid)
        }
        return null
    }

    private fun delegateProvider(p: FsPath): FileSystemProvider = when (p.scheme) {
        FsPath.SCHEME_SAF -> saf.get()
        FsPath.SCHEME_SHIZUKU -> shizuku.get()
        else -> root.get()
    }

    private fun FileEntry.rebase(original: FsPath, delegated: FsPath): FileEntry {
        val rel = path.relativeTo(delegated)
        return copy(path = if (rel.isEmpty()) original else original.resolve(rel))
    }

    private suspend fun FsPath.mapBack(original: FsPath, delegated: FsPath): FsPath {
        val rel = relativeTo(delegated.parent ?: delegated)
        return (original.parent ?: original).resolve(rel)
    }

    // ---- FileSystemProvider ----

    override suspend fun list(dir: FsPath): List<FileEntry> {
        delegateFor(dir, false)?.let { d -> return delegateProvider(d).list(d).map { it.rebase(dir, d) } }
        return io(dir) {
            val f = file(dir)
            if (!f.exists()) throw FsException.NotFound(dir)
            if (!f.isDirectory) throw FsException.NotADirectory(dir)
            val names = f.list() ?: throw FsException.PermissionDenied(dir)
            names.map { entry(dir.child(it), File(f, it)) }
        }
    }

    override suspend fun stat(path: FsPath): FileEntry {
        delegateFor(path, false)?.let { d -> return delegateProvider(d).stat(d).rebase(path, d) }
        return io(path) {
            val f = file(path)
            if (!f.exists() && !isSymlink(f)) throw FsException.NotFound(path)
            entry(path, f)
        }
    }

    override suspend fun exists(path: FsPath): Boolean {
        delegateFor(path, false)?.let { d -> return delegateProvider(d).exists(d) }
        return withContext(Dispatchers.IO) { file(path).exists() }
    }

    override suspend fun openInput(path: FsPath, offset: Long): InputStream {
        delegateFor(path, false)?.let { d -> return delegateProvider(d).openInput(d, offset) }
        return io(path) {
            val s = FileInputStream(file(path))
            if (offset > 0) s.channel.position(offset)
            s
        }
    }

    override suspend fun openOutput(path: FsPath, size: Long): OutputStream {
        delegateFor(path, true)?.let { d -> return delegateProvider(d).openOutput(d, size) }
        return io(path) {
            val f = file(path)
            f.parentFile?.let { if (!it.exists()) it.mkdirs() }
            FileOutputStream(f)
        }
    }

    fun openAppend(path: FsPath): OutputStream = FileOutputStream(file(path), true)

    override suspend fun mkdir(parent: FsPath, name: String): FsPath {
        validateName(name)
        delegateFor(parent, true)?.let { d -> return delegateProvider(d).mkdir(d, name).let { parent.child(name) } }
        return io(parent) {
            val f = File(file(parent), name)
            if (f.exists()) throw FsException.AlreadyExists(parent.child(name))
            if (!f.mkdirs()) throw FsException.PermissionDenied(parent)
            parent.child(name)
        }
    }

    override suspend fun createFile(parent: FsPath, name: String): FsPath {
        validateName(name)
        delegateFor(parent, true)?.let { d -> delegateProvider(d).createFile(d, name); return parent.child(name) }
        return io(parent) {
            val f = File(file(parent), name)
            if (f.exists()) throw FsException.AlreadyExists(parent.child(name))
            if (!f.createNewFile()) throw FsException.PermissionDenied(parent)
            parent.child(name)
        }
    }

    override suspend fun rename(path: FsPath, newName: String): FsPath {
        validateName(newName)
        delegateFor(path, true)?.let { d -> delegateProvider(d).rename(d, newName); return path.parent!!.child(newName) }
        return io(path) {
            val f = file(path)
            val target = File(f.parentFile, newName)
            if (target.exists() && !target.absolutePath.equals(f.absolutePath, ignoreCase = true)) throw FsException.AlreadyExists(path.parent!!.child(newName))
            if (!f.renameTo(target)) {
                // Case-only renames on FAT/exFAT need a two step rename.
                val tmp = File(f.parentFile, ".xrn_${System.nanoTime()}")
                if (!(f.renameTo(tmp) && tmp.renameTo(target))) throw FsException.PermissionDenied(path)
            }
            path.parent!!.child(newName)
        }
    }

    override suspend fun delete(path: FsPath) {
        delegateFor(path, true)?.let { d -> return delegateProvider(d).delete(d) }
        io(path) {
            val f = file(path)
            if (!f.exists() && !isSymlink(f)) throw FsException.NotFound(path)
            if (!deleteRecursive(f)) throw FsException.PermissionDenied(path)
        }
    }

    private fun deleteRecursive(f: File): Boolean {
        if (f.isDirectory && !isSymlink(f)) f.listFiles()?.forEach { if (!deleteRecursive(it)) return false }
        return f.delete() || !f.exists()
    }

    override suspend fun moveInternal(src: FsPath, dstParent: FsPath, name: String): FsPath? {
        if (delegateFor(src, true) != null || delegateFor(dstParent, true) != null) return null
        return io(src) {
            val from = file(src)
            val to = File(file(dstParent), name)
            if (to.exists()) throw FsException.AlreadyExists(dstParent.child(name))
            // renameTo only works on the same mount point; the engine falls back to copy+delete otherwise.
            if (from.renameTo(to)) dstParent.child(name) else null
        }
    }

    override suspend fun copyInternal(src: FsPath, dstParent: FsPath, name: String, progress: ProgressListener?): FsPath? = null

    override suspend fun spaceInfo(path: FsPath): SpaceInfo? = withContext(Dispatchers.IO) {
        val (t, f) = volumes.space(generateSequence(file(path)) { it.parentFile }.firstOrNull { it.exists() }?.absolutePath)
        if (t > 0) SpaceInfo(t, f) else null
    }

    override suspend fun setLastModified(path: FsPath, time: Long): Boolean =
        withContext(Dispatchers.IO) { file(path).setLastModified(time) }

    override suspend fun rootTitle(authority: String) = "/"

    override suspend fun chmod(path: FsPath, mode: Int, recursive: Boolean) = io(path) {
        fun apply(f: File) {
            Os.chmod(f.absolutePath, mode)
            if (recursive && f.isDirectory) f.listFiles()?.forEach(::apply)
        }
        apply(file(path))
    }

    override suspend fun chown(path: FsPath, owner: String, group: String, recursive: Boolean) {
        // Changing ownership needs root.
        root.get().chown(path.copy(scheme = FsPath.SCHEME_ROOT), owner, group, recursive)
    }

    override suspend fun symlink(target: String, link: FsPath) = io(link) { Os.symlink(target, link.path) }

    override fun watch(dir: FsPath): Flow<Unit> = callbackFlow {
        val mask = FileObserver.CREATE or FileObserver.DELETE or FileObserver.MOVED_FROM or FileObserver.MOVED_TO or
            FileObserver.CLOSE_WRITE or FileObserver.DELETE_SELF or FileObserver.MOVE_SELF or FileObserver.ATTRIB
        val observer = if (Build.VERSION.SDK_INT >= 29) {
            object : FileObserver(file(dir), mask) { override fun onEvent(event: Int, p: String?) { trySend(Unit) } }
        } else {
            @Suppress("DEPRECATION")
            object : FileObserver(dir.path, mask) { override fun onEvent(event: Int, p: String?) { trySend(Unit) } }
        }
        observer.startWatching()
        awaitClose { observer.stopWatching() }
    }.conflate()

    // ---- helpers ----

    private fun isSymlink(f: File): Boolean = try {
        OsConstants.S_ISLNK(Os.lstat(f.absolutePath).st_mode)
    } catch (e: Exception) { false }

    protected fun entry(path: FsPath, f: File): FileEntry {
        var perms: String? = null; var owner: String? = null; var group: String? = null
        var link = false; var linkTarget: String? = null
        try {
            val l = Os.lstat(f.absolutePath)
            link = OsConstants.S_ISLNK(l.st_mode)
            if (link) linkTarget = runCatching { Os.readlink(f.absolutePath) }.getOrNull()
            val st = if (link) runCatching { Os.stat(f.absolutePath) }.getOrDefault(l) else l
            perms = PosixInfo.permissionString(st.st_mode)
            owner = PosixInfo.userName(st.st_uid)
            group = PosixInfo.userName(st.st_gid)
        } catch (_: Exception) { }
        val dir = f.isDirectory
        return FileEntry(
            path = path,
            isDirectory = dir,
            size = if (dir) 0 else f.length(),
            lastModified = f.lastModified(),
            childCount = null,
            permissions = perms, owner = owner, group = group,
            isSymlink = link, linkTarget = linkTarget,
            canRead = f.canRead(), canWrite = f.canWrite(),
        )
    }

    /** Counts children for "show folder item counts" lazily (separate from list() to keep listing fast). */
    suspend fun childCount(path: FsPath): Int? = withContext(Dispatchers.IO) { file(path).list()?.size }

    private fun validateName(name: String) {
        if (name.isBlank() || name == "." || name == ".." || name.contains('/') || name.contains('\u0000')) throw FsException.InvalidName(name)
    }

    private suspend fun <T> io(path: FsPath, block: suspend () -> T): T = withContext(Dispatchers.IO) {
        try { block() } catch (e: Exception) { throw FsException.wrap(e, path) }
    }

    companion object {
        val externalRoot: String get() = Environment.getExternalStorageDirectory().absolutePath
    }
}

/** Thrown for Android/data|obb on Android 11+ when no SAF grant, Shizuku or root is available. */
class RestrictedFolderException(val path: FsPath) :
    FsException.Generic("Android restricts access to this folder. Grant access, or enable Shizuku or root.")

/** Thrown when a removable volume needs a SAF tree grant to be written. */
class SafGrantRequiredException(val path: FsPath, val volumeUuid: String?) :
    FsException.Generic("Write access to this storage must be granted once.")
