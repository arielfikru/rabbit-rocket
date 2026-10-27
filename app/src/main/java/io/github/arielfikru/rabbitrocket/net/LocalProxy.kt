package io.github.arielfikru.rabbitrocket.net

import android.util.Log
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * A loopback HTTP proxy for the WebView, whose only job is to resolve hosts through [AppDns].
 * HTTPS goes through untouched as a CONNECT tunnel; plain HTTP is forwarded one request per
 * connection.
 */
object LocalProxy {
    private const val TAG = "RRProxy"
    @Volatile private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "rr-proxy").apply { isDaemon = true } }

    /** Starts once; returns the port. */
    @Synchronized
    fun start(): Int {
        server?.let { return it.localPort }
        val s = ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
        server = s
        thread(name = "rr-proxy-accept", isDaemon = true) {
            while (true) {
                val client = try { s.accept() } catch (e: IOException) { break }
                pool.execute { handle(client) }
            }
        }
        return s.localPort
    }

    private fun handle(client: Socket) {
        var upstream: Socket? = null
        try {
            client.soTimeout = 30_000
            val input = client.getInputStream().buffered()
            val head = readHead(input) ?: return
            val lines = head.split("\r\n")
            val parts = lines[0].split(' ')
            if (parts.size < 3) return reply(client, "400 Bad Request")
            val (method, target) = parts

            if (method.equals("CONNECT", true)) {
                val host = target.substringBeforeLast(':').removePrefix("[").removeSuffix("]")
                val port = target.substringAfterLast(':').toIntOrNull() ?: 443
                upstream = open(host, port) ?: return reply(client, "502 Bad Gateway")
                client.getOutputStream().apply { write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray()); flush() }
            } else {
                val url = target.toHttpUrlOrNull() ?: return reply(client, "400 Bad Request")
                upstream = open(url.host, url.port) ?: return reply(client, "502 Bad Gateway")
                val path = url.encodedPath + (url.encodedQuery?.let { "?$it" } ?: "")
                val headers = lines.drop(1).filter { line ->
                    val name = line.substringBefore(':').trim().lowercase()
                    line.isNotEmpty() && name != "proxy-connection" && name != "connection" && name != "keep-alive"
                }
                // One request per connection keeps the forwarding dumb: the server closes, we close.
                val out = buildString {
                    append("$method $path ${parts[2]}\r\n")
                    headers.forEach { append(it).append("\r\n") }
                    append("Connection: close\r\n\r\n")
                }
                upstream.getOutputStream().apply { write(out.toByteArray()); flush() }
            }
            // Tunnels idle for long stretches (HTTP/2, websockets); the peer decides when they end.
            client.soTimeout = 0
            upstream.soTimeout = 0
            val up = upstream
            pool.execute { pipe(input, up.getOutputStream(), client, up) }
            pipe(up.getInputStream(), client.getOutputStream(), client, up)
        } catch (e: IOException) {
            // Peer went away; nothing to report.
        } finally {
            if (upstream == null) runCatching { client.close() }
        }
    }

    private fun open(host: String, port: Int): Socket? {
        val addrs = try {
            AppDns.lookup(host)
        } catch (e: Exception) {
            Log.w(TAG, "resolve $host failed: ${e.message}")
            return null
        }
        Log.d(TAG, "${AppDns.choice.name} $host -> ${addrs.firstOrNull()?.hostAddress}")
        for (a in addrs) {
            val s = Socket()
            try {
                s.connect(InetSocketAddress(a, port), 15_000)
                s.tcpNoDelay = true
                return s
            } catch (e: IOException) {
                runCatching { s.close() }
            }
        }
        return null
    }

    /** Whichever side finishes first closes both, which also ends the other pipe. */
    private fun pipe(from: InputStream, to: OutputStream, a: Socket, b: Socket) {
        try {
            val buf = ByteArray(32 * 1024)
            while (true) {
                val n = from.read(buf)
                if (n < 0) break
                to.write(buf, 0, n)
                to.flush()
            }
        } catch (e: IOException) {
            // closed
        } finally {
            runCatching { a.close() }
            runCatching { b.close() }
        }
    }

    private fun readHead(input: InputStream): String? {
        val sb = StringBuilder()
        while (sb.length < 64 * 1024) {
            val c = input.read()
            if (c < 0) return null
            sb.append(c.toChar())
            if (sb.endsWith("\r\n\r\n")) return sb.substring(0, sb.length - 4)
        }
        return null
    }

    private fun reply(client: Socket, status: String) {
        runCatching {
            client.getOutputStream().write("HTTP/1.1 $status\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
        }
        runCatching { client.close() }
    }
}
