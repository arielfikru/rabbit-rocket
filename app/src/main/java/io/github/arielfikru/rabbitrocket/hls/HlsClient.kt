package io.github.arielfikru.rabbitrocket.hls

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

const val DEFAULT_UA =
    "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0 Mobile Safari/537.36"

object Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .connectionPool(ConnectionPool(16, 5, TimeUnit.MINUTES))
        .build()
        .also { it.dispatcher.maxRequestsPerHost = 16 }
}

class HttpException(val code: Int) : IOException("Server membalas HTTP $code")

class HlsClient(private val headers: Map<String, String>) {
    private val keys = ConcurrentHashMap<String, ByteArray>()

    suspend fun fetchPlaylist(url: String): Playlist {
        val (body, finalUrl) = retrying {
            Http.client.newCall(request(url)).await().use { r ->
                r.ensureOk()
                withContext(Dispatchers.IO) { r.body!!.string() } to r.request.url.toString()
            }
        }
        if (body.contains("<html", ignoreCase = true)) {
            throw IOException("Link ini halaman web, bukan playlist .m3u8")
        }
        return PlaylistParser.parse(body, finalUrl)
    }

    /** Resolves a master playlist down to the media playlist of its best variant. */
    suspend fun fetchMedia(url: String): Playlist.Media =
        when (val p = fetchPlaylist(url)) {
            is Playlist.Media -> p
            is Playlist.Master -> {
                val best = p.variants.firstOrNull() ?: throw IOException("Playlist tidak punya stream")
                fetchPlaylist(best.url) as? Playlist.Media
                    ?: throw IOException("Playlist bertingkat terlalu dalam")
            }
        }

    suspend fun bytes(url: String, range: ByteRange? = null): ByteArray = retrying {
        Http.client.newCall(request(url, range)).await().use { r ->
            r.ensureOk()
            withContext(Dispatchers.IO) { r.body!!.bytes() }
        }
    }

    suspend fun segment(seg: Segment, isTs: Boolean): ByteArray {
        var data = bytes(seg.url, seg.range)
        seg.key?.let { data = decrypt(data, key(it), it.iv ?: ivFor(seg.sequence)) }
        return if (isTs) stripFakeHeader(data) else data
    }

    suspend fun init(init: InitSegment, firstSequence: Long): ByteArray {
        val data = bytes(init.url, init.range)
        return init.key?.let { decrypt(data, key(it), it.iv ?: ivFor(firstSequence)) } ?: data
    }

    private suspend fun key(k: KeyInfo): ByteArray {
        val url = k.url ?: throw IOException("Kunci enkripsi tanpa URI")
        return keys[url] ?: bytes(url).also { keys[url] = it }
    }

    private fun request(url: String, range: ByteRange? = null) = Request.Builder().url(url).apply {
        headers.forEach { (k, v) -> header(k, v) }
        range?.let { header("Range", "bytes=${it.offset}-${it.offset + it.length - 1}") }
    }.build()

    private fun Response.ensureOk() {
        if (!isSuccessful) throw HttpException(code)
    }

    private suspend fun <T> retrying(times: Int = 4, block: suspend () -> T): T {
        var last: IOException? = null
        repeat(times) { attempt ->
            try {
                return block()
            } catch (e: IOException) {
                if (e is HttpException && e.code in 400..499 && e.code != 408 && e.code != 429) throw e
                last = e
                delay(800L * (attempt + 1))
            }
        }
        throw last!!
    }

    companion object {
        fun ivFor(sequence: Long): ByteArray = ByteBuffer.allocate(16).putLong(8, sequence).array()

        fun decrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
            fun cipher(padding: String) = Cipher.getInstance("AES/CBC/$padding").apply {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            }
            return try {
                cipher("PKCS5Padding").doFinal(data)
            } catch (e: BadPaddingException) {
                // Some encoders skip PKCS7 padding; decrypt whole blocks only.
                cipher("NoPadding").doFinal(data, 0, data.size - data.size % 16)
            }
        }

        /** Some hosts disguise TS chunks behind a fake PNG/JPEG header; skip to the first TS sync byte. */
        fun stripFakeHeader(d: ByteArray): ByteArray {
            val sync = 0x47.toByte()
            if (d.isEmpty() || d[0] == sync) return d
            val limit = minOf(d.size - 376, 8192)
            for (i in 0 until limit) {
                if (d[i] == sync && d[i + 188] == sync && d[i + 376] == sync) return d.copyOfRange(i, d.size)
            }
            return d
        }
    }
}

suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            cont.resume(response) { _, value, _ -> value.close() }
        }

        override fun onFailure(call: Call, e: IOException) {
            cont.resumeWithException(e)
        }
    })
}
