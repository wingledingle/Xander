package com.xander.files.data.books

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.net.URLDecoder
import java.nio.charset.Charset
import java.util.zip.ZipFile

data class Chapter(val title: String, val href: String?, val text: String? = null)
data class TocEntry(val title: String, val chapterIndex: Int, val depth: Int)
data class Book(val title: String, val author: String?, val chapters: List<Chapter>, val toc: List<TocEntry>, val isEpub: Boolean)

/** EPUB 2/3 (OPF spine + NCX / nav TOC) and plain-text books split into chapters. */
object BookParser {

    fun epub(file: File): Book = ZipFile(file).use { zip ->
        val container = zip.getEntry("META-INF/container.xml") ?: error("Not an EPUB")
        val opfPath = parse(zip.getInputStream(container).readBytes()) { p -> if (p.name == "rootfile") p.getAttributeValue(null, "full-path") else null }.firstOrNull()
            ?: error("EPUB has no package document")
        val base = opfPath.substringBeforeLast('/', "")
        val opf = zip.getInputStream(zip.getEntry(opfPath)).readBytes()
        val manifest = HashMap<String, Triple<String, String, String?>>() // id -> href, media-type, properties
        val spine = ArrayList<String>()
        var title = file.nameWithoutExtension; var author: String? = null; var ncxId: String? = null
        val p = Xml.newPullParser().apply { setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false); setInput(opf.inputStream(), null) }
        while (p.next() != XmlPullParser.END_DOCUMENT) if (p.eventType == XmlPullParser.START_TAG) when (p.name.substringAfter(':')) {
            "title" -> title = p.nextText().ifBlank { title }
            "creator" -> author = p.nextText()
            "item" -> manifest[p.getAttributeValue(null, "id")] = Triple(resolve(base, p.getAttributeValue(null, "href")), p.getAttributeValue(null, "media-type") ?: "", p.getAttributeValue(null, "properties"))
            "spine" -> ncxId = p.getAttributeValue(null, "toc")
            "itemref" -> if (p.getAttributeValue(null, "linear") != "no") p.getAttributeValue(null, "idref")?.let { spine += it }
        }
        val chapters = spine.mapNotNull { manifest[it]?.first }.map { Chapter(it.substringAfterLast('/').substringBeforeLast('.'), it) }
        val index = chapters.withIndex().associate { it.value.href!! to it.index }
        val toc = ArrayList<TocEntry>()
        val nav = manifest.values.firstOrNull { it.third?.contains("nav") == true }
        if (nav != null) zip.getEntry(nav.first)?.let { e ->
            val navBase = nav.first.substringBeforeLast('/', "")
            val x = Xml.newPullParser().apply { setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false); setInput(zip.getInputStream(e), null) }
            var inToc = false; var depth = 0
            while (x.next() != XmlPullParser.END_DOCUMENT) {
                if (x.eventType == XmlPullParser.START_TAG) {
                    if (x.name == "nav" && (x.getAttributeValue(null, "epub:type") == "toc" || x.getAttributeValue(null, "id") == "toc")) inToc = true
                    if (inToc && x.name == "ol") depth++
                    if (inToc && x.name == "a") {
                        val href = x.getAttributeValue(null, "href")?.substringBefore('#')?.let { resolve(navBase, it) }
                        val t = runCatching { x.nextText() }.getOrDefault("").trim()
                        index[href]?.let { toc += TocEntry(t.ifEmpty { "•" }, it, (depth - 1).coerceAtLeast(0)) }
                    }
                } else if (x.eventType == XmlPullParser.END_TAG) { if (x.name == "ol" && inToc) depth--; if (x.name == "nav") inToc = false }
            }
        }
        if (toc.isEmpty()) (ncxId?.let { manifest[it] } ?: manifest.values.firstOrNull { it.second == "application/x-dtbncx+xml" })?.let { ncx ->
            zip.getEntry(ncx.first)?.let { e ->
                val ncxBase = ncx.first.substringBeforeLast('/', "")
                val x = Xml.newPullParser().apply { setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false); setInput(zip.getInputStream(e), null) }
                var depth = 0; var label = ""
                while (x.next() != XmlPullParser.END_DOCUMENT) {
                    if (x.eventType == XmlPullParser.START_TAG) when (x.name) {
                        "navPoint" -> depth++
                        "text" -> label = x.nextText().trim()
                        "content" -> x.getAttributeValue(null, "src")?.substringBefore('#')?.let { resolve(ncxBase, it) }?.let { index[it] }?.let { toc += TocEntry(label, it, depth - 1) }
                    } else if (x.eventType == XmlPullParser.END_TAG && x.name == "navPoint") depth--
                }
            }
        }
        val titled = chapters.mapIndexed { i, c -> toc.firstOrNull { it.chapterIndex == i }?.let { c.copy(title = it.title) } ?: c }
        Book(title, author, titled, toc.ifEmpty { titled.mapIndexed { i, c -> TocEntry(c.title, i, 0) } }, true)
    }

    private val chapterRe = Regex("^\\s*((chapter|chap\\.|part|book|prologue|epilogue|kapitel|capítulo|capitulo|chapitre|capitolo|глава|часть|κεφάλαιο|الفصل)\\b.*|第[0-9一二三四五六七八九十百千零〇]+[章回节卷].*)$", RegexOption.IGNORE_CASE)

    fun txt(file: File, charset: Charset): Book {
        val text = file.readText(charset)
        val lines = text.split('\n')
        val starts = lines.withIndex().filter { (_, l) -> l.length < 80 && chapterRe.matches(l) }.map { it.index }
        val chapters = if (starts.size < 2) {
            // No headings: split into ~20k character sections at paragraph breaks.
            val out = ArrayList<Chapter>(); var sb = StringBuilder(); var n = 1
            for (l in lines) { sb.append(l).append('\n'); if (sb.length > 20_000 && l.isBlank()) { out += Chapter("Part ${n++}", null, sb.toString()); sb = StringBuilder() } }
            if (sb.isNotBlank()) out += Chapter("Part $n", null, sb.toString())
            out
        } else {
            val out = ArrayList<Chapter>()
            if (starts.first() > 0) out += Chapter("Start", null, lines.subList(0, starts.first()).joinToString("\n"))
            starts.forEachIndexed { i, s -> val e = starts.getOrElse(i + 1) { lines.size }; out += Chapter(lines[s].trim(), null, lines.subList(s, e).joinToString("\n")) }
            out
        }
        return Book(file.nameWithoutExtension, null, chapters, chapters.mapIndexed { i, c -> TocEntry(c.title, i, 0) }, false)
    }

    fun resolve(base: String, href: String): String {
        val h = URLDecoder.decode(href, "UTF-8")
        val parts = ArrayList<String>()
        (if (base.isEmpty()) h else "$base/$h").split('/').forEach { if (it == "..") { if (parts.isNotEmpty()) parts.removeAt(parts.size - 1) } else if (it.isNotEmpty() && it != ".") parts += it }
        return parts.joinToString("/")
    }

    private fun <T> parse(bytes: ByteArray, pick: (XmlPullParser) -> T?): List<T> {
        val p = Xml.newPullParser().apply { setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false); setInput(bytes.inputStream(), null) }
        val out = ArrayList<T>()
        while (p.next() != XmlPullParser.END_DOCUMENT) if (p.eventType == XmlPullParser.START_TAG) pick(p)?.let { out += it }
        return out
    }
}
