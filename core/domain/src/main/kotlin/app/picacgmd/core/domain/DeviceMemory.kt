package app.picacgmd.core.domain

import android.app.ActivityManager
import android.content.Context

private const val LOW_RAM_THRESHOLD_BYTES = 3L * 1024 * 1024 * 1024

/** Use total physical RAM, not currently available RAM, so defaults do not fluctuate with app use. */
fun isLowRamDevice(context: Context): Boolean {
    val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    if (activityManager.isLowRamDevice) return true
    val memory = ActivityManager.MemoryInfo()
    activityManager.getMemoryInfo(memory)
    return isLowRamDevice(systemLowRam = false, totalMemoryBytes = memory.totalMem)
}

internal fun isLowRamDevice(systemLowRam: Boolean, totalMemoryBytes: Long): Boolean =
    systemLowRam || totalMemoryBytes < LOW_RAM_THRESHOLD_BYTES
