package io.github.arielfikru.rabbitrocket

import io.github.arielfikru.rabbitrocket.hls.DEFAULT_UA
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.atomic.AtomicLong

enum class Status(val label: String) {
    Queued("ANTRI"),
    Downloading("DOWNLOAD"),
    Merging("GABUNG"),
    Converting("KONVERSI"),
    Saving("SIMPAN"),
    Done("SELESAI"),
    Failed("GAGAL"),
    Canceled("BATAL");

    val isActive get() = this == Downloading || this == Merging || this == Converting || this == Saving
    val isWaiting get() = this == Queued || isActive
}

data class DlJob(
    val id: Long,
    val name: String,
    val url: String,
    val referer: String?,
    val userAgent: String?,
    val toMp4: Boolean,
    /** A plain media file (mp4/mkv/…) rather than an HLS playlist. */
    val direct: Boolean = false,
    val cookie: String? = null,
    val status: Status = Status.Queued,
    val done: Int = 0,
    val total: Int = 0,
    val bytes: Long = 0,
    /** Direct files only: full size when the server reports it. */
    val totalBytes: Long = 0,
    val speed: Long = 0,
    val convertProgress: Int = 0,
    val outputUri: String? = null,
    val outputMime: String? = null,
    val fileName: String? = null,
    val size: Long = 0,
    val error: String? = null,
) {
    fun headers(): Map<String, String> = buildMap {
        put("User-Agent", userAgent?.takeIf { it.isNotBlank() } ?: DEFAULT_UA)
        cookie?.takeIf { it.isNotBlank() }?.let { put("Cookie", it) }
        referer?.takeIf { it.isNotBlank() }?.let { ref ->
            put("Referer", ref)
            ref.toHttpUrlOrNull()?.let { u ->
                val port = if (u.port == 80 || u.port == 443) "" else ":${u.port}"
                put("Origin", "${u.scheme}://${u.host}$port")
            }
        }
    }

    val downloadFraction: Float
        get() = when {
            direct -> if (totalBytes > 0) (bytes.toFloat() / totalBytes).coerceAtMost(1f) else 0f
            total > 0 -> done / total.toFloat()
            else -> 0f
        }

    val hasSize: Boolean get() = if (direct) totalBytes > 0 else total > 0

    /** Something on disk that "Lanjutkan" can build on. */
    val hasPartial: Boolean get() = if (direct) bytes > 0 else done > 0

    /** Direct files have no merge step, so downloading fills almost the whole bar. */
    private val downloadShare: Float get() = if (direct) 0.97f else 0.85f

    /** Whole-job progress: downloading is 0–85%, merging/converting/saving fill the rest. */
    val overall: Float
        get() = when (status) {
            Status.Queued, Status.Failed, Status.Canceled -> downloadFraction * downloadShare
            Status.Downloading -> downloadFraction * downloadShare
            Status.Merging -> 0.87f
            Status.Converting -> 0.88f + convertProgress / 100f * 0.1f
            Status.Saving -> 0.99f
            Status.Done -> 1f
        }

    val isIndeterminate: Boolean
        get() = status == Status.Merging || status == Status.Saving ||
            (status == Status.Downloading && !hasSize)
}

object DownloadStore {
    private val _jobs = MutableStateFlow<List<DlJob>>(emptyList())
    val jobs: StateFlow<List<DlJob>> = _jobs.asStateFlow()
    private val ids = AtomicLong(System.currentTimeMillis())

    fun newId() = ids.incrementAndGet()
    fun get(id: Long) = _jobs.value.firstOrNull { it.id == id }
    fun add(job: DlJob) = _jobs.update { it + job }
    fun remove(id: Long) = _jobs.update { list -> list.filterNot { it.id == id } }
    fun update(id: Long, f: (DlJob) -> DlJob) =
        _jobs.update { list -> list.map { if (it.id == id) f(it) else it } }
}
