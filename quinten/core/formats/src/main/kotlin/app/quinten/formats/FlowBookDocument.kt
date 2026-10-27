package app.quinten.formats

import app.quinten.analysis.FlowCleaner
import app.quinten.model.Block
import app.quinten.model.BookDocument
import app.quinten.model.Chapter
import app.quinten.model.CleanOptions

/** Raw, uncleaned chapter content produced by a parser. */
class RawChapter(val title: String?, val blocks: List<Block>)

/**
 * Base class for flowing (reflowable) formats. Subclasses produce raw chapters; this class
 * caches them, runs Clean Reading Mode detection once per chapter and applies display options.
 */
abstract class FlowBookDocument : BookDocument {
    private val cache = object : LinkedHashMap<Int, Pair<RawChapter, List<FlowCleaner.Detection>>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Pair<RawChapter, List<FlowCleaner.Detection>>>?) =
            size > maxCachedChapters
    }
    protected open val maxCachedChapters = 12

    protected abstract fun loadRaw(index: Int): RawChapter

    // Many e-books are made from scans and carry OCR mistakes ("1s", "th1s", "Tt"): repaired here.
    // Replacements keep every character position, so styles and links stay in place.
    private val bookWords = HashMap<String, Int>()
    private val corrector = app.quinten.analysis.text.OcrCorrector { w -> synchronized(bookWords) { bookWords[w] ?: 0 } }
    private val wordRe = Regex("\\p{L}{3,}")

    private fun repairOcr(r: RawChapter): RawChapter {
        synchronized(bookWords) {
            if (bookWords.size < 50_000) for (b in r.blocks) if (b is app.quinten.model.TextBlock) for (m in wordRe.findAll(b.text)) {
                val w = m.value.lowercase(); bookWords[w] = (bookWords[w] ?: 0) + 1
            }
        }
        var changed = false
        val blocks = r.blocks.map { b ->
            if (b is app.quinten.model.TextBlock) {
                val t = corrector.correctLine(b.text)
                if (t != b.text) { changed = true; b.copy(text = t) } else b
            } else b
        }
        return if (changed) RawChapter(r.title, blocks) else r
    }

    override fun chapterTitle(index: Int): String? =
        toc.firstOrNull { it.chapter == index }?.title

    override fun loadChapter(index: Int, clean: CleanOptions): Chapter {
        require(index in 0 until chapterCount) { "chapter $index out of range" }
        val (raw, det) = synchronized(cache) { cache[index] } ?: run {
            val r = repairOcr(loadRaw(index))
            val d = FlowCleaner.detect(index, r.blocks)
            (r to d).also { synchronized(cache) { cache[index] = it } }
        }
        return FlowCleaner.apply(index, raw.title ?: chapterTitle(index), raw.blocks, clean, det)
    }
}

/** A document whose content is fully parsed up front (small/medium formats). */
open class InMemoryDocument(
    override val format: app.quinten.model.BookFormat,
    override val metadata: app.quinten.model.BookMetadata,
    private val chapters: List<RawChapter>,
    override val toc: List<app.quinten.model.TocEntry>,
    private val images: Map<String, () -> ByteArray?> = emptyMap(),
) : FlowBookDocument() {
    override val chapterCount: Int get() = chapters.size
    override fun loadRaw(index: Int) = chapters[index]
    override fun chapterTitle(index: Int) = chapters[index].title ?: super.chapterTitle(index)
    override fun chapterWeight(index: Int) = chapters[index].blocks.sumOf { it.length }.coerceAtLeast(1)
    override fun image(resource: String): app.quinten.model.ImageData? =
        images[resource]?.invoke()?.let { app.quinten.model.ImageData.Encoded(it) }
}

class UnsupportedBookException(message: String) : Exception(message)
class DrmProtectedException(message: String = "This book is protected by DRM and cannot be opened") : Exception(message)
