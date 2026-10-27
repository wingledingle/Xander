// Ported to Kotlin from djvu-rs (MIT License, Copyright (c) 2026 Lev Matyushkin),
// itself a clean-room implementation of the public DjVu v3 specification.
package app.quinten.formats.djvu

/** IW44 wavelet image decoder (BG44/FG44/TH44 chunks). Feed chunks in order, then call [toArgb]. */
class Iw44Image {
    var width = 0; private set
    var height = 0; private set
    private var isColor = false
    private var delay = 0
    private var chromaHalf = false
    private var y: Plane? = null
    private var cb: Plane? = null
    private var cr: Plane? = null
    private var cslice = 0

    fun decodeChunk(data: ByteArray, off: Int = 0, len: Int = data.size - off) {
        if (len < 2) throw DjvuException("IW44 chunk too short")
        val serial = data[off].toInt() and 0xFF
        val slices = data[off + 1].toInt() and 0xFF
        val payload: Int
        if (serial == 0) {
            if (len < 9) throw DjvuException("IW44 header too short")
            val maj = data[off + 2].toInt() and 0xFF
            val minor = data[off + 3].toInt() and 0xFF
            val gray = (maj ushr 7) != 0
            val w = ((data[off + 4].toInt() and 0xFF) shl 8) or (data[off + 5].toInt() and 0xFF)
            val h = ((data[off + 6].toInt() and 0xFF) shl 8) or (data[off + 7].toInt() and 0xFF)
            val delayByte = data[off + 8].toInt() and 0xFF
            if (w == 0 || h == 0) throw DjvuException("IW44 zero dimension")
            width = w; height = h
            isColor = !gray
            delay = if (minor >= 2) delayByte and 127 else 0
            chromaHalf = isColor && minor >= 2 && (delayByte and 0x80) == 0
            cslice = 0
            y = Plane(w, h)
            if (isColor) { cb = Plane(w, h); cr = Plane(w, h) }
            payload = 9
        } else {
            if (y == null) throw DjvuException("IW44 first chunk missing")
            payload = 2
        }
        if (len - payload < 2) return
        val zp = ZpDecoder(data, off + payload, off + len)
        repeat(slices) {
            cslice++
            y!!.decodeSlice(zp)
            if (isColor && cslice > delay) { cb!!.decodeSlice(zp); cr!!.decodeSlice(zp) }
        }
    }

    val isInitialized get() = y != null

    /** Renders to ARGB (top-down) at 1/[subsample] resolution. */
    fun toArgb(subsample: Int = 1): IntArray {
        val sub = subsample.coerceAtLeast(1)
        val yd = y ?: throw DjvuException("IW44 no data")
        val w = (width + sub - 1) / sub
        val h = (height + sub - 1) / sub
        val yp = yd.reconstruct(sub)
        val out = IntArray(w * h)
        if (isColor) {
            val cs = if (chromaHalf) maxOf(sub, 2) else sub
            val cbp = cb!!.reconstruct(cs)
            val crp = cr!!.reconstruct(cs)
            for (row in 0 until h) {
                val outRow = h - 1 - row
                for (col in 0 until w) {
                    val sr = row * sub; val sc = col * sub
                    val yy = norm(yp.data[sr * yp.stride + sc])
                    val chr = if (chromaHalf) sr and 1.inv() else sr
                    val chc = if (chromaHalf) sc and 1.inv() else sc
                    val ci = chr * cbp.stride + chc
                    val b = norm(cbp.data[ci]); val r = norm(crp.data[ci])
                    val t2 = r + (r shr 1)
                    val t3 = yy + 128 - (b shr 2)
                    val red = (yy + 128 + t2).coerceIn(0, 255)
                    val green = (t3 - (t2 shr 1)).coerceIn(0, 255)
                    val blue = (t3 + (b shl 1)).coerceIn(0, 255)
                    out[outRow * w + col] = (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
                }
            }
        } else {
            for (row in 0 until h) {
                val outRow = h - 1 - row
                for (col in 0 until w) {
                    val v = norm(yp.data[row * sub * yp.stride + col * sub])
                    val g = 127 - v
                    out[outRow * w + col] = (0xFF shl 24) or (g shl 16) or (g shl 8) or g
                }
            }
        }
        return out
    }

    fun outWidth(sub: Int) = (width + sub - 1) / sub
    fun outHeight(sub: Int) = (height + sub - 1) / sub

    private fun norm(v: Short): Int = ((v.toInt() + 32) shr 6).coerceIn(-128, 127)

    private class Flat(val data: ShortArray, val stride: Int)

    private class Plane(val width: Int, val height: Int) {
        val blockCols = (width + 31) / 32
        val blockRows = (height + 31) / 32
        val blocks = Array(blockCols * blockRows) { ShortArray(1024) }
        val quantLo = QUANT_LO.copyOf()
        val quantHi = QUANT_HI.copyOf()
        var curband = 0
        val ctxBucket = ByteArray(1)
        val ctxCoef = ByteArray(80)
        val ctxActivate = ByteArray(16)
        val ctxIncrease = ByteArray(1)
        val coeffstate = Array(16) { IntArray(16) }
        val bucketstate = IntArray(16)
        var bbstate = 0

        fun decodeSlice(zp: ZpDecoder) {
            if (!isNullSlice()) {
                for (bi in blocks.indices) {
                    preliminary(bi)
                    if (blockBand(zp)) {
                        bucketPass(zp, bi)
                        newlyActive(zp, bi)
                    }
                    previouslyActive(zp, bi)
                }
            }
            finish()
        }

        fun isNullSlice(): Boolean {
            if (curband == 0) {
                var isNull = true
                for (i in 0 until 16) {
                    val t = quantLo[i]
                    coeffstate[0][i] = ZERO
                    if (t in 1 until 0x8000) { coeffstate[0][i] = UNK; isNull = false }
                }
                return isNull
            }
            val t = quantHi[curband]
            return !(t in 1 until 0x8000)
        }

        fun preliminary(bi: Int) {
            bbstate = 0
            val from = BUCKETS[curband * 2]; val to = BUCKETS[curband * 2 + 1]
            val blk = blocks[bi]
            if (curband != 0) {
                for (j in from..to) {
                    val boff = j - from
                    var bs = 0
                    for (k in 0 until 16) {
                        coeffstate[boff][k] = if (blk[(j shl 4) or k].toInt() == 0) UNK else ACTIVE
                        bs = bs or coeffstate[boff][k]
                    }
                    bucketstate[boff] = bs
                    bbstate = bbstate or bs
                }
            } else {
                var bs = 0
                for (k in 0 until 16) {
                    if (coeffstate[0][k] != ZERO) coeffstate[0][k] = if (blk[k].toInt() == 0) UNK else ACTIVE
                    bs = bs or coeffstate[0][k]
                }
                bucketstate[0] = bs
                bbstate = bbstate or bs
            }
        }

        fun blockBand(zp: ZpDecoder): Boolean {
            val from = BUCKETS[curband * 2]; val to = BUCKETS[curband * 2 + 1]
            val bcount = to - from + 1
            val mark = bcount < 16 || (bbstate and ACTIVE) != 0 || ((bbstate and UNK) != 0 && zp.decodeBit(ctxBucket, 0))
            if (mark) bbstate = bbstate or NEW
            return (bbstate and NEW) != 0
        }

        fun bucketPass(zp: ZpDecoder, bi: Int) {
            val from = BUCKETS[curband * 2]; val to = BUCKETS[curband * 2 + 1]
            val blk = blocks[bi]
            for (i in from..to) {
                val boff = i - from
                if ((bucketstate[boff] and UNK) == 0) continue
                var n = 0
                if (curband != 0) {
                    val t = 4 * i
                    for (j in t until t + 4) if (blk[j].toInt() != 0) n++
                    if (n == 4) n = 3
                }
                if ((bbstate and ACTIVE) != 0) n = n or 4
                if (zp.decodeBit(ctxCoef, n + curband * 8)) bucketstate[boff] = bucketstate[boff] or NEW
            }
        }

        fun newlyActive(zp: ZpDecoder, bi: Int) {
            val from = BUCKETS[curband * 2]; val to = BUCKETS[curband * 2 + 1]
            var step = quantHi[curband]
            val blk = blocks[bi]
            for (i in from..to) {
                val boff = i - from
                if ((bucketstate[boff] and NEW) == 0) continue
                val shift = if ((bucketstate[boff] and ACTIVE) != 0) 8 else 0
                var np = 0
                for (j in 0 until 16) if ((coeffstate[boff][j] and UNK) != 0) np++
                for (j in 0 until 16) {
                    if ((coeffstate[boff][j] and UNK) != 0) {
                        val ip = minOf(np, 7)
                        if (zp.decodeBit(ctxActivate, shift + ip)) {
                            val sign = if (zp.decodePassthroughIw44()) -1 else 1
                            np = 0
                            if (curband == 0) step = quantLo[j]
                            val s = step
                            blk[(i shl 4) or j] = (sign * (s + (s shr 1) - (s shr 3))).toShort()
                        }
                        np = maxOf(0, np - 1)
                    }
                }
            }
        }

        fun previouslyActive(zp: ZpDecoder, bi: Int) {
            val from = BUCKETS[curband * 2]; val to = BUCKETS[curband * 2 + 1]
            var step = quantHi[curband]
            val blk = blocks[bi]
            for (i in from..to) {
                val boff = i - from
                for (j in 0 until 16) {
                    if ((coeffstate[boff][j] and ACTIVE) == 0) continue
                    if (curband == 0) step = quantLo[j]
                    val coef = blk[(i shl 4) or j].toInt()
                    var abs = kotlin.math.abs(coef)
                    val s = step
                    val des = if (abs <= 3 * s) {
                        val d = zp.decodeBit(ctxIncrease, 0)
                        abs += s shr 2
                        d
                    } else zp.decodePassthroughIw44()
                    abs += if (des) s shr 1 else -s + (s shr 1)
                    blk[(i shl 4) or j] = (if (coef < 0) -abs else abs).toShort()
                }
            }
        }

        fun finish() {
            quantHi[curband] = quantHi[curband] ushr 1
            if (curband == 0) for (i in 0 until 16) quantLo[i] = quantLo[i] ushr 1
            curband++
            if (curband == 10) curband = 0
        }

        fun reconstruct(sub: Int): Flat {
            val fw = blockCols * 32
            val fh = blockRows * 32
            val data = ShortArray(fw * fh)
            for (r in 0 until blockRows) for (c in 0 until blockCols) {
                val blk = blocks[r * blockCols + c]
                val rb = r shl 5; val cbase = c shl 5
                for (i in 0 until 1024) data[(ZZ_ROW[i] + rb) * fw + ZZ_COL[i] + cbase] = blk[i]
            }
            inverseWavelet(data, fw, width, height, sub)
            return Flat(data, fw)
        }
    }

    private companion object {
        const val ZERO = 1
        const val ACTIVE = 2
        const val NEW = 4
        const val UNK = 8
        val BUCKETS = intArrayOf(0, 0, 1, 1, 2, 2, 3, 3, 4, 7, 8, 11, 12, 15, 16, 31, 32, 47, 48, 63)
        val QUANT_LO = intArrayOf(0x004000, 0x008000, 0x008000, 0x010000, 0x010000, 0x010000, 0x010000, 0x010000, 0x010000, 0x010000, 0x010000, 0x010000, 0x020000, 0x020000, 0x020000, 0x020000)
        val QUANT_HI = intArrayOf(0, 0x020000, 0x020000, 0x040000, 0x040000, 0x040000, 0x080000, 0x040000, 0x040000, 0x080000)
        val ZZ_ROW = IntArray(1024) { i -> ((i shr 1) and 1) * 16 + ((i shr 3) and 1) * 8 + ((i shr 5) and 1) * 4 + ((i shr 7) and 1) * 2 + ((i shr 9) and 1) }
        val ZZ_COL = IntArray(1024) { i -> (i and 1) * 16 + ((i shr 2) and 1) * 8 + ((i shr 4) and 1) * 4 + ((i shr 6) and 1) * 2 + ((i shr 8) and 1) }

        fun inverseWavelet(d: ShortArray, stride: Int, width: Int, height: Int, subsample: Int) {
            var sd = 4
            var s = 16
            val st0 = IntArray(width); val st1 = IntArray(width); val st2 = IntArray(width)
            while (s >= subsample) {
                // Vertical pass
                run {
                    val kmax = (height - 1) shr sd
                    val border = maxOf(0, kmax - 3)
                    val ncols = (width + s - 1) / s
                    for (i in 0 until ncols) { st0[i] = 0; st1[i] = 0 }
                    if (kmax >= 1) {
                        val off = (1 shl sd) * stride
                        var ci = 0; var col = 0
                        while (col < width) { st2[ci] = d[off + col].toInt(); ci++; col += s }
                    } else for (i in 0 until ncols) st2[i] = 0
                    var k = 0
                    while (k <= kmax) {
                        val kOff = (k shl sd) * stride
                        val has3 = k + 3 <= kmax
                        val n3Off = if (has3) ((k + 3) shl sd) * stride else 0
                        var ci = 0; var col = 0
                        while (col < width) {
                            val p3 = st0[ci]; val p1 = st1[ci]; val n1 = st2[ci]
                            val n3 = if (has3) d[n3Off + col].toInt() else 0
                            val a = p1 + n1; val c = p3 + n3
                            val idx = kOff + col
                            d[idx] = (d[idx] - (((a shl 3) + a - c + 16) shr 5)).toShort()
                            st0[ci] = p1; st1[ci] = n1; st2[ci] = n3
                            ci++; col += s
                        }
                        k += 2
                    }
                    if (kmax >= 1) {
                        val kOff = (1 shl sd) * stride
                        if (2 <= kmax) {
                            val kp1 = (2 shl sd) * stride
                            var ci = 0; var col = 0
                            while (col < width) {
                                val p = d[col].toInt(); val n = d[kp1 + col].toInt()
                                val idx = kOff + col
                                d[idx] = (d[idx] + ((p + n + 1) shr 1)).toShort()
                                st0[ci] = p; st1[ci] = n
                                ci++; col += s
                            }
                        } else {
                            var ci = 0; var col = 0
                            while (col < width) {
                                val p = d[col].toInt()
                                val idx = kOff + col
                                d[idx] = (d[idx] + p).toShort()
                                st0[ci] = p; st1[ci] = 0
                                ci++; col += s
                            }
                        }
                        if (border >= 3) {
                            val off = (4 shl sd) * stride
                            var ci = 0; var col = 0
                            while (col < width) { st2[ci] = d[off + col].toInt(); ci++; col += s }
                        }
                        k = 3
                        while (k <= border) {
                            val kOff2 = (k shl sd) * stride
                            val n3Off = ((k + 3) shl sd) * stride
                            var ci = 0; var col = 0
                            while (col < width) {
                                val p3 = st0[ci]; val p1 = st1[ci]; val n1 = st2[ci]
                                val n3 = d[n3Off + col].toInt()
                                val a = p1 + n1
                                val idx = kOff2 + col
                                d[idx] = (d[idx] + (((a shl 3) + a - (p3 + n3) + 8) shr 4)).toShort()
                                st0[ci] = p1; st1[ci] = n1; st2[ci] = n3
                                ci++; col += s
                            }
                            k += 2
                        }
                        while (k <= kmax) {
                            val kOff2 = (k shl sd) * stride
                            var ci = 0; var col = 0
                            if (k < kmax) {
                                while (col < width) {
                                    val p = st1[ci]; val n = st2[ci]
                                    val idx = kOff2 + col
                                    d[idx] = (d[idx] + ((p + n + 1) shr 1)).toShort()
                                    st1[ci] = n; st2[ci] = 0
                                    ci++; col += s
                                }
                            } else {
                                while (col < width) {
                                    val p = st1[ci]
                                    val idx = kOff2 + col
                                    d[idx] = (d[idx] + p).toShort()
                                    st1[ci] = st2[ci]; st2[ci] = 0
                                    ci++; col += s
                                }
                            }
                            k += 2
                        }
                    }
                }
                // Horizontal pass
                run {
                    val kmax = (width - 1) shr sd
                    val border = maxOf(0, kmax - 3)
                    var row = 0
                    while (row < height) {
                        val off = row * stride
                        var prev1 = 0; var next1 = 0
                        var next3 = if (kmax >= 1) d[off + (1 shl sd)].toInt() else 0
                        var prev3: Int
                        var k = 0
                        while (k <= kmax) {
                            prev3 = prev1; prev1 = next1; next1 = next3
                            next3 = if (k + 3 <= kmax) d[off + ((k + 3) shl sd)].toInt() else 0
                            val a = prev1 + next1; val c = prev3 + next3
                            val idx = off + (k shl sd)
                            d[idx] = (d[idx] - (((a shl 3) + a - c + 16) shr 5)).toShort()
                            k += 2
                        }
                        if (kmax >= 1) {
                            k = 1
                            prev1 = d[off + ((k - 1) shl sd)].toInt()
                            if (k < kmax) {
                                next1 = d[off + ((k + 1) shl sd)].toInt()
                                val idx = off + (k shl sd)
                                d[idx] = (d[idx] + ((prev1 + next1 + 1) shr 1)).toShort()
                            } else {
                                val idx = off + (k shl sd)
                                d[idx] = (d[idx] + prev1).toShort()
                            }
                            next3 = if (border >= 3) d[off + ((k + 3) shl sd)].toInt() else 0
                            k = 3
                            while (k <= border) {
                                prev3 = prev1; prev1 = next1; next1 = next3
                                next3 = d[off + ((k + 3) shl sd)].toInt()
                                val a = prev1 + next1
                                val idx = off + (k shl sd)
                                d[idx] = (d[idx] + (((a shl 3) + a - (prev3 + next3) + 8) shr 4)).toShort()
                                k += 2
                            }
                            while (k <= kmax) {
                                prev1 = next1; next1 = next3; next3 = 0
                                val idx = off + (k shl sd)
                                d[idx] = if (k < kmax) (d[idx] + ((prev1 + next1 + 1) shr 1)).toShort() else (d[idx] + prev1).toShort()
                                k += 2
                            }
                        }
                        row += s
                    }
                }
                s = s shr 1
                sd = maxOf(0, sd - 1)
            }
        }
    }
}
