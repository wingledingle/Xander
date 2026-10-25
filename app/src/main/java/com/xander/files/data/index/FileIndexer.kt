package com.xander.files.data.index

import android.content.Context
import android.os.Build
import android.provider.MediaStore
import com.xander.files.data.db.FileIndexDao
import com.xander.files.data.db.FileIndexEntity
import com.xander.files.data.storage.StorageVolumes
import com.xander.files.domain.fs.FileCategory
import com.xander.files.domain.fs.FsPath
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

data class IndexStatus(val running: Boolean = false, val indexed: Int = 0, val lastRun: Long = 0)

/**
 * Builds the local file index used for category counts, Library views, large/recent files and instant search.
 * Pass 1 reads MediaStore (fast, available right after launch); pass 2 walks every volume to catch everything.
 */
@Singleton
class FileIndexer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: FileIndexDao,
    private val volumes: StorageVolumes,
) {
    private val mutex = Mutex()
    private val _status = MutableStateFlow(IndexStatus())
    val status: StateFlow<IndexStatus> = _status

    suspend fun indexAll(full: Boolean = true) = withContext(Dispatchers.IO) {
        if (mutex.isLocked) return@withContext
        mutex.withLock {
            val stamp = System.currentTimeMillis()
            _status.value = IndexStatus(true, 0, _status.value.lastRun)
            try {
                if (dao.count() == 0) fromMediaStore(stamp)
                if (full) for (v in volumes.volumes()) {
                    val root = v.directPath ?: continue
                    walk(File(root), v.id, stamp)
                    dao.pruneStale(v.id, stamp)
                }
            } finally {
                _status.value = IndexStatus(false, _status.value.indexed, stamp)
            }
        }
    }

    private suspend fun fromMediaStore(stamp: Long) {
        val uri = MediaStore.Files.getContentUri("external")
        val proj = arrayOf(MediaStore.Files.FileColumns.DATA, MediaStore.Files.FileColumns.SIZE, MediaStore.Files.FileColumns.DATE_MODIFIED)
        val batch = ArrayList<FileIndexEntity>(1000)
        val primary = volumes.primaryRoot
        runCatching {
            context.contentResolver.query(uri, proj, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    coroutineContext.ensureActive()
                    val path = c.getString(0) ?: continue
                    val f = File(path)
                    if (f.isDirectory) continue
                    batch += row(path, c.getLong(1), c.getLong(2) * 1000, false, if (path.startsWith(primary)) "primary" else volumeIdFor(path), stamp)
                    if (batch.size >= 1000) flush(batch)
                }
            }
        }
        flush(batch)
    }

    private fun volumeIdFor(path: String) = volumes.volumeFor(path)?.id ?: "primary"

    private suspend fun walk(root: File, volume: String, stamp: Long) {
        val batch = ArrayList<FileIndexEntity>(1000)
        val stack = ArrayDeque<File>().apply { add(root) }
        val restricted = if (Build.VERSION.SDK_INT >= 30) setOf("${volumes.primaryRoot}/Android/data", "${volumes.primaryRoot}/Android/obb") else emptySet()
        while (stack.isNotEmpty()) {
            coroutineContext.ensureActive()
            val dir = stack.removeLast()
            val kids = dir.listFiles() ?: continue
            for (k in kids) {
                val isDir = k.isDirectory
                if (isDir && (k.absolutePath in restricted || k.name == RECYCLE_DIR)) continue
                batch += row(k.absolutePath, if (isDir) 0 else k.length(), k.lastModified(), isDir, volume, stamp)
                if (isDir) stack.add(k)
            }
            if (batch.size >= 1000) flush(batch)
        }
        flush(batch)
    }

    private fun row(path: String, size: Long, modified: Long, isDir: Boolean, volume: String, stamp: Long): FileIndexEntity {
        val name = path.substringAfterLast('/')
        return FileIndexEntity(
            path = path, name = name, parent = path.substringBeforeLast('/'),
            size = size, modified = modified,
            category = if (isDir) FileCategory.FOLDER.name else FileCategory.of(name).name,
            isDirectory = isDir, volume = volume, seen = stamp,
        )
    }

    private suspend fun flush(batch: ArrayList<FileIndexEntity>) {
        if (batch.isEmpty()) return
        dao.insertAll(batch)
        _status.value = _status.value.copy(indexed = _status.value.indexed + batch.size)
        batch.clear()
    }

    /** Keeps the index in sync after in-app operations without waiting for the next full pass. */
    suspend fun onDeleted(path: FsPath) { if (path.isLocal) dao.deleteTree(path.path) }

    suspend fun onCreated(path: FsPath) = withContext(Dispatchers.IO) {
        if (!path.isLocal) return@withContext
        val f = File(path.path)
        if (!f.exists()) return@withContext
        val vol = volumeIdFor(path.path)
        val stamp = System.currentTimeMillis()
        dao.insertAll(listOf(row(f.absolutePath, if (f.isDirectory) 0 else f.length(), f.lastModified(), f.isDirectory, vol, stamp)))
        if (f.isDirectory) walk(f, vol, stamp)
    }

    companion object { const val RECYCLE_DIR = ".XanderRecycle" }
}
