package com.xander.files.data.transfer

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import com.xander.files.data.db.TransferDao
import com.xander.files.data.db.TransferEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Public API of the background transfer engine: queue, observe, pause/resume/cancel. */
@Singleton
class TransferManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: TransferDao,
    val conflicts: ConflictBroker,
    private val cipher: com.xander.files.data.security.SecretCipher,
) {
    // A job's password (encrypt, decrypt, protected archives) is kept so the job can resume, but only encrypted with
    // the Keystore key (never in plain text), and it's erased once the job has finished.
    fun encodeOptions(o: TransferOptions): String =
        json.encodeToString(TransferOptions.serializer(), if (o.password.isEmpty()) o else o.copy(password = ENC + cipher.encrypt(o.password)))

    fun decodeOptions(s: String): TransferOptions {
        val o = runCatching { json.decodeFromString(TransferOptions.serializer(), s) }.getOrDefault(TransferOptions())
        return if (o.password.startsWith(ENC)) o.copy(password = cipher.decrypt(o.password.removePrefix(ENC))) else o
    }

    /** Erases the password of finished jobs, including plain-text ones saved by builds before this was fixed. */
    suspend fun scrubPasswords() {
        for (e in dao.all()) {
            val o = runCatching { json.decodeFromString(TransferOptions.serializer(), e.optionsJson) }.getOrNull() ?: continue
            if (o.password.isEmpty()) continue
            val finished = e.state == TransferState.DONE.name
            if (finished || !o.password.startsWith(ENC)) dao.upsert(e.copy(optionsJson = encodeOptions(if (finished) o.copy(password = "") else o)))
        }
    }

    internal val running = ConcurrentHashMap<String, TransferContext>()

    fun observeAll(): Flow<List<TransferEntity>> = dao.observeAll()
    fun observe(id: String) = dao.observe(id)
    fun observeActiveCount() = dao.observeActiveCount()

    suspend fun enqueue(req: TransferRequest): String {
        val id = UUID.randomUUID().toString()
        dao.upsert(
            TransferEntity(
                id = id, type = req.type.name, state = TransferState.QUEUED.name, title = req.title,
                sourcesJson = json.encodeToString(ListSerializer(String.serializer()), req.sources),
                destination = req.destination,
                optionsJson = encodeOptions(req.options),
            )
        )
        kick()
        return id
    }

    fun kick() {
        val work = OneTimeWorkRequestBuilder<TransferWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setConstraints(Constraints.NONE)
            .addTag(TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.KEEP, work)
    }

    suspend fun pause(id: String) {
        running[id]?.paused?.value = true
        dao.setState(id, TransferState.PAUSED.name)
    }

    suspend fun resume(id: String) {
        val r = running[id]
        if (r != null) { r.paused.value = false; dao.setState(id, TransferState.RUNNING.name) }
        else { dao.setState(id, TransferState.QUEUED.name); kick() }
    }

    suspend fun cancel(id: String) {
        running[id]?.let { it.cancelled = true; it.paused.value = false }
        conflicts.cancelPending(id)
        dao.setState(id, TransferState.CANCELLED.name)
    }

    suspend fun retry(id: String) {
        val e = dao.get(id) ?: return
        val opts = decodeOptions(e.optionsJson)
        dao.upsert(e.copy(state = TransferState.QUEUED.name, error = null, doneBytes = 0, doneItems = 0,
            optionsJson = encodeOptions(opts.copy(resumed = true))))
        kick()
    }

    suspend fun clearFinished() = dao.clearFinished()
    suspend fun remove(id: String) = dao.delete(id)

    companion object {
        private const val ENC = "enc:"
        const val UNIQUE = "xander-transfers"
        const val TAG = "transfer"
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
