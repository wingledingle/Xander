package app.quinten.formats.docx

import app.quinten.formats.InMemoryDocument
import app.quinten.formats.UnsupportedBookException
import app.quinten.formats.util.ChapterSplitter
import app.quinten.formats.util.Paths
import app.quinten.model.AlignHint
import app.quinten.model.Block
import app.quinten.model.BlockKind
import app.quinten.model.BookFormat
import app.quinten.model.BookMetadata
import app.quinten.model.ContentBuilder
import app.quinten.model.InlineStyle
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.io.File
import app.quinten.formats.util.FileTitle
import app.quinten.formats.util.Zips

/** Office Open XML (Word 2007+) reader. */
object DocxParser {

    private class StyleInfo(
        val name: String, val basedOn: String?, val bold: Boolean?, val italic: Boolean?, val outline: Int?,
        val numId: String? = null, val ilvl: Int? = null,
    )

    /** One level of a list definition (word/numbering.xml). */
    private class Level(val format: String, val text: String, val start: Int)

    /** List definitions: numId -> levels, and running counters while converting. */
    private class Numbering(val levels: Map<String, Map<Int, Level>>, val starts: Map<String, Map<Int, Int>>) {
        private val counters = HashMap<String, IntArray>()

        fun hasList(numId: String) = numId in levels

        /** The label of the next item of list [numId] at level [ilvl] ("1.", "a)", "iv.", "• "), or null. */
        fun next(numId: String, ilvl: Int): String? {
            val lv = levels[numId] ?: return null
            val level = lv[ilvl] ?: return null
            val c = counters.getOrPut(numId) { IntArray(9) { -1 } }
            val l = ilvl.coerceIn(0, 8)
            fun startOf(i: Int) = starts[numId]?.get(i) ?: lv[i]?.start ?: 1
            c[l] = if (c[l] < 0) startOf(l) else c[l] + 1
            for (i in l + 1 until 9) c[i] = -1
            if (level.format == "bullet") return "• "
            if (level.format == "none") return null
            val label = Regex("%([1-9])").replace(level.text) { m ->
                val i = m.groupValues[1].toInt() - 1
                val v = if (c[i] < 0) startOf(i) else c[i]
                format(lv[i]?.format ?: "decimal", v)
            }
            return if (label.isBlank()) null else "$label "
        }

        companion object {
            fun format(fmt: String, n: Int): String = when (fmt) {
                "lowerLetter" -> letters(n).lowercase()
                "upperLetter" -> letters(n)
                "lowerRoman" -> roman(n).lowercase()
                "upperRoman" -> roman(n)
                "decimalZero" -> if (n in 0..9) "0$n" else n.toString()
                else -> n.toString()
            }

            private fun letters(n: Int): String {
                if (n <= 0) return n.toString()
                // Word repeats the letter: a..z, aa..zz, aaa...
                val ch = 'A' + (n - 1) % 26
                return ch.toString().repeat((n - 1) / 26 + 1)
            }

            private fun roman(n: Int): String {
                if (n <= 0 || n >= 4000) return n.toString()
                val vals = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
                val syms = arrayOf("M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I")
                var x = n
                val sb = StringBuilder()
                for (i in vals.indices) while (x >= vals[i]) { sb.append(syms[i]); x -= vals[i] }
                return sb.toString()
            }
        }
    }

    private fun parseNumbering(d: Document?): Numbering {
        if (d == null) return Numbering(emptyMap(), emptyMap())
        val abstract = HashMap<String, Map<Int, Level>>()
        for (a in d.select("w|abstractNum")) {
            val lv = HashMap<Int, Level>()
            for (l in a.children().filter { it.normalName() == "w:lvl" }) {
                val i = l.attr("w:ilvl").toIntOrNull() ?: continue
                fun v(tag: String) = l.children().firstOrNull { it.normalName() == tag }?.attr("w:val")
                lv[i] = Level(v("w:numfmt") ?: "decimal", v("w:lvltext") ?: "%${i + 1}.", v("w:start")?.toIntOrNull() ?: 1)
            }
            abstract[a.attr("w:abstractNumId")] = lv
        }
        val levels = HashMap<String, Map<Int, Level>>()
        val starts = HashMap<String, Map<Int, Int>>()
        for (n in d.select("w|num")) {
            val id = n.attr("w:numId")
            val absId = n.children().firstOrNull { it.normalName() == "w:abstractnumid" }?.attr("w:val") ?: continue
            levels[id] = abstract[absId] ?: continue
            val ov = HashMap<Int, Int>()
            for (o in n.children().filter { it.normalName() == "w:lvloverride" }) {
                val i = o.attr("w:ilvl").toIntOrNull() ?: continue
                o.children().firstOrNull { it.normalName() == "w:startoverride" }?.attr("w:val")?.toIntOrNull()?.let { ov[i] = it }
            }
            if (ov.isNotEmpty()) starts[id] = ov
        }
        return Numbering(levels, starts)
    }

    fun open(file: File): InMemoryDocument {
        val zip = Zips.open(file)
        try {
            fun xml(path: String): Document? = zip.getEntry(path)?.let { e ->
                zip.getInputStream(e).use { Jsoup.parse(it, "UTF-8", "", Parser.xmlParser()) }
            }
            fun rels(path: String): Map<String, Pair<String, Boolean>> {
                val relPath = Paths.dir(path) + "_rels/" + path.substringAfterLast('/') + ".rels"
                val d = xml(relPath) ?: return emptyMap()
                return d.select("Relationship").associate { r ->
                    val external = r.attr("TargetMode") == "External"
                    r.attr("Id") to ((if (external) r.attr("Target") else Paths.resolve(path, r.attr("Target"))) to external)
                }
            }
            val mainPath = xml("_rels/.rels")?.select("Relationship")
                ?.firstOrNull { it.attr("Type").endsWith("/officeDocument") }?.attr("Target")?.removePrefix("/") ?: "word/document.xml"
            val doc = xml(mainPath) ?: throw UnsupportedBookException("Not a Word document")
            val styles = parseStyles(xml(Paths.dir(mainPath) + "styles.xml"))
            val mainRels = rels(mainPath)

            val numbering = parseNumbering(xml(Paths.dir(mainPath) + "numbering.xml"))
            val conv = Converter(styles, mainRels, numbering = numbering)
            val body = doc.selectFirst("w|body") ?: doc.selectFirst("body") ?: throw UnsupportedBookException("No document body")
            conv.container(body)
            val bodyBlocks = conv.b.build()

            val notes = ArrayList<Block>()
            for ((part, prefix, tag) in listOf(Triple("footnotes.xml", "fn", "w|footnote"), Triple("endnotes.xml", "en", "w|endnote"))) {
                val path = Paths.dir(mainPath) + part
                val nd = xml(path) ?: continue
                val nc = Converter(styles, rels(path), footnoteMode = true, numbering = numbering)
                for (fn in nd.select(tag)) {
                    val type = fn.attr("w:type")
                    if (type == "separator" || type == "continuationSeparator" || type == "continuationNotice") continue
                    nc.b.anchor("$prefix${fn.attr("w:id")}")
                    nc.container(fn)
                }
                notes += nc.b.build()
            }

            val core = xml("docProps/core.xml")
            val meta = BookMetadata(
                title = core?.selectFirst("dc|title")?.text()?.ifBlank { null } ?: FileTitle.of(file),
                authors = listOfNotNull(core?.selectFirst("dc|creator")?.text()?.ifBlank { null }),
                description = core?.selectFirst("dc|description")?.text()?.ifBlank { null },
                subjects = listOfNotNull(core?.selectFirst("dc|subject")?.text()?.ifBlank { null }),
                language = core?.selectFirst("dc|language")?.text()?.ifBlank { null },
                coverResource = bodyBlocks.firstOrNull { it is app.quinten.model.ImageBlock }?.let { (it as app.quinten.model.ImageBlock).resource },
            )
            val split = ChapterSplitter.split(bodyBlocks, if (notes.isEmpty()) emptyList() else listOf("Notes" to notes))
            val zipRef = zip
            return object : InMemoryDocument(BookFormat.DOCX, meta, split.chapters, split.toc) {
                override fun image(resource: String) = synchronized(zipRef) {
                    zipRef.getEntry(resource)?.let { e -> zipRef.getInputStream(e).use { app.quinten.model.ImageData.Encoded(it.readBytes()) } }
                }
                override fun close() = zipRef.close()
            }
        } catch (e: Exception) {
            zip.close(); throw e
        }
    }

    private fun parseStyles(d: Document?): Map<String, StyleInfo> {
        if (d == null) return emptyMap()
        val m = HashMap<String, StyleInfo>()
        for (s in d.select("w|style")) {
            val id = s.attr("w:styleId")
            val rpr = s.selectFirst("w|rPr")
            val numPr = s.selectFirst("w|pPr w|numPr")
            m[id] = StyleInfo(
                name = s.selectFirst("w|name")?.attr("w:val")?.lowercase() ?: id.lowercase(),
                basedOn = s.selectFirst("w|basedOn")?.attr("w:val"),
                bold = rpr?.selectFirst("w|b")?.let { onOff(it) },
                italic = rpr?.selectFirst("w|i")?.let { onOff(it) },
                outline = s.selectFirst("w|pPr w|outlineLvl")?.attr("w:val")?.toIntOrNull(),
                numId = numPr?.selectFirst("w|numId")?.attr("w:val"),
                ilvl = numPr?.selectFirst("w|ilvl")?.attr("w:val")?.toIntOrNull(),
            )
        }
        return m
    }

    private fun onOff(e: Element): Boolean = e.attr("w:val").let { it.isEmpty() || it == "1" || it == "true" || it == "on" }

    private class Converter(
        val styles: Map<String, StyleInfo>, val rels: Map<String, Pair<String, Boolean>>, val footnoteMode: Boolean = false,
        val numbering: Numbering = Numbering(emptyMap(), emptyMap()),
    ) {
        val b = ContentBuilder()
        var noteCounter = 0
        /** Text boxes met inside the current paragraph; emitted as paragraphs after it. */
        private val textBoxes = ArrayList<Element>()

        /** The branch of an mc:AlternateContent to read: the first Choice, else the Fallback (never both). */
        fun alternate(e: Element): Element? =
            e.children().firstOrNull { it.normalName() == "mc:choice" } ?: e.children().firstOrNull { it.normalName() == "mc:fallback" }

        fun style(id: String?): List<StyleInfo> {
            val chain = ArrayList<StyleInfo>()
            var cur = id
            while (cur != null && chain.size < 10) {
                val s = styles[cur] ?: break
                chain += s; cur = s.basedOn
            }
            return chain
        }

        fun headingLevel(styleId: String?): Int? {
            for (s in style(styleId)) {
                if (s.name == "title") return 1
                Regex("^heading\\s*(\\d)$").find(s.name)?.let { return it.groupValues[1].toInt() }
                if (s.outline != null && s.outline in 0..5) return s.outline + 1
            }
            return null
        }

        fun container(e: Element) {
            for (c in e.children()) {
                when (c.normalName()) {
                    "w:p" -> paragraph(c)
                    "w:tbl" -> table(c)
                    "w:sdt" -> c.selectFirst("w|sdtContent")?.let { container(it) }
                    // jsoup's normalName() is lower case.
                    "w:customxml", "w:smarttag", "w:ins", "w:moveto" -> container(c)
                    "mc:alternatecontent" -> alternate(c)?.let { container(it) }
                    "w:bookmarkstart" -> b.anchor(c.attr("w:name"))
                }
            }
        }

        fun table(t: Element) {
            for (tr in t.children().filter { it.normalName() == "w:tr" }) {
                val cells = tr.children().filter { it.normalName() == "w:tc" }
                // Cells with several paragraphs become one row block separated by " | ".
                b.beginBlock(BlockKind.TABLE_ROW)
                cells.forEachIndexed { i, tc ->
                    if (i > 0) b.text(" | ")
                    tc.select("w|p").forEachIndexed { pi, p -> if (pi > 0) b.text(" "); runs(p, emptyList()) }
                }
                b.endBlock()
                flushTextBoxes()
            }
        }

        fun paragraph(p: Element) {
            val ppr = p.children().firstOrNull { it.normalName() == "w:ppr" }
            val styleId = ppr?.selectFirst("w|pStyle")?.attr("w:val")
            val level = headingLevel(styleId)
            val jc = ppr?.selectFirst("w|jc")?.attr("w:val")
            val align = when (jc) { "center" -> AlignHint.CENTER; "right", "end" -> AlignHint.RIGHT; else -> AlignHint.DEFAULT }
            val numPr = ppr?.selectFirst("w|numPr")
            val styleNum = style(styleId).firstOrNull { it.numId != null }
            val numId = numPr?.selectFirst("w|numId")?.attr("w:val") ?: styleNum?.numId
            val ilvl = numPr?.selectFirst("w|ilvl")?.attr("w:val")?.toIntOrNull() ?: styleNum?.ilvl ?: 0
            // numId 0 explicitly removes the numbering.
            val numbered = numId != null && numId != "0"
            val isList = numbered || (numId == null && style(styleId).any { it.name.startsWith("list") })
            val kind = when {
                footnoteMode -> BlockKind.FOOTNOTE
                level != null -> BlockKind.HEADING
                isList -> BlockKind.LIST_ITEM
                style(styleId).any { it.name.contains("quote") } -> BlockKind.QUOTE
                style(styleId).any { it.name == "caption" } -> BlockKind.CAPTION
                else -> BlockKind.PARAGRAPH
            }
            b.beginBlock(kind, level ?: (if (isList) ilvl else 0), align)
            if (isList && !footnoteMode && level == null) {
                // Real list numbers from numbering.xml; a bullet when the list isn't defined there.
                val label = if (numbered) numbering.next(numId!!, ilvl) else null
                b.text(label ?: if (numbered && numbering.hasList(numId!!)) "" else "• ")
            }
            runs(p, style(styleId))
            b.endBlock()
            flushTextBoxes()
        }

        /** Text boxes (w:txbxContent) met inside a paragraph are read as paragraphs after it. */
        private fun flushTextBoxes() {
            if (textBoxes.isEmpty()) return
            val boxes = ArrayList(textBoxes); textBoxes.clear()
            for (t in boxes) container(t)
        }

        fun runs(p: Element, pStyle: List<StyleInfo>) {
            for (c in p.children()) {
                when (c.normalName()) {
                    "w:r" -> run(c, pStyle)
                    "w:hyperlink" -> {
                        val rid = c.attr("r:id")
                        val target = if (rid.isNotEmpty()) rels[rid]?.takeIf { it.second }?.first
                        else c.attr("w:anchor").takeIf { it.isNotEmpty() }?.let { ChapterSplitter.pendingLink(it) }
                        if (target != null) b.pushLink(target)
                        runs(c, pStyle)
                        if (target != null) b.popLink()
                    }
                    "w:bookmarkstart" -> b.anchor(c.attr("w:name"))
                    // jsoup's normalName() is lower case. Moved text is kept at its new place only.
                    "w:ins", "w:moveto", "w:smarttag", "w:customxml", "w:fldsimple" -> runs(c, pStyle)
                    "w:sdt" -> c.selectFirst("w|sdtContent")?.let { runs(it, pStyle) }
                    "mc:alternatecontent" -> alternate(c)?.let { runs(it, pStyle) }
                    "w:del", "w:movefrom" -> {} // deleted revisions are not part of the text
                }
            }
        }

        fun run(r: Element, pStyle: List<StyleInfo>) {
            val rpr = r.children().firstOrNull { it.normalName() == "w:rpr" }
            val rStyle = style(rpr?.selectFirst("w|rStyle")?.attr("w:val"))
            fun prop(tag: String, fromStyle: (StyleInfo) -> Boolean?): Boolean {
                rpr?.children()?.firstOrNull { it.normalName() == tag }?.let { return onOff(it) }
                return (rStyle + pStyle).firstNotNullOfOrNull(fromStyle) ?: false
            }
            val styles = ArrayList<InlineStyle>()
            run {
                if (prop("w:b") { it.bold }) styles += InlineStyle.BOLD
                if (prop("w:i") { it.italic }) styles += InlineStyle.ITALIC
                if (rpr?.selectFirst("w|u")?.attr("w:val")?.let { it != "none" } == true) styles += InlineStyle.UNDERLINE
                if (prop("w:strike") { null } || prop("w:dstrike") { null }) styles += InlineStyle.STRIKE
                if (prop("w:smallcaps") { null }) styles += InlineStyle.SMALL_CAPS
                when (rpr?.selectFirst("w|vertAlign")?.attr("w:val")) {
                    "superscript" -> styles += InlineStyle.SUPERSCRIPT
                    "subscript" -> styles += InlineStyle.SUBSCRIPT
                }
            }
            val hidden = rpr?.selectFirst("w|vanish")?.let { onOff(it) } == true
            if (hidden) return
            val caps = rpr?.selectFirst("w|caps")?.let { onOff(it) } == true
            for (s in styles) b.pushStyle(s)
            runContent(r.children(), caps)
            for (s in styles.asReversed()) b.popStyle(s)
        }

        private fun collectTextBoxes(e: Element) {
            for (t in e.select("w|txbxContent")) if (t.parents().none { it.normalName() == "w:txbxcontent" }) textBoxes += t
        }

        private fun runContent(children: List<Element>, caps: Boolean) {
            for (c in children) {
                when (c.normalName()) {
                    "mc:alternatecontent" -> alternate(c)?.let { runContent(it.children(), caps) }
                    "w:t" -> b.text(if (caps) c.wholeText().uppercase() else c.wholeText())
                    "w:tab" -> b.text(" ")
                    "w:br", "w:cr" -> if (c.attr("w:type") != "page" && c.attr("w:type") != "column") b.lineBreak()
                    "w:nobreakhyphen" -> b.text("-")
                    "w:sym" -> c.attr("w:char").toIntOrNull(16)?.let { code -> if (code in 0x20..0xEFFF && code !in 0xF000..0xF0FF) b.text(String(Character.toChars(code))) }
                    "w:footnotereference", "w:endnotereference" -> {
                        val prefix = if (c.normalName() == "w:footnotereference") "fn" else "en"
                        val id = c.attr("w:id")
                        b.pushLink(ChapterSplitter.pendingLink("$prefix$id"), footnote = true)
                        b.pushStyle(InlineStyle.SUPERSCRIPT)
                        noteCounter++
                        b.text(noteCounter.toString())
                        b.popStyle(InlineStyle.SUPERSCRIPT)
                        b.popLink()
                    }
                    "w:drawing" -> {
                        c.selectFirst("a|blip")?.attr("r:embed")?.let { rid -> rels[rid]?.first }?.let { b.image(it, alt = c.selectFirst("wp|docPr")?.attr("descr")?.ifBlank { null }) }
                        collectTextBoxes(c)
                    }
                    "w:pict", "w:object" -> {
                        c.selectFirst("v|imagedata")?.attr("r:id")?.let { rid -> rels[rid]?.first }?.let { b.image(it) }
                        collectTextBoxes(c)
                    }
                }
            }
        }

    }
}
