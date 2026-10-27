package app.quinten.formats

import app.quinten.formats.epub.EpubDocument
import app.quinten.formats.html.HtmlConverter
import app.quinten.formats.html.MiniCss
import app.quinten.model.LinkSpan
import app.quinten.model.TextBlock
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Builds a small EPUB in a temp file. [files] maps zip paths (under OEBPS/) to content. */
fun makeEpub(spine: List<String>, files: Map<String, String>, charset: Charset = Charsets.UTF_8, nav: String? = null): File {
    val f = File.createTempFile("test", ".epub").also { it.deleteOnExit() }
    ZipOutputStream(f.outputStream(), charset).use { z ->
        fun put(name: String, content: String) { z.putNextEntry(ZipEntry(name)); z.write(content.toByteArray()); z.closeEntry() }
        put("mimetype", "application/epub+zip")
        put("META-INF/container.xml", """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
        val items = spine.mapIndexed { i, p -> """<item id="c$i" href="$p" media-type="application/xhtml+xml"/>""" }.joinToString("")
        val navItem = if (nav != null) """<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>""" else ""
        val refs = spine.indices.joinToString("") { """<itemref idref="c$it"/>""" }
        put("OEBPS/content.opf", """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>T</dc:title></metadata><manifest>$items$navItem</manifest><spine>$refs</spine></package>""")
        if (nav != null) put("OEBPS/nav.xhtml", nav)
        for ((k, v) in files) put("OEBPS/$k", v)
    }
    return f
}

class EpubExtraTest {
    private fun xhtml(body: String, head: String = "") =
        """<?xml version="1.0" encoding="utf-8"?><html xmlns="http://www.w3.org/1999/xhtml"><head>$head</head><body>$body</body></html>"""

    private fun convert(html: String, css: String): List<String> {
        val m = MiniCss().apply { addStylesheet(css) }
        val doc = Jsoup.parse(html, "", Parser.xmlParser())
        return HtmlConverter(m).convert(doc.selectFirst("body")!!).build().filterIsInstance<TextBlock>().map { it.text }
    }

    @Test fun kindleMediaBlocks() {
        val css = "@media amzn-mobi { .kf8 { display: none } } @media amzn-kf8 { .mobi { display: none } } @media print { p { display:none } } " +
            "@media screen and (max-width: 300px) { .wide { display:none } } .x { font-weight: bold }"
        val text = convert(xhtml("<p class='kf8'>KF8 text</p><p class='mobi'>MOBI text</p><p class='wide'>Wide</p>"), css)
        assertEquals(listOf("KF8 text", "Wide"), text)
    }

    @Test fun descendantSelectorsMatchContext() {
        val css = ".sidebar span { display: none } div > em.x { display:none } h1 + p { display: none }"
        val text = convert(xhtml("<div class='sidebar'><p>Side <span>hidden</span></p></div><p>Body <span>kept</span> <em class='x'>also</em></p><div><em class='x'>gone</em>left</div><h1>H</h1><p>after</p><p>second</p>"), css)
        assertEquals(listOf("Side", "Body kept also", "left", "H", "second"), text)
    }

    @Test fun unclosedVoidTagsKeepText() {
        // Not well-formed XML: the XML parser makes the following text children of <br>/<img>.
        val text = convert(xhtml("<p>Line one<br>line two<img src='a.png'>line three</p><p>Next<hr>after rule</p>"), "")
        assertTrue(text.toString(), text.any { it.contains("line two") })
        assertTrue(text.toString(), text.any { it.contains("line three") })
        assertTrue(text.toString(), text.any { it.contains("after rule") })
    }

    @Test fun hugeSpineItemIsSplitWithWorkingLinks() {
        val para = "Lorem ipsum dolor sit amet, consectetur adipiscing elit. ".repeat(20)
        val body = StringBuilder()
        for (i in 0 until 400) {
            if (i % 50 == 0) body.append("<h2 id='s$i'>Section $i</h2>")
            body.append("<p>$i $para</p>")
        }
        body.append("<p id='end'>The very end.</p>")
        val nav = xhtml("<nav epub:type='toc' xmlns:epub='http://www.idpf.org/2007/ops'><ol><li><a href='big.xhtml'>Start</a></li><li><a href='big.xhtml#s350'>Late</a></li></ol></nav>")
        val f = makeEpub(listOf("intro.xhtml", "big.xhtml"), mapOf(
            "intro.xhtml" to xhtml("<p>Intro <a href='big.xhtml#end'>to the end</a></p>"),
            "big.xhtml" to xhtml(body.toString()),
        ), nav = nav)
        EpubDocument(f).use { doc ->
            assertTrue("chapters ${doc.chapterCount}", doc.chapterCount > 3)
            val all = doc.allText()
            assertTrue(all.contains("399 Lorem") && all.contains("0 Lorem") && all.contains("The very end."))
            // TOC resolves into the right part.
            val late = doc.toc.first { it.title == "Late" }
            val ch = doc.loadChapter(late.chapter)
            assertTrue(ch.blocks.any { "s350" in it.anchors })
            // Link from another item resolves into the last part.
            val link = doc.loadChapter(0).blocks.filterIsInstance<TextBlock>().flatMap { it.spans }.filterIsInstance<LinkSpan>().single()
            val loc = doc.resolveLink(link.target)!!
            assertEquals(doc.chapterCount - 1, loc.chapter)
            val target = doc.loadChapter(loc.chapter)
            assertTrue(target.blocks.any { "end" in it.anchors && it.firstUnit == loc.unit })
            for (i in 0 until doc.chapterCount) assertTrue(doc.loadChapter(i).blocks.sumOf { it.length } < 150_000)
            assertFalse(doc.chapterTitle(late.chapter).isNullOrEmpty())
        }
    }

    @Test fun nonUtf8ZipNames() {
        val f = makeEpub(listOf("chapitre_é.xhtml"), mapOf("chapitre_é.xhtml" to xhtml("<p>Bonjour</p>")), charset = Charset.forName("IBM437"))
        // A plain UTF-8 ZipFile can't read these names.
        val plain = runCatching { java.util.zip.ZipFile(f).use { z -> z.entries().toList().map { it.name } } }
        assertTrue(plain.isFailure)
        EpubDocument(f).use { assertTrue(it.allText().contains("Bonjour")) }
    }
}
