package app.quinten.data

import androidx.test.core.app.ApplicationProvider
import app.quinten.formats.BookOpener
import app.quinten.formats.util.ZippedBook
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** The library scan finds books inside .zip files and skips zips without one. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ZippedBookScanTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun scanFindsZippedBooks() = runTest {
        val root = Files.createTempDirectory("lib").toFile()
        fun zip(name: String, entry: String, body: String) = File(root, name).apply {
            ZipOutputStream(outputStream()).use { z -> z.putNextEntry(ZipEntry(entry)); z.write(body.toByteArray()); z.closeEntry() }
        }
        zip("story.zip", "story.txt", "The Story\n\nIt was a quiet morning in the valley.\n")
        zip("backup.zip", "settings.json", "{}")
        val r = LibraryRepository(ctx, LibraryDatabase.create(ctx, inMemory = true)) { f, fmt, _ -> BookOpener.open(ZippedBook.unwrap(f, ctx.cacheDir, fmt), fmt) }
        r.addScanFolder(root.absolutePath)
        r.scan()
        val books = r.observeBooks().first()
        assertEquals(listOf("story.zip" to "TXT"), books.map { it.fileName to it.format })
    }
}
