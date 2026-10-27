package app.quinten.formats.txt

import app.quinten.formats.InMemoryDocument
import app.quinten.formats.RawChapter
import app.quinten.formats.util.FileTitle
import app.quinten.formats.util.TextEncoding
import app.quinten.model.BlockKind
import app.quinten.model.BookFormat
import app.quinten.model.BookMetadata
import app.quinten.model.ContentBuilder
import app.quinten.model.TocEntry
import java.io.File
import java.nio.charset.Charset

/**
 * Plain text: encoding detection, hard-wrap repair, paragraph and chapter detection.
 *
 * Memory: the file bytes are only alive while decoding; the text is then scanned in place (line
 * offsets only, no list of line strings) and paragraphs stream straight into the chapter
 * builders, so the peak is about the decoded text plus the built blocks.
 */
object TxtParser {
    private const val MAX_CHAPTER_CHARS = 120_000

    private val chapterRegex = Regex(
        "^\\s{0,8}((chapter|part|book|volume|section|prologue|epilogue|introduction|preface|foreword|afterword|appendix|" +
            "глава|часть|книга|пролог|эпилог|предисловие|послесловие|kapitel|teil|chapitre|partie|capítulo|capitulo|parte|libro|rozdział|hoofdstuk)" +
            "(\\s+[\\p{L}\\d.:IVXLCDM-]{1,20})?(\\s*[.:—–-]\\s*.{0,80})?)\\s*$",
        RegexOption.IGNORE_CASE,
    )
    private val romanOrNumber = Regex("^\\s{0,40}([IVXLCDM]{1,8}|\\d{1,3})\\.?\\s*$")

    fun open(file: File, charsetOverride: Charset? = null): InMemoryDocument =
        parseText(readText(file, charsetOverride), FileTitle.of(file))

    /** Reads and decodes [file]; the raw bytes become garbage as soon as this returns. */
    private fun readText(file: File, charsetOverride: Charset?): String = decode(file.readBytes(), charsetOverride)

    fun decode(bytes: ByteArray, override: Charset? = null): String = TextEncoding.decode(bytes, override)

    fun guessCharset(bytes: ByteArray): Charset = TextEncoding.guess(bytes)

    fun isHeading(line: String): Boolean {
        val t = line.trim()
        if (t.isEmpty() || t.length > 90) return false
        if (chapterRegex.matches(t)) return true
        if (romanOrNumber.matches(line) && t.trimEnd('.').let { it.length <= 4 }) return true
        return false
    }

    fun parseText(text: String, fallbackTitle: String? = null): InMemoryDocument {
        val chapters = ArrayList<RawChapter>()
        val toc = ArrayList<TocEntry>()
        var builder = ContentBuilder()
        var title: String? = null
        var size = 0
        fun flush() {
            if (!builder.isEmpty) chapters += RawChapter(title, builder.build())
            builder = ContentBuilder(); size = 0; title = null
        }
        forEachParagraph(text) { p ->
            if (p.heading) {
                flush()
                title = p.text.trim()
                toc += TocEntry(title!!, 0, chapters.size)
                builder.beginBlock(BlockKind.HEADING, 2)
                builder.text(p.text)
                builder.endBlock()
                return@forEachParagraph
            }
            if (size > MAX_CHAPTER_CHARS) {
                val t = title
                flush()
                title = t // continuation keeps the chapter title for display
            }
            builder.beginBlock(if (p.preformatted) BlockKind.POEM else BlockKind.PARAGRAPH)
            if (p.preformatted) builder.preformatted = true
            builder.text(p.text)
            builder.preformatted = false
            builder.endBlock()
            size += p.text.length
        }
        flush()
        if (chapters.isEmpty()) chapters += RawChapter(null, emptyList())
        return InMemoryDocument(BookFormat.TXT, BookMetadata(title = fallbackTitle), chapters, toc)
    }

    class Para(val text: String, val heading: Boolean = false, val preformatted: Boolean = false)

    /** Line offsets into the text: [start, end) with trailing whitespace removed; [len] counts a tab as 4. */
    private class Lines(val text: String) {
        var count = 0
        var start = IntArray(1024); var end = IntArray(1024); var len = IntArray(1024)

        init {
            var p = 0
            val n = text.length
            while (p <= n) {
                var e = p
                var tabs = 0
                while (e < n && text[e] != '\n' && text[e] != '\r') { if (text[e] == '\t') tabs++; e++ }
                val next = if (e < n && text[e] == '\r' && e + 1 < n && text[e + 1] == '\n') e + 2 else e + 1
                var te = e
                while (te > p && text[te - 1].isWhitespace()) { if (text[te - 1] == '\t') tabs--; te-- }
                add(p, te, te - p + 3 * tabs)
                if (e >= n) break
                p = next
            }
        }

        private fun add(s: Int, e: Int, l: Int) {
            if (count == start.size) { start = start.copyOf(count * 2); end = end.copyOf(count * 2); len = len.copyOf(count * 2) }
            start[count] = s; end[count] = e; len[count] = l; count++
        }

        fun blank(i: Int) = len[i] == 0
        fun firstNonWs(i: Int): Int { var s = start[i]; while (s < end[i] && text[s].isWhitespace()) s++; return s }
        fun trimmedLength(i: Int) = end[i] - firstNonWs(i)
        fun raw(i: Int): String = text.substring(start[i], end[i]).let { if (it.indexOf('\t') >= 0) it.replace("\t", "    ") else it }
        fun trimmed(i: Int): String = text.substring(firstNonWs(i), end[i]).let { if (it.indexOf('\t') >= 0) it.replace("\t", "    ") else it }
        fun indented(i: Int): Boolean {
            val s = start[i]
            if (s >= end[i]) return false
            val c0 = text[s]
            if (c0 == '\t' || c0 == '　') return true
            return c0 == ' ' && s + 1 < end[i] && (text[s + 1] == ' ' || text[s + 1] == '\t')
        }
        fun lastChar(i: Int): Char? = if (end[i] > start[i]) text[end[i] - 1] else null
    }

    /** Repairs hard-wrapped text and splits it into paragraphs (convenience for tests). */
    fun toParagraphs(lines: List<String>): List<Para> {
        val out = ArrayList<Para>()
        forEachParagraph(lines.joinToString("\n")) { out += it }
        return out
    }

    /** Repairs hard-wrapped text and streams its paragraphs to [emit]. */
    fun forEachParagraph(text: String, emit: (Para) -> Unit) {
        val lines = Lines(text)
        val n = lines.count
        var nonEmpty = 0
        for (i in 0 until n) if (!lines.blank(i)) nonEmpty++
        if (nonEmpty == 0) return
        val blankRatio = (n - nonEmpty).toDouble() / n
        val lengths = IntArray(nonEmpty).also { a -> var k = 0; for (i in 0 until n) if (!lines.blank(i)) a[k++] = lines.len[i] }
        lengths.sort()
        val p90 = lengths[(lengths.size * 0.9).toInt().coerceAtMost(lengths.size - 1)]
        val longLines = lengths.count { it >= p90 * 0.75 }
        // Hard-wrapped: most lines fill a fixed width that is far narrower than a paragraph.
        val hardWrapped = p90 in 40..110 && longLines > nonEmpty * 0.45
        val cur = StringBuilder()
        var verse = false
        fun flush() {
            if (cur.isNotBlank()) {
                val t = cur.toString().trim()
                // Lines joined with '\n' form verse; mark them so they keep line breaks.
                emit(Para(t, preformatted = verse && t.contains('\n')))
            }
            cur.setLength(0); verse = false
        }
        fun append(i: Int) {
            val t = lines.trimmed(i)
            if (cur.isEmpty()) { cur.append(t); return }
            val last = cur.last()
            if (last == '-' && cur.length >= 2 && cur[cur.length - 2].isLetter() && t.firstOrNull()?.isLowerCase() == true) {
                cur.setLength(cur.length - 1); cur.append(t)
            } else {
                cur.append(' ').append(t)
            }
        }
        val separatedByBlank = blankRatio > 0.15
        for (i in 0 until n) {
            if (lines.blank(i)) { flush(); continue }
            if (lines.trimmedLength(i) <= 90 && (i == 0 || lines.blank(i - 1) || (i + 1 < n && lines.blank(i + 1))) && isHeading(lines.raw(i))) {
                flush(); emit(Para(lines.trimmed(i), heading = true)); continue
            }
            val len = lines.len[i]
            when {
                separatedByBlank && !hardWrapped -> {
                    // Blank lines separate paragraphs; single newlines are soft unless lines are short (verse).
                    if (cur.isNotEmpty() && len < 45 && (i > 0 && lines.len[i - 1] < 45)) {
                        cur.append('\n').append(lines.trimmed(i)); verse = true
                    } else append(i)
                }
                separatedByBlank || hardWrapped -> {
                    val prevLen = if (i > 0) lines.len[i - 1] else 0
                    val prevShort = prevLen > 0 && prevLen < p90 * 0.7
                    val prevEnds = (if (i > 0) lines.lastChar(i - 1) else null)?.let { it in ".!?…:\"”»)" } == true
                    if (cur.isNotEmpty() && (lines.indented(i) || (prevShort && prevEnds) || (prevShort && prevLen < p90 * 0.4))) flush()
                    append(i)
                }
                else -> { flush(); append(i); flush() }
            }
        }
        flush()
    }
}
