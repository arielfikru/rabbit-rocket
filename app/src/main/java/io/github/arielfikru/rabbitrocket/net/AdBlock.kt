package io.github.arielfikru.rabbitrocket.net

import io.github.arielfikru.rabbitrocket.hls.Http
import okhttp3.Request
import java.io.File

/** Host rules from an AdGuard/ABP-style list: `||host^` blocks, `@@||host^` allows. */
class HostRules(val blocked: Set<String>, val allowed: Set<String>) {
    val size get() = blocked.size

    fun isBlocked(host: String): Boolean {
        val h = host.lowercase().trimEnd('.')
        return !matches(allowed, h) && matches(blocked, h)
    }

    /** The host itself or any parent domain is listed. */
    private fun matches(set: Set<String>, host: String): Boolean {
        var h = host
        while (true) {
            if (h in set) return true
            val dot = h.indexOf('.')
            if (dot < 0) return false
            h = h.substring(dot + 1)
        }
    }

    companion object {
        private val RULE = Regex("""^(@@)?\|\|([a-z0-9.-]+)\^(\${'$'}important)?$""")

        fun parse(text: String): HostRules {
            val blocked = HashSet<String>()
            val allowed = HashSet<String>()
            text.lineSequence().forEach { raw ->
                val m = RULE.matchEntire(raw.trim().lowercase()) ?: return@forEach
                val host = m.groupValues[2].trim('.')
                // A bare TLD rule would take out half the web.
                if ('.' !in host) return@forEach
                if (m.groupValues[1].isEmpty()) blocked += host else allowed += host
            }
            return HostRules(blocked, allowed)
        }

        /** Rough "same site": last two labels, three for ccSLDs like co.id or ac.uk. */
        fun site(host: String): String {
            val labels = host.lowercase().trimEnd('.').split('.')
            if (labels.size <= 2) return labels.joinToString(".")
            val n = if (labels.last().length == 2 && labels[labels.size - 2].length <= 3) 3 else 2
            return labels.takeLast(n).joinToString(".")
        }
    }
}

/**
 * Blocks ad and tracker hosts in the browser. Starts with a small built-in list, then swaps in the
 * AdGuard DNS filter, downloaded at runtime and refreshed weekly.
 */
object AdBlock {
    private val SEED = listOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com",
        "google-analytics.com", "googletagmanager.com", "adnxs.com", "adsrvr.org", "criteo.com",
        "taboola.com", "outbrain.com", "popads.net", "popcash.net", "propellerads.com", "exoclick.com",
        "exosrv.com", "juicyads.com", "trafficjunky.net", "trafficstars.com", "tsyndicate.com",
        "adsterra.com", "hilltopads.net", "clickadu.com", "adcash.com", "a-ads.com", "realsrv.com",
        "magsrv.com", "onclickads.net", "onclickalgo.com", "mgid.com", "revcontent.com",
        "scorecardresearch.com", "amazon-adsystem.com", "moatads.com", "pubmatic.com", "rubiconproject.com",
        "openx.net", "smartadserver.com", "yandexadexchange.net", "adskeeper.co.uk", "hotjar.com",
    )

    /** Mirrors the setting for the WebView's network threads. */
    @Volatile var enabled = true
    @Volatile var rules: HostRules = HostRules.parse(SEED.joinToString("\n") { "||$it^" })
        private set
    @Volatile var updatedAt: Long = 0
        private set

    /** First-party requests are never blocked, so a site's own CDN keeps working. */
    fun shouldBlock(host: String?, pageHost: String?): Boolean {
        if (!enabled || host.isNullOrEmpty()) return false
        if (pageHost != null && HostRules.site(host) == HostRules.site(pageHost)) return false
        return rules.isBlocked(host)
    }

    /** Loads the cached list, and downloads a fresh one when it's missing or a week old. Blocking; call off the main thread. */
    fun refresh(dir: File) {
        val file = File(dir, "adguard-dns.txt")
        if (file.exists() && rules.size < 1000) {
            runCatching { rules = HostRules.parse(file.readText()); updatedAt = file.lastModified() }
        }
        if (file.exists() && System.currentTimeMillis() - file.lastModified() < WEEK) return
        runCatching {
            Http.client.newCall(Request.Builder().url(LIST_URL).build()).execute().use { r ->
                if (!r.isSuccessful) return
                val text = r.body!!.string()
                val parsed = HostRules.parse(text)
                if (parsed.size < 1000) return // a captive portal page, not the list
                File(dir, "adguard-dns.tmp").apply { writeText(text); renameTo(file) }
                rules = parsed
                updatedAt = file.lastModified()
            }
        }
    }

    private const val LIST_URL = "https://adguardteam.github.io/HostlistsRegistry/assets/filter_1.txt"
    private const val WEEK = 7 * 24 * 3600_000L
}
