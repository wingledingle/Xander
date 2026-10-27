package app.quinten.formats

import app.quinten.model.BookDocument
import app.quinten.model.Chapter
import app.quinten.model.CleanOptions
import app.quinten.model.ImageBlock
import app.quinten.model.Locator
import app.quinten.model.TocEntry

/**
 * "Original pages" view of a fixed-layout document: one full-width page image per chapter.
 * Images are rendered at the requested width, so pages always fit the screen width.
 */
class OriginalPagesDocument(private val inner: BookDocument) : BookDocument by inner {
    override val chapterCount: Int get() = inner.originalPageCount
    override fun chapterTitle(index: Int) = "Page ${index + 1}"
    override fun chapterWeight(index: Int) = 1000
    /** Reflowed TOC entries carry page-based units (page * 4096 + line) for fixed-layout sources. */
    override val toc: List<TocEntry> get() = inner.toc.filter { it.unit >= 0 }.map { TocEntry(it.title, it.level, it.unit / 4096) }
    override fun loadChapter(index: Int, clean: CleanOptions): Chapter =
        Chapter(index, chapterTitle(index), listOf(ImageBlock("page:$index", sourcePage = index, fullPage = true, units = intArrayOf(index * 4096))))
    override fun locatorForOriginalPage(page: Int) = Locator(page, page * 4096, 0)
    override fun resolveLink(target: String): Locator? = null
}
