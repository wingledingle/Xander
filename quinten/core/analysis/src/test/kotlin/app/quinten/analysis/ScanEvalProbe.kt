package app.quinten.analysis

import app.quinten.analysis.page.Box
import app.quinten.analysis.page.PageLayout
import app.quinten.analysis.page.PageText
import app.quinten.analysis.page.StyleRun
import app.quinten.analysis.page.TextLine
import app.quinten.analysis.paged.OcrEngine
import app.quinten.analysis.paged.PageSource
import app.quinten.analysis.paged.PagedBookDocument
import app.quinten.analysis.text.OcrLines
import app.quinten.model.BookFormat
import app.quinten.model.BookMetadata
import app.quinten.model.CleanOptions
import app.quinten.model.ImageData
import app.quinten.model.TextBlock
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Manual evaluation tool for scanned books. Runs the reader's text pipeline on pages of a real
 * scan, either from the PDF's own text layer (EVAL_MODE=layer) or from Tesseract TSV output made
 * with the app's OCR model (EVAL_MODE=ocr, EVAL_TSV_DIR with p-<n>.tsv, n = 1-based page), and
 * writes the reflowed text (one paragraph per line) to EVAL_OUT.
 * The TSV lines are turned into page text the same way the Android OCR engine does it.
 */
class ScanEvalProbe {
    private fun env(k: String) = System.getenv(k)

    private fun tsvPage(f: File, page: Int, w: Float, h: Float, imgW: Int, imgH: Int): PageText {
        class Word(val block: Int, val par: Int, val line: Int, val l: Int, val t: Int, val w: Int, val h: Int, val conf: Float, val text: String)
        val words = f.readLines().drop(1).mapNotNull { row ->
            val c = row.split('\t')
            if (c.size < 12 || c[0] != "5" || c[11].isBlank()) null
            else Word(c[2].toInt(), c[3].toInt(), c[4].toInt(), c[6].toInt(), c[7].toInt(), c[8].toInt(), c[9].toInt(), c[10].toFloat(), c[11])
        }
        val sx = w / imgW; val sy = h / imgH
        val lines = ArrayList<TextLine>()
        for ((_, ws) in words.groupBy { Triple(it.block, it.par, it.line) }) {
            // Same line building as TesseractOcrEngine.
            OcrLines.line(ws.map { OcrLines.Word(it.text, it.conf, Box(it.l * sx, it.t * sy, (it.l + it.w) * sx, (it.t + it.h) * sy)) })?.let { lines += it }
        }
        val laid = PageLayout.fromLines(page, w, h, lines)
        return PageText(page, w, h, laid.lines, emptyList(), laid.columnOf, laid.columns, ocr = true)
    }

    @Test fun run() {
        val pdf = env("EVAL_PDF"); assumeTrue(pdf != null)
        val from = env("EVAL_FROM")!!.toInt(); val n = env("EVAL_N")!!.toInt()
        val mode = env("EVAL_MODE") ?: "layer"
        val pages = PdfFixtures.pages(File(pdf!!), from until from + n)
        val tsvDir = env("EVAL_TSV_DIR")?.let { File(it) }
        val imgW = (env("EVAL_IMG_W") ?: "1800").toInt()
        val src = object : PageSource {
            override val pageCount = from + n
            override val metadata = BookMetadata(title = "eval")
            override fun pageSize(page: Int) = pages.getOrNull(page - from)?.let { it.width to it.height } ?: (600f to 800f)
            override fun textLayer(page: Int): PageText? = if (mode == "layer") pages.getOrNull(page - from) else null
            override fun render(page: Int, targetWidth: Int): ImageData = ImageData.Raster(1, 1, IntArray(1))
            override fun close() {}
        }
        val ocr = object : OcrEngine {
            override val isAvailable = mode == "ocr"
            override fun recognize(image: ImageData, pageIndex: Int, pageWidth: Float, pageHeight: Float): PageText? {
                val f = tsvDir?.listFiles()?.firstOrNull { it.name.matches(Regex("p-0*${pageIndex + 1}\\.tsv")) } ?: return null
                val imgH = (imgW * pageHeight / pageWidth).toInt()
                return tsvPage(f, pageIndex, pageWidth, pageHeight, imgW, imgH)
            }
        }
        if (env("EVAL_DEBUG_IMAGES") != null) for (pg in pages.take(5)) println("DBG img p${pg.pageIndex} ${pg.width}x${pg.height} " + pg.images.map { "${it.box.width}x${it.box.height}" })
        env("EVAL_DEBUG_PAGE")?.toInt()?.let { dp ->
            val win = (dp - 2..dp + 2).mapNotNull { pg -> tsvDir?.let { ocr.recognize(src.render(pg, 1), pg, src.pageSize(pg).first, src.pageSize(pg).second) } }
            for (w in win) for ((i, l) in w.lines.take(3).withIndex()) println("DBG p${w.pageIndex} l$i y=${l.box.y0}-${l.box.y1}/${w.height} fs=${l.fontSize} key=${app.quinten.analysis.page.PageCleaner.key(l.text)} '${l.text}'")
            println("DBG removals " + app.quinten.analysis.page.PageCleaner.detect(win, setOf(dp)))
        }
        val doc = PagedBookDocument(src, BookFormat.PDF, ocr = ocr, chunkPages = 3)
        val sb = StringBuilder()
        for (c in doc.chapterOfPage(from) until doc.chapterCount) {
            for (b in doc.loadChapter(c, CleanOptions.ON).blocks) if (b is TextBlock) sb.append(b.text).append('\n')
        }
        File(env("EVAL_OUT")!!).writeText(sb.toString())
    }
}
