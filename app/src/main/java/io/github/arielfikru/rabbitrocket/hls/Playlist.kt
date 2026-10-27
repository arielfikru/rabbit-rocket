package io.github.arielfikru.rabbitrocket.hls

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class Variant(val url: String, val bandwidth: Long, val resolution: String?, val codecs: String?) {
    /** "1080p", "720p", or a bitrate when the playlist gives no resolution. */
    val quality: String
        get() {
            val h = resolution?.substringAfter('x', "")?.toIntOrNull()
            return when {
                h != null -> "${h}p"
                bandwidth > 0 -> "${bandwidth / 1000}k"
                else -> "Auto"
            }
        }

    val detail: String
        get() = listOfNotNull(
            if (bandwidth > 0) "%.1f Mbps".format(bandwidth / 1_000_000.0) else null,
            codecs?.split(',')?.joinToString(" + ") { it.trim().substringBefore('.') },
        ).joinToString(" · ").ifEmpty { url.substringAfterLast('/').substringBefore('?') }
}

data class KeyInfo(val method: String, val url: String?, val iv: ByteArray?)

data class ByteRange(val offset: Long, val length: Long)

data class Segment(
    val url: String,
    val duration: Double,
    val sequence: Long,
    val key: KeyInfo?,
    val range: ByteRange?,
)

data class InitSegment(val url: String, val range: ByteRange?, val key: KeyInfo?)

sealed interface Playlist {
    data class Master(val variants: List<Variant>) : Playlist

    data class Media(
        val segments: List<Segment>,
        val init: InitSegment?,
        val isLive: Boolean,
    ) : Playlist {
        val duration: Double get() = segments.sumOf { it.duration }
    }
}

object PlaylistParser {
    private val ATTR = Regex("""([A-Z0-9-]+)=("[^"]*"|[^,]*)""")

    fun attrs(s: String): Map<String, String> =
        ATTR.findAll(s).associate { it.groupValues[1] to it.groupValues[2].trim('"') }

    fun resolve(base: String, ref: String): String =
        base.toHttpUrlOrNull()?.resolve(ref)?.toString() ?: ref

    fun parse(text: String, baseUrl: String): Playlist {
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.any { it.startsWith("#EXT-X-STREAM-INF") }) return parseMaster(lines, baseUrl)
        if (lines.none { it.startsWith("#EXTINF") }) return parsePlain(lines, baseUrl)
        return parseMedia(lines, baseUrl)
    }

    /** A bare .m3u: one URL per line, either sub-playlists or media chunks. */
    private fun parsePlain(lines: List<String>, base: String): Playlist {
        val urls = lines.filterNot { it.startsWith("#") }.map { resolve(base, it) }
        val allPlaylists = urls.isNotEmpty() && urls.all {
            val path = it.substringBefore('?').lowercase()
            path.endsWith(".m3u8") || path.endsWith(".m3u")
        }
        if (allPlaylists) return Playlist.Master(urls.map { Variant(it, 0, null, null) })
        return Playlist.Media(
            urls.mapIndexed { i, u -> Segment(u, 0.0, i.toLong(), null, null) },
            init = null,
            isLive = false,
        )
    }

    private fun parseMaster(lines: List<String>, base: String): Playlist.Master {
        val out = mutableListOf<Variant>()
        var pending: Map<String, String>? = null
        for (l in lines) {
            when {
                l.startsWith("#EXT-X-STREAM-INF:") -> pending = attrs(l.substringAfter(':'))
                l.startsWith("#") -> Unit
                pending != null -> {
                    out += Variant(
                        url = resolve(base, l),
                        bandwidth = pending["BANDWIDTH"]?.toLongOrNull() ?: 0,
                        resolution = pending["RESOLUTION"],
                        codecs = pending["CODECS"],
                    )
                    pending = null
                }
            }
        }
        return Playlist.Master(out.distinctBy { it.url }.sortedByDescending { it.bandwidth })
    }

    private fun parseMedia(lines: List<String>, base: String): Playlist.Media {
        var sequence = 0L
        var key: KeyInfo? = null
        var init: InitSegment? = null
        var duration = 0.0
        var rangeSpec: String? = null
        var ended = false
        val rangeEnd = mutableMapOf<String, Long>()
        val segments = mutableListOf<Segment>()

        for (l in lines) {
            when {
                l.startsWith("#EXT-X-MEDIA-SEQUENCE:") ->
                    sequence = l.substringAfter(':').toLongOrNull() ?: 0

                l.startsWith("#EXT-X-KEY:") -> {
                    val a = attrs(l.substringAfter(':'))
                    val method = a["METHOD"] ?: "NONE"
                    key = if (method == "NONE") null
                    else KeyInfo(method, a["URI"]?.let { resolve(base, it) }, a["IV"]?.let(::hexToBytes))
                }

                l.startsWith("#EXT-X-MAP:") -> {
                    val a = attrs(l.substringAfter(':'))
                    a["URI"]?.let { uri ->
                        init = InitSegment(resolve(base, uri), a["BYTERANGE"]?.let { parseRange(it, 0) }, key)
                    }
                }

                l.startsWith("#EXTINF:") ->
                    duration = l.substringAfter(':').substringBefore(',').trim().toDoubleOrNull() ?: 0.0

                l.startsWith("#EXT-X-BYTERANGE:") -> rangeSpec = l.substringAfter(':')
                l.startsWith("#EXT-X-ENDLIST") -> ended = true
                l.startsWith("#") -> Unit
                else -> {
                    val url = resolve(base, l)
                    val range = rangeSpec?.let { parseRange(it, rangeEnd[url] ?: 0) }
                    range?.let { rangeEnd[url] = it.offset + it.length }
                    segments += Segment(url, duration, sequence++, key, range)
                    duration = 0.0
                    rangeSpec = null
                }
            }
        }
        return Playlist.Media(segments, init, isLive = !ended)
    }

    /** "length[@offset]"; a missing offset continues where the previous range of that URL ended. */
    private fun parseRange(spec: String, defaultOffset: Long): ByteRange {
        val parts = spec.trim().split('@')
        return ByteRange(parts.getOrNull(1)?.toLongOrNull() ?: defaultOffset, parts[0].toLong())
    }

    fun hexToBytes(s: String): ByteArray {
        val hex = s.removePrefix("0x").removePrefix("0X").padStart(32, '0')
        return ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
