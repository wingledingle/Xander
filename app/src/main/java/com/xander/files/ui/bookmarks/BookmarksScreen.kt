package com.xander.files.ui.bookmarks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Star
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
import com.xander.files.data.bookmarks.BookmarkRepository
import com.xander.files.data.db.BookmarkEntity
import com.xander.files.domain.fs.FileCategory
import com.xander.files.domain.fs.FsPath
import com.xander.files.ui.common.CategoryIcon
import com.xander.files.ui.common.EmptyState
import com.xander.files.ui.common.TextInputDialog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BookmarksViewModel @Inject constructor(private val repo: BookmarkRepository) : ViewModel() {
    val items = repo.bookmarks.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    fun remove(b: BookmarkEntity) = viewModelScope.launch { repo.remove(b.path) }
    fun rename(b: BookmarkEntity, t: String) = viewModelScope.launch { repo.rename(b.id, t) }
    fun move(index: Int, delta: Int) = viewModelScope.launch {
        val l = items.value.toMutableList()
        val j = index + delta
        if (j !in l.indices) return@launch
        val tmp = l[index]; l[index] = l[j]; l[j] = tmp
        repo.reorder(l)
    }
}

@Composable
fun BookmarksScreen(onBack: () -> Unit, onOpen: (FsPath, Boolean) -> Unit, vm: BookmarksViewModel = hiltViewModel()) {
    val items by vm.items.collectAsStateWithLifecycle()
    var renaming by remember { mutableStateOf<BookmarkEntity?>(null) }
    Scaffold(topBar = {
        TopAppBar(
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            title = { Text(stringResource(R.string.favorites)) }
        )
    }) { pad ->
        if (items.isEmpty()) Column(Modifier.padding(pad)) { EmptyState(Icons.Filled.Star, stringResource(R.string.no_favorites), stringResource(R.string.no_favorites_hint)) }
        else LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            itemsIndexed(items, key = { _, b -> b.id }) { i, b ->
                Row(Modifier.fillMaxWidth().clickable { onOpen(FsPath.parse(b.path), b.isFolder) }.padding(start = 16.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    CategoryIcon(if (b.isFolder) FileCategory.FOLDER else FileCategory.of(b.path), 40)
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        Text(b.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                        Text(b.path.removePrefix("file://"), style = MaterialTheme.typography.bodySmall, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { vm.move(i, -1) }, enabled = i > 0) { Icon(Icons.Filled.KeyboardArrowUp, stringResource(R.string.move_up)) }
                    IconButton(onClick = { vm.move(i, 1) }, enabled = i < items.lastIndex) { Icon(Icons.Filled.KeyboardArrowDown, stringResource(R.string.move_down)) }
                    IconButton(onClick = { renaming = b }) { Icon(Icons.Filled.Edit, stringResource(R.string.rename)) }
                    IconButton(onClick = { vm.remove(b) }) { Icon(Icons.Filled.Close, stringResource(R.string.remove)) }
                }
            }
        }
    }
    renaming?.let { b -> TextInputDialog(stringResource(R.string.rename), b.title, selectBaseName = false, validator = { null },
        onDismiss = { renaming = null }) { vm.rename(b, it); renaming = null } }
}
