package app.quinten.formats.mobi

import app.quinten.formats.DrmProtectedException
import app.quinten.formats.InMemoryDocument
import app.quinten.formats.UnsupportedBookException
import app.quinten.formats.html.HtmlConverter
import app.quinten.formats.html.MiniCss
import app.quinten.formats.mobi.HuffCdic.Companion.be16
import app.quinten.formats.mobi.HuffCdic.Companion.be32
import app.quinten.formats.util.ChapterSplitter
import app.quinten.formats.util.FileTitle
import app.quinten.model.Block
import app.quinten.model.BookFormat
import app.quinten.model.BookMetadata
import app.quinten.model.ImageData
import app.quinten.model.LinkTarget
import app.quinten.model.TocEntry
import org.jsoup.Jsoup
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.Charset

/**
 * Mobipocket (MOBI 6) and Kindle Format 8 (AZW3/KF8) reader for DRM-free files.
 * KF8 content is reassembled from the skeleton and fragment indexes; for combined
 * MOBI+KF8 files the KF8 part is preferred.
 */
object MobiParser {

    /**
     * Palm database records. Either over bytes in memory, or over a file: then only the record
     * offset table is kept and records are read on demand (the book is not held in memory).
     */
    class Pdb private constructor(private val data: ByteArray?, private val raf: RandomAccessFile?, private val length: Long, header: ByteArray) : java.io.Closeable {
        constructor(data: ByteArray) : this(data, null, data.size.toLong(), data)

        val count = be16(header, 76)
        val offsets = IntArray(count) { be32(header, 78 + it * 8) }

        fun record(i: Int): ByteArray {
            require(i in 0 until count) { "record $i out of range" }
            val s = offsets[i].toLong().coerceIn(0, length)
            val e = (if (i + 1 < count) offsets[i + 1].toLong() else length).coerceIn(s, length)
            if (data != null) return data.copyOfRange(s.toInt(), e.toInt())
            val r = raf!!
            return synchronized(r) { r.seek(s); ByteArray((e - s).toInt()).also { r.readFully(it) } }
        }

        override fun close() { raf?.close() }

        companion object {
            fun open(file: File): Pdb {
                val r = RandomAccessFile(file, "r")
                try {
                    val len = r.length()
                    if (len < 78 + 8) throw UnsupportedBookException("File too small")
                    val head = ByteArray(78).also { r.readFully(it) }
                    checkType(head)
                    val n = be16(head, 76)
                    val table = ByteArray(78 + n * 8)
                    r.seek(0); r.readFully(table)
                    return Pdb(null, r, len, table)
                } catch (e: Exception) {
                    r.close(); throw e
                }
            }

            internal fun checkType(head: ByteArray) {
                val magic = String(head, 0, minOf(8, head.size), Charsets.ISO_8859_1)
                if (magic.startsWith("TPZ") || magic.startsWith("CONT") || magic.startsWith("\u00EADRMION"))
                    throw UnsupportedBookException("Kindle KFX/Topaz format is not supported")
                val type = String(head, 60, 8, Charsets.ISO_8859_1)
                if (type != "BOOKMOBI" && type != "TEXtREAd") throw UnsupportedBookException("Not a MOBI file ($type)")
            }
        }
    }

    private class Header(val rec0: ByteArray, val base: Int) {
        val compression = be16(rec0, 0)
        val textLength = be32(rec0, 4)
        val textRecords = be16(rec0, 8)
        val encryption = be16(rec0, 12)
        val hasMobi = rec0.size > 20 && String(rec0, 16, 4, Charsets.ISO_8859_1) == "MOBI"
        val headerLen = if (hasMobi) be32(rec0, 20) else 0
        val encoding = if (hasMobi) be32(rec0, 28) else 1252
        val version = if (hasMobi && rec0.size > 0x28) be32(rec0, 0x24) else 0
        fun u32(off: Int) = if (hasMobi && off + 4 <= 16 + headerLen && off + 4 <= rec0.size) be32(rec0, off) else -1
        val firstImage = u32(0x6C)
        val huffRec = u32(0x70)
        val huffCount = u32(0x74)
        val exthFlags = u32(0x80)
        val extraFlags = if (headerLen >= 0xE4 && rec0.size >= 0xF4) be16(rec0, 0xF2) else 0
        val ncxIndex = u32(0xF4)
        val fragIndex = if (version >= 8) u32(0xF8) else -1
        val skelIndex = if (version >= 8) u32(0xFC) else -1
        val fdst = if (version >= 8) u32(0xC0) else -1
        val fullNameOff = u32(0x54)
        val fullNameLen = u32(0x58)
        val exth: Map<Int, List<ByteArray>> = parseExth()

        private fun parseExth(): Map<Int, List<ByteArray>> {
            if (!hasMobi || exthFlags == -1 || (exthFlags and 0x40) == 0) return emptyMap()
            val p = 16 + headerLen
            if (p + 12 > rec0.size || String(rec0, p, 4, Charsets.ISO_8859_1) != "EXTH") return emptyMap()
            val n = be32(rec0, p + 8)
            val m = HashMap<Int, MutableList<ByteArray>>()
            var q = p + 12
            for (i in 0 until n) {
                if (q + 8 > rec0.size) break
                val type = be32(rec0, q)
                val len = be32(rec0, q + 4)
                if (len < 8 || q + len > rec0.size) break
                m.getOrPut(type) { ArrayList() }.add(rec0.copyOfRange(q + 8, q + len))
                q += len
            }
            return m
        }

        fun charset(): Charset = if (encoding == 65001) Charsets.UTF_8 else Charset.forName("windows-1252")
        fun exthString(t: Int): String? = exth[t]?.firstOrNull()?.let { String(it, charset()).trim() }?.ifEmpty { null }
        fun exthInt(t: Int): Int? = exth[t]?.firstOrNull()?.takeIf { it.size >= 4 }?.let { be32(it, 0) }
    }

    fun open(file: File): InMemoryDocument {
        val pdb = Pdb.open(file)
        try {
            return parse(pdb, FileTitle.of(file), null)
        } catch (e: Exception) {
            pdb.close(); throw e
        }
    }

    /** Parses a book in memory. [format] null: AZW3 when the KF8 part is used, otherwise MOBI. */
    fun parse(data: ByteArray, fallbackTitle: String?, format: BookFormat? = null): InMemoryDocument {
        if (data.size < 78 + 8) throw UnsupportedBookException("File too small")
        Pdb.checkType(data)
        return parse(Pdb(data), fallbackTitle, format)
    }

    private fun parse(pdb: Pdb, fallbackTitle: String?, formatOverride: BookFormat?): InMemoryDocument {
        var h = Header(pdb.record(0), 0)
        if (h.encryption != 0) throw DrmProtectedException()
        // Combined MOBI6 + KF8: EXTH 121 points at the KF8 header record.
        val boundary = h.exthInt(121)
        val mobi6 = h
        if (boundary != null && boundary > 0 && boundary < pdb.count) {
            val kf8 = Header(pdb.record(boundary), boundary)
            if (kf8.hasMobi && kf8.version >= 8) h = kf8
        }
        // KF8-only files (AZW3) have a version 8 main header; combined files a KF8 boundary.
        val format = formatOverride ?: if (h.version >= 8) BookFormat.AZW3 else BookFormat.MOBI
        val text = readText(pdb, h)
        val cs = h.charset()
        val firstImage = if (h.firstImage in 1 until pdb.count) h.firstImage else mobi6.firstImage
        val imageBase = when {
            h.base > 0 && h.firstImage >= 0 && h.firstImage + h.base < pdb.count && isImage(pdb.record(h.firstImage + h.base)) -> h.firstImage + h.base
            else -> firstImage
        }
        val imageResolver: (Int) -> String? = { idx1 ->
            val rec = imageBase + idx1 - 1
            if (imageBase > 0 && rec in 0 until pdb.count) {
                "mobiimg:$rec"
            } else null
        }

        val meta = metadata(h, mobi6, fallbackTitle) { idx -> imageResolver(idx + 1) }

        val (segments, toc) = if (h.version >= 8 && h.skelIndex in 0 until 0x7FFFFFFF && h.fragIndex >= 0) {
            kf8Segments(pdb, h, text, cs, imageResolver)
        } else {
            mobi6Segments(pdb, h, text, cs, imageResolver)
        }
        val split = ChapterSplitter.splitSegments(segments)
        val finalToc = toc.mapNotNull { (title, level, anchor) ->
            val ch = split.anchorChapter[anchor] ?: return@mapNotNull null
            TocEntry(title, level, ch, anchor)
        }.ifEmpty { split.toc }
        return object : InMemoryDocument(format, meta, split.chapters, finalToc) {
            override fun image(resource: String): ImageData? {
                val rec = resource.removePrefix("mobiimg:").toIntOrNull() ?: return null
                if (rec !in 0 until pdb.count) return null
                return pdb.record(rec).takeIf { isImage(it) }?.let { ImageData.Encoded(it) }
            }

            override fun close() = pdb.close()
        }
    }

    private fun isImage(b: ByteArray): Boolean = b.size > 4 && (
        (b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()) || (b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte()) ||
            (b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte()) || (b[0] == 'B'.code.toByte() && b[1] == 'M'.code.toByte())
        )

    private fun metadata(h: Header, mobi6: Header, fallback: String?, cover: (Int) -> String?): BookMetadata {
        val fullName = if (h.fullNameOff > 0 && h.fullNameLen > 0 && h.fullNameOff + h.fullNameLen <= h.rec0.size) {
            String(h.rec0, h.fullNameOff, h.fullNameLen, h.charset()).trim()
        } else null
        val src = if (h.exth.isNotEmpty()) h else mobi6
        val coverIdx = src.exthInt(201)?.takeIf { it != -1 && it != 0xFFFFFFFF.toInt() } ?: src.exthInt(202)
        return BookMetadata(
            title = src.exthString(503) ?: fullName ?: fallback,
            authors = src.exth[100]?.map { String(it, src.charset()).trim() }?.filter { it.isNotEmpty() } ?: emptyList(),
            publisher = src.exthString(101),
            description = src.exthString(103)?.let { Jsoup.parse(it).text() },
            identifier = src.exthString(104),
            subjects = src.exth[105]?.map { String(it, src.charset()).trim() } ?: emptyList(),
            year = src.exthString(106)?.take(4),
            language = src.exthString(524),
            coverResource = coverIdx?.let { cover(it) },
        )
    }

    private fun trailingSize(rec: ByteArray, flags: Int): Int {
        var size = rec.size
        var f = flags ushr 1
        var total = 0
        while (f != 0) {
            if (f and 1 != 0) {
                // backward varint at the current end
                var bitpos = 0; var result = 0; var s = size - total
                while (s > 0) {
                    val v = rec[s - 1].toInt() and 0xFF
                    result = result or ((v and 0x7F) shl bitpos)
                    bitpos += 7; s--
                    if (v and 0x80 != 0 || bitpos >= 28) break
                }
                total += result
            }
            f = f ushr 1
        }
        if (flags and 1 != 0 && size - total - 1 >= 0) total += (rec[size - total - 1].toInt() and 0x3) + 1
        size = total
        return size
    }

    private fun readText(pdb: Pdb, h: Header): ByteArray {
        val out = java.io.ByteArrayOutputStream(h.textLength.coerceAtLeast(0))
        val huff = if (h.compression == 17480) {
            val base = h.base
            val huffRec = pdb.record(base + h.huffRec)
            val cdics = (1 until h.huffCount).map { pdb.record(base + h.huffRec + it) }
            HuffCdic(huffRec, cdics)
        } else null
        for (i in 1..h.textRecords) {
            val idx = h.base + i
            if (idx >= pdb.count) break
            val rec = pdb.record(idx)
            val trail = trailingSize(rec, h.extraFlags).coerceIn(0, rec.size)
            val body = rec.copyOf(rec.size - trail)
            val dec = when (h.compression) {
                1 -> body
                2 -> PalmDoc.decompress(body)
                17480 -> huff!!.decompress(body)
                else -> throw UnsupportedBookException("Unknown MOBI compression ${h.compression}")
            }
            out.write(dec)
        }
        return out.toByteArray()
    }

    // ---------- INDX ----------

    class IndexEntry(val name: String, val tags: Map<Int, List<Int>>)

    private fun decint(b: ByteArray, pos: Int): Pair<Int, Int> {
        var v = 0; var p = pos
        while (p < b.size) {
            val c = b[p++].toInt() and 0xFF
            v = (v shl 7) or (c and 0x7F)
            if (c and 0x80 != 0) break
        }
        return v to (p - pos)
    }

    fun readIndex(pdb: Pdb, first: Int): Pair<List<IndexEntry>, Map<Int, String>> {
        val hdr = pdb.record(first)
        if (String(hdr, 0, 4, Charsets.ISO_8859_1) != "INDX") return emptyList<IndexEntry>() to emptyMap()
        val hdrLen = be32(hdr, 4)
        val numRecords = be32(hdr, 24)
        val cncxCount = be32(hdr, 52)
        // TAGX
        var tagx = hdrLen
        if (String(hdr, tagx, 4, Charsets.ISO_8859_1) != "TAGX") {
            tagx = (0 until hdr.size - 4).firstOrNull { String(hdr, it, 4, Charsets.ISO_8859_1) == "TAGX" } ?: return emptyList<IndexEntry>() to emptyMap()
        }
        val tagxLen = be32(hdr, tagx + 4)
        val controlBytes = be32(hdr, tagx + 8)
        val tagDefs = ArrayList<IntArray>()
        var t = tagx + 12
        while (t + 4 <= tagx + tagxLen) {
            tagDefs += intArrayOf(hdr[t].toInt() and 0xFF, hdr[t + 1].toInt() and 0xFF, hdr[t + 2].toInt() and 0xFF, hdr[t + 3].toInt() and 0xFF)
            t += 4
        }
        val entries = ArrayList<IndexEntry>()
        for (r in 1..numRecords) {
            if (first + r >= pdb.count) break
            val rec = pdb.record(first + r)
            if (String(rec, 0, 4, Charsets.ISO_8859_1) != "INDX") continue
            val idxt = be32(rec, 20)
            val n = be32(rec, 24)
            if (idxt + 4 + n * 2 > rec.size) continue
            for (k in 0 until n) {
                val start = be16(rec, idxt + 4 + k * 2)
                val end = if (k + 1 < n) be16(rec, idxt + 4 + (k + 1) * 2) else idxt
                if (start >= rec.size) continue
                val nameLen = rec[start].toInt() and 0xFF
                val name = String(rec, start + 1, nameLen.coerceAtMost(rec.size - start - 1), Charsets.ISO_8859_1)
                val cbStart = start + 1 + nameLen
                var dp = cbStart + controlBytes
                val tags = LinkedHashMap<Int, List<Int>>()
                val pending = ArrayList<IntArray>() // tag, count or -1, bytes or -1, valuesPerEntry
                var cbi = 0
                for (def in tagDefs) {
                    val (tag, vpe, mask0, endFlag) = def.toList()
                    if (endFlag == 1) { cbi++; continue }
                    if (cbStart + cbi >= rec.size) break
                    val cbyte = rec[cbStart + cbi].toInt() and 0xFF
                    var value = cbyte and mask0
                    if (value == 0) continue
                    if (value == mask0) {
                        if (Integer.bitCount(mask0) > 1) {
                            val (v, used) = decint(rec, dp); dp += used
                            pending += intArrayOf(tag, -1, v, vpe)
                        } else pending += intArrayOf(tag, 1, -1, vpe)
                    } else {
                        var m = mask0
                        while (m and 1 == 0) { m = m ushr 1; value = value ushr 1 }
                        pending += intArrayOf(tag, value, -1, vpe)
                    }
                }
                for ((tag, count, bytes, vpe) in pending.map { it.toList() }) {
                    val vals = ArrayList<Int>()
                    if (count >= 0) {
                        repeat(count * vpe) { val (v, used) = decint(rec, dp); dp += used; vals += v }
                    } else {
                        var consumed = 0
                        while (consumed < bytes && dp < end) { val (v, used) = decint(rec, dp); dp += used; consumed += used; vals += v }
                    }
                    tags[tag] = vals
                }
                entries += IndexEntry(name, tags)
            }
        }
        // CNCX string records follow the index records.
        val cncx = HashMap<Int, String>()
        for (c in 0 until cncxCount) {
            val ri = first + numRecords + 1 + c
            if (ri >= pdb.count) break
            val rec = pdb.record(ri)
            var p = 0
            while (p < rec.size) {
                val (len, used) = decint(rec, p)
                if (len == 0 && used == 0) break
                val s = p + used
                if (s + len > rec.size) break
                cncx[c * 0x10000 + p] = String(rec, s, len, Charsets.UTF_8)
                p = s + len
                if (len == 0) p++ // skip padding
            }
        }
        return entries to cncx
    }

    // ---------- KF8 ----------

    private class Segment(val html: String)

    private fun base32(s: String): Int = s.uppercase().fold(0) { acc, c -> acc * 32 + "0123456789ABCDEFGHIJKLMNOPQRSTUV".indexOf(c).coerceAtLeast(0) }

    private fun kf8Segments(pdb: Pdb, h: Header, text: ByteArray, cs: Charset, image: (Int) -> String?): Pair<List<List<Block>>, List<Triple<String, Int, String>>> {
        // Flows (FDST): flow 0 is the main text, others CSS/SVG.
        val flows = ArrayList<IntArray>()
        if (h.fdst > 0 && h.base + h.fdst < pdb.count) {
            val f = pdb.record(h.base + h.fdst)
            if (String(f, 0, 4, Charsets.ISO_8859_1) == "FDST") {
                val n = be32(f, 8)
                for (i in 0 until n) flows += intArrayOf(be32(f, 12 + i * 8), be32(f, 16 + i * 8))
            }
        }
        if (flows.isEmpty()) flows += intArrayOf(0, text.size)
        fun flow(i: Int): ByteArray = flows.getOrNull(i)?.let { text.copyOfRange(it[0].coerceIn(0, text.size), it[1].coerceIn(0, text.size)) } ?: ByteArray(0)
        val main = flow(0)

        val (skels, _) = readIndex(pdb, h.base + h.skelIndex)
        val (frags, _) = readIndex(pdb, h.base + h.fragIndex)
        // Fragment: insertPos (entry name), fileNum (tag 3), seq (4), (start, length) (tag 6)
        class Frag(val insert: Int, val length: Int)
        val fragList = frags.map { Frag(it.name.toIntOrNull() ?: 0, it.tags[6]?.getOrNull(1) ?: 0) }
        val parts = ArrayList<ByteArray>()
        val fragPartPos = IntArray(fragList.size) // part index
        val fragOffsetInPart = IntArray(fragList.size)
        var fp = 0
        for (sk in skels) {
            val fragCount = sk.tags[1]?.firstOrNull() ?: 0
            val pos = sk.tags[6]?.getOrNull(0) ?: 0
            val len = sk.tags[6]?.getOrNull(1) ?: 0
            var part = main.copyOfRange(pos.coerceIn(0, main.size), (pos + len).coerceIn(0, main.size))
            var baseptr = pos + len
            for (k in 0 until fragCount) {
                if (fp >= fragList.size) break
                val fr = fragList[fp]
                val ins = (fr.insert - pos).coerceIn(0, part.size)
                val slice = main.copyOfRange(baseptr.coerceIn(0, main.size), (baseptr + fr.length).coerceIn(0, main.size))
                part = part.copyOfRange(0, ins) + slice + part.copyOfRange(ins, part.size)
                fragPartPos[fp] = parts.size
                fragOffsetInPart[fp] = ins
                baseptr += fr.length
                fp++
            }
            parts += part
        }
        if (parts.isEmpty()) parts += main

        // Collect link targets kindle:pos:fid:XXXX:off:YYYY and TOC positions; insert anchors.
        val inserts = Array(parts.size) { ArrayList<Pair<Int, String>>() }
        fun anchorFor(fid: Int, off: Int): String? {
            if (fid !in fragList.indices) return null
            val part = fragPartPos[fid]
            val id = "kpos_${fid}_$off"
            inserts[part] += (fragOffsetInPart[fid] + off) to id
            return id
        }
        val posRegex = Regex("kindle:pos:fid:([0-9A-Va-v]{4}):off:([0-9A-Va-v]{10})")
        for (p in parts) {
            for (m in posRegex.findAll(String(p, Charsets.ISO_8859_1))) anchorFor(base32(m.groupValues[1]), base32(m.groupValues[2]))
        }
        val toc = ArrayList<Triple<String, Int, String>>()
        if (h.ncxIndex > 0 && h.base + h.ncxIndex < pdb.count) {
            val (ncx, cncx) = readIndex(pdb, h.base + h.ncxIndex)
            for (e in ncx) {
                val label = e.tags[3]?.firstOrNull()?.let { cncx[it] } ?: continue
                val level = e.tags[4]?.firstOrNull() ?: 0
                val pf = e.tags[6]
                val anchor = if (pf != null && pf.size >= 2) anchorFor(pf[0], pf[1]) else null
                if (anchor != null) toc += Triple(label, level, anchor)
            }
        }
        val css = MiniCss()
        val cssFlows = HashSet<Int>()
        val segments = parts.mapIndexed { i, raw ->
            val withAnchors = insertAnchors(raw, inserts[i])
            var html = String(withAnchors, cs)
            Regex("kindle:flow:([0-9A-Va-v]{4})\\?mime=text/css").findAll(html).forEach { m ->
                val f = base32(m.groupValues[1])
                if (cssFlows.add(f)) css.addStylesheet(String(flow(f), cs))
            }
            html = html.replace(posRegex) { m -> "#kpos_${base32(m.groupValues[1])}_${base32(m.groupValues[2])}" }
            htmlToBlocks(html, css) { src ->
                Regex("kindle:embed:([0-9A-Va-v]{4})").find(src)?.let { image(base32(it.groupValues[1])) }
            }
        }
        return segments to toc
    }

    private fun insertAnchors(raw: ByteArray, ins: List<Pair<Int, String>>): ByteArray {
        if (ins.isEmpty()) return raw
        val out = java.io.ByteArrayOutputStream(raw.size + ins.size * 40)
        var last = 0
        for ((pos0, id) in ins.distinctBy { it.second }.sortedBy { it.first }) {
            var pos = pos0.coerceIn(0, raw.size)
            // Do not split a tag: move to the next '<' if we are inside one.
            var k = pos - 1
            while (k >= last && raw[k] != '>'.code.toByte() && raw[k] != '<'.code.toByte()) k--
            if (k >= last && raw[k] == '<'.code.toByte()) {
                while (pos < raw.size && raw[pos] != '>'.code.toByte()) pos++
                if (pos < raw.size) pos++
            }
            if (pos < last) pos = last
            out.write(raw, last, pos - last)
            out.write("<a id=\"$id\"></a>".toByteArray(Charsets.ISO_8859_1))
            last = pos
        }
        out.write(raw, last, raw.size - last)
        return out.toByteArray()
    }

    // ---------- MOBI 6 ----------

    private fun mobi6Segments(pdb: Pdb, h: Header, text: ByteArray, cs: Charset, image: (Int) -> String?): Pair<List<List<Block>>, List<Triple<String, Int, String>>> {
        val s = String(text, Charsets.ISO_8859_1)
        val targets = Regex("filepos\\s*=\\s*[\"']?0*(\\d+)", RegexOption.IGNORE_CASE).findAll(s).map { it.groupValues[1].toInt() }.toMutableSet()
        val toc = ArrayList<Triple<String, Int, String>>()
        if (h.ncxIndex > 0 && h.ncxIndex != -1 && h.ncxIndex < pdb.count) {
            try {
                val (ncx, cncx) = readIndex(pdb, h.ncxIndex)
                for (e in ncx) {
                    val label = e.tags[3]?.firstOrNull()?.let { cncx[it] } ?: continue
                    val pos = e.tags[1]?.firstOrNull() ?: continue
                    targets += pos
                    toc += Triple(label, e.tags[4]?.firstOrNull() ?: 0, "fp$pos")
                }
            } catch (_: Exception) {}
        }
        val withAnchors = insertAnchors(text, targets.map { it to "fp$it" })
        val html = String(withAnchors, cs)
            .replace(Regex("filepos\\s*=\\s*[\"']?0*(\\d+)[\"']?", RegexOption.IGNORE_CASE)) { "href=\"#fp${it.groupValues[1]}\"" }
            .replace(Regex("recindex\\s*=\\s*[\"']?0*(\\d+)[\"']?", RegexOption.IGNORE_CASE)) { "src=\"mobirec:${it.groupValues[1]}\"" }
        val parts = html.split(Regex("<mbp:pagebreak\\s*/?>", RegexOption.IGNORE_CASE))
        val segments = parts.map { part ->
            htmlToBlocks(part, MiniCss()) { src -> src.removePrefix("mobirec:").toIntOrNull()?.let { image(it) } }
        }.filter { it.isNotEmpty() }
        return segments to toc
    }

    private fun htmlToBlocks(html: String, css: MiniCss, img: (String) -> String?): List<Block> {
        val doc = Jsoup.parse(html)
        doc.select("guide, mbp\\:guide").remove()
        val conv = HtmlConverter(
            css,
            resolveHref = { href ->
                when {
                    href.startsWith("#") -> ChapterSplitter.pendingLink(href.substring(1))
                    LinkTarget.isExternal(href) -> href
                    else -> null
                }
            },
            resolveImage = img,
        )
        return conv.convert(doc.body()).build()
    }
}
