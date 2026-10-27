package io.github.arielfikru.rabbitrocket.net

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import kotlin.concurrent.thread

/** Browser privacy settings, persisted and applied app-wide. */
object NetSettings {
    var dns by mutableStateOf(DnsChoice.Cloudflare)
        private set
    var adBlock by mutableStateOf(true)
        private set

    private var loaded = false
    private lateinit var appCtx: Context

    val proxySupported: Boolean get() = WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)

    /** Safe to call from every entry point (activity, download service). */
    @Synchronized
    fun init(ctx: Context) {
        if (loaded) return
        loaded = true
        appCtx = ctx.applicationContext
        val p = prefs()
        dns = runCatching { DnsChoice.valueOf(p.getString("dns", null) ?: "") }.getOrDefault(DnsChoice.Cloudflare)
        adBlock = p.getBoolean("adblock", true)
        AppDns.choice = dns
        AdBlock.enabled = adBlock
        thread(name = "rr-adblock", isDaemon = true) { AdBlock.refresh(appCtx.filesDir) }
    }

    fun chooseDns(choice: DnsChoice) {
        dns = choice
        AppDns.choice = choice
        prefs().edit().putString("dns", choice.name).apply()
        applyToWebView()
    }

    fun enableAdBlock(on: Boolean) {
        adBlock = on
        AdBlock.enabled = on
        prefs().edit().putBoolean("adblock", on).apply()
    }

    /** Routes every WebView through [LocalProxy] while a custom DNS is chosen. Call on the main thread. */
    fun applyToWebView() {
        if (!proxySupported) return
        val controller = ProxyController.getInstance()
        if (dns.url == null) {
            controller.clearProxyOverride({ it.run() }) {}
        } else {
            val port = LocalProxy.start()
            controller.setProxyOverride(
                ProxyConfig.Builder().addProxyRule("127.0.0.1:$port").build(),
                { it.run() },
            ) {}
        }
    }

    private fun prefs() = appCtx.getSharedPreferences("browser", Context.MODE_PRIVATE)
}
