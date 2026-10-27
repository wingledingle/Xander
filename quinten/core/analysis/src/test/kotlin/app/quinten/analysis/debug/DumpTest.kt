package app.quinten.analysis.debug

import app.quinten.analysis.PdfFixtures
import org.junit.Assume.assumeTrue
import org.junit.Test

class DumpTest {
    @Test fun dump() {
        val spec = System.getenv("DUMP_PDF") ?: return assumeTrue(false)
        val (name, page) = spec.split(":")
        val p = PdfFixtures.pages(name)[page.toInt()]
        println("columns=${p.columns}")
        p.lines.forEachIndexed { i, l -> println("$i c${p.columnOf[i]} ${l.box.x0.toInt()},${l.box.y0.toInt()}-${l.box.x1.toInt()},${l.box.y1.toInt()} fs=${l.fontSize} '${l.text}'") }
    }
}
