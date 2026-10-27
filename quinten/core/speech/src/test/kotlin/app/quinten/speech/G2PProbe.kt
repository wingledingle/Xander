package app.quinten.speech

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Phonemes for each line of G2P_TEXT, written to G2P_OUT (line, tab, phonemes). */
class G2PProbe {
    @Test fun probe() {
        val text = System.getenv("G2P_TEXT"); assumeTrue(text != null)
        val g = G2P()
        File(System.getenv("G2P_OUT")).writeText(File(text!!).readLines().joinToString("\n") { "$it\t${g.phonemize(it)}" })
    }
}
