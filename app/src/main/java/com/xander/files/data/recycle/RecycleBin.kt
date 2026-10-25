package com.xander.files.data.recycle

import com.xander.files.data.db.RecycleDao
import com.xander.files.data.db.RecycleEntity
import com.xander.files.data.storage.StorageVolumes
import com.xander.files.data.transfer.CopyEngine
import com.xander.files.data.transfer.TransferContext
import com.xander.files.domain.fs.FileSystemRegistry
import com.xander.files.domain.fs.FsException
import com.xander.files.domain.fs.FsPath
import com.xander.files.domain.fs.measure
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Recycle bin kept on the same volume/backend as the deleted item (fast rename, works on SD cards too):
 *   <volume root>/.XanderRecycle/<uuid>   with the original path stored in Room.
 */
@Singleton
class RecycleBin @Inject constructor(
    private val dao: RecycleDao,
    private val registry: FileSystemRegistry,
    private val volumes: StorageVolumes,
    private val copy: CopyEngine,
) {
    fun observe(): Flow<List<RecycleEntity>> = dao.observeAll()
    fun observeCount() = dao.observeCount()
    fun observeSize() = dao.observeSize()

    fun binRootFor(path: FsPath): FsPath = when (path.scheme) {
        FsPath.SCHEME_FILE, FsPath.SCHEME_ROOT, FsPath.SCHEME_SHIZUKU -> {
            val vol = volumes.volumeFor(path.path)?.directPath
            if (vol != null) FsPath(path.scheme, "", vol).child(DIR) else FsPath(path.scheme, "", "/").child(DIR)
        }
        else -> FsPath(path.scheme, path.authority, "/").child(DIR)
    }

    suspend fun moveToBin(path: FsPath, ctx: TransferContext?) {
        val p = registry.provider(path)
        val entry = p.stat(path)
        if (path.path.contains("/$DIR")) { p.delete(path); return } // deleting inside the bin is permanent
        val root = binRootFor(path)
        if (!p.exists(root)) {
            p.mkdir(root.parent!!, DIR)
            runCatching { p.createFile(root, ".nomedia") }
        }
        val id = UUID.randomUUID().toString()
        val size = if (entry.isDirectory) runCatching { p.measure(path).first }.getOrDefault(0L) else entry.size
        val moved = p.moveInternal(path, root, id)
        if (moved == null) {
            // Different mount point or backend without rename-across-folders: copy then delete.
            val c = ctx ?: throw FsException.Unsupported("recycle")
            p.mkdir(root, id)
            copy.transfer(listOf(path), root.child(id), move = true, ctx = c)
            dao.insert(RecycleEntity(id, path.toString(), root.child(id).child(entry.name).toString(), entry.name, entry.isDirectory, size, System.currentTimeMillis()))
            return
        }
        dao.insert(RecycleEntity(id, path.toString(), moved.toString(), entry.name, entry.isDirectory, size, System.currentTimeMillis()))
    }

    suspend fun restore(id: String, ctx: TransferContext? = null) {
        val e = dao.get(id) ?: return
        val bin = FsPath.parse(e.binPath)
        val original = FsPath.parse(e.originalPath)
        val p = registry.provider(bin)
        val parent = original.parent ?: throw FsException.NotFound(original)
        if (!p.exists(parent)) {
            // Recreate missing parents.
            var cur: FsPath = FsPath(parent.scheme, parent.authority, "/")
            for (seg in parent.path.removePrefix("/").split('/')) {
                val next = cur.child(seg)
                if (!p.exists(next)) p.mkdir(cur, seg)
                cur = next
            }
        }
        val name = if (p.exists(original)) CopyEngine.uniqueName(p, parent, original.name) else original.name
        if (p.moveInternal(bin, parent, name) == null) {
            val c = ctx ?: throw FsException.Unsupported("restore")
            copy.transfer(listOf(bin), parent, move = true, ctx = c)
        }
        // Clean the per-item folder used by copy-based recycling.
        bin.parent?.takeIf { it.name == id }?.let { runCatching { p.delete(it) } }
        dao.delete(id)
    }

    suspend fun purge(id: String) {
        val e = dao.get(id) ?: return
        val bin = FsPath.parse(e.binPath)
        val p = registry.provider(bin)
        runCatching { p.delete(if (bin.parent?.name == id) bin.parent!! else bin) }
        dao.delete(id)
    }

    suspend fun empty() { dao.all().forEach { purge(it.id) } }

    suspend fun purgeOlderThan(days: Int) {
        if (days <= 0) return
        dao.olderThan(System.currentTimeMillis() - days * 86_400_000L).forEach { purge(it.id) }
    }

    suspend fun get(id: String) = dao.get(id)

    companion object { const val DIR = ".XanderRecycle" }
}
