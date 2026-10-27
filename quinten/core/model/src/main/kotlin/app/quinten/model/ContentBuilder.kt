package app.quinten.model

/**
 * Incrementally builds a list of [Block]s from a stream of structural and inline events.
 * Used by all flowing-format parsers (EPUB/HTML, FB2, DOCX, RTF, DOC, MOBI, TXT).
 *
 * Whitespace is collapsed like HTML unless [preformatted] is set. Inline styles may span
 * block boundaries: they are closed at the end of each block and re-opened in the next.
 */
class ContentBuilder {
    private val blocks = ArrayList<Block>()
    private val sb = StringBuilder()
    private val spans = ArrayList<Span>()
    private val openStyles = ArrayList<Pair<InlineStyle, Int>>()
    private val openLinks = ArrayList<Triple<String, Boolean, Int>>()
    private val pendingAnchors = ArrayList<String>()
    private var kind = BlockKind.PARAGRAPH
    private var level = 0
    private var align = AlignHint.DEFAULT
    private var sourcePage = -1
    var preformatted = false

    val isEmpty: Boolean get() = blocks.isEmpty() && sb.isBlank()
    val blockCount: Int get() = blocks.size
    val currentText: CharSequence get() = sb

    /** Starts a new block; flushes the current one if it has content. */
    fun beginBlock(kind: BlockKind = BlockKind.PARAGRAPH, level: Int = 0, align: AlignHint = AlignHint.DEFAULT, sourcePage: Int = -1) {
        endBlock()
        this.kind = kind
        this.level = level
        this.align = align
        this.sourcePage = sourcePage
    }

    /** Changes the kind of the block being built (e.g. when a style reveals a heading). */
    fun setKind(kind: BlockKind, level: Int = this.level) {
        this.kind = kind
        this.level = level
    }

    fun setAlign(align: AlignHint) {
        this.align = align
    }

    fun text(s: String) {
        if (s.isEmpty()) return
        if (preformatted) {
            sb.append(s.replace("\r\n", "\n").replace('\r', '\n'))
            return
        }
        for (ch in s) {
            if (ch == '\u00A0') { // keep non-breaking spaces
                sb.append(ch)
            } else if (ch.isWhitespace()) {
                if (sb.isNotEmpty() && !sb.last().isWhitespace()) sb.append(' ')
            } else if (ch == '\u00AD' || ch == '\u200B' || ch == '\uFEFF') {
                // soft hyphen: drop, the layout engine hyphenates on its own;
                // zero-width space (Thai/CJK word boundary) and stray BOM: invisible, never a space
            } else {
                sb.append(ch)
            }
        }
    }

    fun lineBreak() {
        // Remove a trailing collapsed space before a hard break.
        if (sb.isNotEmpty() && sb.last() == ' ') sb.setLength(sb.length - 1)
        if (sb.isNotEmpty()) sb.append('\n')
    }

    fun pushStyle(style: InlineStyle) {
        openStyles.add(style to sb.length)
    }

    fun popStyle(style: InlineStyle) {
        val i = openStyles.indexOfLast { it.first == style }
        if (i < 0) return
        val (_, start) = openStyles.removeAt(i)
        if (sb.length > start) {
            // Merge with an adjacent span of the same style (common in DOCX run splitting).
            val prev = spans.indexOfLast { it is StyleSpan && it.style == style && it.end == start }
            if (prev >= 0) spans[prev] = (spans[prev] as StyleSpan).copy(end = sb.length)
            else spans.add(StyleSpan(start, sb.length, style))
        }
    }

    fun pushLink(target: String, footnote: Boolean = false) {
        openLinks.add(Triple(target, footnote, sb.length))
    }

    fun popLink() {
        if (openLinks.isEmpty()) return
        val (t, fn, start) = openLinks.removeAt(openLinks.size - 1)
        if (sb.length > start) spans.add(LinkSpan(start, sb.length, t, fn))
    }

    fun anchor(id: String) {
        if (id.isNotBlank()) pendingAnchors.add(id)
    }

    fun image(resource: String, width: Int = 0, height: Int = 0, alt: String? = null, fullPage: Boolean = false) {
        val k = kind; val l = level; val a = align
        endBlock()
        blocks.add(ImageBlock(resource, width, height, alt, takeAnchors(), sourcePage, fullPage = fullPage))
        kind = k; level = l; align = a
    }

    fun rule() {
        endBlock()
        blocks.add(RuleBlock(takeAnchors(), sourcePage))
    }

    /** Adds an already-built block (used by analyzers). */
    fun add(block: Block) {
        endBlock()
        blocks.add(block)
    }

    private fun takeAnchors(): List<String> =
        if (pendingAnchors.isEmpty()) emptyList() else ArrayList(pendingAnchors).also { pendingAnchors.clear() }

    fun endBlock() {
        // Trim whitespace at both ends, adjusting spans.
        var end = sb.length
        while (end > 0 && (sb[end - 1] == ' ' || sb[end - 1] == '\n' || (!preformatted && sb[end - 1].isWhitespace()))) end--
        var start = 0
        while (start < end && (sb[start] == ' ' || sb[start] == '\n')) start++
        val styleRestarts = openStyles.map { it.first }
        val linkRestarts = openLinks.map { it.first to it.second }
        // close open inline elements at the block end
        for ((style, s) in openStyles) if (sb.length > s) spans.add(StyleSpan(s, sb.length, style))
        for ((t, fn, s) in openLinks) if (sb.length > s) spans.add(LinkSpan(s, sb.length, t, fn))
        if (end > start) {
            val text = sb.substring(start, end)
            val clipped = spans.mapNotNull { sp ->
                val s = (sp.start - start).coerceIn(0, text.length)
                val e = (sp.end - start).coerceIn(0, text.length)
                if (e <= s) null else when (sp) {
                    is StyleSpan -> sp.copy(start = s, end = e)
                    is LinkSpan -> sp.copy(start = s, end = e)
                    is RemovedSpan -> sp.copy(start = s, end = e)
                }
            }
            blocks.add(TextBlock(text, kind, level, clipped, align, takeAnchors(), sourcePage))
        }
        sb.setLength(0)
        spans.clear()
        openStyles.clear(); openStyles.addAll(styleRestarts.map { it to 0 })
        openLinks.clear(); openLinks.addAll(linkRestarts.map { Triple(it.first, it.second, 0) })
        kind = BlockKind.PARAGRAPH
        level = 0
        align = AlignHint.DEFAULT
    }

    /** Finishes and returns blocks with units assigned sequentially from [unitBase]. */
    fun build(unitBase: Int = 0): List<Block> {
        endBlock()
        if (pendingAnchors.isNotEmpty() && blocks.isNotEmpty()) {
            // Anchors at the very end point at the last block.
            val last = blocks.removeAt(blocks.size - 1)
            blocks.add(withAnchors(last, last.anchors + takeAnchors()))
        }
        return blocks.mapIndexed { i, b -> assignUnit(b, unitBase + i) }
    }

    companion object {
        fun assignUnit(b: Block, unit: Int): Block = when (b) {
            is TextBlock -> b.withUnits(intArrayOf(unit), intArrayOf(0))
            is ImageBlock -> b.withUnit(unit)
            is RuleBlock -> b.withUnit(unit)
        }

        fun withAnchors(b: Block, anchors: List<String>): Block = when (b) {
            is TextBlock -> b.copy(anchors = anchors)
            is ImageBlock -> ImageBlock(b.resource, b.width, b.height, b.alt, anchors, b.sourcePage, b.units, b.fullPage)
            is RuleBlock -> RuleBlock(anchors, b.sourcePage, b.units)
        }
    }
}
