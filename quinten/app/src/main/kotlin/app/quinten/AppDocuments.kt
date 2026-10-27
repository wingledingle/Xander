package app.quinten

import android.content.Context
import android.graphics.Bitmap
import app.quinten.analysis.paged.DirectoryPageTextStore
import app.quinten.analysis.paged.OcrMode
import app.quinten.analysis.paged.PagedBookDocument
import app.quinten.analysis.paged.RasterDecoder
import app.quinten.data.BookPrefs
import app.quinten.data.Images
import app.quinten.formats.BookOpener
import app.quinten.formats.OriginalPagesDocument
import app.quinten.formats.comic.ComicDocument
import app.quinten.formats.comic.ComicPageSource
import app.quinten.model.BookDocument
import app.quinten.model.BookFormat
import app.quinten.model.ImageData
import app.quinten.ocr.TesseractOcrEngine
import app.quinten.pdf.PdfPageSource
import java.io.File

/** Opens any supported file as a [BookDocument] with OCR, OCR caching and display modes applied. */
class AppDocuments(private val context: Context, private val ocr: TesseractOcrEngine) {

    /** Converts platform/encoded images into ARGB rasters for analysis (illustration detection). */
    val decoder = RasterDecoder { data, maxWidth ->
        val bmp = Images.toBitmap(data, maxWidth) ?: return@RasterDecoder null
        val scaled = if (bmp.width > maxWidth) Bitmap.createScaledBitmap(bmp, maxWidth, (bmp.height.toLong() * maxWidth / bmp.width).toInt().coerceAtLeast(1), true) else bmp
        val px = IntArray(scaled.width * scaled.height)
        scaled.getPixels(px, 0, scaled.width, 0, 0, scaled.width, scaled.height)
        ImageData.Raster(scaled.width, scaled.height, px)
    }

    private fun store(bookId: Long) = DirectoryPageTextStore(File(context.filesDir, "ocr-cache/$bookId"))

    fun clearOcrCache(bookId: Long) = store(bookId).clear()

    /**
     * Opens a book only to read its title, authors and cover (library scan). No OCR engine, OCR
     * cache or page-text analysis is set up: the library reads several books at a time.
     */
    fun openForMetadata(file: File, format: BookFormat, @Suppress("UNUSED_PARAMETER") bookId: Long): BookDocument = when (format) {
        BookFormat.PDF -> PagedBookDocument(PdfPageSource(context, file), BookFormat.PDF, ocrMode = OcrMode.NEVER)
        BookFormat.DJVU -> BookOpener.open(file, format, BookOpener.PagedServices(ocrMode = OcrMode.NEVER))
        BookFormat.CBZ, BookFormat.CBR -> ComicDocument.open(file, format)
        else -> BookOpener.open(file, format)
    }

    fun open(file: File, format: BookFormat, bookId: Long, prefs: BookPrefs): BookDocument {
        val mode = runCatching { OcrMode.valueOf(prefs.ocrMode) }.getOrDefault(OcrMode.AUTO)
        prefs.ocrLanguages?.let { ocr.languages = it }
        val doc: BookDocument = when (format) {
            BookFormat.PDF -> PagedBookDocument(PdfPageSource(context, file, prefs.pdfPassword ?: ""), BookFormat.PDF, ocr, store(bookId), decoder, mode)
            BookFormat.DJVU -> BookOpener.open(file, format, BookOpener.PagedServices(ocr, store(bookId), decoder, mode))
            BookFormat.CBZ, BookFormat.CBR -> {
                val comic = ComicDocument.open(file, format)
                if (prefs.comicTextMode) {
                    val source = ComicPageSource(comic) { img -> Images.toBitmap(img, 4000)?.let { it.width to it.height } }
                    PagedBookDocument(source, format, ocr, store(bookId), decoder, OcrMode.ALWAYS, chunkPages = 2)
                } else comic
            }
            else -> BookOpener.open(file, format)
        }
        return if (prefs.originalPages && doc.originalPageCount > 0 && doc !is ComicDocument) OriginalPagesDocument(doc) else doc
    }
}
