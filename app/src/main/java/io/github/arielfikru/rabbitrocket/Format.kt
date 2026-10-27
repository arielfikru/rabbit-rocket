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
    Status.Downloading ->
        if (job.total == 0) "membaca playlist…"
        else "${job.done}/${job.total} seg · ${fmtBytes(job.bytes)} · ${fmtBytes(job.speed)}/s"
    Status.Merging -> "menggabungkan ${job.total} segmen"
    Status.Converting -> "konversi ke MP4 · ${job.convertProgress}%"
    Status.Saving -> "menyimpan ke ${Storage.displayPath}"
    Status.Done -> "${fmtBytes(job.size)} · ${Storage.displayPath}"
    Status.Failed -> job.error ?: "gagal"
    Status.Canceled ->
        if (job.done > 0) "${job.done}/${job.total} seg tersimpan · bisa dilanjutkan" else "dibatalkan"
}

fun fmtDuration(sec: Double): String {
    val s = sec.toLong()
    val h = s / 3600
    val m = (s % 3600) / 60
    val r = s % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, r) else String.format(Locale.US, "%d:%02d", m, r)
}

private val URL_RE = Regex("""https?://[^\s"'<>]+""")

fun extractUrl(text: String): String? = URL_RE.find(text)?.value
