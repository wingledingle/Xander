package dev.groovesmith.studio

import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xander.files.modules.HostFiles
import com.xander.files.modules.XanderHostBridge
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class GroovesmithAppTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun webViews(v: View): List<WebView> = when (v) {
        is WebView -> listOf(v)
        is ViewGroup -> (0 until v.childCount).flatMap { webViews(v.getChildAt(it)) }
        else -> emptyList()
    }

    private fun launchAndFindWebView(intent: Intent, check: (WebView) -> Unit) {
        ActivityScenario.launch<MainActivity>(intent).use { s ->
            var web: WebView? = null
            val deadline = System.currentTimeMillis() + 20_000
            while (web == null && System.currentTimeMillis() < deadline) {
                ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
                s.onActivity { a -> web = webViews(a.window.decorView).firstOrNull() }
            }
            check(checkNotNull(web) { "the Studio page never appeared" })
        }
    }

    @Test fun opensTheStudioWithTheNativeBridge() = launchAndFindWebView(Intent(ctx, MainActivity::class.java)) { w ->
        val shadow = shadowOf(w)
        assertEquals("https://appassets.androidplatform.net/assets/modules/studio/index.html", shadow.lastLoadedUrl)
        val bridge = shadow.getJavascriptInterface("XanderHost") as XanderHostBridge
        assertTrue("native video", bridge.videoAvailable())
        assertTrue("low-latency pads", bridge.padAvailable())
        assertTrue(!w.settings.allowFileAccess)
    }

    @Test fun openWithHandsTheFileToThePage() {
        val clip = File(ctx.cacheDir, "clip.mp4").apply { writeBytes(ByteArray(2048) { it.toByte() }) }
        launchAndFindWebView(Intent(ctx, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.fromFile(clip))) { w ->
            val launch = JSONObject((shadowOf(w).getJavascriptInterface("XanderHost") as XanderHostBridge).launchFile())
            assertEquals("clip.mp4", launch.getString("name"))
            assertEquals(2048, launch.getLong("size"))
        }
    }

    @Test fun localStorageReadsAndSeeks() = runBlocking {
        val f = File(ctx.cacheDir, "a.wav").apply { writeBytes(ByteArray(100) { it.toByte() }) }
        val storage = LocalHostStorage(ctx)
        assertEquals(100, storage.stat(f.path).size)
        assertArrayEquals(ByteArray(10) { (90 + it).toByte() }, storage.open(Uri.fromFile(f).toString(), 90).readBytes())
        val files = HostFiles(ctx, storage)
        val h = files.register(Uri.fromFile(f).toString())
        assertEquals(206, files.respond(h.token, "bytes=10-19").statusCode)
    }
}
