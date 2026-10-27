package app.quinten

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import app.quinten.ui.theme.AppThemes
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.file.Files

/** Renders the library in every styled theme (PNG files in app/build/screenshots) and checks the wallpaper is drawn. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThemeScreenshotTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun styledThemesRender() {
        val app = rule.activity.container
        val dir = Files.createTempDirectory("lib").toFile()
        for (n in listOf("sample.epub", "sample.fb2", "sample.docx")) {
            val f = File(dir, n)
            File("../core/formats/src/test/resources/fixtures/$n").copyTo(f)
            runBlocking { app.library.addBook(f.absolutePath) }
        }
        val out = File("build/screenshots").apply { mkdirs() }
        for (t in AppThemes.STYLED) {
            runBlocking { app.settings.updateApp { it.copy(uiTheme = t.id) } }
            val end = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < end && rule.onAllNodesWithText("The Test Voyage").fetchSemanticsNodes().isEmpty()) { rule.waitForIdle(); Thread.sleep(20) }
            rule.waitForIdle()
            val v = rule.activity.window.decorView
            val bmp = android.graphics.Bitmap.createBitmap(v.width, v.height, android.graphics.Bitmap.Config.ARGB_8888)
            rule.runOnUiThread { v.draw(android.graphics.Canvas(bmp)) }
            File(out, "${t.id}.png").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            // The wallpaper differs from a flat background: sample a few far-apart pixels.
            val colors = listOf(bmp.getPixel(bmp.width / 10, bmp.height - 40), bmp.getPixel(bmp.width / 2, bmp.height - 200), bmp.getPixel(bmp.width - 30, bmp.height / 3)).toSet()
            assertTrue("${t.id} looks flat", colors.size > 1)
        }
    }
}
