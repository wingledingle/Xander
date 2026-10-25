package com.xander.files.ui.viewers

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.os.Build
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.util.Consumer
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.xander.files.R
import com.xander.files.ui.common.Format
import kotlinx.coroutines.delay
import kotlin.math.abs

private enum class Fit(val mode: Int, val label: String) {
    FIT(AspectRatioFrameLayout.RESIZE_MODE_FIT, "Fit"), FILL(AspectRatioFrameLayout.RESIZE_MODE_ZOOM, "Crop"),
    STRETCH(AspectRatioFrameLayout.RESIZE_MODE_FILL, "Stretch")
}

@OptIn(UnstableApi::class)
@Composable
fun VideoPlayerScreen(onBack: () -> Unit, castButton: @Composable () -> Unit = {}, vm: VideoPlayerViewModel = hiltViewModel()) {
    val ctx = LocalContext.current
    val activity = ctx as? ComponentActivity
    val player = vm.player
    val title by vm.title.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val playlist by vm.playlist.collectAsStateWithLifecycle()
    val audio = remember { ctx.getSystemService(AudioManager::class.java) }

    var inPip by remember { mutableStateOf(false) }
    var controls by remember { mutableStateOf(true) }
    var locked by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(player.isPlaying) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var buffered by remember { mutableLongStateOf(0L) }
    var scrub by remember { mutableStateOf<Float?>(null) }
    var showRemaining by remember { mutableStateOf(false) }
    var fit by remember { mutableStateOf(Fit.FIT) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var osd by remember { mutableStateOf<String?>(null) }
    var osdTick by remember { mutableIntStateOf(0) }
    var interaction by remember { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf<String?>(null) }
    var speed by remember { mutableFloatStateOf(1f) }
    var orientationLock by remember { mutableIntStateOf(0) } // 0 auto, 1 landscape, 2 portrait
    var brightness by remember { mutableFloatStateOf(activity?.window?.attributes?.screenBrightness?.takeIf { it >= 0 } ?: 0.5f) }

    fun flash(text: String) { osd = text; osdTick++ }
    fun poke() { interaction++ }
    fun seekBy(ms: Long) { player.seekTo((player.currentPosition + ms).coerceIn(0, player.duration.coerceAtLeast(0))); flash(if (ms > 0) "+${ms / 1000}s" else "${ms / 1000}s") }

    // Fullscreen, keep-awake, PiP wiring, brightness restore.
    DisposableEffect(activity) {
        val w = activity?.window
        w?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        w?.let { WindowCompat.getInsetsController(it, it.decorView).apply { systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE; hide(WindowInsetsCompat.Type.systemBars()) } }
        val l = Consumer<PictureInPictureModeChangedInfo> { inPip = it.isInPictureInPictureMode }
        activity?.addOnPictureInPictureModeChangedListener(l)
        if (Build.VERSION.SDK_INT >= 31) activity?.setPictureInPictureParams(PictureInPictureParams.Builder().setAutoEnterEnabled(true).build())
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onVideoSizeChanged(size: androidx.media3.common.VideoSize) {
                // Auto-rotate to the video's shape unless the user locked an orientation.
                if (orientationLock == 0 && size.width > 0 && size.height > 0) activity?.requestedOrientation =
                    if (size.width * size.pixelWidthHeightRatio >= size.height) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            }
        }
        player.addListener(listener)
        onDispose {
            vm.savePosition()
            player.removeListener(listener)
            activity?.removeOnPictureInPictureModeChangedListener(l)
            w?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            w?.let { it.attributes = it.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE } }
            w?.let { WindowCompat.getInsetsController(it, it.decorView).show(WindowInsetsCompat.Type.systemBars()) }
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            if (Build.VERSION.SDK_INT >= 31) activity?.setPictureInPictureParams(PictureInPictureParams.Builder().setAutoEnterEnabled(false).build())
        }
    }
    LaunchedEffect(Unit) { while (true) { position = player.currentPosition.coerceAtLeast(0); duration = player.duration.coerceAtLeast(0); buffered = player.bufferedPosition; delay(250) } }
    LaunchedEffect(osdTick) { if (osd != null) { delay(900); osd = null } }
    LaunchedEffect(interaction, playing, controls, menu) { if (controls && playing && menu == null && scrub == null) { delay(3500); controls = false } }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { c ->
            PlayerView(c).apply { useController = false; this.player = player; keepScreenOn = true }
        }, update = { v -> v.resizeMode = fit.mode }, modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = zoom; scaleY = zoom })

        if (inPip) return@Box

        // Gesture layer: tap toggles controls, double-tap sides seek ±10 s, horizontal drag scrubs,
        // vertical drag left = brightness / right = volume, pinch zooms.
        Box(Modifier.fillMaxSize()
            .pointerInput(locked) {
                detectTapGestures(
                    onTap = { controls = !controls; poke() },
                    onDoubleTap = { o -> if (locked) return@detectTapGestures
                        when { o.x < size.width / 3 -> seekBy(-10_000); o.x > size.width * 2 / 3 -> seekBy(10_000)
                            else -> { if (player.isPlaying) player.pause() else player.play() } } },
                )
            }
            .pointerInput(locked) {
                if (locked) return@pointerInput
                detectTransformGestures { _, _, z, _ -> if (z != 1f) { zoom = (zoom * z).coerceIn(1f, 4f); flash("${(zoom * 100).toInt()}%") } }
            }
            .pointerInput(locked) {
                if (locked) return@pointerInput
                var mode = 0; var start = 0L; var acc = 0f
                detectDragGestures(
                    onDragStart = { mode = 0; acc = 0f; start = player.currentPosition },
                    onDragEnd = { if (mode == 1) player.seekTo((start + (acc / size.width * 90_000).toLong()).coerceIn(0, duration)); mode = 0 },
                ) { change, d ->
                    if (mode == 0) mode = if (abs(d.x) > abs(d.y)) 1 else if (change.position.x < size.width / 2) 2 else 3
                    when (mode) {
                        1 -> { acc += d.x; val t = (start + (acc / size.width * 90_000).toLong()).coerceIn(0, duration)
                            val delta = (t - start) / 1000; flash("${Format.duration(t / 1000)}  (${if (delta >= 0) "+" else ""}${delta}s)") }
                        2 -> { brightness = (brightness - d.y / size.height).coerceIn(0.01f, 1f)
                            activity?.window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = brightness } }
                            flash("☀ ${(brightness * 100).toInt()}%") }
                        3 -> { acc -= d.y
                            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                            if (abs(acc) > size.height / (max * 1.5f)) { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, if (acc > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, 0); acc = 0f }
                            flash("🔊 ${audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max}%") }
                    }
                    change.consume()
                }
            })

        osd?.let { Text(it, color = Color.White, style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(12.dp)).padding(horizontal = 20.dp, vertical = 12.dp)) }
        error?.let { Text(it, color = Color.White, modifier = Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = 0.7f)).padding(16.dp)) }

        if (locked) {
            AnimatedVisibility(controls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.CenterStart).padding(16.dp)) {
                Round(Icons.Filled.Lock, R.string.unlock) { locked = false; poke() }
            }
            return@Box
        }

        AnimatedVisibility(controls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f))) {
                // Top bar
                Row(Modifier.fillMaxWidth().statusBarsPadding().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), tint = Color.White) }
                    Text(title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    castButton()
                    MenuButton(Icons.Filled.Audiotrack, R.string.audio_track, menu == "audio", { menu = if (it) "audio" else null; poke() }, vm.audioTracks())
                    MenuButton(Icons.Filled.Subtitles, R.string.subtitles, menu == "subs", { menu = if (it) "subs" else null; poke() }, vm.textTracks())
                    MenuButton(Icons.Filled.Speed, R.string.speed, menu == "speed", { menu = if (it) "speed" else null; poke() },
                        listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 3f, 4f).map { sp -> (if (sp == speed) "✓ " else "") + "${sp}×" to { speed = sp; player.setPlaybackSpeed(sp) } })
                }
                // Center transport
                Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Round(Icons.Filled.SkipPrevious, R.string.previous, enabled = player.hasPreviousMediaItem() || position > 3000) { player.seekToPrevious(); poke() }
                    Round(Icons.Filled.Replay10, R.string.back) { seekBy(-10_000); poke() }
                    Round(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, R.string.play, big = true) {
                        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0); if (playing) player.pause() else player.play(); poke() }
                    Round(Icons.Filled.Forward10, R.string.forward) { seekBy(10_000); poke() }
                    Round(Icons.Filled.SkipNext, R.string.next, enabled = player.hasNextMediaItem()) { player.seekToNext(); poke() }
                }
                // Bottom: seek bar + tools
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val shown = scrub?.let { (it * duration).toLong() } ?: position
                        Text(Format.duration(shown / 1000), color = Color.White, style = MaterialTheme.typography.labelMedium)
                        Slider(
                            value = scrub ?: if (duration > 0) position.toFloat() / duration else 0f,
                            onValueChange = { scrub = it; poke() },
                            onValueChangeFinished = { scrub?.let { player.seekTo((it * duration).toLong()) }; scrub = null },
                            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = MaterialTheme.colorScheme.primary, inactiveTrackColor = Color.White.copy(alpha = 0.3f)),
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                        )
                        Text(if (showRemaining) "-" + Format.duration((duration - shown).coerceAtLeast(0) / 1000) else Format.duration(duration / 1000),
                            color = Color.White, style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.pointerInput(Unit) { detectTapGestures { showRemaining = !showRemaining } })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { locked = true; controls = true; poke() }) { Icon(Icons.Filled.LockOpen, stringResource(R.string.lock_controls), tint = Color.White) }
                        IconButton(onClick = { fit = Fit.entries[(fit.ordinal + 1) % Fit.entries.size]; zoom = 1f; flash(fit.label); poke() }) { Icon(Icons.Filled.AspectRatio, stringResource(R.string.aspect_ratio), tint = Color.White) }
                        IconButton(onClick = {
                            orientationLock = (orientationLock + 1) % 3
                            activity?.requestedOrientation = when (orientationLock) { 1 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE; 2 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT; else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR }
                            flash(when (orientationLock) { 1 -> "Landscape"; 2 -> "Portrait"; else -> "Auto-rotate" }); poke()
                        }) { Icon(Icons.Filled.ScreenRotation, stringResource(R.string.rotate), tint = Color.White) }
                        if (Build.VERSION.SDK_INT >= 26) IconButton(onClick = {
                            (activity as? Activity)?.enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(
                                player.videoSize.let { if (it.width > 0 && it.height > 0) Rational(it.width, it.height).let { r -> if (r.toFloat() in 0.42f..2.39f) r else Rational(16, 9) } else Rational(16, 9) }
                            ).build())
                        }) { Icon(Icons.Filled.PictureInPictureAlt, stringResource(R.string.pip), tint = Color.White) }
                        Text(if (playlist.size > 1) "${player.currentMediaItemIndex + 1} / ${playlist.size}" else "", color = Color.White.copy(alpha = 0.8f),
                            style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f).padding(start = 8.dp))
                        if (speed != 1f) Text("${speed}×", color = Color.White, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun Round(icon: ImageVector, label: Int, big: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(if (big) 76.dp else 56.dp).background(Color.Black.copy(alpha = 0.35f), CircleShape)) {
        Icon(icon, stringResource(label), tint = if (enabled) Color.White else Color.White.copy(alpha = 0.35f), modifier = Modifier.size(if (big) 44.dp else 30.dp))
    }
}

@Composable
private fun MenuButton(icon: ImageVector, label: Int, open: Boolean, onOpen: (Boolean) -> Unit, items: List<Pair<String, () -> Unit>>) {
    Box {
        IconButton(onClick = { onOpen(true) }) { Icon(icon, stringResource(label), tint = Color.White) }
        DropdownMenu(open, { onOpen(false) }) {
            if (items.isEmpty()) DropdownMenuItem(text = { Text("—") }, onClick = { onOpen(false) })
            items.forEach { (l, a) -> DropdownMenuItem(text = { Text(l) }, onClick = { onOpen(false); a() }) }
        }
    }
}
