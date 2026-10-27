package io.github.arielfikru.rabbitrocket.ui

import android.content.Context
import android.graphics.Bitmap
import io.github.arielfikru.rabbitrocket.hls.Probe
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * A link put aside for later. [checked] ones went through the preview, so they carry everything
 * a download needs; links saved straight from the browser get checked when opened.
 */
data class Saved(
    val id: Long,
    val url: String,
    val name: String,
    val referer: String?,
    val ua: String?,
    val cookie: String?,
    /** "HLS", "MP4", … */
    val kind: String,
    val checked: Boolean = false,
    val streamUrl: String? = null,
    val quality: String? = null,
    val direct: Probe.Direct? = null,
    val duration: Double = 0.0,
    val width: Int = 0,
    val height: Int = 0,
    val bytes: Long = 0,
    val thumb: String? = null,
)

internal object SavedStore {
    private fun file(ctx: Context) = File(ctx.filesDir, "saved.json")
    fun thumbDir(ctx: Context) = File(ctx.filesDir, "saved_thumbs").apply { mkdirs() }

    fun load(ctx: Context): List<Saved> = runCatching {
        val arr = JSONArray(file(ctx).readText())
        (0 until arr.length()).map { i -> arr.getJSONObject(i).toSaved() }
    }.getOrDefault(emptyList())

    fun save(ctx: Context, list: List<Saved>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        val f = file(ctx)
        File(f.parentFile, "saved.tmp").apply { writeText(arr.toString()); renameTo(f) }
    }

    fun writeThumb(ctx: Context, id: Long, bmp: Bitmap): String? = runCatching {
        val f = File(thumbDir(ctx), "$id.jpg")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 82, it) }
        f.absolutePath
    }.getOrNull()

    private fun Saved.toJson() = JSONObject()
        .put("id", id).put("url", url).put("name", name)
        .put("referer", referer).put("ua", ua).put("cookie", cookie)
        .put("kind", kind).put("checked", checked)
        .put("stream", streamUrl).put("quality", quality)
        .put("duration", duration).put("w", width).put("h", height).put("bytes", bytes)
        .put("thumb", thumb)
        .apply {
            direct?.let { d ->
                put("direct", JSONObject().put("url", d.url).put("size", d.size).put("mime", d.mime)
                    .put("ranges", d.ranges).put("file", d.fileName))
            }
        }

    private fun JSONObject.str(k: String) = if (isNull(k)) null else optString(k).ifEmpty { null }

    private fun JSONObject.toSaved() = Saved(
        id = getLong("id"),
        url = getString("url"),
        name = optString("name"),
        referer = str("referer"),
        ua = str("ua"),
        cookie = str("cookie"),
        kind = optString("kind", "HLS"),
        checked = optBoolean("checked"),
        streamUrl = str("stream"),
        quality = str("quality"),
        direct = optJSONObject("direct")?.let { d ->
            Probe.Direct(d.getString("url"), d.optLong("size", -1), d.str("mime"), d.optBoolean("ranges"), d.str("file"))
        },
        duration = optDouble("duration", 0.0),
        width = optInt("w"),
        height = optInt("h"),
        bytes = optLong("bytes"),
        thumb = str("thumb"),
    )
}
