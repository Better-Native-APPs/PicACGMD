package app.picacgmd.core.domain.cache

import android.content.Context
import coil3.ImageLoader
import app.picacgmd.core.data.db.ApiCacheDao
import app.picacgmd.core.data.settings.CacheCleanupInterval
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A backward wall-clock correction makes the schedule due rather than postponing it indefinitely. */
internal fun isCacheCleanupDue(lastRunMillis: Long?, nowMillis: Long, intervalSeconds: Long): Boolean =
    intervalSeconds > 0 && (lastRunMillis == null || nowMillis < lastRunMillis || nowMillis - lastRunMillis >= intervalSeconds * 1_000)

/** Only image and catalogue caches are touched; downloads and user records are never traversed. */
class CacheMaintenance(context: Context, private val images: ImageLoader, private val apiCache: ApiCacheDao) {
    private val history = context.applicationContext.getSharedPreferences("cache_maintenance", Context.MODE_PRIVATE)
    private val mutex = Mutex()

    suspend fun cleanIfDue(interval: CacheCleanupInterval, nowMillis: Long = System.currentTimeMillis()) {
        mutex.withLock {
            val lastRun = if (history.contains(LAST_RUN)) history.getLong(LAST_RUN, 0) else null
            if (isCacheCleanupDue(lastRun, nowMillis, interval.seconds)) clear(images = true, api = true)
        }
    }

    suspend fun clear(images: Boolean, api: Boolean) = withContext(Dispatchers.IO) {
        if (images) {
            this@CacheMaintenance.images.memoryCache?.clear()
            this@CacheMaintenance.images.diskCache?.clear()
        }
        if (api) apiCache.clear()
        // A partial or failed cleanup must not suppress the next complete scheduled cleanup.
        if (images && api) history.edit().putLong(LAST_RUN, System.currentTimeMillis()).apply()
    }

    private companion object { const val LAST_RUN = "last_successful_cleanup_millis" }
}
