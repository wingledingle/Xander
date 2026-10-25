package com.xander.files

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xander.files.data.analyzer.JunkKind
import com.xander.files.data.analyzer.StorageAnalyzer
import com.xander.files.data.storage.StorageVolumes
import com.xander.files.domain.fs.FileCategory
import com.xander.files.domain.fs.FileSystemRegistry
import com.xander.files.domain.fs.FsPath
import com.xander.files.fs.MemoryFileSystemProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AnalyzerTest {
    @Test fun findsSizesDuplicatesJunkAndEmptyFolders() = runBlocking {
        val mem = MemoryFileSystemProvider("mem").apply {
            put("/Photos/a.jpg", "x".repeat(4000))
            put("/Photos/copy/a-copy.jpg", "x".repeat(4000))
            put("/Docs/report.pdf", "y".repeat(9000))
            put("/Docs/unique.txt", "z".repeat(3000))
            put("/tmp/session.tmp", "t")
            put("/logs/app.log", "l".repeat(50))
            dirs.add("/Empty")
        }
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val a = StorageAnalyzer(ctx, FileSystemRegistry(setOf(mem)), StorageVolumes(ctx))
        val r = a.analyze(FsPath("mem", "", "/"))
        assertEquals(6, r.files)
        assertEquals(4000L * 2 + 9000 + 3000 + 1 + 50, r.totalBytes)
        assertEquals("report.pdf", r.largest.first().name)
        assertEquals(1, r.duplicates.size)
        assertEquals(setOf("a.jpg", "a-copy.jpg"), r.duplicates.first().files.map { it.name }.toSet())
        assertEquals(4000L, r.duplicates.first().wasted)
        assertTrue(r.junk.any { it.kind == JunkKind.TEMP && it.entry.name == "session.tmp" })
        assertTrue(r.junk.any { it.kind == JunkKind.LOG })
        assertEquals(listOf("/Empty"), r.emptyFolders.map { it.path.path })
        assertEquals(8000L, r.folderTree[FsPath("mem", "", "/Photos")]!!.size)
        assertEquals(2 * 4000L, r.categories.first { it.category == FileCategory.IMAGE }.size)
    }
}
