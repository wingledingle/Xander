package com.xander.files.data.transfer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.xander.files.MainActivity
import com.xander.files.R
import com.xander.files.data.db.TransferDao
import com.xander.files.data.db.TransferEntity
import com.xander.files.domain.fs.FsException
import com.xander.files.domain.fs.FsPath
import com.xander.files.ui.common.Format
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/** Runs queued transfers one after another in a foreground service (dataSync) with a progress notification. */
@HiltWorker
class TransferWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val dao: TransferDao,
    private val manager: TransferManager,
    private val operations: Map<TransferType, @JvmSuppressWildcards TransferOperation>,
) : CoroutineWorker(appContext, params) {

    private val nm = NotificationManagerCompat.from(appContext)
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override suspend fun doWork(): Result {
        ensureChannel(applicationContext)
        dao.requeueRunning()
        runCatching { setForeground(foreground(applicationContext.getString(R.string.transfer_preparing), null, 0f, true)) }
        manager.conflicts.onAsk = { req -> notifyConflict(req.sourceName) }
        while (true) {
            val job = dao.nextRunnable() ?: break
            runJob(job)
        }
        manager.conflicts.onAsk = null
        return Result.success()
    }

    private suspend fun runJob(job: TransferEntity) {
        val type = runCatching { TransferType.valueOf(job.type) }.getOrNull()
        val op = type?.let { operations[it] }
        if (op == null) { dao.setState(job.id, TransferState.FAILED.name, "Unsupported operation"); return }
        val options = runCatching { TransferManager.json.decodeFromString(TransferOptions.serializer(), job.optionsJson) }.getOrDefault(TransferOptions())
        val sources = TransferManager.json.decodeFromString(ListSerializer(String.serializer()), job.sourcesJson).map(FsPath::parse)
        val dest = job.destination?.let(FsPath::parse)
        var latest = job
        val ctx = TransferContext(job.id, options, manager.conflicts) { p ->
            latest = latest.copy(totalBytes = p.totalBytes, doneBytes = p.doneBytes, totalItems = p.totalItems, doneItems = p.doneItems,
                currentName = p.current, speedBps = p.speedBps, updatedAt = System.currentTimeMillis())
            val snapshot = latest
            io.launch { dao.get(snapshot.id)?.let { cur -> if (cur.state == TransferState.RUNNING.name || cur.state == TransferState.PAUSED.name) dao.upsert(snapshot.copy(state = cur.state)) } }
            updateNotification(snapshot, p)
        }
        manager.running[job.id] = ctx
        dao.setState(job.id, TransferState.RUNNING.name)
        latest = latest.copy(state = TransferState.RUNNING.name)
        try {
            op.run(sources, dest, ctx)
            ctx.flush()
            dao.get(job.id)?.let { dao.upsert(latest.copy(state = TransferState.DONE.name, doneBytes = latest.totalBytes, speedBps = 0, currentName = null)) }
            notifyFinished(job.title, null)
        } catch (e: Throwable) {
            val fe = FsException.wrap(e)
            if (fe is FsException.Cancelled || ctx.cancelled) {
                dao.setState(job.id, TransferState.CANCELLED.name)
            } else {
                dao.setState(job.id, TransferState.FAILED.name, fe.message)
                notifyFinished(job.title, fe.message)
            }
        } finally {
            manager.running.remove(job.id)
        }
    }

    private fun contentIntent(): PendingIntent = PendingIntent.getActivity(
        applicationContext, 0,
        Intent(applicationContext, MainActivity::class.java).putExtra(MainActivity.EXTRA_ROUTE, "transfers").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun foreground(title: String, text: String?, fraction: Float, indeterminate: Boolean): ForegroundInfo {
        val n = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title).setContentText(text)
            .setOnlyAlertOnce(true).setOngoing(true)
            .setProgress(1000, (fraction * 1000).toInt(), indeterminate)
            .setContentIntent(contentIntent())
            .build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(NOTIF_ID, n)
    }

    private fun updateNotification(job: TransferEntity, p: TransferProgress) {
        val text = buildString {
            append("${Format.size(p.doneBytes)} / ${Format.size(p.totalBytes)}")
            if (p.speedBps > 0) append(" · ${Format.speed(p.speedBps)} · ${Format.duration(p.etaSeconds)}")
            p.current?.let { append("\n").append(it) }
        }
        runCatching { setForegroundAsync(foreground(job.title, text, p.fraction, p.totalBytes == 0L && p.totalItems == 0)) }
    }

    private fun notifyConflict(name: String) {
        val n = NotificationCompat.Builder(applicationContext, CHANNEL_ALERTS)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(applicationContext.getString(R.string.conflict_title))
            .setContentText(name).setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(contentIntent()).build()
        runCatching { nm.notify(NOTIF_CONFLICT, n) }
    }

    private fun notifyFinished(title: String, error: String?) {
        val n = NotificationCompat.Builder(applicationContext, CHANNEL_ALERTS)
            .setSmallIcon(if (error == null) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
            .setContentTitle(title)
            .setContentText(error ?: applicationContext.getString(R.string.transfer_done))
            .setAutoCancel(true).setContentIntent(contentIntent()).build()
        runCatching { nm.notify(title.hashCode(), n) }
    }

    companion object {
        const val CHANNEL = "transfers"
        const val CHANNEL_ALERTS = "transfer_alerts"
        const val NOTIF_ID = 1001
        const val NOTIF_CONFLICT = 1002

        fun ensureChannel(c: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = c.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, c.getString(R.string.channel_transfers), NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ALERTS, c.getString(R.string.channel_transfer_alerts), NotificationManager.IMPORTANCE_DEFAULT))
        }
    }
}
