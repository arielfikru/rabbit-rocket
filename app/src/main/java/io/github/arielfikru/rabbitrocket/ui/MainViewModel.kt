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
import io.github.arielfikru.rabbitrocket.JobFrames
import io.github.arielfikru.rabbitrocket.Notifs
import io.github.arielfikru.rabbitrocket.Status
import io.github.arielfikru.rabbitrocket.hls.HlsClient
import io.github.arielfikru.rabbitrocket.hls.MediaKind
import io.github.arielfikru.rabbitrocket.hls.MediaTypes
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

    /** Links put aside to preview or download later, newest first. */
    val saved = mutableStateListOf<Saved>().apply { addAll(SavedStore.load(app)) }
    /** The saved entry the open preview came from; downloading it takes it off the list. */
    private var previewOf: Long? = null

    /** 0 = home, 1 = browser. */
    var tab by mutableStateOf(0)
    /** Set when a link comes from the browser so the download looks like the page's own request. */
    private var browserUa: String? = null
    private var browserCookie: String? = null

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

    /** Puts a sniffed link on the saved list without leaving the browser. */
    fun saveFound(f: Found, ua: String?): Boolean {
        if (saved.any { it.url == f.url }) return false
        val cookie = runCatching { CookieManager.getInstance().getCookie(f.url) }.getOrNull()
        val kind = if (f.kind == MediaKind.Hls) "HLS" else MediaTypes.extFor(f.url, null, null).uppercase()
        addSaved(Saved(DownloadStore.newId(), f.url, sanitize(guessName(f.url)), f.referer, ua, cookie, kind))
        return true
    }

    /** Keeps the open preview on the saved list, with what it learned, and clears the form. */
    fun saveForLater() {
        val p = preview ?: return
        val id = previewOf ?: DownloadStore.newId()
        val ctx = getApplication<Application>()
        val thumb = thumbs.minByOrNull { it.slot }?.let { SavedStore.writeThumb(ctx, id, it.bitmap) }
        val size = p.size
        val entry = Saved(
            id = id,
            url = url.trim(),
            name = jobName(p.direct?.fileName?.substringBeforeLast('.'), p.variant?.quality),
            referer = referer.trim().ifBlank { null },
            ua = userAgent.trim().ifBlank { null } ?: browserUa,
            cookie = browserCookie,
            kind = p.direct?.ext?.uppercase() ?: "HLS",
            checked = true,
            streamUrl = p.streamUrl,
            quality = p.variant?.quality,
            direct = p.direct,
            duration = p.duration,
            width = size?.first ?: 0,
            height = size?.second ?: 0,
            bytes = p.estimatedBytes ?: 0,
            thumb = thumb,
        )
        saved.removeAll { it.id == id || it.url == entry.url }
        addSaved(entry)
        closePreview()
        previewOf = null
        url = ""
        fileName = ""
        browserUa = null
        browserCookie = null
    }

    /** Reopens a saved link in the preview, with the identity it was saved with. */
    fun previewSaved(s: Saved) {
        editUrl(s.url)
        fileName = if (s.checked) s.name.removeSuffix("_${s.quality}") else ""
        referer = s.referer.orEmpty()
        browserUa = s.ua
        browserCookie = s.cookie
        previewOf = s.id
        tab = 0
        check()
    }

    /** Checked links download straight away; unchecked ones need a look first. */
    fun downloadSaved(s: Saved) {
        val stream = s.streamUrl
        if (!s.checked || stream == null) return previewSaved(s)
        val job = DlJob(
            id = DownloadStore.newId(),
            name = s.name,
            url = stream,
            referer = s.referer,
            userAgent = userAgent.trim().ifBlank { null } ?: s.ua,
            toMp4 = toMp4,
            direct = s.direct != null,
            cookie = s.cookie,
            totalBytes = s.direct?.size?.coerceAtLeast(0) ?: 0,
        )
        DownloadStore.add(job)
        DownloadService.start(getApplication(), job.id)
        removeSaved(s)
    }

    fun removeSaved(s: Saved) {
        saved.removeAll { it.id == s.id }
        s.thumb?.let { File(it).delete() }
        persistSaved()
    }

    private fun addSaved(s: Saved) {
        saved.add(0, s)
        persistSaved()
    }

    private fun persistSaved() {
        val snapshot = saved.toList()
        viewModelScope.launch(Dispatchers.IO) { SavedStore.save(getApplication(), snapshot) }
    }

    /** Typing a new link drops the browser's identity. */
    fun editUrl(v: String) {
        url = v
        previewOf = null
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
                android.util.Log.w("RabbitRocket", "check failed", e)
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
        val from = previewOf?.let { id -> saved.firstOrNull { it.id == id } }
        previewOf = null
        closePreview()
        // Let the preview start folding before the new job card and the service start.
        launching = true
        viewModelScope.launch {
            delay(220)
            enqueue(p.streamUrl, p.variant?.quality, p.direct)
            from?.let { removeSaved(it) }
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
            // Thumbs come from the lightest variant, so only a single-stream link says what the download looks like.
            val single = p.variants.size <= 1
            Thumbnails.generate(client, media, dir) { w, h ->
                if (single) viewModelScope.launch {
                    preview = preview?.takeIf { it.sourceUrl == p.sourceUrl }?.copy(probedSize = w to h)
                }
            }.collect { thumbs = thumbs + it }
        }
    }

    private fun startDirectThumbs(d: Probe.Direct) {
        stopThumbs()
        thumbs = emptyList()
        thumbSlots = Thumbnails.COUNT
        val headers = draft(d.url).headers()
        thumbJob = viewModelScope.launch {
            Thumbnails.generateDirect(d.url, headers) { sec, size ->
                viewModelScope.launch {
                    preview = preview?.takeIf { it.direct == d }?.copy(directDuration = sec, probedSize = size)
                }
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

    fun pause(id: Long) {
        getApplication<Application>().startService(DownloadService.pauseIntent(getApplication(), id))
    }

    /** Lanjutkan / Ulangi; the service moves the job back to the queue. */
    fun retry(id: Long) {
        val job = DownloadStore.get(id) ?: return
        if (job.status != Status.Failed && job.status != Status.Canceled && job.status != Status.Paused) return
        DownloadService.start(getApplication(), id)
    }

    fun remove(id: Long) {
        DownloadStore.remove(id)
        Notifs.cancelResult(getApplication(), id)
        viewModelScope.launch(Dispatchers.IO) {
            DownloadEngine.workDir(getApplication(), id).deleteRecursively()
            JobFrames.delete(getApplication(), id)
        }
    }

    /** Old history entries get their poster the first time their card shows. */
    fun ensurePoster(id: Long) {
        viewModelScope.launch { JobFrames.ensurePoster(getApplication(), id) }
    }

    suspend fun frameSheet(job: DlJob) = JobFrames.sheet(getApplication(), job)

    /** Drops a finished job from the list, and optionally the saved file with it. */
    fun deleteJob(job: DlJob, withFile: Boolean) {
        remove(job.id)
        if (!withFile) return
        val uri = job.outputUri?.let(android.net.Uri::parse) ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val ok = runCatching { getApplication<Application>().contentResolver.delete(uri, null, null) >= 0 }.getOrDefault(false)
            if (!ok) {
                launch(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        getApplication(), "File tidak bisa dihapus dari sini; hapus lewat aplikasi File", android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    /** Renames a finished download in the Downloads folder; the extension stays. */
    fun renameJob(job: DlJob, newName: String) {
        val uri = job.outputUri?.let(android.net.Uri::parse) ?: return
        val base = sanitize(newName.trim().removeSuffix(".${job.fileName?.substringAfterLast('.', "")}"))
        val ext = job.fileName?.substringAfterLast('.', "")?.ifEmpty { null }
        val target = if (ext != null) "$base.$ext" else base
        if (target == job.fileName) return
        viewModelScope.launch(Dispatchers.IO) {
            val resolver = getApplication<Application>().contentResolver
            val actual = runCatching {
                resolver.update(uri, android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, target)
                }, null, null)
                // MediaStore adds " (1)" on a clash; show what it really is.
                resolver.query(uri, arrayOf(android.provider.MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
                    ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            }.getOrNull()
            if (actual == null) {
                launch(Dispatchers.Main) {
                    android.widget.Toast.makeText(getApplication(), "Gagal mengganti nama file", android.widget.Toast.LENGTH_SHORT).show()
                }
                return@launch
            }
            DownloadStore.update(job.id) { it.copy(name = actual.substringBeforeLast('.'), fileName = actual) }
        }
    }

    fun clearFinished() {
        jobs.value.filter { it.status == Status.Done }.forEach { remove(it.id) }
    }

    private fun draft(u: String) = DlJob(
        0, "", u, referer.trim().ifBlank { null },
        userAgent.trim().ifBlank { null } ?: browserUa, toMp4, cookie = browserCookie,
    )

    private fun enqueue(mediaUrl: String, quality: String?, direct: Probe.Direct?) {
        val job = draft(mediaUrl).copy(
            id = DownloadStore.newId(),
            name = jobName(direct?.fileName?.substringBeforeLast('.'), quality),
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

    private fun jobName(hint: String?, quality: String?): String {
        val base = sanitize(fileName.ifBlank { hint ?: guessName(url.trim()) })
        return if (quality != null && quality != "Auto") "${base}_$quality" else base
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
    /** Width × height read from the media itself, when the playlist doesn't say. */
    val probedSize: Pair<Int, Int>? = null,
) {
    val isDirect: Boolean get() = direct != null
    val variant: Variant? get() = variants.getOrNull(selected)
    val streamUrl: String get() = variant?.url ?: sourceUrl
    val duration: Double get() = media?.duration ?: directDuration
    val segments: Int get() = media?.segments?.size ?: 0
    val encrypted: Boolean get() = media?.segments?.any { it.key != null } == true
    val isLive: Boolean get() = media?.isLive == true

    /** The selected variant's declared resolution, else what the frames showed. */
    val size: Pair<Int, Int>?
        get() = variant?.resolution?.split('x')?.mapNotNull { it.trim().toIntOrNull() }
            ?.takeIf { it.size == 2 }?.let { it[0] to it[1] } ?: probedSize

    /** bandwidth × duration; null when the playlist doesn't say. */
    val estimatedBytes: Long?
        get() = direct?.size?.takeIf { it > 0 } ?: variant?.bandwidth?.takeIf { it > 0 && duration > 0 }?.let { (it * duration / 8).toLong() }
}

/** A media link spotted by the browser. */
data class Found(val url: String, val kind: MediaKind, val via: String, val referer: String?)
