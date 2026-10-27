package app.quinten.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** Something that speaks queued sentences and reports when each one starts and ends. */
interface SpeechBackend {
    interface Listener {
        fun onStart(id: String)
        fun onDone(id: String)
    }

    var listener: Listener?
    var rate: Float
    var pitch: Float
    /** Whether [pitch] does anything (Kokoro voices have a fixed pitch). */
    val hasPitch: Boolean

    fun init(onReady: (Boolean) -> Unit)
    /** Adds a sentence to the end of the queue. */
    fun speak(id: String, text: String)
    /** Stops at once and empties the queue. */
    fun stop()
    fun setLanguage(tag: String?) {}
    fun shutdown()
}

/** The phone's own text-to-speech engine and voices. */
class SystemSpeech(private val context: Context) : SpeechBackend {
    private var tts: TextToSpeech? = null
    private var ready = false
    override var listener: SpeechBackend.Listener? = null
    override var rate = 1f
        set(v) { field = v; tts?.setSpeechRate(v) }
    override var pitch = 1f
        set(v) { field = v; tts?.setPitch(v) }
    override val hasPitch = true

    override fun init(onReady: (Boolean) -> Unit) {
        if (tts != null) { onReady(ready); return }
        tts = TextToSpeech(context) { status ->
            ready = status == TextToSpeech.SUCCESS
            tts?.setSpeechRate(rate); tts?.setPitch(pitch)
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String) { listener?.onStart(utteranceId) }
                override fun onDone(utteranceId: String) { listener?.onDone(utteranceId) }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String) { listener?.onDone(utteranceId) }
            })
            onReady(ready)
        }
    }

    override fun speak(id: String, text: String) { tts?.speak(text, TextToSpeech.QUEUE_ADD, Bundle(), id) }
    override fun stop() { tts?.stop() }

    override fun setLanguage(tag: String?) {
        val loc = tag?.let { Locale.forLanguageTag(it) } ?: return
        tts?.let { t -> if (t.isLanguageAvailable(loc) >= TextToSpeech.LANG_AVAILABLE) t.language = loc }
    }

    override fun shutdown() { tts?.shutdown(); tts = null; ready = false }
}
