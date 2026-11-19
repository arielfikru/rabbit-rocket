package io.github.arielfikru.rabbitrocket.net

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.URI
import java.util.Base64

/**
 * Walks a droplink.co shortlink to its real destination with plain HTTP, no ads or timers shown.
 *
 * The flow (AdLinkFly + "Linkpage" hop sites, seen 2026-11):
 * 1. droplink.co/ALIAS -> hop site `.../Linkpage x/safe2-v2.php`, a page that puts a signed
 *    `adl_*` state into sessionStorage and jumps (via a google/bing referrer URL) to a blog post.
 * 2. The blog post, POSTed with that state + `adl_resume=1`, answers an ad page with a form
 *    (`adl_start_next`) to the next hop; repeated for steps 1..3 on rotating hop domains.
 * 3. The last ad page only has a client-side timer and `<a id="go_d" href="droplink.co/ALIAS">`.
 * 4. droplink.co/ALIAS now shows the AdLinkFly `/links/go` form; POSTed as XHR after its counter,
 *    it answers JSON with the destination URL.
 *
 * Pure JVM (OkHttp + stdlib only) so it runs in local unit tests. Blocking: call it off the main thread.
 */
class LinkSkipper(base: OkHttpClient, private val userAgent: String, private val waitMs: (Int) -> Unit = { Thread.sleep(it * 1000L) }) {

    class SkipException(message: String) : IOException(message)

    // Its own cookie jar: the flow is held together by session, visitor and CSRF cookies.
    private val client = base.newBuilder().cookieJar(MemoryJar()).followRedirects(true).build()

    fun resolve(shortUrl: String, maxSteps: Int = 15): String {
        var r = get(shortUrl, null)
        repeat(maxSteps) {
            val (url, page) = r
            if (!isHop(url) && "adl_" !in page) return url

            // 1) sessionStorage + JS redirect page
            val state = STATE_RE.find(page)?.groupValues?.get(1)
            val dest = DEST_RE.find(page)?.groupValues?.get(1)
            if (state != null && dest != null) {
                val target = unwrapReferrer(jsUnescape(dest))
                if (!isHop(target)) return target
                val fields = parseFlatJson(state) + ("adl_resume" to "1")
                r = post(target, fields, referer = target, xhr = true)
                return@repeat
            }

            // 2) final AdLinkFly step
            if ("action=\"/links/go\"" in page) {
                if (CAPTCHA_RE.containsMatchIn(page)) throw SkipException("droplink meminta captcha")
                val fields = parseForm(page, "ad_form_data") ?: throw SkipException("Form link tidak terbaca")
                val wait = COUNTER_RE.find(page)?.groupValues?.get(1)?.toIntOrNull() ?: 5
                waitMs(wait.coerceIn(0, 30) + 2)
                val (_, json) = post(resolveRef(url, "/links/go"), fields, referer = url, xhr = true)
                val dst = JSON_URL_RE.find(json)?.groupValues?.get(1)?.let(::jsUnescape)
                if (dst.isNullOrBlank()) throw SkipException("droplink tidak memberi link: ${json.take(120)}")
                return dst
            }

            // 3) ad page with the "continue" form to the next hop
            parseFormWithAction(page, "adl_")?.let { (action, fields) ->
                r = post(resolveRef(url, action ?: url), fields, referer = url, xhr = false)
                return@repeat
            }

            // 4) last ad page: timer is client-side only, the button links back to droplink
            GO_D_RE.find(page)?.let { m ->
                val href = m.groupValues.drop(1).first { it.isNotEmpty() }
                r = get(htmlUnescape(href), url)
                return@repeat
            }

            throw SkipException("Halaman shortlink tidak dikenal: $url")
        }
        throw SkipException("Shortlink terlalu banyak langkah")
    }

    // ------------------------------------------------------------ HTTP

    private fun get(url: String, referer: String?): Pair<String, String> =
        exec(Request.Builder().url(url).headers(referer, xhr = false).build())

    private fun post(url: String, fields: Map<String, String>, referer: String?, xhr: Boolean): Pair<String, String> {
        val body = FormBody.Builder().apply { fields.forEach { (k, v) -> add(k, v) } }.build()
        var target = url
        // OkHttp won't re-POST on 307/308, browsers do.
        repeat(5) {
            val req = Request.Builder().url(target).post(body).headers(referer, xhr).build()
            client.newCall(req).execute().use { res ->
                val loc = res.header("Location")
                if ((res.code == 307 || res.code == 308) && loc != null) {
                    target = resolveRef(target, loc)
                    return@repeat
                }
                return read(res)
            }
        }
        throw SkipException("Terlalu banyak redirect")
    }

    private fun exec(req: Request): Pair<String, String> = client.newCall(req).execute().use(::read)

    private fun read(res: Response): Pair<String, String> {
        val text = res.body?.string().orEmpty()
        if (!res.isSuccessful && res.code != 403) throw SkipException("Shortlink membalas HTTP ${res.code}")
        if (res.code == 403) throw SkipException("Alur shortlink kedaluwarsa (HTTP 403)")
        return res.request.url.toString() to text
    }

    private fun Request.Builder.headers(referer: String?, xhr: Boolean) = apply {
        header("User-Agent", userAgent)
        header("Accept-Language", "en-US,en;q=0.9,id;q=0.8")
        referer?.let { header("Referer", it) }
        if (xhr) header("X-Requested-With", "XMLHttpRequest")
    }

    private class MemoryJar : CookieJar {
        private val store = mutableListOf<Cookie>()

        @Synchronized
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            cookies.forEach { c -> store.removeAll { it.name == c.name && it.domain == c.domain && it.path == c.path }; store += c }
        }

        @Synchronized
        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val now = System.currentTimeMillis()
            store.removeAll { it.expiresAt < now }
            return store.filter { it.matches(url) }
        }
    }

    companion object {
        private val SHORT_HOSTS = setOf("droplink.co", "www.droplink.co")

        /** `https://droplink.co/<alias>` and nothing else (not the home page, not /links/go). */
        fun isShortlink(url: String?): Boolean {
            val u = url?.trim()?.toHttpUrlOrNull() ?: return false
            if (u.host.lowercase() !in SHORT_HOSTS) return false
            val segs = u.pathSegments.filter { it.isNotEmpty() }
            return segs.size == 1 && segs[0].matches(Regex("[A-Za-z0-9_-]{3,32}"))
        }

        private val hopHosts = mutableSetOf("droplink.co", "www.droplink.co")

        /** Hop domains rotate, so a `Linkpage` script path marks a new one. */
        @Synchronized
        internal fun isHop(url: String): Boolean {
            val u = url.toHttpUrlOrNull() ?: return false
            val host = u.host.lowercase()
            if (u.encodedPath.contains("Linkpage")) {
                hopHosts += host
                return true
            }
            return hopHosts.any { host == it || host.endsWith(".$it") }
        }

        private val STATE_RE = Regex("""var state=(\{.*?\});""")
        private val DEST_RE = Regex("""var destination="(.*?)";""")
        private val GO_D_RE = Regex("""<a\s[^>]*?href="([^"]+)"[^>]*?id="go_d"|id="go_d"[^>]*?href="([^"]+)"""")
        private val COUNTER_RE = Regex("""counter_value\W{1,4}(\d{1,3})""")
        private val JSON_URL_RE = Regex(""""url"\s*:\s*"((?:\\.|[^"\\])*)"""")
        private val CAPTCHA_RE = Regex("""class="g-recaptcha|data-sitekey=|hcaptcha\.com/1/api|challenges\.cloudflare\.com/turnstile""")
        private val FORM_RE = Regex("""<form\b([^>]*)>(.*?)</form>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        private val INPUT_RE = Regex("""<input\b[^>]*>""", RegexOption.IGNORE_CASE)
        private val NAME_RE = Regex("""name="([^"]*)"""")
        private val VALUE_RE = Regex("""value="([^"]*)"""")
        private val ACTION_RE = Regex("""action="([^"]*)"""")

        internal fun parseForm(page: String, marker: String): Map<String, String>? =
            parseFormWithAction(page, marker)?.second

        internal fun parseFormWithAction(page: String, marker: String): Pair<String?, Map<String, String>>? {
            for (m in FORM_RE.findAll(page)) {
                val (attrs, body) = m.destructured
                if (marker !in body) continue
                val fields = LinkedHashMap<String, String>()
                INPUT_RE.findAll(body).forEach { tag ->
                    val name = NAME_RE.find(tag.value)?.groupValues?.get(1) ?: return@forEach
                    fields[name] = VALUE_RE.find(tag.value)?.groupValues?.get(1)?.let(::htmlUnescape).orEmpty()
                }
                return ACTION_RE.find(attrs)?.groupValues?.get(1)?.let(::htmlUnescape) to fields
            }
            return null
        }

        /** The state object is a flat `{"k":"v",...}` of strings. */
        internal fun parseFlatJson(obj: String): Map<String, String> =
            Regex(""""((?:\\.|[^"\\])*)"\s*:\s*"((?:\\.|[^"\\])*)"""").findAll(obj)
                .associate { jsUnescape(it.groupValues[1]) to jsUnescape(it.groupValues[2]) }

        /** Strips google.com/url and bing.com/ck/a referrer wrappers. */
        internal fun unwrapReferrer(url: String): String {
            val u = url.toHttpUrlOrNull() ?: return url
            val host = u.host.lowercase()
            if (host.endsWith("google.com") && u.encodedPath == "/url") u.queryParameter("url")?.let { return it }
            if (host.endsWith("bing.com") && u.encodedPath.startsWith("/ck/")) {
                val p = u.queryParameter("u")
                if (p != null && p.startsWith("a1")) {
                    runCatching { return String(Base64.getUrlDecoder().decode(p.substring(2).trimEnd('='))) }
                }
            }
            return url
        }

        internal fun jsUnescape(s: String): String {
            val out = StringBuilder(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '\\' && i + 1 < s.length) {
                    val n = s[i + 1]
                    val code = if (n == 'u' && i + 5 < s.length) s.substring(i + 2, i + 6).toIntOrNull(16) else null
                    if (code != null) {
                        out.append(code.toChar())
                        i += 6
                        continue
                    }
                    out.append(when (n) { 'n' -> '\n'; 't' -> '\t'; 'r' -> '\r'; else -> n })
                    i += 2
                } else {
                    out.append(c)
                    i++
                }
            }
            return out.toString()
        }

        internal fun htmlUnescape(s: String): String = s.replace("&amp;", "&").replace("&quot;", "\"")
            .replace("&#039;", "'").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")

        private fun resolveRef(base: String, ref: String): String =
            runCatching { URI(base).resolve(ref.replace(" ", "%20")).toString() }.getOrDefault(ref)
    }
}
