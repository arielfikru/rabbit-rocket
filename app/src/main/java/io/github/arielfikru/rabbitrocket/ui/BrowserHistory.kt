package io.github.arielfikru.rabbitrocket.ui

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** One page in the browsing history; a URL appears once, at its latest visit. */
internal data class Visit(val url: String, val title: String, val time: Long)

internal object BrowserHistory {
    /** Newest first. */
    val list = mutableStateListOf<Visit>()
    private var file: File? = null
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "rr-history").apply { isDaemon = true } }
    private const val LIMIT = 1000

    fun init(ctx: Context) {
        if (file != null) return
        val f = File(ctx.applicationContext.filesDir, "browser_history.json")
        file = f
        runCatching {
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list += Visit(o.getString("u"), o.optString("t"), o.optLong("at"))
            }
        }
    }

    /** Called on the main thread when a page finishes loading. */
    fun record(url: String, title: String, now: Long = System.currentTimeMillis()) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return
        val i = list.indexOfFirst { it.url == url }
        // A reload or a title arriving late shouldn't blank a good title.
        val keptTitle = title.ifBlank { if (i >= 0) list[i].title else "" }
        if (i >= 0) list.removeAt(i)
        list.add(0, Visit(url, keptTitle, now))
        while (list.size > LIMIT) list.removeAt(list.lastIndex)
        save()
    }

    fun remove(v: Visit) {
        list.remove(v)
        save()
    }

    fun clear() {
        list.clear()
        save()
    }

    private fun save() {
        val f = file ?: return
        val snapshot = list.toList()
        io.execute {
            val arr = JSONArray()
            snapshot.forEach { arr.put(JSONObject().put("u", it.url).put("t", it.title).put("at", it.time)) }
            runCatching { File(f.parentFile, "browser_history.tmp").apply { writeText(arr.toString()); renameTo(f) } }
        }
    }
}

/** "Hari ini", "Kemarin", or a date like "Senin, 26 Okt" (with the year when it isn't this year). */
internal fun dayLabel(time: Long, now: Long = System.currentTimeMillis()): String {
    val day = Calendar.getInstance().apply { timeInMillis = time }
    val today = Calendar.getInstance().apply { timeInMillis = now }
    fun Calendar.key() = get(Calendar.YEAR) * 1000 + get(Calendar.DAY_OF_YEAR)
    val yesterday = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
    val id = Locale("id")
    return when (day.key()) {
        today.key() -> "Hari ini"
        yesterday.key() -> "Kemarin"
        else -> {
            val pattern = if (day.get(Calendar.YEAR) == today.get(Calendar.YEAR)) "EEEE, d MMM" else "d MMM yyyy"
            SimpleDateFormat(pattern, id).format(Date(time))
        }
    }
}

/** Visits matching [query] in title or URL, grouped under their day label, newest first. */
internal fun groupHistory(visits: List<Visit>, query: String, now: Long = System.currentTimeMillis()): List<Pair<String, List<Visit>>> {
    val q = query.trim()
    val hits = if (q.isEmpty()) visits else visits.filter { it.title.contains(q, true) || it.url.contains(q, true) }
    return hits.groupBy { dayLabel(it.time, now) }.toList()
}
