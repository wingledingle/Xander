package app.quinten.speech

/**
 * Spelling-to-sound rules for words in no dictionary (character names, rare and made-up words).
 * misaki uses espeak (GPL) or a neural model here; these are ordinary English spelling rules,
 * written for this app, giving Kokoro's phoneme symbols. First the word is tried as a compound of
 * dictionary words ("Ravenhill" = raven + hill); otherwise it is read by the rules, stressed on the
 * first syllable as most English names are.
 */
object LetterToSound {
    fun phonemes(word: String, lexicon: Lexicon): String? {
        val w = word.lowercase().filter { it in 'a'..'z' || it == '\'' }
        if (w.isEmpty()) return null
        compound(w, lexicon)?.let { return it }
        return Lexicon.applyStress(rules(w.replace("'", "")), 2.0)
    }

    /** Two dictionary words joined: both parts at least 3 letters. */
    private fun compound(w: String, lexicon: Lexicon): String? {
        if (w.length < 7) return null
        val ctx = Lexicon.Context()
        for (k in (3..w.length - 3).sortedBy { kotlin.math.abs(it - w.length / 2) }) {
            val a = w.substring(0, k); val b = w.substring(k)
            if (!lexicon.isKnown(a) || !lexicon.isKnown(b)) continue
            val pa = lexicon.lookup(a, null, null, ctx) ?: continue
            val pb = lexicon.lookup(b, null, -1.0, ctx) ?: continue
            return pa + pb
        }
        return null
    }

    private val vowelsLetters = "aeiouy".toSet()
    private fun isV(c: Char?) = c != null && c in vowelsLetters

    // Longest spellings first. Each maps to Kokoro / misaki US phonemes.
    private val chunks: List<Pair<String, String>> = listOf(
        "tion" to "ʃən", "sion" to "ʒən", "ture" to "ʧəɹ", "ough" to "O", "augh" to "ɔ", "eigh" to "A",
        "tch" to "ʧ", "sch" to "sk", "igh" to "I", "dge" to "ʤ", "que" to "k", "cqu" to "kw",
        "ch" to "ʧ", "sh" to "ʃ", "th" to "θ", "ph" to "f", "wh" to "w", "ck" to "k", "ng" to "ŋ", "qu" to "kw",
        "gh" to "", "kn" to "n", "wr" to "ɹ", "gn" to "n", "mb" to "m", "dg" to "ʤ",
        "ee" to "i", "ea" to "i", "oo" to "u", "ou" to "W", "ow" to "O", "oi" to "Y", "oy" to "Y", "ai" to "A", "ay" to "A",
        "au" to "ɔ", "aw" to "ɔ", "ie" to "i", "ei" to "A", "ey" to "i", "oa" to "O", "ue" to "u", "ew" to "u", "ui" to "u",
        "ar" to "ɑɹ", "er" to "əɹ", "ir" to "ɜɹ", "ur" to "ɜɹ", "or" to "ɔɹ",
    )

    fun rules(w: String): String {
        val sb = StringBuilder()
        var i = 0
        val n = w.length
        // Silent final e ("Lestrade"), and the vowel before it is long ("a_e" -> A).
        val silentE = n > 2 && w.last() == 'e' && !isV(w[n - 2]) && w.substring(0, n - 1).any { isV(it) }
        val longAt = if (silentE && isV(w[n - 3]) && !isV(w.getOrNull(n - 4))) n - 3 else null
        while (i < n) {
            if (silentE && i == n - 1) break
            val c = w[i]
            // Initial "kn", "wr", "gn" are in the table; elsewhere "gn" etc. fall through letter by letter.
            val chunk = chunks.firstOrNull { (g, _) -> w.startsWith(g, i) && (g !in setOf("kn", "wr", "gn") || i == 0) && !(g == "gh" && i == 0) }
            if (chunk != null && !(chunk.first.length == 2 && chunk.first[0] in "aeiou" && chunk.first[1] in "r" && isV(w.getOrNull(i + 2)))) {
                sb.append(chunk.second); i += chunk.first.length; continue
            }
            val next = w.getOrNull(i + 1)
            val ph = when (c) {
                'a' -> if (i == longAt) "A" else "æ"
                'e' -> if (i == longAt) "i" else if (i == n - 1) "i" else "ɛ"
                'i' -> if (i == longAt) "I" else "ɪ"
                'o' -> if (i == longAt) "O" else "ɑ"
                'u' -> if (i == longAt) "u" else "ʌ"
                'y' -> if (i == 0 && isV(next)) "j" else if (i == n - 1) "i" else "ɪ"
                'c' -> if (next != null && next in "eiy") "s" else "k"
                'g' -> if (next != null && next in "eiy" && i > 0) "ʤ" else "ɡ"
                'j' -> "ʤ"
                'x' -> if (i == 0) "z" else "ks"
                'r' -> "ɹ"
                's' -> if (i > 0 && isV(w[i - 1]) && isV(next)) "z" else "s"
                'q' -> "k"
                'n' -> if (next == 'k') "ŋ" else "n"
                'b', 'd', 'f', 'h', 'k', 'l', 'm', 'p', 't', 'v', 'w', 'z' -> c.toString()
                else -> ""
            }
            // Double consonants are said once ("Russell").
            if (ph.length == 1 && sb.isNotEmpty() && sb.last().toString() == ph && ph[0] !in Lexicon.VOWELS) { i++; continue }
            sb.append(ph)
            i++
        }
        // Final "-le" after a consonant: syllabic l ("Kimble").
        return sb.toString().replace(Regex("ɛl$|lɛ$"), "ᵊl")
    }
}
