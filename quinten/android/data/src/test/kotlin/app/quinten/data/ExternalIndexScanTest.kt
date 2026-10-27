package app.quinten.data

import androidx.test.core.app.ApplicationProvider
import app.quinten.formats.BookOpener
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/** Inside Xander the storage volumes come from the host's file index: those books are found without walking them. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExternalIndexScanTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    @After fun reset() { ExternalBookIndex.provider = null }

    @Test fun storageRootsAreReadFromTheIndex() = runTest {
        val volume = Files.createTempDirectory("vol").toFile()
        val indexed = File(volume, "Books/a.txt").apply { parentFile!!.mkdirs(); writeText("Indexed book\n\nSome text.\n") }
        File(volume, "Other/b.txt").apply { parentFile!!.mkdirs(); writeText("Not in the index\n\nMore text.\n") }
        ExternalBookIndex.provider = { listOf(indexed) }
        val r = LibraryRepository(ctx, LibraryDatabase.create(ctx, inMemory = true)) { f, fmt, _ -> BookOpener.open(f, fmt) }
        r.scan(listOf(volume))
        assertEquals("only the indexed book: the volume itself was not walked", listOf(indexed.absolutePath), r.observeBooks().first().map { it.location })
    }
}
