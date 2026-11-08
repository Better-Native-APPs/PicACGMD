package app.picacgmd.feature.reader.logic

import app.picacgmd.feature.reader.ReaderConfig

/** 自动滚动速度 steps, in dp per second, from 很慢 (1) to 很快 (5). */
internal object AutoScrollSpeed {
    private val DP_PER_SECOND = floatArrayOf(24f, 45f, 75f, 115f, 170f)

    /** dp per second for a stored step; out-of-range steps are clamped. */
    fun dpPerSecond(step: Int): Float {
        val range = ReaderConfig.AUTO_SCROLL_SPEED_RANGE
        return DP_PER_SECOND[step.coerceIn(range) - range.first]
    }
}

/** Longest frame gap honoured; a longer one (a hitch, a resume) moves no more than this. */
internal const val AUTO_SCROLL_MAX_FRAME_NANOS: Long = 50_000_000L

/**
 * Pixels to scroll for one frame of [frameNanos] at [pixelsPerSecond], never further than
 * [remaining] (the distance left to the end of the chapter's last page). Returns 0 at the end.
 */
internal fun autoScrollDelta(pixelsPerSecond: Float, frameNanos: Long, remaining: Float): Float {
    if (remaining <= 0f || pixelsPerSecond <= 0f || frameNanos <= 0L) return 0f
    val nanos = frameNanos.coerceAtMost(AUTO_SCROLL_MAX_FRAME_NANOS)
    return (pixelsPerSecond * nanos / 1_000_000_000f).coerceAtMost(remaining)
}
