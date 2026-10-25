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

    override fun onCleared() { savePosition(); player.release() }
}

@OptIn(UnstableApi::class)
@Composable
fun VideoPlayerScreen(onBack: () -> Unit, castButton: @Composable () -> Unit = {}, vm: VideoPlayerViewModel = hiltViewModel()) {
    val ctx = LocalContext.current
    val activity = ctx as? ComponentActivity
    val title by vm.title.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    var inPip by remember { mutableStateOf(false) }
    var chrome by remember { mutableStateOf(true) }
    var gestureLabel by remember { mutableStateOf<String?>(null) }
    var speedMenu by remember { mutableStateOf(false) }
    var subsMenu by remember { mutableStateOf(false) }
    val audio = remember { ctx.getSystemService(AudioManager::class.java) }
    var brightness by remember { mutableFloatStateOf(activity?.window?.attributes?.screenBrightness?.takeIf { it >= 0 } ?: 0.5f) }

    DisposableEffect(activity) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val l = Consumer<PictureInPictureModeChangedInfo> { inPip = it.isInPictureInPictureMode }
        activity?.addOnPictureInPictureModeChangedListener(l)
        if (Build.VERSION.SDK_INT >= 31) activity?.setPictureInPictureParams(PictureInPictureParams.Builder().setAutoEnterEnabled(true).setAspectRatio(Rational(16, 9)).build())
        onDispose {
            vm.savePosition()
            activity?.removeOnPictureInPictureModeChangedListener(l)
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            activity?.window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE } }
            if (Build.VERSION.SDK_INT >= 31) activity?.setPictureInPictureParams(PictureInPictureParams.Builder().setAutoEnterEnabled(false).build())
        }
    }
    LaunchedEffect(gestureLabel) { if (gestureLabel != null) { delay(800); gestureLabel = null } }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { c ->
            PlayerView(c).apply {
                player = vm.player
                setShowSubtitleButton(true)
                setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v -> chrome = v == android.view.View.VISIBLE })
            }
        }, modifier = Modifier.fillMaxSize())

        if (!inPip) {
            // Gestures: horizontal drag seeks; vertical drag on the left = brightness, on the right = volume.
            Box(Modifier.fillMaxSize().padding(top = 80.dp, bottom = 120.dp).pointerInput(Unit) {
                var mode = 0; var startPos = 0L; var acc = 0f
                detectDragGestures(
                    onDragStart = { mode = 0; acc = 0f; startPos = vm.player.currentPosition },
                    onDragEnd = { if (mode == 1) vm.player.seekTo(startPos + (acc / size.width * 120_000).toLong()) },
                ) { change, drag ->
                    if (mode == 0) mode = if (kotlin.math.abs(drag.x) > kotlin.math.abs(drag.y)) 1 else if (change.position.x < size.width / 2) 2 else 3
                    when (mode) {
                        1 -> { acc += drag.x; val target = (startPos + (acc / size.width * 120_000).toLong()).coerceAtLeast(0); gestureLabel = Format.duration(target / 1000) + " / " + Format.duration(vm.player.duration.coerceAtLeast(0) / 1000) }
                        2 -> {
                            brightness = (brightness - drag.y / size.height).coerceIn(0.01f, 1f)
                            activity?.window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = brightness } }
                            gestureLabel = "☀ ${(brightness * 100).toInt()}%"
                        }
                        3 -> {
                            acc -= drag.y
                            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                            val step = size.height / (max * 1.5f)
                            if (kotlin.math.abs(acc) > step) {
                                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, if (acc > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, 0)
                                acc = 0f
                            }
                            gestureLabel = "🔊 ${audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max}%"
                        }
                    }
                    change.consume()
                }
            })
            gestureLabel?.let { Text(it, color = Color.White, style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(12.dp)).padding(16.dp)) }
            if (chrome) Row(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.4f)).statusBarsPadding().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), tint = Color.White) }
                Text(title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                castButton()
                Box {
                    IconButton(onClick = { subsMenu = true }) { Icon(Icons.Filled.Subtitles, stringResource(R.string.subtitles), tint = Color.White) }
                    DropdownMenu(subsMenu, { subsMenu = false }) { vm.textTracks().forEach { (l, a) -> DropdownMenuItem(text = { Text(l) }, onClick = { subsMenu = false; a() }) } }
                }
                Box {
                    IconButton(onClick = { speedMenu = true }) { Icon(Icons.Filled.Speed, stringResource(R.string.speed), tint = Color.White) }
                    DropdownMenu(speedMenu, { speedMenu = false }) {
                        listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 3f).forEach { sp ->
                            DropdownMenuItem(text = { Text("${sp}×") }, onClick = { speedMenu = false; vm.player.setPlaybackSpeed(sp) })
                        }
                    }
                }
                if (Build.VERSION.SDK_INT >= 26) IconButton(onClick = {
                    (activity as? Activity)?.enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(
                        vm.player.videoSize.let { if (it.width > 0 && it.height > 0) Rational(it.width, it.height).let { r -> if (r.toFloat() in 0.42f..2.39f) r else Rational(16, 9) } else Rational(16, 9) }
                    ).build())
                }) { Icon(Icons.Filled.PictureInPictureAlt, stringResource(R.string.pip), tint = Color.White) }
            }
            error?.let { Text(it, color = Color.White, modifier = Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = 0.7f)).padding(16.dp)) }
        }
    }
}
