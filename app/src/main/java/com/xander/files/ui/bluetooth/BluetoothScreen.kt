package com.xander.files.ui.bluetooth

import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.xander.files.data.bluetooth.BluetoothRepository
import com.xander.files.data.bluetooth.BtDevice
import com.xander.files.domain.fs.FsPath
import com.xander.files.ui.common.Format
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BluetoothViewModel @Inject constructor(val repo: BluetoothRepository) : ViewModel() {
    val paired = MutableStateFlow<List<BtDevice>>(emptyList())
    val found = MutableStateFlow<List<BtDevice>>(emptyList())
    val scanning = MutableStateFlow(false)
    val enabled = MutableStateFlow(repo.enabled)
    val message = MutableStateFlow<String?>(null)
    val pairing = MutableStateFlow<String?>(null)
    private var scanJob: Job? = null

    init {
        viewModelScope.launch {
            repo.bondChanges().collect { (addr, state) ->
                if (state != BluetoothDevice.BOND_BONDING) pairing.value = null
                refresh()
                if (state == BluetoothDevice.BOND_BONDED) found.value = found.value.filter { it.address != addr }
            }
        }
    }

    fun refresh() { enabled.value = repo.enabled; if (repo.hasPermissions()) paired.value = repo.bonded() }

    fun scan() {
        scanJob?.cancel(); found.value = emptyList()
        scanJob = viewModelScope.launch {
            scanning.value = true
            try { repo.scan().collect { d -> if (!d.bonded && found.value.none { it.address == d.address }) found.value = (found.value + d).sortedBy { it.name.lowercase() } } }
            catch (e: Exception) { message.value = e.message }
            finally { scanning.value = false }
        }
    }

    fun pair(d: BtDevice) { pairing.value = d.address; if (!repo.pair(d.address)) { pairing.value = null; message.value = "Pairing could not start" } }
    fun unpair(d: BtDevice) { repo.unpair(d.address); refresh() }

    fun sendDirect(d: BtDevice, done: () -> Unit) = viewModelScope.launch {
        val files = repo.pendingSend.value
        repo.sendDirect(d.address, files).onSuccess { repo.pendingSend.value = emptyList(); done() }.onFailure { message.value = it.message }
    }

    suspend fun systemIntent() = repo.systemShareIntent(repo.pendingSend.value).also { repo.pendingSend.value = emptyList() }
}

@Composable
fun BluetoothScreen(onBack: () -> Unit, onOpenFolder: (FsPath) -> Unit, vm: BluetoothViewModel = hiltViewModel()) {
    val paired by vm.paired.collectAsStateWithLifecycle()
    val found by vm.found.collectAsStateWithLifecycle()
    val scanning by vm.scanning.collectAsStateWithLifecycle()
    val enabled by vm.enabled.collectAsStateWithLifecycle()
    val pending by vm.repo.pendingSend.collectAsStateWithLifecycle()
    val transfer by vm.repo.transfer.collectAsStateWithLifecycle()
    val receiving by vm.repo.receiving.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val pairing by vm.pairing.collectAsStateWithLifecycle()
    var target by remember { mutableStateOf<BtDevice?>(null) }
    var granted by remember { mutableStateOf(vm.repo.hasPermissions()) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current

    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted = vm.repo.hasPermissions(); vm.refresh() }
    val enable = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { vm.refresh() }
    val discoverable = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }
    LaunchedEffect(Unit) { if (!granted) perms.launch(vm.repo.requiredPermissions()) else vm.refresh() }

    Scaffold(topBar = {
        TopAppBar(navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            title = { Text(stringResource(R.string.bluetooth)) },
            actions = {
                if (enabled && granted) IconButton(onClick = { discoverable.launch(vm.repo.discoverableIntent()) }) { Icon(Icons.Filled.Visibility, stringResource(R.string.bt_discoverable)) }
                IconButton(onClick = { onOpenFolder(FsPath.local(vm.repo.receiveDir().path)) }) { Icon(Icons.Filled.FolderOpen, stringResource(R.string.bt_received)) }
            })
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            when {
                !vm.repo.supported -> { Text(stringResource(R.string.bt_unsupported), Modifier.padding(24.dp)); return@Column }
                !granted -> {
                    Column(Modifier.padding(24.dp)) {
                        Text(stringResource(R.string.bt_permission))
                        Button(onClick = { perms.launch(vm.repo.requiredPermissions()) }, Modifier.padding(top = 12.dp)) { Text(stringResource(R.string.grant)) }
                    }
                    return@Column
                }
                !enabled -> {
                    Column(Modifier.padding(24.dp)) {
                        Text(stringResource(R.string.bt_off))
                        Button(onClick = { runCatching { enable.launch(vm.repo.enableIntent()) } }, Modifier.padding(top = 12.dp)) { Text(stringResource(R.string.bt_turn_on)) }
                    }
                    return@Column
                }
            }
            if (pending.isNotEmpty()) Card(Modifier.fillMaxWidth().padding(12.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text(stringResource(R.string.bt_pick_target, pending.size), style = MaterialTheme.typography.titleSmall)
                    TextButton(onClick = { scope.launch { runCatching { ctx.startActivity(vm.systemIntent()) } } }) { Text(stringResource(R.string.bt_use_system)) }
                }
            }
            transfer?.let { t ->
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(t.error ?: stringResource(if (t.finished) R.string.bt_done else if (t.incoming) R.string.bt_receiving_from else R.string.bt_sending_to, t.device), style = MaterialTheme.typography.titleSmall)
                        if (!t.finished) {
                            Text(t.file, style = MaterialTheme.typography.bodySmall)
                            if (t.total > 0) LinearProgressIndicator(progress = { (t.done.toFloat() / t.total).coerceIn(0f, 1f) }, Modifier.fillMaxWidth().padding(top = 6.dp))
                            Text("${Format.size(t.done)} / ${if (t.total > 0) Format.size(t.total) else "?"}", style = MaterialTheme.typography.labelSmall)
                        } else TextButton(onClick = { vm.repo.clearTransfer() }) { Text(stringResource(R.string.dismiss)) }
                    }
                }
            }
            ListItem(headlineContent = { Text(stringResource(R.string.bt_receive)) }, supportingContent = { Text(stringResource(R.string.bt_receive_summary)) },
                trailingContent = { Switch(receiving, { if (it) vm.repo.startReceiving() else vm.repo.stopReceiving() }) })
            LazyColumn(Modifier.fillMaxSize()) {
                item { Header(stringResource(R.string.bt_paired)) }
                items(paired, key = { "p" + it.address }) { d ->
                    DeviceRow(d, null, onClick = { if (pending.isNotEmpty()) target = d }, onUnpair = { vm.unpair(d) })
                }
                item {
                    Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Header(stringResource(R.string.bt_available), Modifier.weight(1f))
                        if (scanning) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else TextButton(onClick = vm::scan) { Icon(Icons.Filled.BluetoothSearching, null); Text(stringResource(R.string.bt_scan)) }
                    }
                }
                items(found, key = { "f" + it.address }) { d -> DeviceRow(d, if (pairing == d.address) stringResource(R.string.bt_pairing) else null, onClick = { vm.pair(d) }, onUnpair = null) }
            }
        }
    }
    target?.let { d ->
        AlertDialog(onDismissRequest = { target = null }, title = { Text(stringResource(R.string.bt_send_to, d.name)) },
            text = { Text(stringResource(R.string.bt_send_how)) },
            confirmButton = { TextButton(onClick = { target = null; vm.sendDirect(d) { } }) { Text(stringResource(R.string.bt_direct)) } },
            dismissButton = { TextButton(onClick = { target = null; scope.launch { runCatching { ctx.startActivity(vm.systemIntent()) } } }) { Text(stringResource(R.string.bt_use_system)) } })
    }
    message?.let { m -> AlertDialog(onDismissRequest = { vm.message.value = null }, text = { Text(m) }, confirmButton = { TextButton(onClick = { vm.message.value = null }) { Text(stringResource(android.R.string.ok)) } }) }
}

@Composable
private fun Header(text: String, modifier: Modifier = Modifier) =
    Text(text, modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)

@Composable
private fun DeviceRow(d: BtDevice, status: String?, onClick: () -> Unit, onUnpair: (() -> Unit)?) {
    var menu by remember { mutableStateOf(false) }
    val icon = when (d.type) {
        BluetoothClass.Device.Major.COMPUTER -> Icons.Filled.Computer
        BluetoothClass.Device.Major.PHONE -> Icons.Filled.PhoneAndroid
        BluetoothClass.Device.Major.AUDIO_VIDEO -> Icons.Filled.Headphones
        else -> Icons.Filled.Bluetooth
    }
    ListItem(modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { Icon(icon, null) },
        headlineContent = { Text(d.name) },
        supportingContent = { Text(status ?: d.address) },
        trailingContent = onUnpair?.let { un -> {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, null) }
            DropdownMenu(menu, { menu = false }) { DropdownMenuItem(text = { Text(stringResource(R.string.bt_unpair)) }, onClick = { menu = false; un() }) }
        } })
}
