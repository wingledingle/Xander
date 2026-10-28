package app.quinten.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Path
import android.graphics.PointF
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import app.quinten.analysis.page.Box
import app.quinten.analysis.page.Glyph
import app.quinten.analysis.page.PageImage
import app.quinten.analysis.page.PageLayout
import app.quinten.analysis.page.PageText
import app.quinten.analysis.paged.OutlineEntry
import app.quinten.analysis.paged.PageSource
import app.quinten.analysis.paged.PagedBookDocument
import app.quinten.model.BookMetadata
import app.quinten.model.ImageData
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.contentstream.PDFGraphicsStreamEngine
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImage
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.File

/** The PDF needs a password ([wrongPassword]: one was given but it is not right). */
class PdfPasswordException(val wrongPassword: Boolean = false) : Exception("This PDF is password protected")

/**
 * PDF page source: text with positions and fonts, embedded images and vector figure regions come
 * from PdfBox-Android (Apache-2.0); rasterisation uses the platform [PdfRenderer].
 * The source file is opened read-only.
 */
class PdfPageSource(context: Context, private val file: File, password: String = "") : PageSource {
    private val doc: PDDocument
    private var pfd: ParcelFileDescriptor
    private val renderer: PdfRenderer?
    private val lock = Any()

    init {
        if (!PDFBoxResourceLoader.isReady()) PDFBoxResourceLoader.init(context.applicationContext)
        doc = try {
            PDDocument.load(file, password, MemoryUsageSetting.setupTempFileOnly())
        } catch (e: InvalidPasswordException) {
            throw PdfPasswordException(wrongPassword = password.isNotEmpty())
        }
        if (doc.isEncrypted) doc.setAllSecurityToBeRemoved(true)
        pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        renderer = try { PdfRenderer(pfd) } catch (_: Exception) {
            // Android's renderer cannot open encrypted PDFs: draw pages from a decrypted copy kept in
            // the app's private cache (the original file is never modified).
            runCatching { pfd.close() }
            val plain = decryptedCopy(context) ?: file
            pfd = ParcelFileDescriptor.open(plain, ParcelFileDescriptor.MODE_READ_ONLY)
            try { PdfRenderer(pfd) } catch (_: Exception) { null }
        }
    }

    override val pageCount: Int get() = doc.numberOfPages

    override val metadata: BookMetadata by lazy {
        synchronized(lock) {
            val info = doc.documentInformation
            BookMetadata(
                title = info?.title?.trim()?.ifBlank { null } ?: file.nameWithoutExtension.replace('_', ' '),
                authors = info?.author?.split(';', ',', '&')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList(),
                subjects = listOfNotNull(info?.subject?.ifBlank { null }),
                description = info?.keywords?.ifBlank { null },
                coverResource = "cover",
            )
        }
    }

    override val outline: List<OutlineEntry> by lazy {
        synchronized(lock) {
            val out = ArrayList<OutlineEntry>()
            fun walk(node: PDOutlineNode, level: Int) {
                var item = node.firstChild
                var guard = 0
                while (item != null && guard++ < 10_000) {
                    val page = try {
                        (item.destination as? PDPageDestination)?.retrievePageNumber()
                            ?: ((item.action as? PDActionGoTo)?.destination as? PDPageDestination)?.retrievePageNumber()
                            ?: item.findDestinationPage(doc)?.let { doc.pages.indexOf(it) }
                    } catch (_: Exception) { null }
                    val title = item.title?.trim().orEmpty()
                    if (page != null && page >= 0 && title.isNotEmpty()) out += OutlineEntry(title, level, page)
                    if (level < 6) walk(item, level + 1)
                    item = item.nextSibling
                }
            }
            doc.documentCatalog.documentOutline?.let { walk(it, 0) }
            out
        }
    }

    private fun pageBox(p: PDPage): Pair<Float, Float> {
        val b = p.cropBox
        return if (p.rotation % 180 != 0) b.height to b.width else b.width to b.height
    }

    override fun pageSize(page: Int): Pair<Float, Float> = synchronized(lock) { pageBox(doc.getPage(page)) }

    override fun textLayer(page: Int): PageText? = synchronized(lock) {
        try { extract(page) } catch (_: Exception) { null }
    }

    private fun extract(page: Int): PageText {
        val pdPage = doc.getPage(page)
        val (w, h) = pageBox(pdPage)
        val glyphs = ArrayList<Glyph>()
        val glyphDirs = ArrayList<Int>()
        // Which text-showing operation (or space-separated part of one) each glyph came from: in a scan's OCR
        // layer that is one word, which PageLayout.fromWords keeps whole.
        val glyphRun = ArrayList<Int>()
        val glyphHidden = ArrayList<Boolean>()
        var run = 0
        var invisible = 0
        val stripper = object : PDFTextStripper() {
            override fun showText(string: ByteArray) { run++; super.showText(string) }
            override fun processTextPosition(tp: TextPosition) {
                val u = tp.unicode ?: return
                if (u.isBlank()) { run++; return }
                // Scanned books carry the scanner's OCR as invisible text over the page picture.
                // Hidden either with the "invisible" text mode or by drawing it fully transparent (Library of Congress,
                // some Internet Archive scans).
                val hidden = graphicsState.textState.renderingMode == com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode.NEITHER ||
                    graphicsState.nonStrokeAlphaConstant < 0.05
                if (hidden) invisible++
                val fontName = tp.font?.name?.lowercase() ?: ""
                val desc = tp.font?.fontDescriptor
                val bold = fontName.contains("bold") || fontName.contains("black") || fontName.contains("heavy") ||
                    (desc?.fontWeight ?: 0f) >= 600f || desc?.isForceBold == true
                val italic = fontName.contains("italic") || fontName.contains("oblique") || desc?.isItalic == true
                val size = if (tp.fontSizeInPt > 0.5f) tp.fontSizeInPt else tp.heightDir * 1.4f
                val gh = tp.heightDir.coerceAtLeast(size * 0.6f)
                // Ligature glyphs (ﬁ, ﬂ, ﬀ...) become their letters, so search, read-aloud and
                // hyphen repair see ordinary words. The glyph box is shared out between the letters.
                // Only real ligature characters are split: any other multi-unit text (accents with
                // combining marks, math letters stored as surrogate pairs) stays one glyph, since
                // splitting it produced broken characters.
                val parts: List<String> = if (u.length == 1 && u[0].code in 0xFB00..0xFB06) {
                    java.text.Normalizer.normalize(u, java.text.Normalizer.Form.NFKC).map { it.toString() }
                } else listOf(u)
                val w = tp.widthDirAdj / parts.size
                for ((k, part) in parts.withIndex()) {
                    val x = tp.xDirAdj + w * k
                    glyphDirs += ((Math.round(tp.dir / 90f) * 90) % 360)
                    glyphRun += run
                    glyphHidden += hidden
                    glyphs += Glyph(
                        part, Box(x, tp.yDirAdj - gh, x + w, tp.yDirAdj),
                        tp.yDirAdj, size, bold, italic, tp.widthOfSpace.takeIf { it > 0 } ?: (size * 0.25f),
                    )
                }
            }
        }
        stripper.startPage = page + 1
        stripper.endPage = page + 1
        stripper.getText(doc)

        val graphics = GraphicsCollector(pdPage)
        try { graphics.processPage(pdPage) } catch (_: Exception) {}
        // Sideways text (chart axis labels, "Downloaded from..." up the margin) is not part of the
        // reading text; when a whole page is rotated, its main direction is kept.
        val mainDir = glyphDirs.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: 0
        val upright = glyphs.filterIndexed { i, _ -> glyphDirs[i] == mainDir }
        val scannedLayer = glyphs.isNotEmpty() && invisible > glyphs.size / 2
        val text = if (scannedLayer) PageLayout.fromWords(page, w, h, ocrWords(glyphs, glyphRun, glyphDirs, mainDir, glyphHidden)) else PageLayout.build(page, w, h, upright)
        val textChars = text.lines.sumOf { it.text.length }
        val pageArea = w * h
        val images = ArrayList<Box>()
        for (b in graphics.images) {
            val clipped = Box(b.x0.coerceIn(0f, w), b.y0.coerceIn(0f, h), b.x1.coerceIn(0f, w), b.y1.coerceIn(0f, h))
            val area = clipped.width * clipped.height
            if (area < pageArea * 0.01f || clipped.width < 20 || clipped.height < 20) continue
            // Full-page backgrounds behind real text are decoration, not illustrations.
            if (area > pageArea * 0.7f && textChars > 200) continue
            images += clipped
        }
        images += vectorFigures(graphics.paths, text, w, h)
        val merged = mergeBoxes(images)
        val scanned = scannedLayer
        return PageText(page, w, h, text.lines, merged.map { PageImage(it, PagedBookDocument.regionResource(page, it)) }, text.columnOf, text.columns, scanned = scanned)
    }

    /** Groups a scan's OCR glyphs into words: one per text run, split further at gaps inside a run. */
    private fun ocrWords(glyphs: List<Glyph>, runs: List<Int>, dirs: List<Int>, mainDir: Int, hidden: List<Boolean>): List<Glyph> {
        val out = ArrayList<Glyph>()
        var cur = ArrayList<Glyph>()
        fun flush() {
            if (cur.isEmpty()) return
            val box = cur.map { it.box }.reduce { a, b -> a.union(b) }
            out += Glyph(cur.joinToString("") { it.text }, box, cur.maxOf { it.baseline }, cur.map { it.fontSize }.sorted()[cur.size / 2],
                cur.count { it.bold } * 2 > cur.size, cur.count { it.italic } * 2 > cur.size)
            cur = ArrayList()
        }
        for (i in glyphs.indices) {
            if (dirs[i] != mainDir || !hidden[i]) continue // only the OCR layer, not any visible text on the page
            val g = glyphs[i]
            val prev = cur.lastOrNull()
            val newRun = i > 0 && runs[i] != runs[i - 1]
            val gap = prev != null && (g.box.x0 - prev.box.x1 > g.box.height * 0.35f || g.box.x1 < prev.box.x0)
            if (newRun || gap) flush()
            cur += g
        }
        flush()
        return out
    }

    private fun mergeBoxes(input: List<Box>): List<Box> {
        val out = ArrayList(input)
        var changed = true
        while (changed) {
            changed = false
            loop@ for (i in out.indices) for (j in i + 1 until out.size) {
                if (out[i].overlapsX(out[j]) && out[i].overlapsY(out[j])) { out[i] = out[i].union(out[j]); out.removeAt(j); changed = true; break@loop }
            }
        }
        return out.sortedBy { it.y0 }
    }

    /** Clusters drawn paths into figure regions (charts, diagrams), ignoring rules and text boxes. */
    private fun vectorFigures(paths: List<Box>, text: PageText, w: Float, h: Float): List<Box> {
        // Everything except page-sized backgrounds participates; thin rules alone never form a
        // figure because clusters need several paths and a real area (and little text inside).
        val candidates = paths.filter { b -> b.width * b.height < w * h * 0.9f && (b.width >= 1f || b.height >= 1f) }
        if (candidates.size < 3) return emptyList()
        val clusters = ArrayList<Pair<Box, Int>>()
        for (b in candidates.sortedBy { it.y0 }) {
            val grown = Box(b.x0 - 6, b.y0 - 6, b.x1 + 6, b.y1 + 6)
            val idx = clusters.indexOfFirst { it.first.overlapsX(grown) && it.first.overlapsY(grown) }
            if (idx >= 0) clusters[idx] = clusters[idx].first.union(b) to clusters[idx].second + 1 else clusters += b to 1
        }
        return clusters.filter { (box, count) ->
            val area = box.width * box.height
            if (count < 3 || area < w * h * 0.025f || box.width < 40 || box.height < 30 || area > w * h * 0.9f) return@filter false
            // Mostly text inside (text boxes, shaded paragraphs, tables) => not a figure.
            val textArea = text.lines.filter { it.box.overlapsX(box) && it.box.overlapsY(box) }.sumOf { (it.box.width * it.box.height).toDouble() }
            textArea < area * 0.3
        }.map { it.first }
    }

    /** Collects image and path bounding boxes in top-left page coordinates. */
    private class GraphicsCollector(private val page: PDPage) : PDFGraphicsStreamEngine(page) {
        val images = ArrayList<Box>()
        val paths = ArrayList<Box>()
        private var minX = Float.MAX_VALUE; private var minY = Float.MAX_VALUE
        private var maxX = -Float.MAX_VALUE; private var maxY = -Float.MAX_VALUE
        private var current = PointF()
        private val crop = page.cropBox
        private val rotation = ((page.rotation % 360) + 360) % 360

        private fun toPage(x: Float, y: Float): PointF {
            // User space (origin bottom-left of the crop box) -> rotated, top-left page space.
            val ux = x - crop.lowerLeftX
            val uy = y - crop.lowerLeftY
            val cw = crop.width; val ch = crop.height
            return when (rotation) {
                90 -> PointF(uy, ux)
                180 -> PointF(cw - ux, uy)
                270 -> PointF(ch - uy, cw - ux)
                else -> PointF(ux, ch - uy)
            }
        }

        private fun extend(x: Float, y: Float) {
            val p = toPage(x, y)
            minX = minOf(minX, p.x); minY = minOf(minY, p.y); maxX = maxOf(maxX, p.x); maxY = maxOf(maxY, p.y)
        }

        private fun flush() {
            if (minX <= maxX && minY <= maxY && paths.size < 5000) paths += Box(minX, minY, maxX, maxY)
            reset()
        }

        private fun reset() { minX = Float.MAX_VALUE; minY = Float.MAX_VALUE; maxX = -Float.MAX_VALUE; maxY = -Float.MAX_VALUE }

        override fun appendRectangle(p0: PointF, p1: PointF, p2: PointF, p3: PointF) {
            extend(p0.x, p0.y); extend(p1.x, p1.y); extend(p2.x, p2.y); extend(p3.x, p3.y)
            current = p0
        }

        override fun drawImage(pdImage: PDImage) {
            val m = graphicsState.currentTransformationMatrix
            val pts = listOf(0f to 0f, 1f to 0f, 0f to 1f, 1f to 1f).map { (u, v) ->
                toPage(m.scaleX * u + m.shearX * v + m.translateX, m.shearY * u + m.scaleY * v + m.translateY)
            }
            if (images.size < 500) images += Box(pts.minOf { it.x }, pts.minOf { it.y }, pts.maxOf { it.x }, pts.maxOf { it.y })
        }

        override fun clip(windingRule: Path.FillType) {}
        override fun moveTo(x: Float, y: Float) { extend(x, y); current = PointF(x, y) }
        override fun lineTo(x: Float, y: Float) { extend(x, y); current = PointF(x, y) }
        override fun curveTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) { extend(x1, y1); extend(x2, y2); extend(x3, y3); current = PointF(x3, y3) }
        override fun getCurrentPoint(): PointF = current
        override fun closePath() {}
        override fun endPath() = reset()
        override fun strokePath() = flush()
        override fun fillPath(windingRule: Path.FillType) = flush()
        override fun fillAndStrokePath(windingRule: Path.FillType) = flush()
        override fun shadingFill(shadingName: COSName) {}
    }

    // ---------------- rendering ----------------

    override fun render(page: Int, targetWidth: Int): ImageData? = synchronized(lock) {
        val r = renderer ?: return null
        try {
            r.openPage(page).use { p ->
                val w = targetWidth.coerceIn(16, 4096)
                val h = (p.height.toLong() * w / p.width).toInt().coerceIn(16, 8192)
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                ImageData.Platform(bmp, w, h)
            }
        } catch (_: Exception) { null }
    }

    override fun renderRegion(page: Int, box: Box, targetWidth: Int): ImageData? = synchronized(lock) {
        val r = renderer ?: return null
        try {
            r.openPage(page).use { p ->
                val (pw, _) = pageBox(doc.getPage(page))
                // PdfRenderer page units are points of the (rotated) page.
                val unit = p.width / pw
                val scale = targetWidth / (box.width * unit).coerceAtLeast(1f)
                val w = targetWidth.coerceIn(16, 4096)
                val h = (box.height * unit * scale).toInt().coerceIn(16, 8192)
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                val m = Matrix()
                m.postTranslate(-box.x0 * unit, -box.y0 * unit)
                m.postScale(scale, scale)
                p.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                ImageData.Platform(bmp, w, h)
            }
        } catch (_: Exception) { null }
    }

    override fun cover(): ImageData? = render(0, 480)

    /** A decrypted copy of this (open, already unlocked) document, reused while the original is unchanged. */
    private fun decryptedCopy(context: Context): File? = try {
        val dir = File(context.cacheDir, "decrypted").apply { mkdirs() }
        val key = (file.absolutePath + ":" + file.length() + ":" + file.lastModified()).hashCode().toUInt().toString(16)
        val out = File(dir, "$key.pdf")
        if (!out.isFile || out.length() == 0L) {
            val tmp = File.createTempFile("dec", ".tmp", dir)
            synchronized(lock) { doc.save(tmp) }
            if (!tmp.renameTo(out)) tmp.delete()
        }
        out.takeIf { it.isFile }
    } catch (_: Throwable) { null }

    override fun close() {
        synchronized(lock) {
            try { renderer?.close() } catch (_: Exception) {}
            try { pfd.close() } catch (_: Exception) {}
            try { doc.close() } catch (_: Exception) {}
        }
    }
}
