package io.github.arielfikru.rabbitrocket

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat

object Notifs {
    private const val CH_PROGRESS = "progress"
    private const val CH_RESULT = "result"
    const val PROGRESS_ID = 7

    private const val FLAME = 0xFFFF7A3D.toInt()
    private const val PINK = 0xFFFFB3C7.toInt()
    private const val VIOLET = 0xFF5B4BDB.toInt()

    fun ensureChannels(ctx: Context) {
        NotificationManagerCompat.from(ctx).createNotificationChannelsCompat(
            listOf(
                NotificationChannelCompat.Builder(CH_PROGRESS, NotificationManagerCompat.IMPORTANCE_LOW)
                    .setName("Progress download")
                    .setDescription("Progress roket yang sedang meluncur")
                    .build(),
                NotificationChannelCompat.Builder(CH_RESULT, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                    .setName("Download selesai / gagal")
                    .build(),
            ),
        )
    }

    fun progress(ctx: Context, job: DlJob?, queued: Int): Notification {
        val b = NotificationCompat.Builder(ctx, CH_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_rocket)
            .setColor(FLAME)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp(ctx, 0))

        if (job == null) {
            return b.setContentTitle("Menyiapkan roket…").setProgress(0, 0, true).build()
        }

        val pct = (job.overall * 100).toInt()
        val max = 1000
        val value = (job.overall * max).toInt()
        b.setContentTitle(job.name)
            .setContentText(statusLine(job))
            .setSubText(if (queued > 0) "$pct% · +$queued antri" else "$pct%")
            .setShortCriticalText(if (job.status == Status.Downloading) "$pct%" else job.status.label.lowercase())
            .setRequestPromotedOngoing(true)
            .addAction(R.drawable.ic_action_close, "Batal", cancelIntent(ctx, job.id))
            .setProgress(max, value, job.isIndeterminate)
            .setStyle(
                NotificationCompat.ProgressStyle()
                    .setProgressTrackerIcon(IconCompat.createWithResource(ctx, R.drawable.ic_tracker_rocket))
                    .setProgressSegments(
                        listOf(
                            NotificationCompat.ProgressStyle.Segment(850).setColor(FLAME),
                            NotificationCompat.ProgressStyle.Segment(150).setColor(PINK),
                        ),
                    )
                    .setProgress(value)
                    .setProgressIndeterminate(job.isIndeterminate)
                    .setStyledByProgress(true),
            )
        return b.build()
    }

    fun result(ctx: Context, job: DlJob) {
        if (!canPost(ctx)) return
        val nm = NotificationManagerCompat.from(ctx)
        val id = resultId(job.id)
        val b = NotificationCompat.Builder(ctx, CH_RESULT)
            .setSmallIcon(R.drawable.ic_stat_rocket)
            .setAutoCancel(true)
        when (job.status) {
            Status.Done -> {
                val uri = Uri.parse(job.outputUri)
                b.setColor(FLAME)
                    .setContentTitle("Mendarat! ${job.fileName}")
                    .setContentText("${fmtBytes(job.size)} · ${Storage.displayPath}")
                    .setContentIntent(viewIntent(ctx, job.id, uri, job.outputMime))
                    .addAction(R.drawable.ic_action_play, "Buka", viewIntent(ctx, job.id, uri, job.outputMime))
                    .addAction(R.drawable.ic_action_share, "Share", shareIntent(ctx, job.id, uri, job.outputMime))
            }
            Status.Failed -> {
                b.setColor(VIOLET)
                    .setContentTitle("Gagal meluncur: ${job.name}")
                    .setContentText(job.error)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(job.error))
                    .setContentIntent(openApp(ctx, 0))
                    .addAction(R.drawable.ic_action_play, "Ulangi", openApp(ctx, job.id))
            }
            else -> {
                nm.cancel(id)
                return
            }
        }
        @Suppress("MissingPermission")
        nm.notify(id, b.build())
    }

    fun cancelResult(ctx: Context, jobId: Long) = NotificationManagerCompat.from(ctx).cancel(resultId(jobId))

    private fun resultId(jobId: Long) = 1000 + (jobId % 100_000).toInt()

    private fun canPost(ctx: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

    private const val FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    /** retryId > 0 makes MainActivity re-queue that job. */
    private fun openApp(ctx: Context, retryId: Long): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (retryId > 0) i.putExtra(MainActivity.EXTRA_RETRY, retryId)
        return PendingIntent.getActivity(ctx, (retryId % 100_000).toInt(), i, FLAGS)
    }

    private fun cancelIntent(ctx: Context, id: Long): PendingIntent =
        PendingIntent.getService(ctx, (id % 100_000).toInt(), DownloadService.cancelIntent(ctx, id), FLAGS)

    private fun viewIntent(ctx: Context, id: Long, uri: Uri, mime: String?): PendingIntent =
        PendingIntent.getActivity(ctx, 200_000 + (id % 100_000).toInt(), Media.viewIntent(uri, mime), FLAGS)

    private fun shareIntent(ctx: Context, id: Long, uri: Uri, mime: String?): PendingIntent =
        PendingIntent.getActivity(ctx, 300_000 + (id % 100_000).toInt(), Media.shareIntent(uri, mime), FLAGS)
}

object Media {
    fun viewIntent(uri: Uri, mime: String?): Intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, mime ?: "video/*")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)

    fun shareIntent(uri: Uri, mime: String?): Intent = Intent.createChooser(
        Intent(Intent.ACTION_SEND)
            .setType(mime ?: "video/*")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        null,
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
