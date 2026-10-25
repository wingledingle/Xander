package com.xander.files.data.nsfw

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.FloatBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * On-device NSFW image classifier: Marqo/nsfw-image-detection-384 (ViT-tiny, Apache-2.0) exported to ONNX
 * by ICIJ for nuditag, run with ONNX Runtime (MIT). Nothing leaves the phone.
 */
@Singleton
class NsfwClassifier @Inject constructor(@ApplicationContext private val context: Context) {
    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
    private val session: OrtSession by lazy {
        val bytes = context.assets.open(MODEL).use { it.readBytes() }
        OrtSession.SessionOptions().use { o -> o.setIntraOpNumThreads(2); env.createSession(bytes, o) }
    }

    /** NSFW probability in [0, 1] for one image. */
    @Synchronized
    fun score(bitmap: Bitmap): Float {
        val input = preprocess(bitmap)
        OnnxTensor.createTensor(env, input, longArrayOf(1, 3, SIZE.toLong(), SIZE.toLong())).use { t ->
            session.run(mapOf(session.inputNames.first() to t)).use { out ->
                @Suppress("UNCHECKED_CAST")
                val logits = (out[0].value as Array<FloatArray>)[0]
                return softmax(logits)[0]
            }
        }
    }

    companion object {
        const val MODEL = "nsfw/marqo-nsfw-384.onnx"
        const val SIZE = 384

        /** Resize to 384×384 and scale RGB to [-1, 1] (mean 0.5, std 0.5), NCHW. */
        fun preprocess(src: Bitmap): FloatBuffer {
            val b = if (src.width == SIZE && src.height == SIZE && src.config == Bitmap.Config.ARGB_8888) src
            else Bitmap.createScaledBitmap(src.copyIfNeeded(), SIZE, SIZE, true)
            val px = IntArray(SIZE * SIZE); b.getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE)
            val buf = FloatBuffer.allocate(3 * SIZE * SIZE)
            val plane = SIZE * SIZE
            for (i in px.indices) {
                val c = px[i]
                buf.put(i, ((c shr 16 and 0xff) / 127.5f) - 1f)
                buf.put(plane + i, ((c shr 8 and 0xff) / 127.5f) - 1f)
                buf.put(2 * plane + i, ((c and 0xff) / 127.5f) - 1f)
            }
            return buf
        }

        private fun Bitmap.copyIfNeeded(): Bitmap = if (config == Bitmap.Config.HARDWARE) copy(Bitmap.Config.ARGB_8888, false) else this

        fun softmax(l: FloatArray): FloatArray {
            val m = l.max(); val e = l.map { exp((it - m).toDouble()) }; val s = e.sum()
            return FloatArray(l.size) { (e[it] / s).toFloat() }
        }

        /** Near-uniform frames (fades, black frames) score ~0.36 regardless of content, so videos skip them. */
        fun isFlat(b: Bitmap): Boolean {
            val w = b.width; val h = b.height; var n = 0; var sum = 0.0; var sq = 0.0
            var y = 0
            while (y < h) { var x = 0; while (x < w) { val c = b.getPixel(x, y); val l = ((c shr 16 and 0xff) + (c shr 8 and 0xff) + (c and 0xff)) / 3.0; sum += l; sq += l * l; n++; x += maxOf(1, w / 32) }; y += maxOf(1, h / 32) }
            val mean = sum / n
            return sqrt((sq / n - mean * mean).coerceAtLeast(0.0)) < 12.0
        }
    }
}
