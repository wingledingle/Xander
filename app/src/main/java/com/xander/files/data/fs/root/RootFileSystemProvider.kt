package com.xander.files.data.fs.root

import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.io.SuFile
import com.topjohnwu.superuser.io.SuFileInputStream
import com.topjohnwu.superuser.io.SuFileOutputStream
import com.xander.files.domain.fs.Capability
import com.xander.files.domain.fs.FileEntry
import com.xander.files.domain.fs.FsException
import com.xander.files.domain.fs.FsPath
import com.xander.files.domain.fs.PosixFileSystemProvider
import com.xander.files.domain.fs.ProgressListener
import com.xander.files.domain.fs.SearchQuery
import com.xander.files.domain.fs.SpaceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** Root shell backend (libsu). Paths are absolute device paths, e.g. root:///data/system. */
@Singleton
class RootFileSystemProvider @Inject constructor() : PosixFileSystemProvider {

    override val scheme = FsPath.SCHEME_ROOT

    override fun capabilities(path: FsPath) = setOf(
        Capability.WRITE, Capability.RENAME, Capability.SERVER_SIDE_COPY, Capability.SERVER_SIDE_MOVE,
        Capability.POSIX, Capability.SYMLINKS, Capability.SEARCH, Capability.SPACE_INFO, Capability.THUMBNAILS
    )

    fun isAvailable(): Boolean = try { Shell.getShell().isRoot } catch (e: Exception) { false }

    /** Triggers the su prompt; returns true when granted. */
    suspend fun requestRoot(): Boolean = withContext(Dispatchers.IO) {
        try { Shell.getCachedShell()?.close(); Shell.getShell().isRoot } catch (e: Exception) { false }
    }

    /** Runs [cmd] as root and returns its output, or null if it failed. */
    suspend fun exec(cmd: String): String? = withContext(Dispatchers.IO) { sh(cmd).let { r -> if (r.isSuccess) r.out.joinToString("\n") else null } }

    private fun sh(cmd: String): Shell.Result {
        if (!isAvailable()) throw FsException.PermissionDenied(null, IllegalStateException("Root not available"))
        return Shell.cmd(cmd).exec()
    }

    private fun shOk(cmd: String, path: FsPath) {
        val r = sh(cmd)
        if (!r.isSuccess) {
            val err = (r.err + r.out).joinToString("\n")
            when {
                err.contains("No such file") -> throw FsException.NotFound(path)
                err.contains("Read-only file system") -> throw FsException.Generic("Read-only file system. Remount as R/W first.")
                err.contains("No space") -> throw FsException.OutOfSpace()
                err.contains("File exists") -> throw FsException.AlreadyExists(path)
                else -> throw FsException.Generic(err.ifBlank { "Root command failed" })
            }
        }
    }

    // toybox stat: raw mode(hex) | size | mtime(s) | user | group | name
    private val statFmt = "'%f|%s|%Y|%U|%G|%n'"

    private fun parseStat(line: String, dirOverride: FsPath?): FileEntry? {
        val parts = line.split('|', limit = 6)
        if (parts.size < 6) return null
        val mode = parts[0].toIntOrNull(16) ?: return null
        val fullName = parts[5]
        val type = mode and 0xF000
        val isLink = type == 0xA000
        val path = dirOverride?.child(fullName.substringAfterLast('/')) ?: FsPath(scheme, "", FsPath.normalize(fullName))
        var isDir = type == 0x4000
        if (isLink) isDir = SuFile(fullName).isDirectory
        return FileEntry(
            path = path, isDirectory = isDir,
            size = if (isDir) 0 else parts[1].toLongOrNull() ?: 0,
            lastModified = (parts[2].toLongOrNull() ?: 0) * 1000,
            permissions = com.xander.files.data.fs.local.PosixInfo.permissionString(mode),
            owner = parts[3], group = parts[4], isSymlink = isLink,
            linkTarget = if (isLink) Shell.cmd("readlink ${q(fullName)}").exec().out.firstOrNull() else null,
        )
    }

    override suspend fun list(dir: FsPath): List<FileEntry> = io(dir) {
        val d = dir.path
        val glob = if (d == "/") "" else d
        val r = sh("cd ${q(d)} && for f in ${q(glob)}/* ${q(glob)}/.[!.]* ${q(glob)}/..?*; do [ -e \"\$f\" ] || [ -L \"\$f\" ] && stat -c $statFmt \"\$f\"; done")
        if (!r.isSuccess && r.out.isEmpty()) {
            if (!SuFile(d).exists()) throw FsException.NotFound(dir)
            if (!SuFile(d).isDirectory) throw FsException.NotADirectory(dir)
        }
        r.out.mapNotNull { parseStat(it, dir) }
    }

    override suspend fun stat(path: FsPath): FileEntry = io(path) {
        val r = sh("stat -c $statFmt ${q(path.path)}")
        r.out.firstOrNull()?.let { parseStat(it, path.parent) }?.copy(path = path) ?: throw FsException.NotFound(path)
    }

    override suspend fun exists(path: FsPath): Boolean = io(path) { SuFile(path.path).exists() }

    override suspend fun openInput(path: FsPath, offset: Long): InputStream = io(path) {
        val s = SuFileInputStream.open(SuFile(path.path))
        if (offset > 0) { var left = offset; while (left > 0) { val k = s.skip(left); if (k <= 0) break; left -= k } }
        s
    }

    override suspend fun openOutput(path: FsPath, size: Long): OutputStream = io(path) { SuFileOutputStream.open(SuFile(path.path)) }

    override suspend fun mkdir(parent: FsPath, name: String): FsPath = io(parent) {
        val p = parent.child(name)
        if (SuFile(p.path).exists()) throw FsException.AlreadyExists(p)
        shOk("mkdir -p ${q(p.path)}", p); p
    }

    override suspend fun createFile(parent: FsPath, name: String): FsPath = io(parent) {
        val p = parent.child(name)
        if (SuFile(p.path).exists()) throw FsException.AlreadyExists(p)
        shOk("touch ${q(p.path)}", p); p
    }

    override suspend fun rename(path: FsPath, newName: String): FsPath = io(path) {
        val np = path.parent!!.child(newName)
        if (SuFile(np.path).exists()) throw FsException.AlreadyExists(np)
        shOk("mv ${q(path.path)} ${q(np.path)}", path); np
    }

    override suspend fun delete(path: FsPath) = io(path) { shOk("rm -rf ${q(path.path)}", path) }

    override suspend fun copyInternal(src: FsPath, dstParent: FsPath, name: String, progress: ProgressListener?): FsPath? =
        if (dstParent.scheme != scheme) null else io(src) {
            val dst = dstParent.child(name)
            shOk("cp -a ${q(src.path)} ${q(dst.path)}", dst)
            progress?.onBytes(runCatching { stat(dst).size }.getOrDefault(0)); dst
        }

    override suspend fun moveInternal(src: FsPath, dstParent: FsPath, name: String): FsPath? =
        if (dstParent.scheme != scheme) null else io(src) {
            val dst = dstParent.child(name)
            if (SuFile(dst.path).exists()) throw FsException.AlreadyExists(dst)
            shOk("mv ${q(src.path)} ${q(dst.path)}", dst); dst
        }

    override fun search(root: FsPath, query: SearchQuery): Flow<FileEntry> = channelFlow {
        // `find` in the root shell is much faster than walking via stat calls.
        val args = StringBuilder("find ${q(root.path)} -mindepth 1")
        if (!query.recursive) args.append(" -maxdepth 1")
        // Skip pseudo file systems and the shared-storage backing dirs (searched through the normal volumes).
        args.append(" \\( " + PRUNE.joinToString(" -o ") { "-path ${q(it)}" } + " \\) -prune -o")
        if (query.text.isNotBlank()) {
            val pat = if (query.text.contains('*') || query.text.contains('?')) query.text else "*${query.text}*"
            args.append(" -iname ${q(pat)}")
        }
        if (!query.includeFolders) args.append(" -type f")
        args.append(" -exec stat -c $statFmt {} + 2>/dev/null")
        // `-prune -o` needs an explicit action on the right-hand side, which -exec provides.
        val out = object : com.topjohnwu.superuser.CallbackList<String>() {
            override fun onAddElement(e: String) {
                parseStat(e, null)?.let { if (query.matches(it)) trySend(it) }
            }
        }
        Shell.cmd(args.toString()).to(out).exec()
    }.flowOn(Dispatchers.IO)

    override suspend fun spaceInfo(path: FsPath): SpaceInfo? = io(path) {
        val r = sh("df -k ${q(path.path)} | tail -n 1")
        val cols = r.out.firstOrNull()?.trim()?.split(Regex("\\s+")) ?: return@io null
        val total = cols.getOrNull(1)?.toLongOrNull() ?: return@io null
        val avail = cols.getOrNull(3)?.toLongOrNull() ?: return@io null
        SpaceInfo(total * 1024, avail * 1024)
    }

    override suspend fun setLastModified(path: FsPath, time: Long): Boolean = io(path) {
        sh("touch -m -d @${time / 1000} ${q(path.path)}").isSuccess
    }

    override suspend fun chmod(path: FsPath, mode: Int, recursive: Boolean) = io(path) {
        shOk("chmod ${if (recursive) "-R " else ""}${Integer.toOctalString(mode)} ${q(path.path)}", path)
    }

    override suspend fun chown(path: FsPath, owner: String, group: String, recursive: Boolean) = io(path) {
        shOk("chown ${if (recursive) "-R " else ""}${q("$owner:$group")} ${q(path.path)}", path)
    }

    override suspend fun symlink(target: String, link: FsPath) = io(link) { shOk("ln -s ${q(target)} ${q(link.path)}", link) }

    /** Remounts the mount point containing [path] read-write (or read-only). */
    suspend fun remount(path: String, readWrite: Boolean): Boolean = withContext(Dispatchers.IO) {
        val mode = if (readWrite) "rw" else "ro"
        val mp = mountPointOf(path)
        val r = sh("mount -o remount,$mode ${q(mp)} || mount -o remount,$mode / ")
        r.isSuccess
    }

    suspend fun isReadOnlyMount(path: String): Boolean = withContext(Dispatchers.IO) {
        val mp = mountPointOf(path)
        sh("cat /proc/mounts").out.map { it.split(' ') }.lastOrNull { it.getOrNull(1) == mp }
            ?.getOrNull(3)?.split(',')?.contains("ro") ?: false
    }

    private fun mountPointOf(path: String): String {
        val mounts = sh("cat /proc/mounts").out.mapNotNull { it.split(' ').getOrNull(1) }
        return mounts.filter { path == it || path.startsWith(if (it == "/") "/" else "$it/") }.maxByOrNull { it.length } ?: "/"
    }

    private suspend fun <T> io(path: FsPath, block: suspend () -> T): T = withContext(Dispatchers.IO) {
        try { block() } catch (e: Exception) { throw FsException.wrap(e, path) }
    }

    companion object {
        fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"
        val PRUNE = listOf("/proc", "/sys", "/dev", "/data/media", "/storage", "/mnt", "/sdcard", "/acct", "/config")
    }
}
