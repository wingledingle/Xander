package app.quinten.formats.djvu

import app.quinten.analysis.page.Box
import app.quinten.analysis.page.PageLayout
import app.quinten.analysis.page.PageText
import app.quinten.analysis.page.StyleRun
import app.quinten.analysis.page.TextLine
import app.quinten.analysis.paged.OutlineEntry
import app.quinten.analysis.paged.PageSource
import app.quinten.formats.UnsupportedBookException
import app.quinten.model.BookMetadata
import app.quinten.model.ImageData
import java.io.File
import java.io.RandomAccessFile

/**
 * DjVu reader: bundled and indirect multi-page documents and single-page files. Decodes JB2
 * masks (with shared dictionaries), IW44 backgrounds/foregrounds, FGbz palettes, the hidden
 * text layer and the NAVM outline. Rendering composites mask colours over the background.
 */
class DjvuDocument(private val file: File, private val pageCacheSize: Int = REF_CACHE_PAGES) : PageSource {
    private class Chunk(val id: String, val offset: Int, val size: Int, val children: List<Chunk> = emptyList(), val formType: String? = null)

    private class PageRef(val data: ByteArray, val form: Chunk, val id: String)

    private val raf = RandomAccessFile(file, "r")
    private val pages = ArrayList<() -> PageRef>()
    private val includes = HashMap<String, () -> PageRef>()
    private val pageIds = HashMap<String, Int>()
    private var navm: ByteArray? = null
    private val dictCache = HashMap<String, Jb2.Dict>()
    private val infoCache = HashMap<Int, IntArray>()

    init {
        try { open() } catch (e: Exception) { raf.close(); throw if (e is UnsupportedBookException) e else UnsupportedBookException("Invalid DjVu file: ${e.message}") }
    }

    private fun readAt(off: Long, len: Int): ByteArray = synchronized(raf) {
        raf.seek(off); ByteArray(len).also { raf.readFully(it) }
    }

    private fun be32(b: ByteArray, o: Int) = ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)
    private fun be16(b: ByteArray, o: Int) = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

    /** Parses IFF chunks of [data] in [start, end). */
    private fun parseChunks(data: ByteArray, start: Int, end: Int): List<Chunk> {
        val out = ArrayList<Chunk>()
        var p = start
        while (p + 8 <= end) {
            val id = String(data, p, 4, Charsets.ISO_8859_1)
            val size = be32(data, p + 4)
            val body = p + 8
            if (size < 0 || body + size > end) break
            out += if (id == "FORM" && size >= 4) {
                Chunk(id, body, size, parseChunks(data, body + 4, body + size), String(data, body, 4, Charsets.ISO_8859_1))
            } else Chunk(id, body, size)
            p = body + size + (size and 1)
        }
        return out
    }

    private fun loadForm(offset: Long): PageRef {
        val head = readAt(offset, 8)
        val size = be32(head, 4)
        val data = readAt(offset, 8 + size)
        val form = parseChunks(data, 0, data.size).first()
        return PageRef(data, form, "")
    }

    private fun open() {
        val magic = readAt(0, 4)
        if (String(magic, Charsets.ISO_8859_1) != "AT&T") throw UnsupportedBookException("Not a DjVu file")
        val head = readAt(4, 12)
        if (String(head, 0, 4, Charsets.ISO_8859_1) != "FORM") throw UnsupportedBookException("Not a DjVu file")
        val type = String(head, 8, 4, Charsets.ISO_8859_1)
        when (type) {
            "DJVU" -> { val ref = loadForm(4); pages += { ref } }
            "DJVM" -> {
                // Read the directory chunk (DIRM) which follows the form type.
                val dirmHead = readAt(16, 8)
                if (String(dirmHead, 0, 4, Charsets.ISO_8859_1) != "DIRM") throw UnsupportedBookException("DjVu directory missing")
                val dsize = be32(dirmHead, 4)
                val dirm = readAt(24, dsize)
                val bundled = (dirm[0].toInt() and 0x80) != 0
                val n = be16(dirm, 1)
                var p = 3
                val offsets = IntArray(n)
                if (bundled) for (i in 0 until n) { offsets[i] = be32(dirm, p); p += 4 }
                val meta = Bzz.decode(dirm, p, dsize - p)
                var q = 0
                q += 3 * n // sizes
                val flags = IntArray(n) { meta[q + it].toInt() and 0xFF }
                q += n
                fun zstr(): String { val s = q; while (q < meta.size && meta[q].toInt() != 0) q++; val r = String(meta, s, q - s, Charsets.UTF_8); q++; return r }
                val ids = Array(n) { zstr() }
                for (i in 0 until n) {
                    if (flags[i] and 0x80 != 0) zstr() // name
                    if (flags[i] and 0x40 != 0) zstr() // title
                }
                for (i in 0 until n) {
                    val id = ids[i]
                    val loader: () -> PageRef = if (bundled) {
                        val off = offsets[i].toLong()
                        lazyRef { loadForm(off) }
                    } else {
                        val f = File(file.parentFile, id)
                        lazyRef {
                            val bytes = f.readBytes()
                            val form = parseChunks(bytes, 4, bytes.size).first()
                            PageRef(bytes, form, id)
                        }
                    }
                    when (flags[i] and 0x3F) {
                        1 -> { pageIds[id] = pages.size; pages += loader }
                        0 -> includes[id] = loader
                        else -> {}
                    }
                }
                // NAVM (outline) chunk follows the directory in bundled documents.
                var off = 24L + dsize + (dsize and 1)
                val total = raf.length()
                while (off + 8 <= total) {
                    val h = readAt(off, 8)
                    val id = String(h, 0, 4, Charsets.ISO_8859_1)
                    val sz = be32(h, 4)
                    if (id == "NAVM") { navm = readAt(off + 8, sz); break }
                    if (id == "FORM") break
                    off += 8 + sz + (sz and 1)
                }
            }
            else -> throw UnsupportedBookException("Unsupported DjVu form $type")
        }
        if (pages.isEmpty()) throw UnsupportedBookException("DjVu document has no pages")
    }

    /** Raw bytes of recently used page/include forms; bounded so big documents don't keep every page. */
    private val refCache = object : LinkedHashMap<Int, PageRef>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, PageRef>?) = size > pageCacheSize
    }
    private var nextRefKey = 0

    private fun lazyRef(f: () -> PageRef): () -> PageRef {
        val key = nextRefKey++
        return {
            synchronized(refCache) { refCache[key] } ?: f().also { r -> synchronized(refCache) { refCache[key] = r } }
        }
    }

    /** Number of cached raw page forms (for tests). */
    internal val cachedPageForms: Int get() = synchronized(refCache) { refCache.size }

    override val pageCount: Int get() = pages.size

    override val metadata: BookMetadata = BookMetadata(title = app.quinten.formats.util.FileTitle.of(file))

    override val outline: List<OutlineEntry> by lazy { parseNavm() }

    private fun parseNavm(): List<OutlineEntry> {
        val raw = navm ?: return emptyList()
        return try {
            val d = Bzz.decode(raw)
            var p = 0
            val total = be16(d, 0); p = 2
            val out = ArrayList<OutlineEntry>()
            fun u24(): Int { val v = ((d[p].toInt() and 0xFF) shl 16) or ((d[p + 1].toInt() and 0xFF) shl 8) or (d[p + 2].toInt() and 0xFF); p += 3; return v }
            fun read(level: Int) {
                val children = d[p++].toInt() and 0xFF
                val tl = u24(); val title = String(d, p, tl, Charsets.UTF_8); p += tl
                val ul = u24(); val url = String(d, p, ul, Charsets.UTF_8); p += ul
                val page = urlToPage(url)
                if (page != null && title.isNotBlank()) out += OutlineEntry(title.trim(), level, page)
                repeat(children) { if (out.size < total + 1 && p < d.size) read(level + 1) }
            }
            while (p < d.size && out.size < total) read(0)
            out
        } catch (_: Exception) { emptyList() }
    }

    private fun urlToPage(url: String): Int? {
        val u = url.removePrefix("#")
        pageIds[u]?.let { return it }
        u.toIntOrNull()?.let { return (it - 1).coerceIn(0, pages.size - 1) }
        return null
    }

    private fun page(i: Int): PageRef = pages[i]()

    private fun info(i: Int): IntArray = synchronized(infoCache) {
        infoCache.getOrPut(i) {
            val ref = page(i)
            val c = ref.form.children.firstOrNull { it.id == "INFO" }
            if (c == null || c.size < 5) intArrayOf(1000, 1400, 300, 1)
            else {
                val d = ref.data; val o = c.offset
                val w = be16(d, o); val h = be16(d, o + 2)
                val dpi = if (c.size >= 8) ((d[o + 6].toInt() and 0xFF) or ((d[o + 7].toInt() and 0xFF) shl 8)).let { if (it in 25..6000) it else 300 } else 300
                val flags = if (c.size >= 10) d[o + 9].toInt() and 7 else 1
                intArrayOf(w, h, dpi, flags)
            }
        }
    }

    override fun pageSize(page: Int): Pair<Float, Float> = info(page).let { it[0].toFloat() to it[1].toFloat() }

    private fun chunk(ref: PageRef, id: String) = ref.form.children.firstOrNull { it.id == id }

    private fun sharedDict(ref: PageRef): Jb2.Dict? {
        for (inc in ref.form.children.filter { it.id == "INCL" }) {
            val name = String(ref.data, inc.offset, inc.size, Charsets.UTF_8).trim()
            synchronized(dictCache) { dictCache[name] }?.let { return it }
            val incRef = includes[name]?.invoke() ?: continue
            val djbz = incRef.form.children.firstOrNull { it.id == "Djbz" } ?: continue
            val dict = Jb2.decodeDict(incRef.data, offset = djbz.offset, end = djbz.offset + djbz.size)
            synchronized(dictCache) { dictCache[name] = dict }
            return dict
        }
        chunk(ref, "Djbz")?.let { return Jb2.decodeDict(ref.data, offset = it.offset, end = it.offset + it.size) }
        return null
    }

    // ---------------- text layer ----------------

    private class Zone(val type: Int, val x0: Int, val y0: Int, val x1: Int, val y1: Int, val textStart: Int, val textLen: Int, val children: List<Zone>)

    override fun textLayer(page: Int): PageText? {
        val ref = page(page)
        val (w, h) = info(page).let { it[0] to it[1] }
        val raw = chunk(ref, "TXTz")?.let { Bzz.decode(ref.data, it.offset, it.size) }
            ?: chunk(ref, "TXTa")?.let { ref.data.copyOfRange(it.offset, it.offset + it.size) } ?: return null
        if (raw.size < 3) return null
        val textLen = ((raw[0].toInt() and 0xFF) shl 16) or ((raw[1].toInt() and 0xFF) shl 8) or (raw[2].toInt() and 0xFF)
        if (3 + textLen > raw.size) return null
        val text = raw.copyOfRange(3, 3 + textLen)
        var p = 3 + textLen + 1 // version byte
        if (p >= raw.size) return null
        fun s16(): Int { val v = ((raw[p].toInt() and 0xFF) shl 8 or (raw[p + 1].toInt() and 0xFF)) - 0x8000; p += 2; return v }
        fun u24(): Int { val v = ((raw[p].toInt() and 0xFF) shl 16) or ((raw[p + 1].toInt() and 0xFF) shl 8) or (raw[p + 2].toInt() and 0xFF); p += 3; return v }
        class Ctx(val x0: Int, val y0: Int, val x1: Int, val y1: Int, val ts: Int, val tl: Int)
        fun zone(parent: Ctx?, prev: Ctx?, depth: Int): Pair<Zone, Ctx> {
            if (p + 17 > raw.size || depth > 12) throw DjvuException("truncated text zone")
            val type = raw[p++].toInt() and 0xFF
            var x = s16(); var y = s16(); val zw = s16(); val zh = s16()
            var ts = s16(); val tl = u24()
            if (prev != null) {
                if (type == 1 || type == 4 || type == 5) { x += prev.x0; y = prev.y0 - (y + zh) } else { x += prev.x1; y += prev.y0 }
                ts += prev.ts + prev.tl
            } else if (parent != null) {
                x += parent.x0; y = parent.y1 - (y + zh); ts += parent.ts
            }
            val ctx = Ctx(x, y, x + zw, y + zh, ts, tl)
            val n = u24()
            val kids = ArrayList<Zone>()
            var prevKid: Ctx? = null
            for (k in 0 until n) { val (z, c) = zone(ctx, prevKid, depth + 1); kids += z; prevKid = c }
            return Zone(type, x, y, x + zw, y + zh, ts, tl, kids) to ctx
        }
        val root = try { zone(null, null, 0).first } catch (_: Exception) { return null }
        fun str(z: Zone): String {
            val s = z.textStart.coerceIn(0, text.size); val e = (z.textStart + z.textLen).coerceIn(s, text.size)
            return String(text, s, e - s, Charsets.UTF_8)
        }
        val lines = ArrayList<TextLine>()
        val cols = ArrayList<Int>()
        var colIndex = -1
        fun walk(z: Zone, col: Int) {
            when {
                z.type == 5 -> {
                    val words = z.children.filter { it.type >= 6 }
                    val t = (if (words.isNotEmpty()) words.joinToString(" ") { str(it).trim() } else str(z)).replace(Regex("\\s+"), " ").trim()
                    if (t.isNotEmpty()) {
                        // DjVu y grows upwards; convert to top-left origin.
                        val box = Box(z.x0.toFloat(), (h - z.y1).toFloat(), z.x1.toFloat(), (h - z.y0).toFloat())
                        val size = (box.height * 0.8f).coerceAtLeast(1f)
                        lines += TextLine(t, box, box.y1 - box.height * 0.2f, size, listOf(StyleRun(0, t.length, size, false, false)))
                        cols += col
                    }
                }
                z.type == 2 || z.type == 3 -> { colIndex++; val c = colIndex; z.children.forEach { walk(it, c) } }
                else -> z.children.forEach { walk(it, col) }
            }
        }
        walk(root, 0)
        if (lines.isEmpty()) {
            val all = str(root).trim()
            if (all.isEmpty()) return null
            // Text without geometry: one pseudo line per text line.
            val parts = all.split('\n').filter { it.isNotBlank() }
            val lh = h.toFloat() / (parts.size + 2)
            parts.forEachIndexed { i, s -> lines += TextLine(s.trim(), Box(0f, lh * (i + 1), w.toFloat(), lh * (i + 2)), lh * (i + 2), lh * 0.8f); cols += 0 }
        }
        val pt = PageLayout.fromLines(page, w.toFloat(), h.toFloat(), lines, trustOrder = true)
        // DjVu hidden text is always a scanner's OCR.
        return PageText(page, pt.width, pt.height, pt.lines, emptyList(), cols.toIntArray(), pt.columns, scanned = true)
    }

    // ---------------- rendering ----------------

    override fun render(page: Int, targetWidth: Int): ImageData? = try { renderPage(page, targetWidth) } catch (e: DjvuException) { null }

    /** Like [render] but propagates decoding errors. */
    fun renderStrict(page: Int, targetWidth: Int): ImageData.Raster = renderPage(page, targetWidth)

    private fun renderPage(page: Int, targetWidth: Int): ImageData.Raster {
        val ref = page(page)
        val inf = info(page)
        val pw = inf[0]; val ph = inf[1]
        val ow = targetWidth.coerceIn(16, (pw * 2).coerceAtLeast(16))
        val oh = (ph.toLong() * ow / pw).toInt().coerceAtLeast(1)
        val out = IntArray(ow * oh) { -1 }

        // Background
        val bgChunks = ref.form.children.filter { it.id == "BG44" }
        if (bgChunks.isNotEmpty()) {
            val bg = Iw44Image()
            try {
                for (c in bgChunks) bg.decodeChunk(ref.data, c.offset, c.size)
            } catch (_: DjvuException) {}
            if (bg.isInitialized) {
                var sub = 1
                while (sub < 16 && bg.width / (sub * 2) >= ow) sub *= 2
                val px = bg.toArgb(sub)
                scaleInto(px, bg.outWidth(sub), bg.outHeight(sub), out, ow, oh)
            }
        } else chunk(ref, "BGjp")?.let { /* JPEG backgrounds need the platform decoder; leave white */ }

        // Foreground mask
        val sjbz = chunk(ref, "Sjbz")
        if (sjbz != null) {
            val fgbz = chunk(ref, "FGbz")
            var palette: IntArray? = null
            var blitColor: IntArray? = null
            if (fgbz != null) {
                val d = ref.data; var o = fgbz.offset
                val ver = d[o++].toInt() and 0xFF
                val n = be16(d, o); o += 2
                palette = IntArray(n) { i -> val b = d[o + i * 3].toInt() and 0xFF; val g = d[o + i * 3 + 1].toInt() and 0xFF; val r = d[o + i * 3 + 2].toInt() and 0xFF; (0xFF shl 24) or (r shl 16) or (g shl 8) or b }
                o += n * 3
                if (ver and 0x80 != 0 && o + 3 <= fgbz.offset + fgbz.size) {
                    // The number of colour indices is stored before the BZZ stream (as in DjVuLibre's
                    // DjVuPalette::decode); the stream itself holds only the 16-bit indices.
                    val nb = ((d[o].toInt() and 0xFF) shl 16) or ((d[o + 1].toInt() and 0xFF) shl 8) or (d[o + 2].toInt() and 0xFF)
                    o += 3
                    val idx = Bzz.decode(d, o, fgbz.offset + fgbz.size - o)
                    blitColor = IntArray(nb) { i -> if (i * 2 + 1 < idx.size) be16(idx, i * 2) else 0 }
                }
            }
            val pal = palette?.takeIf { it.isNotEmpty() }
            val mask = Jb2.decode(ref.data, sharedDict(ref), paletteSize = pal?.size ?: 0, blitColor = blitColor, offset = sjbz.offset, end = sjbz.offset + sjbz.size)
            var fgImage: IntArray? = null; var fgW = 0; var fgH = 0
            val fg44 = ref.form.children.filter { it.id == "FG44" }
            if (palette == null && fg44.isNotEmpty()) {
                val fg = Iw44Image()
                try { for (c in fg44) fg.decodeChunk(ref.data, c.offset, c.size) } catch (_: DjvuException) {}
                if (fg.isInitialized) { fgImage = fg.toArgb(1); fgW = fg.width; fgH = fg.height }
            }
            compositeMask(mask, out, ow, oh, pal, fgImage, fgW, fgH)
        }
        return ImageData.Raster(ow, oh, out)
    }

    /** Area-averaging (downscale) or nearest (upscale) resampling. */
    private fun scaleInto(src: IntArray, sw: Int, sh: Int, dst: IntArray, dw: Int, dh: Int) {
        if (dw > sw) { bilinear(src, sw, sh, dst, dw, dh); return }
        for (y in 0 until dh) {
            val sy0 = y * sh / dh; val sy1 = maxOf(sy0 + 1, (y + 1) * sh / dh)
            for (x in 0 until dw) {
                val sx0 = x * sw / dw; val sx1 = maxOf(sx0 + 1, (x + 1) * sw / dw)
                var r = 0; var g = 0; var b = 0; var n = 0
                var yy = sy0
                while (yy < sy1 && yy < sh) {
                    var xx = sx0
                    while (xx < sx1 && xx < sw) {
                        val c = src[yy * sw + xx]
                        r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF; n++
                        xx++
                    }
                    yy++
                }
                if (n > 0) dst[y * dw + x] = (0xFF shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
            }
        }
    }

    private fun bilinear(src: IntArray, sw: Int, sh: Int, dst: IntArray, dw: Int, dh: Int) {
        for (y in 0 until dh) {
            val fy = ((y + 0.5f) * sh / dh - 0.5f).coerceIn(0f, (sh - 1).toFloat())
            val y0 = fy.toInt(); val y1 = minOf(y0 + 1, sh - 1); val wy = fy - y0
            for (x in 0 until dw) {
                val fx = ((x + 0.5f) * sw / dw - 0.5f).coerceIn(0f, (sw - 1).toFloat())
                val x0 = fx.toInt(); val x1 = minOf(x0 + 1, sw - 1); val wx = fx - x0
                val a = src[y0 * sw + x0]; val b = src[y0 * sw + x1]; val c = src[y1 * sw + x0]; val d = src[y1 * sw + x1]
                fun ch(sh: Int): Int {
                    val top = ((a shr sh) and 0xFF) * (1 - wx) + ((b shr sh) and 0xFF) * wx
                    val bot = ((c shr sh) and 0xFF) * (1 - wx) + ((d shr sh) and 0xFF) * wx
                    return (top * (1 - wy) + bot * wy).toInt().coerceIn(0, 255)
                }
                dst[y * dw + x] = (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
            }
        }
    }

    private fun maskColor(m: Bitonal, i: Int, x: Int, y: Int, palette: IntArray?, fg: IntArray?, fgW: Int, fgH: Int): Int = when {
        palette != null && m.indexed -> palette[m.colorIndex(i).coerceIn(0, palette.size - 1)]
        fg != null -> fg[(y.toLong() * fgH / m.height).toInt().coerceIn(0, fgH - 1) * fgW + (x.toLong() * fgW / m.width).toInt().coerceIn(0, fgW - 1)]
        else -> 0xFF000000.toInt()
    }

    private fun blend(bg: Int, color: Int, alpha: Float): Int {
        val br = (bg shr 16) and 0xFF; val bgg = (bg shr 8) and 0xFF; val bb = bg and 0xFF
        val r = (color shr 16) and 0xFF; val g = (color shr 8) and 0xFF; val b = color and 0xFF
        return (0xFF shl 24) or ((br + (r - br) * alpha).toInt() shl 16) or ((bgg + (g - bgg) * alpha).toInt() shl 8) or (bb + (b - bb) * alpha).toInt()
    }

    /**
     * Composites the mask over [out]. Downscaling accumulates one output row at a time (row-sized
     * buffers only); upscaling samples the mask per output pixel (bilinear coverage) so zoomed text
     * stays solid instead of a grid of dots.
     */
    private fun compositeMask(m: Bitonal, out: IntArray, ow: Int, oh: Int, palette: IntArray?, fg: IntArray?, fgW: Int, fgH: Int) {
        val mw = m.width; val mh = m.height
        if (mw <= 0 || mh <= 0) return
        if (ow > mw || oh > mh) { compositeUpscaled(m, out, ow, oh, palette, fg, fgW, fgH); return }
        val cov = IntArray(ow); val cr = IntArray(ow); val cg = IntArray(ow); val cb = IntArray(ow); val area = IntArray(ow)
        var curOy = 0
        fun flush(oy: Int) {
            val base = oy * ow
            for (ox in 0 until ow) {
                val c = cov[ox]
                if (c != 0) {
                    val a = maxOf(area[ox], 1)
                    // Slight boost so thin strokes stay legible when downscaled.
                    val alpha = minOf(1f, c.toFloat() / a * 1.25f)
                    val color = (0xFF shl 24) or ((cr[ox] / c) shl 16) or ((cg[ox] / c) shl 8) or (cb[ox] / c)
                    out[base + ox] = blend(out[base + ox], color, alpha)
                }
            }
            cov.fill(0); cr.fill(0); cg.fill(0); cb.fill(0); area.fill(0)
        }
        for (y in 0 until mh) {
            val oy = (y.toLong() * oh / mh).toInt()
            if (oy != curOy) { flush(curOy); curOy = oy }
            val row = y * mw
            for (x in 0 until mw) {
                val ox = (x.toLong() * ow / mw).toInt()
                area[ox]++
                if (m.data[row + x].toInt() == 0) continue
                cov[ox]++
                val color = maskColor(m, row + x, x, y, palette, fg, fgW, fgH)
                cr[ox] += (color shr 16) and 0xFF; cg[ox] += (color shr 8) and 0xFF; cb[ox] += color and 0xFF
            }
        }
        flush(curOy)
    }

    private fun compositeUpscaled(m: Bitonal, out: IntArray, ow: Int, oh: Int, palette: IntArray?, fg: IntArray?, fgW: Int, fgH: Int) {
        val mw = m.width; val mh = m.height
        val d = m.data
        for (oy in 0 until oh) {
            val fy = ((oy + 0.5f) * mh / oh - 0.5f).coerceIn(0f, (mh - 1).toFloat())
            val y0 = fy.toInt(); val y1 = minOf(y0 + 1, mh - 1); val wy = fy - y0
            for (ox in 0 until ow) {
                val fx = ((ox + 0.5f) * mw / ow - 0.5f).coerceIn(0f, (mw - 1).toFloat())
                val x0 = fx.toInt(); val x1 = minOf(x0 + 1, mw - 1); val wx = fx - x0
                val i00 = y0 * mw + x0; val i01 = y0 * mw + x1; val i10 = y1 * mw + x0; val i11 = y1 * mw + x1
                val a00 = d[i00].toInt() != 0; val a01 = d[i01].toInt() != 0; val a10 = d[i10].toInt() != 0; val a11 = d[i11].toInt() != 0
                if (!a00 && !a01 && !a10 && !a11) continue
                var alpha = 0f
                if (a00) alpha += (1 - wx) * (1 - wy)
                if (a01) alpha += wx * (1 - wy)
                if (a10) alpha += (1 - wx) * wy
                if (a11) alpha += wx * wy
                // Sharpen the edge a little (ink is binary; plain bilinear looks blurry when zoomed).
                alpha = ((alpha - 0.5f) * 2f + 0.5f).coerceIn(0f, 1f)
                if (alpha <= 0f) continue
                // Colour of the nearest inked neighbour.
                val near = when {
                    (if (wx < 0.5f) (if (wy < 0.5f) a00 else a10) else (if (wy < 0.5f) a01 else a11)) ->
                        if (wx < 0.5f) (if (wy < 0.5f) i00 else i10) else (if (wy < 0.5f) i01 else i11)
                    a00 -> i00; a01 -> i01; a10 -> i10; else -> i11
                }
                val color = maskColor(m, near, near % mw, near / mw, palette, fg, fgW, fgH)
                val oi = oy * ow + ox
                out[oi] = blend(out[oi], color, alpha)
            }
        }
    }

    override fun renderRegion(page: Int, box: Box, targetWidth: Int): ImageData? {
        val (pw, _) = pageSize(page)
        val scale = targetWidth / box.width.coerceAtLeast(1f)
        val full = renderPage(page, (pw * scale).toInt().coerceIn(64, 4000))
        val s = full.width / pw
        val x0 = (box.x0 * s).toInt().coerceIn(0, full.width - 1); val y0 = (box.y0 * s).toInt().coerceIn(0, full.height - 1)
        val x1 = (box.x1 * s).toInt().coerceIn(x0 + 1, full.width); val y1 = (box.y1 * s).toInt().coerceIn(y0 + 1, full.height)
        val w = x1 - x0; val h = y1 - y0
        val px = IntArray(w * h)
        for (y in 0 until h) System.arraycopy(full.argb, (y0 + y) * full.width + x0, px, y * w, w)
        return ImageData.Raster(w, h, px)
    }

    override fun cover(): ImageData? = render(0, 600)

    override fun close() = raf.close()

    companion object {
        /** Raw page forms kept in memory by default. */
        const val REF_CACHE_PAGES = 8

    }
}
