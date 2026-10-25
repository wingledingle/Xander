package com.xander.files.ui.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.History
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
import com.xander.files.data.bookmarks.BookmarkRepository
import com.xander.files.domain.fs.FileCategory
import com.xander.files.domain.fs.FsPath
import com.xander.files.ui.common.CategoryIcon
import com.xander.files.ui.common.EmptyState
import com.xander.files.ui.common.Format
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HistoryViewModel @Inject constructor(private val repo: BookmarkRepository) : ViewModel() {
    val items = repo.historyFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    fun clear() = viewModelScope.launch { repo.clearHistory() }
}

/** Visited folders, most recent first. */
@Composable
fun HistoryScreen(onBack: () -> Unit, onOpen: (FsPath) -> Unit, vm: HistoryViewModel = hiltViewModel()) {
    val items by vm.items.collectAsStateWithLifecycle()
    Scaffold(topBar = {
        TopAppBar(
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            title = { Text(stringResource(R.string.history)) },
            actions = { IconButton(onClick = vm::clear) { Icon(Icons.Filled.ClearAll, stringResource(R.string.clear)) } }
        )
    }) { pad ->
        if (items.isEmpty()) Column(Modifier.padding(pad)) { EmptyState(Icons.Filled.History, stringResource(R.string.no_history)) }
        else LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            items(items, key = { it.path }) { h ->
                Row(Modifier.fillMaxWidth().clickable { onOpen(FsPath.parse(h.path)) }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    CategoryIcon(FileCategory.FOLDER, 36)
                    Column(Modifier.padding(start = 14.dp)) {
                        Text(h.title.ifEmpty { "/" }, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                        Text(h.path.removePrefix("file://") + " · " + Format.relative(h.visitedAt), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                }
            }
        }
    }
}
