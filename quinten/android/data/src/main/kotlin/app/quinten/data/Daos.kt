package app.quinten.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Query("SELECT * FROM books WHERE hidden = 0 ORDER BY lastOpenedAt DESC, title COLLATE NOCASE")
    fun observeAll(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE id = :id")
    fun observe(id: Long): Flow<BookEntity?>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun get(id: Long): BookEntity?

    @Query("SELECT * FROM books WHERE fingerprint = :fp")
    suspend fun byFingerprint(fp: String): BookEntity?

    @Query("SELECT * FROM books WHERE location = :location")
    suspend fun byLocation(location: String): BookEntity?

    @Query("SELECT * FROM books")
    suspend fun all(): List<BookEntity>

    @Query("SELECT * FROM books WHERE metadataLoaded = 0 AND hidden = 0 AND missing = 0")
    suspend fun pendingMetadata(): List<BookEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(book: BookEntity): Long

    @Update
    suspend fun update(book: BookEntity)

    @Query("UPDATE books SET progress = :progress, position = :position, lastOpenedAt = :time WHERE id = :id")
    suspend fun updatePosition(id: Long, progress: Float, position: String, time: Long)

    @Query("UPDATE books SET favorite = :fav WHERE id = :id")
    suspend fun setFavorite(id: Long, fav: Boolean)

    @Query("UPDATE books SET finishedAt = :time WHERE id = :id")
    suspend fun setFinished(id: Long, time: Long)

    @Query("UPDATE books SET hidden = :hidden WHERE id = :id")
    suspend fun setHidden(id: Long, hidden: Boolean)

    @Query("UPDATE books SET missing = :missing WHERE id = :id")
    suspend fun setMissing(id: Long, missing: Boolean)

    // Targeted updates: never write back a whole (possibly stale) row, so progress, favourites
    // and finished flags saved meanwhile by the reader are kept.

    @Query(
        "UPDATE books SET title = :title, authors = :authors, series = :series, seriesIndex = :seriesIndex, " +
            "language = :language, description = :description, coverPath = :coverPath, metadataLoaded = 1, missing = 0 WHERE id = :id",
    )
    suspend fun updateMetadata(
        id: Long, title: String, authors: String, series: String?, seriesIndex: Float?,
        language: String?, description: String?, coverPath: String?,
    )

    @Query("UPDATE books SET metadataLoaded = 1 WHERE id = :id")
    suspend fun setMetadataLoaded(id: Long)

    @Query("UPDATE books SET location = :location, fileName = :fileName, missing = 0 WHERE id = :id")
    suspend fun relocate(id: Long, location: String, fileName: String)

    /** Books whose location starts with [prefix] (exact prefix; no LIKE wildcards). */
    @Query("SELECT * FROM books WHERE substr(location, 1, length(:prefix)) = :prefix")
    suspend fun byLocationPrefix(prefix: String): List<BookEntity>

    @Delete
    suspend fun delete(book: BookEntity)

    @Query(
        "SELECT * FROM books WHERE hidden = 0 AND (title LIKE '%' || :q || '%' OR authors LIKE '%' || :q || '%' " +
            "OR series LIKE '%' || :q || '%' OR fileName LIKE '%' || :q || '%') ORDER BY title COLLATE NOCASE",
    )
    fun search(q: String): Flow<List<BookEntity>>
}

@Dao
interface CollectionDao {
    @Query("SELECT * FROM collections ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<CollectionEntity>>

    @Query("SELECT c.*, (SELECT COUNT(*) FROM book_collections bc JOIN books b ON b.id = bc.bookId WHERE bc.collectionId = c.id AND b.hidden = 0) AS count FROM collections c ORDER BY name COLLATE NOCASE")
    fun observeWithCounts(): Flow<List<CollectionWithCount>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(c: CollectionEntity): Long

    @Query("SELECT * FROM collections WHERE name = :name")
    suspend fun byName(name: String): CollectionEntity?

    @Query("SELECT * FROM collections WHERE name = :name COLLATE NOCASE")
    suspend fun byNameIgnoreCase(name: String): List<CollectionEntity>

    /** Throws SQLiteConstraintException when [name] is already used (unique index). */
    @Query("UPDATE collections SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("DELETE FROM collections WHERE id = :id")
    suspend fun delete(id: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addBook(ref: BookCollectionRef)

    @Query("DELETE FROM book_collections WHERE bookId = :bookId AND collectionId = :collectionId")
    suspend fun removeBook(bookId: Long, collectionId: Long)

    @Query("SELECT b.* FROM books b JOIN book_collections bc ON bc.bookId = b.id WHERE bc.collectionId = :id AND b.hidden = 0 ORDER BY b.title COLLATE NOCASE")
    fun observeBooks(id: Long): Flow<List<BookEntity>>

    @Query("SELECT collectionId FROM book_collections WHERE bookId = :bookId")
    fun observeCollectionsOf(bookId: Long): Flow<List<Long>>
}

data class CollectionWithCount(val id: Long, val name: String, val createdAt: Long, val count: Int)

@Dao
interface AnnotationDao {
    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY progress")
    fun observeBookmarks(bookId: Long): Flow<List<BookmarkEntity>>

    @Insert
    suspend fun insertBookmark(b: BookmarkEntity): Long

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun deleteBookmark(id: Long)

    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY progress")
    suspend fun bookmarks(bookId: Long): List<BookmarkEntity>

    /** Books that have at least one highlight, note or bookmark. */
    @Query("SELECT bookId FROM highlights UNION SELECT bookId FROM bookmarks")
    suspend fun annotatedBookIds(): List<Long>

    @Query("SELECT * FROM highlights WHERE bookId = :bookId ORDER BY progress")
    fun observeHighlights(bookId: Long): Flow<List<HighlightEntity>>

    @Query("SELECT * FROM highlights WHERE bookId = :bookId ORDER BY progress")
    suspend fun highlights(bookId: Long): List<HighlightEntity>

    @Insert
    suspend fun insertHighlight(h: HighlightEntity): Long

    @Update
    suspend fun updateHighlight(h: HighlightEntity)

    @Query("DELETE FROM highlights WHERE id = :id")
    suspend fun deleteHighlight(id: Long)

    /** All notes across the library, newest first (for the Notes screen). */
    @Query("SELECT h.*, b.title AS bookTitle FROM highlights h JOIN books b ON b.id = h.bookId ORDER BY h.updatedAt DESC")
    fun observeAllHighlights(): Flow<List<HighlightWithBook>>
}

data class HighlightWithBook(
    val id: Long, val bookId: Long, val range: String, val progress: Float, val text: String, val color: Int,
    val note: String?, val createdAt: Long, val updatedAt: Long, val bookTitle: String,
)

@Dao
interface HistoryDao {
    @Insert
    suspend fun insert(h: HistoryEntity): Long

    @Query("UPDATE history SET closedAt = :closedAt, endProgress = :endProgress WHERE id = :id")
    suspend fun close(id: Long, closedAt: Long, endProgress: Float)

    @Query("SELECT h.id, h.bookId, h.openedAt, h.closedAt, h.startProgress, h.endProgress, b.title AS bookTitle FROM history h JOIN books b ON b.id = h.bookId ORDER BY h.openedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 500): Flow<List<HistoryWithBook>>

    @Query("SELECT COALESCE(SUM(closedAt - openedAt), 0) FROM history WHERE bookId = :bookId")
    fun observeReadingTime(bookId: Long): Flow<Long>

    @Query("DELETE FROM history")
    suspend fun clear()
}

data class HistoryWithBook(val id: Long, val bookId: Long, val openedAt: Long, val closedAt: Long, val startProgress: Float, val endProgress: Float, val bookTitle: String)

@Dao
interface PrefsDao {
    @Query("SELECT json FROM book_prefs WHERE bookId = :bookId")
    suspend fun get(bookId: Long): String?

    @Upsert
    suspend fun put(p: BookPrefsEntity)

    @Query("SELECT * FROM scan_folders ORDER BY addedAt")
    fun observeFolders(): Flow<List<ScanFolderEntity>>

    @Query("SELECT * FROM scan_folders")
    suspend fun folders(): List<ScanFolderEntity>

    @Upsert
    suspend fun addFolder(f: ScanFolderEntity)

    @Query("DELETE FROM scan_folders WHERE location = :location")
    suspend fun removeFolder(location: String)

    @Query("SELECT * FROM scanned_dirs")
    suspend fun scannedDirs(): List<ScannedDirEntity>

    @Query("DELETE FROM scanned_dirs WHERE path = :root OR path LIKE :root || '/%'")
    suspend fun clearScannedDirs(root: String)

    @Upsert
    suspend fun putScannedDirs(dirs: List<ScannedDirEntity>)

    @Query("DELETE FROM scanned_dirs")
    suspend fun forgetScannedDirs()

    @Query("SELECT * FROM scanned_files")
    suspend fun rejectedFiles(): List<ScannedFileEntity>

    @Upsert
    suspend fun rejectFile(f: ScannedFileEntity)

    @Query("DELETE FROM scanned_files")
    suspend fun forgetRejectedFiles()
}

@Database(
    entities = [
        BookEntity::class, CollectionEntity::class, BookCollectionRef::class, BookmarkEntity::class,
        HighlightEntity::class, HistoryEntity::class, BookPrefsEntity::class, ScanFolderEntity::class,
        ScannedDirEntity::class, ScannedFileEntity::class,
    ],
    version = 2,
    exportSchema = true,
    // 2: remembered folders and rejected files for quick rescans (new tables only).
    autoMigrations = [androidx.room.AutoMigration(from = 1, to = 2)],
)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun books(): BookDao
    abstract fun collections(): CollectionDao
    abstract fun annotations(): AnnotationDao
    abstract fun history(): HistoryDao
    abstract fun prefs(): PrefsDao

    companion object {
        fun create(context: Context, inMemory: Boolean = false): LibraryDatabase =
            (if (inMemory) Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
            else Room.databaseBuilder(context, LibraryDatabase::class.java, "library.db")).build()
    }
}
