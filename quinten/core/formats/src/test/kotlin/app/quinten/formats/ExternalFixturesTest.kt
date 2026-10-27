package app.quinten.formats

import app.quinten.formats.rar.RarArchive
import app.quinten.model.BookFormat
import app.quinten.model.ImageBlock
import app.quinten.model.TextBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.zip.CRC32

/** Tests against third-party samples fetched by scripts/fetch_external_fixtures.sh (skipped if absent). */
class ExternalFixturesTest {
    private val dir = File("build/external-fixtures")
    private fun ext(name: String): File = File(dir, name).also { assumeTrue("fixture $name not downloaded", it.isFile) }

    private fun checkAlice(f: File, format: BookFormat) {
        BookOpener.open(f, format).use { doc ->
            val text = doc.allText()
            println("$format: chapters=${doc.chapterCount} toc=${doc.toc.size} title=${doc.metadata.title} authors=${doc.metadata.authors} chars=${text.length}")
            doc.toc.take(5).forEach { println("  toc: ${it.title} -> ${it.chapter}#${it.anchor}") }
            assertTrue(text.contains("Alice was beginning to get very tired of sitting by her sister"))
            assertTrue(text.contains("Down the Rabbit-Hole", ignoreCase = true))
            assertTrue("whole book present", text.contains("happy summer days"))
            assertTrue(doc.toc.size >= 10)
            assertTrue((doc.metadata.title ?: "").contains("Alice"))
            // TOC entries resolve to the chapter heading text
            val entry = doc.toc.first { it.title.contains("Rabbit-Hole", true) }
            val ch = doc.loadChapter(entry.chapter)
            val idx = entry.anchor?.let { ch.findAnchor(it) }?.takeIf { it >= 0 } ?: 0
            val near = ch.blocks.drop(idx).take(4).filterIsInstance<TextBlock>().joinToString(" ") { it.text }
            assertTrue("toc target '$near'", near.contains("Rabbit", true))
            assertTrue("cover", doc.cover() != null)
            val imgs = (0 until doc.chapterCount).flatMap { doc.loadChapter(it).blocks.filterIsInstance<ImageBlock>() }
            imgs.forEach { assertTrue("image ${it.resource} decodes", doc.image(it.resource) != null) }
        }
    }

    @Test fun mobi() = checkAlice(ext("alice.mobi"), BookFormat.MOBI)
    @Test fun azw3() = checkAlice(ext("alice.azw3"), BookFormat.AZW3)
    @Test fun epub3() = checkAlice(ext("alice.epub"), BookFormat.EPUB)

    @Test fun txt() {
        BookOpener.open(ext("alice.txt"), BookFormat.TXT).use { doc ->
            val text = doc.allText()
            println("TXT chapters=${doc.chapterCount} toc=${doc.toc.map { it.title }.take(14)}")
            assertTrue(text.contains("Alice was beginning to get very tired of sitting by her sister on the bank, and of having nothing to do"))
            assertTrue(doc.toc.count { it.title.startsWith("CHAPTER") } >= 12)
        }
    }

    @Test fun rarArchives() {
        val files = listOf(
            "test_read_format_rar.rar", "test_read_format_rar_compress_normal.rar",
            "test_read_format_rar5_stored.rar", "test_read_format_rar5_compressed.rar", "test_read_format_rar5_multiple_files.rar",
            "test_read_format_rar5_solid.rar", "test_read_format_rar_multi_lzss_blocks.rar", "test_read_format_rar_filter.rar",
            "test_read_format_rar5_multiple_files_solid.rar",
        )
        var checked = 0
        for (name in files) {
            val f = File(dir, name)
            if (!f.isFile) continue
            RarArchive(f).use { rar ->
                for (e in rar.entries.filter { !it.isDir }) {
                    val data = rar.read(e.name)!!
                    val crc = CRC32().apply { update(data) }.value
                    println("$name :: ${e.name} v${e.version} m${e.method} solid=${e.solid} size=${data.size}/${e.unpackedSize} crc=${crc == e.crc}")
                    assertEquals("$name ${e.name} size", e.unpackedSize, data.size.toLong())
                    if (e.crc >= 0) assertEquals("$name ${e.name} crc", e.crc, crc)
                    checked++
                }
            }
        }
        assumeTrue(checked > 0)
    }

    @Test fun rarOutOfOrderReads() {
        var checked = 0
        for (name in listOf("test_read_format_rar5_solid.rar", "test_read_format_rar5_multiple_files_solid.rar", "test_read_format_rar5_multiple_files.rar", "test_read_format_rar_filter.rar")) {
            val f = File(dir, name)
            if (!f.isFile) continue
            RarArchive(f).use { rar ->
                val files = rar.entries.filter { !it.isDir }
                // Backwards, then forwards again: solid archives restart or hit the cache, non-solid ones reuse decoders.
                for (e in files.reversed() + files + files.reversed()) {
                    val data = rar.read(e.name)!!
                    assertEquals("$name ${e.name} size", e.unpackedSize, data.size.toLong())
                    if (e.crc >= 0) assertEquals("$name ${e.name} crc", e.crc, CRC32().apply { update(data) }.value)
                    checked++
                }
            }
        }
        assumeTrue(checked > 0)
    }

    @Test fun rar4UnicodeNames() {
        val f = File(dir, "test_read_format_rar_unicode.rar")
        assumeTrue(f.isFile)
        RarArchive(f).use { rar ->
            println("unicode names: ${rar.entries.map { it.name }}")
            assertTrue(rar.entries.any { it.name == "表だよ/新しいフォルダ/新規テキスト ドキュメント.txt" })
        }
    }
}
