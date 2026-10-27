package app.quinten.formats.rar

/** MSB-first bit reader over a byte array; reads past the end return zero bits. */
internal class BitInput(private val buf: ByteArray) {
    var inAddr = 0
    var inBit = 0

    private fun b(i: Int): Int = if (i < buf.size) buf[i].toInt() and 0xFF else 0

    /** Next 16 bits without consuming them. */
    fun getbits(): Int {
        val v = (b(inAddr) shl 16) or (b(inAddr + 1) shl 8) or b(inAddr + 2)
        return (v ushr (8 - inBit)) and 0xFFFF
    }

    /** Next 32 bits without consuming them. */
    fun getbits32(): Int {
        var v = (b(inAddr) shl 24) or (b(inAddr + 1) shl 16) or (b(inAddr + 2) shl 8) or b(inAddr + 3)
        v = v shl inBit
        v = v or (b(inAddr + 4) ushr (8 - inBit))
        return v
    }

    fun addbits(n: Int) {
        val bits = inBit + n
        inAddr += bits ushr 3
        inBit = bits and 7
    }

    fun alignByte() {
        if (inBit != 0) addbits(8 - inBit)
    }

    val size get() = buf.size
    fun overflow(extraBytes: Int) = inAddr + extraBytes >= buf.size
}

/** Canonical Huffman decoder in the style used by RAR (max code length 15). */
internal class HuffTable(val maxNum: Int) {
    private val decodeLen = IntArray(16)
    private val decodePos = IntArray(16)
    private val decodeNum = IntArray(maxNum)

    fun build(lengths: IntArray, offset: Int, size: Int) {
        val count = IntArray(16)
        for (i in 0 until size) count[lengths[offset + i] and 0xF]++
        count[0] = 0
        decodeNum.fill(0)
        decodePos[0] = 0
        decodeLen[0] = 0
        var upper = 0
        for (i in 1 until 16) {
            upper += count[i]
            val left = upper shl (16 - i)
            upper *= 2
            decodeLen[i] = left
            decodePos[i] = decodePos[i - 1] + count[i - 1]
        }
        val copy = decodePos.copyOf()
        for (i in 0 until size) {
            val l = lengths[offset + i] and 0xF
            if (l != 0) {
                val p = copy[l]++
                if (p < maxNum) decodeNum[p] = i
            }
        }
    }

    fun decode(inp: BitInput): Int {
        val bitField = inp.getbits() and 0xFFFE
        var bits = 15
        for (i in 1 until 15) if (bitField < decodeLen[i]) { bits = i; break }
        inp.addbits(bits)
        var dist = bitField - decodeLen[bits - 1]
        dist = dist ushr (16 - bits)
        var pos = decodePos[bits] + dist
        if (pos >= maxNum || pos < 0) pos = 0
        return decodeNum[pos]
    }
}

/** Reads the bit-length table and main code lengths shared by RAR 2.9 and RAR 5. */
internal fun readCodeLengths(inp: BitInput, table: IntArray, size: Int, old: IntArray?): Boolean {
    val bitLength = IntArray(20)
    var i = 0
    while (i < 20) {
        val len = inp.getbits() ushr 12
        inp.addbits(4)
        if (len == 15) {
            var zero = inp.getbits() ushr 12
            inp.addbits(4)
            if (zero == 0) bitLength[i++] = 15
            else {
                zero += 2
                while (zero-- > 0 && i < 20) bitLength[i++] = 0
            }
        } else bitLength[i++] = len
    }
    val bc = HuffTable(20)
    bc.build(bitLength, 0, 20)
    i = 0
    while (i < size) {
        if (inp.overflow(0)) return false
        val number = bc.decode(inp)
        when {
            number < 16 -> { table[i] = if (old != null) (number + old[i]) and 0xF else number; i++ }
            number < 18 -> {
                val n: Int
                if (number == 16) { n = (inp.getbits() ushr 13) + 3; inp.addbits(3) } else { n = (inp.getbits() ushr 9) + 11; inp.addbits(7) }
                if (i == 0) return false
                repeat(n) { if (i < size) { table[i] = table[i - 1]; i++ } }
            }
            else -> {
                val n: Int
                if (number == 18) { n = (inp.getbits() ushr 13) + 3; inp.addbits(3) } else { n = (inp.getbits() ushr 9) + 11; inp.addbits(7) }
                repeat(n) { if (i < size) table[i++] = 0 }
            }
        }
    }
    return true
}

/** Circular LZ window shared by solid streams. */
internal class Window(sizeHint: Int) {
    val size: Int = Integer.highestOneBit(maxOf(sizeHint, 0x40000) - 1) shl 1
    val mask = size - 1
    val data = ByteArray(size)
    var ptr = 0
    var total = 0L // bytes ever written (for distance validation)
}

internal class RarCorruptException(msg: String) : Exception(msg)
