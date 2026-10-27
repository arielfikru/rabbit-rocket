package io.github.arielfikru.rabbitrocket.hls

enum class MediaKind(val label: String) { Hls("HLS"), File("FILE") }

object MediaTypes {
    private val EXT_MIME = mapOf(
        "mp4" to "video/mp4", "m4v" to "video/x-m4v", "mkv" to "video/x-matroska", "webm" to "video/webm",
        "mov" to "video/quicktime", "3gp" to "video/3gpp", "ts" to "video/mp2t", "flv" to "video/x-flv",
        "avi" to "video/x-msvideo", "mp3" to "audio/mpeg", "m4a" to "audio/mp4", "aac" to "audio/aac",
        "ogg" to "audio/ogg", "wav" to "audio/wav",
    )
    private val MIME_EXT = EXT_MIME.entries.associate { (ext, mime) -> mime to ext } +
        mapOf("video/mpeg" to "mpg", "audio/x-m4a" to "m4a", "video/x-mp4" to "mp4")

    /** Extension of the URL path; tolerates trailing slashes like ".../123.mp4/?token=". */
    fun pathExt(url: String): String? =
        url.substringBefore('#').substringBefore('?').trimEnd('/').substringAfterLast('/')
            .substringAfterLast('.', "").lowercase().takeIf { it.isNotEmpty() }

    /** What a sniffed URL is worth showing as; null for everything else (images, scripts, TS segments). */
    fun classify(url: String, mime: String? = null): MediaKind? {
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) return null
        val ext = pathExt(url)
        val m = mime?.lowercase()
        return when {
            ext == "m3u8" || ext == "m3u" || m?.contains("mpegurl") == true -> MediaKind.Hls
            ext == "ts" -> null
            ext != null && ext in EXT_MIME -> MediaKind.File
            m != null && m.startsWith("video/") -> MediaKind.File
            else -> null
        }
    }

    fun extFor(url: String, mime: String?, fileName: String?): String =
        pathExt(url)?.takeIf { it in EXT_MIME }
            ?: fileName?.substringAfterLast('.', "")?.lowercase()?.takeIf { it in EXT_MIME }
            ?: mime?.lowercase()?.substringBefore(';')?.trim()?.let { MIME_EXT[it] }
            ?: "mp4"

    fun mimeFor(ext: String): String = EXT_MIME[ext] ?: "video/mp4"
}
