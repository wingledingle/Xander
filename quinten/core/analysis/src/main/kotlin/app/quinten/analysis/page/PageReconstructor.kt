package app.quinten.analysis.page

import app.quinten.model.AlignHint
import app.quinten.model.Block
import app.quinten.model.BlockKind
import app.quinten.model.Chapter
import app.quinten.model.CleanOptions
import app.quinten.model.ImageBlock
import app.quinten.model.InlineStyle
import app.quinten.model.LinkSpan
import app.quinten.model.LinkTarget
import app.quinten.model.Removal
import app.quinten.model.RemovedSpan
import app.quinten.model.Span
import app.quinten.model.StyleSpan
import app.quinten.model.TextBlock
import kotlin.math.abs

/**
 * Rebuilds reflowable text from fixed-layout pages: joins lines into paragraphs (also across
 * columns and pages), removes end-of-line hyphenation, detects headings and footnotes, places
 * illustrations, links footnote references, and applies Clean Reading Mode.
 *
 * Units are `page * UNIT_STRIDE + itemIndex`, where items are the page's lines followed by its
 * images, so positions stay stable regardless of display options.
 */
object PageReconstructor {
    const val UNIT_STRIDE = 4096

    fun unitPage(unit: Int) = unit / UNIT_STRIDE

    private enum class Kind { BODY, HEADING, FOOTNOTE, CENTERED }

    private class Item(
        val page: PageText,
        val lineIndex: Int, // -1 for images
        val line: TextLine?,
        val image: PageImage?,
        val unit: Int,
        val column: Int,
        val removal: Removal?,
    )

    private class Stats(val bodySize: Float, val lineGap: Float)

    private fun stats(pages: List<PageText>): Stats {
        val body = PageCleaner.bodyFontSize(pages)
        val gaps = ArrayList<Float>()
        for (p in pages) {
            for (i in 1 until p.lines.size) {
                val a = p.lines[i - 1]; val b = p.lines[i]
                if (p.columnOf.getOrNull(i) != p.columnOf.getOrNull(i - 1)) continue
                if (abs(a.fontSize - body) > body * 0.15f || abs(b.fontSize - body) > body * 0.15f) continue
                val d = b.baseline - a.baseline
                if (d > body * 0.6f && d < body * 3f) gaps += d
            }
        }
        return Stats(body, gaps.median().takeIf { it > 0 } ?: body * 1.25f)
    }

    private class ColGeom(val left: Float, val right: Float)

    private fun columnGeometry(p: PageText, body: Float): Map<Int, ColGeom> {
        val res = HashMap<Int, ColGeom>()
        val byCol = p.lines.indices.groupBy { p.columnOf.getOrElse(it) { 0 } }
        for ((c, idx) in byCol) {
            val ls = idx.map { p.lines[it] }.filter { abs(it.fontSize - body) < body * 0.25f }.ifEmpty { idx.map { p.lines[it] } }
            val x0s = ls.map { it.box.x0 }.sorted()
            val x1s = ls.map { it.box.x1 }.sorted()
            val left = x0s[(x0s.size * 0.2).toInt().coerceAtMost(x0s.size - 1)]
            val right = x1s[(x1s.size * 0.8).toInt().coerceAtMost(x1s.size - 1)]
            res[c] = ColGeom(left, maxOf(right, left + body))
        }
        return res
    }

    /**
     * Builds the chapter covering pages [firstPage]..[lastPage]. [pages] must contain those pages
     * and should include one page of context on each side so paragraphs crossing chapter
     * boundaries are assigned consistently.
     */
    fun reconstruct(
        chapterIndex: Int,
        title: String?,
        pages: List<PageText>,
        firstPage: Int,
        lastPage: Int,
        removals: Map<Pair<Int, Int>, Removal>,
        options: CleanOptions,
        partial: Boolean = false,
    ): Chapter {
        val sorted = pages.sortedBy { it.pageIndex }
        val st = stats(sorted)
        val geoms = sorted.associate { it.pageIndex to columnGeometry(it, st.bodySize) }
        val indentStyle = detectIndentStyle(sorted, geoms, st.bodySize)
        val vocab = Vocabulary.of(sorted)

        // Items in reading order with images interleaved by vertical position.
        val items = ArrayList<Item>()
        for (p in sorted) {
            val lineItems = p.lines.mapIndexed { i, l ->
                Item(p, i, l, null, p.pageIndex * UNIT_STRIDE + i, p.columnOf.getOrElse(i) { 0 }, removals[p.pageIndex to i])
            }.toMutableList()
            for ((k, img) in p.images.withIndex()) {
                val unit = p.pageIndex * UNIT_STRIDE + p.lines.size + k
                // Insert before the first line of the same column that starts below the image top.
                val pos = lineItems.indexOfFirst { it.line != null && it.line.box.y0 >= img.box.y0 && it.line.box.overlapsX(img.box) }
                    .let { if (it < 0) lineItems.indexOfFirst { li -> li.line != null && li.line.box.y0 >= img.box.y1 } else it }
                val item = Item(p, -1, null, img, unit, 0, null)
                if (pos < 0) lineItems += item else lineItems.add(pos, item)
            }
            items += lineItems
        }

        val out = ArrayList<Block>()
        // Review list in reading order: by page, then line (string order put "p:100" before "p:2").
        val allRemovals = removals.values.filter { it.sourcePage in firstPage..lastPage }
            .sortedWith(compareBy({ it.sourcePage }, { it.id.substringAfterLast(':').toIntOrNull() ?: 0 }))
        val para = ParaBuilder(chapterIndex, vocab)
        val pendingNotes = ArrayList<Block>()
        var prev: Item? = null
        var prevKind: Kind? = null

        fun include(b: Block) = unitPage(b.firstUnit) in firstPage..lastPage

        fun flushPara() {
            para.build()?.let { if (include(it)) out += it }
            if (pendingNotes.isNotEmpty()) { pendingNotes.filter(::include).forEach { out += it }; pendingNotes.clear() }
        }

        // Footnote markers present on each page (for linking references).
        val noteMarkers = HashMap<Int, MutableSet<String>>()

        fun classify(it: Item): Kind {
            val l = it.line!!
            val g = geoms[it.page.pageIndex]?.get(it.column)
            val ratio = l.fontSize / st.bodySize
            if (ratio >= 1.25f && l.text.length < 200) return Kind.HEADING
            if (ratio <= 0.88f && l.box.y0 > it.page.height * 0.55f && isFootnoteZone(it, st)) return Kind.FOOTNOTE
            if (g != null) {
                val leftGap = l.box.x0 - g.left
                val rightGap = g.right - l.box.x1
                val width = g.right - g.left
                if (leftGap > width * 0.12f && rightGap > width * 0.12f && abs(leftGap - rightGap) < width * 0.1f && l.text.length < 120) {
                    if (l.isBold && l.text.length < 90) return Kind.HEADING
                    return Kind.CENTERED
                }
            }
            if (l.isBold && l.text.length < 80 && ratio >= 1.05f) return Kind.HEADING
            return Kind.BODY
        }

        val kinds = items.map { if (it.line != null) classify(it) else Kind.BODY }
        for ((i, it) in items.withIndex()) {
            if (it.line != null && kinds[i] == Kind.FOOTNOTE) {
                footnoteMarker(it.line.text)?.let { m -> noteMarkers.getOrPut(it.page.pageIndex) { HashSet() }.add(m) }
            }
        }

        for ((i, it) in items.withIndex()) {
            if (it.image != null) {
                flushPara()
                val img = ImageBlock(it.image.resource, it.image.box.width.toInt(), it.image.box.height.toInt(), sourcePage = it.page.pageIndex, units = intArrayOf(it.unit))
                if (include(img)) out += img
                prev = null; prevKind = null
                continue
            }
            val l = it.line!!
            val r = it.removal
            if (r != null && options.hides(r)) continue
            if (r != null && options.marks(r)) {
                // Preview: removed text shown inline (struck through) without breaking the paragraph.
                if (para.isOpen) para.appendRemoved(l.text, it.unit, r.id)
                else {
                    val b = TextBlock(l.text, BlockKind.PARAGRAPH, sourcePage = it.page.pageIndex, units = intArrayOf(it.unit), removalId = r.id,
                        spans = listOf(RemovedSpan(0, l.text.length, r.id)))
                    if (include(b)) out += b
                }
                continue
            }
            val kind = kinds[i]
            if (kind == Kind.FOOTNOTE) {
                // Footnotes are collected and shown after the paragraph that is being read.
                val marker = footnoteMarker(l.text)
                val prevNote = pendingNotes.lastOrNull() as? TextBlock
                val continues = marker == null && prevNote != null && prevNote.sourcePage == it.page.pageIndex
                if (continues) {
                    pendingNotes[pendingNotes.size - 1] = joinInto(prevNote!!, l, it.unit, vocab)
                } else {
                    val anchors = if (marker != null) listOf(noteAnchor(it.page.pageIndex, marker)) else emptyList()
                    pendingNotes += TextBlock(l.text, BlockKind.FOOTNOTE, anchors = anchors, sourcePage = it.page.pageIndex, units = intArrayOf(it.unit), spans = runSpans(l, 0, null, emptySet()))
                }
                if (!para.isOpen) { pendingNotes.filter(::include).forEach { out += it }; pendingNotes.clear() }
                continue
            }
            val p = prev
            val startNew = when {
                p == null || !para.isOpen -> true
                kind != prevKind -> true
                kind == Kind.HEADING -> abs(p.line!!.fontSize - l.fontSize) > l.fontSize * 0.1f || l.box.y0 - p.line.box.y1 > l.fontSize * 1.2f || p.page !== it.page
                kind == Kind.CENTERED -> true
                else -> isParagraphBreak(p, it, st, geoms, indentStyle)
            }
            if (startNew) {
                flushPara()
                val (bk, level) = when (kind) {
                    Kind.HEADING -> BlockKind.HEADING to headingLevel(l.fontSize / st.bodySize)
                    else -> BlockKind.PARAGRAPH to 0
                }
                para.start(bk, level, if (kind == Kind.CENTERED) AlignHint.CENTER else AlignHint.DEFAULT, it.page.pageIndex)
            }
            para.appendLine(l, it.unit, noteMarkers[it.page.pageIndex] ?: emptySet(), it.page.pageIndex)
            prev = it
            prevKind = kind
        }
        flushPara()
        return Chapter(chapterIndex, title, out, allRemovals, partial)
    }

    private fun isFootnoteZone(it: Item, st: Stats): Boolean {
        // A footnote line is small text below the page's body text.
        val p = it.page
        val idx = it.lineIndex
        val after = p.lines.drop(idx + 1)
        if (after.any { abs(it.fontSize - st.bodySize) < st.bodySize * 0.08f && it.box.y0 > p.lines[idx].box.y0 && it.text.length > 30 }) return false
        // Small type at the bottom is also used for quotations, letters and verse. It is a footnote
        // block only if it opens with a marker the page refers to (superscript number, or *, †...)
        // or is set off by a clear gap and clearly smaller type.
        var s = idx
        while (s > 0 && p.lines[s - 1].fontSize <= st.bodySize * 0.88f && p.lines[s - 1].box.y0 > p.height * 0.5f) s--
        val first = p.lines[s]
        val marker = footnoteMarker(first.text)
        val referenced = marker != null && (marker.none { it.isLetterOrDigit() } || p.lines.any { l ->
            l !== first && l.runs.any { r -> r.superscript && r.end <= l.text.length && l.text.substring(r.start, r.end).trim() == marker }
        })
        val gapAbove = s > 0 && first.baseline - p.lines[s - 1].baseline > st.lineGap * 1.8f
        return referenced || (gapAbove && first.fontSize <= st.bodySize * 0.8f && marker != null)
    }

    private val listStart = Regex("^([•▪●◦‣\\-–—]|\\d{1,3}[.)]|[a-z][.)])\\s")
    private val markerRegex = Regex("^(\\d{1,3}|[*†‡§¶]+|\\[\\d{1,3}])[.)]?\\s")

    private fun footnoteMarker(text: String): String? =
        markerRegex.find(text)?.groupValues?.get(1)?.trim('[', ']')

    private fun noteAnchor(page: Int, marker: String) = "fn${page}_$marker"

    private fun headingLevel(ratio: Float) = when {
        ratio >= 1.8f -> 1
        ratio >= 1.45f -> 2
        else -> 3
    }

    /** True when paragraphs are marked by first-line indentation (most printed books). */
    private fun detectIndentStyle(pages: List<PageText>, geoms: Map<Int, Map<Int, ColGeom>>, body: Float): Boolean {
        var indented = 0; var total = 0
        for (p in pages) for ((i, l) in p.lines.withIndex()) {
            if (abs(l.fontSize - body) > body * 0.15f || l.text.length < 20) continue
            val g = geoms[p.pageIndex]?.get(p.columnOf.getOrElse(i) { 0 }) ?: continue
            total++
            val d = l.box.x0 - g.left
            if (d > 0.8f * body && d < (g.right - g.left) * 0.25f) indented++
        }
        return total > 0 && indented >= maxOf(2, total / 40)
    }

    private fun isParagraphBreak(a: Item, b: Item, st: Stats, geoms: Map<Int, Map<Int, ColGeom>>, indentStyle: Boolean): Boolean {
        val la = a.line!!; val lb = b.line!!
        val ga = geoms[a.page.pageIndex]?.get(a.column)
        val gb = geoms[b.page.pageIndex]?.get(b.column)
        val em = st.bodySize
        val aText = la.text.trimEnd()
        val endsHyphen = aText.endsWith("-") || aText.endsWith("­")
        val aShort = ga != null && la.box.x1 < ga.right - maxOf(2.5f * em, (ga.right - ga.left) * 0.08f)
        val bIndented = gb != null && lb.box.x0 - gb.left > 0.8f * em && lb.box.x0 - gb.left < (gb.right - gb.left) * 0.4f
        val bListStart = listStart.containsMatchIn(lb.text)
        if (bListStart && !endsHyphen) return true
        val samePageColumn = a.page === b.page && a.column == b.column
        if (samePageColumn) {
            val gap = lb.baseline - la.baseline
            if (gap > st.lineGap * 1.45f) return true
            if (gap < 0) return true
            if (bIndented && !endsHyphen) return true
            if (aShort && !endsHyphen) return true
            return false
        }
        // Column or page change: continue unless there is positive evidence of a new paragraph.
        if (endsHyphen) return false
        if (bIndented) return true
        if (aShort) return true
        // Dialogue opening after a finished sentence starts a new paragraph.
        val endsSentence = aText.lastOrNull()?.let { it in ".!?…\"”»’)" } == true
        if (endsSentence && lb.text.firstOrNull()?.let { it in "“\"«„—‘" } == true) return true
        // Otherwise a full last line at the bottom of a column/page means the paragraph continues.
        return false
    }

    private fun runSpans(l: TextLine, offset: Int, links: ((String) -> String?)?, markers: Set<String>): List<Span> {
        val spans = ArrayList<Span>()
        for (r in l.runs) {
            val s = offset + r.start; val e = offset + r.end
            if (r.bold) spans += StyleSpan(s, e, InlineStyle.BOLD)
            if (r.italic) spans += StyleSpan(s, e, InlineStyle.ITALIC)
            if (r.superscript) {
                spans += StyleSpan(s, e, InlineStyle.SUPERSCRIPT)
                val m = l.text.substring(r.start, r.end).trim()
                if (links != null && m in markers) links(m)?.let { spans += LinkSpan(s, e, it, footnote = true) }
            }
        }
        return spans
    }

    private fun joinInto(prev: TextBlock, l: TextLine, unit: Int, vocab: Vocabulary): TextBlock {
        val (text, start) = join(prev.text, l.text, vocab)
        return prev.copy(
            text = text,
            spans = prev.spans + runSpans(l, start, null, emptySet()),
            units = prev.units + unit,
            unitStarts = prev.unitStarts + start,
        )
    }

    /**
     * Word statistics of the surrounding pages, used to decide whether a hyphen at a line end is
     * a printing artefact ("thou-|sand") or part of a compound word ("waistcoat-|pocket").
     */
    class Vocabulary(val words: Set<String>, val hyphenated: Set<String>) {
        companion object {
            val EMPTY = Vocabulary(emptySet(), emptySet())
            private val wordRe = Regex("[\\p{L}][\\p{L}'’-]*[\\p{L}]|[\\p{L}]")
            fun of(pages: List<PageText>): Vocabulary {
                val w = HashSet<String>(); val h = HashSet<String>()
                for (p in pages) for ((li, l) in p.lines.withIndex()) {
                    val t = l.text
                    val prevHyphen = li > 0 && p.lines[li - 1].text.trimEnd().let { it.endsWith("-") || it.endsWith("\u00AD") }
                    val endsHyphen = t.trimEnd().let { it.endsWith("-") || it.endsWith("\u00AD") }
                    val matches = wordRe.findAll(t).toList()
                    for ((mi, m) in matches.withIndex()) {
                        // Words touching a line-end hyphen are ambiguous fragments: skip them.
                        if (endsHyphen && mi == matches.size - 1) continue
                        if (prevHyphen && mi == 0) continue
                        val word = m.value.lowercase()
                        if (word.contains('-')) { h += word; word.split('-').forEach { if (it.isNotEmpty()) w += it } } else w += word
                    }
                }
                return Vocabulary(w, h)
            }
        }
    }

    /** Joins [b] to [a] with dehyphenation; returns the text and the start offset of [b]. */
    fun join(a: String, b: String, vocab: Vocabulary = Vocabulary.EMPTY): Pair<String, Int> {
        val at = a.trimEnd()
        val bt = b.trimStart()
        if (at.isEmpty()) return bt to 0
        val last = at.last()
        val prevChar = at.getOrNull(at.length - 2)
        val first = bt.firstOrNull()
        if (last == '\u00AD') {
            val base = at.dropLast(1)
            return (base + bt) to base.length
        }
        if ((last == '-' || last == '‐') && prevChar?.isLetter() == true && first?.isLowerCase() == true) {
            val head = at.dropLast(1).takeLastWhile { it.isLetter() || it == '-' || it == '\'' || it == '’' }
            val tail = bt.takeWhile { it.isLetter() }
            val keep = when {
                head.contains('-') -> true // compound in progress: out-of-the-|way
                (head + "-" + tail).lowercase() in vocab.hyphenated -> true
                (head + tail).lowercase() in vocab.words -> false
                head.length >= 3 && tail.length >= 3 && head.lowercase() in vocab.words && tail.lowercase() in vocab.words &&
                    vocab.words.size > 200 -> true
                else -> false
            }
            if (!keep) {
                val base = at.dropLast(1)
                return (base + bt) to base.length
            }
            return (at + bt) to at.length
        }
        val cjk = isCjk(last) || (first != null && isCjk(first))
        val noSpace = cjk || last == '-' || last == '—' || last == '/' ||
            (first == '—' && last.isLetterOrDigit()) || (first != null && first in ",.;:!?”’)»" && first != '(')
        val sep = if (noSpace) "" else " "
        return (at + sep + bt) to (at.length + sep.length)
    }

    private fun isCjk(c: Char) = Character.UnicodeScript.of(c.code) in setOf(
        Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA, Character.UnicodeScript.HANGUL,
    ) && Character.UnicodeScript.of(c.code) != Character.UnicodeScript.HANGUL

    private class ParaBuilder(val chapter: Int, val vocab: Vocabulary) {
        private val sb = StringBuilder()
        private val spans = ArrayList<Span>()
        private val units = ArrayList<Int>()
        private val starts = ArrayList<Int>()
        private var kind = BlockKind.PARAGRAPH
        private var level = 0
        private var align = AlignHint.DEFAULT
        private var page = -1
        var isOpen = false
            private set

        fun start(kind: BlockKind, level: Int, align: AlignHint, page: Int) {
            this.kind = kind; this.level = level; this.align = align; this.page = page
            isOpen = true
        }

        fun appendLine(l: TextLine, unit: Int, markers: Set<String>, page: Int) {
            val (text, s) = join(sb.toString(), l.text, vocab)
            sb.setLength(0); sb.append(text)
            units += unit; starts += s
            spans += runSpans(l, s, { m -> LinkTarget(chapter, noteAnchor(page, m)).encode() }, markers)
        }

        fun appendRemoved(text: String, unit: Int, id: String) {
            val s = if (sb.isEmpty()) 0 else sb.length + 1
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(text.trim())
            units += unit; starts += s
            spans += RemovedSpan(s, sb.length, id)
        }

        fun build(): TextBlock? {
            if (!isOpen) return null
            isOpen = false
            if (sb.isBlank()) { reset(); return null }
            val text = sb.toString()
            // Merge adjacent spans of the same style.
            val merged = ArrayList<Span>()
            for (sp in spans.sortedBy { it.start }) {
                val prevIdx = merged.indexOfLast { it is StyleSpan && sp is StyleSpan && it.style == sp.style && it.end >= sp.start - 1 }
                if (sp is StyleSpan && prevIdx >= 0) {
                    val p = merged[prevIdx] as StyleSpan
                    merged[prevIdx] = p.copy(end = maxOf(p.end, sp.end))
                } else merged += sp
            }
            val b = TextBlock(text, kind, level, merged.map { clip(it, text.length) }, align, emptyList(), page, units.toIntArray(), starts.toIntArray())
            reset()
            return b
        }

        private fun clip(s: Span, len: Int): Span = when (s) {
            is StyleSpan -> s.copy(start = s.start.coerceIn(0, len), end = s.end.coerceIn(0, len))
            is LinkSpan -> s.copy(start = s.start.coerceIn(0, len), end = s.end.coerceIn(0, len))
            is RemovedSpan -> s.copy(start = s.start.coerceIn(0, len), end = s.end.coerceIn(0, len))
        }

        private fun reset() {
            sb.setLength(0); spans.clear(); units.clear(); starts.clear()
        }
    }
}
