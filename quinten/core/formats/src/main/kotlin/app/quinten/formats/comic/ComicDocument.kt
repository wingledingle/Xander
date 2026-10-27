package app.quinten.formats.comic

import app.quinten.formats.UnsupportedBookException
import app.quinten.formats.rar.RarArchive
import app.quinten.formats.util.Paths
import app.quinten.model.BookDocument
import app.quinten.model.BookFormat
import app.quinten.model.BookMetadata
import app.quinten.model.Chapter
import app.quinten.model.CleanOptions
import app.quinten.model.ImageBlock
import app.quinten.model.ImageData
import app.quinten.model.Locator
import app.quinten.model.TocEntry
import java.io.Closeable
import java.io.File
import app.quinten.formats.util.FileTitle
import app.quinten.formats.util.Zips

/** Read-only access to the files of an archive. */
interface Archive : Closeable {
    val names: List<String>
    fun read(name: String): ByteArray?
}

class ZipArchive(file: File) : Archive {
    // Japanese CBZ files often carry Shift_JIS names; CP437 (old Windows tools) is the last resort.
    private val zip = Zips.open(file, listOf("Shift_JIS"))
    override val names: List<String> = zip.entries().asSequence().filter { !it.isDirectory }.map { it.name }.toList()
    override fun read(name: String): ByteArray? = synchronized(zip) { zip.getEntry(name)?.let { e -> zip.getInputStream(e).use { it.readBytes() } } }
    override fun close() = zip.close()
}

/**
 * Comic book archives (CBZ = zip, CBR = rar). Each page image is one chapter holding a single
 * full-width [ImageBlock], so pages always scale to the screen width (no horizontal scrolling).
 * Text in speech bubbles can be made reflowable with the OCR text mode in the app layer.
 */
class ComicDocument(private val archive: Archive, override val format: BookFormat, title: String?) : BookDocument {
    private val pages: List<String> = archive.names
        .filter { n -> IMAGE_EXT.any { n.lowercase().endsWith(it) } && !n.substringAfterLast('/').startsWith(".") && !n.startsWith("__MACOSX") }
        .sortedWith(Paths.naturalOrder)

    init {
        if (pages.isEmpty()) {
            archive.close()
            throw UnsupportedBookException("No images found in the comic archive")
        }
    }

    private val comicInfo: Map<String, String> by lazy {
        val xml = archive.names.firstOrNull { it.equals("ComicInfo.xml", true) }?.let { archive.read(it) } ?: return@lazy emptyMap()
        val doc = org.jsoup.Jsoup.parse(String(xml, Charsets.UTF_8), "", org.jsoup.parser.Parser.xmlParser())
        doc.selectFirst("ComicInfo")?.children()?.associate { it.normalName() to it.text() } ?: emptyMap()
    }

    override val metadata = BookMetadata(
        title = comicInfo["title"]?.ifBlank { null } ?: comicInfo["series"]?.ifBlank { null } ?: title,
        authors = listOfNotNull(comicInfo["writer"]?.ifBlank { null }),
        series = comicInfo["series"]?.ifBlank { null },
        seriesIndex = comicInfo["number"]?.toFloatOrNull(),
        description = comicInfo["summary"]?.ifBlank { null },
        coverResource = pages.first(),
    )

    override val toc: List<TocEntry> = emptyList()
    override val chapterCount: Int get() = pages.size
    override fun chapterTitle(index: Int) = "Page ${index + 1}"
    override fun chapterWeight(index: Int) = 1000

    override fun loadChapter(index: Int, clean: CleanOptions): Chapter =
        Chapter(index, chapterTitle(index), listOf(ImageBlock(pages[index], sourcePage = index, fullPage = true)))

    override fun image(resource: String): ImageData? = archive.read(resource)?.let { ImageData.Encoded(it) }

    override val originalPageCount: Int get() = pages.size
    override fun originalPage(page: Int, targetWidth: Int): ImageData? = image(pages[page])
    override fun locatorForOriginalPage(page: Int) = Locator(page, 0, 0)

    override fun close() = archive.close()

    companion object {
        // AVIF decodes on Android 12+; JPEG XL and TIFF have no platform decoder.
        val IMAGE_EXT = listOf(".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp", ".avif")

        fun open(file: File, format: BookFormat): ComicDocument {
            val head = file.inputStream().use { s -> ByteArray(8).also { s.read(it) } }
            val isZip = head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
            val isRar = String(head, 0, 4, Charsets.ISO_8859_1) == "Rar!"
            val archive: Archive = when {
                isZip -> ZipArchive(file)
                isRar -> RarArchive(file)
                else -> throw UnsupportedBookException("Unknown comic archive type")
            }
            return ComicDocument(archive, if (isZip) BookFormat.CBZ else if (isRar) BookFormat.CBR else format, FileTitle.of(file))

        }
    }
}
