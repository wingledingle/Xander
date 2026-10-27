package app.quinten.tts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import app.quinten.MainActivity

/** Keeps read-aloud running with the screen off and offers pause/stop in a notification. */
class TtsService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> TtsController.active?.let { if (it.state == TtsController.State.PLAYING) it.pause() else it.resume() }
            ACTION_STOP -> { TtsController.active?.stop(); stopSelf(); return START_NOT_STICKY }
        }
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, "Read aloud", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        fun action(a: String) = PendingIntent.getService(this, a.hashCode(), Intent(this, TtsService::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE)
        val n = (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this))
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setContentTitle("Reading aloud")
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .also { b -> TtsController.active?.session?.sessionToken?.let { t -> b.setStyle(Notification.MediaStyle().setMediaSession(t).setShowActionsInCompactView(0, 1)) } }
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Pause / resume", action(ACTION_PAUSE)).build())
            .addAction(Notification.Action.Builder(null, "Stop", action(ACTION_STOP)).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) else startForeground(1, n)
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "tts"
        private const val ACTION_PAUSE = "pause"
        private const val ACTION_STOP = "stop"
        fun start(c: Context) {
            try {
                val i = Intent(c, TtsService::class.java)
                if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
            } catch (_: Exception) {}
        }
        fun stop(c: Context) { c.stopService(Intent(c, TtsService::class.java)) }
    }
}
