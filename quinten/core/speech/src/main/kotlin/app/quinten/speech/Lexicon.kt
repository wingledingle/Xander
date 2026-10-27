package app.quinten.speech

import java.util.zip.GZIPInputStream

/**
 * Pronunciation dictionary for Kokoro: a Kotlin port of the lexicon part of misaki
 * (https://github.com/hexgrad/misaki, Apache-2.0), with its US gold and silver word lists, so
 * words are spelled in exactly the phonemes Kokoro was trained on. Parts of speech come from a
 * tagger in misaki; here a few cheap context rules pick between the readings ("to read" vs "he read").
 */
class Lexicon private constructor(private val words: Map<String, Any>, val british: Boolean) {
    /** What follows the word being read (misaki's TokenContext): next sound vowel/consonant/pause. */
    data class Context(val futureVowel: Boolean? = null, val futureTo: Boolean = false)

    fun isKnown(word: String): Boolean {
        if (word in words || word in SYMBOLS) return true
        if (!word.all { it.code in LEXICON_ORDS }) return false
        if (word.length == 1) return true
        if (word == word.uppercase() && word.lowercase() in words) return true
        return word.substring(1) == word.substring(1).uppercase()
    }

    private fun entry(word: String): Any? = words[word]
        ?: if (word.length >= 2 && word == word.lowercase()) words[word.replaceFirstChar { it.uppercaseChar() }]
        else if (word.length >= 2 && word == word.lowercase().replaceFirstChar { it.uppercaseChar() }) words[word.lowercase()] else null

    /** Spells an abbreviation or initials letter by letter ("BBC", "U.S."). */
    fun spell(word: String): String? {
        val ps = word.filter { it.isLetter() }.map { (words[it.uppercaseChar().toString()] as? String) ?: return null }
        if (ps.isEmpty()) return null
        val joined = applyStress(ps.joinToString(""), 0.0)
        val k = joined.lastIndexOf(SECONDARY)
        return if (k < 0) joined else joined.substring(0, k) + PRIMARY + joined.substring(k + 1)
    }

    fun lookup(word0: String, tag: String?, stress: Double?, ctx: Context?): String? {
        var word = word0
        if (word == word.uppercase() && word !in words) word = word.lowercase()
        var ps = entry(word)
        if (ps is Map<*, *>) {
            @Suppress("UNCHECKED_CAST") val m = ps as Map<String, String>
            val t = when {
                ctx != null && ctx.futureVowel == null && "None" in m -> "None"
                tag != null && tag in m -> tag
                else -> parentTag(tag)
            }
            ps = m[t] ?: m["DEFAULT"]
        }
        val s = ps as? String ?: return spell(word).takeIf { word0 == word0.uppercase() && isInitials(word0) }
        return applyStress(s, stress)
    }

    fun specialCase(word: String, tag: String?, ctx: Context): String? = when {
        word in SYMBOLS -> lookup(SYMBOLS.getValue(word), null, null, ctx)
        '.' in word.trim('.') && word.replace(".", "").all { it.isLetter() } && word.split('.').maxOf { it.length } < 3 -> spell(word)
        word == "a" || word == "A" -> if (tag == "DT") "ɐ" else "ˈA"
        word == "am" || word == "Am" || word == "AM" -> if (ctx.futureVowel == null || word != "am") words["am"] as String else "ɐm"
        word == "an" || word == "An" -> "ɐn"
        word == "I" && tag == "PRP" -> "${SECONDARY}I"
        word == "to" || word == "To" -> when (ctx.futureVowel) { null -> words["to"] as String; false -> "tə"; true -> "tʊ" }
        word == "in" || word == "In" -> (if (ctx.futureVowel == null || tag != "IN") PRIMARY.toString() else "") + "ɪn"
        word == "the" || word == "The" -> if (ctx.futureVowel == true) "ði" else "ðə"
        word == "used" || word == "Used" -> {
            @Suppress("UNCHECKED_CAST") val m = words["used"] as Map<String, String>
            if (ctx.futureTo) m["VBD"] else m["DEFAULT"]
        }
        else -> null
    }

    // ---- -s, -ed, -ing endings of known stems (misaki's stem_s / stem_ed / stem_ing) ----
    private fun plural(stem: String?): String? = when {
        stem.isNullOrEmpty() -> null
        stem.last() in "ptkfθ" -> stem + "s"
        stem.last() in "szʃʒʧʤ" -> stem + (if (british) "ɪz" else "ᵻz")
        else -> stem + "z"
    }

    private fun past(stem: String?): String? = when {
        stem.isNullOrEmpty() -> null
        stem.last() in "pkfθʃsʧ" -> stem + "t"
        stem.last() == 'd' -> stem + (if (british) "ɪd" else "ᵻd")
        stem.last() != 't' -> stem + "d"
        british || stem.length < 2 -> stem + "ɪd"
        stem[stem.length - 2] in US_TAUS -> stem.dropLast(1) + "ɾᵻd"
        else -> stem + "ᵻd"
    }

    private fun gerund(stem: String?): String? = when {
        stem.isNullOrEmpty() -> null
        british -> if (stem.last() in "əː") null else stem + "ɪŋ"
        stem.length > 1 && stem.last() == 't' && stem[stem.length - 2] in US_TAUS -> stem.dropLast(1) + "ɾɪŋ"
        else -> stem + "ɪŋ"
    }

    fun stemS(word: String, tag: String?, stress: Double?, ctx: Context?): String? {
        if (word.length < 3 || !word.endsWith("s")) return null
        val stem = when {
            !word.endsWith("ss") && isKnown(word.dropLast(1)) -> word.dropLast(1)
            (word.endsWith("'s") || (word.length > 4 && word.endsWith("es") && !word.endsWith("ies"))) && isKnown(word.dropLast(2)) -> word.dropLast(2)
            word.length > 4 && word.endsWith("ies") && isKnown(word.dropLast(3) + "y") -> word.dropLast(3) + "y"
            else -> return null
        }
        return plural(lookup(stem, tag, stress, ctx))
    }

    fun stemEd(word: String, tag: String?, stress: Double?, ctx: Context?): String? {
        if (word.length < 4 || !word.endsWith("d")) return null
        val stem = when {
            !word.endsWith("dd") && isKnown(word.dropLast(1)) -> word.dropLast(1)
            word.length > 4 && word.endsWith("ed") && !word.endsWith("eed") && isKnown(word.dropLast(2)) -> word.dropLast(2)
            else -> return null
        }
        return past(lookup(stem, tag, stress, ctx))
    }

    fun stemIng(word: String, tag: String?, stress: Double?, ctx: Context?): String? {
        if (word.length < 5 || !word.endsWith("ing")) return null
        val stem = when {
            word.length > 5 && isKnown(word.dropLast(3)) -> word.dropLast(3)
            isKnown(word.dropLast(3) + "e") -> word.dropLast(3) + "e"
            word.length > 5 && doubled.containsMatchIn(word) && isKnown(word.dropLast(4)) -> word.dropLast(4)
            else -> return null
        }
        return gerund(lookup(stem, tag, stress, ctx))
    }

    /** Phonemes for one word, or null when it is not in the dictionaries (misaki's get_word). */
    fun word(word0: String, tag: String?, stress: Double?, ctx: Context): String? {
        specialCase(word0, tag, ctx)?.let { return it }
        var word = word0
        val wl = word.lowercase()
        if (word.length > 1 && word.replace("'", "").all { it.isLetter() } && word != wl && word !in words &&
            (word == word.uppercase() || word.substring(1) == word.substring(1).lowercase()) &&
            (wl in words || stemS(wl, tag, stress, ctx) != null || stemEd(wl, tag, stress, ctx) != null || stemIng(wl, tag, stress, ctx) != null)
        ) word = wl
        if (isKnown(word)) return lookup(word, tag, stress, ctx)
        if (word.endsWith("s'") && isKnown(word.dropLast(2) + "'s")) return lookup(word.dropLast(2) + "'s", tag, stress, ctx)
        if (word.endsWith("'") && isKnown(word.dropLast(1))) return lookup(word.dropLast(1), tag, stress, ctx)
        return stemS(word, tag, stress, ctx) ?: stemEd(word, tag, stress, ctx) ?: stemIng(word, tag, stress ?: 0.5, ctx)
    }

    companion object {
        const val PRIMARY = 'ˈ'
        const val SECONDARY = 'ˌ'
        val VOWELS = "AIOQWYaiuæɑɒɔəɛɜɪʊʌᵻ".toSet()
        val CONSONANTS = "bdfhjklmnpstvwzðŋɡɹɾʃʒʤʧθ".toSet()
        private val US_TAUS = "AIOWYiuæɑəɛɪɹʊʌ".toSet()
        private val LEXICON_ORDS = (listOf(39, 45) + (65..90) + (97..122)).toSet()
        val SYMBOLS = mapOf("%" to "percent", "&" to "and", "+" to "plus", "@" to "at")
        private val doubled = Regex("([bcdgklmnprstvxz])\\1ing$|cking$")

        /** Capitals read letter by letter: short ("BBC", "USA") or without vowels ("NBC", "TV"); "JABEZ", "NASA" are words. */
        fun isInitials(w: String) = w.length <= 3 || w.none { it in "AEIOUY" }

        fun parentTag(tag: String?): String? = when {
            tag == null -> null
            tag.startsWith("VB") -> "VERB"
            tag.startsWith("NN") -> "NOUN"
            tag.startsWith("ADV") || tag.startsWith("RB") -> "ADV"
            tag.startsWith("ADJ") || tag.startsWith("JJ") -> "ADJ"
            else -> tag
        }

        /** misaki's apply_stress: lowers, removes or adds stress marks. */
        fun applyStress(ps: String, stress: Double?): String {
            fun restress(p: String): String {
                // Move each stress mark to just before the vowel it belongs to.
                val chars = p.toCharArray().toMutableList()
                val keyed = chars.mapIndexed { i, c -> i.toDouble() to c }.toMutableList()
                for ((i, c) in chars.withIndex()) if (c == PRIMARY || c == SECONDARY) {
                    val j = (i until chars.size).firstOrNull { chars[it] in VOWELS } ?: continue
                    keyed[i] = (j - 0.5) to c
                }
                return keyed.sortedBy { it.first }.map { it.second }.joinToString("")
            }
            val hasStress = PRIMARY in ps || SECONDARY in ps
            return when {
                stress == null -> ps
                stress < -1 -> ps.replace(PRIMARY.toString(), "").replace(SECONDARY.toString(), "")
                stress == -1.0 || ((stress == 0.0 || stress == -0.5) && PRIMARY in ps) ->
                    ps.replace(SECONDARY.toString(), "").replace(PRIMARY, SECONDARY)
                (stress == 0.0 || stress == 0.5 || stress == 1.0) && !hasStress ->
                    if (ps.none { it in VOWELS }) ps else restress(SECONDARY + ps)
                stress >= 1 && PRIMARY !in ps && SECONDARY in ps -> ps.replace(SECONDARY, PRIMARY)
                stress > 1 && !hasStress -> if (ps.none { it in VOWELS }) ps else restress(PRIMARY + ps)
                else -> ps
            }
        }

        @Volatile private var us: Lexicon? = null
        @Volatile private var gb: Lexicon? = null

        /** The US English dictionary (≈180k words), loaded once. */
        fun us(): Lexicon = us ?: synchronized(this) {
            us ?: load("/app/quinten/speech/us_lexicon.tsv.gz", false).also { us = it }
        }

        /** British English, for the British voices (bf_*, bm_*). */
        fun gb(): Lexicon = gb ?: synchronized(this) {
            gb ?: load("/app/quinten/speech/gb_lexicon.tsv.gz", true).also { gb = it }
        }

        private fun load(resource: String, british: Boolean): Lexicon {
            val m = HashMap<String, Any>(200_000)
            val stream = Lexicon::class.java.getResourceAsStream(resource) ?: error("missing $resource")
            GZIPInputStream(stream).bufferedReader().useLines { lines ->
                for (l in lines) {
                    val tab = l.indexOf('\t')
                    if (tab <= 0) continue
                    val k = l.substring(0, tab); val v = l.substring(tab + 1)
                    m[k] = if ('=' in v) v.split(';').associate { it.substringBefore('=') to it.substringAfter('=') } else v
                }
            }
            return Lexicon(m, british)
        }
    }
}
