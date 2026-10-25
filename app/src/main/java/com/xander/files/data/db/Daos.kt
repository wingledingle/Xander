package com.xander.files.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SafRootDao {
    @Query("SELECT * FROM saf_roots ORDER BY addedAt") fun observeAll(): Flow<List<SafRootEntity>>
    @Query("SELECT * FROM saf_roots") suspend fun all(): List<SafRootEntity>
    @Query("SELECT * FROM saf_roots WHERE id = :id") suspend fun get(id: String): SafRootEntity?
    @Upsert suspend fun upsert(e: SafRootEntity)
    @Query("DELETE FROM saf_roots WHERE id = :id") suspend fun delete(id: String)
}

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks ORDER BY position, id") fun observeAll(): Flow<List<BookmarkEntity>>
    @Query("SELECT * FROM bookmarks ORDER BY position, id") suspend fun all(): List<BookmarkEntity>
    @Query("SELECT COUNT(*) FROM bookmarks WHERE path = :path") fun observeIsBookmarked(path: String): Flow<Int>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(e: BookmarkEntity): Long
    @Upsert suspend fun upsertAll(list: List<BookmarkEntity>)
    @Query("DELETE FROM bookmarks WHERE path = :path") suspend fun deleteByPath(path: String)
    @Query("UPDATE bookmarks SET title = :title WHERE id = :id") suspend fun rename(id: Long, title: String)
    @Query("SELECT COALESCE(MAX(position), 0) FROM bookmarks") suspend fun maxPosition(): Int
}

@Dao
interface RecentDao {
    @Query("SELECT * FROM recent_files WHERE openedAt >= :since ORDER BY openedAt DESC LIMIT :limit")
    fun observeSince(since: Long, limit: Int = 200): Flow<List<RecentFileEntity>>
    @Upsert suspend fun upsert(e: RecentFileEntity)
    @Query("DELETE FROM recent_files WHERE path = :path") suspend fun delete(path: String)
    @Query("DELETE FROM recent_files") suspend fun clear()
}

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history ORDER BY visitedAt DESC LIMIT 300") fun observe(): Flow<List<HistoryEntity>>
    @Upsert suspend fun upsert(e: HistoryEntity)
    @Query("DELETE FROM history") suspend fun clear()
    @Query("DELETE FROM history WHERE path = :path") suspend fun delete(path: String)
}

@Dao
interface RecycleDao {
    @Query("SELECT * FROM recycle_bin ORDER BY deletedAt DESC") fun observeAll(): Flow<List<RecycleEntity>>
    @Query("SELECT * FROM recycle_bin ORDER BY deletedAt DESC") suspend fun all(): List<RecycleEntity>
    @Query("SELECT * FROM recycle_bin WHERE deletedAt < :before") suspend fun olderThan(before: Long): List<RecycleEntity>
    @Query("SELECT * FROM recycle_bin WHERE id = :id") suspend fun get(id: String): RecycleEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(e: RecycleEntity)
    @Query("DELETE FROM recycle_bin WHERE id = :id") suspend fun delete(id: String)
    @Query("SELECT COUNT(*) FROM recycle_bin") fun observeCount(): Flow<Int>
    @Query("SELECT COALESCE(SUM(size),0) FROM recycle_bin") fun observeSize(): Flow<Long>
}

@Dao
interface ClipboardDao {
    @Query("SELECT * FROM clipboard ORDER BY createdAt DESC") fun observeAll(): Flow<List<ClipboardEntity>>
    @Query("SELECT * FROM clipboard WHERE id = :id") suspend fun get(id: Long): ClipboardEntity?
    @Insert suspend fun insert(e: ClipboardEntity): Long
    @Query("DELETE FROM clipboard WHERE id = :id") suspend fun delete(id: Long)
    @Query("DELETE FROM clipboard") suspend fun clear()
}

@Dao
interface ConnectionDao {
    @Query("SELECT * FROM connections ORDER BY name COLLATE NOCASE") fun observeAll(): Flow<List<ConnectionEntity>>
    @Query("SELECT * FROM connections WHERE id = :id") suspend fun get(id: String): ConnectionEntity?
    @Upsert suspend fun upsert(e: ConnectionEntity)
    @Query("DELETE FROM connections WHERE id = :id") suspend fun delete(id: String)
}

@Dao
interface CloudAccountDao {
    @Query("SELECT * FROM cloud_accounts ORDER BY service, displayName") fun observeAll(): Flow<List<CloudAccountEntity>>
    @Query("SELECT * FROM cloud_accounts WHERE id = :id") suspend fun get(id: String): CloudAccountEntity?
    @Upsert suspend fun upsert(e: CloudAccountEntity)
    @Query("UPDATE cloud_accounts SET secret = :secret WHERE id = :id") suspend fun updateSecret(id: String, secret: String)
    @Query("DELETE FROM cloud_accounts WHERE id = :id") suspend fun delete(id: String)
}

@Dao
interface TransferDao {
    @Query("SELECT * FROM transfers ORDER BY createdAt DESC LIMIT 200") fun observeAll(): Flow<List<TransferEntity>>
    @Query("SELECT * FROM transfers WHERE state IN ('QUEUED','RUNNING') ORDER BY createdAt LIMIT 1") suspend fun nextRunnable(): TransferEntity?
    @Query("SELECT * FROM transfers WHERE id = :id") suspend fun get(id: String): TransferEntity?
    @Query("SELECT * FROM transfers WHERE id = :id") fun observe(id: String): Flow<TransferEntity?>
    @Upsert suspend fun upsert(e: TransferEntity)
    @Query("UPDATE transfers SET state = :state, error = :error, updatedAt = :now WHERE id = :id")
    suspend fun setState(id: String, state: String, error: String? = null, now: Long = System.currentTimeMillis())
    @Query("UPDATE transfers SET state = 'QUEUED' WHERE state = 'RUNNING'") suspend fun requeueRunning()
    @Query("DELETE FROM transfers WHERE state IN ('DONE','FAILED','CANCELLED')") suspend fun clearFinished()
    @Query("DELETE FROM transfers WHERE id = :id") suspend fun delete(id: String)
    @Query("SELECT COUNT(*) FROM transfers WHERE state IN ('QUEUED','RUNNING','PAUSED','WAITING')") fun observeActiveCount(): Flow<Int>
}

data class CategoryStat(val category: String, val count: Int, val size: Long)
data class CountSize(val count: Int, val size: Long)

@Dao
interface FileIndexDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertAll(list: List<FileIndexEntity>)
    @Query("DELETE FROM file_index WHERE volume = :volume") suspend fun clearVolume(volume: String)
    @Query("DELETE FROM file_index WHERE volume = :volume AND seen < :stamp") suspend fun pruneStale(volume: String, stamp: Long)
    @Query("SELECT COALESCE(SUM(size),0) FROM file_index WHERE isDirectory = 0 AND size >= :minSize") fun observeLargeSize(minSize: Long): Flow<Long>
    @Query("SELECT COUNT(*) FROM file_index WHERE isDirectory = 0 AND size >= :minSize") fun observeLargeCount(minSize: Long): Flow<Int>
    @Query("SELECT COUNT(*) FROM file_index WHERE isDirectory = 0 AND parent LIKE :downloadDir || '%'") fun observeDownloadCount(downloadDir: String): Flow<Int>
    @Query("SELECT COALESCE(SUM(size),0) FROM file_index WHERE isDirectory = 0 AND parent LIKE :downloadDir || '%'") fun observeDownloadSize(downloadDir: String): Flow<Long>
    @Query("SELECT * FROM file_index WHERE isDirectory = 0 AND parent LIKE :dir || '%' ORDER BY modified DESC") suspend fun under(dir: String): List<FileIndexEntity>
    @Query("DELETE FROM file_index WHERE path = :path OR path LIKE :path || '/%'") suspend fun deleteTree(path: String)
    @Query("SELECT category, COUNT(*) AS count, COALESCE(SUM(size),0) AS size FROM file_index WHERE isDirectory = 0 GROUP BY category")
    fun observeCategoryStats(): Flow<List<CategoryStat>>
    @Query("SELECT COUNT(*) FROM file_index WHERE isDirectory = 0") fun observeFileCount(): Flow<Int>
    @Query("SELECT COUNT(*) AS count, COALESCE(SUM(size),0) AS size FROM file_index WHERE isDirectory = 0 AND name LIKE '%.obb'") fun observeObbStats(): Flow<CountSize>
    @Query("SELECT * FROM file_index WHERE isDirectory = 0 AND name LIKE '%.obb'") suspend fun obbFiles(): List<FileIndexEntity>
    @Query("SELECT * FROM file_index WHERE path = :path") suspend fun get(path: String): FileIndexEntity?
    @Query("SELECT * FROM file_index WHERE isDirectory = 0 AND (category = 'IMAGE' OR category = 'VIDEO')") suspend fun media(): List<FileIndexEntity>
    @Query("SELECT * FROM file_index WHERE category = :category AND isDirectory = 0 ORDER BY modified DESC")
    suspend fun byCategory(category: String): List<FileIndexEntity>
    @Query("SELECT * FROM file_index WHERE isDirectory = 0 AND size >= :minSize ORDER BY size DESC LIMIT :limit")
    suspend fun large(minSize: Long, limit: Int = 500): List<FileIndexEntity>
    @Query("SELECT * FROM file_index WHERE isDirectory = 0 AND modified >= :since ORDER BY modified DESC LIMIT :limit")
    suspend fun recent(since: Long, limit: Int = 300): List<FileIndexEntity>
    @Query("SELECT * FROM file_index WHERE parent = :parent") suspend fun children(parent: String): List<FileIndexEntity>
    @Query("""SELECT * FROM file_index WHERE name LIKE '%' || :q || '%' ESCAPE '\'
        AND (:category IS NULL OR category = :category)
        AND size >= :minSize AND size <= :maxSize AND modified >= :after AND modified <= :before
        AND path LIKE :root || '%'
        ORDER BY isDirectory DESC, name COLLATE NOCASE LIMIT :limit""")
    suspend fun search(q: String, category: String?, minSize: Long, maxSize: Long, after: Long, before: Long, root: String, limit: Int = 2000): List<FileIndexEntity>
    @Query("SELECT COUNT(*) FROM file_index") suspend fun count(): Int
}

@Dao
interface MediaPositionDao {
    @Query("SELECT * FROM media_positions WHERE `key` = :key") suspend fun get(key: String): MediaPositionEntity?
    @Upsert suspend fun upsert(e: MediaPositionEntity)
}

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY name COLLATE NOCASE") fun observePlaylists(): Flow<List<PlaylistEntity>>
    @Insert suspend fun insert(p: PlaylistEntity): Long
    @Query("UPDATE playlists SET name = :name WHERE id = :id") suspend fun rename(id: Long, name: String)
    @Query("DELETE FROM playlists WHERE id = :id") suspend fun deletePlaylist(id: Long)
    @Query("SELECT * FROM playlist_items WHERE playlistId = :id ORDER BY position") fun observeItems(id: Long): Flow<List<PlaylistItemEntity>>
    @Query("SELECT * FROM playlist_items WHERE playlistId = :id ORDER BY position") suspend fun items(id: Long): List<PlaylistItemEntity>
    @Query("DELETE FROM playlist_items WHERE playlistId = :id") suspend fun clearItems(id: Long)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertItems(items: List<PlaylistItemEntity>)
    @Transaction
    suspend fun replaceItems(id: Long, items: List<PlaylistItemEntity>) {
        clearItems(id); insertItems(items.mapIndexed { i, it -> it.copy(playlistId = id, position = i) })
    }
}

@Dao
interface FolderPrefDao {
    @Query("SELECT * FROM folder_prefs WHERE path = :path") suspend fun get(path: String): FolderPrefEntity?
    @Upsert suspend fun upsert(e: FolderPrefEntity)
    @Query("DELETE FROM folder_prefs") suspend fun clear()
}

@Dao
interface DefaultAppDao {
    @Query("SELECT * FROM default_apps WHERE extension = :ext") suspend fun get(ext: String): DefaultAppEntity?
    @Upsert suspend fun upsert(e: DefaultAppEntity)
    @Query("DELETE FROM default_apps WHERE extension = :ext") suspend fun delete(ext: String)
    @Query("DELETE FROM default_apps") suspend fun clear()
}

@Dao
interface TvDeviceDao {
    @Query("SELECT * FROM tv_devices ORDER BY name") fun observeAll(): Flow<List<TvDeviceEntity>>
    @Query("SELECT * FROM tv_devices WHERE id = :id") suspend fun get(id: String): TvDeviceEntity?
    @Query("SELECT * FROM tv_devices") suspend fun observeAllOnce(): List<TvDeviceEntity>
    @Upsert suspend fun upsert(e: TvDeviceEntity)
    @Query("DELETE FROM tv_devices WHERE id = :id") suspend fun delete(id: String)
}

@Dao
interface NsfwDao {
    @Query("SELECT * FROM nsfw_scores WHERE path = :path") suspend fun get(path: String): NsfwScoreEntity?
    @Upsert suspend fun upsert(e: NsfwScoreEntity)
    @Query("SELECT path FROM nsfw_scores WHERE score >= :threshold ORDER BY score DESC") suspend fun flagged(threshold: Float): List<String>
    @Query("SELECT COUNT(*) AS count, COALESCE(SUM(size),0) AS size FROM nsfw_scores WHERE score >= :threshold") fun observeFlagged(threshold: Float): Flow<CountSize>
    @Query("SELECT COUNT(*) FROM nsfw_scores") fun observeScanned(): Flow<Int>
    @Query("DELETE FROM nsfw_scores WHERE path = :path") suspend fun delete(path: String)
    @Query("DELETE FROM nsfw_scores") suspend fun clear()
}
