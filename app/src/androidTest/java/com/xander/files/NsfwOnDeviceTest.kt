package com.xander.files

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xander.files.data.nsfw.NsfwClassifier
import com.xander.files.data.nsfw.NsfwScorer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Runs the adult-content classifier with the real TensorFlow Lite runtime on a device. Run it against the
 * release build too (-Pxander.testRelease), where R8 renaming once broke the native bindings.
 */
@RunWith(AndroidJUnit4::class)
class NsfwOnDeviceTest {
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun picture(): Bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888).also { b ->
        val c = Canvas(b)
        c.drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, 640f, 480f, Color.rgb(30, 90, 160), Color.rgb(220, 200, 80), Shader.TileMode.CLAMP) })
        val p = Paint().apply { color = Color.rgb(40, 140, 60) }
        for (i in 0 until 12) c.drawCircle(50f + i * 50f, 240f + (i % 3) * 60f, 30f + i * 2f, p)
    }

    @Test fun classifierRunsAndReturnsProbabilities() {
        val s = NsfwClassifier(ctx).classify(picture())
        val sum = s.drawings + s.hentai + s.neutral + s.porn + s.sexy
        assertEquals("class probabilities sum to 1", 1f, sum, 0.02f)
        assertTrue("a plain picture is not adult (porn=${s.porn})", s.porn < 0.5f)
    }

    @Test fun scorerReadsAnImageFile() {
        val f = File(ctx.cacheDir, "nsfw-test.jpg")
        f.outputStream().use { picture().compress(Bitmap.CompressFormat.JPEG, 90, it) }
        val s = NsfwScorer(NsfwClassifier(ctx)).image(f)
        assertNotNull("the scorer returned no result for a readable JPEG", s)
    }
}
