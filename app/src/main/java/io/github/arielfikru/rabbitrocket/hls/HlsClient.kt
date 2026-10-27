package io.github.arielfikru.rabbitrocket.hls

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URLDecoder
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

sealed interface Probe {
    data class Hls(val url: String) : Probe

    data class Direct(
        val url: String,
        val size: Long,
        val mime: String?,
        val ranges: Boolean,
        val fileName: String?,
    ) : Probe {
        val ext: String get() = MediaTypes.extFor(url, mime, fileName)
        val mimeType: String get() = MediaTypes.mimeFor(ext)
    }
}

private val CD_NAME = Regex("""filename\*?=(?:UTF-8'')?"?([^";]+)"?""", RegexOption.IGNORE_CASE)

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

    /** Fetches only the first bytes to tell a playlist from a direct media file. */
    suspend fun probe(url: String): Probe = retrying {
        Http.client.newCall(request(url, ByteRange(0, 4096))).await().use { r ->
            r.ensureOk()
            val type = r.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase()
            val head = withContext(Dispatchers.IO) {
                val src = r.body!!.source()
                src.request(64)
                src.buffer.snapshot().utf8().take(64)
            }
            val finalUrl = r.request.url.toString()
            val ext = MediaTypes.pathExt(finalUrl) ?: MediaTypes.pathExt(url)
            val textual = type == null || type.startsWith("text/") || type == "application/octet-stream"
            when {
                head.trimStart('\uFEFF', ' ', '\r', '\n', '\t').startsWith("#EXTM3U") ||
                    type?.contains("mpegurl") == true ||
                    ((ext == "m3u8" || ext == "m3u") && textual) -> Probe.Hls(finalUrl)

                type == "text/html" || head.trimStart().startsWith("<") ->
                    throw IllegalArgumentException(
                        "Link ini halaman web, bukan video. Buka lewat tab Browser supaya link videonya ketemu.",
                    )

                else -> {
                    val partial = r.code == 206
                    val size = if (partial) r.header("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: -1L
                    else r.body!!.contentLength()
                    val name = r.header("Content-Disposition")?.let { CD_NAME.find(it)?.groupValues?.get(1) }
                        ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
                    Probe.Direct(finalUrl, size, type, partial && size > 0, name)
                }
            }
        }
    }

    /**
     * Appends bytes [from]..[to] (inclusive; null = until EOF) of [url] to [file].
     * Network drops resume from the file's current length.
     */
    suspend fun downloadRange(url: String, file: File, from: Long, to: Long?) {
        var failures = 0
        while (true) {
            val start = from + file.length()
            if (to != null && start > to) return
            val ranged = start > 0 || to != null
            val req = Request.Builder().url(url).apply {
                headers.forEach { (k, v) -> header(k, v) }
                if (ranged) header("Range", "bytes=$start-${to ?: ""}")
            }.build()
            val before = file.length()
            try {
                Http.client.newCall(req).await().use { r ->
                    r.ensureOk()
                    if (ranged && r.code != 206) {
                        // Server ignored the range: only a whole-file download can start over.
                        if (from == 0L && to == null) file.delete()
                        else throw IllegalStateException("Server tidak mendukung download bertahap")
                    }
                    withContext(Dispatchers.IO) {
                        r.body!!.byteStream().use { input ->
                            FileOutputStream(file, true).use { out ->
                                val buf = ByteArray(1 shl 16)
                                while (true) {
                                    ensureActive()
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                }
                            }
                        }
                    }
                }
                if (to == null || from + file.length() > to) return
                if (file.length() == before && ++failures > 5) throw IOException("Koneksi terus terputus")
            } catch (e: IOException) {
                if (e is HttpException && e.code in 400..499 && e.code != 408 && e.code != 429) throw e
                if (++failures > 5) throw e
                delay(1000L * failures)
            }
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
