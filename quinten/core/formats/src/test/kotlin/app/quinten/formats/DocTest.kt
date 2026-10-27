package app.quinten.formats

import app.quinten.formats.doc.CompoundFile
import app.quinten.formats.doc.DocParser
import app.quinten.model.BlockKind
import app.quinten.model.TextBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DocTest {
    @Test fun tableRowsAreSeparateBlocks() {
        // table.doc: pandoc markdown table -> docx -> LibreOffice .doc
        DocParser.open(fixture("table.doc")).use { doc ->
            val blocks = (0 until doc.chapterCount).flatMap { doc.loadChapter(it).blocks }.filterIsInstance<TextBlock>()
            println(blocks.joinToString("\n") { "[${it.kind}] ${it.text}" })
            val rows = blocks.filter { it.kind == BlockKind.TABLE_ROW }.map { it.text }
            assertEquals(listOf("Name | Age", "Alice | 30", "Bob | 25"), rows)
            assertTrue(blocks.any { it.text == "After the table." })
        }
    }

    @Test fun rtfFieldsAndBinaryData() {
        val bin = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), '{'.code.toByte(), '}'.code.toByte(), '\\'.code.toByte(), 'x'.code.toByte(), 1, 2)
        val rtf = "{\\rtf1\\ansi Page {\\field{\\*\\fldinst PAGE}{\\fldrslt 3}} and " +
            "{\\field{\\*\\fldinst HYPERLINK \"https://example.org\"}{\\fldrslt link}}.\\par " +
            "{\\pict\\pngblip\\bin${bin.size} "
        val data = rtf.toByteArray(Charsets.ISO_8859_1) + bin + "}After picture.\\par}".toByteArray(Charsets.ISO_8859_1)
        val doc = app.quinten.formats.rtf.RtfParser.parse(data)
        val blocks = (0 until doc.chapterCount).flatMap { doc.loadChapter(it).blocks }
        val texts = blocks.filterIsInstance<TextBlock>()
        assertEquals("Page 3 and link.", texts.first().text)
        val links = texts.flatMap { it.spans }.filterIsInstance<app.quinten.model.LinkSpan>()
        assertEquals(listOf("https://example.org"), links.map { it.target })
        assertTrue(texts.any { it.text == "After picture." })
        val img = blocks.filterIsInstance<app.quinten.model.ImageBlock>().single()
        assertTrue((doc.image(img.resource) as app.quinten.model.ImageData.Encoded).bytes.contentEquals(bin))
    }

    @Test fun cyclicFatChainTerminates() {
        // Minimal CFB whose directory chain points at itself.
        val sector = 512
        val data = ByteArray(sector * 3)
        val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        b.putLong(0, 0xE11AB1A1E011CFD0uL.toLong())
        b.putShort(0x1E, 9); b.putShort(0x20, 6)
        b.putInt(0x2C, 1) // one FAT sector
        b.putInt(0x30, 1) // first directory sector
        b.putInt(0x38, 4096)
        b.putInt(0x3C, -2); b.putInt(0x44, -2); b.putInt(0x48, 0)
        for (i in 0 until 109) b.putInt(0x4C + i * 4, -1)
        b.putInt(0x4C, 0) // FAT at sector 0
        val fat = sector * 1
        for (i in 0 until 128) b.putInt(fat + i * 4, -1)
        b.putInt(fat + 4, 1) // sector 1 -> sector 1 (cycle)
        val t = System.currentTimeMillis()
        CompoundFile(data)
        assertTrue(System.currentTimeMillis() - t < 5000)
    }
}
