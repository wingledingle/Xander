package app.quinten.formats

import app.quinten.formats.docx.DocxParser
import app.quinten.model.BlockKind
import app.quinten.model.TextBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DocxExtraTest {
    private val ns = """xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:mc="http://schemas.openxmlformats.org/markup-compatibility/2006" """ +
        """xmlns:wps="http://schemas.microsoft.com/office/word/2010/wordprocessingShape" xmlns:v="urn:schemas-microsoft-com:vml" xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing" """ +
        """xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships""""

    private fun docx(body: String, numbering: String? = null): File {
        val f = File.createTempFile("tst", ".docx").also { it.deleteOnExit() }
        ZipOutputStream(f.outputStream()).use { z ->
            fun put(n: String, s: String) { z.putNextEntry(ZipEntry(n)); z.write(s.toByteArray()); z.closeEntry() }
            put("[Content_Types].xml", """<?xml version="1.0"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"/>""")
            put("word/document.xml", """<?xml version="1.0"?><w:document $ns><w:body>$body</w:body></w:document>""")
            if (numbering != null) put("word/numbering.xml", """<?xml version="1.0"?><w:numbering $ns>$numbering</w:numbering>""")
        }
        return f
    }

    private fun p(text: String) = "<w:p><w:r><w:t>$text</w:t></w:r></w:p>"
    private fun texts(f: File) = DocxParser.open(f).use { d -> (0 until d.chapterCount).flatMap { d.loadChapter(it).blocks }.filterIsInstance<TextBlock>() }

    @Test fun movedAndDeletedRevisions() {
        val body = "<w:p><w:r><w:t xml:space=\"preserve\">Start </w:t></w:r><w:moveFrom><w:r><w:t>OLDPLACE</w:t></w:r></w:moveFrom>" +
            "<w:del><w:r><w:delText>DELETED</w:delText></w:r></w:del><w:ins><w:r><w:t xml:space=\"preserve\">inserted </w:t></w:r></w:ins>" +
            "<w:moveTo><w:r><w:t>moved here</w:t></w:r></w:moveTo></w:p>"
        val t = texts(docx(body)).joinToString("\n") { it.text }
        assertEquals("Start inserted moved here", t)
    }

    @Test fun alternateContentAndTextBoxesReadOnce() {
        val box = "<w:txbxContent><w:p><w:r><w:t>Box text</w:t></w:r></w:p></w:txbxContent>"
        val body = "<w:p><w:r><w:t xml:space=\"preserve\">Before </w:t></w:r><w:r><mc:AlternateContent>" +
            "<mc:Choice Requires=\"wps\"><w:drawing><wp:anchor><a:graphic><a:graphicData><wps:wsp><wps:txbx>$box</wps:txbx></wps:wsp></a:graphicData></a:graphic></wp:anchor></w:drawing></mc:Choice>" +
            "<mc:Fallback><w:pict><v:shape><v:textbox>$box</v:textbox></v:shape></w:pict></mc:Fallback>" +
            "</mc:AlternateContent></w:r><w:r><w:t>after</w:t></w:r></w:p>" +
            "<w:p><w:r><w:pict><v:shape><v:textbox><w:txbxContent><w:p><w:r><w:t>VML box</w:t></w:r></w:p></w:txbxContent></v:textbox></v:shape></w:pict></w:r></w:p>" +
            p("End")
        val t = texts(docx(body)).map { it.text }
        assertEquals(listOf("Before after", "Box text", "VML box", "End"), t)
    }

    @Test fun numberedLists() {
        val numbering = """<w:abstractNum w:abstractNumId="0"><w:lvl w:ilvl="0"><w:start w:val="1"/><w:numFmt w:val="decimal"/><w:lvlText w:val="%1."/></w:lvl>""" +
            """<w:lvl w:ilvl="1"><w:start w:val="1"/><w:numFmt w:val="lowerLetter"/><w:lvlText w:val="%2)"/></w:lvl>""" +
            """<w:lvl w:ilvl="2"><w:start w:val="1"/><w:numFmt w:val="lowerRoman"/><w:lvlText w:val="%1.%3"/></w:lvl></w:abstractNum>""" +
            """<w:abstractNum w:abstractNumId="1"><w:lvl w:ilvl="0"><w:numFmt w:val="bullet"/><w:lvlText w:val="•"/></w:lvl></w:abstractNum>""" +
            """<w:abstractNum w:abstractNumId="2"><w:lvl w:ilvl="0"><w:start w:val="1"/><w:numFmt w:val="upperRoman"/><w:lvlText w:val="%1."/></w:lvl></w:abstractNum>""" +
            """<w:num w:numId="1"><w:abstractNumId w:val="0"/></w:num><w:num w:numId="2"><w:abstractNumId w:val="1"/></w:num>""" +
            """<w:num w:numId="3"><w:abstractNumId w:val="2"/><w:lvlOverride w:ilvl="0"><w:startOverride w:val="3"/></w:lvlOverride></w:num>"""
        fun li(num: Int, lvl: Int, text: String) = """<w:p><w:pPr><w:numPr><w:ilvl w:val="$lvl"/><w:numId w:val="$num"/></w:numPr></w:pPr><w:r><w:t>$text</w:t></w:r></w:p>"""
        val body = li(1, 0, "one") + li(1, 1, "sub a") + li(1, 1, "sub b") + li(1, 2, "deep") + li(1, 0, "two") + li(1, 1, "sub again") +
            li(2, 0, "bullet") + li(3, 0, "third") + li(3, 0, "fourth")
        val t = texts(docx(body, numbering))
        assertTrue(t.all { it.kind == BlockKind.LIST_ITEM })
        assertEquals(listOf("1. one", "a) sub a", "b) sub b", "1.i deep", "2. two", "a) sub again", "• bullet", "III. third", "IV. fourth"), t.map { it.text })
    }

    @Test fun pandocNumberedList() {
        val t = texts(fixture("table.docx")).map { it.text }
        println(t)
        assertTrue(t.toString(), t.contains("1. First numbered") && t.contains("2. Second numbered"))
        assertTrue(t.toString(), t.any { it.endsWith("A bullet") })
        assertFalse(t.any { it.contains("•") && it.contains("numbered") })
    }
}
