package app.quinten.model

import java.io.Closeable

data class BookMetadata(
    val title: String? = null,
    val authors: List<String> = emptyList(),
    val language: String? = null,
    val series: String? = null,
    val seriesIndex: Float? = null,
    val description: String? = null,
    val publisher: String? = null,
    val identifier: String? = null,
    val subjects: List<String> = emptyList(),
    val year: String? = null,
    /** Resource path of the cover image, resolvable through [BookDocument.image]. */
    val coverResource: String? = null,
)

data class TocEntry(
    val title: String,
    val level: Int,
    val chapter: Int,
    /** Optional anchor inside the chapter (see [TextBlock.anchors]). */
    val anchor: String? = null,
    /** Pre-resolved unit inside the chapter, when known (page based formats). */
    val unit: Int = -1,
)

/** Image data returned by documents. */
sealed class ImageData {
    /** Encoded image bytes (JPEG, PNG, GIF, WebP, BMP...). */
    class Encoded(val bytes: ByteArray) : ImageData()

    /** Decoded ARGB pixels. */
    class Raster(val width: Int, val height: Int, val argb: IntArray) : ImageData()

    /** A platform image object (e.g. an Android Bitmap) produced by a platform renderer. */
    class Platform(val image: Any, val width: Int, val height: Int) : ImageData()
}

/**
 * A parsed, read-only book. Implementations never write to the source file.
 *
 * Chapters are loaded lazily so that large books open quickly. All methods are thread-safe
 * unless stated otherwise; [loadChapter] may be slow (e.g. OCR) and must be called off the UI thread.
 */
interface BookDocument : Closeable {
    val format: BookFormat
    val metadata: BookMetadata
    val toc: List<TocEntry>
    val chapterCount: Int

    fun chapterTitle(index: Int): String? = null

    /** Relative size estimate of a chapter (characters or equivalent), used for progress. */
    fun chapterWeight(index: Int): Int = 1

    /** Loads a chapter, applying clean reading [clean] options. */
    fun loadChapter(index: Int, clean: CleanOptions = CleanOptions.OFF): Chapter

    /** Returns image data for a resource referenced by an [ImageBlock] or the cover. */
    fun image(resource: String): ImageData? = null

    fun cover(): ImageData? = metadata.coverResource?.let { image(it) }

    /** Resolves an internal link target (see [LinkTarget]) to a locator. */
    fun resolveLink(target: String): Locator? {
        val t = LinkTarget.parse(target) ?: return null
        if (t.chapter !in 0 until chapterCount) return null
        if (t.anchor == null) return Locator(t.chapter, 0, 0)
        val ch = loadChapter(t.chapter)
        val idx = ch.blocks.indexOfFirst { t.anchor in it.anchors }
        if (idx < 0) return Locator(t.chapter, 0, 0)
        return Locator(t.chapter, ch.blocks[idx].firstUnit, 0)
    }

    /** Number of original fixed-layout pages, or 0 for flowing formats. */
    val originalPageCount: Int get() = 0

    /** Renders an original page (fixed-layout formats only). [targetWidth] is a hint in pixels. */
    fun originalPage(page: Int, targetWidth: Int): ImageData? = null

    /** Maps an original page to the chapter/unit containing it, for page based formats. */
    fun locatorForOriginalPage(page: Int): Locator? = null

    override fun close() {}
}

/** Internal link targets are encoded as `q:<chapter>` or `q:<chapter>#<anchor>`. */
data class LinkTarget(val chapter: Int, val anchor: String?) {
    fun encode(): String = if (anchor == null) "q:$chapter" else "q:$chapter#$anchor"

    companion object {
        fun parse(s: String): LinkTarget? {
            if (!s.startsWith("q:")) return null
            val rest = s.substring(2)
            val hash = rest.indexOf('#')
            val ch = (if (hash < 0) rest else rest.substring(0, hash)).toIntOrNull() ?: return null
            return LinkTarget(ch, if (hash < 0) null else rest.substring(hash + 1))
        }

        fun isExternal(s: String) = s.startsWith("http://") || s.startsWith("https://") || s.startsWith("mailto:")
    }
}
