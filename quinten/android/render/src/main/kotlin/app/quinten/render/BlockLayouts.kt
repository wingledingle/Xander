package app.quinten.render

import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.SubscriptSpan
import android.text.style.SuperscriptSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import app.quinten.model.AlignHint
import app.quinten.model.Block
import app.quinten.model.BlockKind
import app.quinten.model.ImageBlock
import app.quinten.model.InlineStyle
import app.quinten.model.LinkSpan
import app.quinten.model.RemovedSpan
import app.quinten.model.RuleBlock
import app.quinten.model.StyleSpan as ModelStyle
import app.quinten.model.TextBlock
import java.io.File

/** A decoration drawn over text: highlights, search hits, the sentence being spoken. */
data class Decoration(val start: Int, val end: Int, val color: Int, val kind: Kind = Kind.HIGHLIGHT) {
    enum class Kind { HIGHLIGHT, NOTE, SEARCH, TTS, SELECTION }
}

/** Laid-out block. Heights are in pixels; rows are the units pagination works with. */
sealed class BlockLayout {
    abstract val height: Int
    abstract val rowCount: Int
    /** Top (inclusive) of row [i] within the block. */
    abstract fun rowTop(i: Int): Int
    abstract fun rowBottom(i: Int): Int

    class Text(
        val layout: StaticLayout,
        val padTop: Int,
        val padBottom: Int,
        val left: Int,
        val block: TextBlock,
        /** Rows that render as headings must not end a page alone. */
        val keepWithNext: Boolean,
    ) : BlockLayout() {
        override val height = padTop + layout.height + padBottom
        override val rowCount get() = layout.lineCount
        override fun rowTop(i: Int) = if (i == 0) 0 else padTop + layout.getLineTop(i)
        override fun rowBottom(i: Int) = if (i == rowCount - 1) height else padTop + layout.getLineBottom(i)
        /** Character offset of the start of row [i]. */
        fun rowStart(i: Int) = layout.getLineStart(i)
        fun rowForOffset(offset: Int) = layout.getLineForOffset(offset.coerceIn(0, layout.text.length))
    }

    class Image(val block: ImageBlock, val width: Int, val imageHeight: Int, val left: Int, val padTop: Int, val padBottom: Int) : BlockLayout() {
        override val height = padTop + imageHeight + padBottom
        override val rowCount = 1
        override fun rowTop(i: Int) = 0
        override fun rowBottom(i: Int) = height
    }

    class Rule(override val height: Int) : BlockLayout() {
        override val rowCount = 1
        override fun rowTop(i: Int) = 0
        override fun rowBottom(i: Int) = height
    }
}

/** Builds [BlockLayout]s with the reader's settings. Not thread-safe; use one per thread. */
class BlockLayouter(private val density: Float, private val scaledDensity: Float) {
    private val typefaceCache = HashMap<String, Typeface>()

    fun typeface(family: String, bold: Boolean): Typeface {
        val key = "$family|$bold"
        return typefaceCache.getOrPut(key) {
            val base = if (family.contains('/')) runCatching { Typeface.createFromFile(File(family)) }.getOrDefault(Typeface.SERIF)
            else Typeface.create(family, Typeface.NORMAL)
            if (bold) Typeface.create(base, Typeface.BOLD) else base
        }
    }

    fun basePaint(s: ReaderSettings): TextPaint = TextPaint(TextPaint.ANTI_ALIAS_FLAG or TextPaint.SUBPIXEL_TEXT_FLAG).apply {
        textSize = s.fontSizeSp * scaledDensity
        color = s.theme.text
        typeface = typeface(s.fontFamily, s.boldText)
        letterSpacing = s.letterSpacing
        isLinearText = true
    }

    /**
     * Lays out [block] for a content area [width] x [pageHeight] pixels.
     * [imageSize] returns the natural pixel size of an image resource (or null if unknown).
     */
    fun layout(
        block: Block,
        width: Int,
        pageHeight: Int,
        s: ReaderSettings,
        decorations: List<Decoration>,
        imageSize: (ImageBlock) -> Pair<Int, Int>?,
        isFirstInChapter: Boolean,
    ): BlockLayout {
        val em = s.fontSizeSp * scaledDensity
        return when (block) {
            is TextBlock -> text(block, width, s, decorations, em, isFirstInChapter)
            is ImageBlock -> {
                if (!s.showImages) return BlockLayout.Rule((em * 0.5f).toInt())
                val natural = imageSize(block) ?: (if (block.width > 0 && block.height > 0) block.width to block.height else (width to width * 3 / 4))
                val (nw, nh) = natural
                val aspect = nh.toFloat() / nw.coerceAtLeast(1)
                var w = when {
                    block.fullPage -> width
                    nw * density >= width * 0.4f -> width
                    else -> (nw * density).toInt().coerceIn((em * 1.5f).toInt(), width)
                }
                var h = (w * aspect).toInt().coerceAtLeast(1)
                // Whole pages (comics, scans) use the full screen height; other pictures leave a line free.
                val maxH = (if (block.fullPage) pageHeight else (pageHeight - em).toInt()).coerceAtLeast(1)
                if (h > maxH) { h = maxH; w = (h / aspect).toInt().coerceAtMost(width) }
                val pad = if (block.fullPage) 0 else (em * 0.5f).toInt()
                BlockLayout.Image(block, w, h, (width - w) / 2, pad, pad)
            }
            is RuleBlock -> BlockLayout.Rule((em * 2f).toInt())
        }
    }

    private fun text(b: TextBlock, width: Int, s: ReaderSettings, decorations: List<Decoration>, em: Float, first: Boolean): BlockLayout.Text {
        val paint = basePaint(s)
        val sb = SpannableStringBuilder(b.text)
        val n = b.text.length
        fun span(o: Any, st: Int, en: Int) {
            val a = st.coerceIn(0, n); val z = en.coerceIn(a, n)
            if (z > a) sb.setSpan(o, a, z, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        for (sp in b.spans) {
            when (sp) {
                is ModelStyle -> when (sp.style) {
                    InlineStyle.BOLD -> span(StyleSpan(Typeface.BOLD), sp.start, sp.end)
                    InlineStyle.ITALIC -> span(StyleSpan(Typeface.ITALIC), sp.start, sp.end)
                    InlineStyle.UNDERLINE -> span(UnderlineSpan(), sp.start, sp.end)
                    InlineStyle.STRIKE -> span(StrikethroughSpan(), sp.start, sp.end)
                    InlineStyle.SUPERSCRIPT -> { span(SuperscriptSpan(), sp.start, sp.end); span(RelativeSizeSpan(0.7f), sp.start, sp.end) }
                    InlineStyle.SUBSCRIPT -> { span(SubscriptSpan(), sp.start, sp.end); span(RelativeSizeSpan(0.7f), sp.start, sp.end) }
                    InlineStyle.MONOSPACE -> span(TypefaceSpan("monospace"), sp.start, sp.end)
                    InlineStyle.SMALL_CAPS -> span(RelativeSizeSpan(0.85f), sp.start, sp.end)
                }
                is LinkSpan -> {
                    span(ForegroundColorSpan(s.theme.link), sp.start, sp.end)
                    if (!sp.footnote) span(UnderlineSpan(), sp.start, sp.end)
                }
                is RemovedSpan -> {
                    span(StrikethroughSpan(), sp.start, sp.end)
                    span(ForegroundColorSpan(0xFFC62828.toInt()), sp.start, sp.end)
                    span(BackgroundColorSpan(0x33C62828), sp.start, sp.end)
                }
            }
        }
        if (b.removalId != null) {
            span(StrikethroughSpan(), 0, n)
            span(ForegroundColorSpan(0xFFC62828.toInt()), 0, n)
            span(BackgroundColorSpan(0x33C62828), 0, n)
        }
        for (d in decorations) {
            when (d.kind) {
                Decoration.Kind.NOTE -> { span(BackgroundColorSpan(d.color), d.start, d.end); span(UnderlineSpan(), d.start, d.end) }
                else -> span(BackgroundColorSpan(d.color), d.start, d.end)
            }
        }

        var align = Layout.Alignment.ALIGN_NORMAL
        var justify = s.align == TextAlign.JUSTIFY
        var indentFirst = 0
        var indentRest = 0
        var padTop = (em * s.paragraphSpacing).toInt()
        var padBottom = 0
        var keep = false
        when (b.kind) {
            BlockKind.HEADING -> {
                val scale = when (b.level) { 1 -> 1.6f; 2 -> 1.4f; 3 -> 1.25f; else -> 1.12f }
                span(RelativeSizeSpan(scale), 0, n); span(StyleSpan(Typeface.BOLD), 0, n)
                padTop = if (first) 0 else (em * 1.4f).toInt()
                padBottom = (em * 0.6f).toInt()
                justify = false
                keep = true
                if (s.respectAlignment && (b.level <= 2 || b.align == AlignHint.CENTER)) align = Layout.Alignment.ALIGN_CENTER
            }
            BlockKind.SUBTITLE -> { span(StyleSpan(Typeface.BOLD), 0, n); justify = false; align = Layout.Alignment.ALIGN_CENTER; keep = true; padTop = (em * 0.8f).toInt() }
            BlockKind.QUOTE, BlockKind.EPIGRAPH -> {
                indentFirst = (em * 1.5f).toInt(); indentRest = indentFirst
                if (b.kind == BlockKind.EPIGRAPH) { span(StyleSpan(Typeface.ITALIC), 0, n); justify = false }
            }
            BlockKind.POEM -> { indentFirst = (em * 1.5f).toInt(); indentRest = (em * 2.5f).toInt(); justify = false; padTop = (em * 0.6f).toInt() }
            BlockKind.AUTHOR -> { span(StyleSpan(Typeface.ITALIC), 0, n); justify = false; align = Layout.Alignment.ALIGN_OPPOSITE }
            BlockKind.PREFORMATTED -> { span(TypefaceSpan("monospace"), 0, n); span(RelativeSizeSpan(0.9f), 0, n); justify = false }
            BlockKind.LIST_ITEM -> { indentFirst = (em * (0.8f + b.level * 1.2f)).toInt(); indentRest = indentFirst + (em * 0.9f).toInt(); padTop = (em * 0.2f).toInt() }
            BlockKind.CAPTION -> { span(RelativeSizeSpan(0.88f), 0, n); span(StyleSpan(Typeface.ITALIC), 0, n); justify = false; align = Layout.Alignment.ALIGN_CENTER }
            BlockKind.FOOTNOTE -> { span(RelativeSizeSpan(0.85f), 0, n); span(ForegroundColorSpan(s.theme.secondaryText), 0, n); padTop = (em * 0.3f).toInt() }
            BlockKind.TABLE_ROW -> { justify = false; padTop = (em * 0.25f).toInt() }
            BlockKind.PARAGRAPH -> {
                if (s.firstLineIndent > 0 && !first && b.align == AlignHint.DEFAULT) indentFirst = (em * s.firstLineIndent).toInt()
            }
        }
        if (s.respectAlignment && b.kind == BlockKind.PARAGRAPH) {
            when (b.align) {
                AlignHint.CENTER -> { align = Layout.Alignment.ALIGN_CENTER; justify = false; indentFirst = 0 }
                AlignHint.RIGHT -> { align = Layout.Alignment.ALIGN_OPPOSITE; justify = false; indentFirst = 0 }
                else -> {}
            }
        }
        // Very large print: indents scale with the font and could leave one word per line; cap them.
        val maxIndent = (width * 0.25f).toInt()
        indentFirst = indentFirst.coerceAtMost(maxIndent); indentRest = indentRest.coerceAtMost(maxIndent)
        if (indentFirst > 0 || indentRest > 0) sb.setSpan(LeadingMarginSpan.Standard(indentFirst, indentRest), 0, n, Spanned.SPAN_INCLUSIVE_INCLUSIVE)

        val builder = StaticLayout.Builder.obtain(sb, 0, sb.length, paint, width.coerceAtLeast(1))
            .setAlignment(align)
            .setIncludePad(false)
            .setLineSpacing(0f, s.lineSpacing)
            .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
            .setHyphenationFrequency(if (s.hyphenation) Layout.HYPHENATION_FREQUENCY_NORMAL else Layout.HYPHENATION_FREQUENCY_NONE)
        if (justify && Build.VERSION.SDK_INT >= 26) builder.setJustificationMode(Layout.JUSTIFICATION_MODE_INTER_WORD)
        return BlockLayout.Text(builder.build(), padTop, padBottom, 0, b, keep)
    }

    @Suppress("unused")
    private fun absolute(px: Int) = AbsoluteSizeSpan(px)
}
