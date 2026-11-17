package com.xander.files

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xander.files.data.db.AppDatabase
import com.xander.files.data.db.FileIndexEntity
import com.xander.files.data.db.NsfwScoreEntity
import com.xander.files.domain.fs.FileCategory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Library counts (Images, Games, Adult...) must count each real file once. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class LibraryCountsTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).allowMainThreadQueries().build()
    private val dao = db.fileIndex()
    @After fun close() = db.close()

    private fun file(path: String, volume: String = "primary") = path.substringAfterLast('/').let { n ->
        FileIndexEntity(path, n, path.substringBeforeLast('/'), 100, 0, FileCategory.of(n).name, false, volume)
    }
    private suspend fun count(cat: FileCategory) = dao.observeCategoryStats().first().firstOrNull { it.category == cat.name }?.count ?: 0

    @Test fun hiddenThumbnailsAndTrashedPhotosAreNotCounted() = runBlocking {
        dao.insertAll(listOf(
            file("/sdcard/DCIM/Camera/a.jpg"), file("/sdcard/DCIM/Camera/b.jpg"),
            file("/sdcard/DCIM/.thumbnails/a.jpg"), file("/sdcard/DCIM/.thumbnails/b.jpg"), // the phone's thumbnail copies
            file("/sdcard/DCIM/Camera/.trashed-1700000000-c.jpg"),                          // already in the phone's trash
        ))
        assertEquals(2, count(FileCategory.IMAGE))
        assertEquals(2, dao.byCategory(FileCategory.IMAGE.name).size)
        assertEquals(2, dao.media().size)
    }

    @Test fun saveFilesAreNotGames() = runBlocking {
        dao.insertAll(listOf(file("/sdcard/Roms/zelda.gba"), file("/sdcard/Roms/zelda.sav"), file("/sdcard/Roms/zelda.srm"), file("/sdcard/Roms/zelda.state")))
        assertEquals(1, count(FileCategory.GAME))
    }

    @Test fun adultCountSkipsDeletedFilesAndHiddenCopies() = runBlocking {
        dao.insertAll(listOf(file("/sdcard/DCIM/a.jpg"), file("/sdcard/DCIM/.thumbnails/a.jpg")))
        fun flagged(p: String) = NsfwScoreEntity(p, 1, 1, 0f, 0f, 0f, 0.9f, 0.05f)
        db.nsfw().upsert(flagged("/sdcard/DCIM/a.jpg"))
        db.nsfw().upsert(flagged("/sdcard/DCIM/.thumbnails/a.jpg")) // its thumbnail, scored by older builds
        db.nsfw().upsert(flagged("/sdcard/DCIM/deleted.jpg"))       // deleted since it was scanned
        assertEquals(1, db.nsfw().observeFlagged(0.7f, drawn = false, nude = true).first().count)
    }

    @Test fun removedCardsFilesAreDropped() = runBlocking {
        dao.insertAll(listOf(file("/sdcard/a.jpg"), file("/storage/1234-ABCD/b.jpg", volume = "1234-ABCD")))
        dao.pruneUnmounted(listOf("primary"))
        assertEquals(1, count(FileCategory.IMAGE))
    }
}
