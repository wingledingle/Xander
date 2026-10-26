package com.xander.files.features

import androidx.activity.ComponentActivity
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xander.files.data.analyzer.ArchiveCopyFinder
import com.xander.files.domain.fs.FileCategory
import com.xander.files.domain.fs.FileEntry
import com.xander.files.domain.fs.FsPath
import com.xander.files.ui.viewers.SlideshowEffect
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class NewFeaturesTest {
    @get:Rule val tmp = TemporaryFolder()
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun walk(root: File): List<FileEntry> = root.walkTopDown().filter { it.isFile }.map { FileEntry(FsPath.local(it.path), false, size = it.length(), lastModified = it.lastModified()) }.toList()
    private fun zip(out: File, files: Map<String, String>) = ZipOutputStream(out.outputStream()).use { z -> files.forEach { (n, c) -> z.putNextEntry(ZipEntry(n)); z.write(c.toByteArray()); z.closeEntry() } }

    @Test fun archiveCopiesFindsExtractedFoldersOnly() = runBlocking {
        val root = tmp.newFolder("dl")
        val content = mapOf("Album/a.jpg" to "aaaa", "Album/b.jpg" to "bbbbbb", "Album/sub/c.txt" to "cc")
        // 1) zip extracted into its single top-level folder
        zip(File(root, "album.zip"), content)
        content.forEach { (n, c) -> File(root, n).apply { parentFile!!.mkdirs(); writeText(c) } }
        // 2) zip extracted into a folder named after the archive (no top-level folder inside)
        zip(File(root, "photos.zip"), mapOf("x.png" to "xxxxx", "y.png" to "yy"))
        File(root, "photos").mkdirs(); File(root, "photos/x.png").writeText("xxxxx"); File(root, "photos/y.png").writeText("yy")
        // 3) 7z extracted next to it
        SevenZOutputFile(File(root, "docs.7z")).use { z -> listOf("d1.txt" to "hello", "d2.txt" to "world!").forEach { (n, c) ->
            val f = File(tmp.root, n).apply { writeText(c) }; z.putArchiveEntry(z.createArchiveEntry(f, n)); z.write(f.readBytes()); z.closeArchiveEntry() } }
        File(root, "d1.txt").writeText("hello"); File(root, "d2.txt").writeText("world!")
        // 4) not extracted: sizes differ / files missing
        zip(File(root, "other.zip"), mapOf("q.bin" to "123456789", "r.bin" to "1"))
        File(root, "other").mkdirs(); File(root, "other/q.bin").writeText("different-size-content")

        val found = ArchiveCopyFinder.find(walk(root), emptyMap()).associate { it.archive.name to it.folder.path.removePrefix(root.path) }
        assertEquals(mapOf("album.zip" to "/Album", "photos.zip" to "/photos", "docs.7z" to ""), found)
    }

    @Test fun gamesAreTheirOwnCategoryWithoutStealingOtherTypes() {
        listOf("Zelda.sfc", "pokemon.gba", "mario.nes", "game.nsp", "gt.chd", "save.srm", "Crash.pbp", "disc.cue").forEach {
            assertEquals(it, FileCategory.GAME, FileCategory.of(it))
        }
        assertEquals(FileCategory.TEXT, FileCategory.of("README.md"))
        assertEquals(FileCategory.ARCHIVE, FileCategory.of("backup.iso"))
        assertEquals(FileCategory.IMAGE, FileCategory.of("photo.jpg"))
        assertEquals(FileCategory.APK, FileCategory.of("app.apk"))
    }

    @Test fun slideshowAdvancesThroughEveryImageAndStops() {
        lateinit var pager: PagerState
        var running by mutableStateOf(true)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            pager = rememberPagerState { 4 }
            HorizontalPager(pager) { Text("page $it") }
            SlideshowEffect(pager, 4, running, intervalMs = 1000) { running = false }
        }
        // Each step: 1 s wait + 600 ms swipe. The old bug left the pager stuck between pages.
        for (expected in 1..3) {
            compose.mainClock.advanceTimeBy(1000 + 700)
            compose.waitForIdle()
            assertEquals(expected, pager.settledPage)
            assertEquals(0f, pager.currentPageOffsetFraction, 0.001f)
        }
        compose.mainClock.advanceTimeBy(1100); compose.waitForIdle()
        assertFalse(running)
        assertEquals(3, pager.settledPage)
        assertTrue(true)
    }
}
