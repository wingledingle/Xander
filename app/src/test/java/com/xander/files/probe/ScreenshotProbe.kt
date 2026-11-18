package com.xander.files.probe

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xander.files.MainActivity
import com.xander.files.data.storage.StorageAccess
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Renders screens at phone size to PNGs (for looking at layout problems). Not part of the normal suite:
 *   SCREENSHOTS=1 ./gradlew :app:testMobileFilesDebugUnitTest --tests '*ScreenshotProbe*'
 * Output: app/build/screenshots/<route>.png
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xxhdpi")
class ScreenshotProbe {
    init { StorageAccess.overrideForTests = true }
    @get:Rule val rule = createEmptyComposeRule()

    private fun shot(route: String?, name: String, settleMs: Long = 3000) {
        assumeTrue(System.getenv("SCREENSHOTS") == "1")
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val i = Intent(ctx, MainActivity::class.java)
        if (route != null) i.putExtra(MainActivity.EXTRA_TOKEN, MainActivity.INTERNAL_TOKEN).putExtra(MainActivity.EXTRA_ROUTE, route)
        ActivityScenario.launch<MainActivity>(i).use {
            val end = System.currentTimeMillis() + settleMs
            while (System.currentTimeMillis() < end) { ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS); rule.waitForIdle() }
            val bmp = rule.onRoot().captureToImage().asAndroidBitmap()
            val out = File("build/screenshots").apply { mkdirs() }
            File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test fun home() = shot(null, "home")
    @Test fun analyzer() = shot("analyzer", "analyzer")
    @Test fun settings() = shot("settings", "settings")
}
