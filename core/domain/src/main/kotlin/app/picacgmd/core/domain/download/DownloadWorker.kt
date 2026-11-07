package app.picacgmd.core.domain.download

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import java.io.IOException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class DownloadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val manager = (applicationContext as DownloadManagerOwner).downloadManager
        if (!manager.hasWork()) return Result.success()
        try {
            setForeground(ForegroundInfo(
                DownloadNotifications.FOREGROUND_ID,
                manager.notifications.ongoing(manager.progress.value),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            ))
        } catch (_: IllegalStateException) {
            // Android can defer a dataSync foreground start from a background-only launch.
            return Result.retry()
        }
        return try {
            coroutineScope {
                val notificationUpdates = launch {
                    var lastUpdateAt = 0L
                    manager.progress.collect { progress ->
                        val now = System.nanoTime() / 1_000_000
                        if (now - lastUpdateAt >= 1_000) {
                            manager.notifications.update(progress)
                            lastUpdateAt = now
                        }
                    }
                }
                try {
                    manager.runUntilIdle()
                } finally {
                    notificationUpdates.cancel()
                }
            }
            manager.notifications.finished(manager.progress.value)
            Result.success()
        } catch (_: IOException) {
            // Picture/API retries are handled inside the engine; this is a storage/queue restart.
            Result.retry()
        }
    }
}
