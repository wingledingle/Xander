package app.quinten.formats.epub

import app.quinten.formats.DrmProtectedException
import app.quinten.formats.FlowBookDocument
import app.quinten.formats.RawChapter
import app.quinten.formats.UnsupportedBookException
import app.quinten.formats.html.HtmlConverter
import app.quinten.formats.html.MiniCss
import app.quinten.formats.util.Paths
import app.quinten.model.BookFormat
import app.quinten.model.BookMetadata
import app.quinten.model.ImageData
import app.quinten.model.LinkTarget
import app.quinten.model.TocEntry
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.io.File
import app.quinten.formats.util.Zips
import app.quinten.model.Block
import app.quinten.model.BlockKind
import app.quinten.model.ContentBuilder
import app.quinten.model.TextBlock

/**
 * EPUB 2 and EPUB 3 reader (DRM-free).
 *
 * Chapters are spine items, except that very large items (whole books in one XHTML file) are
 * split into parts of about [PART_CHARS] characters. Their part boundaries and anchor locations
 * are computed when the book is opened, so TOC entries and links point at the right part.
 */
class EpubDocument(file: File) : FlowBookDocument() {
    private val zip = Zips.open(file)
    override val format = BookFormat.EPUB

    private class Item(val id: String, val href: String, val mediaType: String, val properties: String)

    private val opfPath: String
    private val manifest = LinkedHashMap<String, Item>()
    private val spine = ArrayList<Item>()
    private val spineIndexByPath = HashMap<String, Int>()
    private val cssCache = HashMap<String, String>()

    /** spine index -> first chapter index; chapter index -> spine index. */
    private lateinit var partStart: IntArray
    private lateinit var chapterSpine: IntArray
    /** For split spine items: first block index of every part, and anchor -> part. */
    private val partBlocks = HashMap<Int, IntArray>()
    private val anchorPart = HashMap<Int, Map<String, Int>>()
    /** Blocks of the most recently parsed split item (parts are usually read in sequence). */
    private var lastSplit: Pair<Int, List<Block>>? = null
    override val metadata: BookMetadata
    override val toc: List<TocEntry>

    init {
        try {
            checkDrm()
            val container = readXml("META-INF/container.xml") ?: throw UnsupportedBookException("Missing META-INF/container.xml")
            opfPath = container.select("rootfile").firstOrNull { it.attr("media-type").let { m -> m.isEmpty() || m == "application/oebps-package+xml" } }
                ?.attr("full-path") ?: findOpf() ?: throw UnsupportedBookException("No package document")
            val opf = readXml(opfPath) ?: throw UnsupportedBookException("Cannot read $opfPath")
            for (it in opf.select("manifest > item, manifest item")) {
                val id = it.attr("id")
                if (id.isEmpty() || manifest.containsKey(id)) continue
                manifest[id] = Item(id, Paths.resolve(opfPath, it.attr("href")), it.attr("media-type"), it.attr("properties"))
            }
            val spineEl = opf.selectFirst("spine")
            for (ref in spineEl?.select("itemref") ?: emptyList()) {
                val item = manifest[ref.attr("idref")] ?: continue
                if (!item.mediaType.contains("html") && !item.mediaType.contains("xml") && !item.href.endsWith("htm") && !item.href.endsWith("html")) continue
                spineIndexByPath[item.href] = spine.size
                spine.add(item)
            }
            if (spine.isEmpty()) {
                // Broken OPF: fall back to all html items in manifest order.
                for (item in manifest.values.filter { it.mediaType.contains("html") }) {
                    spineIndexByPath[item.href] = spine.size; spine.add(item)
                }
            }
            if (spine.isEmpty()) throw UnsupportedBookException("EPUB has no readable content")
            computeParts()
            metadata = parseMetadata(opf)
            toc = parseNav() ?: parseNcx(spineEl?.attr("toc")) ?: emptyList()
        } catch (e: Exception) {
            zip.close()
            throw e
        }
    }

    private fun checkDrm() {
        val enc = readXml("META-INF/encryption.xml") ?: return
        val algorithms = enc.select("EncryptionMethod, enc|EncryptionMethod").map { it.attr("Algorithm") }
        // Font obfuscation (IDPF / Adobe) is harmless; anything else is real encryption.
        val real = algorithms.any { it.isNotEmpty() && !it.contains("idpf.org/2008/embedding") && !it.contains("ns.adobe.com/pdf/enc#RC") }
        if (real || zip.getEntry("META-INF/rights.xml") != null) throw DrmProtectedException()
    }

    private fun findOpf(): String? = zip.entries().asSequence().map { it.name }.firstOrNull { it.endsWith(".opf") }

    private fun bytes(path: String): ByteArray? {
        val e = zip.getEntry(path) ?: zip.entries().asSequence().firstOrNull { it.name.equals(path, true) } ?: return null
        return synchronized(zip) { zip.getInputStream(e).use { it.readBytes() } }
    }

    private fun readXml(path: String): Document? {
        val b = bytes(path) ?: return null
        return Jsoup.parse(b.inputStream(), null, "", Parser.xmlParser())
    }

    private fun parseMetadata(opf: Document): BookMetadata {
        val md = opf.selectFirst("metadata") ?: return BookMetadata()
        fun dc(name: String) = md.children().filter { it.normalName().substringAfter(':') == name }.map { it.text().trim() }.filter { it.isNotEmpty() }
        val metas = md.select("meta")
        fun meta(name: String) = metas.firstOrNull { it.attr("name") == name }?.attr("content")
            ?: metas.firstOrNull { it.attr("property") == name }?.text()
        var coverHref: String? = manifest.values.firstOrNull { it.properties.split(' ').contains("cover-image") }?.href
        if (coverHref == null) {
            meta("cover")?.let { id -> coverHref = manifest[id]?.href ?: manifest.values.firstOrNull { it.href.endsWith(id) }?.href }
        }
        if (coverHref == null) {
            coverHref = manifest.values.firstOrNull { it.mediaType.startsWith("image/") && it.id.contains("cover", true) }?.href
        }
        val series = meta("calibre:series") ?: metas.firstOrNull { it.attr("property") == "belongs-to-collection" }?.text()
        val seriesIdx = meta("calibre:series_index")?.toFloatOrNull()
            ?: metas.firstOrNull { it.attr("property") == "group-position" }?.text()?.toFloatOrNull()
        return BookMetadata(
            title = dc("title").firstOrNull(),
            authors = dc("creator").ifEmpty { dc("contributor").take(1) },
            language = dc("language").firstOrNull(),
            series = series,
            seriesIndex = seriesIdx,
            description = dc("description").firstOrNull()?.let { Jsoup.parse(it).text() },
            publisher = dc("publisher").firstOrNull(),
            identifier = dc("identifier").firstOrNull(),
            subjects = dc("subject"),
            year = dc("date").firstOrNull()?.take(4),
            coverResource = coverHref,
        )
    }

    /** Chapter target (chapter index, anchor) of an href, for TOC entries. */
    private fun tocTarget(base: String, href: String): Pair<Int, String?>? {
        val path = Paths.resolve(base, href)
        val idx = spineIndexByPath[path] ?: return null
        val frag = Paths.fragment(href)
        return chapterFor(idx, frag) to frag
    }

    /** Chapter that holds [anchor] of spine item [spineIndex] (its first part without an anchor). */
    private fun chapterFor(spineIndex: Int, anchor: String?): Int {
        val base = partStart[spineIndex]
        if (anchor == null) return base
        return base + (anchorPart[spineIndex]?.get(anchor) ?: 0)
    }

    private fun computeParts() {
        partStart = IntArray(spine.size)
        val chapters = ArrayList<Int>()
        for ((i, item) in spine.withIndex()) {
            partStart[i] = chapters.size
            val size = zip.getEntry(item.href)?.size ?: -1L
            var parts = 1
            if (size > SPLIT_BYTES) {
                val blocks = try { parseItem(i) } catch (_: Exception) { emptyList() }
                val starts = splitPoints(blocks)
                if (starts.size > 1) {
                    parts = starts.size
                    partBlocks[i] = starts
                    val anchors = HashMap<String, Int>()
                    var part = 0
                    for ((bi, b) in blocks.withIndex()) {
                        while (part + 1 < starts.size && bi >= starts[part + 1]) part++
                        for (a in b.anchors) anchors.putIfAbsent(a, part)
                    }
                    anchorPart[i] = anchors
                }
            }
            repeat(parts) { chapters += i }
        }
        chapterSpine = chapters.toIntArray()
    }

    /** First block index of each part: a new part starts after about [PART_CHARS] characters, preferably at a heading. */
    private fun splitPoints(blocks: List<Block>): IntArray {
        val starts = arrayListOf(0)
        var size = 0
        for ((i, b) in blocks.withIndex()) {
            val heading = b is TextBlock && b.kind == BlockKind.HEADING
            if (i > 0 && ((size > PART_CHARS && b is TextBlock && !heading) || (heading && size > PART_CHARS / 3))) {
                // Keep a heading together with what follows: never split right after one.
                val prev = blocks[i - 1]
                if (!(prev is TextBlock && prev.kind == BlockKind.HEADING)) { starts += i; size = 0 }
            }
            size += b.length
        }
        return starts.toIntArray()
    }

    private fun parseNav(): List<TocEntry>? {
        val nav = manifest.values.firstOrNull { it.properties.split(' ').contains("nav") } ?: return null
        val doc = bytes(nav.href)?.let { Jsoup.parse(String(it, Charsets.UTF_8), "", Parser.xmlParser()) } ?: return null
        val navEl = doc.select("nav").firstOrNull { it.attr("epub:type").contains("toc") } ?: doc.selectFirst("nav") ?: return null
        val out = ArrayList<TocEntry>()
        fun walk(ol: Element, level: Int) {
            for (li in ol.children().filter { it.normalName() == "li" }) {
                val a = li.children().firstOrNull { it.normalName() == "a" || it.normalName() == "span" }
                val title = a?.text()?.trim().orEmpty()
                val target = a?.attr("href")?.takeIf { it.isNotEmpty() }?.let { tocTarget(nav.href, it) }
                if (title.isNotEmpty() && target != null) out += TocEntry(title, level, target.first, target.second)
                li.children().firstOrNull { it.normalName() == "ol" }?.let { walk(it, level + 1) }
            }
        }
        navEl.children().firstOrNull { it.normalName() == "ol" }?.let { walk(it, 0) }
        return out.ifEmpty { null }
    }

    private fun parseNcx(tocId: String?): List<TocEntry>? {
        val ncx = (tocId?.let { manifest[it] } ?: manifest.values.firstOrNull { it.mediaType == "application/x-dtbncx+xml" }) ?: return null
        val doc = readXml(ncx.href) ?: return null
        val out = ArrayList<TocEntry>()
        fun walk(parent: Element, level: Int) {
            for (np in parent.children().filter { it.normalName() == "navpoint" }) {
                val title = np.selectFirst("navLabel text, navlabel text")?.text()?.trim().orEmpty()
                val src = np.selectFirst("content")?.attr("src").orEmpty()
                val target = if (src.isNotEmpty()) tocTarget(ncx.href, src) else null
                if (title.isNotEmpty() && target != null) out += TocEntry(title, level, target.first, target.second)
                walk(np, level + 1)
            }
        }
        doc.selectFirst("navMap, navmap")?.let { walk(it, 0) }
        return out.ifEmpty { null }
    }

    override val chapterCount: Int get() = chapterSpine.size

    override fun chapterWeight(index: Int): Int {
        val s = chapterSpine[index]
        val parts = partBlocks[s]?.size ?: 1
        return ((zip.getEntry(spine[s].href)?.size ?: 1000L) / parts).toInt().coerceAtLeast(2) / 2
    }

    override fun chapterTitle(index: Int): String? {
        toc.firstOrNull { it.chapter == index }?.let { return it.title }
        // Later parts of a split item keep the title of the last TOC entry before them in the item.
        val s = chapterSpine[index]
        if (index == partStart[s]) return null
        return toc.lastOrNull { it.chapter in partStart[s] until index }?.title
    }

    override fun loadRaw(index: Int): RawChapter {
        val s = chapterSpine[index]
        val starts = partBlocks[s] ?: return RawChapter(chapterTitle(index), parseItem(s))
        val all = synchronized(this) { lastSplit?.takeIf { it.first == s }?.second }
            ?: parseItem(s).also { synchronized(this) { lastSplit = s to it } }
        val part = index - partStart[s]
        val from = starts[part].coerceAtMost(all.size)
        val to = (if (part + 1 < starts.size) starts[part + 1] else all.size).coerceIn(from, all.size)
        val blocks = all.subList(from, to).mapIndexed { i, b -> ContentBuilder.assignUnit(b, i) }
        return RawChapter(chapterTitle(index), blocks)
    }

    /** Parses a whole spine item into blocks. */
    private fun parseItem(spineIndex: Int): List<Block> {
        val item = spine[spineIndex]
        val raw = bytes(item.href) ?: return emptyList()
        val isXml = item.mediaType.contains("xhtml") || item.mediaType.contains("xml") || item.href.endsWith(".xhtml")
        val text = decode(raw)
        val doc = if (isXml) Jsoup.parse(text, "", Parser.xmlParser()) else Jsoup.parse(text)
        val css = MiniCss()
        for (link in doc.select("link")) {
            if (!link.attr("rel").contains("stylesheet", true) && !link.attr("href").endsWith(".css")) continue
            val p = Paths.resolve(item.href, link.attr("href"))
            val s = synchronized(cssCache) { cssCache.getOrPut(p) { bytes(p)?.let { String(it, Charsets.UTF_8) } ?: "" } }
            css.addStylesheet(s)
        }
        for (st in doc.select("style")) css.addStylesheet(st.data())
        val body = doc.selectFirst("body") ?: doc
        val converter = HtmlConverter(
            css,
            resolveHref = { href -> resolveHref(item.href, href) },
            resolveImage = { src -> if (src.isBlank() || src.startsWith("data:")) null else Paths.resolve(item.href, src) },
        )
        return converter.convert(body).build()
    }

    private fun decode(raw: ByteArray): String {
        if (raw.size >= 2 && raw[0] == 0xFF.toByte() && raw[1] == 0xFE.toByte()) return String(raw, Charsets.UTF_16LE).substring(1)
        if (raw.size >= 2 && raw[0] == 0xFE.toByte() && raw[1] == 0xFF.toByte()) return String(raw, Charsets.UTF_16BE).substring(1)
        val head = String(raw, 0, minOf(raw.size, 200), Charsets.ISO_8859_1)
        val enc = Regex("encoding=[\"']([A-Za-z0-9_-]+)[\"']").find(head)?.groupValues?.get(1)
        val cs = try { enc?.let { charset(it) } } catch (_: Exception) { null } ?: Charsets.UTF_8
        return String(raw, cs).removePrefix("﻿")
    }

    private fun resolveHref(base: String, href: String): String? {
        if (LinkTarget.isExternal(href)) return href
        if (href.startsWith("#")) {
            val idx = spineIndexByPath[base] ?: return null
            val a = href.substring(1)
            return LinkTarget(chapterIndexFor(idx, a), a).encode()
        }
        val path = Paths.resolve(base, href)
        val idx = spineIndexByPath[path] ?: return null
        val frag = Paths.fragment(href)
        return LinkTarget(chapterIndexFor(idx, frag), frag).encode()
    }

    /** Like [chapterFor], but usable while the parts are still being computed (links don't matter then). */
    private fun chapterIndexFor(spineIndex: Int, anchor: String?): Int =
        if (!::chapterSpine.isInitialized) spineIndex else chapterFor(spineIndex, anchor)

    override fun image(resource: String): ImageData? = bytes(resource)?.let { ImageData.Encoded(it) }

    override fun close() = zip.close()

    companion object {
        /** Spine items larger than this (uncompressed bytes) are checked for splitting. */
        const val SPLIT_BYTES = 250_000L
        const val PART_CHARS = 100_000
    }
}

