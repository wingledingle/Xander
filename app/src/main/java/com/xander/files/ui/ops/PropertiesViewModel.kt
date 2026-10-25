package com.xander.files.ui.ops

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xander.files.data.ops.FileActions
import com.xander.files.domain.fs.FileEntry
import com.xander.files.domain.fs.FileSystemRegistry
import com.xander.files.domain.fs.PosixFileSystemProvider
import com.xander.files.domain.fs.measure
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PropertiesState(
    val entries: List<FileEntry> = emptyList(),
    val size: Long = 0, val files: Int = 0, val folders: Int = 0, val measuring: Boolean = false,
    val checksums: Map<String, String> = emptyMap(), val hashing: String? = null, val hashProgress: Long = 0,
    val posix: Boolean = false, val error: String? = null,
)

@HiltViewModel
class PropertiesViewModel @Inject constructor(
    private val registry: FileSystemRegistry,
    private val actions: FileActions,
) : ViewModel() {
    private val _state = MutableStateFlow(PropertiesState())
    val state: StateFlow<PropertiesState> = _state
    private var hashJob: Job? = null

    fun load(entries: List<FileEntry>) {
        if (_state.value.entries == entries) return
        _state.value = PropertiesState(entries = entries, measuring = true,
            posix = entries.size == 1 && registry.provider(entries.first().path) is PosixFileSystemProvider)
        viewModelScope.launch {
            var size = 0L; var files = 0; var folders = 0
            for (e in entries) {
                val p = registry.provider(e.path)
                if (!e.isDirectory) { size += e.size; files++; continue }
                folders++
                val base = Triple(size, files, folders)
                val (s, f, d) = runCatching {
                    p.measure(e.path) { ps, pf, pd -> _state.update { it.copy(size = base.first + ps, files = base.second + pf, folders = base.third + pd) } }
                }.getOrDefault(Triple(0L, 0, 0))
                size += s; files += f; folders += d
            }
            _state.update { it.copy(size = size, files = files, folders = folders, measuring = false) }
        }
    }

    fun hash(algorithm: String) {
        val e = _state.value.entries.singleOrNull()?.takeIf { !it.isDirectory } ?: return
        hashJob?.cancel()
        hashJob = viewModelScope.launch {
            _state.update { it.copy(hashing = algorithm, hashProgress = 0) }
            try {
                val h = actions.checksum(e.path, algorithm) { p -> _state.update { it.copy(hashProgress = p) } }
                _state.update { it.copy(checksums = it.checksums + (algorithm to h), hashing = null) }
            } catch (t: Throwable) {
                _state.update { it.copy(hashing = null, error = t.message) }
            }
        }
    }

    fun chmod(mode: Int, recursive: Boolean, done: () -> Unit) = posixOp(done) { p, e -> p.chmod(e.path, mode, recursive) }
    fun chown(owner: String, group: String, recursive: Boolean, done: () -> Unit) = posixOp(done) { p, e -> p.chown(e.path, owner, group, recursive) }

    private fun posixOp(done: () -> Unit, block: suspend (PosixFileSystemProvider, FileEntry) -> Unit) = viewModelScope.launch {
        val e = _state.value.entries.singleOrNull() ?: return@launch
        val p = registry.provider(e.path) as? PosixFileSystemProvider ?: return@launch
        try { block(p, e); done() } catch (t: Throwable) { _state.update { it.copy(error = t.message) } }
    }
}
