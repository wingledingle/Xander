package com.xander.files.ui.browser

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xander.files.data.db.HistoryDao
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
}

sealed interface BrowserEvent {
    data class Toast(val text: String) : BrowserEvent
    data class Launch(val intent: Intent) : BrowserEvent
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
) : ViewModel() {

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
            try {
                val list = provider.list(path)
                raw.update { it.copy(entries = list, loading = false, error = null, title = title) }
                if (!silent) history.upsert(HistoryEntity(path.toString(), title, System.currentTimeMillis()))
            } catch (e: RestrictedFolderException) {
                raw.update { it.copy(entries = emptyList(), loading = false, error = BrowserError.Restricted(path), title = title) }
            } catch (e: SafGrantRequiredException) {
                raw.update { it.copy(loading = false, error = BrowserError.SafGrant(path, e.volumeUuid), title = title) }
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
        viewModelScope.launch {
            try { _events.send(BrowserEvent.Launch(Intent.createChooser(opener.openExternal(entry), entry.name))) }
            catch (e: Exception) { _events.send(BrowserEvent.Toast(FsException.wrap(e).message ?: "Cannot open")) }
        }
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
    /** Deletes the given entries (routed through the recycle bin once it is enabled). */
    fun delete(entries: List<FileEntry>) = op {
        for (e in entries) registry.provider(e.path).delete(e.path)
        clearSelection()
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
