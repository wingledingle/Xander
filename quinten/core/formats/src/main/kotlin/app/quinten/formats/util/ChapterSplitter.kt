package app.quinten.formats.util

import app.quinten.formats.RawChapter
import app.quinten.model.Block
import app.quinten.model.BlockKind
import app.quinten.model.ContentBuilder
import app.quinten.model.LinkSpan
import app.quinten.model.LinkTarget
import app.quinten.model.TextBlock
import app.quinten.model.TocEntry

/**
 * Splits one long block stream (DOCX, DOC, RTF, MOBI) into chapters at top-level headings,
 * further chunking very long chapters so layout stays fast. Internal links of the form
 * `q:-1#anchor` (anchor without chapter) are rewritten to the chapter that holds the anchor.
 */
object ChapterSplitter {
    const val MAX_CHARS = 100_000

    class Result(val chapters: List<RawChapter>, val toc: List<TocEntry>, val anchorChapter: Map<String, Int> = emptyMap())

    /**
     * Splits pre-segmented content (e.g. KF8 files or MOBI page-break sections): every segment
     * starts a new chapter; long segments are chunked by size.
     */
    fun splitSegments(segments: List<List<Block>>, trailing: List<Pair<String, List<Block>>> = emptyList(), maxChars: Int = MAX_CHARS): Result {
        if (segments.size <= 1) return split(segments.firstOrNull() ?: emptyList(), trailing, maxChars)
        val groups = ArrayList<List<Block>>()
        for (seg in segments) {
            var cur = ArrayList<Block>(); var size = 0
            for (b in seg) {
                if (size > maxChars && b is TextBlock && b.kind != BlockKind.HEADING) { groups += cur; cur = ArrayList(); size = 0 }
                cur += b; size += b.length
            }
            if (cur.isNotEmpty()) groups += cur
        }
        val all = groups + trailing.map { it.second }.filter { it.isNotEmpty() }
        val anchorChapter = HashMap<String, Int>()
        all.forEachIndexed { ci, g -> g.forEach { b -> b.anchors.forEach { anchorChapter.putIfAbsent(it, ci) } } }
        val toc = ArrayList<TocEntry>()
        val minLevel = groups.flatten().filterIsInstance<TextBlock>().filter { it.kind == BlockKind.HEADING }.minOfOrNull { it.level } ?: 1
        val trailingTitles = trailing.filter { it.second.isNotEmpty() }.map { it.first }
        val chapters = all.mapIndexed { ci, g ->
            val fixed = g.mapIndexed { i, b -> ContentBuilder.assignUnit(rewriteLinks(b, anchorChapter), i) }
            val title = if (ci >= groups.size) trailingTitles[ci - groups.size]
            else fixed.firstOrNull { it is TextBlock && it.kind == BlockKind.HEADING }?.let { (it as TextBlock).text.take(120) }
            if (ci < groups.size) {
                for (b in fixed) if (b is TextBlock && b.kind == BlockKind.HEADING && b.level <= minLevel + 1) {
                    toc += TocEntry(b.text.take(120), b.level - minLevel, ci, b.anchors.firstOrNull(), b.firstUnit)
                }
            } else toc += TocEntry(title ?: "Notes", 0, ci)
            RawChapter(title, fixed)
        }
        return Result(chapters, toc, anchorChapter)
    }

    fun split(blocks: List<Block>, trailing: List<Pair<String, List<Block>>> = emptyList(), maxChars: Int = MAX_CHARS): Result {
        val headingLevels = blocks.filterIsInstance<TextBlock>().filter { it.kind == BlockKind.HEADING }.map { it.level }
        val splitLevel = headingLevels.minOrNull()?.let { min ->
            // Only split at the top level if it occurs more than once; otherwise use the next level.
            if (headingLevels.count { it == min } > 1) min else headingLevels.filter { it > min }.minOrNull() ?: min
        }
        val groups = ArrayList<MutableList<Block>>()
        val titles = ArrayList<String?>()
        var cur = ArrayList<Block>()
        var curTitle: String? = null
        var size = 0
        fun flush() {
            if (cur.isNotEmpty()) { groups += cur; titles += curTitle }
            cur = ArrayList(); size = 0
        }
        for (b in blocks) {
            val isSplit = b is TextBlock && b.kind == BlockKind.HEADING && splitLevel != null && b.level <= splitLevel
            if (isSplit) {
                flush(); curTitle = (b as TextBlock).text.take(120)
            } else if (size > maxChars && b is TextBlock && b.kind != BlockKind.HEADING) {
                flush()
            }
            cur.add(b); size += b.length
        }
        flush()
        if (groups.isEmpty()) { groups += ArrayList<Block>(); titles += null }
        val firstTrailing = groups.size
        for ((t, bl) in trailing) if (bl.isNotEmpty()) { groups += bl.toMutableList(); titles += t }

        // anchor -> chapter
        val anchorChapter = HashMap<String, Int>()
        groups.forEachIndexed { ci, g -> g.forEach { b -> b.anchors.forEach { anchorChapter.putIfAbsent(it, ci) } } }
        val toc = ArrayList<TocEntry>()
        val chapters = groups.mapIndexed { ci, g ->
            val fixed = g.mapIndexed { i, b -> ContentBuilder.assignUnit(rewriteLinks(b, anchorChapter), i) }
            if (ci < firstTrailing) for (b in fixed) {
                if (b is TextBlock && b.kind == BlockKind.HEADING && b.level <= (splitLevel ?: 0) + 2) {
                    toc += TocEntry(b.text.take(120), b.level - (headingLevels.minOrNull() ?: 1), ci, b.anchors.firstOrNull(), b.firstUnit)
                }
            }
            if (ci >= firstTrailing) toc += TocEntry(titles[ci] ?: "Notes", 0, ci)
            RawChapter(titles[ci], fixed)
        }
        return Result(chapters, toc, anchorChapter)
    }

    /** Placeholder link target for an anchor whose chapter is not known yet. */
    fun pendingLink(anchor: String) = "q:-1#$anchor"

    private fun rewriteLinks(b: Block, anchorChapter: Map<String, Int>): Block {
        if (b !is TextBlock || b.spans.none { it is LinkSpan && it.target.startsWith("q:-1#") }) return b
        return b.copy(spans = b.spans.map { s ->
            if (s is LinkSpan && s.target.startsWith("q:-1#")) {
                val a = s.target.substring(5)
                val ch = anchorChapter[a]
                if (ch != null) s.copy(target = LinkTarget(ch, a).encode()) else s
            } else s
        })
    }
}
