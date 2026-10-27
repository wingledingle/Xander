package app.quinten.formats

import app.quinten.model.BookFormat
import app.quinten.model.CleanOptions
import app.quinten.model.TextBlock
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Manual tool: PROBE_FILES=a.epub,b.txt PROBE_OUT=dir dumps each book's text (Clean mode on). */
class FlowDumpProbe {
    @Test fun dump() {
        val files = System.getenv("PROBE_FILES"); assumeTrue(files != null)
        val out = File(System.getenv("PROBE_OUT")).apply { mkdirs() }
        for (path in files!!.split(',')) {
            val f = File(path)
            val fmt = when (f.extension.lowercase()) { "epub" -> BookFormat.EPUB; "txt" -> BookFormat.TXT; "mobi" -> BookFormat.MOBI; "azw3" -> BookFormat.AZW3; "fb2" -> BookFormat.FB2; else -> continue }
            val sb = StringBuilder()
            try {
                BookOpener.open(f, fmt).use { d ->
                    for (c in 0 until d.chapterCount) {
                        val ch = d.loadChapter(c, CleanOptions.ON)
                        for (b in ch.blocks) if (b is TextBlock) sb.append(b.text).append('\n')
                        sb.append("removals: ").append(ch.removals.size).append('\n')
                    }
                }
            } catch (e: Throwable) { sb.append("ERROR ").append(e) }
            File(out, f.name + ".txt").writeText(sb.toString())
        }
    }
}
