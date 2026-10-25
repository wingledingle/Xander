package com.xander.files.ui.browser

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xander.files.data.bookmarks.BookmarkRepository
import com.xander.files.data.clipboard.ClipOp
import com.xander.files.data.clipboard.ClipboardRepository
import com.xander.files.data.db.HistoryDao
import com.xander.files.data.ops.FileActions
import com.xander.files.data.transfer.TransferManager
import com.xander.files.data.transfer.TransferRequest
import com.xander.files.data.transfer.TransferType
import com.xander.files.data.transfer.TransferOptions
import com.xander.files.data.archive.ArchiveFormats
import com.xander.files.domain.fs.Capability
import com.xander.files.data.db.HistoryEntity
import com.xander.files.data.fs.local.RestrictedFolderException
import com.xander.files.data.fs.local.SafGrantRequiredException
import com.xander.files.data.fs.root.RootFileSystemProvider
import com.xander.files.data.fs.saf.SafRootKind
import com.xander.files.data.fs.saf.SafRootStore
import com.xander.files.data.fs.shizuku.ShizukuFileSystemProvider
import com.xander.files.data.open.FileOpener
import com.xander.files.data.settings.AppSettings
import com.xander.files.data.settings.SettingsRepository
import com.xander.files.data.settings.SortBy
import com.xander.files.data.settings.ViewMode
import com.xander.files.data.storage.StorageVolumes
import com.xander.files.domain.fs.FileEntry
import com.xander.files.domain.fs.FileSystemRegistry
import com.xander.files.domain.fs.FsException
import com.xander.files.domain.fs.FsPath
import com.xander.files.domain.fs.WatchableProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Collator
import javax.inject.Inject

sealed interface BrowserError {
    data class Restricted(val path: FsPath) : BrowserError
    data class SafGrant(val path: FsPath, val volumeUuid: String?) : BrowserError
    data class Message(val text: String, val retryable: Boolean) : BrowserError
    data class Password(val archive: FsPath, val wrong: Boolean) : BrowserError
}

sealed interface BrowserEvent {
    data class Toast(val text: String) : BrowserEvent
    data class Launch(val intent: Intent) : BrowserEvent
    data class AskArchivePassword(val archive: FsPath, val wrong: Boolean) : BrowserEvent
}

data class BrowserState(
    val path: FsPath = FsPath.local("/"),
    val title: String = "",
    val entries: List<FileEntry> = emptyList(),
    val loading: Boolean = true,
    val error: BrowserError? = null,
    val selection: Set<FsPath> = emptySet(),
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val writable: Boolean = true,
) {
    val selecting get() = selection.isNotEmpty()
}

@HiltViewModel
class BrowserViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val registry: FileSystemRegistry,
    private val settingsRepo: SettingsRepository,
    private val opener: FileOpener,
    private val history: HistoryDao,
    private val shizuku: ShizukuFileSystemProvider,
    private val root: RootFileSystemProvider,
    private val safRoots: SafRootStore,
    private val volumes: StorageVolumes,
    private val clipboard: ClipboardRepository,
    private val transfers: TransferManager,
    private val actions: FileActions,
    private val bookmarks: BookmarkRepository,
    private val installer: com.xander.files.data.apps.ApkInstaller,
    private val archivePasswords: com.xander.files.data.archive.ArchivePasswords,
    private val archives: com.xander.files.data.archive.ArchiveFileSystemProvider,
) : ViewModel() {

    val clipItems = clipboard.items.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val raw = MutableStateFlow(BrowserState())
    private val backStack = ArrayDeque<FsPath>()
    private val forwardStack = ArrayDeque<FsPath>()
    private var loadJob: Job? = null
    private var watchJob: Job? = null
    private val _events = Channel<BrowserEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    val settings: StateFlow<AppSettings> = settingsRepo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    /** Raw listing + current settings => visible, sorted list (computed off the main thread). */
    val state: StateFlow<BrowserState> = combine(raw, settings) { s, st ->
        withContext(Dispatchers.Default) { s.copy(entries = arrange(s.entries, st)) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, BrowserState())

    init {
        val start = savedState.get<String>("path")?.takeIf { it.isNotEmpty() }?.let { FsPath.parse(it) } ?: FsPath.local(volumes.primaryRoot)
        load(start)
    }

    private fun arrange(list: List<FileEntry>, s: AppSettings): List<FileEntry> {
        val collator = Collator.getInstance().apply { strength = Collator.SECONDARY }
        val filtered = if (s.showHidden) list else list.filter { !it.isHidden }
        val cmp: Comparator<FileEntry> = when (s.sortBy) {
            SortBy.NAME -> Comparator { a, b -> collator.compare(a.name, b.name) }
            SortBy.SIZE -> compareBy<FileEntry> { it.size }.thenComparator { a, b -> collator.compare(a.name, b.name) }
            SortBy.DATE -> compareBy<FileEntry> { it.lastModified }.thenComparator { a, b -> collator.compare(a.name, b.name) }
            SortBy.TYPE -> compareBy<FileEntry> { it.extension }.thenComparator { a, b -> collator.compare(a.name, b.name) }
        }
        val dir = if (s.sortAscending) cmp else cmp.reversed()
        return if (s.foldersFirst) filtered.sortedWith(compareByDescending<FileEntry> { it.isDirectory }.then(dir)) else filtered.sortedWith(dir)
    }

    private fun load(path: FsPath, keepSelection: Boolean = false, silent: Boolean = false) {
        loadJob?.cancel()
        raw.update {
            it.copy(path = path, loading = !silent || it.path != path, error = if (silent) it.error else null,
                selection = if (keepSelection) it.selection else emptySet(),
                entries = if (it.path == path) it.entries else emptyList(),
                canGoBack = backStack.isNotEmpty(), canGoForward = forwardStack.isNotEmpty())
        }
        loadJob = viewModelScope.launch {
            val provider = try { registry.provider(path) } catch (e: FsException) { raw.update { it.copy(loading = false, error = BrowserError.Message(e.message ?: "", false)) }; return@launch }
            val title = if (path.isRoot) provider.rootTitle(path.authority) else path.name
            val writable = Capability.WRITE in provider.capabilities(path)
            try {
                val list = provider.list(path)
                raw.update { it.copy(entries = list, loading = false, error = null, title = title, writable = writable) }
                if (!silent) history.upsert(HistoryEntity(path.toString(), title, System.currentTimeMillis()))
            } catch (e: RestrictedFolderException) {
                raw.update { it.copy(entries = emptyList(), loading = false, error = BrowserError.Restricted(path), title = title) }
            } catch (e: SafGrantRequiredException) {
                raw.update { it.copy(loading = false, error = BrowserError.SafGrant(path, e.volumeUuid), title = title) }
            } catch (e: FsException.PasswordRequired) {
                raw.update { it.copy(entries = emptyList(), loading = false, error = BrowserError.Password(e.path, false), title = title) }
            } catch (e: FsException.WrongPassword) {
                raw.update { it.copy(entries = emptyList(), loading = false, error = BrowserError.Password(archiveOf(path), true), title = title) }
            } catch (e: FsException) {
                raw.update { it.copy(entries = emptyList(), loading = false, error = BrowserError.Message(e.message ?: "Error", e.retryable), title = title) }
            }
        }
        watchJob?.cancel()
        val p = runCatching { registry.provider(path) }.getOrNull()
        if (p is WatchableProvider) {
            watchJob = viewModelScope.launch { p.watch(path).debounce(400).collect { load(path, keepSelection = true, silent = true) } }
        }
    }

    fun navigate(path: FsPath) {
        val cur = raw.value.path
        if (cur == path) return refresh()
        backStack.addLast(cur); forwardStack.clear()
        load(path)
    }

    fun refresh() = load(raw.value.path, keepSelection = true)

    fun up(): Boolean {
        val parent = raw.value.path.parent ?: return false
        navigate(parent); return true
    }

    /** Back in browsing history; returns false when there is nothing to go back to. */
    fun back(): Boolean {
        if (raw.value.selecting) { clearSelection(); return true }
        val prev = backStack.removeLastOrNull() ?: return false
        forwardStack.addLast(raw.value.path)
        load(prev); return true
    }

    fun forward() {
        val next = forwardStack.removeLastOrNull() ?: return
        backStack.addLast(raw.value.path)
        load(next)
    }

    fun open(entry: FileEntry) {
        if (raw.value.selecting) { toggle(entry); return }
        if (entry.isDirectory) { navigate(entry.path); return }
        if (entry.extension in setOf("apk", "apks", "xapk", "apkm")) { install(entry); return }
        if (ArchiveFormats.isArchiveName(entry.name)) { navigate(FsPath.archive(entry.path)); return }
        viewModelScope.launch {
            bookmarks.recordOpened(entry)
            try { val i = opener.openExternal(entry); _events.send(BrowserEvent.Launch(if (i.component != null) i else Intent.createChooser(i, entry.name))) }
            catch (e: FsException.PasswordRequired) { _events.send(BrowserEvent.AskArchivePassword(e.path, false)) }
            catch (e: FsException.WrongPassword) { _events.send(BrowserEvent.AskArchivePassword(archiveOf(entry.path), true)) }
            catch (e: Exception) { _events.send(BrowserEvent.Toast(FsException.wrap(e).message ?: "Cannot open")) }
        }
    }

    private fun archiveOf(p: FsPath): FsPath = if (p.scheme == FsPath.SCHEME_ARCHIVE) FsPath.parse(FsPath.decodeAuthority(p.authority)) else p

    fun submitArchivePassword(archive: FsPath, password: String) { archivePasswords.set(archive, password); refresh() }

    // ---- archives, encryption, copy/move to ----

    private fun enqueue(type: TransferType, sources: List<FsPath>, dest: FsPath?, title: String, options: TransferOptions = TransferOptions()) =
        viewModelScope.launch { transfers.enqueue(TransferRequest(type, sources.map { it.toString() }, dest?.toString(), title, options)); clearSelection() }

    fun compress(entries: List<FileEntry>, spec: com.xander.files.ui.ops.CompressSpec) = enqueue(
        TransferType.COMPRESS, entries.map { it.path }, raw.value.path.takeIf { raw.value.writable } ?: entries.first().path.parent, "Compressing ${spec.name}",
        TransferOptions(archiveFormat = spec.format.name, archiveName = spec.name, password = spec.password, compressionLevel = spec.level))

    fun extractHere(entries: List<FileEntry>) = viewModelScope.launch {
        if (!passwordsReady(entries)) return@launch
        entries.groupBy { it.path.parent!! }.forEach { (dir, list) -> enqueue(TransferType.EXTRACT, list.map { it.path }, dir, "Extracting ${list.first().name}") }
    }

    fun extractTo(entries: List<FileEntry>, dest: FsPath) = viewModelScope.launch {
        if (!passwordsReady(entries)) return@launch
        enqueue(TransferType.EXTRACT, entries.map { it.path }, dest, "Extracting ${entries.first().name}")
    }

    /** Asks for the archive password up front so background extraction doesn't fail half way. */
    private suspend fun passwordsReady(entries: List<FileEntry>): Boolean {
        for (e in entries) {
            val root = if (e.path.scheme == FsPath.SCHEME_ARCHIVE) FsPath(e.path.scheme, e.path.authority, "/") else FsPath.archive(e.path)
            if (archives.needsPassword(root)) { _events.send(BrowserEvent.AskArchivePassword(archiveOf(root), false)); return false }
        }
        return true
    }

    fun copyTo(entries: List<FileEntry>, dest: FsPath, move: Boolean) = transferTo(entries.map { it.path }, dest, move).also { clearSelection() }

    fun encrypt(entries: List<FileEntry>, password: String, deleteOriginal: Boolean) = enqueue(
        TransferType.ENCRYPT, entries.map { it.path }, null, "Encrypting ${entries.size} item(s)", TransferOptions(password = password, deleteOriginal = deleteOriginal))

    fun decrypt(entries: List<FileEntry>, password: String, deleteOriginal: Boolean) = enqueue(
        TransferType.DECRYPT, entries.map { it.path }, null, "Decrypting ${entries.size} item(s)", TransferOptions(password = password, deleteOriginal = deleteOriginal))

    fun viewAsZip(entry: FileEntry) = navigate(FsPath.archive(entry.path))

    fun install(entry: FileEntry) = viewModelScope.launch {
        if (!installer.canInstall()) { _events.send(BrowserEvent.Launch(installer.permissionIntent())); _events.send(BrowserEvent.Toast("Allow installs, then tap the file again")); return@launch }
        try { installer.install(entry.path) } catch (e: Exception) { _events.send(BrowserEvent.Toast(FsException.wrap(e).message ?: "Install failed")) }
    }

    fun toggle(entry: FileEntry) = raw.update {
        it.copy(selection = if (entry.path in it.selection) it.selection - entry.path else it.selection + entry.path)
    }

    fun selectRange(to: FileEntry) {
        val list = state.value.entries
        val anchor = list.indexOfFirst { it.path in raw.value.selection }.takeIf { it >= 0 } ?: return toggle(to)
        val end = list.indexOf(to)
        val range = if (anchor <= end) anchor..end else end..anchor
        raw.update { s -> s.copy(selection = s.selection + range.map { list[it].path }) }
    }

    fun selectAll() = raw.update { it.copy(selection = state.value.entries.map { e -> e.path }.toSet()) }
    fun invertSelection() = raw.update { s -> s.copy(selection = state.value.entries.map { it.path }.filter { it !in s.selection }.toSet()) }
    fun clearSelection() = raw.update { it.copy(selection = emptySet()) }

    fun selectedEntries(): List<FileEntry> = state.value.entries.filter { it.path in raw.value.selection }

    // ---- simple operations (heavy ones go through the transfer engine) ----

    private fun op(block: suspend () -> Unit) = viewModelScope.launch {
        try { block() } catch (e: Exception) { _events.send(BrowserEvent.Toast(FsException.wrap(e).message ?: "Failed")) }
        refresh()
    }

    fun newFolder(name: String) = op { registry.provider(raw.value.path).mkdir(raw.value.path, name) }
    fun newFile(name: String) = op { registry.provider(raw.value.path).createFile(raw.value.path, name) }
    /** Deletes through the transfer engine: to the recycle bin unless [permanent] or the bin is off. */
    fun delete(entries: List<FileEntry>, permanent: Boolean) = viewModelScope.launch {
        val toBin = !permanent && settings.value.recycleBinEnabled
        val title = (if (toBin) "Moving to recycle bin: " else "Deleting ") + if (entries.size == 1) entries.first().name else "${entries.size} items"
        transfers.enqueue(TransferRequest(if (toBin) TransferType.RECYCLE else TransferType.DELETE, entries.map { it.path.toString() }, null, title))
        clearSelection()
    }

    /** Asks the screen to show the delete confirmation (used from the action sheet). */
    val deleteRequests = Channel<List<FileEntry>>(Channel.CONFLATED)
    fun requestDelete(entries: List<FileEntry>) { deleteRequests.trySend(entries) }

    suspend fun currentFolderEntry(): FileEntry? = runCatching { registry.provider(raw.value.path).stat(raw.value.path) }.getOrNull()

    fun copy(entries: List<FileEntry>, cut: Boolean) = viewModelScope.launch {
        clipboard.add(if (cut) ClipOp.CUT else ClipOp.COPY, entries.map { it.path })
        _events.send(BrowserEvent.Toast(if (cut) "Cut ${entries.size} item(s)" else "Copied ${entries.size} item(s)"))
        clearSelection()
    }

    fun pasteLatest() = viewModelScope.launch {
        val item = clipItems.value.firstOrNull() ?: return@launch
        clipboard.paste(item.id, raw.value.path)
    }

    /** Drag-and-drop / direct transfer used by dual-pane mode. */
    fun transferTo(paths: List<FsPath>, destination: FsPath, move: Boolean) = viewModelScope.launch {
        val title = (if (move) "Moving " else "Copying ") + if (paths.size == 1) paths.first().name else "${paths.size} items"
        transfers.enqueue(TransferRequest(if (move) TransferType.MOVE else TransferType.COPY, paths.map { it.toString() }, destination.toString(), title))
    }

    fun share(entries: List<FileEntry>) = launchIntent { actions.shareIntent(entries) }
    fun openWith(entry: FileEntry) = launchIntent { Intent.createChooser(opener.viewIntent(entry, "*/*").setType(entry.mimeType ?: "*/*").setData(opener.shareableUri(entry.path)), entry.name) }
    fun setAs(entry: FileEntry) = launchIntent { actions.setAsIntent(entry) }
    fun duplicate(entry: FileEntry) = op { actions.duplicate(entry); clearSelection() }
    fun setHidden(entries: List<FileEntry>, hidden: Boolean) = op { entries.forEach { actions.setHidden(it, hidden) }; clearSelection() }
    fun toggleFavorite(entries: List<FileEntry>) = viewModelScope.launch { entries.forEach { bookmarks.toggle(it) }; clearSelection() }
    fun bookmarkCurrent() = viewModelScope.launch {
        val p = raw.value.path
        bookmarks.add(p, raw.value.title.ifEmpty { p.name.ifEmpty { "/" } }, true)
        _events.send(BrowserEvent.Toast("Added to favorites"))
    }
    fun createShortcut(entry: FileEntry) = viewModelScope.launch {
        if (!actions.createShortcut(entry)) _events.send(BrowserEvent.Toast("Your launcher does not support shortcuts"))
    }

    private fun launchIntent(block: suspend () -> Intent) = viewModelScope.launch {
        try { _events.send(BrowserEvent.Launch(block())) } catch (e: Exception) { _events.send(BrowserEvent.Toast(FsException.wrap(e).message ?: "Failed")) }
    }

    fun rename(entry: FileEntry, name: String) = op { registry.provider(entry.path).rename(entry.path, name); clearSelection() }

    fun setSort(by: SortBy) = viewModelScope.launch {
        settingsRepo.update { if (it.sortBy == by) it.copy(sortAscending = !it.sortAscending) else it.copy(sortBy = by, sortAscending = by == SortBy.NAME || by == SortBy.TYPE) }
    }
    fun toggleFoldersFirst() = viewModelScope.launch { settingsRepo.update { it.copy(foldersFirst = !it.foldersFirst) } }
    fun toggleHidden() = viewModelScope.launch { settingsRepo.update { it.copy(showHidden = !it.showHidden) } }
    fun setViewMode(m: ViewMode) = viewModelScope.launch { settingsRepo.update { it.copy(viewMode = m) } }
    fun setGridSize(n: Int) = viewModelScope.launch { settingsRepo.update { it.copy(gridSize = n.coerceIn(2, 6)) } }

    // ---- restricted folder access ----

    fun useShizuku() = viewModelScope.launch {
        shizuku.refresh()
        if (shizuku.requestPermission()) refresh()
        else _events.send(BrowserEvent.Toast("Start the Shizuku app first, then try again"))
    }

    fun useRoot() = viewModelScope.launch {
        if (root.requestRoot()) { settingsRepo.update { it.copy(rootMode = true) }; refresh() }
        else _events.send(BrowserEvent.Toast("Root access was not granted"))
    }

    fun onTreeGranted(uri: Uri, kind: SafRootKind, volumePath: String?, uuid: String?) = viewModelScope.launch {
        try {
            safRoots.add(uri, uri.lastPathSegment ?: "Storage", kind, uuid, volumePath)
            refresh()
        } catch (e: Exception) { _events.send(BrowserEvent.Toast(e.message ?: "Grant failed")) }
    }

    fun volumePathFor(path: FsPath): String? = volumes.volumeFor(path.path)?.directPath
    fun primaryRoot() = volumes.primaryRoot
}
