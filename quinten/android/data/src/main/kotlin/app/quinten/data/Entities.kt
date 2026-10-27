package app.quinten.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A book in the library. [location] is an absolute file path or a `content://` URI.
 * [fingerprint] identifies the file content (size + sampled hash) so moved or renamed files keep
 * their reading position, bookmarks and notes.
 */
@Entity(tableName = "books", indices = [Index(value = ["fingerprint"], unique = true), Index("location")])
data class BookEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fingerprint: String,
    val location: String,
    val fileName: String,
    val format: String,
    val sizeBytes: Long,
    val title: String,
    val authors: String = "",
    val series: String? = null,
    val seriesIndex: Float? = null,
    val language: String? = null,
    val description: String? = null,
    val coverPath: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
    val lastOpenedAt: Long = 0,
    val finishedAt: Long = 0,
    /** Reading progress 0..1. */
    val progress: Float = 0f,
    /** Encoded [app.quinten.model.Locator]. */
    val position: String? = null,
    val favorite: Boolean = false,
    /** File no longer found at [location]; kept so notes are not lost. */
    val missing: Boolean = false,
    /** Removed from the library by the user (file itself is never deleted unless asked). */
    val hidden: Boolean = false,
    val metadataLoaded: Boolean = false,
) {
    val authorList: List<String> get() = authors.split('\n').filter { it.isNotBlank() }
    val isReading: Boolean get() = progress > 0f && finishedAt == 0L
}

@Entity(tableName = "collections", indices = [Index(value = ["name"], unique = true)])
data class CollectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "book_collections",
    primaryKeys = ["bookId", "collectionId"],
    indices = [Index("collectionId")],
    foreignKeys = [
        ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = CollectionEntity::class, parentColumns = ["id"], childColumns = ["collectionId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class BookCollectionRef(val bookId: Long, val collectionId: Long)

@Entity(
    tableName = "bookmarks",
    indices = [Index("bookId")],
    foreignKeys = [ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)],
)
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val position: String,
    val progress: Float,
    val snippet: String,
    val label: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

/** A highlight; when [note] is non-null it is also a note. */
@Entity(
    tableName = "highlights",
    indices = [Index("bookId")],
    foreignKeys = [ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)],
)
data class HighlightEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    /** Encoded [app.quinten.model.LocatorRange]. */
    val range: String,
    val progress: Float,
    val text: String,
    val color: Int,
    val note: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

/** One reading session. */
@Entity(
    tableName = "history",
    indices = [Index("bookId"), Index("openedAt")],
    foreignKeys = [ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)],
)
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val openedAt: Long,
    val closedAt: Long = openedAt,
    val startProgress: Float,
    val endProgress: Float = startProgress,
)

/** Per-book reader state that is not part of the global settings (JSON, see [BookPrefs]). */
@Entity(
    tableName = "book_prefs",
    foreignKeys = [ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)],
)
data class BookPrefsEntity(@PrimaryKey val bookId: Long, val json: String)

/** A folder watched for books: file-system path or SAF tree URI. */
@Entity(tableName = "scan_folders")
data class ScanFolderEntity(@PrimaryKey val location: String, val addedAt: Long = System.currentTimeMillis())

/**
 * A folder the scanner has already listed: its last-modified time, subfolders and book files. While the time
 * is unchanged (no file added, removed or renamed in it), a rescan reuses this instead of listing the folder.
 */
@Entity(tableName = "scanned_dirs")
data class ScannedDirEntity(@PrimaryKey val path: String, val modified: Long, val subdirs: String, val books: String) {
    fun subdirList() = subdirs.split('\n').filter { it.isNotEmpty() }
    fun bookList() = books.split('\n').filter { it.isNotEmpty() }
}

/** A file the scanner already examined and found not to be a readable book; skipped while size and date are unchanged. */
@Entity(tableName = "scanned_files")
data class ScannedFileEntity(@PrimaryKey val location: String, val size: Long, val modified: Long)
