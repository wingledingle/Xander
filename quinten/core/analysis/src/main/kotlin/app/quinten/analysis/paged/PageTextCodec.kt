package app.quinten.analysis.paged

import app.quinten.analysis.page.Box
import app.quinten.analysis.page.PageImage
import app.quinten.analysis.page.PageText
import app.quinten.analysis.page.StyleRun
import app.quinten.analysis.page.TextLine
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/** Compact binary serialisation of [PageText] for the OCR result cache. */
object PageTextCodec {
    private const val VERSION = 1

    fun encode(p: PageText): ByteArray {
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use { o ->
            o.writeInt(VERSION)
            o.writeInt(p.pageIndex); o.writeFloat(p.width); o.writeFloat(p.height); o.writeByte((if (p.ocr) 1 else 0) or (if (p.scanned) 2 else 0))
            o.writeInt(p.lines.size)
            for ((i, l) in p.lines.withIndex()) {
                o.writeUTF(l.text); box(o, l.box); o.writeFloat(l.baseline); o.writeFloat(l.fontSize); o.writeFloat(l.confidence)
                o.writeInt(p.columnOf.getOrElse(i) { 0 })
                o.writeInt(l.runs.size)
                for (r in l.runs) { o.writeInt(r.start); o.writeInt(r.end); o.writeFloat(r.fontSize); o.writeBoolean(r.bold); o.writeBoolean(r.italic); o.writeBoolean(r.superscript) }
            }
            o.writeInt(p.images.size)
            for (im in p.images) { box(o, im.box); o.writeUTF(im.resource) }
            o.writeInt(p.columns.size)
            for (c in p.columns) box(o, c)
        }
        return bos.toByteArray()
    }

    private fun box(o: DataOutputStream, b: Box) { o.writeFloat(b.x0); o.writeFloat(b.y0); o.writeFloat(b.x1); o.writeFloat(b.y1) }
    private fun box(i: DataInputStream) = Box(i.readFloat(), i.readFloat(), i.readFloat(), i.readFloat())

    fun decode(bytes: ByteArray): PageText? = try {
        DataInputStream(ByteArrayInputStream(bytes)).use { i ->
            if (i.readInt() != VERSION) return null
            val page = i.readInt(); val w = i.readFloat(); val h = i.readFloat(); val flags = i.readByte().toInt(); val ocr = flags and 1 != 0; val scanned = flags and 2 != 0
            val n = i.readInt()
            val lines = ArrayList<TextLine>(n)
            val cols = IntArray(n)
            for (k in 0 until n) {
                val text = i.readUTF(); val b = box(i); val base = i.readFloat(); val fs = i.readFloat(); val conf = i.readFloat()
                cols[k] = i.readInt()
                val rn = i.readInt()
                val runs = (0 until rn).map { StyleRun(i.readInt(), i.readInt(), i.readFloat(), i.readBoolean(), i.readBoolean(), i.readBoolean()) }
                lines += TextLine(text, b, base, fs, runs, conf)
            }
            val imgs = (0 until i.readInt()).map { PageImage(box(i), i.readUTF()) }
            val columns = (0 until i.readInt()).map { box(i) }
            PageText(page, w, h, lines, imgs, cols, columns, ocr, scanned)
        }
    } catch (_: Exception) { null }
}

/** File-based [PageTextStore] (one file per page). */
/**
 * Disk cache of page text. Text-layer pages live under the reconstruction [LAYER_VERSION] (cheap to
 * redo, so an algorithm change simply re-extracts them); OCR pages live under their OCR language
 * key (expensive, kept across app updates, redone only when the languages change).
 */
class DirectoryPageTextStore(private val dir: File) : PageTextStore {
    @Volatile private var ocrKey = "default"
    init { dir.mkdirs() }

    override fun configure(ocrKey: String) { this.ocrKey = ocrKey.ifBlank { "default" }.replace(Regex("[^A-Za-z0-9+_-]"), "_") }

    private fun layerDir() = File(dir, "layer-v$LAYER_VERSION")
    private fun ocrDir() = File(dir, "ocr-$ocrKey")

    private fun read(f: File): PageText? = f.takeIf { it.isFile }?.let { runCatching { PageTextCodec.decode(it.readBytes()) }.getOrNull() }

    override fun load(page: Int): PageText? =
        read(File(ocrDir(), "p$page.bin"))
            ?: read(File(layerDir(), "p$page.bin"))
            // Files from older versions: OCR results are still good; old text-layer results are not.
            ?: read(File(dir, "p$page.bin"))?.takeIf { it.ocr && ocrKey in setOf("default", "eng") }

    override fun save(page: Int, text: PageText) {
        val d = if (text.ocr) ocrDir() else layerDir()
        d.mkdirs()
        // A unique temporary file per write: two threads saving the same page must not interleave.
        val tmp = File.createTempFile("page$page-", ".tmp", d)
        try {
            tmp.writeBytes(PageTextCodec.encode(text))
            if (!tmp.renameTo(File(d, "p$page.bin"))) tmp.delete()
        } catch (e: Exception) { tmp.delete() }
    }

    override fun meta(key: String): String? = File(dir, "$key.meta").takeIf { it.isFile }?.readText()
    override fun putMeta(key: String, value: String) { runCatching { File(dir, "$key.meta").writeText(value) } }

    fun clear() { dir.deleteRecursively(); dir.mkdirs() }

    companion object {
        /** Bump when text-layer extraction or page layout changes, so cached layer pages are redone. */
        const val LAYER_VERSION = 3
    }
}
