package com.xander.files.modules

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes the audio track of any audio or video file the phone can play (MP4, MKV, WebM, 3GP, MP3, FLAC…) to a
 * 16-bit WAV, using the platform codecs. Web Audio's decodeAudioData only knows a few containers, so web modules
 * ask the host for this instead. A window ([startMs], [maxMs]) keeps memory bounded for long videos.
 */
class AudioExtractor(private val context: Context, private val files: HostFiles) {

    data class Result(val sampleRate: Int, val channels: Int, val startMs: Long, val durationMs: Long)

    fun extract(f: HostFile, startMs: Long, maxMs: Long, out: File): Result {
        val ex = MediaExtractor()
        var dataSource: MediaDataSource? = null
        try {
            when (val s = f.source) {
                is HostSource.Local -> ex.setDataSource(s.file.absolutePath)
                is HostSource.Content -> ex.setDataSource(context, s.uri, null)
                is HostSource.Fs -> if (s.path.isLocal && File(s.path.path).canRead()) ex.setDataSource(s.path.path)
                    else ex.setDataSource(StreamDataSource(f, files).also { dataSource = it })
            }
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: throw IllegalArgumentException("No audio track in ${f.name}")
            ex.selectTrack(track)
            val format = ex.getTrackFormat(track)
            val startUs = startMs * 1000
            val endUs = startUs + maxMs * 1000
            if (startUs > 0) ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var inCh = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var float = false
            var frames = 0L
            var firstUs = -1L
            RandomAccessFile(out, "rw").use { raf ->
                raf.setLength(0)
                raf.write(ByteArray(44))
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false
                val chunk = ByteArray(64 * 1024)
                while (!outputDone) {
                    if (!inputDone) {
                        val i = codec.dequeueInputBuffer(10_000)
                        if (i >= 0) {
                            val n = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                            if (n < 0 || ex.sampleTime > endUs) {
                                codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true
                            } else { codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                        }
                    }
                    val o = codec.dequeueOutputBuffer(info, 10_000)
                    when {
                        o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val of = codec.outputFormat
                            rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            inCh = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            float = Build.VERSION.SDK_INT >= 24 && of.containsKey(MediaFormat.KEY_PCM_ENCODING) && of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                        }
                        o >= 0 -> {
                            if (info.size > 0 && info.presentationTimeUs + 1 >= startUs && info.presentationTimeUs <= endUs) {
                                if (firstUs < 0) firstUs = info.presentationTimeUs
                                val buf = codec.getOutputBuffer(o)!!.order(ByteOrder.LITTLE_ENDIAN)
                                buf.position(info.offset); buf.limit(info.offset + info.size)
                                frames += writePcm(buf, raf, inCh, minOf(2, inCh), float, chunk)
                            }
                            codec.releaseOutputBuffer(o, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        }
                    }
                }
                codec.stop(); codec.release()
                val ch = minOf(2, inCh)
                raf.seek(0)
                raf.write(wavHeader(frames, rate, ch))
                return Result(rate, ch, if (firstUs >= 0) firstUs / 1000 else startMs, frames * 1000 / rate.coerceAtLeast(1))
            }
        } finally {
            ex.release()
            dataSource?.close()
        }
    }

    // Converts one decoder buffer to interleaved 16-bit with at most two channels. Returns frames written.
    private fun writePcm(buf: ByteBuffer, raf: RandomAccessFile, inCh: Int, outCh: Int, float: Boolean, chunk: ByteArray): Long {
        val bytesPer = if (float) 4 else 2
        val frameCount = buf.remaining() / (bytesPer * inCh)
        var pos = 0
        for (fr in 0 until frameCount) {
            for (c in 0 until inCh) {
                val v: Int = if (float) (buf.float.coerceIn(-1f, 1f) * 32767).toInt() else buf.short.toInt()
                if (c < outCh) {
                    chunk[pos++] = v.toByte(); chunk[pos++] = (v shr 8).toByte()
                    if (pos == chunk.size) { raf.write(chunk, 0, pos); pos = 0 }
                }
            }
        }
        if (pos > 0) raf.write(chunk, 0, pos)
        return frameCount.toLong()
    }

    companion object {
        fun wavHeader(frames: Long, rate: Int, ch: Int): ByteArray {
            val data = frames * ch * 2
            return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray()); putInt((36 + data).toInt()); put("WAVE".toByteArray())
                put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(ch.toShort()); putInt(rate); putInt(rate * ch * 2); putShort((ch * 2).toShort()); putShort(16)
                put("data".toByteArray()); putInt(data.toInt())
            }.array()
        }
    }
}

/** Random access over any file-manager backend (SMB, cloud…) for MediaExtractor; reopens only on seeks. */
private class StreamDataSource(private val f: HostFile, private val files: HostFiles) : MediaDataSource() {
    private var input: InputStream? = null
    private var pos = -1L
    override fun getSize(): Long = f.size
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (f.size in 0..position) return -1
        if (input == null || pos != position) { input?.close(); input = files.open(f, position); pos = position }
        var n = 0
        while (n < size) {
            val r = input!!.read(buffer, offset + n, size - n)
            if (r < 0) break
            n += r
        }
        pos += n
        return if (n == 0) -1 else n
    }
    override fun close() { input?.close(); input = null }
}
