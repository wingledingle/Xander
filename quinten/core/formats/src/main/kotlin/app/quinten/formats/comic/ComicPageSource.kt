package app.quinten.formats.comic

import app.quinten.analysis.page.PageText
import app.quinten.analysis.paged.PageSource
import app.quinten.model.BookMetadata
import app.quinten.model.ImageData

/**
 * Exposes comic pages as a fixed-layout [PageSource] so the OCR text mode can turn speech
 * bubbles and captions into reflowable large-print text. Page size is the image's pixel size.
 */
class ComicPageSource(private val comic: ComicDocument, private val sizeOf: (ImageData) -> Pair<Int, Int>?) : PageSource {
    override val pageCount: Int get() = comic.originalPageCount
    override val metadata: BookMetadata get() = comic.metadata
    private val sizes = HashMap<Int, Pair<Float, Float>>()

    override fun pageSize(page: Int): Pair<Float, Float> = synchronized(sizes) {
        sizes.getOrPut(page) {
            val img = comic.originalPage(page, 0)
            val s = img?.let { sizeOf(it) } ?: (1000 to 1500)
            s.first.toFloat() to s.second.toFloat()
        }
    }

    override fun textLayer(page: Int): PageText? = null
    override fun render(page: Int, targetWidth: Int): ImageData? = comic.originalPage(page, targetWidth)
    override fun cover(): ImageData? = comic.cover()
    override fun close() = comic.close()
}
