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

/** Manual tool: PROBE_PDF, PROBE_FROM_PAGE, PROBE_PAGES, PROBE_OUT. Dumps reflowed text via the Android PDF source. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidPdfDumpProbe {
    @Test fun dump() {
        val path = System.getenv("PROBE_PDF"); assumeTrue(path != null)
        val from = System.getenv("PROBE_FROM_PAGE")!!.toInt(); val n = System.getenv("PROBE_PAGES")!!.toInt()
        val doc = PagedBookDocument(PdfPageSource(ApplicationProvider.getApplicationContext(), File(path!!)), BookFormat.PDF, chunkPages = 3)
        val sb = StringBuilder()
        val c0 = doc.chapterOfPage(from); val c1 = doc.chapterOfPage(from + n - 1)
        for (c in c0..c1) {
            val ch = doc.loadChapter(c, CleanOptions.ON)
            sb.append("=== ").append(c).append(" removals=").append(ch.removals.map { it.category.name + ":" + it.text }).append('\n')
            for (b in ch.blocks) if (b is TextBlock) sb.append("¶ ").append(b.text).append('\n')
        }
        File(System.getenv("PROBE_OUT")).writeText(sb.toString())
    }
}
