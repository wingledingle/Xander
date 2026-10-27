package app.quinten.formats.html

import org.jsoup.nodes.Element

/**
 * A deliberately small CSS reader: it understands simple selectors (`tag`, `.class`,
 * `tag.class`, `#id`, comma lists), descendant/child/sibling combinators, `@media` blocks for
 * screen/all/amzn-kf8, and the handful of properties that matter for reflowable text.
 * Layout properties (font sizes, margins, colours) are intentionally ignored: the user's
 * reading settings always win so that every book can be read in large print.
 */
class MiniCss {
    data class Props(
        val bold: Boolean? = null,
        val italic: Boolean? = null,
        val underline: Boolean? = null,
        val strike: Boolean? = null,
        val hidden: Boolean? = null,
        val align: String? = null,
        val smallCaps: Boolean? = null,
        val superscript: Boolean? = null,
        val subscript: Boolean? = null,
        val block: Boolean? = null,
        val monospace: Boolean? = null,
        val pageBreakBefore: Boolean? = null,
        val largeFont: Boolean? = null,
    ) {
        fun merge(o: Props) = Props(
            o.bold ?: bold, o.italic ?: italic, o.underline ?: underline, o.strike ?: strike, o.hidden ?: hidden,
            o.align ?: align, o.smallCaps ?: smallCaps, o.superscript ?: superscript, o.subscript ?: subscript,
            o.block ?: block, o.monospace ?: monospace, o.pageBreakBefore ?: pageBreakBefore, o.largeFont ?: largeFont,
        )

        val isEmpty get() = this == EMPTY
    }

    private val byTag = HashMap<String, Props>()
    private val byClass = HashMap<String, Props>()
    private val byTagClass = HashMap<String, Props>()

    /** One compound selector (`tag.a.b#id`). */
    private class Compound(val tag: String?, val classes: List<String>, val id: String?) {
        fun matches(e: Element): Boolean {
            if (tag != null && e.normalName().substringAfter(':') != tag) return false
            if (id != null && e.id() != id) return false
            if (classes.isNotEmpty()) {
                val have = e.className()
                if (have.isEmpty()) return false
                val set = have.split(' ', '\t', '\n').toSet()
                if (!classes.all { it in set }) return false
            }
            return true
        }
    }

    /**
     * A selector with combinators (or an id), matched right to left against the element's
     * ancestors and siblings. combinators[i] joins compounds[i] and compounds[i + 1].
     */
    private class Complex(val compounds: List<Compound>, val combinators: List<Char>, val props: Props)

    private val complexRules = ArrayList<Complex>()

    fun addStylesheet(css: String) {
        val clean = css.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        parseBlock(clean, 0, clean.length, conditional = false)
    }

    /** Parses the rules in [start, end), entering `@media` blocks whose query applies to us. */
    private fun parseBlock(css: String, start: Int, end: Int, conditional: Boolean) {
        var p = start
        while (p < end) {
            val open = css.indexOf('{', p).let { if (it < 0 || it >= end) -1 else it }
            val semi = css.indexOf(';', p).let { if (it < 0 || it >= end) -1 else it }
            // Statement at-rules (@import, @charset, @namespace) end at ';'.
            if (semi >= 0 && (open < 0 || semi < open) && css.substring(p, semi).trim().startsWith("@")) { p = semi + 1; continue }
            if (open < 0) return
            val close = matchingBrace(css, open, end)
            val bodyEnd = if (close < 0) end else close
            val prelude = css.substring(p, open).substringAfterLast(';').trim()
            when {
                prelude.startsWith("@media", ignoreCase = true) -> when (mediaApplies(prelude.substring(6).trim().lowercase())) {
                    MediaMatch.YES -> parseBlock(css, open + 1, bodyEnd, conditional)
                    MediaMatch.CONDITIONAL -> parseBlock(css, open + 1, bodyEnd, conditional = true)
                    MediaMatch.NO -> {}
                }
                prelude.startsWith("@supports", ignoreCase = true) -> parseBlock(css, open + 1, bodyEnd, conditional)
                prelude.startsWith("@") -> {} // @font-face, @page, @keyframes...
                else -> addRule(prelude, css.substring(open + 1, bodyEnd), conditional)
            }
            p = if (close < 0) end else close + 1
        }
    }

    private fun matchingBrace(css: String, open: Int, end: Int): Int {
        var depth = 0
        for (i in open until end) {
            when (css[i]) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return i }
            }
        }
        return -1
    }

    private enum class MediaMatch { YES, CONDITIONAL, NO }

    /**
     * Media queries we honour: empty, `all`, `screen` and `amzn-kf8` (we render like a KF8
     * reader); `print`, `amzn-mobi`, `speech`... are dropped. Queries with features we can't
     * evaluate (widths, orientation) apply, but without their hiding rules.
     */
    private fun mediaApplies(query: String): MediaMatch {
        if (query.isBlank()) return MediaMatch.YES
        var result = MediaMatch.NO
        for (q0 in query.split(',')) {
            var q = q0.trim()
            var negate = false
            if (q.startsWith("not ")) { negate = true; q = q.substring(4).trim() }
            if (q.startsWith("only ")) q = q.substring(5).trim()
            val type = if (q.startsWith("(")) "all" else q.substringBefore(' ').substringBefore('(').trim()
            val hasFeatures = q.contains('(')
            val typeOk = type in OUR_MEDIA
            val m = when {
                negate -> if (!typeOk && !hasFeatures) MediaMatch.YES else MediaMatch.NO
                !typeOk -> MediaMatch.NO
                hasFeatures -> MediaMatch.CONDITIONAL
                else -> MediaMatch.YES
            }
            if (m == MediaMatch.YES) return m
            if (m == MediaMatch.CONDITIONAL) result = m
        }
        return result
    }

    private fun parseCompound(c: String): Compound? {
        if (c.isEmpty() || c.contains(':') || c.contains('[')) return null
        var id: String? = null
        var rest = c
        val hash = rest.indexOf('#')
        if (hash >= 0) {
            val after = rest.substring(hash + 1)
            val idPart = after.substringBefore('.')
            if (idPart.isEmpty()) return null
            id = idPart
            rest = rest.substring(0, hash) + after.substring(idPart.length)
        }
        val parts = rest.split('.')
        val tag = parts[0].lowercase().takeIf { it.isNotEmpty() && it != "*" }
        val classes = parts.drop(1).filter { it.isNotEmpty() }
        return Compound(tag, classes, id)
    }

    /** Splits a selector into compounds and combinators (' ', '>', '+', '~'). */
    private fun splitSelector(sel: String): Pair<List<String>, List<Char>> {
        val tokens = ArrayList<String>()
        val combs = ArrayList<Char>()
        val cur = StringBuilder()
        var pending: Char? = null
        for (ch in sel) {
            val isComb = ch == '>' || ch == '+' || ch == '~'
            if (ch.isWhitespace() || isComb) {
                if (cur.isNotEmpty()) { tokens += cur.toString(); cur.setLength(0); pending = ' ' }
                if (isComb) pending = ch
            } else {
                if (pending != null && tokens.isNotEmpty()) combs += pending
                pending = null
                cur.append(ch)
            }
        }
        if (cur.isNotEmpty()) tokens += cur.toString()
        return tokens to combs
    }

    private fun addRule(selectors: String, declarations: String, conditional: Boolean) {
        var props = parseDeclarations(declarations)
        if (conditional) props = props.copy(hidden = null)
        if (props.isEmpty) return
        for (rawSel in selectors.split(',')) {
            val (tokens, combs) = splitSelector(rawSel.trim())
            if (tokens.isEmpty() || combs.size != tokens.size - 1) continue
            val compounds = tokens.map { parseCompound(it) }
            if (compounds.any { it == null }) continue
            val cs = compounds.filterNotNull()
            val only = cs.singleOrNull()
            if (only != null && only.id == null) {
                val tag = only.tag
                when {
                    only.classes.isEmpty() && tag != null -> byTag.merge(tag, props) { a, b -> a.merge(b) }
                    only.classes.isNotEmpty() && tag == null -> only.classes.forEach { c -> byClass.merge(c, props) { a, b -> a.merge(b) } }
                    only.classes.isNotEmpty() -> only.classes.forEach { c -> byTagClass.merge("$tag.$c", props) { a, b -> a.merge(b) } }
                }
            } else {
                complexRules += Complex(cs, combs, props)
            }
        }
    }

    private fun matchesComplex(r: Complex, e: Element): Boolean =
        r.compounds.last().matches(e) && matchFrom(r, r.compounds.size - 2, e)

    /** Matches compounds[0..i], given that compounds[i + 1] matched [e]. */
    private fun matchFrom(r: Complex, i: Int, e: Element): Boolean {
        if (i < 0) return true
        val c = r.compounds[i]
        return when (r.combinators[i]) {
            '>' -> e.parent()?.let { p -> c.matches(p) && matchFrom(r, i - 1, p) } ?: false
            '+' -> e.previousElementSibling()?.let { s -> c.matches(s) && matchFrom(r, i - 1, s) } ?: false
            '~' -> {
                var s = e.previousElementSibling()
                while (s != null) { if (c.matches(s) && matchFrom(r, i - 1, s)) return true; s = s.previousElementSibling() }
                false
            }
            else -> {
                var a = e.parent()
                while (a != null) { if (c.matches(a) && matchFrom(r, i - 1, a)) return true; a = a.parent() }
                false
            }
        }
    }

    fun propsFor(tag: String, classAttr: String, styleAttr: String): Props {
        var p = byTag[tag] ?: EMPTY
        if (classAttr.isNotBlank()) {
            for (c in classAttr.split(' ', '\t', '\n')) {
                if (c.isEmpty()) continue
                byClass[c]?.let { p = p.merge(it) }
                byTagClass["$tag.$c"]?.let { p = p.merge(it) }
            }
        }
        if (styleAttr.isNotBlank()) p = p.merge(parseDeclarations(styleAttr))
        return p
    }

    /** Like [propsFor] but also applies combinator and id selectors, matched against [e]'s context. */
    fun propsFor(e: Element, tag: String): Props {
        if (complexRules.isEmpty()) return propsFor(tag, e.attr("class"), e.attr("style"))
        var p = propsFor(tag, e.attr("class"), "")
        for (r in complexRules) if (matchesComplex(r, e)) p = p.merge(r.props)
        val style = e.attr("style")
        if (style.isNotBlank()) p = p.merge(parseDeclarations(style))
        return p
    }

    companion object {
        val EMPTY = Props()
        private val OUR_MEDIA = setOf("all", "screen", "amzn-kf8")

        fun parseDeclarations(decl: String): Props {
            var p = EMPTY
            for (d in decl.split(';')) {
                val idx = d.indexOf(':')
                if (idx <= 0) continue
                val name = d.substring(0, idx).trim().lowercase()
                val value = d.substring(idx + 1).trim().lowercase().removeSuffix("!important").trim()
                p = when (name) {
                    "font-weight" -> p.copy(bold = value == "bold" || value == "bolder" || (value.toIntOrNull() ?: 400) >= 600)
                    "font-style" -> p.copy(italic = value == "italic" || value == "oblique")
                    "text-decoration", "text-decoration-line" -> p.copy(
                        underline = value.contains("underline"), strike = value.contains("line-through"),
                    )
                    "display" -> when (value) {
                        "none" -> p.copy(hidden = true)
                        "block", "list-item", "table-row" -> p.copy(block = true, hidden = false)
                        "inline", "inline-block" -> p.copy(block = false, hidden = false)
                        else -> p
                    }
                    "visibility" -> if (value == "hidden") p.copy(hidden = true) else p
                    "text-align" -> p.copy(align = value)
                    "font-variant", "font-variant-caps" -> p.copy(smallCaps = value.contains("small-caps"))
                    "vertical-align" -> when (value) {
                        "super" -> p.copy(superscript = true)
                        "sub" -> p.copy(subscript = true)
                        else -> p
                    }
                    "font-family" -> if (value.contains("monospace") || value.contains("courier")) p.copy(monospace = true) else p
                    "page-break-before", "break-before" -> p.copy(pageBreakBefore = value == "always" || value == "page")
                    "font-size" -> p.copy(largeFont = isLarge(value))
                    else -> p
                }
            }
            return p
        }

        private fun isLarge(v: String): Boolean? {
            if (v in setOf("large", "x-large", "xx-large", "larger")) return true
            val num = v.takeWhile { it.isDigit() || it == '.' }.toFloatOrNull() ?: return null
            return when {
                v.endsWith("em") || v.endsWith("rem") -> num >= 1.4f
                v.endsWith("%") -> num >= 140f
                v.endsWith("pt") -> num >= 16f
                v.endsWith("px") -> num >= 22f
                else -> null
            }
        }
    }
}
