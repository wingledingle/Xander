package app.quinten.analysis.paged

import app.quinten.analysis.page.Box
import app.quinten.analysis.page.PageCleaner
import app.quinten.analysis.page.PageReconstructor
import app.quinten.analysis.page.PageText
import app.quinten.model.BookDocument
import app.quinten.model.BookFormat
import app.quinten.model.BookMetadata
import app.quinten.model.Chapter
import app.quinten.model.CleanOptions
import app.quinten.model.ImageBlock
import app.quinten.model.ImageData
import app.quinten.model.LinkTarget
import app.quinten.model.Locator
import app.quinten.model.TocEntry
import java.io.Closeable

data class OutlineEntry(val title: String, val level: Int, val page: Int)

/** A fixed-layout source: PDF, DjVu, image archives. All page indexes are 0-based. */
interface PageSource : Closeable {
    val pageCount: Int
    val metadata: BookMetadata
    val outline: List<OutlineEntry> get() = emptyList()
    fun pageSize(page: Int): Pair<Float, Float>

    /** Embedded text layer (PDF text, DjVu hidden text) or null when the page has none. */
    fun textLayer(page: Int): PageText?

    /** Renders the page; [targetWidth] in pixels. */
    fun render(page: Int, targetWidth: Int): ImageData?

    /** Renders a region of the page given in page coordinates (illustrations). */
    fun renderRegion(page: Int, box: Box, targetWidth: Int): ImageData? = null

    /** Cover image (defaults to the first page). */
    fun cover(): ImageData? = render(0, 600)
}

/** OCR backend (Tesseract on Android). Implementations must be thread-safe or synchronize. */
interface OcrEngine {
    val isAvailable: Boolean
    /** The recognition languages (e.g. "eng+deu"); OCR results are cached per language set. */
    val languages: String get() = ""
    /** Recognises [image] of a page whose size in page units is [pageWidth] x [pageHeight]. */
    fun recognize(image: ImageData, pageIndex: Int, pageWidth: Float, pageHeight: Float): PageText?
}

/** Persists page text (mainly OCR results) between sessions. */
interface PageTextStore {
    fun load(page: Int): PageText?
    fun save(page: Int, text: PageText)
    /** Tells the store which OCR languages produced OCR pages (OCR pages from other languages are ignored). */
    fun configure(ocrKey: String) {}
    /** Small per-book facts (e.g. whether re-recognising garbled pages helps for this book). */
    fun meta(key: String): String? = null
    fun putMeta(key: String, value: String) {}
}

enum class OcrMode { AUTO, ALWAYS, NEVER }

/** Rasters a fixed-layout page into ARGB pixels; supplied by the platform for encoded images. */
fun interface RasterDecoder {
    fun decode(data: ImageData, maxWidth: Int): ImageData.Raster?
}

/**
 * Reflowable view over a fixed-layout [PageSource]. Pages are grouped into chapters (outline
 * entries or fixed-size chunks); text comes from the text layer or OCR; layout is rebuilt with
 * [PageReconstructor] and Clean Reading Mode with [PageCleaner].
 */
open class PagedBookDocument(
    private val source: PageSource,
    override val format: BookFormat,
    private val ocr: OcrEngine? = null,
    private val store: PageTextStore? = null,
    private val decoder: RasterDecoder? = null,
    var ocrMode: OcrMode = OcrMode.AUTO,
    private val ocrWidth: Int = 1800,
    chunkPages: Int = 3,
) : BookDocument {
    /** Notified while slow pages (OCR) are processed: page index and completion fraction. */
    @Volatile var progressListener: ((page: Int, done: Int, total: Int) -> Unit)? = null

    override val metadata: BookMetadata get() = source.metadata

    private val starts: IntArray = chapterStarts(source.pageCount, source.outline, chunkPages)
    override val chapterCount: Int get() = starts.size

    fun pagesOf(chapter: Int): IntRange = starts[chapter] until (if (chapter + 1 < starts.size) starts[chapter + 1] else source.pageCount)
    fun chapterOfPage(page: Int): Int = starts.indexOfLast { it <= page }.coerceAtLeast(0)

    override val toc: List<TocEntry> = source.outline.map { o ->
        TocEntry(o.title, o.level, chapterOfPage(o.page), unit = o.page * PageReconstructor.UNIT_STRIDE)
    }

    override fun chapterTitle(index: Int): String? =
        source.outline.lastOrNull { it.page <= starts[index] }?.title

    override fun chapterWeight(index: Int): Int = pagesOf(index).count() * 2000

    private val cache = object : LinkedHashMap<Int, PageText>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, PageText>?) = size > 80
    }

    /** Pages whose text could not be obtained (no text layer and OCR unavailable). */
    val imageOnlyPages = java.util.Collections.synchronizedSet(HashSet<Int>())

    private fun looksBroken(p: PageText): Boolean {
        val text = p.lines.joinToString(" ") { it.text }
        if (text.isBlank()) return true
        // Tables, indexes and maths are mostly digits and symbols: those are fine. Only replacement
        // characters, private-use glyphs and control codes mean the font encoding is broken.
        val bad = text.count { it == '�' || (it.code in 0xE000..0xF8FF) || (it.isISOControl() && it != '\t') }
        val meaningful = text.count { it.isLetterOrDigit() }
        return bad > text.length * 0.1 || meaningful < text.count { !it.isWhitespace() } * 0.2
    }

    // Words seen in this book (names, rare words): helps repair OCR errors on later pages.
    private val bookWords = HashMap<String, Int>()
    private fun bookFreq(w: String): Int = synchronized(bookWords) { bookWords[w] ?: 0 }
    private val corrector = app.quinten.analysis.text.OcrCorrector(::bookFreq)
    private val wordRe = Regex("\\p{L}{3,}")

    init { store?.configure(ocr?.languages ?: "") }

    // Garbled text layers are re-recognised. The tally is kept with the book: when OCR stops winning
    // (e.g. a non-English book judged by the English word list) the check is switched off for good.
    private val rescueTries = java.util.concurrent.atomic.AtomicInteger()
    private val rescueWins = java.util.concurrent.atomic.AtomicInteger()
    init {
        store?.meta("rescue")?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.takeIf { it.size == 2 }?.let { (t, w) ->
            rescueTries.set(t); rescueWins.set(w)
        }
    }
    private val rescueEnabled get() = rescueTries.get() < 3 || rescueWins.get() * 4 >= rescueTries.get() * 3
    private fun recordRescue(won: Boolean) {
        rescueTries.incrementAndGet(); if (won) rescueWins.incrementAndGet()
        store?.putMeta("rescue", "${rescueTries.get()},${rescueWins.get()}")
    }

    // One thread per page: the current section and the prefetch must not OCR the same page twice.
    private val pageLocks = java.util.concurrent.ConcurrentHashMap<Int, Any>()
    // Each page's words are learned once (re-learning on every reload made OCR junk look like real words).
    private val learnedPages = HashSet<Int>()

    /** Fixes OCR confusions and learns the page's words. */
    private fun finish(t: PageText): PageText {
        // Born-digital text is exactly what the author wrote ("t.lt(90)", "divided by 2"): only text
        // read from a picture of the page is repaired.
        val fixed = if (t.recognised) corrector.correct(t) else t
        synchronized(bookWords) {
            if (learnedPages.add(t.pageIndex) && bookWords.size < 50_000) for (l in fixed.lines) for (m in wordRe.findAll(l.text)) {
                val w = m.value.lowercase()
                bookWords[w] = (bookWords[w] ?: 0) + 1
            }
        }
        return fixed
    }

    /**
     * A text layer that is mostly non-words (bad embedded OCR, broken font encoding). Judged by the
     * reference word list only: a broken encoding repeats the same junk on every page, so words
     * "seen in this book" must not count here.
     */
    private fun garbled(t: PageText): Boolean {
        val q = app.quinten.analysis.text.TextQuality.score(corrector.correct(t))
        // Only clear garbage: OCR text is rougher than a decent text layer (stray symbols, page numbers
        // that are harder to spot), so a text layer that is merely imperfect is kept.
        return q.words >= 25 && q.knownFraction < 0.4
    }

    /** Returns page text, running OCR when required. Cheap pages are cached in memory. */
    fun pageText(page: Int, allowOcr: Boolean = true): PageText? {
        synchronized(cache) { cache[page] }?.let { return it }
        return synchronized(pageLocks.getOrPut(page) { Any() }) {
            synchronized(cache) { cache[page] } ?: computePageText(page, allowOcr)
        }
    }

    private fun computePageText(page: Int, allowOcr: Boolean): PageText? {
        // Disk cache (text layer and OCR results) makes reopening and re-reading instant.
        store?.load(page)?.let { stored ->
            val usable = (ocrMode != OcrMode.ALWAYS || stored.ocr) && (stored.lines.isNotEmpty() || stored.images.isNotEmpty())
            if (usable) {
                val f = finish(stored)
                synchronized(cache) { cache[page] = f }
                return f
            }
        }
        val layer = if (ocrMode == OcrMode.ALWAYS) null else source.textLayer(page)
        val needsOcr = layer == null || (layer.lines.sumOf { it.text.length } < 15) || looksBroken(layer)
        var result: PageText? = layer?.takeIf { !needsOcr }
        if (needsOcr && ocrMode != OcrMode.NEVER) {
            result = store?.load(page)?.takeIf { it.ocr }
            if (result == null && allowOcr && ocr?.isAvailable == true) {
                result = runOcr(page)
                if (result != null) store?.save(page, result)
            }
        } else if (result != null && allowOcr && canOcr() && rescueEnabled && garbled(result)) {
            // The embedded text is mostly garbage: recognise the page ourselves and keep the better one.
            val o = runOcr(page)
            if (o != null) {
                val qo = app.quinten.analysis.text.TextQuality.score(corrector.correct(o)).knownFraction
                val ql = app.quinten.analysis.text.TextQuality.score(corrector.correct(result)).knownFraction
                val won = qo > ql + 0.25
                recordRescue(won)
                if (won) { result = o; store?.save(page, o) }
            }
        }
        if (result == null && layer != null && layer.lines.isNotEmpty()) result = layer
        if (result != null && !result.ocr && result.lines.isNotEmpty()) store?.save(page, result)
        if (result == null) {
            if (!allowOcr) return null
            imageOnlyPages += page
            result = PageText(page, source.pageSize(page).first, source.pageSize(page).second, emptyList())
        } else {
            imageOnlyPages -= page
            result = finish(result)
        }
        synchronized(cache) { cache[page] = result }
        return result
    }

    private fun canOcr() = ocrMode == OcrMode.AUTO && ocr?.isAvailable == true

    private fun runOcr(page: Int): PageText? {
        val img = source.render(page, ocrWidth) ?: return null
        val (w, h) = source.pageSize(page)
        // A failing OCR engine (native library missing, out of memory) leaves the page as it is.
        val text = try { ocr!!.recognize(img, page, w, h) } catch (_: Throwable) { null } ?: return null
        // Illustrations: dark regions not covered by recognised text.
        val raster = (img as? ImageData.Raster) ?: decoder?.decode(img, 900)
        val figures = if (raster != null) IllustrationDetector.find(raster, text, w, h) else emptyList()
        return PageText(text.pageIndex, text.width, text.height, text.lines, text.images + figures.map {
            app.quinten.analysis.page.PageImage(it, regionResource(page, it))
        }, text.columnOf, text.columns, ocr = true)
    }

    override fun loadChapter(index: Int, clean: CleanOptions): Chapter {
        val range = pagesOf(index)
        val first = range.first
        val last = range.last
        val total = range.count()
        // The page before and after are always read (OCR'd if needed): a paragraph crossing a section
        // edge belongs to the section where it starts, and both sections must agree on where it is.
        // Using only pages that happened to be cached made lines vanish or repeat every few pages.
        val needed = (first - 1..last + 1).filter { it in 0 until source.pageCount }
        val texts = ArrayList<PageText>()
        val pictures = HashSet<Int>()
        fun add(p: Int) {
            val t = pageText(p, allowOcr = true) ?: return
            // Comic panels, posters, maps: reflowing their scraps of text wrecks them; show the page whole.
            if (isPicturePage(t)) { pictures += p; return }
            texts += withoutImageText(t)
        }
        for ((k, p) in needed.withIndex()) {
            if (p in range) progressListener?.invoke(p, k, needed.size)
            add(p)
        }
        progressListener?.invoke(last, total, total)

        fun build(): Chapter {
            // Clean-mode window: the pages read above plus neighbours already known, in memory or saved
            // from earlier reading (more pages = page numbers and headers recognised reliably).
            val window = ArrayList(texts)
            for (p in (first - 6..last + 6)) {
                if (p !in 0 until source.pageCount || window.any { it.pageIndex == p }) continue
                val cached = synchronized(cache) { cache[p] } ?: store?.load(p)
                if (cached != null && !isPicturePage(cached)) window += cached
            }
            val removals = PageCleaner.detect(window.sortedBy { it.pageIndex }, texts.map { it.pageIndex }.toSet())
            return PageReconstructor.reconstruct(index, chapterTitle(index), texts, first, last, removals, clean)
        }
        var chapter = build()
        // The last paragraph started here but is still running at the bottom of the last page read:
        // keep reading (at most a few pages) until it ends, so it is complete in this section.
        var lastRead = texts.maxOfOrNull { it.pageIndex } ?: last
        var extra = 0
        while (lastRead > last && lastRead + 1 < source.pageCount && extra++ < 4) {
            val endPage = texts.firstOrNull { it.pageIndex == lastRead } ?: break
            if (endPage.lines.isEmpty()) break
            val endUnit = lastRead * PageReconstructor.UNIT_STRIDE + endPage.lines.size - 1
            val lastText = chapter.blocks.lastOrNull { it is app.quinten.model.TextBlock } ?: break
            if (lastText.units.last() != endUnit) break
            add(lastRead + 1); lastRead++
            chapter = build()
        }
        // Second repair pass on whole paragraphs: a misread "1"/"7" for "I" at the end of a printed
        // line can only be judged with the next line's first word.
        val recognisedPages = texts.filter { it.recognised }.mapTo(HashSet()) { it.pageIndex }
        val blocks = ArrayList(chapter.blocks.map { b ->
            if (b is app.quinten.model.TextBlock && b.sourcePage in recognisedPages) {
                val t = corrector.correctLine(b.text)
                // Words run together across a line break ("wellto-" / "do") are whole only now.
                val (split, ins) = corrector.splitJoined(t)
                if (ins.isEmpty()) { if (t != b.text) b.copy(text = t) else b }
                else {
                    fun moved(i: Int) = i + ins.count { it < i }
                    val spans = b.spans.map { sp ->
                        val st = moved(sp.start); val en = moved(sp.end)
                        when (sp) {
                            is app.quinten.model.StyleSpan -> sp.copy(start = st, end = en)
                            is app.quinten.model.LinkSpan -> sp.copy(start = st, end = en)
                            is app.quinten.model.RemovedSpan -> sp.copy(start = st, end = en)
                        }
                    }
                    b.copy(text = split, spans = spans, unitStarts = IntArray(b.unitStarts.size) { moved(b.unitStarts[it]) })
                }
            } else b
        })
        // Pages without any text (scans without OCR, full-page pictures) are shown as images.
        for (p in range) {
            val t = texts.firstOrNull { it.pageIndex == p }
            if (p in pictures || t == null || (t.lines.isEmpty() && t.images.isEmpty())) {
                val unit = p * PageReconstructor.UNIT_STRIDE
                val img = ImageBlock(pageResource(p), source.pageSize(p).first.toInt(), source.pageSize(p).second.toInt(), sourcePage = p, units = intArrayOf(unit), fullPage = true)
                val pos = blocks.indexOfFirst { it.firstUnit > unit }.let { if (it < 0) blocks.size else it }
                blocks.add(pos, img)
            }
        }
        return Chapter(index, chapter.title, blocks, chapter.removals)
    }


    override fun image(resource: String): ImageData? {
        val parts = resource.split(':')
        return when (parts.getOrNull(0)) {
            "page" -> parts.getOrNull(1)?.toIntOrNull()?.let { source.render(it, 1400) }
            "region" -> {
                val page = parts.getOrNull(1)?.toIntOrNull() ?: return null
                val c = parts.getOrNull(2)?.split(',')?.mapNotNull { it.toFloatOrNull() } ?: return null
                if (c.size != 4) return null
                source.renderRegion(page, Box(c[0], c[1], c[2], c[3]), 1400) ?: cropFromPage(page, Box(c[0], c[1], c[2], c[3]))
            }
            "cover" -> source.cover()
            else -> null
        }
    }

    private fun cropFromPage(page: Int, box: Box): ImageData? {
        val full = source.render(page, 1600) ?: return null
        val r = (full as? ImageData.Raster) ?: decoder?.decode(full, 1600) ?: return null
        val (w, h) = source.pageSize(page)
        val sx = r.width / w; val sy = r.height / h
        val x0 = (box.x0 * sx).toInt().coerceIn(0, r.width - 1); val y0 = (box.y0 * sy).toInt().coerceIn(0, r.height - 1)
        val x1 = (box.x1 * sx).toInt().coerceIn(x0 + 1, r.width); val y1 = (box.y1 * sy).toInt().coerceIn(y0 + 1, r.height)
        val cw = x1 - x0; val ch = y1 - y0
        val px = IntArray(cw * ch)
        for (y in 0 until ch) System.arraycopy(r.argb, (y0 + y) * r.width + x0, px, y * cw, cw)
        return ImageData.Raster(cw, ch, px)
    }

    override fun cover(): ImageData? = source.cover()

    override val originalPageCount: Int get() = source.pageCount
    override fun originalPage(page: Int, targetWidth: Int): ImageData? = source.render(page, targetWidth)
    override fun locatorForOriginalPage(page: Int) = Locator(chapterOfPage(page), page * PageReconstructor.UNIT_STRIDE, 0)

    override fun resolveLink(target: String): Locator? {
        if (target.startsWith("page:")) return target.substring(5).toIntOrNull()?.let { locatorForOriginalPage(it) }
        val t = LinkTarget.parse(target) ?: return null
        if (t.anchor == null) return Locator(t.chapter, 0, 0)
        return super.resolveLink(target)
    }

    override fun close() = source.close()

    companion object {
        /**
         * True for pages that are mostly artwork with a little text (comic pages, posters, maps).
         * Prose pages with an illustration keep plenty of text and stay reflowable.
         */
        fun isPicturePage(t: PageText): Boolean {
            val area = t.width * t.height
            if (area <= 0f || t.lines.isEmpty()) return false
            val letters = t.lines.sumOf { l -> l.text.count { it.isLetter() } }
            if (letters > 700) return false
            // Text set in normal-width lines (a chapter end, a poem, a title page) is a text page;
            // comic speech bubbles are narrow.
            val wideLetters = t.lines.filter { it.box.width > t.width * 0.4f }.sumOf { l -> l.text.count { it.isLetter() } }
            if (wideLetters > letters * 0.5) return false
            val textArea = t.lines.sumOf { (it.box.width * it.box.height).toDouble() } / area
            val imageArea = coverage(t.images.map { it.box }, t.width, t.height)
            // Scattered short lines (speech bubbles) rather than paragraphs.
            val avgLine = letters.toFloat() / t.lines.size
            return when {
                imageArea > 0.5 && textArea < 0.2 -> true
                imageArea > 0.3 && letters < 300 && textArea < 0.12 -> true
                t.ocr && imageArea > 0.25 && avgLine < 18 && letters < 450 -> true
                else -> false
            }
        }

        /**
         * Text pages: page-sized background images (the scan under its text layer) are dropped, and
         * labels inside a figure are left to the figure picture instead of appearing a second time as
         * stray text lines. If most of the text sits inside an "image", that image is a scan: it goes.
         */
        fun withoutImageText(t: PageText): PageText {
            if (t.images.isEmpty() || t.lines.isEmpty()) return t
            val area = t.width * t.height
            val letters = t.lines.sumOf { l -> l.text.count { it.isLetter() } }.coerceAtLeast(1)
            fun inside(l: app.quinten.analysis.page.TextLine, b: Box) = l.box.cx >= b.x0 && l.box.cx <= b.x1 && l.box.cy >= b.y0 && l.box.cy <= b.y1
            val keepImages = t.images.filter { img ->
                val b = img.box
                val covered = t.lines.filter { inside(it, b) }.sumOf { l -> l.text.count { it.isLetter() } }
                b.width * b.height < area * 0.7f && covered < letters * 0.3
            }
            val keepLines = t.lines.indices.filter { i -> keepImages.none { inside(t.lines[i], it.box) } }
            if (keepImages.size == t.images.size && keepLines.size == t.lines.size) return t
            return PageText(
                t.pageIndex, t.width, t.height, keepLines.map { t.lines[it] }, keepImages,
                IntArray(keepLines.size) { t.columnOf.getOrElse(keepLines[it]) { 0 } }, t.columns, t.ocr, t.scanned,
            )
        }

        /** Fraction of the page covered by the union of [boxes], on a coarse grid. */
        private fun coverage(boxes: List<Box>, w: Float, h: Float): Double {
            if (boxes.isEmpty()) return 0.0
            val n = 40
            var hit = 0
            for (gy in 0 until n) for (gx in 0 until n) {
                val x = (gx + 0.5f) * w / n; val y = (gy + 0.5f) * h / n
                if (boxes.any { x >= it.x0 && x < it.x1 && y >= it.y0 && y < it.y1 }) hit++
            }
            return hit.toDouble() / (n * n)
        }

        fun pageResource(page: Int) = "page:$page"
        fun regionResource(page: Int, b: Box) = "region:$page:${b.x0},${b.y0},${b.x1},${b.y1}"

        fun chapterStarts(pageCount: Int, outline: List<OutlineEntry>, chunk: Int): IntArray {
            if (pageCount <= 0) return intArrayOf(0)
            val starts = sortedSetOf(0)
            // Outline entries make natural chapter boundaries.
            outline.filter { it.level <= 1 && it.page in 1 until pageCount }.forEach { starts += it.page }
            // Keep chapters small enough for quick loading (OCR runs per chapter).
            val list = starts.toMutableList()
            val out = ArrayList<Int>()
            for ((i, s) in list.withIndex()) {
                val end = if (i + 1 < list.size) list[i + 1] else pageCount
                var p = s
                while (p < end) { out += p; p += chunk }
            }
            return out.toIntArray()
        }
    }
}
