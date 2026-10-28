package app.quinten.formats

import app.quinten.formats.comic.ComicDocument
import app.quinten.formats.doc.DocParser
import app.quinten.formats.docx.DocxParser
import app.quinten.formats.epub.EpubDocument
import app.quinten.formats.fb2.Fb2Parser
import app.quinten.formats.mobi.MobiParser
import app.quinten.formats.rtf.RtfParser
import app.quinten.formats.txt.TxtParser
import app.quinten.model.BookDocument
import app.quinten.model.BookFormat
import java.io.File

/** Opens the pure-JVM formats. Page-rendered formats (PDF, DjVu) are opened by platform modules. */
object BookOpener {
    fun detect(file: File, nameHint: String? = null): BookFormat? {
        if (app.quinten.formats.util.ZippedBook.isPlainZip(nameHint ?: file.name)) return app.quinten.formats.util.ZippedBook.formatOf(file)
        val head = ByteArray(4096)
        val n = file.inputStream().use { it.read(head) }
        return BookFormat.sniff(if (n > 0) head.copyOf(n) else ByteArray(0), nameHint ?: file.name)
    }

    /** Optional services for page-based formats (OCR, OCR cache, image decoding). */
    class PagedServices(
        val ocr: app.quinten.analysis.paged.OcrEngine? = null,
        val store: app.quinten.analysis.paged.PageTextStore? = null,
        val decoder: app.quinten.analysis.paged.RasterDecoder? = null,
        val ocrMode: app.quinten.analysis.paged.OcrMode = app.quinten.analysis.paged.OcrMode.AUTO,
    )

    fun open(file: File, format: BookFormat, services: PagedServices = PagedServices()): BookDocument = when (format) {
        BookFormat.EPUB -> EpubDocument(file)
        BookFormat.FB2 -> Fb2Parser.open(file)
        BookFormat.TXT -> TxtParser.open(file)
        BookFormat.RTF -> RtfParser.open(file)
        BookFormat.DOC -> {
            // Some ".doc" files are really RTF or DOCX in disguise.
            val sniffed = BookFormat.sniff(file.inputStream().use { s -> ByteArray(512).also { s.read(it) } })
            when (sniffed) {
                BookFormat.RTF -> RtfParser.open(file)
                BookFormat.DOCX -> DocxParser.open(file)
                else -> DocParser.open(file)
            }
        }
        BookFormat.DOCX -> DocxParser.open(file)
        BookFormat.MOBI, BookFormat.AZW3 -> MobiParser.open(file)
        BookFormat.CBZ, BookFormat.CBR -> ComicDocument.open(file, format)
        BookFormat.DJVU -> app.quinten.analysis.paged.PagedBookDocument(
            app.quinten.formats.djvu.DjvuDocument(file), BookFormat.DJVU, services.ocr, services.store, services.decoder, services.ocrMode,
        )
        BookFormat.PDF -> throw UnsupportedBookException("PDF must be opened by the platform reader")
    }
}
