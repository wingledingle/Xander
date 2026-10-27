package app.quinten.data

import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Backup of everything the reader has added to the library: reading positions, progress,
 * favourites, finished books, collections, bookmarks, highlights with notes and per-book settings.
 * Books are matched by content fingerprint, not by path, so a backup restores on another phone.
 * Entries for books not found yet are kept and applied when a later scan finds them.
 */
object ReadingDataBackup {
    @Serializable
    data class Mark(val position: String, val progress: Float, val snippet: String, val label: String? = null, val createdAt: Long)

    @Serializable
    data class Highlight(
        val range: String, val progress: Float, val text: String, val color: Int, val note: String? = null,
        val createdAt: Long, val updatedAt: Long,
    )

    @Serializable
    data class Book(
        val fingerprint: String,
        val title: String,
        val fileName: String,
        val progress: Float = 0f,
        val position: String? = null,
        val lastOpenedAt: Long = 0,
        val finishedAt: Long = 0,
        val favorite: Boolean = false,
        val hidden: Boolean = false,
        val collections: List<String> = emptyList(),
        val bookmarks: List<Mark> = emptyList(),
        val highlights: List<Highlight> = emptyList(),
        val prefs: String? = null,
    )

    @Serializable
    data class Backup(val version: Int = 1, val exportedAt: Long, val collections: List<String> = emptyList(), val books: List<Book>)

    class Result(val restored: Int, val waiting: Int)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; prettyPrint = true }

    suspend fun export(db: LibraryDatabase): String {
        val books = db.books(); val cols = db.collections(); val ann = db.annotations(); val prefs = db.prefs()
        val collections = cols.observeAll().first()
        val names = collections.associate { it.id to it.name }
        val out = ArrayList<Book>()
        for (b in books.all()) {
            val marks = ann.bookmarks(b.id)
            val hls = ann.highlights(b.id)
            val inCols = cols.observeCollectionsOf(b.id).first().mapNotNull { names[it] }
            val p = prefs.get(b.id)
            // Books never opened and without anything added carry no reading data.
            if (b.position == null && !b.favorite && b.finishedAt == 0L && marks.isEmpty() && hls.isEmpty() && inCols.isEmpty() && p == null && !b.hidden) continue
            out += Book(
                b.fingerprint, b.title, b.fileName, b.progress, b.position, b.lastOpenedAt, b.finishedAt, b.favorite, b.hidden, inCols,
                marks.map { Mark(it.position, it.progress, it.snippet, it.label, it.createdAt) },
                hls.map { Highlight(it.range, it.progress, it.text, it.color, it.note, it.createdAt, it.updatedAt) },
                p,
            )
        }
        return json.encodeToString(Backup.serializer(), Backup(exportedAt = System.currentTimeMillis(), collections = collections.map { it.name }, books = out))
    }

    /** Merges [text] into the library; books not in the library yet are kept in [pendingFile]. */
    suspend fun import(db: LibraryDatabase, text: String, pendingFile: File): Result {
        val backup = json.decodeFromString(Backup.serializer(), text)
        for (name in backup.collections) ensureCollection(db, name)
        var restored = 0
        val waiting = ArrayList<Book>()
        for (b in backup.books) if (apply(db, b)) restored++ else waiting += b
        // Keep earlier waiting entries for books that are still not here.
        val earlier = readPending(pendingFile).filter { e -> waiting.none { it.fingerprint == e.fingerprint } && db.books().byFingerprint(e.fingerprint) == null }
        writePending(pendingFile, earlier + waiting)
        return Result(restored, earlier.size + waiting.size)
    }

    /** Applies waiting entries whose books have appeared (after a scan or when a book is added). */
    suspend fun applyPending(db: LibraryDatabase, pendingFile: File): Int {
        val pending = readPending(pendingFile)
        if (pending.isEmpty()) return 0
        val left = ArrayList<Book>()
        var applied = 0
        for (b in pending) if (apply(db, b)) applied++ else left += b
        if (applied > 0) writePending(pendingFile, left)
        return applied
    }

    private suspend fun ensureCollection(db: LibraryDatabase, name: String): Long {
        val cols = db.collections()
        cols.byName(name)?.let { return it.id }
        return runCatching { cols.insert(CollectionEntity(name = name)) }.getOrNull()?.takeIf { it > 0 } ?: cols.byName(name)?.id ?: -1
    }

    /** Merges one book's data; false when the book is not in the library. */
    private suspend fun apply(db: LibraryDatabase, b: Book): Boolean {
        val books = db.books(); val ann = db.annotations()
        val local = books.byFingerprint(b.fingerprint) ?: return false
        // The more recently read copy decides the reading position.
        if (b.position != null && (local.position == null || b.lastOpenedAt > local.lastOpenedAt)) {
            books.updatePosition(local.id, b.progress, b.position, maxOf(b.lastOpenedAt, local.lastOpenedAt))
        }
        if (b.favorite && !local.favorite) books.setFavorite(local.id, true)
        if (b.finishedAt > 0 && local.finishedAt == 0L) books.setFinished(local.id, b.finishedAt)
        for (name in b.collections) {
            val cid = ensureCollection(db, name)
            if (cid > 0) runCatching { db.collections().addBook(BookCollectionRef(local.id, cid)) }
        }
        val marks = ann.bookmarks(local.id)
        for (m in b.bookmarks) if (marks.none { it.position == m.position }) {
            ann.insertBookmark(BookmarkEntity(bookId = local.id, position = m.position, progress = m.progress, snippet = m.snippet, label = m.label, createdAt = m.createdAt))
        }
        val hls = ann.highlights(local.id)
        for (h in b.highlights) {
            val same = hls.firstOrNull { it.range == h.range }
            if (same == null) {
                ann.insertHighlight(HighlightEntity(bookId = local.id, range = h.range, progress = h.progress, text = h.text, color = h.color, note = h.note, createdAt = h.createdAt, updatedAt = h.updatedAt))
            } else if (h.updatedAt > same.updatedAt) {
                ann.updateHighlight(same.copy(color = h.color, note = h.note, updatedAt = h.updatedAt))
            }
        }
        if (b.prefs != null && db.prefs().get(local.id) == null) db.prefs().put(BookPrefsEntity(local.id, b.prefs))
        return true
    }

    private fun readPending(f: File): List<Book> =
        if (!f.isFile) emptyList() else runCatching { json.decodeFromString(Backup.serializer(), f.readText()).books }.getOrDefault(emptyList())

    private fun writePending(f: File, books: List<Book>) {
        if (books.isEmpty()) { f.delete(); return }
        f.parentFile?.mkdirs()
        f.writeText(json.encodeToString(Backup.serializer(), Backup(exportedAt = System.currentTimeMillis(), books = books)))
    }
}
