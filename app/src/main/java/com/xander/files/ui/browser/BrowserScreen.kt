package com.xander.files.ui.browser

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xander.files.R
import com.xander.files.data.fs.saf.SafRootKind
import com.xander.files.data.fs.saf.SafRootStore
import com.xander.files.data.settings.SortBy
import com.xander.files.data.settings.ViewMode
import com.xander.files.data.storage.StorageAccess
import com.xander.files.domain.fs.FileEntry
import com.xander.files.domain.fs.FsPath
import com.xander.files.ui.common.ConfirmDialog
import com.xander.files.ui.common.EmptyState
import com.xander.files.ui.common.TextInputDialog
import com.xander.files.ui.ops.ActionSheet
import com.xander.files.ui.ops.DeleteDialog
import com.xander.files.ui.ops.PropertiesDialog
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

private sealed interface Dialog {
    data object NewFolder : Dialog
    data object NewFile : Dialog
    data class Rename(val entry: FileEntry) : Dialog
    data class Delete(val entries: List<FileEntry>) : Dialog
    data object EditPath : Dialog
    data class Properties(val entries: List<FileEntry>) : Dialog
    data class Actions(val entries: List<FileEntry>) : Dialog
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BrowserScreen(
    onExit: () -> Unit,
    onSearch: (FsPath) -> Unit,
    onOpenClipboard: (FsPath) -> Unit = {},
    vm: BrowserViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val clips by vm.clipItems.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var dialog by remember { mutableStateOf<Dialog?>(null) }
    var menu by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var fabMenu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        vm.events.collect { e ->
            when (e) {
                is BrowserEvent.Toast -> Toast.makeText(context, e.text, Toast.LENGTH_LONG).show()
                is BrowserEvent.Launch -> runCatching { context.startActivity(e.intent) }
                    .onFailure { Toast.makeText(context, R.string.no_app_found, Toast.LENGTH_SHORT).show() }
            }
        }
    }

    BackHandler { if (!vm.back()) onExit() }
    LaunchedEffect(Unit) { for (req in vm.deleteRequests) dialog = Dialog.Delete(req) }

    // SAF tree grant for restricted folders (Android 11/12) and SD cards on old releases.
    var pendingKind by remember { mutableStateOf(SafRootKind.FOLDER) }
    val treeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val vp = if (pendingKind == SafRootKind.ANDROID_DATA || pendingKind == SafRootKind.ANDROID_OBB) vm.primaryRoot() else vm.volumePathFor(state.path)
            vm.onTreeGranted(uri, pendingKind, vp, null)
        }
    }

    Scaffold(
        topBar = {
            if (state.selecting) {
                TopAppBar(
                    navigationIcon = { IconButton(onClick = vm::clearSelection) { Icon(Icons.Filled.Close, stringResource(R.string.cancel)) } },
                    title = { Text("${state.selection.size}") },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    actions = {
                        val sel = vm.selectedEntries()
                        IconButton(onClick = { vm.copy(sel, cut = false) }) { Icon(Icons.Filled.ContentCopy, stringResource(R.string.copy)) }
                        IconButton(onClick = { vm.copy(sel, cut = true) }) { Icon(Icons.Filled.ContentCut, stringResource(R.string.cut)) }
                        IconButton(onClick = { dialog = Dialog.Delete(sel) }) { Icon(Icons.Filled.Delete, stringResource(R.string.delete)) }
                        IconButton(onClick = { vm.share(sel) }, enabled = sel.any { !it.isDirectory }) { Icon(Icons.Filled.Share, stringResource(R.string.share)) }
                        IconButton(onClick = { dialog = Dialog.Actions(sel) }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.more)) }
                    }
                )
            } else {
                TopAppBar(
                    navigationIcon = { IconButton(onClick = { if (!vm.back()) onExit() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                    title = { Text(state.title.ifEmpty { state.path.name.ifEmpty { "/" } }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    actions = {
                        IconButton(onClick = { onSearch(state.path) }) { Icon(Icons.Filled.Search, stringResource(R.string.search)) }
                        IconButton(onClick = { vm.setViewMode(if (settings.viewMode == ViewMode.LIST) ViewMode.GRID else ViewMode.LIST) }) {
                            Icon(if (settings.viewMode == ViewMode.LIST) Icons.Filled.GridView else Icons.Filled.ViewList, stringResource(R.string.view_mode))
                        }
                        Box {
                            IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Filled.Sort, stringResource(R.string.sort)) }
                            DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                                SortBy.entries.forEach { by ->
                                    DropdownMenuItem(
                                        text = { Text(stringResource(sortLabel(by)) + if (settings.sortBy == by) (if (settings.sortAscending) "  ↑" else "  ↓") else "") },
                                        leadingIcon = { if (settings.sortBy == by) Icon(Icons.Filled.Check, null) },
                                        onClick = { vm.setSort(by) }
                                    )
                                }
                                HorizontalDivider()
                                DropdownMenuItem(text = { Text(stringResource(R.string.folders_first)) },
                                    leadingIcon = { if (settings.foldersFirst) Icon(Icons.Filled.Check, null) }, onClick = { vm.toggleFoldersFirst() })
                            }
                        }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, null) }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(if (settings.showHidden) R.string.hide_hidden else R.string.show_hidden)) },
                                    leadingIcon = { Icon(if (settings.showHidden) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, null) },
                                    onClick = { menu = false; vm.toggleHidden() })
                                if (state.canGoForward) DropdownMenuItem(text = { Text(stringResource(R.string.forward)) },
                                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.ArrowForward, null) }, onClick = { menu = false; vm.forward() })
                                DropdownMenuItem(text = { Text(stringResource(R.string.edit_path)) }, leadingIcon = { Icon(Icons.Filled.Edit, null) },
                                    onClick = { menu = false; dialog = Dialog.EditPath })
                                DropdownMenuItem(text = { Text(stringResource(R.string.select_all)) }, leadingIcon = { Icon(Icons.Filled.SelectAll, null) },
                                    onClick = { menu = false; vm.selectAll() })
                                DropdownMenuItem(text = { Text(stringResource(R.string.add_to_favorites)) }, leadingIcon = { Icon(Icons.Filled.Star, null) },
                                    onClick = { menu = false; vm.bookmarkCurrent() })
                                DropdownMenuItem(text = { Text(stringResource(R.string.clipboard)) }, leadingIcon = { Icon(Icons.Filled.ContentPaste, null) },
                                    onClick = { menu = false; onOpenClipboard(state.path) })
                                DropdownMenuItem(text = { Text(stringResource(R.string.properties)) }, leadingIcon = { Icon(Icons.Filled.Info, null) },
                                    onClick = { menu = false; scope.launch { vm.currentFolderEntry()?.let { dialog = Dialog.Properties(listOf(it)) } } })
                            }
                        }
                    }
                )
            }
        },
        bottomBar = {
            val top = clips.firstOrNull()
            if (top != null && !state.selecting && state.error == null) PasteBar(top, clips.size, onPaste = vm::pasteLatest, onMore = { onOpenClipboard(state.path) })
        },
        floatingActionButton = {
            if (!state.selecting && state.error == null) Box {
                FloatingActionButton(onClick = { fabMenu = true }) { Icon(Icons.Filled.Add, stringResource(R.string.create)) }
                DropdownMenu(expanded = fabMenu, onDismissRequest = { fabMenu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.new_folder)) }, leadingIcon = { Icon(Icons.Filled.CreateNewFolder, null) },
                        onClick = { fabMenu = false; dialog = Dialog.NewFolder })
                    DropdownMenuItem(text = { Text(stringResource(R.string.new_file)) }, leadingIcon = { Icon(Icons.Filled.NoteAdd, null) },
                        onClick = { fabMenu = false; dialog = Dialog.NewFile })
                }
            }
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Breadcrumbs(state.path, onJump = vm::navigate, onCopy = { copyToClipboard(context, it.path) })
            HorizontalDivider()
            PullToRefreshBox(isRefreshing = state.loading && state.entries.isNotEmpty(), onRefresh = vm::refresh, modifier = Modifier.fillMaxSize()) {
                when {
                    state.error is BrowserError.Restricted -> RestrictedCard(
                        onShizuku = vm::useShizuku, onRoot = vm::useRoot,
                        onSaf = if (StorageAccess.canGrantAndroidDataViaSaf) ({
                            val isObb = state.path.path.contains("/Android/obb")
                            pendingKind = if (isObb) SafRootKind.ANDROID_OBB else SafRootKind.ANDROID_DATA
                            treeLauncher.launch(SafRootStore.initialUri("primary", if (isObb) "Android/obb" else "Android/data"))
                        }) else null
                    )
                    state.error is BrowserError.SafGrant -> {
                        val e = state.error as BrowserError.SafGrant
                        EmptyState(Icons.Filled.Lock, stringResource(R.string.sd_grant_title), stringResource(R.string.sd_grant_body)) {
                            Button(onClick = {
                                pendingKind = SafRootKind.SD_CARD
                                treeLauncher.launch(if (Build.VERSION.SDK_INT >= 26 && e.volumeUuid != null) DocumentsContract.buildRootUri(SafRootStore.EXTERNAL_STORAGE_AUTHORITY, e.volumeUuid) else null)
                            }) { Text(stringResource(R.string.grant_access)) }
                        }
                    }
                    state.error is BrowserError.Message -> {
                        val e = state.error as BrowserError.Message
                        EmptyState(Icons.Filled.FolderOff, e.text) { OutlinedButton(onClick = vm::refresh) { Text(stringResource(R.string.retry)) } }
                    }
                    state.loading && state.entries.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    state.entries.isEmpty() -> EmptyState(Icons.Filled.FolderOff, stringResource(R.string.empty_folder))
                    settings.viewMode == ViewMode.GRID -> LazyVerticalGrid(GridCells.Fixed(settings.gridSize), Modifier.fillMaxSize()) {
                        items(state.entries, key = { it.path.toString() }) { e ->
                            FileGridCell(e, e.path in state.selection, settings.showExtensions, settings.thumbnails,
                                onClick = { vm.open(e) }, onLongClick = { if (state.selecting) vm.selectRange(e) else vm.toggle(e) })
                        }
                    }
                    else -> {
                        val listState = rememberLazyListState()
                        LazyColumn(Modifier.fillMaxSize(), state = listState) {
                            items(state.entries, key = { it.path.toString() }) { e ->
                                FileListRow(e, e.path in state.selection, settings.showExtensions, settings.thumbnails,
                                    onClick = { vm.open(e) }, onLongClick = { if (state.selecting) vm.selectRange(e) else vm.toggle(e) },
                                    onThumbLongPress = { if (state.selecting) vm.selectRange(e) else vm.toggle(e) })
                            }
                        }
                    }
                }
            }
        }
    }

    when (val d = dialog) {
        Dialog.NewFolder -> TextInputDialog(stringResource(R.string.new_folder), "", selectBaseName = false, onDismiss = { dialog = null }) { vm.newFolder(it); dialog = null }
        Dialog.NewFile -> TextInputDialog(stringResource(R.string.new_file), "", selectBaseName = false, onDismiss = { dialog = null }) { vm.newFile(it); dialog = null }
        is Dialog.Rename -> TextInputDialog(stringResource(R.string.rename), d.entry.name, onDismiss = { dialog = null }) { vm.rename(d.entry, it); dialog = null }
        is Dialog.Delete -> if (settings.confirmDelete) DeleteDialog(d.entries, settings.recycleBinEnabled, onDismiss = { dialog = null }) { permanent ->
            dialog = null; vm.delete(d.entries, permanent)
        } else { LaunchedEffect(d) { dialog = null; vm.delete(d.entries, false) } }
        is Dialog.Properties -> PropertiesDialog(d.entries, onDismiss = { dialog = null })
        is Dialog.Actions -> ActionSheet(
            title = if (d.entries.size == 1) d.entries.first().name else "${d.entries.size} items",
            actions = entryActions(d.entries, vm, onRename = { dialog = Dialog.Rename(it) }, onProperties = { dialog = Dialog.Properties(it) },
                onCopyPath = { copyToClipboard(context, d.entries.joinToString("\n") { it.path.toString().removePrefix("file://") }) }),
            onDismiss = { if (dialog is Dialog.Actions) dialog = null }
        )
        Dialog.EditPath -> TextInputDialog(stringResource(R.string.edit_path), state.path.toString().removePrefix("file://"), selectBaseName = false,
            validator = { null }, onDismiss = { dialog = null }) { vm.navigate(FsPath.parse(it)); dialog = null }
        null -> Unit
    }
}

private fun sortLabel(by: SortBy) = when (by) {
    SortBy.NAME -> R.string.sort_name
    SortBy.SIZE -> R.string.sort_size
    SortBy.DATE -> R.string.sort_date
    SortBy.TYPE -> R.string.sort_type
}

fun copyToClipboard(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("path", text))
    if (Build.VERSION.SDK_INT < 33) Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Breadcrumbs(path: FsPath, onJump: (FsPath) -> Unit, onCopy: (FsPath) -> Unit) {
    val scroll = rememberScrollState()
    LaunchedEffect(path) { scroll.animateScrollTo(scroll.maxValue) }
    Row(Modifier.fillMaxWidth().horizontalScroll(scroll).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        val segs = path.segments()
        segs.forEachIndexed { i, seg ->
            val label = if (seg.isRoot) (if (path.scheme == FsPath.SCHEME_FILE) "/" else path.scheme.uppercase()) else seg.name
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = if (i == segs.lastIndex) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .combinedClickable(onClick = { onJump(seg) }, onLongClick = { onCopy(seg) })
                    .padding(horizontal = 6.dp, vertical = 6.dp)
            )
            if (i < segs.lastIndex) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
private fun RestrictedCard(onShizuku: () -> Unit, onRoot: () -> Unit, onSaf: (() -> Unit)?) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Card(shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(20.dp)) {
                Icon(Icons.Filled.Lock, null, tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.restricted_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                Text(stringResource(R.string.restricted_body), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                if (onSaf != null) Button(onClick = onSaf, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) { Text(stringResource(R.string.grant_with_picker)) }
                OutlinedButton(onClick = onShizuku, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text(stringResource(R.string.use_shizuku)) }
                OutlinedButton(onClick = onRoot, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text(stringResource(R.string.use_root)) }
            }
        }
    }
}
