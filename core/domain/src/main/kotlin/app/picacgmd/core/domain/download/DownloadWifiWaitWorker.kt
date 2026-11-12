package app.picacgmd.core.domain.download

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class DownloadWifiWaitWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val manager = (applicationContext as DownloadManagerOwner).downloadManager
        if (!manager.hasWork()) return Result.success()
        // A matching Wi-Fi network can exist before it becomes the default network.
        if (manager.blockedByWifiRule()) return Result.retry()
        manager.start()
        return Result.success()
    }
}
