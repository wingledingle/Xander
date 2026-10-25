package com.xander.files.data.cast

import android.content.Context
import android.net.Uri
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaQueueItem
import com.google.android.gms.cast.MediaTrack
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.common.images.WebImage
import com.xander.files.domain.fs.FileCategory
import com.xander.files.domain.fs.FileEntry
import com.xander.files.domain.fs.MimeTypes
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Chromecast / Google TV casting of local or remote files through [MediaStreamServer]. */
@Singleton
class CastManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val stream: MediaStreamServer,
) {
    private val _device = MutableStateFlow<String?>(null)
    /** Name of the connected Cast device, or null. */
    val device: StateFlow<String?> = _device

    val castContext: CastContext? by lazy {
        // Missing or outdated Google Play services (e.g. on de-Googled phones) simply disables casting.
        runCatching { @Suppress("DEPRECATION") CastContext.getSharedInstance(context) }.getOrNull()
    }

    val available: Boolean get() = castContext != null

    private var registered = false

    /** Call on the main thread once the UI starts. */
    fun init() {
        val cc = castContext ?: return
        if (registered) return
        registered = true
        cc.sessionManager.addSessionManagerListener(object : SessionManagerListener<CastSession> {
            override fun onSessionStarted(s: CastSession, id: String) { _device.value = s.castDevice?.friendlyName ?: "Cast" }
            override fun onSessionResumed(s: CastSession, wasSuspended: Boolean) { _device.value = s.castDevice?.friendlyName ?: "Cast" }
            override fun onSessionEnded(s: CastSession, error: Int) { _device.value = null; stream.stop() }
            override fun onSessionSuspended(s: CastSession, reason: Int) {}
            override fun onSessionStarting(s: CastSession) {}
            override fun onSessionStartFailed(s: CastSession, error: Int) { _device.value = null }
            override fun onSessionEnding(s: CastSession) {}
            override fun onSessionResuming(s: CastSession, id: String) {}
            override fun onSessionResumeFailed(s: CastSession, error: Int) {}
        }, CastSession::class.java)
        cc.sessionManager.currentCastSession?.takeIf { it.isConnected }?.let { _device.value = it.castDevice?.friendlyName ?: "Cast" }
    }

    private fun session(): CastSession? = castContext?.sessionManager?.currentCastSession?.takeIf { it.isConnected }

    private fun info(e: FileEntry, subtitles: List<FileEntry> = emptyList()): MediaInfo? {
        val url = stream.urlFor(e.path) ?: return null
        val type = when (e.category) { FileCategory.IMAGE -> MediaMetadata.MEDIA_TYPE_PHOTO; FileCategory.AUDIO -> MediaMetadata.MEDIA_TYPE_MUSIC_TRACK; else -> MediaMetadata.MEDIA_TYPE_MOVIE }
        val md = MediaMetadata(type).apply {
            putString(MediaMetadata.KEY_TITLE, e.name.substringBeforeLast('.'))
            if (e.category == FileCategory.IMAGE) addImage(WebImage(Uri.parse(url)))
        }
        val tracks = subtitles.mapIndexedNotNull { i, s ->
            val u = stream.urlFor(s.path, vtt = true) ?: return@mapIndexedNotNull null
            MediaTrack.Builder(i + 1L, MediaTrack.TYPE_TEXT).setName(s.name).setSubtype(MediaTrack.SUBTYPE_SUBTITLES)
                .setContentId(u).setContentType("text/vtt").build()
        }
        return MediaInfo.Builder(url).setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(e.mimeType ?: MimeTypes.fromName(e.name)).setMetadata(md)
            .apply { if (tracks.isNotEmpty()) setMediaTracks(tracks) }.build()
    }

    /** Loads one item on the receiver. Returns false when not connected or no LAN address is available. */
    fun cast(e: FileEntry, positionMs: Long = 0, subtitles: List<FileEntry> = emptyList()): Boolean {
        val client = session()?.remoteMediaClient ?: return false
        val mi = info(e, subtitles) ?: return false
        val req = MediaLoadRequestData.Builder().setMediaInfo(mi).setAutoplay(true).setCurrentTime(positionMs)
            .apply { if (subtitles.isNotEmpty()) setActiveTrackIds(longArrayOf(1)) }.build()
        client.load(req)
        return true
    }

    /** Loads a playlist (music queue). */
    fun castQueue(items: List<FileEntry>, start: Int, positionMs: Long = 0): Boolean {
        val client = session()?.remoteMediaClient ?: return false
        val queue = items.mapNotNull { info(it) }.map { MediaQueueItem.Builder(it).setAutoplay(true).build() }
        if (queue.isEmpty()) return false
        client.queueLoad(queue.toTypedArray(), start.coerceIn(0, queue.size - 1), com.google.android.gms.cast.MediaStatus.REPEAT_MODE_REPEAT_OFF, positionMs, null)
        return true
    }

    fun stopCasting() { castContext?.sessionManager?.endCurrentSession(true) }
}
