package app.quinten.analysis

import java.text.BreakIterator
import java.util.Locale

/** Sentence segmentation for text-to-speech and "read from here". */
object Sentences {
    /** Splits [text] into sentence ranges; very long sentences are cut at commas/spaces under [maxLen]. */
    fun split(text: String, locale: Locale = Locale.getDefault(), maxLen: Int = 1500): List<IntRange> {
        if (text.isBlank()) return emptyList()
        val it = BreakIterator.getSentenceInstance(locale)
        it.setText(text)
        val out = ArrayList<IntRange>()
        var start = it.first()
        var end = it.next()
        while (end != BreakIterator.DONE) {
            addTrimmed(text, start, end, maxLen, out)
            start = end
            end = it.next()
        }
        return out
    }

    private fun addTrimmed(text: String, s0: Int, e0: Int, maxLen: Int, out: MutableList<IntRange>) {
        var s = s0; var e = e0
        while (s < e && text[s].isWhitespace()) s++
        while (e > s && text[e - 1].isWhitespace()) e--
        if (e <= s) return
        while (e - s > maxLen) {
            var cut = s + maxLen
            val window = text.substring(s, cut)
            val comma = maxOf(window.lastIndexOf(", "), window.lastIndexOf("; "))
            cut = if (comma > maxLen / 3) s + comma + 1 else s + window.lastIndexOf(' ').let { if (it > 0) it else maxLen }
            out += s until cut
            s = cut
            while (s < e && text[s].isWhitespace()) s++
        }
        if (e > s) out += s until e
    }
}
