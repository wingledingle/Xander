package com.xander.files.modules

import android.content.Context
import android.content.Intent
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xander.files.MainActivity
import com.xander.files.data.storage.StorageAccess
import com.xander.files.domain.fs.FileEntry
import com.xander.files.domain.fs.FileSystemRegistry
import com.xander.files.domain.fs.FsPath
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ModulesTest {
    @get:Rule val tmp = TemporaryFolder()
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private fun entry(name: String, dir: Boolean = false) = FileEntry(FsPath.local("/sdcard/$name"), dir)

    @Test fun registryOffersStudioForMediaAndOpensProjectsOnTap() {
        assertEquals(listOf(StudioModule), Modules.forFile(entry("beat.mp4")))
        assertEquals(listOf(StudioModule), Modules.forFile(entry("loop.WAV")))
        assertTrue(Modules.forFile(entry("data.xyz")).isEmpty())
        assertEquals(listOf(QuintenModule), Modules.forFile(entry("notes.txt")))
        assertTrue(Modules.forFile(entry("folder.mp4", dir = true)).isEmpty())
        assertEquals(StudioModule, Modules.defaultFor(entry("song.gsproj")))
        assertNull("videos still open in the video player on tap", Modules.defaultFor(entry("clip.mp4")))
        assertEquals("module/studio?path=file%3A%2F%2F%2Fsdcard%2Fa%20b.mp4", Modules.route(StudioModule, FsPath.local("/sdcard/a b.mp4")))
        assertEquals(OleanderModule, Modules.defaultFor(entry("pokemon.gba")))
        assertTrue(OleanderModule in Modules.forFile(entry("roms.zip")))
        assertEquals(QuintenModule, Modules.defaultFor(entry("novel.mobi")))
        assertNull("EPUBs keep Xander's own reader on tap", Modules.defaultFor(entry("novel.epub")))
        assertTrue(QuintenModule in Modules.forFile(entry("novel.epub")))
        assertTrue(ctx.assets.list("web")!!.contains("oleander.wasm"))
        for (m in Modules.all) {
            assertTrue("${m.id}: defaultFor must be a subset of opens", m.opens.containsAll(m.defaultFor))
            assertEquals("duplicate id ${m.id}", 1, Modules.all.count { it.id == m.id })
            if (m is WebModule) assertNotNull("assets/modules/${m.id}/${m.entry} must be bundled", ctx.assets.open("modules/${m.id}/${m.entry}"))
        }
    }

    @Test fun parseRange() {
        assertEquals(0L..99L, HostFiles.parseRange("bytes=0-", 100))
        assertEquals(10L..19L, HostFiles.parseRange("bytes=10-19", 100))
        assertEquals(90L..99L, HostFiles.parseRange("bytes=-10", 100))
        assertEquals(50L..99L, HostFiles.parseRange("bytes=50-500", 100))
        assertNull(HostFiles.parseRange("bytes=200-", 100))
        assertNull(HostFiles.parseRange(null, 100))
        assertNull(HostFiles.parseRange("bytes=0-", -1))
        assertNull(HostFiles.parseRange("bytes=-", 100))
    }

    @Test fun servesFilesWithRangesForSeeking() {
        val f = tmp.newFile("clip.webm").apply { writeBytes(ByteArray(1000) { it.toByte() }) }
        val files = HostFiles(ctx, FileSystemRegistry(emptySet()))
        val h = files.register(f, "video/webm")
        assertTrue(h.url.startsWith("https://appassets.androidplatform.net/host/file/${h.token}/"))
        val full = files.respond(h.token, null)
        assertEquals(200, full.statusCode)
        assertEquals(1000, full.data.readBytes().size)
        val part = files.respond(h.token, "bytes=100-109")
        assertEquals(206, part.statusCode)
        assertEquals("bytes 100-109/1000", part.responseHeaders["Content-Range"])
        assertArrayEquals(ByteArray(10) { (100 + it).toByte() }, part.data.readBytes())
        assertEquals(404, files.respond("nope", null).statusCode)
    }

    @Test fun wavHeaderMatchesFormat() {
        val h = ByteBuffer.wrap(AudioExtractor.wavHeader(frames = 48000, rate = 48000, ch = 2)).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(ByteArray(4) { h.get(it) }))
        assertEquals(36 + 48000 * 4, h.getInt(4))
        assertEquals(2, h.getShort(22).toInt())
        assertEquals(48000, h.getInt(24))
        assertEquals(48000 * 4, h.getInt(40))
    }

    @Test fun padsResampleToStereoAndClipSoftly() {
        val mono = shortArrayOf(0, 1000, 2000, 3000)
        val st = LowLatencyPads.resampleStereo(mono, 1, 24000, 48000)
        assertEquals(16, st.size)
        assertEquals(st[2], st[3]) // mono copied to both channels
        assertEquals(500, st[2].toInt()) // halfway between 0 and 1000
        assertArrayEquals(shortArrayOf(1, 2, 3, 4), LowLatencyPads.resampleStereo(shortArrayOf(1, 2, 3, 4), 2, 48000, 48000))
        assertEquals(1000, LowLatencyPads.softClip(1000f).toInt())
        assertTrue(LowLatencyPads.softClip(200000f) in 32000..32767)
        assertTrue(LowLatencyPads.softClip(-200000f) in -32768..-32000)
    }

    @Test fun saveNamesAreSafeAndUnique() {
        assertEquals("a_b_c.wav", WebModuleViewModel.safeName("a/b:c.wav"))
        val dir = tmp.newFolder()
        java.io.File(dir, "beat.wav").writeText("x")
        assertEquals("beat (2).wav", WebModuleViewModel.uniqueFile(dir, "beat.wav").name)
        assertArrayEquals(shortArrayOf(1, -2), WebModuleViewModel.decodePcm16(android.util.Base64.encodeToString(byteArrayOf(1, 0, -2, -1), 0)))
    }

    @Test fun studioRouteOpensTheWebModuleWithTheBridge() {
        StorageAccess.overrideForTests = true
        val scenario = ActivityScenario.launch<MainActivity>(Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_ROUTE, Modules.route(StudioModule)))
        try {
            var web: WebView? = null
            val deadline = System.currentTimeMillis() + 20_000
            while (web == null && System.currentTimeMillis() < deadline) {
                ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
                scenario.onActivity { a -> web = webViews(a.window.decorView).firstOrNull() }
            }
            val w = checkNotNull(web) { "no WebView on the Studio route" }
            val shadow = shadowOf(w)
            assertEquals("https://appassets.androidplatform.net/assets/modules/studio/index.html", shadow.lastLoadedUrl)
            assertTrue(shadow.getJavascriptInterface("XanderHost") is XanderHostBridge)
            assertTrue(w.settings.javaScriptEnabled)
            assertTrue(!w.settings.allowFileAccess)
        } finally { scenario.close() }
    }

    @Test fun activityModulesLaunchTheirAppWithTheFile() {
        StorageAccess.overrideForTests = true
        val book = java.io.File(android.os.Environment.getExternalStorageDirectory(), "Books/novel.mobi").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(64)) }
        val scenario = ActivityScenario.launch<MainActivity>(Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_ROUTE, Modules.route(QuintenModule, FsPath.local(book.path))))
        try {
            var started: Intent? = null
            val deadline = System.currentTimeMillis() + 20_000
            while (started == null && System.currentTimeMillis() < deadline) {
                ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
                started = shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>()).nextStartedActivity
                    ?.takeIf { it.component?.className == "app.quinten.MainActivity" }
            }
            val i = checkNotNull(started) { "Quinten was not started" }
            assertEquals(Intent.ACTION_VIEW, i.action)
            assertEquals("content", i.data?.scheme)
            assertTrue(i.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        } finally { scenario.close() }
    }
}

private fun webViews(v: android.view.View): List<WebView> =
    if (v is WebView) listOf(v) else (v as? android.view.ViewGroup)?.let { g -> (0 until g.childCount).flatMap { webViews(g.getChildAt(it)) } } ?: emptyList()
