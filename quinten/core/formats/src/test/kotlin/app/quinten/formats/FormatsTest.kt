package app.quinten.formats

import app.quinten.model.BookDocument
import app.quinten.model.BookFormat
import app.quinten.model.ImageBlock
import app.quinten.model.ImageData
import app.quinten.model.InlineStyle
import app.quinten.model.LinkSpan
import app.quinten.model.StyleSpan
import app.quinten.model.TextBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

fun fixture(name: String): File = File(requireNotNull(FormatsTest::class.java.classLoader.getResource("fixtures/$name")) { "missing $name" }.toURI())

fun BookDocument.dump(): String = buildString {
    for (i in 0 until chapterCount) {
        val ch = loadChapter(i)
        appendLine("=== chapter $i: ${ch.title}")
        for (b in ch.blocks) when (b) {
            is TextBlock -> appendLine("[${b.kind}${if (b.level > 0) b.level else ""}] ${b.text}  ${b.spans}")
            is ImageBlock -> appendLine("[IMG ${b.resource}]")
            else -> appendLine("[$b]")
        }
    }
}

fun BookDocument.allText(): String = (0 until chapterCount).joinToString("\n") { i ->
    loadChapter(i).blocks.filterIsInstance<TextBlock>().joinToString("\n") { it.text }
}

class FormatsTest {
    private fun checkSample(name: String, format: BookFormat, expectImage: Boolean = true, expectFootnote: Boolean = true, expectItalic: Boolean = true) {
        val f = fixture(name)
        assertEquals(format, BookOpener.detect(f).let { if (it == BookFormat.DOC && format == BookFormat.DOC) it else it })
        BookOpener.open(f, format).use { doc ->
            val dump = doc.dump()
            println("----- $name\n$dump")
            val text = doc.allText()
            assertTrue("$name: body text", text.contains("bright cold day in April"))
            assertTrue("$name: chapter two", text.contains("second chapter begins"))
            assertTrue("$name: dialogue kept", text.contains("Is anyone there?"))
            assertTrue("$name: at least 2 chapters (${doc.chapterCount})", doc.chapterCount >= 2)
            val blocks = (0 until doc.chapterCount).flatMap { doc.loadChapter(it).blocks }
            if (expectItalic) {
                val italic = blocks.filterIsInstance<TextBlock>().any { b ->
                    b.spans.any { it is StyleSpan && it.style == InlineStyle.ITALIC && b.text.substring(it.start, it.end) == "bright" }
                }
                assertTrue("$name: italic span", italic)
            }
            if (expectImage) {
                val img = blocks.filterIsInstance<ImageBlock>().firstOrNull()
                assertNotNull("$name: image block", img)
                val data = doc.image(img!!.resource)
                assertTrue("$name: image data", data is ImageData.Encoded && data.bytes.size > 20)
            }
            if (expectFootnote) {
                val link = blocks.filterIsInstance<TextBlock>().flatMap { it.spans }.filterIsInstance<LinkSpan>().firstOrNull { it.footnote }
                assertNotNull("$name: footnote link", link)
                val loc = doc.resolveLink(link!!.target)
                assertNotNull("$name: footnote target ${link.target}", loc)
                val ch = doc.loadChapter(loc!!.chapter)
                val (bi, _) = ch.positionOf(loc)
                val noteText = ch.blocks.drop(bi).take(3).filterIsInstance<TextBlock>().joinToString(" ") { it.text }
                assertTrue("$name: footnote text '$noteText'", noteText.contains("This is the footnote text"))
            }
            assertTrue("$name: toc", doc.toc.isNotEmpty())
        }
    }

    @Test fun epub() = checkSample("sample.epub", BookFormat.EPUB)
    @Test fun docx() = checkSample("sample.docx", BookFormat.DOCX)
    @Test fun doc() = checkSample("sample.doc", BookFormat.DOC)
    @Test fun rtfPandoc() = checkSample("sample.rtf", BookFormat.RTF, expectImage = false)
    @Test fun rtfLibreOffice() = checkSample("sample-lo.rtf", BookFormat.RTF)
    @Test fun fb2() = checkSample("sample.fb2", BookFormat.FB2, expectImage = false)

    @Test fun cbz() {
        BookOpener.open(fixture("sample.cbz"), BookFormat.CBZ).use { doc ->
            assertEquals(3, doc.chapterCount)
            val first = doc.loadChapter(0).blocks.single() as ImageBlock
            assertEquals("pages/page1.png", first.resource)
            assertEquals("pages/page10.png", (doc.loadChapter(2).blocks.single() as ImageBlock).resource)
            assertNotNull(doc.image(first.resource))
        }
    }
}
