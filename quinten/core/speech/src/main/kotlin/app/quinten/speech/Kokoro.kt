package app.quinten.speech

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Kokoro-82M text to speech (https://huggingface.co/hexgrad/Kokoro-82M, Apache-2.0) run with ONNX
 * Runtime on the device: [model] is the ONNX export, voices are the 510×256 style tables (`.bin`).
 * Output is 24 kHz mono float PCM.
 */
class Kokoro(model: File, private val voicesDir: File, threads: Int = 2) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession = env.createSession(model.absolutePath, OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(threads)
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
    })
    private val g2pUs by lazy { G2P(Lexicon.us()) }
    private val g2pGb by lazy { G2P(Lexicon.gb()) }
    private val voices = HashMap<String, FloatArray>()

    /** Voice names available in [voicesDir] ("af_heart", "bm_george", ...). */
    fun voiceNames(): List<String> = voicesDir.listFiles()?.filter { it.name.endsWith(".bin") }?.map { it.name.removeSuffix(".bin") }?.sorted() ?: emptyList()

    private fun voice(name: String): FloatArray = synchronized(voices) {
        voices.getOrPut(name) {
            val bytes = File(voicesDir, "$name.bin").readBytes()
            val fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            FloatArray(fb.remaining()).also { fb.get(it) }
        }
    }

    /** British voices ("bf_", "bm_") read with British pronunciations, as in Kokoro. */
    fun phonemes(text: String, voice: String = "af_heart"): String = (if (voice.startsWith("b")) g2pGb else g2pUs).phonemize(text)

    /** Speech for [text]; long text is cut at pauses into pieces Kokoro can take (510 tokens). */
    fun speak(text: String, voice: String, speed: Float = 1f): FloatArray {
        val pieces = split(Tokenizer.encode(phonemes(text, voice)))
        if (pieces.isEmpty()) return FloatArray(0)
        val out = ArrayList<FloatArray>()
        for (p in pieces) out += synthesize(p, voice, speed)
        return concat(out)
    }

    /** One call of the model on token ids (without the boundary tokens). */
    fun synthesize(ids: IntArray, voice: String, speed: Float = 1f): FloatArray {
        if (ids.isEmpty()) return FloatArray(0)
        val table = voice(voice)
        val rows = table.size / STYLE
        // The style row is chosen by the number of tokens, as in Kokoro's reference code.
        val row = minOf(ids.size, rows - 1)
        // A copy: ONNX Runtime reads a heap buffer from the start of its array, not its offset.
        val style = FloatBuffer.wrap(table.copyOfRange(row * STYLE, row * STYLE + STYLE))
        val input = LongArray(ids.size + 2).also { a -> for (i in ids.indices) a[i + 1] = ids[i].toLong() }
        OnnxTensor.createTensor(env, LongBuffer.wrap(input), longArrayOf(1, input.size.toLong())).use { tIds ->
            OnnxTensor.createTensor(env, style, longArrayOf(1, STYLE.toLong())).use { tStyle ->
                OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(speed)), longArrayOf(1)).use { tSpeed ->
                    session.run(mapOf("input_ids" to tIds, "style" to tStyle, "speed" to tSpeed)).use { r ->
                        val t = r[0] as OnnxTensor
                        val fb = t.floatBuffer
                        return FloatArray(fb.remaining()).also { fb.get(it) }
                    }
                }
            }
        }
    }

    override fun close() { session.close() }

    companion object {
        const val SAMPLE_RATE = 24_000
        const val STYLE = 256
        const val MAX_TOKENS = 510

        /** Cuts token ids at the last pause (". , ; : ! ?" or a space) before the model's limit. */
        fun split(ids: IntArray, max: Int = MAX_TOKENS): List<IntArray> {
            val out = ArrayList<IntArray>()
            var start = 0
            while (start < ids.size) {
                if (ids.size - start <= max) { out += ids.copyOfRange(start, ids.size); break }
                var cut = -1
                for (i in start + max - 1 downTo start + max / 2) if (ids[i] in Tokenizer.pauses) { cut = i + 1; break }
                if (cut < 0) for (i in start + max - 1 downTo start + max / 2) if (ids[i] == Tokenizer.space) { cut = i + 1; break }
                if (cut < 0) cut = start + max
                out += ids.copyOfRange(start, cut)
                start = cut
            }
            return out.filter { it.isNotEmpty() }
        }

        fun concat(parts: List<FloatArray>): FloatArray {
            val out = FloatArray(parts.sumOf { it.size })
            var o = 0
            for (p in parts) { p.copyInto(out, o); o += p.size }
            return out
        }
    }
}

/** Kokoro's phoneme vocabulary: one id per symbol; symbols outside it are dropped. */
object Tokenizer {
    private val ids: Map<Int, Int> by lazy {
        Tokenizer::class.java.getResourceAsStream("/app/quinten/speech/kokoro_vocab.tsv")!!.bufferedReader().readLines()
            .filter { it.isNotBlank() }.associate { l -> l.substringBefore('\t').toInt() to l.substringAfter('\t').toInt() }
    }
    val space get() = ids.getValue(' '.code)
    val pauses: Set<Int> by lazy { ".,;:!?—…".mapNotNull { ids[it.code] }.toSet() }

    fun encode(phonemes: String): IntArray {
        val out = ArrayList<Int>(phonemes.length)
        var i = 0
        while (i < phonemes.length) {
            val cp = phonemes.codePointAt(i)
            ids[cp]?.let { out += it }
            i += Character.charCount(cp)
        }
        return out.toIntArray()
    }
}
