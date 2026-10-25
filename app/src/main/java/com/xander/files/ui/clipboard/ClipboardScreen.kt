package com.xander.files.ui.clipboard

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.xander.files.data.clipboard.ClipItem
import com.xander.files.data.clipboard.ClipOp
import com.xander.files.data.clipboard.ClipboardRepository
import com.xander.files.domain.fs.FsPath
import com.xander.files.ui.common.EmptyState
import com.xander.files.ui.common.Format
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ClipboardViewModel @Inject constructor(private val repo: ClipboardRepository) : ViewModel() {
    val items = repo.items.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    fun remove(id: Long) = viewModelScope.launch { repo.remove(id) }
    fun clear() = viewModelScope.launch { repo.clear() }
    fun paste(id: Long, dest: FsPath) = viewModelScope.launch { repo.paste(id, dest) }
}

/** Lists all clipboard entries; [pasteTarget] non-null lets the user paste any entry into that folder. */
@Composable
fun ClipboardScreen(onBack: () -> Unit, pasteTarget: FsPath?, vm: ClipboardViewModel = hiltViewModel()) {
    val items by vm.items.collectAsStateWithLifecycle()
    Scaffold(topBar = {
        TopAppBar(
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            title = { Text(stringResource(R.string.clipboard)) },
            actions = { IconButton(onClick = vm::clear, enabled = items.isNotEmpty()) { Icon(Icons.Filled.ClearAll, stringResource(R.string.clear)) } }
        )
    }) { pad ->
        if (items.isEmpty()) Column(Modifier.padding(pad)) { EmptyState(Icons.Filled.ContentPaste, stringResource(R.string.clipboard_empty)) }
        else LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            items(items, key = { it.id }) { c -> ClipRow(c, pasteTarget, vm, onBack) }
        }
    }
}

@Composable
private fun ClipRow(c: ClipItem, target: FsPath?, vm: ClipboardViewModel, onDone: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (c.op == ClipOp.CUT) Icons.Filled.ContentCut else Icons.Filled.ContentCopy, null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(c.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            Text(c.paths.first().let { it.parent?.toString() ?: it.toString() }.removePrefix("file://"), style = MaterialTheme.typography.bodySmall, maxLines = 1,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(Format.relative(c.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
        if (target != null) IconButton(onClick = { vm.paste(c.id, target); onDone() }) { Icon(Icons.Filled.ContentPaste, stringResource(R.string.paste)) }
        IconButton(onClick = { vm.remove(c.id) }) { Icon(Icons.Filled.Close, stringResource(R.string.remove)) }
    }
}
