package com.xander.files.ui.transfers

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.xander.files.R
import com.xander.files.data.db.TransferEntity
import com.xander.files.data.transfer.TransferManager
import com.xander.files.data.transfer.TransferState
import com.xander.files.ui.common.EmptyState
import com.xander.files.ui.common.Format
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TransfersViewModel @Inject constructor(private val manager: TransferManager) : ViewModel() {
    val items = manager.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    fun pause(id: String) = viewModelScope.launch { manager.pause(id) }
    fun resume(id: String) = viewModelScope.launch { manager.resume(id) }
    fun cancel(id: String) = viewModelScope.launch { manager.cancel(id) }
    fun retry(id: String) = viewModelScope.launch { manager.retry(id) }
    fun remove(id: String) = viewModelScope.launch { manager.remove(id) }
    fun clearFinished() = viewModelScope.launch { manager.clearFinished() }
}

@Composable
fun TransfersScreen(onBack: () -> Unit, vm: TransfersViewModel = hiltViewModel()) {
    val items by vm.items.collectAsStateWithLifecycle()
    Scaffold(topBar = {
        TopAppBar(
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            title = { Text(stringResource(R.string.transfers)) },
            actions = { IconButton(onClick = vm::clearFinished) { Icon(Icons.Filled.ClearAll, stringResource(R.string.clear_finished)) } }
        )
    }) { pad ->
        if (items.isEmpty()) Column(Modifier.padding(pad)) { EmptyState(Icons.Filled.SwapVert, stringResource(R.string.no_transfers)) }
        else LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            items(items, key = { it.id }) { t -> TransferCard(t, vm) }
        }
    }
}

@Composable
private fun TransferCard(t: TransferEntity, vm: TransfersViewModel) {
    val state = runCatching { TransferState.valueOf(t.state) }.getOrDefault(TransferState.FAILED)
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(t.title, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                    Text(stateLabel(state, t), style = MaterialTheme.typography.bodySmall,
                        color = if (state == TransferState.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                when (state) {
                    TransferState.RUNNING, TransferState.QUEUED, TransferState.WAITING -> {
                        IconButton(onClick = { vm.pause(t.id) }) { Icon(Icons.Filled.Pause, stringResource(R.string.pause)) }
                        IconButton(onClick = { vm.cancel(t.id) }) { Icon(Icons.Filled.Close, stringResource(R.string.cancel)) }
                    }
                    TransferState.PAUSED -> {
                        IconButton(onClick = { vm.resume(t.id) }) { Icon(Icons.Filled.PlayArrow, stringResource(R.string.resume)) }
                        IconButton(onClick = { vm.cancel(t.id) }) { Icon(Icons.Filled.Close, stringResource(R.string.cancel)) }
                    }
                    TransferState.FAILED, TransferState.CANCELLED -> {
                        IconButton(onClick = { vm.retry(t.id) }) { Icon(Icons.Filled.Refresh, stringResource(R.string.retry)) }
                        IconButton(onClick = { vm.remove(t.id) }) { Icon(Icons.Filled.Close, stringResource(R.string.remove)) }
                    }
                    TransferState.DONE -> IconButton(onClick = { vm.remove(t.id) }) { Icon(Icons.Filled.Close, stringResource(R.string.remove)) }
                }
            }
            if (state == TransferState.RUNNING || state == TransferState.PAUSED) {
                val frac = if (t.totalBytes > 0) t.doneBytes.toFloat() / t.totalBytes else if (t.totalItems > 0) t.doneItems.toFloat() / t.totalItems else 0f
                LinearProgressIndicator(progress = { frac.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                t.currentName?.let { Text(it, style = MaterialTheme.typography.labelSmall, maxLines = 1, modifier = Modifier.padding(top = 4.dp)) }
            }
        }
    }
}

@Composable
private fun stateLabel(s: TransferState, t: TransferEntity): String = when (s) {
    TransferState.QUEUED -> stringResource(R.string.queued)
    TransferState.RUNNING -> buildString {
        append("${Format.size(t.doneBytes)} / ${Format.size(t.totalBytes)} · ${t.doneItems}/${t.totalItems}")
        if (t.speedBps > 0) {
            append(" · ${Format.speed(t.speedBps)}")
            append(" · ${Format.duration((t.totalBytes - t.doneBytes) / t.speedBps)}")
        }
    }
    TransferState.PAUSED -> stringResource(R.string.paused)
    TransferState.WAITING -> stringResource(R.string.waiting)
    TransferState.DONE -> stringResource(R.string.transfer_done) + " · " + Format.size(t.totalBytes)
    TransferState.FAILED -> t.error ?: stringResource(R.string.failed)
    TransferState.CANCELLED -> stringResource(R.string.cancelled)
}
