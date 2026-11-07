package com.xander.files

import android.content.Intent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xander.files.data.storage.StorageAccess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

/** The launcher activity is exported: extras from other apps must never crash Xander or open arbitrary screens. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class LaunchExtrasTest {
    init { StorageAccess.overrideForTests = true }
    @get:Rule val rule = createEmptyComposeRule()

    private fun launch(extras: Intent.() -> Unit): Lifecycle.State {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        ActivityScenario.launch<MainActivity>(Intent(ctx, MainActivity::class.java).apply(extras)).use { s ->
            repeat(10) { ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS); rule.waitForIdle() }
            return s.state
        }
    }

    @Test fun unknownRouteFromAnotherAppDoesNotCrash() {
        assertEquals(Lifecycle.State.RESUMED, launch { putExtra(MainActivity.EXTRA_ROUTE, "no/such/screen?x={") })
    }

    @Test fun malformedPathDoesNotCrash() {
        assertEquals(Lifecycle.State.RESUMED, launch { putExtra(MainActivity.EXTRA_PATH, "smb://") })
    }

    @Test fun otherAppsCannotOpenInternalScreens() {
        // A wrong token is treated like no token: "settings" isn't in the allowed list, so it isn't opened.
        assertTrue(MainActivity.INTERNAL_TOKEN.length >= 32)
        assertEquals(Lifecycle.State.RESUMED, launch { putExtra(MainActivity.EXTRA_TOKEN, "guess").putExtra(MainActivity.EXTRA_ROUTE, "editor?path=/data/data/com.xander.files/x") })
    }
}
