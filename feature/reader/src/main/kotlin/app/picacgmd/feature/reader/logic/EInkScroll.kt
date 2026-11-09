package app.picacgmd.feature.reader.logic

import kotlin.math.abs

/** Signed reading steps: -1 previous, 0 below threshold, +1 next. */
internal fun eInkSwipeStep(distancePixels: Float, pixelsPerInch: Float, triggerCm: Float, forwardSign: Int = -1): Int {
    val thresholdPixels = triggerCm.coerceIn(0.1f, 2f) * pixelsPerInch / 2.54f
    if (abs(distancePixels) < thresholdPixels) return 0
    return if (distancePixels * forwardSign > 0f) 1 else -1
}

/** Screen fraction in content coordinates; zoom must not change the visible step distance. */
internal fun eInkStepDistance(viewportPixels: Int, fraction: Float, scale: Float = 1f): Float =
    stripStepDistance(viewportPixels, fraction, scale)
