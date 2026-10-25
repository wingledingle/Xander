package com.xander.files.data.nsfw

import android.content.Context
import android.graphics.Bitmap
import dagger.hilt.android.qualifiers.ApplicationContext
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/** Class probabilities from GantMan's nsfw_model (MobileNetV2 224, MIT). */
data class NsfwScores(val drawings: Float, val hentai: Float, val neutral: Float, val porn: Float, val sexy: Float)

/**
 * On-device classifier: GantMan nsfw_model mobilenet_v2_140_224 (MIT) run with TensorFlow Lite (Apache-2.0).
 * It has a separate "drawings" class, so comics and manga are not treated as porn. Nothing leaves the phone.
 */
@Singleton
class NsfwClassifier @Inject constructor(@ApplicationContext private val context: Context) {
    private val interpreter: Interpreter by lazy {
        val buf = context.assets.openFd(MODEL).use { fd ->
            java.io.FileInputStream(fd.fileDescriptor).channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        }
        Interpreter(buf, Interpreter.Options().setNumThreads(2))
    }
    private val input = ByteBuffer.allocateDirect(4 * SIZE * SIZE * 3).order(ByteOrder.nativeOrder())
    private val output = Array(1) { FloatArray(5) }

    @Synchronized
    fun classify(bitmap: Bitmap): NsfwScores {
        fill(input, bitmap)
        interpreter.run(input, output)
        val p = output[0]
        return NsfwScores(p[0], p[1], p[2], p[3], p[4])
    }

    companion object {
        const val MODEL = "nsfw/gantman_mobilenet_v2_224.tflite"
        const val SIZE = 224

        /** Same as the model's predict.py: nearest-neighbour resize to 224×224, RGB / 255, NHWC. */
        fun fill(buf: ByteBuffer, src: Bitmap) {
            val b0 = if (src.config == Bitmap.Config.HARDWARE) src.copy(Bitmap.Config.ARGB_8888, false) else src
            val b = if (b0.width == SIZE && b0.height == SIZE) b0 else Bitmap.createScaledBitmap(b0, SIZE, SIZE, false)
            val px = IntArray(SIZE * SIZE); b.getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE)
            buf.rewind()
            for (c in px) { buf.putFloat((c shr 16 and 0xff) / 255f); buf.putFloat((c shr 8 and 0xff) / 255f); buf.putFloat((c and 0xff) / 255f) }
            buf.rewind()
        }

        /** Near-uniform frames (fades, black frames, blank pages) carry no signal; skip them. */
        fun isFlat(b: Bitmap): Boolean {
            val w = b.width; val h = b.height; var n = 0; var sum = 0.0; var sq = 0.0
            var y = 0
            while (y < h) { var x = 0; while (x < w) { val c = b.getPixel(x, y); val l = ((c shr 16 and 0xff) + (c shr 8 and 0xff) + (c and 0xff)) / 3.0; sum += l; sq += l * l; n++; x += maxOf(1, w / 32) }; y += maxOf(1, h / 32) }
            val mean = sum / n
            return sqrt((sq / n - mean * mean).coerceAtLeast(0.0)) < 12.0
        }
    }
}
