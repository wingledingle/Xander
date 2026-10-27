package app.quinten.formats.fb2

import app.quinten.formats.InMemoryDocument
import app.quinten.formats.RawChapter
import app.quinten.formats.UnsupportedBookException
import app.quinten.model.AlignHint
import app.quinten.model.Block
import app.quinten.model.BlockKind
import app.quinten.model.BookFormat
import app.quinten.model.BookMetadata
import app.quinten.model.ContentBuilder
import app.quinten.model.InlineStyle
import app.quinten.model.LinkTarget
import app.quinten.model.TocEntry
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import java.io.File
import java.io.InputStream
import java.util.Base64
import java.io.RandomAccessFile
import java.nio.charset.Charset
import app.quinten.formats.util.TextEncoding
import app.quinten.formats.util.Zips


/**
 * FictionBook 2 parser. Top-level sections of the main body become chapters; notes bodies
 * become a final "Notes" chapter whose sections are link targets for footnote popups.
 */
object Fb2Parser {

    fun open(file: File): InMemoryDocument {
        val name = file.name.lowercase()
        if (name.endsWith(".zip") || name.endsWith(".fbz") || isZip(file)) {
            Zips.open(file).use { z ->
                val entry = z.entries().asSequence().firstOrNull { it.name.lowercase().endsWith(".fb2") }
                    ?: z.entries().asSequence().firstOrNull { !it.isDirectory } ?: throw UnsupportedBookException("Empty archive")
                return parse(z.getInputStream(entry).use { it.readBytes() }, null)
            }
        }
        return parse(file.readBytes(), file)
    }

    private fun isZip(f: File) = f.inputStream().use { val b = ByteArray(2); it.read(b) == 2 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte() }

    fun parse(input: InputStream): InMemoryDocument = parse(input.readBytes(), null)

    /** Charset of an FB2 file: BOM, then the XML declaration, then a guess. */
    internal fun charsetOf(bytes: ByteArray): Pair<Charset, Int> {
        TextEncoding.bom(bytes)?.let { return it }
        val head = String(bytes, 0, minOf(200, bytes.size), Charsets.ISO_8859_1)
        if (head.startsWith("<?xml")) {
            val enc = Regex("encoding=[\"']([A-Za-z0-9_.:-]+)[\"']").find(head.substringBefore("?>"))?.groupValues?.get(1)
            val cs = try { enc?.let { charset(it) } } catch (_: Exception) { null }
            if (cs != null) return cs to 0
            if (enc == null) return Charsets.UTF_8 to 0 // XML default
        }
        return TextEncoding.guess(bytes) to 0
    }

    /** A `<binary>` element's base64 content at bytes [start, end) of the source. */
    private class BinRange(val id: String, val tagStart: Int, val start: Int, val end: Int, val after: Int)

    private fun indexOf(b: ByteArray, pat: ByteArray, from: Int): Int {
        var i = from
        val last = b.size - pat.size
        outer@ while (i <= last) {
            for (k in pat.indices) if (b[i + k] != pat[k]) { i++; continue@outer }
            return i
        }
        return -1
    }

    /** Finds `<binary>` elements in ASCII-compatible encodings without decoding the file. */
    private fun findBinaries(b: ByteArray): List<BinRange> {
        val open = "<binary".toByteArray(Charsets.ISO_8859_1)
        val close = "</binary>".toByteArray(Charsets.ISO_8859_1)
        val idRe = Regex("\\bid\\s*=\\s*[\"']([^\"']*)[\"']")
        val out = ArrayList<BinRange>()
        var p = 0
        while (true) {
            val s = indexOf(b, open, p)
            if (s < 0) break
            var gt = s + open.size
            while (gt < b.size && b[gt] != '>'.code.toByte()) gt++
            if (gt >= b.size) break
            val tag = String(b, s, gt - s, Charsets.ISO_8859_1)
            if (b[gt - 1] == '/'.code.toByte()) { p = gt + 1; continue }
            val e = indexOf(b, close, gt + 1)
            if (e < 0) break
            idRe.find(tag)?.let { out += BinRange(it.groupValues[1], s, gt + 1, e, e + close.size) }
            p = e + close.size
        }
        return out
    }

    /** Lazily reads and decodes base64 data at [off, off + len) of [f]. */
    private fun fileRange(f: File, off: Long, len: Int): () -> ByteArray? = {
        try {
            RandomAccessFile(f, "r").use { a -> a.seek(off); ByteArray(len).also { a.readFully(it) } }.let { decodeBase64(it) }
        } catch (_: Exception) { null }
    }

    private fun decodeBase64(data: ByteArray): ByteArray? = try { Base64.getMimeDecoder().decode(data) } catch (_: Exception) { null }

    /**
     * Parses FB2 [raw] bytes. Images are not kept as base64 text: for a plain file ([source])
     * only their byte ranges are kept and read back on demand; otherwise they are decoded once.
     * The XML parser never sees the (often very large) base64 data.
     */
    fun parse(raw: ByteArray, source: File?): InMemoryDocument {
        val (cs, bomLen) = charsetOf(raw)
        val asciiCompatible = cs != Charsets.UTF_16LE && cs != Charsets.UTF_16BE && !cs.name().startsWith("UTF-16") && !cs.name().startsWith("UTF-32")
        val binaries = HashMap<String, () -> ByteArray?>()
        val text: String
        if (asciiCompatible) {
            val ranges = findBinaries(raw)
            for (r in ranges) {
                binaries[r.id] = if (source != null) fileRange(source, r.start.toLong(), r.end - r.start)
                else decodeBase64(raw.copyOfRange(r.start, r.end)).let { bytes -> { bytes } }
            }
            // Text without the binaries.
            if (ranges.isEmpty()) text = String(raw, bomLen, raw.size - bomLen, cs)
            else {
                val sb = java.io.ByteArrayOutputStream(raw.size - ranges.sumOf { it.after - it.tagStart })
                var p = bomLen
                for (r in ranges) { sb.write(raw, p, r.tagStart - p); p = r.after }
                sb.write(raw, p, raw.size - p)
                text = String(sb.toByteArray(), cs)
            }
        } else text = String(raw, bomLen, raw.size - bomLen, cs)
        val doc = Jsoup.parse(text.removePrefix("\uFEFF"), "", Parser.xmlParser())
        val root = doc.selectFirst("FictionBook") ?: throw UnsupportedBookException("Not a FictionBook file")

        // Binaries left in the text (UTF-16 files or unusual markup).
        for (b in root.children().filter { it.normalName() == "binary" }) {
            val id = b.attr("id")
            if (id in binaries) continue
            val bytes = decodeBase64(b.text().toByteArray(Charsets.ISO_8859_1))
            binaries[id] = { bytes }
        }

        val bodies = root.children().filter { it.normalName() == "body" }
        val main = bodies.firstOrNull { it.attr("name").isEmpty() } ?: bodies.firstOrNull() ?: throw UnsupportedBookException("No body")
        val noteBodies = bodies.filter { it !== main }

        // Map of anchor id -> chapter index, filled while building chapters.
        val anchorChapter = HashMap<String, Int>()
        val chapters = ArrayList<RawChapter>()
        val toc = ArrayList<TocEntry>()

        // Pre-assign chapters: each top-level section in the main body (or the body itself).
        val topSections = main.children().filter { it.normalName() == "section" }
        val prelude = main.children().filter { it.normalName() != "section" }
        val notesChapter = topSections.size.coerceAtLeast(1) + (if (prelude.isNotEmpty() && topSections.isNotEmpty()) 1 else 0)
        // Collect ids first so links can be resolved forward.
        var idx = 0
        if (prelude.isNotEmpty() && topSections.isNotEmpty()) { prelude.forEach { collectIds(it, idx, anchorChapter) }; idx++ }
        if (topSections.isEmpty()) collectIds(main, idx, anchorChapter) else topSections.forEach { collectIds(it, idx++, anchorChapter) }
        noteBodies.forEach { collectIds(it, notesChapter, anchorChapter) }

        val resolver = { href: String ->
            if (href.startsWith("#")) {
                val id = href.substring(1)
                anchorChapter[id]?.let { LinkTarget(it, id).encode() }
            } else if (LinkTarget.isExternal(href)) href else null
        }

        val noteIds = noteBodies.flatMap { nb -> nb.select("[id]").map { it.id() } }.toSet()
        fun build(elements: List<Element>, chapterIndex: Int, tocLevelBase: Int, collectToc: Boolean): RawChapter {
            val w = Writer(resolver, noteIds)
            for (e in elements) w.block(e, 0)
            val blocks = w.b.build()
            if (collectToc) for ((title, level) in w.titles) toc += TocEntry(title, tocLevelBase + level, chapterIndex)
            val title = w.titles.firstOrNull()?.first
            return RawChapter(title, blocks)
        }

        if (prelude.isNotEmpty() && topSections.isNotEmpty()) chapters += build(prelude, 0, 0, true)
        if (topSections.isEmpty()) chapters += build(listOf(main), 0, 0, true)
        else for (s in topSections) chapters += build(listOf(s), chapters.size, 0, true)
        if (noteBodies.isNotEmpty()) {
            val notes = build(noteBodies, chapters.size, 0, false)
            val notesTitle = noteBodies.firstNotNullOfOrNull { nb -> nb.children().firstOrNull { it.normalName() == "title" }?.text()?.trim()?.ifEmpty { null } } ?: "Notes"
            chapters += RawChapter(notesTitle, notes.blocks.map { b ->
                if (b is app.quinten.model.TextBlock && b.kind == BlockKind.PARAGRAPH) b.copy(kind = BlockKind.FOOTNOTE) else b
            }.mapIndexed { i, b -> ContentBuilder.assignUnit(b, i) })
            toc += TocEntry(notesTitle, 0, chapters.size - 1)
        }

        val meta = parseMeta(root)
        return InMemoryDocument(BookFormat.FB2, meta, chapters, toc, binaries.mapKeys { it.key })
    }

    private fun collectIds(e: Element, chapter: Int, out: HashMap<String, Int>) {
        if (e.id().isNotEmpty()) out[e.id()] = chapter
        for (c in e.select("[id]")) out[c.id()] = chapter
    }

    private fun parseMeta(root: Element): BookMetadata {
        val ti = root.selectFirst("description > title-info") ?: root.selectFirst("title-info")
        val authors = ti?.children()?.filter { it.normalName() == "author" }?.map { a ->
            listOf("first-name", "middle-name", "last-name").mapNotNull { n -> a.selectFirst(n)?.text()?.trim()?.ifEmpty { null } }
                .joinToString(" ").ifEmpty { a.selectFirst("nickname")?.text().orEmpty() }
        }?.filter { it.isNotBlank() } ?: emptyList()
        val coverHref = ti?.selectFirst("coverpage image")?.let { it.attr("l:href").ifEmpty { it.attr("xlink:href").ifEmpty { it.attr("href") } } }
        val seq = ti?.selectFirst("sequence")
        return BookMetadata(
            title = ti?.selectFirst("book-title")?.text()?.trim(),
            authors = authors,
            language = ti?.selectFirst("lang")?.text()?.trim(),
            series = seq?.attr("name")?.ifEmpty { null },
            seriesIndex = seq?.attr("number")?.toFloatOrNull(),
            description = ti?.selectFirst("annotation")?.text()?.trim(),
            publisher = root.selectFirst("publish-info publisher")?.text(),
            identifier = root.selectFirst("publish-info isbn")?.text() ?: root.selectFirst("document-info id")?.text(),
            subjects = ti?.select("genre")?.map { it.text() } ?: emptyList(),
            year = ti?.selectFirst("date")?.text()?.take(4),
            coverResource = coverHref?.removePrefix("#"),
        )
    }

    private class Writer(val resolve: (String) -> String?, val noteIds: Set<String>) {
        val b = ContentBuilder()
        val titles = ArrayList<Pair<String, Int>>()
        var sectionDepth = 0

        fun block(e: Element, depth: Int) {
            if (e.id().isNotEmpty()) b.anchor(e.id())
            when (e.normalName()) {
                "body" -> e.children().forEach { block(it, depth) }
                "section" -> { sectionDepth++; e.children().forEach { block(it, depth + 1) }; sectionDepth-- }
                "title" -> {
                    val text = e.children().joinToString(" ") { it.text().trim() }.ifEmpty { e.text() }
                    if (text.isNotBlank()) titles += text.trim() to (depth - 1).coerceAtLeast(0)
                    val paras = e.children().filter { it.normalName() == "p" }
                    if (paras.isEmpty()) { b.beginBlock(BlockKind.HEADING, depth.coerceIn(1, 6)); inline(e); b.endBlock() }
                    for (p in paras) { b.beginBlock(BlockKind.HEADING, depth.coerceIn(1, 6)); inline(p); b.endBlock() }
                }
                "subtitle" -> { b.beginBlock(BlockKind.SUBTITLE, 0, AlignHint.CENTER); inline(e); b.endBlock() }
                "p" -> { b.beginBlock(BlockKind.PARAGRAPH); inline(e); b.endBlock() }
                "empty-line" -> b.endBlock()
                "image" -> imageEl(e)
                "epigraph" -> container(e, BlockKind.EPIGRAPH, depth)
                "cite" -> container(e, BlockKind.QUOTE, depth)
                "annotation" -> container(e, BlockKind.QUOTE, depth)
                "poem" -> poem(e, depth)
                "text-author" -> { b.beginBlock(BlockKind.AUTHOR, 0, AlignHint.RIGHT); inline(e); b.endBlock() }
                "table" -> for (tr in e.children().filter { it.normalName() == "tr" }) {
                    b.beginBlock(BlockKind.TABLE_ROW)
                    tr.children().forEachIndexed { i, td -> if (i > 0) b.text(" | "); inline(td) }
                    b.endBlock()
                }
                else -> { b.beginBlock(); inline(e); b.endBlock() }
            }
        }

        private fun container(e: Element, kind: BlockKind, depth: Int) {
            for (c in e.children()) {
                if (c.id().isNotEmpty()) b.anchor(c.id())
                when (c.normalName()) {
                    "p" -> { b.beginBlock(kind, 1); inline(c); b.endBlock() }
                    "text-author" -> { b.beginBlock(BlockKind.AUTHOR, 0, AlignHint.RIGHT); inline(c); b.endBlock() }
                    "empty-line" -> b.endBlock()
                    "poem" -> poem(c, depth)
                    else -> block(c, depth)
                }
            }
        }

        private fun poem(e: Element, depth: Int) {
            for (c in e.children()) {
                when (c.normalName()) {
                    "stanza" -> {
                        b.beginBlock(BlockKind.POEM)
                        for (v in c.children()) {
                            when (v.normalName()) {
                                "v" -> { inline(v); b.lineBreak() }
                                "title", "subtitle" -> { b.pushStyle(InlineStyle.BOLD); inline(v); b.popStyle(InlineStyle.BOLD); b.lineBreak() }
                            }
                        }
                        b.endBlock()
                    }
                    "title" -> block(c, depth + 1)
                    "text-author" -> { b.beginBlock(BlockKind.AUTHOR, 0, AlignHint.RIGHT); inline(c); b.endBlock() }
                    "epigraph" -> container(c, BlockKind.EPIGRAPH, depth)
                    else -> block(c, depth)
                }
            }
        }

        private fun imageEl(e: Element) {
            val href = e.attr("l:href").ifEmpty { e.attr("xlink:href").ifEmpty { e.attributes().firstOrNull { it.key.endsWith("href") }?.value.orEmpty() } }
            if (href.startsWith("#")) b.image(href.substring(1), alt = e.attr("alt").ifEmpty { null })
        }

        fun inline(e: Element) {
            for (n in e.childNodes()) node(n)
        }

        private fun node(n: Node) {
            if (n is TextNode) { b.text(n.wholeText); return }
            if (n !is Element) return
            val style = when (n.normalName()) {
                "strong" -> InlineStyle.BOLD
                "emphasis" -> InlineStyle.ITALIC
                "strikethrough" -> InlineStyle.STRIKE
                "sup" -> InlineStyle.SUPERSCRIPT
                "sub" -> InlineStyle.SUBSCRIPT
                "code" -> InlineStyle.MONOSPACE
                else -> null
            }
            when {
                n.normalName() == "a" -> {
                    val href = n.attributes().firstOrNull { it.key.endsWith("href") }?.value.orEmpty()
                    val target = resolve(href)
                    val isNote = n.attr("type") == "note" || href.removePrefix("#") in noteIds
                    if (target != null) b.pushLink(target, isNote)
                    if (isNote) b.pushStyle(InlineStyle.SUPERSCRIPT)
                    inline(n)
                    if (isNote) b.popStyle(InlineStyle.SUPERSCRIPT)
                    if (target != null) b.popLink()
                }
                n.normalName() == "image" -> imageEl(n)
                style != null -> { b.pushStyle(style); inline(n); b.popStyle(style) }
                else -> inline(n)
            }
        }
    }
}

private fun RawChapter.withBlocks(blocks: List<Block>) = RawChapter(title, blocks)
