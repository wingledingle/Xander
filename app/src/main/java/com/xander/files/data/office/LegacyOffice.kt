package com.xander.files.data.office

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset

/** Text extraction for binary Word 97-2003, Excel 97-2003 (BIFF8) and PowerPoint 97-2003 files. */
object LegacyOffice {

    /** Word: rebuilds the text from the piece table (CLX) of the table stream. */
    fun docText(cf: CompoundFile): List<String> {
        val word = cf.stream("WordDocument") ?: return emptyList()
        val fib = ByteBuffer.wrap(word).order(ByteOrder.LITTLE_ENDIAN)
        val flags = fib.getShort(0x0A).toInt()
        val table = cf.stream(if (flags and 0x0200 != 0) "1Table" else "0Table") ?: return fallbackText(word)
        val fcClx = fib.getInt(0x01A2); val lcbClx = fib.getInt(0x01A6)
        if (fcClx <= 0 || lcbClx <= 0 || fcClx + lcbClx > table.size) return fallbackText(word)
        val tb = ByteBuffer.wrap(table).order(ByteOrder.LITTLE_ENDIAN)
        var pos = fcClx
        while (pos < fcClx + lcbClx && table[pos].toInt() == 0x01) { val cb = tb.getShort(pos + 1).toInt() and 0xFFFF; pos += 3 + cb }
        if (pos >= table.size || table[pos].toInt() != 0x02) return fallbackText(word)
        val lcb = tb.getInt(pos + 1)
        val n = (lcb - 4) / 12
        val cps = IntArray(n + 1) { tb.getInt(pos + 5 + it * 4) }
        val sb = StringBuilder()
        val cp1252 = Charset.forName("windows-1252")
        for (i in 0 until n) {
            val pcd = pos + 5 + (n + 1) * 4 + i * 8
            var fc = tb.getInt(pcd + 2)
            val compressed = fc and 0x40000000 != 0
            val len = cps[i + 1] - cps[i]
            if (len <= 0) continue
            if (compressed) {
                fc = (fc and 0x40000000.inv()) / 2
                if (fc + len <= word.size) sb.append(String(word, fc, len, cp1252))
            } else if (fc + len * 2 <= word.size) sb.append(String(word, fc, len * 2, Charsets.UTF_16LE))
        }
        return cleanWordText(sb.toString())
    }

    private fun cleanWordText(raw: String): List<String> {
        val out = StringBuilder(); var depth = 0; var inResult = false
        for (c in raw) when (c.code) {
            0x13 -> { depth++; inResult = false }               // field begin: skip instructions
            0x14 -> inResult = true                               // field separator: keep result text
            0x15 -> { depth = (depth - 1).coerceAtLeast(0); inResult = false }
            else -> if (depth == 0 || inResult) out.append(when (c.code) { 0x07 -> '\t'; 0x0B, 0x0C -> '\n'; 0x0D -> '\n'; else -> if (c.code < 0x09) ' ' else c })
        }
        return out.toString().split('\n').map { it.trimEnd() }
    }

    private fun fallbackText(b: ByteArray): List<String> =
        String(b, Charsets.UTF_16LE).filter { it == '\n' || it == '\r' || !it.isISOControl() && it.code < 0xD800 }.split('\r', '\n').filter { it.isNotBlank() && it.count(Char::isLetter) > 2 }

    data class Sheet(val name: String, val cells: MutableMap<Pair<Int, Int>, String> = HashMap())

    /** Excel BIFF8: shared strings (with CONTINUE handling), labels, numbers, RK/MULRK, formulas' cached strings. */
    fun xlsSheets(cf: CompoundFile): List<Sheet> {
        val wb = cf.stream("Workbook") ?: cf.stream("Book") ?: return emptyList()
        val recs = ArrayList<Triple<Int, Int, Int>>() // type, offset, length
        var p = 0
        while (p + 4 <= wb.size) {
            val t = (wb[p].toInt() and 0xFF) or ((wb[p + 1].toInt() and 0xFF) shl 8)
            val l = (wb[p + 2].toInt() and 0xFF) or ((wb[p + 3].toInt() and 0xFF) shl 8)
            recs += Triple(t, p + 4, l); p += 4 + l
        }
        val sst = ArrayList<String>()
        val names = ArrayList<String>()
        val sheets = ArrayList<Sheet>()
        var current: Sheet? = null
        var bofCount = 0
        var i = 0
        while (i < recs.size) {
            val (t, off, len) = recs[i]
            val b = ByteBuffer.wrap(wb, off, len).order(ByteOrder.LITTLE_ENDIAN)
            when (t) {
                0x0809 -> { bofCount++; if (bofCount > 1) { current = Sheet(names.getOrElse(sheets.size) { "Sheet ${sheets.size + 1}" }); sheets += current } }
                0x0085 -> { // BOUNDSHEET
                    val cch = wb[off + 6].toInt() and 0xFF; val hi = wb[off + 7].toInt() and 1
                    names += if (hi == 1) String(wb, off + 8, cch * 2, Charsets.UTF_16LE) else String(wb, off + 8, cch, Charsets.ISO_8859_1)
                }
                0x00FC -> { // SST + following CONTINUE records
                    val chunks = arrayListOf(off to len)
                    var j = i + 1
                    while (j < recs.size && recs[j].first == 0x003C) { chunks += recs[j].second to recs[j].third; j++ }
                    parseSst(wb, chunks, sst)
                    i = j - 1
                }
                0x00FD -> current?.cells?.put(b.getShort(off).toInt() and 0xFFFF to (b.getShort(off + 2).toInt() and 0xFFFF), sst.getOrElse(b.getInt(off + 6)) { "" })
                0x0203 -> current?.cells?.put(b.getShort(off).toInt() and 0xFFFF to (b.getShort(off + 2).toInt() and 0xFFFF), fmt(b.getDouble(off + 6)))
                0x027E -> current?.cells?.put(b.getShort(off).toInt() and 0xFFFF to (b.getShort(off + 2).toInt() and 0xFFFF), fmt(rk(b.getInt(off + 6))))
                0x00BD -> { // MULRK
                    val row = b.getShort(off).toInt() and 0xFFFF; val first = b.getShort(off + 2).toInt() and 0xFFFF
                    val count = (len - 6) / 6
                    for (k in 0 until count) current?.cells?.put(row to first + k, fmt(rk(b.getInt(off + 4 + k * 6 + 2))))
                }
                0x0204 -> { // LABEL (BIFF5-style)
                    val row = b.getShort(off).toInt() and 0xFFFF; val col = b.getShort(off + 2).toInt() and 0xFFFF
                    val cch = b.getShort(off + 6).toInt() and 0xFFFF
                    current?.cells?.put(row to col, if (len > 8 + cch && wb[off + 8].toInt() == 1) String(wb, off + 9, cch * 2, Charsets.UTF_16LE) else String(wb, off + 8, cch.coerceAtMost(len - 8), Charsets.ISO_8859_1))
                }
                0x0006 -> { // FORMULA with numeric cached result
                    val row = b.getShort(off).toInt() and 0xFFFF; val col = b.getShort(off + 2).toInt() and 0xFFFF
                    if ((b.getShort(off + 12).toInt() and 0xFFFF) != 0xFFFF) current?.cells?.put(row to col, fmt(b.getDouble(off + 6)))
                    else if (i + 1 < recs.size && recs[i + 1].first == 0x0207) {
                        val (_, so, sl) = recs[i + 1]
                        val cch = (wb[so].toInt() and 0xFF) or ((wb[so + 1].toInt() and 0xFF) shl 8)
                        current?.cells?.put(row to col, if (wb[so + 2].toInt() and 1 == 1) String(wb, so + 3, (cch * 2).coerceAtMost(sl - 3), Charsets.UTF_16LE) else String(wb, so + 3, cch.coerceAtMost(sl - 3), Charsets.ISO_8859_1))
                    }
                }
            }
            i++
        }
        return sheets.filter { it.cells.isNotEmpty() }.ifEmpty { sheets }
    }

    private fun parseSst(wb: ByteArray, chunks: List<Pair<Int, Int>>, out: MutableList<String>) {
        var ci = 0; var pos = chunks[0].first; var end = pos + chunks[0].second
        fun ensure(n: Int) { while (pos + n > end && ci + 1 < chunks.size) { ci++; pos = chunks[ci].first; end = pos + chunks[ci].second } }
        fun u8(): Int { ensure(1); return wb[pos++].toInt() and 0xFF }
        fun u16(): Int { ensure(2); val v = (wb[pos].toInt() and 0xFF) or ((wb[pos + 1].toInt() and 0xFF) shl 8); pos += 2; return v }
        fun u32(): Int { ensure(4); val v = ByteBuffer.wrap(wb, pos, 4).order(ByteOrder.LITTLE_ENDIAN).int; pos += 4; return v }
        pos += 8 // total + unique counts
        val unique = ByteBuffer.wrap(wb, chunks[0].first + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
        repeat(unique) {
            if (pos >= end && ci + 1 >= chunks.size) return
            val cch = u16(); var flags = u8()
            val rich = flags and 0x08 != 0; val ext = flags and 0x04 != 0
            val runs = if (rich) u16() else 0
            val extLen = if (ext) u32() else 0
            val sb = StringBuilder()
            var left = cch
            while (left > 0) {
                if (pos >= end) { if (ci + 1 >= chunks.size) break; ci++; pos = chunks[ci].first; end = pos + chunks[ci].second; flags = u8() }
                val wide = flags and 1 == 1
                val avail = if (wide) (end - pos) / 2 else end - pos
                val take = minOf(avail, left)
                sb.append(if (wide) String(wb, pos, take * 2, Charsets.UTF_16LE) else String(wb, pos, take, Charsets.ISO_8859_1))
                pos += if (wide) take * 2 else take
                left -= take
            }
            var skip = runs * 4 + extLen
            while (skip > 0) { if (pos >= end) { if (ci + 1 >= chunks.size) break; ci++; pos = chunks[ci].first; end = pos + chunks[ci].second }; val k = minOf(skip, end - pos); pos += k; skip -= k }
            out += sb.toString()
        }
    }

    private fun rk(v: Int): Double {
        val d = if (v and 2 != 0) (v shr 2).toDouble() else java.lang.Double.longBitsToDouble((v.toLong() and 0xFFFFFFFCL) shl 32)
        return if (v and 1 != 0) d / 100 else d
    }

    private fun fmt(d: Double) = if (d == Math.rint(d) && kotlin.math.abs(d) < 1e15) d.toLong().toString() else d.toString()

    /** PowerPoint: text atoms grouped per slide (SlidePersistAtom boundaries in SlideListWithText). */
    fun pptSlides(cf: CompoundFile): List<List<String>> {
        val doc = cf.stream("PowerPoint Document") ?: return emptyList()
        val slides = ArrayList<MutableList<String>>()
        val loose = ArrayList<String>()
        fun walk(start: Int, end: Int, inList: Boolean) {
            var p = start
            while (p + 8 <= end) {
                val verInst = (doc[p].toInt() and 0xFF) or ((doc[p + 1].toInt() and 0xFF) shl 8)
                val type = (doc[p + 2].toInt() and 0xFF) or ((doc[p + 3].toInt() and 0xFF) shl 8)
                val len = ByteBuffer.wrap(doc, p + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
                if (len < 0 || p + 8 + len > end) break
                val body = p + 8
                when {
                    verInst and 0x0F == 0x0F -> walk(body, body + len, inList || type == 0x0FF0)
                    type == 0x03F3 && inList -> slides.add(ArrayList())
                    type == 0x0FA0 -> String(doc, body, len, Charsets.UTF_16LE).let { (if (inList && slides.isNotEmpty()) slides.last() else loose).add(it) }
                    type == 0x0FA8 -> String(doc, body, len, Charsets.ISO_8859_1).let { (if (inList && slides.isNotEmpty()) slides.last() else loose).add(it) }
                }
                p = body + len
            }
        }
        walk(0, doc.size, false)
        return slides.filter { it.isNotEmpty() }.ifEmpty { if (loose.isEmpty()) emptyList() else listOf(loose) }
    }
}
