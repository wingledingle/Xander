package app.quinten.formats.html

import app.quinten.model.AlignHint
import app.quinten.model.BlockKind
import app.quinten.model.ContentBuilder
import app.quinten.model.InlineStyle
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * Converts (X)HTML into the reflowable block model. Shared by EPUB, MOBI/AZW3 and other
 * HTML-based sources. All presentational sizes are dropped; structure and inline semantics
 * (emphasis, links, footnotes, headings, images) are preserved.
 */
class HtmlConverter(
    private val css: MiniCss = MiniCss(),
    /** Maps an href to an internal [app.quinten.model.LinkTarget] string or external URL, or null. */
    private val resolveHref: (String) -> String? = { if (it.startsWith("http")) it else null },
    /** Maps an image src to a resource path for [app.quinten.model.BookDocument.image]. */
    private val resolveImage: (String) -> String? = { it },
) {
    private lateinit var out: ContentBuilder
    private var quoteDepth = 0
    private var listDepth = 0
    private var inFootnote = 0

    fun convert(root: Element, builder: ContentBuilder = ContentBuilder()): ContentBuilder {
        out = builder
        walkChildren(root)
        out.endBlock()
        return out
    }

    private fun walkChildren(e: Node) {
        for (child in e.childNodes()) walk(child)
    }

    private fun walk(node: Node) {
        when (node) {
            is TextNode -> out.text(node.wholeText)
            is Element -> element(node)
            else -> {}
        }
    }

    private fun element(e: Element) {
        val tag = e.normalName().substringAfter(':')
        if (tag in VOID && e.childNodeSize() > 0) {
            // XHTML read by the XML parser with an unclosed <br>/<img>/<hr>: the rest of the
            // parent's content became the void element's children. Emit the element, then them.
            if (tag !in SKIP && css.propsFor(e, tag).hidden != true && !e.hasAttr("hidden")) emitVoid(e, tag, e.id())
            walkChildren(e)
            return
        }
        if (tag in SKIP) return
        val props = css.propsFor(e, tag)
        if (props.hidden == true) return
        if (e.hasAttr("hidden")) return
        val epubType = e.attr("epub:type").ifEmpty { e.attr("type") }.lowercase()
        val role = e.attr("role").lowercase()

        val id = e.id().ifEmpty { if (tag == "a") e.attr("name") else "" }

        when (tag) {
            "br", "hr", "img", "image" -> { emitVoid(e, tag, id); return }
            "svg" -> {
                // SVG wrappers around a single raster image (common for covers).
                val img = e.getElementsByTag("image").firstOrNull() ?: e.getElementsByTag("svg:image").firstOrNull()
                if (img != null) element(img)
                return
            }
        }

        val isFootnoteBody = epubType.contains("footnote") || epubType.contains("endnote") || epubType.contains("rearnote") ||
            role == "doc-footnote" || role == "doc-endnote"
        val blockKind: BlockKind? = when {
            isFootnoteBody -> null // container; children become FOOTNOTE paragraphs
            tag.length == 2 && tag[0] == 'h' && tag[1] in '1'..'6' -> BlockKind.HEADING
            tag == "blockquote" -> null
            tag == "pre" -> BlockKind.PREFORMATTED
            tag == "li" || tag == "dt" || tag == "dd" -> BlockKind.LIST_ITEM
            tag == "figcaption" || tag == "caption" -> BlockKind.CAPTION
            tag == "tr" -> BlockKind.TABLE_ROW
            tag in BLOCKS || props.block == true -> BlockKind.PARAGRAPH
            else -> null
        }
        val isContainer = blockKind == null && (tag in CONTAINERS || isFootnoteBody || tag == "blockquote")

        if (id.isNotEmpty()) out.anchor(id)

        if (blockKind != null) {
            var kind = blockKind
            var level = 0
            when (blockKind) {
                BlockKind.HEADING -> level = tag[1] - '0'
                BlockKind.LIST_ITEM -> level = listDepth
                BlockKind.PARAGRAPH -> when {
                    inFootnote > 0 -> kind = BlockKind.FOOTNOTE
                    quoteDepth > 0 -> { kind = BlockKind.QUOTE; level = quoteDepth }
                    props.largeFont == true && props.bold == true && e.text().length < 120 -> { kind = BlockKind.HEADING; level = 3 }
                }
                else -> {}
            }
            if (kind == BlockKind.PARAGRAPH && inFootnote > 0) kind = BlockKind.FOOTNOTE
            out.beginBlock(kind, level, alignOf(props.align))
            if (kind == BlockKind.PREFORMATTED) out.preformatted = true
            if (kind == BlockKind.LIST_ITEM && tag == "li") {
                val parent = e.parent()
                if (parent?.normalName() == "ol") {
                    val start = parent.attr("start").toIntOrNull() ?: 1
                    out.text("${start + e.elementSiblingIndex()}. ")
                } else {
                    out.text("• ")
                }
            }
            withInline(e, tag, props, epubType) {
                if (tag == "tr") {
                    var first = true
                    for (cell in e.children()) {
                        if (cell.normalName() != "td" && cell.normalName() != "th") continue
                        if (!first) out.text(" | ")
                        first = false
                        walkChildren(cell)
                    }
                } else {
                    walkChildren(e)
                }
            }
            out.preformatted = false
            out.endBlock()
            return
        }

        if (isContainer) {
            if (tag == "blockquote") quoteDepth++
            if (tag == "ul" || tag == "ol" || tag == "dl") listDepth++
            if (isFootnoteBody) inFootnote++
            out.endBlock()
            withInline(e, tag, props, epubType) { walkChildren(e) }
            out.endBlock()
            if (isFootnoteBody) inFootnote--
            if (tag == "ul" || tag == "ol" || tag == "dl") listDepth--
            if (tag == "blockquote") quoteDepth--
            return
        }

        // Inline element
        withInline(e, tag, props, epubType) { walkChildren(e) }
    }

    private fun emitVoid(e: Element, tag: String, id: String) {
        when (tag) {
            "br" -> out.lineBreak()
            "hr" -> { if (id.isNotEmpty()) out.anchor(id); out.rule() }
            "img", "image" -> {
                if (id.isNotEmpty()) out.anchor(id)
                val src = e.attr("src").ifEmpty { e.attr("xlink:href").ifEmpty { e.attr("href") } }
                val res = resolveImage(src)
                if (res != null) {
                    out.image(res, e.attr("width").toIntOrNull() ?: 0, e.attr("height").toIntOrNull() ?: 0, e.attr("alt").ifBlank { null })
                }
            }
        }
    }

    private inline fun withInline(e: Element, tag: String, props: MiniCss.Props, epubType: String, body: () -> Unit) {
        val styles = ArrayList<InlineStyle>(2)
        when (tag) {
            "b", "strong" -> styles += InlineStyle.BOLD
            "i", "em", "cite", "var", "dfn" -> styles += InlineStyle.ITALIC
            "u", "ins" -> styles += InlineStyle.UNDERLINE
            "s", "strike", "del" -> styles += InlineStyle.STRIKE
            "sup" -> styles += InlineStyle.SUPERSCRIPT
            "sub" -> styles += InlineStyle.SUBSCRIPT
            "code", "tt", "kbd", "samp" -> styles += InlineStyle.MONOSPACE
        }
        if (props.bold == true && InlineStyle.BOLD !in styles && tag !in HEADINGS) styles += InlineStyle.BOLD
        if (props.italic == true && InlineStyle.ITALIC !in styles) styles += InlineStyle.ITALIC
        if (props.underline == true && InlineStyle.UNDERLINE !in styles) styles += InlineStyle.UNDERLINE
        if (props.strike == true && InlineStyle.STRIKE !in styles) styles += InlineStyle.STRIKE
        if (props.smallCaps == true) styles += InlineStyle.SMALL_CAPS
        if (props.superscript == true && InlineStyle.SUPERSCRIPT !in styles) styles += InlineStyle.SUPERSCRIPT
        if (props.subscript == true && InlineStyle.SUBSCRIPT !in styles) styles += InlineStyle.SUBSCRIPT
        if (props.monospace == true && InlineStyle.MONOSPACE !in styles) styles += InlineStyle.MONOSPACE
        if (props.align != null && tag in BLOCKS + CONTAINERS) out.setAlign(alignOf(props.align))

        var link = false
        if (tag == "a" && e.hasAttr("href")) {
            val target = resolveHref(e.attr("href"))
            if (target != null) {
                val fn = epubType.contains("noteref") || e.attr("role") == "doc-noteref" ||
                    e.className().contains("footnote", true) || e.className().contains("noteref", true) ||
                    looksLikeNoteRef(e)
                out.pushLink(target, fn)
                link = true
            }
        }
        for (s in styles) out.pushStyle(s)
        body()
        for (s in styles.asReversed()) out.popStyle(s)
        if (link) out.popLink()
    }

    private fun looksLikeNoteRef(a: Element): Boolean {
        val t = a.text().trim()
        if (t.isEmpty() || t.length > 4) return false
        val numeric = t.trim('[', ']', '(', ')', '*').let { it.isEmpty() || it.all { c -> c.isDigit() } }
        return numeric && (a.parent()?.normalName() == "sup" || a.selectFirst("sup") != null || t.startsWith("[") || t.all { it == '*' })
    }

    private fun alignOf(a: String?) = when (a) {
        "center" -> AlignHint.CENTER
        "right", "end" -> AlignHint.RIGHT
        else -> AlignHint.DEFAULT
    }

    companion object {
        private val SKIP = setOf("head", "script", "style", "noscript", "template", "title", "meta", "link", "object", "embed", "iframe", "audio", "video", "math", "button", "input", "select", "textarea")
        /** HTML void elements: they never have content of their own. */
        private val VOID = setOf("br", "hr", "img", "image", "wbr", "col", "source", "area", "param", "track", "input", "meta", "link", "embed", "base")
        private val HEADINGS =setOf("h1", "h2", "h3", "h4", "h5", "h6")
        val BLOCKS = setOf("p", "address", "center", "dt", "dd", "figcaption", "caption", "legend", "summary")
        private val CONTAINERS = setOf(
            "div", "body", "section", "article", "aside", "header", "footer", "nav", "main", "figure", "ul", "ol", "dl",
            "table", "thead", "tbody", "tfoot", "details", "fieldset", "html", "hgroup", "form", "td", "th",
        )
    }
}
