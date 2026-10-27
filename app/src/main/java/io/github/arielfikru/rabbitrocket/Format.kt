package io.github.arielfikru.rabbitrocket

import java.util.Locale

fun fmtBytes(b: Long): String {
    if (b < 1024) return "$b B"
    val units = listOf("KB", "MB", "GB", "TB")
    var v = b / 1024.0
    var i = 0
    while (v >= 1024 && i < units.lastIndex) {
        v /= 1024
        i++
    }
    return String.format(Locale.US, if (v >= 100) "%.0f %s" else "%.1f %s", v, units[i])
}

fun statusLine(job: DlJob): String = when (job.status) {
    Status.Queued -> "menunggu giliran"
    Status.Downloading -> when {
        job.direct && job.totalBytes > 0 ->
            "${fmtBytes(job.bytes)} / ${fmtBytes(job.totalBytes)} · ${fmtBytes(job.speed)}/s"
        job.direct -> if (job.bytes == 0L) "menghubungkan…" else "${fmtBytes(job.bytes)} · ${fmtBytes(job.speed)}/s"
        job.total == 0 -> "membaca playlist…"
        else -> "${job.done}/${job.total} seg · ${fmtBytes(job.bytes)} · ${fmtBytes(job.speed)}/s"
    }
    Status.Merging -> "menggabungkan ${job.total} segmen"
    Status.Converting -> "konversi ke MP4 · ${job.convertProgress}%"
    Status.Saving -> "menyimpan ke ${Storage.displayPath}"
    Status.Done -> listOfNotNull(
        job.width.takeIf { it > 0 }?.let { "${minOf(job.width, job.height)}p" },
        job.durationSec.takeIf { it > 0 }?.let(::fmtDuration),
        fmtBytes(job.size),
        Storage.displayPath,
    ).joinToString(" · ")
    Status.Retrying -> if (job.waitingNet) "${job.error ?: "Koneksi putus"} · menunggu internet"
    else "${job.error ?: "Koneksi putus"} · coba lagi ${job.attempt}/3 dalam ${job.retryIn} dtk"
    Status.Paused -> "dijeda · " + when {
        job.direct && job.totalBytes > 0 -> "${fmtBytes(job.bytes)} / ${fmtBytes(job.totalBytes)}"
        job.direct -> fmtBytes(job.bytes)
        job.total > 0 -> "${job.done}/${job.total} seg"
        else -> "belum ada yang terunduh"
    }
    Status.Failed -> job.error ?: "gagal"
    Status.Canceled -> "dibatalkan"
}

/** "1080p · 1920×1080"; the short side names the class, so portrait clips read right too. */
fun fmtResolution(w: Int, h: Int): String = "${minOf(w, h)}p · ${w}×${h}"

fun fmtDuration(sec: Double): String {
    val s = sec.toLong()
    val h = s / 3600
    val m = (s % 3600) / 60
    val r = s % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, r) else String.format(Locale.US, "%d:%02d", m, r)
}

private val URL_RE = Regex("""https?://[^\s"'<>]+""")

fun extractUrl(text: String): String? = URL_RE.find(text)?.value
