// Ported to Kotlin from djvu-rs (MIT License, Copyright (c) 2026 Lev Matyushkin),
// itself a clean-room implementation of the public DjVu v3 specification.
package app.quinten.formats.djvu

import app.quinten.formats.djvu.ZpTables.LPS_NEXT
import app.quinten.formats.djvu.ZpTables.MPS_NEXT
import app.quinten.formats.djvu.ZpTables.PROB
import app.quinten.formats.djvu.ZpTables.THRESHOLD

class DjvuException(msg: String) : Exception(msg)

/** ZP adaptive binary arithmetic decoder. Contexts are bytes holding state index (low bit = MPS). */
internal class ZpDecoder(private val data: ByteArray, private var pos: Int = 0, private val end: Int = data.size) {
    private var a = 0
    private var c = 0
    private var fence = 0
    private var bitBuf = 0L
    private var bitCount = 0

    init {
        if (end - pos < 2) throw DjvuException("ZP stream too short")
        val high = readByte()
        val low = readByte()
        c = (high shl 8) or low
        refill()
        fence = minOf(c, 0x7fff)
    }

    private fun readByte(): Int = if (pos < end) (data[pos++].toInt() and 0xFF) else 0xFF

    private fun refill() {
        while (bitCount <= 24) {
            bitBuf = ((bitBuf shl 8) or readByte().toLong()) and 0xFFFFFFFFL
            bitCount += 8
        }
    }

    fun decodeBit(ctx: ByteArray, i: Int): Boolean {
        val state = ctx[i].toInt() and 0xFF
        val mps = state and 1
        val z = a + PROB[state]
        if (z <= fence) {
            a = z
            return mps != 0
        }
        val boundary = 0x6000 + ((a + z) ushr 2)
        val zc = minOf(z, boundary)
        if (zc > c) {
            val comp = 0x10000 - zc
            a = (a + comp) and 0xFFFF
            c = (c + comp) and 0xFFFF
            ctx[i] = LPS_NEXT[state].toByte()
            renormalize()
            return mps == 0
        } else {
            if (a >= THRESHOLD[state]) ctx[i] = MPS_NEXT[state].toByte()
            bitCount -= 1
            a = (zc shl 1) and 0xFFFF
            c = ((c shl 1) or ((bitBuf ushr bitCount).toInt() and 1)) and 0xFFFF
            if (bitCount < 16) refill()
            fence = minOf(c, 0x7fff)
            return mps != 0
        }
    }

    fun decodePassthrough(): Boolean = passthrough((0x8000 + (a ushr 1)) and 0xFFFF)

    fun decodePassthroughIw44(): Boolean = passthrough((0x8000 + (3 * a) / 8) and 0xFFFF)

    private fun passthrough(z: Int): Boolean {
        if (z > c) {
            val comp = 0x10000 - z
            a = (a + comp) and 0xFFFF
            c = (c + comp) and 0xFFFF
            renormalize()
            return true
        } else {
            bitCount -= 1
            a = (z * 2) and 0xFFFF
            c = ((c shl 1) or ((bitBuf ushr bitCount).toInt() and 1)) and 0xFFFF
            if (bitCount < 16) refill()
            fence = minOf(c, 0x7fff)
            return false
        }
    }

    private fun leadingOnes16(x: Int): Int {
        var n = 0
        var v = x
        while (n < 16 && (v and 0x8000) != 0) { n++; v = v shl 1 }
        return n
    }

    private fun renormalize() {
        val shift = leadingOnes16(a)
        bitCount -= shift
        a = (a shl shift) and 0xFFFF
        val mask = (1 shl shift) - 1
        c = ((c shl shift) or ((bitBuf ushr bitCount).toInt() and mask)) and 0xFFFF
        if (bitCount < 16) refill()
        fence = minOf(c, 0x7fff)
    }
}

/** BZZ (ZP + move-to-front + Burrows-Wheeler) decompressor used for DjVu metadata chunks. */
object Bzz {
    private const val CTX_COUNT = 300
    private const val FREQ_SLOTS = 4
    private const val LEVEL_CTXIDS = 3

    fun decode(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): ByteArray {
        val zp = ZpDecoder(data, offset, offset + length)
        val out = java.io.ByteArrayOutputStream()
        val ctx = ByteArray(CTX_COUNT)
        while (true) {
            val size = rawBits(zp, 24)
            if (size == 0) break
            out.write(block(zp, ctx, size))
        }
        return out.toByteArray()
    }

    private fun rawBits(zp: ZpDecoder, bits: Int): Int {
        val limit = 1 shl bits
        var n = 1
        while (n < limit) n = (n shl 1) or (if (zp.decodePassthrough()) 1 else 0)
        return n - limit
    }

    private fun ctxBits(zp: ZpDecoder, ctx: ByteArray, base: Int, bits: Int): Int {
        val sub = base - 1
        val limit = 1 shl bits
        var n = 1
        while (n < limit) n = (n shl 1) or (if (zp.decodeBit(ctx, sub + n)) 1 else 0)
        return n - limit
    }

    private fun block(zp: ZpDecoder, ctx: ByteArray, size: Int): ByteArray {
        if (size > 0x1000000) throw DjvuException("BZZ block too large")
        var freqShift = 0
        if (zp.decodePassthrough()) { freqShift++; if (zp.decodePassthrough()) freqShift++ }
        val mtf = IntArray(256) { it }
        val freq = IntArray(FREQ_SLOTS)
        var freqAdd = 4L
        var last = 3
        var marker = -1
        val bwt = ByteArray(size)
        for (idx in 0 until size) {
            val ctxId = minOf(last, LEVEL_CTXIDS - 1)
            var off = 0
            val pos: Int
            if (zp.decodeBit(ctx, off + ctxId)) pos = 0
            else {
                off += LEVEL_CTXIDS
                if (zp.decodeBit(ctx, off + ctxId)) pos = 1
                else {
                    off += LEVEL_CTXIDS
                    var result = -1
                    var span = 1
                    var baseVal = 2
                    var bits = 1
                    while (bits <= 7) {
                        if (zp.decodeBit(ctx, off)) { result = baseVal + ctxBits(zp, ctx, off + 1, bits); break }
                        off += span * 2
                        span *= 2; baseVal *= 2; bits++
                    }
                    pos = if (result < 0) 256 else result
                }
            }
            last = pos
            if (pos == 256) {
                bwt[idx] = 0; marker = idx
            } else {
                val sym = mtf[pos]
                bwt[idx] = sym.toByte()
                freqAdd += freqAdd ushr freqShift
                if (freqAdd > 0x10000000L) {
                    freqAdd = freqAdd ushr 24
                    for (k in freq.indices) freq[k] = freq[k] ushr 24
                }
                var combined = freqAdd
                if (pos < FREQ_SLOTS) combined = minOf(combined + (freq[pos].toLong() and 0xFFFFFFFFL), 0xFFFFFFFFL)
                var at = pos
                while (at >= FREQ_SLOTS) { mtf[at] = mtf[at - 1]; at-- }
                while (at > 0) {
                    val prev = freq[at - 1].toLong() and 0xFFFFFFFFL
                    if (combined >= prev) { mtf[at] = mtf[at - 1]; freq[at] = prev.toInt(); at-- } else break
                }
                mtf[at] = sym
                if (at < FREQ_SLOTS) freq[at] = combined.toInt()
            }
        }
        if (marker < 0) throw DjvuException("BZZ marker missing")
        return inverseBwt(bwt, marker)
    }

    private fun inverseBwt(d: ByteArray, marker: Int): ByteArray {
        val total = d.size
        if (total == 0) return ByteArray(0)
        val count = IntArray(256)
        val rank = IntArray(total)
        for (i in 0 until total) {
            if (i == marker) continue
            val b = d[i].toInt() and 0xFF
            rank[i] = (b shl 24) or (count[b] and 0xFFFFFF)
            count[b]++
        }
        val start = IntArray(256)
        var running = 1
        for (b in 0 until 256) { start[b] = running; running += count[b] }
        val outLen = total - 1
        val out = ByteArray(outLen)
        var follow = 0
        var remaining = outLen
        while (remaining > 0) {
            val enc = rank[follow]
            val b = enc ushr 24
            remaining--
            out[remaining] = b.toByte()
            follow = start[b] + (enc and 0xFFFFFF)
            if (follow >= total) throw DjvuException("corrupt BZZ data")
        }
        return out
    }
}
