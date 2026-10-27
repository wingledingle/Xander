package app.quinten.analysis

import app.quinten.analysis.page.Box
import app.quinten.analysis.page.PageText
import app.quinten.analysis.page.TextLine
import app.quinten.analysis.paged.DirectoryPageTextStore
import app.quinten.analysis.paged.IllustrationDetector
import app.quinten.analysis.paged.OcrEngine
import app.quinten.analysis.paged.PageSource
import app.quinten.analysis.paged.PagedBookDocument
import app.quinten.analysis.paged.PageTextCodec
import app.quinten.model.BookFormat
import app.quinten.model.BookMetadata
import app.quinten.model.CleanOptions
import app.quinten.model.ImageBlock
import app.quinten.model.ImageData
import app.quinten.model.TextBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class PagedDocumentTest {
    private val pages = PdfFixtures.pages("indent.pdf")

    private inner class FakeSource(val withText: Boolean) : PageSource {
        override val pageCount = pages.size
        override val metadata = BookMetadata(title = "Alice")
        override fun pageSize(page: Int) = pages[page].width to pages[page].height
        override fun textLayer(page: Int) = if (withText) pages[page] else null
        override fun render(page: Int, targetWidth: Int) = ImageData.Raster(10, 14, IntArray(140) { -1 })
        override fun close() {}
    }

    private fun norm(s: String) = s.replace("­", "").replace("-", "").replace(Regex("\\s+"), " ").trim()

    private fun allText(doc: PagedBookDocument) = (0 until doc.chapterCount).flatMap { doc.loadChapter(it, CleanOptions.ON).blocks.filterIsInstance<TextBlock>() }.map { norm(it.text) }

    @Test fun textLayerDocument() {
        val doc = PagedBookDocument(FakeSource(true), BookFormat.PDF, chunkPages = 4)
        assertEquals(3, doc.chapterCount)
        val texts = allText(doc)
        val expected = PdfFixtures.expected("indent.txt").map { norm(it.removePrefix("#")) }
        val missing = expected.filter { e -> texts.none { it.contains(e) } }
        assertTrue("missing $missing", missing.isEmpty())
        assertEquals(texts.groupingBy { it }.eachCount().filter { it.value > 1 }.keys.toString(), texts.size, texts.toSet().size)
    }

    @Test fun ocrFallbackAndCache() {
        var calls = 0
        val ocr = object : OcrEngine {
            override val isAvailable = true
            override fun recognize(image: ImageData, pageIndex: Int, pageWidth: Float, pageHeight: Float): PageText? { calls++; return pages[pageIndex] }
        }
        val dir = Files.createTempDirectory("ocr").toFile()
        val store = DirectoryPageTextStore(dir)
        val doc = PagedBookDocument(FakeSource(false), BookFormat.DJVU, ocr = ocr, store = store, chunkPages = 4)
        val texts = allText(doc)
        assertTrue(texts.any { it.contains("Alice was beginning to get very tired") })
        assertEquals(pages.size, calls)
        // A new document instance re-uses the stored OCR results.
        val doc2 = PagedBookDocument(FakeSource(false), BookFormat.DJVU, ocr = ocr, store = store, chunkPages = 4)
        allText(doc2)
        assertEquals(pages.size, calls)
    }

    @Test fun noOcrShowsPageImages() {
        val doc = PagedBookDocument(FakeSource(false), BookFormat.DJVU, chunkPages = 4)
        val ch = doc.loadChapter(0)
        assertEquals(4, ch.blocks.count { it is ImageBlock && it.fullPage })
        assertTrue(doc.image((ch.blocks[0] as ImageBlock).resource) != null)
    }

    private fun comicPage(i: Int): PageText {
        // Four panels covering the page, short speech-bubble lines inside them.
        val panels = listOf(Box(20f, 20f, 290f, 390f), Box(310f, 20f, 590f, 390f), Box(20f, 410f, 290f, 780f), Box(310f, 410f, 590f, 780f))
        val bubbles = listOf("WHERE ARE YOU", "GOING?", "HOME!", "WAIT FOR ME", "NO WAY", "HA HA").mapIndexed { k, s ->
            val x = 40f + (k % 2) * 290f; val y = 60f + (k / 2) * 240f
            TextLine(s, Box(x, y, x + s.length * 7f, y + 12f), y + 10f, 11f, confidence = 60f)
        }
        return PageText(i, 610f, 800f, bubbles, panels.mapIndexed { k, b -> app.quinten.analysis.page.PageImage(b, "region:$i:$k") }, ocr = true)
    }

    @Test fun picturePagesAreDetectedButProseIsNot() {
        assertTrue(PagedBookDocument.isPicturePage(comicPage(0)))
        // Every page of the prose fixtures stays reflowable.
        for (name in listOf("indent.pdf", "columns.pdf")) for (p in PdfFixtures.pages(name)) {
            assertTrue("$name page ${p.pageIndex}", !PagedBookDocument.isPicturePage(p))
        }
        // Prose page with a large illustration: still text.
        val prose = pages[1]
        val withFigure = PageText(prose.pageIndex, prose.width, prose.height, prose.lines,
            listOf(app.quinten.analysis.page.PageImage(Box(0f, 0f, prose.width, prose.height * 0.45f), "region:x")))
        assertTrue(!PagedBookDocument.isPicturePage(withFigure))
    }

    @Test fun comicPdfShowsWholePagesNotTextScraps() {
        val ocr = object : OcrEngine {
            override val isAvailable = true
            override fun recognize(image: ImageData, pageIndex: Int, pageWidth: Float, pageHeight: Float) = comicPage(pageIndex)
        }
        val src = object : PageSource {
            override val pageCount = 6
            override val metadata = BookMetadata(title = "Comic")
            override fun pageSize(page: Int) = 610f to 800f
            override fun textLayer(page: Int): PageText? = null
            override fun render(page: Int, targetWidth: Int) = ImageData.Raster(10, 14, IntArray(140) { -1 })
            override fun close() {}
        }
        val doc = PagedBookDocument(src, BookFormat.PDF, ocr = ocr, chunkPages = 3)
        for (c in 0 until doc.chapterCount) {
            val blocks = doc.loadChapter(c, CleanOptions.ON).blocks
            assertTrue(blocks.none { it is TextBlock })
            assertEquals(3, blocks.count { it is ImageBlock && it.fullPage })
        }
    }

    @Test fun noTextLostOrRepeatedAtSectionEdgesWhateverTheOpeningOrder() {
        val ocr = object : OcrEngine {
            override val isAvailable = true
            override fun recognize(image: ImageData, pageIndex: Int, pageWidth: Float, pageHeight: Float): PageText? = pages[pageIndex]
        }
        val expected = PdfFixtures.expected("indent.txt").filter { !it.startsWith("#") }.map(::norm)
        for (chunk in listOf(1, 2, 3)) {
            val orders = run {
                val n = PagedBookDocument(FakeSource(false), BookFormat.DJVU, ocr = ocr, chunkPages = chunk).chapterCount
                listOf((0 until n).toList(), (0 until n).reversed(), (0 until n).shuffled(java.util.Random(chunk.toLong())))
            }
            for (order in orders) {
                // Scanned book: context pages are only known after OCR, as on a device.
                val doc = PagedBookDocument(FakeSource(false), BookFormat.DJVU, ocr = ocr, chunkPages = chunk)
                val byChapter = HashMap<Int, List<String>>()
                for (c in order) byChapter[c] = doc.loadChapter(c, CleanOptions.ON).blocks.filterIsInstance<TextBlock>().map { norm(it.text) }
                val all = (0 until doc.chapterCount).flatMap { byChapter[it]!! }
                for (e in expected) {
                    val hits = all.count { it.contains(e) }
                    assertEquals("chunk $chunk order $order: '${e.take(50)}'", 1, hits)
                }
            }
        }
    }

    @Test fun shortScannedPagesStayTextAndFigureLabelsAreNotRepeated() {
        fun l(t: String, y: Float, x0: Float = 40f, x1: Float = 560f) = TextLine(t, Box(x0, y, x1, y + 12f), y + 10f, 11f)
        // Chapter end on a scan: three lines over the full-page scan image.
        val end = PageText(0, 610f, 800f, listOf(l("and so they lived quietly in the old house by the river,", 60f), l("never speaking of that night again.", 74f, 40f, 330f), l("THE END", 120f, 250f, 360f)),
            listOf(app.quinten.analysis.page.PageImage(Box(0f, 0f, 610f, 800f), "page")))
        assertTrue(!PagedBookDocument.isPicturePage(end))
        val cleaned = PagedBookDocument.withoutImageText(end)
        assertEquals(3, cleaned.lines.size)
        assertTrue(cleaned.images.isEmpty())
        // A chart with axis labels inside its box: the labels go, the body and the chart stay.
        val chart = Box(100f, 300f, 500f, 520f)
        val body = (0 until 15).map { l("Body text line number $it of the report, explaining the results.", 40f + it * 14f) }
        val labels = listOf(l("Sales", 320f, 120f, 170f), l("2019  2020  2021", 500f, 200f, 400f))
        val page = PageText(1, 610f, 800f, body + labels, listOf(app.quinten.analysis.page.PageImage(chart, "region:1")))
        val c2 = PagedBookDocument.withoutImageText(page)
        assertEquals(15, c2.lines.size)
        assertEquals(1, c2.images.size)
    }

    @Test fun codecRoundTrip() {
        val p = pages[2]
        val back = PageTextCodec.decode(PageTextCodec.encode(p))!!
        assertEquals(p.lines.map { it.text }, back.lines.map { it.text })
        assertEquals(p.lines[3].runs, back.lines[3].runs)
    }

    @Test fun illustrationDetection() {
        val w = 400; val h = 600
        val px = IntArray(w * h) { -1 }
        fun fill(x0: Int, y0: Int, x1: Int, y1: Int) { for (y in y0 until y1) for (x in x0 until x1) px[y * w + x] = 0xFF000000.toInt() }
        fill(100, 200, 300, 350) // picture
        fill(40, 50, 360, 58) // a text line
        val text = PageText(0, 400f, 600f, listOf(TextLine("text", Box(40f, 50f, 360f, 58f), 58f, 8f)))
        val found = IllustrationDetector.find(ImageData.Raster(w, h, px), text, 400f, 600f)
        assertEquals(1, found.size)
        val b = found[0]
        assertTrue(b.x0 in 90f..101f && b.x1 in 299f..310f && b.y0 in 190f..201f && b.y1 in 349f..360f)
    }
}
