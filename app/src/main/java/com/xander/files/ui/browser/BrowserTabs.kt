package com.xander.files.ui.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.VerticalSplit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xander.files.R
import com.xander.files.domain.fs.FsPath
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID
import javax.inject.Inject

data class BrowserTab(val id: String, val initial: FsPath?, val title: String = "")

/** Open browser windows (tabs); in dual-pane mode the active and secondary tabs are shown side by side. */
@HiltViewModel
class BrowserTabsViewModel @Inject constructor(saved: SavedStateHandle) : ViewModel() {
    private val first = saved.get<String>("path")?.takeIf { it.isNotEmpty() }?.let(FsPath::parse)
    val tabs = MutableStateFlow(listOf(BrowserTab(UUID.randomUUID().toString(), first)))
    val active = MutableStateFlow(0)
    val secondary = MutableStateFlow(-1)
    val dualPaneEnabled = MutableStateFlow(true)

    fun open(path: FsPath?, focus: Boolean = true) {
        tabs.update { it + BrowserTab(UUID.randomUUID().toString(), path) }
        if (focus) active.value = tabs.value.lastIndex
    }

    fun close(index: Int): Boolean {
        val list = tabs.value
        if (list.size == 1) return false
        tabs.value = list.filterIndexed { i, _ -> i != index }
        if (active.value >= tabs.value.size) active.value = tabs.value.lastIndex
        if (secondary.value >= tabs.value.size || secondary.value == active.value) secondary.value = -1
        return true
    }

    fun select(index: Int) { if (index == secondary.value) secondary.value = active.value; active.value = index }
    fun setTitle(id: String, title: String) = tabs.update { l -> l.map { if (it.id == id && it.title != title) it.copy(title = title) else it } }

    /** Index of the tab shown in the second pane, creating one if needed. */
    fun ensureSecondary(): Int {
        val s = secondary.value
        if (s in tabs.value.indices && s != active.value) return s
        val other = tabs.value.indices.firstOrNull { it != active.value }
        if (other != null) { secondary.value = other; return other }
        tabs.update { it + BrowserTab(UUID.randomUUID().toString(), tabs.value[active.value].initial) }
        secondary.value = tabs.value.lastIndex
        return secondary.value
    }
}

@Composable
fun BrowserScreen(
    onExit: () -> Unit,
    onSearch: (FsPath) -> Unit,
    onOpenClipboard: (FsPath) -> Unit = {},
    onReauth: (FsPath) -> Unit = {},
    tabsVm: BrowserTabsViewModel = hiltViewModel(),
) {
    val tabs by tabsVm.tabs.collectAsStateWithLifecycle()
    val active by tabsVm.active.collectAsStateWithLifecycle()
    val dualEnabled by tabsVm.dualPaneEnabled.collectAsStateWithLifecycle()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Side-by-side panes on tablets and in landscape on large phones.
        val wide = maxWidth >= 720.dp
        val dual = wide && dualEnabled
        val secondaryIndex = if (dual) tabsVm.ensureSecondary() else -1
        val strip: @Composable () -> Unit = {
            TabStrip(tabs, active, secondaryIndex, wide, dualEnabled,
                onSelect = tabsVm::select, onClose = { i -> if (!tabsVm.close(i)) onExit() },
                onNew = { tabsVm.open(null) }, onToggleDual = { tabsVm.dualPaneEnabled.value = !dualEnabled })
        }
        if (!dual) {
            val t = tabs[active]
            androidx.compose.runtime.key(t.id) {
                BrowserPane(t.id, t.initial, onExit = { if (!tabsVm.close(active)) onExit() }, onSearch = onSearch, onOpenClipboard = onOpenClipboard,
                    onReauth = onReauth, onTitle = { tabsVm.setTitle(t.id, it) }, onOpenInNewTab = { tabsVm.open(it) }, tabStrip = strip)
            }
        } else {
            Row(Modifier.fillMaxSize()) {
                listOf(active, secondaryIndex).forEachIndexed { pos, idx ->
                    val t = tabs[idx]
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        androidx.compose.runtime.key(t.id) {
                            BrowserPane(t.id, t.initial, onExit = { if (pos == 0) onExit() else tabsVm.close(idx) }, onSearch = onSearch,
                                onOpenClipboard = onOpenClipboard, onReauth = onReauth, onTitle = { tabsVm.setTitle(t.id, it) },
                                onOpenInNewTab = { tabsVm.open(it) }, dualPane = true, isActive = pos == 0,
                                onActivate = { if (pos == 1) tabsVm.select(idx) }, tabStrip = if (pos == 0) strip else null)
                        }
                    }
                    if (pos == 0) VerticalDivider()
                }
            }
        }
    }
}

@Composable
private fun TabStrip(
    tabs: List<BrowserTab>, active: Int, secondary: Int, wide: Boolean, dual: Boolean,
    onSelect: (Int) -> Unit, onClose: (Int) -> Unit, onNew: () -> Unit, onToggleDual: () -> Unit,
) {
    if (tabs.size == 1 && !wide) {
        // Single window on a phone: keep the chrome minimal, just a "new tab" affordance.
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f))
            IconButton(onClick = onNew, modifier = Modifier.size(36.dp)) { Icon(Icons.Filled.Add, stringResource(R.string.new_tab)) }
        }
        return
    }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        tabs.forEachIndexed { i, t ->
            val selected = i == active || i == secondary
            Surface(
                color = if (i == active) MaterialTheme.colorScheme.primaryContainer else if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.padding(end = 4.dp).clip(RoundedCornerShape(10.dp)).clickable { onSelect(i) }
            ) {
                Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(t.title.ifEmpty { "…" }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge, modifier = Modifier.widthIn(max = 140.dp))
                    IconButton(onClick = { onClose(i) }, modifier = Modifier.size(32.dp)) { Icon(Icons.Filled.Close, stringResource(R.string.close_tab), Modifier.size(16.dp)) }
                }
            }
        }
        IconButton(onClick = onNew, modifier = Modifier.size(36.dp)) { Icon(Icons.Filled.Add, stringResource(R.string.new_tab)) }
        if (wide) IconButton(onClick = onToggleDual, modifier = Modifier.size(36.dp).background(if (dual) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))) {
            Icon(Icons.Filled.VerticalSplit, stringResource(R.string.dual_pane))
        }
    }
}
