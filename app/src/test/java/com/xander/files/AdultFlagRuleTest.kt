package com.xander.files

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xander.files.data.db.AppDatabase
import com.xander.files.data.db.NsfwScoreEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The model scores nude photos as "sexy", not "porn"; the sorter must count them (it used to flag porn only). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AdultFlagRuleTest {
    @Test fun nudePhotosAreFlaggedUnlessTurnedOff() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).allowMainThreadQueries().build()
        val dao = db.nsfw()
        fun row(p: String, porn: Float, sexy: Float, hentai: Float = 0f) = NsfwScoreEntity(p, 1, 1, 0f, hentai, 1 - porn - sexy - hentai, porn, sexy)
        dao.upsert(row("/nude.jpg", porn = 0.25f, sexy = 0.70f))
        dao.upsert(row("/explicit.jpg", porn = 0.90f, sexy = 0.05f))
        dao.upsert(row("/beach.jpg", porn = 0.02f, sexy = 0.30f))
        dao.upsert(row("/manga.jpg", porn = 0.05f, sexy = 0.05f, hentai = 0.80f))
        assertEquals(setOf("/explicit.jpg", "/nude.jpg"), dao.flagged(0.7f, drawn = false, nude = true).toSet())
        assertEquals(listOf("/explicit.jpg"), dao.flagged(0.7f, drawn = false, nude = false))
        assertEquals(setOf("/explicit.jpg", "/nude.jpg", "/manga.jpg"), dao.flagged(0.7f, drawn = true, nude = true).toSet())
        assertEquals(2, dao.observeFlagged(0.7f, drawn = false, nude = true).first().count)
        db.close()
    }
}
