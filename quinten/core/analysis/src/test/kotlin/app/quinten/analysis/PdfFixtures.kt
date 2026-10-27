package app.quinten.analysis

import app.quinten.analysis.page.Box
import app.quinten.analysis.page.Glyph
import app.quinten.analysis.page.PageLayout
import app.quinten.analysis.page.PageText
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import java.io.File

/** Desktop-PDFBox glyph extraction mirroring the Android adapter (test only). */
object PdfFixtures {
    fun file(name: String) = File(requireNotNull(javaClass.classLoader.getResource("fixtures/$name")).toURI())

    fun pages(name: String): List<PageText> = pages(file(name))

    fun pages(f: File, range: IntRange? = null): List<PageText> = PDDocument.load(f).use { doc ->
        (range ?: (0 until doc.numberOfPages)).map { i ->
            val glyphs = ArrayList<Glyph>()
            var invisible = 0
            val stripper = object : PDFTextStripper() {
                override fun processTextPosition(tp: TextPosition) {
                    if (graphicsState.textState.renderingMode == org.apache.pdfbox.pdmodel.graphics.state.RenderingMode.NEITHER) invisible++
                    val fontName = tp.font?.name?.lowercase() ?: ""
                    val desc = tp.font?.fontDescriptor
                    val bold = fontName.contains("bold") || fontName.contains("black") || (desc?.fontWeight ?: 0f) >= 600f || desc?.isForceBold == true
                    val italic = fontName.contains("italic") || fontName.contains("oblique") || desc?.isItalic == true
                    val h = tp.heightDir.coerceAtLeast(tp.fontSizeInPt * 0.6f)
                    glyphs += Glyph(
                        tp.unicode ?: return, Box(tp.xDirAdj, tp.yDirAdj - h, tp.xDirAdj + tp.widthDirAdj, tp.yDirAdj),
                        tp.yDirAdj, tp.fontSizeInPt, bold, italic, tp.widthOfSpace.takeIf { it > 0 } ?: (tp.fontSizeInPt * 0.25f),
                    )
                }
            }
            stripper.startPage = i + 1
            stripper.endPage = i + 1
            stripper.getText(doc)
            val page = doc.getPage(i)
            val t = PageLayout.build(i, page.mediaBox.width, page.mediaBox.height, glyphs)
            // Same scan detection as PdfPageSource: the scanner's OCR is invisible text.
            if (glyphs.isNotEmpty() && invisible > glyphs.size / 2) PageText(t.pageIndex, t.width, t.height, t.lines, t.images, t.columnOf, t.columns, t.ocr, scanned = true) else t
        }
    }

    fun expected(name: String): List<String> = file(name).readLines().filter { it.isNotBlank() }
}
