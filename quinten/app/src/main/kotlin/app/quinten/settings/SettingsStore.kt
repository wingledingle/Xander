package app.quinten.settings

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.quinten.render.ReaderSettings
import app.quinten.render.ReaderTheme
import app.quinten.render.TextAlign
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

// A corrupt settings file (e.g. power loss while writing) is replaced with defaults instead of
// crashing every launch.
private val Context.dataStore by preferencesDataStore(
    "settings",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

enum class LibrarySort { RECENT, TITLE, AUTHOR, ADDED, PROGRESS }

/** App preferences beyond the typography settings. */
data class AppPrefs(
    val cleanModeDefault: Boolean = true,
    val keepScreenOn: Boolean = true,
    val volumeKeysTurnPages: Boolean = true,
    val librarySort: LibrarySort = LibrarySort.RECENT,
    val libraryGrid: Boolean = true,
    val ttsRate: Float = 1.0f,
    val ttsPitch: Float = 1.0f,
    /** Read-aloud voices: "system" (the phone's) or "kokoro". */
    val ttsEngine: String = "system",
    val kokoroVoice: String = "af_heart",
    val ocrLanguages: String = "eng",
    val autoScanOnStart: Boolean = true,
    /** Follow the system dark theme with the night reading theme. */
    val autoNight: Boolean = false,
    val nightTheme: String = ReaderTheme.NIGHT.id,
    val screenBrightness: Float = -1f,
    val orientationLock: Int = -1,
    /** App colour scheme id, see [app.quinten.ui.theme.AppThemes]. */
    val uiTheme: String = "system",
    /** "system", "light", "dark" or "black". */
    val uiDarkMode: String = "system",
)

class SettingsStore(private val context: Context) {
    private object K {
        val fontSize = floatPreferencesKey("fontSize")
        val fontFamily = stringPreferencesKey("fontFamily")
        val bold = booleanPreferencesKey("bold")
        val lineSpacing = floatPreferencesKey("lineSpacing")
        val paragraphSpacing = floatPreferencesKey("paragraphSpacing")
        val indent = floatPreferencesKey("indent")
        val letterSpacing = floatPreferencesKey("letterSpacing")
        val marginH = intPreferencesKey("marginH")
        val marginV = intPreferencesKey("marginV")
        val align = stringPreferencesKey("align")
        val hyphenation = booleanPreferencesKey("hyphenation")
        val paginated = booleanPreferencesKey("paginated")
        val showImages = booleanPreferencesKey("showImages")
        val respectAlign = booleanPreferencesKey("respectAlign")
        val theme = stringPreferencesKey("theme")
        val statusBar = booleanPreferencesKey("statusBar")
        val cleanDefault = booleanPreferencesKey("cleanDefault")
        val keepScreenOn = booleanPreferencesKey("keepScreenOn")
        val volumeKeys = booleanPreferencesKey("volumeKeys")
        val sort = stringPreferencesKey("sort")
        val grid = booleanPreferencesKey("grid")
        val ttsRate = floatPreferencesKey("ttsRate")
        val ttsPitch = floatPreferencesKey("ttsPitch")
        val ttsEngine = stringPreferencesKey("ttsEngine")
        val kokoroVoice = stringPreferencesKey("kokoroVoice")
        val ocrLangs = stringPreferencesKey("ocrLangs")
        val autoScan = booleanPreferencesKey("autoScan")
        val autoNight = booleanPreferencesKey("autoNight")
        val nightTheme = stringPreferencesKey("nightTheme")
        val uiTheme = stringPreferencesKey("uiTheme")
        val uiDark = stringPreferencesKey("uiDark")
        val brightness = floatPreferencesKey("brightness")
        val orientation = intPreferencesKey("orientation")
    }

    private val data: Flow<Preferences> = context.dataStore.data.catch { e ->
        // Unreadable settings: run with defaults rather than crash.
        if (e is kotlinx.coroutines.CancellationException) throw e
        emit(emptyPreferences())
    }
    val reader: Flow<ReaderSettings> = data.map { readerFrom(it) }
    val app: Flow<AppPrefs> = data.map { appFrom(it) }

    suspend fun currentReader() = reader.first()
    suspend fun currentApp() = app.first()

    private fun readerFrom(p: Preferences): ReaderSettings {
        val d = ReaderSettings()
        return ReaderSettings(
            fontSizeSp = p[K.fontSize] ?: d.fontSizeSp,
            fontFamily = p[K.fontFamily] ?: d.fontFamily,
            boldText = p[K.bold] ?: d.boldText,
            lineSpacing = p[K.lineSpacing] ?: d.lineSpacing,
            paragraphSpacing = p[K.paragraphSpacing] ?: d.paragraphSpacing,
            firstLineIndent = p[K.indent] ?: d.firstLineIndent,
            letterSpacing = p[K.letterSpacing] ?: d.letterSpacing,
            marginHorizontalDp = p[K.marginH] ?: d.marginHorizontalDp,
            marginVerticalDp = p[K.marginV] ?: d.marginVerticalDp,
            align = p[K.align]?.let { runCatching { TextAlign.valueOf(it) }.getOrNull() } ?: d.align,
            hyphenation = p[K.hyphenation] ?: d.hyphenation,
            paginated = p[K.paginated] ?: d.paginated,
            showImages = p[K.showImages] ?: d.showImages,
            respectAlignment = p[K.respectAlign] ?: d.respectAlignment,
            theme = ReaderTheme.byId(p[K.theme]),
            showStatusBar = p[K.statusBar] ?: d.showStatusBar,
        )
    }

    private fun appFrom(p: Preferences): AppPrefs {
        val d = AppPrefs()
        return AppPrefs(
            cleanModeDefault = p[K.cleanDefault] ?: d.cleanModeDefault,
            keepScreenOn = p[K.keepScreenOn] ?: d.keepScreenOn,
            volumeKeysTurnPages = p[K.volumeKeys] ?: d.volumeKeysTurnPages,
            librarySort = p[K.sort]?.let { runCatching { LibrarySort.valueOf(it) }.getOrNull() } ?: d.librarySort,
            libraryGrid = p[K.grid] ?: d.libraryGrid,
            ttsRate = p[K.ttsRate] ?: d.ttsRate,
            ttsPitch = p[K.ttsPitch] ?: d.ttsPitch,
            ttsEngine = p[K.ttsEngine] ?: d.ttsEngine,
            kokoroVoice = p[K.kokoroVoice] ?: d.kokoroVoice,
            ocrLanguages = p[K.ocrLangs] ?: d.ocrLanguages,
            autoScanOnStart = p[K.autoScan] ?: d.autoScanOnStart,
            autoNight = p[K.autoNight] ?: d.autoNight,
            nightTheme = p[K.nightTheme] ?: d.nightTheme,
            screenBrightness = p[K.brightness] ?: d.screenBrightness,
            orientationLock = p[K.orientation] ?: d.orientationLock,
            uiTheme = p[K.uiTheme] ?: d.uiTheme,
            uiDarkMode = p[K.uiDark] ?: d.uiDarkMode,
        )
    }

    suspend fun updateReader(f: (ReaderSettings) -> ReaderSettings) {
        context.dataStore.edit { p ->
            val s = f(readerFrom(p))
            p[K.fontSize] = s.fontSizeSp.coerceIn(ReaderSettings.MIN_FONT, ReaderSettings.MAX_FONT)
            p[K.fontFamily] = s.fontFamily
            p[K.bold] = s.boldText
            p[K.lineSpacing] = s.lineSpacing
            p[K.paragraphSpacing] = s.paragraphSpacing
            p[K.indent] = s.firstLineIndent
            p[K.letterSpacing] = s.letterSpacing
            p[K.marginH] = s.marginHorizontalDp
            p[K.marginV] = s.marginVerticalDp
            p[K.align] = s.align.name
            p[K.hyphenation] = s.hyphenation
            p[K.paginated] = s.paginated
            p[K.showImages] = s.showImages
            p[K.respectAlign] = s.respectAlignment
            p[K.theme] = s.theme.id
            p[K.statusBar] = s.showStatusBar
        }
    }

    suspend fun updateApp(f: (AppPrefs) -> AppPrefs) {
        context.dataStore.edit { p ->
            val a = f(appFrom(p))
            p[K.cleanDefault] = a.cleanModeDefault
            p[K.keepScreenOn] = a.keepScreenOn
            p[K.volumeKeys] = a.volumeKeysTurnPages
            p[K.sort] = a.librarySort.name
            p[K.grid] = a.libraryGrid
            p[K.ttsRate] = a.ttsRate
            p[K.ttsPitch] = a.ttsPitch
            p[K.ttsEngine] = a.ttsEngine
            p[K.kokoroVoice] = a.kokoroVoice
            p[K.ocrLangs] = a.ocrLanguages
            p[K.autoScan] = a.autoScanOnStart
            p[K.autoNight] = a.autoNight
            p[K.nightTheme] = a.nightTheme
            p[K.brightness] = a.screenBrightness
            p[K.orientation] = a.orientationLock
            p[K.uiTheme] = a.uiTheme
            p[K.uiDark] = a.uiDarkMode
        }
    }
}
