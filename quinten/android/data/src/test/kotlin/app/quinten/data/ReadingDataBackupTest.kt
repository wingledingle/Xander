package app.quinten.data

import androidx.test.core.app.ApplicationProvider
import app.quinten.formats.BookOpener
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReadingDataBackupTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val fixtures = File("../../core/formats/src/test/resources/fixtures")

    private fun repo() = LibraryRepository(ctx, LibraryDatabase.create(ctx, inMemory = true)) { f, fmt, _ -> BookOpener.open(f, fmt) }

    @Test fun backupRestoresOnAnotherPhoneEvenBeforeTheBooksAreFound() = runTest {
        File(ctx.filesDir, "pending_restore.json").delete()
        val dirA = Files.createTempDirectory("a").toFile()
        val epubA = File(fixtures, "sample.epub").copyTo(File(dirA, "book.epub"))
        val a = repo()
        val idA = a.addBook(epubA.absolutePath)!!
        a.savePosition(idA, 0.37f, "1:5:12")
        a.addBookmark(BookmarkEntity(bookId = idA, position = "1:3:0", progress = 0.3f, snippet = "a mark"))
        a.addHighlight(HighlightEntity(bookId = idA, range = "1:3:0-1:3:9", progress = 0.3f, text = "bright sea", color = 1, note = "lovely"))
        a.setFavorite(idA, true)
        val col = a.createCollection("Holiday")
        a.addToCollection(idA, col)
        val backup = a.exportReadingData()

        // Phone B: restore first (books not found yet), then the scan finds the same file elsewhere.
        val b = repo()
        val r = b.importReadingData(backup)
        assertEquals(0, r.restored)
        assertEquals(1, r.waiting)
        val dirB = Files.createTempDirectory("b").toFile()
        val epubB = File(fixtures, "sample.epub").copyTo(File(dirB, "renamed on phone B.epub"))
        val idB = b.addBook(epubB.absolutePath)!!
        val book = b.book(idB)!!
        assertEquals(0.37f, book.progress)
        assertEquals("1:5:12", book.position)
        assertTrue(book.favorite)
        assertEquals(listOf("1:3:0"), b.db.annotations().bookmarks(idB).map { it.position })
        assertEquals("lovely", b.db.annotations().highlights(idB).single().note)
        assertTrue(b.db.collections().observeAll().first().any { it.name == "Holiday" })
        // Restoring the same backup again changes nothing (no duplicates).
        b.importReadingData(backup)
        assertEquals(1, b.db.annotations().bookmarks(idB).size)
        assertEquals(1, b.db.annotations().highlights(idB).size)
    }
}
