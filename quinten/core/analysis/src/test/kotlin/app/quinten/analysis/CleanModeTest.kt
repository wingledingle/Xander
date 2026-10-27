package app.quinten.analysis

import app.quinten.analysis.page.Box
import app.quinten.analysis.page.PageCleaner
import app.quinten.analysis.page.PageReconstructor
import app.quinten.analysis.page.PageText
import app.quinten.analysis.page.StyleRun
import app.quinten.analysis.page.TextLine
import app.quinten.model.BlockKind
import app.quinten.model.CleanOptions
import app.quinten.model.ContentBuilder
import app.quinten.model.LinkSpan
import app.quinten.model.RemovalCategory
import app.quinten.model.TextBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CleanModeTest {
    private fun line(text: String, y: Float, size: Float = 10f, x0: Float = 50f, x1: Float = 350f, runs: List<StyleRun>? = null) =
        TextLine(text, Box(x0, y - size, x1, y), y, size, runs ?: listOf(StyleRun(0, text.length, size, false, false)))

    private fun page(i: Int, lines: List<TextLine>) = PageText(i, 400f, 600f, lines)

    private val filler = "the quick brown fox jumps over the lazy dog and keeps running far away"

    private val prose = "It was a bright cold day in April and the clocks were striking thirteen while Winston Smith slipped quickly through the glass doors of Victory Mansions though not quickly enough to prevent a swirl of gritty dust from entering along with him The hallway smelt of boiled cabbage and old rag mats At one end of it a coloured poster too large for indoor display had been tacked to the wall".split(" ").windowed(7, 3).map { it.joinToString(" ") }

    @Test fun detectionIsFastOnDensePages() {
        // 15 pages x 45 lines: used to take over a second per chapter (keys recomputed per line pair).
        val pages = (0 until 15).map { i -> page(i, listOf(line("THE BOOK TITLE ${i + 1}", 20f, 9f)) + (0 until 45).map { k -> line(prose[(i * 45 + k) % prose.size] + " $k", 40f + k * 12f) }) }
        PageCleaner.detect(pages, setOf(6, 7, 8)) // warm-up
        val t0 = System.nanoTime()
        repeat(5) { PageCleaner.detect(pages, setOf(6, 7, 8)) }
        val ms = (System.nanoTime() - t0) / 5_000_000
        assertTrue("detect took $ms ms", ms < 60)
    }

    @Test fun realTextIsNotRemovedAsHeaderOrWatermark() {
        fun body(i: Int, first: String, extra: List<TextLine> = emptyList()) = page(i,
            listOf(line(first, 40f)) + (1 until 30).map { k -> line(prose[(i * 30 + k) % prose.size], 40f + k * 12f) } + extra)
        val pages = listOf(
            body(0, "HAMLET."),
            body(1, "HAMLET."),
            body(2, "“Yes.”"),
            body(3, "“Yes.”", listOf(line("Downloaded from www.example-ebooks.com", 590f, 7f))),
            body(4, "he said that it was confidential and must not be repeated", listOf(line("Downloaded from www.example-ebooks.com", 590f, 7f))),
            body(5, "Name   Age   Town", listOf(line("Downloaded from www.example-ebooks.com", 590f, 7f))),
            body(6, "Name   Age   Town"),
        )
        val r = PageCleaner.detect(pages, pages.indices.toSet())
        for (i in 0..6) assertTrue("first line of page $i removed: ${r[i to 0]}", r[i to 0] == null)
        // The real watermark in the bottom margin is still removed.
        assertEquals(RemovalCategory.WATERMARK, r[3 to 30]?.category)
        assertEquals(RemovalCategory.WATERMARK, r[4 to 30]?.category)
    }

    @Test fun ocrMisreadRomanPageNumbersAreRemoved() {
        val numbers = listOf("XXV", "XXV1", "XXV1l", "XXV111", "XX1X", "XXX")
        val pages = (0 until 6).map { i ->
            page(i, (0 until 25).map { k -> line(prose[(i * 25 + k) % prose.size], 60f + k * 12f) } + line(numbers[i], 580f, 9f, 180f, 220f))
        }
        val r = PageCleaner.detect(pages, pages.indices.toSet())
        for (i in 0 until 6) assertEquals("page $i ${numbers[i]}", RemovalCategory.PAGE_NUMBER, r[i to 25]?.category)
    }

    @Test fun scannedRunningHeadersWithMisreadNumbersAndChangingSectionTitles() {
        // Real-world patterns: OCR misreads roman page numbers; number alternates sides; odd pages
        // carry the current section title (seen in archive.org scans and LaTeX books).
        val headers = listOf("INTRODUCTION XVil", "XV111 INTRODUCTION", "INTRODUCTION X1X", "xX INTRODUCTION", "INTRODUCTION XX1", "XXii INTRODUCTION")
        val ocr = (0 until 6).map { i ->
            page(i, listOf(line(headers[i], 20f, 9f)) + (0 until 20).map { k -> line(prose[(i * 20 + k) % prose.size], 50f + k * 12f) })
        }
        val r1 = PageCleaner.detect(ocr, (0 until 6).toSet())
        for (i in 0 until 6) assertEquals("page $i", RemovalCategory.RUNNING_HEADER, r1[i to 0]?.category)
        assertEquals(6, r1.size)

        val titles = listOf("10 Chapter 2. Variables", "2.4. Script mode 11", "12 Chapter 2. Variables", "2.8. Debugging 13", "14 Chapter 2. Variables", "2.10. Exercises 15")
        val latex = (0 until 6).map { i ->
            page(i, listOf(line(titles[i], 20f, 9f)) + (0 until 20).map { k -> line(prose[(i * 20 + k) % prose.size], 50f + k * 12f) })
        }
        val r2 = PageCleaner.detect(latex, (0 until 6).toSet())
        for (i in 0 until 6) assertTrue("page $i ${titles[i]}", r2[i to 0] != null)
        assertEquals(6, r2.size)
        // Ordinary words made of roman-numeral letters are not mistaken for numbers.
        assertTrue(PageCleaner.key("CIVIL WAR") == "civilwar")
    }

    @Test fun smallTypeQuotationAtPageBottomStaysInPlace() {
        val bodyLines = (0 until 20).map { k -> line(prose[k % prose.size] + " and so on", 60f + k * 12f) }
        val quote = listOf(
            line("12 men came down the hill that night, singing,", 400f, size = 8.5f, x0 = 80f, x1 = 320f),
            line("and no one in the town would ever forget it.", 411f, size = 8.5f, x0 = 80f, x1 = 320f),
        )
        val p = page(0, bodyLines + quote)
        val ch = PageReconstructor.reconstruct(0, null, listOf(p), 0, 0, emptyMap(), CleanOptions.ON)
        val blocks = ch.blocks.filterIsInstance<TextBlock>()
        assertTrue(blocks.none { it.kind == BlockKind.FOOTNOTE })
        // Still at the end of the page's text, in reading order.
        assertTrue(blocks.takeLast(2).joinToString(" ") { it.text }.contains("12 men came down the hill"))
    }

    @Test fun footnotesAreLinkedAndPlacedAfterParagraph() {
        val ref = "This sentence has a note1 and continues here with more words to fill"
        val runs = listOf(StyleRun(0, 24, 10f, false, false), StyleRun(24, 25, 6f, false, false, superscript = true), StyleRun(25, ref.length, 10f, false, false))
        val p = page(0, listOf(
            line(ref, 100f, runs = runs),
            line("$filler.", 112f, x1 = 200f),
            line("Second paragraph starts here and is long enough to be body text.", 140f),
            line("1 This is the footnote text.", 560f, size = 8f, x1 = 200f),
        ))
        val ch = PageReconstructor.reconstruct(0, null, listOf(p), 0, 0, emptyMap(), CleanOptions.ON)
        val blocks = ch.blocks.filterIsInstance<TextBlock>()
        blocks.forEach { println("[${it.kind}] ${it.text} ${it.spans} ${it.anchors}") }
        val note = blocks.single { it.kind == BlockKind.FOOTNOTE }
        assertEquals(listOf("fn0_1"), note.anchors)
        val link = blocks[0].spans.filterIsInstance<LinkSpan>().single()
        assertTrue(link.footnote)
        assertEquals("q:0#fn0_1", link.target)
        // Notes follow the last paragraph of their page (which may continue onto the next page).
        assertEquals(2, blocks.indexOf(note))
    }

    @Test fun headingsChapterNumbersAndDialogueArePreserved() {
        val pages = (0 until 6).map { i ->
            val lines = ArrayList<TextLine>()
            lines += line("THE RUNNING TITLE", 30f, size = 8f, x0 = 150f, x1 = 250f)
            if (i == 3) {
                lines += line("12", 70f, size = 18f, x0 = 190f, x1 = 210f) // chapter number, large
                lines += line("Chapter Twelve", 100f, size = 18f, x0 = 120f, x1 = 280f)
            }
            lines += line("“No.”", 150f, x1 = 80f) // repeated dialogue line in the body
            lines += line("$filler.", 170f)
            lines += line("${i + 41}", 585f, size = 9f, x0 = 195f, x1 = 205f)
            page(i, lines)
        }
        val removals = PageCleaner.detect(pages, pages.map { it.pageIndex }.toSet())
        val cats = removals.values.groupingBy { it.category }.eachCount()
        assertEquals(6, cats[RemovalCategory.RUNNING_HEADER])
        assertEquals(6, cats[RemovalCategory.PAGE_NUMBER])
        assertEquals(12, removals.size)
        val ch = PageReconstructor.reconstruct(0, null, pages, 0, 5, removals, CleanOptions.ON)
        val texts = ch.blocks.filterIsInstance<TextBlock>().map { it.text }
        assertTrue("chapter number kept: $texts", texts.any { it.startsWith("12") })
        assertTrue(texts.any { it.contains("Chapter Twelve") })
        assertEquals(6, texts.count { it == "“No.”" })
        assertFalse(texts.any { it.contains("RUNNING TITLE") })
    }

    @Test fun loneNumberNearTopIsNotAPageNumber() {
        val p = page(0, listOf(line("7", 75f, x0 = 195f, x1 = 205f), line(filler, 120f), line(filler, 132f)))
        assertTrue(PageCleaner.detect(listOf(p), setOf(0)).isEmpty())
    }

    @Test fun watermarkAnywhere() {
        val pages = (0 until 3).map { i -> page(i, listOf(line(filler, 100f), line("Scanned by BookLover for personal use only", 300f), line(filler, 330f))) }
        val r = PageCleaner.detect(pages, setOf(0, 1, 2))
        assertEquals(3, r.values.count { it.category == RemovalCategory.WATERMARK })
    }

    private fun blocks(vararg texts: String) = ContentBuilder().apply { texts.forEach { beginBlock(); text(it) } }.build()

    @Test fun flowCleanerDetectsConvertedPdfArtifacts() {
        val para = "It was a long paragraph of the story with enough words to look like prose text in a novel, ".repeat(10)
        val texts = ArrayList<String>()
        texts += "1" // chapter number at the very start
        for (i in 0 until 8) {
            texts += para
            texts += "${i + 10}"
            texts += "THE GREAT NOVEL"
            texts += "Quoth the Raven “Nevermore.”"
        }
        val b = blocks(*texts.toTypedArray())
        val det = FlowCleaner.detect(0, b)
        val byCat = det.groupingBy { it.removal.category }.eachCount()
        println(byCat)
        assertEquals(8, byCat[RemovalCategory.PAGE_NUMBER])
        assertEquals(8, byCat[RemovalCategory.RUNNING_HEADER])
        val ch = FlowCleaner.apply(0, null, b, CleanOptions.ON, det)
        val out = ch.blocks.filterIsInstance<TextBlock>().map { it.text }
        assertEquals("1", out.first())
        assertEquals(8, out.count { it.startsWith("Quoth") })
        assertFalse(out.contains("THE GREAT NOVEL"))
        val preview = FlowCleaner.apply(0, null, b, CleanOptions(enabled = true, preview = true), det)
        assertEquals(16, preview.blocks.count { (it as TextBlock).removalId != null })
        val restored = FlowCleaner.apply(0, null, b, CleanOptions(enabled = true, disabledCategories = setOf(RemovalCategory.RUNNING_HEADER)), det)
        assertEquals(8, restored.blocks.count { (it as TextBlock).text == "THE GREAT NOVEL" })
    }

    @Test fun flowCleanerRemovesCentredPageNumbersOfConvertedBooks() {
        // PDF-to-EPUB conversions put each page number on its own centred line between paragraphs.
        val para = "It was a long paragraph of the story with enough words to look like prose text in a novel. ".repeat(10)
        val list = ArrayList<app.quinten.model.Block>()
        for (i in 1..6) {
            list += TextBlock(para, units = intArrayOf(list.size))
            list += TextBlock("${i + 40}", align = app.quinten.model.AlignHint.CENTER, units = intArrayOf(list.size))
        }
        val det = FlowCleaner.detect(0, list)
        assertEquals(6, det.count { it.removal.category == RemovalCategory.PAGE_NUMBER })
    }

    @Test fun flowCleanerLeavesNormalBooksAlone() {
        val b = blocks("Chapter 1", "Some text.", "* * *", "More text.", "* * *", "Even more.", "* * *", "End.", "* * *")
        assertTrue(FlowCleaner.detect(0, b).isEmpty())
    }
}
