package app.quinten.analysis

import app.quinten.analysis.page.Box
import app.quinten.analysis.page.PageText
import app.quinten.analysis.page.TextLine
import app.quinten.analysis.paged.OcrEngine
import app.quinten.analysis.paged.PageSource
import app.quinten.analysis.paged.PagedBookDocument
import app.quinten.analysis.text.OcrCorrector
import app.quinten.analysis.text.TextQuality
import app.quinten.model.BookFormat
import app.quinten.model.BookMetadata
import app.quinten.model.CleanOptions
import app.quinten.model.ImageData
import app.quinten.model.TextBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OcrTextTest {
    private val c = OcrCorrector()

    @Test fun fixesDigitAndLetterConfusions() {
        val cases = mapOf(
            "It 1s a truth universally acknowledged" to "It is a truth universally acknowledged",
            "th1s was a1so f0r him" to "this was also for him",
            "1t was late, and ls the door locked?" to "It was late, and is the door locked?",
            "ls it true? 1s it?" to "Is it true? Is it?",
            "He wou1d not te11 them wh1ch" to "He would not tell them which",
            "“Tt missed him, then?”" to "“It missed him, then?”",
            "and 1 am sure that l was right" to "and I am sure that I was right",
            "Iike the Iast time" to "Like the last time",
            "THE G1RL AND TH1S" to "THE GIRL AND THIS",
            "Mr. Ho1mes sa1d" to "Mr. Holmes said",
            "1'm here" to "I'm here",
            "“Tt’s as well,” said he" to "“It’s as well,” said he",
            // Misreads measured on an archive.org scan of Sherlock Holmes.
            "“Ves, sir. I believe" to "“Yes, sir. I believe",
            "“Vou are very kind" to "“You are very kind",
            "her eves were weak" to "her eyes were weak",
            "and thev went" to "and they went",
            "as 1 always was" to "as I always was",
            "but 7 should wish" to "but I should wish",
            "with 4 third point" to "with a third point",
            "wearing 2 coat" to "wearing a coat",
            "gray in color, 2 coat of some sort" to "gray in color, a coat of some sort",
            "“\$he said thi\$ jn the morning,” thjs time" to "“She said this in the morning,” this time",
        )
        for ((bad, good) in cases) assertEquals(bad, good, c.correctLine(bad))
    }

    @Test fun leavesNumbersNamesAndCleanTextAlone() {
        val keep = listOf(
            "In 1950 and the 1960s, on the 21st of May at 10am",
            "Chapter 1 and page 1 of 12; see figure 1 below",
            "Vitamin B12 and COVID-19 in 4K",
            "Ilsa met Ian and Illinois folk in NIH and FBI offices",
            "1 2 3 4 5",
            "She said I will, and Bill was ill.",
            "It cost \$5 or \$ 10, US\$ and Raj, Taj Mahal, fjord, hijab",
            "the vet her tipster, Sc. and sc., a LOT 2 order, of par. 4 is",
            "because 4 drunken English sailors and 2 large dogs, at 7 o'clock",
        )
        for (t in keep) assertEquals(t, c.correctLine(t))
        // Words a scan ran together are split; real compounds, names and dialect stay.
        assertEquals("but you did not see, well to-do, in a hurry", c.splitJoined("but you didnot see, wellto-do, ina hurry").first)
        assertEquals(listOf(11), c.splitJoined("but you didnot").second)
        for (t in listOf("online format nearby teacups", "Lestrade and McCarthy", "_missa_ humana arma", "agoing and a-coming")) assertEquals(t, c.splitJoined(t).first)
        // Halves of a hyphenated word are left for the joined paragraph ("cance" is not "canoe").
        assertEquals("cance in this", c.correctLine("cance in this", continuesWord = true))
        assertEquals("of signifi-", c.correctLine("of signifi-"))
        // A whole public-domain novel passes through (almost) untouched.
        val alice = File("src/test/resources/fixtures/indent.txt").readLines()
        val changed = alice.count { c.correctLine(it) != it }
        assertEquals(0, changed)
    }

    private fun page(lines: List<String>, ocr: Boolean = false) =
        PageText(0, 400f, 600f, lines.mapIndexed { i, t -> TextLine(t, Box(20f, 20f + i * 14f, 380f, 32f + i * 14f), 30f + i * 14f, 11f) }, ocr = ocr)

    private val prose = File("src/test/resources/fixtures/indent.txt").readLines().filter { it.isNotBlank() && !it.startsWith("#") }.take(12)

    @Test fun qualitySeparatesTextFromGarbage() {
        assertTrue(TextQuality.score(page(prose)).knownFraction > 0.9)
        // Broken font encoding: letters shifted by one.
        val shifted = prose.map { l -> l.map { ch -> if (ch in 'a'..'y') ch + 1 else ch }.joinToString("") }
        assertTrue(TextQuality.score(page(shifted)).knownFraction < 0.3)
    }

    @Test fun garbledTextLayerIsReplacedByOcr() {
        val shifted = prose.map { l -> l.map { ch -> if (ch in 'a'..'y') ch + 1 else ch }.joinToString("") }
        var ocrCalls = 0
        val src = object : PageSource {
            override val pageCount = 3
            override val metadata = BookMetadata(title = "x")
            override fun pageSize(page: Int) = 400f to 600f
            override fun textLayer(page: Int) = page(shifted).let { PageText(page, it.width, it.height, it.lines) }
            override fun render(page: Int, targetWidth: Int) = ImageData.Raster(10, 14, IntArray(140) { -1 })
            override fun close() {}
        }
        val ocr = object : OcrEngine {
            override val isAvailable = true
            override fun recognize(image: ImageData, pageIndex: Int, pageWidth: Float, pageHeight: Float): PageText {
                ocrCalls++
                return page(prose.map { it.replace("is", "1s") }, ocr = true).let { PageText(pageIndex, it.width, it.height, it.lines, ocr = true) }
            }
        }
        val doc = PagedBookDocument(src, BookFormat.PDF, ocr = ocr, chunkPages = 3)
        val text = doc.loadChapter(0, CleanOptions.OFF).blocks.filterIsInstance<TextBlock>().joinToString(" ") { it.text }
        assertTrue(ocrCalls >= 1)
        assertTrue(text, text.contains("Alice was beginning to get very tired"))
        assertTrue(text, !text.contains(" 1s "))
    }

    @Test fun storeKeepsOcrAcrossVersionsButRedoesTextLayersAndOtherLanguages() {
        val dir = java.nio.file.Files.createTempDirectory("store").toFile()
        val st = app.quinten.analysis.paged.DirectoryPageTextStore(dir)
        st.configure("eng")
        st.save(1, page(prose, ocr = true).let { PageText(1, it.width, it.height, it.lines, ocr = true) })
        st.save(2, page(prose).let { PageText(2, it.width, it.height, it.lines) })
        assertTrue(st.load(1)!!.ocr)
        assertTrue(st.load(2) != null)
        // Text from an older extraction version (legacy flat file) is not reused; OCR is.
        File(dir, "p3.bin").writeBytes(app.quinten.analysis.paged.PageTextCodec.encode(page(prose)))
        File(dir, "p4.bin").writeBytes(app.quinten.analysis.paged.PageTextCodec.encode(page(prose, ocr = true).let { PageText(4, it.width, it.height, it.lines, ocr = true) }))
        assertEquals(null, st.load(3))
        assertTrue(st.load(4)!!.ocr)
        // Another OCR language: page 1 must be recognised again.
        val other = app.quinten.analysis.paged.DirectoryPageTextStore(dir).also { it.configure("deu") }
        assertEquals(null, other.load(1))
    }

    @Test fun garbledCheckIsSwitchedOffForGoodWhenOcrDoesNotHelp() {
        val dir = java.nio.file.Files.createTempDirectory("rescue").toFile()
        val shifted = prose.map { l -> l.map { ch -> if (ch in 'a'..'y') ch + 1 else ch }.joinToString("") }
        var calls = 0
        val src = object : PageSource {
            override val pageCount = 12
            override val metadata = BookMetadata(title = "x")
            override fun pageSize(page: Int) = 400f to 600f
            override fun textLayer(page: Int) = page(shifted).let { PageText(page, it.width, it.height, it.lines) }
            override fun render(page: Int, targetWidth: Int) = ImageData.Raster(10, 14, IntArray(140) { -1 })
            override fun close() {}
        }
        // OCR is no better than the layer (as with a foreign-language book judged by English words).
        val ocr = object : OcrEngine {
            override val isAvailable = true
            override fun recognize(image: ImageData, pageIndex: Int, pageWidth: Float, pageHeight: Float): PageText {
                calls++; return page(shifted, ocr = true).let { PageText(pageIndex, it.width, it.height, it.lines, ocr = true) }
            }
        }
        val d1 = PagedBookDocument(src, BookFormat.PDF, ocr = ocr, store = app.quinten.analysis.paged.DirectoryPageTextStore(dir), chunkPages = 3)
        for (c in 0 until d1.chapterCount) d1.loadChapter(c, CleanOptions.OFF)
        assertEquals(3, calls)
        val d2 = PagedBookDocument(src, BookFormat.PDF, ocr = ocr, store = app.quinten.analysis.paged.DirectoryPageTextStore(dir), chunkPages = 3)
        for (c in 0 until d2.chapterCount) d2.loadChapter(c, CleanOptions.OFF)
        assertEquals(3, calls)
    }

    @Test fun numberHeavyPagesKeepTheirTextLayer() {
        val table = (1..30).map { "$it.  ${it * 113}.25   ${it * 7}%   ${2000 + it}" }
        var calls = 0
        val src = object : PageSource {
            override val pageCount = 1
            override val metadata = BookMetadata(title = "x")
            override fun pageSize(page: Int) = 400f to 600f
            override fun textLayer(page: Int) = page(table).let { PageText(page, it.width, it.height, it.lines) }
            override fun render(page: Int, targetWidth: Int) = ImageData.Raster(10, 14, IntArray(140) { -1 })
            override fun close() {}
        }
        val ocr = object : OcrEngine {
            override val isAvailable = true
            override fun recognize(image: ImageData, pageIndex: Int, pageWidth: Float, pageHeight: Float): PageText? { calls++; return null }
        }
        val doc = PagedBookDocument(src, BookFormat.PDF, ocr = ocr, chunkPages = 3)
        val text = doc.loadChapter(0, CleanOptions.OFF).blocks.filterIsInstance<TextBlock>().joinToString(" ") { it.text }
        assertEquals(0, calls)
        assertTrue(text.contains("2030"))
    }
}
