package com.xander.files.ui.network

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.xander.files.R
import com.xander.files.data.net.ConnType
import com.xander.files.data.net.Connection
import com.xander.files.data.net.ConnectionRepository
import com.xander.files.data.net.LanHost
import com.xander.files.data.net.LanScanner
import com.xander.files.domain.fs.FsPath
import com.xander.files.ui.common.ConfirmDialog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class NetworkViewModel @Inject constructor(
    private val repo: ConnectionRepository,
    private val scanner: LanScanner,
) : ViewModel() {
    val connections = repo.all.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val hosts = MutableStateFlow<List<LanHost>>(emptyList())
    val scanning = MutableStateFlow(false)
    private var job: Job? = null

    val localIp get() = scanner.localIp()

    fun scan() {
        job?.cancel()
        hosts.value = emptyList(); scanning.value = true
        job = viewModelScope.launch {
            try {
                scanner.scan().collect { h ->
                    val cur = hosts.value.toMutableList()
                    val i = cur.indexOfFirst { it.ip == h.ip }
                    if (i >= 0) cur[i] = cur[i].copy(name = cur[i].name ?: h.name, services = cur[i].services + h.services, ports = cur[i].ports + h.ports)
                    else cur += h
                    hosts.value = cur.sortedBy { it.ip.split('.').map { p -> p.toInt() }.fold(0L) { a, b -> a * 256 + b } }
                }
            } finally { scanning.value = false }
        }
    }

    fun delete(c: Connection) = viewModelScope.launch { repo.delete(c.id) }
}

/** LAN + remote servers: saved connections, LAN discovery and "add" entry points (RS's LAN / Network screens). */
@Composable
fun NetworkScreen(
    onBack: () -> Unit,
    onBrowse: (FsPath) -> Unit,
    onEdit: (id: String?, type: ConnType?, host: String?, port: Int?) -> Unit,
    onCloud: () -> Unit,
    vm: NetworkViewModel = hiltViewModel(),
) {
    val conns by vm.connections.collectAsStateWithLifecycle()
    val hosts by vm.hosts.collectAsStateWithLifecycle()
    val scanning by vm.scanning.collectAsStateWithLifecycle()
    var addMenu by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Connection?>(null) }
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                title = { Text(stringResource(R.string.network)) },
                actions = {
                    IconButton(onClick = vm::scan) { Icon(Icons.Filled.Radar, stringResource(R.string.scan_lan)) }
                    IconButton(onClick = onCloud) { Icon(Icons.Filled.Cloud, stringResource(R.string.cloud)) }
                }
            )
        },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End) {
                DropdownMenu(addMenu, { addMenu = false }) {
                    ConnType.entries.forEach { t -> DropdownMenuItem(text = { Text(typeLabel(t)) }, onClick = { addMenu = false; onEdit(null, t, null, null) }) }
                }
                ExtendedFloatingActionButton(onClick = { addMenu = true }, icon = { Icon(Icons.Filled.Add, null) }, text = { Text(stringResource(R.string.add_connection)) })
            }
        }
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            item { Header(stringResource(R.string.saved_connections)) }
            if (conns.isEmpty()) item { Text(stringResource(R.string.no_connections), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(conns, key = { it.id }) { c ->
                Row(Modifier.fillMaxWidth().clickable { onBrowse(c.root) }.padding(start = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (c.type == ConnType.SMB || c.type == ConnType.NFS) Icons.Filled.Lan else Icons.Filled.Storage, null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f).padding(start = 16.dp)) {
                        Text(c.name, style = MaterialTheme.typography.bodyLarge)
                        Text(c.displayUrl, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    IconButton(onClick = { onEdit(c.id, null, null, null) }) { Icon(Icons.Filled.Edit, stringResource(R.string.edit)) }
                    IconButton(onClick = { deleting = c }) { Icon(Icons.Filled.Delete, stringResource(R.string.delete)) }
                }
            }
            item {
                HorizontalDivider(Modifier.padding(top = 8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Header(stringResource(R.string.lan_devices) + (vm.localIp?.let { " · $it" } ?: ""))
                    TextButton(onClick = vm::scan) { Text(stringResource(if (scanning) R.string.rescan else R.string.scan_lan)) }
                }
                if (scanning) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                if (!scanning && hosts.isEmpty()) Text(stringResource(R.string.scan_hint), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(hosts, key = { it.ip }) { h ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Computer, null)
                        Column(Modifier.padding(start = 16.dp)) {
                            Text(h.name ?: h.ip, style = MaterialTheme.typography.bodyLarge)
                            if (h.name != null) Text(h.ip, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Row(Modifier.padding(start = 40.dp, top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        h.services.forEach { t -> AssistChip(onClick = { onEdit(null, t, h.ip, h.ports[t]) }, label = { Text(typeLabel(t)) }) }
                    }
                }
            }
            item { Column(Modifier.padding(48.dp)) {} }
        }
    }
    deleting?.let { c ->
        ConfirmDialog(stringResource(R.string.delete), c.name, stringResource(R.string.delete), onDismiss = { deleting = null }, onConfirm = { vm.delete(c); deleting = null })
    }
}

@Composable
private fun Header(t: String) = Text(t, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(16.dp))

fun typeLabel(t: ConnType) = when (t) {
    ConnType.SMB -> "SMB"; ConnType.FTP -> "FTP"; ConnType.FTPS -> "FTPS"; ConnType.SFTP -> "SFTP"
    ConnType.WEBDAV -> "WebDAV"; ConnType.NFS -> "NFS"; ConnType.HTTP -> "HTTP"
}
