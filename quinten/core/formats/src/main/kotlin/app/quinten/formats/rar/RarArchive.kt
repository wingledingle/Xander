package app.quinten.formats.rar

import app.quinten.formats.UnsupportedBookException
import app.quinten.formats.comic.Archive
import java.io.File
import java.io.RandomAccessFile

/**
 * Read-only RAR (versions 2.9/4.x and 5.x) archive reader written from the public format
 * description. Supports stored entries and LZ-compressed entries (see [Unpack29], [Unpack50]),
 * including solid archives. Encrypted and multi-volume archives are rejected.
 */
class RarArchive(private val file: File) : Archive {
    class Entry(
        val name: String,
        val packedOffset: Long,
        val packedSize: Long,
        val unpackedSize: Long,
        val method: Int, // 0 = stored
        val solid: Boolean,
        val version: Int, // 29 or 50
        val dictSize: Int,
        val isDir: Boolean,
        val crc: Long = -1,
    )

    private val raf = RandomAccessFile(file, "r")
    val entries: List<Entry>
    private val solidArchive: Boolean
    private val isRar5: Boolean

    // Solid archives must be decoded in order; keep the decoder and its position.
    private var solidDecoder: Any? = null
    private var solidNext = 0

    /** Recently decoded entries of a solid archive (LRU, bounded by bytes) so paging back is free. */
    private val solidCache = object : LinkedHashMap<String, ByteArray>(16, 0.75f, true) {
        var bytes = 0L
        override fun put(key: String, value: ByteArray): ByteArray? {
            val old = super.put(key, value)
            bytes += value.size - (old?.size ?: 0)
            val it = entries.iterator()
            while (bytes > SOLID_CACHE_BYTES && size > 1 && it.hasNext()) {
                val e = it.next()
                if (e.key == key) continue
                bytes -= e.value.size; it.remove()
            }
            return old
        }
    }

    /** A spare decoder for non-solid entries, reused while its window is big enough. */
    private var spare50: Unpack50? = null
    private var spare29: Unpack29? = null

    init {
        val sig = ByteArray(8)
        raf.readFully(sig)
        isRar5 = sig[6].toInt() == 1 && sig[7].toInt() == 0
        if (String(sig, 0, 4, Charsets.ISO_8859_1) != "Rar!") throw UnsupportedBookException("Not a RAR archive")
        val list = ArrayList<Entry>()
        var solid = false
        try {
            if (isRar5) solid = parse5(list) else solid = parse4(list)
        } catch (e: UnsupportedBookException) {
            raf.close(); throw e
        } catch (e: Exception) {
            if (list.isEmpty()) { raf.close(); throw UnsupportedBookException("Corrupt RAR archive: ${e.message}") }
        }
        entries = list
        solidArchive = solid || list.any { it.solid }
    }

    override val names: List<String> get() = entries.filter { !it.isDir }.map { it.name }

    private fun u8(): Int = raf.read().also { if (it < 0) throw java.io.EOFException() }
    private fun u16(): Int = u8() or (u8() shl 8)
    private fun u32(): Long = (u16().toLong()) or (u16().toLong() shl 16)

    private fun parse4(out: MutableList<Entry>): Boolean {
        raf.seek(7)
        var solid = false
        while (raf.filePointer + 7 <= raf.length()) {
            val start = raf.filePointer
            u16() // crc
            val type = u8()
            val flags = u16()
            val headSize = u16()
            if (headSize < 7) break
            var addSize = 0L
            when (type) {
                0x73 -> { // archive header
                    if (flags and 0x0080 != 0) throw UnsupportedBookException("Encrypted RAR archives are not supported")
                    if (flags and 0x0008 != 0) solid = true
                }
                0x74 -> {
                    var pack = u32()
                    var unp = u32()
                    u8() // host os
                    val crc = u32()
                    u32() // time
                    val ver = u8()
                    val method = u8() - 0x30
                    val nameSize = u16()
                    u32() // attr
                    if (flags and 0x100 != 0) { pack = pack or (u32() shl 32); unp = unp or (u32() shl 32) }
                    val nameBytes = ByteArray(nameSize).also { raf.readFully(it) }
                    val name = decodeName4(nameBytes, flags and 0x200 != 0).replace('\\', '/')
                    if (flags and 0x04 != 0) throw UnsupportedBookException("Encrypted RAR archives are not supported")
                    if (flags and 0x01 != 0 || flags and 0x02 != 0) throw UnsupportedBookException("Multi-volume RAR archives are not supported")
                    val dictBits = (flags ushr 5) and 7
                    out += Entry(
                        name, start + headSize, pack, unp, method, flags and 0x10 != 0,
                        if (ver >= 29) 29 else ver, 0x10000 shl dictBits, dictBits == 7, crc,
                    )
                    addSize = pack
                }
                0x7B -> break
                else -> if (flags and 0x8000 != 0) addSize = u32()
            }
            raf.seek(start + headSize + addSize)
        }
        return solid
    }

    private fun decodeName4(b: ByteArray, unicode: Boolean): String {
        if (!unicode) {
            // Non-Windows packers write UTF-8 without setting the unicode flag.
            val utf8 = try {
                Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(b)).toString()
            } catch (_: java.nio.charset.CharacterCodingException) { null }
            return utf8 ?: String(b, Charsets.ISO_8859_1)
        }
        val zero = b.indexOf(0)
        if (zero < 0) return String(b, Charsets.UTF_8)
        // RAR's compressed unicode name encoding.
        val ascii = b.copyOf(zero)
        val enc = b.copyOfRange(zero + 1, b.size)
        val out = StringBuilder()
        var ep = 0
        if (enc.isEmpty()) return String(ascii, Charsets.ISO_8859_1)
        val high = enc[ep++].toInt() and 0xFF
        var flagBits = 0
        var flags = 0
        while (ep < enc.size && out.length < 1024) {
            if (flagBits == 0) { flags = enc[ep++].toInt() and 0xFF; flagBits = 8 }
            flagBits -= 2
            when ((flags ushr flagBits) and 3) {
                0 -> { if (ep >= enc.size) break; out.append((enc[ep++].toInt() and 0xFF).toChar()) }
                1 -> { if (ep >= enc.size) break; out.append(((enc[ep++].toInt() and 0xFF) + (high shl 8)).toChar()) }
                2 -> { if (ep + 1 >= enc.size) break; out.append(((enc[ep].toInt() and 0xFF) + ((enc[ep + 1].toInt() and 0xFF) shl 8)).toChar()); ep += 2 }
                3 -> {
                    if (ep >= enc.size) break
                    var len = enc[ep++].toInt() and 0xFF
                    if (len and 0x80 != 0) {
                        if (ep >= enc.size) break
                        val correction = enc[ep++].toInt() and 0xFF
                        len = (len and 0x7F) + 2
                        while (len-- > 0 && out.length < ascii.size) out.append(((((ascii[out.length].toInt() and 0xFF) + correction) and 0xFF) + (high shl 8)).toChar())
                    } else {
                        len += 2
                        while (len-- > 0 && out.length < ascii.size) out.append((ascii[out.length].toInt() and 0xFF).toChar())
                    }
                }
            }
        }
        return out.toString()
    }

    private fun vint(): Long {
        var result = 0L
        var shift = 0
        while (shift < 64) {
            val b = u8()
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) break
            shift += 7
        }
        return result
    }

    private fun parse5(out: MutableList<Entry>): Boolean {
        raf.seek(8)
        var solid = false
        while (raf.filePointer + 7 <= raf.length()) {
            u32() // crc
            val headSize = vint()
            val headStart = raf.filePointer
            val type = vint().toInt()
            val flags = vint().toInt()
            val extraSize = if (flags and 1 != 0) vint() else 0
            val dataSize = if (flags and 2 != 0) vint() else 0
            when (type) {
                1 -> {
                    val aflags = vint().toInt()
                    if (aflags and 0x4 != 0) solid = true
                    if (aflags and 0x1 != 0) throw UnsupportedBookException("Multi-volume RAR archives are not supported")
                }
                2 -> {
                    val fflags = vint().toInt()
                    val unp = vint()
                    vint() // attributes
                    if (fflags and 0x2 != 0) u32()
                    val crc = if (fflags and 0x4 != 0) u32() else -1L
                    val comp = vint().toInt()
                    vint() // host os
                    val nameLen = vint().toInt()
                    val name = ByteArray(nameLen).also { raf.readFully(it) }.toString(Charsets.UTF_8)
                    // Look for an encryption record in the extra area.
                    val extraStart = headStart + headSize - extraSize
                    if (extraSize > 0) {
                        raf.seek(extraStart)
                        val end = extraStart + extraSize
                        while (raf.filePointer < end) {
                            val sz = vint()
                            val recStart = raf.filePointer
                            val rt = vint().toInt()
                            if (rt == 1) throw UnsupportedBookException("Encrypted RAR archives are not supported")
                            raf.seek(recStart + sz)
                        }
                    }
                    if (flags and 0x8 != 0 || flags and 0x10 != 0) throw UnsupportedBookException("Multi-volume RAR archives are not supported")
                    val method = (comp ushr 7) and 7
                    val dictBits = (comp ushr 10) and 0xF
                    out += Entry(
                        name, headStart + headSize, dataSize, unp, method, comp and 0x40 != 0, 50,
                        (0x20000L shl dictBits).coerceAtMost(1L shl 30).toInt(), fflags and 1 != 0, crc,
                    )
                }
                4 -> throw UnsupportedBookException("Encrypted RAR archives are not supported")
                5 -> break
            }
            raf.seek(headStart + headSize + dataSize)
        }
        return solid
    }

    private fun packed(e: Entry): ByteArray = synchronized(raf) {
        raf.seek(e.packedOffset)
        ByteArray(e.packedSize.toInt()).also { raf.readFully(it) }
    }

    override fun read(name: String): ByteArray? {
        val idx = entries.indexOfFirst { it.name == name }
        if (idx < 0) return null
        val e = entries[idx]
        if (e.method == 0) return packed(e)
        if (!solidArchive) return decodeSingle(e)
        synchronized(this) {
            solidCache[name]?.let { return it }
            if (idx < solidNext) { solidDecoder = null; solidNext = 0 }
            var result: ByteArray? = null
            while (solidNext <= idx) {
                val cur = entries[solidNext]
                val data = if (cur.method == 0 || cur.isDir) (if (cur.isDir) ByteArray(0) else packed(cur)) else decodeSolid(cur)
                if (solidNext == idx) result = data
                if (!cur.isDir) solidCache[cur.name] = data
                solidNext++
            }
            return result
        }
    }

    /**
     * Window size for an entry. LZ distances never reach before the start of the stream, so a
     * non-solid entry needs at most its own size and a solid stream at most everything before it.
     */
    private fun windowFor(dictSize: Int, streamBytes: Long): Int =
        minOf(maxOf(dictSize, 0x10000).toLong(), maxOf(streamBytes, 0x10000L)).coerceAtMost(1L shl 30).toInt()

    private val solidStreamBytes: Long by lazy { entries.sumOf { maxOf(it.unpackedSize, 0L) } }

    private fun decodeSolid(e: Entry): ByteArray {
        val input = packed(e)
        val window = windowFor(e.dictSize, solidStreamBytes)
        return when (e.version) {
            50 -> {
                val prev = solidDecoder as? Unpack50
                val d = prev?.takeIf { e.solid } ?: Unpack50(window)
                solidDecoder = d
                d.unpack(input, e.unpackedSize.toInt(), e.solid && d === prev)
            }
            29 -> {
                val prev = solidDecoder as? Unpack29
                val d = prev?.takeIf { e.solid } ?: Unpack29(window)
                solidDecoder = d
                d.unpack(input, e.unpackedSize.toInt(), e.solid && d === prev)
            }
            else -> throw UnsupportedBookException("RAR compression version ${e.version} is not supported")
        }
    }

    /** Decodes a non-solid entry, with a window no bigger than the entry (reused between calls). */
    private fun decodeSingle(e: Entry): ByteArray {
        val input = packed(e)
        val window = windowFor(e.dictSize, e.unpackedSize)
        return when (e.version) {
            50 -> {
                val d = synchronized(this) { spare50.also { spare50 = null } }?.takeIf { it.windowSize >= window } ?: Unpack50(window)
                try { d.unpack(input, e.unpackedSize.toInt(), false) } finally { synchronized(this) { if ((spare50?.windowSize ?: 0) < d.windowSize) spare50 = d } }
            }
            29 -> {
                val d = synchronized(this) { spare29.also { spare29 = null } }?.takeIf { it.windowSize >= window } ?: Unpack29(window)
                try { d.unpack(input, e.unpackedSize.toInt(), false) } finally { synchronized(this) { if ((spare29?.windowSize ?: 0) < d.windowSize) spare29 = d } }
            }
            else -> throw UnsupportedBookException("RAR compression version ${e.version} is not supported")
        }
    }

    /** Bytes held by the solid-archive page cache (for tests). */
    internal val cachedBytes: Long get() = synchronized(this) { solidCache.bytes }

    override fun close() = raf.close()

    companion object {
        const val SOLID_CACHE_BYTES = 64L * 1024 * 1024
    }
}

