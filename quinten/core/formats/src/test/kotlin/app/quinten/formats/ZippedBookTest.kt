package app.quinten.formats

import app.quinten.formats.util.ZippedBook
import app.quinten.model.BookFormat
import app.quinten.model.CleanOptions
import app.quinten.model.TextBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Books inside plain .zip files open without unpacking by hand. */
class ZippedBookTest {
    private val dir = Files.createTempDirectory("zb").toFile()
    private fun zip(name: String, vararg entries: Pair<String, ByteArray>) = File(dir, name).apply {
        ZipOutputStream(outputStream()).use { z -> for ((n, b) in entries) { z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
    }

    @Test fun epubAndTextInsideZipsOpen() {
        val epub = File("src/test/resources/fixtures/sample.epub").readBytes()
        val z = zip("novel.zip", "readme.txt" to "hi".toByteArray(), "Some Book/novel.epub" to epub)
        assertEquals(BookFormat.EPUB, BookOpener.detect(z))
        val inner = ZippedBook.unwrap(z, File(dir, "cache"), BookFormat.EPUB)
        assertEquals("novel.epub", inner.name.substringAfter('-'))
        val text = BookOpener.open(inner, BookFormat.EPUB).use { d -> (0 until d.chapterCount).flatMap { d.loadChapter(it, CleanOptions()).blocks }.filterIsInstance<TextBlock>().joinToString(" ") { it.text } }
        assertTrue(text.length > 100)
        // An imported copy keeps the inner book's extension: still unwrapped by content.
        val copy = z.copyTo(File(dir, "abc123.epub"))
        assertTrue(ZippedBook.unwrap(copy, File(dir, "cache2"), BookFormat.EPUB).name.endsWith("-novel.epub"))
        // A real EPUB (itself a zip) is left alone.
        val real = File("src/test/resources/fixtures/sample.epub")
        assertEquals(real, ZippedBook.unwrap(real, File(dir, "cache4"), BookFormat.EPUB))
        val txt = zip("story.zip", "story.txt" to "Chapter 1\n\nIt was a dark and stormy night.\n".toByteArray())
        assertEquals(BookFormat.TXT, BookOpener.detect(txt))
        assertTrue(BookOpener.open(ZippedBook.unwrap(txt, File(dir, "c3"), BookFormat.TXT), BookFormat.TXT).use { d -> d.chapterCount } > 0)
    }

    @Test fun picturesAreAComicAndOtherZipsAreNotBooks() {
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())
        assertEquals(BookFormat.CBZ, BookOpener.detect(zip("pages.zip", "001.png" to png, "002.png" to png)))
        assertNull(BookOpener.detect(zip("backup.zip", "settings.json" to "{}".toByteArray(), "data.bin" to ByteArray(10))))
    }
}
