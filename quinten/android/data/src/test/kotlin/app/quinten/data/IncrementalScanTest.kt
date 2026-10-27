package app.quinten.data

import androidx.test.core.app.ApplicationProvider
import app.quinten.formats.BookOpener
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/** Rescans remember what was already scanned: unchanged folders are not listed again, new books are still found. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IncrementalScanTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val prefs get() = ctx.getSharedPreferences("quinten-scan", android.content.Context.MODE_PRIVATE)
    private fun repo(db: LibraryDatabase) = LibraryRepository(ctx, db) { f, fmt, _ -> BookOpener.open(f, fmt) }
    private fun book(dir: File, name: String, n: Int) = File(dir, name).apply { parentFile!!.mkdirs(); writeText("Book $n\n\nIt was a quiet morning, copy $n.\n") }

    @Before fun clean() { prefs.edit().clear().commit() }

    @Test fun quickRescanSkipsUnchangedFoldersAndFindsNewBooks() = runTest {
        val root = Files.createTempDirectory("lib").toFile()
        for (a in 0 until 10) for (b in 0 until 3) book(File(root, "author$a/series$b"), "vol.txt", a * 10 + b)
        val db = LibraryDatabase.create(ctx, inMemory = true)
        val r = repo(db)
        r.addScanFolder(root.absolutePath)

        r.scan()
        val first = r.scanState.value
        assertTrue("the first scan examines everything", first.full)
        assertEquals(30, first.added)
        assertEquals(41, first.folders) // root + 10 authors + 30 series

        r.scan()
        val second = r.scanState.value
        assertFalse(second.full)
        assertEquals(0, second.added)
        assertEquals(30, second.found)
        assertEquals("nothing changed, so no folder is listed again", 41, second.unchanged)

        Thread.sleep(20) // folder dates have millisecond resolution
        book(File(root, "author3/series1"), "vol2.txt", 999)
        book(File(root, "author9/new-series"), "vol.txt", 1000)
        r.scan()
        val third = r.scanState.value
        assertEquals(2, third.added)
        assertEquals(32, third.found)
        assertEquals(42, third.folders) // one new folder
        // Listed again: series1 (new file), author9 (new subfolder) and the new folder itself.
        assertEquals("only the folders that changed are listed again", 42 - 3, third.unchanged)
        assertEquals(32, r.observeBooks().first().size)

        r.scan(full = true)
        val full = r.scanState.value
        assertTrue(full.full)
        assertEquals(0, full.unchanged)
        assertEquals(0, full.added)
        assertEquals(32, full.found)
    }

    @Test fun deletedFoldersAndBooksAreNoticed() = runTest {
        val root = Files.createTempDirectory("lib").toFile()
        val keep = book(File(root, "a"), "keep.txt", 1)
        val gone = book(File(root, "b"), "gone.txt", 2)
        val r = repo(LibraryDatabase.create(ctx, inMemory = true))
        r.addScanFolder(root.absolutePath)
        r.scan()
        assertEquals(2, r.observeBooks().first().size)
        Thread.sleep(20)
        gone.parentFile!!.deleteRecursively()
        r.scan()
        val books = r.observeBooks().first()
        assertTrue(books.single { it.location == gone.absolutePath }.missing)
        assertFalse(books.single { it.location == keep.absolutePath }.missing)
    }

    @Test fun aWeekAfterTheLastFullScanTheNextScanIsFull() = runTest {
        val root = Files.createTempDirectory("lib").toFile()
        book(File(root, "a"), "x.txt", 1)
        val r = repo(LibraryDatabase.create(ctx, inMemory = true))
        r.addScanFolder(root.absolutePath)
        r.scan()
        r.scan()
        assertFalse(r.scanState.value.full)
        prefs.edit().putLong("lastFullScan", System.currentTimeMillis() - LibraryRepository.FULL_SCAN_EVERY_MS - 1000).commit()
        r.scan()
        assertTrue(r.scanState.value.full)
    }
}
