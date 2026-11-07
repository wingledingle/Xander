package com.xander.files.data.music

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
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.xander.files.MainActivity
import com.xander.files.R
import com.xander.files.data.media.FsUri
import com.xander.files.data.media.RoutingDataSourceFactory
import com.xander.files.domain.fs.FileSystemRegistry
import com.xander.files.domain.fs.FsPath
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class SoundLayer(val id: Int, val title: String, val playing: Boolean, val volume: Float, val loop: Boolean)

/**
 * Sound layers: audio files that play at the same time as each other and as the music queue (rain under a podcast,
 * a drone under a beat). Each layer is its own player with its own volume and loop, and none of them takes audio
 * focus, so they never pause each other. A small foreground service keeps them going with the screen off.
 */
@Singleton
class AudioMixer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val registry: FileSystemRegistry,
) {
    private val players = LinkedHashMap<Int, ExoPlayer>()
    private val _layers = MutableStateFlow<List<SoundLayer>>(emptyList())
    val layers: StateFlow<List<SoundLayer>> = _layers
    private var nextId = 1

    fun add(path: FsPath, title: String) = add(FsUri.of(path), title)

    @OptIn(UnstableApi::class)
    fun add(uri: android.net.Uri, title: String) {
        val id = nextId++
        val p = ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(RoutingDataSourceFactory(context, registry)))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), false)
            .build()
        p.setMediaItem(MediaItem.fromUri(uri))
        p.repeatMode = Player.REPEAT_MODE_ONE
        p.addListener(object : Player.Listener { override fun onIsPlayingChanged(isPlaying: Boolean) = update(id) { it.copy(playing = isPlaying) } })
        p.prepare(); p.play()
        players[id] = p
        _layers.value = _layers.value + SoundLayer(id, title, true, 1f, true)
        MixService.sync(context, _layers.value.size)
    }

    private fun update(id: Int, f: (SoundLayer) -> SoundLayer) { _layers.value = _layers.value.map { if (it.id == id) f(it) else it } }

    fun toggle(id: Int) { players[id]?.let { if (it.isPlaying) it.pause() else { if (it.playbackState == Player.STATE_ENDED) it.seekTo(0); it.play() } } }
    fun setVolume(id: Int, v: Float) { players[id]?.volume = v.coerceIn(0f, 1f); update(id) { it.copy(volume = v.coerceIn(0f, 1f)) } }
    fun setLoop(id: Int, on: Boolean) { players[id]?.repeatMode = if (on) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF; update(id) { it.copy(loop = on) } }
    fun playAll(play: Boolean) = players.values.forEach { if (play) it.play() else it.pause() }

    fun remove(id: Int) {
        players.remove(id)?.release()
        _layers.value = _layers.value.filterNot { it.id == id }
        MixService.sync(context, _layers.value.size)
    }

    fun clear() = players.keys.toList().forEach(::remove)
}

/** Foreground service that keeps sound layers playing in the background, with a "Stop" action. */
class MixService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val mixer = dagger.hilt.android.EntryPointAccessors.fromApplication(applicationContext, Deps::class.java).mixer()
        if (intent?.action == ACTION_STOP) { mixer.clear(); stopSelf(); return START_NOT_STICKY }
        val n = intent?.getIntExtra(EXTRA_COUNT, 0) ?: 0
        if (n == 0) { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY }
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.sound_layers), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_ROUTE, "nowplaying")
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 2, Intent(this, MixService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(resources.getQuantityString(R.plurals.sound_layers_playing, n, n))
            .setContentIntent(open).setOngoing(true).setSilent(true)
            .addAction(0, getString(R.string.stop), stop).build()
        ServiceCompat.startForeground(this, NOTIF_ID, notification, if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0)
        return START_NOT_STICKY
    }

    @dagger.hilt.EntryPoint
    @dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
    interface Deps { fun mixer(): AudioMixer }

    companion object {
        private const val CHANNEL = "sound_layers"
        private const val NOTIF_ID = 4311
        private const val ACTION_STOP = "com.xander.files.mix.STOP"
        private const val EXTRA_COUNT = "count"
        fun sync(c: Context, count: Int) {
            val i = Intent(c, MixService::class.java).putExtra(EXTRA_COUNT, count)
            if (count > 0) runCatching { androidx.core.content.ContextCompat.startForegroundService(c, i) } else runCatching { c.startService(i) }
        }
    }
}
