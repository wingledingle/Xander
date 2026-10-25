package com.xander.files.fs

import com.xander.files.domain.fs.Capability
import com.xander.files.domain.fs.FileEntry
import com.xander.files.domain.fs.FileSystemProvider
import com.xander.files.domain.fs.FsException
import com.xander.files.domain.fs.FsPath
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/** In-memory backend for engine tests; [supportsMove] toggles server-side move. */
class MemoryFileSystemProvider(override val scheme: String, private val supportsMove: Boolean = true) : FileSystemProvider {
    val files = HashMap<String, ByteArray>()
    val dirs = HashSet<String>().apply { add("/") }
    val mtimes = HashMap<String, Long>()
    var internalMoves = 0

    override fun capabilities(path: FsPath) = setOf(Capability.WRITE, Capability.RENAME)

    fun put(path: String, content: String) {
        var p = path.substringBeforeLast('/').ifEmpty { "/" }
        while (p != "/") { dirs.add(p); p = p.substringBeforeLast('/').ifEmpty { "/" } }
        files[path] = content.toByteArray(); mtimes[path] = 1000
    }

    fun read(path: String) = files[path]?.let { String(it) }

    private fun parentOf(p: String) = p.substringBeforeLast('/').ifEmpty { "/" }

    override suspend fun list(dir: FsPath): List<FileEntry> {
        if (dir.path !in dirs) throw FsException.NotFound(dir)
        val prefix = if (dir.path == "/") "/" else dir.path + "/"
        val d = dirs.filter { it != "/" && parentOf(it) == dir.path }.map { FileEntry(FsPath(scheme, "", it), true) }
        val f = files.keys.filter { parentOf(it) == dir.path && it.startsWith(prefix) }.map { FileEntry(FsPath(scheme, "", it), false, files[it]!!.size.toLong(), mtimes[it] ?: 0) }
        return d + f
    }

    override suspend fun stat(path: FsPath): FileEntry = when {
        path.path in dirs -> FileEntry(path, true)
        path.path in files -> FileEntry(path, false, files[path.path]!!.size.toLong(), mtimes[path.path] ?: 0)
        else -> throw FsException.NotFound(path)
    }

    override suspend fun openInput(path: FsPath, offset: Long): InputStream =
        ByteArrayInputStream(files[path.path] ?: throw FsException.NotFound(path)).also { it.skip(offset) }

    override suspend fun openOutput(path: FsPath, size: Long): OutputStream {
        if (parentOf(path.path) !in dirs) throw FsException.NotFound(path)
        return object : ByteArrayOutputStream() {
            override fun close() { super.close(); files[path.path] = toByteArray() }
            override fun flush() { files[path.path] = toByteArray() }
        }
    }

    override suspend fun mkdir(parent: FsPath, name: String): FsPath {
        val p = parent.child(name)
        if (p.path in dirs || p.path in files) throw FsException.AlreadyExists(p)
        dirs.add(p.path); return p
    }

    override suspend fun rename(path: FsPath, newName: String): FsPath {
        val np = path.parent!!.child(newName)
        moveTree(path.path, np.path); return np
    }

    override suspend fun delete(path: FsPath) {
        if (path.path !in dirs && path.path !in files) throw FsException.NotFound(path)
        files.keys.removeAll { it == path.path || it.startsWith(path.path + "/") }
        dirs.removeAll { it == path.path || it.startsWith(path.path + "/") }
    }

    override suspend fun moveInternal(src: FsPath, dstParent: FsPath, name: String): FsPath? {
        if (!supportsMove || dstParent.scheme != scheme) return null
        val dst = dstParent.child(name)
        moveTree(src.path, dst.path); internalMoves++; return dst
    }

    override suspend fun setLastModified(path: FsPath, time: Long): Boolean { mtimes[path.path] = time; return true }

    private fun moveTree(from: String, to: String) {
        files.keys.filter { it == from || it.startsWith("$from/") }.forEach { k -> files[to + k.removePrefix(from)] = files.remove(k)!! }
        val ds = dirs.filter { it == from || it.startsWith("$from/") }
        dirs.removeAll(ds.toSet()); ds.forEach { dirs.add(to + it.removePrefix(from)) }
    }
}
