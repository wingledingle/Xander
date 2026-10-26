package com.xander.files.ui.library

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.xander.files.R
import com.xander.files.data.bookmarks.BookmarkRepository
import com.xander.files.data.clipboard.ClipOp
import com.xander.files.data.clipboard.ClipboardRepository
import com.xander.files.data.index.FileIndexer
import com.xander.files.data.index.LibraryKind
import com.xander.files.data.index.LibraryRepository
import com.xander.files.data.open.FileOpener
import com.xander.files.data.ops.FileActions
import com.xander.files.data.settings.SettingsRepository
import com.xander.files.data.transfer.TransferManager
import com.xander.files.data.transfer.TransferRequest
import com.xander.files.data.transfer.TransferType
import com.xander.files.domain.fs.FileEntry
import com.xander.files.domain.fs.FsPath
import com.xander.files.ui.browser.FileGridCell
import com.xander.files.ui.browser.FileListRow
import com.xander.files.ui.common.EmptyState
import com.xander.files.ui.ops.DeleteDialog
import com.xander.files.ui.ops.PropertiesDialog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class LibSort { DATE, NAME, SIZE }

@HiltViewModel
class LibraryViewModel @Inject constructor(
    saved: SavedStateHandle,
    private val repo: LibraryRepository,
    private val indexer: FileIndexer,
    private val transfers: TransferManager,
    private val clipboard: ClipboardRepository,
    private val actions: FileActions,
    private val opener: FileOpener,
    private val bookmarks: BookmarkRepository,
    private val launcher: com.xander.files.data.open.ViewerLauncher,
    private val nsfw: com.xander.files.data.nsfw.NsfwRepository,
    private val settingsRepo: SettingsRepository,
) : ViewModel() {
    val kind: LibraryKind = LibraryKind.valueOf(saved.get<String>("kind") ?: LibraryKind.IMAGES.name)
    val items = MutableStateFlow<List<FileEntry>>(emptyList())
    val loading = MutableStateFlow(true)
    val selection = MutableStateFlow<Set<FsPath>>(emptySet())
    val sort = MutableStateFlow(if (kind == LibraryKind.LARGE) LibSort.SIZE else LibSort.DATE)
    val grid = MutableStateFlow(kind == LibraryKind.IMAGES || kind == LibraryKind.VIDEOS)
    val settings = settingsRepo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, com.xander.files.data.settings.AppSettings())
    val events = Channel<Any>(Channel.BUFFERED)
    private var raw: List<FileEntry> = emptyList()

    init { load() }

    /** Adult list only: per-file class probabilities, shown under each row. */
    val scores = MutableStateFlow<Map<String, com.xander.files.data.db.NsfwScoreEntity>>(emptyMap())

    fun setAdultThreshold(pct: Int) = viewModelScope.launch { settingsRepo.update { it.copy(nsfwThreshold = pct) }; load() }

    fun load() = viewModelScope.launch {
        loading.value = true
        raw = repo.files(kind).filter { java.io.File(it.path.path).exists() }
        if (kind == LibraryKind.ADULT) scores.value = nsfw.scoresFor(raw.map { it.path.path })
        apply(); loading.value = false
    }

    fun refresh() = viewModelScope.launch { indexer.indexAll(full = true); load() }

    private fun apply() {
        val hidden = settings.value.showHidden
        val base = if (hidden) raw else raw.filter { e -> !e.isHidden && !e.path.path.contains("/.") }
        items.value = when (sort.value) {
            LibSort.DATE -> base.sortedByDescending { it.lastModified }
            LibSort.NAME -> base.sortedBy { it.name.lowercase() }
            LibSort.SIZE -> base.sortedByDescending { it.size }
        }
    }

    fun setSort(s: LibSort) { sort.value = s; apply() }
    fun toggleGrid() { grid.value = !grid.value }
    fun toggle(e: FileEntry) { selection.value = selection.value.let { if (e.path in it) it - e.path else it + e.path } }
    fun selectAll() { selection.value = items.value.map { it.path }.toSet() }
    fun clear() { selection.value = emptySet() }
    fun selected() = items.value.filter { it.path in selection.value }

    fun open(e: FileEntry) = viewModelScope.launch {
        if (selection.value.isNotEmpty()) { toggle(e); return@launch }
        launcher.open(e)
    }

    fun copy(cut: Boolean) = viewModelScope.launch {
        clipboard.add(if (cut) ClipOp.CUT else ClipOp.COPY, selection.value.toList()); events.send("Added to clipboard"); clear()
    }

    /** Moves the selection into a private folder that gallery apps skip (.nomedia). */
    fun moveToPrivate() = viewModelScope.launch {
        val dir = java.io.File(android.os.Environment.getExternalStorageDirectory(), "Xander Private").apply { mkdirs() }
        runCatching { java.io.File(dir, ".nomedia").createNewFile() }
        val sel = selected()
        transfers.enqueue(TransferRequest(TransferType.MOVE, sel.map { it.path.toString() }, FsPath.local(dir.path).toString(), "Moving ${sel.size} item(s) to Xander Private"))
        raw = raw.filter { it.path !in selection.value }; clear(); apply()
    }

    fun share() = viewModelScope.launch { runCatching { events.send(actions.shareIntent(selected())) } }

    fun delete(permanent: Boolean) = viewModelScope.launch {
        val sel = selected()
        val toBin = !permanent && settings.value.recycleBinEnabled
        transfers.enqueue(TransferRequest(if (toBin) TransferType.RECYCLE else TransferType.DELETE, sel.map { it.path.toString() }, null,
            "Deleting ${sel.size} item(s)"))
        raw = raw.filter { it.path !in selection.value }; clear(); apply()
    }
}

@Composable
fun LibraryScreen(onBack: () -> Unit, onOpenFolder: (FsPath) -> Unit, vm: LibraryViewModel = hiltViewModel()) {
    val items by vm.items.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val sel by vm.selection.collectAsStateWithLifecycle()
    val grid by vm.grid.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var sortMenu by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var props by remember { mutableStateOf<List<FileEntry>?>(null) }
    LaunchedEffect(Unit) {
        vm.events.receiveAsFlow().collect { e ->
            when (e) {
                is Intent -> runCatching { ctx.startActivity(e) }.onFailure { Toast.makeText(ctx, R.string.no_app_found, Toast.LENGTH_SHORT).show() }
                is String -> Toast.makeText(ctx, e, Toast.LENGTH_SHORT).show()
            }
        }
    }
    androidx.activity.compose.BackHandler(enabled = sel.isNotEmpty()) { vm.clear() }
    Scaffold(topBar = {
        if (sel.isNotEmpty()) TopAppBar(
            navigationIcon = { IconButton(onClick = vm::clear) { Icon(Icons.Filled.Close, null) } },
            title = { Text("${sel.size}") },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            actions = {
                IconButton(onClick = vm::selectAll) { Icon(Icons.Filled.SelectAll, stringResource(R.string.select_all)) }
                IconButton(onClick = { vm.copy(false) }) { Icon(Icons.Filled.ContentCopy, stringResource(R.string.copy)) }
                IconButton(onClick = { vm.copy(true) }) { Icon(Icons.Filled.ContentCut, stringResource(R.string.cut)) }
                IconButton(onClick = vm::share) { Icon(Icons.Filled.Share, stringResource(R.string.share)) }
                IconButton(onClick = { deleting = true }) { Icon(Icons.Filled.Delete, stringResource(R.string.delete)) }
                IconButton(onClick = { props = vm.selected() }) { Icon(Icons.Filled.Info, stringResource(R.string.properties)) }
                if (vm.kind == LibraryKind.ADULT) IconButton(onClick = vm::moveToPrivate) { Icon(Icons.Filled.VisibilityOff, stringResource(R.string.move_to_private)) }
                if (sel.size == 1) IconButton(onClick = { sel.first().parent?.let(onOpenFolder) }) { Icon(Icons.Filled.FolderOpen, stringResource(R.string.open_containing_folder)) }
            }
        ) else TopAppBar(
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            title = { Text(stringResource(libraryTitle(vm.kind)) + if (items.isNotEmpty()) " (${items.size})" else "") },
            actions = {
                IconButton(onClick = vm::toggleGrid) { Icon(if (grid) Icons.Filled.ViewList else Icons.Filled.GridView, stringResource(R.string.view_mode)) }
                Box {
                    IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Filled.Sort, stringResource(R.string.sort)) }
                    DropdownMenu(sortMenu, { sortMenu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.sort_date)) }, onClick = { sortMenu = false; vm.setSort(LibSort.DATE) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.sort_name)) }, onClick = { sortMenu = false; vm.setSort(LibSort.NAME) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.sort_size)) }, onClick = { sortMenu = false; vm.setSort(LibSort.SIZE) })
                    }
                }
            }
        )
    }) { pad ->
        val scores by vm.scores.collectAsStateWithLifecycle()
    Column(Modifier.padding(pad).fillMaxSize()) {
    if (vm.kind == LibraryKind.ADULT) {
        var t by remember(settings.nsfwThreshold) { mutableStateOf(settings.nsfwThreshold.toFloat()) }
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(stringResource(R.string.adult_threshold, t.toInt()), style = MaterialTheme.typography.labelLarge)
            androidx.compose.material3.Slider(t, { t = it }, valueRange = 30f..95f, onValueChangeFinished = { vm.setAdultThreshold(t.toInt()) })
        }
    }
    PullToRefreshBox(isRefreshing = loading && items.isNotEmpty(), onRefresh = vm::refresh, modifier = Modifier.fillMaxSize()) {
            when {
                loading && items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                items.isEmpty() -> EmptyState(Icons.Filled.SearchOff, stringResource(R.string.library_empty))
                grid -> LazyVerticalGrid(GridCells.Adaptive(110.dp), Modifier.fillMaxSize()) {
                    items(items, key = { it.path.toString() }) { e ->
                        FileGridCell(e, e.path in sel, settings.showExtensions, settings.thumbnails, onClick = { vm.open(e) }, onLongClick = { vm.toggle(e) })
                    }
                }
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(items, key = { it.path.toString() }) { e ->
                        FileListRow(e, e.path in sel, settings.showExtensions, settings.thumbnails, onClick = { vm.open(e) }, onLongClick = { vm.toggle(e) })
                        scores[e.path.path]?.let { sc ->
                            Text(com.xander.files.ui.nsfw.scoreLine(sc.porn, sc.hentai, sc.sexy, sc.drawings), style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 72.dp, bottom = 6.dp))
                        }
                    }
                }
            }
        }
    }
    }
    if (deleting) DeleteDialog(vm.selected(), settings.recycleBinEnabled, onDismiss = { deleting = false }) { deleting = false; vm.delete(it) }
    props?.let { PropertiesDialog(it, onDismiss = { props = null }) }
}

private val Int.dp get() = androidx.compose.ui.unit.Dp(this.toFloat())

fun libraryTitle(k: LibraryKind) = when (k) {
    LibraryKind.IMAGES -> R.string.cat_images; LibraryKind.VIDEOS -> R.string.cat_videos; LibraryKind.MUSIC -> R.string.cat_music
    LibraryKind.DOCUMENTS -> R.string.cat_documents; LibraryKind.APKS -> R.string.cat_apks; LibraryKind.ARCHIVES -> R.string.cat_archives
    LibraryKind.GAMES -> R.string.cat_games; LibraryKind.ADULT -> R.string.cat_adult
    LibraryKind.DOWNLOADS -> R.string.downloads; LibraryKind.RECENT -> R.string.recent_files; LibraryKind.LARGE -> R.string.cat_large
}
