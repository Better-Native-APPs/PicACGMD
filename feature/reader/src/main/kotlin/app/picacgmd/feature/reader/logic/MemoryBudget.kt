package app.picacgmd.feature.reader.logic

import android.app.ActivityManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

internal const val LOW_RAM_TOTAL_MEMORY_BYTES: Long = 3L * 1024 * 1024 * 1024
private const val LOW_RAM_MAX_STRIP_PAGE_PIXELS = 6_000_000

internal data class ReaderPreloadWindow(val ahead: Int, val behind: Int)

/** Reader-only limits; normal devices retain the configured preload and decode sizes. */
internal data class ReaderMemoryBudget(val isLowRam: Boolean) {
    val preloadNextChapterImages: Boolean get() = !isLowRam
    val previewDecodeFactor: Int get() = if (isLowRam) 1 else 2
    val maxStripPagePixels: Int get() = if (isLowRam) LOW_RAM_MAX_STRIP_PAGE_PIXELS else MAX_STRIP_PAGE_PIXELS

    /** [ahead] is counted from the first page of the current slot, including its other page. */
    fun preloadWindow(ahead: Int, behind: Int, pagesPerSlot: Int = 1): ReaderPreloadWindow =
        ReaderPreloadWindow(
            ahead = if (isLowRam) minOf(ahead, pagesPerSlot + (pagesPerSlot - 1)) else ahead,
            behind = if (isLowRam) minOf(behind, 1) else behind,
        )
}

/** Pure classification, including devices that do not advertise Android's low-RAM flag. */
internal fun readerMemoryBudget(isLowRamDevice: Boolean, totalMemoryBytes: Long): ReaderMemoryBudget =
    ReaderMemoryBudget(isLowRamDevice || totalMemoryBytes < LOW_RAM_TOTAL_MEMORY_BYTES)

@Composable
internal fun rememberReaderMemoryBudget(): ReaderMemoryBudget {
    val context = LocalContext.current.applicationContext
    return remember(context) { ReaderMemoryBudgetCache.get(context) }
}

/** Query the device once per process, shared by reader modes, previews and preload requests. */
private object ReaderMemoryBudgetCache {
    private var budget: ReaderMemoryBudget? = null

    fun get(context: Context): ReaderMemoryBudget = synchronized(this) {
        budget ?: run {
            val manager = requireNotNull(context.getSystemService(ActivityManager::class.java))
            val info = ActivityManager.MemoryInfo()
            manager.getMemoryInfo(info)
            readerMemoryBudget(manager.isLowRamDevice, info.totalMem).also { budget = it }
        }
    }
}
