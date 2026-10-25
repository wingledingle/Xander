package com.xander.files.ui.recycle

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import com.xander.files.data.recycle.RecycleBin
import com.xander.files.data.settings.SettingsRepository
import com.xander.files.data.transfer.TransferManager
import com.xander.files.data.transfer.TransferOptions
import com.xander.files.data.transfer.TransferRequest
import com.xander.files.data.transfer.TransferType
import com.xander.files.domain.fs.FileCategory
import com.xander.files.ui.common.CategoryIcon
import com.xander.files.ui.common.ConfirmDialog
import com.xander.files.ui.common.EmptyState
import com.xander.files.ui.common.Format
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RecycleBinViewModel @Inject constructor(
    private val bin: RecycleBin,
    private val transfers: TransferManager,
    settings: SettingsRepository,
) : ViewModel() {
    val items = bin.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val retention = settings.settings.map { it.recycleRetentionDays }.stateIn(viewModelScope, SharingStarted.Eagerly, 30)
    val selection = MutableStateFlow<Set<String>>(emptySet())

    fun toggle(id: String) { selection.value = selection.value.let { if (id in it) it - id else it + id } }
    fun selectAll() { selection.value = items.value.map { it.id }.toSet() }

    fun restoreSelected() = viewModelScope.launch {
        val ids = selection.value.toList().ifEmpty { return@launch }
        transfers.enqueue(TransferRequest(TransferType.RESTORE, emptyList(), null, "Restoring ${ids.size} item(s)", TransferOptions(recycleIds = ids)))
        selection.value = emptySet()
    }

    fun deleteSelected() = viewModelScope.launch {
        selection.value.forEach { bin.purge(it) }; selection.value = emptySet()
    }

    fun empty() = viewModelScope.launch { bin.empty(); selection.value = emptySet() }
}

@Composable
fun RecycleBinScreen(onBack: () -> Unit, vm: RecycleBinViewModel = hiltViewModel()) {
    val items by vm.items.collectAsStateWithLifecycle()
    val sel by vm.selection.collectAsStateWithLifecycle()
    val days by vm.retention.collectAsStateWithLifecycle()
    var confirmEmpty by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    Scaffold(topBar = {
        TopAppBar(
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            title = { Text(if (sel.isEmpty()) stringResource(R.string.recycle_bin) else "${sel.size}") },
            actions = {
                if (sel.isNotEmpty()) {
                    IconButton(onClick = vm::restoreSelected) { Icon(Icons.Filled.Restore, stringResource(R.string.restore)) }
                    IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.DeleteForever, stringResource(R.string.delete_permanently)) }
                }
                IconButton(onClick = vm::selectAll) { Icon(Icons.Filled.SelectAll, stringResource(R.string.select_all)) }
                IconButton(onClick = { confirmEmpty = true }, enabled = items.isNotEmpty()) { Icon(Icons.Filled.DeleteSweep, stringResource(R.string.empty_bin)) }
            }
        )
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (days > 0) Text(stringResource(R.string.bin_retention_hint, days), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
            if (items.isEmpty()) EmptyState(Icons.Filled.DeleteSweep, stringResource(R.string.bin_empty))
            else LazyColumn {
                items(items, key = { it.id }) { e ->
                    Row(Modifier.fillMaxWidth().combinedClickable(onClick = { vm.toggle(e.id) }, onLongClick = { vm.toggle(e.id) })
                        .padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        CategoryIcon(if (e.isDirectory) FileCategory.FOLDER else FileCategory.of(e.name), 40)
                        Column(Modifier.weight(1f).padding(start = 14.dp)) {
                            Text(e.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                            Text(e.originalPath.removePrefix("file://"), style = MaterialTheme.typography.bodySmall, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("${Format.size(e.size)} · ${Format.relative(e.deletedAt)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                        }
                        Checkbox(e.id in sel, { vm.toggle(e.id) })
                    }
                }
            }
        }
    }
    if (confirmEmpty) ConfirmDialog(stringResource(R.string.empty_bin), stringResource(R.string.empty_bin_confirm), stringResource(R.string.delete),
        onDismiss = { confirmEmpty = false }, onConfirm = { confirmEmpty = false; vm.empty() })
    if (confirmDelete) ConfirmDialog(stringResource(R.string.delete_permanently), stringResource(R.string.cannot_undo), stringResource(R.string.delete),
        onDismiss = { confirmDelete = false }, onConfirm = { confirmDelete = false; vm.deleteSelected() })
}
