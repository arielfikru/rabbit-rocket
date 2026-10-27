package io.github.arielfikru.rabbitrocket.ui

import android.content.Context
import android.content.res.Configuration
import android.media.AudioManager
import android.provider.Settings
import android.view.LayoutInflater
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import io.github.arielfikru.rabbitrocket.PlayerActivity
import io.github.arielfikru.rabbitrocket.R
import kotlinx.coroutines.delay

private const val HIDE_AFTER_MS = 3_000L
private val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

/** Snapshot of the player, refreshed from its events plus a position tick. */
private class Playback {
    var isPlaying by mutableStateOf(false)
    var state by mutableIntStateOf(Player.STATE_IDLE)
    var duration by mutableLongStateOf(0L)
    var position by mutableLongStateOf(0L)
    var buffered by mutableLongStateOf(0L)
    var error by mutableStateOf<String?>(null)

    fun read(p: Player) {
        isPlaying = p.isPlaying
        state = p.playbackState
        duration = p.duration.takeIf { it != C.TIME_UNSET } ?: 0L
        position = p.currentPosition
        buffered = p.bufferedPosition
    }
}

@Composable
private fun rememberPlayback(player: Player?): Playback {
    val pb = remember { Playback() }
    DisposableEffect(player) {
        if (player == null) return@DisposableEffect onDispose {}
        val l = object : Player.Listener {
            override fun onEvents(p: Player, events: Player.Events) = pb.read(p)
            override fun onPlayerError(e: PlaybackException) {
                pb.error = e.errorCodeName
            }
        }
        pb.read(player)
        player.addListener(l)
        onDispose { player.removeListener(l) }
    }
    LaunchedEffect(player) {
        while (player != null) {
            pb.position = player.currentPosition
            pb.buffered = player.bufferedPosition
            delay(200)
        }
    }
    return pb
}

private enum class Side { Back, Forward }
private class SeekFlash(val side: Side, val seconds: Int, val stamp: Long)

private enum class HudKind { Brightness, Volume }
private class Hud(val kind: HudKind, val level: Float)

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(act: PlayerActivity) {
    val player = act.player
    val pb = rememberPlayback(player)
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    var controls by remember { mutableStateOf(true) }
    var poke by remember { mutableIntStateOf(0) }
    var scrub by remember { mutableStateOf<Float?>(null) }
    var speedOpen by remember { mutableStateOf(false) }
    var speed by rememberSaveable { mutableFloatStateOf(1f) }
    var zoom by rememberSaveable { mutableStateOf(false) }
    var flash by remember { mutableStateOf<SeekFlash?>(null) }
    var hud by remember { mutableStateOf<Hud?>(null) }
    var hint by remember { mutableStateOf<String?>(null) }

    // Bars go away in landscape so the video gets the whole screen; a swipe brings them back briefly.
    LaunchedEffect(landscape, act.inPip) {
        val c = WindowCompat.getInsetsController(act.window, act.window.decorView)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (landscape || act.inPip) c.hide(WindowInsetsCompat.Type.systemBars())
        else c.show(WindowInsetsCompat.Type.systemBars())
    }
    LaunchedEffect(controls, pb.isPlaying, poke, scrub, speedOpen) {
        if (controls && pb.isPlaying && scrub == null && !speedOpen) {
            delay(HIDE_AFTER_MS)
            controls = false
        }
    }
    LaunchedEffect(player, speed) { player?.setPlaybackSpeed(speed) }
    LaunchedEffect(flash) { if (flash != null) { delay(650); flash = null } }
    LaunchedEffect(hint) { if (hint != null) { delay(1200); hint = null } }
    BackHandler(enabled = speedOpen) { speedOpen = false }

    fun seekBy(side: Side) {
        val p = player ?: return
        if (side == Side.Back) p.seekBack() else p.seekForward()
        val prev = flash
        val total = if (prev != null && prev.side == side) prev.seconds + 10 else 10
        flash = SeekFlash(side, total, System.nanoTime())
    }

    fun togglePlay() {
        val p = player ?: return
        when {
            p.isPlaying -> p.pause()
            p.playbackState == Player.STATE_ENDED -> { p.seekTo(0); p.play() }
            else -> p.play()
        }
        poke++
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { c -> LayoutInflater.from(c).inflate(R.layout.player_surface, null) as PlayerView },
            update = {
                it.player = player
                it.resizeMode = if (zoom) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
            },
            onRelease = { it.player = null },
            modifier = Modifier.fillMaxSize(),
        )

        if (!act.inPip) {
            GestureLayer(
                onTap = { controls = !controls; speedOpen = false; poke++ },
                onDoubleTap = ::seekBy,
                onHud = {
                    hud = it
                    if (it != null) controls = false
                },
            )
        }

        SeekFlashBubble(flash, Side.Back, Modifier.align(Alignment.CenterStart))
        SeekFlashBubble(flash, Side.Forward, Modifier.align(Alignment.CenterEnd))

        if (pb.state == Player.STATE_BUFFERING && pb.error == null) {
            CircularProgressIndicator(
                Modifier.align(Alignment.Center).size(48.dp), color = RR.Flame, strokeWidth = 3.dp,
                trackColor = Color.Transparent,
            )
        }

        AnimatedVisibility(
            visible = controls && !act.inPip && pb.error == null,
            enter = fadeIn(tween(220, easing = ExpoOut)),
            exit = fadeOut(tween(320, easing = InOut)),
        ) {
            Controls(
                act = act, pb = pb, scrub = scrub, speed = speed, zoom = zoom, speedOpen = speedOpen,
                onTogglePlay = ::togglePlay,
                onSeek = { seekBy(it); poke++ },
                onScrub = { f ->
                    scrub = f
                    player?.setSeekParameters(SeekParameters.CLOSEST_SYNC)
                    player?.seekTo((f * pb.duration).toLong())
                },
                onScrubEnd = {
                    scrub?.let { f ->
                        player?.setSeekParameters(SeekParameters.EXACT)
                        player?.seekTo((f * pb.duration).toLong())
                    }
                    scrub = null
                },
                onSpeedMenu = { speedOpen = !speedOpen; poke++ },
                onSpeed = { speed = it; speedOpen = false; poke++ },
                onZoom = {
                    zoom = !zoom
                    hint = if (zoom) "Isi layar" else "Pas layar"
                    poke++
                },
                onLock = {
                    act.toggleOrientationLock()
                    hint = if (act.orientationLocked) "Rotasi dikunci" else "Rotasi ikut HP"
                    poke++
                },
                onRotate = { act.rotate(); poke++ },
                onPip = { controls = false; act.enterPip() },
            )
        }

        HudPill(hud, Modifier.align(Alignment.Center))
        HintPill(hint, Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(top = 72.dp))

        if (!act.inPip) {
            ResumeChip(
                act.resumedFrom,
                onRestart = { player?.seekTo(0); act.dismissResume() },
                onDismiss = act::dismissResume,
                modifier = Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(bottom = if (controls) 132.dp else 24.dp),
            )
        }

        pb.error?.let { ErrorPanel(it, act) }
    }
}

// ---------------------------------------------------------------- gestures

/** Tap toggles controls, double tap seeks, vertical swipes set brightness (left) and volume (right). */
@Composable
private fun GestureLayer(onTap: () -> Unit, onDoubleTap: (Side) -> Unit, onHud: (Hud?) -> Unit) {
    val ctx = LocalContext.current
    val act = ctx as PlayerActivity
    val audio = remember { ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    Box(
        Modifier.fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { onDoubleTap(if (it.x < size.width / 2) Side.Back else Side.Forward) },
                )
            }
            .pointerInput(Unit) {
                var kind: HudKind? = null
                var level = 0f
                // Swipes starting at the very top or bottom belong to the system bars.
                val edge = 48.dp.toPx()
                detectVerticalDragGestures(
                    onDragStart = { o ->
                        kind = when {
                            o.y < edge || o.y > size.height - edge -> null
                            o.x < size.width / 2 -> HudKind.Brightness
                            else -> HudKind.Volume
                        }
                        level = when (kind) {
                            HudKind.Brightness -> currentBrightness(act)
                            HudKind.Volume -> audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() /
                                audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                            null -> 0f
                        }
                    },
                    onDragEnd = { onHud(null) },
                    onDragCancel = { onHud(null) },
                    onVerticalDrag = { change, dy ->
                        val k = kind ?: return@detectVerticalDragGestures
                        change.consume()
                        level = (level - dy / (size.height * 0.7f)).coerceIn(0f, 1f)
                        when (k) {
                            HudKind.Brightness -> act.window.attributes = act.window.attributes.apply {
                                screenBrightness = level.coerceAtLeast(0.01f)
                            }
                            HudKind.Volume -> {
                                val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                audio.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(level * max), 0)
                            }
                        }
                        onHud(Hud(k, level))
                    },
                )
            },
    )
}

private fun currentBrightness(act: PlayerActivity): Float {
    val own = act.window.attributes.screenBrightness
    if (own >= 0f) return own
    return runCatching {
        Settings.System.getInt(act.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
    }.getOrDefault(0.5f)
}

// ---------------------------------------------------------------- controls

@Composable
private fun Controls(
    act: PlayerActivity,
    pb: Playback,
    scrub: Float?,
    speed: Float,
    zoom: Boolean,
    speedOpen: Boolean,
    onTogglePlay: () -> Unit,
    onSeek: (Side) -> Unit,
    onScrub: (Float) -> Unit,
    onScrubEnd: () -> Unit,
    onSpeedMenu: () -> Unit,
    onSpeed: (Float) -> Unit,
    onZoom: () -> Unit,
    onLock: () -> Unit,
    onRotate: () -> Unit,
    onPip: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        // Scrims keep white text readable over bright frames.
        Box(
            Modifier.fillMaxWidth().height(140.dp).align(Alignment.TopCenter)
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent))),
        )
        Box(
            Modifier.fillMaxWidth().height(200.dp).align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f)))),
        )

        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 8.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundIcon(Icons.AutoMirrored.Filled.ArrowBack, "Kembali") { act.finish() }
                Text(
                    act.title, style = Type.strong, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                RoundIcon(PlayerIcons.OpenWith, "Buka dengan app lain") { act.openExternally() }
                if (act.canPip) RoundIcon(PlayerIcons.Pip, "Picture-in-picture", onClick = onPip)
            }

            Spacer(Modifier.weight(1f))

            Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                AnimatedVisibility(
                    visible = speedOpen,
                    enter = expandVertically(tween(300, easing = ExpoOut)) + fadeIn(tween(160)),
                    exit = shrinkVertically(tween(220, easing = InOut)) + fadeOut(tween(120)),
                ) {
                    SpeedRow(speed, onSpeed)
                }
                val dur = pb.duration
                val shown = scrub ?: if (dur > 0) pb.position.toFloat() / dur else 0f
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        fmtTime((shown * dur).toLong()),
                        style = Type.label.copy(color = if (scrub != null) RR.Flame else RR.Cream, fontSize = 14.sp),
                    )
                    Spacer(Modifier.weight(1f))
                    Text(fmtTime(dur), style = Type.label.copy(color = RR.Muted, fontSize = 14.sp))
                }
                SeekBar(
                    fraction = shown,
                    buffered = if (dur > 0) pb.buffered.toFloat() / dur else 0f,
                    scrubbing = scrub != null,
                    enabled = dur > 0,
                    onScrub = onScrub,
                    onScrubEnd = onScrubEnd,
                )
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    RoundIcon(
                        if (act.orientationLocked) Icons.Default.Lock else PlayerIcons.LockOpen,
                        if (act.orientationLocked) "Lepas kunci rotasi" else "Kunci rotasi",
                        tint = if (act.orientationLocked) RR.NightSky else RR.Cream,
                        bg = if (act.orientationLocked) RR.Flame else Color.Transparent,
                        onClick = onLock,
                    )
                    RoundIcon(
                        PlayerIcons.Rotate,
                        if (act.isLandscape) "Putar ke potret" else "Putar ke lanskap",
                        onClick = onRotate,
                    )
                    Spacer(Modifier.weight(1f))
                    TextChip(fmtSpeed(speed), active = speedOpen || speed != 1f, onClick = onSpeedMenu)
                    RoundIcon(
                        if (zoom) PlayerIcons.FitScreen else PlayerIcons.FillScreen,
                        if (zoom) "Pas layar" else "Isi layar",
                        onClick = onZoom,
                    )
                }
            }
        }

        // Centre transport sits on the full screen so it stays centred with or without bars.
        Row(
            Modifier.align(Alignment.Center),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(36.dp),
        ) {
            SkipButton(Side.Back) { onSeek(Side.Back) }
            PlayButton(playing = pb.isPlaying, ended = pb.state == Player.STATE_ENDED, onClick = onTogglePlay)
            SkipButton(Side.Forward) { onSeek(Side.Forward) }
        }
    }
}

@Composable
private fun PlayButton(playing: Boolean, ended: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(76.dp).clip(CircleShape).background(RR.Flame)
            .clickable(role = Role.Button, onClickLabel = if (playing) "Jeda" else "Putar", onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = when { ended -> 2; playing -> 1; else -> 0 },
            transitionSpec = {
                (scaleIn(tween(260, easing = ExpoOut), initialScale = 0.6f) + fadeIn(tween(160))) togetherWith
                    (scaleOut(tween(160), targetScale = 0.6f) + fadeOut(tween(120)))
            },
            label = "play",
        ) { s ->
            val icon = when (s) { 2 -> PlayerIcons.Replay; 1 -> PlayerIcons.Pause; else -> PlayerIcons.Play }
            Icon(icon, null, tint = RR.NightSky, modifier = Modifier.size(40.dp))
        }
    }
}

@Composable
private fun SkipButton(side: Side, onClick: () -> Unit) {
    Box(
        Modifier.size(56.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.35f))
            .clickable(role = Role.Button, onClickLabel = if (side == Side.Back) "Mundur 10 detik" else "Maju 10 detik", onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            PlayerIcons.Replay, null, tint = RR.Cream,
            modifier = Modifier.size(40.dp).graphicsLayer { if (side == Side.Forward) scaleX = -1f },
        )
        Text("10", style = Type.label.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold), modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun RoundIcon(
    icon: ImageVector,
    label: String,
    tint: Color = RR.Cream,
    bg: Color = Color.Transparent,
    onClick: () -> Unit,
) {
    Box(
        Modifier.size(48.dp).clip(CircleShape).background(bg)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = tint, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun TextChip(text: String, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.height(36.dp).clip(RoundedCornerShape(percent = 50))
            .background(if (active) RR.Violet else Color.White.copy(alpha = 0.12f))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Type.label.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold))
    }
}

@Composable
private fun SpeedRow(speed: Float, onSpeed: (Float) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SPEEDS.forEach { s ->
            val on = s == speed
            Box(
                Modifier.height(40.dp).clip(RoundedCornerShape(percent = 50))
                    .background(if (on) RR.Flame else RR.OrbitDeck.copy(alpha = 0.9f))
                    .clickable(role = Role.Button) { onSpeed(s) }
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(fmtSpeed(s), style = Type.label.copy(color = if (on) RR.NightSky else RR.Cream, fontSize = 14.sp, fontWeight = FontWeight.SemiBold))
            }
        }
    }
}

@Composable
private fun SeekBar(
    fraction: Float,
    buffered: Float,
    scrubbing: Boolean,
    enabled: Boolean,
    onScrub: (Float) -> Unit,
    onScrubEnd: () -> Unit,
) {
    val track by animateDpAsState(if (scrubbing) 6.dp else 4.dp, tween(200, easing = ExpoOut), label = "track")
    val thumb by animateDpAsState(if (scrubbing) 10.dp else 7.dp, tween(200, easing = ExpoOut), label = "thumb")
    Canvas(
        Modifier.fillMaxWidth().height(36.dp).pointerInput(enabled) {
            if (!enabled) return@pointerInput
            fun at(x: Float) = (x / size.width).coerceIn(0f, 1f)
            awaitEachGesture {
                val down = awaitFirstDown()
                down.consume()
                onScrub(at(down.position.x))
                while (true) {
                    val e = awaitPointerEvent()
                    val c = e.changes.firstOrNull { it.id == down.id } ?: break
                    if (!c.pressed) break
                    c.consume()
                    onScrub(at(c.position.x))
                }
                onScrubEnd()
            }
        },
    ) {
        val h = track.toPx()
        val y = size.height / 2
        val r = CornerRadius(h / 2)
        val top = Offset(0f, y - h / 2)
        drawRoundRect(RR.Track.copy(alpha = 0.8f), top, Size(size.width, h), r)
        drawRoundRect(RR.Ring, top, Size(size.width * buffered.coerceIn(0f, 1f), h), r)
        drawRoundRect(RR.Flame, top, Size(size.width * fraction.coerceIn(0f, 1f), h), r)
        drawCircle(RR.Cream, thumb.toPx(), Offset(size.width * fraction.coerceIn(0f, 1f), y))
    }
}

// ---------------------------------------------------------------- overlays

@Composable
private fun SeekFlashBubble(flash: SeekFlash?, side: Side, modifier: Modifier) {
    val mine = flash?.takeIf { it.side == side }
    val last = rememberLast(mine)
    AnimatedVisibility(
        visible = mine != null,
        enter = fadeIn(tween(120)) + scaleIn(tween(260, easing = ExpoOut), initialScale = 0.8f),
        exit = fadeOut(tween(260, easing = InOut)),
        modifier = modifier.fillMaxHeight().fillMaxWidth(0.4f),
    ) {
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    if (side == Side.Back) listOf(RR.Violet.copy(alpha = 0.35f), Color.Transparent)
                    else listOf(Color.Transparent, RR.Violet.copy(alpha = 0.35f)),
                ),
            ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                (if (side == Side.Back) "−" else "+") + "${last?.seconds ?: 10} dtk",
                style = Type.section.copy(fontSize = 18.sp),
            )
        }
    }
}

@Composable
private fun HudPill(hud: Hud?, modifier: Modifier) {
    val last = rememberLast(hud) ?: return
    AnimatedVisibility(visible = hud != null, enter = fadeIn(tween(120)), exit = fadeOut(tween(300)), modifier = modifier) {
        Row(
            Modifier.clip(RoundedCornerShape(percent = 50)).background(RR.NightSky.copy(alpha = 0.85f))
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                if (last.kind == HudKind.Brightness) PlayerIcons.Brightness else PlayerIcons.Volume,
                null, tint = RR.Cream, modifier = Modifier.size(22.dp),
            )
            Canvas(Modifier.width(120.dp).height(6.dp)) {
                val r = CornerRadius(size.height / 2)
                drawRoundRect(RR.Track, cornerRadius = r)
                drawRoundRect(RR.Flame, size = Size(size.width * last.level, size.height), cornerRadius = r)
            }
            Text("${(last.level * 100).toInt()}%", style = Type.label, modifier = Modifier.width(40.dp))
        }
    }
}

@Composable
private fun HintPill(hint: String?, modifier: Modifier) {
    val last = rememberLast(hint) ?: return
    AnimatedVisibility(
        visible = hint != null,
        enter = fadeIn(tween(160)) + slideInVertically(tween(300, easing = ExpoOut)) { -it / 2 },
        exit = fadeOut(tween(240)),
        modifier = modifier,
    ) {
        Pill(last, RR.NightSky.copy(alpha = 0.9f), RR.Cream)
    }
}

@Composable
private fun ResumeChip(from: Long, onRestart: () -> Unit, onDismiss: () -> Unit, modifier: Modifier) {
    LaunchedEffect(from) { if (from > 0) { delay(6_000); onDismiss() } }
    val last = rememberLast(from.takeIf { it > 0 }) ?: return
    AnimatedVisibility(
        visible = from > 0,
        enter = fadeIn(tween(200)) + slideInVertically(tween(420, easing = ExpoOut)) { it },
        exit = fadeOut(tween(200)) + slideOutVertically(tween(300, easing = InOut)) { it / 2 },
        modifier = modifier,
    ) {
        Row(
            Modifier.clip(RoundedCornerShape(percent = 50)).background(RR.OrbitDeck.copy(alpha = 0.95f))
                .padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Lanjut dari ${fmtTime(last)}", style = Type.label.copy(fontSize = 14.sp))
            Box(
                Modifier.height(36.dp).clip(RoundedCornerShape(percent = 50)).background(RR.Flame)
                    .clickable(interactionSource = null, indication = ripple(color = RR.NightSky), role = Role.Button, onClick = onRestart)
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("Dari awal", style = Type.label.copy(color = RR.NightSky, fontSize = 14.sp, fontWeight = FontWeight.SemiBold))
            }
        }
    }
}

@Composable
private fun BoxScope.ErrorPanel(code: String, act: PlayerActivity) {
    Column(
        Modifier.align(Alignment.Center).padding(32.dp).clip(RoundedCornerShape(20.dp)).background(RR.OrbitDeck)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Video ini gagal diputar di sini", style = Type.strong)
        Text(code, style = Type.small)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallButton("Tutup", RR.NightSky, RR.Cream) { act.finish() }
            SmallButton("Buka dengan app lain", RR.Flame, RR.NightSky) { act.openExternally() }
        }
    }
}

// ---------------------------------------------------------------- helpers

private fun fmtTime(ms: Long): String {
    val s = (ms.coerceAtLeast(0) / 1000).toInt()
    val h = s / 3600
    val m = s % 3600 / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

private fun fmtSpeed(s: Float) = (if (s % 1f == 0f) "${s.toInt()}" else "$s".trimEnd('0')) + "×"

/** Player glyphs that material-icons-core lacks. */
private object PlayerIcons {
    private fun icon(name: String, vararg paths: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        paths.forEach { addPath(addPathNodes(it), fill = SolidColor(Color.White)) }
    }.build()

    val Play = icon("play", "M8,5v14l11,-7z")
    val Pause = icon("pause", "M6,19h4V5H6v14zM14,5v14h4V5h-4z")
    val Replay = icon("replay", "M12,5V1L7,6l5,5V7c3.31,0 6,2.69 6,6s-2.69,6 -6,6 -6,-2.69 -6,-6H4c0,4.42 3.58,8 8,8s8,-3.58 8,-8 -3.58,-8 -8,-8z")
    val OpenWith = icon(
        "open_with",
        "M19,19H5V5h7V3H5c-1.11,0 -2,0.9 -2,2v14c0,1.1 0.89,2 2,2h14c1.1,0 2,-0.9 2,-2v-7h-2v7zM14,3v2h3.59l-9.83,9.83 1.41,1.41L19,6.41V10h2V3h-7z",
    )
    val Pip = icon(
        "pip",
        "M19,7h-8v6h8V7zM21,3H3c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,1.98 2,1.98h18c1.1,0 2,-0.88 2,-1.98V5c0,-1.1 -0.9,-2 -2,-2zM21,19.01H3V4.98h18v14.03z",
    )
    val LockOpen = icon(
        "lock_open",
        "M12,17c1.1,0 2,-0.9 2,-2s-0.9,-2 -2,-2 -2,0.9 -2,2 0.9,2 2,2zM18,8h-1V6c0,-2.76 -2.24,-5 -5,-5S7,3.24 7,6h1.9c0,-1.71 1.39,-3.1 3.1,-3.1 1.71,0 3.1,1.39 3.1,3.1v2H6c-1.1,0 -2,0.9 -2,2v10c0,1.1 0.9,2 2,2h12c1.1,0 2,-0.9 2,-2V10c0,-1.1 -0.9,-2 -2,-2zM18,20H6V10h12v10z",
    )
    val Rotate = icon(
        "screen_rotation",
        "M16.48,2.52c3.27,1.55 5.61,4.72 5.97,8.48h1.5C23.44,4.84 18.29,0 12,0l-0.66,0.03 3.81,3.81 1.33,-1.32zM10.23,1.75c-0.59,-0.59 -1.54,-0.59 -2.12,0L1.75,8.11c-0.59,0.59 -0.59,1.54 0,2.12l12.02,12.02c0.59,0.59 1.54,0.59 2.12,0l6.36,-6.36c0.59,-0.59 0.59,-1.54 0,-2.12L10.23,1.75zM14.83,21.19L2.81,9.17l6.36,-6.36 12.02,12.02 -6.36,6.36zM7.52,21.48C4.25,19.94 1.91,16.76 1.55,13H0.05C0.56,19.16 5.71,24 12,24l0.66,-0.03 -3.81,-3.81 -1.33,1.32z",
    )
    val FillScreen = icon(
        "fullscreen",
        "M7,14H5v5h5v-2H7v-3zM5,10h2V7h3V5H5v5zM17,17h-3v2h5v-5h-2v3zM14,5v2h3v3h2V5h-5z",
    )
    val FitScreen = icon(
        "fullscreen_exit",
        "M5,16h3v3h2v-5H5v2zM8,8H5v2h5V5H8v3zM14,19h2v-3h3v-2h-5v5zM16,8V5h-2v5h5V8h-3z",
    )
    val Brightness = icon(
        "brightness",
        "M20,15.31L23.31,12 20,8.69V4h-4.69L12,0.69 8.69,4H4v4.69L0.69,12 4,15.31V20h4.69L12,23.31 15.31,20H20v-4.69zM12,18c-3.31,0 -6,-2.69 -6,-6s2.69,-6 6,-6 6,2.69 6,6 -2.69,6 -6,6z",
    )
    val Volume = icon(
        "volume",
        "M3,9v6h4l5,5V4L7,9H3zM16.5,12c0,-1.77 -1.02,-3.29 -2.5,-4.03v8.05c1.48,-0.73 2.5,-2.25 2.5,-4.02zM14,3.23v2.06c2.89,0.86 5,3.54 5,6.71s-2.11,5.85 -5,6.71v2.06c4.01,-0.91 7,-4.49 7,-8.77s-2.99,-7.86 -7,-8.77z",
    )
}
