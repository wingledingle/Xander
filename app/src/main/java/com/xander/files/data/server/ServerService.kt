package com.xander.files.data.server

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.xander.files.MainActivity
import com.xander.files.R
import com.xander.files.di.AppScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Foreground service that keeps Wi-Fi and the CPU awake while the FTP/web servers run. */
@AndroidEntryPoint
class ServerService : Service() {
    @Inject lateinit var controller: ServerController
    @Inject @AppScope lateinit var scope: CoroutineScope
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { controller.stopAll(); stopSelf(); return START_NOT_STICKY }
        ensureChannel(this)
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIF_ID, notification(controller.state.value), type)
        if (wifiLock == null) {
            @Suppress("DEPRECATION")
            wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "xander:server").apply { setReferenceCounted(false); acquire() }
            wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "xander:server").apply { setReferenceCounted(false); acquire() }
        }
        if (job == null) job = scope.launch {
            controller.state.collect { s ->
                if (!s.anyRunning) { stopSelf(); return@collect }
                getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(s))
            }
        }
        return START_NOT_STICKY
    }

    private fun notification(s: ServerState): android.app.Notification {
        val ip = s.addresses.firstOrNull() ?: "—"
        val lines = buildList {
            if (s.ftpRunning) add("ftp://$ip:${s.ftpPort}" + if (s.ftpClients > 0) "  (${s.ftpClients})" else "")
            if (s.httpRunning) add("http://$ip:${s.httpPort}")
        }
        val open = PendingIntent.getActivity(this, 3, Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_ROUTE, "servers").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 4, Intent(this, ServerService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.access_from_pc_running))
            .setContentText(lines.joinToString("   "))
            .setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n")))
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open)
            .addAction(0, getString(R.string.stop), stop)
            .build()
    }

    override fun onDestroy() {
        job?.cancel()
        runCatching { wifiLock?.release() }; runCatching { wakeLock?.release() }
        super.onDestroy()
    }

    companion object {
        const val CHANNEL = "servers"
        const val NOTIF_ID = 4201
        const val ACTION_STOP = "com.xander.files.server.STOP"
        fun ensureChannel(c: Context) {
            if (Build.VERSION.SDK_INT >= 26) c.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(CHANNEL, c.getString(R.string.access_from_pc), NotificationManager.IMPORTANCE_LOW))
        }
    }
}
