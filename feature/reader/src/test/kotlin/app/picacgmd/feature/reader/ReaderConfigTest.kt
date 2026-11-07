package app.picacgmd.feature.reader

import kotlin.test.Test
import kotlin.test.assertEquals

class ReaderConfigTest {

    @Test
    fun defaultsAreAlreadyNormalized() {
        assertEquals(ReaderConfig(), ReaderConfig().normalized())
    }

    @Test
    fun normalizedClampsEveryRange() {
        val config = ReaderConfig(
            scrollFraction = 3f,
            autoTurnSeconds = 1,
            minZoom = 0.1f,
            maxZoom = 9f,
            doubleTapZoom = 0f,
            gestureSpeed = 10f,
            preloadAhead = 40,
        ).normalized()
        assertEquals(1f, config.scrollFraction)
        assertEquals(2, config.autoTurnSeconds)
        assertEquals(0.5f, config.minZoom)
        assertEquals(5f, config.maxZoom)
        assertEquals(1.5f, config.doubleTapZoom)
        assertEquals(3f, config.gestureSpeed)
        assertEquals(5, config.preloadAhead)
    }

    @Test
    fun doubleTapZoomStaysInsideTheZoomLimits() {
        val config = ReaderConfig(minZoom = 2f, maxZoom = 2f, doubleTapZoom = 3f).normalized()
        assertEquals(2f, config.doubleTapZoom)
    }
}
