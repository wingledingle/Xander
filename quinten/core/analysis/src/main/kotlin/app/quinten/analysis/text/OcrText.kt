package app.quinten.analysis.text

import app.quinten.analysis.page.PageText
import app.quinten.analysis.page.TextLine
import java.util.zip.GZIPInputStream

/** English word frequencies (counted from public-domain Project Gutenberg books, see scripts/build_wordlist.py). */
object EnglishWords {
    private val counts: Map<String, Int> by lazy {
        val m = HashMap<String, Int>(40_000)
        val stream = EnglishWords::class.java.getResourceAsStream("/app/quinten/analysis/text/en_words.txt.gz") ?: return@lazy m
        GZIPInputStream(stream).bufferedReader().useLines { lines ->
            for (l in lines) {
                val tab = l.indexOf('\t')
                if (tab > 0) m[l.substring(0, tab)] = l.substring(tab + 1).toIntOrNull() ?: 1
            }
        }
        m
    }

    /** Occurrences of the lower-case word in the reference corpus (0 = unknown). */
    fun freq(word: String): Int = counts[word] ?: 0

    // Large dictionary (SCOWL size 70, ~160k words, see scripts/build_lexicon.sh): tells rare real
    // words ("format", "nearby", "weevil") from words a scan ran together ("didnot", "wellto").
    private val lexicon: Set<String> by lazy {
        val set = HashSet<String>(200_000)
        val stream = EnglishWords::class.java.getResourceAsStream("/app/quinten/analysis/text/en_lexicon.txt.gz") ?: return@lazy set
        GZIPInputStream(stream).bufferedReader().useLines { lines -> lines.forEach { set += it } }
        set
    }

    /** True for a dictionary word (lower case) or a word of the frequency list. */
    fun isWord(word: String): Boolean = word in counts || word in lexicon || word.replace('’', '\'') in lexicon
}

/**
 * Repairs the typical OCR confusions of embedded and on-device text recognition: digits inside
 * words ("1s", "th1s", "a1so", "f0r"), "l"/"I" mix-ups ("ls", "Iike"), "Tt" for "It", and a lone
 * "1"/"l" used as the pronoun "I". A word is only changed into a known word, and known words are
 * never touched, so names and numbers survive. Replacements keep the text length unchanged.
 *
 * [bookWords] adds words already seen in the same book (character names and the like).
 */
class OcrCorrector(private val bookWords: (String) -> Int = { 0 }) {
    private fun known(w: String): Int {
        val k = w.lowercase().replace('’', '\'')
        return EnglishWords.freq(k) + bookWords(k) * 50
    }

    fun correct(page: PageText): PageText {
        var changed = false
        var sentenceStart = true
        var prevHyphen = false
        val lines = page.lines.map { l ->
            val fixed = correctLine(l.text, sentenceStart, prevHyphen)
            prevHyphen = l.text.trimEnd().lastOrNull()?.let { it == '-' || it == '\u00AD' } == true
            sentenceStart = l.text.trimEnd().lastOrNull()?.let { it in ".!?”\":" } ?: sentenceStart
            val (split, inserted) = splitJoined(fixed)
            if (split != l.text) {
                changed = true
                // Style runs move with the spaces put between run-together words.
                fun shift(i: Int) = i + inserted.count { it < i }
                val runs = if (inserted.isEmpty()) l.runs else l.runs.map { it.copy(start = shift(it.start), end = shift(it.end)) }
                TextLine(split, l.box, l.baseline, l.fontSize, runs, l.confidence)
            } else l
        }
        return if (!changed) page else PageText(page.pageIndex, page.width, page.height, lines, page.images, page.columnOf, page.columns, page.ocr, page.scanned)
    }

    /**
     * Words a scan ran together ("didnot", "wellto-do", "ina") are split when the word is unknown
     * and both halves are common words. Returns the text and the offsets where spaces went in.
     */
    fun splitJoined(text: String): Pair<String, List<Int>> {
        val inserted = ArrayList<Int>()
        val out = StringBuilder()
        var last = 0
        for (m in lettersRe.findAll(text)) {
            val tok = m.value
            val at = splitPoint(tok, m.range.first, text) ?: continue
            out.append(text, last, m.range.first + at).append(' ')
            inserted += m.range.first + at
            last = m.range.first + at
        }
        if (inserted.isEmpty()) return text to emptyList()
        out.append(text, last, text.length)
        return out.toString() to inserted
    }

    private fun splitPoint(tok: String, start: Int, text: String): Int? {
        if (tok.length < 3 || tok.length > 20) return null
        // Part of a longer token ("can't", "x1", "e-mail"): leave it.
        val before = text.getOrNull(start - 1); val after = text.getOrNull(start + tok.length)
        // Underscores mark italics in plain-text books: foreign words ("_missa_") are left alone.
        if (before != null && (before.isLetterOrDigit() || before in "'’-_")) return null
        if (after != null && (after.isLetterOrDigit() || after in "'’_")) return null
        if (after == '-' && text.getOrNull(start + tok.length + 1) == null) return null // a line-end hyphen half
        val lower = tok.lowercase()
        // Names and capitalised words ("Lestrade", "McCarthy") are not split; nor all-caps.
        if (tok.drop(1).any { it.isUpperCase() }) return null
        if (tok[0].isUpperCase() && start > 0 && text.substring(0, start).trimEnd().lastOrNull()?.let { it !in ".!?“\"‘(—" } != false) return null
        if (EnglishWords.isWord(lower) || bookWords(lower) >= 3) return null
        var best = -1; var bestScore = 0
        for (k in 1 until lower.length) {
            val a = lower.substring(0, k); val b = lower.substring(k)
            if ((a.length == 1 && a != "a" && a != "i") || (b.length == 1 && b != "a")) continue
            val fa = EnglishWords.freq(a); val fb = EnglishWords.freq(b)
            // Both halves common: "did"+"not", "well"+"to", "in"+"a".
            val need = { w: String, f: Int -> f >= (if (w.length <= 2) 2_000 else 150) }
            if (!need(a, fa) || !need(b, fb)) continue
            // Scans run words into the little words around them; two content words joined are
            // usually a real compound the word list lacks ("teacups", "uptown").
            if (a !in glue && b !in glue) continue
            // Trailing "a" only after a little word ("ina", "ofa"), never after a content word
            // ("arma", "humana"); "a" before an -ing word is dialect ("agoing", "a-coming").
            if (b == "a" && a !in glue) continue
            if (a == "a" && b.endsWith("ing")) continue
            val score = minOf(fa, fb)
            if (score > bestScore) { bestScore = score; best = k }
        }
        return best.takeIf { it > 0 }
    }

    fun correctLine(text: String, startsSentence: Boolean = true, continuesWord: Boolean = false): String {
        val toks = tokenRe.findAll(text).toList()
        if (toks.isEmpty()) return text
        val out = StringBuilder(text)
        for ((i, m) in toks.withIndex()) {
            val tok = m.value
            // Halves of a word split across lines ("signifi-" / "cance") are not words: they are
            // judged once the paragraph is joined.
            if (i == 0 && continuesWord) continue
            if (i == toks.lastIndex && text.substring(m.range.last + 1).trim().let { it == "-" || it == "\u00AD" }) continue
            val before = text.substring(0, m.range.first).trimEnd()
            val atSentenceStart = if (before.isEmpty()) startsSentence else before.last() in ".!?“\"(:—"
            val prev = toks.getOrNull(i - 1); val next = toks.getOrNull(i + 1)
            // Only plain spaces to the next word: "u, l, p", "S.l.", "bo-l-e" are letters, not the pronoun.
            val spacedNext = next != null && text.substring(m.range.last + 1, next.range.first).isNotEmpty() &&
                text.substring(m.range.last + 1, next.range.first).isBlank()
            val nearNumber = listOfNotNull(prev?.value, next?.value).any { it.first().isDigit() }
            val fixed = when {
                tok == "1" || tok == "l" || tok == "|" || tok == "7" || tok == "t" ->
                    if (spacedNext && !(tok == "t" && before.lastOrNull()?.let { it == '\'' || it == '’' } == true)) pronoun(tok, prev?.value, next?.value) else null
                tok == "2" || tok == "4" -> if (spacedNext && prev != null && text.substring(prev.range.last + 1, m.range.first).let { it.isBlank() || it.matches(commaSpace) }) article(prev.value, toks.drop(i + 1).take(3).map { it.value }) else null
                nearNumber && tok.any { it.isDigit() } -> null // "1s 4d", "5 0f 10" are amounts and tables
                else -> word(tok, atSentenceStart, prev?.value, before.lastOrNull()?.let { it in "“\"‘" } == true)
            } ?: continue
            if (fixed.length == tok.length) out.replace(m.range.first, m.range.last + 1, fixed)
        }
        return out.toString()
    }

    /** "1 am", "l was" -> "I am", "I was"; but "chapter 1", "page 1 of", "1 2 3" stay. */
    private fun pronoun(tok: String, prev: String?, next: String?): String? {
        if (next == null || next.length < 2 || next.any { it.isDigit() } || !next.first().isLowerCase()) return null
        if (prev != null && (prev.all { it.isDigit() } || prev.lowercase() in numberWords || (prev.length >= 2 && prev.all { it.isUpperCase() }))) return null
        val n = next.lowercase().replace('’', '\'')
        // "7" and "t" are real tokens far more often than "1"/"l": only before a typical verb.
        return if (n in followsI || (tok != "1" && tok != "7" && tok != "t" && known(n) > 0)) "I" else null
    }

    /** "was 4 third point", "a gray colour, 2 coat": a lone 2/4 read for the article "a". */
    private fun article(prev: String?, following: List<String>): String? {
        val next = following.firstOrNull() ?: return null
        if (prev == null || prev.length < 2 || !prev.all { it.isLetter() } || !prev.first().isLowerCase()) return null
        if (prev.lowercase() in notBeforeArticle || prev.lowercase() in numberWords) return null
        if (next.length < 2 || !next.all { it.isLetter() } || !next.first().isLowerCase()) return null
        // Counted things stay numbers: a plural or a unit within the next words ("4 miles",
        // "2 o'clock", "4 drunken English sailors", "2 large dogs").
        for ((k, w) in following.withIndex()) {
            val n = w.lowercase()
            if ((n.endsWith("s") && !n.endsWith("ss") && n.length > 2) || (k == 0 && n in units)) return null
        }
        return if (known(next.lowercase()) >= 40) "a" else null
    }

    private fun word(tok: String, atSentenceStart: Boolean, prev: String? = null, afterQuote: Boolean = false): String? {
        // "$" read for S ("$he", "thi$"); amounts ("$5", "$") are numbers, not words.
        if (tok.startsWith("$") && tok.getOrNull(1)?.isLetter() != true) return null
        val hasDigit = tok.any { it in "105|$" }
        val letters = tok.count { it.isLetter() }
        if (letters == 0) return null
        // Numbers with units or ordinals: "1950s", "21st", "10km", "4th".
        val digits = tok.count { it.isDigit() }
        // "1s" is almost always "is"; other single digits with a unit/ordinal ("1st", "5m") are numbers.
        val oneS = digits == 1 && tok.length == 2 && tok[1].lowercaseChar() == 's'
        if (hasDigit && ((numberLike.matches(tok) && !oneS) || digits > letters)) return null
        // A misread repeated through a scan ("ves", "eves") is in the book's own words but not in
        // English: such v/c words may still be fixed when the fix is far more common.
        val own = known(tok)
        val vcOnly = !hasDigit && tok.any { it == 'v' || it == 'c' || it == 'V' } && EnglishWords.freq(tok.lowercase().replace('’', '\'')) == 0
        if (!hasDigit && own > 0 && !vcOnly) return null
        // Short codes such as "A1", "B12", "R2": capitals with digits.
        if (hasDigit && tok.length <= 3 && tok.filter { it.isLetter() }.all { it.isUpperCase() }) return null
        // Letter confusions only for unknown words; digit confusions always.
        val positions = tok.indices.filter { i ->
            val c = tok[i]
            c in "105|$" || (!hasDigit && c == 'j') || (c == 'I' && (i > 0 || tok.length >= 3)) || (c == 'l' && i == 0) || (c == 'T' && i == 0 && (tok.length == 2 || tok[2] in "'’")) ||
                // Classic scan misreads: y read as v ("ves", "thev"), o read as c ("fcrefinger").
                (!hasDigit && (c == 'v' || c == 'c' || (c == 'V' && i == 0)))
        }
        if (positions.isEmpty() || positions.size > 5) return null
        var best: String? = null
        var bestScore = 0
        fun search(k: Int, cur: CharArray) {
            if (k == positions.size) {
                val cand = String(cur)
                val s = known(cand)
                if (s > bestScore) { bestScore = s; best = cand }
                return
            }
            val p = positions[k]
            for (r in alternatives(tok[p])) { cur[p] = r; search(k + 1, cur) }
            cur[p] = tok[p]
        }
        search(0, tok.toCharArray())
        val b = best ?: return null
        if (b == tok) return null
        // Letter-only swaps (I/l/T) must land on a common word, so names like "Ian" are left alone.
        if (!hasDigit && bestScore < 40) return null
        // v->y and c->o only for really common results ("ves"->"yes", "thev"->"they"), never for
        // abbreviations ("Sc.") or names ("Eves"); rare real words ("laving", "vet") are kept.
        val swapped = tok.indices.filter { tok[it] != b[it] }.map { tok[it].lowercaseChar() }
        val capitalised = tok.first().isUpperCase() && tok.drop(1).all { !it.isUpperCase() }
        if (own > 0 && bestScore < own * 20) return null
        // The result must be an English word: words seen in a scanned book include its own misreads.
        val english = if ('v' in swapped || 'c' in swapped) EnglishWords.freq(b.lowercase().replace('’', '\'')) else 0
        if ('v' in swapped && (tok.length < 3 || english < (if (capitalised && !afterQuote) 10_000 else 1_500) || (prev?.lowercase() in determiners && b.lowercase() in notAfterDeterminer))) return null
        if ('c' in swapped && (tok.length < 4 || capitalised || english < 40)) return null
        return applyCase(tok, b, positions, atSentenceStart)
    }

    private fun alternatives(c: Char): String = when (c) {
        '1' -> "il"
        '0' -> "o"
        '5' -> "s"
        '|' -> "li"
        'I' -> "Il"
        'l' -> "lI"
        'T' -> "TI"
        '$' -> "Ss"
        'j' -> "ji"
        'v' -> "vy"
        'V' -> "VY"
        'c' -> "co"
        else -> c.toString()
    }

    /** Case of replaced characters follows the rest of the word ("TH1S" -> "THIS", "1t" at a sentence start -> "It"). */
    private fun applyCase(orig: String, cand: String, positions: List<Int>, atSentenceStart: Boolean): String {
        val others = orig.filterIndexed { i, ch -> i !in positions && ch.isLetter() }
        val allUpper = others.isNotEmpty() && others.all { it.isUpperCase() }
        val sb = StringBuilder(cand)
        for (p in positions) {
            val ch = cand[p]
            sb.setCharAt(p, when {
                allUpper -> ch.uppercaseChar()
                p == 0 && (atSentenceStart || cand.lowercase().let { it == "i" || it.startsWith("i'") }) -> ch.uppercaseChar()
                else -> ch.lowercaseChar()
            })
        }
        // The pronoun inside contractions: "1'm" -> "I'm".
        val lc = sb.toString().lowercase()
        if (lc == "i" || lc.startsWith("i'") || lc.startsWith("i’")) sb.setCharAt(0, 'I')
        return sb.toString()
    }

    companion object {
        private val glue = setOf(
            "a", "i", "an", "the", "to", "of", "in", "and", "not", "is", "it", "he", "she", "was", "at", "on", "for", "be", "as", "by",
            "his", "her", "had", "have", "that", "this", "you", "we", "they", "but", "or", "so", "if", "my", "me", "him", "are", "were",
        )
        private val lettersRe = Regex("\\p{L}+")
        private val tokenRe = Regex("[\\p{L}\\p{N}|'’$]+")
        private val numberLike = Regex("^\\d+(s|st|nd|rd|th|am|pm|km|kg|cm|mm|ml|ft|lb|lbs|x|m|g|h|d)$", RegexOption.IGNORE_CASE)
        private val units = setOf(
            "o'clock", "percent", "per", "cent", "am", "pm", "ft", "in", "inch", "foot", "feet", "mile", "yard", "pound", "lb", "oz", "kg", "km", "cm", "mm",
            "hour", "minute", "second", "day", "night", "week", "month", "year", "times", "and", "or", "to", "of", "out", "by", "x", "more", "less", "dozen", "hundred", "thousand", "million",
        )
        private val notBeforeArticle = setOf(
            "the", "these", "those", "all", "about", "over", "under", "than", "only", "nearly", "almost", "some", "every", "each", "first", "last", "next", "and", "or", "to",
            "aged", "age", "number", "numbers", "plus", "minus", "times", "between", "from", "till", "until", "nos", "pp",
        )
        private val determiners = setOf("the", "a", "an", "his", "her", "my", "your", "our", "their", "its", "this", "that")
        private val commaSpace = Regex(",\\s+")
        private val notAfterDeterminer = setOf("yet", "yes", "you", "they", "very", "your")
        private val numberWords = setOf("chapter", "page", "no", "vol", "volume", "part", "section", "book", "figure", "fig", "table", "step", "number", "verse", "act", "scene", "plate", "item", "art", "article")
        private val followsI = setOf(
            "am", "was", "have", "had", "will", "would", "could", "should", "shall", "think", "thought", "know", "knew", "said",
            "saw", "see", "felt", "feel", "must", "do", "did", "don't", "didn't", "can", "cannot", "can't", "may", "might", "mean",
            "want", "wanted", "wish", "hope", "believe", "suppose", "fear", "told", "went", "found", "came", "took", "got", "heard",
            "never", "also", "just", "really", "only", "still", "love", "like", "need", "remember", "looked", "turned", "asked",
            "replied", "answered", "cried", "began", "tell", "say", "understand", "won't", "wouldn't", "couldn't", "haven't", "hadn't",
            "wasn't", "am,", "should", "dare", "guess", "suspect", "confess", "assure", "beg", "made", "make", "let", "put", "kept",
            "always", "often", "once", "then", "now", "soon", "hardly", "scarcely", "certainly", "quite", "rather", "suddenly",
            "observed", "remarked", "returned", "noticed", "glanced", "walked", "ran", "sat", "stood", "rose", "laughed", "smiled",
            "shook", "nodded", "waited", "watched", "followed", "opened", "closed", "left", "lay", "slept", "awoke", "woke", "drove", "read",
            "wrote", "gave", "sent", "tried", "trust", "fancy", "presume", "imagine", "wonder", "admit", "promise", "swear",
            "shan't", "doubt", "feared", "shall",
        )
    }
}

/** How much of a text consists of real words: used to spot garbled text layers. */
object TextQuality {
    class Score(val knownFraction: Double, val words: Int)

    private val wordRe = Regex("[\\p{L}][\\p{L}'’]*")

    fun score(page: PageText, bookWords: (String) -> Int = { 0 }): Score {
        var words = 0; var known = 0
        for (l in page.lines) for (m in wordRe.findAll(l.text)) {
            val w = m.value
            if (w.length < 2) continue
            words++
            val k = w.lowercase().replace('’', '\'')
            if (EnglishWords.freq(k) > 0 || bookWords(k) >= 2) known++
        }
        return Score(if (words == 0) 1.0 else known.toDouble() / words, words)
    }
}
