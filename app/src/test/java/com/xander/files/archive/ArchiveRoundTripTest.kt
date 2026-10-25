package com.xander.files.archive

import com.xander.files.data.archive.ArchiveFormat
import com.xander.files.data.archive.ArchiveFormats
import com.xander.files.data.archive.ArchivePasswordException
import com.xander.files.data.archive.ArchiveReaders
import com.xander.files.data.archive.ArchiveWriter
import com.xander.files.data.archive.OutFormat
import com.xander.files.domain.fs.FileSystemRegistry
import com.xander.files.domain.fs.FsPath
import com.xander.files.fs.MemoryFileSystemProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ArchiveRoundTripTest {
    @get:Rule val tmp = TemporaryFolder()

    private val mem = MemoryFileSystemProvider("mem").apply {
        put("/docs/a.txt", "alpha")
        put("/docs/sub/b.txt", "bravo".repeat(1000))
        put("/single.txt", "single")
    }
    private val writer = ArchiveWriter(FileSystemRegistry(setOf(mem)))

    private suspend fun pack(format: OutFormat, password: String? = null, name: String = "out.${format.ext}"): File {
        val items = writer.collect(listOf(FsPath("mem", "", "/docs")))
        val f = tmp.newFile(name)
        f.outputStream().use { writer.write(items, format, it, password, 6, tmp.newFile(), {}, {}) }
        return f
    }

    private fun readAll(f: File, password: String? = null): Map<String, String> =
        ArchiveReaders.open(f, f.name, password?.toCharArray()).use { r ->
            r.items().filter { !it.isDirectory }.associate { it.path to r.open(it.path).use { s -> String(s.readBytes()) } }
        }

    private fun assertDocs(m: Map<String, String>) {
        assertEquals("alpha", m["/docs/a.txt"])
        assertEquals("bravo".repeat(1000), m["/docs/sub/b.txt"])
    }

    @Test fun zip() = runTest { assertDocs(readAll(pack(OutFormat.ZIP))) }
    @Test fun sevenZip() = runTest { assertDocs(readAll(pack(OutFormat.SEVEN_Z))) }
    @Test fun tar() = runTest { assertDocs(readAll(pack(OutFormat.TAR))) }
    @Test fun tarGz() = runTest { assertDocs(readAll(pack(OutFormat.TAR_GZ))) }
    @Test fun tarBz2() = runTest { assertDocs(readAll(pack(OutFormat.TAR_BZ2))) }

    @Test fun encryptedZipNeedsCorrectPassword() = runTest {
        val f = pack(OutFormat.ZIP, password = "s3cret")
        assertTrue(ArchiveReaders.open(f, f.name, null).use { r -> r.items().any { it.encrypted } })
        try { readAll(f, null); fail("expected password prompt") } catch (e: ArchivePasswordException) { assertTrue(!e.wrong) }
        try { readAll(f, "nope"); fail("expected wrong password") } catch (e: Exception) { assertTrue(e is ArchivePasswordException || e.message.orEmpty().contains("password", true)) }
        assertDocs(readAll(f, "s3cret"))
    }

    @Test fun streamFormatsSinglePass() = runTest {
        val f = pack(OutFormat.TAR_GZ)
        val seen = HashMap<String, Int>()
        ArchiveReaders.open(f, f.name, null).use { r -> r.forEach { item, s -> seen[item.path] = s.readBytes().size } }
        assertEquals(5, seen["/docs/a.txt"]); assertEquals(5000, seen["/docs/sub/b.txt"])
    }

    @Test fun plainGzipExposesOneFile() {
        val f = tmp.newFile("notes.txt.gz")
        java.util.zip.GZIPOutputStream(f.outputStream()).use { it.write("hello gz".toByteArray()) }
        val m = readAll(f)
        assertEquals(mapOf("/notes.txt" to "hello gz"), m)
    }

    @Test fun detectsFormats() {
        assertEquals(ArchiveFormat.TAR_GZ, ArchiveFormats.detect("x.tar.gz", null))
        assertEquals(ArchiveFormat.TAR_BZ2, ArchiveFormats.detect("x.tbz2", null))
        assertEquals(ArchiveFormat.ZIP, ArchiveFormats.detect("game.obb", byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 3, 4)))
        assertEquals(ArchiveFormat.SEVEN_Z, ArchiveFormats.detect("misnamed.zip", byteArrayOf('7'.code.toByte(), 'z'.code.toByte(), 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C)))
        assertTrue(ArchiveFormats.isArchiveName("a.zip"))
        assertTrue(!ArchiveFormats.isArchiveName("book.epub"))
        assertTrue(!ArchiveFormats.isArchiveName("app.apk"))
        assertEquals("/a/b", ArchiveFormats.norm("./a\\b/"))
    }
}
