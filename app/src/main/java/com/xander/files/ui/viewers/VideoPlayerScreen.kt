package com.xander.files.ui.viewers

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.util.Consumer
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.xander.files.R
import com.xander.files.data.media.FsUri
import com.xander.files.data.media.MediaLibraryHelper
import com.xander.files.data.media.RoutingDataSourceFactory
import com.xander.files.domain.fs.FileCategory
import com.xander.files.domain.fs.FileEntry
import com.xander.files.domain.fs.FileSystemRegistry
import com.xander.files.domain.fs.FsPath
import com.xander.files.ui.common.Format
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(UnstableApi::class)
@HiltViewModel
class VideoPlayerViewModel @Inject constructor(
    saved: SavedStateHandle,
    @ApplicationContext context: Context,
    private val registry: FileSystemRegistry,
    private val media: MediaLibraryHelper,
    val cast: com.xander.files.data.cast.CastManager,
) : ViewModel() {
    val start: FsPath = FsPath.parse(saved.get<String>("path")!!)
    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(RoutingDataSourceFactory(context, registry)))
        .setHandleAudioBecomingNoisy(true)
        .build()
    val playlist = MutableStateFlow<List<FileEntry>>(emptyList())
    val error = MutableStateFlow<String?>(null)
    val title = MutableStateFlow(start.name)

    init {
        player.addListener(object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) { error.value = e.localizedMessage }
            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                title.value = item?.mediaMetadata?.title?.toString() ?: ""
                // Restore the resume point of the newly selected item.
                val p = item?.localConfiguration?.uri?.let(FsUri::parse) ?: return
                viewModelScope.launch { media.position(p).takeIf { it > 0 }?.let { player.seekTo(it) } }
            }
        })
        viewModelScope.launch {
            val list = media.siblings(start, setOf(FileCategory.VIDEO)).ifEmpty { listOf(FileEntry(start, false)) }
            playlist.value = list
            val items = list.map { e -> item(e) }
            val index = list.indexOfFirst { it.path == start }.coerceAtLeast(0)
            player.setMediaItems(items, index, media.position(start))
            player.prepare()
            player.playWhenReady = true
        }
        viewModelScope.launch { while (true) { delay(5000); savePosition() } }
        // When a Cast session connects, hand the current video (with its subtitles) to the TV and pause here.
        viewModelScope.launch { cast.device.collect { if (it != null) castCurrent() } }
    }

    fun castCurrent() {
        val p = player.currentMediaItem?.localConfiguration?.uri?.let(FsUri::parse) ?: return
        val e = playlist.value.firstOrNull { it.path == p } ?: FileEntry(p, false)
        viewModelScope.launch {
            if (cast.cast(e, player.currentPosition.coerceAtLeast(0), media.subtitlesFor(p))) player.pause()
            else error.value = "Cast needs the phone on Wi-Fi"
        }
    }

    private suspend fun item(e: FileEntry): MediaItem {
        val subs = media.subtitlesFor(e.path).map { s ->
            MediaItem.SubtitleConfiguration.Builder(FsUri.of(s.path))
                .setMimeType(when (s.extension) { "srt" -> MimeTypes.APPLICATION_SUBRIP; "vtt" -> MimeTypes.TEXT_VTT; "ttml" -> MimeTypes.APPLICATION_TTML; else -> MimeTypes.TEXT_SSA })
                .setLanguage(s.name.removePrefix(e.name.substringBeforeLast('.')).trim('.').substringBeforeLast('.').ifEmpty { null })
                .setLabel(s.name).setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()
        }
        return MediaItem.Builder().setUri(FsUri.of(e.path)).setMediaId(e.path.toString())
            .setMediaMetadata(MediaMetadata.Builder().setTitle(e.name).build())
            .setSubtitleConfigurations(subs).build()
    }

    fun savePosition() {
        val p = player.currentMediaItem?.localConfiguration?.uri?.let(FsUri::parse) ?: return
        val pos = player.currentPosition; val dur = player.duration
        viewModelScope.launch { media.savePosition(p, pos, if (dur == C.TIME_UNSET) 0 else dur) }
    }

    fun textTracks(): List<Pair<String, () -> Unit>> {
        val groups = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
        val off = "Off" to { player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build() }
        return listOf(off) + groups.flatMap { g ->
            (0 until g.length).map { i ->
                val f = g.getTrackFormat(i)
                (f.label ?: f.language ?: "Track ${i + 1}") to {
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i)).build()
                }
            }
        }
    }

    fun audioTracks(): List<Pair<String, () -> Unit>> {
        val groups = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        return groups.flatMap { g ->
            (0 until g.length).map { i ->
                val f = g.getTrackFormat(i)
                listOfNotNull(f.label, f.language, f.sampleMimeType?.substringAfter('/'), f.channelCount.takeIf { it > 0 }?.let { "${it}ch" }).joinToString(" · ").ifEmpty { "Track ${i + 1}" } to {
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i)).build()
                }
            }
        }
    }

    override fun onCleared() { savePosition(); player.release() }
}

