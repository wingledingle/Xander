package com.xander.files.data.office

import android.util.Base64
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/** Converts office documents to self-contained HTML for in-app viewing. */
object OfficeConverter {

    fun toHtml(file: File, ext: String, darkMode: Boolean): String {
        val body = when (ext) {
            "docx" -> ZipFile(file).use { docx(it) }
            "xlsx" -> ZipFile(file).use { xlsx(it) }
            "pptx" -> ZipFile(file).use { pptx(it) }
            "odt", "odp" -> ZipFile(file).use { odfText(it) }
            "ods" -> ZipFile(file).use { odfSheet(it) }
            "doc" -> CompoundFile(file).use { cf -> LegacyOffice.docText(cf).joinToString("") { "<p>${esc(it)}</p>" } }
            "xls" -> CompoundFile(file).use { cf -> sheetsHtml(LegacyOffice.xlsSheets(cf).map { it.name to it.cells }) }
            "ppt" -> CompoundFile(file).use { cf -> LegacyOffice.pptSlides(cf).mapIndexed { i, s -> slide(i + 1, s.joinToString("") { t -> t.split('\r', '\n').joinToString("") { "<p>${esc(it)}</p>" } }) }.joinToString("") }
            "csv" -> csv(file)
            "rtf" -> rtf(file.readText(Charsets.ISO_8859_1)).joinToString("") { "<p>${esc(it)}</p>" }
            else -> "<p>Unsupported format</p>"
        }
        return page(body, darkMode)
    }

    private fun page(body: String, dark: Boolean) = """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<style>
body{font-family:sans-serif;margin:16px;line-height:1.5;${if (dark) "background:#121212;color:#e6e6e6;" else "background:#fff;color:#202020;"}}
table{border-collapse:collapse;margin:8px 0;font-size:14px}td,th{border:1px solid ${if (dark) "#444" else "#ccc"};padding:4px 8px;vertical-align:top;white-space:pre-wrap}
th{background:${if (dark) "#2a2a2a" else "#f0f0f0"};position:sticky;top:0}.sheet{overflow:auto;max-width:100%}
.slide{border:1px solid ${if (dark) "#444" else "#ccc"};border-radius:8px;padding:16px;margin:16px 0;aspect-ratio:16/9;overflow:hidden}
.slide h3{margin-top:0;color:#888;font-size:12px}img{max-width:100%;height:auto}nav a{margin-right:12px}
</style></head><body>$body</body></html>"""

    fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun parser(input: InputStream): XmlPullParser = Xml.newPullParser().apply {
        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false); setInput(input, "UTF-8")
    }

    private fun rels(zip: ZipFile, path: String): Map<String, String> {
        val dir = path.substringBeforeLast('/', "")
        val relPath = (if (dir.isEmpty()) "" else "$dir/") + "_rels/" + path.substringAfterLast('/') + ".rels"
        val e = zip.getEntry(relPath) ?: return emptyMap()
        val out = HashMap<String, String>()
        zip.getInputStream(e).use { s ->
            val p = parser(s)
            while (p.next() != XmlPullParser.END_DOCUMENT) if (p.eventType == XmlPullParser.START_TAG && p.name == "Relationship") {
                val target = p.getAttributeValue(null, "Target") ?: continue
                val mode = p.getAttributeValue(null, "TargetMode")
                out[p.getAttributeValue(null, "Id")] = if (mode == "External" || target.startsWith("/")) target.removePrefix("/") else normalize(if (dir.isEmpty()) target else "$dir/$target")
            }
        }
        return out
    }

    private fun normalize(p: String): String {
        val parts = ArrayList<String>()
        p.split('/').forEach { if (it == "..") { if (parts.isNotEmpty()) parts.removeAt(parts.size - 1) } else if (it != "." && it.isNotEmpty()) parts += it }
        return parts.joinToString("/")
    }

    private fun image(zip: ZipFile, path: String): String? {
        val e = zip.getEntry(path) ?: return null
        if (e.size > 4L shl 20) return null
        val mime = when (path.substringAfterLast('.').lowercase()) { "png" -> "image/png"; "gif" -> "image/gif"; "bmp" -> "image/bmp"; "svg" -> "image/svg+xml"; else -> "image/jpeg" }
        val b = zip.getInputStream(e).use { it.readBytes() }
        return "<img src=\"data:$mime;base64,${Base64.encodeToString(b, Base64.NO_WRAP)}\">"
    }

    // ---- DOCX ----
    private fun docx(zip: ZipFile): String {
        val doc = "word/document.xml"
        val rel = rels(zip, doc)
        val sb = StringBuilder()
        zip.getInputStream(zip.getEntry(doc) ?: return "<p>Empty document</p>").use { s ->
            val p = parser(s)
            var para = StringBuilder(); var tag = "p"; var bold = false; var italic = false; var under = false; var strike = false; var color: String? = null
            var inTable = 0; var listItem = false; var href: String? = null
            fun run(text: String) {
                if (text.isEmpty()) return
                var t = esc(text)
                if (bold) t = "<b>$t</b>"; if (italic) t = "<i>$t</i>"; if (under) t = "<u>$t</u>"; if (strike) t = "<s>$t</s>"
                if (color != null && color != "auto") t = "<span style=\"color:#$color\">$t</span>"
                if (href != null) t = "<a href=\"$href\">$t</a>"
                para.append(t)
            }
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                when (p.eventType) {
                    XmlPullParser.START_TAG -> when (p.name) {
                        "w:p" -> { para = StringBuilder(); tag = "p"; listItem = false }
                        "w:pStyle" -> p.getAttributeValue(null, "w:val")?.let { v ->
                            Regex("(?i)heading\\s*(\\d)|^(\\d)$").find(v)?.let { m -> tag = "h" + (m.groupValues[1].ifEmpty { m.groupValues[2] }).coerceAtMost("6") }
                            if (v.equals("Title", true)) tag = "h1"
                            if (v.contains("List", true)) listItem = true
                        }
                        "w:numPr" -> listItem = true
                        "w:r" -> { bold = false; italic = false; under = false; strike = false; color = null }
                        "w:b" -> bold = p.getAttributeValue(null, "w:val") != "0"
                        "w:i" -> italic = p.getAttributeValue(null, "w:val") != "0"
                        "w:u" -> under = p.getAttributeValue(null, "w:val") != "none"
                        "w:strike" -> strike = true
                        "w:color" -> color = p.getAttributeValue(null, "w:val")
                        "w:t" -> run(p.nextText())
                        "w:tab" -> para.append("&emsp;")
                        "w:br", "w:cr" -> para.append("<br>")
                        "w:hyperlink" -> href = p.getAttributeValue(null, "r:id")?.let { rel[it] }
                        "a:blip" -> p.getAttributeValue(null, "r:embed")?.let { rel[it] }?.let { image(zip, it) }?.let { para.append(it) }
                        "w:tbl" -> { inTable++; sb.append("<table>") }
                        "w:tr" -> sb.append("<tr>")
                        "w:tc" -> sb.append("<td>")
                    }
                    XmlPullParser.END_TAG -> when (p.name) {
                        "w:p" -> {
                            val content = para.toString()
                            sb.append(if (listItem) "<ul><li>$content</li></ul>" else if (content.isEmpty() && tag == "p") "<p>&nbsp;</p>" else "<$tag>$content</$tag>")
                        }
                        "w:hyperlink" -> href = null
                        "w:tc" -> sb.append("</td>")
                        "w:tr" -> sb.append("</tr>")
                        "w:tbl" -> { inTable--; sb.append("</table>") }
                    }
                }
            }
        }
        return sb.toString().replace("</ul><ul>", "")
    }

    // ---- XLSX ----
    private fun xlsx(zip: ZipFile): String {
        val shared = ArrayList<String>()
        zip.getEntry("xl/sharedStrings.xml")?.let { e ->
            zip.getInputStream(e).use { s ->
                val p = parser(s); var cur: StringBuilder? = null
                while (p.next() != XmlPullParser.END_DOCUMENT) {
                    if (p.eventType == XmlPullParser.START_TAG && p.name == "si") cur = StringBuilder()
                    else if (p.eventType == XmlPullParser.START_TAG && p.name == "t") cur?.append(p.nextText())
                    else if (p.eventType == XmlPullParser.END_TAG && p.name == "si") { shared += cur.toString(); cur = null }
                }
            }
        }
        val wbRels = rels(zip, "xl/workbook.xml")
        val sheets = ArrayList<Pair<String, String>>()
        zip.getInputStream(zip.getEntry("xl/workbook.xml") ?: return "<p>Empty workbook</p>").use { s ->
            val p = parser(s)
            while (p.next() != XmlPullParser.END_DOCUMENT) if (p.eventType == XmlPullParser.START_TAG && p.name == "sheet") {
                val target = wbRels[p.getAttributeValue(null, "r:id")] ?: continue
                sheets += (p.getAttributeValue(null, "name") ?: "Sheet") to target
            }
        }
        val data = sheets.map { (name, path) ->
            val cells = HashMap<Pair<Int, Int>, String>()
            zip.getEntry(path)?.let { e ->
                zip.getInputStream(e).use { s ->
                    val p = parser(s); var ref = ""; var type: String? = null; var value: String? = null; var inline = StringBuilder()
                    var rows = 0
                    while (p.next() != XmlPullParser.END_DOCUMENT && rows < MAX_ROWS) {
                        if (p.eventType == XmlPullParser.START_TAG) when (p.name) {
                            "row" -> rows++
                            "c" -> { ref = p.getAttributeValue(null, "r") ?: ""; type = p.getAttributeValue(null, "t"); value = null; inline = StringBuilder() }
                            "v" -> value = p.nextText()
                            "t" -> inline.append(p.nextText())
                        } else if (p.eventType == XmlPullParser.END_TAG && p.name == "c") {
                            val text = when (type) {
                                "s" -> shared.getOrElse(value?.toIntOrNull() ?: -1) { "" }
                                "inlineStr" -> inline.toString()
                                "b" -> if (value == "1") "TRUE" else "FALSE"
                                else -> value ?: ""
                            }
                            cellRef(ref)?.let { cells[it] = text }
                        }
                    }
                }
            }
            name to cells
        }
        return sheetsHtml(data)
    }

    private fun cellRef(r: String): Pair<Int, Int>? {
        val m = Regex("([A-Z]+)(\\d+)").find(r) ?: return null
        val col = m.groupValues[1].fold(0) { acc, c -> acc * 26 + (c - 'A' + 1) } - 1
        return (m.groupValues[2].toInt() - 1) to col
    }

    private fun colName(i: Int): String { var n = i + 1; val sb = StringBuilder(); while (n > 0) { val r = (n - 1) % 26; sb.insert(0, 'A' + r); n = (n - 1) / 26 }; return sb.toString() }

    private fun sheetsHtml(sheets: List<Pair<String, Map<Pair<Int, Int>, String>>>): String {
        val sb = StringBuilder()
        if (sheets.size > 1) sb.append("<nav>").append(sheets.mapIndexed { i, s -> "<a href=\"#s$i\">${esc(s.first)}</a>" }.joinToString("")).append("</nav>")
        sheets.forEachIndexed { i, (name, cells) ->
            sb.append("<h3 id=\"s$i\">${esc(name)}</h3>")
            if (cells.isEmpty()) { sb.append("<p>(empty)</p>"); return@forEachIndexed }
            val maxRow = cells.keys.maxOf { it.first }.coerceAtMost(MAX_ROWS)
            val maxCol = cells.keys.maxOf { it.second }.coerceAtMost(MAX_COLS)
            sb.append("<div class=\"sheet\"><table><tr><th></th>")
            for (c in 0..maxCol) sb.append("<th>${colName(c)}</th>")
            sb.append("</tr>")
            for (r in 0..maxRow) {
                sb.append("<tr><th>${r + 1}</th>")
                for (c in 0..maxCol) sb.append("<td>").append(esc(cells[r to c] ?: "")).append("</td>")
                sb.append("</tr>")
            }
            sb.append("</table></div>")
        }
        return sb.toString()
    }

    // ---- PPTX ----
    private fun pptx(zip: ZipFile): String {
        val presRels = rels(zip, "ppt/presentation.xml")
        val order = ArrayList<String>()
        zip.getInputStream(zip.getEntry("ppt/presentation.xml") ?: return "").use { s ->
            val p = parser(s)
            while (p.next() != XmlPullParser.END_DOCUMENT) if (p.eventType == XmlPullParser.START_TAG && p.name == "p:sldId") presRels[p.getAttributeValue(null, "r:id")]?.let { order += it }
        }
        return order.mapIndexed { i, path ->
            val rel = rels(zip, path)
            val sb = StringBuilder()
            zip.getEntry(path)?.let { e ->
                zip.getInputStream(e).use { s ->
                    val p = parser(s); var para = StringBuilder()
                    while (p.next() != XmlPullParser.END_DOCUMENT) {
                        if (p.eventType == XmlPullParser.START_TAG) when (p.name) {
                            "a:p" -> para = StringBuilder()
                            "a:t" -> para.append(esc(p.nextText()))
                            "a:blip" -> p.getAttributeValue(null, "r:embed")?.let { rel[it] }?.let { image(zip, it) }?.let { sb.append(it) }
                        } else if (p.eventType == XmlPullParser.END_TAG && p.name == "a:p" && para.isNotEmpty()) sb.append("<p>$para</p>")
                    }
                }
            }
            slide(i + 1, sb.toString())
        }.joinToString("")
    }

    private fun slide(n: Int, html: String) = "<div class=\"slide\"><h3>Slide $n</h3>$html</div>"

    // ---- OpenDocument ----
    private fun odfText(zip: ZipFile): String {
        val sb = StringBuilder()
        zip.getInputStream(zip.getEntry("content.xml") ?: return "").use { s ->
            val p = parser(s); var depth = 0; var cur = StringBuilder(); var tag = "p"
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                when (p.eventType) {
                    XmlPullParser.START_TAG -> when (p.name) {
                        "text:p", "text:h" -> { depth++; cur = StringBuilder(); tag = if (p.name == "text:h") "h" + (p.getAttributeValue(null, "text:outline-level") ?: "2") else "p" }
                        "text:tab" -> cur.append("&emsp;"); "text:line-break" -> cur.append("<br>"); "text:s" -> cur.append(" ")
                        "draw:page" -> sb.append("<div class=\"slide\">")
                    }
                    XmlPullParser.TEXT -> if (depth > 0) cur.append(esc(p.text))
                    XmlPullParser.END_TAG -> when (p.name) {
                        "text:p", "text:h" -> { depth--; sb.append("<$tag>$cur</$tag>") }
                        "draw:page" -> sb.append("</div>")
                    }
                }
            }
        }
        return sb.toString()
    }

    private fun odfSheet(zip: ZipFile): String {
        val sheets = ArrayList<Pair<String, MutableMap<Pair<Int, Int>, String>>>()
        zip.getInputStream(zip.getEntry("content.xml") ?: return "").use { s ->
            val p = parser(s); var row = -1; var col = 0; var cells: MutableMap<Pair<Int, Int>, String>? = null; var text = StringBuilder(); var repeat = 1
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                when (p.eventType) {
                    XmlPullParser.START_TAG -> when (p.name) {
                        "table:table" -> { cells = HashMap(); sheets += (p.getAttributeValue(null, "table:name") ?: "Sheet") to cells!!; row = -1 }
                        "table:table-row" -> { row++; col = 0 }
                        "table:table-cell", "table:covered-table-cell" -> { text = StringBuilder(); repeat = (p.getAttributeValue(null, "table:number-columns-repeated")?.toIntOrNull() ?: 1).coerceAtMost(50) }
                    }
                    XmlPullParser.TEXT -> text.append(p.text)
                    XmlPullParser.END_TAG -> if (p.name == "table:table-cell" || p.name == "table:covered-table-cell") {
                        val t = text.toString().trim()
                        repeat(repeat) { if (t.isNotEmpty() && row < MAX_ROWS) cells?.put(row to col, t); col++ }
                    }
                }
            }
        }
        return sheetsHtml(sheets)
    }

    private fun csv(file: File): String {
        val lines = file.bufferedReader().useLines { it.take(MAX_ROWS).toList() }
        val sep = listOf(',', ';', '\t').maxByOrNull { c -> lines.firstOrNull()?.count { it == c } ?: 0 } ?: ','
        val cells = HashMap<Pair<Int, Int>, String>()
        lines.forEachIndexed { r, l -> parseCsvLine(l, sep).forEachIndexed { c, v -> cells[r to c] = v } }
        return sheetsHtml(listOf(file.name to cells))
    }

    fun parseCsvLine(line: String, sep: Char): List<String> {
        val out = ArrayList<String>(); val sb = StringBuilder(); var q = false; var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                q && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                c == '"' -> q = !q
                c == sep && !q -> { out += sb.toString(); sb.clear() }
                else -> sb.append(c)
            }
            i++
        }
        out += sb.toString()
        return out
    }

    /** Plain text from RTF: drops groups like fonttbl/colortbl and control words, decodes \'hh and \uN. */
    fun rtf(src: String): List<String> {
        val out = StringBuilder(); var i = 0; var skipDepth = -1; var depth = 0
        while (i < src.length) {
            val c = src[i]
            when (c) {
                '{' -> { depth++; if (src.startsWith("{\\*", i) || Regex("^\\{\\\\(fonttbl|colortbl|stylesheet|info|pict|header|footer)").containsMatchIn(src.substring(i, minOf(src.length, i + 16)))) { if (skipDepth < 0) skipDepth = depth } }
                '}' -> { if (depth == skipDepth) skipDepth = -1; depth-- }
                '\\' -> {
                    val m = Regex("^\\\\([a-z]+)(-?\\d+)? ?|^\\\\'([0-9a-fA-F]{2})|^\\\\(.)").find(src.substring(i, minOf(src.length, i + 40)))
                    if (m != null) {
                        if (skipDepth < 0) when {
                            m.groupValues[3].isNotEmpty() -> out.append(m.groupValues[3].toInt(16).toChar())
                            m.groupValues[1] == "par" || m.groupValues[1] == "line" -> out.append('\n')
                            m.groupValues[1] == "tab" -> out.append('\t')
                            m.groupValues[1] == "u" -> m.groupValues[2].toIntOrNull()?.let { out.append((if (it < 0) it + 65536 else it).toChar()) }
                            m.groupValues[4].isNotEmpty() && m.groupValues[4] in listOf("\\", "{", "}") -> out.append(m.groupValues[4])
                        }
                        i += m.value.length - 1
                    }
                }
                '\r', '\n' -> Unit
                else -> if (skipDepth < 0) out.append(c)
            }
            i++
        }
        return out.toString().split('\n')
    }

    const val MAX_ROWS = 3000
    const val MAX_COLS = 60
}
