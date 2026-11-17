package app.picacgmd.core.data.db

object HistoryMutationTime {
    // The absolute arithmetic guard for local successors; incoming sync events have a separate clock-relative bound.
    const val MAX_EPOCH_MILLIS = 253_402_300_799_999L

    fun isSupported(timestamp: Long): Boolean = timestamp in 0..MAX_EPOCH_MILLIS

    /** A new event must be strictly newer than every observed event, never a saturated tie. */
    fun next(requested: Long, vararg observed: Long?): Long {
        require(isSupported(requested)) { "Unsupported history mutation timestamp" }
        val latest = observed.filterNotNull().maxOrNull() ?: return requested
        require(isSupported(latest) && latest < MAX_EPOCH_MILLIS) { "History mutation timestamp cannot advance" }
        return maxOf(requested, latest + 1)
    }
}
