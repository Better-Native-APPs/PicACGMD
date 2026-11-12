package app.picacgmd.core.domain.download

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DownloadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val manager = (applicationContext as DownloadManagerOwner).downloadManager
        if (!manager.hasWork()) return Result.success()
        if (manager.blockedByWifiRule()) {
            manager.start()
            return Result.success()
        }
        // Android 12+ refuses a foreground-service start from the background, except for a moment after
        // the user acts on a notification. A background run works as a plain job until an activity starts.
        val background = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !appIsVisible() && !manager.userActionRecent()
        if (!background) {
            try {
                enterForeground(manager)
            } catch (_: IllegalStateException) {
                // A failed foreground start must finish immediately to release WorkManager's lock.
                return Result.retry()
            }
        }
        return try {
            val completed = coroutineScope {
                val application = applicationContext as Application
                val activityStarted = CompletableDeferred<Unit>()
                val callbacks = if (background) object : Application.ActivityLifecycleCallbacks {
                    override fun onActivityStarted(activity: Activity) { activityStarted.complete(Unit) }
                    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
                    override fun onActivityResumed(activity: Activity) = Unit
                    override fun onActivityPaused(activity: Activity) = Unit
                    override fun onActivityStopped(activity: Activity) = Unit
                    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
                    override fun onActivityDestroyed(activity: Activity) = Unit
                } else null
                if (callbacks != null) application.registerActivityLifecycleCallbacks(callbacks)
                val promotion = if (background) launch {
                    // Recheck after registration so an activity start cannot be missed.
                    if (!appIsVisible()) activityStarted.await()
                    try {
                        enterForeground(manager)
                    } catch (exception: IllegalStateException) {
                        throw ForegroundPromotionFailed(exception)
                    }
                } else null
                // A background run shows the same progress notification, just without a service behind it.
                val notificationUpdates = launch {
                    // A slow collector of a state flow is handed the latest value next, so the final
                    // change of a burst still reaches the notification; a time filter dropped it.
                    manager.progress.collect { progress ->
                        manager.notifications.update(progress)
                        delay(1_000)
                    }
                }
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        runWithWifiWaitWatch(manager.progress.map { it.waitingForWifi }, WIFI_WAIT_GRACE_MILLIS) {
                            manager.runUntilIdle()
                        }
                    } else {
                        manager.runUntilIdle()
                        true
                    }
                } finally {
                    withContext(NonCancellable) {
                        notificationUpdates.cancelAndJoin()
                        promotion?.cancelAndJoin()
                    }
                    if (callbacks != null) application.unregisterActivityLifecycleCallbacks(callbacks)
                    // The system removes a foreground service's notification with the service and ignores
                    // this call while one is attached. A plain one, or one posted again just as the
                    // service was taken down, has nothing else to remove it.
                    manager.notifications.cancelOngoing()
                }
            }
            if (!completed) {
                manager.start()
                return Result.success()
            }
            manager.notifications.finished(manager.progress.value)
            Result.success()
        } catch (_: ForegroundPromotionFailed) {
            currentCoroutineContext().ensureActive()
            Result.retry()
        } catch (_: IOException) {
            // Picture/API retries are handled inside the engine; this is a storage/queue restart.
            Result.retry()
        }
    }

    private suspend fun enterForeground(manager: DownloadManager) {
        setForeground(ForegroundInfo(
            DownloadNotifications.FOREGROUND_ID,
            manager.notifications.ongoing(manager.progress.value),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        ))
    }
}

private fun appIsVisible(): Boolean {
    val process = ActivityManager.RunningAppProcessInfo()
    ActivityManager.getMyMemoryState(process)
    // Only the top state means an activity is on screen; the foreground-service and visible states do not.
    return process.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
}

private class ForegroundPromotionFailed(cause: IllegalStateException) : RuntimeException(cause)
