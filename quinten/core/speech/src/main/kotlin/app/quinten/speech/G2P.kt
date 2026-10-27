package app.quinten.speech

import java.text.Normalizer

/**
 * English text to Kokoro phonemes, following misaki's G2P: dictionary words in the phonemes Kokoro
 * was trained with, context for "the"/"to"/"a", numbers and money as words, initials spelled out,
 * and [LetterToSound] for words no dictionary has. Punctuation that Kokoro uses for pauses and
 * intonation is kept.
 */
class G2P(private val lexicon: Lexicon = Lexicon.us()) {
    private class Tok(val text: String, val space: Boolean, val kind: Kind, var tag: String? = null, var currency: Char? = null) {
        var phonemes: String? = null
    }
    private enum class Kind { WORD, NUMBER, PUNCT, SYMBOL }

    fun phonemize(text0: String): String {
        val text = normalize(text0)
        val toks = tokenize(text)
        tagWords(toks)
        var ctx = Lexicon.Context()
        for (t in toks.asReversed()) {
            t.phonemes = when (t.kind) {
                Kind.PUNCT -> t.text
                Kind.SYMBOL -> Lexicon.SYMBOLS[t.text]?.let { lexicon.lookup(it, null, null, ctx) } ?: ""
                Kind.NUMBER -> Numbers.words(t.text, t.currency)?.let { words -> phrase(words) } ?: ""
                Kind.WORD -> word(t.text, t.tag, ctx)
            }
            ctx = context(ctx, t.phonemes!!, t.text)
        }
        val sb = StringBuilder()
        for ((i, t) in toks.withIndex()) {
            val p = t.phonemes ?: continue
            if (p.isEmpty()) continue
            if (i > 0 && t.space && sb.isNotEmpty() && sb.last() != ' ') sb.append(' ')
            sb.append(p)
        }
        // Kokoro v1.0 was trained with flaps and glottal stops written as T and t.
        return sb.toString().replace('ɾ', 'T').replace('ʔ', 't').trim()
    }

    private fun phrase(words: String): String {
        val parts = words.split(Regex("[^a-z]+")).filter { it.isNotEmpty() }
        return parts.joinToString(" ") { lexicon.lookup(it, null, if (it == "point") -2.0 else null, null) ?: LetterToSound.rules(it) }
    }

    private fun word(text: String, tag: String?, ctx: Lexicon.Context): String {
        val w = text.replace('’', '\'').replace('‘', '\'')
        val stress = if (w == w.lowercase()) null else if (w == w.uppercase() && w.length > 1) 2.0 else 0.5
        lexicon.word(w, tag, stress, ctx)?.let { return it }
        // Hyphenated and joined words: each part on its own ("well-known", "Mrs.Hudson").
        if ('-' in w || '.' in w.trim('.')) {
            val parts = w.split('-', '.').filter { it.isNotBlank() }
            if (parts.size > 1) return parts.joinToString(" ") { word(it, null, Lexicon.Context()) }
        }
        // All capitals: initials ("BBC"), unless it reads as a word.
        if (w.length >= 2 && w == w.uppercase() && w.all { it.isLetter() } && Lexicon.isInitials(w)) lexicon.spell(w)?.let { return it }
        return LetterToSound.phonemes(w, lexicon) ?: ""
    }

    private fun context(ctx: Lexicon.Context, ps: String, text: String): Lexicon.Context {
        var vowel = ctx.futureVowel
        if (ps.isNotEmpty()) {
            for (c in ps) {
                if (c in NON_QUOTE_PUNCTS) { vowel = null; break }
                if (c in Lexicon.VOWELS) { vowel = true; break }
                if (c in Lexicon.CONSONANTS) { vowel = false; break }
            }
        }
        return Lexicon.Context(futureVowel = vowel, futureTo = text == "to" || text == "To")
    }

    // ---- text handling ----

    private fun normalize(t: String): String {
        var s = Normalizer.normalize(t, Normalizer.Form.NFKC)
        // NFKC turns "…" into "...": Kokoro has its own symbol for it.
        s = s.replace("...", "\u2026").replace('\u00A0', ' ').replace("\u00AD", "")
        // Common abbreviations books use; the dictionary has Mr./Mrs./Dr. already.
        for ((k, v) in abbreviations) s = s.replace(k, v)
        return s
    }

    private fun tokenize(text: String): List<Tok> {
        val out = ArrayList<Tok>()
        var currency: Char? = null
        for (m in tokenRe.findAll(text)) {
            val v = m.value
            val space = m.range.first > 0 && text[m.range.first - 1].isWhitespace()
            when {
                v.isBlank() -> {}
                v[0] in "$£€" && v.length == 1 -> currency = v[0]
                v[0].isDigit() || (v[0] == '.' && v.length > 1 && v[1].isDigit()) -> { out += Tok(v, space, Kind.NUMBER, currency = currency); currency = null }
                v[0].isLetter() || v[0] == '\'' || v[0] == '’' -> { out += Tok(v.trimEnd('\'', '’').ifEmpty { v }, space, Kind.WORD); currency = null }
                v in Lexicon.SYMBOLS -> out += Tok(v, space, Kind.SYMBOL)
                else -> {
                    val p = v.map { c -> when (c) { '-', '–' -> '—'; '[' -> '('; ']' -> ')'; '‘', '’' -> '"'; else -> c } }
                        .filter { it in PUNCTS }.joinToString("")
                    if (p.isNotEmpty()) out += Tok(p, space, Kind.PUNCT)
                }
            }
        }
        return out
    }

    /**
     * misaki tags words with spaCy. The tags it needs are few: a determiner "a", the pronoun "I",
     * "in" as a preposition, and verb or noun readings ("to read", "the wind", "he read").
     */
    private fun tagWords(toks: List<Tok>) {
        for ((i, t) in toks.withIndex()) {
            if (t.kind != Kind.WORD) continue
            val prev = toks.getOrNull(i - 1)?.takeIf { it.kind == Kind.WORD }?.text?.lowercase()
            val next = toks.getOrNull(i + 1)
            t.tag = when {
                t.text == "a" || t.text == "A" -> if (next != null && next.kind == Kind.WORD) "DT" else "NN"
                t.text == "I" -> "PRP"
                t.text.lowercase() == "in" -> if (next != null && next.kind == Kind.WORD) "IN" else "RB"
                prev in verbBefore -> "VERB"
                prev in pastBefore -> "VBD"
                prev in nounBefore -> "NOUN"
                else -> null
            }
        }
    }

    companion object {
        private val PUNCTS = ";:,.!?—…\"“”()".toSet()
        private val NON_QUOTE_PUNCTS = ";:,.!?—…".toSet()
        private val tokenRe = Regex("[\\p{L}'’][\\p{L}\\p{M}'’.\\-]*\\p{L}|[\\p{L}'’]|\\.?[0-9]+(?:[,.][0-9]+)*(?:st|nd|rd|th|s|'s|’s)?|\\s+|.")
        private val verbBefore = setOf("to", "will", "would", "can", "could", "shall", "should", "may", "might", "must", "do", "does", "did", "don't", "didn't", "let", "please")
        private val pastBefore = setOf("he", "she", "it", "they", "we", "you", "had", "have", "has", "was", "were", "been")
        private val nounBefore = setOf("the", "a", "an", "his", "her", "my", "your", "our", "their", "its", "this", "that")
        private val abbreviations = listOf(
            // A full stop here is not the end of a sentence (Kokoro would pause).
            Regex("\\bMr\\.") to "Mister", Regex("\\bMrs\\.") to "Missus", Regex("\\bDr\\.(?= [A-Z])") to "Doctor", Regex("\\bMs\\.") to "Miz",
            Regex("\\bSt\\.(?= [A-Z])") to "Saint", Regex("\\bMt\\.(?= [A-Z])") to "Mount", Regex("\\bCapt\\.") to "Captain",
            Regex("\\bCol\\.") to "Colonel", Regex("\\bGen\\.(?= [A-Z])") to "General", Regex("\\bProf\\.") to "Professor",
            Regex("\\bRev\\.") to "Reverend", Regex("\\bSgt\\.") to "Sergeant", Regex("\\bLt\\.") to "Lieutenant",
            Regex("\\bvs\\.") to "versus", Regex("\\betc\\.") to "et cetera", Regex("\\be\\.g\\.") to "for example",
            Regex("\\bi\\.e\\.") to "that is", Regex("\\bNo\\.(?= ?[0-9])") to "number",
        )
    }
}
