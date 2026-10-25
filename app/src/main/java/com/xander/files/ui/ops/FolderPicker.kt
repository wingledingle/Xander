package com.xander.files.ui.ops

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.xander.files.R
import com.xander.files.data.storage.StorageVolumes
import com.xander.files.data.storage.VolumeInfo
import com.xander.files.domain.fs.Capability
import com.xander.files.domain.fs.FileEntry
import com.xander.files.domain.fs.FileSystemRegistry
import com.xander.files.domain.fs.FsPath
import com.xander.files.ui.common.TextInputDialog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FolderPickerViewModel @Inject constructor(
    private val registry: FileSystemRegistry,
    private val storage: StorageVolumes,
) : ViewModel() {
    val path = MutableStateFlow<FsPath?>(null)
    val folders = MutableStateFlow<List<FileEntry>>(emptyList())
    val volumes = MutableStateFlow<List<VolumeInfo>>(emptyList())
    val error = MutableStateFlow<String?>(null)

    fun start(initial: FsPath?) {
        viewModelScope.launch(Dispatchers.IO) { volumes.value = storage.volumes() }
        go(initial?.takeIf { runCatching { registry.provider(it).capabilities(it).contains(Capability.WRITE) }.getOrDefault(false) } ?: FsPath.local(storage.primaryRoot))
    }

    fun go(p: FsPath?) {
        path.value = p
        if (p == null) { folders.value = emptyList(); return }
        viewModelScope.launch {
            error.value = null
            folders.value = runCatching { registry.provider(p).list(p).filter { it.isDirectory && !it.isHidden }.sortedBy { it.name.lowercase() } }
                .onFailure { error.value = it.message }.getOrDefault(emptyList())
        }
    }

    fun mkdir(name: String) = viewModelScope.launch {
        val p = path.value ?: return@launch
        runCatching { registry.provider(p).mkdir(p, name) }.onSuccess { go(it) }.onFailure { error.value = it.message }
    }
}

/** Full-screen destination chooser used by Copy to / Move to / Extract to / Backup. */
@Composable
fun FolderPickerDialog(title: String, initial: FsPath?, onDismiss: () -> Unit, onPick: (FsPath) -> Unit, vm: FolderPickerViewModel = hiltViewModel(key = "picker")) {
    LaunchedEffect(Unit) { vm.start(initial) }
    val path by vm.path.collectAsStateWithLifecycle()
    val folders by vm.folders.collectAsStateWithLifecycle()
    val volumes by vm.volumes.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    var newFolder by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, stringResource(R.string.cancel)) } },
                title = { Column { Text(title); Text(path?.path ?: stringResource(R.string.storage), style = MaterialTheme.typography.labelSmall, maxLines = 1) } },
                actions = {
                    IconButton(onClick = { vm.go(path?.parent) }, enabled = path != null) { Icon(Icons.Filled.ArrowUpward, stringResource(R.string.up)) }
                    IconButton(onClick = { newFolder = true }, enabled = path != null) { Icon(Icons.Filled.CreateNewFolder, stringResource(R.string.new_folder)) }
                }
            )
        }, bottomBar = {
            Row(Modifier.fillMaxWidth().padding(16.dp)) {
                Button(onClick = { path?.let(onPick) }, enabled = path != null, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.select_this_folder)) }
            }
        }) { pad ->
            LazyColumn(Modifier.padding(pad).fillMaxSize()) {
                if (path == null) items(volumes, key = { it.id }) { v ->
                    Row(Modifier.fillMaxWidth().clickable { vm.go(v.root) }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.PhoneAndroid, null); Text(v.label, Modifier.padding(start = 16.dp))
                    }
                }
                error?.let { item { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) } }
                items(folders, key = { it.path.toString() }) { f ->
                    Row(Modifier.fillMaxWidth().clickable { vm.go(f.path) }.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Folder, null, tint = MaterialTheme.colorScheme.primary); Text(f.name, Modifier.padding(start = 16.dp))
                    }
                    HorizontalDivider()
                }
            }
        }
    }
    if (newFolder) TextInputDialog(stringResource(R.string.new_folder), "", selectBaseName = false, onDismiss = { newFolder = false }) { vm.mkdir(it); newFolder = false }
}
