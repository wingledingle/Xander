package com.xander.files.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xander.files.R
import com.xander.files.data.fs.shizuku.ShizukuState
import com.xander.files.data.storage.VolumeInfo
import com.xander.files.data.storage.VolumeKind
import com.xander.files.domain.fs.FsPath
import com.xander.files.ui.common.Format
import com.xander.files.ui.navigation.Routes
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Cloud

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(onOpen: (FsPath) -> Unit, onOpenFile: (com.xander.files.domain.fs.FileEntry) -> Unit = {}, onSearch: () -> Unit, onMenu: () -> Unit = {}, onRoute: (String) -> Unit = {}, vm: HomeViewModel = hiltViewModel()) {
    val volumes by vm.volumeList.collectAsStateWithLifecycle()
    val shizuku by vm.shizukuState.collectAsStateWithLifecycle()
    val rootMode by vm.rootMode.collectAsStateWithLifecycle()
    val stats by vm.stats.collectAsStateWithLifecycle()
    val fileCount by vm.fileCount.collectAsStateWithLifecycle()
    val appCount by vm.appCount.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    val recentDays by vm.recentDays.collectAsStateWithLifecycle()
    val index by vm.indexStatus.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) vm.refresh() }
        lifecycle.addObserver(o); onDispose { lifecycle.removeObserver(o) }
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.app_name)) },
            navigationIcon = { IconButton(onClick = onMenu) { Icon(Icons.Filled.Menu, stringResource(R.string.menu)) } },
            actions = {
            IconButton(onClick = onSearch) { Icon(Icons.Filled.Search, stringResource(R.string.search)) }
            IconButton(onClick = { onRoute(Routes.SETTINGS) }) { Icon(Icons.Filled.Settings, stringResource(R.string.settings)) }
        })
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            volumes.forEach { v -> VolumeCard(v) { onOpen(v.root) } }
            Text(
                stringResource(R.string.files_and_apps, fileCount, appCount) + if (index.running) " · " + stringResource(R.string.indexing) else "",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            CategoryGrid(stats, appCount, onOpen = { onRoute(Routes.library(it)) }, onApps = { onRoute(Routes.APPS) })
            RecentStrip(recent, recentDays, vm::setRecentDays, onOpen = onOpenFile, onMore = { onRoute(Routes.library(com.xander.files.data.index.LibraryKind.RECENT)) })

            Text(stringResource(R.string.quick_access), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            val p = vm.primaryRoot
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Shortcut(Icons.Filled.Delete, stringResource(R.string.recycle_bin)) { onRoute(Routes.RECYCLE) }
                Shortcut(Icons.Filled.PieChart, stringResource(R.string.analyzer)) { onRoute(Routes.ANALYZER) }
                Shortcut(Icons.Filled.Star, stringResource(R.string.favorites)) { onRoute(Routes.BOOKMARKS) }
                Shortcut(Icons.Filled.ContentPaste, stringResource(R.string.clipboard)) { onRoute(Routes.CLIPBOARD_ROUTE) }
                Shortcut(Icons.Filled.SwapVert, stringResource(R.string.transfers)) { onRoute(Routes.TRANSFERS) }
                Shortcut(Icons.Filled.Lan, stringResource(R.string.network)) { onRoute(Routes.NETWORK) }
                Shortcut(Icons.Filled.Cloud, stringResource(R.string.cloud)) { onRoute(Routes.CLOUD) }
                Shortcut(Icons.Filled.Download, stringResource(R.string.downloads)) { onOpen(FsPath.local("$p/Download")) }
                Shortcut(Icons.Filled.PhotoCamera, "DCIM") { onOpen(FsPath.local("$p/DCIM")) }
                Shortcut(Icons.Filled.FolderSpecial, "Android/data") { onOpen(FsPath.local("$p/Android/data")) }
                Shortcut(Icons.Filled.Folder, "Android/obb") { onOpen(FsPath.local("$p/Android/obb")) }
                Shortcut(Icons.Filled.AdminPanelSettings, stringResource(R.string.root_folder)) {
                    onOpen(if (rootMode) FsPath(FsPath.SCHEME_ROOT, "", "/") else FsPath.local("/"))
                }
            }

            Text(stringResource(R.string.privileged_access), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            Card(shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable(enabled = shizuku == ShizukuState.PERMISSION_NEEDED) { vm.requestShizuku() }) {
                        Icon(Icons.Filled.Security, null)
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text("Shizuku", style = MaterialTheme.typography.bodyLarge)
                            Text(stringResource(shizukuLabel(shizuku)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.AdminPanelSettings, null)
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(stringResource(R.string.root_mode), style = MaterialTheme.typography.bodyLarge)
                            Text(stringResource(R.string.root_mode_summary), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = rootMode, onCheckedChange = { vm.toggleRoot() })
                    }
                }
            }
        }
    }
}

private fun shizukuLabel(s: ShizukuState) = when (s) {
    ShizukuState.READY -> R.string.shizuku_ready
    ShizukuState.PERMISSION_NEEDED -> R.string.shizuku_permission
    ShizukuState.NOT_RUNNING -> R.string.shizuku_not_running
    ShizukuState.NOT_INSTALLED -> R.string.shizuku_not_installed
    ShizukuState.UNSUPPORTED -> R.string.shizuku_unsupported
}

@Composable
fun VolumeCard(v: VolumeInfo, onClick: () -> Unit) {
    Card(onClick = onClick, shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(when (v.kind) { VolumeKind.INTERNAL -> Icons.Filled.PhoneAndroid; VolumeKind.SD_CARD -> Icons.Filled.SdCard; VolumeKind.USB -> Icons.Filled.Usb },
                null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f).padding(start = 16.dp)) {
                Text(v.label, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(progress = { v.fraction }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(4.dp))
                Text("${Format.size(v.used)} / ${Format.size(v.total)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Shortcut(icon: ImageVector, label: String, onClick: () -> Unit) {
    Card(onClick = onClick, shape = RoundedCornerShape(12.dp), modifier = Modifier.width(104.dp)) {
        Column(Modifier.padding(12.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, modifier = Modifier.padding(top = 6.dp))
        }
    }
}
