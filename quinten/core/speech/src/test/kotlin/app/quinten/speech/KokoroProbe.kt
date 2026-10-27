package app.quinten.speech

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Synthesises each line of KOKORO_TEXT with the model in KOKORO_DIR (model_quantized.onnx,
 * voices/) into KOKORO_OUT/<n>.wav, and writes phonemes, token ids and timings to KOKORO_OUT/log.tsv.
 * Used to check speech against a reference run and with a speech recogniser.
 */
class KokoroProbe {
    @Test fun probe() {
        val dir = System.getenv("KOKORO_DIR"); assumeTrue(dir != null)
        val out = File(System.getenv("KOKORO_OUT")).apply { mkdirs() }
        val voice = System.getenv("KOKORO_VOICE") ?: "af_heart"
        Kokoro(File(dir, "model_quantized.onnx"), File(dir, "voices"), threads = (System.getenv("KOKORO_THREADS") ?: "2").toInt()).use { k ->
            val log = StringBuilder()
            for ((n, line) in File(System.getenv("KOKORO_TEXT")).readLines().filter { it.isNotBlank() }.withIndex()) {
                val ph = k.phonemes(line, voice)
                val ids = Tokenizer.encode(ph)
                val t0 = System.nanoTime()
                val audio = k.speak(line, voice)
                val ms = (System.nanoTime() - t0) / 1_000_000
                wav(File(out, "$n.wav"), audio)
                log.append(n).append('\t').append(ms).append('\t').append(audio.size * 1000L / Kokoro.SAMPLE_RATE).append('\t')
                    .append(ph).append('\t').append(ids.joinToString(",")).append('\t').append(line).append('\n')
            }
            File(out, "log.tsv").writeText(log.toString())
        }
    }

    private fun wav(f: File, pcm: FloatArray) = DataOutputStream(FileOutputStream(f)).use { o ->
        fun le32(v: Int) { o.write(v and 255); o.write((v shr 8) and 255); o.write((v shr 16) and 255); o.write((v shr 24) and 255) }
        fun le16(v: Int) { o.write(v and 255); o.write((v shr 8) and 255) }
        o.writeBytes("RIFF"); le32(36 + pcm.size * 2); o.writeBytes("WAVEfmt "); le32(16); le16(1); le16(1)
        le32(Kokoro.SAMPLE_RATE); le32(Kokoro.SAMPLE_RATE * 2); le16(2); le16(16); o.writeBytes("data"); le32(pcm.size * 2)
        for (s in pcm) le16((s.coerceIn(-1f, 1f) * 32767).toInt())
    }
}
