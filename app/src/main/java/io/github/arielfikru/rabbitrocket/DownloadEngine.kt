package io.github.arielfikru.rabbitrocket

import android.content.Context
import io.github.arielfikru.rabbitrocket.hls.HlsClient
import io.github.arielfikru.rabbitrocket.hls.Playlist
import io.github.arielfikru.rabbitrocket.hls.Probe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
            val (final, mime) = if (job.direct) runDirect(id, client, job, work) else runHls(id, client, job, work)

            DownloadStore.update(id) { it.copy(status = Status.Saving, speed = 0) }
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

    private suspend fun runHls(id: Long, client: HlsClient, job: DlJob, work: File): Pair<File, String> {
        val media = client.fetchMedia(job.url)
        if (media.segments.isEmpty()) throw IOException("Playlist kosong")
        media.segments.firstNotNullOfOrNull { s -> s.key?.method?.takeIf { it != "AES-128" } }?.let {
            throw IOException("Enkripsi $it (DRM) tidak didukung")
        }
        DownloadStore.update(id) { it.copy(total = media.segments.size) }

        downloadSegments(id, client, media, work)

        val fmp4 = media.init != null
        DownloadStore.update(id) { it.copy(status = Status.Merging, speed = 0) }
        val merged = File(work, if (fmp4) "merged.mp4" else "merged.ts")
        withContext(Dispatchers.IO) {
            merged.outputStream().buffered(1 shl 16).use { out ->
                if (fmp4) File(work, INIT).inputStream().use { it.copyTo(out) }
                media.segments.indices.forEach { i -> segFile(work, i).inputStream().use { it.copyTo(out) } }
            }
        }

        if (!fmp4 && job.toMp4) {
            DownloadStore.update(id) { it.copy(status = Status.Converting, convertProgress = 0) }
            val mp4 = File(work, "out.mp4")
            val ok = Remux.toMp4(merged, mp4, media.duration) { p -> DownloadStore.update(id) { it.copy(convertProgress = p) } }
            if (ok) return mp4 to "video/mp4"
        }
        return merged to (if (fmp4) "video/mp4" else "video/mp2t")
    }

    /** Plain media file: split into ranged parts when the server allows it; parts survive for resume. */
    private suspend fun runDirect(id: Long, client: HlsClient, job: DlJob, work: File): Pair<File, String> =
        coroutineScope {
            val probe = client.probe(job.url) as? Probe.Direct
                ?: throw IOException("Link ini ternyata playlist; cek ulang dari awal")
            val size = probe.size
            DownloadStore.update(id) { it.copy(totalBytes = maxOf(size, 0)) }

            val partCount = if (probe.ranges && size >= MIN_SPLIT) PARTS else 1
            val parts = (0 until partCount).map { File(work, "part$it") }
            // Without range support a half-written file can't be continued.
            if (!probe.ranges) parts.forEach { it.delete() }

            val ticker = speedTicker(id) { parts.sumOf { it.length() } }
            parts.mapIndexed { i, f ->
                launch(Dispatchers.IO) {
                    when {
                        partCount > 1 -> {
                            val chunk = size / partCount
                            val start = i * chunk
                            val end = if (i == partCount - 1) size - 1 else start + chunk - 1
                            client.downloadRange(probe.url, f, start, end)
                        }
                        probe.ranges -> client.downloadRange(probe.url, f, 0, size - 1)
                        else -> client.downloadRange(probe.url, f, 0, null)
                    }
                }
            }.joinAll()
            ticker.cancel()

            val out = File(work, "out.${probe.ext}")
            withContext(Dispatchers.IO) {
                if (partCount == 1) {
                    if (!parts[0].renameTo(out)) throw IOException("Gagal menyiapkan file")
                } else {
                    out.outputStream().buffered(1 shl 16).use { o -> parts.forEach { p -> p.inputStream().use { it.copyTo(o) } } }
                }
            }
            if (size > 0 && out.length() != size) {
                throw IOException("Ukuran file tidak cocok (${fmtBytes(out.length())} dari ${fmtBytes(size)}); coba Lanjutkan")
            }
            DownloadStore.update(id) { it.copy(bytes = out.length()) }
            out to probe.mimeType
        }

    private suspend fun downloadSegments(id: Long, client: HlsClient, media: Playlist.Media, work: File) =
        coroutineScope {
            val isTs = media.init == null
            media.init?.let { init ->
                val f = File(work, INIT)
                if (!f.exists()) f.writeBytes(client.init(init, media.segments.first().sequence))
            }

            val done = AtomicInteger()
            val bytes = AtomicLong()
            val ticker = speedTicker(id, segments = done) { bytes.get() }

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
                        }
                        done.incrementAndGet()
                    }
                }
            }.joinAll()

            ticker.cancel()
            DownloadStore.update(id) { it.copy(done = done.get(), bytes = bytes.get()) }
        }

    /**
     * Publishes progress every 500 ms with a smoothed speed. Bytes already on disk when a job
     * resumes count towards progress but not towards speed.
     */
    private fun CoroutineScope.speedTicker(id: Long, segments: AtomicInteger? = null, bytes: () -> Long): Job =
        launch {
            var lastBytes = -1L
            var lastTime = System.nanoTime()
            var speed = 0.0
            while (isActive) {
                delay(500)
                val now = System.nanoTime()
                val b = bytes()
                if (lastBytes >= 0) {
                    val instant = (b - lastBytes).coerceAtLeast(0) / ((now - lastTime) / 1e9)
                    speed = if (speed == 0.0) instant else speed * 0.7 + instant * 0.3
                }
                lastBytes = b
                lastTime = now
                DownloadStore.update(id) {
                    it.copy(done = segments?.get() ?: it.done, bytes = b, speed = speed.toLong())
                }
            }
        }

    companion object {
        private const val PARALLEL = 6
        private const val PARTS = 4
        private const val MIN_SPLIT = 8L shl 20
        private const val INIT = "init.bin"

        fun workDir(ctx: Context, id: Long) = File(ctx.externalCacheDir ?: ctx.cacheDir, "jobs/$id")
        private fun segFile(work: File, i: Int) = File(work, "%06d.seg".format(i))
    }
}
