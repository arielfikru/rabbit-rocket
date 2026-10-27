package io.github.arielfikru.rabbitrocket.ui

import android.app.Application
import android.webkit.CookieManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.arielfikru.rabbitrocket.DlJob
import io.github.arielfikru.rabbitrocket.DownloadEngine
import io.github.arielfikru.rabbitrocket.DownloadService
import io.github.arielfikru.rabbitrocket.DownloadStore
import io.github.arielfikru.rabbitrocket.Status
import io.github.arielfikru.rabbitrocket.hls.HlsClient
import io.github.arielfikru.rabbitrocket.hls.MediaKind
import io.github.arielfikru.rabbitrocket.hls.Playlist
import io.github.arielfikru.rabbitrocket.hls.Probe
import io.github.arielfikru.rabbitrocket.hls.Thumbnails
import io.github.arielfikru.rabbitrocket.hls.Variant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("rabbitrocket", 0)

    var url by mutableStateOf("")
    var fileName by mutableStateOf("")
    var referer by mutableStateOf("")
    var userAgent by mutableStateOf(prefs.getString("ua", "")!!)
    var toMp4 by mutableStateOf(prefs.getBoolean("mp4", true))
    var optionsOpen by mutableStateOf(false)

    var analyzing by mutableStateOf(false)
        private set
    /** True between "Luncurkan download" and the job appearing, so the empty state doesn't flash back. */
    var launching by mutableStateOf(false)
        private set
    var preview by mutableStateOf<Preview?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var thumbs by mutableStateOf<List<Thumbnails.Thumb>>(emptyList())
        private set
    var thumbSlots by mutableStateOf(0)
        private set
    private var thumbJob: Job? = null

    val jobs = DownloadStore.jobs

    /** 0 = home, 1 = browser. */
    var tab by mutableStateOf(0)
    /** Media links the browser spotted on the current page, newest first. */
    val found = mutableStateListOf<Found>()
    /** Set when a link comes from the browser so the download looks like the page's own request. */
    private var browserUa: String? = null
    private var browserCookie: String? = null

    fun onFound(f: Found) {
        val i = found.indexOfFirst { it.url == f.url }
        if (i >= 0) return
        found.add(0, f)
        if (found.size > 40) found.removeAt(found.lastIndex)
    }

    fun clearFound() = found.clear()

    /** Hands a sniffed link to the home tab and opens its preview. */
    fun downloadFound(f: Found, ua: String?) {
        url = f.url
        fileName = ""
        referer = f.referer.orEmpty()
        browserUa = ua
        browserCookie = runCatching { CookieManager.getInstance().getCookie(f.url) }.getOrNull()
        tab = 0
        check()
    }

    /** Typing a new link drops the browser's identity. */
    fun editUrl(v: String) {
        url = v
        browserUa = null
        browserCookie = null
    }

    /** Reads the playlist and opens the preview card; nothing is downloaded yet. */
    fun check() {
        val u = url.trim()
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            error = "Link harus diawali http:// atau https://"
            return
        }
        error = null
        preview = null
        analyzing = true
        viewModelScope.launch {
            try {
                val client = HlsClient(draft(u).headers())
                val probe = client.probe(u)
                if (probe is Probe.Direct) {
                    preview = Preview(u, emptyList(), 0, null, direct = probe)
                    startDirectThumbs(probe)
                    return@launch
                }
                val hlsUrl = (probe as Probe.Hls).url
                preview = when (val p = client.fetchPlaylist(hlsUrl)) {
                    is Playlist.Master -> {
                        val best = p.variants.firstOrNull() ?: error("Playlist tidak punya stream")
                        val media = client.fetchPlaylist(best.url) as? Playlist.Media
                        Preview(hlsUrl, p.variants, 0, media)
                    }
                    is Playlist.Media -> {
                        if (p.segments.isEmpty()) error("Playlist kosong")
                        Preview(hlsUrl, emptyList(), 0, p)
                    }
                }
                preview?.let { startThumbs(client, it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Gagal membaca link"
            } finally {
                analyzing = false
            }
        }
    }

    fun select(index: Int) {
        preview = preview?.copy(selected = index)
    }

    fun closePreview() {
        preview = null
        stopThumbs()
    }

    fun download() {
        val p = preview ?: return
        closePreview()
        // Let the preview start folding before the new job card and the service start.
        launching = true
        viewModelScope.launch {
            delay(220)
            enqueue(p.streamUrl, p.variant?.quality, p.direct)
            launching = false
        }
    }

    /** Thumbnails come from the lightest variant to keep preview data small. */
    private fun startThumbs(client: HlsClient, p: Preview) {
        stopThumbs()
        thumbs = emptyList()
        // Reserve the grid now so tiles fill in place instead of pushing the button down later.
        thumbSlots = p.media?.let { Thumbnails.slots(it).size } ?: 0
        thumbJob = viewModelScope.launch {
            val media = p.variants.lastOrNull()?.takeIf { p.variants.size > 1 }?.let {
                runCatching { client.fetchPlaylist(it.url) as? Playlist.Media }.getOrNull()
            } ?: p.media ?: return@launch
            thumbSlots = Thumbnails.slots(media).size
            val dir = File(getApplication<Application>().cacheDir, "thumbs").apply {
                deleteRecursively()
                mkdirs()
            }
            Thumbnails.generate(client, media, dir).collect { thumbs = thumbs + it }
        }
    }

    private fun startDirectThumbs(d: Probe.Direct) {
        stopThumbs()
        thumbs = emptyList()
        thumbSlots = Thumbnails.COUNT
        val headers = draft(d.url).headers()
        thumbJob = viewModelScope.launch {
            Thumbnails.generateDirect(d.url, headers) { sec ->
                viewModelScope.launch { preview = preview?.takeIf { it.direct == d }?.copy(directDuration = sec) }
            }.collect { thumbs = thumbs + it }
            // Formats the retriever can't read (or servers that refuse it) leave no tiles: drop the grid.
            if (thumbs.isEmpty()) thumbSlots = 0
        }
    }

    /** Keeps the tiles so the folding preview card doesn't change height mid-exit. */
    private fun stopThumbs() {
        thumbJob?.cancel()
        thumbJob = null
    }

    fun headersFor(u: String) = draft(u).headers()

    fun cancel(id: Long) {
        getApplication<Application>().startService(DownloadService.cancelIntent(getApplication(), id))
    }

    fun retry(id: Long) {
        val job = DownloadStore.get(id) ?: return
        if (job.status != Status.Failed && job.status != Status.Canceled) return
        DownloadStore.update(id) { it.copy(status = Status.Queued, error = null) }
        DownloadService.start(getApplication(), id)
    }

    fun remove(id: Long) {
        DownloadStore.remove(id)
        viewModelScope.launch(Dispatchers.IO) { DownloadEngine.workDir(getApplication(), id).deleteRecursively() }
    }

    fun clearFinished() {
        jobs.value.filter { it.status == Status.Done }.forEach { remove(it.id) }
    }

    private fun draft(u: String) = DlJob(
        0, "", u, referer.trim().ifBlank { null },
        userAgent.trim().ifBlank { null } ?: browserUa, toMp4, cookie = browserCookie,
    )

    private fun enqueue(mediaUrl: String, quality: String?, direct: Probe.Direct?) {
        val hint = direct?.fileName?.substringBeforeLast('.')
        val base = sanitize(fileName.ifBlank { hint ?: guessName(url.trim()) })
        val job = draft(mediaUrl).copy(
            id = DownloadStore.newId(),
            name = if (quality != null && quality != "Auto") "${base}_$quality" else base,
            direct = direct != null,
            totalBytes = direct?.size?.coerceAtLeast(0) ?: 0,
        )
        DownloadStore.add(job)
        DownloadService.start(getApplication(), job.id)
        url = ""
        fileName = ""
        browserUa = null
        browserCookie = null
        prefs.edit {
            putString("ua", userAgent)
            putBoolean("mp4", toMp4)
        }
    }

    private companion object {
        val GENERIC = setOf(
            "index", "playlist", "master", "prog_index", "chunklist", "manifest", "media", "video",
            "stream", "main", "mono", "output", "hls", "live", "default", "file", "download", "get",
        )

        fun guessName(url: String): String {
            val stem = url.substringBefore('?').substringBefore('#').trimEnd('/')
                .substringAfterLast('/').substringBeforeLast('.')
            val generic = stem.isBlank() || stem.lowercase().let { s -> GENERIC.any { s.startsWith(it) } }
            return if (generic) "video_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) else stem
        }

        fun sanitize(s: String) = s.replace(Regex("""[\\/:*?"<>|\s]+"""), "_").trim('_', '.').take(80)
            .ifBlank { "video" }
    }
}

data class Preview(
    val sourceUrl: String,
    val variants: List<Variant>,
    val selected: Int,
    val media: Playlist.Media?,
    val direct: Probe.Direct? = null,
    /** Read from the file's metadata once the retriever opens it. */
    val directDuration: Double = 0.0,
) {
    val isDirect: Boolean get() = direct != null
    val variant: Variant? get() = variants.getOrNull(selected)
    val streamUrl: String get() = variant?.url ?: sourceUrl
    val duration: Double get() = media?.duration ?: directDuration
    val segments: Int get() = media?.segments?.size ?: 0
    val encrypted: Boolean get() = media?.segments?.any { it.key != null } == true
    val isLive: Boolean get() = media?.isLive == true

    /** bandwidth × duration; null when the playlist doesn't say. */
    val estimatedBytes: Long?
        get() = direct?.size?.takeIf { it > 0 } ?: variant?.bandwidth?.takeIf { it > 0 && duration > 0 }?.let { (it * duration / 8).toLong() }
}

/** A media link spotted by the browser. */
data class Found(val url: String, val kind: MediaKind, val via: String, val referer: String?)
