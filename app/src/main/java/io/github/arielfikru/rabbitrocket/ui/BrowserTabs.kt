package io.github.arielfikru.rabbitrocket.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import io.github.arielfikru.rabbitrocket.hls.MediaKind
import io.github.arielfikru.rabbitrocket.hls.MediaTypes
import io.github.arielfikru.rabbitrocket.net.AdBlock
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream

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

internal class BrowserState {
    var address by mutableStateOf("")
    var title by mutableStateOf("")
    var progress by mutableIntStateOf(100)
    var canBack by mutableStateOf(false)
    var canForward by mutableStateOf(false)
    var started by mutableStateOf(false)
    /** Read from the network thread, which can't touch the WebView. */
    @Volatile var pageUrl: String? = null
}

internal class Tab(val id: Long, restoreUrl: String?, restoreTitle: String?) {
    val state = BrowserState().apply {
        if (restoreUrl != null) {
            address = restoreUrl
            title = restoreTitle.orEmpty()
            started = true
        }
    }
    /** Created when the tab is first shown; recreated if its renderer dies. */
    var web by mutableStateOf<WebView?>(null)
    /** Loaded as soon as [web] exists. */
    var pendingUrl: String? = restoreUrl
    /** Media links spotted on the current page, newest first. */
    val found = mutableStateListOf<Found>()
    /** Requests and popups stopped on the current page. */
    var blocked by mutableIntStateOf(0)
    /** Set for tabs a page opened; Back on their first page returns to the opener. */
    var openerId: Long? = null
    private var lastPage: String? = null

    val url: String? get() = state.pageUrl ?: pendingUrl ?: state.address.ifEmpty { null }

    fun addFound(f: Found) {
        if (found.any { it.url == f.url }) return
        found.add(0, f)
        if (found.size > 40) found.removeAt(found.lastIndex)
    }

    /** A new page means a new list; same-page updates (SPA hashes, reloads) keep what was found. */
    fun onPage(url: String) {
        val page = url.substringBefore('#')
        if (page != lastPage) {
            if (lastPage != null) {
                found.clear()
                blocked = 0
            }
            lastPage = page
        }
    }
}

/**
 * Open tabs live for the whole process, not the activity: a rotation, theme change or trip to the
 * Download tab must never cost the user their page. WebViews sit on a [MutableContextWrapper]
 * that is pointed at whichever activity is current.
 */
internal object Tabs {
    val list = mutableStateListOf<Tab>()
    var activeId by mutableStateOf(-1L)
        private set
    val active: Tab? get() = list.firstOrNull { it.id == activeId } ?: list.firstOrNull()

    private var wrapper: MutableContextWrapper? = null
    private var nextId = 1L
    private val main = Handler(Looper.getMainLooper())

    fun attach(activity: Context) {
        val w = wrapper
        if (w != null) {
            w.baseContext = activity
            return
        }
        wrapper = MutableContextWrapper(activity)
        restore(activity)
    }

    /** Called when the activity goes away, so tabs don't keep it alive. */
    fun detach(activity: Context) {
        wrapper?.let { if (it.baseContext === activity) it.baseContext = activity.applicationContext }
    }

    fun webFor(tab: Tab): WebView = tab.web ?: createWebView(wrapper!!, tab).also { w ->
        tab.web = w
        tab.pendingUrl?.let { w.loadUrl(it) }
        tab.pendingUrl = null
    }

    fun newTab(url: String? = null, opener: Tab? = null, select: Boolean = true): Tab {
        val t = Tab(nextId++, null, null).apply { openerId = opener?.id }
        val at = opener?.let { list.indexOf(it) + 1 } ?: list.size
        list.add(at.coerceIn(0, list.size), t)
        if (url != null) webFor(t).loadUrl(url)
        if (select) select(t) else save()
        return t
    }

    fun select(tab: Tab) {
        activeId = tab.id
        save()
    }

    fun close(tab: Tab) {
        val i = list.indexOf(tab)
        if (i < 0) return
        list.removeAt(i)
        tab.web?.let { w ->
            (w.parent as? ViewGroup)?.removeView(w)
            w.destroy()
        }
        if (list.isEmpty()) newTab()
        else if (activeId == tab.id) {
            val opener = list.firstOrNull { it.id == tab.openerId }
            select(opener ?: list[(i - 1).coerceAtLeast(0)])
        } else save()
    }

    fun save() {
        val ctx = wrapper ?: return
        val arr = JSONArray()
        list.forEach { t -> arr.put(JSONObject().put("u", t.url ?: "").put("t", t.state.title)) }
        prefs(ctx).edit()
            .putString("tabs", arr.toString())
            .putInt("active", list.indexOfFirst { it.id == activeId }.coerceAtLeast(0))
            .apply()
    }

    private fun restore(ctx: Context) {
        val p = prefs(ctx)
        runCatching {
            val arr = JSONArray(p.getString("tabs", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list += Tab(nextId++, o.optString("u").ifEmpty { null }, o.optString("t"))
            }
        }
        if (list.isEmpty()) list += Tab(nextId++, null, null)
        activeId = list[p.getInt("active", 0).coerceIn(0, list.lastIndex)].id
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("browser", Context.MODE_PRIVATE)

    // ------------------------------------------------------------ WebView

    private fun blockedResponse() = WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    private fun pageHost(tab: Tab) = tab.state.pageUrl?.let { Uri.parse(it).host }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun createWebView(ctx: Context, tab: Tab): WebView {
        val state = tab.state
        fun post(url: String, via: String, referer: String?) {
            // A <video> src without a recognisable extension is still a media file.
            val kind = MediaTypes.classify(url)
                ?: (if (via != "jaringan" && MediaTypes.pathExt(url) != "ts") MediaKind.File else null)
                ?: return
            main.post { tab.addFound(Found(url, kind, via, referer)) }
        }

        return WebView(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(android.graphics.Color.parseColor("#16143B"))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // Popups come to onCreateWindow, which decides between a new tab and the bin.
            settings.setSupportMultipleWindows(true)
            settings.javaScriptCanOpenWindowsAutomatically = true
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
                    if (scheme != "http" && scheme != "https") return true
                    // Click-jacking overlays send the whole page to an ad network.
                    if (request.isForMainFrame && AdBlock.shouldBlock(request.url.host, pageHost(tab))) {
                        main.post { tab.blocked++ }
                        return true
                    }
                    return false
                }

                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    if (!request.isForMainFrame && AdBlock.shouldBlock(request.url.host, pageHost(tab))) {
                        main.post { tab.blocked++ }
                        return blockedResponse()
                    }
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
                    tab.onPage(url)
                }

                override fun onPageFinished(view: WebView, url: String) {
                    if (!docStart) view.evaluateJavascript(SNIFF_JS, null)
                    save()
                }

                override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                    state.pageUrl = url
                    state.address = url
                    state.canBack = view.canGoBack()
                    state.canForward = view.canGoForward()
                    tab.onPage(url)
                }

                /** Without this a killed renderer takes the whole app down with it. */
                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    if (tab.web === view) {
                        tab.pendingUrl = state.pageUrl
                        tab.web = null
                    }
                    (view.parent as? ViewGroup)?.removeView(view)
                    view.destroy()
                    return true
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    state.progress = newProgress
                }

                override fun onReceivedTitle(view: WebView, title: String?) {
                    state.title = title.orEmpty()
                }

                override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                    val hit = view.hitTestResult
                    val link = hit.extra?.takeIf {
                        hit.type == WebView.HitTestResult.SRC_ANCHOR_TYPE || hit.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE
                    }
                    if (AdBlock.enabled) {
                        // Only a real tap on a target=_blank link opens a tab; scripted popunders are dropped.
                        if (isUserGesture && link != null && link.startsWith("http") && !AdBlock.shouldBlock(Uri.parse(link).host, null)) {
                            newTab(link, opener = tab)
                        } else {
                            tab.blocked++
                        }
                        return false
                    }
                    val child = newTab(opener = tab)
                    (resultMsg.obj as WebView.WebViewTransport).webView = webFor(child)
                    resultMsg.sendToTarget()
                    return true
                }

                override fun onCloseWindow(window: WebView) {
                    list.firstOrNull { it.web === window }?.let { close(it) }
                }
            }
            setDownloadListener { url, _, _, mime, _ ->
                val kind = MediaTypes.classify(url, mime)
                    ?: if (mime?.startsWith("audio/") == true) MediaKind.File else null
                if (kind != null) main.post { tab.addFound(Found(url, kind, "download", state.pageUrl)) }
                else main.post { Toast.makeText(ctx, "Bukan file video, dilewati", Toast.LENGTH_SHORT).show() }
            }
        }
    }
}

/** Saved pages, newest first. */
internal data class Bookmark(val url: String, val title: String)

internal object Bookmarks {
    val list = mutableStateListOf<Bookmark>()
    private var prefs: android.content.SharedPreferences? = null

    fun init(ctx: Context) {
        if (prefs != null) return
        val p = ctx.applicationContext.getSharedPreferences("bookmarks", Context.MODE_PRIVATE)
        prefs = p
        runCatching {
            val arr = JSONArray(p.getString("list", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list += Bookmark(o.getString("u"), o.optString("t"))
            }
        }
    }

    fun contains(url: String?) = url != null && list.any { it.url == url }

    fun toggle(url: String, title: String) {
        if (!list.removeAll { it.url == url }) list.add(0, Bookmark(url, title.ifBlank { Uri.parse(url).host ?: url }))
        save()
    }

    fun remove(b: Bookmark) {
        list.remove(b)
        save()
    }

    private fun save() {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("u", it.url).put("t", it.title)) }
        prefs?.edit()?.putString("list", arr.toString())?.apply()
    }
}
