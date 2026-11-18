package com.xander.files.ui.analyzer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.xander.files.R
import kotlin.random.Random

/**
 * A tiny runner to play while storage is scanned (like Chrome's offline dinosaur): a folder hops over files.
 * Tap to jump; hitting a file ends the run, tap again to restart. Best score is remembered.
 */
@Composable
fun WaitingGame(modifier: Modifier = Modifier) {
    val prefs = LocalContext.current.getSharedPreferences("waiting_game", 0)
    val colors = MaterialTheme.colorScheme
    var y by remember { mutableFloatStateOf(0f) }      // folder height above the ground, in px
    var vy by remember { mutableFloatStateOf(0f) }
    var score by remember { mutableIntStateOf(0) }
    var best by remember { mutableIntStateOf(prefs.getInt("best", 0)) }
    var over by remember { mutableStateOf(false) }
    var started by remember { mutableStateOf(false) }
    val obstacles = remember { mutableStateListOf<Offset>() } // x, height
    var w by remember { mutableFloatStateOf(1f) }
    var unit by remember { mutableFloatStateOf(1f) }        // 1 dp in px

    fun restart() { obstacles.clear(); y = 0f; vy = 0f; score = 0; over = false; started = true }
    fun jump() { when { over -> restart(); !started -> { started = true; vy = 620f * unit }; y == 0f -> vy = 620f * unit } }

    LaunchedEffect(Unit) {
        var last = 0L; var spawn = 0f; var dist = 0f
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceAtMost(0.05f)
                last = now
                if (!started || over) return@withFrameNanos
                val speed = (260f + score * 1.2f).coerceAtMost(620f) * unit
                vy -= 1900f * unit * dt; y = (y + vy * dt).coerceAtLeast(0f); if (y == 0f) vy = 0f
                for (i in obstacles.indices) obstacles[i] = obstacles[i].copy(x = obstacles[i].x - speed * dt)
                obstacles.removeAll { it.x < -40f * unit }
                spawn -= speed * dt
                if (spawn <= 0f) { obstacles += Offset(w + 20f * unit, (22 + Random.nextInt(22)) * unit); spawn = (260 + Random.nextInt(260)) * unit }
                dist += speed * dt; score = (dist / (40f * unit)).toInt()
                val px = 48f * unit; val size = 30f * unit
                if (obstacles.any { o -> o.x < px + size && o.x + 22f * unit > px && y < o.y }) {
                    over = true
                    if (score > best) { best = score; prefs.edit().putInt("best", best).apply() }
                }
            }
        }
    }

    Box(modifier.pointerInput(Unit) { detectTapGestures { jump() } }) {
        Canvas(Modifier.matchParentSize()) {
            w = size.width; unit = density
            val ground = size.height * 0.72f
            drawLine(colors.outline, Offset(0f, ground), Offset(size.width, ground), strokeWidth = 2.dp.toPx())
            // The player: a folder.
            val px = 48.dp.toPx(); val s = 30.dp.toPx(); val top = ground - y - s
            drawRoundRect(colors.primary, Offset(px, top + s * 0.18f), Size(s, s * 0.82f), CornerRadius(4.dp.toPx()))
            drawRoundRect(colors.primary, Offset(px, top), Size(s * 0.45f, s * 0.3f), CornerRadius(3.dp.toPx()))
            // Obstacles: files with a folded corner.
            for (o in obstacles) {
                val ow = 22.dp.toPx()
                drawRoundRect(colors.tertiary, Offset(o.x, ground - o.y), Size(ow, o.y), CornerRadius(3.dp.toPx()))
                drawRect(colors.surface, Offset(o.x + ow * 0.62f, ground - o.y), Size(ow * 0.38f, ow * 0.38f))
            }
        }
        Text("$score  ·  ${stringResource(R.string.game_best, best)}", style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.align(Alignment.TopEnd).padding(16.dp))
        if (!started || over) Text(stringResource(if (over) R.string.game_over else R.string.game_start), style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.align(Alignment.Center).padding(16.dp))
    }
}
