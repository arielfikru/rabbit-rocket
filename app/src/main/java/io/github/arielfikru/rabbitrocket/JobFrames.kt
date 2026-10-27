package io.github.arielfikru.rabbitrocket

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import io.github.arielfikru.rabbitrocket.hls.Thumbnails
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Pictures of finished downloads, read back from the saved file: a small poster for the card
 * (kept in filesDir, listed in history.json) and a 9-frame sheet made on demand (cacheDir).
 */
object JobFrames {
    private const val POSTER_MAX = 480
    private val busy = ConcurrentHashMap<Long, Mutex>()

    private fun posterFile(ctx: Context, id: Long) = File(File(ctx.filesDir, "job_thumbs").apply { mkdirs() }, "$id.jpg")
    private fun sheetDir(ctx: Context, id: Long) = File(ctx.cacheDir, "job_frames/$id")

    /** Grabs the poster (at 10% of the video) plus duration and size, and stores them on the job. */
    suspend fun ensurePoster(ctx: Context, id: Long) = lock(id) {
        val job = DownloadStore.get(id)?.takeIf { it.status == Status.Done && it.thumb == null } ?: return@lock
        val uri = job.outputUri?.let(Uri::parse) ?: return@lock
        withContext(Dispatchers.IO) {
            retriever(ctx, uri) { r ->
                val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                val (w, h) = Thumbnails.size(r) ?: (0 to 0)
                val bmp = Thumbnails.grab(r, ms * 100, POSTER_MAX) // 10% of ms, in µs
                val path = bmp?.let { b -> writeJpeg(b, posterFile(ctx, id), 82)?.absolutePath }
                DownloadStore.update(id) {
                    it.copy(thumb = path ?: "", durationSec = ms / 1000.0, width = w, height = h)
                }
            } ?: DownloadStore.update(id) { it.copy(thumb = "") } // unreadable; don't try on every scroll
        }
    }

    /** The 9 frames of a finished file as (time, jpeg); made once and cached. */
    suspend fun sheet(ctx: Context, job: DlJob): List<Pair<Double, File>> = lock(job.id) {
        val uri = job.outputUri?.let(Uri::parse) ?: return@lock emptyList()
        withContext(Dispatchers.IO) {
            val dir = sheetDir(ctx, job.id).apply { mkdirs() }
            var durMs = (job.durationSec * 1000).toLong()
            val files = (0 until Thumbnails.COUNT).map { File(dir, "$it.jpg") }
            if (durMs > 0 && files.all { it.exists() }) return@withContext times(durMs).zip(files)
            retriever(ctx, uri) { r ->
                if (durMs <= 0) durMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                times(durMs).zip(files).mapNotNull { (t, f) ->
                    if (f.exists()) return@mapNotNull t to f
                    // One frame in memory at a time; written out and dropped straight away.
                    val bmp = runCatching { Thumbnails.grab(r, (t * 1_000_000).toLong()) }.getOrNull() ?: return@mapNotNull null
                    writeJpeg(bmp, f, 88)?.let { t to it }
                }
            }.orEmpty()
        }
    }

    fun delete(ctx: Context, id: Long) {
        posterFile(ctx, id).delete()
        sheetDir(ctx, id).deleteRecursively()
    }

    /** Decodes no bigger than [maxSide] so grid tiles stay light. */
    fun decode(f: File, maxSide: Int = Int.MAX_VALUE): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        return BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        })
    }

    private fun times(durMs: Long) = (0 until Thumbnails.COUNT).map { i -> (i + 0.5) * durMs / Thumbnails.COUNT / 1000.0 }

    private suspend fun <T> lock(id: Long, block: suspend () -> T): T = busy.getOrPut(id) { Mutex() }.withLock { block() }

    private fun <T> retriever(ctx: Context, uri: Uri, block: (MediaMetadataRetriever) -> T): T? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, uri)
            block(r)
        } catch (_: Exception) {
            null
        } finally {
            r.release()
        }
    }

    private fun writeJpeg(b: Bitmap, f: File, quality: Int): File? = runCatching {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, quality, it) }
        b.recycle()
        if (!tmp.renameTo(f)) error("rename")
        f
    }.getOrNull()
}
