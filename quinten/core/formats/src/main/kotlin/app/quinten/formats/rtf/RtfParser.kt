package app.quinten.formats.rtf

import app.quinten.formats.InMemoryDocument
import app.quinten.formats.UnsupportedBookException
import app.quinten.formats.util.ChapterSplitter
import app.quinten.formats.util.FileTitle
import app.quinten.model.AlignHint
import app.quinten.model.Block
import app.quinten.model.BlockKind
import app.quinten.model.BookFormat
import app.quinten.model.BookMetadata
import app.quinten.model.ContentBuilder
import app.quinten.model.ImageData
import app.quinten.model.InlineStyle
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.Charset

/** Rich Text Format reader: text, emphasis, headings (via styles/outline levels), images, footnotes, links. */
object RtfParser {

    fun open(file: File): InMemoryDocument = parse(file.readBytes(), FileTitle.of(file))

    private data class CharState(
        var bold: Boolean = false, var italic: Boolean = false, var underline: Boolean = false, var strike: Boolean = false,
        var sup: Boolean = false, var sub: Boolean = false, var hidden: Boolean = false, var font: Int = -1,
        var skip: Boolean = false, var uc: Int = 1, var dest: String? = null,
    )

    fun parse(data: ByteArray, fallbackTitle: String? = null): InMemoryDocument {
        val head = String(data, 0, minOf(5, data.size), Charsets.ISO_8859_1)
        if (!head.startsWith("{\\rtf")) throw UnsupportedBookException("Not an RTF file")
        val p = Parser(data)
        p.run()
        val blocks = p.body.build()
        val notes = p.notes.build()
        val split = ChapterSplitter.split(blocks, if (notes.isEmpty()) emptyList() else listOf("Notes" to notes))
        val images = p.images
        val meta = BookMetadata(title = p.infoTitle ?: fallbackTitle, authors = listOfNotNull(p.infoAuthor))
        return object : InMemoryDocument(BookFormat.RTF, meta, split.chapters, split.toc) {
            override fun image(resource: String): ImageData? = images[resource]?.let { ImageData.Encoded(it) }
        }
    }

    private class Parser(val d: ByteArray) {
        val body = ContentBuilder()
        val notes = ContentBuilder()
        val images = HashMap<String, ByteArray>()
        var infoTitle: String? = null
        var infoAuthor: String? = null

        private var pos = 0
        private val stack = ArrayList<CharState>()
        private var st = CharState()
        private var codepage: Charset = Charset.forName("windows-1252")
        private val fontCharsets = HashMap<Int, Charset>()
        private val styleNames = HashMap<Int, String>()
        private var pendingBytes = ByteArrayOutputStream()
        private var ucSkip = 0
        private var out = body
        private var paraStyle = -1
        private var outline = -1
        private var align = AlignHint.DEFAULT
        private var inTable = false
        private var noteCount = 0
        private var imageCount = 0
        // destination buffers
        private val destText = StringBuilder()
        private var pictHex: StringBuilder? = null
        private var pictBin: ByteArray? = null
        /** Per open field result: whether it pushed a link. */
        private val fieldLinks = ArrayList<Boolean>()
        private var pictType: String? = null
        private var fieldInst: String? = null
        private var fontTblCurrent = -1
        private var styleCurrent = -1
        private val openStyles = HashSet<InlineStyle>()

        private val skipDest = setOf(
            "colortbl", "header", "headerl", "headerr", "headerf", "footer", "footerl", "footerr", "footerf", "listtable",
            "listoverridetable", "rsidtbl", "generator", "xmlnstbl", "themedata", "colorschememapping", "datastore",
            "latentstyles", "mmathPr", "pgdsctbl", "nonshppict", "object", "objdata", "revtbl", "filetbl", "printim",
            "userprops", "docvar", "fldtype", "wgrffmtfilter", "panose", "falt", "listtext", "pntext", "pntxta", "pntxtb",
            "xe", "tc", "bkmkend", "atnid", "atnauthor", "annotation", "comment", "template", "operator", "company",
        )

        fun run() {
            while (pos < d.size) {
                val c = d[pos].toInt() and 0xFF
                when (c) {
                    '{'.code -> { flushBytes(); stack.add(st.copy()); pos++ ; groupStart() }
                    '}'.code -> { flushBytes(); groupEnd(); pos++ }
                    '\\'.code -> control()
                    '\r'.code, '\n'.code -> pos++
                    else -> {
                        pos++
                        if (ucSkip > 0) { ucSkip--; continue }
                        if (pictHex != null && st.dest == "pict") { if (c.toChar().isLetterOrDigit()) pictHex!!.append(c.toChar()); continue }
                        pendingBytes.write(c)
                    }
                }
            }
            flushBytes()
            out.endBlock()
            styleSync(emptySet())
        }

        private var groupDepthAtDest = ArrayList<Pair<Int, String>>()

        private fun groupStart() {}

        private fun groupEnd() {
            val prev = st
            val dest = prev.dest
            st = if (stack.isNotEmpty()) stack.removeAt(stack.size - 1) else CharState()
            if (dest != null && st.dest != dest) endDestination(dest)
            if (!st.skip && st.dest == null) styleSync(currentStyles())
        }

        private fun endDestination(dest: String) {
            when (dest) {
                "title" -> infoTitle = destText.toString().trim().ifEmpty { null }
                "author" -> infoAuthor = destText.toString().trim().ifEmpty { null }
                "fonttbl-entry" -> {}
                "stylesheet-entry" -> if (styleCurrent >= 0) styleNames[styleCurrent] = destText.toString().trim().trimEnd(';').lowercase()
                "pict" -> {
                    val hex = pictHex?.toString()
                    pictHex = null
                    val bin = pictBin
                    pictBin = null
                    if (bin != null && pictType != null && bin.size > 8) {
                        val id = "rtfimg${imageCount++}.$pictType"
                        images[id] = bin
                        out.image(id)
                    } else if (hex != null && pictType != null && hex.length > 16) {
                        val bytes = ByteArray(hex.length / 2) { i -> ((Character.digit(hex[2 * i], 16) shl 4) or Character.digit(hex[2 * i + 1], 16)).toByte() }
                        val id = "rtfimg${imageCount++}.$pictType"
                        images[id] = bytes
                        out.image(id)
                    }
                    pictType = null
                }
                "fldinst" -> fieldInst = destText.toString().trim()
                "fldrslt" -> {
                    // A link was pushed when fldrslt started, for HYPERLINK fields only.
                    if (fieldLinks.removeLastOrNull() == true) { styleSync(emptySet()); out.popLink() }
                }
                "footnote" -> {
                    styleSync(emptySet())
                    notes.endBlock()
                    out = body
                }
                "bkmkstart" -> out.anchor(destText.toString().trim())
            }
            destText.setLength(0)
        }

        private fun currentStyles(): Set<InlineStyle> = buildSet {
            if (st.bold) add(InlineStyle.BOLD)
            if (st.italic) add(InlineStyle.ITALIC)
            if (st.underline) add(InlineStyle.UNDERLINE)
            if (st.strike) add(InlineStyle.STRIKE)
            if (st.sup) add(InlineStyle.SUPERSCRIPT)
            if (st.sub) add(InlineStyle.SUBSCRIPT)
        }

        private fun styleSync(target: Set<InlineStyle>) {
            for (s in openStyles.toList()) if (s !in target) { out.popStyle(s); openStyles.remove(s) }
            for (s in target) if (s !in openStyles) { out.pushStyle(s); openStyles.add(s) }
        }

        private fun charset(): Charset = (if (st.font >= 0) fontCharsets[st.font] else null) ?: codepage

        private fun flushBytes() {
            if (pendingBytes.size() == 0) return
            val s = String(pendingBytes.toByteArray(), charset())
            pendingBytes = ByteArrayOutputStream()
            emitText(s)
        }

        private fun emitText(s: String) {
            val dest = st.dest
            if (dest != null && dest != "footnote" && dest != "fldrslt") { destText.append(s); return }
            if (st.skip || st.hidden) return
            styleSync(currentStyles())
            out.text(s)
        }

        private fun control() {
            pos++ // backslash
            if (pos >= d.size) return
            val c = d[pos].toInt().toChar()
            if (!c.isLetter()) {
                pos++
                when (c) {
                    '\\', '{', '}' -> { if (ucSkip > 0) ucSkip-- else pendingBytes.write(c.code) }
                    '~' -> { flushBytes(); emitText(" ") }
                    '_' -> { flushBytes(); emitText("‑") }
                    '-' -> {}
                    '*' -> { flushBytes(); st.skip = true; st.dest = "*" }
                    '\'' -> {
                        if (pos + 2 <= d.size) {
                            val hex = String(d, pos, 2, Charsets.ISO_8859_1)
                            pos += 2
                            if (ucSkip > 0) { ucSkip--; return }
                            hex.toIntOrNull(16)?.let { pendingBytes.write(it) }
                        }
                    }
                    '\n', '\r' -> { flushBytes(); par() }
                }
                return
            }
            val start = pos
            while (pos < d.size && (d[pos].toInt().toChar()).isLetter()) pos++
            val word = String(d, start, pos - start, Charsets.ISO_8859_1)
            var param: Int? = null
            if (pos < d.size && (d[pos].toInt().toChar() == '-' || d[pos].toInt().toChar().isDigit())) {
                val ps = pos
                pos++
                while (pos < d.size && d[pos].toInt().toChar().isDigit()) pos++
                param = String(d, ps, pos - ps, Charsets.ISO_8859_1).toIntOrNull()
            }
            if (pos < d.size && d[pos].toInt().toChar() == ' ') pos++
            flushBytes()
            word(word, param)
        }

        private fun beginDest(name: String, skipText: Boolean = true) {
            st.dest = name
            st.skip = skipText
            destText.setLength(0)
        }

        private fun word(w: String, p: Int?) {
            if (w == "bin") {
                // \binN: N raw bytes follow (binary picture data); never parse them as RTF.
                val n = (p ?: 0).coerceIn(0, d.size - pos)
                if (st.dest == "pict") pictBin = d.copyOfRange(pos, pos + n)
                pos += n
                return
            }
            // Destination handling first.
            if (st.dest == "*" && w !in setOf("fldinst", "bkmkstart", "shppict", "pict", "footnote")) {
                st.dest = "skip"; return
            }
            when (w) {
                "ansicpg" -> p?.let { cpCharset(it) }?.let { codepage = it }
                "fonttbl" -> { beginDest("fonttbl"); return }
                "stylesheet" -> { beginDest("stylesheet"); return }
                "info" -> { beginDest("info"); return }
                "title", "author" -> if (st.dest == "info" || st.dest == "*") { beginDest(w); return }
                "pict" -> { beginDest("pict"); pictHex = StringBuilder(); return }
                "pngblip" -> pictType = "png"
                "jpegblip" -> pictType = "jpg"
                "shppict" -> { st.dest = stack.lastOrNull()?.dest; st.skip = stack.lastOrNull()?.skip ?: false; return }
                "fldinst" -> { beginDest("fldinst"); return }
                "fldrslt" -> {
                    st.dest = null; st.skip = false
                    val m = fieldInst?.let { Regex("HYPERLINK\\s+(\\\\l\\s+)?\"([^\"]+)\"").find(it) }
                    val url = m?.let { if (it.groupValues[1].isNotEmpty() || it.groupValues[2].startsWith("#")) ChapterSplitter.pendingLink(it.groupValues[2].removePrefix("#")) else it.groupValues[2] }
                    fieldInst = null
                    // Only HYPERLINK fields become links (no empty-target links for PAGE, REF, TOC...).
                    if (url != null && url.isNotBlank()) { styleSync(emptySet()); out.pushLink(url, false) }
                    fieldLinks += url != null && url.isNotBlank()
                    // the group end pops the link (see endDestination)
                    st.dest = "fldrslt"

                    return
                }
                "footnote" -> {
                    noteCount++
                    val id = "rtfnote$noteCount"
                    styleSync(emptySet())
                    out.pushLink(ChapterSplitter.pendingLink(id), footnote = true)
                    out.pushStyle(InlineStyle.SUPERSCRIPT); out.text("$noteCount"); out.popStyle(InlineStyle.SUPERSCRIPT)
                    out.popLink()
                    out = notes
                    notes.beginBlock(BlockKind.FOOTNOTE)
                    notes.anchor(id)
                    notes.text("$noteCount. ")
                    st.dest = "footnote"; st.skip = false
                    return
                }
                "bkmkstart" -> { beginDest("bkmkstart"); return }
            }
            if (w in skipDest) { st.dest = "skip"; st.skip = true; return }
            if (st.dest == "fonttbl") {
                when (w) {
                    "f" -> fontTblCurrent = p ?: -1
                    "fcharset" -> if (fontTblCurrent >= 0) charsetFor(p ?: 0)?.let { fontCharsets[fontTblCurrent] = it }
                    "cpg" -> if (fontTblCurrent >= 0) p?.let { cpCharset(it) }?.let { fontCharsets[fontTblCurrent] = it }
                }
                return
            }
            if (st.dest == "stylesheet") {
                if (w == "s") { styleCurrent = p ?: -1; st.dest = "stylesheet-entry"; destText.setLength(0) }
                return
            }
            if (st.dest == "stylesheet-entry") return
            if (st.dest != null && st.dest != "footnote" && st.dest != "fldrslt") return
            when (w) {
                "par" -> par()
                "sect" -> par()
                "line" -> out.lineBreak()
                "tab" -> emitText(" ")
                "cell" -> emitText(" | ")
                "row" -> { inTable = false; par() }
                "intbl" -> inTable = true
                "emdash" -> emitText("—")
                "endash" -> emitText("–")
                "lquote" -> emitText("‘")
                "rquote" -> emitText("’")
                "ldblquote" -> emitText("“")
                "rdblquote" -> emitText("”")
                "bullet" -> emitText("•")
                "pard" -> { paraStyle = -1; outline = -1; align = AlignHint.DEFAULT; inTable = false }
                "plain" -> { st.bold = false; st.italic = false; st.underline = false; st.strike = false; st.sup = false; st.sub = false; st.hidden = false }
                "b" -> st.bold = p != 0
                "i" -> st.italic = p != 0
                "ul" -> st.underline = p != 0
                "ulnone" -> st.underline = false
                "strike" -> st.strike = p != 0
                "super" -> { st.sup = true; st.sub = false }
                "sub" -> { st.sub = true; st.sup = false }
                "nosupersub" -> { st.sup = false; st.sub = false }
                "v" -> st.hidden = p != 0
                "f" -> st.font = p ?: -1
                "uc" -> st.uc = p ?: 1
                "u" -> {
                    var code = p ?: 0
                    if (code < 0) code += 65536
                    emitText(code.toChar().toString())
                    ucSkip = st.uc
                }
                "s" -> paraStyle = p ?: -1
                "outlinelevel" -> outline = p ?: -1
                "qc" -> align = AlignHint.CENTER
                "qr" -> align = AlignHint.RIGHT
                "ql", "qj" -> align = AlignHint.DEFAULT
            }
        }

        private fun par() {
            val name = styleNames[paraStyle]
            val level = when {
                name != null && Regex("^heading\\s*(\\d)").find(name) != null -> Regex("^heading\\s*(\\d)").find(name)!!.groupValues[1].toInt()
                name == "title" -> 1
                outline in 0..5 -> outline + 1
                else -> 0
            }
            if (out === notes) {
                // keep footnote paragraphs together
                notes.lineBreak()
                return
            }
            styleSync(emptySet())
            val txt = out.currentText
            if (level > 0 && txt.isNotBlank()) out.setKind(BlockKind.HEADING, level)
            else if (inTable) out.setKind(BlockKind.TABLE_ROW)
            out.setAlign(align)
            out.endBlock()
            out.beginBlock(BlockKind.PARAGRAPH)
        }

        private fun charsetFor(fcharset: Int): Charset? = when (fcharset) {
            0 -> null
            128 -> cs("Shift_JIS"); 129 -> cs("x-windows-949"); 134 -> cs("GBK"); 136 -> cs("Big5")
            161 -> cs("windows-1253"); 162 -> cs("windows-1254"); 163 -> cs("windows-1258"); 177 -> cs("windows-1255")
            178 -> cs("windows-1256"); 186 -> cs("windows-1257"); 204 -> cs("windows-1251"); 222 -> cs("x-windows-874")
            238 -> cs("windows-1250"); 255 -> cs("IBM437")
            else -> null
        }

        private fun cpCharset(cp: Int): Charset? = when (cp) {
            932 -> cs("Shift_JIS"); 936 -> cs("GBK"); 949 -> cs("x-windows-949"); 950 -> cs("Big5"); 65001 -> Charsets.UTF_8
            437 -> cs("IBM437"); 850 -> cs("IBM850"); 866 -> cs("IBM866"); 874 -> cs("x-windows-874")
            in 1250..1258 -> cs("windows-$cp")
            10000 -> cs("x-MacRoman")
            else -> null
        }

        private fun cs(n: String): Charset? = try { Charset.forName(n) } catch (_: Exception) { null }
    }

    @Suppress("unused")
    private fun List<Block>.text() = filterIsInstance<app.quinten.model.TextBlock>().joinToString("\n") { it.text }
}
