package app.quinten.formats

import app.quinten.formats.comic.ComicDocument
import app.quinten.formats.fb2.Fb2Parser
import app.quinten.model.BookFormat
import app.quinten.model.ImageBlock
import app.quinten.model.ImageData
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.charset.Charset
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class Fb2ComicTest {
    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 1, 2, 3, 4, 5, 6, 7, 8, 9, 10)

    private fun fb2(decl: String?, body: String): String =
        (decl ?: "") + """<FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0" xmlns:l="http://www.w3.org/1999/xlink">""" +
            """<description><title-info><book-title>Книга</book-title></title-info></description>""" +
            """<body><section><title><p>Глава</p></title><p>$body</p><image l:href="#pic.png"/></section></body>""" +
            """<binary id="pic.png" content-type="image/png">${Base64.getMimeEncoder().encodeToString(png)}</binary></FictionBook>"""

    private fun write(bytes: ByteArray): File = File.createTempFile("tst", ".fb2").also { it.deleteOnExit(); it.writeBytes(bytes) }

    private fun check(f: File, expect: String) {
        Fb2Parser.open(f).use { doc ->
            assertTrue(doc.allText(), doc.allText().contains(expect))
            assertEquals("Книга", doc.metadata.title)
            val img = (0 until doc.chapterCount).flatMap { doc.loadChapter(it).blocks }.filterIsInstance<ImageBlock>().single()
            assertArrayEquals(png, (doc.image(img.resource) as ImageData.Encoded).bytes)
        }
    }

    private val russian = "Все счастливые семьи похожи друг на друга, каждая несчастливая семья несчастлива по-своему. ".repeat(10)

    @Test fun utf16WithBom() {
        val text = fb2("""<?xml version="1.0" encoding="UTF-16"?>""", russian)
        check(write(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)), "счастливые")
    }

    @Test fun utf8BomWithoutDeclaration() {
        check(write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + fb2(null, russian).toByteArray()), "счастливые")
    }

    @Test fun cp1251WithoutDeclarationIsGuessed() {
        check(write(fb2(null, russian).toByteArray(Charset.forName("windows-1251"))), "счастливые")
    }

    @Test fun declaredEncoding() {
        check(write(fb2("""<?xml version="1.0" encoding="windows-1251"?>""", russian).toByteArray(Charset.forName("windows-1251"))), "счастливые")
    }

    @Test fun zippedFb2() {
        val f = File.createTempFile("tst", ".fb2.zip").also { it.deleteOnExit() }
        ZipOutputStream(f.outputStream()).use { z -> z.putNextEntry(ZipEntry("book.fb2")); z.write(fb2(null, russian).toByteArray()); z.closeEntry() }
        check(f, "счастливые")
    }

    @Test fun cbzWithShiftJisNamesAndAvif() {
        val f = File.createTempFile("tst", ".cbz").also { it.deleteOnExit() }
        ZipOutputStream(f.outputStream(), Charset.forName("Shift_JIS")).use { z ->
            for (n in listOf("第1話/02.png", "第1話/01.avif")) { z.putNextEntry(ZipEntry(n)); z.write(png); z.closeEntry() }
        }
        ComicDocument.open(f, BookFormat.CBZ).use { doc ->
            assertEquals(2, doc.chapterCount)
            assertEquals("第1話/01.avif", (doc.loadChapter(0).blocks.single() as ImageBlock).resource)
            assertTrue(doc.image("第1話/02.png") != null)
        }
    }

    @Test fun numericComicNameHasNoTitle() {
        val dir = createTempDirectory()
        val f = File(dir, "57")
        ZipOutputStream(f.outputStream()).use { z -> z.putNextEntry(ZipEntry("a.png")); z.write(png); z.closeEntry() }
        ComicDocument.open(f, BookFormat.CBZ).use { assertNull(it.metadata.title) }
    }

    @Test fun kfxAndTopazHaveAClearMessage() {
        for (magic in listOf("TPZ0", "CONT", "êDRMIONî")) {
            val f = File.createTempFile("tst", ".azw").also { it.deleteOnExit() }
            f.writeBytes(magic.toByteArray(Charsets.ISO_8859_1) + ByteArray(200))
            val e = runCatching { app.quinten.formats.mobi.MobiParser.open(f) }.exceptionOrNull()
            assertEquals("Kindle KFX/Topaz format is not supported", e?.message)
        }
    }

    @Test fun mobiFormatFromContentNotExtension() {
        val src = File("build/external-fixtures/alice.azw3")
        org.junit.Assume.assumeTrue(src.isFile)
        val renamed = File(createTempDirectory(), "12.mobi").also { src.copyTo(it) }
        app.quinten.formats.mobi.MobiParser.open(renamed).use { doc ->
            assertEquals(BookFormat.AZW3, doc.format)
            assertTrue(doc.metadata.title!!.contains("Alice"))
        }
    }

    private fun createTempDirectory(): File = kotlin.io.path.createTempDirectory("q").toFile().also { it.deleteOnExit() }
}
