package app.quinten.pdf

import androidx.test.core.app.ApplicationProvider
import app.quinten.analysis.paged.PagedBookDocument
import app.quinten.model.BookFormat
import app.quinten.model.CleanOptions
import app.quinten.model.TextBlock
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Manual quality probe: reflows every PDF in $PDF_CORPUS through the app's real pipeline (PdfPageSource ->
 * PagedBookDocument) and writes <name>.reflow.txt next to it, for scoring against a reference extractor.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PdfCorpusProbe {
    @Test fun dump() {
        val dir = System.getenv("PDF_CORPUS")?.let(::File)
        assumeTrue(dir != null && dir.isDirectory)
        val pages = (System.getenv("PDF_PAGES") ?: "25").toInt()
        for (f in dir!!.listFiles { x -> x.name.endsWith(".pdf") }!!.sorted()) {
            val out = StringBuilder()
            runCatching {
                PagedBookDocument(LimitedSource(PdfPageSource(ApplicationProvider.getApplicationContext(), f), pages), BookFormat.PDF).use { doc ->
                    for (c in 0 until doc.chapterCount) for (b in doc.loadChapter(c, CleanOptions()).blocks) if (b is TextBlock) out.append(b.text).append("\n\n")
                }
            }.onFailure { out.append("FAILED: $it") }
            File(dir, f.nameWithoutExtension + ".reflow.txt").writeText(out.toString())
        }
    }

    private class LimitedSource(private val s: PdfPageSource, private val n: Int) : app.quinten.analysis.paged.PageSource by s {
        override val pageCount: Int get() = minOf(n, s.pageCount)
    }
}
