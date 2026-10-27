package io.github.arielfikru.rabbitrocket.net

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

enum class DnsChoice(val label: String, val detail: String, val url: String?, val bootstrap: List<String>) {
    System("Bawaan jaringan", "DNS dari operator atau Wi-Fi", null, emptyList()),
    Cloudflare("Cloudflare", "1.1.1.1 · cepat, tanpa filter", "https://cloudflare-dns.com/dns-query", listOf("1.1.1.1", "1.0.0.1")),
    AdGuard("AdGuard DNS", "Ikut memblokir iklan di level DNS", "https://dns.adguard-dns.com/dns-query", listOf("94.140.14.14", "94.140.15.15")),
    Google("Google", "8.8.8.8", "https://dns.google/dns-query", listOf("8.8.8.8", "8.8.4.4")),
    Quad9("Quad9", "9.9.9.9 · blokir situs berbahaya", "https://dns.quad9.net/dns-query", listOf("9.9.9.9", "149.112.112.112")),
}

/**
 * Resolver shared by downloads (OkHttp) and the browser (through [LocalProxy]).
 * DNS-over-HTTPS gets past ISP DNS hijacking; when the DoH server itself is unreachable we fall
 * back to the system resolver rather than failing every request.
 */
object AppDns : Dns {
    @Volatile var choice: DnsChoice = DnsChoice.Cloudflare
        set(value) {
            if (field != value) cache.clear()
            field = value
        }

    private val bootstrapClient by lazy {
        OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build()
    }
    private val resolvers = ConcurrentHashMap<DnsChoice, DnsOverHttps>()
    private val cache = ConcurrentHashMap<String, Pair<List<InetAddress>, Long>>()
    private val IP_LITERAL = Regex("""^[\d.]+$|:""")

    override fun lookup(hostname: String): List<InetAddress> {
        val c = choice
        if (c.url == null || hostname == "localhost" || IP_LITERAL.containsMatchIn(hostname)) {
            return Dns.SYSTEM.lookup(hostname)
        }
        cache[hostname]?.let { (addrs, until) -> if (System.currentTimeMillis() < until) return addrs }
        val addrs = try {
            resolver(c).lookup(hostname)
        } catch (e: Exception) {
            return Dns.SYSTEM.lookup(hostname)
        }
        cache[hostname] = addrs to System.currentTimeMillis() + TTL
        return addrs
    }

    private fun resolver(c: DnsChoice) = resolvers.getOrPut(c) {
        DnsOverHttps.Builder()
            .client(bootstrapClient)
            .url(c.url!!.toHttpUrl())
            .bootstrapDnsHosts(c.bootstrap.map { InetAddress.getByName(it) })
            // Many mobile networks still have flaky IPv6; v4 answers connect first and reliably.
            .includeIPv6(false)
            .build()
    }

    private const val TTL = 5 * 60_000L
}
