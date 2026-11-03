package com.unfiltered.studio.presentation.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import java.io.File

/**
 * Pinch-to-zoom / pan / double-tap image that cooperates with a HorizontalPager: single-finger
 * drags are only consumed while zoomed in, so swiping between images keeps working.
 */
@Composable
fun ZoomableImage(
    path: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    onTap: () -> Unit = {},
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    val scale = remember(path) { Animatable(1f) }
    var offset by remember(path) { mutableStateOf(Offset.Zero) }
    val scope = rememberCoroutineScope()

    fun clampOffset(raw: Offset, s: Float): Offset {
        val maxX = (size.width * (s - 1f)) / 2f
        val maxY = (size.height * (s - 1f)) / 2f
        return Offset(raw.x.coerceIn(-maxX, maxX), raw.y.coerceIn(-maxY, maxY))
    }

    Box(
        modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .pointerInput(path) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { tap ->
                        scope.launch {
                            if (scale.value > 1.05f) {
                                offset = Offset.Zero
                                scale.animateTo(1f)
                            } else {
                                val target = 2.5f
                                val center = Offset(size.width / 2f, size.height / 2f)
                                offset = clampOffset((center - tap) * (target - 1f), target)
                                scale.animateTo(target)
                            }
                        }
                    },
                )
            }
            .pointerInput(path) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val multiTouch = event.changes.count { it.pressed } > 1
                        val zoom = event.calculateZoom()
                        val pan = event.calculatePan()
                        if (multiTouch || scale.value > 1.01f) {
                            val newScale = (scale.value * zoom).coerceIn(1f, 6f)
                            val centroid = event.calculateCentroid(useCurrent = true)
                            val center = Offset(size.width / 2f, size.height / 2f)
                            val zoomed = (offset + (centroid - center) * (1 - zoom)) + pan
                            offset = if (newScale <= 1f) Offset.Zero else clampOffset(zoomed, newScale)
                            scope.launch { scale.snapTo(newScale) }
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = File(path),
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                    translationX = offset.x
                    translationY = offset.y
                },
        )
    }
}
