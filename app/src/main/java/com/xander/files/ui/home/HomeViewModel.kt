package com.xander.files.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xander.files.data.fs.root.RootFileSystemProvider
import com.xander.files.data.fs.shizuku.ShizukuFileSystemProvider
import com.xander.files.data.fs.shizuku.ShizukuState
import com.xander.files.data.settings.SettingsRepository
import com.xander.files.data.storage.StorageVolumes
import com.xander.files.data.storage.VolumeInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.xander.files.data.index.FileIndexer
import com.xander.files.data.index.LibraryKind
import com.xander.files.data.index.LibraryRepository
import com.xander.files.domain.fs.FileEntry

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val volumes: StorageVolumes,
    val shizuku: ShizukuFileSystemProvider,
    private val root: RootFileSystemProvider,
    private val settings: SettingsRepository,
    private val library: LibraryRepository,
    indexer: FileIndexer,
) : ViewModel() {

    val stats = library.stats.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val fileCount = library.fileCount.stateIn(viewModelScope, SharingStarted.Eagerly, 0)
    val indexStatus = indexer.status
    private val _appCount = MutableStateFlow(0)
    val appCount: StateFlow<Int> = _appCount
    val recentDays = MutableStateFlow(7)
    private val _recent = MutableStateFlow<List<FileEntry>>(emptyList())
    val recent: StateFlow<List<FileEntry>> = _recent

    fun setRecentDays(d: Int) { recentDays.value = d; loadRecent() }
    private fun loadRecent() = viewModelScope.launch {
        _recent.value = library.files(LibraryKind.RECENT, recentDays.value).filter { !it.isHidden }.take(60)
    }

    private val _volumes = MutableStateFlow<List<VolumeInfo>>(emptyList())
    val volumeList: StateFlow<List<VolumeInfo>> = _volumes
    val shizukuState: StateFlow<ShizukuState> = shizuku.state
    val rootMode = settings.settings.map { it.rootMode }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val primaryRoot get() = volumes.primaryRoot

    init {
        viewModelScope.launch(Dispatchers.IO) { volumes.changes().collect { _volumes.value = volumes.volumes() } }
    }

    fun refresh() = viewModelScope.launch(Dispatchers.IO) {
        _volumes.value = volumes.volumes(); shizuku.refresh()
        _appCount.value = library.appCount()
        loadRecent()
    }

    fun requestShizuku() = viewModelScope.launch { shizuku.requestPermission() }

    fun toggleRoot() = viewModelScope.launch {
        val enable = !rootMode.value
        if (enable && !root.requestRoot()) return@launch
        settings.update { it.copy(rootMode = enable) }
    }
}
