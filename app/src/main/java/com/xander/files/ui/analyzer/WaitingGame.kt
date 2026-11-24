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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.xander.files.R
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/** What the butler has to clear. Sizes are in dp; [lift] is the gap under a flying one. */
private enum class Thing(val w: Float, val h: Float) {
    TEAPOT(24f, 20f), SUITCASE(36f, 26f), CACTUS(20f, 42f), CRATES(26f, 26f), CRATES_TALL(26f, 50f),
    ROBOT_VACUUM(34f, 11f), GULL_LOW(28f, 14f), GULL_HIGH(28f, 14f),
}

private class Obstacle(var x: Float, val thing: Thing, val lift: Float)

private val FUR = Color(0xFFD9893B)
private val FUR_LIGHT = Color(0xFFF6DDB8)
private val COAT = Color(0xFF22232B)
private val SHIRT = Color(0xFFFAFAFA)
private val BOW = Color(0xFFC62828)
private val SILVER = Color(0xFFB8BEC7)
private val INK = Color(0xFF15161A)

/**
 * A little runner to play while storage is scanned: a dingo butler, tray in paw, jumps over teapots, suitcases,
 * cacti, crates and robot vacuums, and keeps his head down under high-flying gulls. Tap to hop, hold to jump higher.
 */
@Composable
fun WaitingGame(modifier: Modifier = Modifier) {
    val prefs = LocalContext.current.getSharedPreferences("waiting_game", 0)
    val colors = MaterialTheme.colorScheme
    var score by remember { mutableIntStateOf(0) }
    var best by remember { mutableIntStateOf(prefs.getInt("best", 0)) }
    var over by remember { mutableStateOf(false) }
    var started by remember { mutableStateOf(false) }
    var frame by remember { mutableLongStateOf(0L) } // ticks every frame so the Canvas redraws
    val s = remember { object { var y = 0f; var vy = 0f; var held = false; var dist = 0f; var spawn = 0f; var unit = 1f; var w = 1f; var run = 0f
        val obstacles = ArrayList<Obstacle>() } }

    fun restart() { s.obstacles.clear(); s.y = 0f; s.vy = 0f; s.dist = 0f; s.spawn = 0f; score = 0; over = false; started = true }
    fun press() {
        s.held = true
        when { over -> restart(); !started -> { started = true; s.vy = 640f * s.unit }; s.y == 0f -> s.vy = 640f * s.unit }
    }
    fun release() { s.held = false; if (s.vy > 260f * s.unit) s.vy = 260f * s.unit } // let go early = small hop

    LaunchedEffect(Unit) {
        var last = 0L
        while (true) withFrameNanos { now ->
            val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceAtMost(0.05f)
            last = now; frame = now
            if (!started || over) return@withFrameNanos
            val u = s.unit
            val speed = (250f + score * 1.1f).coerceAtMost(600f) * u
            val gravity = if (s.held && s.vy > 0) 1500f else 2300f
            s.vy -= gravity * u * dt; s.y = (s.y + s.vy * dt).coerceAtLeast(0f); if (s.y == 0f && s.vy < 0) s.vy = 0f
            s.run += speed * dt
            s.obstacles.forEach { it.x -= speed * dt }
            s.obstacles.removeAll { it.x < -60f * u }
            s.spawn -= speed * dt
            if (s.spawn <= 0f) {
                val pool = if (score < 15) Thing.entries.filter { it != Thing.CRATES_TALL && it != Thing.GULL_HIGH } else Thing.entries
                val t = pool[Random.nextInt(pool.size)]
                val lift = when (t) { Thing.GULL_LOW -> 26f + Random.nextInt(18); Thing.GULL_HIGH -> 64f + Random.nextInt(10); else -> 0f }
                s.obstacles += Obstacle(s.w + 30f * u, t, lift)
                // Never closer than one full jump apart, so every gap can be cleared.
                s.spawn = speed * (0.72f + Random.nextFloat() * 0.9f)
            }
            s.dist += speed * dt; score = (s.dist / (40f * u)).toInt()
            // Hit box: the dingo's body and head, a little smaller than drawn so near misses count as misses.
            val left = 54f * u; val right = 86f * u; val bottom = s.y; val top = s.y + 40f * u
            if (s.obstacles.any { o -> o.x < right && o.x + o.thing.w * u > left && bottom < (o.lift + o.thing.h) * u && top > o.lift * u }) {
                over = true
                if (score > best) { best = score; prefs.edit().putInt("best", best).apply() }
            }
        }
    }

    Box(modifier.pointerInput(Unit) { detectTapGestures(onPress = { press(); tryAwaitRelease(); release() }) }) {
        Canvas(Modifier.matchParentSize()) {
            frame // read so every frame redraws
            s.w = size.width; s.unit = density
            val u = density
            val ground = size.height * 0.78f
            // Far hills and clouds drift slower than the ground.
            val hill = colors.surfaceVariant
            for (i in -1..4) {
                val hx = i * 180f * u - (s.run * 0.15f) % (180f * u)
                drawOval(hill, Offset(hx, ground - 46f * u), Size(220f * u, 92f * u))
            }
            for (i in -1..3) {
                val cx = i * 240f * u - (s.run * 0.06f) % (240f * u) + 60f * u
                val cy = ground - 150f * u + (i % 2) * 24f * u
                drawOval(colors.surface.copy(alpha = 0.9f), Offset(cx, cy), Size(54f * u, 18f * u))
                drawOval(colors.surface.copy(alpha = 0.9f), Offset(cx + 14f * u, cy - 9f * u), Size(30f * u, 20f * u))
            }
            drawRect(colors.surfaceContainerHighest, Offset(0f, ground), Size(size.width, size.height - ground))
            drawLine(colors.outline, Offset(0f, ground), Offset(size.width, ground), 2f * u)
            for (i in 0..(size.width / (34f * u)).toInt() + 1) {
                val dx = i * 34f * u - s.run % (34f * u)
                drawLine(colors.outlineVariant, Offset(dx, ground + 10f * u), Offset(dx + 10f * u, ground + 10f * u), 2f * u)
            }
            for (o in s.obstacles) drawThing(o, ground, u, s.run, colors.primary)
            drawButler(50f * u, ground - s.y, u, if (s.y > 0f) null else s.run / (16f * u))
        }
        Text("$score  ·  ${stringResource(R.string.game_best, best)}", style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.align(Alignment.TopEnd).padding(16.dp))
        if (!started || over) Text(stringResource(if (over) R.string.game_over else R.string.game_start), style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 48.dp, start = 16.dp, end = 16.dp))
    }
}

private fun tri(a: Offset, b: Offset, c: Offset) = Path().apply { moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(c.x, c.y); close() }

/** The dingo butler, feet at ([x], [base]). [stride] animates the legs; null while in the air (legs tucked). */
private fun DrawScope.drawButler(x: Float, base: Float, u: Float, stride: Float?) {
    fun p(dx: Float, dy: Float) = Offset(x + dx * u, base - dy * u)
    val swing = stride?.let { sin(it * PI.toFloat()) * 5f } ?: 0f
    // Bushy tail with a pale tip.
    drawOval(FUR, p(-10f, 33f), Size(18f * u, 9f * u))
    drawOval(FUR_LIGHT, p(-12f, 32f), Size(7f * u, 6f * u))
    // Legs: far pair darker, near pair in front.
    val tuck = if (stride == null) 5f else 0f
    fun leg(hipX: Float, dx: Float, c: Color) = drawLine(c, p(hipX, 15f), p(hipX + dx, tuck), 4.5f * u, StrokeCap.Round)
    leg(9f, -swing, FUR.copy(red = 0.7f)); leg(27f, swing, FUR.copy(red = 0.7f))
    leg(12f, swing, FUR); leg(30f, -swing, FUR)
    // Tailcoat with its two tails flapping behind.
    drawPath(tri(p(4f, 20f), p(-6f, 9f + swing * 0.4f), p(12f, 17f)), COAT)
    drawRoundRect(COAT, p(2f, 31f), Size(32f * u, 16f * u), CornerRadius(8f * u))
    drawOval(SHIRT, p(23f, 32f), Size(10f * u, 13f * u))
    drawPath(tri(p(25f, 31f), p(25f, 27f), p(28.5f, 29f)), BOW); drawPath(tri(p(32f, 31f), p(32f, 27f), p(28.5f, 29f)), BOW)
    // Head: ears, face, pale muzzle, nose and eye.
    drawPath(tri(p(25f, 44f), p(27f, 55f), p(31.5f, 45f)), FUR)
    drawPath(tri(p(30f, 45f), p(34f, 55f), p(36.5f, 44f)), FUR)
    drawPath(tri(p(31.5f, 46f), p(33.8f, 52.5f), p(35.3f, 45.5f)), FUR_LIGHT)
    drawOval(FUR, p(23f, 47f), Size(17f * u, 15f * u))
    drawRoundRect(FUR_LIGHT, p(34f, 41f), Size(12f * u, 7f * u), CornerRadius(3.5f * u))
    drawCircle(INK, 2.2f * u, p(45.5f, 38.5f))
    drawCircle(INK, 1.7f * u, p(33f, 42f))
    // Arm in a white glove, carrying a silver tray with a cloche.
    drawLine(COAT, p(26f, 24f), p(38f, 28f), 4f * u, StrokeCap.Round)
    drawCircle(SHIRT, 2.6f * u, p(39f, 28.5f))
    drawLine(SILVER, p(32f, 31f), p(50f, 31f), 2.2f * u, StrokeCap.Round)
    drawArc(SILVER, 180f, 180f, true, p(35f, 39f), Size(12f * u, 16f * u))
    drawCircle(SILVER, 1.4f * u, p(41f, 39.5f))
}

private fun DrawScope.drawThing(o: Obstacle, ground: Float, u: Float, run: Float, accent: Color) {
    val w = o.thing.w * u; val h = o.thing.h * u
    val x = o.x; val bottom = ground - o.lift * u; val top = bottom - h
    when (o.thing) {
        Thing.TEAPOT -> {
            drawOval(accent, Offset(x + 3f * u, top + 5f * u), Size(w - 6f * u, h - 5f * u))
            drawLine(accent, Offset(x + w - 5f * u, bottom - 9f * u), Offset(x + w, top + 3f * u), 3f * u, StrokeCap.Round)
            drawArc(accent, 90f, 180f, false, Offset(x - 1f * u, top + 8f * u), Size(8f * u, 9f * u), style = Stroke(2.5f * u))
            drawCircle(accent, 2.5f * u, Offset(x + w / 2, top + 4f * u))
        }
        Thing.SUITCASE -> {
            drawRoundRect(Color(0xFF8D5A2B), Offset(x, top + 5f * u), Size(w, h - 5f * u), CornerRadius(4f * u))
            drawRoundRect(Color(0xFF5D3A1A), Offset(x + w * 0.35f, top), Size(w * 0.3f, 6f * u), CornerRadius(2f * u), style = Stroke(2.5f * u))
            drawLine(Color(0xFFE0B25A), Offset(x + w * 0.25f, top + 5f * u), Offset(x + w * 0.25f, bottom), 3f * u)
            drawLine(Color(0xFFE0B25A), Offset(x + w * 0.75f, top + 5f * u), Offset(x + w * 0.75f, bottom), 3f * u)
        }
        Thing.CACTUS -> {
            val pot = Color(0xFFC0623A); val green = Color(0xFF3E8E41)
            drawRect(pot, Offset(x + 2f * u, bottom - 11f * u), Size(w - 4f * u, 11f * u))
            drawRect(pot, Offset(x, bottom - 13f * u), Size(w, 3f * u))
            drawRoundRect(green, Offset(x + w * 0.32f, top), Size(w * 0.36f, h - 12f * u), CornerRadius(4f * u))
            drawRoundRect(green, Offset(x + 1f * u, top + 10f * u), Size(5f * u, 12f * u), CornerRadius(2.5f * u))
            drawRoundRect(green, Offset(x + w - 6f * u, top + 6f * u), Size(5f * u, 12f * u), CornerRadius(2.5f * u))
        }
        Thing.CRATES, Thing.CRATES_TALL -> {
            val wood = Color(0xFFB07B45); val dark = Color(0xFF7A5128)
            var y = bottom
            while (y > top + 1f) {
                val ch = minOf(w, y - top)
                drawRect(wood, Offset(x, y - ch), Size(w, ch))
                drawRect(dark, Offset(x, y - ch), Size(w, ch), style = Stroke(2f * u))
                drawLine(dark, Offset(x, y - ch), Offset(x + w, y), 2f * u); drawLine(dark, Offset(x + w, y - ch), Offset(x, y), 2f * u)
                y -= ch
            }
        }
        Thing.ROBOT_VACUUM -> {
            drawOval(Color(0xFF3A3D45), Offset(x, top), Size(w, h))
            drawOval(Color(0xFF5A5E68), Offset(x + 3f * u, top), Size(w - 6f * u, h - 4f * u))
            drawCircle(if ((run / (30f * u)).toInt() % 2 == 0) Color(0xFF4CAF50) else Color(0xFF2E7D32), 2f * u, Offset(x + w / 2, top + 3f * u))
        }
        Thing.GULL_LOW, Thing.GULL_HIGH -> {
            val flap = sin(run / (9f * u)) * 6f * u
            val body = Color(0xFFECEFF1); val wing = Color(0xFF90A4AE)
            drawPath(tri(Offset(x + 8f * u, top + 6f * u), Offset(x + 16f * u, top + 6f * u), Offset(x + 10f * u, top - 2f * u + flap)), wing)
            drawOval(body, Offset(x + 2f * u, top + 4f * u), Size(w - 6f * u, h - 6f * u))
            drawCircle(body, 4f * u, Offset(x + 4f * u, top + 6f * u))
            drawPath(tri(Offset(x, top + 6f * u), Offset(x - 5f * u, top + 7.5f * u), Offset(x, top + 8f * u)), Color(0xFFFFB300))
            drawCircle(INK, 1f * u, Offset(x + 3f * u, top + 5f * u))
            drawPath(tri(Offset(x + 10f * u, top + 7f * u), Offset(x + 20f * u, top + 7f * u), Offset(x + 16f * u, top + 1f * u - flap)), wing)
        }
    }
}
