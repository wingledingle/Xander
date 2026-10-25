package com.xander.files.ui.settings

import android.content.Intent
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.xander.files.BuildConfig
import com.xander.files.R
import com.xander.files.data.billing.BillingRepository
import com.xander.files.data.db.FolderPrefDao
import com.xander.files.data.lock.AppLock
import com.xander.files.data.open.FileOpener
import com.xander.files.data.settings.AppSettings
import com.xander.files.data.settings.SettingsRepository
import com.xander.files.data.settings.SortBy
import com.xander.files.data.settings.ThemeMode
import com.xander.files.data.settings.ViewMode
import com.xander.files.ui.theme.Accents
import com.xander.files.ui.theme.Skins
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: android.content.Context,
    private val repo: SettingsRepository,
    billing: BillingRepository,
    private val lock: AppLock,
    private val opener: FileOpener,
    private val folderPrefs: FolderPrefDao,
    val nsfw: com.xander.files.data.nsfw.NsfwRepository,
) : ViewModel() {
    val settings = repo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val premium = billing.premium
    fun update(f: (AppSettings) -> AppSettings) = viewModelScope.launch { repo.update(f) }
    fun setPin(pin: String?) = viewModelScope.launch { lock.setPin(pin) }
    fun nsfwScan(all: Boolean) = viewModelScope.launch { nsfw.scan(all) }
    fun nsfwClear() = viewModelScope.launch { nsfw.clear() }

    suspend fun cacheSize(): Long = withContext(Dispatchers.IO) { context.cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() } }
    fun clearCache(done: () -> Unit) = viewModelScope.launch {
        withContext(Dispatchers.IO) { context.cacheDir.listFiles()?.forEach { it.deleteRecursively() }; coil3.SingletonImageLoader.get(context).memoryCache?.clear() }
        done()
    }
    fun clearDefaults() = viewModelScope.launch { opener.clearDefaults(); folderPrefs.clear() }
}

val Languages = listOf("" to "System default", "en" to "English", "ar" to "العربية", "de" to "Deutsch", "es" to "Español",
    "fr" to "Français", "el" to "Ελληνικά", "it" to "Italiano", "pt" to "Português", "ru" to "Русский")

@Composable
fun SettingsScreen(onBack: () -> Unit, onPremium: () -> Unit, onLicenses: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val premium by vm.premium.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var choice by remember { mutableStateOf<String?>(null) }
    var pinDialog by remember { mutableStateOf(false) }
    var cacheLabel by remember { mutableStateOf("") }
    androidx.compose.runtime.LaunchedEffect(Unit) { cacheLabel = com.xander.files.ui.common.Format.size(vm.cacheSize()) }

    Scaffold(topBar = {
        TopAppBar(navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } }, title = { Text(stringResource(R.string.settings)) })
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState())) {
            if (!premium) Item(stringResource(R.string.go_premium), stringResource(R.string.go_premium_summary), onClick = onPremium)

            Section(R.string.appearance)
            Item(stringResource(R.string.theme), stringResource(themeLabel(s.themeMode))) { choice = "theme" }
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(stringResource(R.string.accent_color), style = MaterialTheme.typography.bodyLarge)
                Row(Modifier.padding(top = 8.dp)) {
                    Accents.forEachIndexed { i, c ->
                        Box(Modifier.padding(end = 8.dp).size(34.dp).background(c, CircleShape)
                            .border(if (s.accent == i) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                            .clickable { vm.update { it.copy(accent = i) } }, contentAlignment = Alignment.Center) {
                            if (s.accent == i) Icon(Icons.Filled.Check, null, tint = Color.White)
                        }
                    }
                }
            }
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(stringResource(R.string.skin), style = MaterialTheme.typography.bodyLarge)
                Row(Modifier.padding(top = 8.dp)) {
                    Skins.forEach { sk ->
                        val locked = sk.premium && !premium
                        FilterChip(s.skin == sk.id, { if (locked) onPremium() else vm.update { it.copy(skin = sk.id) } },
                            label = { Text(sk.id.replaceFirstChar { it.uppercase() }) },
                            leadingIcon = if (locked) ({ Icon(Icons.Filled.Lock, null, Modifier.size(16.dp)) }) else null,
                            modifier = Modifier.padding(end = 6.dp))
                    }
                }
            }
            if (android.os.Build.VERSION.SDK_INT >= 31) Toggle(stringResource(R.string.dynamic_color), s.dynamicColor) { v -> vm.update { it.copy(dynamicColor = v) } }
            Toggle(stringResource(R.string.compact_text), s.compactText, stringResource(R.string.compact_text_summary)) { v -> vm.update { it.copy(compactText = v) } }
            Item(stringResource(R.string.language), Languages.firstOrNull { it.first == s.language }?.second ?: "") { choice = "language" }

            Section(R.string.browsing)
            Item(stringResource(R.string.default_view), stringResource(if (s.viewMode == ViewMode.LIST) R.string.view_list else R.string.view_grid)) {
                vm.update { it.copy(viewMode = if (it.viewMode == ViewMode.LIST) ViewMode.GRID else ViewMode.LIST) }
            }
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text(stringResource(R.string.grid_columns, s.gridSize))
                Slider(s.gridSize.toFloat(), { v -> vm.update { it.copy(gridSize = v.toInt()) } }, valueRange = 2f..6f, steps = 3)
            }
            Item(stringResource(R.string.default_sort), stringResource(sortLabel(s.sortBy)) + if (s.sortAscending) " ↑" else " ↓") { choice = "sort" }
            Toggle(stringResource(R.string.folders_first), s.foldersFirst) { v -> vm.update { it.copy(foldersFirst = v) } }
            Toggle(stringResource(R.string.per_folder_view), s.perFolderView, stringResource(R.string.per_folder_view_summary)) { v -> vm.update { it.copy(perFolderView = v) } }
            Toggle(stringResource(R.string.show_hidden), s.showHidden) { v -> vm.update { it.copy(showHidden = v) } }
            Toggle(stringResource(R.string.show_extensions), s.showExtensions) { v -> vm.update { it.copy(showExtensions = v) } }
            Toggle(stringResource(R.string.show_folder_counts), s.showFolderCounts) { v -> vm.update { it.copy(showFolderCounts = v) } }
            Toggle(stringResource(R.string.show_thumbnails), s.thumbnails) { v -> vm.update { it.copy(thumbnails = v) } }
            Item(stringResource(R.string.default_encoding), s.defaultEncoding) { choice = "encoding" }
            Toggle(stringResource(R.string.builtin_viewers), s.builtInViewers, stringResource(R.string.builtin_viewers_summary)) { v -> vm.update { it.copy(builtInViewers = v) } }

            Section(R.string.files_section)
            Toggle(stringResource(R.string.confirm_delete), s.confirmDelete) { v -> vm.update { it.copy(confirmDelete = v) } }
            Toggle(stringResource(R.string.recycle_bin), s.recycleBinEnabled, stringResource(R.string.recycle_bin_summary)) { v -> vm.update { it.copy(recycleBinEnabled = v) } }
            if (s.recycleBinEnabled) Column(Modifier.padding(horizontal = 16.dp)) {
                Text(if (s.recycleRetentionDays == 0) stringResource(R.string.retention_forever) else stringResource(R.string.retention_days, s.recycleRetentionDays))
                Slider(s.recycleRetentionDays.toFloat(), { v -> vm.update { it.copy(recycleRetentionDays = v.toInt()) } }, valueRange = 0f..90f, steps = 17)
            }
            Toggle(stringResource(R.string.root_mode), s.rootMode, stringResource(R.string.root_mode_summary)) { v -> vm.update { it.copy(rootMode = v) } }
            Item(stringResource(R.string.reset_defaults), stringResource(R.string.reset_defaults_summary)) {
                vm.clearDefaults(); Toast.makeText(ctx, R.string.done, Toast.LENGTH_SHORT).show()
            }

            if (com.xander.files.data.nsfw.NsfwRepository.supported) Section(R.string.adult_section)
            if (com.xander.files.data.nsfw.NsfwRepository.supported) Toggle(stringResource(R.string.adult_detect), s.nsfwEnabled, stringResource(R.string.adult_detect_summary)) { v ->
                vm.update { it.copy(nsfwEnabled = v) }; if (v) vm.nsfwScan(false)
            }
            if (s.nsfwEnabled && com.xander.files.data.nsfw.NsfwRepository.supported) {
                val scanned by vm.nsfw.observeScanned().collectAsStateWithLifecycle(0)
                val work by vm.nsfw.progress().collectAsStateWithLifecycle(emptyList())
                val running = work.firstOrNull { it.state == androidx.work.WorkInfo.State.RUNNING }
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Text(stringResource(R.string.adult_threshold, s.nsfwThreshold))
                    Slider(s.nsfwThreshold.toFloat(), { v -> vm.update { it.copy(nsfwThreshold = v.toInt()) } }, valueRange = 20f..90f)
                    Text(stringResource(R.string.adult_threshold_hint), style = MaterialTheme.typography.bodySmall)
                    Text(running?.progress?.let { p -> stringResource(R.string.adult_scanning, p.getInt("done", 0), p.getInt("total", 0)) }
                        ?: stringResource(R.string.adult_scanned, scanned), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                }
                Toggle(stringResource(R.string.adult_videos), s.nsfwVideos) { v -> vm.update { it.copy(nsfwVideos = v) } }
                Toggle(stringResource(R.string.adult_charging), s.nsfwChargingOnly) { v -> vm.update { it.copy(nsfwChargingOnly = v) } }
                Item(stringResource(R.string.adult_rescan), stringResource(R.string.adult_rescan_summary)) { vm.nsfwScan(true) }
                Item(stringResource(R.string.adult_clear), "") { vm.nsfwClear() }
            }

            Section(R.string.security)
            Toggle(stringResource(R.string.app_lock), s.appLockEnabled, stringResource(R.string.app_lock_summary)) { v -> if (v) pinDialog = true else vm.setPin(null) }
            if (s.appLockEnabled) {
                Item(stringResource(R.string.change_pin), "") { pinDialog = true }
                Toggle(stringResource(R.string.use_biometric), s.appLockBiometric) { v -> vm.update { it.copy(appLockBiometric = v) } }
            }

            Section(R.string.storage_section)
            Item(stringResource(R.string.clear_cache), cacheLabel) { vm.clearCache { cacheLabel = "0 B"; Toast.makeText(ctx, R.string.done, Toast.LENGTH_SHORT).show() } }

            Section(R.string.about)
            Item(stringResource(R.string.app_name), "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})" + if (premium) " · Premium" else "") {}
            Item(stringResource(R.string.open_source_licenses), "") { onLicenses() }
        }
    }

    when (choice) {
        "theme" -> ChoiceDialog(stringResource(R.string.theme), ThemeMode.entries.map { stringResource(themeLabel(it)) }, ThemeMode.entries.indexOf(s.themeMode), { choice = null }) { i ->
            vm.update { it.copy(themeMode = ThemeMode.entries[i]) }
        }
        "sort" -> ChoiceDialog(stringResource(R.string.default_sort), SortBy.entries.map { stringResource(sortLabel(it)) }, SortBy.entries.indexOf(s.sortBy), { choice = null }) { i ->
            vm.update { it.copy(sortBy = SortBy.entries[i]) }
        }
        "encoding" -> {
            val encs = listOf("UTF-8", "UTF-16", "ISO-8859-1", "windows-1252", "windows-1251", "windows-1256", "ISO-8859-7", "KOI8-R", "GBK", "Big5", "Shift_JIS", "EUC-KR")
            ChoiceDialog(stringResource(R.string.default_encoding), encs, encs.indexOf(s.defaultEncoding), { choice = null }) { i -> vm.update { it.copy(defaultEncoding = encs[i]) } }
        }
        "language" -> ChoiceDialog(stringResource(R.string.language), Languages.map { it.second }, Languages.indexOfFirst { it.first == s.language }, { choice = null }) { i ->
            val tag = Languages[i].first
            vm.update { it.copy(language = tag) }
            AppCompatDelegate.setApplicationLocales(if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag))
        }
    }
    if (pinDialog) com.xander.files.ui.lock.SetPinDialog(onDismiss = { pinDialog = false }) { pin -> pinDialog = false; vm.setPin(pin) }
}

@Composable
private fun Section(title: Int) {
    HorizontalDivider(Modifier.padding(top = 8.dp))
    Text(stringResource(title), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp))
}

@Composable
private fun Item(title: String, summary: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        if (summary.isNotEmpty()) Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Toggle(title: String, value: Boolean, summary: String = "", onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!value) }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (summary.isNotEmpty()) Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(value, onChange)
    }
}

@Composable
fun ChoiceDialog(title: String, options: List<String>, selected: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            options.forEachIndexed { i, o ->
                Row(Modifier.fillMaxWidth().clickable { onPick(i); onDismiss() }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(i == selected, { onPick(i); onDismiss() }); Text(o)
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

private fun themeLabel(t: ThemeMode) = when (t) {
    ThemeMode.SYSTEM -> R.string.theme_system; ThemeMode.LIGHT -> R.string.theme_light; ThemeMode.DARK -> R.string.theme_dark; ThemeMode.BLACK -> R.string.theme_black
}

private fun sortLabel(by: SortBy) = when (by) {
    SortBy.NAME -> R.string.sort_name; SortBy.SIZE -> R.string.sort_size; SortBy.DATE -> R.string.sort_date; SortBy.TYPE -> R.string.sort_type
}
