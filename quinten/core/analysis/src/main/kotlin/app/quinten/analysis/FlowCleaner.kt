package app.quinten.analysis


import app.quinten.model.Block
import app.quinten.model.BlockKind
import app.quinten.model.Chapter
import app.quinten.model.CleanOptions
import app.quinten.model.Removal
import app.quinten.model.RemovalCategory
import app.quinten.model.TextBlock

/**
 * Clean Reading Mode for flowing formats (EPUB, DOCX, FB2, TXT...). Many such files are
 * conversions of printed books and still carry page numbers, running headers and scanner
 * watermarks as stand-alone paragraphs. Detection is conservative: it only ever targets
 * whole short blocks, never headings, poems or dialogue, and every decision is reversible.
 */
object FlowCleaner {

    data class Detection(val blockIndex: Int, val removal: Removal)

    private val explicitPageNumber = Regex(
        "^[\\s\\-–—\\[(|.]*(page|pg\\.?|p\\.|стр\\.?|страница|seite|s\\.|pág\\.?|página|pagina)\\s*\\d{1,4}(\\s*(of|/|из|von|de)\\s*\\d{1,4})?[\\s\\-–—\\])|.]*$",
        RegexOption.IGNORE_CASE,
    )
    private val decoratedNumber = Regex("^\\s*[\\-–—~|\\[(]\\s*(\\d{1,4}|[ivxlcdm]{1,7})\\s*[\\-–—~|\\])]\\s*$", RegexOption.IGNORE_CASE)
    private val bareNumber = Regex("^\\s*(\\d{1,4})\\s*$")
    private val numberOfTotal = Regex("^\\s*\\d{1,4}\\s*(/|of|из)\\s*\\d{1,4}\\s*$", RegexOption.IGNORE_CASE)
    private val watermarkStrong = Regex(
        "(downloaded\\s+(free\\s+)?from|scanned\\s+by|uploaded\\s+by|oceanofpdf|for\\s+personal\\s+use\\s+only|this\\s+(e-?book|copy)\\s+(is\\s+)?(licensed|belongs|was\\s+(created|downloaded|produced))|free\\s+e-?books?\\s+(at|from)|get\\s+more\\s+e-?books|visit\\s+\\S+\\s+for\\s+more|unregistered\\s+version|evaluation\\s+(copy|version)|created\\s+(with|by)\\s+\\S*(pdf|convert))",
        RegexOption.IGNORE_CASE,
    )
    private val urlLike = Regex("(https?://|www\\.)\\S+", RegexOption.IGNORE_CASE)
    private const val SENTENCE_END = ".!?…\"”»’'):;"

    private val digits = Regex("\\d+")
    private val nonWord = Regex("[^\\p{L}#]+")

    fun normalize(s: String): String = s.lowercase().replace(digits, "#").replace(nonWord, " ").trim()

    private fun isDialogue(s: String) = s.isNotEmpty() && s[0] in "\"“”«»„‘'—–-"

    fun detect(chapterIndex: Int, blocks: List<Block>): List<Detection> {
        val out = ArrayList<Detection>()
        val taken = HashSet<Int>()
        val charPos = IntArray(blocks.size)
        var acc = 0
        for ((i, b) in blocks.withIndex()) { charPos[i] = acc; acc += b.length }

        fun candidate(b: Block): TextBlock? {
            if (b !is TextBlock) return null
            if (b.kind == BlockKind.HEADING || b.kind == BlockKind.POEM || b.kind == BlockKind.PREFORMATTED ||
                b.kind == BlockKind.FOOTNOTE || b.kind == BlockKind.TABLE_ROW
            ) return null
            return b
        }

        fun add(i: Int, cat: RemovalCategory) {
            if (!taken.add(i)) return
            val b = blocks[i]
            out += Detection(i, Removal("f:$chapterIndex:${b.firstUnit}", cat, (b as TextBlock).text, b.sourcePage))
        }

        // 1. Explicit page-number markers ("Page 12", "- 12 -", "12 / 300").
        for ((i, b) in blocks.withIndex()) {
            val t = candidate(b)?.text ?: continue
            if (t.length > 24) continue
            if (explicitPageNumber.matches(t) || decoratedNumber.matches(t) || numberOfTotal.matches(t)) add(i, RemovalCategory.PAGE_NUMBER)
        }

        // 2. Bare numbers forming an increasing, page-like sequence (not chapter numbers).
        val bare = blocks.indices.filter { i ->
            val t = candidate(blocks[i])?.text ?: return@filter false
            i !in taken && bareNumber.matches(t)
        }
        // Books converted from PDF usually carry their page numbers as centred lines, so centring or
        // bold is no evidence of a section number here (protecting them kept page numbers in).
        if (bare.size >= 3) {
            val values = bare.map { (blocks[it] as TextBlock).text.trim().toInt() }
            val increasing = values.zipWithNext().count { (a, b) -> b > a && b - a <= 3 }
            val avgGap = if (bare.size > 1) (charPos[bare.last()] - charPos[bare.first()]) / (bare.size - 1) else Int.MAX_VALUE
            if (increasing >= (bare.size - 1) * 0.7 && avgGap < 8000) {
                for ((k, i) in bare.withIndex()) {
                    // Keep a leading number that opens the chapter (likely a chapter number).
                    if (k == 0 && i <= 1 && values.size > 1 && values[1] - values[0] > 3) continue
                    add(i, RemovalCategory.PAGE_NUMBER)
                }
            }
        }

        // 3. Watermarks.
        val normCounts = HashMap<String, MutableList<Int>>()
        for ((i, b) in blocks.withIndex()) {
            val t = candidate(b)?.text ?: continue
            if (t.length <= 100 && !isDialogue(t)) normCounts.getOrPut(normalize(t)) { ArrayList() }.add(i)
        }
        for ((i, b) in blocks.withIndex()) {
            val t = candidate(b)?.text ?: continue
            if (t.length > 200 || i in taken) continue
            val repeated = (normCounts[normalize(t)]?.size ?: 0) >= 2
            if (watermarkStrong.containsMatchIn(t) || (repeated && urlLike.containsMatchIn(t) && t.length < 120)) {
                add(i, RemovalCategory.WATERMARK)
            }
        }

        // 4. Running headers / repeated boilerplate lines.
        for ((norm, idxs) in normCounts) {
            if (norm.isBlank() || norm == "#") continue
            val fresh = idxs.filter { it !in taken }
            if (fresh.size < 4) continue
            val sample = (blocks[fresh[0]] as TextBlock).text.trim()
            if (sample.last() in SENTENCE_END) continue // prose refrains ("Nevermore.") stay
            if (sample.all { !it.isLetter() }) continue // scene separators such as "* * *"
            val gaps = fresh.zipWithNext { a, b -> (charPos[b] - charPos[a]).toDouble() }
            if (gaps.any { it < 200 }) continue
            val mean = gaps.average()
            val sd = kotlin.math.sqrt(gaps.sumOf { (it - mean) * (it - mean) } / gaps.size)
            if (mean > 12000 || sd / mean > 0.75) continue
            for (i in fresh) add(i, RemovalCategory.RUNNING_HEADER)
        }
        return out.sortedBy { it.blockIndex }
    }

    /** Builds the displayed chapter from raw blocks according to [options]. */
    fun apply(index: Int, title: String?, blocks: List<Block>, options: CleanOptions, detections: List<Detection>? = null): Chapter {
        val det = detections ?: detect(index, blocks)
        if (det.isEmpty()) return Chapter(index, title, blocks)
        val byIndex = det.associateBy { it.blockIndex }
        val out = ArrayList<Block>(blocks.size)
        var orphanAnchors = emptyList<String>()
        for ((i, raw) in blocks.withIndex()) {
            val d = byIndex[i]
            val b = if (orphanAnchors.isNotEmpty() && (d == null || !options.hides(d.removal))) {
                app.quinten.model.ContentBuilder.withAnchors(raw, orphanAnchors + raw.anchors).also { orphanAnchors = emptyList() }
            } else raw
            when {
                d == null -> out += b
                options.hides(d.removal) -> orphanAnchors = orphanAnchors + b.anchors
                options.marks(d.removal) -> out += (b as TextBlock).copy(removalId = d.removal.id)
                else -> out += b
            }
        }
        return Chapter(index, title, out, det.map { it.removal })
    }
}
