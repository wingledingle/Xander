package app.quinten.data

import app.quinten.model.CleanOptions
import app.quinten.model.RemovalCategory
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Per-book reader state: clean mode overrides, OCR mode, display mode. */
@Serializable
data class BookPrefs(
    /** null = follow the global default. */
    val cleanMode: Boolean? = null,
    val restoredRemovals: Set<String> = emptySet(),
    val disabledCategories: Set<String> = emptySet(),
    /** AUTO, ALWAYS, NEVER (fixed-layout formats). */
    val ocrMode: String = "AUTO",
    /** Show original pages (fit width) instead of reflowed text for fixed-layout formats. */
    val originalPages: Boolean = false,
    /** Comics: read recognised text instead of page images. */
    val comicTextMode: Boolean = false,
    val ocrLanguages: String? = null,
    /** Password of a protected PDF, kept with the book so it is asked only once (app-private storage). */
    val pdfPassword: String? = null,
) {
    fun cleanOptions(globalDefault: Boolean, preview: Boolean = false) = CleanOptions(
        enabled = cleanMode ?: globalDefault,
        preview = preview,
        restoredIds = restoredRemovals,
        disabledCategories = disabledCategories.mapNotNull { runCatching { RemovalCategory.valueOf(it) }.getOrNull() }.toSet(),
    )

    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
        fun decode(s: String?): BookPrefs = s?.let { runCatching { json.decodeFromString(serializer(), it) }.getOrNull() } ?: BookPrefs()
    }
}
