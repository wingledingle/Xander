package com.xander.files.data.archive

import android.content.Context
import com.xander.files.domain.fs.Capability
import com.xander.files.domain.fs.FileEntry
import com.xander.files.domain.fs.FileSystemProvider
import com.xander.files.domain.fs.FileSystemRegistry
import com.xander.files.domain.fs.FsException
import com.xander.files.domain.fs.FsPath
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.Charset
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/** Remembers archive passwords for the session (never persisted). */
@Singleton
class ArchivePasswords @Inject constructor() {
    private val map = ConcurrentHashMap<String, CharArray>()
    fun get(archive: FsPath): CharArray? = map[archive.toString()]
    fun set(archive: FsPath, password: String) { map[archive.toString()] = password.toCharArray() }
    fun clear(archive: FsPath) { map.remove(archive.toString()) }
}

/**
 * Read-only backend that exposes the inside of ZIP / 7z / TAR(.gz/.bz2/.xz) / GZ / BZ2 / XZ archives
 * as folders: `archive://<urlencoded archive FsPath>/inner/path`. Works for archives on any backend.
 */
@Singleton
class ArchiveFileSystemProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val registry: Provider<FileSystemRegistry>,
    private val passwords: ArchivePasswords,
) : FileSystemProvider {

    override val scheme = FsPath.SCHEME_ARCHIVE
    override fun capabilities(path: FsPath) = setOf(Capability.THUMBNAILS)

    private class Session(val key: String, val reader: ArchiveReader, val tree: Map<String, List<ArchiveItem>>, val items: Map<String, ArchiveItem>)

    private val sessions = object : LinkedHashMap<String, Session>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Session>?): Boolean {
            if (size > 6) { runCatching { eldest?.value?.reader?.close() }; return true }
            return false
        }
    }
    private val lock = Mutex()
    private val cacheDir = File(context.cacheDir, "archives").apply { mkdirs() }

    fun archiveOf(path: FsPath): FsPath = FsPath.parse(FsPath.decodeAuthority(path.authority))

    var charset: Charset? = null

    private suspend fun session(path: FsPath): Session = lock.withLock {
        val archive = archiveOf(path)
        val provider = registry.get().provider(archive)
        val st = provider.stat(archive)
        val key = "$archive|${st.size}|${st.lastModified}|${passwords.get(archive)?.concatToString().hashCode()}"
        sessions[key]?.let { return it }
        val local = localCopy(archive, st)
        val reader = try {
            ArchiveReaders.open(local, archive.name, passwords.get(archive), charset)
        } catch (e: ArchivePasswordException) {
            throw if (e.wrong) FsException.WrongPassword(e) else FsException.PasswordRequired(archive)
        } catch (e: UnsupportedArchiveException) {
            throw FsException.Unsupported(e.message ?: "archive")
        }
        val items = try { reader.items() } catch (e: ArchivePasswordException) {
            reader.close(); throw if (e.wrong) FsException.WrongPassword(e) else FsException.PasswordRequired(archive)
        }
        val byPath = HashMap<String, ArchiveItem>()
        items.forEach { byPath[it.path] = it }
        // Synthesize parent directories that are not stored explicitly.
        for (it in items) {
            var p = it.path.substringBeforeLast('/', "")
            while (p.isNotEmpty() && p != "/") {
                if (byPath[p] == null) byPath[p] = ArchiveItem(p, true, 0, 0, it.modified, false)
                p = p.substringBeforeLast('/', "")
            }
        }
        val tree = byPath.values.filter { it.path != "/" }.groupBy { it.path.substringBeforeLast('/').ifEmpty { "/" } }
        Session(key, reader, tree, byPath).also { sessions[key] = it }
    }

    private suspend fun localCopy(archive: FsPath, st: FileEntry): File = withContext(Dispatchers.IO) {
        if (archive.isLocal) {
            val f = File(archive.path)
            if (f.canRead()) return@withContext f
        }
        val digest = MessageDigest.getInstance("MD5").digest("$archive|${st.size}|${st.lastModified}".toByteArray()).joinToString("") { "%02x".format(it) }
        val out = File(cacheDir, digest + "_" + archive.name)
        if (!out.exists() || out.length() != st.size) {
            val tmp = File(out.path + ".part")
            registry.get().provider(archive).openInput(archive).use { i -> tmp.outputStream().use { i.copyTo(it, 1 shl 18) } }
            tmp.renameTo(out)
        }
        trim()
        out
    }

    private fun trim() {
        val files = cacheDir.listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) { if (total < MAX_CACHE) break; total -= f.length(); f.delete() }
    }

    private fun ArchiveItem.toEntry(base: FsPath) = FileEntry(
        path = FsPath(scheme, base.authority, path), isDirectory = isDirectory,
        size = if (size < 0) 0 else size, lastModified = modified, canWrite = false,
    )

    override suspend fun list(dir: FsPath): List<FileEntry> = io(dir) {
        val s = session(dir)
        if (!dir.isRoot && s.items[dir.path]?.isDirectory != true) throw FsException.NotADirectory(dir)
        s.tree[dir.path].orEmpty().map { it.toEntry(dir) }
    }

    override suspend fun stat(path: FsPath): FileEntry = io(path) {
        if (path.isRoot) {
            val a = archiveOf(path)
            val st = registry.get().provider(a).stat(a)
            FileEntry(path, true, 0, st.lastModified, canWrite = false)
        } else session(path).items[path.path]?.toEntry(path) ?: throw FsException.NotFound(path)
    }

    override suspend fun openInput(path: FsPath, offset: Long): InputStream = io(path) {
        val s = session(path)
        val item = s.items[path.path] ?: throw FsException.NotFound(path)
        if (item.encrypted && passwords.get(archiveOf(path)) == null) throw FsException.PasswordRequired(archiveOf(path))
        val stream = try { s.reader.open(path.path) } catch (e: ArchivePasswordException) {
            throw if (e.wrong) FsException.WrongPassword(e) else FsException.PasswordRequired(archiveOf(path))
        }
        if (offset > 0) { var left = offset; while (left > 0) { val k = stream.skip(left); if (k <= 0) break; left -= k } }
        stream
    }

    /** Streams every file of the archive once (single pass; fast for stream-only TAR/GZ formats). */
    suspend fun forEachEntry(archiveRoot: FsPath, block: suspend (ArchiveItem, InputStream) -> Unit) = io(archiveRoot) {
        val s = session(archiveRoot)
        if (s.reader.randomAccess) {
            for (it in s.items.values.sortedBy { it.path }) if (!it.isDirectory) s.reader.open(it.path).use { st -> block(it, st) }
        } else {
            // The reader callback is blocking and we are already on an IO thread.
            s.reader.forEach { item, input -> kotlinx.coroutines.runBlocking { block(item, input) } }
        }
    }

    /** True when the archive is encrypted and no password has been entered yet (or the stored one is wrong). */
    suspend fun needsPassword(root: FsPath): Boolean = try {
        val s = session(root)
        s.items.values.any { it.encrypted } && passwords.get(archiveOf(root)) == null
    } catch (e: FsException.PasswordRequired) { true } catch (e: FsException.WrongPassword) { true }

    suspend fun isRandomAccess(root: FsPath) = session(root).reader.randomAccess
    suspend fun allItems(root: FsPath): Collection<ArchiveItem> = session(root).items.values

    private fun readOnly(): Nothing = throw FsException.Unsupported("archives are read-only; extract first")
    override suspend fun openOutput(path: FsPath, size: Long): OutputStream = readOnly()
    override suspend fun mkdir(parent: FsPath, name: String): FsPath = readOnly()
    override suspend fun rename(path: FsPath, newName: String): FsPath = readOnly()
    override suspend fun delete(path: FsPath) = readOnly()

    override suspend fun rootTitle(authority: String): String = FsPath.parse(FsPath.decodeAuthority(authority)).name

    private suspend fun <T> io(path: FsPath, block: suspend () -> T): T = withContext(Dispatchers.IO) {
        try { block() } catch (e: FsException) { throw e } catch (e: ArchivePasswordException) {
            throw if (e.wrong) FsException.WrongPassword(e) else FsException.PasswordRequired(archiveOf(path))
        } catch (e: Exception) { throw FsException.wrap(e, path) }
    }

    companion object { const val MAX_CACHE = 1L shl 30 }
}
