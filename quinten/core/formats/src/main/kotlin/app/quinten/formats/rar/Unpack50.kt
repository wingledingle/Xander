package app.quinten.formats.rar

/** RAR 5.x LZ decoder (with delta / x86 / ARM filters). State persists for solid archives. */
internal class Unpack50(dictSize: Int) {
    private val win = Window(dictSize)
    val windowSize: Int get() = win.size
    private val ld = HuffTable(NC)
    private val dd = HuffTable(DC)
    private val ldd = HuffTable(LDC)
    private val rd = HuffTable(RC)
    private val lengths = IntArray(TABLE_SIZE)
    private var tablesRead = false
    private val oldDist = IntArray(4)
    private var lastLength = 0

    private class Filter(val start: Int, val length: Int, val type: Int, val channels: Int)

    fun unpack(input: ByteArray, outSize: Int, solidContinue: Boolean): ByteArray {
        if (!solidContinue) {
            // A new stream never refers to earlier data (the decoder may be reused).
            win.ptr = 0; win.total = 0
            tablesRead = false

            oldDist.fill(0)
            lastLength = 0
            lengths.fill(0)
        }
        val out = ByteArray(outSize)
        var outPos = 0
        val filters = ArrayList<Filter>()
        val inp = BitInput(input)
        var blockStart: Int
        var blockSize: Int
        var blockBitSize: Int
        var lastBlock: Boolean

        fun readBlockHeader(): Boolean {
            inp.alignByte()
            if (inp.overflow(3)) return false
            val flags = inp.getbits() ushr 8; inp.addbits(8)
            val byteCount = ((flags ushr 3) and 3) + 1
            if (byteCount == 4) return false
            val check = inp.getbits() ushr 8; inp.addbits(8)
            var bs = 0
            for (i in 0 until byteCount) { bs += (inp.getbits() ushr 8) shl (i * 8); inp.addbits(8) }
            val sum = (0x5A xor flags xor bs xor (bs ushr 8) xor (bs ushr 16)) and 0xFF
            if (sum != check) return false
            blockSize = bs
            blockStart = inp.inAddr
            blockBitSize = (flags and 7) + 1
            lastBlock = flags and 0x40 != 0
            if (flags and 0x80 != 0) {
                if (!readCodeLengths(inp, lengths, TABLE_SIZE, null)) return false
                ld.build(lengths, 0, NC)
                dd.build(lengths, NC, DC)
                ldd.build(lengths, NC + DC, LDC)
                rd.build(lengths, NC + DC + LDC, RC)
                tablesRead = true
            }
            return tablesRead
        }

        blockStart = 0; blockSize = 0; blockBitSize = 0; lastBlock = false
        if (!readBlockHeader()) throw RarCorruptException("bad RAR5 block header")

        fun put(b: Int) {
            win.data[win.ptr] = b.toByte()
            win.ptr = (win.ptr + 1) and win.mask
            win.total++
            if (outPos < out.size) out[outPos] = b.toByte()
            outPos++
        }

        fun copy(length: Int, distance: Int) {
            if (distance <= 0 || distance > win.total || distance > win.size) {
                // corrupt distance: emit zeros to stay in sync
                repeat(length) { put(0) }
                return
            }
            var src = (win.ptr - distance) and win.mask
            repeat(length) {
                put(win.data[src].toInt())
                src = (src + 1) and win.mask
            }
        }

        fun slotToLength(slot: Int): Int {
            val lbits: Int
            var length: Int
            if (slot < 8) { lbits = 0; length = 2 + slot } else { lbits = slot / 4 - 1; length = 2 + ((4 or (slot and 3)) shl lbits) }
            if (lbits > 0) { length += inp.getbits() ushr (16 - lbits); inp.addbits(lbits) }
            return length
        }

        fun readFilterData(): Int {
            val byteCount = (inp.getbits() ushr 14) + 1
            inp.addbits(2)
            var data = 0
            for (i in 0 until byteCount) { data += (inp.getbits() ushr 8) shl (i * 8); inp.addbits(8) }
            return data
        }

        while (outPos < outSize) {
            if (inp.inAddr > blockStart + blockSize - 1 || (inp.inAddr == blockStart + blockSize - 1 && inp.inBit >= blockBitSize)) {
                if (lastBlock) break
                if (!readBlockHeader()) break
            }
            if (inp.inAddr >= input.size + 4) break
            val mainSlot = ld.decode(inp)
            when {
                mainSlot < 256 -> put(mainSlot)
                mainSlot >= 262 -> {
                    var length = slotToLength(mainSlot - 262)
                    val distSlot = dd.decode(inp)
                    val dbits: Int
                    var distance: Int
                    if (distSlot < 4) { dbits = 0; distance = 1 + distSlot } else { dbits = distSlot / 2 - 1; distance = 1 + ((2 or (distSlot and 1)) shl dbits) }
                    if (dbits > 0) {
                        if (dbits >= 4) {
                            if (dbits > 4) { distance += ((inp.getbits32() ushr (36 - dbits)) shl 4); inp.addbits(dbits - 4) }
                            distance += ldd.decode(inp)
                        } else {
                            distance += inp.getbits32() ushr (32 - dbits); inp.addbits(dbits)
                        }
                    }
                    if (distance > 0x100) { length++; if (distance > 0x2000) { length++; if (distance > 0x40000) length++ } }
                    oldDist[3] = oldDist[2]; oldDist[2] = oldDist[1]; oldDist[1] = oldDist[0]; oldDist[0] = distance
                    lastLength = length
                    copy(length, distance)
                }
                mainSlot == 256 -> {
                    val start = readFilterData()
                    val len = readFilterData()
                    val type = inp.getbits() ushr 13; inp.addbits(3)
                    var channels = 0
                    if (type == FILTER_DELTA) { channels = (inp.getbits() ushr 11) + 1; inp.addbits(5) }
                    filters += Filter(outPos + start, len, type, channels)
                }
                mainSlot == 257 -> if (lastLength != 0) copy(lastLength, oldDist[0])
                else -> {
                    val distNum = mainSlot - 258
                    val distance = oldDist[distNum]
                    for (i in distNum downTo 1) oldDist[i] = oldDist[i - 1]
                    oldDist[0] = distance
                    val length = slotToLength(rd.decode(inp))
                    lastLength = length
                    copy(length, distance)
                }
            }
        }
        for (f in filters) applyFilter(out, f)
        return out
    }

    private fun applyFilter(out: ByteArray, f: Filter) {
        val start = f.start
        if (start < 0 || f.length <= 0 || start + f.length > out.size) return
        val src = out.copyOfRange(start, start + f.length)
        when (f.type) {
            FILTER_DELTA -> {
                var sp = 0
                for (ch in 0 until f.channels) {
                    var prev = 0
                    var dp = ch
                    while (dp < f.length) {
                        prev = (prev - src[sp++]) and 0xFF
                        out[start + dp] = prev.toByte()
                        dp += f.channels
                    }
                }
            }
            FILTER_E8, FILTER_E8E9 -> {
                val fileSize = 0x1000000
                var cur = 0
                while (cur + 4 < f.length) {
                    val b = src[cur++].toInt() and 0xFF
                    if (b == 0xE8 || (f.type == FILTER_E8E9 && b == 0xE9)) {
                        val offset = (cur + start) % fileSize
                        val addr = le32(src, cur)
                        if (addr < 0) {
                            if ((addr + offset) >= 0) put32(src, cur, addr + fileSize)
                        } else if ((addr - fileSize) < 0) put32(src, cur, addr - offset)
                        cur += 4
                    }
                }
                System.arraycopy(src, 0, out, start, f.length)
            }
            FILTER_ARM -> {
                var cur = 0
                while (cur + 3 < f.length) {
                    if (src[cur + 3].toInt() and 0xFF == 0xEB) {
                        var offset = (src[cur].toInt() and 0xFF) or ((src[cur + 1].toInt() and 0xFF) shl 8) or ((src[cur + 2].toInt() and 0xFF) shl 16)
                        offset -= (start + cur) / 4
                        src[cur] = offset.toByte(); src[cur + 1] = (offset ushr 8).toByte(); src[cur + 2] = (offset ushr 16).toByte()
                    }
                    cur += 4
                }
                System.arraycopy(src, 0, out, start, f.length)
            }
        }
    }

    private fun le32(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
        ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    private fun put32(b: ByteArray, o: Int, v: Int) {
        b[o] = v.toByte(); b[o + 1] = (v ushr 8).toByte(); b[o + 2] = (v ushr 16).toByte(); b[o + 3] = (v ushr 24).toByte()
    }

    companion object {
        const val NC = 306
        const val DC = 64
        const val LDC = 16
        const val RC = 44
        const val TABLE_SIZE = NC + DC + LDC + RC
        const val FILTER_DELTA = 0
        const val FILTER_E8 = 1
        const val FILTER_E8E9 = 2
        const val FILTER_ARM = 3
    }
}
