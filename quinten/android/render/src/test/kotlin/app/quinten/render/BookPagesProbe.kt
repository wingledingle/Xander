package app.quinten.render

import android.os.Looper
import app.quinten.formats.BookOpener
import app.quinten.model.BookFormat
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Manual probe: BOOK=path paginates the book at phone size and prints what lands on the first pages. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BookPagesProbe {
    @Test fun pages() {
        val f = System.getenv("BOOK")?.let { File(it) }
        assumeTrue(f != null && f.isFile)
        val c = ReaderController(BookOpener.open(f!!, BookFormat.fromFileName(f.name)!!), 2.75f, 2.75f)
        c.settings = ReaderSettings(fontSizeSp = 18f)
        c.setViewport(1080 - 2 * 66, 2200 - 300)
        fun ensure(i: Int) { var g = 0; while (c.chapter(i) == null && g++ < 5000) { Thread.sleep(1); shadowOf(Looper.getMainLooper()).idle() } }
        val out = StringBuilder()
        var start: Pos? = Pos(0, 0, 0)
        var n = 0
        while (start != null && n < 400) {
            var p = c.pageFrom(start, null); var g = 0
            while (p.isLoading && g++ < 50) { ensure(p.loadingChapter); p = c.pageFrom(start, null) }
            val text = p.rows.joinToString("|") { r -> (c.layout(r.chapter, r.block) as? BlockLayout.Text)?.let { l -> l.layout.text.subSequence(l.layout.getLineStart(r.rowStart), l.layout.getLineEnd(r.rowEnd - 1)).toString() } ?: "[img]" }
            if (n < 40 || n % 50 == 0) out.append("page $n ${p.start}->${p.end} rows=${p.rows.size} chars=${text.length}: ${text.take(120).replace('\n', ' ')}\n")
            n++
            if (p.end == null || p.end!! <= p.start) break
            start = p.end
        }
        out.append("pages walked: $n\n")
        File(System.getenv("BOOK_OUT") ?: (f.path + ".pages.txt")).writeText(out.toString())
    }
}
