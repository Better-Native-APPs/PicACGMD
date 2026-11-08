package app.picacgmd.core.domain.download

/** The deadline follows the last flush, not the most recent arriving result. */
internal class DownloadProgressFlushPolicy(
    private val clockMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private var lastFlushAt = clockMillis()

    fun timeUntilFlushMillis(): Long = (1_000 - (clockMillis() - lastFlushAt)).coerceAtLeast(0)

    fun shouldFlush(batchSize: Int): Boolean =
        batchSize > 0 && (batchSize >= 16 || timeUntilFlushMillis() == 0L)

    fun onFlushed() { lastFlushAt = clockMillis() }
}
