package app.quinten.tts

import android.content.Context
import android.net.Uri
import app.quinten.speech.Kokoro
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * The Kokoro voice model on the phone: a one-time download (≈106 MB: the model and the English
 * voices) from a fixed revision of the ONNX export on Hugging Face, checked against its SHA-256,
 * or an import from a zip for phones without internet. Afterwards read-aloud works offline.
 */
object KokoroModels {
    private const val REVISION = "1939ad2a8e416c0acfeecc08a694d14ef25f2231"
    private const val BASE = "https://huggingface.co/onnx-community/Kokoro-82M-v1.0-ONNX/resolve/$REVISION"
    private const val MODEL = "model_quantized.onnx"
    private const val MODEL_SHA256 = "fbae9257e1e05ffc727e951ef9b9c98418e6d79f1c9b6b13bd59f5c9028a1478"
    private const val MODEL_SIZE = 92_361_116L
    private const val VOICE_SIZE = 522_240L

    class Voice(val id: String, val label: String)

    /** English voices (American "a", British "b"; female "f", male "m"). */
    val VOICES = listOf(
        "af_heart" to "Heart", "af_bella" to "Bella", "af_nicole" to "Nicole", "af_sarah" to "Sarah", "af_sky" to "Sky",
        "af_alloy" to "Alloy", "af_aoede" to "Aoede", "af_jessica" to "Jessica", "af_kore" to "Kore", "af_nova" to "Nova", "af_river" to "River",
        "am_michael" to "Michael", "am_fenrir" to "Fenrir", "am_puck" to "Puck", "am_adam" to "Adam", "am_echo" to "Echo",
        "am_eric" to "Eric", "am_liam" to "Liam", "am_onyx" to "Onyx",
        "bf_emma" to "Emma", "bf_isabella" to "Isabella", "bf_alice" to "Alice", "bf_lily" to "Lily",
        "bm_george" to "George", "bm_fable" to "Fable", "bm_daniel" to "Daniel", "bm_lewis" to "Lewis",
    ).map { (id, name) ->
        val accent = if (id[0] == 'b') "British" else "American"
        val who = if (id[1] == 'f') "woman" else "man"
        Voice(id, "$name ($accent $who)")
    }
    const val DEFAULT_VOICE = "af_heart"

    sealed class State {
        object NotInstalled : State()
        class Downloading(val done: Long, val total: Long) : State()
        object Ready : State()
        class Failed(val message: String) : State()
    }

    val state = MutableStateFlow<State>(State.NotInstalled)

    /** Downloads keep going when the book or the panel is closed. */
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    fun startDownload(context: Context, then: () -> Unit = {}) {
        if (state.value is State.Downloading) return
        val app = context.applicationContext
        scope.launch { download(app); if (isInstalled(app)) kotlinx.coroutines.withContext(Dispatchers.Main) { then() } }
    }

    fun dir(context: Context) = File(context.filesDir, "kokoro")
    private fun modelFile(context: Context) = File(dir(context), MODEL)
    private fun voicesDir(context: Context) = File(dir(context), "voices")

    fun isInstalled(context: Context): Boolean =
        modelFile(context).length() > 10_000_000 && (voicesDir(context).listFiles()?.any { it.name.endsWith(".bin") } == true)

    fun installedVoices(context: Context): List<Voice> {
        val have = voicesDir(context).listFiles()?.map { it.name.removeSuffix(".bin") }?.toSet() ?: emptySet()
        return VOICES.filter { it.id in have }
    }

    fun refresh(context: Context) {
        if (state.value !is State.Downloading) state.value = if (isInstalled(context)) State.Ready else State.NotInstalled
    }

    // ---- download ----

    suspend fun download(context: Context) = withContext(Dispatchers.IO) {
        try {
            val voices = VOICES.map { it.id }
            val total = MODEL_SIZE + VOICE_SIZE * voices.size
            var done = 0L
            state.value = State.Downloading(0, total)
            val d = dir(context).apply { mkdirs() }
            val model = modelFile(context)
            if (model.length() != MODEL_SIZE || sha256(model) != MODEL_SHA256) {
                val part = File(d, "$MODEL.part")
                fetch("$BASE/onnx/$MODEL", part) { n -> state.value = State.Downloading(done + n, total) }
                if (sha256(part) != MODEL_SHA256) { part.delete(); error("The downloaded model is damaged (checksum mismatch). Try again.") }
                if (!part.renameTo(model)) error("Could not save the model.")
            }
            done += MODEL_SIZE
            val vd = voicesDir(context).apply { mkdirs() }
            for (v in voices) {
                val f = File(vd, "$v.bin")
                if (f.length() != VOICE_SIZE) {
                    val part = File(vd, "$v.part")
                    fetch("$BASE/voices/$v.bin", part) { n -> state.value = State.Downloading(done + n, total) }
                    if (part.length() != VOICE_SIZE || !part.renameTo(f)) { part.delete(); error("Voice $v did not download completely.") }
                }
                done += VOICE_SIZE
                state.value = State.Downloading(done, total)
            }
            closeEngine()
            state.value = State.Ready
        } catch (e: Exception) {
            state.value = State.Failed(e.message ?: "Download failed. Check the internet connection and try again.")
        }
    }

    private fun fetch(url: String, to: File, progress: (Long) -> Unit) {
        var u = URL(url)
        var conn: HttpURLConnection
        var redirects = 0
        while (true) {
            conn = (u.openConnection() as HttpURLConnection).apply { connectTimeout = 20_000; readTimeout = 30_000; instanceFollowRedirects = false }
            val code = conn.responseCode
            if (code in 300..399 && redirects++ < 5) { u = URL(u, conn.getHeaderField("Location")); conn.disconnect(); continue }
            if (code != 200) { conn.disconnect(); error("Download failed (HTTP $code).") }
            break
        }
        conn.inputStream.use { input ->
            to.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var n = 0L
                var last = 0L
                while (true) {
                    val r = input.read(buf); if (r < 0) break
                    out.write(buf, 0, r); n += r
                    if (n - last > 512 * 1024) { progress(n); last = n }
                }
                progress(n)
            }
        }
        conn.disconnect()
    }

    private fun sha256(f: File): String {
        if (!f.isFile) return ""
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { s -> val b = ByteArray(1 shl 16); while (true) { val r = s.read(b); if (r < 0) break; md.update(b, 0, r) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    // ---- import (no internet) ----

    /**
     * Installs from a zip holding an .onnx Kokoro model and voice .bin files (any folders), for
     * example one made on a computer from the Hugging Face files. Returns an error message or null.
     */
    suspend fun importZip(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        val tmp = File(dir(context).parentFile, "kokoro-import").apply { deleteRecursively(); mkdirs() }
        try {
            var model = false; var voices = 0
            val input = context.contentResolver.openInputStream(uri) ?: return@withContext "Could not open the file."
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    val name = e.name.substringAfterLast('/')
                    val target = when {
                        e.isDirectory -> null
                        name.endsWith(".onnx") && !model -> File(tmp, MODEL).also { model = true }
                        name.endsWith(".bin") && name.matches(Regex("[a-z]{2}_[a-z]+\\.bin")) -> File(tmp, "voices/$name").also { voices++ }
                        else -> null
                    }
                    if (target != null) { target.parentFile?.mkdirs(); target.outputStream().use { zip.copyTo(it) } }
                }
            }
            if (!model) return@withContext "No Kokoro model (.onnx) in the zip."
            if (voices == 0) return@withContext "No voices (.bin files) in the zip."
            closeEngine()
            dir(context).deleteRecursively()
            if (!tmp.renameTo(dir(context))) return@withContext "Could not install the model."
            state.value = State.Ready
            null
        } catch (e: Exception) {
            "Import failed: ${e.message}"
        } finally { tmp.deleteRecursively() }
    }

    fun delete(context: Context) {
        closeEngine()
        dir(context).deleteRecursively()
        state.value = State.NotInstalled
    }

    // ---- the engine, shared by all books ----

    @Volatile private var engine: Kokoro? = null

    fun engine(context: Context): Kokoro = engine ?: synchronized(this) {
        engine ?: run {
            val cores = Runtime.getRuntime().availableProcessors()
            Kokoro(modelFile(context), voicesDir(context), threads = (cores / 2).coerceIn(2, 4)).also { engine = it }
        }
    }

    private fun closeEngine() = synchronized(this) { engine?.close(); engine = null }
}
