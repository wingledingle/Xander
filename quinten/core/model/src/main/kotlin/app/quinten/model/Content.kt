package app.quinten.model

enum class InlineStyle { BOLD, ITALIC, UNDERLINE, STRIKE, SUPERSCRIPT, SUBSCRIPT, MONOSPACE, SMALL_CAPS }

sealed class Span {
    abstract val start: Int
    abstract val end: Int
    abstract fun shift(delta: Int): Span
}

data class StyleSpan(override val start: Int, override val end: Int, val style: InlineStyle) : Span() {
    override fun shift(delta: Int) = copy(start = start + delta, end = end + delta)
}

/** A link: internal ([LinkTarget]) or external URL. [footnote] marks note references. */
data class LinkSpan(override val start: Int, override val end: Int, val target: String, val footnote: Boolean = false) : Span() {
    override fun shift(delta: Int) = copy(start = start + delta, end = end + delta)
}

/** Text that Clean Reading Mode would remove; only present in preview mode. */
data class RemovedSpan(override val start: Int, override val end: Int, val removalId: String) : Span() {
    override fun shift(delta: Int) = copy(start = start + delta, end = end + delta)
}

enum class BlockKind {
    PARAGRAPH, HEADING, SUBTITLE, QUOTE, EPIGRAPH, POEM, AUTHOR, PREFORMATTED, LIST_ITEM, CAPTION, FOOTNOTE, TABLE_ROW,
}

enum class AlignHint { DEFAULT, CENTER, RIGHT }

/**
 * Content blocks. Each block covers one or more *units*: stable integer keys of the original
 * source (an original block index, or a page/line id for page based formats). Units never
 * change when display options such as Clean Reading Mode change, so positions stored as
 * [Locator]s survive re-layout.
 */
sealed class Block {
    abstract val anchors: List<String>
    abstract val sourcePage: Int
    abstract val units: IntArray
    abstract val unitStarts: IntArray
    val firstUnit: Int get() = units[0]
    abstract val length: Int
}

class TextBlock(
    val text: String,
    val kind: BlockKind = BlockKind.PARAGRAPH,
    /** Heading level (1..6), list depth or quote depth. */
    val level: Int = 0,
    val spans: List<Span> = emptyList(),
    val align: AlignHint = AlignHint.DEFAULT,
    override val anchors: List<String> = emptyList(),
    override val sourcePage: Int = -1,
    override val units: IntArray = intArrayOf(0),
    /** Character index in [text] where each unit starts. Same size as [units]. */
    override val unitStarts: IntArray = intArrayOf(0),
    /** When non-null the whole block would be removed by Clean Reading Mode (preview only). */
    val removalId: String? = null,
) : Block() {
    override val length: Int get() = text.length

    fun withUnits(units: IntArray, starts: IntArray = IntArray(units.size)) =
        TextBlock(text, kind, level, spans, align, anchors, sourcePage, units, starts, removalId)

    fun copy(
        text: String = this.text, kind: BlockKind = this.kind, level: Int = this.level, spans: List<Span> = this.spans,
        align: AlignHint = this.align, anchors: List<String> = this.anchors, sourcePage: Int = this.sourcePage,
        units: IntArray = this.units, unitStarts: IntArray = this.unitStarts, removalId: String? = this.removalId,
    ) = TextBlock(text, kind, level, spans, align, anchors, sourcePage, units, unitStarts, removalId)

    /** Index into [units] for a character offset. */
    fun unitIndexAt(offset: Int): Int {
        var i = unitStarts.size - 1
        while (i > 0 && unitStarts[i] > offset) i--
        return i
    }

    override fun toString() = "TextBlock($kind,'${text.take(40)}')"
}

class ImageBlock(
    val resource: String,
    val width: Int = 0,
    val height: Int = 0,
    val alt: String? = null,
    override val anchors: List<String> = emptyList(),
    override val sourcePage: Int = -1,
    override val units: IntArray = intArrayOf(0),
    /** Fixed-layout page images are shown full-width; illustrations keep natural size where possible. */
    val fullPage: Boolean = false,
) : Block() {
    override val unitStarts: IntArray get() = ZERO
    override val length: Int get() = 1

    fun withUnit(unit: Int) = ImageBlock(resource, width, height, alt, anchors, sourcePage, intArrayOf(unit), fullPage)

    override fun toString() = "ImageBlock($resource)"

    private companion object { val ZERO = intArrayOf(0) }
}

/** Scene break or horizontal rule. */
class RuleBlock(
    override val anchors: List<String> = emptyList(),
    override val sourcePage: Int = -1,
    override val units: IntArray = intArrayOf(0),
) : Block() {
    override val unitStarts: IntArray get() = intArrayOf(0)
    override val length: Int get() = 1
    fun withUnit(unit: Int) = RuleBlock(anchors, sourcePage, intArrayOf(unit))
}

class Chapter(
    val index: Int,
    val title: String?,
    val blocks: List<Block>,
    /** Everything Clean Reading Mode detected in this chapter, whether currently applied or not. */
    val removals: List<Removal> = emptyList(),
    /** True while content is incomplete (e.g. OCR still running). */
    val partial: Boolean = false,
) {
    val textLength: Int by lazy { blocks.sumOf { it.length } }

    /** Converts a render position (block index, char offset) to a stable locator. */
    fun locatorAt(blockIndex: Int, offset: Int, isEnd: Boolean = false): Locator {
        if (blocks.isEmpty()) return Locator(index, 0, 0)
        val b = blocks[blockIndex.coerceIn(0, blocks.size - 1)]
        // The end of a range (exclusive) may sit just after the last character.
        val o = offset.coerceIn(0, if (isEnd) b.length else maxOf(0, b.length - 1))
        val ui = if (b is TextBlock) b.unitIndexAt(o) else 0
        return Locator(index, b.units[ui], o - b.unitStarts[ui])
    }

    /**
     * Converts a stable locator into a render position: the exact unit when present, otherwise
     * the start of the nearest preceding unit (e.g. when that unit is hidden by Clean Reading Mode).
     */
    fun positionOf(loc: Locator, isEnd: Boolean = false): Pair<Int, Int> {
        if (blocks.isEmpty()) return 0 to 0
        var bestBlock = -1
        var bestOffset = 0
        var bestUnit = Int.MIN_VALUE
        for ((bi, b) in blocks.withIndex()) {
            for (ui in b.units.indices) {
                val u = b.units[ui]
                if (u == loc.unit) {
                    val end = if (ui + 1 < b.units.size) b.unitStarts[ui + 1] else b.length
                    val o = (b.unitStarts[ui] + loc.offset).coerceAtMost(if (isEnd) end else maxOf(end - 1, b.unitStarts[ui]))
                    return bi to o.coerceAtLeast(0)
                }
                if (u < loc.unit && u > bestUnit) {
                    bestUnit = u; bestBlock = bi; bestOffset = b.unitStarts[ui]
                }
            }
        }
        return if (bestBlock < 0) 0 to 0 else bestBlock to bestOffset
    }

    /** Character offset of a block from chapter start. */
    fun charOffsetOf(blockIndex: Int): Int {
        var s = 0
        for (i in 0 until blockIndex.coerceAtMost(blocks.size)) s += blocks[i].length
        return s
    }

    fun findAnchor(anchor: String): Int = blocks.indexOfFirst { anchor in it.anchors }
}

/** Stable reading position: chapter + source unit + character offset inside that unit. */
data class Locator(val chapter: Int, val unit: Int, val offset: Int) : Comparable<Locator> {
    override fun compareTo(other: Locator): Int =
        compareValuesBy(this, other, { it.chapter }, { it.unit }, { it.offset })

    fun encode(): String = "$chapter:$unit:$offset"

    companion object {
        val START = Locator(0, 0, 0)
        fun decode(s: String?): Locator? {
            val p = s?.split(':') ?: return null
            if (p.size != 3) return null
            return Locator(p[0].toIntOrNull() ?: return null, p[1].toIntOrNull() ?: return null, p[2].toIntOrNull() ?: return null)
        }
    }
}

/** A range of text between two locators (highlights, notes, search hits). */
data class LocatorRange(val start: Locator, val end: Locator) {
    fun encode() = start.encode() + "-" + end.encode()

    companion object {
        fun decode(s: String?): LocatorRange? {
            val p = s?.split('-') ?: return null
            if (p.size != 2) return null
            return LocatorRange(Locator.decode(p[0]) ?: return null, Locator.decode(p[1]) ?: return null)
        }
    }
}
