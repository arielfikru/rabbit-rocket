package io.github.arielfikru.rabbitrocket

import android.content.Context
import io.github.arielfikru.rabbitrocket.hls.HlsClient
import io.github.arielfikru.rabbitrocket.hls.Playlist
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class DownloadEngine(private val ctx: Context) {

    suspend fun run(id: Long) {
        val job = DownloadStore.get(id)?.takeIf { it.status == Status.Queued } ?: return
        val work = workDir(ctx, id).apply { mkdirs() }
        try {
            DownloadStore.update(id) { it.copy(status = Status.Downloading, error = null, speed = 0) }
            val client = HlsClient(job.headers())
            val media = client.fetchMedia(job.url)
            if (media.segments.isEmpty()) throw IOException("Playlist kosong")
            media.segments.firstNotNullOfOrNull { s -> s.key?.method?.takeIf { it != "AES-128" } }?.let {
                throw IOException("Enkripsi $it (DRM) tidak didukung")
            }
            DownloadStore.update(id) { it.copy(total = media.segments.size) }

            downloadAll(id, client, media, work)

            val fmp4 = media.init != null
            DownloadStore.update(id) { it.copy(status = Status.Merging, speed = 0) }
            val merged = File(work, if (fmp4) "merged.mp4" else "merged.ts")
            withContext(Dispatchers.IO) {
                merged.outputStream().buffered(1 shl 16).use { out ->
                    if (fmp4) File(work, INIT).inputStream().use { it.copyTo(out) }
                    media.segments.indices.forEach { i -> segFile(work, i).inputStream().use { it.copyTo(out) } }
                }
            }

            var final = merged
            var mime = if (fmp4) "video/mp4" else "video/mp2t"
            if (!fmp4 && job.toMp4) {
                DownloadStore.update(id) { it.copy(status = Status.Converting, convertProgress = 0) }
                val mp4 = File(work, "out.mp4")
                val ok = Remux.toMp4(merged, mp4, media.duration) { p -> DownloadStore.update(id) { it.copy(convertProgress = p) } }
                if (ok) {
                    final = mp4
                    mime = "video/mp4"
                }
            }

            DownloadStore.update(id) { it.copy(status = Status.Saving) }
            val fileName = "${job.name}.${final.extension}"
            val size = final.length()
            val uri = withContext(Dispatchers.IO) { Storage.saveToDownloads(ctx, final, fileName, mime) }
            work.deleteRecursively()
            DownloadStore.update(id) {
                it.copy(
                    status = Status.Done, outputUri = uri.toString(), outputMime = mime,
                    fileName = fileName, size = size, speed = 0,
                )
            }
        } catch (e: CancellationException) {
            DownloadStore.update(id) { it.copy(status = Status.Canceled, speed = 0) }
            throw e
        } catch (e: Throwable) {
            DownloadStore.update(id) {
                it.copy(status = Status.Failed, speed = 0, error = e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private suspend fun downloadAll(id: Long, client: HlsClient, media: Playlist.Media, work: File) =
        coroutineScope {
            val isTs = media.init == null
            media.init?.let { init ->
                val f = File(work, INIT)
                if (!f.exists()) f.writeBytes(client.init(init, media.segments.first().sequence))
            }

            val done = AtomicInteger()
            val bytes = AtomicLong()
            val fresh = AtomicLong() // bytes fetched this run, for speed
            val ticker = launch {
                var lastBytes = 0L
                var lastTime = System.nanoTime()
                var speed = 0.0
                while (isActive) {
                    delay(500)
                    val now = System.nanoTime()
                    val b = fresh.get()
                    val instant = (b - lastBytes) / ((now - lastTime) / 1e9)
                    speed = if (speed == 0.0) instant else speed * 0.7 + instant * 0.3
                    lastBytes = b
                    lastTime = now
                    DownloadStore.update(id) { it.copy(done = done.get(), bytes = bytes.get(), speed = speed.toLong()) }
                }
            }

            val gate = Semaphore(PARALLEL)
            media.segments.mapIndexed { i, seg ->
                launch(Dispatchers.IO) {
                    gate.withPermit {
                        val f = segFile(work, i)
                        if (f.exists()) {
                            bytes.addAndGet(f.length())
                        } else {
                            val data = client.segment(seg, isTs)
                            val part = File(work, "$i.part")
                            part.writeBytes(data)
                            if (!part.renameTo(f)) throw IOException("Gagal menulis segmen $i")
                            bytes.addAndGet(data.size.toLong())
                            fresh.addAndGet(data.size.toLong())
                        }
                        done.incrementAndGet()
                    }
                }
            }.joinAll()

            ticker.cancel()
            DownloadStore.update(id) { it.copy(done = done.get(), bytes = bytes.get()) }
        }

    companion object {
        private const val PARALLEL = 6
        private const val INIT = "init.bin"

        fun workDir(ctx: Context, id: Long) = File(ctx.externalCacheDir ?: ctx.cacheDir, "jobs/$id")
        private fun segFile(work: File, i: Int) = File(work, "%06d.seg".format(i))
    }
}
