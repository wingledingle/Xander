package app.quinten.model

/** Kinds of nonessential content Clean Reading Mode can hide. */
enum class RemovalCategory(val label: String) {
    PAGE_NUMBER("Page numbers"),
    RUNNING_HEADER("Running headers"),
    RUNNING_FOOTER("Running footers"),
    WATERMARK("Watermarks"),
    REPEATED_LINE("Repeated boilerplate"),
}

/**
 * A single detected removal. [id] is stable across sessions for the same source file and is
 * used to persist per-item restorations.
 */
data class Removal(
    val id: String,
    val category: RemovalCategory,
    val text: String,
    val sourcePage: Int = -1,
)

/**
 * Display options for Clean Reading Mode. Clean mode never touches source files: removals are
 * computed on the fly and can be previewed and reversed individually or by category.
 */
data class CleanOptions(
    val enabled: Boolean = false,
    /** Show what would be removed (struck through) instead of hiding it. */
    val preview: Boolean = false,
    val restoredIds: Set<String> = emptySet(),
    val disabledCategories: Set<RemovalCategory> = emptySet(),
) {
    /** Whether a detected item is currently hidden. */
    fun hides(r: Removal): Boolean = enabled && !preview && isActive(r)

    /** Whether a detected item is currently marked (preview mode). */
    fun marks(r: Removal): Boolean = enabled && preview && isActive(r)

    fun isActive(r: Removal) = r.category !in disabledCategories && r.id !in restoredIds

    companion object {
        val OFF = CleanOptions()
        val ON = CleanOptions(enabled = true)
    }
}
