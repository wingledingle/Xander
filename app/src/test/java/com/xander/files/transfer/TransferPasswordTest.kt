package com.xander.files.transfer

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xander.files.data.db.AppDatabase
import com.xander.files.data.db.TransferEntity
import com.xander.files.data.security.SecretCipher
import com.xander.files.data.transfer.ConflictBroker
import com.xander.files.data.transfer.TransferManager
import com.xander.files.data.transfer.TransferOptions
import com.xander.files.data.transfer.TransferState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Passwords of encrypt/decrypt/archive jobs are never stored in plain text, and are erased when the job is done. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class TransferPasswordTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).allowMainThreadQueries().build()
    // Stand-in for the Keystore cipher (Robolectric has no Keystore): reversible, and clearly not the plain text.
    private val cipher = object : SecretCipher {
        override fun encrypt(plain: String) = plain.reversed() + "#"
        override fun decrypt(cipherText: String) = cipherText.removeSuffix("#").reversed()
    }
    private val manager = TransferManager(ApplicationProvider.getApplicationContext(), db.transfers(), ConflictBroker(), cipher)
    @After fun close() = db.close()

    private fun job(id: String, state: TransferState, optionsJson: String) =
        TransferEntity(id, "ENCRYPT", state.name, "t", "[]", null, optionsJson)

    @Test fun storedOptionsNeverHoldThePlainPassword() {
        val stored = manager.encodeOptions(TransferOptions(password = "hunter2"))
        assertFalse(stored, stored.contains("hunter2"))
        assertEquals("hunter2", manager.decodeOptions(stored).password)
    }

    @Test fun finishedJobsLoseTheirPasswordAndOldPlainTextIsEncrypted() = runBlocking {
        val plain = TransferManager.json.encodeToString(TransferOptions.serializer(), TransferOptions(password = "hunter2"))
        db.transfers().upsert(job("done", TransferState.DONE, plain))      // saved by an older build
        db.transfers().upsert(job("failed", TransferState.FAILED, plain))  // can still be retried
        manager.scrubPasswords()
        assertEquals("", manager.decodeOptions(db.transfers().get("done")!!.optionsJson).password)
        val failed = db.transfers().get("failed")!!.optionsJson
        assertFalse(failed.contains("hunter2"))
        assertEquals("retry still works", "hunter2", manager.decodeOptions(failed).password)
    }
}
