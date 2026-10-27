package app.quinten.analysis

import app.quinten.analysis.text.OcrCorrector
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class CorrectorProbe {
    @Test fun probe() {
        val dir = System.getenv("PROBE_TEXTS"); assumeTrue(dir != null)
        val c = OcrCorrector()
        var lines = 0; val changes = ArrayList<String>()
        for (f in File(dir!!).listFiles()!!.filter { it.name.endsWith(".txt") }) for (l in f.readLines()) {
            lines++
            val x = c.splitJoined(c.correctLine(l)).first
            if (x != l) changes += "${f.name}: $l\n   -> $x"
        }
        File(System.getenv("PROBE_OUT")).writeText("lines $lines changed ${changes.size}\n" + changes.take(300).joinToString("\n"))
    }
}
