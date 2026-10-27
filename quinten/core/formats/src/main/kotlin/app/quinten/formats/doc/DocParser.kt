package app.quinten.formats.doc

import app.quinten.formats.DrmProtectedException
import app.quinten.formats.InMemoryDocument
import app.quinten.formats.UnsupportedBookException
import app.quinten.formats.util.ChapterSplitter
import app.quinten.formats.util.FileTitle
import app.quinten.model.AlignHint
import app.quinten.model.BlockKind
import app.quinten.model.BookFormat
import app.quinten.model.BookMetadata
import app.quinten.model.ContentBuilder
import app.quinten.model.ImageData
import app.quinten.model.InlineStyle
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset

/**
 * Word 97-2003 binary (.doc) reader. Reads the piece table for text, paragraph properties for
 * headings/alignment, character properties for bold/italic, footnotes, and inline pictures.
 * Headers and footers are stored separately in .doc files and are deliberately not shown.
 */
object DocParser {
    private val cp1252: Charset = Charset.forName("windows-1252")

    private class Piece(val cpStart: Int, val cpEnd: Int, val fc: Int, val compressed: Boolean)
    private class Run(val fcStart: Int, val fcEnd: Int, val grpprl: ByteArray, val istd: Int = 0)

    fun open(file: File): InMemoryDocument {
        val cfb = CompoundFile.open(file)
        val word = cfb.stream("WordDocument") ?: throw UnsupportedBookException("Not a Word document")
        val wb = ByteBuffer.wrap(word).order(ByteOrder.LITTLE_ENDIAN)
        if ((wb.getShort(0).toInt() and 0xFFFF) != 0xA5EC) throw UnsupportedBookException("Invalid Word document")
        val flags = wb.getShort(0x0A).toInt()
        if (flags and 0x0100 != 0) throw DrmProtectedException("This document is password protected")
        val table = cfb.stream(if (flags and 0x0200 != 0) "1Table" else "0Table") ?: throw UnsupportedBookException("Missing table stream")
        val tb = ByteBuffer.wrap(table).order(ByteOrder.LITTLE_ENDIAN)
        val dataStream = cfb.stream("Data")

        val csw = wb.getShort(32).toInt() and 0xFFFF
        val lwStart = 34 + csw * 2
        val cslw = wb.getShort(lwStart).toInt() and 0xFFFF
        val rgLw = lwStart + 2
        val ccpText = wb.getInt(rgLw + 3 * 4)
        val ccpFtn = wb.getInt(rgLw + 4 * 4)
        val fcLcbStart = rgLw + cslw * 4 + 2
        fun fc(i: Int) = wb.getInt(fcLcbStart + i * 8)
        fun lcb(i: Int) = wb.getInt(fcLcbStart + i * 8 + 4)

        val pieces = readPieces(tb, fc(33), lcb(33)) ?: listOf(Piece(0, ccpText + ccpFtn, 0x800 * 2, true))
        val styles = readStyles(tb, fc(1), lcb(1))
        val papx = readBte(tb, wb, fc(13), lcb(13), paragraph = true)
        val chpx = readBte(tb, wb, fc(12), lcb(12), paragraph = false)

        // The document text, decoded once piece by piece (index = character position).
        val sorted = pieces.filter { it.cpEnd > it.cpStart && it.cpStart >= 0 }.sortedBy { it.cpStart }
        val textLen = (sorted.maxOfOrNull { it.cpEnd } ?: 0).coerceAtMost(ccpText + ccpFtn + 1).coerceAtLeast(0)
        val text = CharArray(textLen)
        for (p in sorted) {
            val s = p.cpStart.coerceAtMost(textLen); val e = p.cpEnd.coerceAtMost(textLen)
            if (e <= s) continue
            val n = e - s
            val bytes = n.toLong() * (if (p.compressed) 1 else 2)
            if (p.fc < 0 || p.fc + bytes > word.size) continue
            val decoded = if (p.compressed) String(word, p.fc, n, cp1252) else String(word, p.fc, n * 2, Charsets.UTF_16LE)
            decoded.toCharArray(text, s, 0, minOf(n, decoded.length))
        }
        val pieceStarts = IntArray(sorted.size) { sorted[it].cpStart }
        fun fcOf(cp: Int): Int {
            var i = java.util.Arrays.binarySearch(pieceStarts, cp)
            if (i < 0) i = -i - 2
            if (i < 0) return -1
            val p = sorted[i]
            if (cp >= p.cpEnd) return -1
            return p.fc + (cp - p.cpStart) * (if (p.compressed) 1 else 2)
        }
        fun charAt(cp: Int): Char = if (cp in 0 until textLen) text[cp] else '\u0000'
        fun find(runs: List<Run>, f: Int): Run? {
            var lo = 0; var hi = runs.size - 1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                val r = runs[mid]
                when {
                    f < r.fcStart -> hi = mid - 1
                    f >= r.fcEnd -> lo = mid + 1
                    else -> return r
                }
            }
            return null
        }

        val images = HashMap<String, ByteArray>()

        fun emit(from: Int, to: Int, b: ContentBuilder, footnotes: Boolean) {
            var cp = from
            var fieldDepth = 0
            val fieldShowing = ArrayList<Boolean>()
            val fieldInstr = ArrayList<StringBuilder>()
            val fieldLink = ArrayList<Boolean>()
            var curStyles = emptySet<InlineStyle>()
            var noteNo = 0
            var paraStart = true
            // Plain characters are collected and added in runs, not one String per character.
            val pending = StringBuilder()
            fun flush() { if (pending.isNotEmpty()) { b.text(pending.toString()); pending.setLength(0) } }
            // A cell separator is written lazily, so a row doesn't end with " | ".
            var cellSep = false
            fun setStyles(s: Set<InlineStyle>) {
                if (s == curStyles) return
                flush()
                for (x in curStyles) if (x !in s) b.popStyle(x)
                for (x in s) if (x !in curStyles) b.pushStyle(x)
                curStyles = s
            }
            fun startParagraph(atCp: Int) {
                // Paragraph properties live on the paragraph mark (or table cell mark): scan ahead to it.
                var e = atCp
                while (e < to && charAt(e) != '\r' && charAt(e) != '\u0007' && e - atCp < 100_000) e++
                val run = find(papx, fcOf(e.coerceAtMost(to - 1)))
                val sprms = run?.grpprl
                val istd = run?.istd ?: 0
                val outline = sprms?.let { sprmByte(it, 0x2640) } ?: -1
                val sti = styles.getOrNull(istd) ?: -1
                val level = when {
                    sti in 1..9 -> sti
                    sti == 62 -> 1
                    outline in 0..5 -> outline + 1
                    else -> 0
                }
                val jc = sprms?.let { sprmByte(it, 0x2403) ?: sprmByte(it, 0x2461) } ?: 0
                val align = when (jc) { 1 -> AlignHint.CENTER; 2 -> AlignHint.RIGHT; else -> AlignHint.DEFAULT }
                val inTable = sprms?.let { sprmByte(it, 0x2416) } == 1
                val kind = when {
                    footnotes -> BlockKind.FOOTNOTE
                    level > 0 -> BlockKind.HEADING
                    inTable -> BlockKind.TABLE_ROW
                    else -> BlockKind.PARAGRAPH
                }
                setStyles(emptySet())
                b.beginBlock(kind, level, align)
                paraStart = false
            }
            // Character properties of the last run, decoded once per run.
            var styledRun: Run? = null; var styledValid = false
            var runStyles: Set<InlineStyle> = emptySet(); var runHidden = false
            var chpRun: Run? = null
            fun chpAt(cp: Int): Run? {
                val f = fcOf(cp)
                chpRun?.let { if (f >= it.fcStart && f < it.fcEnd) return it }
                return find(chpx, f).also { chpRun = it }
            }
            while (cp < to) {
                if (paraStart) startParagraph(cp)
                val ch = charAt(cp)
                val showing = fieldShowing.isEmpty() || fieldShowing.all { it }
                val plain = ch >= ' ' || ch == '\t'
                if (!plain) flush()
                when (ch) {
                    '\u0013' -> { fieldDepth++; fieldShowing.add(false); fieldInstr.add(StringBuilder()); fieldLink.add(false) }
                    '\u0014' -> if (fieldShowing.isNotEmpty()) {
                        fieldShowing[fieldShowing.size - 1] = true
                        val instr = fieldInstr.last().toString().trim()
                        val m = Regex("^HYPERLINK\\s+(\\\\l\\s+)?\"([^\"]+)\"").find(instr)
                        if (m != null) {
                            val target = m.groupValues[2]
                            val t = if (m.groupValues[1].isNotEmpty() || target.startsWith("#")) ChapterSplitter.pendingLink(target.removePrefix("#")) else target
                            setStyles(emptySet())
                            b.pushLink(t)
                            fieldLink[fieldLink.size - 1] = true
                        }
                    }
                    '\u0015' -> if (fieldShowing.isNotEmpty()) {
                        fieldShowing.removeAt(fieldShowing.size - 1); fieldDepth--
                        fieldInstr.removeAt(fieldInstr.size - 1)
                        if (fieldLink.removeAt(fieldLink.size - 1)) { setStyles(emptySet()); b.popLink() }
                    }
                    '\r' -> { if (footnotes && fieldShowing.isEmpty()) b.lineBreak() else { setStyles(emptySet()); b.endBlock(); paraStart = true; cellSep = false } }
                    '\u0007' -> {
                        // Cell mark, or the row-end mark (a paragraph with sprmPFTtp): one TABLE_ROW block per row.
                        val rowEnd = find(papx, fcOf(cp))?.grpprl?.let { sprmByte(it, 0x2417) == 1 || sprmByte(it, 0x244B) == 1 } == true
                        if (rowEnd) { setStyles(emptySet()); b.endBlock(); paraStart = true; cellSep = false } else cellSep = true
                    }
                    '\u000B' -> if (showing) b.lineBreak()
                    '\u000C' -> { setStyles(emptySet()); b.endBlock(); paraStart = true }
                    '\u001E' -> if (showing) b.text("-")
                    '\u001F', '\u0000' -> {}
                    '\u0002' -> if (showing) {
                        if (footnotes) {
                            noteNo++
                            setStyles(emptySet())
                            b.beginBlock(BlockKind.FOOTNOTE)
                            b.anchor("docnote$noteNo")
                            b.text("$noteNo. ")
                        } else {
                            noteNo++
                            setStyles(emptySet())
                            b.pushLink(ChapterSplitter.pendingLink("docnote$noteNo"), footnote = true)
                            b.pushStyle(InlineStyle.SUPERSCRIPT); b.text("$noteNo"); b.popStyle(InlineStyle.SUPERSCRIPT)
                            b.popLink()
                        }
                    }
                    '\u0001', '\u0008' -> if (showing && ch == '\u0001' && dataStream != null) {
                        val run = find(chpx, fcOf(cp))
                        val loc = run?.grpprl?.let { sprmInt(it, 0x6A03) }
                        if (loc != null) extractPicture(dataStream, loc)?.let { (ext, bytes) ->
                            val id = "docimg${images.size}.$ext"
                            images[id] = bytes
                            setStyles(emptySet())
                            b.image(id)
                        }
                    }
                    else -> if (!showing) {
                        if (fieldShowing.isNotEmpty() && !fieldShowing.last()) fieldInstr.last().append(ch)
                    } else {
                        val run = chpAt(cp)
                        if (run !== styledRun || !styledValid) {
                            val s = HashSet<InlineStyle>(2)
                            run?.grpprl?.let { g ->
                                if (toggle(sprmByte(g, 0x0835))) s += InlineStyle.BOLD
                                if (toggle(sprmByte(g, 0x0836))) s += InlineStyle.ITALIC
                                if (toggle(sprmByte(g, 0x0837))) s += InlineStyle.STRIKE
                                if ((sprmByte(g, 0x2A3E) ?: 0) != 0) s += InlineStyle.UNDERLINE
                                when (sprmByte(g, 0x2A48)) { 1 -> s += InlineStyle.SUPERSCRIPT; 2 -> s += InlineStyle.SUBSCRIPT }
                            }
                            runStyles = s
                            runHidden = run?.grpprl?.let { toggle(sprmByte(it, 0x083C)) } == true
                            styledRun = run; styledValid = true
                        }
                        val s = runStyles
                        if (!runHidden) {
                            if (cellSep) { setStyles(emptySet()); pending.append(" | "); cellSep = false }
                            setStyles(s)
                            pending.append(ch)
                        }
                    }
                }
                cp++
            }
            flush()
            setStyles(emptySet())
            b.endBlock()
        }

        val body = ContentBuilder()
        emit(0, ccpText, body, false)
        val notes = ContentBuilder()
        if (ccpFtn > 0) emit(ccpText, ccpText + ccpFtn, notes, true)
        val bodyBlocks = body.build()
        val noteBlocks = notes.build()
        val split = ChapterSplitter.split(bodyBlocks, if (noteBlocks.isEmpty()) emptyList() else listOf("Notes" to noteBlocks))
        val meta = BookMetadata(title = summaryTitle(cfb) ?: FileTitle.of(file), authors = listOfNotNull(summaryAuthor(cfb)))
        return object : InMemoryDocument(BookFormat.DOC, meta, split.chapters, split.toc) {
            override fun image(resource: String): ImageData? = images[resource]?.let { ImageData.Encoded(it) }
        }
    }

    private fun toggle(v: Int?): Boolean = v == 1 || v == 0x81

    private fun readPieces(tb: ByteBuffer, fcClx: Int, lcbClx: Int): List<Piece>? {
        if (lcbClx <= 0 || fcClx < 0 || fcClx + lcbClx > tb.capacity()) return null
        var p = fcClx
        val end = fcClx + lcbClx
        while (p < end) {
            when (tb.get(p).toInt()) {
                1 -> { val cb = tb.getShort(p + 1).toInt() and 0xFFFF; p += 3 + cb }
                2 -> {
                    val lcb = tb.getInt(p + 1)
                    val start = p + 5
                    val n = (lcb - 4) / 12
                    val out = ArrayList<Piece>(n)
                    for (i in 0 until n) {
                        val cpS = tb.getInt(start + i * 4)
                        val cpE = tb.getInt(start + (i + 1) * 4)
                        val pcd = start + (n + 1) * 4 + i * 8
                        val raw = tb.getInt(pcd + 2)
                        val compressed = raw and 0x40000000 != 0
                        val fc = raw and 0x3FFFFFFF
                        out += Piece(cpS, cpE, if (compressed) fc / 2 else fc, compressed)
                    }
                    return out
                }
                else -> return null
            }
        }
        return null
    }

    /** Returns sti (built-in style identifier) per istd. */
    private fun readStyles(tb: ByteBuffer, fc: Int, lcb: Int): List<Int> {
        if (lcb <= 0 || fc + lcb > tb.capacity()) return emptyList()
        return try {
            val cbStshi = tb.getShort(fc).toInt() and 0xFFFF
            val cstd = tb.getShort(fc + 2).toInt() and 0xFFFF
            var p = fc + 2 + cbStshi
            val out = ArrayList<Int>(cstd)
            for (i in 0 until cstd) {
                val cbStd = tb.getShort(p).toInt() and 0xFFFF
                if (cbStd == 0) out += -1 else out += (tb.getShort(p + 2).toInt() and 0x0FFF)
                p += 2 + cbStd
                if (p > fc + lcb) break
            }
            out
        } catch (_: Exception) { emptyList() }
    }

    private fun readBte(tb: ByteBuffer, wb: ByteBuffer, fc: Int, lcb: Int, paragraph: Boolean): List<Run> {
        if (lcb <= 0 || fc + lcb > tb.capacity()) return emptyList()
        val n = (lcb - 4) / 8
        val runs = ArrayList<Run>()
        for (i in 0 until n) {
            val pn = tb.getInt(fc + (n + 1) * 4 + i * 4) and 0x3FFFFF
            val page = pn * 512
            if (page + 512 > wb.capacity()) continue
            val crun = wb.get(page + 511).toInt() and 0xFF
            for (r in 0 until crun) {
                val fcS = wb.getInt(page + r * 4)
                val fcE = wb.getInt(page + (r + 1) * 4)
                try {
                    if (paragraph) {
                        val bxOff = page + (crun + 1) * 4 + r * 13
                        val off = (wb.get(bxOff).toInt() and 0xFF) * 2
                        if (off == 0) { runs += Run(fcS, fcE, ByteArray(0)); continue }
                        var q = page + off
                        var cb = wb.get(q).toInt() and 0xFF
                        q++
                        val size = if (cb == 0) { cb = wb.get(q).toInt() and 0xFF; q++; cb * 2 } else cb * 2 - 1
                        val istd = wb.getShort(q).toInt() and 0xFFFF
                        val g = ByteArray((size - 2).coerceAtLeast(0))
                        for (k in g.indices) g[k] = wb.get(q + 2 + k)
                        runs += Run(fcS, fcE, g, istd)
                    } else {
                        val off = (wb.get(page + (crun + 1) * 4 + r).toInt() and 0xFF) * 2
                        if (off == 0) { runs += Run(fcS, fcE, ByteArray(0)); continue }
                        val cb = wb.get(page + off).toInt() and 0xFF
                        val g = ByteArray(cb)
                        for (k in 0 until cb) g[k] = wb.get(page + off + 1 + k)
                        runs += Run(fcS, fcE, g)
                    }
                } catch (_: IndexOutOfBoundsException) {}
            }
        }
        runs.sortBy { it.fcStart }
        return runs
    }

    /** Iterates sprms in a grpprl, returning the operand offset of the last occurrence of [sprm]. */
    private fun findSprm(g: ByteArray, sprm: Int): Int {
        var p = 0
        var found = -1
        while (p + 2 <= g.size) {
            val s = (g[p].toInt() and 0xFF) or ((g[p + 1].toInt() and 0xFF) shl 8)
            p += 2
            val size = when (s ushr 13) {
                0, 1 -> 1
                2, 4, 5 -> 2
                3 -> 4
                7 -> 3
                else -> {
                    if (p >= g.size) return found
                    if (s == 0xD608 || s == 0xD606) {
                        if (p + 2 > g.size) return found
                        val l = (g[p].toInt() and 0xFF) or ((g[p + 1].toInt() and 0xFF) shl 8)
                        if (s == sprm) found = p + 2
                        p += 2 + l - 1; continue
                    }
                    val l = g[p].toInt() and 0xFF
                    if (s == sprm) found = p + 1
                    p += 1 + l; continue
                }
            }
            if (s == sprm) found = p
            p += size
        }
        return found
    }

    private fun sprmByte(g: ByteArray, sprm: Int): Int? = findSprm(g, sprm).takeIf { it in g.indices }?.let { g[it].toInt() and 0xFF }

    private fun sprmInt(g: ByteArray, sprm: Int): Int? = findSprm(g, sprm).takeIf { it >= 0 && it + 4 <= g.size }?.let {
        (g[it].toInt() and 0xFF) or ((g[it + 1].toInt() and 0xFF) shl 8) or ((g[it + 2].toInt() and 0xFF) shl 16) or ((g[it + 3].toInt() and 0xFF) shl 24)
    }

    /** Extracts a PNG/JPEG blip from a PICF structure in the Data stream. */
    private fun extractPicture(data: ByteArray, offset: Int): Pair<String, ByteArray>? {
        if (offset < 0 || offset + 8 > data.size) return null
        val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val lcb = bb.getInt(offset)
        val end = (offset + lcb).coerceAtMost(data.size)
        var p = offset + (bb.getShort(offset + 4).toInt() and 0xFFFF)
        // MM_SHAPE / MM_SHAPEFILE pictures carry a picture name before the OfficeArt data.
        val mm = bb.getShort(offset + 6).toInt() and 0xFFFF
        if (mm == 0x66 && p < end) p += 1 + (data[p].toInt() and 0xFF)
        // Walk OfficeArt records looking for a JPEG/PNG blip.
        while (p + 8 <= end) {
            val verInst = bb.getShort(p).toInt() and 0xFFFF
            val type = bb.getShort(p + 2).toInt() and 0xFFFF
            val len = bb.getInt(p + 4)
            val ver = verInst and 0xF
            when (type) {
                0xF01D, 0xF02A, 0xF01E -> {
                    val start = p + 8
                    val stop = (start + len).coerceAtMost(end)
                    // Skip UIDs/tag; find the magic number.
                    for (k in start until minOf(stop, start + 64)) {
                        val isJpeg = data[k] == 0xFF.toByte() && k + 2 < stop && data[k + 1] == 0xD8.toByte() && data[k + 2] == 0xFF.toByte()
                        val isPng = data[k] == 0x89.toByte() && k + 3 < stop && data[k + 1] == 'P'.code.toByte() && data[k + 2] == 'N'.code.toByte()
                        if (isJpeg || isPng) return (if (isPng) "png" else "jpg") to data.copyOfRange(k, stop)
                    }
                    p += 8 + len
                }
                0xF007 -> p += 8 + 36 + (data.getOrNull(p + 8 + 33)?.toInt()?.and(0xFF) ?: 0) // FBSE: embedded blip follows
                else -> p += if (ver == 0xF) 8 else 8 + len // containers: descend
            }
            if (len < 0) return null
        }
        return null
    }

    private fun summaryTitle(cfb: CompoundFile): String? = summaryProp(cfb, 2)
    private fun summaryAuthor(cfb: CompoundFile): String? = summaryProp(cfb, 4)

    /** Reads a string property from the \u0005SummaryInformation stream. */
    private fun summaryProp(cfb: CompoundFile, id: Int): String? {
        val s = cfb.stream("\u0005SummaryInformation") ?: return null
        return try {
            val bb = ByteBuffer.wrap(s).order(ByteOrder.LITTLE_ENDIAN)
            val sectionOff = bb.getInt(28 + 16)
            val count = bb.getInt(sectionOff + 4)
            var codepage = cp1252
            for (i in 0 until count) {
                val pid = bb.getInt(sectionOff + 8 + i * 8)
                val off = bb.getInt(sectionOff + 12 + i * 8)
                if (pid == 1) { val cp = bb.getShort(sectionOff + off + 4).toInt() and 0xFFFF; codepage = if (cp == 65001) Charsets.UTF_8 else runCatching { Charset.forName("windows-$cp") }.getOrDefault(cp1252) }
            }
            for (i in 0 until count) {
                val pid = bb.getInt(sectionOff + 8 + i * 8)
                if (pid != id) continue
                val off = sectionOff + bb.getInt(sectionOff + 12 + i * 8)
                val type = bb.getInt(off)
                if (type != 30) return null
                val len = bb.getInt(off + 4)
                return String(s, off + 8, len, codepage).trimEnd('\u0000').trim().ifEmpty { null }
            }
            null
        } catch (_: Exception) { null }
    }
}
