package app.quinten.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class SentencesTest {
    @Test fun splitsSentences() {
        val t = "Hello there. “Is anyone home?” she asked! Mr. Smith arrived at 3 p.m. and left."
        val parts = Sentences.split(t, Locale.US).map { t.substring(it) }
        assertTrue(parts.size >= 3)
        assertEquals("Hello there.", parts[0])
        assertTrue(parts.joinToString(" ").replace(Regex("\\s+"), " ") == t)
    }

    @Test fun longSentencesAreCut() {
        val t = (1..400).joinToString(", ") { "word$it" } + "."
        val parts = Sentences.split(t, Locale.US, maxLen = 300)
        assertTrue(parts.all { it.last - it.first + 1 <= 300 })
        assertEquals(t.replace(" ", ""), parts.joinToString("") { t.substring(it) }.replace(" ", ""))
    }
}
