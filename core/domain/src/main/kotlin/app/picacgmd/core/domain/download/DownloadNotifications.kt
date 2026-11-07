package app.picacgmd.core.domain.download

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import app.picacgmd.core.domain.R
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

internal class DownloadNotifications(private val context: Context, private val smallIcon: Int) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    fun ongoing(progress: DownloadProgress): Notification {
        ensureChannel()
        val speed = String.format(Locale.SIMPLIFIED_CHINESE, "%.1f MB/s", progress.bytesPerSecond / 1_000_000.0)
        val title = context.getString(R.string.download_notification_running, progress.activeComics, speed)
        val text = if (progress.waitingForWifi) context.getString(R.string.download_notification_waiting_wifi) else {
            context.getString(R.string.download_notification_progress,
                progress.currentEpTitle ?: context.getString(R.string.download_notification_preparing), progress.remainingPictures)
        }
        return builder(title, text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(progress.totalPictures, progress.completedPictures, progress.totalPictures == 0)
            .addAction(Notification.Action.Builder(null, context.getString(R.string.download_notification_pause_all),
                controlIntent(DownloadActionReceiver.ACTION_PAUSE)).build())
            .apply { openIntent()?.let { addAction(Notification.Action.Builder(null,
                context.getString(R.string.download_notification_open), it).build()) } }
            .build()
    }

    fun update(progress: DownloadProgress) { notifyIfAllowed(FOREGROUND_ID, ongoing(progress)) }

    fun finished(progress: DownloadProgress) {
        if (progress.failedComics > 0 || progress.failedPictures > 0) {
            val title = if (progress.failedPictures > 0) {
                context.getString(R.string.download_notification_failed_pictures, progress.failedPictures)
            } else context.getString(R.string.download_notification_failed_comics, progress.failedComics)
            notifyIfAllowed(FINISHED_ID, builder(title, context.getString(R.string.download_notification_retry))
                .setAutoCancel(true)
                .addAction(Notification.Action.Builder(null, context.getString(R.string.download_notification_retry),
                    controlIntent(DownloadActionReceiver.ACTION_RETRY)).build()).build())
        } else if (progress.completedComics > 0) {
            notifyIfAllowed(FINISHED_ID, builder(
                context.getString(R.string.download_notification_completed, progress.completedComics),
                context.getString(R.string.download_notification_open),
            ).setAutoCancel(true).build())
        }
    }

    private fun builder(title: String, text: String) = Notification.Builder(context, CHANNEL_ID)
        .setSmallIcon(smallIcon)
        .setContentTitle(title)
        .setContentText(text)
        .setCategory(Notification.CATEGORY_PROGRESS)
        .setVisibility(Notification.VISIBILITY_PRIVATE)
        .apply { openIntent()?.let(::setContentIntent) }

    private fun ensureChannel() {
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID,
            context.getString(R.string.download_notification_channel), NotificationManager.IMPORTANCE_LOW))
    }

    private fun notifyIfAllowed(id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED) return
        if (!manager.areNotificationsEnabled()) return
        try {
            manager.notify(id, notification)
        } catch (_: SecurityException) {
            // Permission can be revoked between the check and notify; downloading still continues.
        }
    }

    private fun controlIntent(action: String): PendingIntent = PendingIntent.getBroadcast(context, action.hashCode(),
        Intent(context, DownloadActionReceiver::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun openIntent(): PendingIntent? = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
        PendingIntent.getActivity(context, 0, it.setAction(DownloadManager.ACTION_OPEN_DOWNLOADS)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    companion object {
        private const val CHANNEL_ID = "downloads"
        const val FOREGROUND_ID = 1801
        private const val FINISHED_ID = 1901
    }
}

/** Explicit, non-exported notification actions; notification permission is never requested here. */
class DownloadActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_PAUSE && intent.action != ACTION_RETRY) return
        val manager = (context.applicationContext as DownloadManagerOwner).downloadManager
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    ACTION_PAUSE -> manager.pauseAll()
                    ACTION_RETRY -> manager.retryFailed()
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        internal const val ACTION_PAUSE = "app.picacgmd.action.PAUSE_DOWNLOADS"
        internal const val ACTION_RETRY = "app.picacgmd.action.RETRY_DOWNLOADS"
    }
}
