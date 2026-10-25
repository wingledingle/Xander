package com.xander.files.ui.analyzer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.xander.files.R
import com.xander.files.data.analyzer.AnalysisResult
import com.xander.files.data.analyzer.JunkKind
import com.xander.files.data.analyzer.StorageAnalyzer
import com.xander.files.data.settings.SettingsRepository
import com.xander.files.data.storage.StorageVolumes
import com.xander.files.data.storage.VolumeInfo
import com.xander.files.data.transfer.TransferManager
import com.xander.files.data.transfer.TransferRequest
import com.xander.files.data.transfer.TransferType
import com.xander.files.domain.fs.FileCategory
import com.xander.files.domain.fs.FileEntry
import com.xander.files.domain.fs.FsPath
import com.xander.files.ui.common.CategoryIcon
import com.xander.files.ui.common.ConfirmDialog
import com.xander.files.ui.common.FileIcons
import com.xander.files.ui.common.Format
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class AnalyzerTab(val label: Int) {
    OVERVIEW(R.string.an_overview), LARGE(R.string.an_large_files), FOLDERS(R.string.an_folders), DUPLICATES(R.string.an_duplicates),
    JUNK(R.string.an_junk), EMPTY(R.string.an_empty_folders), APKS(R.string.an_old_apks), RECENT(R.string.an_recent),
}

@HiltViewModel
class AnalyzerViewModel @Inject constructor(
    private val analyzer: StorageAnalyzer,
    private val storage: StorageVolumes,
    private val transfers: TransferManager,
    private val settings: SettingsRepository,
) : ViewModel() {
    val result = MutableStateFlow(analyzer.last)
    val progress = analyzer.progress
    val running = MutableStateFlow(false)
    val volumes = MutableStateFlow<List<VolumeInfo>>(emptyList())
    val root = MutableStateFlow<FsPath?>(analyzer.last?.root)
    val selection = MutableStateFlow<Set<FsPath>>(emptySet())
    val folder = MutableStateFlow<FsPath?>(null)
    private var job: Job? = null

    init {
        viewModelScope.launch(Dispatchers.IO) {
            volumes.value = storage.volumes()
            if (root.value == null) { root.value = volumes.value.firstOrNull()?.root; scan() }
        }
    }

    fun scan() {
        val r = root.value ?: return
        job?.cancel()
        job = viewModelScope.launch {
            running.value = true; selection.value = emptySet()
            try { result.value = analyzer.analyze(r); folder.value = r } finally { running.value = false }
        }
    }

    fun setRoot(p: FsPath) { root.value = p; scan() }
    fun toggle(p: FsPath) { selection.value = selection.value.let { if (p in it) it - p else it + p } }
    fun selectAll(paths: List<FsPath>) { selection.value = selection.value + paths }
    fun clear() { selection.value = emptySet() }
    fun openFolder(p: FsPath?) { folder.value = p }

    /** Deletes through the transfer engine and removes the items from the current views. */
    fun deleteSelected(permanent: Boolean) = viewModelScope.launch {
        val sel = selection.value.toList(); if (sel.isEmpty()) return@launch
        val toBin = !permanent && settings.settings.first().recycleBinEnabled
        transfers.enqueue(TransferRequest(if (toBin) TransferType.RECYCLE else TransferType.DELETE, sel.map { it.toString() }, null, "Cleaning ${sel.size} item(s)"))
        val gone = sel.toSet()
        result.value = result.value?.let { r ->
            r.copy(
                largest = r.largest.filter { it.path !in gone }, recent = r.recent.filter { it.path !in gone },
                duplicates = r.duplicates.map { g -> g.copy(files = g.files.filter { it.path !in gone }) }.filter { it.files.size > 1 },
                emptyFolders = r.emptyFolders.filter { it.path !in gone }, junk = r.junk.filter { it.entry.path !in gone },
                oldApks = r.oldApks.filter { it.entry.path !in gone },
                archiveCopies = r.archiveCopies.filter { it.archive.path !in gone && it.folder !in gone },
            )
        }
        selection.value = emptySet()
    }
}

@Composable
fun AnalyzerScreen(onBack: () -> Unit, onOpenFolder: (FsPath) -> Unit, vm: AnalyzerViewModel = hiltViewModel()) {
    val r by vm.result.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val running by vm.running.collectAsStateWithLifecycle()
    val sel by vm.selection.collectAsStateWithLifecycle()
    val volumes by vm.volumes.collectAsStateWithLifecycle()
    val root by vm.root.collectAsStateWithLifecycle()
    var tab by remember { mutableStateOf(AnalyzerTab.OVERVIEW) }
    var confirm by remember { mutableStateOf(false) }
    Scaffold(topBar = {
        TopAppBar(
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            title = { Text(if (sel.isEmpty()) stringResource(R.string.analyzer) else "${sel.size}") },
            actions = {
                if (sel.isNotEmpty()) IconButton(onClick = { confirm = true }) { Icon(Icons.Filled.Delete, stringResource(R.string.delete)) }
                IconButton(onClick = vm::scan, enabled = !running) { Icon(Icons.Filled.Refresh, stringResource(R.string.rescan)) }
            }
        )
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                volumes.forEach { v -> FilterChip(root == v.root, { vm.setRoot(v.root) }, label = { Text(v.label) }) }
            }
            if (running) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                progress?.let { p ->
                    Text(if (p.phase == "scan") stringResource(R.string.an_scanning, p.files, Format.size(p.bytes)) else stringResource(R.string.an_hashing, p.files, p.bytes),
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp, 4.dp))
                    p.current?.let { Text(it, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.MiddleEllipsis, modifier = Modifier.padding(horizontal = 16.dp)) }
                }
            }
            val res = r
            if (res == null) { if (!running) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Button(onClick = vm::scan) { Text(stringResource(R.string.an_start)) } }; return@Column }
            ScrollableTabRow(selectedTabIndex = tab.ordinal, edgePadding = 8.dp) {
                AnalyzerTab.entries.forEach { t -> Tab(tab == t, { tab = t; vm.clear() }, text = { Text(stringResource(t.label)) }) }
            }
            when (tab) {
                AnalyzerTab.OVERVIEW -> Overview(res)
                AnalyzerTab.LARGE -> FileList(res.largest, sel, vm, onOpenFolder)
                AnalyzerTab.FOLDERS -> FolderTree(res, vm, onOpenFolder)
                AnalyzerTab.RECENT -> FileList(res.recent, sel, vm, onOpenFolder)
                AnalyzerTab.EMPTY -> FileList(res.emptyFolders, sel, vm, onOpenFolder)
                AnalyzerTab.APKS -> FileList(res.oldApks.map { it.entry }, sel, vm, onOpenFolder) { e ->
                    res.oldApks.firstOrNull { it.entry.path == e.path }?.let { "${it.packageName} ${it.versionName} · ${if (it.reason == "installed") stringResource(R.string.an_already_installed) else stringResource(R.string.an_newer_copy)}" }
                }
                AnalyzerTab.JUNK -> FileList(res.junk.map { it.entry.copy(size = it.size) }, sel, vm, onOpenFolder) { e ->
                    res.junk.firstOrNull { it.entry.path == e.path }?.let { j -> stringResource(junkLabel(j.kind)) + (j.note?.let { " · $it" } ?: "") }
                }
                AnalyzerTab.DUPLICATES -> Duplicates(res, sel, vm)
            }
        }
    }
    if (confirm) ConfirmDialog(stringResource(R.string.delete), stringResource(R.string.an_delete_confirm, sel.size), stringResource(R.string.delete),
        onDismiss = { confirm = false }, onConfirm = { confirm = false; vm.deleteSelected(false) })
}

@Composable
private fun Overview(r: AnalysisResult) {
    LazyColumn(Modifier.fillMaxSize().padding(16.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val used = r.volumeTotal - r.volumeFree
                val catColors = r.categories.map { FileIcons.color(it.category) to it.size }
                val surface = MaterialTheme.colorScheme.surfaceVariant
                Canvas(Modifier.size(140.dp)) {
                    val stroke = 28f
                    val box = Size(size.width - stroke, size.height - stroke)
                    drawArc(surface, 0f, 360f, false, Offset(stroke / 2, stroke / 2), box, style = Stroke(stroke))
                    var start = -90f
                    val total = if (r.volumeTotal > 0) r.volumeTotal.toFloat() else r.totalBytes.toFloat().coerceAtLeast(1f)
                    catColors.forEach { (c, s) -> val sweep = 360f * s / total; drawArc(c, start, sweep, false, Offset(stroke / 2, stroke / 2), box, style = Stroke(stroke)); start += sweep }
                    if (r.volumeTotal > 0) {
                        val other = (used - r.totalBytes).coerceAtLeast(0)
                        drawArc(androidx.compose.ui.graphics.Color.Gray, start, 360f * other / total, false, Offset(stroke / 2, stroke / 2), box, style = Stroke(stroke))
                    }
                }
                Column(Modifier.padding(start = 16.dp)) {
                    Text(Format.size(r.totalBytes), style = MaterialTheme.typography.headlineSmall)
                    Text(stringResource(R.string.an_files_folders, r.files, r.folders), style = MaterialTheme.typography.bodySmall)
                    if (r.volumeTotal > 0) Text(stringResource(R.string.an_free_of, Format.size(r.volumeFree), Format.size(r.volumeTotal)), style = MaterialTheme.typography.bodySmall)
                    val dupWaste = r.duplicates.sumOf { it.wasted } + r.archiveCopies.sumOf { it.wasted }; val junk = r.junk.sumOf { it.size }
                    Text(stringResource(R.string.an_reclaimable, Format.size(dupWaste + junk)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        items(r.categories) { c ->
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                CategoryIcon(c.category, 36)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text("${c.category.name.lowercase().replaceFirstChar { it.uppercase() }} · ${c.count}", style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(progress = { if (r.totalBytes > 0) c.size.toFloat() / r.totalBytes else 0f }, modifier = Modifier.fillMaxWidth().height(6.dp).padding(top = 4.dp),
                        color = FileIcons.color(c.category))
                }
                Text(Format.size(c.size), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 12.dp))
            }
        }
    }
}

@Composable
private fun FileList(items: List<FileEntry>, sel: Set<FsPath>, vm: AnalyzerViewModel, onOpenFolder: (FsPath) -> Unit, note: @Composable (FileEntry) -> String? = { null }) {
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.an_items_total, items.size, Format.size(items.sumOf { it.size })), style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f).padding(start = 8.dp))
            TextButton(onClick = { vm.selectAll(items.map { it.path }) }) { Icon(Icons.Filled.SelectAll, null); Text(" " + stringResource(R.string.select_all)) }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(items, key = { it.path.toString() }) { e ->
                Row(Modifier.fillMaxWidth().clickable { vm.toggle(e.path) }.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(e.path in sel, { vm.toggle(e.path) })
                    CategoryIcon(if (e.isDirectory) FileCategory.FOLDER else e.category, 36)
                    Column(Modifier.weight(1f).padding(start = 12.dp).clickable { e.path.parent?.let(onOpenFolder) }) {
                        Text(e.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                        Text(note(e) ?: e.path.parent?.path.orEmpty(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                    }
                    Text(Format.size(e.size), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun FolderTree(r: AnalysisResult, vm: AnalyzerViewModel, onOpenFolder: (FsPath) -> Unit) {
    val current by vm.folder.collectAsStateWithLifecycle()
    val node = r.folderTree[current ?: r.root] ?: r.folderTree[r.root] ?: return
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (node.path != r.root) TextButton(onClick = { vm.openFolder(node.path.parent) }) { Text("↑ " + stringResource(R.string.up)) }
            Text(node.path.path, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.StartEllipsis, modifier = Modifier.weight(1f))
            TextButton(onClick = { onOpenFolder(node.path) }) { Text(stringResource(R.string.open)) }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(node.children, key = { it.toString() }) { child ->
                val c = r.folderTree[child] ?: return@items
                Row(Modifier.fillMaxWidth().clickable { vm.openFolder(child) }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    CategoryIcon(FileCategory.FOLDER, 36)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(child.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                        LinearProgressIndicator(progress = { if (node.size > 0) c.size.toFloat() / node.size else 0f }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                    }
                    Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = 12.dp)) {
                        Text(Format.size(c.size), style = MaterialTheme.typography.bodySmall)
                        Text("${c.files}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun Duplicates(r: AnalysisResult, sel: Set<FsPath>, vm: AnalyzerViewModel) {
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.an_dup_summary, r.duplicates.size, Format.size(r.duplicates.sumOf { it.wasted })), style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f).padding(start = 8.dp))
            // Keep the first copy in every group, select the rest.
            TextButton(onClick = { vm.selectAll(r.duplicates.flatMap { g -> g.files.drop(1).map { it.path } }) }) { Text(stringResource(R.string.an_select_copies)) }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            if (r.archiveCopies.isNotEmpty()) {
                item(key = "arch-h") {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.an_archive_copies, r.archiveCopies.size, Format.size(r.archiveCopies.sumOf { it.wasted })),
                            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                        TextButton(onClick = { vm.selectAll(r.archiveCopies.map { it.archive.path }) }) { Text(stringResource(R.string.an_select_archives)) }
                    }
                }
                items(r.archiveCopies, key = { "arch" + it.archive.path }) { c ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                        Row(Modifier.clickable { vm.toggle(c.archive.path) }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(c.archive.path in sel, { vm.toggle(c.archive.path) })
                            Column(Modifier.weight(1f)) {
                                Text(c.archive.name + " · " + Format.size(c.archive.size), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                                Text(c.archive.path.parent?.path.orEmpty(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                            }
                        }
                        Row(Modifier.clickable { vm.toggle(c.folder) }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(c.folder in sel, { vm.toggle(c.folder) })
                            Column(Modifier.weight(1f)) {
                                Text("📁 " + c.folder.name + " · " + Format.size(c.folderSize) + " · " + stringResource(R.string.an_extracted_match, c.matched, c.total), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                                Text(c.folder.path, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                            }
                        }
                    }
                }
            }
            r.duplicates.forEach { g ->
                item(key = "h" + g.hash) {
                    Text("${g.files.size} × ${Format.size(g.size)}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp, top = 12.dp))
                }
                items(g.files, key = { g.hash + it.path }) { e ->
                    Row(Modifier.fillMaxWidth().clickable { vm.toggle(e.path) }.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(e.path in sel, { vm.toggle(e.path) })
                        Column(Modifier.weight(1f)) {
                            Text(e.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                            Text(e.path.parent?.path.orEmpty(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                        }
                    }
                }
            }
        }
    }
}

private fun junkLabel(k: JunkKind) = when (k) {
    JunkKind.APP_CACHE -> R.string.junk_cache; JunkKind.THUMBNAILS -> R.string.junk_thumbs; JunkKind.TEMP -> R.string.junk_temp
    JunkKind.LOG -> R.string.junk_log; JunkKind.EMPTY_FILE -> R.string.junk_empty; JunkKind.LOST_DIR -> R.string.junk_lost; JunkKind.RESIDUAL -> R.string.junk_residual
}
