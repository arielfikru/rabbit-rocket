package io.github.arielfikru.rabbitrocket.ui

import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import io.github.arielfikru.rabbitrocket.R
import io.github.arielfikru.rabbitrocket.hls.MediaKind
import io.github.arielfikru.rabbitrocket.hls.MediaTypes
import io.github.arielfikru.rabbitrocket.net.AdBlock
import io.github.arielfikru.rabbitrocket.net.DnsChoice
import io.github.arielfikru.rabbitrocket.net.NetSettings
import kotlinx.coroutines.launch
import java.net.URLEncoder
import java.text.NumberFormat
import java.util.Locale

// ---------------------------------------------------------------- app shell

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppScreen(vm: MainViewModel) {
    // The browser stays composed once opened, so switching tabs keeps the page and what it found.
    var browserOpened by rememberSaveable { mutableStateOf(false) }
    if (vm.tab == 1) browserOpened = true
    // Home fades; the browser switches instantly because a WebView under a translucent
    // graphicsLayer stops drawing its Compose siblings.
    val homeAlpha by animateFloatAsState(
        if (vm.tab == 0) 1f else 0f,
        if (vm.tab == 0) tween(210, delayMillis = 90) else tween(90),
        label = "home",
    )

    Column(Modifier.fillMaxSize().background(RR.NightSky)) {
        Box(Modifier.weight(1f).imePadding()) {
            HomeScreen(
                vm,
                Modifier.zIndex(if (vm.tab == 0) 1f else 0f).graphicsLayer { alpha = homeAlpha },
            )
            if (browserOpened) {
                BrowserScreen(
                    vm,
                    visible = vm.tab == 1,
                    modifier = Modifier.zIndex(if (vm.tab == 1) 1f else 0f),
                )
            }
        }
        if (!WindowInsets.isImeVisible) BottomNav(vm.tab, Tabs.active?.found?.size ?: 0) { vm.tab = it }
    }
}

@Composable
private fun BottomNav(tab: Int, found: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(NavBg).windowInsetsPadding(WindowInsets.navigationBars)
            .height(64.dp),
    ) {
        NavItem("Download", R.drawable.ic_nav_home, tab == 0, 0, Modifier.weight(1f)) { onSelect(0) }
        NavItem("Browser", R.drawable.ic_nav_globe, tab == 1, found, Modifier.weight(1f)) { onSelect(1) }
    }
}

@Composable
private fun NavItem(label: String, icon: Int, selected: Boolean, badge: Int, modifier: Modifier, onClick: () -> Unit) {
    val tint by animateColor(if (selected) RR.Flame else RR.Muted)
    Column(
        modifier.fillMaxSize()
            .clickable(remember { MutableInteractionSource() }, ripple(bounded = false, radius = 40.dp), role = Role.Tab, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box {
            Icon(painterResource(icon), null, Modifier.size(24.dp), tint = tint)
            if (badge > 0) {
                Text(
                    if (badge > 9) "9+" else "$badge",
                    style = Type.small.copy(color = RR.NightSky, fontWeight = FontWeight.Bold, fontSize = 10.sp),
                    modifier = Modifier.align(Alignment.TopEnd)
                        .graphicsLayer { translationX = 12.dp.toPx(); translationY = (-6).dp.toPx() }
                        .clip(RoundedCornerShape(percent = 50)).background(RR.Pink)
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
        }
        Text(label, style = Type.small.copy(color = tint, fontSize = 12.sp, fontWeight = FontWeight.Medium))
    }
}

@Composable
private fun animateColor(target: Color) = androidx.compose.animation.animateColorAsState(target, tween(200), label = "tint")

private val NavBg = Color(0xFF0E0C29)

// ---------------------------------------------------------------- browser

/** Dev boxes and LAN players rarely have TLS. */
private val LOCAL_HOST = Regex("""^(localhost|127\.|10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.)""")

/** Typed text becomes a URL, or a search when it doesn't look like one. */
private fun normalize(input: String): String {
    val t = input.trim()
    return when {
        t.startsWith("http://", true) || t.startsWith("https://", true) -> t
        ' ' !in t && LOCAL_HOST.containsMatchIn(t) -> "http://$t"
        ' ' !in t && ('.' in t || ':' in t) -> "https://$t"
        else -> "https://duckduckgo.com/?q=" + URLEncoder.encode(t, "UTF-8")
    }
}

private enum class BrowserSheet { Found, Menu, Tabs, History }

@Composable
fun BrowserScreen(vm: MainViewModel, visible: Boolean, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    // Tabs outlive the activity; this only points them at the current one.
    remember(ctx) {
        Tabs.attach(ctx)
        Bookmarks.init(ctx)
        BrowserHistory.init(ctx)
        NetSettings.applyToWebView()
    }
    DisposableEffect(ctx) { onDispose { Tabs.detach(ctx) } }

    val tab = Tabs.active ?: return
    var sheet by remember { mutableStateOf<BrowserSheet?>(null) }

    // Only the visible tab may play or run timers.
    LaunchedEffect(visible, tab.id) {
        Tabs.list.forEach { t ->
            val w = t.web ?: return@forEach
            if (visible && t === tab) {
                w.onResume()
            } else {
                w.evaluateJavascript("document.querySelectorAll('video,audio').forEach(function(m){m.pause()})", null)
                w.onPause()
            }
        }
    }
    BackHandler(enabled = visible) {
        when {
            sheet != null -> sheet = null
            tab.state.canBack -> tab.web?.goBack()
            tab.openerId != null -> Tabs.close(tab)
            else -> vm.tab = 0
        }
    }
    val load = { url: String -> Tabs.webFor(tab).loadUrl(url) }

    Box(
        modifier.fillMaxSize().background(RR.NightSky)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
    ) {
        Column(Modifier.fillMaxSize()) {
            key(tab.id) {
                AddressBar(tab, visible, onTabs = { sheet = BrowserSheet.Tabs }, onMenu = { sheet = BrowserSheet.Menu }, load = load)
            }
            Box(Modifier.weight(1f)) {
                if (tab.web != null || tab.pendingUrl != null) {
                    AndroidView(
                        factory = { FrameLayout(it) },
                        update = { box ->
                            val w = Tabs.webFor(tab)
                            if (w.parent !== box) {
                                box.removeAllViews()
                                (w.parent as? ViewGroup)?.removeView(w)
                                box.addView(w)
                            }
                            // An empty WebView paints its background past its bounds, over the address bar.
                            box.visibility = if (visible && tab.state.started) View.VISIBLE else View.INVISIBLE
                        },
                        onRelease = { it.removeAllViews() },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                if (!tab.state.started) NewTabPage(load)
            }
        }

        FoundPill(
            count = tab.found.size,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
        ) { sheet = BrowserSheet.Found }

        // In-layout sheet: a dialog window would bring its own (light) navigation bar.
        Sheet(open = sheet != null, onDismiss = { sheet = null }) {
            when (rememberLast(sheet)) {
                BrowserSheet.Found -> FoundList(
                    tab.found,
                    onSave = { f -> vm.saveFound(f, Tabs.webFor(tab).settings.userAgentString) },
                    onDownload = { f ->
                        sheet = null
                        vm.downloadFound(f, Tabs.webFor(tab).settings.userAgentString)
                    },
                )
                BrowserSheet.Menu -> MenuContent(tab, onHistory = { sheet = BrowserSheet.History }) { sheet = null }
                BrowserSheet.Tabs -> TabsContent { sheet = null }
                BrowserSheet.History -> HistoryContent(
                    open = { url ->
                        load(url)
                        sheet = null
                    },
                )
                null -> {}
            }
        }
    }
}

@Composable
private fun Sheet(open: Boolean, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val scrim by animateFloatAsState(if (open) 0.55f else 0f, tween(if (open) 240 else 180), label = "scrim")
    Box(Modifier.fillMaxSize()) {
        if (scrim > 0f) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = scrim))
                    .clickable(remember { MutableInteractionSource() }, null, onClick = onDismiss),
            )
        }
        AnimatedVisibility(
            visible = open,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(tween(420, easing = ExpoOut)) { it },
            exit = slideOutVertically(tween(240, easing = InOut)) { it },
        ) {
            // Drag the handle area down to dismiss; the sheet follows the finger and springs back.
            val drag = remember { Animatable(0f) }
            val scope = rememberCoroutineScope()
            Column(
                Modifier.fillMaxWidth()
                    .graphicsLayer { translationY = drag.value }
                    .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                    .background(RR.OrbitDeck)
                    .clickable(remember { MutableInteractionSource() }, null) {}
                    .draggable(
                        rememberDraggableState { d -> scope.launch { drag.snapTo((drag.value + d).coerceAtLeast(0f)) } },
                        Orientation.Vertical,
                        onDragStopped = { v ->
                            if (drag.value > 240f || v > 2000f) onDismiss()
                            else drag.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = 500f))
                        },
                    ),
            ) {
                Box(
                    Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(36.dp, 4.dp).clip(RoundedCornerShape(percent = 50)).background(RR.Ring))
                }
                content()
            }
        }
    }
}

@Composable
private fun AddressBar(tab: Tab, visible: Boolean, onTabs: () -> Unit, onMenu: () -> Unit, load: (String) -> Unit) {
    val state = tab.state
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var focused by remember { mutableStateOf(false) }
    /** The focusing tap's cursor placement becomes "select all", like a browser address bar. */
    var selectAllNext by remember { mutableStateOf(false) }
    var field by remember { mutableStateOf(TextFieldValue("")) }
    var editing by remember { mutableStateOf(false) }
    if (!editing && field.text != state.address) field = TextFieldValue(state.address)
    val text = field.text
    val go = {
        keyboard?.hide()
        focus.clearFocus()
        editing = false
        if (text.isNotBlank()) {
            load(normalize(text))
            tab.web?.requestFocus()
        }
    }
    val loading = state.progress < 100 && state.started
    // A hidden tab must not keep a focused field: its selection handles would float over Home.
    LaunchedEffect(visible) { if (!visible) focus.clearFocus(force = true) }

    Column {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { tab.web?.goBack() }, enabled = state.canBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Kembali", tint = if (state.canBack) RR.Cream else RR.Ring)
            }
            BasicTextField(
                value = field,
                onValueChange = {
                    if (it.text != field.text) {
                        editing = true
                        selectAllNext = false
                        field = it
                    } else if (selectAllNext) {
                        selectAllNext = false
                        field = it.copy(selection = TextRange(0, it.text.length))
                    } else {
                        field = it
                    }
                },
                singleLine = true,
                textStyle = Type.body.copy(fontSize = 14.sp),
                cursorBrush = SolidColor(RR.Flame),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { go() }),
                modifier = Modifier.weight(1f).onFocusChanged { f ->
                    if (f.isFocused && !focused) selectAllNext = true
                    focused = f.isFocused
                    if (!f.isFocused) editing = false
                },
                decorationBox = { inner ->
                    Box(
                        Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(percent = 50)).background(RR.OrbitDeck)
                            .padding(horizontal = 16.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (text.isEmpty()) Text("Cari atau ketik alamat situs", style = Type.body.copy(fontSize = 14.sp, color = RR.Muted))
                        inner()
                    }
                },
            )
            IconButton(onClick = onTabs) { TabCount(Tabs.list.size) }
            IconButton(onClick = onMenu) { Icon(Icons.Default.MoreVert, "Menu", tint = RR.Cream) }
        }
        // Flame progress line; fades out once the page settles.
        val shown by animateFloatAsState(if (loading) 1f else 0f, tween(if (loading) 120 else 400), label = "bar")
        val fraction by animateFloatAsState(state.progress / 100f, tween(240), label = "progress")
        Box(Modifier.fillMaxWidth().height(2.dp).graphicsLayer { alpha = shown }) {
            Box(Modifier.fillMaxWidth(fraction).height(2.dp).background(RR.Flame))
        }
    }
}

/** The tab switcher button: a rounded square holding the number of open tabs. */
@Composable
private fun TabCount(n: Int) {
    Box(
        Modifier.size(22.dp).border(2.dp, RR.Cream, RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (n > 99) ":D" else "$n",
            style = Type.small.copy(color = RR.Cream, fontSize = 11.sp, fontWeight = FontWeight.Bold),
        )
    }
}

@Composable
private fun NewTabPage(load: (String) -> Unit) {
    Column(
        Modifier.fillMaxSize().background(RR.NightSky).verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(32.dp))
        Image(painterResource(R.drawable.ic_mascot), null, Modifier.size(84.dp, 92.dp))
        Text(
            "Buka halaman videonya lalu putar. Link HLS atau MP4 yang muncul akan dikumpulkan di sini.",
            style = Type.small.copy(textAlign = TextAlign.Center),
            modifier = Modifier.widthIn(max = 280.dp).padding(top = 14.dp),
        )
        val recent = BrowserHistory.list.asSequence().filter { v -> Bookmarks.list.none { it.url == v.url } }.take(5).toList()
        if (recent.isNotEmpty()) {
            Text(
                "Terakhir dibuka",
                style = Type.section.copy(fontSize = 17.sp),
                modifier = Modifier.fillMaxWidth().padding(top = 32.dp, bottom = 10.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                recent.forEach { v ->
                    PageRow(
                        title = v.title,
                        url = v.url,
                        selected = false,
                        onClick = { load(v.url) },
                        onRemove = { BrowserHistory.remove(v) },
                        removeLabel = "Hapus dari riwayat",
                    )
                }
            }
        }
        if (Bookmarks.list.isNotEmpty()) {
            Text(
                "Bookmark",
                style = Type.section.copy(fontSize = 17.sp),
                modifier = Modifier.fillMaxWidth().padding(top = 32.dp, bottom = 10.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Bookmarks.list.forEach { b ->
                    PageRow(
                        title = b.title,
                        url = b.url,
                        selected = false,
                        onClick = { load(b.url) },
                        onRemove = { Bookmarks.remove(b) },
                        removeLabel = "Hapus bookmark",
                    )
                }
            }
        }
    }
}

/** A page in a list: title and host, tap to open, × to remove. Shared by bookmarks and tabs. */
@Composable
private fun PageRow(title: String, url: String?, selected: Boolean, onClick: () -> Unit, onRemove: () -> Unit, removeLabel: String) {
    val host = url?.let { Uri.parse(it).host?.removePrefix("www.") }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(if (selected) RR.Track else RR.OrbitDeck)
            .then(if (selected) Modifier.border(2.dp, RR.Flame, RoundedCornerShape(16.dp)) else Modifier)
            .clickable(onClick = onClick)
            .padding(start = 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(
                title.ifBlank { host ?: "Tab baru" },
                style = Type.body.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (host != null) Text(host, style = Type.small.copy(fontSize = 12.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onRemove) { Icon(Icons.Default.Close, removeLabel, tint = RR.Muted) }
    }
}

@Composable
private fun TabsContent(onDone: () -> Unit) {
    Column(Modifier.padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${Tabs.list.size} tab terbuka", style = Type.section, modifier = Modifier.weight(1f))
            SmallButton("+ Tab baru", RR.Flame, RR.NightSky) {
                Tabs.newTab()
                onDone()
            }
        }
        LazyColumn(
            Modifier.fillMaxWidth().heightIn(max = 480.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            items(Tabs.list, key = { it.id }) { t ->
                PageRow(
                    title = t.state.title,
                    url = t.url,
                    selected = t === Tabs.active,
                    onClick = {
                        Tabs.select(t)
                        onDone()
                    },
                    onRemove = { Tabs.close(t) },
                    removeLabel = "Tutup tab",
                )
            }
        }
    }
}

@Composable
private fun MenuContent(tab: Tab, onHistory: () -> Unit, onDone: () -> Unit) {
    val state = tab.state
    val loading = state.progress < 100 && state.started
    val url = state.pageUrl
    val saved = Bookmarks.contains(url)
    val ctx = LocalContext.current

    Column(
        Modifier.fillMaxWidth().heightIn(max = 600.dp).verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            MenuAction(Icons.AutoMirrored.Filled.ArrowForward, "Maju", state.canForward) {
                tab.web?.goForward()
                onDone()
            }
            MenuAction(if (loading) Icons.Default.Close else Icons.Default.Refresh, if (loading) "Berhenti" else "Muat ulang", state.started) {
                if (loading) tab.web?.stopLoading() else tab.web?.reload()
                onDone()
            }
            MenuAction(Icons.Default.Star, if (saved) "Tersimpan" else "Bookmark", url != null, tint = if (saved) RR.Flame else RR.Cream) {
                if (url != null) {
                    Bookmarks.toggle(url, state.title)
                    Toast.makeText(ctx, if (saved) "Bookmark dihapus" else "Disimpan ke bookmark", Toast.LENGTH_SHORT).show()
                }
            }
            MenuAction(Icons.Default.Add, "Tab baru", true) {
                Tabs.newTab()
                onDone()
            }
        }

        Row(
            Modifier.padding(top = 16.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(RR.NightSky)
                .clickable(role = Role.Button, onClick = onHistory)
                .heightIn(min = 52.dp).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Default.DateRange, null, tint = RR.Cream)
            Text("Riwayat", style = Type.body.copy(fontWeight = FontWeight.Medium), modifier = Modifier.weight(1f))
            Text("${BrowserHistory.list.size} situs", style = Type.small.copy(fontSize = 12.sp))
        }

        Text("Perlindungan", style = Type.section.copy(fontSize = 17.sp), modifier = Modifier.padding(top = 24.dp, bottom = 8.dp))
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Blokir iklan & popup", style = Type.body.copy(color = Color.White))
                val n = NumberFormat.getIntegerInstance(Locale("id")).format(AdBlock.rules.size)
                Text(
                    if (NetSettings.adBlock) "${tab.blocked} diblokir di halaman ini · $n domain" else "Mati",
                    style = Type.small.copy(fontSize = 12.sp),
                )
            }
            Switch(
                checked = NetSettings.adBlock,
                onCheckedChange = {
                    NetSettings.enableAdBlock(it)
                    tab.web?.reload()
                },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = RR.NightSky, checkedTrackColor = RR.Flame, checkedBorderColor = RR.Flame,
                    uncheckedThumbColor = RR.Muted, uncheckedTrackColor = RR.Track, uncheckedBorderColor = RR.Track,
                ),
            )
        }

        Text("DNS", style = Type.section.copy(fontSize = 17.sp), modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            DnsChoice.entries.forEach { c ->
                val on = NetSettings.dns == c
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                        .background(if (on) RR.Track else RR.NightSky)
                        .clickable(role = Role.RadioButton) {
                            if (!on) {
                                NetSettings.chooseDns(c)
                                tab.web?.reload()
                            }
                        }
                        .heightIn(min = 52.dp).padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(c.label, style = Type.body.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium))
                        Text(c.detail, style = Type.small.copy(fontSize = 12.sp))
                    }
                    if (on) Icon(Icons.Default.Check, null, tint = RR.Flame)
                }
            }
        }
        Text(
            if (NetSettings.proxySupported) "Dipakai untuk browser dan download. Membantu membuka situs yang diblokir lewat DNS operator."
            else "WebView di HP ini belum mendukung DNS khusus, jadi DNS ini hanya dipakai untuk download.",
            style = Type.small.copy(fontSize = 12.sp),
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}

@Composable
private fun HistoryContent(open: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }
    val groups = groupHistory(BrowserHistory.list, query)

    Column(Modifier.padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Riwayat", style = Type.section, modifier = Modifier.weight(1f))
            if (BrowserHistory.list.isNotEmpty()) {
                if (confirmClear) {
                    SmallButton("Batal", RR.NightSky, RR.Muted) { confirmClear = false }
                    Spacer(Modifier.size(8.dp))
                    SmallButton("Hapus semua", RR.Danger, RR.NightSky) {
                        BrowserHistory.clear()
                        confirmClear = false
                    }
                } else {
                    SmallButton("Hapus semua", RR.NightSky, RR.Muted) { confirmClear = true }
                }
            }
        }
        BasicTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            textStyle = Type.body.copy(fontSize = 14.sp),
            cursorBrush = SolidColor(RR.Flame),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            decorationBox = { inner ->
                Box(
                    Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(percent = 50)).background(RR.NightSky)
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (query.isEmpty()) Text("Cari judul atau alamat", style = Type.body.copy(fontSize = 14.sp, color = RR.Muted))
                    inner()
                }
            },
        )
        LazyColumn(
            Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 480.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            if (groups.isEmpty()) {
                item {
                    Text(
                        if (query.isBlank()) "Belum ada halaman yang dibuka." else "Tidak ada yang cocok dengan \"$query\".",
                        style = Type.small,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                }
            }
            groups.forEach { (day, visits) ->
                item(key = "day-$day") {
                    Text(day, style = Type.label.copy(color = RR.Muted), modifier = Modifier.padding(top = 8.dp))
                }
                items(visits, key = { it.url }) { v ->
                    PageRow(
                        title = v.title,
                        url = v.url,
                        selected = false,
                        onClick = { open(v.url) },
                        onRemove = { BrowserHistory.remove(v) },
                        removeLabel = "Hapus dari riwayat",
                    )
                }
            }
        }
    }
}

@Composable
private fun MenuAction(icon: ImageVector, label: String, enabled: Boolean, tint: Color = RR.Cream, onClick: () -> Unit) {
    val c = if (enabled) tint else RR.Ring
    Column(
        Modifier.widthIn(min = 72.dp).clip(RoundedCornerShape(16.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, null, tint = c)
        Text(label, style = Type.small.copy(color = c, fontSize = 12.sp))
    }
}

@Composable
private fun FoundPill(count: Int, modifier: Modifier, onClick: () -> Unit) {
    // A small bump each time a new link lands, so the user notices without a toast.
    val bump = remember { Animatable(1f) }
    LaunchedEffect(count) {
        if (count > 0) {
            bump.snapTo(1.08f)
            bump.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 500f))
        }
    }
    AnimatedVisibility(
        visible = count > 0,
        modifier = modifier,
        enter = slideInVertically(spring(dampingRatio = 0.7f, stiffness = 400f)) { it * 2 } + fadeIn(tween(150)),
        exit = slideOutVertically(tween(200)) { it * 2 } + fadeOut(tween(150)),
    ) {
        val n = rememberLast(count.takeIf { it > 0 }) ?: 1
        Row(
            Modifier.graphicsLayer { scaleX = bump.value; scaleY = bump.value }
                .clip(RoundedCornerShape(percent = 50)).background(RR.Flame)
                .clickable(onClick = onClick, role = Role.Button)
                .heightIn(min = 48.dp).padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(painterResource(R.drawable.ic_rocket_line), null, Modifier.size(20.dp), tint = RR.NightSky)
            Text("$n media ditemukan", style = Type.button.copy(color = RR.NightSky, fontSize = 15.sp))
        }
    }
}

@Composable
private fun FoundList(found: List<Found>, onSave: (Found) -> Boolean, onDownload: (Found) -> Unit) {
    val clipboard = LocalClipboardManager.current
    val ctx = LocalContext.current
    Column(Modifier.padding(horizontal = 20.dp)) {
        Text("Media di halaman ini", style = Type.section)
        Text(
            "Yang paling atas paling baru. HLS biasanya kualitas terbaik.",
            style = Type.small,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )
        LazyColumn(
            Modifier.fillMaxWidth().heightIn(max = 480.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            items(found, key = { it.url }) { f ->
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(RR.NightSky).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill(
                            if (f.kind == MediaKind.Hls) "HLS" else MediaTypes.extFor(f.url, null, null).uppercase(),
                            if (f.kind == MediaKind.Hls) RR.Violet else RR.Track, Color.White,
                        )
                        Text("dari ${viaLabel(f.via)}", style = Type.small)
                    }
                    Text(
                        f.url,
                        style = Type.small.copy(color = RR.Cream, fontSize = 13.sp),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                        SmallButton("Salin", RR.OrbitDeck, RR.Cream) {
                            clipboard.setText(AnnotatedString(f.url))
                            Toast.makeText(ctx, "Link disalin", Toast.LENGTH_SHORT).show()
                        }
                        SmallButton("Simpan", RR.OrbitDeck, RR.Cream) {
                            val added = onSave(f)
                            Toast.makeText(ctx, if (added) "Disimpan di tab Download" else "Sudah ada di daftar simpan", Toast.LENGTH_SHORT).show()
                        }
                        SmallButton("Download", RR.Flame, RR.NightSky) { onDownload(f) }
                    }
                }
            }
        }
    }
}

private fun viaLabel(via: String) = when (via) {
    "jaringan" -> "lalu lintas jaringan"
    "download" -> "tombol download"
    else -> "elemen <$via>"
}
