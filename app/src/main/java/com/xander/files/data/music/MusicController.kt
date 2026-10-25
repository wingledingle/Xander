package com.xander.files.data.music

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import com.xander.files.data.media.FsUri
import com.xander.files.domain.fs.FileEntry
import com.xander.files.domain.fs.FsPath
import com.xander.files.di.AppScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

data class NowPlaying(
    val title: String = "", val artist: String = "", val album: String = "", val path: FsPath? = null,
    val isPlaying: Boolean = false, val positionMs: Long = 0, val durationMs: Long = 0,
    val shuffle: Boolean = false, val repeat: Int = Player.REPEAT_MODE_OFF, val index: Int = 0, val queue: List<MediaItem> = emptyList(),
) { val active get() = queue.isNotEmpty() }

/** App-side handle to [PlaybackService]; exposes playback state as flows for the music UI. */
@Singleton
class MusicController @Inject constructor(
    @ApplicationContext private val context: Context,
    @AppScope private val scope: CoroutineScope,
) {
    private var controller: MediaController? = null
    private val _state = MutableStateFlow(NowPlaying())
    val state: StateFlow<NowPlaying> = _state

    private suspend fun ctrl(): MediaController {
        controller?.let { return it }
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        val c = suspendCancellableCoroutine { cont -> future.addListener({ cont.resume(future.get()) }, MoreExecutors.directExecutor()) }
        c.addListener(object : Player.Listener { override fun onEvents(player: Player, events: Player.Events) = publish() })
        controller = c
        scope.launch(kotlinx.coroutines.Dispatchers.Main) { while (true) { publish(); delay(500) } }
        return c
    }

    private fun publish() {
        val c = controller ?: return
        val md = c.mediaMetadata
        _state.value = NowPlaying(
            title = md.title?.toString() ?: "", artist = md.artist?.toString() ?: "", album = md.albumTitle?.toString() ?: "",
            path = c.currentMediaItem?.mediaId?.takeIf { it.contains("://") }?.let(FsPath::parse),
            isPlaying = c.isPlaying, positionMs = c.currentPosition.coerceAtLeast(0), durationMs = c.duration.coerceAtLeast(0),
            shuffle = c.shuffleModeEnabled, repeat = c.repeatMode, index = c.currentMediaItemIndex,
            queue = (0 until c.mediaItemCount).map { c.getMediaItemAt(it) },
        )
    }

    fun item(path: FsPath, title: String, artist: String? = null, album: String? = null): MediaItem =
        MediaItem.Builder().setMediaId(path.toString()).setUri(FsUri.of(path))
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(FsUri.of(path)).build())
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist(artist).setAlbumTitle(album)
                .setArtworkUri(null).setIsPlayable(true).build()).build()

    fun songItem(s: Song) = item(s.fsPath, s.title, s.artist, s.album)
    fun fileItem(e: FileEntry) = item(e.path, e.name.substringBeforeLast('.'))

    fun play(items: List<MediaItem>, start: Int = 0, shuffle: Boolean = false) = scope.launch(kotlinx.coroutines.Dispatchers.Main) {
        val c = ctrl()
        c.setMediaItems(items, start.coerceIn(0, (items.size - 1).coerceAtLeast(0)), 0)
        c.shuffleModeEnabled = shuffle
        c.prepare(); c.play()
    }

    fun enqueue(items: List<MediaItem>, next: Boolean = false) = scope.launch(kotlinx.coroutines.Dispatchers.Main) {
        val c = ctrl()
        if (c.mediaItemCount == 0) { c.setMediaItems(items); c.prepare(); c.play() }
        else if (next) c.addMediaItems(c.currentMediaItemIndex + 1, items) else c.addMediaItems(items)
    }

    fun toggle() = scope.launch(kotlinx.coroutines.Dispatchers.Main) { ctrl().let { if (it.isPlaying) it.pause() else it.play() } }
    fun next() = scope.launch(kotlinx.coroutines.Dispatchers.Main) { ctrl().seekToNextMediaItem() }
    fun previous() = scope.launch(kotlinx.coroutines.Dispatchers.Main) { ctrl().seekToPrevious() }
    fun seek(ms: Long) = scope.launch(kotlinx.coroutines.Dispatchers.Main) { ctrl().seekTo(ms) }
    fun skipTo(index: Int) = scope.launch(kotlinx.coroutines.Dispatchers.Main) { ctrl().seekTo(index, 0) }
    fun remove(index: Int) = scope.launch(kotlinx.coroutines.Dispatchers.Main) { ctrl().removeMediaItem(index) }
    fun move(from: Int, to: Int) = scope.launch(kotlinx.coroutines.Dispatchers.Main) { ctrl().moveMediaItem(from, to) }
    fun setShuffle(on: Boolean) = scope.launch(kotlinx.coroutines.Dispatchers.Main) { ctrl().shuffleModeEnabled = on }
    fun cycleRepeat() = scope.launch(kotlinx.coroutines.Dispatchers.Main) {
        val c = ctrl()
        c.repeatMode = when (c.repeatMode) { Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL; Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF }
    }
    fun stop() = scope.launch(kotlinx.coroutines.Dispatchers.Main) { ctrl().let { it.stop(); it.clearMediaItems() } }

    /** Ensures the state flow is live (e.g. when the music screen opens while the service already plays). */
    fun connect() = scope.launch(kotlinx.coroutines.Dispatchers.Main) { ctrl() }
}
