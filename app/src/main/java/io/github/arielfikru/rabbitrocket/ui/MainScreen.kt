package io.github.arielfikru.rabbitrocket.ui

import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.material.icons.filled.Edit
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import io.github.arielfikru.rabbitrocket.DlJob
import io.github.arielfikru.rabbitrocket.Media
import io.github.arielfikru.rabbitrocket.PlayerActivity
import io.github.arielfikru.rabbitrocket.R
import io.github.arielfikru.rabbitrocket.Status
import io.github.arielfikru.rabbitrocket.extractUrl
import io.github.arielfikru.rabbitrocket.fmtBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.arielfikru.rabbitrocket.fmtDuration
import io.github.arielfikru.rabbitrocket.fmtResolution
import io.github.arielfikru.rabbitrocket.hls.Http
import io.github.arielfikru.rabbitrocket.hls.Thumbnails
import io.github.arielfikru.rabbitrocket.statusLine

// Motion vocabulary: shape morphs use expo-out tweens, arrivals use soft springs, exits ease in-out.
internal val ExpoOut = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)
internal val InOut = CubicBezierEasing(0.65f, 0f, 0.35f, 1f)
private const val GROW_MS = 520
/** Preview contents start once the card is ~45% grown, then step down every 60 ms. */
private const val ENTER_DELAY = 230
private const val STEP_MS = 60
private const val RELEASE_AFTER_MS = 600L

@Composable
fun HomeScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val jobs by vm.jobs.collectAsStateWithLifecycle()
    val waiting = jobs.count { it.status.isWaiting }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(RR.NightSky)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "header") { Header(waiting) }
        item(key = "launch") {
            Column {
                LaunchCard(vm)
                // Grows out of the launch card (its trigger) and folds back into it.
                AnimatedVisibility(
                    visible = vm.preview != null,
                    enter = expandVertically(tween(GROW_MS, easing = ExpoOut), expandFrom = Alignment.Top) +
                        fadeIn(tween(180)),
                    exit = fadeOut(tween(140)) +
                        shrinkVertically(tween(360, easing = InOut), shrinkTowards = Alignment.Top),
                ) {
                    rememberLast(vm.preview)?.let { p ->
                        Column {
                            Spacer(Modifier.height(16.dp))
                            PreviewCard(p, vm)
                        }
                    }
                }
            }
        }
        // Items below the preview move with no placement animation: a lagging spring lets the
        // growing card slide over them. The empty state leaves as soon as checking starts.
        if (vm.saved.isNotEmpty()) {
            item(key = "saved-head") {
                Text(
                    "Disimpan · ${vm.saved.size}",
                    style = Type.section,
                    modifier = Modifier.animateItem(placementSpec = null).padding(top = 4.dp),
                )
            }
            items(vm.saved, key = { "s${it.id}" }) { s ->
                SavedCard(s, vm, Modifier.animateItem(fadeInSpec = tween(320, delayMillis = 60), placementSpec = null, fadeOutSpec = tween(160)))
            }
        }
        if (jobs.isEmpty()) {
            if (vm.saved.isEmpty() && vm.preview == null && !vm.analyzing && !vm.launching) {
                item(key = "empty") { EmptyState(Modifier.animateItem(placementSpec = null, fadeOutSpec = tween(140))) }
            }
        } else {
            item(key = "section") { SectionRow(jobs, vm, Modifier.animateItem(placementSpec = null)) }
        }
        items(jobs.asReversed(), key = { it.id }) { job ->
            JobCard(
                job, vm,
                Modifier.animateItem(
                    // The VM enqueues 220 ms after the fold starts, so the card lands into settled space.
                    fadeInSpec = tween(320, delayMillis = 60),
                    placementSpec = null,
                    fadeOutSpec = tween(160),
                ),
            )
        }
    }
}

@Composable
private fun Header(waiting: Int) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Image(painterResource(R.drawable.ic_mascot), null, Modifier.size(36.dp, 40.dp))
        Text("Rabbit Rocket", style = Type.title, modifier = Modifier.weight(1f))
        AnimatedVisibility(
            visible = waiting > 0,
            enter = scaleIn(spring(dampingRatio = 0.55f, stiffness = 300f)) + fadeIn(tween(120)),
            exit = scaleOut(tween(150)) + fadeOut(tween(150)),
        ) {
            Pill("${rememberLast(waiting.takeIf { it > 0 }) ?: 1} meluncur", RR.Pink, RR.NightSky)
        }
    }
}

@Composable
private fun LaunchCard(vm: MainViewModel) {
    val clipboard = LocalClipboardManager.current
    val focus = LocalFocusManager.current
    val submit = {
        focus.clearFocus()
        vm.check()
    }
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(RR.Violet)) {
        Box(
            Modifier.align(Alignment.TopEnd).offset(30.dp, (-30).dp).size(130.dp)
                .clip(CircleShape).background(RR.VioletGlow),
        )
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Link HLS / M3U / MP4", style = Type.label)
            UrlField(
                value = vm.url,
                onChange = vm::editUrl,
                onGo = submit,
                action = if (vm.url.isEmpty()) "Paste" else "Hapus",
                onAction = {
                    if (vm.url.isEmpty()) clipboard.getText()?.text?.let { vm.editUrl(extractUrl(it) ?: it.trim()) }
                    else vm.editUrl("")
                },
            )
            OptionsToggle(vm.optionsOpen) { vm.optionsOpen = !vm.optionsOpen }
            AnimatedVisibility(
                visible = vm.optionsOpen,
                enter = expandVertically(tween(420, easing = ExpoOut)) + fadeIn(tween(220, delayMillis = 120)),
                exit = fadeOut(tween(100)) + shrinkVertically(tween(300, easing = InOut)),
            ) { Options(vm) }
            PrimaryButton(
                text = if (vm.analyzing) "Membaca playlist…" else "Cek & preview",
                enabled = vm.url.isNotBlank() && !vm.analyzing,
                loading = vm.analyzing,
                onClick = submit,
            )
            AnimatedVisibility(
                visible = vm.error != null,
                enter = expandVertically(tween(300, easing = ExpoOut)) + fadeIn(),
                exit = shrinkVertically(tween(220, easing = InOut)) + fadeOut(),
            ) {
                Text(
                    rememberLast(vm.error).orEmpty(),
                    style = Type.small.copy(color = RR.Danger),
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(RR.NightSky).padding(12.dp),
                )
            }
        }
    }
}

@Composable
private fun UrlField(value: String, onChange: (String) -> Unit, onGo: () -> Unit, action: String, onAction: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(16.dp))
            .background(RR.NightSky).padding(start = 14.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.weight(1f).padding(vertical = 14.dp),
            textStyle = Type.body,
            cursorBrush = SolidColor(RR.Flame),
            maxLines = 3,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false,
            ),
            keyboardActions = KeyboardActions(onGo = { onGo() }),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) Text("https://…/video.m3u8 atau .mp4", style = Type.body.copy(color = RR.Muted))
                    inner()
                }
            },
        )
        SmallButton(action, RR.Pink, RR.NightSky, onClick = onAction)
    }
}

@Composable
private fun OptionsToggle(open: Boolean, onClick: () -> Unit) {
    val rotation by animateFloatAsState(if (open) 180f else 0f, tween(300, easing = ExpoOut), label = "chevron")
    Row(
        Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClickLabel = "Buka opsi", onClick = onClick)
            .heightIn(min = 44.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Opsi lanjutan", style = Type.label.copy(fontSize = 14.sp))
        Icon(Icons.Default.KeyboardArrowDown, null, tint = Color.White, modifier = Modifier.rotate(rotation))
    }
}

@Composable
private fun Options(vm: MainViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Field("Nama file", vm.fileName, { vm.fileName = it }, "otomatis dari link")
        Field("Referer", vm.referer, { vm.referer = it }, "https://situs-asal.com/")
        Field("User-Agent", vm.userAgent, { vm.userAgent = it }, "default: Chrome Android")
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Konversi ke MP4", style = Type.body.copy(color = Color.White), modifier = Modifier.weight(1f))
            Switch(
                checked = vm.toMp4,
                onCheckedChange = { vm.toMp4 = it },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = RR.NightSky, checkedTrackColor = RR.Flame, checkedBorderColor = RR.Flame,
                    uncheckedThumbColor = RR.Muted, uncheckedTrackColor = RR.Track, uncheckedBorderColor = RR.Track,
                ),
            )
        }
    }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit, placeholder: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = Type.label.copy(fontSize = 12.sp))
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = Type.body.copy(fontSize = 14.sp),
            cursorBrush = SolidColor(RR.Flame),
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(RR.Track)
                .padding(horizontal = 14.dp, vertical = 13.dp),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) Text(placeholder, style = Type.body.copy(fontSize = 14.sp, color = RR.Muted))
                    inner()
                }
            },
        )
    }
}

// ---------------------------------------------------------------- preview

@Composable
private fun PreviewCard(p: Preview, vm: MainViewModel) {
    val headers = remember(p.streamUrl) { vm.headersFor(p.streamUrl) }
    val player = rememberPreviewPlayer(p.streamUrl, headers, hls = !p.isDirect)
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(RR.OrbitDeck).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Stagger(shown, 0) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Preview", style = Type.section, modifier = Modifier.weight(1f))
                IconButton(onClick = vm::closePreview) {
                    Icon(Icons.Default.Close, "Tutup preview", tint = RR.Muted)
                }
            }
        }
        Stagger(shown, 1) { PlayerBox(player) }
        Stagger(shown, 2) { InfoChips(p) }
        if (p.variants.size > 1) Stagger(shown, 3) { QualityPicker(p, vm::select) }
        if (vm.thumbSlots > 0) {
            Stagger(shown, 4) {
                ThumbGrid(vm.thumbs, vm.thumbSlots) { t ->
                    player.player.seekTo((t * 1000).toLong())
                    player.player.play()
                }
            }
        }
        Stagger(shown, 5) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton("Simpan", Modifier.weight(1f), onClick = vm::saveForLater)
                PrimaryButton("Luncurkan", enabled = true, modifier = Modifier.weight(1.6f), onClick = vm::download)
            }
        }
    }
}

/** Fades content up from 14 dp below; layout is final from frame one, so the card never re-flows. */
@Composable
private fun Stagger(visible: Boolean, index: Int, content: @Composable () -> Unit) {
    val delay = ENTER_DELAY + index * STEP_MS
    val alpha by animateFloatAsState(if (visible) 1f else 0f, tween(260, delayMillis = delay), label = "fade")
    val dy by animateDpAsState(if (visible) 0.dp else 14.dp, tween(420, delayMillis = delay, easing = ExpoOut), label = "rise")
    Box(Modifier.graphicsLayer { this.alpha = alpha; translationY = dy.toPx() }) { content() }
}

private class PreviewPlayerState(val player: ExoPlayer) {
    var failed by mutableStateOf(false)
}

@OptIn(UnstableApi::class)
@Composable
private fun rememberPreviewPlayer(url: String, headers: Map<String, String>, hls: Boolean): PreviewPlayerState {
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val state = remember(url) {
        val source = OkHttpDataSource.Factory(Http.client)
            .setUserAgent(headers["User-Agent"])
            .setDefaultRequestProperties(headers - "User-Agent")
        val player = ExoPlayer.Builder(ctx).setMediaSourceFactory(DefaultMediaSourceFactory(source)).build()
        // Direct files get no mime hint so ExoPlayer sniffs the container itself.
        player.setMediaItem(MediaItem.Builder().setUri(url).apply { if (hls) setMimeType(MimeTypes.APPLICATION_M3U8) }.build())
        player.prepare()
        PreviewPlayerState(player)
    }
    DisposableEffect(state) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                state.failed = true
            }
        }
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_PAUSE) state.player.pause() }
        state.player.addListener(listener)
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            state.player.removeListener(listener)
            // release() blocks the main thread on the playback thread; doing it mid-fold freezes the exit.
            state.player.stop()
            Handler(Looper.getMainLooper()).postDelayed({ state.player.release() }, RELEASE_AFTER_MS)
        }
    }
    return state
}

@Composable
private fun PlayerBox(state: PreviewPlayerState) {
    Box(
        Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(16.dp)).background(Color.Black),
    ) {
        AndroidView(
            factory = { c -> LayoutInflater.from(c).inflate(R.layout.preview_player, null) as PlayerView },
            update = { it.player = state.player },
            onRelease = { it.player = null },
            modifier = Modifier.fillMaxSize(),
        )
        if (state.failed) {
            Text(
                "Preview gagal diputar. Download biasanya tetap bisa.",
                style = Type.small.copy(color = RR.Cream, textAlign = TextAlign.Center),
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
            )
        }
    }
}

@kotlin.OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InfoChips(p: Preview) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (p.duration > 0) Pill(fmtDuration(p.duration), RR.NightSky, RR.Pink)
        p.size?.let { (w, h) -> Pill(fmtResolution(w, h), RR.NightSky, RR.Cream) }
        if (p.segments > 0) Pill("${p.segments} segmen", RR.NightSky, RR.Muted)
        p.direct?.let { d ->
            Pill(d.ext.uppercase(), RR.Violet, Color.White)
            if (d.size > 0) Pill(fmtBytes(d.size), RR.NightSky, RR.Muted)
            if (d.ranges && d.size >= 8L shl 20) Pill("Multi-koneksi", RR.NightSky, RR.Muted)
        } ?: p.estimatedBytes?.let { Pill("± ${fmtBytes(it)}", RR.NightSky, RR.Muted) }
        if (p.encrypted) Pill("AES-128", RR.Violet, Color.White)
        if (p.isLive) Pill("LIVE · ambil yang ada", RR.Pink, RR.NightSky)
    }
}

@Composable
private fun QualityPicker(p: Preview, onSelect: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            p.variants.forEachIndexed { i, v -> QualityPill(v.quality, i == p.selected) { onSelect(i) } }
        }
        Text(p.variant?.detail.orEmpty(), style = Type.small)
    }
}

@Composable
private fun QualityPill(text: String, selected: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(if (selected) RR.Flame else RR.NightSky, tween(220), label = "bg")
    val fg by animateColorAsState(if (selected) RR.NightSky else RR.Cream, tween(220), label = "fg")
    Box(
        Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(percent = 50)).background(bg)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Type.strong.copy(fontFamily = Fredoka, color = fg))
    }
}

@Composable
private fun ThumbGrid(thumbs: List<Thumbnails.Thumb>, slots: Int, onSeek: (Double) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        (0 until slots).chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { slot ->
                    ThumbTile(thumbs.firstOrNull { it.slot == slot }, Modifier.weight(1f), onSeek)
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun ThumbTile(thumb: Thumbnails.Thumb?, modifier: Modifier, onSeek: (Double) -> Unit) {
    Box(modifier.aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)).background(RR.NightSky)) {
        if (thumb == null) {
            val pulse by rememberInfiniteTransition("wait").animateFloat(
                0.35f, 0.7f, infiniteRepeatable(tween(800, easing = InOut), androidx.compose.animation.core.RepeatMode.Reverse),
                label = "pulse",
            )
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = pulse }.background(RR.Track))
        } else {
            val image = remember(thumb.bitmap) { thumb.bitmap.asImageBitmap() }
            val arrive = remember(thumb) { Animatable(0f) }
            LaunchedEffect(thumb) { arrive.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = 280f)) }
            Image(
                image,
                contentDescription = "Cuplikan menit ${fmtDuration(thumb.timeSec)}",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
                    .graphicsLayer {
                        alpha = arrive.value.coerceIn(0f, 1f)
                        val s = 0.92f + 0.08f * arrive.value
                        scaleX = s
                        scaleY = s
                    }
                    .clickable(onClickLabel = "Putar dari sini") { onSeek(thumb.timeSec) },
            )
            Text(
                fmtDuration(thumb.timeSec),
                style = Type.small.copy(fontSize = 11.sp, color = RR.Cream),
                modifier = Modifier.align(Alignment.BottomStart).padding(4.dp)
                    .clip(RoundedCornerShape(6.dp)).background(RR.NightSky.copy(alpha = 0.8f))
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }
    }
}

// ---------------------------------------------------------------- jobs

@Composable
private fun SectionRow(jobs: List<DlJob>, vm: MainViewModel, modifier: Modifier) {
    Row(modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Misi download", style = Type.section, modifier = Modifier.weight(1f))
        if (jobs.any { it.status == Status.Done }) {
            Text(
                "Bersihkan selesai",
                style = Type.label.copy(color = RR.Pink, fontSize = 14.sp),
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = vm::clearFinished)
                    .padding(horizontal = 8.dp, vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun JobCard(job: DlJob, vm: MainViewModel, modifier: Modifier) {
    var renaming by remember(job.id) { mutableStateOf(false) }
    Column(
        modifier.fillMaxWidth()
            .animateContentSize(tween(300, easing = ExpoOut))
            .clip(RoundedCornerShape(20.dp))
            .background(RR.OrbitDeck)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            StatusDot(job)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(job.name, style = Type.strong, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (job.status == Status.Done && !renaming) {
                        Icon(
                            Icons.Default.Edit, "Ganti nama", tint = RR.Muted,
                            modifier = Modifier.padding(start = 4.dp).size(32.dp).clip(CircleShape)
                                .clickable(role = Role.Button) { renaming = true }.padding(7.dp),
                        )
                    }
                }
                Text(
                    statusLine(job),
                    style = Type.small.copy(color = if (job.status == Status.Failed) RR.Danger else RR.Muted),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Pill(chipText(job), RR.NightSky, RR.Pink)
        }
        if (job.status.isActive || (job.hasPartial && job.status != Status.Done)) {
            FlameBar(job.overall, job.isIndeterminate && job.status.isActive)
        }
        if (renaming) RenameRow(job.name, onCancel = { renaming = false }) {
            vm.renameJob(job, it)
            renaming = false
        } else Actions(job, vm)
    }
}

@Composable
private fun RenameRow(current: String, onCancel: () -> Unit, onSave: (String) -> Unit) {
    var field by remember { mutableStateOf(TextFieldValue(current, TextRange(0, current.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BasicTextField(
            value = field,
            onValueChange = { field = it },
            singleLine = true,
            textStyle = Type.body,
            cursorBrush = SolidColor(RR.Flame),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (field.text.isNotBlank()) onSave(field.text) }),
            modifier = Modifier.fillMaxWidth().focusRequester(focus).clip(RoundedCornerShape(14.dp))
                .background(RR.NightSky).padding(horizontal = 14.dp, vertical = 13.dp),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            SmallButton("Batal", RR.NightSky, RR.Muted, onCancel)
            SmallButton("Simpan nama", RR.Flame, RR.NightSky) { if (field.text.isNotBlank()) onSave(field.text) }
        }
    }
}

private fun chipText(job: DlJob) = when (job.status) {
    Status.Downloading -> if (job.hasSize) "${(job.downloadFraction * 100).toInt()}%" else "…"
    Status.Converting -> "${job.convertProgress}%"
    else -> job.status.label.lowercase().replaceFirstChar { it.uppercase() }
}

@Composable
private fun StatusDot(job: DlJob) {
    val kind = when (job.status) {
        Status.Done -> 0
        Status.Failed -> 1
        Status.Queued -> 2
        Status.Canceled -> 3
        else -> 4
    }
    val progress by animateFloatAsState(job.overall, tween(450, easing = FastOutSlowInEasing), label = "ring")
    AnimatedContent(
        targetState = kind,
        transitionSpec = {
            (scaleIn(spring(dampingRatio = 0.5f, stiffness = 320f), initialScale = 0.4f) + fadeIn(tween(120))) togetherWith
                (scaleOut(tween(120), targetScale = 0.6f) + fadeOut(tween(120)))
        },
        contentAlignment = Alignment.Center,
        label = "dot",
    ) { k ->
        Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
            when (k) {
                0 -> Box(Modifier.size(28.dp).clip(CircleShape).background(RR.Flame), Alignment.Center) {
                    Icon(Icons.Default.Check, null, tint = RR.NightSky, modifier = Modifier.size(16.dp))
                }
                1 -> Box(Modifier.size(28.dp).clip(CircleShape).background(RR.Danger), Alignment.Center) {
                    Text("!", style = Type.strong.copy(color = RR.NightSky, fontWeight = FontWeight.Bold))
                }
                2 -> Box(Modifier.size(28.dp).border(2.5.dp, RR.Ring, CircleShape))
                3 -> Box(Modifier.size(28.dp).border(2.5.dp, RR.Ring, CircleShape), Alignment.Center) {
                    Icon(Icons.Default.Close, null, tint = RR.Ring, modifier = Modifier.size(14.dp))
                }
                else -> if (job.isIndeterminate) {
                    CircularProgressIndicator(
                        Modifier.size(26.dp), color = RR.Flame, strokeWidth = 3.dp, trackColor = RR.Ring.copy(alpha = 0.4f),
                    )
                } else {
                    CircularProgressIndicator(
                        progress = { progress }, modifier = Modifier.size(26.dp), color = RR.Flame,
                        strokeWidth = 3.dp, trackColor = RR.Ring.copy(alpha = 0.4f), gapSize = 0.dp,
                    )
                }
            }
        }
    }
}

@Composable
private fun FlameBar(fraction: Float, indeterminate: Boolean) {
    val shown by animateFloatAsState(fraction, tween(450, easing = FastOutSlowInEasing), label = "bar")
    val sweep = if (indeterminate) {
        rememberInfiniteTransition("sweep").animateFloat(
            0f, 1f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "sweep",
        ).value
    } else 0f
    Canvas(
        Modifier.fillMaxWidth().height(8.dp)
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f) },
    ) {
        val r = CornerRadius(size.height / 2)
        drawRoundRect(RR.NightSky, cornerRadius = r)
        if (indeterminate) {
            val w = size.width * 0.3f
            clipRect {
                drawRoundRect(RR.Flame, topLeft = Offset(-w + (size.width + w) * sweep, 0f), size = Size(w, size.height), cornerRadius = r)
            }
        } else {
            drawRoundRect(RR.Flame, size = Size(size.width * shown, size.height), cornerRadius = r)
        }
    }
}

@Composable
private fun Actions(job: DlJob, vm: MainViewModel) {
    val ctx = LocalContext.current
    var confirm by remember(job.id) { mutableStateOf(false) }
    if (confirm) {
        // Asked in place: the card already says which video this is.
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Hapus dari daftar saja, atau sekalian file videonya?", style = Type.small.copy(color = RR.Cream))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                SmallButton("Batal", RR.NightSky, RR.Muted) { confirm = false }
                SmallButton("Daftar saja", RR.NightSky, RR.Cream) { vm.deleteJob(job, withFile = false) }
                SmallButton("Hapus file", RR.Danger, RR.NightSky) { vm.deleteJob(job, withFile = true) }
            }
        }
        return
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
        when (job.status) {
            Status.Done -> {
                val uri = Uri.parse(job.outputUri)
                SmallButton("Hapus", RR.NightSky, RR.Muted) { confirm = true }
                SmallButton("Share", RR.NightSky, RR.Cream) { ctx.open(Media.shareIntent(uri, job.outputMime)) }
                // "Putar" stays in the app; the icon is the way out to VLC & co.
                SmallIconButton(R.drawable.ic_action_open_with, "Buka dengan app lain") {
                    Media.openWith(ctx, uri, job.outputMime)
                }
                SmallButton("Putar", RR.Flame, RR.NightSky) {
                    ctx.startActivity(PlayerActivity.intent(ctx, uri, job.outputMime, job.name))
                }
            }
            Status.Failed, Status.Canceled -> {
                SmallButton("Hapus", RR.NightSky, RR.Muted) { vm.remove(job.id) }
                SmallButton(if (job.hasPartial) "Lanjutkan" else "Ulangi", RR.Flame, RR.NightSky) { vm.retry(job.id) }
            }
            else -> SmallButton("Batal", RR.NightSky, RR.Cream) { vm.cancel(job.id) }
        }
    }
}

private fun Context.open(i: Intent) = try {
    startActivity(i)
} catch (_: ActivityNotFoundException) {
    Toast.makeText(this, "Gak ada aplikasi yang bisa membuka file ini", Toast.LENGTH_SHORT).show()
}

// ---------------------------------------------------------------- saved

@Composable
private fun SavedCard(s: Saved, vm: MainViewModel, modifier: Modifier) {
    val thumb by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, s.thumb) {
        value = s.thumb?.let { path ->
            withContext(Dispatchers.IO) { android.graphics.BitmapFactory.decodeFile(path)?.asImageBitmap() }
        }
    }
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(RR.OrbitDeck).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(104.dp, 58.dp).clip(RoundedCornerShape(12.dp)).background(RR.NightSky),
                contentAlignment = Alignment.Center,
            ) {
                thumb?.let {
                    Image(it, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                } ?: Text(s.kind, style = Type.label.copy(color = RR.Muted))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(s.name, style = Type.strong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(savedLine(s), style = Type.small, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            SmallButton("Hapus", RR.NightSky, RR.Muted) { vm.removeSaved(s) }
            if (s.checked) {
                SmallButton("Preview", RR.NightSky, RR.Cream) { vm.previewSaved(s) }
                SmallButton("Download", RR.Flame, RR.NightSky) { vm.downloadSaved(s) }
            } else {
                SmallButton("Cek & preview", RR.Flame, RR.NightSky) { vm.previewSaved(s) }
            }
        }
    }
}

private fun savedLine(s: Saved): String = if (!s.checked) {
    "${s.kind} · belum dicek · ${Uri.parse(s.url).host.orEmpty().removePrefix("www.")}"
} else {
    listOfNotNull(
        s.kind,
        s.duration.takeIf { it > 0 }?.let(::fmtDuration),
        if (s.width > 0) "${minOf(s.width, s.height)}p" else s.quality,
        s.bytes.takeIf { it > 0 }?.let { (if (s.direct != null) "" else "± ") + fmtBytes(it) },
    ).joinToString(" · ")
}

@Composable
private fun EmptyState(modifier: Modifier) {
    // Three gentle bobs, then still: ambient motion that stops on its own.
    val bob = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        repeat(3) {
            bob.animateTo(-6f, tween(900, easing = InOut))
            bob.animateTo(0f, tween(900, easing = InOut))
        }
    }
    Column(
        modifier.fillMaxWidth().padding(vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Image(
            painterResource(R.drawable.ic_mascot), null,
            Modifier.size(84.dp, 92.dp).graphicsLayer { translationY = bob.value.dp.toPx() },
        )
        Text(
            "Tempel link .m3u8 atau .mp4 di atas, atau buka tab Browser biar link videonya dicari otomatis.",
            style = Type.small.copy(textAlign = TextAlign.Center),
            modifier = Modifier.widthIn(max = 280.dp),
        )
    }
}

// ---------------------------------------------------------------- shared bits

@Composable
internal fun Pill(text: String, bg: Color, fg: Color, modifier: Modifier = Modifier) {
    Text(
        text,
        style = Type.small.copy(color = fg, fontWeight = FontWeight.SemiBold),
        maxLines = 1,
        modifier = modifier.clip(RoundedCornerShape(percent = 50)).background(bg)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun PrimaryButton(
    text: String,
    enabled: Boolean,
    loading: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, spring(stiffness = 600f), label = "press")
    Button(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interaction,
        shape = RoundedCornerShape(percent = 50),
        colors = ButtonDefaults.buttonColors(
            containerColor = RR.Flame, contentColor = RR.NightSky,
            disabledContainerColor = RR.Track, disabledContentColor = RR.Muted,
        ),
        modifier = modifier.fillMaxWidth().height(56.dp).graphicsLayer { scaleX = scale; scaleY = scale },
    ) {
        // Only the fixed-size glyph cross-fades; the label sits outside so a width change never clips it.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AnimatedContent(
                targetState = loading,
                transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(22.dp),
                label = "glyph",
            ) { isLoading ->
                if (isLoading) {
                    CircularProgressIndicator(
                        Modifier.size(18.dp), color = RR.Muted, strokeWidth = 2.5.dp, trackColor = Color.Transparent,
                    )
                } else {
                    Icon(painterResource(R.drawable.ic_rocket_line), null, Modifier.size(22.dp))
                }
            }
            Text(text, style = Type.button)
        }
    }
}

/** Quiet partner of [PrimaryButton]: same height and press, no fill colour fighting for attention. */
@Composable
private fun SecondaryButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, spring(stiffness = 600f), label = "press")
    Box(
        modifier.height(56.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(percent = 50))
            .background(RR.NightSky)
            .clickable(interaction, ripple(color = RR.Cream), role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Type.button.copy(color = RR.Cream))
    }
}

@Composable
internal fun SmallButton(text: String, bg: Color, fg: Color, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.95f else 1f, spring(stiffness = 600f), label = "press")
    Box(
        Modifier.heightIn(min = 44.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(percent = 50))
            .background(bg)
            .clickable(interaction, ripple(color = fg), role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Type.small.copy(color = fg, fontWeight = FontWeight.SemiBold, fontSize = 14.sp))
    }
}

@Composable
private fun SmallIconButton(icon: Int, label: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.92f else 1f, spring(stiffness = 600f), label = "press")
    Box(
        Modifier.size(44.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(CircleShape)
            .background(RR.NightSky)
            .clickable(interaction, ripple(color = RR.Cream), role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), label, tint = RR.Cream, modifier = Modifier.size(20.dp))
    }
}

/** Keeps showing the last non-null value, so exit animations have content to animate. */
@Composable
internal fun <T> rememberLast(value: T?): T? {
    val holder = remember { arrayOfNulls<Any?>(1) }
    if (value != null) holder[0] = value
    @Suppress("UNCHECKED_CAST")
    return holder[0] as T?
}
