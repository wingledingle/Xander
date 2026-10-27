package app.quinten.data

import androidx.test.core.app.ApplicationProvider
import app.quinten.formats.BookOpener
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryRepositoryTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val fixtures = File("../../core/formats/src/test/resources/fixtures")

    private fun repo() = LibraryRepository(ctx, LibraryDatabase.create(ctx, inMemory = true)) { f, fmt, _ -> BookOpener.open(f, fmt) }

    @Test fun scanAddsBooksWithMetadataAndHandlesMovesAndMissing() = runTest {
        val dir = Files.createTempDirectory("books").toFile()
        File(dir, "sub").mkdirs()
        listOf("sample.epub", "sample.fb2", "sample.docx", "sample.djvu", "sample.cbz").map { File(fixtures, it) }.forEach { it.copyTo(File(dir, "sub/" + it.name)) }
        File(dir, "notes.xyz").writeText("not a book")
        File(dir, ".hidden").mkdirs()
        val r = repo()
        r.addScanFolder(dir.absolutePath)
        r.scan()
        val books = r.observeBooks().first()
        assertEquals(5, books.size)
        val epub = books.single { it.format == "EPUB" }
        assertEquals("The Test Voyage", epub.title)
        assertEquals(listOf("Ada Example"), epub.authorList)
        assertTrue(epub.metadataLoaded)
        // Fixed-layout books get their first page as cover.
        val djvu = books.single { it.format == "DJVU" }
        assertNotNull(djvu.coverPath)
        assertTrue(File(djvu.coverPath!!).length() > 0)

        // Reading position and notes survive a move/rename (same content).
        r.savePosition(epub.id, 0.42f, "1:3:0")
        val moved = File(dir, "renamed.epub")
        File(epub.location).renameTo(moved)
        r.scan()
        val after = r.book(epub.id)!!
        assertEquals(moved.absolutePath, after.location)
        assertEquals(0.42f, after.progress)
        assertFalse(after.missing)
        assertEquals(5, r.observeBooks().first().size)

        // Deleted outside the app: flagged missing, notes kept.
        r.addHighlight(HighlightEntity(bookId = epub.id, range = "1:3:0-1:3:5", progress = 0.4f, text = "bright", color = 1, note = "nice"))
        moved.delete()
        r.scan()
        assertTrue(r.book(epub.id)!!.missing)
        assertEquals(1, r.observeHighlights(epub.id).first().size)
    }

    @Test fun scanFindsHundredsOfBooksInDeepAndUnreadableTrees() = runTest {
        val root = Files.createTempDirectory("device").toFile()
        val txt = File(root, "seed.tmp").apply { writeText("A Small Book\n\nIt was a quiet morning in the harbour town.\n") }
        // 300 books spread over many folders, some 20 levels deep (old limit was 14).
        var deep = File(root, "Books")
        repeat(20) { deep = File(deep, "level$it") }
        deep.mkdirs()
        for (i in 0 until 300) {
            val d = if (i % 3 == 0) deep else File(root, "Download/author${i % 37}")
            d.mkdirs()
            File(d, "book$i.txt").writeText("" + txt.readText() + "\nCopy number $i of the test book.\n")
        }
        File(root, "Android/data/com.other/files").mkdirs()
        txt.copyTo(File(root, "Android/data/com.other/files/skip.txt"))
        val locked = File(root, "locked").apply { mkdirs() }
        txt.copyTo(File(locked, "x.txt"))
        locked.setReadable(false); locked.setExecutable(false)
        // Symlink loop must not hang the scan.
        runCatching { Files.createSymbolicLink(File(root, "Books/loop").toPath(), root.toPath()) }
        val r = repo()
        try { r.scan(listOf(root)) } finally { locked.setReadable(true); locked.setExecutable(true) }
        val books = r.observeBooks().first()
        assertTrue("found ${books.size}", books.size >= 300)
        assertTrue(books.none { "Android/data" in it.location })
        assertTrue(r.scanState.value.folders > 20)
    }

    @Test fun collectionsBookmarksHistoryPrefs() = runTest {
        val r = repo()
        val f = File(Files.createTempDirectory("b").toFile(), "a.fb2").also { File(fixtures, "sample.fb2").copyTo(it) }
        val id = r.addBook(f.absolutePath)!!
        assertEquals(id, r.addBook(f.absolutePath)) // idempotent
        val c = r.createCollection("  Favourites ")
        assertEquals(c, r.createCollection("Favourites"))
        r.addToCollection(id, c)
        assertEquals(1, r.observeCollections().first().single().count)
        assertEquals(listOf(id), r.observeCollectionBooks(c).first().map { it.id })
        r.removeFromLibrary(id)
        assertEquals(0, r.observeCollections().first().single().count)
        r.restoreToLibrary(id)

        r.addBookmark(BookmarkEntity(bookId = id, position = "0:1:0", progress = 0.1f, snippet = "x"))
        assertEquals(1, r.observeBookmarks(id).first().size)

        val s = r.startSession(id, 0.1f)
        r.endSession(s, 0.3f)
        val h = r.observeHistory().first().single()
        assertEquals(0.3f, h.endProgress)

        r.savePrefs(id, BookPrefs(cleanMode = true, restoredRemovals = setOf("p:1:2"), disabledCategories = setOf("WATERMARK")))
        val p = r.prefs(id)
        val opts = p.cleanOptions(false)
        assertTrue(opts.enabled)
        assertEquals(setOf("p:1:2"), opts.restoredIds)
        assertEquals(setOf(app.quinten.model.RemovalCategory.WATERMARK), opts.disabledCategories)

        r.addHighlight(HighlightEntity(bookId = id, range = "1:0:0-1:0:5", progress = 0.2f, text = "It was", color = 0, note = "start"))
        val export = r.exportNotes(id)
        assertTrue(export.contains("“It was”") && export.contains("Note: start"))
        assertEquals(1, r.search("Voyage").first().size)
    }
}
