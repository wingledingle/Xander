package app.quinten.ui.library

import android.app.Application
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.quinten.container
import app.quinten.data.BookEntity
import app.quinten.settings.AppPrefs
import app.quinten.settings.LibrarySort
import app.quinten.data.CollectionNameResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class Shelf(val label: String) {
    ALL("All"), READING("Reading"), UNREAD("Not started"), FINISHED("Finished"), FAVORITES("Favourites"),
    COLLECTIONS("Collections"), AUTHORS("Authors"), SERIES("Series"), FORMATS("Formats"), MISSING("Missing files"),
}

/** A drill-down within a grouping shelf (an author, a series, a collection or a format). */
data class Group(val shelf: Shelf, val key: String, val id: Long = -1)

class LibraryViewModel(app: Application) : AndroidViewModel(app) {
    private val c = app.container
    private val repo = c.library

    val shelf = MutableStateFlow(Shelf.ALL)
    val group = MutableStateFlow<Group?>(null)
    val query = MutableStateFlow("")
    val prefs: StateFlow<AppPrefs> = c.settings.app.stateIn(viewModelScope, SharingStarted.Eagerly, AppPrefs())
    val scan = repo.scanState
    val collections = repo.observeCollections().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val folders = repo.observeScanFolders().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val allBooks = repo.observeBooks().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    private val collectionBooks = group.flatMapLatest { g ->
        if (g?.shelf == Shelf.COLLECTIONS) repo.observeCollectionBooks(g.id) else flowOf(emptyList())
    }

    val books: StateFlow<List<BookEntity>> = combine(allBooks, shelf, group, query, prefs) { all, sh, g, q, p ->
        Triple(all, sh, g) to (q to p)
    }.combine(collectionBooks) { (t, qp), coll ->
        val (all, sh, g) = t
        val (q, p) = qp
        var list = when {
            g != null -> when (g.shelf) {
                Shelf.AUTHORS -> all.filter { g.key in it.authorList || (g.key == UNKNOWN && it.authorList.isEmpty()) }
                Shelf.SERIES -> all.filter { it.series == g.key }.sortedBy { it.seriesIndex ?: Float.MAX_VALUE }
                Shelf.FORMATS -> all.filter { it.format == g.key }
                Shelf.COLLECTIONS -> coll
                else -> all
            }
            else -> when (sh) {
                Shelf.ALL -> all.filter { !it.missing }
                Shelf.READING -> all.filter { it.isReading && !it.missing }
                Shelf.UNREAD -> all.filter { it.progress == 0f && !it.missing }
                Shelf.FINISHED -> all.filter { it.finishedAt > 0 }
                Shelf.FAVORITES -> all.filter { it.favorite }
                Shelf.MISSING -> all.filter { it.missing }
                else -> emptyList()
            }
        }
        if (q.isNotBlank()) list = list.filter { b ->
            b.title.contains(q, true) || b.authors.contains(q, true) || (b.series?.contains(q, true) == true) || b.fileName.contains(q, true)
        }
        if (g?.shelf == Shelf.SERIES) list else when (p.librarySort) {
            LibrarySort.RECENT -> list.sortedWith(compareByDescending<BookEntity> { it.lastOpenedAt }.thenByDescending { it.addedAt })
            LibrarySort.TITLE -> list.sortedBy { it.title.lowercase() }
            LibrarySort.AUTHOR -> list.sortedBy { (it.authorList.firstOrNull() ?: "~").lowercase() }
            LibrarySort.ADDED -> list.sortedByDescending { it.addedAt }
            LibrarySort.PROGRESS -> list.sortedByDescending { it.progress }
        }
    }
        // Filtering and sorting thousands of books must not run on the main thread for every
        // database change during a scan; only the latest result matters.
        .conflate().flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Groups for the Authors / Series / Formats shelves with book counts. */
    val groups: StateFlow<List<Pair<String, Int>>> = combine(allBooks, shelf) { all, sh ->
        val visible = all.filter { !it.missing }
        when (sh) {
            Shelf.AUTHORS -> visible.flatMap { b -> b.authorList.ifEmpty { listOf(UNKNOWN) } }.groupingBy { it }.eachCount().toList().sortedBy { it.first.lowercase() }
            Shelf.SERIES -> visible.mapNotNull { it.series }.groupingBy { it }.eachCount().toList().sortedBy { it.first.lowercase() }
            Shelf.FORMATS -> visible.groupingBy { it.format }.eachCount().toList().sortedBy { it.first }
            else -> emptyList()
        }
    }.conflate().flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Runs in the application scope so the scan keeps going when you open a book. */
    /** Quick by default: folders and files already scanned are remembered; [full] re-examines everything. */
    fun scanNow(full: Boolean = false) = c.appScope.launch {
        val roots = if (hasAllFilesAccess()) repo.storageRoots() else emptyList()
        repo.scan(roots, full)
    }

    fun hasAllFilesAccess(): Boolean = runCatching {
        if (android.os.Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else androidx.core.content.ContextCompat.checkSelfPermission(getApplication(), android.Manifest.permission.READ_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun addFolder(uri: String) = viewModelScope.launch { repo.addScanFolder(uri); scanNow() }
    fun removeFolder(location: String) = viewModelScope.launch { repo.removeScanFolder(location) }
    suspend fun addBook(location: String): Long? = repo.addBook(location)
    fun setFavorite(b: BookEntity, fav: Boolean) = viewModelScope.launch { repo.setFavorite(b.id, fav) }
    fun setFinished(b: BookEntity, f: Boolean) = viewModelScope.launch { repo.setFinished(b.id, f) }
    fun remove(b: BookEntity) = viewModelScope.launch { repo.removeFromLibrary(b.id) }
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** Short messages for the user (shown as a toast). */
    val messages: SharedFlow<String> = _messages

    fun createCollection(name: String, addBook: Long? = null) = viewModelScope.launch {
        val clean = name.trim()
        if (clean.isEmpty()) { _messages.tryEmit("A collection needs a name."); return@launch }
        val existing = repo.collectionNamed(clean)
        val id = runCatching { existing?.id ?: repo.createCollection(clean) }.getOrElse {
            _messages.tryEmit("The collection could not be created."); return@launch
        }
        if (existing != null) {
            _messages.tryEmit(if (addBook != null) "Added to the existing collection “${existing.name}”." else "There is already a collection called “${existing.name}”.")
        }
        if (addBook != null) repo.addToCollection(addBook, id)
    }
    fun renameCollection(id: Long, name: String) = viewModelScope.launch {
        when (runCatching { repo.renameCollection(id, name) }.getOrDefault(CollectionNameResult.DUPLICATE)) {
            CollectionNameResult.OK -> {}
            CollectionNameResult.BLANK -> _messages.tryEmit("A collection needs a name. The old name was kept.")
            CollectionNameResult.DUPLICATE -> _messages.tryEmit("There is already a collection called “${name.trim()}”. The old name was kept.")
        }
    }
    /** Removes a book and its notes for good (the file itself is kept). */
    fun forget(b: BookEntity) = viewModelScope.launch { repo.forgetBook(b.id) }
    fun deleteCollection(id: Long) = viewModelScope.launch { repo.deleteCollection(id) }
    fun addToCollection(bookId: Long, collectionId: Long) = viewModelScope.launch { repo.addToCollection(bookId, collectionId) }
    fun removeFromCollection(bookId: Long, collectionId: Long) = viewModelScope.launch { repo.removeFromCollection(bookId, collectionId) }
    fun collectionsOf(bookId: Long) = repo.observeCollectionsOf(bookId)
    fun updateApp(f: (AppPrefs) -> AppPrefs) = viewModelScope.launch { c.settings.updateApp(f) }
    fun reloadMetadata(b: BookEntity) = viewModelScope.launch { repo.loadMetadata(b.id) }

    companion object { const val UNKNOWN = "Unknown author" }
}
