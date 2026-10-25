package com.xander.files.ui.apps

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import com.xander.files.R
import com.xander.files.data.apps.AppInfo
import com.xander.files.data.apps.AppRepository
import com.xander.files.data.storage.StorageVolumes
import com.xander.files.data.thumbs.AppIconModel
import com.xander.files.domain.fs.FsPath
import com.xander.files.ui.common.Format
import com.xander.files.ui.ops.ActionSheet
import com.xander.files.ui.ops.SheetAction
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class AppSort { NAME, SIZE, INSTALLED, UPDATED }

@HiltViewModel
class AppsViewModel @Inject constructor(
    private val repo: AppRepository,
    private val volumes: StorageVolumes,
) : ViewModel() {
    private val all = MutableStateFlow<List<AppInfo>>(emptyList())
    val loading = MutableStateFlow(true)
    val system = MutableStateFlow(false)
    val query = MutableStateFlow("")
    val sort = MutableStateFlow(AppSort.NAME)
    val selection = MutableStateFlow<Set<String>>(emptySet())
    val events = Channel<Any>(Channel.BUFFERED)
    /** Uninstall requests are launched one at a time by the screen. */
    val uninstallQueue = MutableStateFlow<List<String>>(emptyList())

    val visible = combine(all, system, query, sort) { list, sys, q, s ->
        list.filter { it.isSystem == sys && (q.isBlank() || it.label.contains(q, true) || it.packageName.contains(q, true)) }
            .let { l ->
                when (s) {
                    AppSort.NAME -> l.sortedBy { it.label.lowercase() }
                    AppSort.SIZE -> l.sortedByDescending { it.totalSize ?: it.apkSize }
                    AppSort.INSTALLED -> l.sortedByDescending { it.installed }
                    AppSort.UPDATED -> l.sortedByDescending { it.updated }
                }
            }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val backupDir: FsPath get() = FsPath.local(volumes.primaryRoot + "/XanderFiles/Apps")
    val hasUsageAccess get() = repo.hasUsageAccess()

    init { reload() }

    fun reload() = viewModelScope.launch { loading.value = true; all.value = repo.list(); loading.value = false }

    fun toggle(pkg: String) { selection.value = selection.value.let { if (pkg in it) it - pkg else it + pkg } }
    fun selectAll() { selection.value = visible.value.map { it.packageName }.toSet() }
    fun clear() { selection.value = emptySet() }

    fun backup(apps: List<AppInfo>) = viewModelScope.launch {
        var ok = 0
        for (a in apps) runCatching { repo.backup(a, backupDir) }.onSuccess { ok++ }.onFailure { events.send("${a.label}: ${it.message}") }
        events.send("Backed up $ok app(s) to ${backupDir.path}")
        clear()
    }

    fun uninstall(pkgs: List<String>) { uninstallQueue.value = pkgs; clear() }
    fun nextUninstall(): String? = uninstallQueue.value.firstOrNull()?.also { uninstallQueue.value = uninstallQueue.value.drop(1) }

    fun share(a: AppInfo) = viewModelScope.launch { runCatching { events.send(repo.shareIntent(a)) }.onFailure { events.send(it.message ?: "") } }
    fun launch(a: AppInfo) = viewModelScope.launch { repo.launchIntent(a.packageName)?.let { events.send(it) } ?: events.send("This app has no launcher screen") }
    fun info(a: AppInfo) = viewModelScope.launch { events.send(repo.appInfoIntent(a.packageName)) }
    fun store(a: AppInfo) = viewModelScope.launch { events.send(repo.playStoreIntent(a.packageName)) }
    fun requestUsageAccess() = viewModelScope.launch { events.send(repo.usageAccessIntent()) }
    fun uninstallIntent(pkg: String) = repo.uninstallIntent(pkg)
}

@Composable
fun AppsScreen(onBack: () -> Unit, vm: AppsViewModel = hiltViewModel()) {
    val apps by vm.visible.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val system by vm.system.collectAsStateWithLifecycle()
    val q by vm.query.collectAsStateWithLifecycle()
    val sel by vm.selection.collectAsStateWithLifecycle()
    val queue by vm.uninstallQueue.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var sortMenu by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var sheetFor by remember { mutableStateOf<AppInfo?>(null) }

    val uninstallLauncher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) {
        vm.reload()
    }
    LaunchedEffect(queue) { vm.nextUninstall()?.let { uninstallLauncher.launch(vm.uninstallIntent(it)) } }
    LaunchedEffect(Unit) {
        vm.events.receiveAsFlow().collect { e ->
            when (e) {
                is Intent -> runCatching { ctx.startActivity(e) }
                is String -> Toast.makeText(ctx, e, Toast.LENGTH_LONG).show()
            }
        }
    }
    BackHandler(enabled = sel.isNotEmpty()) { vm.clear() }

    Scaffold(topBar = {
        Column {
            if (sel.isNotEmpty()) TopAppBar(
                navigationIcon = { IconButton(onClick = vm::clear) { Icon(Icons.Filled.Close, null) } },
                title = { Text("${sel.size}") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                actions = {
                    IconButton(onClick = vm::selectAll) { Icon(Icons.Filled.SelectAll, stringResource(R.string.select_all)) }
                    IconButton(onClick = { vm.backup(apps.filter { it.packageName in sel }) }) { Icon(Icons.Filled.Archive, stringResource(R.string.backup_apk)) }
                    IconButton(onClick = { vm.uninstall(sel.toList()) }) { Icon(Icons.Filled.Delete, stringResource(R.string.uninstall)) }
                }
            ) else TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                title = {
                    if (searching) OutlinedTextField(q, { vm.query.value = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), placeholder = { Text(stringResource(R.string.search)) })
                    else Text(stringResource(R.string.app_manager))
                },
                actions = {
                    IconButton(onClick = { searching = !searching; if (!searching) vm.query.value = "" }) { Icon(Icons.Filled.Search, stringResource(R.string.search)) }
                    Box {
                        IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Filled.Sort, stringResource(R.string.sort)) }
                        DropdownMenu(sortMenu, { sortMenu = false }) {
                            listOf(AppSort.NAME to R.string.sort_name, AppSort.SIZE to R.string.sort_size, AppSort.INSTALLED to R.string.sort_installed, AppSort.UPDATED to R.string.sort_updated)
                                .forEach { (s, l) -> DropdownMenuItem(text = { Text(stringResource(l)) }, onClick = { sortMenu = false; vm.sort.value = s }) }
                        }
                    }
                }
            )
            PrimaryTabRow(selectedTabIndex = if (system) 1 else 0) {
                Tab(!system, { vm.system.value = false }, text = { Text(stringResource(R.string.user_apps)) })
                Tab(system, { vm.system.value = true }, text = { Text(stringResource(R.string.system_apps)) })
            }
        }
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (!vm.hasUsageAccess) TextButton(onClick = vm::requestUsageAccess, modifier = Modifier.padding(horizontal = 8.dp)) { Text(stringResource(R.string.usage_access_hint)) }
            if (loading && apps.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else LazyColumn(Modifier.fillMaxSize()) {
                items(apps, key = { it.packageName }) { a ->
                    Row(
                        Modifier.fillMaxWidth().combinedClickable(onClick = { if (sel.isNotEmpty()) vm.toggle(a.packageName) else sheetFor = a }, onLongClick = { vm.toggle(a.packageName) })
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box {
                            AsyncImage(AppIconModel(a.packageName), null, Modifier.size(44.dp))
                            if (a.packageName in sel) Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.align(Alignment.BottomEnd).size(18.dp))
                        }
                        Column(Modifier.weight(1f).padding(start = 14.dp)) {
                            Text(a.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                            Text("${a.versionName} · ${Format.size(a.totalSize ?: a.apkSize)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(stringResource(R.string.installed_updated, Format.shortDate(a.installed), Format.shortDate(a.updated)), style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
    sheetFor?.let { a ->
        ActionSheet(a.label + " (" + a.packageName + ")", listOf(
            SheetAction(Icons.AutoMirrored.Filled.OpenInNew, stringResource(R.string.open)) { vm.launch(a) },
            SheetAction(Icons.Filled.Info, stringResource(R.string.app_info)) { vm.info(a) },
            SheetAction(Icons.Filled.Shop, stringResource(R.string.play_store)) { vm.store(a) },
            SheetAction(Icons.Filled.Share, stringResource(R.string.share_apk)) { vm.share(a) },
            SheetAction(Icons.Filled.Archive, stringResource(R.string.backup_apk)) { vm.backup(listOf(a)) },
            SheetAction(Icons.Filled.Delete, stringResource(R.string.uninstall)) { vm.uninstall(listOf(a.packageName)) },
        ), onDismiss = { sheetFor = null })
    }
}
