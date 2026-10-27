package app.quinten.formats.mobi

import java.io.ByteArrayOutputStream

/** PalmDOC LZ77 variant used by MOBI text records. */
object PalmDoc {
    fun decompress(src: ByteArray, len: Int = src.size): ByteArray {
        val out = ByteArrayOutputStream(len * 2)
        val buf = ArrayList<Byte>()
        var o = ByteArray(maxOf(len * 8, 4096))
        var op = 0
        fun put(b: Int) {
            if (op >= o.size) o = o.copyOf(o.size * 2)
            o[op++] = b.toByte()
        }
        var i = 0
        while (i < len) {
            val c = src[i++].toInt() and 0xFF
            when {
                c in 1..8 -> { for (k in 0 until c) { if (i < len) put(src[i++].toInt()) } }
                c < 0x80 -> put(c)
                c >= 0xC0 -> { put(' '.code); put(c xor 0x80) }
                else -> {
                    if (i >= len) break
                    val pair = (c shl 8) or (src[i++].toInt() and 0xFF)
                    val dist = (pair and 0x3FFF) ushr 3
                    val n = (pair and 7) + 3
                    if (dist == 0 || dist > op) continue
                    for (k in 0 until n) put(o[op - dist].toInt())
                }
            }
        }
        buf.clear()
        out.write(o, 0, op)
        return out.toByteArray()
    }
}

/** Huffman/CDIC decompressor used by some MOBI files (e.g. those made with Kindlegen "huff" option). */
class HuffCdic(huff: ByteArray, cdics: List<ByteArray>) {
    private val dict1Len = IntArray(256)
    private val dict1Term = BooleanArray(256)
    private val dict1Max = LongArray(256)
    private val minCode = LongArray(33)
    private val maxCode = LongArray(33)
    private val dictionary = ArrayList<ByteArray?>()
    private val dictFlag = ArrayList<Boolean>()

    init {
        require(String(huff, 0, 4, Charsets.ISO_8859_1) == "HUFF") { "bad HUFF record" }
        val off1 = be32(huff, 8)
        val off2 = be32(huff, 12)
        for (i in 0 until 256) {
            val v = be32(huff, off1 + i * 4).toLong() and 0xFFFFFFFFL
            val codelen = (v and 0x1F).toInt()
            dict1Len[i] = codelen
            dict1Term[i] = (v and 0x80) != 0L
            val max = v ushr 8
            dict1Max[i] = if (codelen == 0) 0 else ((max + 1) shl (32 - codelen)) - 1
        }
        minCode[0] = 0; maxCode[0] = (1L shl 32) - 1
        for (codelen in 1..32) {
            val mn = be32(huff, off2 + (codelen - 1) * 8).toLong() and 0xFFFFFFFFL
            val mx = be32(huff, off2 + (codelen - 1) * 8 + 4).toLong() and 0xFFFFFFFFL
            minCode[codelen] = mn shl (32 - codelen)
            maxCode[codelen] = ((mx + 1) shl (32 - codelen)) - 1
        }
        for (c in cdics) {
            require(String(c, 0, 4, Charsets.ISO_8859_1) == "CDIC") { "bad CDIC record" }
            val phrases = be32(c, 8)
            val bits = be32(c, 12)
            val n = minOf(1 shl bits, phrases - dictionary.size)
            for (k in 0 until n) {
                val off = be16(c, 16 + k * 2)
                val blen = be16(c, 16 + off)
                val start = 18 + off
                val l = blen and 0x7FFF
                dictionary += c.copyOfRange(start, (start + l).coerceAtMost(c.size))
                dictFlag += (blen and 0x8000) != 0
            }
        }
    }

    fun decompress(data: ByteArray, depth: Int = 0): ByteArray {
        val out = ByteArrayOutputStream(data.size * 3)
        var bitsLeft = data.size.toLong() * 8
        val d = data.copyOf(data.size + 8)
        var pos = 0
        var x = be64(d, pos)
        var n = 32
        while (true) {
            if (n <= 0) {
                pos += 4
                x = be64(d, pos)
                n += 32
            }
            val code = (x ushr n) and 0xFFFFFFFFL
            var codelen = dict1Len[(code ushr 24).toInt()]
            var max = dict1Max[(code ushr 24).toInt()]
            if (!dict1Term[(code ushr 24).toInt()]) {
                while (codelen < 32 && code < minCode[codelen]) codelen++
                max = maxCode[codelen]
            }
            if (codelen == 0) break
            n -= codelen
            bitsLeft -= codelen
            if (bitsLeft < 0) break
            val r = ((max - code) ushr (32 - codelen)).toInt()
            if (r < 0 || r >= dictionary.size) break
            var slice = dictionary[r] ?: break
            if (!dictFlag[r]) {
                if (depth > 32) break
                dictionary[r] = null
                slice = decompress(slice, depth + 1)
                dictionary[r] = slice
                dictFlag[r] = true
            }
            out.write(slice)
        }
        return out.toByteArray()
    }

    companion object {
        fun be32(b: ByteArray, o: Int) = ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)
        fun be16(b: ByteArray, o: Int) = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)
        fun be64(b: ByteArray, o: Int): Long {
            var v = 0L
            for (k in 0 until 8) v = (v shl 8) or (b[o + k].toLong() and 0xFF)
            return v
        }
    }
}
