package app.quinten.pdf

import androidx.test.core.app.ApplicationProvider
import app.quinten.analysis.paged.PagedBookDocument
import app.quinten.model.BookFormat
import app.quinten.model.CleanOptions
import app.quinten.model.TextBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PdfPageSourceTest {
    private val fixtures = File("../../core/analysis/src/test/resources/fixtures")

    @Test fun extractsAndReflowsText() {
        val src = PdfPageSource(ApplicationProvider.getApplicationContext(), File(fixtures, "indent.pdf"))
        assertEquals(9, src.pageCount)
        val t = src.textLayer(0)!!
        assertTrue(t.lines.any { it.text.contains("Alice was beginning to get very tired") })
        val doc = PagedBookDocument(src, BookFormat.PDF)
        doc.use {
            val texts = (0 until it.chapterCount).flatMap { c -> it.loadChapter(c, CleanOptions.ON).blocks }.filterIsInstance<TextBlock>().map { b -> b.text }
            val expected = File(fixtures, "indent.txt").readLines().filter { l -> l.isNotBlank() && !l.startsWith("#") }
            fun norm(s: String) = s.replace("­", "").replace("-", "").replace(Regex("\\s+"), " ").trim()
            val have = texts.map(::norm)
            val missing = expected.map(::norm).filter { e -> have.none { h -> h.contains(e) } }
            assertTrue("missing ${missing.size}: ${missing.take(1)}", missing.isEmpty())
            assertTrue(have.none { h -> h.contains("ALICE'S ADVENTURES IN WONDERLAND") })
            assertEquals("indent", it.metadata.title) // no PDF title metadata: falls back to the file name
        }
    }

    @Test fun passwordProtectedPdf() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(ctx)
        val f = File.createTempFile("locked", ".pdf")
        com.tom_roush.pdfbox.pdmodel.PDDocument().use { d ->
            val page = com.tom_roush.pdfbox.pdmodel.PDPage()
            d.addPage(page)
            com.tom_roush.pdfbox.pdmodel.PDPageContentStream(d, page).use { cs ->
                cs.beginText()
                cs.setFont(com.tom_roush.pdfbox.pdmodel.font.PDType1Font.HELVETICA, 14f)
                cs.newLineAtOffset(72f, 700f)
                cs.showText("The secret garden was behind the old wall.")
                cs.endText()
            }
            val policy = com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy("owner", "secret", com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission())
            policy.encryptionKeyLength = 128
            d.protect(policy)
            d.save(f)
        }
        val noPw = runCatching { PdfPageSource(ctx, f) }.exceptionOrNull()
        assertTrue(noPw is PdfPasswordException && !noPw.wrongPassword)
        val bad = runCatching { PdfPageSource(ctx, f, "nope") }.exceptionOrNull()
        assertTrue(bad is PdfPasswordException && bad.wrongPassword)
        PdfPageSource(ctx, f, "secret").use { src ->
            assertTrue(src.textLayer(0)!!.lines.any { it.text.contains("secret garden") })
        }
    }
}
