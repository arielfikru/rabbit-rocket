package io.github.arielfikru.rabbitrocket

import io.github.arielfikru.rabbitrocket.hls.DEFAULT_UA
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicLong

enum class Status(val label: String) {
    Queued("ANTRI"),
    Downloading("DOWNLOAD"),
    Merging("GABUNG"),
    Converting("KONVERSI"),
    Saving("SIMPAN"),
    /** Between automatic retries: counting down, or waiting for the internet to come back. */
    Retrying("ULANG"),
    Paused("JEDA"),
    Done("SELESAI"),
    Failed("GAGAL"),
    Canceled("BATAL");

    val isActive get() = this == Downloading || this == Merging || this == Converting || this == Saving
    val isWaiting get() = this == Queued || this == Retrying || isActive

    /** Kept in history.json: finished files, and paused jobs so they can be continued after a restart. */
    val isKept get() = this == Done || this == Paused
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
    /** Finished files: poster jpeg path ("" = tried, none), plus what the file itself reports. */
    val thumb: String? = null,
    val durationSec: Double = 0.0,
    val width: Int = 0,
    val height: Int = 0,
    /** Automatic retries used so far, and the countdown to the next one. */
    val attempt: Int = 0,
    val retryIn: Int = 0,
    val waitingNet: Boolean = false,
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
            Status.Queued, Status.Failed, Status.Canceled, Status.Paused, Status.Retrying -> downloadFraction * downloadShare
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
    /** Finished jobs are kept across restarts; everything else lives only as long as the process. */
    private var history: File? = null

    @Synchronized
    fun attach(ctx: Context) {
        if (history != null) return
        val f = File(ctx.applicationContext.filesDir, "history.json")
        history = f
        val done = runCatching {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                fun str(k: String) = o.optString(k).takeIf { o.has(k) && !o.isNull(k) }
                val paused = o.optString("status") == Status.Paused.name
                DlJob(
                    id = o.getLong("id"), name = o.getString("name"), url = o.getString("url"),
                    referer = str("referer"), userAgent = str("ua"), toMp4 = o.optBoolean("toMp4"),
                    direct = o.optBoolean("direct"), cookie = str("cookie"),
                    status = if (paused) Status.Paused else Status.Done,
                    done = o.optInt("done"), total = o.optInt("total"),
                    bytes = o.optLong("bytes"), totalBytes = o.optLong("totalBytes"),
                    outputUri = str("uri"), outputMime = str("mime"),
                    fileName = str("file"), size = o.optLong("size"),
                    thumb = str("thumb"), durationSec = o.optDouble("dur", 0.0),
                    width = o.optInt("w"), height = o.optInt("h"),
                )
            }
        }.getOrDefault(emptyList())
        _jobs.update { list -> done.filter { d -> list.none { it.id == d.id } } + list }
    }

    fun newId() = ids.incrementAndGet()
    fun get(id: Long) = _jobs.value.firstOrNull { it.id == id }
    fun add(job: DlJob) = _jobs.update { it + job }

    fun remove(id: Long) {
        val kept = get(id)?.status?.isKept == true
        _jobs.update { list -> list.filterNot { it.id == id } }
        if (kept) saveHistory()
    }

    fun update(id: Long, f: (DlJob) -> DlJob) {
        val before = get(id)
        _jobs.update { list -> list.map { if (it.id == id) f(it) else it } }
        val after = get(id)
        if (before != after && (before?.status?.isKept == true || after?.status?.isKept == true)) saveHistory()
    }

    private fun saveHistory() {
        val f = history ?: return
        val arr = JSONArray()
        _jobs.value.filter { (it.status == Status.Done && it.outputUri != null) || it.status == Status.Paused }.forEach { j ->
            arr.put(
                JSONObject().put("id", j.id).put("name", j.name).put("url", j.url).put("direct", j.direct)
                    .put("status", j.status.name)
                    .put("referer", j.referer).put("ua", j.userAgent).put("cookie", j.cookie).put("toMp4", j.toMp4)
                    .put("done", j.done).put("total", j.total).put("bytes", j.bytes).put("totalBytes", j.totalBytes)
                    .put("uri", j.outputUri).put("mime", j.outputMime).put("file", j.fileName).put("size", j.size)
                    .put("thumb", j.thumb).put("dur", j.durationSec).put("w", j.width).put("h", j.height),
            )
        }
        synchronized(this) { File(f.parentFile, "history.tmp").apply { writeText(arr.toString()); renameTo(f) } }
    }
}
