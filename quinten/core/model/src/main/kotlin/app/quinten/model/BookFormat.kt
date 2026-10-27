package app.quinten.model

/** All formats supported by the reader. Detection is by extension first and magic bytes second. */
enum class BookFormat(val label: String, val extensions: List<String>, val pageBased: Boolean) {
    EPUB("EPUB", listOf("epub"), false),
    PDF("PDF", listOf("pdf"), true),
    TXT("TXT", listOf("txt", "text"), false),
    DOC("DOC", listOf("doc"), false),
    DOCX("DOCX", listOf("docx"), false),
    RTF("RTF", listOf("rtf"), false),
    FB2("FB2", listOf("fb2", "fb2.zip", "fbz"), false),
    MOBI("MOBI", listOf("mobi", "prc", "pdb"), false),
    AZW3("AZW3", listOf("azw3", "azw", "kf8"), false),
    DJVU("DjVu", listOf("djvu", "djv"), true),
    CBZ("CBZ", listOf("cbz"), true),
    CBR("CBR", listOf("cbr"), true);

    companion object {
        /** Returns the format for a file name, or null when the extension is not supported. */
        fun fromFileName(name: String): BookFormat? {
            val lower = name.lowercase()
            // Longest extensions first so "x.fb2.zip" resolves to FB2 rather than failing on "zip".
            return entries.flatMap { f -> f.extensions.map { it to f } }
                .sortedByDescending { it.first.length }
                .firstOrNull { lower.endsWith("." + it.first) }?.second
        }

        /** Sniffs the format from the first bytes of a file. Useful for content URIs without names. */
        fun sniff(head: ByteArray, nameHint: String? = null): BookFormat? {
            fun at(off: Int, s: String) = head.size >= off + s.length &&
                s.indices.all { head[off + it] == s[it].code.toByte() }
            nameHint?.let { fromFileName(it) }?.let { byName ->
                // Trust the name unless magic bytes contradict it in a well known way.
                if (byName == CBR && at(0, "PK")) return CBZ
                if (byName == CBZ && at(0, "Rar!")) return CBR
                return byName
            }
            return when {
                at(0, "%PDF") -> PDF
                at(0, "AT&TFORM") -> DJVU
                at(0, "Rar!") -> CBR
                at(0, "{\\rtf") -> RTF
                at(60, "BOOKMOBI") || at(60, "TEXtREAd") -> MOBI
                // Kindle Topaz / KFX: routed to the Kindle reader, which explains they're unsupported.
                at(0, "TPZ") || (at(0, "CONT") && head.size >= 6 && head[4] in 1..2 && head[5].toInt() == 0) || (head.size >= 7 && head[0] == 0xEA.toByte() && at(1, "DRMION")) -> AZW3
                head.size >= 8 && head[0] == 0xD0.toByte() && head[1] == 0xCF.toByte() &&
                    head[2] == 0x11.toByte() && head[3] == 0xE0.toByte() -> DOC
                at(0, "PK") -> {
                    val s = String(head, Charsets.ISO_8859_1)
                    when {
                        s.contains("application/epub+zip") -> EPUB
                        s.contains("word/") || s.contains("[Content_Types].xml") -> DOCX
                        s.contains(".fb2") -> FB2
                        else -> CBZ
                    }
                }
                String(head, Charsets.ISO_8859_1).let { it.contains("<FictionBook") } -> FB2
                else -> null
            }
        }

        val allExtensions: Set<String> = entries.flatMap { it.extensions }.toSet()
    }
}
