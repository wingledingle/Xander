package app.quinten.formats.rar

import app.quinten.formats.UnsupportedBookException
import java.util.zip.CRC32

/**
 * RAR 2.9 / 3.x / 4.x LZ decoder including the standard RarVM filters (E8, E8E9, DELTA, RGB,
 * AUDIO), recognised by code checksum. PPMd-compressed blocks are not supported.
 */
internal class Unpack29(dictSize: Int = 0x400000) {
    private val win = Window(dictSize)
    val windowSize: Int get() = win.size
    private val ld = HuffTable(NC)
    private val dd = HuffTable(DC)
    private val ldd = HuffTable(LDC)
    private val rd = HuffTable(RC)
    private val oldTable = IntArray(TABLE_SIZE)
    private val table = IntArray(TABLE_SIZE)
    private var tablesRead = false
    private val oldDist = IntArray(4)
    private var lastLength = 0
    private var prevLowDist = 0
    private var lowDistRepCount = 0

    // Filters
    private class FilterProgram(val type: Int)
    private class StackFilter(val prog: FilterProgram?, val start: Int, val length: Int, val r: IntArray)
    private val programs = ArrayList<FilterProgram?>()
    private val oldFilterLengths = ArrayList<Int>()
    private var lastFilter = 0

    fun unpack(input: ByteArray, outSize: Int, solidContinue: Boolean): ByteArray {
        if (!solidContinue) {
            // A new stream never refers to earlier data (the decoder may be reused).
            win.ptr = 0; win.total = 0
            oldDist.fill(0)
; lastLength = 0; prevLowDist = 0; lowDistRepCount = 0
            oldTable.fill(0); tablesRead = false
            programs.clear(); oldFilterLengths.clear(); lastFilter = 0
        }
        val out = ByteArray(outSize)
        var outPos = 0
        val inp = BitInput(input)
        val pending = ArrayList<StackFilter>()

        fun put(b: Int) {
            win.data[win.ptr] = b.toByte()
            win.ptr = (win.ptr + 1) and win.mask
            win.total++
            if (outPos < out.size) out[outPos] = b.toByte()
            outPos++
        }

        fun copy(length: Int, distance: Int) {
            if (distance <= 0 || distance > win.total || distance > win.size) { repeat(length) { put(0) }; return }
            var src = (win.ptr - distance) and win.mask
            repeat(length) { put(win.data[src].toInt()); src = (src + 1) and win.mask }
        }

        fun readTables(): Boolean {
            inp.alignByte()
            val bitField = inp.getbits()
            if (bitField and 0x8000 != 0) throw UnsupportedBookException("PPMd-compressed RAR archives are not supported")
            prevLowDist = 0; lowDistRepCount = 0
            if (bitField and 0x4000 == 0) oldTable.fill(0)
            inp.addbits(2)
            if (!readCodeLengths(inp, table, TABLE_SIZE, oldTable)) return false
            ld.build(table, 0, NC)
            dd.build(table, NC, DC)
            ldd.build(table, NC + DC, LDC)
            rd.build(table, NC + DC + LDC, RC)
            System.arraycopy(table, 0, oldTable, 0, TABLE_SIZE)
            tablesRead = true
            return true
        }

        /** Returns false at end of file. */
        fun readEndOfBlock(): Boolean {
            val bitField = inp.getbits()
            val newTable: Boolean
            var newFile = false
            if (bitField and 0x8000 != 0) { newTable = true; inp.addbits(1) } else { newFile = true; newTable = bitField and 0x4000 != 0; inp.addbits(2) }
            tablesRead = !newTable
            if (newFile) return false
            return !newTable || readTables()
        }

        fun readVmCode(): Boolean {
            val first = inp.getbits() ushr 8; inp.addbits(8)
            var length = (first and 7) + 1
            if (length == 7) { length = (inp.getbits() ushr 8) + 7; inp.addbits(8) } else if (length == 8) { length = inp.getbits(); inp.addbits(16) }
            val code = ByteArray(length)
            for (i in 0 until length) { if (inp.overflow(3)) return false; code[i] = (inp.getbits() ushr 8).toByte(); inp.addbits(8) }
            addVmCode(first, code, outPos)?.let { pending += it }
            return true
        }

        if (!solidContinue || !tablesRead) if (!readTables()) throw RarCorruptException("bad tables")

        while (outPos < outSize) {
            if (inp.inAddr > input.size + 8) break
            val number = ld.decode(inp)
            when {
                number < 256 -> put(number)
                number >= 271 -> {
                    val n = number - 271
                    var length = LDECODE[n] + 3
                    val lb = LBITS[n]
                    if (lb > 0) { length += inp.getbits() ushr (16 - lb); inp.addbits(lb) }
                    val distNumber = dd.decode(inp)
                    var distance = DDECODE[distNumber] + 1
                    val bits = DBITS[distNumber]
                    if (bits > 0) {
                        if (distNumber > 9) {
                            if (bits > 4) { distance += ((inp.getbits() ushr (20 - bits)) shl 4); inp.addbits(bits - 4) }
                            if (lowDistRepCount > 0) { lowDistRepCount--; distance += prevLowDist }
                            else {
                                val lowDist = ldd.decode(inp)
                                if (lowDist == 16) { lowDistRepCount = 15; distance += prevLowDist } else { distance += lowDist; prevLowDist = lowDist }
                            }
                        } else { distance += inp.getbits() ushr (16 - bits); inp.addbits(bits) }
                    }
                    if (distance >= 0x2000) { length++; if (distance >= 0x40000) length++ }
                    insertOldDist(distance)
                    lastLength = length
                    copy(length, distance)
                }
                number == 256 -> if (!readEndOfBlock()) break
                number == 257 -> if (!readVmCode()) break
                number == 258 -> if (lastLength != 0) copy(lastLength, oldDist[0])
                number < 263 -> {
                    val distNum = number - 259
                    val distance = oldDist[distNum]
                    for (i in distNum downTo 1) oldDist[i] = oldDist[i - 1]
                    oldDist[0] = distance
                    val ln = rd.decode(inp)
                    var length = LDECODE[ln] + 2
                    val lb = LBITS[ln]
                    if (lb > 0) { length += inp.getbits() ushr (16 - lb); inp.addbits(lb) }
                    lastLength = length
                    copy(length, distance)
                }
                else -> { // 263..270
                    val n = number - 263
                    var distance = SDDECODE[n] + 1
                    val bits = SDBITS[n]
                    if (bits > 0) { distance += inp.getbits() ushr (16 - bits); inp.addbits(bits) }
                    insertOldDist(distance)
                    lastLength = 2
                    copy(2, distance)
                }
            }
        }
        for (f in pending) applyFilter(out, f)
        return out
    }

    private fun insertOldDist(d: Int) {
        oldDist[3] = oldDist[2]; oldDist[2] = oldDist[1]; oldDist[1] = oldDist[0]; oldDist[0] = d
    }

    private fun readData(inp: BitInput): Int {
        var data = inp.getbits()
        return when (data and 0xC000) {
            0 -> { inp.addbits(6); (data ushr 10) and 0xF }
            0x4000 -> if (data and 0x3C00 == 0) { data = -0x100 or ((data ushr 2) and 0xFF); inp.addbits(14); data } else { data = (data ushr 6) and 0xFF; inp.addbits(10); data }
            0x8000 -> { inp.addbits(2); data = inp.getbits(); inp.addbits(16); data }
            else -> { inp.addbits(2); data = inp.getbits() shl 16; inp.addbits(16); data = data or inp.getbits(); inp.addbits(16); data }
        }
    }

    private fun addVmCode(first: Int, code: ByteArray, outPos: Int): StackFilter? {
        val inp = BitInput(code)
        var filtPos: Int
        if (first and 0x80 != 0) {
            filtPos = readData(inp)
            if (filtPos == 0) { programs.clear(); oldFilterLengths.clear() } else filtPos--
        } else filtPos = lastFilter
        if (filtPos > programs.size || filtPos > oldFilterLengths.size) return null
        lastFilter = filtPos
        val newFilter = filtPos == programs.size
        if (newFilter) { if (filtPos > 1024) return null; programs += null; oldFilterLengths += 0 }
        var blockStart = readData(inp)
        if (first and 0x40 != 0) blockStart += 258
        val blockLength = if (first and 0x20 != 0) readData(inp).also { oldFilterLengths[filtPos] = it } else oldFilterLengths.getOrElse(filtPos) { 0 }
        val r = IntArray(7)
        r[4] = blockLength
        if (first and 0x10 != 0) {
            val mask = inp.getbits() ushr 9; inp.addbits(7)
            for (i in 0 until 7) if (mask and (1 shl i) != 0) r[i] = readData(inp)
        }
        if (newFilter) {
            val size = readData(inp)
            if (size <= 0 || size >= 0x10000) return null
            val vm = ByteArray(size)
            for (i in 0 until size) { vm[i] = (inp.getbits() ushr 8).toByte(); inp.addbits(8) }
            programs[filtPos] = FilterProgram(identify(vm))
        }
        // Global data (ignored by standard filters) follows when bit 0x08 is set.
        return StackFilter(programs[filtPos], outPos + blockStart, blockLength, r)
    }

    private fun identify(code: ByteArray): Int {
        var xor = 0
        for (i in 1 until code.size) xor = xor xor (code[i].toInt() and 0xFF)
        if (xor != (code[0].toInt() and 0xFF)) return F_NONE
        val crc = CRC32().apply { update(code) }.value.toInt()
        return when {
            code.size == 53 && crc == 0xad576887.toInt() -> F_E8
            code.size == 57 && crc == 0x3cd7e57e -> F_E8E9
            code.size == 29 && crc == 0x0e06077d -> F_DELTA
            code.size == 149 && crc == 0x1c2c5dc8 -> F_RGB
            code.size == 216 && crc == 0xbc85e701.toInt() -> F_AUDIO
            code.size == 120 && crc == 0x3769893f -> F_ITANIUM
            else -> F_NONE
        }
    }

    private fun applyFilter(out: ByteArray, f: StackFilter) {
        val type = f.prog?.type ?: return
        val start = f.start
        val size = f.r[4]
        if (start < 0 || size <= 0 || start + size > out.size) return
        val src = out.copyOfRange(start, start + size)
        val dst: ByteArray = when (type) {
            F_E8, F_E8E9 -> {
                val fileSize = 0x1000000
                var cur = 0
                while (cur < size - 4) {
                    val b = src[cur++].toInt() and 0xFF
                    if (b == 0xE8 || (type == F_E8E9 && b == 0xE9)) {
                        val offset = cur + start
                        val addr = le32(src, cur)
                        if (addr < 0) { if ((addr + offset) >= 0) put32(src, cur, addr + fileSize) }
                        else if ((addr - fileSize) < 0) put32(src, cur, addr - offset)
                        cur += 4
                    }
                }
                src
            }
            F_DELTA -> {
                val channels = f.r[0]
                if (channels <= 0) return
                val d = ByteArray(size)
                var sp = 0
                for (ch in 0 until channels) {
                    var prev = 0
                    var dp = ch
                    while (dp < size) { prev = (prev - src[sp++]) and 0xFF; d[dp] = prev.toByte(); dp += channels }
                }
                d
            }
            F_RGB -> {
                val width = f.r[0] - 3
                val posR = f.r[1]
                if (size < 3 || width > size || posR > 2 || width < 0) return
                val d = ByteArray(size)
                var sp = 0
                for (ch in 0 until 3) {
                    var prev = 0
                    var i = ch
                    while (i < size) {
                        var predicted: Int
                        if (i >= width + 3) {
                            val upper = d[i - width].toInt() and 0xFF
                            val upperLeft = d[i - width - 3].toInt() and 0xFF
                            predicted = prev + upper - upperLeft
                            val pa = kotlin.math.abs(predicted - prev)
                            val pb = kotlin.math.abs(predicted - upper)
                            val pc = kotlin.math.abs(predicted - upperLeft)
                            predicted = if (pa <= pb && pa <= pc) prev else if (pb <= pc) upper else upperLeft
                        } else predicted = prev
                        prev = (predicted - src[sp++]) and 0xFF
                        d[i] = prev.toByte()
                        i += 3
                    }
                }
                var i = posR
                while (i < size - 2) {
                    val g = d[i + 1]
                    d[i] = (d[i] + g).toByte(); d[i + 2] = (d[i + 2] + g).toByte()
                    i += 3
                }
                d
            }
            F_AUDIO -> {
                val channels = f.r[0]
                if (channels <= 0) return
                val d = ByteArray(size)
                var sp = 0
                for (ch in 0 until channels) {
                    var prevByte = 0; var prevDelta = 0
                    val dif = IntArray(7)
                    var d1 = 0; var d2 = 0; var d3: Int
                    var k1 = 0; var k2 = 0; var k3 = 0
                    var i = ch; var count = 0
                    while (i < size) {
                        d3 = d2; d2 = prevDelta - d1; d1 = prevDelta
                        var predicted = 8 * prevByte + k1 * d1 + k2 * d2 + k3 * d3
                        predicted = (predicted ushr 3) and 0xFF
                        val curByte = src[sp++].toInt() and 0xFF
                        predicted = (predicted - curByte) and 0xFF
                        d[i] = predicted.toByte()
                        prevDelta = (predicted - prevByte).toByte().toInt()
                        prevByte = predicted
                        val dd0 = curByte.toByte().toInt() shl 3
                        dif[0] += kotlin.math.abs(dd0); dif[1] += kotlin.math.abs(dd0 - d1); dif[2] += kotlin.math.abs(dd0 + d1)
                        dif[3] += kotlin.math.abs(dd0 - d2); dif[4] += kotlin.math.abs(dd0 + d2); dif[5] += kotlin.math.abs(dd0 - d3); dif[6] += kotlin.math.abs(dd0 + d3)
                        if (count and 0x1F == 0) {
                            var minDif = dif[0]; var numMin = 0; dif[0] = 0
                            for (j in 1 until 7) { if (dif[j] < minDif) { minDif = dif[j]; numMin = j }; dif[j] = 0 }
                            when (numMin) {
                                1 -> if (k1 >= -16) k1--; 2 -> if (k1 < 16) k1++
                                3 -> if (k2 >= -16) k2--; 4 -> if (k2 < 16) k2++
                                5 -> if (k3 >= -16) k3--; 6 -> if (k3 < 16) k3++
                            }
                        }
                        i += channels; count++
                    }
                }
                d
            }
            else -> return
        }
        System.arraycopy(dst, 0, out, start, size)
    }

    private fun le32(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
        ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    private fun put32(b: ByteArray, o: Int, v: Int) {
        b[o] = v.toByte(); b[o + 1] = (v ushr 8).toByte(); b[o + 2] = (v ushr 16).toByte(); b[o + 3] = (v ushr 24).toByte()
    }

    companion object {
        const val NC = 299
        const val DC = 60
        const val LDC = 17
        const val RC = 28
        const val TABLE_SIZE = NC + DC + LDC + RC
        const val F_NONE = 0
        const val F_E8 = 1
        const val F_E8E9 = 2
        const val F_DELTA = 3
        const val F_RGB = 4
        const val F_AUDIO = 5
        const val F_ITANIUM = 6

        val LDECODE = intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 10, 12, 14, 16, 20, 24, 28, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224)
        val LBITS = intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5)
        val SDDECODE = intArrayOf(0, 4, 8, 16, 32, 64, 128, 192)
        val SDBITS = intArrayOf(2, 2, 3, 4, 5, 6, 6, 6)
        val DDECODE = IntArray(DC)
        val DBITS = IntArray(DC)

        init {
            val counts = intArrayOf(4, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 14, 0, 12)
            var dist = 0; var slot = 0
            for ((bitLength, c) in counts.withIndex()) {
                repeat(c) {
                    if (slot < DC) { DDECODE[slot] = dist; DBITS[slot] = bitLength }
                    slot++; dist += 1 shl bitLength
                }
            }
        }
    }
}
