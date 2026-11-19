package app.picacgmd.reader

import app.picacgmd.feature.reader.ImageQuality

internal data class ReaderLoadingPolicy(val quality: ImageQuality, val preloadAhead: Int)

internal fun effectiveReaderLoading(
    dataSaverEnabled: Boolean,
    metered: Boolean,
    userQuality: ImageQuality,
    userPreloadAhead: Int,
): ReaderLoadingPolicy = ReaderLoadingPolicy(
    quality = if (dataSaverEnabled) ImageQuality.Low else userQuality,
    preloadAhead = if (dataSaverEnabled || metered) minOf(userPreloadAhead, 1) else userPreloadAhead,
)
