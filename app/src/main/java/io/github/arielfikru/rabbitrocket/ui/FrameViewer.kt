package io.github.arielfikru.rabbitrocket.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.arielfikru.rabbitrocket.fmtDuration
import kotlinx.coroutines.launch
import kotlin.math.abs

/** One frame for [FrameViewer]; [load] runs when its page comes into view, so only nearby pages hold pixels. */
class ViewerFrame(val timeSec: Double, val load: suspend () -> ImageBitmap?)

private const val MAX_ZOOM = 5f
private const val DOUBLE_TAP_ZOOM = 2.5f

/**
 * Full-screen frames: pinch or double-tap to zoom, drag to pan, swipe sideways between frames,
 * swipe down / tap outside / back to close. [onPlay] gets the time of the frame on screen.
 */
@Composable
fun FrameViewer(frames: List<ViewerFrame>, start: Int, onPlay: (Double) -> Unit, onDismiss: () -> Unit) {
    if (frames.isEmpty()) return
    val pager = rememberPagerState(initialPage = start.coerceIn(0, frames.lastIndex)) { frames.size }
    val scope = rememberCoroutineScope()
    // Zoom of the page on screen; sideways swipes belong to the image while it is zoomed.
    var zoomed by remember { mutableStateOf(false) }
    val drop = remember { Animatable(0f) }
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, tween(320, easing = ExpoOut)) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val fade = (1f - abs(drop.value) / 900f).coerceIn(0.3f, 1f)
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer { alpha = enter.value }
                .background(Color.Black.copy(alpha = fade))
                .pointerInput(zoomed) {
                    if (zoomed) return@pointerInput
                    detectVerticalDragGestures(
                        onDragEnd = {
                            scope.launch {
                                if (abs(drop.value) > 140.dp.toPx()) onDismiss()
                                else drop.animateTo(0f, spring(dampingRatio = 0.75f, stiffness = 400f))
                            }
                        },
                        onDragCancel = { scope.launch { drop.animateTo(0f) } },
                        onVerticalDrag = { change, dy ->
                            change.consume()
                            scope.launch { drop.snapTo(drop.value + dy) }
                        },
                    )
                },
        ) {
            HorizontalPager(
                state = pager,
                userScrollEnabled = !zoomed,
                beyondViewportPageCount = 1,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    translationY = drop.value
                    val s = 0.94f + 0.06f * enter.value - abs(drop.value) / 6000f
                    scaleX = s
                    scaleY = s
                },
            ) { page ->
                ZoomableFrame(
                    frame = frames[page],
                    active = page == pager.currentPage,
                    onZoomChange = { if (page == pager.currentPage) zoomed = it },
                    onTapOutside = onDismiss,
                )
            }

            val current = frames[pager.currentPage]
            Row(
                Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing).padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(48.dp).clip(CircleShape).background(RR.NightSky.copy(alpha = 0.7f))
                        .clickable(role = Role.Button, onClickLabel = "Tutup", onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Close, "Tutup", tint = RR.Cream)
                }
                Spacer(Modifier.weight(1f))
                Pill("${fmtDuration(current.timeSec)} · ${pager.currentPage + 1}/${frames.size}", RR.NightSky.copy(alpha = 0.8f), RR.Cream)
            }

            Column(
                Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(bottom = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier.height(52.dp).clip(RoundedCornerShape(percent = 50)).background(RR.Flame)
                        .clickable(role = Role.Button) { onPlay(current.timeSec) }
                        .padding(horizontal = 26.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Putar dari ${fmtDuration(current.timeSec)}",
                        style = Type.button.copy(color = RR.NightSky, fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
                    )
                }
            }
        }
    }
}

@Composable
private fun ZoomableFrame(frame: ViewerFrame, active: Boolean, onZoomChange: (Boolean) -> Unit, onTapOutside: () -> Unit) {
    val image by produceState<ImageBitmap?>(null, frame) { value = frame.load() }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var box by remember { mutableStateOf(IntSize.Zero) }
    val scope = rememberCoroutineScope()

    // Leaving a page resets it, so coming back never lands mid-zoom.
    LaunchedEffect(active) {
        if (!active) { scale = 1f; offset = Offset.Zero }
    }
    LaunchedEffect(scale > 1.01f) { onZoomChange(scale > 1.01f) }

    fun clamp(o: Offset, s: Float): Offset {
        val maxX = box.width * (s - 1) / 2
        val maxY = box.height * (s - 1) / 2
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }

    /** Where the fitted image sits inside the page, unzoomed. */
    fun fittedHit(p: Offset): Boolean {
        val img = image ?: return true
        val fit = minOf(box.width / img.width.toFloat(), box.height / img.height.toFloat())
        val w = img.width * fit * scale
        val h = img.height * fit * scale
        val cx = box.width / 2f + offset.x
        val cy = box.height / 2f + offset.y
        return abs(p.x - cx) <= w / 2 && abs(p.y - cy) <= h / 2
    }

    Box(
        Modifier.fillMaxSize()
            .pointerInput(Unit) { box = size }
            .pointerInput(image) {
                detectTapGestures(
                    onTap = { if (!fittedHit(it)) onTapOutside() },
                    onDoubleTap = { p ->
                        val from = scale
                        val to = if (from > 1.01f) 1f else DOUBLE_TAP_ZOOM
                        val center = Offset(box.width / 2f, box.height / 2f)
                        // Keep the tapped point under the finger while zooming in.
                        val target = if (to > 1f) clamp((center - p) * (to - 1), to) else Offset.Zero
                        val startOffset = offset
                        scope.launch {
                            animate(0f, 1f, animationSpec = tween(300, easing = ExpoOut)) { t, _ ->
                                scale = from + (to - from) * t
                                offset = startOffset + (target - startOffset) * t
                            }
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                // Pinch always; one-finger drags only while zoomed, so the pager and swipe-down keep theirs.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val pointers = event.changes.count { it.pressed }
                        if (pointers >= 2 || scale > 1.01f) {
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            val centroid = event.calculateCentroid()
                            val newScale = (scale * zoom).coerceIn(1f, MAX_ZOOM)
                            val center = Offset(box.width / 2f, box.height / 2f)
                            val anchor = centroid - center
                            val o = (offset - anchor) * (newScale / scale) + anchor + pan
                            scale = newScale
                            offset = clamp(o, newScale)
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val img = image
        if (img == null) {
            CircularProgressIndicator(Modifier.size(40.dp), color = RR.Flame, strokeWidth = 3.dp, trackColor = Color.Transparent)
        } else {
            Image(
                img, null, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
            )
        }
    }
}
