package app.quinten.data

import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteConstraintException
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.room.withTransaction
import app.quinten.model.BookDocument
import app.quinten.model.BookFormat
import app.quinten.model.ImageData
import app.quinten.model.Locator
import app.quinten.model.LocatorRange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** Opens a book file as a document (the app wires in PDF/DjVu/OCR support). */
fun interface DocumentFactory {
    fun open(file: File, format: BookFormat, bookId: Long): BookDocument
}

/** Turns platform or raster images into bitmaps. */
object Images {
    fun toBitmap(img: ImageData?, maxWidth: Int = 4096): Bitmap? = when (img) {
        null -> null
        is ImageData.Platform -> img.image as? Bitmap
        is ImageData.Raster -> Bitmap.createBitmap(img.argb, img.width, img.height, Bitmap.Config.ARGB_8888)
        is ImageData.Encoded -> {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(img.bytes, 0, img.bytes.size, o)
            var sample = 1
            while (o.outWidth / (sample * 2) >= maxWidth) sample *= 2
            BitmapFactory.decodeByteArray(img.bytes, 0, img.bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }
}

private const val METADATA_PARALLELISM = 3
private const val METADATA_BATCH = 25

/**
 * The parser's title, unless it is useless: blank, only digits (e.g. "57" from a /proc/self/fd/57
 * file name) or the name of the opened file itself.
 */
internal fun acceptableTitle(title: String?, opened: File): String? {
    val t = title?.trim() ?: return null
    if (t.isEmpty() || t.all { it.isDigit() }) return null
    if (t == opened.name || t == opened.name.substringBeforeLast('.')) return null
    return t
}

data class ScanState(
    val running: Boolean = false,
    val found: Int = 0,
    val added: Int = 0,
    val current: String? = null,
    val failed: Int = 0,
    val folders: Int = 0,
    val finishedAt: Long = 0,
    /** Folders that had not changed since the last scan and were not listed again. */
    val unchanged: Int = 0,
    /** True when everything was re-examined (Full rescan, or the weekly safety pass). */
    val full: Boolean = false,
)

/** Outcome of naming a collection. */
enum class CollectionNameResult { OK, BLANK, DUPLICATE }

/** Title, authors and cover read from a book, or why they could not be read. */
internal sealed interface MetaResult {
    val id: Long

    data class Loaded(
        override val id: Long, val title: String, val authors: String, val series: String?, val seriesIndex: Float?,
        val language: String?, val description: String?, val coverPath: String?,
    ) : MetaResult

    /** Parsed but unreadable (DRM, corrupt): keep the file-name title and do not retry forever. */
    data class Failed(override val id: Long) : MetaResult

    /** The file (or the permission to it) is gone. */
    data class Missing(override val id: Long) : MetaResult
}

class LibraryRepository(
    private val context: Context,
    val db: LibraryDatabase,
    private val factory: DocumentFactory,
) {
    private val books = db.books()
    private val scanMutex = Mutex()
    private val rescanRequested = AtomicBoolean(false)
    private val requestedRoots = LinkedHashSet<String>()
    private val _scan = MutableStateFlow(ScanState())
    val scanState: StateFlow<ScanState> = _scan

    /** Books whose file was not found while reading details in the current scan: not retried in it. */
    private val missingThisScan = HashSet<Long>()

    val coverDir = File(context.filesDir, "covers").apply { mkdirs() }

    /** Copies of books opened with a temporary permission only ("Open with" from apps that grant no lasting access). */
    val importDir = File(context.filesDir, "imported")

    /** Page-text/OCR cache of a book (written by the app's document layer). */
    fun ocrCacheDir(id: Long) = File(context.filesDir, "ocr-cache/$id")

    fun observeBooks(): Flow<List<BookEntity>> = books.observeAll()
    fun observeBook(id: Long) = books.observe(id)
    fun search(q: String) = books.search(q)
    suspend fun book(id: Long) = books.get(id)

    // ---------------- adding books ----------------

    /**
     * Adds (or finds) a single book given a path or content URI, as chosen explicitly by the user
     * ("Open with", "Open a single file"). Returns its id. A book removed from the library earlier
     * is shown again.
     */
    suspend fun addBook(location: String): Long? = withContext(Dispatchers.IO) {
        val (stable, name) = if (BookFiles.isContentUri(location)) {
            runCatching { stableLocation(location) }.getOrNull() ?: return@withContext null
        } else location to null
        val id = runCatching { register(stable, name, emptySet())?.id }.getOrNull() ?: return@withContext null
        books.get(id)?.let { b ->
            if (b.hidden) books.setHidden(id, false)
            if (!b.metadataLoaded || b.missing) loadMetadata(id)
        }
        runCatching { ReadingDataBackup.applyPending(db, pendingRestore) }
        id
    }

    // ---------------- backup of reading data ----------------

    /** Restore entries waiting for their books to be found on this device. */
    private val pendingRestore get() = File(context.filesDir, "pending_restore.json")

    /** Positions, bookmarks, highlights, notes, collections and per-book settings as JSON. */
    suspend fun exportReadingData(): String = withContext(Dispatchers.IO) { ReadingDataBackup.export(db) }

    suspend fun importReadingData(text: String): ReadingDataBackup.Result = withContext(Dispatchers.IO) {
        ReadingDataBackup.import(db, text, pendingRestore)
    }

    /**
     * A location for a content URI that still works after a restart. "Open with" usually grants
     * only temporary access; when no persistable permission can be taken, the book keeps an existing
     * readable location, or else its real file path, or else a private copy in [importDir].
     * Returns the location and a display name for it (null: read it from the location).
     */
    private suspend fun stableLocation(location: String): Pair<String, String?> {
        val uri = Uri.parse(location)
        if (persistPermission(uri)) return location to null
        BookFiles.open(context, location).use { of ->
            val name = of.displayName
            val fp = BookFiles.fingerprint(of.file)
            books.byFingerprint(fp)?.let { existing ->
                if (existing.location != location && BookFiles.isReadable(context, existing.location)) return existing.location to existing.fileName
            }
            BookFiles.resolveFilePath(context, uri)?.let { path ->
                if (runCatching { BookFiles.fingerprint(File(path)) }.getOrNull() == fp) return path to File(path).name
            }
            val format = BookFormat.fromFileName(name) ?: app.quinten.formats.BookOpener.detect(of.file, name)
                ?: throw IOException("Not a book: $name")
            val ext = format.extensions.sortedByDescending { it.length }.firstOrNull { name.lowercase().endsWith(".$it") } ?: format.extensions.first()
            val target = File(importDir, "$fp.$ext")
            if (!target.exists() || target.length() != of.file.length()) {
                of.file.inputStream().use { BookFiles.copyToFingerprinted(it, importDir, name) { _ -> target.name } }
            }
            return target.absolutePath to name
        }
    }

    /**
     * Tries to keep read access to [uri] across restarts; true when a persisted permission is held.
     * (Replaceable in tests: Robolectric grants every persistable permission.)
     */
    internal var persistPermission: (Uri) -> Boolean = { uri ->
        try {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {}
        BookFiles.hasPersistedPermission(context, uri)
    }

    private fun isImportedCopy(location: String) = location.startsWith(importDir.absolutePath + "/")

    private data class Registered(val id: Long, val inserted: Boolean)

    /**
     * Registers a file without parsing it. Handles moved/renamed files via the fingerprint.
     * When the same content is reachable at two addresses the current one is kept while it is
     * readable (or was seen in this scan, [seen]); a real file path wins over a content URI or a
     * private import copy.
     */
    private suspend fun register(location: String, nameHint: String?, seen: Set<String>): Registered? {
        books.byLocation(location)?.let { existing ->
            if (existing.missing) books.setMissing(existing.id, false)
            return Registered(existing.id, false)
        }
        BookFiles.open(context, location).use { of ->
            val name = nameHint ?: of.displayName
            val format = BookFormat.fromFileName(name) ?: app.quinten.formats.BookOpener.detect(of.file, name) ?: return null
            val fp = BookFiles.fingerprint(of.file)
            books.byFingerprint(fp)?.let { existing -> return Registered(relink(existing, location, name, seen), false) }
            val title = name.substringBeforeLast('.').let { if (it.lowercase().endsWith(".fb2")) it.dropLast(4) else it }.replace('_', ' ')
            return try {
                Registered(
                    books.insert(BookEntity(fingerprint = fp, location = location, fileName = name, format = format.name, sizeBytes = of.file.length(), title = title)),
                    true,
                )
            } catch (e: SQLiteConstraintException) {
                // Registered concurrently (a scan and "Open with" at once): use the other insert.
                val other = books.byFingerprint(fp) ?: throw e
                Registered(relink(other, location, name, seen), false)
            }
        }
    }

    /** Same content found at [location]: keep progress and notes, and decide which address to keep. */
    private suspend fun relink(existing: BookEntity, location: String, name: String, seen: Set<String>): Long {
        val old = existing.location
        if (old == location) {
            if (existing.missing) books.setMissing(existing.id, false)
            return existing.id
        }
        val newIsBetter = (BookFiles.isContentUri(old) || isImportedCopy(old)) && !BookFiles.isContentUri(location) && !isImportedCopy(location)
        val keepOld = !newIsBetter && (old in seen || BookFiles.isReadable(context, old))
        if (keepOld) {
            if (existing.missing) books.setMissing(existing.id, false)
        } else {
            books.relocate(existing.id, location, name)
            // Our own private copy is no longer needed once the real file is known.
            if (isImportedCopy(old) && !isImportedCopy(location)) runCatching { File(old).delete() }
        }
        return existing.id
    }

    /** Reads title/author/cover from the book. */
    suspend fun loadMetadata(id: Long) = withContext(Dispatchers.IO) {
        val b = books.get(id) ?: return@withContext
        applyMetadata(listOf(readMetadata(b)))
    }

    /** Parses [b] without writing to the database (several books may be read in parallel). */
    private fun readMetadata(b: BookEntity): MetaResult {
        val id = b.id
        return try {
            BookFiles.open(context, b.location).use { of ->
                factory.open(of.file, BookFormat.valueOf(b.format), id).use { doc ->
                    val m = doc.metadata
                    val cover = runCatching { Images.toBitmap(doc.cover(), 600) }.getOrNull()
                    var coverPath = b.coverPath?.takeIf { File(it).exists() }
                    if (cover != null) {
                        val scaled = if (cover.width > 360) Bitmap.createScaledBitmap(cover, 360, (cover.height * 360f / cover.width).toInt().coerceAtLeast(1), true) else cover
                        // A new name each time, so a reloaded cover is never served from an image cache.
                        val coverFile = File(coverDir, "${id}_${System.nanoTime()}.jpg")
                        coverFile.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                        coverPath = coverFile.absolutePath
                    }
                    MetaResult.Loaded(
                        id = id,
                        title = acceptableTitle(m.title, of.file) ?: b.title,
                        authors = m.authors.joinToString("\n"),
                        series = m.series, seriesIndex = m.seriesIndex, language = m.language,
                        description = m.description?.take(4000),
                        coverPath = coverPath,
                    )
                }
            }
        } catch (e: java.io.FileNotFoundException) {
            MetaResult.Missing(id)
        } catch (e: OutOfMemoryError) {
            MetaResult.Failed(id)
        } catch (e: Exception) {
            // Unreadable/DRM file: keep it listed with the file name, do not retry forever.
            MetaResult.Failed(id)
        }
    }

    /** Writes metadata results in one transaction (one library refresh instead of one per book). */
    private suspend fun applyMetadata(results: List<MetaResult>) {
        if (results.isEmpty()) return
        db.withTransaction {
            for (r in results) when (r) {
                is MetaResult.Loaded -> books.updateMetadata(r.id, r.title, r.authors, r.series, r.seriesIndex, r.language, r.description, r.coverPath)
                is MetaResult.Failed -> books.setMetadataLoaded(r.id)
                is MetaResult.Missing -> books.setMissing(r.id, true)
            }
        }
        // Older covers of these books are no longer referenced.
        for (r in results) if (r is MetaResult.Loaded) deleteCovers(r.id, except = r.coverPath)
    }

    private fun deleteCovers(id: Long, except: String? = null) {
        coverDir.listFiles()?.forEach { f ->
            val n = f.name
            if ((n == "$id.jpg" || n.startsWith("${id}_")) && f.absolutePath != except) f.delete()
        }
    }

    /** Reads the details of every pending book, a few at a time (PDF/DjVu one at a time, for memory). */
    private suspend fun loadPendingMetadata(skip: Set<Long>, onProgress: (String) -> Unit) {
        val pending = books.pendingMetadata().filter { it.id !in skip }
        if (pending.isEmpty()) return
        val general = Semaphore(METADATA_PARALLELISM)
        val heavy = Semaphore(1)
        val lock = Mutex()
        val buffer = ArrayList<MetaResult>()
        coroutineScope {
            for (b in pending) launch {
                val fmt = runCatching { BookFormat.valueOf(b.format) }.getOrNull()
                val read = { onProgress(b.fileName); readMetadata(b) }
                val r = if (fmt == BookFormat.PDF || fmt == BookFormat.DJVU) heavy.withPermit { general.withPermit { read() } }
                else general.withPermit { read() }
                if (r is MetaResult.Missing) synchronized(missingThisScan) { missingThisScan += r.id }
                val batch = lock.withLock {
                    buffer += r
                    if (buffer.size >= METADATA_BATCH) ArrayList(buffer).also { buffer.clear() } else null
                }
                if (batch != null) applyMetadata(batch)
            }
        }
        applyMetadata(lock.withLock { ArrayList(buffer).also { buffer.clear() } })
    }

    // ---------------- automatic discovery ----------------

    suspend fun addScanFolder(location: String) {
        if (BookFiles.isContentUri(location)) {
            try {
                context.contentResolver.takePersistableUriPermission(Uri.parse(location), Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {}
        }
        db.prefs().addFolder(ScanFolderEntity(location))
    }

    /**
     * Stops watching a folder: releases the folder permission and removes its books from the
     * library (hidden, so their notes and positions come back if they are added again).
     */
    suspend fun removeScanFolder(location: String) = withContext(Dispatchers.IO) {
        db.prefs().removeFolder(location)
        val remaining = db.prefs().folders().map { it.location }
        val content = BookFiles.isContentUri(location)
        if (content) {
            runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(location), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        fun prefixOf(folder: String) = if (BookFiles.isContentUri(folder)) "$folder/document/" else folder.trimEnd('/') + "/"
        val affected = books.byLocationPrefix(prefixOf(location)).filter { b -> remaining.none { b.location.startsWith(prefixOf(it)) } }
        if (affected.isNotEmpty()) db.withTransaction { for (b in affected) books.setHidden(b.id, true) }
    }

    fun observeScanFolders() = db.prefs().observeFolders()

    /**
     * Scans all configured folders (file paths need all-files access; SAF trees work without it),
     * registers new books, re-links moved ones, flags missing ones and loads metadata.
     * A request made while a scan runs is not dropped: one more scan follows the current one.
     */
    suspend fun scan(extraRoots: List<File> = emptyList(), full: Boolean = false) = withContext(Dispatchers.IO) {
        synchronized(requestedRoots) { extraRoots.forEach { requestedRoots += it.absolutePath } }
        if (full) fullRequested.set(true)
        rescanRequested.set(true)
        while (rescanRequested.get() && scanMutex.tryLock()) {
            try {
                synchronized(missingThisScan) { missingThisScan.clear() }
                while (rescanRequested.getAndSet(false)) {
                    val roots = synchronized(requestedRoots) { requestedRoots.map { File(it) }.also { requestedRoots.clear() } }
                    // Quick scans trust remembered folders; once a week everything is re-examined anyway, in case a
                    // file system didn't update a folder's date.
                    val lastFull = scanPrefs.getLong(KEY_LAST_FULL, 0L)
                    val doFull = fullRequested.getAndSet(false) || System.currentTimeMillis() - lastFull > FULL_SCAN_EVERY_MS
                    scanOnce(roots, doFull)
                    if (doFull) scanPrefs.edit().putLong(KEY_LAST_FULL, System.currentTimeMillis()).apply()
                }
            } finally {
                scanMutex.unlock()
            }
        }
    }

    private val fullRequested = java.util.concurrent.atomic.AtomicBoolean(false)
    private val scanPrefs by lazy { context.getSharedPreferences("quinten-scan", Context.MODE_PRIVATE) }

    private suspend fun scanOnce(extraRoots: List<File>, full: Boolean) {
        _scan.value = ScanState(running = true, full = full)
        if (full) { db.prefs().forgetScannedDirs(); db.prefs().forgetRejectedFiles() }
        val rejected = db.prefs().rejectedFiles().associateBy { it.location }
        val dirCache = db.prefs().scannedDirs().associateBy { it.path }
        val listed = ArrayList<ScannedDirEntity>()
        var unchanged = 0
        val seen = HashSet<String>()
        var found = 0
        var added = 0
        var failed = 0
        var folders = 0
        val before = books.all().associateBy { it.location }
        suspend fun consider(location: String, name: String, size: Long, modified: Long) {
            if (BookFormat.fromFileName(name) == null) return
            if (!seen.add(location)) return
            val known = before[location]
            if (known != null) {
                found++
                // Seen again: e.g. a picked-folder book flagged while its folder was unavailable.
                if (known.missing) books.setMissing(known.id, false)
                return
            }
            // Already examined and not a readable book, and unchanged since: don't open it again.
            rejected[location]?.let { if (it.size == size && it.modified == modified) return }
            found++
            _scan.value = ScanState(true, found, added, name, failed, folders, unchanged = unchanged, full = full)
            val r = runCatching { register(location, name, seen) }.getOrNull()
            if (r == null) { failed++; runCatching { db.prefs().rejectFile(ScannedFileEntity(location, size, modified)) } }
            else if (r.inserted) added++
        }
        val roots = db.prefs().folders().map { it.location }
        val fileRoots = (roots.filter { !BookFiles.isContentUri(it) }.map { File(it) } + extraRoots).distinctBy { it.absolutePath }
        val visited = HashSet<String>()
        // 1. Walk the file system (every storage volume when all-files access is granted). Android's
        //    media index is not queried as well: it covers the same roots and only cost time.
        for (root in fileRoots) {
            walkFiles(root, 0, visited, if (full) emptyMap() else dirCache, listed, { reused -> folders++; if (reused) unchanged++ }) { f ->
                consider(f.absolutePath, f.name, runCatching { f.length() }.getOrDefault(0L), runCatching { f.lastModified() }.getOrDefault(0L))
            }
        }
        // Remember what was listed, replacing the old records under each walked root (deleted folders drop out).
        db.withTransaction {
            for (root in fileRoots) db.prefs().clearScannedDirs(root.absolutePath.trimEnd('/'))
            db.prefs().putScannedDirs(listed)
        }
        // 2. Folders granted through the system folder picker.
        for (tree in roots.filter { BookFiles.isContentUri(it) }) {
            val doc = runCatching { DocumentFile.fromTreeUri(context, Uri.parse(tree)) }.getOrNull() ?: continue
            walkTree(doc, 0, { folders++ }, { failed++ }) { d -> consider(d.uri.toString(), d.name ?: "", runCatching { d.length() }.getOrDefault(0L), runCatching { d.lastModified() }.getOrDefault(0L)) }
        }
        // Books under scanned file roots that disappeared are flagged (never deleted). A root that
        // cannot be listed (SD card removed, volume unmounted) says nothing about its books.
        // Use current records: books re-linked during this scan already point at their new place.
        fun prefix(f: File) = f.absolutePath.trimEnd('/') + "/"
        val liveRoots = fileRoots.filter { r -> r.canRead() && runCatching { r.list() }.getOrNull() != null }.map(::prefix)
        val deadRoots = fileRoots.map(::prefix) - liveRoots.toSet()
        val current = books.all()
        db.withTransaction {
            for (b in current) {
                if (BookFiles.isContentUri(b.location)) continue
                val exists = File(b.location).exists()
                if (exists) {
                    if (b.missing) books.setMissing(b.id, false)
                    continue
                }
                if (b.missing) continue
                val underLive = liveRoots.any { b.location.startsWith(it) }
                val underDead = deadRoots.any { b.location.startsWith(it) }
                if (underLive && !underDead) books.setMissing(b.id, true)
            }
        }
        _scan.value = ScanState(true, found, added, null, failed, folders, unchanged = unchanged, full = full)
        val skip = synchronized(missingThisScan) { HashSet(missingThisScan) }
        loadPendingMetadata(skip) { name ->
            _scan.value = ScanState(true, found, added, "Reading details: $name", failed, folders, unchanged = unchanged, full = full)
        }
        // A restored backup may have been waiting for books this scan just found.
        runCatching { ReadingDataBackup.applyPending(db, pendingRestore) }
        _scan.value = ScanState(false, found, added, null, failed, folders, System.currentTimeMillis(), unchanged, full)
    }

    /**
     * Walks [dir] for book files. A folder whose last-modified time matches its record in [cache] was not changed
     * (nothing added, removed or renamed in it), so its remembered books and subfolders are used without listing
     * it. Every folder visited is added to [listed] for the next scan. [onFolder] gets true for a reused folder.
     */
    private suspend fun walkFiles(
        dir: File, depth: Int, visited: MutableSet<String>, cache: Map<String, ScannedDirEntity>, listed: MutableList<ScannedDirEntity>,
        onFolder: (Boolean) -> Unit, onFile: suspend (File) -> Unit,
    ) {
        if (depth > 40) return
        // Guard against symlink loops (e.g. /storage/self/primary -> /storage/emulated/0).
        val canonical = runCatching { dir.canonicalPath }.getOrDefault(dir.absolutePath)
        if (!visited.add(canonical)) return
        val path = dir.absolutePath.trimEnd('/')
        val modified = runCatching { dir.lastModified() }.getOrDefault(0L)
        val known = cache[path]
        if (known != null && modified != 0L && known.modified == modified) {
            onFolder(true)
            listed += known
            for (name in known.bookList()) onFile(File(dir, name))
            for (name in known.subdirList()) walkFiles(File(dir, name), depth + 1, visited, cache, listed, onFolder, onFile)
            return
        }
        val children = runCatching { dir.listFiles() }.getOrNull() ?: return
        onFolder(false)
        val subdirs = ArrayList<String>()
        val bookFiles = ArrayList<String>()
        for (c in children) {
            if (c.name.startsWith(".")) continue
            val isDir = runCatching { c.isDirectory }.getOrDefault(false)
            if (isDir) {
                val p = c.absolutePath
                if (p.endsWith("/Android/data") || p.endsWith("/Android/obb")) continue
                subdirs += c.name
            } else if (BookFormat.fromFileName(c.name) != null && runCatching { c.length() }.getOrDefault(0L) > 0) bookFiles += c.name
        }
        listed += ScannedDirEntity(path, modified, subdirs.joinToString("\n"), bookFiles.joinToString("\n"))
        for (name in bookFiles) onFile(File(dir, name))
        for (name in subdirs) walkFiles(File(dir, name), depth + 1, visited, cache, listed, onFolder, onFile)
    }

    private suspend fun walkTree(dir: DocumentFile, depth: Int, onFolder: () -> Unit, onError: () -> Unit, onFile: suspend (DocumentFile) -> Unit) {
        if (depth > 40) return
        // A single unreadable folder must not stop the whole scan.
        val children = try { dir.listFiles() } catch (_: Exception) { onError(); return }
        onFolder()
        for (c in children) {
            try {
                val name = c.name ?: continue
                if (name.startsWith(".")) continue
                if (c.isDirectory) walkTree(c, depth + 1, onFolder, onError, onFile)
                else if (BookFormat.fromFileName(name) != null) onFile(c)
            } catch (_: Exception) { onError() }
        }
    }

    /** Roots of every mounted storage volume: internal storage and SD cards / USB drives. */
    fun storageRoots(): List<File> {
        val roots = LinkedHashSet<String>()
        roots += android.os.Environment.getExternalStorageDirectory().absolutePath
        context.getExternalFilesDirs(null).filterNotNull().forEach { f ->
            val p = f.absolutePath
            val i = p.indexOf("/Android/data/")
            if (i > 0) roots += p.substring(0, i)
        }
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val sm = context.getSystemService(android.os.storage.StorageManager::class.java)
            sm?.storageVolumes?.forEach { v -> v.directory?.absolutePath?.let { roots += it } }
        }
        return roots.map { File(it) }.filter { it.canRead() }
    }

    // ---------------- per-book state ----------------

    suspend fun savePosition(id: Long, progress: Float, position: String) =
        books.updatePosition(id, progress.coerceIn(0f, 1f), position, System.currentTimeMillis())

    suspend fun markMissing(id: Long) = books.setMissing(id, true)
    suspend fun setFavorite(id: Long, fav: Boolean) = books.setFavorite(id, fav)
    suspend fun setFinished(id: Long, finished: Boolean) = books.setFinished(id, if (finished) System.currentTimeMillis() else 0)
    suspend fun removeFromLibrary(id: Long) = books.setHidden(id, true)
    suspend fun restoreToLibrary(id: Long) = books.setHidden(id, false)

    /**
     * Forgets a book for good: its record, notes, bookmarks, history, cover and OCR cache (and a
     * private import copy). The book file itself is never touched.
     */
    suspend fun forgetBook(id: Long) = withContext(Dispatchers.IO) {
        val b = books.get(id) ?: return@withContext
        books.delete(b)
        deleteCovers(id)
        runCatching { ocrCacheDir(id).deleteRecursively() }
        if (isImportedCopy(b.location)) runCatching { File(b.location).delete() }
    }

    suspend fun prefs(id: Long): BookPrefs = BookPrefs.decode(db.prefs().get(id))
    suspend fun savePrefs(id: Long, p: BookPrefs) = db.prefs().put(BookPrefsEntity(id, p.encode()))

    // ---------------- collections ----------------

    fun observeCollections() = db.collections().observeWithCounts()
    fun observeCollectionBooks(id: Long) = db.collections().observeBooks(id)
    fun observeCollectionsOf(bookId: Long) = db.collections().observeCollectionsOf(bookId)

    /** The collection called [name] (ignoring case and surrounding spaces), if any. */
    suspend fun collectionNamed(name: String): CollectionEntity? = db.collections().byNameIgnoreCase(name.trim()).firstOrNull()

    /** Creates a collection, or returns the existing one with that name. */
    suspend fun createCollection(name: String): Long {
        val clean = name.trim()
        require(clean.isNotEmpty()) { "Collection name is empty" }
        collectionNamed(clean)?.let { return it.id }
        return try {
            db.collections().insert(CollectionEntity(name = clean)).takeIf { it > 0 } ?: db.collections().byName(clean)!!.id
        } catch (e: SQLiteConstraintException) {
            db.collections().byName(clean)?.id ?: throw e
        }
    }

    /** Renames a collection; a blank name or one already used by another collection is refused. */
    suspend fun renameCollection(id: Long, name: String): CollectionNameResult {
        val clean = name.trim()
        if (clean.isEmpty()) return CollectionNameResult.BLANK
        if (db.collections().byNameIgnoreCase(clean).any { it.id != id }) return CollectionNameResult.DUPLICATE
        return try {
            db.collections().rename(id, clean)
            CollectionNameResult.OK
        } catch (_: SQLiteConstraintException) {
            CollectionNameResult.DUPLICATE
        }
    }

    suspend fun deleteCollection(id: Long) = db.collections().delete(id)
    suspend fun addToCollection(bookId: Long, collectionId: Long) = db.collections().addBook(BookCollectionRef(bookId, collectionId))
    suspend fun removeFromCollection(bookId: Long, collectionId: Long) = db.collections().removeBook(bookId, collectionId)

    // ---------------- annotations ----------------

    fun observeBookmarks(bookId: Long) = db.annotations().observeBookmarks(bookId)
    suspend fun addBookmark(b: BookmarkEntity) = db.annotations().insertBookmark(b)
    suspend fun deleteBookmark(id: Long) = db.annotations().deleteBookmark(id)
    fun observeHighlights(bookId: Long) = db.annotations().observeHighlights(bookId)
    fun observeAllHighlights() = db.annotations().observeAllHighlights()
    suspend fun addHighlight(h: HighlightEntity) = db.annotations().insertHighlight(h)
    suspend fun updateHighlight(h: HighlightEntity) = db.annotations().updateHighlight(h.copy(updatedAt = System.currentTimeMillis()))
    suspend fun deleteHighlight(id: Long) = db.annotations().deleteHighlight(id)

    // ---------------- history ----------------

    suspend fun startSession(bookId: Long, progress: Float): Long =
        db.history().insert(HistoryEntity(bookId = bookId, openedAt = System.currentTimeMillis(), startProgress = progress))
    suspend fun endSession(sessionId: Long, progress: Float) = db.history().close(sessionId, System.currentTimeMillis(), progress)
    fun observeHistory() = db.history().observeRecent()
    fun observeReadingTime(bookId: Long) = db.history().observeReadingTime(bookId)
    suspend fun clearHistory() = db.history().clear()

    // ---------------- export ----------------

    /** Exports the bookmarks, highlights and notes of a book as Markdown text (offline sharing). */
    suspend fun exportNotes(bookId: Long): String = buildString { appendBookNotes(bookId, "#") }

    /** Exports the bookmarks, highlights and notes of every book as one Markdown document. */
    suspend fun exportAllNotes(): String {
        val ids = db.annotations().annotatedBookIds().distinct()
        val list = ids.mapNotNull { books.get(it) }.sortedBy { it.title.lowercase() }
        return buildString {
            appendLine("# Notes and highlights")
            appendLine()
            for (b in list) appendBookNotes(b.id, "##")
        }
    }

    private suspend fun StringBuilder.appendBookNotes(bookId: Long, h: String) {
        val b = books.get(bookId) ?: return
        val hs = db.annotations().highlights(bookId).sortedWith(byPosition({ it.range }, { it.progress }))
        val bms = db.annotations().bookmarks(bookId).sortedWith(compareBy<BookmarkEntity, Locator?>(nullsLast()) { Locator.decode(it.position) }.thenBy { it.progress })
        appendLine("$h ${b.title}")
        if (b.authors.isNotBlank()) appendLine(b.authorList.joinToString(", "))
        appendLine()
        if (bms.isNotEmpty()) {
            appendLine("$h# Bookmarks")
            appendLine()
            for (m in bms) {
                val label = m.label?.takeIf { it.isNotBlank() } ?: m.snippet.trim().replace('\n', ' ').take(120)
                appendLine("- $label  (${(m.progress * 100).toInt()}%)")
            }
            appendLine()
        }
        if (hs.isNotEmpty()) {
            appendLine("$h# Highlights and notes")
            appendLine()
            for (x in hs) {
                appendLine("> “${x.text.trim().replace("\n", "\n> ")}”  (${(x.progress * 100).toInt()}%)")
                if (!x.note.isNullOrBlank()) { appendLine(); appendLine("Note: ${x.note.trim()}") }
                appendLine()
            }
        }
    }

    companion object {
        private const val KEY_LAST_FULL = "lastFullScan"
        /** Even quick scans re-examine everything this often. */
        const val FULL_SCAN_EVERY_MS = 7L * 24 * 60 * 60 * 1000
        /** Orders annotations by their place in the book (falls back to the progress fraction). */
        fun <T> byPosition(range: (T) -> String, progress: (T) -> Float): Comparator<T> =
            compareBy<T, Locator?>(nullsLast()) { LocatorRange.decode(range(it))?.start }.thenBy { progress(it) }
    }
}
