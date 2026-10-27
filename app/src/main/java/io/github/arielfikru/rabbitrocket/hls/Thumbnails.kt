package io.github.arielfikru.rabbitrocket.hls

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
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

    fun generate(client: HlsClient, media: Playlist.Media, dir: File): Flow<Thumb> = channelFlow {
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
                        frame(f)?.let { send(Thumb(slot, starts[segIdx], it)) }
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

    private fun frame(f: File): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(f.absolutePath)
            r.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 480, 270)
                ?: r.getFrameAtTime(-1)
        } catch (_: Exception) {
            null
        } finally {
            r.release()
        }
    }
}
