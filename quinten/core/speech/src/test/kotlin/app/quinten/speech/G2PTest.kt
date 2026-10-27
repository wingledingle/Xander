package app.quinten.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class G2PTest {
    private val g = G2P()

    @Test fun dictionaryWordsAndContext() {
        assertEquals("həlˈO wˈɜɹld!", g.phonemize("Hello world!"))
        // "the" before a vowel, "to" before a consonant, "a" as an article.
        assertEquals("ði ˈæpᵊl", g.phonemize("the apple"))
        assertEquals("ðə kˈæt", g.phonemize("the cat"))
        assertTrue(g.phonemize("I want to go").contains("tə ɡ"))
        assertTrue(g.phonemize("a cat").startsWith("ɐ "))
        // Endings of known words: plural, past tense, -ing.
        assertTrue(g.phonemize("cats").endsWith("ts"))
        assertTrue(g.phonemize("walked").endsWith("kt"))
        // Mr. is a word, not the end of a sentence.
        assertEquals(false, g.phonemize("Mr. Holmes").contains('.'))
    }

    @Test fun numbersMoneyAndOrdinals() {
        assertEquals("eighteen ninety-five", Numbers.words("1895"))
        assertEquals("twenty-first", Numbers.words("21st"))
        assertEquals("three point one four", Numbers.words("3.14"))
        assertEquals("two pounds and fifty pence", Numbers.words("2.50", '£'))
        assertEquals("one dollar", Numbers.words("1", '$'))
        assertEquals("one thousand two hundred and thirty-four", Numbers.words("1,234"))
        assertEquals("nineteen twenties", Numbers.words("1920s"))
        assertEquals("two thousand and five", Numbers.words("2005"))
        // A number before a full stop is read, and the stop kept.
        val p = g.phonemize("in 1895.")
        assertTrue(p, p.endsWith("fˈIv.") && p.length > 15)
        assertTrue(g.phonemize("wait…").endsWith("…"))
    }

    @Test fun unknownWordsAreStillSpoken() {
        // Names no dictionary has are read by spelling rules, never dropped.
        for (w in listOf("Lestrade", "Zorblax", "Windibank", "Quinten")) {
            val p = g.phonemize(w)
            assertTrue("$w -> $p", p.length >= 4 && p.any { it in Lexicon.VOWELS })
        }
    }

    @Test fun tokensStayInKokorosVocabularyAndLongTextIsSplit() {
        val ph = g.phonemize("“Well,” said he—quite calmly; “it’s 5 o’clock… isn’t it?”")
        val ids = Tokenizer.encode(ph)
        assertEquals(ph.codePoints().count().toInt(), ids.size)
        val long = IntArray(1300) { if (it % 40 == 39) Tokenizer.pauses.first() else 50 }
        val parts = Kokoro.split(long)
        assertTrue(parts.all { it.size <= Kokoro.MAX_TOKENS })
        assertEquals(long.size, parts.sumOf { it.size })
    }
}
