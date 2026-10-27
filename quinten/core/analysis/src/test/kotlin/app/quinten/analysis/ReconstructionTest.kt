package app.quinten.analysis

import app.quinten.analysis.page.PageCleaner
import app.quinten.analysis.page.PageReconstructor
import app.quinten.model.BlockKind
import app.quinten.model.CleanOptions
import app.quinten.model.RemovalCategory
import app.quinten.model.RemovedSpan
import app.quinten.model.TextBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconstructionTest {
    /** Hyphens are ignored: without a dictionary "pine-|apple" vs "thou-|sand" is ambiguous (see [dehyphenation]). */
    private fun norm(s: String) = s.replace("\u00AD", "").replace("-", "").replace(Regex("\\s+"), " ").trim()

    private fun check(pdf: String, txt: String) {
        val pages = PdfFixtures.pages(pdf)
        val removals = PageCleaner.detect(pages, pages.map { it.pageIndex }.toSet())
        val clean = PageReconstructor.reconstruct(0, null, pages, 0, pages.size - 1, removals, CleanOptions.ON)
        val blocks = clean.blocks.filterIsInstance<TextBlock>()
        val texts = blocks.map { norm(it.text) }
        println("== $pdf: ${pages.size} pages, ${blocks.size} blocks, removals=${removals.values.groupingBy { it.category }.eachCount()}")
        blocks.take(8).forEach { println("  [${it.kind}${it.level}] ${it.text.take(100)}") }

        // Clean mode removed header, page numbers and watermark everywhere.
        assertTrue(texts.none { it.contains("ALICE'S ADVENTURES IN WONDERLAND") })
        assertTrue(texts.none { it.contains("Downloaded from") })
        assertTrue(texts.none { it.matches(Regex("\\d{1,3}")) })
        val cats = removals.values.map { it.category }.toSet()
        assertTrue(RemovalCategory.PAGE_NUMBER in cats)
        assertTrue(RemovalCategory.RUNNING_HEADER in cats)
        assertTrue(RemovalCategory.WATERMARK in cats)
        assertEquals("one header per page", pages.size, removals.values.count { it.category == RemovalCategory.RUNNING_HEADER })

        // Every source paragraph is present, intact and unbroken (incl. across pages/columns).
        val expected = PdfFixtures.expected(txt)
        val missing = ArrayList<String>()
        for (e in expected) {
            val want = norm(e.removePrefix("#"))
            if (texts.none { it.contains(want) }) missing += want
        }
        missing.take(5).forEach { m ->
            println("MISSING: ${m.take(160)}")
            val probe = m.take(40)
            texts.filter { it.contains(probe) }.forEach { h ->
                val i = h.indices.firstOrNull { k -> k >= m.length || h[k] != m[k] } ?: h.length
                println("   DIFF at $i: want '${m.substring(maxOf(0, i - 30), minOf(m.length, i + 30))}' have '${h.substring(maxOf(0, i - 30), minOf(h.length, i + 30))}'")
            }
        }
        assertTrue("${missing.size} of ${expected.size} paragraphs not reconstructed", missing.isEmpty())
        // Headings
        val headings = blocks.filter { it.kind == BlockKind.HEADING }.map { norm(it.text) }
        assertTrue("headings $headings", headings.any { it.contains(norm("Down the Rabbit-Hole")) })
        assertTrue("headings $headings", headings.any { it.contains("The Pool of Tears") })
        // Paragraph count close to source (no spurious splits)
        assertTrue("paragraphs ${blocks.size} vs ${expected.size}", blocks.size <= expected.size + 2)

        // Clean mode off: header text and page numbers are back.
        val off = PageReconstructor.reconstruct(0, null, pages, 0, pages.size - 1, removals, CleanOptions.OFF)
        assertTrue(off.blocks.filterIsInstance<TextBlock>().any { it.text.contains("ALICE'S ADVENTURES IN WONDERLAND") })
        // Preview: removals visible as struck-through spans.
        val preview = PageReconstructor.reconstruct(0, null, pages, 0, pages.size - 1, removals, CleanOptions(enabled = true, preview = true))
        assertTrue(preview.blocks.filterIsInstance<TextBlock>().any { b -> b.spans.any { it is RemovedSpan } || b.removalId != null })
        // Restoring a single item brings back exactly that line.
        val one = removals.values.first { it.category == RemovalCategory.RUNNING_HEADER }
        val restored = PageReconstructor.reconstruct(0, null, pages, 0, pages.size - 1, removals, CleanOptions(enabled = true, restoredIds = setOf(one.id)))
        assertEquals(1, restored.blocks.filterIsInstance<TextBlock>().count { it.text.contains("ALICE'S ADVENTURES IN WONDERLAND") })
        // Disabling a category
        val noPn = PageReconstructor.reconstruct(0, null, pages, 0, pages.size - 1, removals, CleanOptions(enabled = true, disabledCategories = setOf(RemovalCategory.PAGE_NUMBER)))
        assertTrue(noPn.blocks.filterIsInstance<TextBlock>().any { it.text.trim().matches(Regex("\\d{1,3}")) })
    }

    @Test fun indentedBook() = check("indent.pdf", "indent.txt")
    @Test fun twoColumnBook() = check("columns.pdf", "columns.txt")

    @Test fun chunkedReconstructionKeepsParagraphsWhole() {
        val pages = PdfFixtures.pages("indent.pdf")
        val removals = PageCleaner.detect(pages, pages.map { it.pageIndex }.toSet())
        // Three chunks of 3 pages with one page of context each side.
        val all = (0 until 3).flatMap { c ->
            val first = c * 3; val last = minOf(pages.size - 1, first + 2)
            val ctx = pages.filter { it.pageIndex in (first - 1)..(last + 1) }
            PageReconstructor.reconstruct(c, null, ctx, first, last, removals, CleanOptions.ON).blocks.filterIsInstance<TextBlock>()
        }
        val texts = all.map { norm(it.text) }
        val expected = PdfFixtures.expected("indent.txt").map { norm(it.removePrefix("#")) }
        val missing = expected.filter { e -> texts.none { it.contains(e) } }
        assertTrue("missing ${missing.size}: ${missing.take(2)}", missing.isEmpty())
        // No duplicated paragraphs across chunk boundaries.
        assertEquals(texts.size, texts.toSet().size)
    }

    @Test fun dehyphenation() {
        assertEquals("thousand miles" to 4, PageReconstructor.join("thou-", "sand miles").let { it.first to 4 })
        assertEquals("well- Known".replace("- ", "-"), PageReconstructor.join("well-", "Known").first)
        assertEquals("end. Next", PageReconstructor.join("end.", "Next").first)
    }
}
