package io.github.arielfikru.rabbitrocket.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import io.github.arielfikru.rabbitrocket.R
import io.github.arielfikru.rabbitrocket.hls.MediaKind
import io.github.arielfikru.rabbitrocket.hls.MediaTypes
import kotlinx.coroutines.launch
import java.net.URLEncoder

// ---------------------------------------------------------------- app shell

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppScreen(vm: MainViewModel) {
    // The browser stays composed once opened, so switching tabs keeps the page and what it found.
    var browserOpened by remember { mutableStateOf(false) }
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
        if (!WindowInsets.isImeVisible) BottomNav(vm.tab, vm.found.size) { vm.tab = it }
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
                    modifier = Modifier.align(Alignment.TopEnd).padding(start = 0.dp)
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

/** Reports `<video>`/`<audio>` sources, including ones set later by players like the KT player. */
private const val SNIFF_JS = """
(function () {
  if (window.__rrSniff) return; window.__rrSniff = 1;
  var seen = {};
  function report(u, via) {
    try {
      if (!u) return;
      u = new URL(u, location.href).href;
      if (!/^https?:/i.test(u) || seen[u]) return;
      seen[u] = 1;
      RRBridge.found(u, via, location.href);
    } catch (e) {}
  }
  function scan() {
    var els = document.querySelectorAll('video, audio, source');
    for (var i = 0; i < els.length; i++) {
      var el = els[i], tag = el.tagName.toLowerCase();
      report(el.currentSrc, tag);
      report(el.getAttribute('src'), tag);
    }
  }
  ['loadstart', 'loadedmetadata', 'play'].forEach(function (t) {
    document.addEventListener(t, function (e) {
      var el = e.target;
      if (el && (el.tagName === 'VIDEO' || el.tagName === 'AUDIO')) report(el.currentSrc || el.src, el.tagName.toLowerCase());
    }, true);
  });
  var pending = 0;
  function soon() { if (!pending) pending = setTimeout(function () { pending = 0; scan(); }, 300); }
  new MutationObserver(soon).observe(document.documentElement || document,
    { childList: true, subtree: true, attributes: true, attributeFilter: ['src'] });
  setInterval(scan, 2000);
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', scan); else scan();
})();
"""

private class BrowserState {
    var address by mutableStateOf("")
    var progress by mutableIntStateOf(100)
    var canBack by mutableStateOf(false)
    var canForward by mutableStateOf(false)
    var started by mutableStateOf(false)
    /** Read from the network thread, which can't touch the WebView. */
    @Volatile var pageUrl: String? = null
}

@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
private fun createWebView(ctx: Context, state: BrowserState, onFound: (Found) -> Unit): WebView {
    val main = Handler(Looper.getMainLooper())
    fun post(url: String, via: String, referer: String?) {
        // A <video> src without a recognisable extension is still a media file.
        val kind = MediaTypes.classify(url)
            ?: (if (via != "jaringan" && MediaTypes.pathExt(url) != "ts") MediaKind.File else null)
            ?: return
        main.post { onFound(Found(url, kind, via, referer)) }
    }

    return WebView(ctx).apply {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        setBackgroundColor(android.graphics.Color.parseColor("#16143B"))
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.setSupportMultipleWindows(false)
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

        addJavascriptInterface(object {
            @JavascriptInterface
            fun found(url: String?, via: String?, frameUrl: String?) {
                if (url != null) post(url, via ?: "video", frameUrl ?: state.pageUrl)
            }
        }, "RRBridge")

        val docStart = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        if (docStart) WebViewCompat.addDocumentStartJavaScript(this, SNIFF_JS, setOf("*"))

        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url.scheme?.lowercase()
                // intent:, market: and friends would leave the app; ads love them.
                return scheme != "http" && scheme != "https"
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url.toString()
                if (MediaTypes.classify(url) != null) {
                    post(url, "jaringan", request.requestHeaders["Referer"] ?: state.pageUrl)
                }
                return null
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                state.pageUrl = url
                state.address = url
                state.started = true
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (!docStart) view.evaluateJavascript(SNIFF_JS, null)
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                state.pageUrl = url
                state.address = url
                state.canBack = view.canGoBack()
                state.canForward = view.canGoForward()
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                state.progress = newProgress
            }
        }
        setDownloadListener { url, _, _, mime, _ ->
            val kind = MediaTypes.classify(url, mime)
                ?: if (mime?.startsWith("audio/") == true) MediaKind.File else null
            if (kind != null) main.post { onFound(Found(url, kind, "download", state.pageUrl)) }
            else main.post { Toast.makeText(ctx, "Bukan file video, dilewati", Toast.LENGTH_SHORT).show() }
        }
    }
}

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

@Composable
fun BrowserScreen(vm: MainViewModel, visible: Boolean, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val state = remember { BrowserState() }
    val web = remember { createWebView(ctx, state, vm::onFound) }
    var sheetOpen by remember { mutableStateOf(false) }
    var lastPage by remember { mutableStateOf<String?>(null) }

    DisposableEffect(web) { onDispose { web.destroy() } }
    LaunchedEffect(visible) {
        if (visible) {
            web.onResume()
        } else {
            // Leaving the tab shouldn't leave a video talking in the background.
            web.evaluateJavascript("document.querySelectorAll('video,audio').forEach(function(m){m.pause()})", null)
            web.onPause()
        }
    }
    // A new page means a new list; same-page updates (SPA routes, reloads) keep what was found.
    LaunchedEffect(state.address) {
        val page = state.address.substringBefore('#').ifEmpty { null }
        if (page != null && page != lastPage) {
            if (lastPage != null) vm.clearFound()
            lastPage = page
        }
    }
    BackHandler(enabled = visible) {
        when {
            sheetOpen -> sheetOpen = false
            state.canBack -> web.goBack()
            else -> vm.tab = 0
        }
    }

    Box(
        modifier.fillMaxSize().background(RR.NightSky)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
    ) {
        Column(Modifier.fillMaxSize()) {
            AddressBar(state, web, visible)
            Box(Modifier.weight(1f)) {
                AndroidView(
                    factory = { web },
                    // An empty WebView paints its background past its bounds, over the address bar.
                    update = { it.visibility = if (visible && state.started) View.VISIBLE else View.INVISIBLE },
                    modifier = Modifier.fillMaxSize(),
                )
                if (!state.started) StartHint()
            }
        }

        FoundPill(
            count = vm.found.size,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
        ) { sheetOpen = true }

        // In-layout sheet: a dialog window would bring its own (light) navigation bar.
        FoundSheet(open = sheetOpen, onDismiss = { sheetOpen = false }) {
            FoundList(
                vm.found,
                onDownload = { f ->
                    sheetOpen = false
                    vm.downloadFound(f, web.settings.userAgentString)
                },
            )
        }
    }
}

@Composable
private fun FoundSheet(open: Boolean, onDismiss: () -> Unit, content: @Composable () -> Unit) {
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
            val scope = androidx.compose.runtime.rememberCoroutineScope()
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
private fun AddressBar(state: BrowserState, web: WebView, visible: Boolean) {
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
            web.loadUrl(normalize(text))
            web.requestFocus()
        }
    }
    val loading = state.progress < 100 && state.started
    // A hidden tab must not keep a focused field: its selection handles would float over Home.
    LaunchedEffect(visible) { if (!visible) focus.clearFocus(force = true) }

    Column {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { web.goBack() }, enabled = state.canBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Kembali", tint = if (state.canBack) RR.Cream else RR.Ring)
            }
            IconButton(onClick = { web.goForward() }, enabled = state.canForward) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, "Maju", tint = if (state.canForward) RR.Cream else RR.Ring)
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
                cursorBrush = androidx.compose.ui.graphics.SolidColor(RR.Flame),
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
            IconButton(onClick = { if (loading) web.stopLoading() else web.reload() }, enabled = state.started) {
                Icon(
                    if (loading) Icons.Default.Close else Icons.Default.Refresh,
                    if (loading) "Berhenti" else "Muat ulang",
                    tint = if (state.started) RR.Cream else RR.Ring,
                )
            }
        }
        // Flame progress line; fades out once the page settles.
        val shown by animateFloatAsState(if (loading) 1f else 0f, tween(if (loading) 120 else 400), label = "bar")
        val fraction by animateFloatAsState(state.progress / 100f, tween(240), label = "progress")
        Box(Modifier.fillMaxWidth().height(2.dp).graphicsLayer { alpha = shown }) {
            Box(Modifier.fillMaxWidth(fraction).height(2.dp).background(RR.Flame))
        }
    }
}

@Composable
private fun StartHint() {
    Column(
        Modifier.fillMaxSize().background(RR.NightSky).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
    ) {
        Image(painterResource(R.drawable.ic_mascot), null, Modifier.size(84.dp, 92.dp))
        Text(
            "Buka halaman videonya lalu putar. Link HLS atau MP4 yang muncul akan dikumpulkan di sini.",
            style = Type.small.copy(textAlign = TextAlign.Center),
            modifier = Modifier.widthIn(max = 280.dp),
        )
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
private fun FoundList(found: List<Found>, onDownload: (Found) -> Unit) {
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
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
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
