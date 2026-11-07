package com.xander.files.crypto

import com.xander.files.data.archive.EncryptOperation
import com.xander.files.data.transfer.ConflictBroker
import com.xander.files.data.transfer.CopyEngine
import com.xander.files.data.transfer.TransferContext
import com.xander.files.data.transfer.TransferOptions
import com.xander.files.domain.fs.FileSystemProvider
import com.xander.files.domain.fs.FileSystemRegistry
import com.xander.files.domain.fs.FsPath
import com.xander.files.fs.MemoryFileSystemProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FilterOutputStream
import java.io.OutputStream

class EncryptOperationTest {
    private val mem = MemoryFileSystemProvider("mem")
    private fun ctx() = TransferContext("t", TransferOptions(password = "pw", deleteOriginal = true), ConflictBroker()) {}
    private fun p(s: String) = FsPath("mem", "", s)

    @Test fun encryptsThenDeletesTheOriginalOnlyAfterVerifying() = runTest {
        mem.put("/a/secret.txt", "patient record")
        val reg = FileSystemRegistry(setOf(mem))
        EncryptOperation(reg, CopyEngine(reg)).run(listOf(p("/a/secret.txt")), null, ctx())
        assertNull(mem.read("/a/secret.txt"))
        assertTrue(mem.read("/a/secret.txt.xenc") != null)
    }

    // Storage that silently loses the end of what's written: the encrypted copy is broken, so the original must stay.
    @Test fun keepsTheOriginalWhenTheEncryptedCopyIsBroken() = runTest {
        mem.put("/a/secret.txt", "patient record")
        val lossy = object : FileSystemProvider by mem {
            override suspend fun openOutput(path: FsPath, size: Long): OutputStream {
                val real = mem.openOutput(path, size)
                return object : FilterOutputStream(real) {
                    var n = 0
                    override fun write(b: Int) { if (n++ < 60) real.write(b) }
                    override fun write(b: ByteArray, off: Int, len: Int) { for (i in off until off + len) write(b[i].toInt()) }
                }
            }
        }
        val reg = FileSystemRegistry(setOf(lossy))
        val r = runCatching { EncryptOperation(reg, CopyEngine(reg)).run(listOf(p("/a/secret.txt")), null, ctx()) }
        assertTrue("reported as a failure", r.isFailure)
        assertEquals("original kept", "patient record", mem.read("/a/secret.txt"))
    }
}
