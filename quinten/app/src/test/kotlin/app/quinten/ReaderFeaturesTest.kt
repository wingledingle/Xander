package app.quinten

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import app.quinten.model.TextBlock
import app.quinten.render.Pos
import app.quinten.tts.SpeechBackend
import app.quinten.tts.TtsController
import app.quinten.ui.reader.ReaderViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderFeaturesTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    private fun waitFor(timeoutMs: Long = 30_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            if (cond()) return
            Thread.sleep(10)
        }
        error("condition not met in time")
    }

    private fun open(fixture: String): ReaderViewModel {
        val f = File(Files.createTempDirectory("b").toFile(), fixture).also { File("../core/formats/src/test/resources/fixtures/$fixture").copyTo(it) }
        val id = runBlocking { app.container.library.addBook(f.absolutePath) }!!
        val vm = ReaderViewModel(app)
        vm.open(id)
        waitFor { vm.load.value is ReaderViewModel.Load.Ready }
        val rc = vm.controllerOrNull!!
        rc.setViewport(900, 1500)
        return vm
    }

    private fun ReaderViewModel.chapterText(i: Int): String {
        val rc = controllerOrNull!!
        try { waitFor { rc.chapter(i) != null } } catch (e: IllegalStateException) { error("chapter $i: ${rc.chapterError(i)}") }
        return rc.chapter(i)!!.blocks.filterIsInstance<TextBlock>().joinToString("\n") { it.text }
    }

    @Test fun bookmarksHighlightsSearchFootnotes() {
        val vm = open("sample.epub")
        val rc = vm.controllerOrNull!!
        waitFor { rc.chapter(1) != null }
        // Bookmark
        vm.onPosition(Pos(1, 1, 0), 0.4f)
        vm.toggleBookmark()
        waitFor { vm.bookmarks.value.size == 1 }
        // Highlight with note
        vm.addHighlight(Pos(1, 1, 9), Pos(1, 1, 15), "bright", 0x66FFEB3B, "colour")
        waitFor { vm.highlights.value.size == 1 }
        assertEquals("colour", vm.highlights.value.single().note)
        // Search
        vm.search("thirteen")
        waitFor { !vm.searching.value && vm.searchResults.value.isNotEmpty() }
        assertTrue(vm.searchResults.value.first().snippet.contains("thirteen"))
        // Footnote popup text
        val link = rc.chapter(1)!!.blocks.filterIsInstance<TextBlock>().flatMap { it.spans }.filterIsInstance<app.quinten.model.LinkSpan>().first { it.footnote }
        val note = runBlocking { vm.footnoteText(link.target) }
        assertNotNull(note)
        assertTrue(note!!.contains("This is the footnote text"))
    }

    @Test fun cleanModeOnScannedBookIsReversible() {
        val vm = open("sample.djvu")
        vm.setCleanMode(true)
        waitFor { vm.controllerOrNull!!.clean.enabled }
        val cleanText = vm.chapterText(0)
        assertTrue(cleanText.contains("Alice was beginning to get very tired"))
        assertTrue(!cleanText.contains("ALICE'S ADVENTURES IN WONDERLAND"))
        val removals = vm.controllerOrNull!!.chapter(0)!!.removals
        assertTrue(removals.isNotEmpty())

        // Preview shows removed text struck through instead of hiding it.
        vm.setPreview(true)
        assertTrue(vm.chapterText(0).contains("ALICE'S ADVENTURES IN WONDERLAND"))
        vm.setPreview(false)

        // Restoring one header brings back exactly that line.
        val header = removals.first { it.text.contains("ALICE'S") }
        vm.restoreRemoval(header.id)
        waitFor { header.id in vm.controllerOrNull!!.clean.restoredIds }
        assertEquals(1, Regex("ALICE'S ADVENTURES").findAll(vm.chapterText(0)).count())

        // Turning clean mode off shows everything again.
        vm.setCleanMode(false)
        waitFor { !vm.controllerOrNull!!.clean.enabled }
        assertTrue(Regex("ALICE'S ADVENTURES").findAll(vm.chapterText(0)).count() >= 2)
    }

    @Test fun ttsSleepTimerEnds() {
        val vm = open("sample.epub")
        val tts = vm.tts!!
        tts.setSleepTimer(10)
        assertEquals(10, tts.sleepMinutes.value)
        org.robolectric.shadows.ShadowLooper.idleMainLooper(9, java.util.concurrent.TimeUnit.MINUTES)
        assertEquals(10, tts.sleepMinutes.value)
        org.robolectric.shadows.ShadowLooper.idleMainLooper(2, java.util.concurrent.TimeUnit.MINUTES)
        assertEquals(0, tts.sleepMinutes.value)
        tts.setSleepTimer(30); tts.setSleepTimer(0)
        assertEquals(0, tts.sleepMinutes.value)
    }

    /** A voice that speaks only when told to: lets the test play sentences one by one. */
    private class FakeVoice : SpeechBackend {
        override var listener: SpeechBackend.Listener? = null
        override var rate = 1f
        override var pitch = 1f
        override val hasPitch = false
        val queued = ArrayDeque<Pair<String, String>>()
        val spoken = ArrayList<String>()
        var stops = 0
        override fun init(onReady: (Boolean) -> Unit) = onReady(true)
        override fun speak(id: String, text: String) { queued += id to text }
        override fun stop() { queued.clear(); stops++ }
        override fun shutdown() {}
        fun sayNext(): Boolean {
            val (id, text) = queued.removeFirstOrNull() ?: return false
            listener?.onStart(id); spoken += text; listener?.onDone(id)
            return true
        }
    }

    @Test fun readAloudQueueKeepsAheadAndSwitchesVoices() {
        val vm = open("sample.epub")
        val tts = vm.tts!!
        val voice = FakeVoice()
        tts.setBackend(voice)
        var highlighted: Pos? = null
        tts.onSpeaking = { p, _ -> highlighted = p }
        tts.play(Pos(1, 0, 0))
        waitFor { voice.queued.size == 8 }
        assertEquals(TtsController.State.PLAYING, tts.state)
        // The queue is topped up before it runs dry (a slow voice needs sentences ready).
        // After 6 of the 8, more arrive while 2 are still waiting.
        repeat(6) { voice.sayNext(); shadowOf(Looper.getMainLooper()).idle() }
        waitFor { voice.queued.size > 2 }
        assertNotNull(highlighted)
        assertTrue(highlighted!!.chapter >= 1)
        // Sentences come in book order (across chapters) without repeats.
        val chapter = (1 until vm.controllerOrNull!!.chapterCount).joinToString("\n") { vm.chapterText(it) }
        var at = 0
        for (s in voice.spoken) { val k = chapter.indexOf(s, at); assertTrue("in order: $s", k >= at); at = k + s.length }
        // Switching voices while playing carries on from the sentence being read.
        val other = FakeVoice()
        tts.setBackend(other)
        waitFor { other.queued.isNotEmpty() }
        assertEquals(TtsController.State.PLAYING, tts.state)
        assertTrue(chapter.indexOf(other.queued.first().second) >= chapter.indexOf(voice.spoken.last()))
        tts.stop()
        assertEquals(TtsController.State.IDLE, tts.state)
    }
}
