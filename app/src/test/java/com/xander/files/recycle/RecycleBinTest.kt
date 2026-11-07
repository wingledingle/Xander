package com.xander.files.recycle

import androidx.test.core.app.ApplicationProvider
import com.xander.files.data.db.RecycleDao
import com.xander.files.data.db.RecycleEntity
import com.xander.files.data.recycle.RecycleBin
import com.xander.files.data.storage.StorageVolumes
import com.xander.files.data.transfer.ConflictBroker
import com.xander.files.data.transfer.ConflictChoice
import com.xander.files.data.transfer.CopyEngine
import com.xander.files.data.transfer.TransferContext
import com.xander.files.data.transfer.TransferOptions
import com.xander.files.domain.fs.FileSystemRegistry
import com.xander.files.domain.fs.FsPath
import com.xander.files.fs.MemoryFileSystemProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RecycleBinTest {
    private class FakeDao : RecycleDao {
        val rows = MutableStateFlow<Map<String, RecycleEntity>>(emptyMap())
        override fun observeAll() = rows.map { it.values.toList() }
        override suspend fun all() = rows.value.values.toList()
        override suspend fun olderThan(before: Long) = rows.value.values.filter { it.deletedAt < before }
        override suspend fun get(id: String) = rows.value[id]
        override suspend fun insert(e: RecycleEntity) { rows.value = rows.value + (e.id to e) }
        override suspend fun delete(id: String) { rows.value = rows.value - id }
        override fun observeCount() = rows.map { it.size }
        override fun observeSize() = rows.map { r -> r.values.sumOf { it.size } }
    }

    // A backend without server-side move, so recycling and restoring go through the copy engine.
    private val fs = MemoryFileSystemProvider("mem", supportsMove = false)
    private val dao = FakeDao()
    private val registry = FileSystemRegistry(setOf(fs))
    private val bin = RecycleBin(dao, registry, StorageVolumes(ApplicationProvider.getApplicationContext()), CopyEngine(registry))
    private val broker = ConflictBroker()
    private fun ctx(c: ConflictChoice? = null) = TransferContext("t", TransferOptions(defaultConflict = c), broker) {}
    private fun p(s: String) = FsPath("mem", "", s)

    @Test fun recycleAndRestoreRoundTrip() = runTest {
        fs.put("/a/one.txt", "1111")
        bin.moveToBin(p("/a/one.txt"), ctx())
        assertNull(fs.read("/a/one.txt"))
        val id = dao.all().single().id
        bin.restore(id, ctx())
        assertEquals("1111", fs.read("/a/one.txt"))
        assertNull(dao.get(id))
    }

    @Test fun restoreSkippedOnClashKeepsTheItemInTheBin() = runTest {
        fs.put("/a/one.txt", "1111")
        bin.moveToBin(p("/a/one.txt"), ctx())
        val e = dao.all().single()
        fs.put("/a/one.txt", "new")
        bin.restore(e.id, ctx(ConflictChoice.SKIP))
        assertEquals("new", fs.read("/a/one.txt"))
        assertEquals("still in the bin", "1111", fs.read(FsPath.parse(e.binPath).path))
        assertNotNull("bin record kept", dao.get(e.id))
    }

    @Test fun onlyTheRealBinFolderMeansPermanentDelete() = runTest {
        fs.put("/a/.XanderRecycleOld/keep.txt", "k")
        bin.moveToBin(p("/a/.XanderRecycleOld/keep.txt"), ctx())
        assertEquals("went to the bin, not deleted", 1, dao.all().size)
    }
}
