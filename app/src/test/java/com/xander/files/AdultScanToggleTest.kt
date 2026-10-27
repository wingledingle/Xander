package com.xander.files

import android.content.Intent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import com.xander.files.data.nsfw.NsfwRepository
import com.xander.files.data.storage.StorageAccess
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

/** Turning on adult-content detection in Settings must queue the scan (it used to read the old "off" value and skip). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AdultScanToggleTest {
    init { StorageAccess.overrideForTests = true }
    @get:Rule val rule = createEmptyComposeRule()
    private var scenario: ActivityScenario<MainActivity>? = null
    @After fun close() { scenario?.close() }

    private fun idle() { ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS); Thread.sleep(50); rule.waitForIdle() }

    @Test fun switchingDetectionOnQueuesAScan() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        scenario = ActivityScenario.launch(Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_ROUTE, "settings"))
        val label = ctx.getString(R.string.adult_detect)
        val deadline = System.currentTimeMillis() + 20_000
        while (rule.onAllNodesWithText(label).fetchSemanticsNodes().isEmpty()) { check(System.currentTimeMillis() < deadline) { "Settings not shown" }; idle() }
        rule.onNodeWithText(label).performScrollTo().performClick()
        val wm = WorkManager.getInstance(ctx)
        var queued = false
        val until = System.currentTimeMillis() + 15_000
        while (!queued && System.currentTimeMillis() < until) {
            idle()
            queued = wm.getWorkInfosForUniqueWork(NsfwRepository.WORK).get().isNotEmpty()
        }
        assertTrue("the adult scan was not queued after switching detection on", queued)
    }
}
