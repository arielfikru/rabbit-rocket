package io.github.arielfikru.rabbitrocket

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import io.github.arielfikru.rabbitrocket.ui.PlayerScreen
import io.github.arielfikru.rabbitrocket.ui.RabbitRocketTheme

/**
 * In-app player for finished downloads. Orientation is never forced: the manifest leaves it
 * unspecified, so the screen follows how the phone is held (and the system auto-rotate switch).
 * Only the lock / rotate buttons set [setRequestedOrientation], and only because the user asked.
 */
class PlayerActivity : ComponentActivity() {
    var player by mutableStateOf<ExoPlayer?>(null)
        private set
    var uri by mutableStateOf<Uri?>(null)
        private set
    var title by mutableStateOf("")
        private set
    private var mime: String? = null

    var inPip by mutableStateOf(false)
        private set
    var orientationLocked by mutableStateOf(false)
        private set

    /** Saved position found when the file was opened; the UI offers "Dari awal" for it. */
    var resumedFrom by mutableStateOf(0L)
        private set

    private var startPos = 0L
    private var playWhenReady = true
    private var videoSize = VideoSize.UNKNOWN

    private val pipAvailable by lazy {
        packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            updatePip()
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED) uri?.let { Resume.clear(this@PlayerActivity, it) }
        }

        override fun onVideoSizeChanged(size: VideoSize) {
            videoSize = size
            updatePip()
        }
    }

    private val pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val p = player ?: return
            if (p.isPlaying) p.pause() else {
                if (p.playbackState == Player.STATE_ENDED) p.seekTo(0)
                p.play()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        if (!load(intent)) return finish()
        ContextCompat.registerReceiver(
            this, pipReceiver, IntentFilter(ACTION_TOGGLE), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        setContent { RabbitRocketTheme { PlayerScreen(this) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.data == null) return
        // Release first: it records the old file's position, which must not leak into the new one.
        savePosition()
        releasePlayer()
        load(intent)
        initPlayer()
    }

    private fun load(i: Intent?): Boolean {
        val u = i?.data ?: return false
        uri = u
        mime = i.type
        title = i.getStringExtra(EXTRA_TITLE) ?: u.lastPathSegment.orEmpty()
        startPos = Resume.get(this, u)
        resumedFrom = startPos
        playWhenReady = true
        return true
    }

    override fun onStart() {
        super.onStart()
        initPlayer()
    }

    override fun onStop() {
        super.onStop()
        savePosition()
        releasePlayer()
        // Closing the PiP window stops the activity; nothing is left to come back to.
        if (inPip) finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(pipReceiver) }
    }

    private fun initPlayer() {
        if (player != null) return
        val u = uri ?: return
        val p = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(SEEK_MS)
            .setSeekForwardIncrementMs(SEEK_MS)
            .build()
        p.addListener(listener)
        p.setMediaItem(MediaItem.fromUri(u), startPos)
        p.playWhenReady = playWhenReady
        p.prepare()
        player = p
    }

    private fun releasePlayer() {
        val p = player ?: return
        startPos = p.currentPosition
        playWhenReady = p.playWhenReady && p.playbackState != Player.STATE_ENDED
        p.removeListener(listener)
        p.release()
        player = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun savePosition() {
        val p = player ?: return
        val u = uri ?: return
        if (p.playbackState == Player.STATE_ENDED) Resume.clear(this, u)
        else Resume.put(this, u, p.currentPosition, p.duration)
    }

    fun dismissResume() {
        resumedFrom = 0L
    }

    // ------------------------------------------------------------ orientation

    val isLandscape get() = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    /** Locks whatever orientation the screen is in now, or hands control back to the phone. */
    fun toggleOrientationLock() {
        orientationLocked = !orientationLocked
        requestedOrientation = if (orientationLocked) ActivityInfo.SCREEN_ORIENTATION_LOCKED
        else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    /** Flips to the other orientation on demand and keeps it there until the lock is released. */
    fun rotate() {
        orientationLocked = true
        requestedOrientation = if (isLandscape) ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
        else ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
    }

    // ------------------------------------------------------------ picture-in-picture

    val canPip get() = pipAvailable

    private fun pipParams(): PictureInPictureParams {
        val playing = player?.isPlaying == true
        val b = PictureInPictureParams.Builder()
        if (videoSize.width > 0 && videoSize.height > 0) {
            // PiP refuses ratios outside 1:2.39 .. 2.39:1.
            val w = videoSize.width * videoSize.pixelWidthHeightRatio
            val r = (w / videoSize.height).coerceIn(1 / 2.39f, 2.39f)
            b.setAspectRatio(Rational((r * 1000).toInt(), 1000))
        }
        val icon = Icon.createWithResource(this, if (playing) R.drawable.ic_action_pause else R.drawable.ic_action_play)
        val pi = PendingIntent.getBroadcast(
            this, 0, Intent(ACTION_TOGGLE).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val label = if (playing) "Jeda" else "Putar"
        b.setActions(listOf(RemoteAction(icon, label, label, pi)))
        if (Build.VERSION.SDK_INT >= 31) b.setAutoEnterEnabled(playing).setSeamlessResizeEnabled(true)
        return b.build()
    }

    private fun updatePip() {
        if (pipAvailable) runCatching { setPictureInPictureParams(pipParams()) }
    }

    fun enterPip() {
        if (pipAvailable) runCatching { enterPictureInPictureMode(pipParams()) }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // API 31+ enters on its own through setAutoEnterEnabled.
        if (Build.VERSION.SDK_INT < 31 && player?.isPlaying == true) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
    }

    // ------------------------------------------------------------ other apps

    fun openExternally() {
        val u = uri ?: return
        player?.pause()
        Media.openWith(this, u, mime)
    }

    companion object {
        const val EXTRA_TITLE = "title"
        const val SEEK_MS = 10_000L
        private const val ACTION_TOGGLE = "io.github.arielfikru.rabbitrocket.PIP_TOGGLE"

        fun intent(ctx: Context, uri: Uri, mime: String?, title: String?): Intent =
            Intent(ctx, PlayerActivity::class.java)
                .setDataAndType(uri, mime ?: "video/*")
                .putExtra(EXTRA_TITLE, title)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

/** Last position per file, so a video picks up where it was left. */
object Resume {
    private const val PREFS = "player_resume"
    private const val MAX = 200

    /** Too close to either end to be worth resuming. */
    private const val EDGE_MS = 5_000L

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(ctx: Context, uri: Uri): Long = prefs(ctx).getString(uri.toString(), null)
        ?.substringBefore('|')?.toLongOrNull() ?: 0L

    fun put(ctx: Context, uri: Uri, pos: Long, duration: Long) {
        val nearEnd = duration != C.TIME_UNSET && duration > 0 && pos > duration - EDGE_MS
        if (pos < EDGE_MS || nearEnd) return clear(ctx, uri)
        val p = prefs(ctx)
        val e = p.edit().putString(uri.toString(), "$pos|${System.currentTimeMillis()}")
        // Drop the oldest entries so the file never grows without bound.
        val all = p.all
        if (all.size > MAX) {
            all.entries.sortedBy { (it.value as? String)?.substringAfter('|')?.toLongOrNull() ?: 0L }
                .take(all.size - MAX).forEach { e.remove(it.key) }
        }
        e.apply()
    }

    fun clear(ctx: Context, uri: Uri) = prefs(ctx).edit().remove(uri.toString()).apply()
}
