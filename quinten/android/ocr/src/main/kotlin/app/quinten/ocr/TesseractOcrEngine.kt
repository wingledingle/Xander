package app.quinten.ocr

import android.content.Context
import android.graphics.Bitmap
import app.quinten.analysis.page.Box
import app.quinten.analysis.page.PageLayout
import app.quinten.analysis.page.PageText
import app.quinten.analysis.text.OcrLines
import app.quinten.analysis.page.TextLine
import app.quinten.analysis.paged.OcrEngine
import app.quinten.model.ImageData
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File

/**
 * Offline OCR with Tesseract (Tesseract4Android, Apache-2.0). English data ships with the app;
 * more `.traineddata` files can be imported into [dataDir]/tessdata by the user.
 */
class TesseractOcrEngine(private val context: Context, languages: String = "eng") : OcrEngine {
    private val dataDir = File(context.filesDir, "ocr")
    private var api: TessBaseAPI? = null
    private var initFailed = false
    @Volatile override var languages: String = languages
        set(value) { if (value != field) { field = value; synchronized(this) { api?.recycle(); api = null; initFailed = false } } }

    // Checked several times per page: remember the answer instead of listing files each time.
    @Volatile private var hasData: Boolean? = null
    override val isAvailable: Boolean get() = !initFailed && (hasData ?: installedLanguages().isNotEmpty().also { hasData = it })

    /** Languages with a traineddata file available offline. */
    fun installedLanguages(): List<String> {
        ensureBundledData()
        return File(dataDir, "tessdata").listFiles()?.filter { it.name.endsWith(".traineddata") }?.map { it.name.removeSuffix(".traineddata") }?.sorted() ?: emptyList()
    }

    /** Copies a user-provided traineddata file (e.g. from storage) into the OCR data folder. */
    fun importLanguage(name: String, input: java.io.InputStream) {
        val dir = File(dataDir, "tessdata").apply { mkdirs() }
        val target = File(dir, if (name.endsWith(".traineddata")) name else "$name.traineddata")
        target.outputStream().use { input.copyTo(it) }
        hasData = null
    }

    @Synchronized private fun ensureBundledData() {
        val dir = File(dataDir, "tessdata")
        if (!dir.isDirectory) dir.mkdirs()
        for (asset in context.assets.list("tessdata") ?: emptyArray()) {
            val f = File(dir, asset)
            if (!f.exists() || f.length() == 0L) {
                val tmp = File.createTempFile("$asset-", ".tmp", dir)
                try {
                    context.assets.open("tessdata/$asset").use { i -> tmp.outputStream().use { i.copyTo(it) } }
                    if (!tmp.renameTo(f)) tmp.delete()
                } catch (e: Exception) { tmp.delete() }
            }
        }
    }

    private fun api(): TessBaseAPI? {
        api?.let { return it }
        if (initFailed) return null
        ensureBundledData()
        val installed = installedLanguages()
        val langs = languages.split('+').filter { it in installed }.ifEmpty { installed.take(1) }.joinToString("+")
        // The native library can fail to load on some devices: OCR is then unavailable, never a crash.
        val a = try { TessBaseAPI() } catch (_: Throwable) { initFailed = true; return null }
        val ok = try { a.init(dataDir.absolutePath, langs, TessBaseAPI.OEM_LSTM_ONLY) } catch (_: Throwable) { false }
        if (!ok) { runCatching { a.recycle() }; initFailed = true; return null }
        a.pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO
        api = a
        return a
    }

    override fun recognize(image: ImageData, pageIndex: Int, pageWidth: Float, pageHeight: Float): PageText? = synchronized(this) {
        val bmp = when (image) {
            is ImageData.Platform -> image.image as? Bitmap
            is ImageData.Raster -> Bitmap.createBitmap(image.argb, image.width, image.height, Bitmap.Config.ARGB_8888)
            is ImageData.Encoded -> android.graphics.BitmapFactory.decodeByteArray(image.bytes, 0, image.bytes.size)
        } ?: return null
        val ownBitmap = image !is ImageData.Platform
        val a = api() ?: run { if (ownBitmap) bmp.recycle(); return null }
        a.setImage(bmp)
        a.getUTF8Text() // runs recognition
        val sx = pageWidth / bmp.width
        val sy = pageHeight / bmp.height
        val lines = ArrayList<TextLine>()
        val it = a.resultIterator ?: run { a.clear(); if (ownBitmap) bmp.recycle(); return null }
        try {
            it.begin()
            // Word by word, so specks and tall scraps can be dropped and the type size measured per word.
            val words = ArrayList<OcrLines.Word>()
            fun flush() { OcrLines.line(words)?.let { l -> lines += l }; words.clear() }
            do {
                if (it.isAtBeginningOf(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE)) flush()
                val text = it.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_WORD)?.trim() ?: continue
                if (text.isEmpty()) continue
                val r = it.getBoundingRect(TessBaseAPI.PageIteratorLevel.RIL_WORD)
                words += OcrLines.Word(text, it.confidence(TessBaseAPI.PageIteratorLevel.RIL_WORD), Box(r.left * sx, r.top * sy, r.right * sx, r.bottom * sy))
            } while (it.next(TessBaseAPI.PageIteratorLevel.RIL_WORD))
            flush()
        } finally {
            it.delete()
            a.clear()
        }
        if (ownBitmap) bmp.recycle()
        val laid = PageLayout.fromLines(pageIndex, pageWidth, pageHeight, lines)
        PageText(pageIndex, pageWidth, pageHeight, laid.lines, emptyList(), laid.columnOf, laid.columns, ocr = true)
    }

    fun close() = synchronized(this) { api?.recycle(); api = null }

}
