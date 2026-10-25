package com.xander.files.data.bookmarks

import com.xander.files.data.db.BookmarkDao
import com.xander.files.data.db.BookmarkEntity
import com.xander.files.data.db.HistoryDao
import com.xander.files.data.db.RecentDao
import com.xander.files.data.db.RecentFileEntity
import com.xander.files.domain.fs.FileEntry
import com.xander.files.domain.fs.FsPath
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BookmarkRepository @Inject constructor(
    private val dao: BookmarkDao,
    private val recents: RecentDao,
    private val history: HistoryDao,
) {
    val bookmarks: Flow<List<BookmarkEntity>> = dao.observeAll()

    fun isBookmarked(path: FsPath): Flow<Boolean> = dao.observeIsBookmarked(path.toString()).map { it > 0 }

    suspend fun add(path: FsPath, title: String, isFolder: Boolean) {
        dao.insert(BookmarkEntity(path = path.toString(), title = title.ifEmpty { path.path }, isFolder = isFolder, position = dao.maxPosition() + 1))
    }

    suspend fun toggle(entry: FileEntry) {
        val key = entry.path.toString()
        if (dao.all().any { it.path == key }) dao.deleteByPath(key) else add(entry.path, entry.name, entry.isDirectory)
    }

    suspend fun remove(path: String) = dao.deleteByPath(path)
    suspend fun rename(id: Long, title: String) = dao.rename(id, title)

    /** Persists a new order after drag-to-reorder. */
    suspend fun reorder(ordered: List<BookmarkEntity>) = dao.upsertAll(ordered.mapIndexed { i, b -> b.copy(position = i) })

    fun recentFiles(sinceDays: Int): Flow<List<RecentFileEntity>> =
        recents.observeSince(System.currentTimeMillis() - sinceDays * 86_400_000L)

    suspend fun recordOpened(e: FileEntry) =
        recents.upsert(RecentFileEntity(e.path.toString(), e.name, e.mimeType, e.size, System.currentTimeMillis()))

    suspend fun clearRecents() = recents.clear()
    val historyFlow = history.observe()
    suspend fun clearHistory() = history.clear()
}
