package app.quinten.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import app.quinten.model.BookDocument
import app.quinten.model.Chapter
import app.quinten.model.CleanOptions
import app.quinten.model.ImageBlock
import app.quinten.model.ImageData
import app.quinten.model.Locator
import app.quinten.model.LocatorRange
import app.quinten.model.TextBlock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** Render position: chapter, block index and character offset in the block. */
data class Pos(val chapter: Int, val block: Int, val offset: Int) : Comparable<Pos> {
    override fun compareTo(other: Pos) = compareValuesBy(this, other, { it.chapter }, { it.block }, { it.offset })
}

/** A row slice of a block placed on a page at vertical position [y]. */
data class PageRow(val chapter: Int, val block: Int, val rowStart: Int, val rowEnd: Int, val y: Int, val height: Int, val trimTop: Int = 0)

class Page(val rows: List<PageRow>, val start: Pos, val end: Pos?, val loadingChapter: Int = -1) {
    val isLoading get() = loadingChapter >= 0
}

/** A highlight/note in the book, for drawing. */
data class Annotation(val id: Long, val range: LocatorRange, val color: Int, val isNote: Boolean)

/**
 * Owns the document, loads chapters in the background and paginates with "anchored" pages: a
 * page starts at any position and fills forward with layout rows, so jumping anywhere is instant
 * and huge books never need full pagination. Main-thread confined except for loading.
 */
class ReaderController(
    val document: BookDocument,
    private val density: Float,
    scaledDensity: Float,
) {
    private val main = Handler(Looper.getMainLooper())
    private val loader = Executors.newFixedThreadPool(2) { r -> Thread(r, "chapter-loader").apply { priority = Thread.NORM_PRIORITY - 1 } }
    private val imageLoader = Executors.newFixedThreadPool(2) { r -> Thread(r, "image-loader") }
    private val chapters = ConcurrentHashMap<Int, Chapter>()
    private val loading = ConcurrentHashMap.newKeySet<Int>()
    private val failed = ConcurrentHashMap<Int, String>()
    private val layouter = BlockLayouter(density, scaledDensity)
    private val layouts = LruCache<Long, BlockLayout>(600)
    private val images = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val imageSizes = ConcurrentHashMap<String, Pair<Int, Int>>()
    private val imagesLoading = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var generation = 0

    /** Called on the main thread when content changed and the view should redraw / repaginate. */
    var onContentChanged: (() -> Unit)? = null

    var settings: ReaderSettings = ReaderSettings()
        set(value) { if (field != value) { field = value; layouts.evictAll() } }

    var clean: CleanOptions = CleanOptions.OFF
        set(value) {
            if (field != value) {
                field = value
                generation++
                chapters.clear(); layouts.evictAll(); failed.clear()
            }
        }

    var contentWidth = 1; private set
    var contentHeight = 1; private set

    fun setViewport(width: Int, height: Int) {
        if (width != contentWidth || height != contentHeight) {
            contentWidth = width.coerceAtLeast(1); contentHeight = height.coerceAtLeast(1)
            layouts.evictAll()
        }
    }

    /** Continuous documents (scans, PDFs, comics) flow across chapter boundaries without page breaks. */
    val continuous: Boolean = document.originalPageCount > 0

    val chapterCount get() = document.chapterCount

    // ---------------- annotations & transient decorations ----------------

    private var annotations: List<Annotation> = emptyList()
    var searchHighlight: Pair<Locator, Int>? = null
        set(v) { field = v; layouts.evictAll() }
    /** Range currently spoken by text-to-speech. */
    var ttsRange: Pair<Pos, Int>? = null
        set(v) { val old = field; field = v; old?.let { evictBlock(it.first) }; v?.let { evictBlock(it.first) } }
    var selection: Pair<Pos, Pos>? = null
        set(v) { val old = field; field = v; old?.let { evictRange(it) }; v?.let { evictRange(it) } }

    fun setAnnotations(list: List<Annotation>) {
        annotations = list; layouts.evictAll()
        // Redraw now so a new or deleted highlight shows at once.
        onContentChanged?.invoke()
    }

    private fun evictBlock(p: Pos) = layouts.remove(key(p.chapter, p.block))
    private fun evictRange(r: Pair<Pos, Pos>) {
        val (a, b) = r
        if (a.chapter == b.chapter) for (bl in a.block..b.block) layouts.remove(key(a.chapter, bl)) else layouts.evictAll()
    }

    private fun decorations(ch: Chapter, blockIndex: Int): List<Decoration> {
        val out = ArrayList<Decoration>()
        val block = ch.blocks[blockIndex]
        val blockStart = Pos(ch.index, blockIndex, 0)
        val blockEnd = Pos(ch.index, blockIndex, block.length)
        for (a in annotations) {
            if (a.range.end.chapter < ch.index || a.range.start.chapter > ch.index) continue
            val s = posOf(ch, a.range.start); val e = posOf(ch, a.range.end, isEnd = true)
            if (e < blockStart || s > blockEnd) continue
            val st = if (s.block == blockIndex) s.offset else if (s < blockStart) 0 else continue
            val en = if (e.block == blockIndex) e.offset else if (e > blockEnd) block.length else continue
            out += Decoration(st, en, a.color, if (a.isNote) Decoration.Kind.NOTE else Decoration.Kind.HIGHLIGHT)
        }
        searchHighlight?.let { (loc, len) ->
            if (loc.chapter == ch.index) {
                val p = ch.positionOf(loc)
                if (p.first == blockIndex) out += Decoration(p.second, p.second + len, 0x66FFC107, Decoration.Kind.SEARCH)
            }
        }
        ttsRange?.let { (p, len) -> if (p.chapter == ch.index && p.block == blockIndex) out += Decoration(p.offset, p.offset + len, 0x5534A853, Decoration.Kind.TTS) }
        selection?.let { (a, b) ->
            if (a.chapter == ch.index || b.chapter == ch.index) {
                val bs = Pos(ch.index, blockIndex, 0)
                if (!(b < bs || a > Pos(ch.index, blockIndex, block.length))) {
                    val st = if (a.block == blockIndex && a.chapter == ch.index) a.offset else 0
                    val en = if (b.block == blockIndex && b.chapter == ch.index) b.offset else block.length
                    out += Decoration(st, en, settings.theme.selection, Decoration.Kind.SELECTION)
                }
            }
        }
        return out
    }

    private fun posOf(ch: Chapter, loc: Locator, isEnd: Boolean = false): Pos {
        if (loc.chapter < ch.index) return Pos(ch.index, 0, 0)
        if (loc.chapter > ch.index) return Pos(ch.index, ch.blocks.size, 0)
        val (b, o) = ch.positionOf(loc, isEnd)
        return Pos(ch.index, b, o)
    }

    // ---------------- chapters ----------------

    fun chapter(i: Int): Chapter? {
        if (i !in 0 until document.chapterCount) return null
        chapters[i]?.let { return it }
        requestChapter(i)
        return null
    }

    fun chapterError(i: Int): String? = failed[i]

    fun requestChapter(i: Int) {
        if (i !in 0 until document.chapterCount || chapters.containsKey(i) || failed.containsKey(i) || !loading.add(i)) return
        val gen = generation
        val opts = clean
        loader.execute {
            val result = try { Result.success(document.loadChapter(i, opts)) } catch (t: Throwable) { Result.failure(t) }
            main.post {
                loading.remove(i)
                if (gen != generation) { requestChapter(i); return@post }
                result.onSuccess { chapters[i] = it }.onFailure { failed[i] = it.message ?: it.javaClass.simpleName }
                trimChapters(i)
                onContentChanged?.invoke()
            }
        }
    }

    /** Synchronous load (used by search / TTS on a worker thread). */
    fun loadChapterBlocking(i: Int): Chapter = chapters[i] ?: document.loadChapter(i, clean)

    private fun trimChapters(center: Int) {
        if (chapters.size <= 10) return
        chapters.keys.sortedByDescending { kotlin.math.abs(it - center) }.take(chapters.size - 8).forEach { chapters.remove(it) }
    }

    /** Keeps two sections ahead and one behind ready, so page turns rarely wait. */
    fun prefetchAround(c: Int) {
        requestChapter(c + 1)
        if (c > 0) requestChapter(c - 1)
        requestChapter(c + 2)
    }

    // ---------------- layout ----------------

    private fun key(c: Int, b: Int) = (c.toLong() shl 32) or b.toLong()

    fun layout(c: Int, b: Int): BlockLayout? {
        val ch = chapter(c) ?: return null
        if (b !in ch.blocks.indices) return null
        layouts.get(key(c, b))?.let { return it }
        val block = ch.blocks[b]
        val l = layouter.layout(block, contentWidth, contentHeight, settings, if (block is TextBlock) decorations(ch, b) else emptyList(), ::imageSize, b == 0)
        layouts.put(key(c, b), l)
        return l
    }

    fun paint() = layouter.basePaint(settings)

    // ---------------- images ----------------

    private fun imageSize(block: ImageBlock): Pair<Int, Int>? {
        imageSizes[block.resource]?.let { return it }
        if (block.width > 0 && block.height > 0) return block.width to block.height
        val data = try { document.image(block.resource) } catch (_: Throwable) { null }
        val size = when (data) {
            is ImageData.Encoded -> BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeByteArray(data.bytes, 0, data.bytes.size, it) }.let { it.outWidth to it.outHeight }
            is ImageData.Raster -> data.width to data.height
            is ImageData.Platform -> data.width to data.height
            null -> null
        }?.takeIf { it.first > 0 && it.second > 0 }
        if (size != null) imageSizes[block.resource] = size
        return size
    }

    /** Returns a decoded bitmap, or null while it loads in the background. */
    private var bigKey: String? = null
    private var bigBitmap: Bitmap? = null

    /** Frees the large render made for a zoomed page (tens of MB). */
    fun releaseZoomedBitmap() { bigKey = null; bigBitmap = null }

    fun bitmap(resource: String, targetWidth: Int): Bitmap? {
        val k = "$resource@${(targetWidth / 64) * 64}"
        images.get(k)?.let { return it }
        if (k == bigKey) return bigBitmap
        if (imagesLoading.add(k)) {
            imageLoader.execute {
                val bmp = try { decode(resource, targetWidth) } catch (_: Throwable) { null }
                main.post {
                    imagesLoading.remove(k)
                    if (bmp != null) {
                        // A huge zoomed render would evict itself from the cache and be decoded again
                        // forever; it gets its own single slot instead.
                        if (bmp.byteCount > images.maxSize() / 2) { bigKey = k; bigBitmap = bmp } else images.put(k, bmp)
                        onContentChanged?.invoke()
                    }
                }
            }
        }
        return null
    }

    private fun decode(resource: String, targetWidth: Int): Bitmap? {
        // Fixed-layout page renders are requested at the needed width directly.
        val data = if (resource.startsWith("page:")) document.originalPage(resource.substring(5).toInt(), targetWidth) else document.image(resource)
        return when (data) {
            is ImageData.Platform -> data.image as? Bitmap
            is ImageData.Raster -> Bitmap.createBitmap(data.argb, data.width, data.height, Bitmap.Config.ARGB_8888)
            is ImageData.Encoded -> {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(data.bytes, 0, data.bytes.size, o)
                var sample = 1
                while (o.outWidth / (sample * 2) >= targetWidth) sample *= 2
                BitmapFactory.decodeByteArray(data.bytes, 0, data.bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            }
            null -> null
        }
    }

    // ---------------- pagination ----------------

    private fun rowOf(l: BlockLayout, offset: Int): Int = when (l) {
        is BlockLayout.Text -> l.rowForOffset(offset)
        else -> 0
    }

    fun offsetOfRow(l: BlockLayout, row: Int): Int = when (l) {
        is BlockLayout.Text -> if (row >= l.rowCount) l.block.length else l.rowStart(row)
        else -> 0
    }

    /** Normalises a position: moves past the end of a chapter into the next one. */
    fun normalize(p: Pos): Pos? {
        var c = p.chapter; var b = p.block; var o = p.offset
        while (true) {
            if (c >= document.chapterCount) return null
            val ch = chapter(c) ?: return Pos(c, b, o)
            if (b < ch.blocks.size) return Pos(c, b, o)
            c++; b = 0; o = 0
        }
    }

    /** Lays out the page starting at [start]. [limit] stops the page early (used by backward paging). */
    fun pageFrom(start: Pos, limit: Pos? = null): Page {
        val p = buildPage(start, limit)
        if (limit == null && !p.isLoading) recordPage(p.rows, p.end)
        return p
    }

    private fun buildPage(start: Pos, limit: Pos?): Page {
        val rows = ArrayList<PageRow>()
        var y = 0
        var c = start.chapter
        var b = start.block
        var first = true
        var row = -1
        val h = contentHeight
        while (true) {
            if (c >= document.chapterCount) return Page(rows, start, null)
            val ch = chapter(c)
            if (ch == null) {
                return if (rows.isEmpty()) Page(rows, start, start, loadingChapter = c) else Page(rows, start, Pos(c, b, 0))
            }
            if (b >= ch.blocks.size) {
                c++; b = 0; row = -1
                if (c >= document.chapterCount) return Page(rows, start, null)
                if (!continuous && rows.isNotEmpty()) return Page(rows, start, Pos(c, 0, 0))
                continue
            }
            val l = layout(c, b) ?: return Page(rows, start, Pos(c, b, 0), loadingChapter = c)
            if (row < 0) row = if (first) rowOf(l, start.offset) else 0
            first = false
            while (row < l.rowCount) {
                val here = Pos(c, b, offsetOfRow(l, row))
                if (limit != null && here >= limit && rows.isNotEmpty()) return Page(rows, start, limit)
                val rh = l.rowBottom(row) - l.rowTop(row)
                // Paragraph spacing is dropped at the top of a page.
                val effective = if (rows.isEmpty() && row == 0 && l is BlockLayout.Text) rh - l.padTop else rh
                if (y + effective > h && rows.isNotEmpty()) {
                    // Don't leave a heading alone at the bottom of a page.
                    val last = rows.last()
                    val lastLayout = layout(last.chapter, last.block)
                    if (rows.size > 1 && lastLayout is BlockLayout.Text && lastLayout.keepWithNext && last.rowEnd == lastLayout.rowCount) {
                        return Page(rows.dropLast(1), start, Pos(last.chapter, last.block, offsetOfRow(lastLayout, last.rowStart)))
                    }
                    return Page(rows, start, here)
                }
                val prev = rows.lastOrNull()
                if (prev != null && prev.chapter == c && prev.block == b && prev.rowEnd == row) {
                    rows[rows.size - 1] = prev.copy(rowEnd = row + 1, height = prev.height + effective)
                } else {
                    rows += PageRow(c, b, row, row + 1, y, effective, rh - effective)
                }
                y += effective
                row++
            }
            b++; row = -1
        }
    }

    /**
     * Start of the page that ends right before [start], or null when [start] is the beginning of
     * the book or the previous chapter is still loading (it is requested; retry on change).
     */
    fun previousPageStart(start: Pos): Pos? {
        var c = start.chapter
        var b = start.block
        val h = contentHeight
        var l: BlockLayout? = layout(c, b)
        var row = if (l != null) rowOf(l, start.offset) - 1 else -1
        var y = 0
        var result: Pos? = null
        while (true) {
            if (row < 0 || l == null) {
                if (b <= 0) {
                    // A chapter start is always a page start in flowing books.
                    if (!continuous && result != null) return result
                    if (c == 0) return result
                    c--
                    val ch = chapter(c) ?: return result
                    b = ch.blocks.size
                }
                b--
                if (b < 0) { l = null; continue }
                l = layout(c, b) ?: return result
                row = l.rowCount - 1
                continue
            }
            val rh = l.rowBottom(row) - l.rowTop(row)
            if (y + rh > h && result != null) return result
            y += rh
            result = Pos(c, b, offsetOfRow(l, row))
            row--
        }
    }

    // ---------------- positions & progress ----------------

    fun locatorOf(p: Pos, isEnd: Boolean = false): Locator? = chapter(p.chapter)?.let { if (it.blocks.isEmpty()) Locator(p.chapter, 0, 0) else it.locatorAt(p.block.coerceAtMost(it.blocks.size - 1), p.offset, isEnd) }

    fun posOf(loc: Locator): Pos? {
        val ch = chapter(loc.chapter) ?: return null
        val (b, o) = ch.positionOf(loc)
        return Pos(loc.chapter, b, o)
    }

    private val weights: IntArray by lazy { IntArray(document.chapterCount) { document.chapterWeight(it).coerceAtLeast(1) } }
    private val totalWeight: Long by lazy { weights.fold(0L) { a, w -> a + w } }

    fun progressOf(p: Pos): Float {
        if (document.chapterCount == 0) return 0f
        var before = 0L
        for (i in 0 until p.chapter.coerceAtMost(weights.size)) before += weights[i]
        val ch = chapters[p.chapter]
        val frac = if (ch != null && ch.textLength > 0) (ch.charOffsetOf(p.block) + p.offset).toFloat() / ch.textLength else 0f
        return ((before + weights[p.chapter.coerceIn(0, weights.size - 1)] * frac) / totalWeight).toFloat().coerceIn(0f, 1f)
    }

    /** Chapter and in-chapter fraction for an overall progress value. */
    fun chapterForProgress(progress: Float): Pair<Int, Float> {
        val target = (progress.coerceIn(0f, 1f) * totalWeight).toLong()
        var acc = 0L
        for (i in weights.indices) {
            if (acc + weights[i] > target || i == weights.size - 1) return i to ((target - acc).toFloat() / weights[i]).coerceIn(0f, 1f)
            acc += weights[i]
        }
        return 0 to 0f
    }

    fun posInChapter(ch: Chapter, frac: Float): Pos {
        val target = (ch.textLength * frac).toInt()
        var acc = 0
        for ((i, b) in ch.blocks.withIndex()) {
            if (acc + b.length > target) return Pos(ch.index, i, (target - acc).coerceIn(0, b.length))
            acc += b.length
        }
        return Pos(ch.index, (ch.blocks.size - 1).coerceAtLeast(0), 0)
    }

    private var measuredChars = 0L
    private var measuredPages = 0
    private var measuredKey = 0

    /** Records a laid-out page so the page count estimate converges on real pages. */
    private fun recordPage(rows: List<PageRow>, end: Pos?) {
        val key = settings.hashCode() * 31 + contentWidth * 7 + contentHeight
        if (key != measuredKey) { measuredKey = key; measuredChars = 0; measuredPages = 0 }
        if (end == null || measuredPages > 400) return
        var chars = 0
        for (r in rows) {
            val l = layouts.get(key(r.chapter, r.block)) ?: continue
            chars += if (l is BlockLayout.Text) offsetOfRow(l, r.rowEnd) - offsetOfRow(l, r.rowStart) else 0
        }
        if (chars > 0) { measuredChars += chars; measuredPages++ }
    }

    /** Estimated number of pages in the book with the current settings ("page x of y"). */
    fun estimatedPages(): Int {
        // Weights are format-specific (bytes, chars, pages); calibrate with loaded chapters' text.
        var w = 0L; var t = 0L
        for ((i, ch) in chapters) { w += weights.getOrElse(i) { 0 }; t += ch.textLength }
        val charsTotal = if (w > 0) totalWeight * t / w else totalWeight
        val perPage = if (measuredPages >= 2) measuredChars.toFloat() / measuredPages else {
            val paint = paint()
            val charW = paint.measureText("abcdefghijklmnopqrstuvwxyz ") / 27f
            val perLine = (contentWidth / charW.coerceAtLeast(1f)).coerceAtLeast(1f)
            val lines = (contentHeight / (paint.fontSpacing * settings.lineSpacing)).coerceAtLeast(1f)
            perLine * lines * 0.8f
        }
        return (charsTotal / perPage.coerceAtLeast(1f)).toInt().coerceAtLeast(1)
    }

    fun close() {
        loader.shutdownNow(); imageLoader.shutdownNow()
        images.evictAll()
        try { document.close() } catch (_: Exception) {}
    }
}
