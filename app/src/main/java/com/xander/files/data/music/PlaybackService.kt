package com.xander.files.data.music

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.xander.files.MainActivity
import com.xander.files.data.media.RoutingDataSourceFactory
import com.xander.files.domain.fs.FileSystemRegistry
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** Background music playback with media notification, lock screen and Bluetooth/headset controls. */
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {
    @Inject lateinit var registry: FileSystemRegistry
    private var session: MediaSession? = null

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(RoutingDataSourceFactory(this, registry)))
            .setAudioAttributes(ATTRS, MusicController.audioFocus(this))
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_ROUTE, "nowplaying").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        session = MediaSession.Builder(this, player)
            .setSessionActivity(open)
            .setCallback(object : MediaSession.Callback {
                // Controllers send items with URIs only; rebuild full items so they can play.
                override fun onAddMediaItems(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>): ListenableFuture<MutableList<MediaItem>> =
                    Futures.immediateFuture(mediaItems.map { it.buildUpon().setUri(it.requestMetadata.mediaUri ?: it.localConfiguration?.uri).build() }.toMutableList())
            })
            .build()
        MusicController.prefs(this).registerOnSharedPreferenceChangeListener(focusListener)
    }

    // The "Pause other audio" switch: with focus off, music plays alongside other apps instead of stopping them.
    private val focusListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == MusicController.KEY_FOCUS) (session?.player as? ExoPlayer)?.setAudioAttributes(ATTRS, MusicController.audioFocus(this))
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    private companion object {
        val ATTRS: AudioAttributes = AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        MusicController.prefs(this).unregisterOnSharedPreferenceChangeListener(focusListener)
        session?.run { player.release(); release() }
        session = null
        super.onDestroy()
    }
}
