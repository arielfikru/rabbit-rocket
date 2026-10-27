package io.github.arielfikru.rabbitrocket

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Runs queued jobs one at a time. All queue bookkeeping happens on the main thread,
 * so onStartCommand and the worker loop never race.
 */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val queue = ArrayDeque<Long>()
    private var worker: Job? = null
    private var currentId: Long? = null
    private var currentJob: Job? = null
    private lateinit var engine: DownloadEngine

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        engine = DownloadEngine(applicationContext)
        Notifs.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getLongExtra(EXTRA_ID, -1L) ?: -1L
        when (intent?.action) {
            ACTION_START -> {
                ServiceCompat.startForeground(
                    this, Notifs.PROGRESS_ID, Notifs.progress(this, currentId?.let(DownloadStore::get), queue.size),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
                if (id > 0 && id != currentId && id !in queue) queue.addLast(id)
                pump()
            }
            ACTION_CANCEL -> cancel(id)
        }
        if (worker?.isActive != true) stopSelf()
        return START_NOT_STICKY
    }

    private fun pump() {
        if (worker?.isActive == true) return
        worker = scope.launch {
            val ticker = launch {
                while (isActive) {
                    refreshNotification()
                    delay(1000)
                }
            }
            while (queue.isNotEmpty()) {
                val id = queue.removeFirst()
                currentId = id
                Notifs.cancelResult(this@DownloadService, id)
                currentJob = launch { engine.run(id) }
                currentJob?.join()
                currentId = null
                DownloadStore.get(id)?.let { Notifs.result(this@DownloadService, it) }
            }
            ticker.cancel()
            ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun cancel(id: Long) {
        if (id == currentId) {
            currentJob?.cancel()
        } else {
            queue.remove(id)
            DownloadStore.update(id) { if (it.status == Status.Queued) it.copy(status = Status.Canceled) else it }
        }
    }

    @Suppress("MissingPermission")
    private fun refreshNotification() {
        val job = currentId?.let(DownloadStore::get)
        NotificationManagerCompat.from(this).notify(Notifs.PROGRESS_ID, Notifs.progress(this, job, queue.size))
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val ACTION_START = "start"
        private const val ACTION_CANCEL = "cancel"
        private const val EXTRA_ID = "id"

        fun start(ctx: Context, id: Long) {
            val i = Intent(ctx, DownloadService::class.java).setAction(ACTION_START).putExtra(EXTRA_ID, id)
            ContextCompat.startForegroundService(ctx, i)
        }

        fun cancelIntent(ctx: Context, id: Long): Intent =
            Intent(ctx, DownloadService::class.java).setAction(ACTION_CANCEL).putExtra(EXTRA_ID, id)
    }
}
