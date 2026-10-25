package com.xander.files.ui.search

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xander.files.data.open.FileOpener
import com.xander.files.data.search.SearchCoverage
import com.xander.files.data.search.SearchRepository
import com.xander.files.data.search.SearchScope
import com.xander.files.domain.fs.FileCategory
import com.xander.files.domain.fs.FileEntry
import com.xander.files.domain.fs.FsPath
import com.xander.files.domain.fs.SearchQuery
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class DateFilter(val days: Int?) { ANY(null), DAY(1), WEEK(7), MONTH(30), YEAR(365) }
enum class SizeFilter(val min: Long?, val max: Long?) {
    ANY(null, null), SMALL(null, 1L shl 20), MEDIUM(1L shl 20, 100L shl 20), LARGE(100L shl 20, 1L shl 30), HUGE(1L shl 30, null)
}

data class SearchUiState(
    val text: String = "",
    val categories: Set<FileCategory> = emptySet(),
    val date: DateFilter = DateFilter.ANY,
    val size: SizeFilter = SizeFilter.ANY,
    val includeHidden: Boolean = true,
    val scopeRoot: FsPath? = null,
    val everywhere: Boolean = true,
    val results: List<FileEntry> = emptyList(),
    val running: Boolean = false,
    val coverage: SearchCoverage? = null,
)

@HiltViewModel
class SearchViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repo: SearchRepository,
    private val opener: FileOpener,
    private val launcher: com.xander.files.data.open.ViewerLauncher,
) : ViewModel() {

    private val _state = MutableStateFlow(
        SearchUiState(scopeRoot = savedState.get<String>("root")?.takeIf { it.isNotEmpty() }?.let { FsPath.parse(it) })
            .let { it.copy(everywhere = it.scopeRoot == null) }
    )
    val state: StateFlow<SearchUiState> = _state
    private var job: Job? = null
    private val _launch = Channel<Intent>(Channel.BUFFERED)
    val launches = _launch.receiveAsFlow()

    init { viewModelScope.launch { _state.update { it.copy(coverage = repo.coverage()) } } }

    fun setText(t: String) { _state.update { it.copy(text = t) }; restart() }
    fun toggleCategory(c: FileCategory) { _state.update { it.copy(categories = if (c in it.categories) it.categories - c else it.categories + c) }; restart() }
    fun setDate(d: DateFilter) { _state.update { it.copy(date = d) }; restart() }
    fun setSize(s: SizeFilter) { _state.update { it.copy(size = s) }; restart() }
    fun setEverywhere(e: Boolean) { _state.update { it.copy(everywhere = e) }; restart() }
    fun toggleHidden() { _state.update { it.copy(includeHidden = !it.includeHidden) }; restart() }

    /** Results update as you type: each change cancels the running search and starts a new one after a short pause. */
    private fun restart() {
        job?.cancel()
        val s = _state.value
        if (s.text.isBlank() && s.categories.isEmpty() && s.size == SizeFilter.ANY && s.date == DateFilter.ANY) {
            _state.update { it.copy(results = emptyList(), running = false) }; return
        }
        job = viewModelScope.launch {
            delay(250)
            _state.update { it.copy(results = emptyList(), running = true, coverage = repo.coverage()) }
            val q = SearchQuery(
                text = s.text.trim(), categories = s.categories, includeHidden = s.includeHidden,
                minSize = s.size.min, maxSize = s.size.max,
                modifiedAfter = s.date.days?.let { System.currentTimeMillis() - it * 86_400_000L },
            )
            val buffer = ArrayList<FileEntry>()
            val seen = HashSet<String>()
            var last = 0L
            try {
                repo.search(q, SearchScope(if (s.everywhere) null else s.scopeRoot)).collect { e ->
                    if (seen.add(e.path.path) && buffer.size < MAX_RESULTS) buffer.add(e)
                    val now = System.currentTimeMillis()
                    if (now - last > 200) { last = now; _state.update { it.copy(results = ArrayList(buffer)) } }
                    if (buffer.size >= MAX_RESULTS) job?.cancel()
                }
            } finally {
                _state.update { it.copy(results = ArrayList(buffer), running = false) }
            }
        }
    }

    fun open(e: FileEntry) = viewModelScope.launch {
        launcher.open(e)
    }

    companion object { const val MAX_RESULTS = 5000 }
}
