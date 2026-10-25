package com.xander.files.ui.viewers

import androidx.compose.animation.core.animate
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch

/**
 * Pinch-zoom / pan / double-tap image. Single-finger drags pass through to the pager while not zoomed,
 * so swiping between photos keeps working.
 */
@Composable
fun ZoomableImage(model: Any, rotation: Float, onTap: () -> Unit, onZoomChanged: (Boolean) -> Unit = {}) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val scope = rememberCoroutineScope()
    Box(
        Modifier.fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onTap() }, onDoubleTap = { tap ->
                    scope.launch {
                        val target = if (scale > 1.2f) 1f else 2.5f
                        val startScale = scale; val startOffset = offset
                        animate(0f, 1f) { v, _ ->
                            scale = startScale + (target - startScale) * v
                            offset = if (target == 1f) startOffset * (1 - v) else Offset((size.width / 2f - tap.x) * (scale - 1), (size.height / 2f - tap.y) * (scale - 1))
                        }
                        onZoomChanged(scale > 1f)
                    }
                })
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val zoom = event.calculateZoom()
                        val pan = event.calculatePan()
                        val multi = event.changes.size > 1
                        if (multi || scale > 1f) {
                            scale = (scale * zoom).coerceIn(1f, 8f)
                            val maxX = size.width * (scale - 1) / 2; val maxY = size.height * (scale - 1) / 2
                            offset = if (scale == 1f) Offset.Zero else Offset((offset.x + pan.x).coerceIn(-maxX, maxX), (offset.y + pan.y).coerceIn(-maxY, maxY))
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                            onZoomChanged(scale > 1f)
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
    ) {
        AsyncImage(
            model = model, contentDescription = null, contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y; rotationZ = rotation
            }
        )
    }
}
