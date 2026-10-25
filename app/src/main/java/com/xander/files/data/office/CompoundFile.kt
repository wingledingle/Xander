package com.xander.files.data.office

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Minimal reader for OLE2 / Compound File Binary containers (legacy .doc, .xls, .ppt). */
class CompoundFile(file: File) : AutoCloseable {
    private val raf = RandomAccessFile(file, "r")
    private val sectorSize: Int
    private val miniSectorSize: Int
    private val miniCutoff: Long
    private val fat: IntArray
    private val miniFat: IntArray
    private val entries: List<Entry>
    private val miniStream: ByteArray

    data class Entry(val name: String, val type: Int, val start: Int, val size: Long, val child: Int, val left: Int, val right: Int)

    init {
        val h = ByteArray(512).also { raf.readFully(it) }
        val hb = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
        if (hb.getLong(0) != 0xE11AB1A1E011CFD0uL.toLong()) throw IOException("Not an OLE2 compound file")
        sectorSize = 1 shl hb.getShort(0x1E).toInt()
        miniSectorSize = 1 shl hb.getShort(0x20).toInt()
        val fatSectors = hb.getInt(0x2C)
        val firstDir = hb.getInt(0x30)
        miniCutoff = hb.getInt(0x38).toLong() and 0xFFFFFFFFL
        val firstMiniFat = hb.getInt(0x3C)
        val miniFatCount = hb.getInt(0x40)
        var difatSector = hb.getInt(0x44)
        // FAT sector locations: 109 in the header, the rest in DIFAT sectors.
        val fatLocations = ArrayList<Int>()
        for (i in 0 until 109) { val s = hb.getInt(0x4C + i * 4); if (s >= 0) fatLocations += s }
        var guard = 0
        while (difatSector >= 0 && fatLocations.size < fatSectors && guard++ < 100_000) {
            val b = sector(difatSector)
            for (i in 0 until sectorSize / 4 - 1) { val s = b.getInt(i * 4); if (s >= 0) fatLocations += s }
            difatSector = b.getInt(sectorSize - 4)
        }
        val fatList = IntArray(fatLocations.size * sectorSize / 4)
        fatLocations.forEachIndexed { i, s -> val b = sector(s); for (j in 0 until sectorSize / 4) fatList[i * sectorSize / 4 + j] = b.getInt(j * 4) }
        fat = fatList
        val dirBytes = chain(firstDir, -1)
        val db = ByteBuffer.wrap(dirBytes).order(ByteOrder.LITTLE_ENDIAN)
        entries = (0 until dirBytes.size / 128).map { i ->
            val o = i * 128
            val nameLen = (db.getShort(o + 0x40).toInt() and 0xFFFF).coerceIn(0, 64)
            val name = String(dirBytes, o, (nameLen - 2).coerceAtLeast(0), Charsets.UTF_16LE)
            Entry(name, dirBytes[o + 0x42].toInt(), db.getInt(o + 0x74), db.getLong(o + 0x78) and 0xFFFFFFFFL, db.getInt(o + 0x4C), db.getInt(o + 0x44), db.getInt(o + 0x48))
        }
        miniFat = if (miniFatCount > 0) chain(firstMiniFat, -1).let { b -> val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN); IntArray(b.size / 4) { bb.getInt(it * 4) } } else IntArray(0)
        val root = entries.firstOrNull { it.type == 5 }
        miniStream = if (root != null && root.start >= 0) chain(root.start, root.size) else ByteArray(0)
    }

    private fun sector(n: Int): ByteBuffer {
        val b = ByteArray(sectorSize)
        raf.seek((n.toLong() + 1) * sectorSize)
        raf.readFully(b)
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
    }

    private fun chain(start: Int, size: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var s = start; var guard = 0
        while (s >= 0 && guard++ < 10_000_000) {
            out.write(sector(s).array())
            if (size >= 0 && out.size() >= size) break
            s = if (s < fat.size) fat[s] else -2
        }
        val bytes = out.toByteArray()
        return if (size in 0 until bytes.size) bytes.copyOf(size.toInt()) else bytes
    }

    private fun miniChain(start: Int, size: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var s = start; var guard = 0
        while (s >= 0 && guard++ < 10_000_000 && out.size() < size) {
            val off = s * miniSectorSize
            if (off + miniSectorSize > miniStream.size) break
            out.write(miniStream, off, miniSectorSize)
            s = if (s < miniFat.size) miniFat[s] else -2
        }
        return out.toByteArray().copyOf(size.toInt().coerceAtMost(out.size()))
    }

    fun names(): List<String> = entries.filter { it.type == 2 }.map { it.name }

    fun stream(name: String): ByteArray? {
        val e = entries.firstOrNull { it.type == 2 && it.name.equals(name, ignoreCase = true) } ?: return null
        return if (e.size < miniCutoff) miniChain(e.start, e.size) else chain(e.start, e.size)
    }

    override fun close() = raf.close()
}
