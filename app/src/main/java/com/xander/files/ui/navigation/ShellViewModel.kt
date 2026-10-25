package com.xander.files.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xander.files.data.bookmarks.BookmarkRepository
import com.xander.files.data.settings.SettingsRepository
import com.xander.files.data.storage.StorageVolumes
import com.xander.files.data.storage.VolumeInfo
import com.xander.files.data.transfer.ConflictBroker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** App-shell state shared by the drawer and global dialogs. */
@HiltViewModel
class ShellViewModel @Inject constructor(
    private val storage: StorageVolumes,
    bookmarkRepo: BookmarkRepository,
    settings: SettingsRepository,
    val conflicts: ConflictBroker,
    val launcher: com.xander.files.data.open.ViewerLauncher,
    private val registry: com.xander.files.domain.fs.FileSystemRegistry,
) : ViewModel() {
    private val _volumes = MutableStateFlow<List<VolumeInfo>>(emptyList())
    val volumes: StateFlow<List<VolumeInfo>> = _volumes
    val bookmarks = bookmarkRepo.bookmarks.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val rootMode = settings.settings.map { it.rootMode }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Sections such as Library, Network and Cloud are added here as those features register. */
    val extraDrawerSections: List<Pair<Int, List<DrawerDestination>>> = DrawerRegistry.sections

    fun openFile(e: com.xander.files.domain.fs.FileEntry) = viewModelScope.launch { launcher.open(e) }

    /** Opens a path whose type is unknown (history, bookmarks, shortcuts): folders browse, files view. */
    fun openPath(p: com.xander.files.domain.fs.FsPath, onFolder: (com.xander.files.domain.fs.FsPath) -> Unit) = viewModelScope.launch {
        val e = runCatching { kotlinx.coroutines.withContext(Dispatchers.IO) { registry.provider(p).stat(p) } }.getOrNull()
        if (e == null || e.isDirectory) onFolder(p) else launcher.open(e)
    }

    init {
        viewModelScope.launch(Dispatchers.IO) { storage.changes().collect { _volumes.value = storage.volumes() } }
    }
}

object DrawerRegistry {
    val sections = mutableListOf<Pair<Int, List<DrawerDestination>>>()
}
