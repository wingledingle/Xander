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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
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
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
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
    var buffering by remember { mutableStateOf(player.playbackState == Player.STATE_BUFFERING || player.playbackState == Player.STATE_IDLE) }
    var level by remember { mutableStateOf<Pair<ImageVector, Float>?>(null) } // brightness/volume bar while dragging
    var seekSide by remember { mutableIntStateOf(0) }   // -1/+1: which side was double-tapped, for the ripple
    var boosted by remember { mutableStateOf(false) }  // long-press = 2x while held
    var brightness by remember { mutableFloatStateOf(activity?.window?.attributes?.screenBrightness?.takeIf { it >= 0 } ?: 0.5f) }

    fun flash(text: String) { osd = text; level = null; osdTick++ }
    fun showLevel(icon: ImageVector, f: Float) { level = icon to f; osd = null; osdTick++ }
    fun poke() { interaction++ }
    fun seekBy(ms: Long) { player.seekTo((player.currentPosition + ms).coerceIn(0, player.duration.coerceAtLeast(0))); seekSide = if (ms > 0) 1 else -1; osdTick++ }

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
            override fun onPlaybackStateChanged(state: Int) { buffering = state == Player.STATE_BUFFERING }
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
    LaunchedEffect(osdTick) { if (osd != null || level != null || seekSide != 0) { delay(900); osd = null; level = null; seekSide = 0 } }
    LaunchedEffect(interaction, playing, controls, menu) { if (controls && playing && menu == null && scrub == null) { delay(3500); controls = false } }


    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { c ->
            PlayerView(c).apply { useController = false; this.player = player; keepScreenOn = true }
        }, update = { v -> v.resizeMode = fit.mode }, modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = zoom; scaleY = zoom })

        if (inPip) return@Box

        // Gesture layer: tap shows/hides controls, double-tap the sides seeks ±10 s, hold anywhere plays at 2x,
        // horizontal drag scrubs, vertical drag on the left = brightness / right = volume, pinch zooms.
        Box(Modifier.fillMaxSize()
            .pointerInput(locked) {
                detectTapGestures(
                    onTap = { controls = !controls; poke() },
                    onDoubleTap = { o -> if (locked) return@detectTapGestures
                        when { o.x < size.width / 3 -> seekBy(-10_000); o.x > size.width * 2 / 3 -> seekBy(10_000)
                            else -> { if (player.isPlaying) player.pause() else player.play() } } },
                    onPress = {
                        if (locked) return@detectTapGestures
                        // Held for half a second: 2x until released (like YouTube).
                        val released = kotlinx.coroutines.withTimeoutOrNull(500) { tryAwaitRelease() }
                        if (released == null) {
                            boosted = true; player.setPlaybackSpeed(2f)
                            tryAwaitRelease()
                            boosted = false; player.setPlaybackSpeed(speed)
                        }
                    },
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
                            showLevel(Icons.Filled.BrightnessMedium, brightness) }
                        3 -> { acc -= d.y
                            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                            if (abs(acc) > size.height / (max * 1.5f)) { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, if (acc > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, 0); acc = 0f }
                            showLevel(Icons.AutoMirrored.Filled.VolumeUp, audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max) }
                    }
                    change.consume()
                }
            })

        // Feedback that doesn't need the controls: double-tap ripple, level bars, OSD text, 2x badge, buffering.
        if (seekSide != 0) Box(Modifier.align(if (seekSide < 0) Alignment.CenterStart else Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(0.33f)
            .background(Color.White.copy(alpha = 0.12f), if (seekSide < 0) RoundedCornerShape(topEndPercent = 50, bottomEndPercent = 50) else RoundedCornerShape(topStartPercent = 50, bottomStartPercent = 50)),
            contentAlignment = Alignment.Center) {
            Text(if (seekSide < 0) "« 10 s" else "10 s »", color = Color.White, style = MaterialTheme.typography.titleLarge)
        }
        level?.let { (icon, f) ->
            Column(Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(16.dp)).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(icon, null, tint = Color.White, modifier = Modifier.size(28.dp))
                Box(Modifier.padding(top = 10.dp).size(width = 160.dp, height = 6.dp).background(Color.White.copy(alpha = 0.25f), CircleShape)) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(f.coerceIn(0f, 1f)).background(Color.White, CircleShape))
                }
                Text("${(f * 100).toInt()}%", color = Color.White, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
            }
        }
        osd?.let { Text(it, color = Color.White, style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(16.dp)).padding(horizontal = 20.dp, vertical = 12.dp)) }
        if (boosted) Text("2× ▶▶", color = Color.White, style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 12.dp).background(Color.Black.copy(alpha = 0.6f), CircleShape).padding(horizontal = 14.dp, vertical = 6.dp))
        if (buffering && error == null && !controls) CircularProgressIndicator(Modifier.align(Alignment.Center).size(56.dp), color = Color.White, strokeWidth = 4.dp)
        error?.let { Text(it, color = Color.White, modifier = Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(12.dp)).padding(16.dp)) }

        if (locked) {
            AnimatedVisibility(controls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.CenterStart).padding(16.dp)) {
                Round(Icons.Filled.Lock, R.string.unlock) { locked = false; poke() }
            }
            return@Box
        }

        AnimatedVisibility(controls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                // Scrims only where the controls are, so the picture stays visible.
                Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().fillMaxHeight(0.28f).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.75f), Color.Transparent))))
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.42f).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f)))))

                // Top: back, title with "2 / 14", cast.
                Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack, modifier = Modifier.size(52.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), tint = Color.White) }
                    Column(Modifier.weight(1f).padding(start = 4.dp)) {
                        Text(title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                        if (playlist.size > 1) Text("${player.currentMediaItemIndex + 1} / ${playlist.size}", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelMedium)
                    }
                    castButton()
                }

                // Centre: previous, play/pause (big), next. Seeking ±10 s is a double-tap on either side.
                Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(36.dp), verticalAlignment = Alignment.CenterVertically) {
                    Round(Icons.Filled.SkipPrevious, R.string.previous, enabled = player.hasPreviousMediaItem() || position > 3000) { player.seekToPrevious(); poke() }
                    Box(contentAlignment = Alignment.Center) {
                        Round(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, R.string.play, big = true) {
                            if (player.playbackState == Player.STATE_ENDED) player.seekTo(0); if (playing) player.pause() else player.play(); poke() }
                        if (buffering && error == null) CircularProgressIndicator(Modifier.size(84.dp), color = Color.White, strokeWidth = 3.dp)
                    }
                    Round(Icons.Filled.SkipNext, R.string.next, enabled = player.hasNextMediaItem()) { player.seekToNext(); poke() }
                }

                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    // "Up next" in the last 10 seconds.
                    val next = playlist.getOrNull(player.currentMediaItemIndex + 1)
                    if (next != null && duration > 0 && duration - position < 10_000) Text(stringResource(R.string.up_next, next.name), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelLarge, modifier = Modifier.align(Alignment.End).padding(bottom = 6.dp)
                            .background(Color.Black.copy(alpha = 0.6f), CircleShape).clickable { player.seekToNext(); poke() }.padding(horizontal = 14.dp, vertical = 8.dp))
                    val shown = scrub?.let { (it * duration).toLong() } ?: position
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(Format.duration(shown / 1000), color = Color.White, style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.weight(1f))
                        Text(if (showRemaining) "-" + Format.duration((duration - shown).coerceAtLeast(0) / 1000) else Format.duration(duration / 1000),
                            color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.clickable { showRemaining = !showRemaining }.padding(4.dp))
                    }
                    SeekBar(
                        progress = scrub ?: if (duration > 0) position.toFloat() / duration else 0f,
                        buffered = if (duration > 0) buffered.toFloat() / duration else 0f,
                        scrubbing = scrub != null,
                        onScrub = { scrub = it; poke() },
                        onDone = { scrub?.let { player.seekTo((it * duration).toLong()) }; scrub = null },
                    )
                    // Tools as labelled buttons: easier to hit and to understand than a row of bare icons.
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ToolMenu(Icons.Filled.Speed, if (speed == 1f) stringResource(R.string.speed) else "${speed}×", menu == "speed", { menu = if (it) "speed" else null; poke() },
                            listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 3f).map { sp -> (if (sp == speed) "✓ " else "") + "${sp}×" to { speed = sp; player.setPlaybackSpeed(sp) } })
                        ToolMenu(Icons.Filled.Subtitles, stringResource(R.string.subtitles), menu == "subs", { menu = if (it) "subs" else null; poke() }, vm.textTracks())
                        ToolMenu(Icons.Filled.Audiotrack, stringResource(R.string.audio_track), menu == "audio", { menu = if (it) "audio" else null; poke() }, vm.audioTracks())
                        ToolChip(Icons.Filled.AspectRatio, fit.label) { fit = Fit.entries[(fit.ordinal + 1) % Fit.entries.size]; zoom = 1f; flash(fit.label); poke() }
                        ToolChip(Icons.Filled.ScreenRotation, when (orientationLock) { 1 -> "Landscape"; 2 -> "Portrait"; else -> "Auto" }) {
                            orientationLock = (orientationLock + 1) % 3
                            activity?.requestedOrientation = when (orientationLock) { 1 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE; 2 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT; else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR }
                            poke()
                        }
                        ToolChip(Icons.Filled.LockOpen, stringResource(R.string.lock_controls)) { locked = true; controls = true; poke() }
                        if (Build.VERSION.SDK_INT >= 26) ToolChip(Icons.Filled.PictureInPictureAlt, stringResource(R.string.pip)) {
                            (activity as? Activity)?.enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(
                                player.videoSize.let { if (it.width > 0 && it.height > 0) Rational(it.width, it.height).let { r -> if (r.toFloat() in 0.42f..2.39f) r else Rational(16, 9) } else Rational(16, 9) }
                            ).build())
                        }
                    }
                }
            }
        }
    }
}

/** A thick seek bar with the buffered part shown; tap or drag anywhere on its 44 dp tall touch area. */
@Composable
private fun SeekBar(progress: Float, buffered: Float, scrubbing: Boolean, onScrub: (Float) -> Unit, onDone: () -> Unit) {
    val scrub by rememberUpdatedState(onScrub); val done by rememberUpdatedState(onDone)
    val accent = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxWidth().height(44.dp).pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown()
            scrub((down.position.x / size.width).coerceIn(0f, 1f)); down.consume()
            while (true) {
                val ev = awaitPointerEvent()
                val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                if (!c.pressed) break
                scrub((c.position.x / size.width).coerceIn(0f, 1f)); c.consume()
            }
            done()
        }
    }) {
        val h = (if (scrubbing) 8 else 5).dp.toPx(); val y = size.height / 2
        val r = CornerRadius(h / 2)
        drawRoundRect(Color.White.copy(alpha = 0.25f), Offset(0f, y - h / 2), Size(size.width, h), r)
        drawRoundRect(Color.White.copy(alpha = 0.45f), Offset(0f, y - h / 2), Size(size.width * buffered.coerceIn(0f, 1f), h), r)
        drawRoundRect(accent, Offset(0f, y - h / 2), Size(size.width * progress.coerceIn(0f, 1f), h), r)
        drawCircle(accent, (if (scrubbing) 11 else 8).dp.toPx(), Offset(size.width * progress.coerceIn(0f, 1f), y))
    }
}

@Composable
private fun Round(icon: ImageVector, label: Int, big: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(if (big) 84.dp else 60.dp).background(Color.Black.copy(alpha = 0.4f), CircleShape)) {
        Icon(icon, stringResource(label), tint = if (enabled) Color.White else Color.White.copy(alpha = 0.35f), modifier = Modifier.size(if (big) 52.dp else 34.dp))
    }
}

@Composable
private fun ToolChip(icon: ImageVector, text: String, onClick: () -> Unit) {
    Row(Modifier.heightIn(min = 44.dp).background(Color.White.copy(alpha = 0.14f), CircleShape).clickable(onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(20.dp))
        Text(text, color = Color.White, style = MaterialTheme.typography.labelLarge, maxLines = 1, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun ToolMenu(icon: ImageVector, text: String, open: Boolean, onOpen: (Boolean) -> Unit, items: List<Pair<String, () -> Unit>>) {
    Box {
        ToolChip(icon, text) { onOpen(true) }
        DropdownMenu(open, { onOpen(false) }) {
            if (items.isEmpty()) DropdownMenuItem(text = { Text("—") }, onClick = { onOpen(false) })
            items.forEach { (l, a) -> DropdownMenuItem(text = { Text(l) }, onClick = { onOpen(false); a() }) }
        }
    }
}
