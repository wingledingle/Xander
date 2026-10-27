package app.quinten.formats

import app.quinten.analysis.paged.PagedBookDocument
import app.quinten.formats.djvu.DjvuDocument
import app.quinten.model.BookFormat
import app.quinten.model.CleanOptions
import app.quinten.model.ImageData
import app.quinten.model.TextBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DjvuTest {
    private fun pgm(name: String): Triple<Int, Int, ByteArray> {
        val b = fixture(name).readBytes()
        var p = 0
        val tokens = ArrayList<String>()
        while (tokens.size < 4) {
            while (b[p].toInt().toChar().isWhitespace()) p++
            val s = p
            while (!b[p].toInt().toChar().isWhitespace()) p++
            tokens += String(b, s, p - s)
        }
        p++
        return Triple(tokens[1].toInt(), tokens[2].toInt(), b.copyOfRange(p, b.size))
    }

    private fun compare(img: ImageData.Raster, golden: String) {
        val (w, h, px) = pgm(golden)
        assertEquals(w, img.width)
        assertTrue("height ${img.height} vs $h", kotlin.math.abs(img.height - h) <= 1)
        // Pearson correlation of grey levels: robust to antialiasing/gamma differences.
        val n = w * minOf(h, img.height)
        var sa = 0.0; var sb = 0.0; var saa = 0.0; var sbb = 0.0; var sab = 0.0
        for (i in 0 until n) {
            val c = img.argb[i]
            val a = ((((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114) / 1000).toDouble()
            val g = (px[i].toInt() and 0xFF).toDouble()
            sa += a; sb += g; saa += a * a; sbb += g * g; sab += a * g
        }
        val corr = (n * sab - sa * sb) / kotlin.math.sqrt((n * saa - sa * sa) * (n * sbb - sb * sb))
        println("$golden: correlation $corr")
        System.getenv("DJVU_DUMP")?.let { dir ->
            val o = java.io.ByteArrayOutputStream()
            o.write("P5\n${img.width} ${img.height}\n255\n".toByteArray())
            for (c in img.argb) o.write((((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114) / 1000)
            java.io.File(dir, "mine-$golden").writeBytes(o.toByteArray())
        }
        assertTrue("correlation $corr", corr > 0.8)
    }

    @Test fun decodeBundledDocument() {
        DjvuDocument(fixture("sample.djvu")).use { d ->
            assertEquals(4, d.pageCount)
            assertEquals(listOf("Chapter One", "Photo page"), d.outline.map { it.title })
            assertEquals(listOf(0, 3), d.outline.map { it.page })
            assertEquals(875f to 1241f, d.pageSize(0))
            val t = d.textLayer(0)!!
            assertTrue(t.lines.first().text.startsWith("ALICE'S ADVENTURES"))
            assertTrue(t.lines.any { it.text.contains("Alice was beginning to get very tired") })
            assertTrue(d.textLayer(3) == null)
            compare(d.renderStrict(0, 400), "sample-djvu-p1.pgm")
            compare(d.renderStrict(3, 400), "sample-djvu-p4.pgm")
        }
    }

    @Test fun pageCacheIsBounded() {
        DjvuDocument(fixture("sample.djvu"), pageCacheSize = 2).use { d ->
            for (p in 0 until d.pageCount) d.renderStrict(p, 200)
            assertTrue("cached ${d.cachedPageForms}", d.cachedPageForms <= 2)
            // Evicted pages reload correctly.
            compare(d.renderStrict(0, 400), "sample-djvu-p1.pgm")
        }
    }

    private fun inkFraction(img: ImageData.Raster): Double =
        img.argb.count { c -> (((c shr 16) and 0xFF) + ((c shr 8) and 0xFF) + (c and 0xFF)) / 3 < 128 }.toDouble() / img.argb.size

    @Test fun zoomedMaskIsSolid() {
        DjvuDocument(fixture("sample.djvu")).use { d ->
            val (pw, _) = d.pageSize(0)
            val one = d.renderStrict(0, pw.toInt())
            val two = d.renderStrict(0, pw.toInt() * 2)
            assertEquals(pw.toInt() * 2, two.width)
            val f1 = inkFraction(one); val f2 = inkFraction(two)
            // Scattering source pixels only would leave ~1/4 of the ink when zoomed 2x.
            assertTrue("ink 1x=$f1 2x=$f2", f1 > 0 && f2 > f1 * 0.7)
        }
    }

    @Test fun paletteColoursAtEveryScale() {
        // palette.djvu: cpaldjvu -colors 3 of red bars (y 40-120) and blue bars (y 170-250), 400x300.
        DjvuDocument(fixture("palette.djvu")).use { d ->
            for (w in listOf(400, 800, 100)) {
                val img = d.renderStrict(0, w)
                val s = w / 400f
                fun at(x: Int, y: Int) = img.argb[(y * s).toInt() * img.width + (x * s).toInt()]
                val red = at(25, 80); val blue = at(25, 210); val white = at(15, 150)
                fun r(c: Int) = (c shr 16) and 0xFF
                fun b(c: Int) = c and 0xFF
                assertTrue("w=$w red ${Integer.toHexString(red)}", r(red) > 150 && b(red) < 100)
                assertTrue("w=$w blue ${Integer.toHexString(blue)}", b(blue) > 150 && r(blue) < 100)
                assertTrue("w=$w white ${Integer.toHexString(white)}", r(white) > 200 && b(white) > 200)
            }
        }
    }

    @Test fun reflowsDjvuTextLayer() {
        val doc = PagedBookDocument(DjvuDocument(fixture("sample.djvu")), BookFormat.DJVU)
        doc.use {
            val blocks = (0 until it.chapterCount).flatMap { c -> it.loadChapter(c, CleanOptions.ON).blocks }
            val text = blocks.filterIsInstance<TextBlock>().joinToString("\n") { b -> b.text }
            assertTrue(text.contains("Alice was beginning to get very tired of sitting by her sister on the bank"))
            assertTrue(!text.contains("ALICE'S ADVENTURES IN WONDERLAND"))
            // The colour page has no text layer and no OCR engine: shown as a page image.
            assertTrue(blocks.any { b -> b is app.quinten.model.ImageBlock && b.fullPage })
            assertEquals("Chapter One", it.toc.first().title)
        }
    }
}
