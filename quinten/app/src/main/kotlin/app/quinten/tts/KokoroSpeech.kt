package app.quinten.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import app.quinten.speech.Kokoro
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Read-aloud with Kokoro voices: sentences are synthesised on a worker thread a few ahead of what
 * is playing, written to an [AudioTrack], and the track's playback position says when each
 * sentence starts and ends (for highlighting and page turns).
 */
class KokoroSpeech(private val context: Context, private val voice: () -> String) : SpeechBackend {
    override var listener: SpeechBackend.Listener? = null
    override var rate = 1f
    override var pitch = 1f
    override val hasPitch = false

    private class Item(val id: String, val text: String, val gen: Int)
    private class Clip(val id: String, val pcm: FloatArray, val gen: Int)
    private class Mark(val id: String, val start: Long, val end: Long) { var started = false }

    private val main = Handler(Looper.getMainLooper())
    private val toSynth = LinkedBlockingQueue<Item>()
    private val toPlay = LinkedBlockingQueue<Clip>(3) // synthesised ahead, waiting to play
    @Volatile private var gen = 0
    @Volatile private var alive = true
    private var track: AudioTrack? = null
    private var written = 0L // frames written to the current track
    private val marks = ArrayList<Mark>()
    private var synthThread: Thread? = null
    private var playThread: Thread? = null

    override fun init(onReady: (Boolean) -> Unit) {
        if (!KokoroModels.isInstalled(context)) { onReady(false); return }
        if (synthThread != null) { onReady(true); return }
        Thread {
            // Loading the model takes a second or two: done once, off the main thread.
            val ok = runCatching { KokoroModels.engine(context) }.isSuccess
            if (ok) startThreads()
            main.post { onReady(ok) }
        }.start()
    }

    private fun startThreads() {
        synthThread = Thread({
            while (alive) {
                val item = try { toSynth.poll(500, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { null } ?: continue
                if (item.gen != gen) continue
                val pcm = try { KokoroModels.engine(context).speak(item.text, voice(), rate.coerceIn(0.5f, 2f)) } catch (_: Throwable) { FloatArray(0) }
                while (alive && item.gen == gen) {
                    if (toPlay.offer(Clip(item.id, pcm, item.gen), 200, TimeUnit.MILLISECONDS)) break
                }
            }
        }, "kokoro-synth").apply { priority = Thread.NORM_PRIORITY + 1; start() }
        playThread = Thread({
            while (alive) {
                val clip = try { toPlay.poll(500, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { null } ?: continue
                if (clip.gen != gen) continue
                play(clip)
            }
        }, "kokoro-play").apply { start() }
    }

    private fun ensureTrack(): AudioTrack = synchronized(this) {
        track ?: AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(Kokoro.SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(AudioTrack.getMinBufferSize(Kokoro.SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT), Kokoro.SAMPLE_RATE / 2 * 4))
            .build().also { t -> track = t; written = 0; t.play() }
    }

    private fun play(clip: Clip) {
        val t = ensureTrack()
        val mark = synchronized(this) { Mark(clip.id, written, written + clip.pcm.size).also { marks += it; written += clip.pcm.size } }
        startMonitor()
        if (clip.pcm.isEmpty()) return
        var off = 0
        while (off < clip.pcm.size && clip.gen == gen && alive) {
            // stop() may release the track meanwhile.
            val n = try { t.write(clip.pcm, off, minOf(4800, clip.pcm.size - off), AudioTrack.WRITE_BLOCKING) } catch (_: IllegalStateException) { -1 }
            if (n <= 0) break
            off += n
        }
        if (clip.gen != gen) synchronized(this) { marks.remove(mark) }
    }

    // Polls the playback position and reports sentence starts and ends on the main thread.
    private var monitoring = false
    private val monitor = object : Runnable {
        override fun run() {
            val head: Long
            val fire = ArrayList<Pair<Boolean, String>>()
            synchronized(this@KokoroSpeech) {
                val t = track
                if (t == null) { monitoring = false; return }
                head = t.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                val it = marks.iterator()
                while (it.hasNext()) {
                    val m = it.next()
                    if (!m.started && head >= m.start) { m.started = true; fire += true to m.id }
                    if (m.started && head >= m.end) { fire += false to m.id; it.remove() } else break
                }
            }
            for ((start, id) in fire) if (start) listener?.onStart(id) else listener?.onDone(id)
            // Nothing left to report: polling resumes with the next sentence.
            if (synchronized(this@KokoroSpeech) { marks.isEmpty() }) { monitoring = false; return }
            main.postDelayed(this, 40)
        }
    }

    private fun startMonitor() = main.post {
        if (!monitoring) { monitoring = true; main.post(monitor) }
    }

    override fun speak(id: String, text: String) { toSynth.add(Item(id, text, gen)) }

    override fun stop() {
        gen++
        toSynth.clear(); toPlay.clear()
        synchronized(this) {
            marks.clear()
            track?.let { runCatching { it.pause(); it.flush(); it.release() } }
            track = null
            written = 0
        }
        main.removeCallbacks(monitor); monitoring = false
    }

    override fun shutdown() {
        stop()
        alive = false
        synthThread?.interrupt(); playThread?.interrupt()
        synthThread = null; playThread = null
    }
}
