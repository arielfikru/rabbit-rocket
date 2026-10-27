package io.github.arielfikru.rabbitrocket

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer

/**
 * TS → MP4 by copying samples (no re-encode). Uses the platform extractor/muxer:
 * Media3 Transformer was measured dropping a byte from the first IDR and garbling B-frame DTS.
 */
object Remux {
    suspend fun toMp4(input: File, output: File, durationSec: Double, onProgress: (Int) -> Unit): Boolean =
        withContext(Dispatchers.IO) {
            val extractor = MediaExtractor()
            var muxer: MediaMuxer? = null
            try {
                extractor.setDataSource(input.absolutePath)
                val m = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                muxer = m
                val tracks = IntArray(extractor.trackCount) { -1 }
                var maxInput = 1 shl 20
                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                    if (!mime.startsWith("video/") && !mime.startsWith("audio/")) continue
                    extractor.selectTrack(i)
                    tracks[i] = m.addTrack(format)
                    if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                        maxInput = maxOf(maxInput, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                    }
                }
                if (tracks.all { it < 0 }) return@withContext false
                m.start()

                val buffer = ByteBuffer.allocate(maxInput)
                val info = MediaCodec.BufferInfo()
                val totalUs = (durationSec * 1_000_000).toLong()
                var firstUs = -1L
                var lastPct = -1
                while (true) {
                    ensureActive()
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    val track = tracks[extractor.sampleTrackIndex]
                    val time = extractor.sampleTime
                    if (track >= 0) {
                        val key = extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
                        info.set(0, size, time, if (key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                        m.writeSampleData(track, buffer, info)
                    }
                    if (firstUs < 0) firstUs = time
                    if (totalUs > 0) {
                        val pct = ((time - firstUs) * 100 / totalUs).toInt().coerceIn(0, 100)
                        if (pct != lastPct) {
                            lastPct = pct
                            onProgress(pct)
                        }
                    }
                    extractor.advance()
                }
                m.stop()
                input.delete()
                true
            } catch (e: kotlinx.coroutines.CancellationException) {
                output.delete()
                throw e
            } catch (_: Exception) {
                output.delete()
                false
            } finally {
                runCatching { muxer?.release() }
                extractor.release()
            }
        }
}
