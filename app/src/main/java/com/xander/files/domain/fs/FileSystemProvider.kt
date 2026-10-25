package com.xander.files.domain.fs

import android.graphics.Bitmap
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.InputStream
import java.io.OutputStream
import kotlin.coroutines.coroutineContext

fun interface ProgressListener {
    /** Called with the number of bytes processed since the last call. */
    fun onBytes(delta: Long)
}

/**
 * One interface for every storage backend (local, root, SAF, archive, LAN, network, cloud, TV).
 * All functions are main-safe: implementations switch to IO themselves.
 */
interface FileSystemProvider {
    val scheme: String
    fun capabilities(path: FsPath): Set<Capability>

    suspend fun list(dir: FsPath): List<FileEntry>
    suspend fun stat(path: FsPath): FileEntry
    suspend fun exists(path: FsPath): Boolean = try { stat(path); true } catch (e: FsException.NotFound) { false }

    suspend fun openInput(path: FsPath, offset: Long = 0L): InputStream
    /** Opens an output stream that creates or truncates [path]. [size] is a hint (needed by some cloud APIs). */
    suspend fun openOutput(path: FsPath, size: Long = -1L): OutputStream

    suspend fun mkdir(parent: FsPath, name: String): FsPath
    suspend fun createFile(parent: FsPath, name: String): FsPath {
        val p = parent.child(name)
        if (exists(p)) throw FsException.AlreadyExists(p)
        openOutput(p, 0).close()
        return p
    }
    suspend fun rename(path: FsPath, newName: String): FsPath
    /** Deletes recursively. */
    suspend fun delete(path: FsPath)

    /** Same-backend copy done by the server/OS; return null if not supported so the engine streams. */
    suspend fun copyInternal(src: FsPath, dstParent: FsPath, name: String, progress: ProgressListener?): FsPath? = null
    /** Same-backend move (rename across folders); return null if not supported. */
    suspend fun moveInternal(src: FsPath, dstParent: FsPath, name: String): FsPath? = null

    fun search(root: FsPath, query: SearchQuery): Flow<FileEntry> = flow {
        val stack = ArrayDeque<FsPath>().apply { add(root) }
        while (stack.isNotEmpty()) {
            coroutineContext.ensureActive()
            val dir = stack.removeLast()
            val children = try { list(dir) } catch (e: FsException) { continue }
            for (c in children) {
                if (query.matches(c)) emit(c)
                if (c.isDirectory && !c.isSymlink && query.recursive && (query.includeHidden || !c.isHidden)) stack.add(c.path)
            }
        }
    }

    suspend fun thumbnail(entry: FileEntry, sizePx: Int): Bitmap? = null
    suspend fun shareLink(path: FsPath): String? = null
    suspend fun spaceInfo(path: FsPath): SpaceInfo? = null
    suspend fun setLastModified(path: FsPath, time: Long): Boolean = false

    /** Human readable root title (e.g. connection name). */
    suspend fun rootTitle(authority: String): String = authority
}

/** Extra operations offered by POSIX-like backends (local, root, Shizuku, SFTP). */
interface PosixFileSystemProvider : FileSystemProvider {
    suspend fun chmod(path: FsPath, mode: Int, recursive: Boolean)
    suspend fun chown(path: FsPath, owner: String, group: String, recursive: Boolean)
    suspend fun symlink(target: String, link: FsPath)
}

/** Recursively collects size and item counts. */
suspend fun FileSystemProvider.measure(path: FsPath, onProgress: (Long, Int, Int) -> Unit = { _, _, _ -> }): Triple<Long, Int, Int> {
    var size = 0L; var files = 0; var dirs = 0
    val root = stat(path)
    if (!root.isDirectory) return Triple(root.size, 1, 0)
    val stack = ArrayDeque<FsPath>().apply { add(path) }
    while (stack.isNotEmpty()) {
        coroutineContext.ensureActive()
        val d = stack.removeLast()
        val children = try { list(d) } catch (e: FsException) { emptyList() }
        for (c in children) {
            if (c.isDirectory) { dirs++; if (!c.isSymlink) stack.add(c.path) } else { files++; size += c.size }
        }
        onProgress(size, files, dirs)
    }
    return Triple(size, files, dirs)
}

/** Backends that can notify about changes inside a directory (FileObserver for local storage). */
interface WatchableProvider {
    fun watch(dir: FsPath): Flow<Unit>
}
