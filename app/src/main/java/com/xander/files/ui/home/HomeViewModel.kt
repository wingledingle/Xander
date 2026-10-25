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

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val volumes: StorageVolumes,
    val shizuku: ShizukuFileSystemProvider,
    private val root: RootFileSystemProvider,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val _volumes = MutableStateFlow<List<VolumeInfo>>(emptyList())
    val volumeList: StateFlow<List<VolumeInfo>> = _volumes
    val shizukuState: StateFlow<ShizukuState> = shizuku.state
    val rootMode = settings.settings.map { it.rootMode }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val primaryRoot get() = volumes.primaryRoot

    init {
        viewModelScope.launch(Dispatchers.IO) { volumes.changes().collect { _volumes.value = volumes.volumes() } }
    }

    fun refresh() = viewModelScope.launch(Dispatchers.IO) { _volumes.value = volumes.volumes(); shizuku.refresh() }

    fun requestShizuku() = viewModelScope.launch { shizuku.requestPermission() }

    fun toggleRoot() = viewModelScope.launch {
        val enable = !rootMode.value
        if (enable && !root.requestRoot()) return@launch
        settings.update { it.copy(rootMode = enable) }
    }
}
