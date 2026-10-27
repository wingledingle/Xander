package app.quinten

import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.quinten.render.ReaderView
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppFlowTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun findReader(v: View): ReaderView? {
        if (v is ReaderView) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) findReader(v.getChildAt(i))?.let { return it }
        return null
    }

    private fun waitFor(timeoutMs: Long = 20_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            rule.waitForIdle()
            if (cond()) return
            Thread.sleep(20)
        }
        error("condition not met in time")
    }

    @Test fun libraryToReaderFlow() {
        val app = rule.activity.container
        rule.onNodeWithText("Your library is empty").assertIsDisplayed()

        val dir = Files.createTempDirectory("lib").toFile()
        val book = File(dir, "sample.epub")
        File("../core/formats/src/test/resources/fixtures/sample.epub").copyTo(book)
        runBlocking { app.library.addBook(book.absolutePath) }
        waitFor { rule.onAllNodesWithText("The Test Voyage").fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodesWithText("The Test Voyage")[0].performClick()

        var reader: ReaderView? = null
        waitFor { reader = findReader(rule.activity.window.decorView); reader?.width ?: 0 > 0 && reader!!.visibleText().isNotEmpty() }
        val rv = reader!!
        val first = rv.visibleText()
        assertTrue(first, first.contains("The Test Voyage"))

        // Turn pages until the second chapter text shows.
        var guard = 0
        while (!rv.visibleText().contains("second chapter begins") && guard++ < 20) {
            rule.runOnUiThread { rv.nextPageAction(animated = false) }
            waitFor { rv.visibleText().isNotEmpty() }
        }
        assertTrue(rv.visibleText().contains("second chapter begins"))
        val pos = rv.currentPosition

        // Huge print: same position kept, text still visible and reflowed.
        runBlocking { app.settings.updateReader { it.copy(fontSizeSp = 72f) } }
        waitFor { rv.controller?.settings?.fontSizeSp == 72f && rv.visibleText().isNotEmpty() }
        assertTrue(rv.currentPosition.chapter == pos.chapter)
        assertTrue(rv.visibleText().length < 400)

        // Menu opens on centre tap; contents panel lists chapters.
        rule.runOnUiThread { rv.listener?.onCenterTap() }
        rule.onNodeWithContentDescription("Contents, bookmarks and notes").performClick()
        waitFor { rule.onAllNodesWithText("Chapter Two").fetchSemanticsNodes().isNotEmpty() }
        assertNotEquals(0, rule.onAllNodesWithText("Chapter One").fetchSemanticsNodes().size)

        // Jump back to the start via the table of contents, then to chapter two.
        runBlocking { app.settings.updateReader { it.copy(fontSizeSp = 20f) } }
        rule.onAllNodesWithText("Chapter One")[0].performClick()
        waitFor { rv.visibleText().contains("bright cold day") }
        rule.runOnUiThread { rv.listener?.onCenterTap() }
        rule.onNodeWithContentDescription("Contents, bookmarks and notes").performClick()
        waitFor { rule.onAllNodesWithText("Chapter Two").fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodesWithText("Chapter Two")[0].performClick()
        waitFor { rv.visibleText().contains("second chapter begins") }
    }
}
