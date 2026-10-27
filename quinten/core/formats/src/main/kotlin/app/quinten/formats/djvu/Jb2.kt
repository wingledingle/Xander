// Ported to Kotlin from djvu-rs (MIT License, Copyright (c) 2026 Lev Matyushkin),
// itself a clean-room implementation of the public DjVu v3 specification.
package app.quinten.formats.djvu

/**
 * Bilevel bitmap, 1 byte per pixel, row 0 = top. A pixel is ink when its byte is non-zero.
 *
 * When decoded with a foreground palette ([indexed]), the palette colour of each ink pixel is
 * stored directly instead of a per-pixel blit index: in the pixel byte itself (value - 1) for
 * palettes of up to 255 colours, otherwise in [wideColors] (2 bytes per pixel).
 */
class Bitonal(val width: Int, val height: Int, val data: ByteArray, val indexed: Boolean = false, val wideColors: ShortArray? = null) {
    /** Palette index of ink pixel [i] (only meaningful when [indexed]). */
    fun colorIndex(i: Int): Int = if (wideColors != null) wideColors[i].toInt() and 0xFFFF else (data[i].toInt() and 0xFF) - 1
}

/** JB2 bilevel image decoder (Sjbz mask chunks and Djbz shared dictionaries). */
object Jb2 {
    /** Symbol bitmap, row 0 = bottom (JB2 convention). */
    internal class Jbm(val width: Int, val height: Int, val data: ByteArray = ByteArray(maxOf(0, width) * maxOf(0, height))) {
        fun get(row: Int, col: Int): Int =
            if (row < 0 || row >= height || col < 0 || col >= width) 0 else data[row * width + col].toInt()
        fun set(row: Int, col: Int) {
            if (row in 0 until height && col in 0 until width) data[row * width + col] = 1
        }
        fun crop(): Jbm {
            var minR = height; var maxR = -1; var minC = width; var maxC = -1
            for (r in 0 until height) for (c in 0 until width) if (data[r * width + c].toInt() != 0) {
                if (r < minR) minR = r; if (r > maxR) maxR = r; if (c < minC) minC = c; if (c > maxC) maxC = c
            }
            if (maxR < 0) return Jbm(0, 0)
            val nw = maxC - minC + 1; val nh = maxR - minR + 1
            val out = Jbm(nw, nh)
            for (r in minR..maxR) for (c in minC..maxC) if (data[r * width + c].toInt() != 0) out.data[(r - minR) * nw + (c - minC)] = 1
            return out
        }
    }

    class Dict internal constructor(internal val symbols: List<Jbm>)

    private class NumCtx {
        var ctx = ByteArray(64); var left = IntArray(64); var right = IntArray(64); var size = 2
        private fun alloc(): Int {
            if (size == ctx.size) { ctx = ctx.copyOf(size * 2); left = left.copyOf(size * 2); right = right.copyOf(size * 2) }
            return size++
        }
        // Note: alloc() may replace the arrays, so allocate before indexing into them.
        fun left(n: Int): Int { if (left[n] == 0) { val i = alloc(); left[n] = i }; return left[n] }
        fun right(n: Int): Int { if (right[n] == 0) { val i = alloc(); right[n] = i }; return right[n] }
    }

    private fun num(zp: ZpDecoder, c: NumCtx, low0: Int, high0: Int): Int {
        var low = low0; var high = high0
        var negative = false
        var cutoff = 0
        var phase = 1
        var range = -1L and 0xFFFFFFFFL
        var node = 1
        while (range != 1L) {
            val decision = when {
                low >= cutoff -> true
                high >= cutoff -> zp.decodeBit(c.ctx, node)
                else -> false
            }
            node = if (decision) c.right(node) else c.left(node)
            when (phase) {
                1 -> {
                    negative = !decision
                    if (negative) { val t = -low - 1; low = -high - 1; high = t }
                    phase = 2; cutoff = 1
                }
                2 -> if (!decision) {
                    phase = 3
                    range = ((cutoff + 1) / 2).toLong()
                    if (range == 1L) cutoff = 0 else cutoff -= (range / 2).toInt()
                } else cutoff = cutoff * 2 + 1
                3 -> {
                    range /= 2
                    if (range != 1L) {
                        if (!decision) cutoff -= (range / 2).toInt() else cutoff += (range / 2).toInt()
                    } else if (!decision) cutoff -= 1
                }
            }
        }
        return if (negative) -cutoff - 1 else cutoff
    }

    private fun direct(zp: ZpDecoder, ctx: ByteArray, width: Int, height: Int): Jbm {
        if (width < 0 || height < 0 || width.toLong() * height > 64L * 1024 * 1024) throw DjvuException("bad JB2 symbol size")
        val bm = Jbm(width, height)
        for (row in height - 1 downTo 0) {
            var r2 = (bm.get(row + 2, 0) shl 1) or bm.get(row + 2, 1)
            var r1 = (bm.get(row + 1, 0) shl 2) or (bm.get(row + 1, 1) shl 1) or bm.get(row + 1, 2)
            var r0 = 0
            for (col in 0 until width) {
                val idx = (r2 shl 7) or (r1 shl 2) or r0
                val bit = zp.decodeBit(ctx, idx)
                if (bit) bm.set(row, col)
                r2 = ((r2 shl 1) and 7) or bm.get(row + 2, col + 2)
                r1 = ((r1 shl 1) and 31) or bm.get(row + 1, col + 3)
                r0 = ((r0 shl 1) and 3) or (if (bit) 1 else 0)
            }
        }
        return bm
    }

    private fun refine(zp: ZpDecoder, ctx: ByteArray, width: Int, height: Int, m: Jbm): Jbm {
        if (width < 0 || height < 0 || width.toLong() * height > 64L * 1024 * 1024) throw DjvuException("bad JB2 symbol size")
        val c = Jbm(width, height)
        val rowShift = ((m.height - 1) shr 1) - ((height - 1) shr 1)
        val colShift = ((m.width - 1) shr 1) - ((width - 1) shr 1)
        for (row in height - 1 downTo 0) {
            val mr = row + rowShift
            var cr1 = (c.get(row + 1, 0) shl 1) or c.get(row + 1, 1)
            var cr0 = 0
            var mr1 = (m.get(mr, colShift - 1) shl 2) or (m.get(mr, colShift) shl 1) or m.get(mr, colShift + 1)
            var mr0 = (m.get(mr - 1, colShift - 1) shl 2) or (m.get(mr - 1, colShift) shl 1) or m.get(mr - 1, colShift + 1)
            for (col in 0 until width) {
                val mr2 = m.get(mr + 1, col + colShift)
                val idx = (cr1 shl 8) or (cr0 shl 7) or (mr2 shl 6) or (mr1 shl 3) or mr0
                val bit = zp.decodeBit(ctx, idx)
                if (bit) c.set(row, col)
                cr1 = ((cr1 shl 1) and 7) or c.get(row + 1, col + 2)
                cr0 = if (bit) 1 else 0
                mr1 = ((mr1 shl 1) and 7) or m.get(mr, col + colShift + 2)
                mr0 = ((mr0 shl 1) and 7) or m.get(mr - 1, col + colShift + 2)
            }
        }
        return c
    }

    private class Baseline {
        val arr = IntArray(3); var index = -1
        fun fill(v: Int) { arr.fill(v) }
        fun add(v: Int) { index++; if (index == 3) index = 0; arr[index] = v }
        fun get(): Int {
            val a = arr[0]; val b = arr[1]; val c = arr[2]
            return if ((a >= b && a <= c) || (a <= b && a >= c)) a else if ((b >= a && b <= c) || (b <= a && b >= c)) b else c
        }
    }

    fun decodeDict(data: ByteArray, inherited: Dict? = null, offset: Int = 0, end: Int = data.size): Dict =
        Dict(run(data, offset, end, inherited, dictOnly = true, paletteSize = 0, blitColor = null).second)

    /**
     * Decodes a mask. With [paletteSize] > 0 the pixels carry palette indices: blit number n gets
     * colour `blitColor[n]` (or 0 when there is no blit-colour table), mapped while decoding so no
     * full-size blit index array is needed.
     */
    fun decode(data: ByteArray, shared: Dict? = null, paletteSize: Int = 0, blitColor: IntArray? = null, offset: Int = 0, end: Int = data.size): Bitonal =
        run(data, offset, end, shared, false, paletteSize, blitColor).first!!

    private fun run(data: ByteArray, offset: Int, end: Int, shared: Dict?, dictOnly: Boolean, paletteSize: Int, blitColor: IntArray?): Pair<Bitonal?, List<Jbm>> {
        val zp = ZpDecoder(data, offset, end)
        val indexed = paletteSize > 0 && !dictOnly
        val recordType = NumCtx(); val imageSize = NumCtx(); val symW = NumCtx(); val symH = NumCtx(); val inheritSize = NumCtx()
        val hoff = NumCtx(); val voff = NumCtx(); val shoff = NumCtx(); val svoff = NumCtx(); val symIndex = NumCtx()
        val wdiffC = NumCtx(); val hdiffC = NumCtx(); val habs = NumCtx(); val vabs = NumCtx(); val cLen = NumCtx(); val cOct = NumCtx()
        val offsetType = ByteArray(1)
        val directCtx = ByteArray(1024)
        val refineCtx = ByteArray(2048)
        var rtype = num(zp, recordType, 0, 11)
        var initialDict = 0
        if (rtype == 9) {
            initialDict = num(zp, inheritSize, 0, 262142)
            rtype = num(zp, recordType, 0, 11)
        }
        var width = num(zp, imageSize, 0, 262142).let { if (it == 0) 200 else it }
        var height = num(zp, imageSize, 0, 262142).let { if (it == 0) 200 else it }
        if (zp.decodeBit(ByteArray(1), 0)) throw DjvuException("bad JB2 header flag")
        val dict = ArrayList<Jbm>()
        if (initialDict > 0) {
            val sd = shared ?: throw DjvuException("JB2 shared dictionary missing")
            if (initialDict > sd.symbols.size) throw DjvuException("JB2 inherited dictionary too large")
            dict.addAll(sd.symbols.subList(0, initialDict))
        }
        if (dictOnly) { width = 1; height = 1 }
        if (width.toLong() * height > 64L * 1024 * 1024) throw DjvuException("JB2 image too large")
        val page = ByteArray(width * height)
        val wide = if (indexed && paletteSize > 255) ShortArray(width * height) else null
        var blitCount = 0
        var firstLeft = -1
        var firstBottom = height - 1
        var lastRight = 0
        val baseline = Baseline()

        fun coords(sw: Int, sh: Int): Pair<Int, Int> {
            val newLine = zp.decodeBit(offsetType, 0)
            val x: Int; val y: Int
            if (newLine) {
                val h = num(zp, hoff, -262143, 262142)
                val v = num(zp, voff, -262143, 262142)
                x = firstLeft + h
                y = firstBottom + v - sh + 1
                firstLeft = x; firstBottom = y
                baseline.fill(y)
            } else {
                val h = num(zp, shoff, -262143, 262142)
                val v = num(zp, svoff, -262143, 262142)
                x = lastRight + h
                y = baseline.get() + v
            }
            baseline.add(y)
            lastRight = x + sw - 1
            return x to y
        }

        fun blit(s: Jbm, x: Int, y: Int) {
            val idx = blitCount++
            val ci = if (!indexed) 0 else (if (blitColor != null && idx < blitColor.size) blitColor[idx] else 0).coerceIn(0, paletteSize - 1)
            val v: Byte = if (indexed && wide == null) (ci + 1).toByte() else 1
            for (row in 0 until s.height) {
                val py = y + row
                if (py < 0 || py >= height) continue
                for (col in 0 until s.width) {
                    if (s.data[row * s.width + col].toInt() == 0) continue
                    val px = x + col
                    if (px < 0 || px >= width) continue
                    page[py * width + px] = v
                    if (wide != null) wide[py * width + px] = ci.toShort()
                }
            }
        }

        fun refined(): Jbm {
            if (dict.isEmpty()) throw DjvuException("JB2 reference to empty dictionary")
            val i = num(zp, symIndex, 0, dict.size - 1)
            if (i !in dict.indices) throw DjvuException("JB2 bad symbol index")
            val wd = num(zp, wdiffC, -262143, 262142)
            val hd = num(zp, hdiffC, -262143, 262142)
            return refine(zp, refineCtx, dict[i].width + wd, dict[i].height + hd, dict[i])
        }

        loop@ while (true) {
            when (num(zp, recordType, 0, 11)) {
                1 -> { val bm = direct(zp, directCtx, num(zp, symW, 0, 262142), num(zp, symH, 0, 262142)); val (x, y) = coords(bm.width, bm.height); blit(bm, x, y); dict += bm.crop() }
                2 -> { val bm = direct(zp, directCtx, num(zp, symW, 0, 262142), num(zp, symH, 0, 262142)); dict += bm.crop() }
                3 -> { val bm = direct(zp, directCtx, num(zp, symW, 0, 262142), num(zp, symH, 0, 262142)); val (x, y) = coords(bm.width, bm.height); blit(bm, x, y) }
                4 -> { val bm = refined(); val (x, y) = coords(bm.width, bm.height); blit(bm, x, y); dict += bm.crop() }
                5 -> { dict += refined().crop() }
                6 -> { val bm = refined(); val (x, y) = coords(bm.width, bm.height); blit(bm, x, y) }
                7 -> {
                    if (dict.isEmpty()) throw DjvuException("JB2 reference to empty dictionary")
                    val i = num(zp, symIndex, 0, dict.size - 1)
                    if (i !in dict.indices) throw DjvuException("JB2 bad symbol index")
                    val s = dict[i]
                    val (x, y) = coords(s.width, s.height)
                    blit(s, x, y)
                }
                8 -> {
                    val w = num(zp, symW, 0, 262142); val h = num(zp, symH, 0, 262142)
                    val bm = direct(zp, directCtx, w, h)
                    val left = num(zp, habs, 1, width); val top = num(zp, vabs, 1, height)
                    blit(bm, left - 1, top - h)
                }
                9 -> {}
                10 -> { val len = num(zp, cLen, 0, 262142); repeat(len) { num(zp, cOct, 0, 255) } }
                11 -> break@loop
                else -> throw DjvuException("JB2 unknown record")
            }
        }
        if (dictOnly) return null to dict
        // Flip to top-down in place (no second page-sized copy).
        val tmp = ByteArray(width)
        val tmpW = if (wide != null) ShortArray(width) else null
        for (row in 0 until height / 2) {
            val a = row * width; val b = (height - 1 - row) * width
            System.arraycopy(page, a, tmp, 0, width); System.arraycopy(page, b, page, a, width); System.arraycopy(tmp, 0, page, b, width)
            if (wide != null) { System.arraycopy(wide, a, tmpW!!, 0, width); System.arraycopy(wide, b, wide, a, width); System.arraycopy(tmpW, 0, wide, b, width) }
        }
        return Bitonal(width, height, page, indexed, wide) to dict
    }
}
