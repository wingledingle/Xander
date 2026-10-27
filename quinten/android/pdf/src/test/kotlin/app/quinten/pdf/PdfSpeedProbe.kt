package app.quinten.pdf

import androidx.test.core.app.ApplicationProvider
import app.quinten.analysis.paged.PagedBookDocument
import app.quinten.model.BookFormat
import app.quinten.model.CleanOptions
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Manual timing probe: PROBE_PDF=/path/book.pdf. Skipped otherwise. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PdfSpeedProbe {
    @Test fun timings() {
        val path = System.getenv("PROBE_PDF")
        assumeTrue(path != null)
        var t = System.nanoTime()
        fun lap(what: String) { val n = System.nanoTime(); println("PROBE $what: ${(n - t) / 1_000_000} ms"); t = n }
        val src = PdfPageSource(ApplicationProvider.getApplicationContext(), File(path!!))
        lap("open (${src.pageCount} pages)")
        val doc = PagedBookDocument(src, BookFormat.PDF, chunkPages = 3)
        lap("document")
        doc.toc; doc.chapterCount; lap("toc")
        for (p in 100 until 110) { src.textLayer(p) }
        lap("10 x textLayer")
        val mid = doc.chapterOfPage(200)
        doc.loadChapter(mid, CleanOptions.ON); lap("chapter at p200")
        doc.loadChapter(mid + 1, CleanOptions.ON); lap("next chapter")
        for (p in doc.pagesOf(mid + 3).let { (it.first - 1)..(it.last + 1) }) doc.pageText(p)
        lap("pageText for chapter+3 (5 pages)")
        doc.loadChapter(mid + 3, CleanOptions.ON); lap("loadChapter with pages cached")
        doc.loadChapter(mid + 3, CleanOptions.OFF); lap("loadChapter cached, clean off")
        val me = Thread.currentThread()
        val counts = HashMap<String, Int>()
        val running = java.util.concurrent.atomic.AtomicBoolean(true)
        val sampler = Thread {
            while (running.get()) {
                val st = me.stackTrace
                val frames = st.filter { it.className.startsWith("app.quinten") }.take(3).joinToString(" < ") { it.className.substringAfterLast('.') + "." + it.methodName + ":" + it.lineNumber }
                val top = st.firstOrNull()?.let { it.className.substringAfterLast('.') + "." + it.methodName } ?: "?"
                counts.merge("$top | $frames", 1, Int::plus)
                Thread.sleep(2)
            }
        }.apply { isDaemon = true; start() }
        repeat(5) { doc.loadChapter(mid + 3, CleanOptions.ON) }
        running.set(false); sampler.join()
        lap("5x loadChapter sampled")
        counts.entries.sortedByDescending { it.value }.take(15).forEach { println("PROBE sample ${it.value}: ${it.key}") }
    }
}
