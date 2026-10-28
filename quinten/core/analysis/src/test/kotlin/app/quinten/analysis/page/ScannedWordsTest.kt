package app.quinten.analysis.page

import org.junit.Assert.assertEquals
import org.junit.Test

/** A scan's OCR layer (Library of Congress, page 14 of "An Emigrant Boy's Story"): words sized and placed one by one. */
class ScannedWordsTest {
    private fun w(t: String, x0: Int, y0: Int, x1: Int, y1: Int, fs: Int) = Glyph(t, Box(x0.toFloat(), y0.toFloat(), x1.toFloat(), y1.toFloat()), y1.toFloat(), fs.toFloat())

    @Test fun wordsWithUnevenSizesAndGapsFormLines() {
        val words = listOf(
            w("I", 246, 256, 252, 268, 21), w("came", 269, 260, 304, 268, 14), w("out", 345, 256, 373, 268, 20), w("to", 403, 256, 420, 268, 19),
            w("a", 444, 259, 451, 267, 13), w("new", 473, 259, 497, 267, 13), w("settlement", 540, 254, 641, 266, 21), w("on", 692, 258, 708, 266, 14),
            w("the", 739, 253, 769, 265, 21), w("western", 790, 253, 859, 265, 19), w("bor-", 903, 251, 941, 264, 21),
            w("ders", 244, 293, 287, 306, 21), w("of", 309, 293, 326, 306, 21), w("Minnesota,", 349, 295, 482, 311, 27), w("where", 505, 292, 563, 305, 21),
            w("the", 599, 292, 629, 304, 21), w("white", 651, 291, 700, 303, 21), w("men", 735, 295, 762, 303, 13), w("were", 800, 294, 831, 302, 14), w("begin-", 875, 292, 954, 309, 28),
        ).shuffled(java.util.Random(1))
        val t = PageLayout.fromWords(14, 1184f, 1600f, words)
        assertEquals(listOf("I came out to a new settlement on the western bor-", "ders of Minnesota, where the white men were begin-"), t.lines.map { it.text })
    }

    /** A scan tilted by 2 degrees, in two columns: each line drifts by more than a third of a line over its width. */
    @Test fun tiltedTwoColumnScanKeepsLinesAndColumns() {
        val tilt = Math.tan(Math.toRadians(2.0)).toFloat()
        val words = ArrayList<Glyph>()
        val expected = ArrayList<String>()
        for (col in 0..1) for (row in 0 until 30) {
            val line = (0 until 5).map { "c${col}r${row}w$it" }
            expected += line.joinToString(" ")
            for ((i, t) in line.withIndex()) {
                val x = 60f + col * 270f + i * 48f
                val y = 100f + row * 14f - (x - 60f) * tilt
                words += w(t, x.toInt(), (y - 9).toInt(), (x + 40).toInt(), y.toInt(), 11)
            }
        }
        val t = PageLayout.fromWords(0, 612f, 792f, words.shuffled(java.util.Random(2)))
        assertEquals(expected, t.lines.map { it.text })
    }
}
