package app.quinten.tts

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import app.quinten.analysis.Sentences
import app.quinten.model.BlockKind
import app.quinten.model.TextBlock
import app.quinten.render.Pos
import app.quinten.render.ReaderController
import java.util.concurrent.Executors

/**
 * Reads the book aloud sentence by sentence with a [SpeechBackend] (the phone's voices or
 * Kokoro), reporting the sentence being spoken so the reader can highlight it and turn pages.
 */
class TtsController(private val context: Context, private val reader: ReaderController, backend: SpeechBackend = SystemSpeech(context)) {
    enum class State { IDLE, PLAYING, PAUSED, UNAVAILABLE }

    private data class Utterance(val id: String, val pos: Pos, val length: Int, val text: String)

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var tts: SpeechBackend = backend
    private var ready = false
    private var initialised = false
    private var filling = false
    private val queue = ArrayDeque<Utterance>()
    private val byId = HashMap<String, Utterance>()
    private var cursor: Pos? = null // next position to enqueue from
    private var current: Utterance? = null
    private var counter = 0
    private var generation = 0

    var state = State.IDLE
        private set(v) { field = v; onStateChanged?.invoke(v); publishState() }
    var onStateChanged: ((State) -> Unit)? = null
    var onSpeaking: ((Pos, Int) -> Unit)? = null
    var rate = 1.0f
        set(v) { field = v; tts.rate = v }
    var pitch = 1.0f
        set(v) { field = v; tts.pitch = v }
    val hasPitch get() = tts.hasPitch

    /** Switches between the phone's voices and Kokoro; reading continues where it was. */
    fun setBackend(b: SpeechBackend) {
        val playing = state == State.PLAYING
        val at = current?.pos ?: cursor
        if (playing) pause()
        tts.shutdown()
        tts = b; ready = false; initialised = false
        b.rate = rate; b.pitch = pitch
        if (state == State.UNAVAILABLE) state = State.IDLE
        if (playing && at != null) play(at)
    }
    var skipFootnotes = true

    // ---- sleep timer: reading pauses after the chosen time ----
    /** Chosen timer in minutes (0 = off) and when it ends (elapsed-realtime ms). */
    val sleepMinutes = kotlinx.coroutines.flow.MutableStateFlow(0)
    @Volatile var sleepEndsAt = 0L
        private set
    private val sleepRunnable = Runnable { pause(); sleepMinutes.value = 0; sleepEndsAt = 0L }

    fun setSleepTimer(minutes: Int) {
        main.removeCallbacks(sleepRunnable)
        sleepMinutes.value = minutes.coerceAtLeast(0)
        sleepEndsAt = if (minutes > 0) android.os.SystemClock.elapsedRealtime() + minutes * 60_000L else 0L
        if (minutes > 0) main.postDelayed(sleepRunnable, minutes * 60_000L)
    }

    // ---- playing nicely with the phone: audio focus, unplugged headphones, media buttons ----
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
    private var pausedByFocusLoss = false
    private val focusRequest = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_MEDIA).setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setOnAudioFocusChangeListener { change ->
            main.post {
                // Some speech engines take audio focus themselves while talking. Pausing on every
                // short focus loss then restarted the sentence over and over (garbled speech), so only
                // a phone call or another app taking over for good pauses reading; ducking is left to
                // the system.
                val inCall = audio.mode == android.media.AudioManager.MODE_IN_CALL ||
                    audio.mode == android.media.AudioManager.MODE_IN_COMMUNICATION || audio.mode == android.media.AudioManager.MODE_RINGTONE
                when (change) {
                    android.media.AudioManager.AUDIOFOCUS_LOSS -> { pause(); pausedByFocusLoss = false }
                    android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ->
                        if (inCall && state == State.PLAYING) { pause(); pausedByFocusLoss = true }
                    android.media.AudioManager.AUDIOFOCUS_GAIN -> if (pausedByFocusLoss) { pausedByFocusLoss = false; resume() }
                }
            }
        }.build()
    private val noisy = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) { if (i?.action == android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY) pause() }
    }
    private var noisyRegistered = false

    /** Lock-screen, headset and Bluetooth play/pause buttons. */
    val session: android.media.session.MediaSession = android.media.session.MediaSession(context, "QuintenReadAloud").apply {
        setCallback(object : android.media.session.MediaSession.Callback() {
            override fun onPlay() = resume()
            override fun onPause() = pause()
            override fun onStop() = stop()
        })
    }

    private fun publishState() {
        val ps = when (state) {
            State.PLAYING -> android.media.session.PlaybackState.STATE_PLAYING
            State.PAUSED -> android.media.session.PlaybackState.STATE_PAUSED
            else -> android.media.session.PlaybackState.STATE_STOPPED
        }
        session.setPlaybackState(android.media.session.PlaybackState.Builder()
            .setActions(android.media.session.PlaybackState.ACTION_PLAY or android.media.session.PlaybackState.ACTION_PAUSE or
                android.media.session.PlaybackState.ACTION_PLAY_PAUSE or android.media.session.PlaybackState.ACTION_STOP)
            .setState(ps, android.media.session.PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f).build())
        session.isActive = state == State.PLAYING || state == State.PAUSED
    }

    private fun onAudioStart() {
        audio.requestAudioFocus(focusRequest)
        if (!noisyRegistered) {
            context.registerReceiver(noisy, android.content.IntentFilter(android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY))
            noisyRegistered = true
        }
    }

    private fun onAudioEnd(abandonFocus: Boolean) {
        if (noisyRegistered) { runCatching { context.unregisterReceiver(noisy) }; noisyRegistered = false }
        if (abandonFocus) audio.abandonAudioFocusRequest(focusRequest)
    }

    fun init(onReady: (Boolean) -> Unit = {}) {
        if (initialised) { onReady(ready); return }
        initialised = true
        val b = tts
        b.rate = rate; b.pitch = pitch
        b.listener = object : SpeechBackend.Listener {
            override fun onStart(id: String) {
                main.post {
                    val u = byId[id] ?: return@post
                    current = u
                    onSpeaking?.invoke(u.pos, u.length)
                }
            }
            override fun onDone(id: String) {
                main.post {
                    byId.remove(id)
                    // Top up before the queue runs dry, so a slow voice (Kokoro) has the next
                    // sentences ready instead of pausing every few sentences.
                    if (state == State.PLAYING && byId.size <= 3) fill(generation)
                }
            }
        }
        b.init { ok ->
            main.post {
                if (b !== tts) return@post
                ready = ok
                if (!ok) state = State.UNAVAILABLE
                onReady(ok)
            }
        }
    }

    fun setLanguage(tag: String?) = tts.setLanguage(tag)

    fun play(from: Pos) {
        init { ok ->
            if (!ok) return@init
            stopSpeech()
            generation++
            cursor = from
            // The notification and media buttons control the book that is playing (several windows).
            active = this
            onAudioStart()
            state = State.PLAYING
            TtsService.start(context)
            fill(generation)
        }
    }

    /** Starts the current sentence again, e.g. with a newly chosen voice. */
    fun restart() {
        if (state != State.PLAYING) return
        val at = current?.pos ?: cursor ?: return
        play(at)
    }

    fun pause() {
        if (state != State.PLAYING) return
        val resumeAt = current?.pos ?: cursor
        stopSpeech()
        cursor = resumeAt
        onAudioEnd(abandonFocus = false)
        state = State.PAUSED
    }

    fun resume() {
        val c = cursor ?: return
        if (state == State.PAUSED) play(c)
    }

    fun toggle(from: Pos) = when (state) {
        State.PLAYING -> pause()
        State.PAUSED -> resume()
        else -> play(from)
    }

    fun stop() {
        stopSpeech()
        state = State.IDLE
        cursor = null
        current = null
        onSpeaking?.invoke(Pos(-1, -1, 0), 0)
        onAudioEnd(abandonFocus = true)
        // Another window's book may be the one playing: only its own controller stops the service.
        if (active === this) { active = null; TtsService.stop(context) }
    }

    private fun stopSpeech() {
        generation++
        tts.stop()
        queue.clear(); byId.clear()
        filling = false
    }

    /** Enqueues the next batch of sentences (prepared off the main thread). */
    private fun fill(sess: Int) {
        if (filling) return
        val from = cursor ?: run { if (byId.isEmpty()) stop(); return }
        filling = true
        worker.execute {
            val batch = collect(from, 8)
            main.post {
                if (sess != generation || state != State.PLAYING) return@post
                filling = false
                if (batch.first.isEmpty()) { if (byId.isEmpty()) stop(); cursor = null; return@post }
                cursor = batch.second
                for (u in batch.first) {
                    byId[u.id] = u
                    tts.speak(u.id, u.text)
                }
            }
        }
    }

    /** Collects up to [max] sentences starting at [from]; returns them and the next position. */
    private fun collect(from: Pos, max: Int): Pair<List<Utterance>, Pos?> {
        val out = ArrayList<Utterance>()
        var c = from.chapter; var b = from.block; var o = from.offset
        while (out.size < max) {
            if (c >= reader.chapterCount) return out to null
            val ch = try { reader.loadChapterBlocking(c) } catch (_: Throwable) { return out to null }
            if (b >= ch.blocks.size) { c++; b = 0; o = 0; continue }
            val block = ch.blocks[b] as? TextBlock
            if (block == null || (skipFootnotes && block.kind == BlockKind.FOOTNOTE) || block.removalId != null) { b++; o = 0; continue }
            for (r in Sentences.split(block.text)) {
                if (r.last < o) continue
                val start = maxOf(r.first, o)
                val text = block.text.substring(start, r.last + 1)
                if (text.isBlank()) continue
                out += Utterance("u${counter++}", Pos(c, b, start), r.last + 1 - start, text)
                if (out.size >= max) {
                    val next = r.last + 1
                    return out to (if (next >= block.text.length) Pos(c, b + 1, 0) else Pos(c, b, next))
                }
            }
            b++; o = 0
        }
        return out to Pos(c, b, o)
    }

    fun shutdown() {
        main.removeCallbacks(sleepRunnable)
        stop()
        session.release()
        tts.shutdown(); ready = false; initialised = false
        worker.shutdownNow()
    }

    companion object {
        @Volatile var active: TtsController? = null
    }
}
