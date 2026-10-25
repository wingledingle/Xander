package com.xander.files.data.nsfw

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import androidx.hilt.work.HiltWorker
import com.xander.files.data.db.CountSize
import com.xander.files.data.db.FileIndexDao
import com.xander.files.data.db.NsfwDao
import com.xander.files.data.db.NsfwScoreEntity
import com.xander.files.data.settings.SettingsRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NsfwRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: NsfwDao,
    private val settings: SettingsRepository,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeFlagged(): Flow<CountSize> = settings.settings.flatMapLatest { s ->
        if (s.nsfwEnabled) dao.observeFlagged(s.nsfwThreshold / 100f) else flowOf(CountSize(0, 0))
    }

    fun observeScanned() = dao.observeScanned()

    suspend fun flaggedPaths(): List<String> {
        val s = settings.settings.first()
        return if (s.nsfwEnabled) dao.flagged(s.nsfwThreshold / 100f) else emptyList()
    }

    suspend fun scan(rescanAll: Boolean = false) {
        val s = settings.settings.first()
        if (!s.nsfwEnabled) return
        val req = OneTimeWorkRequestBuilder<NsfwScanWorker>()
            .setInputData(workDataOf("all" to rescanAll))
            .setConstraints(Constraints.Builder().setRequiresCharging(s.nsfwChargingOnly).setRequiresBatteryNotLow(true).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK, if (rescanAll) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, req)
    }

    fun progress() = WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(WORK)

    suspend fun clear() { WorkManager.getInstance(context).cancelUniqueWork(WORK); dao.clear() }

    companion object { const val WORK = "nsfw-scan" }
}

/** Scores every indexed local image (and sampled video frames) that has not been scored at its current size/mtime. */
@HiltWorker
class NsfwScanWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val index: FileIndexDao,
    private val dao: NsfwDao,
    private val classifier: NsfwClassifier,
    private val settings: SettingsRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val s = settings.settings.first()
        if (!s.nsfwEnabled) return Result.success()
        val all = inputData.getBoolean("all", false)
        val media = index.media().filter { s.nsfwVideos || it.category == "IMAGE" }
        var done = 0
        for (m in media) {
            if (isStopped) return Result.retry()
            done++
            if (done % 20 == 0) setProgress(workDataOf("done" to done, "total" to media.size))
            val f = File(m.path)
            if (!f.canRead()) continue
            if (!all) dao.get(m.path)?.let { if (it.size == m.size && it.modified == m.modified) continue }
            val score = runCatching { if (m.category == "IMAGE") image(f) else video(f) }.getOrNull() ?: continue
            dao.upsert(NsfwScoreEntity(m.path, m.size, m.modified, score))
        }
        return Result.success()
    }

    private fun image(f: File): Float? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        if (o.outWidth < 64 || o.outHeight < 64) return null // icons, emoji, UI assets
        var sample = 1
        while (o.outWidth / (sample * 2) >= NsfwClassifier.SIZE && o.outHeight / (sample * 2) >= NsfwClassifier.SIZE) sample *= 2
        val b = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        return try { classifier.score(b) } finally { b.recycle() }
    }

    /** Like nuditag: a video scores as its most explicit sampled frame, ignoring flat frames. */
    private fun video(f: File): Float? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(f.path)
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: return null
            var best: Float? = null
            for (i in 1..FRAMES) {
                val t = dur * 1000 * i / (FRAMES + 1)
                val b = (if (Build.VERSION.SDK_INT >= 27) r.getScaledFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, NsfwClassifier.SIZE, NsfwClassifier.SIZE)
                else r.getFrameAtTime(t)) ?: continue
                try { if (!NsfwClassifier.isFlat(b)) best = maxOf(best ?: 0f, classifier.score(b)) } finally { b.recycle() }
                if ((best ?: 0f) > 0.95f) break
            }
            best
        } finally { runCatching { r.release() } }
    }

    companion object { const val FRAMES = 8 }
}
