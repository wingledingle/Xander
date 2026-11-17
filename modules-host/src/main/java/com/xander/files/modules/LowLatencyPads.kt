package com.xander.files.modules

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Process
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Native one-shot sampler for finger drumming. WebView audio adds a large output buffer between a touch and the
 * sound; this plays pre-rendered pad hits through an AudioTrack in low-latency mode (API 26+), mixing on its own
 * urgent-audio thread in bursts of the device's native buffer size. Web modules upload each pad's sound once
 * ([load]) and then [trigger] it by id. The track stops after [IDLE_MS] of silence so it doesn't keep the audio
 * hardware awake, and restarts on the next hit.
 */
class LowLatencyPads(context: Context) : AutoCloseable {
    private val am = context.getSystemService(AudioManager::class.java)
    val sampleRate: Int = am?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 48000
    private val burst: Int = am?.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull()?.takeIf { it in 32..4096 } ?: 256

    private class Pad(val pcm: ShortArray) // interleaved stereo at sampleRate
    private class Voice(val pad: Pad, val gl: Float, val gr: Float) { var frame = 0 }

    private val pads = ConcurrentHashMap<String, Pad>()
    private val incoming = ConcurrentLinkedQueue<Voice>()
    private val lock = Object()
    @Volatile private var running = true
    @Volatile private var track: AudioTrack? = null
    private var thread: Thread? = null

    /** Output latency estimate in ms: the track's buffer plus one burst of mixing. */
    val latencyMs: Double get() = ((track?.bufferSizeInFrames ?: burst * 2) + burst) * 1000.0 / sampleRate

    /** [pcm] is interleaved 16-bit with [channels] (1 or 2) at [rate]; it is resampled to the device rate once. */
    fun load(id: String, rate: Int, channels: Int, pcm: ShortArray) {
        pads[id] = Pad(resampleStereo(pcm, channels.coerceIn(1, 2), rate, sampleRate))
    }

    fun unload(id: String) { pads.remove(id) }

    fun trigger(id: String, gain: Float, pan: Float) {
        val pad = pads[id] ?: return
        val a = ((pan.coerceIn(-1f, 1f) + 1) * PI / 4) // equal-power pan
        incoming.add(Voice(pad, gain.coerceIn(0f, 2f) * cos(a).toFloat() * 1.414f, gain.coerceIn(0f, 2f) * sin(a).toFloat() * 1.414f))
        ensureRunning()
        synchronized(lock) { lock.notifyAll() }
    }

    fun stopAll() { incoming.add(STOP) }

    @Synchronized private fun ensureRunning() {
        if (thread != null) return
        thread = Thread(::loop, "xander-pads").apply { start() }
    }

    private fun build(): AudioTrack {
        val min = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val b = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(min, burst * 2 * 2 * 2))
        if (Build.VERSION.SDK_INT >= 26) b.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        return b.build().also { t ->
            // Shrink to two bursts where the device allows it (the minimum the fast mixer accepts).
            if (Build.VERSION.SDK_INT >= 24) runCatching { t.setBufferSizeInFrames(burst * 2) }
        }
    }

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val active = ArrayList<Voice>()
        val mix = FloatArray(burst * 2)
        val out = ShortArray(burst * 2)
        var idleSince = System.currentTimeMillis()
        while (running) {
            while (true) {
                val v = incoming.poll() ?: break
                if (v === STOP) active.clear() else active.add(v)
                if (active.size > MAX_VOICES) active.removeAt(0)
            }
            if (active.isEmpty()) {
                if (System.currentTimeMillis() - idleSince > IDLE_MS) {
                    track?.let { it.pause(); it.flush() }
                    synchronized(lock) { if (incoming.isEmpty() && running) lock.wait() }
                    idleSince = System.currentTimeMillis()
                    continue
                }
            } else idleSince = System.currentTimeMillis()
            val t = track ?: build().also { track = it }
            if (t.playState != AudioTrack.PLAYSTATE_PLAYING) t.play()
            mix.fill(0f)
            val it = active.iterator()
            while (it.hasNext()) {
                val v = it.next()
                val pcm = v.pad.pcm
                var i = 0
                var s = v.frame * 2
                while (i < mix.size && s < pcm.size) {
                    mix[i] += pcm[s] * v.gl; mix[i + 1] += pcm[s + 1] * v.gr
                    i += 2; s += 2
                }
                v.frame = s / 2
                if (s >= pcm.size) it.remove()
            }
            for (i in mix.indices) out[i] = softClip(mix[i])
            t.write(out, 0, out.size)
        }
        track?.release()
        track = null
    }

    override fun close() {
        running = false
        synchronized(lock) { lock.notifyAll() }
        thread?.join(500)
        thread = null
        pads.clear()
    }

    companion object {
        private const val MAX_VOICES = 32
        private const val IDLE_MS = 15_000L
        private val STOP = Voice(Pad(ShortArray(0)), 0f, 0f)

        // Linear above ±0.75 full scale, tanh-like knee up to the rail, so stacked hits saturate instead of wrapping.
        fun softClip(x: Float): Short {
            val n = x / 32767f
            val a = kotlin.math.abs(n)
            val y = if (a <= 0.75f) n else kotlin.math.sign(n) * (0.75f + 0.25f * kotlin.math.tanh((a - 0.75f) / 0.25f))
            return (y * 32767f).toInt().coerceIn(-32768, 32767).toShort()
        }

        /** Interleaved [channels] at [from] Hz → interleaved stereo at [to] Hz, linear interpolation. */
        fun resampleStereo(pcm: ShortArray, channels: Int, from: Int, to: Int): ShortArray {
            val inFrames = pcm.size / channels
            if (inFrames == 0) return ShortArray(0)
            val outFrames = if (from == to) inFrames else ((inFrames.toLong() * to) / from).toInt()
            val out = ShortArray(outFrames * 2)
            val step = from.toDouble() / to
            for (f in 0 until outFrames) {
                val x = f * step
                val i = x.toInt().coerceAtMost(inFrames - 1)
                val j = (i + 1).coerceAtMost(inFrames - 1)
                val k = (x - i).toFloat()
                for (c in 0 until 2) {
                    val src = c.coerceAtMost(channels - 1)
                    val a = pcm[i * channels + src]; val b = pcm[j * channels + src]
                    out[f * 2 + c] = (a + (b - a) * k).toInt().toShort()
                }
            }
            return out
        }
    }
}
