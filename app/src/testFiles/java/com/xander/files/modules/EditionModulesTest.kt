package com.xander.files.modules

import android.content.Context
import android.content.pm.PackageInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xander.files.domain.fs.FileEntry
import com.xander.files.domain.fs.FsPath
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Xander Files alone: the separate apps appear only when installed, and then get their files. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class EditionModulesTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private fun entry(name: String) = FileEntry(FsPath.local("/sdcard/$name"), false)
    private fun install(pkg: String) = shadowOf(ctx.packageManager).installPackage(PackageInfo().apply { packageName = pkg })

    @Before fun setUp() { Modules.context = ctx }
    @After fun tearDown() { Modules.context = null }

    @Test fun withoutTheAppsOnlyTheBuiltInViewersShow() {
        assertEquals(listOf(Model3dModule, QuickLookModule), Modules.all)
        assertNull("books open in Xander's own viewer", Modules.defaultFor(entry("novel.epub")))
        assertNull(Modules.defaultFor(entry("game.gba")))
        assertEquals(Model3dModule, Modules.defaultFor(entry("part.stl")))
    }

    @Test fun installedAppsGetTheirFiles() {
        install("dev.oleander.app"); install("app.quinten"); install("dev.groovesmith.studio")
        assertEquals(listOf("studio", "oleander", "quinten", "model3d", "quicklook"), Modules.all.map { it.id })
        assertEquals("dev.oleander.app", (Modules.defaultFor(entry("game.gba")) as ExternalAppModule).packageName)
        assertEquals("app.quinten", (Modules.defaultFor(entry("novel.epub")) as ExternalAppModule).packageName)
        assertEquals("dev.groovesmith.studio", (Modules.defaultFor(entry("song.gsproj")) as ExternalAppModule).packageName)
        assertTrue(Modules.forFile(entry("clip.mp4")).any { it.id == "studio" })
        assertEquals(SharedIndex.ZipKind.GBA, Modules.byId("oleander")!!.zipKind)
    }

    // (That the Studio's web app is left out of this APK is checked on the APK itself: scripts/check-apks.sh.)
    @Test fun studioIsOnlyTheSeparateApp() {
        assertNull("not built in", Modules.byId("studio"))
        install("dev.groovesmith.studio")
        assertTrue(Modules.byId("studio") is ExternalAppModule)
    }
}
