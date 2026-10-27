package com.xander.files.data.nsfw

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.xander.files.R
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
        if (s.nsfwEnabled) dao.observeFlagged(s.nsfwThreshold / 100f, s.nsfwDrawn) else flowOf(CountSize(0, 0))
    }

    fun observeScanned() = dao.observeScanned()

    suspend fun scoresFor(paths: Collection<String>): Map<String, NsfwScoreEntity> = paths.mapNotNull { dao.get(it) }.associateBy { it.path }

    /** Scores one file right now (and stores it), for "Check this file". */
    suspend fun check(file: File, video: Boolean, scorer: NsfwScorer): NsfwScores? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
        val r = if (video) scorer.video(file) else scorer.image(file)
        r?.also { dao.upsert(NsfwScoreEntity(file.path, file.length(), file.lastModified(), it.drawings, it.hentai, it.neutral, it.porn, it.sexy)) }
    }

    fun isAdult(e: NsfwScoreEntity, thresholdPct: Int, drawn: Boolean) = e.porn * 100 >= thresholdPct || (drawn && e.hentai * 100 >= thresholdPct)

    suspend fun flaggedPaths(): List<String> {
        val s = settings.settings.first()
        return if (s.nsfwEnabled) dao.flagged(s.nsfwThreshold / 100f, s.nsfwDrawn) else emptyList()
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

    companion object {
        const val WORK = "nsfw-scan"

        /** Photo folders first (where adult photos usually are), comic/manga folders last. */
        fun priority(path: String): Int {
            val p = path.lowercase()
            return when {
                Regex("/(comics?|manga|manhwa|webtoons?|cbz|cbr)(/|$)").containsMatchIn(p) -> 3
                Regex("/(dcim|camera|pictures|download|screenshots|telegram|whatsapp|snapchat|movies)(/|$)").containsMatchIn(p) -> 0
                else -> 1
            }
        }
    }
}

/**
 * Classifies every indexed local image (and sampled video frames) not yet scored at its current size/mtime.
 * Runs as a foreground job with a progress notification so Android does not stop it after 10 minutes.
 */
@HiltWorker
class NsfwScanWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val index: FileIndexDao,
    private val dao: NsfwDao,
    private val scorer: NsfwScorer,
    private val settings: SettingsRepository,
) : CoroutineWorker(context, params) {

    private fun notification(done: Int, total: Int): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, applicationContext.getString(R.string.adult_section), NotificationManager.IMPORTANCE_LOW))
        val n = NotificationCompat.Builder(applicationContext, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(applicationContext.getString(R.string.adult_scanning, done, total))
            .setProgress(total, done, total == 0).setOngoing(true).setOnlyAlertOnce(true).setSilent(true).build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(NOTIF, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(NOTIF, n)
    }

    override suspend fun doWork(): Result {
        val s = settings.settings.first()
        if (!s.nsfwEnabled) return Result.success()
        val all = inputData.getBoolean("all", false)
        val media = index.media().filter { s.nsfwVideos || it.category == "IMAGE" }.sortedBy { NsfwRepository.priority(it.path) }
        runCatching { setForeground(notification(0, media.size)) } // not allowed while the app is in the background on Android 12+; keep going
        var done = 0
        for (m in media) {
            if (isStopped) return Result.retry()
            done++
            if (done % 25 == 0) {
                setProgress(workDataOf("done" to done, "total" to media.size))
                runCatching { setForeground(notification(done, media.size)) }
            }
            val f = File(m.path)
            if (!f.canRead()) continue
            if (!all) dao.get(m.path)?.let { if (it.size == m.size && it.modified == m.modified) continue }
            val r = runCatching { if (m.category == "IMAGE") scorer.image(f) else scorer.video(f) }.getOrNull() ?: continue
            dao.upsert(NsfwScoreEntity(m.path, m.size, m.modified, r.drawings, r.hentai, r.neutral, r.porn, r.sexy))
        }
        return Result.success()
    }

    companion object {
        const val CHANNEL = "adult_scan"
        const val NOTIF = 4401
    }
}

/** Turns an image file or a video (sampled frames) into class probabilities. */
@Singleton
class NsfwScorer @Inject constructor(private val classifier: NsfwClassifier) {
    fun image(f: File): NsfwScores? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        if (o.outWidth < 64 || o.outHeight < 64) return null // icons, emoji, UI assets
        var sample = 1
        // Only pre-shrink very large images: the model was trained on a single nearest-neighbour resize from the
        // original, and an extra 2× decode step first visibly changes scores on line art (measured on Android).
        while (o.outWidth / (sample * 2) >= NsfwClassifier.SIZE * 4 && o.outHeight / (sample * 2) >= NsfwClassifier.SIZE * 4) sample *= 2
        val b = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        return try { if (NsfwClassifier.isFlat(b)) BLANK else classifier.classify(b) } finally { b.recycle() }
    }

    /** A video counts as its most explicit sampled frame (flat frames skipped). */
    fun video(f: File): NsfwScores? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(f.path)
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: return null
            var best: NsfwScores? = null
            for (i in 1..FRAMES) {
                val t = dur * 1000 * i / (FRAMES + 1)
                val b = (if (Build.VERSION.SDK_INT >= 27) r.getScaledFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 448, 448) else r.getFrameAtTime(t)) ?: continue
                try {
                    if (NsfwClassifier.isFlat(b)) continue
                    val sc = classifier.classify(b)
                    if (best == null || maxOf(sc.porn, sc.hentai) > maxOf(best.porn, best.hentai)) best = sc
                } finally { b.recycle() }
                if (best != null && best.porn > 0.95f) break
            }
            best ?: BLANK
        } finally { runCatching { r.release() } }
    }

    companion object {
        const val FRAMES = 8
        val BLANK = NsfwScores(0f, 0f, 1f, 0f, 0f)
    }
}

/** Lowest adult-content sensitivity both sliders (Settings and the adult-images screen) allow, in percent. */
const val NSFW_THRESHOLD_MIN = 30
