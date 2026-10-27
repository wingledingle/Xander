package app.quinten.formats.doc

import app.quinten.formats.UnsupportedBookException
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Minimal read-only reader for OLE2 Compound File Binary containers (used by .doc). */
class CompoundFile(private val data: ByteArray) {
    private val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
    private val sectorSize: Int
    private val miniSectorSize: Int
    private val miniCutoff: Int
    private val fat: IntArray
    private val miniFat: IntArray
    private val entries = ArrayList<Entry>()
    private var miniStream: ByteArray = ByteArray(0)

    class Entry(val name: String, val type: Int, val start: Int, val size: Long)

    init {
        if (data.size < 512 || buf.getLong(0) != 0xE11AB1A1E011CFD0uL.toLong()) throw UnsupportedBookException("Not an OLE2 document")
        sectorSize = 1 shl buf.getShort(0x1E).toInt()
        miniSectorSize = 1 shl buf.getShort(0x20).toInt()
        val numFat = buf.getInt(0x2C)
        val firstDir = buf.getInt(0x30)
        miniCutoff = buf.getInt(0x38)
        val firstMiniFat = buf.getInt(0x3C)
        var difatSector = buf.getInt(0x44)
        val numDifat = buf.getInt(0x48)
        val fatSectors = ArrayList<Int>()
        for (i in 0 until 109) {
            val s = buf.getInt(0x4C + i * 4)
            if (s >= 0) fatSectors += s
        }
        var guard = 0
        while (difatSector >= 0 && guard++ < numDifat + 1 && fatSectors.size < numFat) {
            val off = offset(difatSector)
            val per = sectorSize / 4 - 1
            for (i in 0 until per) {
                val s = buf.getInt(off + i * 4)
                if (s >= 0) fatSectors += s
            }
            difatSector = buf.getInt(off + per * 4)
        }
        val perSector = sectorSize / 4
        fat = IntArray(fatSectors.size * perSector)
        for ((k, s) in fatSectors.withIndex()) {
            val off = offset(s)
            if (off + sectorSize > data.size) break
            for (i in 0 until perSector) fat[k * perSector + i] = buf.getInt(off + i * 4)
        }
        val mf = if (firstMiniFat >= 0) readChain(firstMiniFat, -1) else ByteArray(0)
        val mfb = ByteBuffer.wrap(mf).order(ByteOrder.LITTLE_ENDIAN)
        miniFat = IntArray(mf.size / 4) { mfb.getInt(it * 4) }
        val dir = readChain(firstDir, -1)
        val db = ByteBuffer.wrap(dir).order(ByteOrder.LITTLE_ENDIAN)
        var i = 0
        while (i + 128 <= dir.size) {
            val nameLen = db.getShort(i + 0x40).toInt()
            val chars = (nameLen / 2 - 1).coerceIn(0, 31)
            val name = String(CharArray(chars) { db.getChar(i + it * 2) })
            val type = dir[i + 0x42].toInt()
            val start = db.getInt(i + 0x74)
            val size = db.getInt(i + 0x78).toLong() and 0xFFFFFFFFL
            entries += Entry(name, type, start, size)
            i += 128
        }
        val root = entries.firstOrNull { it.type == 5 }
        if (root != null && root.start >= 0) miniStream = readChain(root.start, root.size)
    }

    private fun offset(sector: Int) = (sector + 1) * sectorSize

    private fun readChain(start: Int, size: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var s = start
        // A corrupt FAT can loop: never visit a sector twice.
        val visited = java.util.BitSet()
        while (s >= 0 && !visited[s]) {
            visited.set(s)
            val off = offset(s)
            if (off < 0 || off >= data.size) break
            out.write(data, off, minOf(sectorSize, data.size - off))
            if (size >= 0 && out.size() >= size) break
            if (s >= fat.size) break
            s = fat[s]
        }
        val bytes = out.toByteArray()
        return if (size >= 0 && bytes.size > size) bytes.copyOf(size.toInt()) else bytes
    }

    private fun readMiniChain(start: Int, size: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var s = start
        val visited = java.util.BitSet()
        while (s >= 0 && !visited[s]) {
            visited.set(s)
            val off = s * miniSectorSize

            if (off < 0 || off >= miniStream.size) break
            out.write(miniStream, off, minOf(miniSectorSize, miniStream.size - off))
            if (out.size() >= size) break
            if (s >= miniFat.size) break
            s = miniFat[s]
        }
        return out.toByteArray().copyOf(size.toInt().coerceAtMost(out.size()))
    }

    fun stream(name: String): ByteArray? {
        val e = entries.firstOrNull { it.type == 2 && it.name == name } ?: return null
        return if (e.size < miniCutoff) readMiniChain(e.start, e.size) else readChain(e.start, e.size)
    }

    fun names(): List<String> = entries.map { it.name }

    companion object {
        fun open(file: File): CompoundFile = CompoundFile(RandomAccessFile(file, "r").use { r ->
            ByteArray(r.length().toInt()).also { r.readFully(it) }
        })
    }
}
