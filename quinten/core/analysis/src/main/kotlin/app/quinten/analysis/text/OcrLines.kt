package app.quinten.analysis.text

import app.quinten.analysis.page.Box
import app.quinten.analysis.page.StyleRun
import app.quinten.analysis.page.TextLine

/**
 * Builds text lines from recognised words, dropping the specks a scan adds: lone symbols read
 * from dirt ("+", "~", ">"), tall low-confidence scraps at a line's edge ("HOLMES Ne") and stray
 * marks glued to words ("<A", "“¢And", "leaves*and", "stil]"). The line's type size comes from
 * the typical word height, so one tall scrap doesn't make a running header look like a heading.
 */
object OcrLines {
    /** One recognised word; box in page units, [conf] 0..100. */
    class Word(val text: String, val conf: Float, val box: Box)

    fun line(words: List<Word>): TextLine? {
        val ws = words.filter { it.text.isNotBlank() }
        if (ws.isEmpty()) return null
        val heights = ws.map { it.box.height }.sorted()
        val typical = heights[heights.size / 2]
        val kept = ws.filterIndexed { i, w ->
            val hasAlnum = w.text.any { it.isLetterOrDigit() }
            val edge = i == 0 || i == ws.lastIndex
            when {
                !hasAlnum && w.conf < 60f && w.text.none { it in "—–-“”\"‘’'…" } -> false
                ws.size >= 3 && edge && w.conf < 50f && w.text.length <= 2 && w.box.height > typical * 1.5f -> false
                else -> true
            }
        }
        if (kept.isEmpty()) return null
        val text = kept.joinToString(" ") { clean(it) }.replace(spaces, " ").trim()
        if (text.isEmpty()) return null
        val conf = kept.map { it.conf }.average().toFloat()
        val letters = text.count { it.isLetterOrDigit() }
        // Garbage from pictures: low confidence and few letters.
        if (conf < 40f && letters < text.length * 0.6) return null
        if (conf < 20f) return null
        val box = Box(kept.minOf { it.box.x0 }, kept.minOf { it.box.y0 }, kept.maxOf { it.box.x1 }, kept.maxOf { it.box.y1 })
        val kh = kept.map { it.box.height }.sorted()
        val size = (kh[kh.size / 2] * 0.72f).coerceAtLeast(1f)
        return TextLine(text, box, box.y1 - box.height * 0.2f, size, listOf(StyleRun(0, text.length, size, false, false)), conf)
    }

    private fun clean(w: Word): String {
        var t = w.text
        if (w.conf >= 90f) return t
        // A speck before the first letter: "<A", "“¢And", "~well".
        t = t.replace(leadSpeck, "$1")
        // Specks inside a word: "on~-far", "leaves*and" (two words run together).
        t = t.replace(dashSpeck, " ").replace(innerSpeck, "$1 $2")
        // "stil]", "wel}": a closing bracket read for a final l.
        val m = bracketL.matchEntire(t)
        if (m != null) {
            val fixed = m.groupValues[1] + "l"
            if (EnglishWords.freq(fixed.lowercase()) > 0) t = fixed + m.groupValues[2]
        }
        if (t == "1}" || t == "l}" || t == "I}") t = "I"
        return t
    }

    private val spaces = Regex("\\s+")
    private val leadSpeck = Regex("^([“\"‘(]?)[<>¢~+*»«}{\\]\\[]+(?=\\p{L})")
    private val dashSpeck = Regex("~+-|-~+")
    private val innerSpeck = Regex("(\\p{Ll}{2,})[*»~](\\p{Ll}{2,})")
    private val bracketL = Regex("(\\p{L}*\\p{Ll})[\\]}]([.,;:!?”’\"]*)")
}
