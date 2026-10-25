package com.xander.files

import android.os.Environment
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xander.files.data.storage.StorageAccess
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.util.concurrent.TimeUnit

/** Drives home -> browser -> global search on the JVM with real providers and a fake external storage. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class BrowseAndSearchFlowTest {

    init { StorageAccess.overrideForTests = true }

    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Before fun seed() {
        val root = Environment.getExternalStorageDirectory()
        File(root, "Documents/Projects").mkdirs()
        File(root, "Documents/Projects/needle-report.txt").writeText("hello")
        File(root, "Download").mkdirs()
        File(root, "DCIM").mkdirs()
    }

    /** Robolectric's paused looper does not advance time by itself; step it while background I/O completes. */
    private fun waitForText(text: String, timeoutMs: Long = 15_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (rule.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isEmpty()) {
            check(System.currentTimeMillis() < deadline) {
                "'$text' never appeared. Screen:\n" + rule.onRoot(useUnmergedTree = true).printToString()
            }
            ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
            Thread.sleep(50)
            rule.waitForIdle()
        }
    }

    @Test fun browseIntoFolderAndFindFileGlobally() {
        waitForText("Quick access")
        rule.onAllNodesWithText("DCIM").onFirst().performScrollTo().performClick()
        waitForText("This folder is empty")
        rule.onNodeWithContentDescription("Search").performClick()
        rule.waitForIdle()
        rule.onNode(hasSetTextAction()).performTextInput("needle")
        // Opened from a folder, search is scoped to it first: nothing in DCIM.
        waitForText("No matching files")
        // Switch the scope chip to everywhere: the file under Documents/Projects is found.
        rule.onAllNodesWithText("DCIM", useUnmergedTree = true).onFirst().performClick()
        waitForText("needle-report.txt")
    }
}
