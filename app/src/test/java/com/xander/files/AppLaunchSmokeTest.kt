package com.xander.files

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Starts the real activity with the real Hilt graph to catch wiring/startup crashes on the JVM. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AppLaunchSmokeTest {
    @Test
    fun mainActivityStarts() {
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            s.onActivity { a -> check(!a.isFinishing) }
        }
    }
}
