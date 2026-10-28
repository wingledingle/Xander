package app.quinten.formats.util

import app.quinten.model.BookFormat
import java.io.File
import java.security.MessageDigest

/**
 * Books inside plain .zip files (a PDF, EPUB, Kindle or text book zipped for download). The book is read from a
 * cached copy of the zip entry, so the zip itself is never unpacked by hand or changed. A zip of only pictures
 * reads as a comic; a zip with no book in it is not a book.
 */
object ZippedBook {
    private val pictures = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")

    /** True for names Quinten should look inside ("x.zip", but not formats that are zips themselves). */
    fun isPlainZip(name: String) = name.lowercase().endsWith(".zip") && BookFormat.fromFileName(name) == null

    /** The book entry inside [zip] (the largest one with a book extension) and its format, or null. */
    fun inner(zip: File): Pair<String, BookFormat>? = runCatching {
        Zips.open(zip).use { z ->
            z.entries().asSequence().filter { !it.isDirectory && !it.name.startsWith("__MACOSX") }
                .mapNotNull { e -> BookFormat.fromFileName(e.name)?.let { Triple(e.name, it, e.size) } }
                .maxByOrNull { it.third }?.let { it.first to it.second }
        }
    }.getOrNull()

    /** Format to open [zip] as: its inner book's, CBZ for a zip of pictures, null otherwise. */
    fun formatOf(zip: File): BookFormat? = inner(zip)?.second ?: runCatching {
        Zips.open(zip).use { z ->
            val files = z.entries().asSequence().filter { !it.isDirectory }.map { it.name.substringAfterLast('.').lowercase() }.toList()
            if (files.isNotEmpty() && files.count { it in pictures } * 2 >= files.size) BookFormat.CBZ else null
        }
    }.getOrNull()

    /** True when [zip] is itself an EPUB, DOCX or FB2.zip (those formats are zips) rather than a zip holding a book. */
    private fun isItself(zip: File, format: BookFormat?): Boolean = runCatching {
        Zips.open(zip).use { z ->
            when (format) {
                BookFormat.EPUB -> z.getEntry("mimetype") != null || z.getEntry("META-INF/container.xml") != null
                BookFormat.DOCX -> z.getEntry("word/document.xml") != null
                BookFormat.FB2 -> true // .fb2.zip is read by the FB2 reader itself
                else -> false
            }
        }
    }.getOrDefault(true)

    /**
     * [file] itself, or for a zip holding a book, that book extracted once into [cacheDir]. [format] is what the
     * book is opened as: a zip opened as e.g. PDF is unwrapped whatever its name (imported copies keep the inner
     * book's extension).
     */
    fun unwrap(file: File, cacheDir: File, format: BookFormat? = null): File {
        val looksZip = runCatching { file.inputStream().use { s -> s.read() == 'P'.code && s.read() == 'K'.code } }.getOrDefault(false)
        if (!looksZip || (format == null && !isPlainZip(file.name)) || isItself(file, format)) return file
        val (entry, _) = inner(file) ?: return file
        val key = MessageDigest.getInstance("SHA-1").digest("${file.absolutePath}|${file.length()}|${file.lastModified()}|$entry".toByteArray())
            .joinToString("") { "%02x".format(it) }.take(16)
        val out = File(File(cacheDir, "zipped"), "$key-${entry.substringAfterLast('/')}")
        if (out.isFile && out.length() > 0) return out
        out.parentFile?.mkdirs()
        val tmp = File(out.path + ".part")
        Zips.open(file).use { z -> z.getInputStream(z.getEntry(entry)).use { i -> tmp.outputStream().use { i.copyTo(it) } } }
        if (!tmp.renameTo(out)) { tmp.delete(); error("could not cache $entry") }
        return out
    }
}
