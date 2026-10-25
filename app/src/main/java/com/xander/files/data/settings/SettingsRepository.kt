package com.xander.files.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

enum class ThemeMode { SYSTEM, LIGHT, DARK, BLACK }
enum class ViewMode { LIST, GRID }
enum class SortBy { NAME, SIZE, DATE, TYPE }

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val accent: Int = 0,
    val skin: String = "default",
    val dynamicColor: Boolean = false,
    val compactText: Boolean = false,
    val viewMode: ViewMode = ViewMode.LIST,
    val gridSize: Int = 3,
    val sortBy: SortBy = SortBy.NAME,
    val sortAscending: Boolean = true,
    val foldersFirst: Boolean = true,
    val perFolderView: Boolean = false,
    val showHidden: Boolean = false,
    val showExtensions: Boolean = true,
    val showFolderCounts: Boolean = true,
    val thumbnails: Boolean = true,
    val confirmDelete: Boolean = true,
    val recycleBinEnabled: Boolean = true,
    val recycleRetentionDays: Int = 30,
    val rootMode: Boolean = false,
    val defaultEncoding: String = "UTF-8",
    val appLockEnabled: Boolean = false,
    val appLockBiometric: Boolean = false,
    val appLockPinHash: String = "",
    val onboardingDone: Boolean = false,
    val language: String = "",
)

@Singleton
class SettingsRepository @Inject constructor(@ApplicationContext context: Context) {
    private val store: DataStore<Preferences> = context.dataStore

    private object K {
        val theme = stringPreferencesKey("theme")
        val accent = intPreferencesKey("accent")
        val skin = stringPreferencesKey("skin")
        val dynamic = booleanPreferencesKey("dynamic")
        val compact = booleanPreferencesKey("compact")
        val view = stringPreferencesKey("view")
        val grid = intPreferencesKey("grid")
        val sort = stringPreferencesKey("sort")
        val asc = booleanPreferencesKey("asc")
        val foldersFirst = booleanPreferencesKey("folders_first")
        val perFolder = booleanPreferencesKey("per_folder")
        val hidden = booleanPreferencesKey("hidden")
        val ext = booleanPreferencesKey("ext")
        val counts = booleanPreferencesKey("counts")
        val thumbs = booleanPreferencesKey("thumbs")
        val confirmDelete = booleanPreferencesKey("confirm_delete")
        val bin = booleanPreferencesKey("bin")
        val binDays = intPreferencesKey("bin_days")
        val root = booleanPreferencesKey("root")
        val encoding = stringPreferencesKey("encoding")
        val lock = booleanPreferencesKey("lock")
        val lockBio = booleanPreferencesKey("lock_bio")
        val lockPin = stringPreferencesKey("lock_pin")
        val onboarding = booleanPreferencesKey("onboarding")
        val language = stringPreferencesKey("language")
    }

    val settings: Flow<AppSettings> = store.data.map { p ->
        AppSettings(
            themeMode = p[K.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
            accent = p[K.accent] ?: 0,
            skin = p[K.skin] ?: "default",
            dynamicColor = p[K.dynamic] ?: false,
            compactText = p[K.compact] ?: false,
            viewMode = p[K.view]?.let { runCatching { ViewMode.valueOf(it) }.getOrNull() } ?: ViewMode.LIST,
            gridSize = p[K.grid] ?: 3,
            sortBy = p[K.sort]?.let { runCatching { SortBy.valueOf(it) }.getOrNull() } ?: SortBy.NAME,
            sortAscending = p[K.asc] ?: true,
            foldersFirst = p[K.foldersFirst] ?: true,
            perFolderView = p[K.perFolder] ?: false,
            showHidden = p[K.hidden] ?: false,
            showExtensions = p[K.ext] ?: true,
            showFolderCounts = p[K.counts] ?: true,
            thumbnails = p[K.thumbs] ?: true,
            confirmDelete = p[K.confirmDelete] ?: true,
            recycleBinEnabled = p[K.bin] ?: true,
            recycleRetentionDays = p[K.binDays] ?: 30,
            rootMode = p[K.root] ?: false,
            defaultEncoding = p[K.encoding] ?: "UTF-8",
            appLockEnabled = p[K.lock] ?: false,
            appLockBiometric = p[K.lockBio] ?: false,
            appLockPinHash = p[K.lockPin] ?: "",
            onboardingDone = p[K.onboarding] ?: false,
            language = p[K.language] ?: "",
        )
    }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.edit { p ->
            val cur = AppSettings(
                themeMode = p[K.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
                accent = p[K.accent] ?: 0, skin = p[K.skin] ?: "default", dynamicColor = p[K.dynamic] ?: false,
                compactText = p[K.compact] ?: false,
                viewMode = p[K.view]?.let { runCatching { ViewMode.valueOf(it) }.getOrNull() } ?: ViewMode.LIST,
                gridSize = p[K.grid] ?: 3,
                sortBy = p[K.sort]?.let { runCatching { SortBy.valueOf(it) }.getOrNull() } ?: SortBy.NAME,
                sortAscending = p[K.asc] ?: true, foldersFirst = p[K.foldersFirst] ?: true,
                perFolderView = p[K.perFolder] ?: false, showHidden = p[K.hidden] ?: false,
                showExtensions = p[K.ext] ?: true, showFolderCounts = p[K.counts] ?: true,
                thumbnails = p[K.thumbs] ?: true, confirmDelete = p[K.confirmDelete] ?: true,
                recycleBinEnabled = p[K.bin] ?: true, recycleRetentionDays = p[K.binDays] ?: 30,
                rootMode = p[K.root] ?: false, defaultEncoding = p[K.encoding] ?: "UTF-8",
                appLockEnabled = p[K.lock] ?: false, appLockBiometric = p[K.lockBio] ?: false,
                appLockPinHash = p[K.lockPin] ?: "", onboardingDone = p[K.onboarding] ?: false,
                language = p[K.language] ?: "",
            )
            val n = transform(cur)
            p[K.theme] = n.themeMode.name; p[K.accent] = n.accent; p[K.skin] = n.skin; p[K.dynamic] = n.dynamicColor
            p[K.compact] = n.compactText; p[K.view] = n.viewMode.name; p[K.grid] = n.gridSize; p[K.sort] = n.sortBy.name
            p[K.asc] = n.sortAscending; p[K.foldersFirst] = n.foldersFirst; p[K.perFolder] = n.perFolderView
            p[K.hidden] = n.showHidden; p[K.ext] = n.showExtensions; p[K.counts] = n.showFolderCounts
            p[K.thumbs] = n.thumbnails; p[K.confirmDelete] = n.confirmDelete; p[K.bin] = n.recycleBinEnabled
            p[K.binDays] = n.recycleRetentionDays; p[K.root] = n.rootMode; p[K.encoding] = n.defaultEncoding
            p[K.lock] = n.appLockEnabled; p[K.lockBio] = n.appLockBiometric; p[K.lockPin] = n.appLockPinHash
            p[K.onboarding] = n.onboardingDone; p[K.language] = n.language
        }
    }
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")
