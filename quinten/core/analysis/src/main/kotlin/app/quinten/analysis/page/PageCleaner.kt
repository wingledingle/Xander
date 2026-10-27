package app.quinten.analysis.page

import app.quinten.model.Removal
import app.quinten.model.RemovalCategory
import kotlin.math.abs

/**
 * Clean Reading Mode detection for fixed-layout pages. Looks at a window of neighbouring pages
 * and flags lines in the top/bottom margins that are page numbers or repeat across pages
 * (running headers/footers), plus watermark-like lines anywhere. Chapter headings, dialogue,
 * footnotes and body text are protected by size, position and repetition checks.
 */
object PageCleaner {
    private val pageNumber = Regex(
        "^[\\s\\-–—\\[(|.·•;:,'\"*~_]*(page|pg\\.?|p\\.|стр\\.?|страница|seite|s\\.|pág\\.?|página|pagina)?\\s*(\\d{1,4}|[ivxlcdm]{1,7})(\\s*(of|/|из|von|de)\\s*\\d{1,4})?[\\s\\-–—\\])|.·•;:,'\"*~_]*$",
        RegexOption.IGNORE_CASE,
    )
    private val watermark = Regex(
        "(downloaded\\s+(free\\s+)?from|scanned\\s+by|uploaded\\s+by|oceanofpdf|for\\s+personal\\s+use\\s+only|this\\s+(e-?book|copy)\\s+(is\\s+)?(licensed|belongs)|licensed\\s+to\\s+\\S+@|free\\s+e-?books?\\s+(at|from)|unregistered\\s+version|evaluation\\s+(copy|version|only)|created\\s+(with|by)\\s+\\S*\\s*(pdf|convert)|watermark|do\\s+not\\s+(copy|distribute)|confidential)",
        RegexOption.IGNORE_CASE,
    )
    private val url = Regex("(https?://|www\\.)\\S+", RegexOption.IGNORE_CASE)

    private val spaces = Regex("\\s+")
    private val nonAlnum = Regex("[^\\p{L}\\d]")
    private val digit = Regex("\\d")
    private val explicitPage = Regex("[a-zA-Zа-яА-Я]{2,}")
    private val pageWord = Regex("page|p\\.", RegexOption.IGNORE_CASE)
    private val romanWords = setOf("did", "mid", "mild", "civil", "dim", "lid", "mix", "vivid", "livid", "dill", "mill", "ill", "id", "mi", "di", "lim", "vic", "liv", "milli")

    /**
     * Comparison key for a margin line: letters only, with page numbers dropped wherever they sit.
     * Numbers include OCR misreadings of roman numerals ("XV111", "X1X", "XXIl1").
     */
    fun key(text: String): String =
        text.lowercase().split(spaces).joinToString("") { tok ->
            val core = tok.replace(nonAlnum, "")
            val numberLike = core.isNotEmpty() && core.length <= 8 && core.all { it.isDigit() || it in "ivxlcdmj" } && core !in romanWords
            if (numberLike) "" else core.replace(digit, "")
        }

    /** Same running header, allowing a few OCR misread letters. */
    private fun sameKey(a: String, b: String): Boolean {
        if (a == b) return true
        // OCR junk where the page number was ("A CASE OF IDENTITY HS)").
        val (sh, lo) = if (a.length <= b.length) a to b else b to a
        if (sh.length >= 6 && lo.length - sh.length <= 3 && (lo.startsWith(sh) || lo.endsWith(sh))) return true
        if (a.length < 6 || abs(a.length - b.length) > a.length / 6 + 1) return false
        return editDistance(a, b) <= maxOf(1, minOf(a.length, b.length) / 8)
    }

    private fun editDistance(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            prev = cur
        }
        return prev[b.length]
    }

    fun romanToInt(s: String): Int? {
        val v = mapOf('i' to 1, 'v' to 5, 'x' to 10, 'l' to 50, 'c' to 100, 'd' to 500, 'm' to 1000)
        var total = 0; var prev = 0
        for (c in s.lowercase().reversed()) {
            val x = v[c] ?: return null
            if (x < prev) total -= x else { total += x; prev = x }
        }
        return total.takeIf { it > 0 }
    }

    private val leadingNumber = Regex("^(\\d{1,4})\\.?\\s+(.*\\p{L}.*)$")
    private val trailingNumber = Regex("^(.*\\p{L}.*?)\\s+(\\d{1,4})$")

    /** Page number printed at the start or end of a header line ("12 Chapter 2. Variables", "2.3 Names 11"). */
    private fun edgeNumber(text: String): Int? {
        trailingNumber.matchEntire(text)?.let { return it.groupValues[2].toIntOrNull() }
        leadingNumber.matchEntire(text)?.let { m -> if (!m.groupValues[2].first().isDigit()) return m.groupValues[1].toIntOrNull() }
        return null
    }

    // Roman page numbers as OCR misreads them: 1 or | for i/l ("XXV1l", "X1X", "XXIl1").
    private val ocrRoman = Regex("^[\\s\\-–—\\[(.·•]*([ivxlcdm1|]{2,9})[\\s\\-–—\\])|.·•]*$", RegexOption.IGNORE_CASE)

    private fun numberValue(text: String): Int? {
        val t = text.trim()
        pageNumber.matchEntire(t)?.let { m ->
            val g = m.groupValues[2]
            return g.toIntOrNull() ?: romanToInt(g)
        }
        val r = ocrRoman.matchEntire(t)?.groupValues?.get(1) ?: return null
        // Needs real roman letters besides the look-alikes, so "11" or "l1" stay plain numbers/words.
        if (r.count { it.lowercaseChar() in "vxlcdm" } < 1 || r.none { it == '1' || it == '|' }) return null
        // With a misread "1" present, a lower-case "l" is a misread "I" too (XXV1l = XXVII).
        val fixed = r.map { if (it == '1' || it == '|' || it == 'l') 'i' else it }.joinToString("")
        return romanToInt(fixed)
    }

    class Zone(val page: Int, val line: Int, val top: Boolean, val t: TextLine, val key: String)

    private fun zones(p: PageText): List<Zone> {
        val out = ArrayList<Zone>()
        if (p.lines.isEmpty()) return out
        val h = p.height
        val byY = p.lines.indices.sortedBy { p.lines[it].box.y0 }
        for (i in byY.take(3)) {
            val l = p.lines[i]
            if (l.box.y1 < h * 0.14f) out += Zone(p.pageIndex, i, true, l, key(l.text))
        }
        for (i in byY.takeLast(3)) {
            val l = p.lines[i]
            if (l.box.y0 > h * 0.86f && out.none { it.line == i }) out += Zone(p.pageIndex, i, false, l, key(l.text))
        }
        return out
    }

    fun bodyFontSize(pages: List<PageText>): Float {
        val sizes = ArrayList<Float>()
        for (p in pages) for (l in p.lines) repeat((l.text.length / 10).coerceIn(1, 20)) { sizes += l.fontSize }
        return sizes.median().takeIf { it > 0 } ?: 10f
    }

    /**
     * Detects removable lines on [targetPages] using [window] as context (should include the
     * targets and their neighbours). Returns removals keyed by page and line index.
     */
    fun detect(window: List<PageText>, targetPages: Set<Int>): Map<Pair<Int, Int>, Removal> {
        val out = HashMap<Pair<Int, Int>, Removal>()
        if (window.isEmpty()) return out
        val body = bodyFontSize(window)
        val zoneMap = window.associate { it.pageIndex to zones(it) }
        val allZones = zoneMap.values.flatten()
        val pagesWithText = window.count { it.lines.isNotEmpty() }
        // Keys of every line, computed once (this used to be recomputed per line pair: seconds per chapter).
        val lineKeys = window.associate { pg -> pg.pageIndex to pg.lines.mapTo(HashSet()) { key(it.text) } }

        // A margin line is "set apart" when a clearly larger gap or a different type size separates it
        // from the body text. Body text that merely happens to be the first line of a page is not.
        val lineGap = HashMap<Int, Float>()
        fun typicalGap(p: PageText): Float = lineGap.getOrPut(p.pageIndex) {
            val ls = p.lines.sortedBy { it.baseline }
            val gaps = ls.zipWithNext { a, b -> b.baseline - a.baseline }.filter { it > 0 && it < body * 3 }.sorted()
            if (gaps.isEmpty()) body * 1.3f else gaps[gaps.size / 2]
        }
        fun setApart(p: PageText, l: TextLine): Boolean {
            // Scans have noisy sizes and spacing: headers are often only ~10% smaller or ~1.5 lines away.
            if (abs(l.fontSize - body) > body * 0.08f) return true
            val others = p.lines.filter { it !== l }
            val gap = if (l.box.cy < p.height / 2) others.filter { it.baseline > l.baseline }.minOfOrNull { it.baseline - l.baseline }
            else others.filter { it.baseline < l.baseline }.minOfOrNull { l.baseline - it.baseline }
            return gap == null || gap > typicalGap(p) * 1.3f
        }
        fun looksLikeDialogue(t: String) = t.firstOrNull()?.let { it in "“\"«„‘'—–-" } == true || t.endsWith(".") && t.length <= 30 && t.dropLast(1).all { it.isUpperCase() || !it.isLetter() }

        fun add(z: Zone, cat: RemovalCategory) {
            val k = z.page to z.line
            if (k !in out) out[k] = Removal("p:${z.page}:${z.line}", cat, z.t.text, z.page)
        }

        for (p in window) {
            if (p.pageIndex !in targetPages) continue
            val zs = zoneMap[p.pageIndex] ?: continue
            for (z in zs) {
                val t = z.t.text.trim()
                if (t.isEmpty()) continue
                val notHeading = z.t.fontSize <= body * 1.2f
                if (watermark.containsMatchIn(t) && t.length <= 200 && setApart(p, z.t)) { add(z, RemovalCategory.WATERMARK); continue }
                // Page numbers
                val v = numberValue(t)
                if (v != null && t.length <= 24 && notHeading) {
                    val isRoman = t.any { it.isLetter() } && t.trim().all { it.lowercaseChar() in "ivxlcdm" || !it.isLetter() } && !t.contains(pageWord)
                    val consistent = allZones.any { o ->
                        o.page != z.page && numberValue(o.t.text.trim())?.let { w -> w - v == o.page - z.page } == true
                    }
                    val farMargin = z.t.box.y1 < p.height * 0.08f || z.t.box.y0 > p.height * 0.92f
                    val explicit = t.contains(explicitPage) && !isRoman // "Page 12"
                    // A bare number is only removed when it is part of a page sequence or sits in the
                    // outer margin, so chapter numbers at the top of a page are preserved.
                    if (consistent || explicit || (farMargin && z.t.fontSize <= body * 1.05f && !isRoman) || (isRoman && farMargin && z.t.fontSize < body)) {
                        add(z, RemovalCategory.PAGE_NUMBER)
                        continue
                    }
                }
                // Headers whose text changes (section titles) but whose page number follows the page sequence.
                val edge = edgeNumber(t)
                if (edge != null && notHeading && t.length <= 120) {
                    val inSequence = allZones.any { o ->
                        o.page != z.page && o.top == z.top && o.t !== z.t &&
                            (edgeNumber(o.t.text.trim()) ?: numberValue(o.t.text.trim()))?.let { it - o.page == edge - z.page } == true
                    }
                    if (inSequence) {
                        add(z, if (z.top) RemovalCategory.RUNNING_HEADER else RemovalCategory.RUNNING_FOOTER)
                        continue
                    }
                }
                // Running headers / footers: same key in the same zone on several pages.
                if (z.key.length >= 2 && notHeading && t.length <= 160 && !looksLikeDialogue(t)) {
                    val similar = allZones.count { o ->
                        o.page != z.page && o.top == z.top && sameKey(z.key, o.key) && abs(o.t.box.cy / heightOf(window, o.page) - z.t.box.cy / p.height) < 0.04f
                    }
                    // One match is enough only for a line set apart from the text (alternating headers);
                    // otherwise two, so a repeated first line of text ("HAMLET.", "“Yes.”") is kept.
                    val needed = if (setApart(p, z.t)) 1 else 2
                    if (similar >= needed) {
                        add(z, if (z.top) RemovalCategory.RUNNING_HEADER else RemovalCategory.RUNNING_FOOTER)
                        continue
                    }
                }
            }
            // Watermarks: anywhere on the page.
            for ((i, l) in p.lines.withIndex()) {
                if ((p.pageIndex to i) in out) continue
                val t = l.text.trim()
                if (t.isEmpty() || t.length > 200) continue
                val k = key(t)
                val others = window.count { o -> o.pageIndex != p.pageIndex && k in lineKeys.getValue(o.pageIndex) }
                val repeated = k.length >= 6 && others >= maxOf(2, (pagesWithText * 0.5).toInt())
                val inMargin = l.box.y1 < p.height * 0.12f || l.box.y0 > p.height * 0.88f || l.box.x1 < p.width * 0.12f || l.box.x0 > p.width * 0.88f
                // Watermark phrases inside running text ("…it was confidential…") are words, not marks:
                // they must also repeat on another page or be set apart from the text.
                val strong = watermark.containsMatchIn(t) && (others >= 1 || setApart(p, l))
                // Repeated lines: links, huge stamped text, or small print in the margins. Small repeated
                // lines inside the page (table headers, captions) are content.
                val stamped = l.fontSize > body * 2.5f
                if (strong || (repeated && (url.containsMatchIn(t) || stamped || (l.fontSize < body * 0.8f && inMargin)))) {
                    out[p.pageIndex to i] = Removal("p:${p.pageIndex}:$i", RemovalCategory.WATERMARK, t, p.pageIndex)
                }
            }
        }
        return out
    }

    private fun heightOf(window: List<PageText>, page: Int) = window.firstOrNull { it.pageIndex == page }?.height ?: 1f
}
