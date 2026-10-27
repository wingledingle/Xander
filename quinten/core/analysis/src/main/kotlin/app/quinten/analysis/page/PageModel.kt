package app.quinten.analysis.page

/**
 * Geometric text model of a fixed-layout page (PDF text layer, DjVu hidden text, OCR output).
 * Coordinates are in page units with the origin at the top-left corner; y grows downwards.
 */
data class Box(val x0: Float, val y0: Float, val x1: Float, val y1: Float) {
    val width get() = x1 - x0
    val height get() = y1 - y0
    val cx get() = (x0 + x1) / 2
    val cy get() = (y0 + y1) / 2
    fun union(o: Box) = Box(minOf(x0, o.x0), minOf(y0, o.y0), maxOf(x1, o.x1), maxOf(y1, o.y1))
    fun overlapsX(o: Box) = x0 < o.x1 && o.x0 < x1
    fun overlapsY(o: Box) = y0 < o.y1 && o.y0 < y1
}

/** A positioned glyph or short run of text with uniform style. */
data class Glyph(
    val text: String,
    val box: Box,
    /** Baseline y. */
    val baseline: Float,
    val fontSize: Float,
    val bold: Boolean = false,
    val italic: Boolean = false,
    /** Approximate width of a space in this font, if known. */
    val spaceWidth: Float = fontSize * 0.25f,
)

/** Style information for a character range of a [TextLine]. */
data class StyleRun(
    val start: Int,
    val end: Int,
    val fontSize: Float,
    val bold: Boolean,
    val italic: Boolean,
    /** Raised and smaller than the line: superscript (footnote references). */
    val superscript: Boolean = false,
)

class TextLine(
    val text: String,
    val box: Box,
    val baseline: Float,
    val fontSize: Float,
    val runs: List<StyleRun> = emptyList(),
    /** OCR confidence 0..100, or -1 when not applicable. */
    val confidence: Float = -1f,
) {
    val isBold get() = runs.isNotEmpty() && runs.all { it.bold || it.end - it.start <= 1 }
    override fun toString() = "Line('$text' ${box.x0.toInt()},${box.y0.toInt()}-${box.x1.toInt()},${box.y1.toInt()} fs=$fontSize)"
}

/** An illustration region on the page. [resource] is resolved by the owning document. */
data class PageImage(val box: Box, val resource: String)

/**
 * Text content of one page in reading order. [lines] are already ordered (columns resolved).
 * [columnOf] gives the column index of each line (used for paragraph geometry).
 */
class PageText(
    val pageIndex: Int,
    val width: Float,
    val height: Float,
    val lines: List<TextLine>,
    val images: List<PageImage> = emptyList(),
    val columnOf: IntArray = IntArray(lines.size),
    val columns: List<Box> = emptyList(),
    /** True when the text came from OCR. */
    val ocr: Boolean = false,
    /** True for a scan's own hidden text layer (made by a scanner's OCR), which has OCR misreads. */
    val scanned: Boolean = false,
) {
    /** Text recognised from a picture of the page: misreads are repaired only in such text. */
    val recognised get() = ocr || scanned
    val isEmpty get() = lines.isEmpty() && images.isEmpty()
}
