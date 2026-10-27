package app.quinten.analysis

import app.quinten.analysis.page.PageText
import app.quinten.analysis.paged.PageSource
import app.quinten.analysis.paged.PagedBookDocument
import app.quinten.model.BookFormat
import app.quinten.model.BookMetadata
import app.quinten.model.CleanOptions
import app.quinten.model.ImageData
import app.quinten.model.TextBlock
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Dumps reflowed text of real PDFs given by -Dprobe.pdf=... (manual inspection tool). */
class RealPdfProbe {
    @Test fun dump() {
        val path = System.getProperty("probe.pdf") ?: System.getenv("PROBE_PDF")
        assumeTrue(path != null)
        val f = File(path!!)
        val from = (System.getenv("PROBE_FROM") ?: "0").toInt()
        val n = (System.getenv("PROBE_N") ?: "30").toInt()
        val t0 = System.currentTimeMillis()
        val pages = PdfFixtures.pages(f, from until from + n)
        println("extract ${pages.size} pages: ${System.currentTimeMillis() - t0} ms")
        if (System.getenv("PROBE_LINES") != null) {
            val sb = StringBuilder()
            for (pg in pages) {
                sb.append("--- page ${pg.pageIndex} ${pg.width}x${pg.height} body=${app.quinten.analysis.page.PageCleaner.bodyFontSize(pages)}\n")
                for (l in pg.lines.take(4) + pg.lines.takeLast(3)) sb.append("  ${l}\n")
            }
            File(System.getenv("PROBE_OUT") + ".lines").writeText(sb.toString())
        }
        val src = object : PageSource {
            override val pageCount = from + n
            override val metadata = BookMetadata(title = f.name)
            override fun pageSize(page: Int) = (pages.getOrNull(page - from)?.let { it.width to it.height }) ?: (600f to 800f)
            override fun textLayer(page: Int): PageText? = pages.getOrNull(page - from)
            override fun render(page: Int, targetWidth: Int): ImageData? = null
            override fun close() {}
        }
        val doc = PagedBookDocument(src, BookFormat.PDF, chunkPages = 3)
        val out = StringBuilder()
        for (c in doc.chapterOfPage(from) until doc.chapterCount) {
            val ch = doc.loadChapter(c, CleanOptions.ON)
            out.append("=== chapter $c removals=${ch.removals.map { it.category.name + ":" + it.text }}\n")
            for (b in ch.blocks) out.append(if (b is TextBlock) "¶ " + b.text + "\n" else "[${b::class.simpleName}]\n")
        }
        File(System.getenv("PROBE_OUT") ?: "build/probe.txt").writeText(out.toString())
    }
}
