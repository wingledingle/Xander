package app.quinten.analysis.page

import kotlin.math.abs

/**
 * Turns positioned glyphs into text lines in reading order: rows are built by baseline,
 * split at large gaps, superscripts are attached to their line, and columns are resolved
 * with a recursive XY-cut that prefers vertical (column) cuts.
 */
object PageLayout {

    private class Frag(val glyphs: MutableList<Glyph>) {
        var box: Box = glyphs.map { it.box }.reduce { a, b -> a.union(b) }
        var baseline: Float = glyphs.map { it.baseline }.median()
        var fontSize: Float = glyphs.map { it.fontSize }.median()
        val supers = HashSet<Glyph>()
        fun recompute() {
            box = glyphs.map { it.box }.reduce { a, b -> a.union(b) }
            val normal = glyphs.filter { it !in supers }.ifEmpty { glyphs }
            baseline = normal.map { it.baseline }.median()
            fontSize = normal.map { it.fontSize }.median()
        }
    }

    fun build(pageIndex: Int, width: Float, height: Float, glyphs: List<Glyph>, images: List<PageImage> = emptyList()): PageText {
        val clean = glyphs.filter { it.text.isNotEmpty() && it.box.width >= 0 && it.fontSize > 0.5f && it.box.y1 > 0 && it.box.y0 < height && it.box.x1 > 0 && it.box.x0 < width }
        if (clean.isEmpty()) return PageText(pageIndex, width, height, emptyList(), images)
        val rows = buildRows(clean)
        val frags = ArrayList<Frag>()
        for (row in rows) frags += splitRow(row)
        attachSuperscripts(frags)
        val ordered = order(frags, width)
        val lines = ArrayList<TextLine>()
        val cols = ArrayList<Int>()
        val colBoxes = ArrayList<Box>()
        for ((ci, leaf) in ordered.withIndex()) {
            colBoxes += leaf.map { it.box }.reduce { a, b -> a.union(b) }
            for (line in mergeLeaf(leaf)) { lines += line; cols += ci }
        }
        return PageText(pageIndex, width, height, lines, images, cols.toIntArray(), colBoxes)
    }

    /**
     * For sources that already provide lines (OCR, DjVu). When [trustOrder] is false the lines
     * are re-ordered with the same column analysis as glyph input.
     */
    fun fromLines(pageIndex: Int, width: Float, height: Float, lines: List<TextLine>, images: List<PageImage> = emptyList(), trustOrder: Boolean = false, ocr: Boolean = false): PageText {
        if (lines.isEmpty()) return PageText(pageIndex, width, height, emptyList(), images, ocr = ocr)
        if (trustOrder) {
            return PageText(pageIndex, width, height, lines, images, IntArray(lines.size), listOf(lines.map { it.box }.reduce { a, b -> a.union(b) }), ocr)
        }
        // Wrap lines as fragments to reuse the XY-cut.
        val map = HashMap<Frag, TextLine>()
        val frags = lines.map { l ->
            val f = Frag(mutableListOf(Glyph(l.text, l.box, l.baseline, l.fontSize)))
            map[f] = l; f
        }
        val ordered = order(frags, width)
        val out = ArrayList<TextLine>()
        val cols = ArrayList<Int>()
        val colBoxes = ArrayList<Box>()
        for ((ci, leaf) in ordered.withIndex()) {
            colBoxes += leaf.map { it.box }.reduce { a, b -> a.union(b) }
            for (f in leaf.sortedWith(compareBy({ it.baseline }, { it.box.x0 }))) { out += map.getValue(f); cols += ci }
        }
        return PageText(pageIndex, width, height, out, images, cols.toIntArray(), colBoxes, ocr)
    }

    private fun buildRows(glyphs: List<Glyph>): List<MutableList<Glyph>> {
        val sorted = glyphs.sortedWith(compareBy({ it.baseline }, { it.box.x0 }))
        val rows = ArrayList<MutableList<Glyph>>()
        val rowBase = ArrayList<Float>()
        val rowSize = ArrayList<Float>()
        for (g in sorted) {
            var best = -1
            var bestD = Float.MAX_VALUE
            // Only recent rows can match because glyphs are sorted by baseline.
            var i = rows.size - 1
            while (i >= 0 && i >= rows.size - 6) {
                val tol = 0.3f * minOf(g.fontSize, rowSize[i])
                val d = abs(g.baseline - rowBase[i])
                if (d <= tol && d < bestD) { best = i; bestD = d }
                i--
            }
            if (best < 0) {
                rows += mutableListOf(g); rowBase += g.baseline; rowSize += g.fontSize
            } else {
                rows[best].add(g)
            }
        }
        return rows
    }

    private fun splitRow(row: MutableList<Glyph>): List<Frag> {
        row.sortBy { it.box.x0 }
        val out = ArrayList<Frag>()
        var cur = ArrayList<Glyph>()
        var lastX1 = Float.NEGATIVE_INFINITY
        var last: Glyph? = null
        for (g in row) {
            // Skip overprinted duplicates (fake bold, shadows).
            if (last != null && last.text == g.text && abs(last.box.x0 - g.box.x0) < 0.3f * maxOf(g.box.width, 0.5f)) continue
            val size = maxOf(g.fontSize, last?.fontSize ?: 0f)
            if (cur.isNotEmpty() && g.box.x0 - lastX1 > 1.0f * size) {
                out += Frag(cur); cur = ArrayList()
            }
            cur += g
            lastX1 = maxOf(lastX1, g.box.x1)
            last = g
        }
        if (cur.isNotEmpty()) out += Frag(cur)
        return out
    }

    private fun attachSuperscripts(frags: MutableList<Frag>) {
        if (frags.size < 2) return
        val sizes = frags.flatMap { f -> f.glyphs.map { it.fontSize } }
        val body = sizes.median()
        val small = frags.filter { f -> f.glyphs.size <= 6 && f.fontSize < body * 0.85f }
        for (s in small) {
            val host = frags.filter { it !== s && it.fontSize >= s.fontSize * 1.15f }
                .filter { h -> s.box.cy > h.box.y0 - h.fontSize * 0.2f && s.box.cy < h.box.y1 && s.baseline < h.baseline - h.fontSize * 0.1f }
                .filter { h -> s.box.x0 - h.box.x1 < h.fontSize * 0.8f && h.box.x0 - s.box.x1 < h.fontSize * 0.8f }
                .minByOrNull { h -> abs(h.box.cy - s.box.cy) } ?: continue
            host.glyphs += s.glyphs
            host.supers += s.glyphs
            host.glyphs.sortBy { it.box.x0 }
            host.recompute()
            frags.remove(s)
        }
    }

    /** Recursive XY-cut. Returns leaves (columns/blocks) in reading order. */
    private fun order(frags: List<Frag>, pageWidth: Float): List<List<Frag>> {
        val out = ArrayList<List<Frag>>()
        cut(frags, pageWidth, out, 0)
        return out
    }

    private fun cut(frags: List<Frag>, pageWidth: Float, out: MutableList<List<Frag>>, depth: Int) {
        if (frags.size <= 1 || depth > 12) { if (frags.isNotEmpty()) out += frags; return }
        val region = frags.map { it.box }.reduce { a, b -> a.union(b) }
        val medSize = frags.map { it.fontSize }.median()
        val gutter = findGutter(frags, region, medSize)
        if (gutter != null) {
            val (gx0, gx1) = gutter
            val crossing = frags.filter { it.box.x0 < gx0 && it.box.x1 > gx1 }
            if (crossing.isEmpty()) {
                cut(frags.filter { it.box.cx < gx0 }, pageWidth, out, depth + 1)
                cut(frags.filter { it.box.cx >= gx0 }, pageWidth, out, depth + 1)
                return
            }
            // Split into horizontal bands at the crossing (full-width) fragments.
            val bands = bandsAround(frags, crossing)
            if (bands.size > 1) { bands.forEach { cut(it, pageWidth, out, depth + 1) }; return }
        }
        // No column structure: try horizontal gaps.
        val bands = horizontalBands(frags, medSize)
        if (bands.size > 1) {
            val gutters = bands.map { b -> findGutter(b, b.map { it.box }.reduce { a, c -> a.union(c) }, medSize) }
            // Only recurse when a band could contain columns; otherwise keep as one leaf.
            if (gutters.any { it != null }) {
                // Consecutive bands whose gutters line up belong to the same column layout:
                // merge them so columns are read top-to-bottom across the bands.
                val groups = ArrayList<MutableList<Frag>>()
                var lastGutter: Pair<Float, Float>? = null
                for ((bi, b) in bands.withIndex()) {
                    val g = gutters[bi]
                    if (g != null && lastGutter != null && g.first < lastGutter.second && lastGutter.first < g.second) {
                        groups.last().addAll(b)
                        lastGutter = maxOf(g.first, lastGutter.first) to minOf(g.second, lastGutter.second)
                    } else {
                        groups += b.toMutableList()
                        lastGutter = g
                    }
                }
                if (groups.size == 1 && groups[0].size == frags.size) {
                    // Merging made the whole region one group again: split columns directly.
                    val region2 = frags.map { it.box }.reduce { a, c -> a.union(c) }
                    val g = gutters.filterNotNull().first()
                    cut(frags.filter { it.box.cx < g.first }, pageWidth, out, depth + 1)
                    cut(frags.filter { it.box.cx >= g.first }, pageWidth, out, depth + 1)
                    @Suppress("UNUSED_VARIABLE") val unused = region2
                } else groups.forEach { cut(it, pageWidth, out, depth + 1) }
                return
            }
        }
        out += frags
    }

    private fun bandsAround(frags: List<Frag>, crossing: List<Frag>): List<List<Frag>> {
        val bands = ArrayList<MutableList<Frag>>()
        val sortedCross = crossing.sortedBy { it.box.y0 }
        var prevBottom = Float.NEGATIVE_INFINITY
        val others = frags.filter { it !in crossing }
        for (c in sortedCross) {
            val above = others.filter { it.box.cy > prevBottom && it.box.cy < c.box.y0 + c.box.height * 0.5f }
            if (above.isNotEmpty()) bands += above.toMutableList()
            bands += mutableListOf(c)
            prevBottom = c.box.y0 + c.box.height * 0.5f
        }
        val rest = others.filter { it.box.cy >= prevBottom }
        if (rest.isNotEmpty()) bands += rest.toMutableList()
        // Merge consecutive single crossing fragments into one band (multi-line full-width text).
        val merged = ArrayList<MutableList<Frag>>()
        for (b in bands) {
            if (merged.isNotEmpty() && b.size == 1 && b[0] in crossing && merged.last().all { it in crossing }) merged.last().addAll(b)
            else merged += b
        }
        return merged
    }

    private fun horizontalBands(frags: List<Frag>, medSize: Float): List<List<Frag>> {
        val sorted = frags.sortedBy { it.box.y0 }
        val bands = ArrayList<MutableList<Frag>>()
        var cur = ArrayList<Frag>()
        var bottom = Float.NEGATIVE_INFINITY
        for (f in sorted) {
            if (cur.isNotEmpty() && f.box.y0 > bottom + medSize * 0.8f) { bands += cur; cur = ArrayList() }
            cur += f
            bottom = maxOf(bottom, f.box.y1)
        }
        if (cur.isNotEmpty()) bands += cur
        return bands
    }

    /** Finds a vertical whitespace gutter separating two text columns, ignoring a few full-width lines. */
    private fun findGutter(frags: List<Frag>, region: Box, medSize: Float): Pair<Float, Float>? {
        if (frags.size < 6 || region.width < medSize * 10) return null
        val wide = frags.filter { it.box.width > region.width * 0.6f }
        val narrow = frags.filter { it.box.width <= region.width * 0.6f }
        if (narrow.size < 6 || wide.size > narrow.size / 2) return null
        // Coverage of x by narrow fragments.
        val steps = 400
        val cell = region.width / steps
        val cover = IntArray(steps)
        for (f in narrow) {
            val a = ((f.box.x0 - region.x0) / cell).toInt().coerceIn(0, steps - 1)
            val b = ((f.box.x1 - region.x0) / cell).toInt().coerceIn(0, steps - 1)
            for (i in a..b) cover[i]++
        }
        val minGap = maxOf(medSize * 0.8f, region.width * 0.015f)
        var best: Pair<Float, Float>? = null
        var bestScore = 0f
        var i = (steps * 0.15).toInt()
        val end = (steps * 0.85).toInt()
        while (i < end) {
            if (cover[i] == 0) {
                var j = i
                while (j < end && cover[j] == 0) j++
                val gx0 = region.x0 + i * cell
                val gx1 = region.x0 + j * cell
                if (gx1 - gx0 >= minGap) {
                    val left = narrow.filter { it.box.x1 <= gx0 + cell }
                    val right = narrow.filter { it.box.x0 >= gx1 - cell }
                    val lh = left.sumOf { it.box.height.toDouble() }.toFloat()
                    val rh = right.sumOf { it.box.height.toDouble() }.toFloat()
                    // Both sides need substantial text spanning a good part of the region height.
                    val lSpan = if (left.isEmpty()) 0f else left.maxOf { it.box.y1 } - left.minOf { it.box.y0 }
                    val rSpan = if (right.isEmpty()) 0f else right.maxOf { it.box.y1 } - right.minOf { it.box.y0 }
                    if (left.size >= 3 && right.size >= 3 && lSpan > region.height * 0.3f && rSpan > region.height * 0.3f &&
                        lh > region.height * 0.15f && rh > region.height * 0.15f
                    ) {
                        val score = (gx1 - gx0) * minOf(left.size, right.size)
                        if (score > bestScore) { bestScore = score; best = gx0 to gx1 }
                    }
                }
                i = j
            } else i++
        }
        return best
    }

    /** Re-merges fragments of a leaf that share a baseline and builds text lines. */
    private fun mergeLeaf(leaf: List<Frag>): List<TextLine> {
        val sorted = leaf.sortedWith(compareBy({ it.baseline }, { it.box.x0 }))
        val groups = ArrayList<MutableList<Frag>>()
        for (f in sorted) {
            val g = groups.lastOrNull()
            if (g != null && abs(g.last().baseline - f.baseline) <= 0.3f * minOf(f.fontSize, g.last().fontSize)) g += f else groups += mutableListOf(f)
        }
        return groups.map { g -> toLine(g.sortedBy { it.box.x0 }) }
    }

    private fun toLine(frags: List<Frag>): TextLine {
        val sb = StringBuilder()
        val runs = ArrayList<StyleRun>()
        var prev: Glyph? = null
        val supers = frags.flatMap { it.supers }.toHashSet()
        val allGlyphs = frags.flatMap { it.glyphs }.sortedBy { it.box.x0 }
        val lineSize = frags.map { it.fontSize }.median()
        for (g in allGlyphs) {
            if (prev != null) {
                val gap = g.box.x0 - prev.box.x1
                val thr = minOf(prev.spaceWidth, g.spaceWidth).coerceAtLeast(lineSize * 0.12f) * 0.5f
                if (gap > thr && !sb.endsWith(" ") && !g.text.startsWith(" ")) sb.append(' ')
            }
            val start = sb.length
            sb.append(g.text)
            val sup = g in supers
            val last = runs.lastOrNull()
            if (last != null && last.end == start && last.bold == g.bold && last.italic == g.italic && last.superscript == sup && abs(last.fontSize - g.fontSize) < 0.5f) {
                runs[runs.size - 1] = last.copy(end = sb.length)
            } else runs += StyleRun(start, sb.length, g.fontSize, g.bold, g.italic, sup)
            prev = g
        }
        val box = frags.map { it.box }.reduce { a, b -> a.union(b) }
        // Trim surrounding whitespace, keeping runs consistent.
        val text = sb.toString()
        val lead = text.length - text.trimStart().length
        val trimmed = text.trim()
        val fixedRuns = runs.mapNotNull { r ->
            val s = (r.start - lead).coerceIn(0, trimmed.length)
            val e = (r.end - lead).coerceIn(0, trimmed.length)
            if (e > s) r.copy(start = s, end = e) else null
        }
        return TextLine(trimmed, box, frags.map { it.baseline }.median(), lineSize, fixedRuns)
    }
}

internal fun List<Float>.median(): Float {
    if (isEmpty()) return 0f
    val s = sorted()
    return s[s.size / 2]
}
