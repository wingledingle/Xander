package com.xander.files

import android.content.Intent
import android.os.Environment
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.printToString
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xander.files.data.storage.StorageAccess
import com.xander.files.domain.fs.FsPath
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.util.concurrent.TimeUnit

/** Opens the rebuilt video player and the analyzer's new tabs in the real app and checks what is on screen. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ScreensSmokeTest {
    init { StorageAccess.overrideForTests = true }
    @get:Rule val rule = createEmptyComposeRule()
    private var scenario: ActivityScenario<MainActivity>? = null
    @After fun close() { scenario?.close() }

    private fun launch(route: String) {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        scenario = ActivityScenario.launch(Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_ROUTE, route))
    }

    private fun waitFor(timeoutMs: Long = 20_000, desc: String, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            check(System.currentTimeMillis() < deadline) { "$desc never appeared. Screen:\n" + rule.onRoot(useUnmergedTree = true).printToString() }
            ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS); Thread.sleep(50); rule.waitForIdle()
        }
    }
    private fun text(t: String) = rule.onAllNodesWithText(t, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun desc(d: String) = rule.onAllNodesWithContentDescription(d, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Test fun videoPlayerShowsControlsAndLockWorks() {
        val clip = File(Environment.getExternalStorageDirectory(), "Movies/holiday clip.mp4").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(2048)) }
        launch("video?path=" + android.net.Uri.encode(FsPath.local(clip.path).toString()))
        waitFor(desc = "player controls") { desc("Lock controls") }
        for (d in listOf("Back", "Audio track", "Subtitles", "Playback speed", "Aspect ratio", "Rotate", "Previous", "Next")) assertTrue("missing $d", desc(d))
        assertTrue(text("holiday clip.mp4"))
        rule.onNodeWithContentDescription("Lock controls", useUnmergedTree = true).performClick()
        waitFor(desc = "unlock button") { desc("Unlock") }
        assertTrue("controls must hide while locked", !desc("Aspect ratio"))
        rule.onNodeWithContentDescription("Unlock", useUnmergedTree = true).performClick()
        waitFor(desc = "controls back") { desc("Aspect ratio") }
    }

    @Test fun analyzerListsAllFilesAndUnreadableFolders() {
        val root = Environment.getExternalStorageDirectory()
        File(root, "Pictures/trip").mkdirs(); File(root, "Pictures/trip/beach-sunset.jpg").writeBytes(ByteArray(5000))
        File(root, "Download").mkdirs(); File(root, "Download/manual-v2.pdf").writeBytes(ByteArray(3000))
        launch("analyzer")
        // Opens straight into the ranked list (scan starts by itself), largest file first.
        waitFor(desc = "ranked file rows") { text("beach-sunset.jpg") && text("manual-v2.pdf") }
        val big = rule.onAllNodesWithText("beach-sunset.jpg", useUnmergedTree = true).fetchSemanticsNodes().first().boundsInRoot.top
        val small = rule.onAllNodesWithText("manual-v2.pdf", useUnmergedTree = true).fetchSemanticsNodes().first().boundsInRoot.top
        assertTrue("5 KB file must be listed above the 3 KB file", big < small)
        rule.onAllNodesWithText("Unreadable").onFirst().performScrollTo().performClick()
        waitFor(desc = "unreadable tab") { text("Every folder could be read") || text("could not be read") }
    }
}
