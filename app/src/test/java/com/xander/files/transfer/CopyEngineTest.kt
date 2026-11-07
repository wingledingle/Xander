package com.xander.files.transfer

import com.xander.files.data.transfer.ConflictBroker
import com.xander.files.data.transfer.ConflictChoice
import com.xander.files.data.transfer.CopyEngine
import com.xander.files.data.transfer.TransferContext
import com.xander.files.data.transfer.TransferOptions
import com.xander.files.domain.fs.FileSystemRegistry
import com.xander.files.domain.fs.FsException
import com.xander.files.domain.fs.FsPath
import com.xander.files.fs.MemoryFileSystemProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CopyEngineTest {
    private lateinit var a: MemoryFileSystemProvider
    private lateinit var b: MemoryFileSystemProvider
    private lateinit var engine: CopyEngine
    private val broker = ConflictBroker()

    @Before fun setUp() {
        a = MemoryFileSystemProvider("mema")
        b = MemoryFileSystemProvider("memb")
        engine = CopyEngine(FileSystemRegistry(setOf(a, b)))
        a.put("/src/one.txt", "1111")
        a.put("/src/sub/two.txt", "22")
        b.dirs.add("/dst")
    }

    private fun ctx(opts: TransferOptions = TransferOptions()) = TransferContext("t", opts, broker) {}
    private fun pa(p: String) = FsPath("mema", "", p)
    private fun pb(p: String) = FsPath("memb", "", p)

    @Test fun copiesFolderTreeAcrossBackends() = runTest {
        engine.transfer(listOf(pa("/src")), pb("/dst"), move = false, ctx = ctx())
        assertEquals("1111", b.read("/dst/src/one.txt"))
        assertEquals("22", b.read("/dst/src/sub/two.txt"))
        assertEquals("1111", a.read("/src/one.txt"))
    }

    @Test fun moveAcrossBackendsDeletesSource() = runTest {
        engine.transfer(listOf(pa("/src")), pb("/dst"), move = true, ctx = ctx())
        assertEquals("22", b.read("/dst/src/sub/two.txt"))
        assertNull(a.read("/src/one.txt"))
        assertFalse("/src" in a.dirs)
    }

    @Test fun moveOnSameBackendUsesServerSideRename() = runTest {
        a.dirs.add("/other")
        engine.transfer(listOf(pa("/src")), pa("/other"), move = true, ctx = ctx())
        assertEquals(1, a.internalMoves)
        assertEquals("22", a.read("/other/src/sub/two.txt"))
    }

    @Test fun preservesModificationTime() = runTest {
        engine.transfer(listOf(pa("/src/one.txt")), pb("/dst"), move = false, ctx = ctx())
        assertEquals(1000L, b.mtimes["/dst/one.txt"])
    }

    // A resumed move must not trust "same size" alone: a different file of the same size (or a half-written copy
    // the destination preallocated to full size) would lose the source.
    @Test fun resumedMoveKeepsSourceWhenSameSizeFileDiffers() = runTest {
        b.put("/dst/one.txt", "9999")
        engine.transfer(listOf(pa("/src/one.txt")), pb("/dst"), move = true, ctx = ctx(TransferOptions(resumed = true, defaultConflict = ConflictChoice.SKIP)))
        assertEquals("1111", a.read("/src/one.txt"))
        assertEquals("9999", b.read("/dst/one.txt"))
    }

    @Test fun resumedMoveFinishesWhenCopyIsIdentical() = runTest {
        b.put("/dst/one.txt", "1111")
        engine.transfer(listOf(pa("/src/one.txt")), pb("/dst"), move = true, ctx = ctx(TransferOptions(resumed = true)))
        assertNull(a.read("/src/one.txt"))
        assertEquals("1111", b.read("/dst/one.txt"))
    }

    // Moving a folder must never delete a source file that wasn't moved (skipped on a conflict).
    @Test fun folderMoveKeepsSkippedFilesInSource() = runTest {
        b.put("/dst/src/one.txt", "old")
        engine.transfer(listOf(pa("/src")), pb("/dst"), move = true, ctx = ctx(TransferOptions(defaultConflict = ConflictChoice.SKIP)))
        // Skip on the folder conflict skips the whole folder: every source file must still be there.
        assertEquals("skipped file stays in the source", "1111", a.read("/src/one.txt"))
        assertEquals("22", a.read("/src/sub/two.txt"))
        assertEquals("old", b.read("/dst/src/one.txt"))
    }

    // A source stream that ends early (dropped connection) must fail the move and keep the source.
    @Test fun moveKeepsSourceWhenTheStreamEndsEarly() = runTest {
        val short = object : com.xander.files.domain.fs.FileSystemProvider by a {
            override suspend fun openInput(path: FsPath, offset: Long) = java.io.ByteArrayInputStream(a.read(path.path)!!.toByteArray().copyOf(2))
        }
        val e = CopyEngine(FileSystemRegistry(setOf(short, b)))
        val r = runCatching { e.transfer(listOf(pa("/src/one.txt")), pb("/dst"), move = true, ctx = ctx()) }
        assertTrue("the move fails", r.isFailure)
        assertEquals("source kept", "1111", a.read("/src/one.txt"))
        assertNull("partial copy removed", b.read("/dst/one.txt"))
    }

    // The real case: merge into an existing folder, skip one clashing file. The folder delete at the end of the
    // move is recursive, so it must not run while the skipped file is still in it.
    @Test fun folderMergeMoveKeepsTheFileYouSkipped() = runTest {
        b.put("/dst/src/one.txt", "old")
        val job = async { engine.transfer(listOf(pa("/src")), pb("/dst"), move = true, ctx = ctx(TransferOptions(defaultConflict = ConflictChoice.MERGE))) }
        assertEquals("one.txt", broker.pending.filterNotNull().first().request.sourceName)
        broker.answer(ConflictChoice.SKIP, false)
        job.await()
        assertEquals("skipped file stays in the source", "1111", a.read("/src/one.txt"))
        assertEquals("old", b.read("/dst/src/one.txt"))
        assertEquals("22", b.read("/dst/src/sub/two.txt"))
        assertNull("moved file is gone from the source", a.read("/src/sub/two.txt"))
    }

    @Test fun conflictSkipKeepsExisting() = runTest {
        b.put("/dst/one.txt", "old")
        engine.transfer(listOf(pa("/src/one.txt")), pb("/dst"), false, ctx(TransferOptions(defaultConflict = ConflictChoice.SKIP)))
        assertEquals("old", b.read("/dst/one.txt"))
    }

    @Test fun conflictOverwriteReplaces() = runTest {
        b.put("/dst/one.txt", "old")
        engine.transfer(listOf(pa("/src/one.txt")), pb("/dst"), false, ctx(TransferOptions(defaultConflict = ConflictChoice.OVERWRITE)))
        assertEquals("1111", b.read("/dst/one.txt"))
    }

    @Test fun conflictRenameKeepsBoth() = runTest {
        b.put("/dst/one.txt", "old")
        engine.transfer(listOf(pa("/src/one.txt")), pb("/dst"), false, ctx(TransferOptions(defaultConflict = ConflictChoice.RENAME)))
        assertEquals("old", b.read("/dst/one.txt"))
        assertEquals("1111", b.read("/dst/one (1).txt"))
    }

    @Test fun mergesExistingFolders() = runTest {
        b.put("/dst/src/keep.txt", "k")
        engine.transfer(listOf(pa("/src")), pb("/dst"), false, ctx(TransferOptions(defaultConflict = ConflictChoice.MERGE)))
        assertEquals("k", b.read("/dst/src/keep.txt"))
        assertEquals("1111", b.read("/dst/src/one.txt"))
    }

    @Test fun asksUserThroughBrokerWhenNoDefault() = runTest {
        b.put("/dst/one.txt", "old")
        val job = async { engine.transfer(listOf(pa("/src/one.txt")), pb("/dst"), false, ctx()) }
        val pending = broker.pending.filterNotNull().first()
        assertEquals("one.txt", pending.request.sourceName)
        broker.answer(ConflictChoice.OVERWRITE, false)
        job.await()
        assertEquals("1111", b.read("/dst/one.txt"))
    }

    @Test fun refusesCopyIntoItself() = runTest {
        val r = runCatching { engine.transfer(listOf(pa("/src")), pa("/src/sub"), false, ctx()) }
        assertTrue(r.exceptionOrNull() is FsException)
    }

    @Test fun cancelStopsAndRemovesPartialFile() = runTest {
        a.put("/big.bin", "x".repeat(2_000_000))
        val c = ctx()
        c.paused.value = true
        val job = launch { runCatching { engine.transfer(listOf(pa("/big.bin")), pb("/dst"), false, c) }.also { assertTrue(it.exceptionOrNull() is FsException.Cancelled) } }
        delay(50)
        c.cancelled = true; c.paused.value = false
        job.join()
        assertNull(b.read("/dst/big.bin"))
    }

    @Test fun uniqueNameSkipsTakenNames() = runTest {
        b.put("/dst/a.txt", ""); b.put("/dst/a (1).txt", "")
        assertEquals("a (2).txt", CopyEngine.uniqueName(b, pb("/dst"), "a.txt"))
    }
}
