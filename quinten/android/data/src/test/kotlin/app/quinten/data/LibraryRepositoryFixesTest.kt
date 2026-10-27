package app.quinten.data

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import app.quinten.formats.BookOpener
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/** Serves files like a file manager does for "Open with": content://test.books/doc/<n>. */
class TestBooksProvider : ContentProvider() {
    companion object {
        val files = HashMap<String, Pair<File, String>>()
        val denied = HashSet<String>()
    }
    override fun onCreate() = true
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val key = uri.lastPathSegment!!
        if (key in denied) throw SecurityException("Permission Denial")
        val f = files[key]?.first ?: throw java.io.FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val c = MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME))
        files[uri.lastPathSegment]?.let { c.addRow(arrayOf(it.second)) }
        return c
    }
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryRepositoryFixesTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val fixtures = File("../../core/formats/src/test/resources/fixtures")

    private fun repo(factory: DocumentFactory = DocumentFactory { f, fmt, _ -> BookOpener.open(f, fmt) }) =
        LibraryRepository(ctx, LibraryDatabase.create(ctx, inMemory = true), factory)

    private fun tempDir() = Files.createTempDirectory("books").toFile()

    @Before fun setUp() {
        TestBooksProvider.files.clear(); TestBooksProvider.denied.clear()
        Robolectric.setupContentProvider(TestBooksProvider::class.java, "test.books")
    }

    private fun serve(key: String, file: File, name: String): String {
        TestBooksProvider.files[key] = file to name
        return "content://test.books/doc/$key"
    }

    @Test fun openWithTemporaryPermissionKeepsTheExistingLocation() = runTest {
        val r = repo()
        val f = File(tempDir(), "voyage.epub").also { File(fixtures, "sample.epub").copyTo(it) }
        val id = r.addBook(f.absolutePath)!!
        r.savePosition(id, 0.3f, "1:0:0")
        r.persistPermission = { false }
        val uri = serve("57", f, "voyage.epub")
        assertEquals(id, r.addBook(uri))
        val b = r.book(id)!!
        assertEquals(f.absolutePath, b.location)
        assertEquals(0.3f, b.progress)
    }

    @Test fun openWithTemporaryPermissionCopiesANewBookIntoAppStorage() = runTest {
        val r = repo()
        r.persistPermission = { false }
        val src = File(tempDir(), "x.fb2").also { File(fixtures, "sample.fb2").copyTo(it) }
        val id = r.addBook(serve("58", src, "My Voyage.fb2"))!!
        val b = r.book(id)!!
        assertTrue(b.location, b.location.startsWith(File(ctx.filesDir, "imported").absolutePath))
        assertTrue(File(b.location).length() == src.length())
        assertEquals("My Voyage.fb2", b.fileName)
        assertFalse(b.missing)
        // Once the real file is found by a scan, it replaces the private copy.
        val dir = tempDir()
        src.copyTo(File(dir, "real.fb2"))
        r.addScanFolder(dir.absolutePath)
        r.scan()
        assertEquals(File(dir, "real.fb2").absolutePath, r.book(id)!!.location)
        assertFalse(File(b.location).exists())
        assertEquals(1, r.observeBooks().first().size)
    }

    @Test fun contentUriBooksDoNotGetNumericTitles() = runTest {
        val r = repo { f, fmt, _ ->
            // A parser falling back to the opened file's name (/proc/self/fd/<n>).
            val doc = BookOpener.open(f, fmt)
            object : app.quinten.model.BookDocument by doc {
                override val metadata = doc.metadata.copy(title = f.name)
            }
        }
        val src = File(tempDir(), "x.txt").apply { writeText("Hello there.\n\nA small story.\n") }
        val id = r.addBook(serve("59", src, "Harbour Tales.txt"))!!
        assertEquals("Harbour Tales", r.book(id)!!.title)
        assertNull(acceptableTitle("57", File("/proc/self/fd/57")))
        assertNull(acceptableTitle("  ", File("a.txt")))
        assertEquals("Real Title", acceptableTitle(" Real Title ", File("/proc/self/fd/57")))
    }

    @Test fun lostPermissionIsReportedAsFileNotFound() {
        val src = File(tempDir(), "a.txt").apply { writeText("text") }
        val uri = serve("60", src, "a.txt")
        TestBooksProvider.denied += "60"
        try {
            BookFiles.open(ctx, uri)
            fail()
        } catch (e: java.io.FileNotFoundException) {
            assertTrue(e is PermissionLostException)
            assertTrue(e.message!!.contains("Permission"))
        }
    }

    @Test fun metadataLoadingKeepsProgressSavedMeanwhile() = runTest {
        lateinit var r: LibraryRepository
        var interfere = false
        r = repo { f, fmt, id ->
            if (interfere) runBlocking { r.savePosition(id, 0.6f, "2:0:0"); r.setFavorite(id, true); r.setFinished(id, true) }
            BookOpener.open(f, fmt)
        }
        val f = File(tempDir(), "a.fb2").also { File(fixtures, "sample.fb2").copyTo(it) }
        val id = r.addBook(f.absolutePath)!!
        interfere = true
        r.loadMetadata(id)
        val b = r.book(id)!!
        assertEquals(0.6f, b.progress)
        assertTrue(b.favorite)
        assertTrue(b.finishedAt > 0)
        assertTrue(b.metadataLoaded)
    }

    @Test fun unavailableFolderDoesNotMarkBooksMissingAndSeenBooksComeBack() = runTest {
        val r = repo()
        val parent = tempDir()
        val dir = File(parent, "card").apply { mkdirs() }
        File(fixtures, "sample.epub").copyTo(File(dir, "a.epub"))
        r.addScanFolder(dir.absolutePath)
        r.scan()
        val id = r.observeBooks().first().single().id
        // The "SD card" is unmounted: the folder is gone, but that says nothing about the book.
        val away = File(parent, "away")
        assertTrue(dir.renameTo(away))
        r.scan()
        assertFalse(r.book(id)!!.missing)
        // Flagged missing by the reader, then seen again by a scan: present again.
        away.renameTo(dir)
        r.markMissing(id)
        r.scan()
        assertFalse(r.book(id)!!.missing)
    }

    @Test fun sameFileAtTwoAddressesKeepsOneLocation() = runTest {
        val r = repo()
        val dir = tempDir()
        File(fixtures, "sample.epub").copyTo(File(dir, "a/one.epub"))
        File(fixtures, "sample.epub").copyTo(File(dir, "b/two.epub"))
        r.addScanFolder(dir.absolutePath)
        r.scan()
        val first = r.observeBooks().first().single()
        repeat(2) {
            r.scan()
            assertEquals(first.location, r.book(first.id)!!.location)
        }
        assertEquals(1, r.observeBooks().first().size)
    }

    @Test fun collectionNamesAreValidated() = runTest {
        val r = repo()
        val a = r.createCollection("Poetry")
        val b = r.createCollection("Plays")
        assertEquals(CollectionNameResult.DUPLICATE, r.renameCollection(b, "Poetry"))
        assertEquals(CollectionNameResult.DUPLICATE, r.renameCollection(b, " poetry "))
        assertEquals(CollectionNameResult.BLANK, r.renameCollection(b, "  "))
        assertEquals(CollectionNameResult.OK, r.renameCollection(a, "poetry"))
        assertEquals(listOf("Plays", "poetry"), r.observeCollections().first().map { it.name })
        assertEquals(a, r.createCollection("POETRY"))
    }

    @Test fun fingerprintRejectsEmptyFiles() {
        val f = File(tempDir(), "empty.epub").apply { writeBytes(ByteArray(0)) }
        try { BookFiles.fingerprint(f); fail() } catch (_: java.io.IOException) {}
        // Unchanged for normal files (book identity).
        val g = File(tempDir(), "x.bin").apply { writeBytes(ByteArray(10) { it.toByte() }) }
        assertEquals("a-", BookFiles.fingerprint(g).take(2))
    }

    @Test fun explicitAddUnhidesForgetDeletesAndFolderRemovalHides() = runTest {
        val r = repo()
        val dir = tempDir()
        val f = File(dir, "a.djvu").also { File(fixtures, "sample.djvu").copyTo(it) }
        val id = r.addBook(f.absolutePath)!!
        r.removeFromLibrary(id)
        assertEquals(id, r.addBook(f.absolutePath))
        assertFalse(r.book(id)!!.hidden)

        r.addScanFolder(dir.absolutePath)
        r.removeScanFolder(dir.absolutePath)
        assertTrue(r.book(id)!!.hidden)

        val cover = r.book(id)!!.coverPath
        assertNotNull(cover)
        // Reloading writes a cover under a new name and removes the old one.
        r.loadMetadata(id)
        val cover2 = r.book(id)!!.coverPath!!
        assertNotEquals(cover, cover2)
        assertFalse(File(cover!!).exists())
        r.ocrCacheDir(id).apply { mkdirs(); File(this, "p0").writeText("x") }
        r.forgetBook(id)
        assertNull(r.book(id))
        assertFalse(File(cover2).exists())
        assertFalse(r.ocrCacheDir(id).exists())
        assertTrue(f.exists())
    }

    @Test fun exportAllNotesIncludesBookmarksAndHighlights() = runTest {
        val r = repo()
        val f = File(tempDir(), "a.fb2").also { File(fixtures, "sample.fb2").copyTo(it) }
        val id = r.addBook(f.absolutePath)!!
        r.addBookmark(BookmarkEntity(bookId = id, position = "0:1:0", progress = 0.1f, snippet = "harbour"))
        r.addHighlight(HighlightEntity(bookId = id, range = "1:2:0-1:2:5", progress = 0.5f, text = "later", color = 0))
        r.addHighlight(HighlightEntity(bookId = id, range = "1:0:0-1:0:5", progress = 0.2f, text = "earlier", color = 0, note = "n"))
        val all = r.exportAllNotes()
        assertTrue(all, all.contains("## Bookmarks") || all.contains("### Bookmarks"))
        assertTrue(all.contains("harbour"))
        assertTrue(all.indexOf("earlier") < all.indexOf("later"))
        assertTrue(all.contains("Note: n"))
    }

    @Test fun documentIdsMapToFilePaths() {
        assertEquals("/storage/emulated/0/Books/a.epub", BookFiles.pathFromDocumentId("com.android.externalstorage.documents", "primary:Books/a.epub"))
        assertEquals("/storage/1234-ABCD/a.epub", BookFiles.pathFromDocumentId("com.android.externalstorage.documents", "1234-ABCD:a.epub"))
        assertEquals("/storage/emulated/0/Download/a.pdf", BookFiles.pathFromDocumentId("com.android.providers.downloads.documents", "raw:/storage/emulated/0/Download/a.pdf"))
        assertNull(BookFiles.pathFromDocumentId("com.other", "42"))
    }
}
