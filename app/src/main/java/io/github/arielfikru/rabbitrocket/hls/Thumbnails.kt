package io.github.arielfikru.rabbitrocket.hls

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File

/** Contact-sheet frames: one frame from each of [COUNT] evenly spaced segments. */
object Thumbnails {
    const val COUNT = 9

    data class Thumb(val slot: Int, val timeSec: Double, val bitmap: Bitmap)

    fun slots(media: Playlist.Media): List<Int> {
        val n = media.segments.size
        if (n <= COUNT) return (0 until n).toList()
        return (0 until COUNT).map { i -> ((i + 0.5) * n / COUNT).toInt().coerceIn(0, n - 1) }
    }

    /** Video dimensions as stored, swapped for rotated (portrait) recordings. */
    private fun size(r: MediaMetadataRetriever): Pair<Int, Int>? {
        val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: return null
        val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: return null
        val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        if (w <= 0 || h <= 0) return null
        return if (rot % 180 != 0) h to w else w to h
    }

    fun generate(client: HlsClient, media: Playlist.Media, dir: File, onSize: (Int, Int) -> Unit = { _, _ -> }): Flow<Thumb> = channelFlow {
        val sized = java.util.concurrent.atomic.AtomicBoolean(false)
        val starts = DoubleArray(media.segments.size)
        for (i in 1 until starts.size) starts[i] = starts[i - 1] + media.segments[i - 1].duration
        val isTs = media.init == null
        val init = media.init?.let { client.init(it, media.segments.first().sequence) }
        val gate = Semaphore(3)

        slots(media).forEachIndexed { slot, segIdx ->
            launch(Dispatchers.IO) {
                gate.withPermit {
                    val f = File(dir, "thumb_$slot.${if (isTs) "ts" else "mp4"}")
                    try {
                        val data = client.segment(media.segments[segIdx], isTs)
                        f.outputStream().use { out ->
                            init?.let(out::write)
                            out.write(data)
                        }
                        frame(f) { w, h -> if (sized.compareAndSet(false, true)) onSize(w, h) }
                            ?.let { send(Thumb(slot, starts[segIdx], it)) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // A missing tile is fine; the grid keeps its placeholder.
                    } finally {
                        f.delete()
                    }
                }
            }
        }
    }

    /**
     * Frames straight from a remote file; MediaMetadataRetriever range-reads only what it needs
     * (the moov box and the sync frames near each time), so this stays light for big MP4s.
     */
    fun generateDirect(
        url: String,
        headers: Map<String, String>,
        onInfo: (durationSec: Double, size: Pair<Int, Int>?) -> Unit,
    ): Flow<Thumb> = flow {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(url, headers)
            val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: return@flow
            onInfo(ms / 1000.0, size(r))
            for (i in 0 until COUNT) {
                val t = (i + 0.5) * ms / COUNT
                val bmp = runCatching {
                    r.getScaledFrameAtTime((t * 1000).toLong(), MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 480, 270)
                }.getOrNull() ?: continue
                emit(Thumb(i, t / 1000.0, bmp))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // No tiles; the preview still works.
        } finally {
            r.release()
        }
    }.flowOn(Dispatchers.IO)

    private fun frame(f: File, onSize: (Int, Int) -> Unit): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(f.absolutePath)
            size(r)?.let { (w, h) -> onSize(w, h) }
            r.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 480, 270)
                ?: r.getFrameAtTime(-1)
        } catch (_: Exception) {
            null
        } finally {
            r.release()
        }
    }
}
