package app.quinten.render

import android.os.Looper
import app.quinten.formats.BookOpener
import app.quinten.model.BookFormat
import app.quinten.model.TextBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PaginationTest {
    private val alice = File("../../core/formats/build/external-fixtures/alice.epub")
    private val sample = File("../../core/formats/src/test/resources/fixtures/sample.epub")

    private fun controller(fontSp: Float, w: Int = 600, h: Int = 900): ReaderController {
        val f = if (alice.isFile) alice else sample
        val c = ReaderController(BookOpener.open(f, BookFormat.EPUB), 2f, 2f)
        c.settings = ReaderSettings(fontSizeSp = fontSp)
        c.setViewport(w, h)
        return c
    }

    /** Waits for a chapter like the view does: request, then pump the main looper. */
    private fun ReaderController.ensure(i: Int) {
        var guard = 0
        while (chapter(i) == null && guard++ < 5000) { Thread.sleep(1); shadowOf(Looper.getMainLooper()).idle() }
        assertNotNull("chapter $i", chapter(i))
    }

    private fun ReaderController.loadAll() { for (i in 0 until chapterCount) ensure(i) }

    private fun ReaderController.page(start: Pos, limit: Pos? = null): Page {
        var p = pageFrom(start, limit)
        var guard = 0
        while (p.isLoading && guard++ < 50) { ensure(p.loadingChapter); p = pageFrom(start, limit) }
        return p
    }

    private fun ReaderController.prev(start: Pos): Pos? {
        ensure(start.chapter); if (start.chapter > 0) ensure(start.chapter - 1)
        return previousPageStart(start)
    }

    private fun walkForward(c: ReaderController): List<Page> {
        val pages = ArrayList<Page>()
        var start: Pos? = Pos(0, 0, 0)
        while (start != null && pages.size < 20000) {
            val p = c.page(start)
            assertTrue("page at $start has rows: loading=${p.loadingChapter} end=${p.end} err=${c.chapterError(start.chapter)} blocks=${c.chapter(start.chapter)?.blocks?.size}", p.rows.isNotEmpty() || p.end == null)
            if (p.rows.isEmpty()) break
            pages += p
            assertTrue("progress", p.end == null || p.end!! > p.start)
            start = p.end
        }
        return pages
    }

    private fun checkCoverage(c: ReaderController, pages: List<Page>) {
        // Every text row of every block appears exactly once, in order, and pages fit the height.
        val seen = HashMap<Pair<Int, Int>, MutableList<IntRange>>()
        for (p in pages) {
            assertTrue("page height", p.rows.sumOf { it.height } <= c.contentHeight)
            for (r in p.rows) seen.getOrPut(r.chapter to r.block) { ArrayList() } += r.rowStart until r.rowEnd
        }
        for (ch in 0 until c.chapterCount) {
            c.ensure(ch)
            val chapter = c.chapter(ch)!!
            for (b in chapter.blocks.indices) {
                val l = c.layout(ch, b)!!
                val ranges = seen[ch to b] ?: error("block $ch/$b never shown: ${chapter.blocks[b]}")
                val rows = ranges.flatMap { it.toList() }
                assertEquals("rows of $ch/$b", (0 until l.rowCount).toList(), rows)
                if (l is BlockLayout.Text) assertTrue(l.layout.width <= c.contentWidth)
            }
        }
    }

    @Test fun normalAndHugePrintCoverWholeBook() {
        for (size in listOf(18f, 72f)) {
            val c = controller(size)
            c.loadAll()
            val pages = walkForward(c)
            println("font $size: ${pages.size} pages, estimate ${c.estimatedPages()}")
            checkCoverage(c, pages)
            // Backward paging lands on real page starts (or chapter starts) and never loops.
            var p = pages.last()
            var steps = 0
            while (true) {
                val prev = c.prev(p.start) ?: break
                assertTrue(prev < p.start)
                p = c.page(prev, p.start)
                steps++
                assertTrue(steps < pages.size * 3)
            }
            assertEquals(Pos(0, 0, 0), p.start)
            c.close()
        }
    }

    @Test fun progressAndLocatorsRoundTrip() {
        val c = controller(22f)
        c.loadAll()
        c.ensure(c.chapterCount - 1)
        val ch = c.chapter(c.chapterCount - 1)!!
        val pos = c.posInChapter(ch, 0.5f)
        val loc = c.locatorOf(pos)!!
        c.ensure(loc.chapter)
        assertEquals(pos, c.posOf(loc))
        val prog = c.progressOf(pos)
        val (chapter, _) = c.chapterForProgress(prog)
        assertEquals(pos.chapter, chapter)
        assertTrue(prog > 0f && prog <= 1f)
        // Estimated page count matches real pagination within 25 %.
        val real = walkForward(c).size
        val est = c.estimatedPages()
        println("pages real=$real estimate=$est")
        assertTrue("estimate $est vs $real", kotlin.math.abs(est - real) < real * 0.25)
        c.close()
    }
}
