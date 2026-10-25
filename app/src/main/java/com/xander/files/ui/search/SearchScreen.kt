package com.xander.files.ui.search

import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xander.files.R
import com.xander.files.domain.fs.FileCategory
import com.xander.files.domain.fs.FsPath
import com.xander.files.ui.browser.FileListRow
import com.xander.files.ui.common.EmptyState

@Composable
fun SearchScreen(onBack: () -> Unit, onOpenFolder: (FsPath) -> Unit, vm: SearchViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(Unit) {
        vm.launches.collect { runCatching { ctx.startActivity(it) }.onFailure { Toast.makeText(ctx, R.string.no_app_found, Toast.LENGTH_SHORT).show() } }
    }
    Scaffold(topBar = {
        TopAppBar(
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            title = {
                TextField(
                    value = s.text, onValueChange = vm::setText, singleLine = true,
                    placeholder = { Text(stringResource(if (s.everywhere) R.string.search_everywhere else R.string.search_here)) },
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    trailingIcon = { if (s.text.isNotEmpty()) IconButton(onClick = { vm.setText("") }) { Icon(Icons.Filled.Clear, null) } },
                    colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus)
                )
            }
        )
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (s.scopeRoot != null) {
                    FilterChip(selected = !s.everywhere, onClick = { vm.setEverywhere(!s.everywhere) },
                        label = { Text(if (s.everywhere) stringResource(R.string.everywhere) else s.scopeRoot!!.name.ifEmpty { "/" }) })
                }
                listOf(FileCategory.IMAGE to R.string.cat_images, FileCategory.VIDEO to R.string.cat_videos, FileCategory.AUDIO to R.string.cat_music,
                    FileCategory.DOCUMENT to R.string.cat_documents, FileCategory.PDF to R.string.pdf, FileCategory.APK to R.string.cat_apks,
                    FileCategory.ARCHIVE to R.string.cat_archives).forEach { (c, label) ->
                    FilterChip(selected = c in s.categories, onClick = { vm.toggleCategory(c) }, label = { Text(stringResource(label)) })
                }
                var dateMenu by remember { mutableStateOf(false) }
                FilterChip(selected = s.date != DateFilter.ANY, onClick = { dateMenu = true }, label = { Text(stringResource(dateLabel(s.date))) })
                DropdownMenu(dateMenu, { dateMenu = false }) {
                    DateFilter.entries.forEach { d -> DropdownMenuItem(text = { Text(stringResource(dateLabel(d))) }, onClick = { dateMenu = false; vm.setDate(d) }) }
                }
                var sizeMenu by remember { mutableStateOf(false) }
                FilterChip(selected = s.size != SizeFilter.ANY, onClick = { sizeMenu = true }, label = { Text(stringResource(sizeLabel(s.size))) })
                DropdownMenu(sizeMenu, { sizeMenu = false }) {
                    SizeFilter.entries.forEach { z -> DropdownMenuItem(text = { Text(stringResource(sizeLabel(z))) }, onClick = { sizeMenu = false; vm.setSize(z) }) }
                }
                FilterChip(selected = s.includeHidden, onClick = vm::toggleHidden, label = { Text(stringResource(R.string.hidden_files)) })
            }
            if (s.running) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
            val cov = s.coverage
            if (s.everywhere && cov != null && !cov.restrictedFolders) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Info, null, tint = MaterialTheme.colorScheme.primary)
                    Text(stringResource(R.string.search_coverage_hint), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 8.dp))
                }
            }
            if (!s.running && s.results.isEmpty() && s.text.isNotBlank()) EmptyState(Icons.Filled.Search, stringResource(R.string.no_results))
            else {
                Text(
                    if (s.results.isNotEmpty()) ctx.resources.getQuantityString(R.plurals.results_count, s.results.size, s.results.size) else "",
                    style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
                LazyColumn(Modifier.fillMaxSize()) {
                    items(s.results, key = { it.path.toString() }) { e ->
                        Column {
                            FileListRow(e, false, true, true, onClick = { if (e.isDirectory) onOpenFolder(e.path) else vm.open(e) },
                                onLongClick = { e.path.parent?.let(onOpenFolder) })
                            Row(Modifier.padding(start = 74.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.FolderOpen, null, Modifier.padding(end = 4.dp), tint = MaterialTheme.colorScheme.outline)
                                Text(e.path.parent?.path ?: "/", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, maxLines = 1,
                                    modifier = Modifier.weight(1f))
                                IconButton(onClick = { e.path.parent?.let(onOpenFolder) }) {
                                    Icon(Icons.Filled.FolderOpen, stringResource(R.string.open_containing_folder))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun dateLabel(d: DateFilter) = when (d) {
    DateFilter.ANY -> R.string.any_date; DateFilter.DAY -> R.string.last_24h; DateFilter.WEEK -> R.string.last_7d
    DateFilter.MONTH -> R.string.last_30d; DateFilter.YEAR -> R.string.last_year
}

private fun sizeLabel(z: SizeFilter) = when (z) {
    SizeFilter.ANY -> R.string.any_size; SizeFilter.SMALL -> R.string.size_small; SizeFilter.MEDIUM -> R.string.size_medium
    SizeFilter.LARGE -> R.string.size_large; SizeFilter.HUGE -> R.string.size_huge
}
