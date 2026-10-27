package app.quinten.formats.util

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Character encoding detection for text without a declared encoding (TXT, FB2 without an XML
 * declaration). Byte-order marks first, then UTF-16 without BOM, UTF-8 validity, the East Asian
 * multi-byte codepages (validity plus character-frequency scoring) and finally the single-byte
 * Cyrillic, Central European and Western codepages.
 */
object TextEncoding {
    private const val SAMPLE = 200_000

    /** Charset and BOM length when [bytes] starts with a UTF-8/UTF-16 byte-order mark. */
    fun bom(bytes: ByteArray): Pair<Charset, Int>? = when {
        bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() -> Charsets.UTF_8 to 3
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> Charsets.UTF_16LE to 2
        bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> Charsets.UTF_16BE to 2
        else -> null
    }

    /** Decodes [bytes], honouring a BOM, otherwise guessing the encoding. */
    fun decode(bytes: ByteArray, override: Charset? = null): String {
        if (override != null) return String(bytes, override)
        bom(bytes)?.let { (cs, n) -> return String(bytes, n, bytes.size - n, cs) }
        return String(bytes, guess(bytes))
    }

    private fun charset(name: String): Charset? = try { Charset.forName(name) } catch (_: Exception) { null }

    fun guess(bytes: ByteArray): Charset {
        val sample = if (bytes.size > SAMPLE) bytes.copyOf(SAMPLE) else bytes
        // UTF-16 without BOM: many zero bytes at even/odd positions.
        var zeroEven = 0; var zeroOdd = 0
        for (i in sample.indices) if (sample[i].toInt() == 0) { if (i % 2 == 0) zeroEven++ else zeroOdd++ }
        if (zeroOdd > sample.size / 5) return Charsets.UTF_16LE
        if (zeroEven > sample.size / 5) return Charsets.UTF_16BE
        if (isUtf8(sample)) return Charsets.UTF_8
        var high = 0; var c0df = 0; var e0ff = 0; var a0bf = 0
        for (b in sample) {
            val v = b.toInt() and 0xFF
            if (v >= 0x80) {
                high++
                when (v) { in 0xC0..0xDF -> c0df++; in 0xE0..0xFF -> e0ff++; in 0xA0..0xBF -> a0bf++ }
            }
        }
        if (high == 0) return Charsets.UTF_8
        if (high > sample.size / 20) guessEastAsian(sample)?.let { return it }
        // Cyrillic text is almost all high bytes; Central European text only has some accented letters.
        val cyrillicLike = (c0df + e0ff).toDouble() / high > 0.75 && high > sample.size / 4
        if (cyrillicLike) return if (e0ff >= c0df) Charset.forName("windows-1251") else Charset.forName("KOI8-R")
        if (a0bf + e0ff + c0df < high / 2 && high > sample.size / 20) return Charset.forName("IBM866")
        if (looksCentralEuropean(sample)) charset("windows-1250")?.let { return it }
        return Charset.forName("windows-1252")
    }

    private fun isUtf8(bytes: ByteArray): Boolean {
        val dec = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        // Allow a multi-byte sequence truncated by sampling at the end.
        for (cut in 0..3) {
            if (bytes.size - cut <= 0) break
            try {
                dec.reset(); dec.decode(ByteBuffer.wrap(bytes, 0, bytes.size - cut)); return true
            } catch (_: CharacterCodingException) {}
        }
        return false
    }

    // Most frequent Chinese characters, simplified and traditional forms.
    private val frequentHanzi: Set<Char> = (
        "的一是不了在人有我他这个们中来上大为和国地到以说时要就出会可也你对生能而子那得于着下自之年过发后作里用道行所然家种事成方多经么去法学如都同现当没动面起看定天分还进好小部其些主样理心她本前开但因只从想实" +
            "這個們來為國說時會對於著過發後裏經麼學現當沒動進樣開從實們嗎呢吧啊"
        ).toSet()

    // Most frequent Hangul syllables.
    private val frequentHangul: Set<Char> = "이의다는에하고을가지한기서로리사도아자게니대어일요수시해들만으나그를있것내라우인정주전보면부나무구여년상거같".toSet()

    private class Stats(var errors: Int = 0, var nonAscii: Int = 0, var kana: Int = 0, var hanzi: Int = 0, var freqHanzi: Int = 0, var hangul: Int = 0, var freqHangul: Int = 0)

    private fun stats(sample: ByteArray, cs: Charset): Stats? {
        val dec = cs.newDecoder().onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE).replaceWith("\uFFFD")
        val out: CharBuffer = try { dec.decode(ByteBuffer.wrap(sample)) } catch (_: Exception) { return null }
        val s = Stats()
        for (i in 0 until out.limit()) {
            val c = out[i]
            if (c.code < 0x80) continue
            s.nonAscii++
            when (c.code) {
                0xFFFD -> s.errors++
                in 0x3040..0x30FF -> s.kana++
                in 0x4E00..0x9FFF -> { s.hanzi++; if (c in frequentHanzi) s.freqHanzi++ }
                in 0xAC00..0xD7A3 -> { s.hangul++; if (c in frequentHangul) s.freqHangul++ }
            }
        }
        return s
    }

    /** Chinese (GB18030/GBK, Big5), Japanese (Shift_JIS) or Korean (EUC-KR), or null. */
    private fun guessEastAsian(sample: ByteArray): Charset? {
        var best: Charset? = null
        var bestScore = 0.0
        for (name in listOf("GB18030", "Big5", "Shift_JIS", "EUC-KR")) {
            val cs = charset(name) ?: continue
            val s = stats(sample, cs) ?: continue
            if (s.nonAscii == 0) continue
            // A few broken bytes are tolerated (truncated sample, stray bytes), wrong codepages break everywhere.
            if (s.errors > s.nonAscii / 500 + 2) continue
            val score = when (name) {
                "Shift_JIS" -> if (s.kana + s.hanzi > s.nonAscii / 2) s.kana.toDouble() / s.nonAscii else 0.0
                "EUC-KR" -> if (s.hangul > s.nonAscii / 2) s.freqHangul.toDouble() / s.hangul else 0.0
                else -> if (s.hanzi > s.nonAscii / 2) s.freqHanzi.toDouble() / s.hanzi else 0.0
            }
            if (score > bestScore) { bestScore = score; best = cs }
        }
        return if (bestScore >= 0.08) best else null
    }

    /**
     * windows-1250 versus windows-1252: Polish/Czech/Slovak/Hungarian letters that windows-1252
     * shows as symbols (ł→³, ą→¹, ż→¿, Ł→£, ...) inside words, or the š/ž plus ě/ř mix of Czech.
     */
    private fun looksCentralEuropean(sample: ByteArray): Boolean {
        val symbolIn1252 = intArrayOf(0x8D, 0x8F, 0x9D, 0xA3, 0xA5, 0xAF, 0xB3, 0xB9, 0xBC, 0xBE, 0xBF)
        val isSym = BooleanArray(256).also { a -> symbolIn1252.forEach { a[it] = true } }
        val count = IntArray(256)
        var inWord = 0
        var high = 0
        fun letterish(v: Int) = (v in 'a'.code..'z'.code) || (v in 'A'.code..'Z'.code) || v >= 0x80
        for (i in sample.indices) {
            val v = sample[i].toInt() and 0xFF
            if (v < 0x80) continue
            high++
            count[v]++
            if (isSym[v]) {
                val prev = if (i > 0) sample[i - 1].toInt() and 0xFF else 0
                val next = if (i + 1 < sample.size) sample[i + 1].toInt() and 0xFF else 0
                if (letterish(prev) || letterish(next)) inWord++
            }
        }
        if (high == 0) return false
        if (inWord > maxOf(2, high / 50)) return true
        val czech = count[0x9A] + count[0x9E] // š ž (same bytes in both codepages)
        val czech2 = count[0xEC] + count[0xF8] + count[0xF9] // ě ř ů (ì ø ù in 1252)
        if (czech > 2 && count[0xEC] > 0 && count[0xF8] > 0 && czech + czech2 > high / 5) return true
        // Hungarian ő (õ in 1252) without Portuguese ã.
        if (count[0xF5] > 3 && count[0xF5] > 2 * count[0xE3] && count[0xF5] > high / 30) return true
        return false
    }
}
