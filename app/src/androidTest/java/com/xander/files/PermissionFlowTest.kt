package com.xander.files

import android.content.Intent
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end storage permission flow on a real device/emulator:
 * revoke access → onboarding appears → "Grant access" opens the system screen → grant → home screen.
 */
@RunWith(AndroidJUnit4::class)
class PermissionFlowTest {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instr)
    private val pkg = instr.targetContext.packageName
    private val res = instr.targetContext.resources

    private fun shell(cmd: String) = device.executeShellCommand(cmd)

    private fun setAccess(allow: Boolean) {
        if (Build.VERSION.SDK_INT >= 30) shell("appops set --uid $pkg MANAGE_EXTERNAL_STORAGE ${if (allow) "allow" else "default"}")
        else listOf("READ_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE").forEach { shell("pm ${if (allow) "grant" else "revoke"} $pkg android.permission.$it") }
    }

    private fun launch() {
        shell("am force-stop $pkg")
        val i = instr.targetContext.packageManager.getLaunchIntentForPackage(pkg)!!.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        instr.targetContext.startActivity(i)
        device.wait(Until.hasObject(By.pkg(pkg).depth(0)), 10_000)
    }

    @Before fun home() { device.pressHome() }

    @Test fun onboardingRequestsAccessAndOpensSystemScreen() {
        setAccess(false)
        launch()
        val grant = device.wait(Until.findObject(By.text(res.getString(R.string.grant_access))), 10_000)
        assertNotNull("Onboarding should ask for storage access", grant)
        grant.click()
        if (Build.VERSION.SDK_INT >= 30) {
            // The "All files access" settings page (or its package) must open.
            assertTrue(device.wait(Until.gone(By.text(res.getString(R.string.grant_access))), 10_000))
            assertTrue("System settings should be in front", device.currentPackageName != pkg)
        } else {
            // Runtime permission dialog from the package installer / permission controller.
            val allow = device.wait(Until.findObject(By.res("com.android.permissioncontroller:id/permission_allow_button")), 5_000)
                ?: device.wait(Until.findObject(By.res("com.android.packageinstaller:id/permission_allow_button")), 5_000)
            allow?.click()
        }
    }

    @Test fun grantedAccessShowsHomeWithStorage() {
        setAccess(true)
        launch()
        val storage = device.wait(Until.findObject(By.textContains(res.getString(R.string.quick_access))), 15_000)
            ?: device.wait(Until.findObject(By.textContains(res.getString(R.string.downloads))), 5_000)
        assertNotNull("Home screen should show storage shortcuts once access is granted", storage)
    }

    @Test fun restrictedFolderOffersPrivilegedAccess() {
        setAccess(true)
        launch()
        device.wait(Until.findObject(By.text("Android/data")), 15_000)?.click()
        // Either the folder lists (Shizuku/root/SAF available) or the restricted-folder explanation appears.
        val explained = device.wait(Until.findObject(By.text(res.getString(R.string.restricted_title))), 8_000)
        val listed = device.findObject(By.textContains("com."))
        assertTrue("Android/data must either list or explain how to unlock it", explained != null || listed != null)
    }
}
